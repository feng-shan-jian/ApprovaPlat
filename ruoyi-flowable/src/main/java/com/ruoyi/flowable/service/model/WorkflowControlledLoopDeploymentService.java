package com.ruoyi.flowable.service.model;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExclusiveGateway;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.GraphicInfo;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.UserTask;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployControlledLoop;
import com.ruoyi.flowable.mapper.WfDeployControlledLoopMapper;
import com.ruoyi.flowable.service.model.WorkflowControlledLoopBpmnContract.AuthorConfig;

/**
 * 把作者 BPMN 的受控整改循环编译为 Flowable 8 可执行网关回路并持久化不可变配置快照。
 */
@Service
public class WorkflowControlledLoopDeploymentService
{
    private final WfDeployControlledLoopMapper loopMapper;

    /**
     * 创建受控循环部署编译服务。
     * @param loopMapper WfDeployControlledLoopMapper，循环部署快照数据访问层
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowControlledLoopDeploymentService(WfDeployControlledLoopMapper loopMapper)
    {
        this.loopMapper = loopMapper;
    }

    /**
     * 校验作者配置与节点表单字段，并把单出口用户任务转换为固定 ExclusiveGateway 回路。
     *
     * @param authorDocument WorkflowBpmnDocument，已通过作者安全门禁的 BPMN 文档
     * @param inputBpmn byte[]，前序扩展编译生成的执行 BPMN
     * @param formSchemas List&lt;WorkflowControlledLoopFormSchema&gt;，节点表单变量白名单
     * @param actorUserId String，当前部署操作人正式用户主键
     * @return WorkflowPreparedControlledLoopDeployment，可继续交给 DMN 和 CallActivity 编译的结果
     */
    public WorkflowPreparedControlledLoopDeployment prepare(WorkflowBpmnDocument authorDocument,
            byte[] inputBpmn, List<WorkflowControlledLoopFormSchema> formSchemas,
            String actorUserId)
    {
        if (authorDocument == null || inputBpmn == null || inputBpmn.length == 0)
        {
            throw new ServiceException("受控循环部署输入不完整", HttpStatus.ERROR);
        }
        Map<NodeKey, Map<String, WorkflowControlledLoopFormField>> fieldsByNode =
                buildFormIndex(formSchemas);
        List<AuthorConfig> configs = collectConfigs(authorDocument.bpmnModel(), fieldsByNode);
        if (configs.isEmpty())
        {
            return new WorkflowPreparedControlledLoopDeployment(inputBpmn, List.of());
        }

        BpmnXMLConverter converter = new BpmnXMLConverter();
        BpmnModel compiledModel = converter.convertToBpmnModel(
                () -> new ByteArrayInputStream(inputBpmn), true, true);
        List<WfDeployControlledLoop> snapshots = new ArrayList<>(configs.size());
        for (AuthorConfig config : configs)
        {
            compileLoop(compiledModel, config);
            snapshots.add(toSnapshot(config, actorUserId));
        }
        byte[] compiled = converter.convertToXML(compiledModel);
        if (compiled == null || compiled.length == 0)
        {
            throw new ServiceException("受控循环执行资源编译失败", HttpStatus.ERROR);
        }
        return new WorkflowPreparedControlledLoopDeployment(compiled, snapshots);
    }

    /**
     * 在 Flowable 部署成功后批量写入受控循环不可变快照。
     * @param deploymentId String，刚创建的 Flowable 部署主键
     * @param prepared WorkflowPreparedControlledLoopDeployment，部署前编译结果
     * @return void，写入行数不一致时抛出冲突并回滚整个部署事务
     */
    public void persist(String deploymentId, WorkflowPreparedControlledLoopDeployment prepared)
    {
        List<WfDeployControlledLoop> snapshots = prepared == null
                ? List.of() : prepared.snapshots();
        for (WfDeployControlledLoop snapshot : snapshots)
        {
            snapshot.setDeployId(deploymentId);
        }
        int inserted = snapshots.isEmpty() ? 0 : loopMapper.insertBatch(snapshots);
        if (inserted != snapshots.size())
        {
            throw new ServiceException("受控循环部署快照保存不完整", HttpStatus.CONFLICT);
        }
    }

    /**
     * 从正式表单快照来源建立流程和节点联合字段索引。
     * @param formSchemas List&lt;WorkflowControlledLoopFormSchema&gt;，允许为空的字段白名单集合
     * @return Map&lt;NodeKey,Set&lt;String&gt;&gt;，节点到字段集合的不可重复索引
     */
    private Map<NodeKey, Map<String, WorkflowControlledLoopFormField>> buildFormIndex(
            List<WorkflowControlledLoopFormSchema> formSchemas)
    {
        Map<NodeKey, Map<String, WorkflowControlledLoopFormField>> result = new HashMap<>();
        for (WorkflowControlledLoopFormSchema schema :
                formSchemas == null ? List.<WorkflowControlledLoopFormSchema>of() : formSchemas)
        {
            NodeKey key = new NodeKey(schema.processKey(), schema.activityId());
            if (result.put(key, schema.fields()) != null)
            {
                throw new ServiceException("受控循环节点表单关系不唯一", HttpStatus.ERROR);
            }
        }
        return Map.copyOf(result);
    }

    /**
     * 收集所有可执行流程顶层用户任务的受控配置并执行表单字段语义校验。
     * @param model BpmnModel，作者 BPMN 公共模型
     * @param variablesByNode Map&lt;NodeKey,Set&lt;String&gt;&gt;，节点表单字段白名单
     * @return List&lt;AuthorConfig&gt;，按流程和节点顺序返回的不可变配置列表
     */
    private List<AuthorConfig> collectConfigs(BpmnModel model,
            Map<NodeKey, Map<String, WorkflowControlledLoopFormField>> fieldsByNode)
    {
        List<AuthorConfig> configs = new ArrayList<>();
        Set<String> routeVariables = new HashSet<>();
        for (Process process : model.getProcesses())
        {
            if (!process.isExecutable())
            {
                continue;
            }
            for (UserTask task : process.findFlowElementsOfType(UserTask.class, true))
            {
                AuthorConfig config;
                try
                {
                    config = WorkflowControlledLoopBpmnContract
                            .readAuthorConfig(process.getId(), task).orElse(null);
                }
                catch (IllegalArgumentException exception)
                {
                    throw invalid(exception.getMessage(), exception);
                }
                if (config == null)
                {
                    continue;
                }
                if (process.findParent(task) != process)
                {
                    throw invalid("受控循环暂不允许配置在子流程内部", null);
                }
                Map<String, WorkflowControlledLoopFormField> fields = fieldsByNode.get(
                        new NodeKey(config.processKey(), config.activityId()));
                WorkflowControlledLoopFormField field = fields == null ? null
                        : fields.get(config.decisionVariable());
                if (field == null)
                {
                    throw invalid("受控循环判断字段必须是当前节点正式表单的可写标量字段", null);
                }
                config = normalizeConditionValues(config, field);
                if (!routeVariables.add(config.routeVariable()))
                {
                    throw new ServiceException("受控循环运行变量发生冲突", HttpStatus.ERROR);
                }
                configs.add(config);
            }
        }
        return List.copyOf(configs);
    }

    /**
     * 按正式表单字段类型校验并规范化作者配置的进入和退出条件值。
     * @param config AuthorConfig，作者 BPMN 中已通过基础格式校验的循环配置
     * @param field WorkflowControlledLoopFormField，正式表单验证器提取的标量字段契约
     * @return AuthorConfig，条件值与运行时 canonicalScalar 语义一致的新配置
     */
    private AuthorConfig normalizeConditionValues(AuthorConfig config,
            WorkflowControlledLoopFormField field)
    {
        String repeatValue = normalizeConditionValue(config.repeatValue(), field);
        String exitValue = normalizeConditionValue(config.exitValue(), field);
        if (repeatValue.equals(exitValue))
        {
            throw invalid("受控循环进入和退出条件规范化后不能相同", null);
        }
        return new AuthorConfig(config.processKey(), config.activityId(), config.activityName(),
                config.decisionVariable(), repeatValue, exitValue, config.maxIterations(),
                config.routeVariable(), config.iterationVariable(), config.generatedIdBase());
    }

    /**
     * 把单个配置值转换为与任务完成判断一致的规范标量文本。
     * @param rawValue String，作者设计器保存的条件值
     * @param field WorkflowControlledLoopFormField，字段数据形态、范围和枚举约束
     * @return String，文本、布尔或十进制的稳定规范形式
     */
    private String normalizeConditionValue(String rawValue,
            WorkflowControlledLoopFormField field)
    {
        String value = rawValue == null ? "" : rawValue.trim();
        try
        {
            return switch (field.kind())
            {
                case TEXT ->
                {
                    if (value.length() < field.minLength() || value.length() > field.maxLength())
                    {
                        throw new IllegalArgumentException("length");
                    }
                    yield value;
                }
                case BOOLEAN ->
                {
                    if (!"true".equals(value) && !"false".equals(value))
                    {
                        throw new IllegalArgumentException("boolean");
                    }
                    yield value;
                }
                case NUMBER -> normalizeNumber(value, field);
                case SCALAR ->
                {
                    if (!field.enumValues().isEmpty() && !field.enumValues().contains(value))
                    {
                        throw new IllegalArgumentException("enum");
                    }
                    if (value.length() < field.minLength() || value.length() > field.maxLength())
                    {
                        throw new IllegalArgumentException("length");
                    }
                    yield value;
                }
            };
        }
        catch (IllegalArgumentException exception)
        {
            throw invalid("受控循环条件值不符合判断字段的数据类型或可选范围", exception);
        }
    }

    /**
     * 校验数值条件的有限十进制、整数类型及表单上下界并生成稳定文本。
     * @param value String，设计器保存的十进制文本
     * @param field WorkflowControlledLoopFormField，正式数值字段约束
     * @return String，与运行时 BigDecimal 规范化一致的十进制文本
     */
    private String normalizeNumber(String value, WorkflowControlledLoopFormField field)
    {
        BigDecimal decimal = new BigDecimal(value);
        BigDecimal normalized = decimal.stripTrailingZeros();
        if (field.numericKind() != WorkflowControlledLoopFormField.NumericKind.DECIMAL
                && normalized.scale() > 0)
        {
            throw new IllegalArgumentException("integer");
        }
        if ((field.numericKind() == WorkflowControlledLoopFormField.NumericKind.INTEGER
                && (decimal.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0
                || decimal.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0))
                || (field.numericKind() == WorkflowControlledLoopFormField.NumericKind.LONG
                && (decimal.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0
                || decimal.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0))
                || (field.minimum() != null && decimal.compareTo(field.minimum()) < 0)
                || (field.maximum() != null && decimal.compareTo(field.maximum()) > 0))
        {
            throw new IllegalArgumentException("range");
        }
        return normalized.toPlainString();
    }

    /**
     * 在编译模型中把单出口用户任务改写为“任务到网关、条件回到任务、默认退出”的固定回路。
     * @param model BpmnModel，前序编译阶段生成的执行模型
     * @param config AuthorConfig，已完成作者和表单语义校验的循环配置
     * @return void，无返回值；任何非白名单拓扑都会拒绝部署
     */
    private void compileLoop(BpmnModel model, AuthorConfig config)
    {
        Process process = model.getProcessById(config.processKey());
        FlowElement element = process == null ? null : process.getFlowElement(config.activityId(), true);
        if (!(element instanceof UserTask task) || process.findParent(task) != process)
        {
            throw new ServiceException("受控循环编译节点不存在", HttpStatus.ERROR);
        }
        requireSafeTopology(task);
        SequenceFlow exitFlow = task.getOutgoingFlows().get(0);
        FlowElement exitTarget = exitFlow.getTargetFlowElement();
        if (!(exitTarget instanceof FlowNode target))
        {
            throw invalid("受控循环出口必须连接可执行流程节点", null);
        }

        String gatewayId = config.generatedIdBase() + "_gateway";
        String bridgeId = config.generatedIdBase() + "_to_gateway";
        String repeatId = config.generatedIdBase() + "_repeat";
        if (process.containsFlowElementId(gatewayId)
                || process.containsFlowElementId(bridgeId)
                || process.containsFlowElementId(repeatId))
        {
            throw invalid("受控循环生成节点标识发生冲突", null);
        }

        ExclusiveGateway gateway = new ExclusiveGateway();
        gateway.setId(gatewayId);
        gateway.setName("受控循环判断");

        SequenceFlow bridge = new SequenceFlow(task.getId(), gatewayId);
        bridge.setId(bridgeId);
        bridge.setSourceFlowElement(task);
        bridge.setTargetFlowElement(gateway);

        SequenceFlow repeat = new SequenceFlow(gatewayId, task.getId());
        repeat.setId(repeatId);
        repeat.setName("再次进入");
        repeat.setConditionExpression("${" + config.routeVariable() + " == true}");
        repeat.setSourceFlowElement(gateway);
        repeat.setTargetFlowElement(task);

        // 原出口成为网关默认退出分支，业务建模的后继节点和顺序流主键保持不变。
        exitFlow.setSourceRef(gatewayId);
        exitFlow.setSourceFlowElement(gateway);
        gateway.setDefaultFlow(exitFlow.getId());
        gateway.setIncomingFlows(new ArrayList<>(List.of(bridge)));
        gateway.setOutgoingFlows(new ArrayList<>(List.of(repeat, exitFlow)));
        task.setOutgoingFlows(new ArrayList<>(List.of(bridge)));
        List<SequenceFlow> incoming = new ArrayList<>(task.getIncomingFlows());
        incoming.add(repeat);
        task.setIncomingFlows(incoming);

        process.addFlowElement(gateway);
        process.addFlowElement(bridge);
        process.addFlowElement(repeat);
        compileDiagramInterchange(model, task, gateway, bridge, repeat, exitFlow);
        WorkflowControlledLoopBpmnContract.removeAuthorProperties(task);
    }

    /**
     * 在作者模型包含 DI 时同步生成网关和回路线条，保证详情 Viewer 与执行拓扑一致。
     * @param model BpmnModel，正在编译的执行模型及其 DI 映射
     * @param task UserTask，循环用户任务
     * @param gateway ExclusiveGateway，编译生成的路由网关
     * @param bridge SequenceFlow，任务到网关的桥接顺序流
     * @param repeat SequenceFlow，网关回到任务的再次进入顺序流
     * @param exitFlow SequenceFlow，作者原始出口且已改由网关发出
     * @return void，无作者 DI 时不生成图形；存在不完整节点 DI 时拒绝部署
     */
    private void compileDiagramInterchange(BpmnModel model, UserTask task,
            ExclusiveGateway gateway, SequenceFlow bridge, SequenceFlow repeat,
            SequenceFlow exitFlow)
    {
        if (model.getLocationMap() == null || model.getLocationMap().isEmpty())
        {
            return;
        }
        GraphicInfo taskGraphic = model.getGraphicInfo(task.getId());
        if (taskGraphic == null)
        {
            throw invalid("受控循环节点的 BPMN DI 不完整", null);
        }

        List<GraphicInfo> originalExit = model.getFlowLocationGraphicInfo(exitFlow.getId());
        double taskRight = taskGraphic.getX() + taskGraphic.getWidth();
        double taskCenterY = taskGraphic.getY() + taskGraphic.getHeight() / 2.0;
        double gatewayCenterX = taskRight + 80.0;
        double gatewayCenterY = taskCenterY;
        if (originalExit != null && originalExit.size() >= 2)
        {
            GraphicInfo start = originalExit.get(0);
            GraphicInfo end = originalExit.get(originalExit.size() - 1);
            double distance = Math.hypot(end.getX() - start.getX(), end.getY() - start.getY());
            if (distance >= 100.0)
            {
                gatewayCenterX = (start.getX() + end.getX()) / 2.0;
                gatewayCenterY = (start.getY() + end.getY()) / 2.0;
            }
        }

        double gatewaySize = 50.0;
        model.addGraphicInfo(gateway.getId(), new GraphicInfo(
                gatewayCenterX - gatewaySize / 2.0,
                gatewayCenterY - gatewaySize / 2.0,
                gatewaySize, gatewaySize));
        model.addFlowGraphicInfoList(bridge.getId(), List.of(
                new GraphicInfo(taskRight, taskCenterY),
                new GraphicInfo(gatewayCenterX - gatewaySize / 2.0, gatewayCenterY)));

        // 回路线固定从网关下方绕过任务底部，避免与默认退出线和任务文本重叠。
        double loopY = Math.max(taskGraphic.getY() + taskGraphic.getHeight() + 60.0,
                gatewayCenterY + gatewaySize / 2.0 + 60.0);
        double taskCenterX = taskGraphic.getX() + taskGraphic.getWidth() / 2.0;
        model.addFlowGraphicInfoList(repeat.getId(), List.of(
                new GraphicInfo(gatewayCenterX, gatewayCenterY + gatewaySize / 2.0),
                new GraphicInfo(gatewayCenterX, loopY),
                new GraphicInfo(taskCenterX, loopY),
                new GraphicInfo(taskCenterX, taskGraphic.getY() + taskGraphic.getHeight())));

        List<GraphicInfo> compiledExit = new ArrayList<>();
        compiledExit.add(new GraphicInfo(gatewayCenterX + gatewaySize / 2.0, gatewayCenterY));
        if (originalExit != null && originalExit.size() > 1)
        {
            compiledExit.addAll(originalExit.subList(1, originalExit.size()));
        }
        else
        {
            GraphicInfo targetGraphic = model.getGraphicInfo(exitFlow.getTargetRef());
            if (targetGraphic == null)
            {
                throw invalid("受控循环出口的 BPMN DI 不完整", null);
            }
            compiledExit.add(new GraphicInfo(targetGraphic.getX(),
                    targetGraphic.getY() + targetGraphic.getHeight() / 2.0));
        }
        model.removeFlowGraphicInfoList(exitFlow.getId());
        model.addFlowGraphicInfoList(exitFlow.getId(), compiledExit);
    }

    /**
     * 校验受控循环只使用可以证明并发、回滚和单一退出语义的顶层普通用户任务。
     * @param task UserTask，待编译的用户任务
     * @return void，无返回值；异步、补偿、多实例、边界事件或复杂出口均拒绝
     */
    private void requireSafeTopology(UserTask task)
    {
        boolean hasBoundaryEvents = task.getBoundaryEvents() != null
                && !task.getBoundaryEvents().isEmpty();
        if (task.getLoopCharacteristics() != null || task.isAsynchronous()
                || task.isAsynchronousLeave() || task.isNotExclusive()
                || task.isForCompensation() || hasBoundaryEvents
                || StringUtils.hasText(task.getSkipExpression())
                || task.getOutgoingFlows() == null || task.getOutgoingFlows().size() != 1)
        {
            throw invalid("受控循环只支持无异步、补偿、边界事件或多实例的单出口用户任务", null);
        }
        SequenceFlow exitFlow = task.getOutgoingFlows().get(0);
        if (!StringUtils.hasText(exitFlow.getId())
                || StringUtils.hasText(exitFlow.getConditionExpression())
                || StringUtils.hasText(exitFlow.getSkipExpression())
                || task.getId().equals(exitFlow.getTargetRef()))
        {
            throw invalid("受控循环出口必须是唯一且无条件的后继顺序流", null);
        }
    }

    /**
     * 把作者配置转换为尚未绑定部署主键的持久化快照。
     * @param config AuthorConfig，已通过全部部署语义校验的作者配置
     * @param actorUserId String，部署操作人正式用户主键
     * @return WfDeployControlledLoop，待与 Flowable 部署同事务写入的快照
     */
    private WfDeployControlledLoop toSnapshot(AuthorConfig config, String actorUserId)
    {
        WfDeployControlledLoop snapshot = new WfDeployControlledLoop();
        snapshot.setProcessKey(config.processKey());
        snapshot.setActivityId(config.activityId());
        snapshot.setActivityName(config.activityName());
        snapshot.setDecisionVariable(config.decisionVariable());
        snapshot.setRepeatValue(config.repeatValue());
        snapshot.setExitValue(config.exitValue());
        snapshot.setMaxIterations(config.maxIterations());
        snapshot.setRouteVariable(config.routeVariable());
        snapshot.setIterationVariable(config.iterationVariable());
        snapshot.setCreateBy(actorUserId);
        return snapshot;
    }

    /**
     * 创建受控循环稳定的 BPMN 客户端错误。
     * @param message String，不包含内部类名或 SQL 的业务提示
     * @param cause Throwable，可为空的内部原因
     * @return ServiceException，HTTP 400 且带固定子码的业务异常
     */
    private ServiceException invalid(String message, Throwable cause)
    {
        ServiceException failure = new ServiceException(message, HttpStatus.BAD_REQUEST)
                .setSubCode("BPMN_CONTROLLED_LOOP_INVALID");
        if (cause != null)
        {
            failure.initCause(cause);
        }
        return failure;
    }

    /**
     * 节点表单字段索引键。
     * @param processKey String，可执行流程标识
     * @param activityId String，用户任务节点标识
     */
    private record NodeKey(String processKey, String activityId)
    {
    }
}

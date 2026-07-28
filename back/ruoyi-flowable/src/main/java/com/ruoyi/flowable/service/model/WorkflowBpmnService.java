package com.ruoyi.flowable.service.model;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.Activity;
import org.flowable.bpmn.model.BoundaryEvent;
import org.flowable.bpmn.model.CallActivity;
import org.flowable.bpmn.model.FieldExtension;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.FlowableListener;
import org.flowable.bpmn.model.ImplementationType;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.ScriptTask;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.validation.ValidationError;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceModelContract;

/**
 * BPMN XML 的有界 UTF-8 解码、安全 XML 解析和工作流业务规则校验组件。
 */
@Component
public class WorkflowBpmnService
{
    /** 单个 BPMN XML 允许的最大 UTF-8 字节数。 */
    static final int MAX_BPMN_BYTES = 2 * 1024 * 1024;

    /** 表单键必须严格使用 key_正Long。 */
    private static final Pattern FORM_KEY_PATTERN = Pattern.compile("key_([1-9][0-9]*)");

    /** 允许的 Java 类全限定名语法。 */
    private static final Pattern JAVA_CLASS_PATTERN = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");

    /** delegateExpression 只允许引用 workflow 前缀的受控 Spring Bean。 */
    private static final Pattern WORKFLOW_BEAN_PATTERN = Pattern.compile(
            "[$#]\\{workflow[A-Za-z0-9_]{1,127}}");

    /** 任务监听器只允许引用生产兼容入口，不接受任意 Spring Bean。 */
    private static final String USER_TASK_LISTENER_EXPRESSION = "${userTaskListener}";

    /** 生产兼容入口允许处理的任务生命周期事件。 */
    private static final Set<String> USER_TASK_LISTENER_EVENTS =
            Set.of("create", "assignment", "complete");

    /** 普通表达式仅允许变量、属性、索引、字面量和运算符，禁止方法调用。 */
    private static final Pattern SAFE_EXPRESSION_PATTERN = Pattern.compile(
            "[$#]\\{[A-Za-z0-9_.$#{}\\[\\]'\"\\s=!<>+\\-*/%?:,&|;]+}");

    /** 允许执行的业务委托类命名空间。 */
    private static final List<String> ALLOWED_CLASS_PREFIXES = List.of(
            "com.ruoyi.flowable.delegate.",
            "com.ruoyi.flowable.listener.");

    /** 即使表达式语法受限也不允许出现的敏感对象和反射入口。 */
    private static final List<String> DANGEROUS_EXPRESSION_TOKENS = List.of(
            "java.", "javax.", "jakarta.", "runtime", "processbuilder", "system.",
            "getclass", "classloader", "forname", "applicationcontext", "beanfactory",
            "scriptengine", "jndi", "reflection", ".class", "exec", "new ");

    private final RepositoryService repositoryService;

    /**
     * 创建 BPMN 安全校验组件。
     *
     * @param repositoryService RepositoryService，执行 Flowable 官方流程模型校验的公共 API
     * @return 无返回值，构造后由 Spring 管理该组件
     */
    public WorkflowBpmnService(RepositoryService repositoryService)
    {
        this.repositoryService = repositoryService;
    }

    /**
     * 有界解码并安全解析 BPMN，校验节点表单、脚本、实现类、表达式和 Flowable 规则。
     *
     * @param bpmnBytes byte[]，客户端或模型仓储提供的 BPMN UTF-8 原始字节
     * @return WorkflowBpmnDocument，通过全部校验的 BPMN 文档和表单引用
     */
    public WorkflowBpmnDocument validate(byte[] bpmnBytes)
    {
        return validateDocument(bpmnBytes, true);
    }

    /**
     * 校验尚在设计阶段的 BPMN 草稿；草稿可以暂未配置开始表单，其余安全和结构规则保持不变。
     *
     * @param bpmnBytes byte[]，模型创建时生成或编辑器读取的 BPMN UTF-8 原始字节
     * @return WorkflowBpmnDocument，通过草稿安全校验的 BPMN 文档和已有表单引用
     */
    public WorkflowBpmnDocument validateDraft(byte[] bpmnBytes)
    {
        return validateDocument(bpmnBytes, false);
    }

    /**
     * 执行统一的 BPMN 解码、解析、安全扫描和 Flowable 官方校验。
     *
     * @param bpmnBytes byte[]，待校验的 BPMN UTF-8 原始字节
     * @param requireStartForm boolean，true 表示保存或部署场景必须配置开始表单
     * @return WorkflowBpmnDocument，通过指定校验强度的 BPMN 文档和表单引用
     */
    private WorkflowBpmnDocument validateDocument(byte[] bpmnBytes, boolean requireStartForm)
    {
        if (bpmnBytes == null || bpmnBytes.length == 0)
        {
            throw invalidBpmn("BPMN XML 不能为空", null);
        }
        if (bpmnBytes.length > MAX_BPMN_BYTES)
        {
            throw invalidBpmn("BPMN XML 超过大小限制", null);
        }

        String bpmnXml = decodeUtf8(bpmnBytes);
        String normalizedXml = bpmnXml.toLowerCase(Locale.ROOT);
        if (normalizedXml.contains("<!doctype") || normalizedXml.contains("<!entity"))
        {
            // 即使底层 StAX 实现忽略相关属性，也不允许 DTD 或实体声明进入转换器。
            throw invalidBpmn("BPMN XML 不允许 DTD 或实体声明", null);
        }
        try
        {
            org.flowable.bpmn.model.BpmnModel bpmnModel = parseSecurely(bpmnXml);
            List<WorkflowBpmnFormReference> references = validateModel(bpmnModel, requireStartForm);
            validateRawExpressions(bpmnXml,
                    countControlledMultiInstanceCollections(bpmnModel));
            validateWithFlowable(bpmnModel);
            return new WorkflowBpmnDocument(bpmnModel, bpmnXml, references);
        }
        catch (ServiceException exception)
        {
            throw exception;
        }
        catch (RuntimeException exception)
        {
            // 解析器或转换器的原始消息可能包含 XML 正文和内部类名，对外只返回稳定提示。
            throw invalidBpmn("BPMN XML 解析失败", exception);
        }
    }

    /**
     * 使用 REPORT 策略严格解码 UTF-8，拒绝替换非法字节。
     *
     * @param bytes byte[]，BPMN 原始字节
     * @return String，严格 UTF-8 文本
     */
    private String decodeUtf8(byte[] bytes)
    {
        try
        {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        }
        catch (CharacterCodingException exception)
        {
            throw invalidBpmn("BPMN XML 必须使用有效 UTF-8 编码", exception);
        }
    }

    /**
     * 使用禁用 DTD、外部实体和实体替换的 StAX Reader 解析 BPMN。
     *
     * @param bpmnXml String，已经严格 UTF-8 解码的 BPMN XML
     * @return org.flowable.bpmn.model.BpmnModel，Flowable 公共 BPMN 模型
     */
    private org.flowable.bpmn.model.BpmnModel parseSecurely(String bpmnXml)
    {
        XMLStreamReader reader = null;
        try
        {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
            factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
            factory.setXMLResolver((publicId, systemId, baseUri, namespace) ->
            {
                throw new XMLStreamException("external resources disabled");
            });
            reader = factory.createXMLStreamReader(new java.io.StringReader(bpmnXml));
            return new BpmnXMLConverter().convertToBpmnModel(reader);
        }
        catch (XMLStreamException | IllegalArgumentException exception)
        {
            throw invalidBpmn("BPMN XML 解析失败", exception);
        }
        finally
        {
            if (reader != null)
            {
                try
                {
                    reader.close();
                }
                catch (XMLStreamException ignored)
                {
                    // Reader 关闭失败不覆盖已经产生的业务校验结果。
                }
            }
        }
    }

    /**
     * 校验可执行流程、开始节点、表单引用及所有递归流程元素的安全约束。
     *
     * @param bpmnModel org.flowable.bpmn.model.BpmnModel，安全 XML Reader 解析后的模型
     * @param requireStartForm boolean，是否要求每个流程的开始节点已经配置表单
     * @return List&lt;WorkflowBpmnFormReference&gt;，按流程和节点顺序提取的表单引用
     */
    private List<WorkflowBpmnFormReference> validateModel(
            org.flowable.bpmn.model.BpmnModel bpmnModel, boolean requireStartForm)
    {
        List<Process> processes = bpmnModel.getProcesses();
        if (processes == null || processes.isEmpty()
                || processes.stream().noneMatch(Process::isExecutable))
        {
            throw invalidBpmn("BPMN 至少需要一个可执行流程", null);
        }

        List<WorkflowBpmnFormReference> references = new ArrayList<>();
        Set<String> uniqueReferences = new HashSet<>();
        for (Process process : processes)
        {
            List<StartEvent> startEvents = process.findFlowElementsOfType(StartEvent.class, true);
            if (startEvents.size() != 1)
            {
                throw invalidBpmn("每个流程必须且只能包含一个开始节点", null);
            }
            StartEvent startEvent = startEvents.get(0);
            if (!hasText(startEvent.getFormKey()))
            {
                if (requireStartForm)
                {
                    throw invalidBpmn("开始节点必须配置流程表单", null);
                }
            }
            else
            {
                addFormReference(startEvent.getFormKey(), startEvent.getId(), startEvent.getName(),
                        references, uniqueReferences);
            }

            for (UserTask userTask : process.findFlowElementsOfType(UserTask.class, true))
            {
                if (hasText(userTask.getFormKey()))
                {
                    addFormReference(userTask.getFormKey(), userTask.getId(), userTask.getName(),
                            references, uniqueReferences);
                }
                validateTaskListeners(userTask.getTaskListeners());
            }
            if (!process.findFlowElementsOfType(ScriptTask.class, true).isEmpty())
            {
                throw invalidBpmn("流程不允许使用脚本任务", null);
            }

            validateListeners(process.getExecutionListeners());
            for (FlowElement element : process.findFlowElementsOfType(FlowElement.class, true))
            {
                validateListeners(element.getExecutionListeners());
                validateFlowElement(process, element);
            }
        }
        return List.copyOf(references);
    }

    /**
     * 校验单个递归流程元素中的实现和表达式。
     *
     * @param process Process，元素所属的可执行流程，用于核验动态多实例初始化拓扑
     * @param element FlowElement，待校验流程元素
     * @return 无返回值
     */
    private void validateFlowElement(Process process, FlowElement element)
    {
        if (element instanceof ServiceTask serviceTask)
        {
            validateServiceTask(serviceTask);
        }
        if (element instanceof SequenceFlow sequenceFlow)
        {
            validateExpression(sequenceFlow.getConditionExpression());
            validateExpression(sequenceFlow.getSkipExpression());
        }
        if (element instanceof UserTask userTask)
        {
            validateExpression(userTask.getAssignee());
            validateExpression(userTask.getOwner());
            validateExpression(userTask.getPriority());
            validateExpression(userTask.getDueDate());
            validateExpression(userTask.getCategory());
            validateExpression(userTask.getSkipExpression());
            validateExpressions(userTask.getCandidateUsers());
            validateExpressions(userTask.getCandidateGroups());
        }
        if (element instanceof CallActivity callActivity)
        {
            validateExpression(callActivity.getCalledElement());
            validateExpression(callActivity.getBusinessKey());
            validateExpression(callActivity.getProcessInstanceName());
        }
        if (element instanceof Activity activity)
        {
            MultiInstanceLoopCharacteristics loop = activity.getLoopCharacteristics();
            if (loop != null)
            {
                validateMultiInstance(process, activity, loop);
            }
        }
    }

    /**
     * 校验多实例集合来源；固定 handler 形态必须同时满足并行 UserTask、固定办理人和
     * ALL/ANY 完成条件，避免只替换一个表达式就绕过动态调整的快照与并发契约。
     *
     * @param process Process，多实例活动所属的可执行流程
     * @param activity Activity，持有多实例循环配置的流程活动
     * @param loop MultiInstanceLoopCharacteristics，待校验的多实例配置
     * @return 无返回值
     */
    private void validateMultiInstance(Process process, Activity activity,
            MultiInstanceLoopCharacteristics loop)
    {
        boolean usesControlledHandler =
                WorkflowMultiInstanceModelContract.usesControlledHandler(loop);

        if (!usesControlledHandler)
        {
            // 静态集合继续保持兼容，但仍走普通表达式白名单并拒绝可实例化的 collectionHandler。
            validateExpression(loop.getInputDataItem());
            validateExpression(loop.getCollectionString());
            validateExpression(loop.getLoopCardinality());
            validateExpression(loop.getCompletionCondition());
            if (loop.getHandler() != null)
            {
                throw invalidBpmn("多实例集合处理器未列入安全白名单", null);
            }
            return;
        }

        try
        {
            // 保存门禁与运行时调整服务复用同一结构契约，不能各自维护近似白名单。
            WorkflowMultiInstanceModelContract.requireMode(activity);
        }
        catch (IllegalArgumentException exception)
        {
            throw invalidBpmn("动态多实例配置不符合受控会签或或签契约", null);
        }
        validateControlledMultiInstanceTopology(process, (UserTask) activity);
    }

    /**
     * 核验受控动态多实例只能由一个同步普通用户任务通过唯一无条件顺序流初始化。
     *
     * <p>运行时只有前序普通用户任务的 {@code nextUserIds} 链路会在完成命令前写入
     * {@code wfMiUsers_<activityId>}。因此开始节点、网关、服务任务、多实例任务、异步任务、
     * 可跳过任务或分支流都不能作为初始化来源，否则模型虽能部署却会在真实进入节点时失败。</p>
     *
     * @param process Process，动态多实例所属的主流程
     * @param dynamicTask UserTask，已经通过固定 handler 结构契约的动态多实例任务
     * @return 无返回值；初始化来源或可重入路径不安全时抛出 HTTP 400
     */
    private void validateControlledMultiInstanceTopology(Process process, UserTask dynamicTask)
    {
        List<SequenceFlow> incomingFlows = dynamicTask.getIncomingFlows();
        if (incomingFlows == null || incomingFlows.size() != 1)
        {
            throw invalidBpmn("动态多实例初始化拓扑不合法：必须存在唯一普通用户任务前驱", null);
        }

        SequenceFlow initializerFlow = incomingFlows.get(0);
        FlowElement source = resolveSequenceEndpoint(process, initializerFlow, true);
        FlowElement target = resolveSequenceEndpoint(process, initializerFlow, false);
        if (!(source instanceof UserTask initializerTask)
                || target != dynamicTask
                || initializerTask.getParentContainer() != process
                || initializerTask.getLoopCharacteristics() != null
                || initializerTask.isForCompensation()
                || hasText(initializerTask.getSkipExpression())
                || hasAsyncContinuation(initializerTask)
                || (initializerTask.getBoundaryEvents() != null
                    && !initializerTask.getBoundaryEvents().isEmpty()))
        {
            throw invalidBpmn("动态多实例初始化拓扑不合法：前驱必须是同步普通用户任务", null);
        }

        // initializerIncomingFlows 表示进入“选择 nextUserIds”任务的全部令牌入口；唯一入边阻断并行分支创建多个来源任务。
        List<SequenceFlow> initializerIncomingFlows = initializerTask.getIncomingFlows();
        if (initializerIncomingFlows == null || initializerIncomingFlows.size() != 1)
        {
            throw invalidBpmn("动态多实例初始化拓扑不合法：前驱必须且只能存在一条入边", null);
        }

        List<SequenceFlow> initializerOutgoingFlows = initializerTask.getOutgoingFlows();
        if (initializerOutgoingFlows == null || initializerOutgoingFlows.size() != 1
                || !sameSequenceFlow(initializerFlow, initializerOutgoingFlows.get(0))
                || hasText(initializerFlow.getConditionExpression())
                || hasText(initializerFlow.getSkipExpression()))
        {
            throw invalidBpmn("动态多实例初始化拓扑不合法：前驱只能通过唯一无条件顺序流直连", null);
        }

        assertDynamicTaskCannotReenter(process, dynamicTask);
    }

    /**
     * 判断普通用户任务是否声明会延迟离开、并发离开或改变排他语义的异步配置。
     *
     * @param task UserTask，动态多实例初始化前驱任务
     * @return boolean，任一异步或非排他标识存在时返回 true
     */
    private boolean hasAsyncContinuation(UserTask task)
    {
        return task.isAsynchronous()
                || task.isAsynchronousLeave()
                || task.isNotExclusive()
                || task.isAsynchronousLeaveNotExclusive();
    }

    /**
     * 从动态多实例的所有后继路径做有界图遍历，拒绝任何能够再次到达同一活动 ID 的回路。
     *
     * <p>成员快照和 revision 当前按活动 ID 存在流程实例作用域，同一节点重复进入会把上一轮
     * 正式状态错误复用到下一轮。遍历同时把可达活动的边界事件作为潜在分支，避免计时器或
     * 错误边界路径绕过普通顺序流检查。</p>
     *
     * @param process Process，动态多实例所属的主流程
     * @param dynamicTask UserTask，禁止重复进入的动态多实例任务
     * @return 无返回值；任一可执行路径可回到该任务时抛出 HTTP 400
     */
    private void assertDynamicTaskCannotReenter(Process process, UserTask dynamicTask)
    {
        ArrayDeque<FlowNode> pending = new ArrayDeque<>();
        enqueueOutgoingTargets(process, dynamicTask, pending);
        Set<FlowNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (!pending.isEmpty())
        {
            FlowNode current = pending.removeFirst();
            if (current == dynamicTask || dynamicTask.getId().equals(current.getId()))
            {
                throw invalidBpmn("动态多实例节点不允许通过流程回路重复进入", null);
            }
            if (!visited.add(current))
            {
                continue;
            }

            enqueueOutgoingTargets(process, current, pending);
            if (current instanceof Activity activity && activity.getBoundaryEvents() != null)
            {
                for (BoundaryEvent boundaryEvent : activity.getBoundaryEvents())
                {
                    if (boundaryEvent == null)
                    {
                        throw invalidBpmn("动态多实例后继执行路径结构不合法", null);
                    }
                    pending.addLast(boundaryEvent);
                }
            }
        }
    }

    /**
     * 解析一个可达流程节点的全部顺序流目标并加入待遍历队列。
     *
     * @param process Process，顺序流端点必须所属的主流程
     * @param source FlowNode，当前可达流程节点
     * @param pending ArrayDeque&lt;FlowNode&gt;，后续待检查节点队列
     * @return 无返回值；损坏或跨作用域端点会阻止模型保存
     */
    private void enqueueOutgoingTargets(Process process, FlowNode source,
            ArrayDeque<FlowNode> pending)
    {
        List<SequenceFlow> outgoingFlows = source.getOutgoingFlows();
        if (outgoingFlows == null)
        {
            return;
        }
        for (SequenceFlow outgoingFlow : outgoingFlows)
        {
            FlowElement target = resolveSequenceEndpoint(process, outgoingFlow, false);
            if (!(target instanceof FlowNode targetNode))
            {
                throw invalidBpmn("动态多实例后继执行路径结构不合法", null);
            }
            pending.addLast(targetNode);
        }
    }

    /**
     * 从 Flowable 模型对象和引用字段解析顺序流端点，并核验端点仍属于同一主流程。
     *
     * @param process Process，端点必须所属的主流程
     * @param sequenceFlow SequenceFlow，待解析的顺序流
     * @param source boolean，true 解析来源端点，false 解析目标端点
     * @return FlowElement，引用完整且属于主流程的真实端点
     */
    private FlowElement resolveSequenceEndpoint(Process process, SequenceFlow sequenceFlow,
            boolean source)
    {
        if (sequenceFlow == null)
        {
            throw invalidBpmn("动态多实例执行路径结构不合法", null);
        }
        FlowElement endpoint = source ? sequenceFlow.getSourceFlowElement()
                : sequenceFlow.getTargetFlowElement();
        String endpointRef = source ? sequenceFlow.getSourceRef() : sequenceFlow.getTargetRef();
        if (endpoint == null && hasText(endpointRef))
        {
            endpoint = process.getFlowElement(endpointRef, true);
        }
        if (endpoint == null || endpoint.getParentContainer() != process
                || !hasText(endpoint.getId())
                || (hasText(endpointRef) && !endpointRef.equals(endpoint.getId())))
        {
            throw invalidBpmn("动态多实例执行路径端点不合法", null);
        }
        return endpoint;
    }

    /**
     * 比较解析器可能复用或重新构造的两个顺序流是否代表同一部署边。
     *
     * @param left SequenceFlow，动态多实例唯一入边
     * @param right SequenceFlow，前驱用户任务唯一出边
     * @return boolean，对象相同或非空 ID 完全一致时返回 true
     */
    private boolean sameSequenceFlow(SequenceFlow left, SequenceFlow right)
    {
        return left == right || (left != null && right != null && hasText(left.getId())
                && left.getId().equals(right.getId()));
    }

    /**
     * 校验服务任务只能使用受控 Java 类或 workflow 前缀 Bean，禁止内建外部执行类型。
     *
     * @param task ServiceTask，待校验服务任务
     * @return 无返回值
     */
    private void validateServiceTask(ServiceTask task)
    {
        if (hasText(task.getType()))
        {
            throw invalidBpmn("服务任务类型未列入安全白名单", null);
        }
        validateImplementation(task.getImplementationType(), task.getImplementation());
        validateExpression(task.getSkipExpression());
        validateFieldExtensions(task.getFieldExtensions());
    }

    /**
     * 校验执行监听器和任务监听器的实现及字段表达式。
     *
     * @param listeners List&lt;FlowableListener&gt;，监听器集合，允许为空
     * @return 无返回值
     */
    private void validateListeners(List<FlowableListener> listeners)
    {
        if (listeners == null)
        {
            return;
        }
        for (FlowableListener listener : listeners)
        {
            validateImplementation(listener.getImplementationType(), listener.getImplementation());
            validateFieldExtensions(listener.getFieldExtensions());
            if (listener.getScriptInfo() != null)
            {
                throw invalidBpmn("监听器不允许执行脚本", null);
            }
            if (hasText(listener.getCustomPropertiesResolverImplementation()))
            {
                validateImplementation(listener.getCustomPropertiesResolverImplementationType(),
                        listener.getCustomPropertiesResolverImplementation());
            }
        }
    }

    /**
     * 校验用户任务监听器只能使用固定兼容 Bean 和批准事件，并拒绝字段注入、脚本、
     * 事务回调及自定义属性解析器，保证模型无法借监听器执行任意代码。
     *
     * @param listeners List&lt;FlowableListener&gt;，用户任务监听器集合，必须完整包含三个固定事件
     * @return 无返回值；监听器缺失、重复或实现漂移时拒绝模型
     */
    private void validateTaskListeners(List<FlowableListener> listeners)
    {
        if (listeners == null || listeners.size() != USER_TASK_LISTENER_EVENTS.size())
        {
            throw invalidBpmn("用户任务必须配置固定身份审计任务监听器", null);
        }
        Set<String> seenEvents = new HashSet<>();
        for (FlowableListener listener : listeners)
        {
            String event = trimToEmpty(listener.getEvent());
            String implementation = trimToEmpty(listener.getImplementation());
            boolean hasFields = listener.getFieldExtensions() != null
                    && !listener.getFieldExtensions().isEmpty();
            boolean hasCustomResolver = hasText(listener.getCustomPropertiesResolverImplementationType())
                    || hasText(listener.getCustomPropertiesResolverImplementation());
            if (!USER_TASK_LISTENER_EVENTS.contains(event)
                    || !seenEvents.add(event)
                    || !ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION.equals(
                            listener.getImplementationType())
                    || !USER_TASK_LISTENER_EXPRESSION.equals(implementation)
                    || hasFields
                    || listener.getScriptInfo() != null
                    || hasCustomResolver
                    || hasText(listener.getOnTransaction())
                    || listener.getInstance() != null)
            {
                throw invalidBpmn("任务监听器未列入安全白名单", null);
            }
        }
        if (!seenEvents.equals(USER_TASK_LISTENER_EVENTS))
        {
            throw invalidBpmn("用户任务必须配置固定身份审计任务监听器", null);
        }
    }

    /**
     * 校验 class 或 delegateExpression 实现，只允许明确白名单。
     *
     * @param implementationType String，Flowable 实现类型
     * @param implementation String，实现类名或委托表达式
     * @return 无返回值
     */
    private void validateImplementation(String implementationType, String implementation)
    {
        if (!hasText(implementationType) || !hasText(implementation))
        {
            throw invalidBpmn("流程实现配置不完整", null);
        }
        if (ImplementationType.IMPLEMENTATION_TYPE_CLASS.equals(implementationType))
        {
            String className = implementation.trim();
            boolean allowedPrefix = ALLOWED_CLASS_PREFIXES.stream().anyMatch(className::startsWith);
            if (!JAVA_CLASS_PATTERN.matcher(className).matches() || !allowedPrefix)
            {
                throw invalidBpmn("流程实现类未列入安全白名单", null);
            }
            return;
        }
        if (ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION.equals(implementationType))
        {
            if (!WORKFLOW_BEAN_PATTERN.matcher(implementation.trim()).matches())
            {
                throw invalidBpmn("流程委托 Bean 未列入安全白名单", null);
            }
            return;
        }
        // expression、script、instance 等类型可调用任意运行时对象，本阶段统一禁止。
        throw invalidBpmn("流程实现类型未列入安全白名单", null);
    }

    /**
     * 校验实现对象中的字段表达式。
     *
     * @param fields List&lt;FieldExtension&gt;，服务任务或监听器字段集合，允许为空
     * @return 无返回值
     */
    private void validateFieldExtensions(List<FieldExtension> fields)
    {
        if (fields == null)
        {
            return;
        }
        for (FieldExtension field : fields)
        {
            validateExpression(field.getExpression());
        }
    }

    /**
     * 校验字符串集合中可能存在的表达式。
     *
     * @param values List&lt;String&gt;，用户或候选组表达式集合，允许为空
     * @return 无返回值
     */
    private void validateExpressions(List<String> values)
    {
        if (values == null)
        {
            return;
        }
        for (String value : values)
        {
            validateExpression(value);
        }
    }

    /**
     * 校验单个可选表达式，不含表达式标记的普通常量直接放行。
     *
     * @param value String，可能包含 ${...} 或 #{...} 的配置值
     * @return 无返回值
     */
    private void validateExpression(String value)
    {
        if (!hasText(value) || (!value.contains("${") && !value.contains("#{")))
        {
            return;
        }
        if (!SAFE_EXPRESSION_PATTERN.matcher(value.trim()).matches())
        {
            throw invalidBpmn("流程表达式包含不允许的语法", null);
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (DANGEROUS_EXPRESSION_TOKENS.stream().anyMatch(normalized::contains))
        {
            throw invalidBpmn("流程表达式包含危险访问", null);
        }
    }

    /**
     * 从原始 XML 扫描全部表达式，防止转换器未映射的扩展字段绕过模型级校验。
     *
     * @param bpmnXml String，严格 UTF-8 解码后的原始 BPMN XML
     * @param controlledCollectionCount int，模型级完整校验通过的受控集合表达式数量
     * @return 无返回值
     */
    private void validateRawExpressions(String bpmnXml, int controlledCollectionCount)
    {
        int remainingControlledCollections = controlledCollectionCount;
        int cursor = 0;
        while (cursor < bpmnXml.length() - 1)
        {
            int dollar = bpmnXml.indexOf("${", cursor);
            int hash = bpmnXml.indexOf("#{", cursor);
            int start;
            if (dollar < 0)
            {
                start = hash;
            }
            else if (hash < 0)
            {
                start = dollar;
            }
            else
            {
                start = Math.min(dollar, hash);
            }
            if (start < 0)
            {
                break;
            }
            int end = bpmnXml.indexOf('}', start + 2);
            if (end < 0 || end - start > 4096)
            {
                throw invalidBpmn("流程表达式格式不合法", null);
            }
            String expression = bpmnXml.substring(start, end + 1);
            if (expression.indexOf('{', 2) >= 0)
            {
                throw invalidBpmn("流程表达式不允许嵌套", null);
            }
            if (WorkflowMultiInstanceModelContract.COLLECTION_EXPRESSION.equals(expression))
            {
                if (remainingControlledCollections == 0)
                {
                    throw invalidBpmn("动态多实例处理器只能用于受控集合字段", null);
                }
                remainingControlledCollections--;
            }
            else
            {
                validateExpression(expression);
            }
            cursor = end + 1;
        }
        if (remainingControlledCollections != 0)
        {
            throw invalidBpmn("动态多实例集合表达式与模型不一致", null);
        }
    }

    /**
     * 统计已经通过完整结构契约的动态多实例集合，供原始 XML 表达式执行一一对应校验。
     *
     * @param bpmnModel org.flowable.bpmn.model.BpmnModel，已完成模型级业务校验的流程模型
     * @return int，受控 handler 合法集合位置的数量
     */
    private int countControlledMultiInstanceCollections(
            org.flowable.bpmn.model.BpmnModel bpmnModel)
    {
        int count = 0;
        for (org.flowable.bpmn.model.Process process : bpmnModel.getProcesses())
        {
            for (org.flowable.bpmn.model.Activity activity :
                    process.findFlowElementsOfType(
                            org.flowable.bpmn.model.Activity.class, true))
            {
                MultiInstanceLoopCharacteristics loop = activity.getLoopCharacteristics();
                if (loop != null && WorkflowMultiInstanceModelContract.COLLECTION_EXPRESSION
                        .equals(trimToEmpty(loop.getInputDataItem())))
                {
                    // validateModel 已对相同 activity 调用 requireMode；此处只统计，不重复放宽契约。
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 解析并添加严格格式的节点表单引用，同时拒绝重复快照主键。
     *
     * @param formKey String，BPMN 表单键
     * @param nodeKey String，BPMN 节点主键
     * @param nodeName String，BPMN 节点名称，允许为空
     * @param references List&lt;WorkflowBpmnFormReference&gt;，待写入引用集合
     * @param uniqueReferences Set&lt;String&gt;，部署快照业务唯一键集合
     * @return 无返回值
     */
    private void addFormReference(String formKey, String nodeKey, String nodeName,
            List<WorkflowBpmnFormReference> references, Set<String> uniqueReferences)
    {
        Matcher matcher = FORM_KEY_PATTERN.matcher(formKey.trim());
        if (!matcher.matches())
        {
            throw invalidBpmn("流程表单键必须使用 key_正整数格式", null);
        }
        if (!hasText(nodeKey))
        {
            throw invalidBpmn("表单节点主键不能为空", null);
        }
        long formId;
        try
        {
            formId = Long.parseLong(matcher.group(1));
        }
        catch (NumberFormatException exception)
        {
            throw invalidBpmn("流程表单主键超出有效范围", exception);
        }
        String uniqueKey = formKey.trim() + '\u0000' + nodeKey.trim();
        if (!uniqueReferences.add(uniqueKey))
        {
            throw invalidBpmn("流程包含重复的节点表单引用", null);
        }
        references.add(new WorkflowBpmnFormReference(
                formId, formKey.trim(), nodeKey.trim(), nodeName == null ? "" : nodeName));
    }

    /**
     * 调用 Flowable 官方验证器并拒绝全部非 warning 错误。
     *
     * @param bpmnModel org.flowable.bpmn.model.BpmnModel，已通过模块安全规则的模型
     * @return 无返回值
     */
    private void validateWithFlowable(org.flowable.bpmn.model.BpmnModel bpmnModel)
    {
        List<ValidationError> errors = repositoryService.validateProcess(bpmnModel);
        if (errors != null && errors.stream().anyMatch(error -> !error.isWarning()))
        {
            throw invalidBpmn("BPMN 流程规则校验失败", null);
        }
    }

    /**
     * 构造不泄露解析器、引擎或 XML 正文的稳定 BPMN 参数异常。
     *
     * @param message String，对外稳定业务提示
     * @param cause Throwable，内部解析或转换异常，允许为空
     * @return ServiceException，HTTP 400 BPMN 参数异常
     */
    private ServiceException invalidBpmn(String message, Throwable cause)
    {
        ServiceException exception = new ServiceException(message, HttpStatus.BAD_REQUEST);
        if (cause != null)
        {
            exception.initCause(cause);
        }
        return exception;
    }

    /**
     * 判断文本是否包含非空白字符。
     *
     * @param value String，待判断文本
     * @return boolean，true 表示文本非空白
     */
    private static boolean hasText(String value)
    {
        return value != null && !value.isBlank();
    }

    /**
     * 将可空文本规范化为去除首尾空白的字符串，供固定 BPMN 契约执行精确比较。
     *
     * @param value String，待规范化文本，允许为空
     * @return String，非空的去空白文本；空值返回空字符串
     */
    private static String trimToEmpty(String value)
    {
        return value == null ? "" : value.trim();
    }
}

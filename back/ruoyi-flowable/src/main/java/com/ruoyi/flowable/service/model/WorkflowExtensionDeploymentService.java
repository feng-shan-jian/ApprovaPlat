package com.ruoyi.flowable.service.model;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.FieldExtension;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElementsContainer;
import org.flowable.bpmn.model.FlowableListener;
import org.flowable.bpmn.model.ImplementationType;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SendTask;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.UserTask;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;
import com.ruoyi.flowable.domain.vo.WorkflowExtensionOptionView;
import com.ruoyi.flowable.extension.WorkflowExtensionBpmnContract;
import com.ruoyi.flowable.extension.WorkflowExtensionChecksum;
import com.ruoyi.flowable.extension.WorkflowExtensionJsonCanonicalizer;
import com.ruoyi.flowable.extension.WorkflowCelSandbox;
import com.ruoyi.flowable.extension.WorkflowJavaExtensionHandler;
import com.ruoyi.flowable.extension.WorkflowJavaExtensionHandlerRegistry;
import com.ruoyi.flowable.extension.WorkflowHttpConnector;
import com.ruoyi.flowable.extension.WorkflowCollaborationOutboxHandler;
import com.ruoyi.flowable.extension.WorkflowSqlConnector;
import com.ruoyi.flowable.mapper.WfDeployExtensionSnapshotMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 受控扩展的部署编译、版本冻结和快照持久化服务。
 */
@Service
public class WorkflowExtensionDeploymentService
{
    /** 单节点扩展配置的 UTF-8 上限。 */
    private static final int MAX_CONFIG_BYTES = 16 * 1024;
    /** 系统用户任务审计监听器只由模型服务维护，不生成业务快照。 */
    private static final String SYSTEM_TASK_LISTENER = "${userTaskListener}";

    private final WorkflowExtensionRegistryService registryService;
    private final WorkflowJavaExtensionHandlerRegistry handlerRegistry;
    private final WfDeployExtensionSnapshotMapper snapshotMapper;
    private final WorkflowHttpConnector httpConnector;
    private final WorkflowSqlConnector sqlConnector;
    private final ObjectMapper objectMapper = JsonMapper.shared();
    /** CEL 配置在部署和运行时复用同一纯代码沙箱。 */
    private final WorkflowCelSandbox celSandbox = new WorkflowCelSandbox();

    /**
     * 创建扩展部署服务。
     * @param registryService WorkflowExtensionRegistryService，扩展版本锁定服务
     * @param handlerRegistry WorkflowJavaExtensionHandlerRegistry，代码安装处理器注册表
     * @param snapshotMapper WfDeployExtensionSnapshotMapper，部署快照数据访问层
     * @param httpConnector WorkflowHttpConnector，HTTP 端点冻结与配置校验器
     * @param sqlConnector WorkflowSqlConnector，SQL 数据源冻结与模板校验器
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowExtensionDeploymentService(WorkflowExtensionRegistryService registryService,
            WorkflowJavaExtensionHandlerRegistry handlerRegistry,
            WfDeployExtensionSnapshotMapper snapshotMapper,
            WorkflowHttpConnector httpConnector,
            WorkflowSqlConnector sqlConnector)
    {
        this.registryService = registryService;
        this.handlerRegistry = handlerRegistry;
        this.snapshotMapper = snapshotMapper;
        this.httpConnector = httpConnector;
        this.sqlConnector = sqlConnector;
    }

    /**
     * 解析作者 BPMN 中的受控 ServiceTask 和 SendTask，冻结最新版并生成不含可变作者字段的执行资源。
     * @param document WorkflowBpmnDocument，已通过保存和部署安全门禁的作者文档
     * @param actorUserId String，当前部署操作人正式用户主键
     * @return WorkflowPreparedExtensionDeployment，编译资源和待持久化快照
     */
    public WorkflowPreparedExtensionDeployment prepare(WorkflowBpmnDocument document,
            String actorUserId)
    {
        BpmnXMLConverter converter = new BpmnXMLConverter();
        byte[] validatedModelBytes = converter.convertToXML(document.bpmnModel());
        BpmnModel compiledModel = converter.convertToBpmnModel(
                () -> new ByteArrayInputStream(validatedModelBytes), true, true);
        List<WfDeployExtensionSnapshot> snapshots = new ArrayList<>();
        Set<String> uniqueActivities = new HashSet<>();
        for (Process process : compiledModel.getProcesses())
        {
            if (!process.isExecutable())
            {
                continue;
            }
            compileExecutionListenerList(process, process.getId(), process.getExecutionListeners(),
                    actorUserId, snapshots);
            for (ServiceTask task : process.findFlowElementsOfType(ServiceTask.class, true))
            {
                requireUniqueActivity(process, task.getId(), uniqueActivities);
                snapshots.add(compileServiceTask(process, task, actorUserId));
            }
            // Flowable 的 SendTask 不支持受控委托实现，因此仅在执行副本中转换为 ServiceTask。
            for (SendTask task : process.findFlowElementsOfType(SendTask.class, true))
            {
                requireUniqueActivity(process, task.getId(), uniqueActivities);
                snapshots.add(compileSendTask(process, task, actorUserId));
            }
            // 业务监听器与 ServiceTask 共用注册表和不可变快照，但使用独立稳定标识避免唯一键冲突。
            for (org.flowable.bpmn.model.FlowElement element
                    : process.findFlowElementsOfType(org.flowable.bpmn.model.FlowElement.class, true))
            {
                compileBusinessListeners(process, element, actorUserId, snapshots);
            }
        }
        byte[] compiled = converter.convertToXML(compiledModel);
        if (compiled == null || compiled.length == 0)
        {
            throw new ServiceException("BPMN 执行资源编译失败", HttpStatus.ERROR);
        }
        return new WorkflowPreparedExtensionDeployment(compiled, snapshots);
    }

    /**
     * 编译一个活动上的受控执行监听器和用户任务业务监听器。
     * @param process Process，监听器所属可执行流程
     * @param element FlowElement，监听器所属 BPMN 活动元素
     * @param actorUserId String，部署操作人正式用户主键
     * @param snapshots List&lt;WfDeployExtensionSnapshot&gt;，本次部署待持久化快照集合
     * @return 无返回值；作者字段被剥离并替换为固定 Bean 入口
     */
    private void compileBusinessListeners(Process process,
            org.flowable.bpmn.model.FlowElement element, String actorUserId,
            List<WfDeployExtensionSnapshot> snapshots)
    {
        compileExecutionListeners(process, element, actorUserId, snapshots);
        if (element instanceof UserTask userTask)
        {
            List<FlowableListener> listeners = userTask.getTaskListeners();
            if (listeners == null)
            {
                return;
            }
            for (FlowableListener listener : listeners)
            {
                if (SYSTEM_TASK_LISTENER.equals(listener.getImplementation()))
                {
                    continue;
                }
                snapshots.add(compileBusinessListener(process, element.getId(), "TASK",
                        listener, actorUserId));
            }
        }
    }

    /**
     * 编译活动执行监听器列表，保留事件名并固定为业务监听 Bean。
     * @param process Process，监听器所属可执行流程
     * @param element FlowElement，监听器所属元素
     * @param actorUserId String，部署操作人正式用户主键
     * @param snapshots List&lt;WfDeployExtensionSnapshot&gt;，待持久化快照集合
     * @return 无返回值；空列表不产生快照
     */
    private void compileExecutionListeners(Process process,
            org.flowable.bpmn.model.FlowElement element, String actorUserId,
            List<WfDeployExtensionSnapshot> snapshots)
    {
        compileExecutionListenerList(process, element.getId(), element.getExecutionListeners(),
                actorUserId, snapshots);
    }

    /**
     * 编译流程或活动持有的执行监听器集合。
     * @param process Process，监听器所属可执行流程
     * @param elementId String，流程或活动的稳定 BPMN 标识
     * @param listeners List&lt;FlowableListener&gt;，待编译执行监听器
     * @param actorUserId String，部署操作人正式用户主键
     * @param snapshots List&lt;WfDeployExtensionSnapshot&gt;，待持久化快照集合
     * @return 无返回值；空列表不产生快照
     */
    private void compileExecutionListenerList(Process process, String elementId,
            List<FlowableListener> listeners, String actorUserId,
            List<WfDeployExtensionSnapshot> snapshots)
    {
        if (listeners == null)
        {
            return;
        }
        for (FlowableListener listener : listeners)
        {
            snapshots.add(compileBusinessListener(process, elementId, "EXECUTION",
                    listener, actorUserId));
        }
    }

    /**
     * 冻结单个监听器的 Java 注册表版本和规范配置，并剥离作者字段。
     * @param process Process，监听器所属可执行流程
     * @param elementId String，监听器所属 BPMN 元素标识
     * @param listenerKind String，EXECUTION 或 TASK
     * @param listener FlowableListener，作者监听器配置
     * @param actorUserId String，部署操作人正式用户主键
     * @return WfDeployExtensionSnapshot，尚未绑定部署 ID 的监听器冻结快照
     */
    private WfDeployExtensionSnapshot compileBusinessListener(Process process, String elementId,
            String listenerKind, FlowableListener listener, String actorUserId)
    {
        String extensionKey = null;
        String configJson = null;
        if (listener.getFieldExtensions() != null)
        {
            for (FieldExtension field : listener.getFieldExtensions())
            {
                if (WorkflowExtensionBpmnContract.EXTENSION_KEY_FIELD.equals(field.getFieldName()))
                {
                    extensionKey = requireUniqueField(extensionKey, field.getStringValue(), "监听器扩展标识");
                }
                else if (WorkflowExtensionBpmnContract.EXTENSION_CONFIG_FIELD.equals(field.getFieldName()))
                {
                    configJson = requireUniqueField(configJson, field.getStringValue(), "监听器扩展配置");
                }
                else
                {
                    throw new ServiceException("业务监听器包含未注册的字段注入", HttpStatus.BAD_REQUEST);
                }
                if (field.getExpression() != null && !field.getExpression().isBlank())
                {
                    throw new ServiceException("业务监听器配置不允许表达式字段", HttpStatus.BAD_REQUEST);
                }
            }
        }
        if (extensionKey == null || extensionKey.isBlank())
        {
            throw new ServiceException("业务监听器必须选择受控扩展", HttpStatus.BAD_REQUEST);
        }
        WorkflowExtensionOptionView version = registryService.lockLatestForDeployment(extensionKey.trim());
        if (!WorkflowExtensionRegistryService.JAVA_TYPE.equals(version.extensionType()))
        {
            throw new ServiceException("业务监听器只能选择已安装 Java 处理器", HttpStatus.CONFLICT);
        }
        WorkflowJavaExtensionHandler handler = handlerRegistry.require(version.implementationKey());
        if (!handler.supportsBusinessListener())
        {
            throw new ServiceException("所选 Java 处理器未注册业务监听能力", HttpStatus.CONFLICT);
        }
        String rawConfig = configJson == null || configJson.isBlank() ? "{}" : configJson.trim();
        JsonNode parsedConfig = readConfig(rawConfig);
        String normalizedConfig = WorkflowExtensionJsonCanonicalizer.canonicalize(
                handler.validateAndNormalizeConfig(parsedConfig));
        String snapshotElementId = WorkflowExtensionBpmnContract.listenerSnapshotElementId(
                elementId, listenerKind, listener.getEvent());
        WfDeployExtensionSnapshot snapshot = new WfDeployExtensionSnapshot();
        snapshot.setProcessKey(process.getId());
        snapshot.setElementId(snapshotElementId);
        snapshot.setExtensionKey(version.extensionKey());
        snapshot.setExtensionVersionId(version.versionId());
        snapshot.setVersionNo(version.versionNo());
        snapshot.setExtensionType(version.extensionType());
        snapshot.setImplementationKey(version.implementationKey());
        snapshot.setConfigJson(normalizedConfig);
        snapshot.setVersionChecksum(version.checksum());
        snapshot.setCreateBy(actorUserId);

        // 编译副本只保留固定业务监听器 Bean；字段和实现类不会进入 Flowable 资源。
        listener.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
        listener.setImplementation(WorkflowExtensionBpmnContract.BUSINESS_LISTENER_DELEGATE_EXPRESSION);
        listener.setFieldExtensions(new ArrayList<>());
        return snapshot;
    }

    /**
     * 在 Flowable 部署成功后绑定 deploymentId、计算最终摘要并批量持久化快照。
     * @param deploymentId String，Flowable 新部署主键
     * @param prepared WorkflowPreparedExtensionDeployment，部署前编译结果
     * @return void，无返回值；行数不一致时由外层事务整体回滚
     */
    public void persist(String deploymentId, WorkflowPreparedExtensionDeployment prepared)
    {
        persist(deploymentId, prepared, List.of());
    }

    /**
     * 在同一部署事务中合并服务任务和表单字段扩展快照后持久化。
     * @param deploymentId String，Flowable 新部署主键
     * @param prepared WorkflowPreparedExtensionDeployment，服务任务部署前编译结果
     * @param additionalSnapshots List&lt;WfDeployExtensionSnapshot&gt;，表单字段等非 ServiceTask 扩展快照
     * @return void，跨来源元素标识冲突或写入不完整时由外层事务整体回滚
     */
    public void persist(String deploymentId, WorkflowPreparedExtensionDeployment prepared,
            List<WfDeployExtensionSnapshot> additionalSnapshots)
    {
        List<WfDeployExtensionSnapshot> snapshots = new ArrayList<>(prepared.snapshots());
        if (additionalSnapshots != null)
        {
            snapshots.addAll(additionalSnapshots);
        }
        Set<String> uniqueElements = new HashSet<>();
        for (WfDeployExtensionSnapshot snapshot : snapshots)
        {
            String uniqueElement = snapshot.getProcessKey() + "\u0000" + snapshot.getElementId();
            if (!uniqueElements.add(uniqueElement))
            {
                throw new ServiceException("部署扩展快照元素标识重复", HttpStatus.CONFLICT);
            }
            snapshot.setDeployId(deploymentId);
            snapshot.setSnapshotChecksum(snapshotChecksum(snapshot));
        }
        int inserted = snapshots.isEmpty() ? 0 : snapshotMapper.insertBatch(snapshots);
        if (inserted != snapshots.size())
        {
            throw new ServiceException("部署扩展快照保存不完整", HttpStatus.CONFLICT);
        }
    }

    /**
     * 读取一个作者 ServiceTask 的固定字段，校验配置并把实现收敛为唯一调度器。
     * @param process Process，服务任务所属可执行流程
     * @param task ServiceTask，待编译服务任务
     * @param actorUserId String，部署操作人正式用户主键
     * @return WfDeployExtensionSnapshot，尚未绑定 deploymentId 的冻结快照
     */
    private WfDeployExtensionSnapshot compileServiceTask(Process process, ServiceTask task,
            String actorUserId)
    {
        String extensionKey = null;
        String configJson = null;
        List<FieldExtension> fields = task.getFieldExtensions();
        if (fields != null)
        {
            for (FieldExtension field : fields)
            {
                if (WorkflowExtensionBpmnContract.EXTENSION_KEY_FIELD.equals(field.getFieldName()))
                {
                    extensionKey = requireUniqueField(extensionKey, field.getStringValue(), "扩展标识");
                }
                else if (WorkflowExtensionBpmnContract.EXTENSION_CONFIG_FIELD.equals(field.getFieldName()))
                {
                    configJson = requireUniqueField(configJson, field.getStringValue(), "扩展配置");
                }
                else
                {
                    throw new ServiceException("服务任务包含未注册的字段注入", HttpStatus.BAD_REQUEST);
                }
                if (field.getExpression() != null && !field.getExpression().isBlank())
                {
                    throw new ServiceException("服务任务扩展配置不允许表达式", HttpStatus.BAD_REQUEST);
                }
            }
        }
        if (extensionKey == null || extensionKey.isBlank())
        {
            throw new ServiceException("服务任务必须选择受控扩展", HttpStatus.BAD_REQUEST);
        }
        String rawConfig = configJson == null || configJson.isBlank() ? "{}" : configJson.trim();
        if (rawConfig.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES)
        {
            throw new ServiceException("服务任务扩展配置超过大小限制", HttpStatus.BAD_REQUEST);
        }

        WorkflowExtensionOptionView version = registryService
                .lockLatestForDeployment(extensionKey.trim());
        JsonNode parsedConfig = readConfig(rawConfig);
        String normalizedConfig;
        if (WorkflowExtensionRegistryService.JAVA_TYPE.equals(version.extensionType()))
        {
            WorkflowJavaExtensionHandler handler = handlerRegistry.require(version.implementationKey());
            if (handler instanceof WorkflowCollaborationOutboxHandler collaborationOutbox)
            {
                // 协作 SendTask 在部署事务中冻结端点修订；运行时只登记 outbox，不回读可变目录。
                normalizedConfig = collaborationOutbox.freezeConfig(parsedConfig);
            }
            else
            {
                normalizedConfig = WorkflowExtensionJsonCanonicalizer.canonicalize(
                        handler.validateAndNormalizeConfig(parsedConfig));
            }
        }
        else if (WorkflowExtensionRegistryService.CEL_TYPE.equals(version.extensionType())
                && WorkflowExtensionRegistryService.CEL_IMPLEMENTATION_KEY
                        .equals(version.implementationKey()))
        {
            normalizedConfig = celSandbox.validateAndNormalizeConfig(parsedConfig);
        }
        else if (WorkflowExtensionRegistryService.HTTP_TYPE.equals(version.extensionType())
                && WorkflowHttpConnector.IMPLEMENTATION_KEY.equals(version.implementationKey()))
        {
            normalizedConfig = httpConnector.freezeConfig(parsedConfig, task.isAsynchronous());
        }
        else if (WorkflowExtensionRegistryService.SQL_TYPE.equals(version.extensionType())
                && WorkflowSqlConnector.IMPLEMENTATION_KEY.equals(version.implementationKey()))
        {
            normalizedConfig = sqlConnector.freezeConfig(parsedConfig, task.isAsynchronous());
        }
        else
        {
            throw new ServiceException("服务任务引用的扩展类型或实现不受支持", HttpStatus.CONFLICT);
        }

        WfDeployExtensionSnapshot snapshot = new WfDeployExtensionSnapshot();
        snapshot.setProcessKey(process.getId());
        snapshot.setElementId(task.getId());
        snapshot.setExtensionKey(version.extensionKey());
        snapshot.setExtensionVersionId(version.versionId());
        snapshot.setVersionNo(version.versionNo());
        snapshot.setExtensionType(version.extensionType());
        snapshot.setImplementationKey(version.implementationKey());
        snapshot.setConfigJson(normalizedConfig);
        snapshot.setVersionChecksum(version.checksum());
        snapshot.setCreateBy(actorUserId);

        // 编译资源只保留固定调度器，扩展键、版本和配置只能从数据库快照取得。
        task.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
        task.setImplementation(WorkflowExtensionBpmnContract.DELEGATE_EXPRESSION);
        task.setFieldExtensions(new ArrayList<>());
        return snapshot;
    }

    /**
     * 将作者 SendTask 转换为等价的受控 ServiceTask，并冻结精确扩展版本。
     * @param process Process，发送任务所属可执行流程
     * @param task SendTask，待转换的作者发送任务
     * @param actorUserId String，部署操作人正式用户主键
     * @return WfDeployExtensionSnapshot，尚未绑定 deploymentId 的冻结快照
     */
    private WfDeployExtensionSnapshot compileSendTask(Process process, SendTask task,
            String actorUserId)
    {
        FlowElementsContainer container = process.findParent(task);
        if (container == null)
        {
            throw new ServiceException("发送任务缺少所属流程容器", HttpStatus.BAD_REQUEST);
        }

        ServiceTask compiledTask = new ServiceTask();
        // Activity.setValues 复制 ID、连线、异步、多实例、补偿、边界事件和扩展属性等公共运行语义。
        compiledTask.setValues(task);
        compiledTask.setFieldExtensions(new ArrayList<>(task.getFieldExtensions()));
        WfDeployExtensionSnapshot snapshot = compileServiceTask(
                process, compiledTask, actorUserId);

        // 只替换深拷贝后的执行模型；作者模型中的 sendTask 类型和原始字段保持不变并可回显。
        container.removeFlowElement(task.getId());
        container.addFlowElement(compiledTask);
        return snapshot;
    }

    /**
     * 检查一个可执行流程内的受控任务标识唯一，避免快照身份发生歧义。
     * @param process Process，任务所属可执行流程
     * @param elementId String，BPMN 活动标识
     * @param uniqueActivities Set&lt;String&gt;，当前部署已登记的流程和活动复合键
     * @return void，标识重复时抛出 HTTP 409
     */
    private void requireUniqueActivity(Process process, String elementId,
            Set<String> uniqueActivities)
    {
        String uniqueKey = process.getId() + "\u0000" + elementId;
        if (!uniqueActivities.add(uniqueKey))
        {
            throw new ServiceException("同一流程内受控任务标识重复", HttpStatus.CONFLICT);
        }
    }

    /**
     * 检查同名作者字段只能出现一次并返回其字符串值。
     * @param existing String，先前读取值；首次为空
     * @param candidate String，本次字段字符串值
     * @param label String，异常中展示的字段业务名称
     * @return String，唯一字段值
     */
    private String requireUniqueField(String existing, String candidate, String label)
    {
        if (existing != null)
        {
            throw new ServiceException("服务任务" + label + "不能重复", HttpStatus.BAD_REQUEST);
        }
        return candidate == null ? "" : candidate;
    }

    /**
     * 使用 Jackson 3 解析配置对象，禁止字符串拼接或任意类型反序列化。
     * @param configJson String，作者 XML 中的配置 JSON
     * @return JsonNode，结构化配置对象
     */
    private JsonNode readConfig(String configJson)
    {
        try
        {
            JsonNode config = objectMapper.readTree(configJson);
            if (config == null || !config.isObject())
            {
                throw new ServiceException("服务任务扩展配置必须是 JSON 对象", HttpStatus.BAD_REQUEST);
            }
            return config;
        }
        catch (JacksonException exception)
        {
            throw new ServiceException("服务任务扩展配置不是合法 JSON", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 计算绑定部署后的完整执行快照摘要。
     * @param snapshot WfDeployExtensionSnapshot，字段完整且已绑定 deploymentId 的快照
     * @return String，快照 SHA-256
     */
    public static String snapshotChecksum(WfDeployExtensionSnapshot snapshot)
    {
        String canonicalConfig = WorkflowExtensionJsonCanonicalizer
                .canonicalize(snapshot.getConfigJson());
        return WorkflowExtensionChecksum.sha256(snapshot.getDeployId(), snapshot.getProcessKey(),
                snapshot.getElementId(), snapshot.getExtensionKey(),
                String.valueOf(snapshot.getExtensionVersionId()), String.valueOf(snapshot.getVersionNo()),
                snapshot.getExtensionType(), snapshot.getImplementationKey(), canonicalConfig,
                snapshot.getVersionChecksum());
    }
}

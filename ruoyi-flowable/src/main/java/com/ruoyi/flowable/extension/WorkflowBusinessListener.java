package com.ruoyi.flowable.extension;

import org.flowable.engine.RepositoryService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.model.WorkflowExtensionDeploymentService;
import com.ruoyi.flowable.service.model.WorkflowExtensionRegistryService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Flowable 受控业务监听器唯一运行入口。
 *
 * 作者 XML 只保存注册表稳定键和配置；部署编译后字段被剥离，本组件只能按不可变快照执行。
 */
@Component("workflowBusinessListener")
public class WorkflowBusinessListener implements ExecutionListener, TaskListener
{
    private static final String EXECUTION_KIND = "EXECUTION";
    private static final String TASK_KIND = "TASK";

    private final RepositoryService repositoryService;
    private final WorkflowDeploymentArtifactRepository artifactRepository;
    private final WorkflowJavaExtensionHandlerRegistry handlerRegistry;
    private final ObjectMapper objectMapper = JsonMapper.shared();

    /**
     * 创建固定业务监听器调度入口。
     * @param repositoryService RepositoryService，流程定义和部署定位服务
     * @param artifactRepository WorkflowDeploymentArtifactRepository，不可变扩展资源仓库
     * @param handlerRegistry WorkflowJavaExtensionHandlerRegistry，已安装 Java 处理器注册表
     * @return 无返回值，构造后由 Spring 以固定 Bean 名管理
     */
    public WorkflowBusinessListener(RepositoryService repositoryService,
            WorkflowDeploymentArtifactRepository artifactRepository,
            WorkflowJavaExtensionHandlerRegistry handlerRegistry)
    {
        this.repositoryService = repositoryService;
        this.artifactRepository = artifactRepository;
        this.handlerRegistry = handlerRegistry;
    }

    /**
     * 执行活动生命周期业务监听器。
     * @param execution DelegateExecution，当前 Flowable 活动执行上下文
     * @return void，无返回值；快照、版本或配置漂移时阻断流程执行
     */
    @Override
    public void notify(DelegateExecution execution)
    {
        RuntimeExtension runtimeExtension = requireRuntimeExtension(
                execution.getProcessDefinitionId(), execution.getCurrentActivityId(),
                EXECUTION_KIND, execution.getEventName(), true);
        runtimeExtension.handler().execute(execution, runtimeExtension.config());
    }

    /**
     * 执行用户任务生命周期业务监听器。
     * @param task DelegateTask，当前 Flowable 用户任务监听上下文
     * @return void，无返回值；处理器未声明监听能力或快照漂移时阻断任务事件
     */
    @Override
    public void notify(DelegateTask task)
    {
        RuntimeExtension runtimeExtension = requireRuntimeExtension(
                task.getProcessDefinitionId(), task.getTaskDefinitionKey(),
                TASK_KIND, task.getEventName(), false);
        runtimeExtension.handler().executeTask(task, runtimeExtension.config());
    }

    /**
     * 按流程定义和监听器稳定标识读取快照，并复核版本、配置和处理器能力。
     * @param processDefinitionId String，当前 Flowable 流程定义 ID
     * @param ownerElementId String，运行时活动或任务定义标识
     * @param listenerKind String，EXECUTION 或 TASK
     * @param event String，当前 Flowable 生命周期事件
     * @param allowProcessFallback boolean，执行监听器找不到活动快照时是否回退流程级监听器
     * @return RuntimeExtension，已完成全部一致性校验的处理器和配置
     */
    private RuntimeExtension requireRuntimeExtension(String processDefinitionId,
            String ownerElementId, String listenerKind, String event,
            boolean allowProcessFallback)
    {
        ProcessDefinition definition = repositoryService.getProcessDefinition(processDefinitionId);
        if (definition == null || definition.getDeploymentId() == null)
        {
            throw new ServiceException("业务监听器对应的流程定义不存在", HttpStatus.ERROR);
        }
        String snapshotElementId = WorkflowExtensionBpmnContract.listenerSnapshotElementId(
                ownerElementId, listenerKind, event);
        WfDeployExtensionSnapshot snapshot = artifactRepository.selectExtensionSnapshot(
                definition.getDeploymentId(), definition.getKey(), snapshotElementId);
        if (snapshot == null && allowProcessFallback)
        {
            // 流程级 start/end 监听回调的 currentActivityId 由引擎决定，使用流程 key 作稳定后备定位。
            String processSnapshotElementId = WorkflowExtensionBpmnContract.listenerSnapshotElementId(
                    definition.getKey(), listenerKind, event);
            snapshot = artifactRepository.selectExtensionSnapshot(
                    definition.getDeploymentId(), definition.getKey(), processSnapshotElementId);
        }
        if (snapshot == null)
        {
            throw new ServiceException("业务监听器执行快照不存在", HttpStatus.ERROR);
        }
        if (!WorkflowExtensionDeploymentService.snapshotChecksum(snapshot)
                .equals(snapshot.getSnapshotChecksum()))
        {
            throw new ServiceException("业务监听器快照校验和不一致", HttpStatus.ERROR);
        }
        if (!WorkflowExtensionRegistryService.JAVA_TYPE.equals(snapshot.getExtensionType()))
        {
            throw new ServiceException("业务监听器只允许 Java 注册表处理器", HttpStatus.ERROR);
        }
        WorkflowJavaExtensionHandler handler = handlerRegistry.require(
                snapshot.getImplementationKey());
        if (!handler.supportsBusinessListener())
        {
            throw new ServiceException("扩展处理器未声明业务监听能力", HttpStatus.ERROR);
        }
        try
        {
            JsonNode config = objectMapper.readTree(snapshot.getConfigJson());
            String installedVersionChecksum = WorkflowExtensionChecksum.sha256(
                    snapshot.getExtensionKey(), snapshot.getExtensionType(),
                    String.valueOf(snapshot.getVersionNo()), snapshot.getImplementationKey(),
                    WorkflowExtensionJsonCanonicalizer.canonicalize(handler.configSchema()));
            if (!installedVersionChecksum.equals(snapshot.getVersionChecksum()))
            {
                throw new ServiceException("业务监听器版本校验和不一致", HttpStatus.ERROR);
            }
            String normalized = WorkflowExtensionJsonCanonicalizer.canonicalize(
                    handler.validateAndNormalizeConfig(config));
            if (!normalized.equals(WorkflowExtensionJsonCanonicalizer
                    .canonicalize(snapshot.getConfigJson())))
            {
                throw new ServiceException("业务监听器配置规范化结果已漂移", HttpStatus.ERROR);
            }
            return new RuntimeExtension(handler, config);
        }
        catch (JacksonException exception)
        {
            throw new ServiceException("业务监听器快照配置无法解析", HttpStatus.ERROR);
        }
    }

    /**
     * 已通过部署快照一致性校验的业务监听运行参数。
     * @param handler WorkflowJavaExtensionHandler，服务端安装处理器
     * @param config JsonNode，不可变配置对象
     */
    private record RuntimeExtension(WorkflowJavaExtensionHandler handler, JsonNode config)
    {
    }
}

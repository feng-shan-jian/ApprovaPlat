package com.ruoyi.flowable.service.process;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessSnapshot;
import com.ruoyi.flowable.authorization.WorkflowTaskAccessSnapshot;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDetailQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDetailView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.service.model.WorkflowDeploymentService;
import com.ruoyi.flowable.service.process.WorkflowProcessFormDetailProjection.FormProjection;
import com.ruoyi.flowable.service.process.WorkflowProcessFormDetailProjection.FormProjectionRequest;
import com.ruoyi.flowable.service.process.WorkflowProcessFormDetailProjection.FormSchemas;
import com.ruoyi.flowable.service.process.WorkflowProcessHistoryProjection.HistoryData;
import com.ruoyi.flowable.service.process.WorkflowProcessHistoryProjection.HistoryPresentation;
import com.ruoyi.flowable.service.process.WorkflowProcessVariableProjection.VariableStore;
import com.ruoyi.flowable.service.task.WorkflowControlledLoopService;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceService;
import com.ruoyi.flowable.service.task.WorkflowNextTaskAssignmentContract;
import com.ruoyi.flowable.service.task.WorkflowNextTaskAssignmentContract.NextUserAssignmentPolicy;
import com.ruoyi.flowable.service.task.WorkflowReturnedApplicationProtocol;
import com.ruoyi.flowable.service.task.WorkflowTaskLifecycleService;

/**
 * 流程实例完整只读详情编排服务。
 *
 * 该服务独占对象授权、只读事务、定义关系、当前 capability 和最终 VO 组装顺序；表单、变量和
 * 历史展示算法委托给直接投影组件，避免编排层重新理解其存储与展示细节。
 */
@Service
public class WorkflowProcessDetailService
{
    /** 请求主键的最大字符数。 */
    private static final int MAX_ID_LENGTH = 255;

    private final WorkflowEngineOperations engineOperations;

    private final WorkflowProcessAccessService processAccessService;

    private final RepositoryService repositoryService;

    private final TaskService taskService;

    private final WorkflowDeploymentService deploymentService;

    /** 已授权详情的历史快照、活动变量和安全 JSON 直接投影组件。 */
    private final WorkflowProcessVariableProjection variableProjection;

    /** 已授权详情的部署表单、提交表单和当前表单直接投影组件。 */
    private final WorkflowProcessFormDetailProjection formProjection;

    /** 已授权详情的历史活动、意见、时间线、Viewer 和父子关系直接投影组件。 */
    private final WorkflowProcessHistoryProjection historyProjection;

    private final WorkflowMultiInstanceService multiInstanceService;

    private final WorkflowTaskLifecycleService taskLifecycleService;

    /** 已授权流程详情的受控循环状态和逐轮审计投影服务。 */
    private final WorkflowControlledLoopService controlledLoopService;

    /**
     * 创建流程实例详情编排服务。
     *
     * @param engineOperations WorkflowEngineOperations，只读事务和异常翻译边界
     * @param processAccessService WorkflowProcessAccessService，实例与任务对象授权服务
     * @param repositoryService RepositoryService，流程定义和 BPMN 模型公共 API
     * @param taskService TaskService，退回任务识别和 capability 实时查询 API
     * @param deploymentService WorkflowDeploymentService，安全 BPMN XML 读取服务
     * @param variableProjection WorkflowProcessVariableProjection，变量存储解码与安全投影组件
     * @param formProjection WorkflowProcessFormDetailProjection，部署与流程表单投影组件
     * @param historyProjection WorkflowProcessHistoryProjection，历史展示与父子关系投影组件
     * @param multiInstanceService WorkflowMultiInstanceService，动态多实例 capability 服务
     * @param taskLifecycleService WorkflowTaskLifecycleService，正式退回能力与执行树校验服务
     * @param controlledLoopService WorkflowControlledLoopService，受控循环状态与审计投影服务
     * @return 无返回值，构造后由 Spring 管理该服务
     */
    public WorkflowProcessDetailService(WorkflowEngineOperations engineOperations,
            WorkflowProcessAccessService processAccessService,
            RepositoryService repositoryService, TaskService taskService,
            WorkflowDeploymentService deploymentService,
            WorkflowProcessVariableProjection variableProjection,
            WorkflowProcessFormDetailProjection formProjection,
            WorkflowProcessHistoryProjection historyProjection,
            WorkflowMultiInstanceService multiInstanceService,
            WorkflowTaskLifecycleService taskLifecycleService,
            WorkflowControlledLoopService controlledLoopService)
    {
        this.engineOperations = engineOperations;
        this.processAccessService = processAccessService;
        this.repositoryService = repositoryService;
        this.taskService = taskService;
        this.deploymentService = deploymentService;
        this.variableProjection = variableProjection;
        this.formProjection = formProjection;
        this.historyProjection = historyProjection;
        this.multiInstanceService = multiInstanceService;
        this.taskLifecycleService = taskLifecycleService;
        this.controlledLoopService = controlledLoopService;
    }

    /**
     * 查询当前用户有对象权限的完整流程详情。
     *
     * @param request WorkflowProcessDetailQueryDto，实例主键和可选任务主键
     * @return WorkflowProcessDetailView，表单、变量、时间线、意见、BPMN 和 Viewer 状态
     */
    public WorkflowProcessDetailView getDetail(WorkflowProcessDetailQueryDto request)
    {
        if (request == null)
        {
            throw invalidArgument("流程详情查询参数不能为空");
        }
        String instanceId = requireText(request.processInstanceId(), "流程实例主键不能为空");
        String taskId = optionalText(request.taskId(), "任务主键过长");
        return engineOperations.read(() -> buildDetail(instanceId, taskId));
    }

    /**
     * 在同一只读事务内按固定顺序完成授权、关系核验和全部详情投影。
     *
     * @param instanceId String，已经过格式校验的流程实例主键
     * @param taskId String，可选的任务主键
     * @return WorkflowProcessDetailView，完成全部数据门禁的详情视图
     */
    private WorkflowProcessDetailView buildDetail(String instanceId, String taskId)
    {
        // 授权必须先于表单、变量、意见和 BPMN 正文读取，拒绝请求不得产生敏感数据查询。
        WorkflowProcessAccessSnapshot instance =
                processAccessService.requireReadableInstance(instanceId);
        WorkflowTaskAccessSnapshot requestedTask = taskId == null ? null
                : processAccessService.requireReadableTask(taskId);
        if (requestedTask == null
                && WorkflowReturnedApplicationProtocol.RETURNED_STATUS.equals(
                        instance.businessStatus())
                && StringUtils.hasText(instance.startUserId()))
        {
            // “我的流程”详情不携带 taskId；退回态由服务端定位发起人独占任务并再次走对象授权。
            List<Task> returnedTasks = taskService.createTaskQuery()
                    .processInstanceId(instance.processInstanceId()).active()
                    .taskAssignee(instance.startUserId()).list();
            if (returnedTasks == null || returnedTasks.size() != 1)
            {
                throw dataError("退回任务状态异常");
            }
            requestedTask = processAccessService.requireReadableTask(returnedTasks.get(0).getId());
        }
        if (requestedTask != null)
        {
            requireSame(instance.processInstanceId(), requestedTask.processInstanceId(),
                    "任务与流程实例关系不一致");
            requireSame(instance.processDefinitionId(), requestedTask.processDefinitionId(),
                    "任务与流程定义关系不一致");
        }

        ProcessDefinition definition = requireDefinition(instance.processDefinitionId());
        requireSame(definition.getDeploymentId(), instance.deploymentId(),
                "流程实例与部署关系不一致");
        BpmnProcessContext bpmn = requireBpmnProcess(definition);

        // 继续保持部署表单、历史活动/任务/意见、祖先关系和变量快照的原有读取顺序。
        FormSchemas schemas = formProjection.loadSchemas(instance.deploymentId());
        HistoryData history = historyProjection.loadHistory(instance);
        Set<String> ancestorDeploymentIds = historyProjection
                .loadAncestorDeploymentIds(instanceId);
        VariableStore variables = variableProjection.loadSubmissionSnapshots(instanceId,
                instance.deploymentId(), ancestorDeploymentIds);

        // 只有活动任务才代表当前循环轮次；历史任务不能把详情投影成“下一轮”。
        String activeControlledLoopActivityId = requestedTask != null && requestedTask.active()
                ? requestedTask.taskDefinitionKey() : null;
        List<com.ruoyi.flowable.domain.vo.WorkflowControlledLoopStateView> controlledLoopStates =
                controlledLoopService.buildStates(definition.getDeploymentId(), definition.getKey(),
                        instance.processInstanceId(), activeControlledLoopActivityId);
        String currentTaskActivityId = requestedTask == null
                ? null : requestedTask.taskDefinitionKey();
        boolean currentTaskControlledLoop = requestedTask != null
                && controlledLoopStates.stream().anyMatch(state -> state.active()
                        && currentTaskActivityId.equals(state.activityId()));
        boolean returnedApplication = WorkflowReturnedApplicationProtocol.RETURNED_STATUS.equals(
                instance.businessStatus());
        FormProjection forms = formProjection.project(new FormProjectionRequest(
                schemas, history, bpmn.process(), variables, instance.deploymentId(),
                requestedTask, currentTaskControlledLoop, returnedApplication));

        String processStatus = normalizeProcessStatus(instance);
        HistoryPresentation historyPresentation = historyProjection.projectPresentation(
                history, instance,
                WorkflowReturnedApplicationProtocol.RETURNED_STATUS.equals(processStatus));
        String bpmnXml = deploymentService.getBpmnXml(definition.getId());
        List<com.ruoyi.flowable.domain.vo.WorkflowProcessRelationView> processRelations =
                historyProjection.buildProcessRelations(instance.processInstanceId());

        Instant startTime = instance.startTime();
        if (startTime == null)
        {
            throw dataError("流程实例开始时间不能为空");
        }
        Long durationMillis = instance.endTime() == null ? null
                : safeDurationMillis(startTime, instance.endTime());
        // 临时申请人任务只承载表单修改，不属于正式 ACTIVE 审批轮次，不能交给多实例服务解析。
        boolean returnedApplicantTask = isReturnedApplicantTask(instance, requestedTask);
        // capability 由服务端对象所有权和部署模型共同计算，普通任务返回 null，页面无需用 409 探测。
        com.ruoyi.flowable.domain.vo.WorkflowMultiInstanceStateView multiInstanceState =
                requestedTask == null || returnedApplicantTask ? null
                        : multiInstanceService.getOptionalState(requestedTask.taskId());
        // 退回入口必须由正式动作准备链投影；静态多实例、子流程和复杂执行树统一失败关闭。
        boolean returnAllowed = requestedTask != null && requestedTask.active()
                && taskLifecycleService.isTaskReturnAllowed(requestedTask.taskId());
        NextUserAssignmentPolicy nextUserAssignmentPolicy = resolveNextUserAssignmentPolicy(
                instance, requestedTask, bpmn.process());
        return new WorkflowProcessDetailView(instance.processInstanceId(), definition.getId(),
                definition.getKey(), definition.getName(), definition.getVersion(),
                definition.getCategory(), definition.getDeploymentId(), instance.businessKey(),
                instance.startUserId(), historyPresentation.startUserName(), startTime,
                instance.endTime(), durationMillis, processStatus, requestedTask,
                nextUserAssignmentPolicy.name(), multiInstanceState, returnAllowed,
                controlledLoopStates, forms.currentTaskForm(), forms.processForms(),
                historyPresentation.timeline(), processRelations, bpmnXml,
                historyPresentation.viewer());
    }

    /**
     * 严格识别退回阶段唯一的申请人待修改任务，避免把临时多实例根投影成正式审批轮次。
     *
     * @param instance WorkflowProcessAccessSnapshot，已经通过对象授权的流程实例快照
     * @param task WorkflowTaskAccessSnapshot，可选的请求任务快照
     * @return boolean，流程为 returned、局部标记和发起人一致且该任务是唯一活动任务时返回 true
     */
    private boolean isReturnedApplicantTask(WorkflowProcessAccessSnapshot instance,
            WorkflowTaskAccessSnapshot task)
    {
        if (task == null || !task.active()
                || !WorkflowReturnedApplicationProtocol.RETURNED_STATUS.equals(
                        instance.businessStatus())
                || !StringUtils.hasText(instance.startUserId())
                || !Objects.equals(instance.processInstanceId(), task.processInstanceId())
                || !Objects.equals(instance.startUserId(), task.assignee())
                || task.owner() != null || StringUtils.hasText(task.delegationState()))
        {
            return false;
        }
        Object applicantMarker = taskService.getVariableLocal(
                task.taskId(), WorkflowReturnedApplicationProtocol.RETURN_APPLICANT_VARIABLE);
        if (!Objects.equals(instance.startUserId(), applicantMarker))
        {
            return false;
        }

        // 只有整个流程唯一的活动任务才可能是申请人待修改任务，组内或组外并行都继续走正式投影校验。
        List<Task> activeTasks = taskService.createTaskQuery()
                .processInstanceId(instance.processInstanceId()).active().list();
        if (activeTasks == null || activeTasks.size() != 1)
        {
            return false;
        }
        Task activeTask = activeTasks.get(0);
        boolean exactApplicantTask = activeTask != null
                && Objects.equals(task.taskId(), activeTask.getId())
                && Objects.equals(instance.startUserId(), activeTask.getAssignee())
                && activeTask.getOwner() == null
                && activeTask.getDelegationState() == null;
        if (!exactApplicantTask)
        {
            return false;
        }
        // 临时待修改任务必须是申请人独占任务；残留候选关系属于持久化漂移，详情直接失败关闭。
        var identityLinks = taskService.getIdentityLinksForTask(activeTask.getId());
        if (identityLinks == null || identityLinks.stream().anyMatch(link -> link != null
                && IdentityLinkType.CANDIDATE.equals(link.getType())))
        {
            throw dataError("退回申请人任务候选关系异常");
        }
        return true;
    }

    /**
     * 按写命令相同的唯一活动任务边界投影动态下一办理人策略。
     *
     * @param instance WorkflowProcessAccessSnapshot，已经通过对象授权的流程实例快照
     * @param requestedTask WorkflowTaskAccessSnapshot，可选的已授权请求任务快照
     * @param process Process，当前流程定义对应的正式部署 BPMN 流程
     * @return NextUserAssignmentPolicy，仅唯一活动任务与请求任务相同时返回模型策略，否则返回 DISABLED
     */
    private NextUserAssignmentPolicy resolveNextUserAssignmentPolicy(
            WorkflowProcessAccessSnapshot instance, WorkflowTaskAccessSnapshot requestedTask,
            org.flowable.bpmn.model.Process process)
    {
        if (requestedTask == null || !requestedTask.active() || instance.endTime() != null)
        {
            return NextUserAssignmentPolicy.DISABLED;
        }

        // 详情能力必须与 prepare() 的写入前检查一致，不能让并行或复杂执行树展示实际不可执行的入口。
        List<Task> activeTasks = taskService.createTaskQuery()
                .processInstanceId(instance.processInstanceId())
                .active()
                .list();
        if (activeTasks == null || activeTasks.size() != 1)
        {
            return NextUserAssignmentPolicy.DISABLED;
        }
        Task activeTask = activeTasks.get(0);
        if (activeTask == null || !requestedTask.taskId().equals(activeTask.getId()))
        {
            return NextUserAssignmentPolicy.DISABLED;
        }

        try
        {
            // 只有运行时结构门禁通过后才读取模型策略，避免复杂执行树泄露伪 capability。
            return WorkflowNextTaskAssignmentContract.resolvePolicy(
                    process, requestedTask.taskDefinitionKey());
        }
        catch (IllegalArgumentException exception)
        {
            throw dataError("动态下一办理人部署契约异常");
        }
    }

    /**
     * 查询并核验流程定义。
     *
     * @param definitionId String，实例记录中的真实流程定义主键
     * @return ProcessDefinition，存在且关键关系字段完整的流程定义
     */
    private ProcessDefinition requireDefinition(String definitionId)
    {
        if (!StringUtils.hasText(definitionId))
        {
            throw dataError("流程实例缺少流程定义关联");
        }
        ProcessDefinition definition = repositoryService.getProcessDefinition(definitionId);
        if (definition == null)
        {
            throw new ServiceException("流程定义不存在或已被删除", HttpStatus.NOT_FOUND);
        }
        if (!StringUtils.hasText(definition.getId()) || !StringUtils.hasText(definition.getKey())
                || !StringUtils.hasText(definition.getDeploymentId()))
        {
            throw dataError("流程定义关联数据异常");
        }
        return definition;
    }

    /**
     * 查询定义对应 BPMN 模型和同 key 的可执行流程。
     *
     * @param definition ProcessDefinition，已经过关系核验的流程定义
     * @return BpmnProcessContext，BPMN 模型和目标流程
     */
    private BpmnProcessContext requireBpmnProcess(ProcessDefinition definition)
    {
        BpmnModel model = repositoryService.getBpmnModel(definition.getId());
        if (model == null)
        {
            throw dataError("流程定义缺少 BPMN 模型");
        }
        org.flowable.bpmn.model.Process process = model.getProcessById(definition.getKey());
        if (process == null)
        {
            throw dataError("流程定义与 BPMN 流程关系不一致");
        }
        return new BpmnProcessContext(model, process);
    }

    /**
     * 规范实例详情状态，优先采用服务端业务终态并保留真实挂起状态。
     *
     * @param instance WorkflowProcessAccessSnapshot，已授权实例快照
     * @return String，稳定的流程业务状态
     */
    private String normalizeProcessStatus(WorkflowProcessAccessSnapshot instance)
    {
        return WorkflowProcessStatusNormalizer.normalize(instance.businessStatus(),
                instance.state(), instance.endTime(), instance.deleteReason());
    }

    /**
     * 计算非负且不溢出的流程持续毫秒数。
     *
     * @param startTime Instant，流程开始时间
     * @param endTime Instant，流程结束时间
     * @return Long，流程持续毫秒数
     */
    private Long safeDurationMillis(Instant startTime, Instant endTime)
    {
        if (endTime.isBefore(startTime))
        {
            throw dataError("流程实例时间范围异常");
        }
        try
        {
            return Duration.between(startTime, endTime).toMillis();
        }
        catch (ArithmeticException exception)
        {
            throw dataError("流程实例持续时间异常");
        }
    }

    /**
     * 校验两个服务端真实关系主键一致。
     *
     * @param expected String，可信对象中的期望主键
     * @param actual String，关联对象中的实际主键
     * @param message String，关系不一致时的稳定提示
     * @return 无返回值，不一致时抛出 409
     */
    private void requireSame(String expected, String actual, String message)
    {
        if (!StringUtils.hasText(expected) || !expected.equals(actual))
        {
            throw new ServiceException(message, HttpStatus.CONFLICT);
        }
    }

    /**
     * 校验必填请求文本并去除首尾空白。
     *
     * @param value String，待校验文本
     * @param message String，空值时稳定提示
     * @return String，规范后的非空文本
     */
    private String requireText(String value, String message)
    {
        String normalized = optionalText(value, message);
        if (normalized == null)
        {
            throw invalidArgument(message);
        }
        return normalized;
    }

    /**
     * 规范可选请求文本并限制主键长度。
     *
     * @param value String，允许为空的请求文本
     * @param message String，长度超限时稳定提示
     * @return String，规范文本或 null
     */
    private String optionalText(String value, String message)
    {
        if (!StringUtils.hasText(value))
        {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_ID_LENGTH)
        {
            throw invalidArgument(message);
        }
        return normalized;
    }

    /**
     * 创建请求参数异常。
     *
     * @param message String，稳定业务提示
     * @return ServiceException，HTTP 400 业务异常
     */
    private ServiceException invalidArgument(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 创建引擎、历史或业务表关联数据异常。
     *
     * @param message String，稳定业务提示
     * @return ServiceException，HTTP 500 业务异常
     */
    private ServiceException dataError(String message)
    {
        return new ServiceException(message, HttpStatus.ERROR);
    }

    /**
     * BPMN 模型及目标流程上下文。
     *
     * @param model BpmnModel，目标定义模型
     * @param process org.flowable.bpmn.model.Process，与定义 key 一致的流程
     */
    private record BpmnProcessContext(BpmnModel model, org.flowable.bpmn.model.Process process)
    {
    }
}

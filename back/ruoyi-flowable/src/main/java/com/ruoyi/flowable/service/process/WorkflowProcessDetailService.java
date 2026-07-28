package com.ruoyi.flowable.service.process;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.task.Comment;
import org.flowable.identitylink.api.IdentityLinkInfo;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessSnapshot;
import com.ruoyi.flowable.authorization.WorkflowTaskAccessSnapshot;
import com.ruoyi.flowable.domain.WorkflowCurrentVariableMetadataRow;
import com.ruoyi.flowable.domain.WorkflowHistoricSubmissionRow;
import com.ruoyi.flowable.domain.WorkflowHistoricVariableBodyRow;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDetailQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowCandidateIdentityView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessActivityView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessCommentView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDetailView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormSnapshotView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessViewerView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.mapper.WfDeployFormMapper;
import com.ruoyi.flowable.mapper.WorkflowHistoricVariableMapper;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.model.WorkflowDeploymentService;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceService;
import com.ruoyi.flowable.service.task.WorkflowNextTaskAssignmentContract;
import com.ruoyi.flowable.service.task.WorkflowNextTaskAssignmentContract.NextUserAssignmentPolicy;
import com.ruoyi.flowable.service.task.WorkflowTaskLifecycleService;
import com.ruoyi.flowable.service.process.WorkflowFormSubmissionSnapshotCodec.SnapshotKind;
import com.ruoyi.flowable.service.process.WorkflowFormSubmissionSnapshotCodec.SubmissionSnapshot;
import com.ruoyi.system.service.ISysUserService;

/**
 * 流程实例完整只读详情服务，统一处理对象授权、表单值白名单、时间线与 Viewer 状态。
 */
@Service
public class WorkflowProcessDetailService
{
    /** 单个详情允许读取的最大历史活动数。 */
    static final int MAX_ACTIVITY_ROWS = 1000;

    /** 单个详情允许读取的最大历史任务数。 */
    static final int MAX_TASK_ROWS = 500;

    /** 单个详情允许读取的最大部署表单快照数。 */
    static final int MAX_FORM_SNAPSHOTS = 500;

    /** 单个详情允许读取的最大历史变量行数。 */
    static final int MAX_VARIABLE_ROWS = 2000;

    /** 单个详情允许扫描的最大历史变量更新数，超过时拒绝截断审计数据。 */
    static final int MAX_VARIABLE_UPDATE_ROWS = 10_000;

    /** 单份内部提交快照允许的最大 UTF-8 字节数，与固定快照编码契约保持一致。 */
    static final int MAX_SUBMISSION_TEXT_BYTES = 2 * 1024 * 1024;

    /** Flowable 字符串 Blob 的最大 Java 序列化字节数，覆盖 modified UTF-8 最坏膨胀。 */
    static final int MAX_SUBMISSION_SERIALIZED_BYTES =
            MAX_SUBMISSION_TEXT_BYTES * 3 / 2 + 1024;

    /** 单次详情允许从历史快照存储读取的最大累计正文大小。 */
    static final int MAX_TOTAL_SUBMISSION_STORED_BYTES = 4 * 1024 * 1024;

    /** 单个详情允许读取的最大原始意见数。 */
    static final int MAX_COMMENT_ROWS = 1000;

    /** 单个任务允许返回的最大候选身份数。 */
    static final int MAX_CANDIDATES_PER_TASK = 100;

    /** 单条审批意见正文最大 UTF-8 字节数。 */
    static final int MAX_COMMENT_BYTES = 8 * 1024;

    /** 单个详情全部意见正文最大 UTF-8 字节数。 */
    static final int MAX_TOTAL_COMMENT_BYTES = 512 * 1024;

    /** 单个详情重复序列化后的表单快照正文最大字节数。 */
    static final int MAX_TOTAL_FORM_BYTES = 4 * 1024 * 1024;

    /** 单个详情全部回显变量 JSON 最大字节数。 */
    static final int MAX_TOTAL_VARIABLE_BYTES = 1024 * 1024;

    /** 单个变量安全 JSON 的最大递归深度。 */
    private static final int MAX_VARIABLE_DEPTH = 10;

    /** 单个变量安全 JSON 的最大节点数。 */
    private static final int MAX_VARIABLE_NODES = 2000;

    /** 单个 JSON 容器允许的最大成员数。 */
    private static final int MAX_VARIABLE_CONTAINER_SIZE = 500;

    /** 单个变量文本允许的最大 UTF-8 字节数。 */
    private static final int MAX_VARIABLE_TEXT_BYTES = 64 * 1024;

    /** 活动表单单个 JSON 正文允许读取的最大存储字节数。 */
    static final int MAX_CURRENT_VARIABLE_BODY_BYTES = 1024 * 1024;

    /** 活动表单单个字符串 Blob 允许的最大 Java 序列化字节数。 */
    static final int MAX_CURRENT_VARIABLE_SERIALIZED_BYTES =
            MAX_VARIABLE_TEXT_BYTES * 3 + 1024;

    /** 单次活动表单允许从变量存储读取的最大累计正文大小。 */
    static final int MAX_TOTAL_CURRENT_VARIABLE_STORED_BYTES = 2 * 1024 * 1024;

    /** 单次正文 SQL 的最大主键数量，防止过长 IN 列表。 */
    private static final int VARIABLE_BODY_QUERY_BATCH_SIZE = 200;

    /** 请求主键和引擎身份字段的最大字符数。 */
    private static final int MAX_ID_LENGTH = 255;

    /** 无论表单 schema 是否声明都不得对外回显的引擎内部变量。 */
    private static final Set<String> INTERNAL_VARIABLE_NAMES = Set.of(
            "initiator", "processStatus", "nrOfInstances", "nrOfActiveInstances",
            "nrOfCompletedInstances", "loopCounter", "_FLOWABLE_SKIP_EXPRESSION_ENABLED");

    /** 可以安全读取 getValue 的 Flowable 标量或 JSON 变量类型。 */
    private static final Set<String> SAFE_VARIABLE_TYPES = Set.of(
            "null", "string", "integer", "long", "short", "double", "boolean",
            "date", "instant", "json", "longjson", "longstring", "uuid",
            "bigdecimal", "biginteger");

    /** 必须无条件绕开 Flowable getValue 并从受控存储正文自行解码的变量类型。 */
    private static final Set<String> RAW_BODY_VARIABLE_TYPES = Set.of(
            "json", "longjson", "longstring");

    /** 递归禁止进入详情响应的原型污染键。 */
    private static final Set<String> FORBIDDEN_JSON_KEYS = Set.of(
            "__proto__", "prototype", "constructor");

    /** 字符串 Blob 只允许恢复单个 String，拒绝数组、自定义类、深层引用和超限流。 */
    private static final ObjectInputFilter STORED_STRING_FILTER = filterInfo ->
    {
        if (filterInfo.depth() > 1 || filterInfo.references() > 2
                || filterInfo.streamBytes() > MAX_SUBMISSION_SERIALIZED_BYTES
                || filterInfo.arrayLength() >= 0)
        {
            return ObjectInputFilter.Status.REJECTED;
        }
        Class<?> serializedClass = filterInfo.serialClass();
        return serializedClass == null || serializedClass == String.class
                ? ObjectInputFilter.Status.UNDECIDED
                : ObjectInputFilter.Status.REJECTED;
    };

    /** 旧系统正式使用且允许进入详情的审批意见类型及显示名称。 */
    private static final Map<String, String> COMMENT_TYPE_NAMES = Map.of(
            "1", "通过",
            "2", "退回",
            "3", "驳回",
            "4", "委派",
            "5", "转办",
            "6", "终止",
            "7", "撤回",
            "comment", "意见");

    private final WorkflowEngineOperations engineOperations;

    private final WorkflowProcessAccessService processAccessService;

    private final RepositoryService repositoryService;

    private final HistoryService historyService;

    private final TaskService taskService;

    private final WorkflowDeploymentService deploymentService;

    private final WfDeployFormMapper deployFormMapper;

    private final WorkflowHistoricVariableMapper historicVariableMapper;

    private final WorkflowFormTemplateValidator formTemplateValidator;

    private final ISysUserService userService;

    private final WorkflowMultiInstanceService multiInstanceService;

    private final WorkflowTaskLifecycleService taskLifecycleService;

    /** 严格拒绝重复字段和根节点后尾随内容的变量 JSON 解析器。 */
    private final ObjectMapper safeJsonMapper = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * 创建流程实例详情服务。
     *
     * @param engineOperations WorkflowEngineOperations，只读事务和异常翻译边界
     * @param processAccessService WorkflowProcessAccessService，实例与任务对象授权服务
     * @param repositoryService RepositoryService，流程定义和 BPMN 模型公共 API
     * @param historyService HistoryService，历史活动、任务和变量公共 API
     * @param taskService TaskService，流程意见公共 API
     * @param deploymentService WorkflowDeploymentService，安全 BPMN XML 读取服务
     * @param deployFormMapper WfDeployFormMapper，不可变部署表单快照 Mapper
     * @param historicVariableMapper WorkflowHistoricVariableMapper，内部提交快照安全查询 Mapper
     * @param formTemplateValidator WorkflowFormTemplateValidator，表单 schema 安全解析器
     * @param userService ISysUserService，历史用户显示名称查询服务
     * @param multiInstanceService WorkflowMultiInstanceService，当前办理任务的动态多实例 capability 服务
     * @param taskLifecycleService WorkflowTaskLifecycleService，正式退回能力与执行树校验服务
     * @return 无返回值，构造后由 Spring 管理该服务
     */
    public WorkflowProcessDetailService(WorkflowEngineOperations engineOperations,
            WorkflowProcessAccessService processAccessService,
            RepositoryService repositoryService, HistoryService historyService,
            TaskService taskService, WorkflowDeploymentService deploymentService,
            WfDeployFormMapper deployFormMapper, WorkflowHistoricVariableMapper historicVariableMapper,
            WorkflowFormTemplateValidator formTemplateValidator, ISysUserService userService,
            WorkflowMultiInstanceService multiInstanceService,
            WorkflowTaskLifecycleService taskLifecycleService)
    {
        this.engineOperations = engineOperations;
        this.processAccessService = processAccessService;
        this.repositoryService = repositoryService;
        this.historyService = historyService;
        this.taskService = taskService;
        this.deploymentService = deploymentService;
        this.deployFormMapper = deployFormMapper;
        this.historicVariableMapper = historicVariableMapper;
        this.formTemplateValidator = formTemplateValidator;
        this.userService = userService;
        this.multiInstanceService = multiInstanceService;
        this.taskLifecycleService = taskLifecycleService;
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
     * 在同一只读事务内按固定顺序完成授权、关系核验和全部详情读取。
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
        Map<NodeFormKey, SnapshotSchema> snapshots = loadSnapshotSchemas(instance.deploymentId());
        List<HistoricActivityInstance> activities = loadActivities(instance);
        List<HistoricTaskInstance> tasks = loadTasks(instance);
        Map<String, HistoricTaskInstance> tasksById = indexTasks(tasks);
        Map<String, List<WorkflowProcessCommentView>> commentsByTask =
                loadComments(instanceId, tasksById.keySet());

        Set<String> variableTaskIds = new LinkedHashSet<>(tasksById.keySet());
        if (requestedTask != null)
        {
            variableTaskIds.add(requestedTask.taskId());
        }
        VariableStore variables = loadVariables(instanceId, variableTaskIds);
        DetailResponseBudget responseBudget = new DetailResponseBudget();
        List<WorkflowProcessFormSnapshotView> processForms = buildExecutedForms(
                activities, tasksById, bpmn.process(), snapshots, variables,
                instance.deploymentId(), responseBudget);
        WorkflowProcessFormSnapshotView currentTaskForm = requestedTask == null ? null
                : buildCurrentTaskForm(requestedTask, tasksById, bpmn.process(), snapshots,
                        variables, instance.deploymentId(), responseBudget);

        Map<String, String> userNames = new HashMap<>();
        String startUserName = resolveUserName(instance.startUserId(), userNames);
        List<WorkflowProcessActivityView> timeline = buildTimeline(activities, tasksById,
                commentsByTask, instance, startUserName, userNames);
        WorkflowProcessViewerView viewer = buildViewer(activities, tasksById, commentsByTask);
        String bpmnXml = deploymentService.getBpmnXml(definition.getId());

        Instant startTime = instance.startTime();
        if (startTime == null)
        {
            throw dataError("流程实例开始时间不能为空");
        }
        Long durationMillis = instance.endTime() == null ? null
                : safeDurationMillis(startTime, instance.endTime());
        // capability 由服务端对象所有权和部署模型共同计算，普通任务返回 null，页面无需用 409 探测。
        com.ruoyi.flowable.domain.vo.WorkflowMultiInstanceStateView multiInstanceState =
                requestedTask == null ? null
                        : multiInstanceService.getOptionalState(requestedTask.taskId());
        // 退回入口必须由正式动作准备链投影；静态多实例、子流程和复杂执行树统一失败关闭。
        boolean returnAllowed = requestedTask != null && requestedTask.active()
                && taskLifecycleService.isTaskReturnAllowed(requestedTask.taskId());
        NextUserAssignmentPolicy nextUserAssignmentPolicy = resolveNextUserAssignmentPolicy(
                instance, requestedTask, bpmn.process());
        boolean nextUserSelectionRequired = nextUserAssignmentPolicy
                == NextUserAssignmentPolicy.REQUIRED_ALL
                || nextUserAssignmentPolicy == NextUserAssignmentPolicy.REQUIRED_ANY;
        String nextUserSelectionMode = switch (nextUserAssignmentPolicy)
        {
            case REQUIRED_ALL -> "ALL";
            case REQUIRED_ANY -> "ANY";
            default -> null;
        };
        return new WorkflowProcessDetailView(instance.processInstanceId(), definition.getId(),
                definition.getKey(), definition.getName(), definition.getVersion(),
                definition.getCategory(), definition.getDeploymentId(), instance.businessKey(),
                instance.startUserId(), startUserName, startTime, instance.endTime(),
                durationMillis, normalizeProcessStatus(instance), requestedTask,
                nextUserAssignmentPolicy.name(), nextUserSelectionRequired,
                nextUserSelectionMode,
                multiInstanceState, returnAllowed, currentTaskForm,
                processForms, timeline, bpmnXml, viewer);
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
     * 一次性读取部署表单快照，验证 schema 并建立节点与表单键联合索引。
     *
     * @param deploymentId String，实例所属真实部署主键
     * @return Map&lt;NodeFormKey, SnapshotSchema&gt;，不可变快照及字段白名单索引
     */
    private Map<NodeFormKey, SnapshotSchema> loadSnapshotSchemas(String deploymentId)
    {
        List<WfDeployForm> rows = deployFormMapper.selectByDeploymentId(deploymentId);
        if (rows == null || rows.size() > MAX_FORM_SNAPSHOTS)
        {
            throw dataError("部署表单快照数量异常");
        }
        Map<NodeFormKey, SnapshotSchema> indexed = new LinkedHashMap<>();
        for (WfDeployForm row : rows)
        {
            if (row == null || !deploymentId.equals(row.getDeployId())
                    || row.getFormId() == null || row.getFormId() <= 0
                    || !StringUtils.hasText(row.getNodeKey())
                    || !StringUtils.hasText(row.getFormKey())
                    || !StringUtils.hasText(row.getContent()))
            {
                throw dataError("部署表单快照关联数据异常");
            }
            Set<String> variableNames = formTemplateValidator.extractVariableNames(row.getContent());
            NodeFormKey key = new NodeFormKey(row.getNodeKey(), row.getFormKey());
            if (indexed.putIfAbsent(key, new SnapshotSchema(row, variableNames)) != null)
            {
                throw dataError("部署表单快照节点关系不唯一");
            }
        }
        return Collections.unmodifiableMap(indexed);
    }

    /**
     * 有界查询实例全部历史活动，Viewer 需要保留顺序流而时间线后续只选择业务节点。
     *
     * @param instance WorkflowProcessAccessSnapshot，已授权实例快照
     * @return List&lt;HistoricActivityInstance&gt;，按开始时间和活动主键稳定排序的历史活动
     */
    private List<HistoricActivityInstance> loadActivities(WorkflowProcessAccessSnapshot instance)
    {
        List<HistoricActivityInstance> rows = historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(instance.processInstanceId())
                .orderByHistoricActivityInstanceStartTime().asc()
                .orderByActivityId().asc()
                .listPage(0, MAX_ACTIVITY_ROWS + 1);
        if (rows == null || rows.size() > MAX_ACTIVITY_ROWS)
        {
            throw dataError("流程历史活动数量超过安全上限");
        }
        for (HistoricActivityInstance row : rows)
        {
            if (row == null || !instance.processInstanceId().equals(row.getProcessInstanceId())
                    || !instance.processDefinitionId().equals(row.getProcessDefinitionId())
                    || !StringUtils.hasText(row.getActivityId()))
            {
                throw dataError("流程历史活动关联数据异常");
            }
        }
        return List.copyOf(rows);
    }

    /**
     * 有界查询实例全部历史任务，并预加载候选身份链接。
     *
     * @param instance WorkflowProcessAccessSnapshot，已授权实例快照
     * @return List&lt;HistoricTaskInstance&gt;，按开始时间和任务主键稳定排序的历史任务
     */
    private List<HistoricTaskInstance> loadTasks(WorkflowProcessAccessSnapshot instance)
    {
        List<HistoricTaskInstance> rows = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(instance.processInstanceId())
                .includeIdentityLinks()
                .orderByHistoricTaskInstanceStartTime().asc()
                .orderByTaskId().asc()
                .listPage(0, MAX_TASK_ROWS + 1);
        if (rows == null || rows.size() > MAX_TASK_ROWS)
        {
            throw dataError("流程历史任务数量超过安全上限");
        }
        for (HistoricTaskInstance row : rows)
        {
            if (row == null || !instance.processInstanceId().equals(row.getProcessInstanceId())
                    || !instance.processDefinitionId().equals(row.getProcessDefinitionId())
                    || !StringUtils.hasText(row.getId()))
            {
                throw dataError("流程历史任务关联数据异常");
            }
        }
        return List.copyOf(rows);
    }

    /**
     * 将历史任务按主键建立唯一索引。
     *
     * @param tasks List&lt;HistoricTaskInstance&gt;，已完成关系核验的历史任务
     * @return Map&lt;String, HistoricTaskInstance&gt;，任务主键唯一索引
     */
    private Map<String, HistoricTaskInstance> indexTasks(List<HistoricTaskInstance> tasks)
    {
        Map<String, HistoricTaskInstance> indexed = new LinkedHashMap<>();
        for (HistoricTaskInstance task : tasks)
        {
            if (indexed.putIfAbsent(task.getId(), task) != null)
            {
                throw dataError("流程历史任务主键不唯一");
            }
        }
        return Collections.unmodifiableMap(indexed);
    }

    /**
     * 一次性读取实例意见，只保留正式业务类型并按任务建立稳定时间顺序。
     *
     * @param instanceId String，已授权流程实例主键
     * @param knownTaskIds Set&lt;String&gt;，本实例历史任务主键集合
     * @return Map&lt;String, List&lt;WorkflowProcessCommentView&gt;&gt;，任务到受控意见列表映射
     */
    private Map<String, List<WorkflowProcessCommentView>> loadComments(
            String instanceId, Set<String> knownTaskIds)
    {
        List<Comment> comments = taskService.getProcessInstanceComments(instanceId);
        if (comments == null || comments.size() > MAX_COMMENT_ROWS)
        {
            throw dataError("流程审批意见数量超过安全上限");
        }
        Map<String, List<WorkflowProcessCommentView>> indexed = new HashMap<>();
        int totalMessageBytes = 0;
        for (Comment comment : comments)
        {
            if (comment == null || (StringUtils.hasText(comment.getProcessInstanceId())
                    && !instanceId.equals(comment.getProcessInstanceId())))
            {
                throw dataError("流程审批意见关联数据异常");
            }
            if (!knownTaskIds.contains(comment.getTaskId())
                    || !COMMENT_TYPE_NAMES.containsKey(comment.getType()))
            {
                // 引擎事件和未知扩展类型不进入用户可见审批意见，任务关系也不能由意见反向扩展。
                continue;
            }
            String message = comment.getFullMessage();
            int messageBytes = message == null ? 0
                    : message.getBytes(StandardCharsets.UTF_8).length;
            if (messageBytes > MAX_COMMENT_BYTES)
            {
                throw dataError("单条流程审批意见超过安全上限");
            }
            totalMessageBytes += messageBytes;
            if (totalMessageBytes > MAX_TOTAL_COMMENT_BYTES)
            {
                throw dataError("流程审批意见正文总量超过安全上限");
            }
            String opinion = extractCommentOpinion(message);
            WorkflowProcessCommentView view = new WorkflowProcessCommentView(comment.getId(),
                    comment.getTaskId(), comment.getType(), COMMENT_TYPE_NAMES.get(comment.getType()),
                    message, opinion, comment.getUserId(), toInstant(comment.getTime()));
            indexed.computeIfAbsent(comment.getTaskId(), ignored -> new ArrayList<>()).add(view);
        }
        Comparator<WorkflowProcessCommentView> comparator = Comparator
                .comparing(WorkflowProcessCommentView::time,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(WorkflowProcessCommentView::commentId,
                        Comparator.nullsLast(Comparator.naturalOrder()));
        Map<String, List<WorkflowProcessCommentView>> immutable = new HashMap<>();
        indexed.forEach((key, value) ->
        {
            value.sort(comparator);
            immutable.put(key, List.copyOf(value));
        });
        return Collections.unmodifiableMap(immutable);
    }

    /**
     * 从服务端结构化审计中提取纯文本业务意见，并兼容既有纯文本 comment。
     *
     * @param message String，已经过单条及累计 UTF-8 大小门禁的原始 comment 正文
     * @return String，可直接展示的纯文本意见；结构化系统事件没有 opinion 时返回 null
     */
    private String extractCommentOpinion(String message)
    {
        if (!StringUtils.hasText(message))
        {
            return message;
        }
        String normalized = message.trim();
        if (!normalized.startsWith("{"))
        {
            return message;
        }
        try
        {
            JsonNode audit = safeJsonMapper.readTree(normalized);
            if (audit != null && audit.isObject())
            {
                JsonNode opinion = audit.get("opinion");
                if (opinion != null && opinion.isTextual())
                {
                    return opinion.textValue();
                }
                if (audit.has("schemaVersion") && audit.has("action"))
                {
                    // 已识别的结构化审计没有业务意见时不得把整段 JSON 当作用户可见正文。
                    return null;
                }
            }
        }
        catch (IOException exception)
        {
            // 历史纯文本可能以左花括号开头；解析失败仍按原始业务意见兼容展示。
        }
        return message;
    }

    /**
     * 分别查询当前变量和正式提交快照，任意值都延迟到受控变量名及类型命中后读取。
     *
     * @param instanceId String，已授权流程实例主键
     * @param taskIds Set&lt;String&gt;，需要支持当前表单回显的历史及活动任务主键
     * @return VariableStore，当前变量元数据及不可变提交快照索引
     */
    private VariableStore loadVariables(String instanceId, Set<String> taskIds)
    {
        List<HistoricVariableInstance> processRows = historyService
                .createHistoricVariableInstanceQuery()
                .processInstanceId(instanceId)
                .excludeTaskVariables()
                .excludeLocalVariables()
                .excludeVariableInitialization()
                .orderByVariableName().asc()
                .listPage(0, MAX_VARIABLE_ROWS + 1);
        if (processRows == null || processRows.size() > MAX_VARIABLE_ROWS)
        {
            throw dataError("流程变量数量超过安全上限");
        }
        Map<String, HistoricVariableInstance> processVariables =
                indexVariables(processRows, instanceId, null);

        Map<String, Map<String, HistoricVariableInstance>> taskVariables = new HashMap<>();
        if (!taskIds.isEmpty())
        {
            List<HistoricVariableInstance> taskRows = historyService
                    .createHistoricVariableInstanceQuery()
                    .taskIds(taskIds)
                    .excludeVariableInitialization()
                    .orderByVariableName().asc()
                    .listPage(0, MAX_VARIABLE_ROWS + 1);
            if (taskRows == null || taskRows.size() > MAX_VARIABLE_ROWS)
            {
                throw dataError("任务局部变量数量超过安全上限");
            }
            for (HistoricVariableInstance variable : taskRows)
            {
                if (variable == null || !instanceId.equals(variable.getProcessInstanceId())
                        || !taskIds.contains(variable.getTaskId())
                        || !StringUtils.hasText(variable.getVariableName()))
                {
                    throw dataError("任务局部变量关联数据异常");
                }
                Map<String, HistoricVariableInstance> byName = taskVariables.computeIfAbsent(
                        variable.getTaskId(), ignored -> new LinkedHashMap<>());
                if (byName.putIfAbsent(variable.getVariableName(), variable) != null)
                {
                    throw dataError("任务局部变量名称不唯一");
                }
            }
        }
        Map<String, Map<String, HistoricVariableInstance>> immutableTasks = new HashMap<>();
        taskVariables.forEach((key, value) ->
                immutableTasks.put(key, Collections.unmodifiableMap(value)));
        SubmissionSnapshotIndex submissions = loadSubmissionSnapshots(instanceId);
        return new VariableStore(Collections.unmodifiableMap(processVariables),
                Collections.unmodifiableMap(immutableTasks), submissions.startSubmission(),
                submissions.taskSubmissions());
    }

    /**
     * 以两阶段方式读取 FULL 历史中的固定内部提交快照。
     *
     * 第一阶段必须看见固定变量名的全部行并完成类型、存储列、Blob 关联和累计容量校验；
     * 只有结果总行数为零时才按升级前旧实例处理。全部元数据合法后才允许第二阶段读取正文。
     *
     * @param instanceId String，快照必须所属的已授权流程实例主键
     * @return SubmissionSnapshotIndex，唯一开始快照及按真实 taskId 建立的任务快照索引
     */
    private SubmissionSnapshotIndex loadSubmissionSnapshots(String instanceId)
    {
        List<WorkflowHistoricSubmissionRow> rows = historicVariableMapper
                .selectSubmissionMetadata(instanceId,
                        WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME,
                        MAX_VARIABLE_UPDATE_ROWS + 1);
        if (rows == null || rows.size() > MAX_VARIABLE_UPDATE_ROWS)
        {
            throw dataError("流程历史变量更新数量超过安全上限");
        }
        if (rows.isEmpty())
        {
            return new SubmissionSnapshotIndex(null, Map.of());
        }

        Map<String, WorkflowHistoricSubmissionRow> metadataById = new LinkedHashMap<>();
        long totalStoredBytes = 0L;
        for (WorkflowHistoricSubmissionRow row : rows)
        {
            validateSubmissionMetadata(row, instanceId);
            long rowStoredBytes = validateSubmissionStorage(row);
            totalStoredBytes = addBoundedStorageBytes(totalStoredBytes, rowStoredBytes,
                    MAX_TOTAL_SUBMISSION_STORED_BYTES,
                    "流程表单提交快照累计正文超过安全上限");
            if (metadataById.putIfAbsent(row.detailId(), row) != null)
            {
                throw dataError("流程历史变量更新主键不唯一");
            }
        }

        // 所有固定名行均完成统计校验后，才按已验证主键批量物化正文。
        Map<String, WorkflowHistoricVariableBodyRow> bodies = loadSubmissionBodies(
                instanceId, new ArrayList<>(metadataById.keySet()));
        StoredSubmission startSubmission = null;
        Map<String, StoredSubmission> taskSubmissions = new LinkedHashMap<>();
        for (WorkflowHistoricSubmissionRow row : rows)
        {
            String encoded = readSubmissionValue(row, bodies.get(row.detailId()));
            SubmissionSnapshot snapshot = WorkflowFormSubmissionSnapshotCodec.decode(encoded);
            StoredSubmission stored = new StoredSubmission(snapshot,
                    row.submittedAt().toInstant(), row.detailId(), row.activityInstanceId(),
                    row.taskId());
            if (snapshot.kind() == SnapshotKind.START)
            {
                if (StringUtils.hasText(row.taskId()) || startSubmission != null)
                {
                    throw dataError("流程开始表单提交快照不唯一");
                }
                startSubmission = stored;
            }
            else
            {
                if (!StringUtils.hasText(row.taskId())
                        || !row.taskId().equals(snapshot.taskId())
                        || taskSubmissions.putIfAbsent(row.taskId(), stored) != null)
                {
                    throw dataError("流程任务表单提交快照关联数据异常");
                }
            }
        }
        return new SubmissionSnapshotIndex(startSubmission,
                Collections.unmodifiableMap(taskSubmissions));
    }

    /**
     * 校验固定内部快照历史行的身份、类型和审计关联字段。
     *
     * @param row WorkflowHistoricSubmissionRow，不包含正文的历史快照元数据
     * @param instanceId String，已经完成对象授权的流程实例主键
     * @return 无返回值，任一固定名坏行都会抛出 HTTP 500 数据异常
     */
    private void validateSubmissionMetadata(WorkflowHistoricSubmissionRow row, String instanceId)
    {
        if (row == null || !instanceId.equals(row.processInstanceId())
                || !WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME.equals(row.variableName())
                || !StringUtils.hasText(row.detailId()) || row.detailId().length() > MAX_ID_LENGTH
                || row.submittedAt() == null || row.revision() == null || row.revision() < 0
                || !"VariableUpdate".equals(row.detailType())
                || !isSnapshotVariableType(row.variableTypeName()))
        {
            throw dataError("流程历史变量更新关联数据异常");
        }
    }

    /**
     * 校验快照元数据中的互斥存储列、Blob 关系和单行正文大小。
     *
     * Flowable 8 的历史更新类型名可能保留为 string，但正文已经按 longString 写入 Blob，
     * 因此字符串类型只约束值语义，实际解码方式必须以 BYTEARRAY_ID_ 等物理元数据为准。
     *
     * @param row WorkflowHistoricSubmissionRow，已通过身份关联校验的快照元数据
     * @return long，后续正文查询将物化的真实存储字节数
     */
    private long validateSubmissionStorage(WorkflowHistoricSubmissionRow row)
    {
        boolean textPresent = requireStorageFlag(row.textPresent());
        boolean text2Present = requireStorageFlag(row.text2Present());
        boolean byteArrayPresent = requireStorageFlag(row.byteArrayPresent());
        boolean byteArrayBodyPresent = requireStorageFlag(row.byteArrayBodyPresent());
        if (text2Present)
        {
            throw dataError("流程表单提交快照历史存储结构异常");
        }

        boolean textStorageValid = textPresent && row.textBytes() != null
                && row.textBytes() >= 1 && row.textBytes() <= MAX_SUBMISSION_TEXT_BYTES
                && row.byteArrayId() == null && !byteArrayPresent
                && !byteArrayBodyPresent && row.storedBytes() == null;
        boolean blobStorageValid = !textPresent && row.textBytes() == null
                && StringUtils.hasText(row.byteArrayId())
                && row.byteArrayId().length() <= MAX_ID_LENGTH
                && byteArrayPresent && byteArrayBodyPresent
                && row.storedBytes() != null && row.storedBytes() >= 1
                && row.storedBytes() <= MAX_SUBMISSION_SERIALIZED_BYTES;
        String normalizedType = normalizeVariableType(row.variableTypeName());
        boolean validStorage = "string".equals(normalizedType)
                ? textStorageValid != blobStorageValid
                : "longstring".equals(normalizedType) && blobStorageValid;
        if (!validStorage)
        {
            throw dataError("流程表单提交快照历史存储结构异常");
        }
        return textStorageValid ? row.textBytes() : row.storedBytes();
    }

    /**
     * 按已验证历史详情主键分批读取快照正文并建立唯一索引。
     *
     * @param instanceId String，已经完成对象授权的流程实例主键
     * @param rowIds List&lt;String&gt;，全部通过元数据和累计容量门禁的历史详情主键
     * @return Map&lt;String, WorkflowHistoricVariableBodyRow&gt;，与输入主键一一对应的正文索引
     */
    private Map<String, WorkflowHistoricVariableBodyRow> loadSubmissionBodies(
            String instanceId, List<String> rowIds)
    {
        Map<String, WorkflowHistoricVariableBodyRow> bodies = new LinkedHashMap<>();
        for (int offset = 0; offset < rowIds.size(); offset += VARIABLE_BODY_QUERY_BATCH_SIZE)
        {
            int end = Math.min(offset + VARIABLE_BODY_QUERY_BATCH_SIZE, rowIds.size());
            List<String> batch = List.copyOf(rowIds.subList(offset, end));
            List<WorkflowHistoricVariableBodyRow> batchRows = historicVariableMapper
                    .selectSubmissionBodies(instanceId,
                            WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME, batch);
            indexBodyRows(batchRows, new LinkedHashSet<>(batch), bodies,
                    "流程表单提交快照历史正文关联异常");
        }
        if (bodies.size() != rowIds.size())
        {
            throw dataError("流程表单提交快照历史正文关联异常");
        }
        return Collections.unmodifiableMap(bodies);
    }

    /**
     * 按已验证的物理存储位置读取内部快照正文，并核验正文与第一阶段统计完全一致。
     *
     * @param row WorkflowHistoricSubmissionRow，第一阶段已通过全部门禁的元数据
     * @param body WorkflowHistoricVariableBodyRow，第二阶段按同一主键读取的正文
     * @return String，尚未经过快照 JSON 结构解码的受限正文
     */
    private String readSubmissionValue(WorkflowHistoricSubmissionRow row,
            WorkflowHistoricVariableBodyRow body)
    {
        if (body == null || !row.detailId().equals(body.rowId()))
        {
            throw dataError("流程表单提交快照历史正文关联异常");
        }
        if (row.byteArrayId() == null)
        {
            String value = body.storedText();
            if (value == null || body.storedBytes() != null
                    || value.getBytes(StandardCharsets.UTF_8).length != row.textBytes())
            {
                throw dataError("流程表单提交快照历史正文异常");
            }
            return value;
        }
        byte[] serialized = body.storedBytes();
        if (body.storedText() != null || serialized == null
                || serialized.length != row.storedBytes())
        {
            throw dataError("流程表单提交快照历史正文异常");
        }
        return deserializeStoredString(serialized);
    }

    /**
     * 以只允许单个 String 的对象过滤器读取 Flowable 字符串 Blob，拒绝任意对象反序列化。
     *
     * @param serialized byte[]，经数据库和服务双重长度门禁的 Java 序列化正文
     * @return String，Flowable 写入的原始字符串
     */
    private String deserializeStoredString(byte[] serialized)
    {
        try (ByteArrayInputStream byteInput = new ByteArrayInputStream(serialized);
                ObjectInputStream objectInput = new ObjectInputStream(byteInput))
        {
            objectInput.setObjectInputFilter(STORED_STRING_FILTER);
            Object value = objectInput.readObject();
            if (!(value instanceof String text) || objectInput.read() != -1)
            {
                throw dataError("流程字符串 Blob 正文异常");
            }
            return text;
        }
        catch (IOException | ClassNotFoundException exception)
        {
            ServiceException failure = dataError("流程字符串 Blob 正文异常");
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 判断内部提交快照是否使用可安全读取的 Flowable 字符串类型。
     *
     * @param variableTypeName String，HistoricVariableUpdate 暴露的类型名
     * @return boolean，仅 string 与 longString 返回 true
     */
    private boolean isSnapshotVariableType(String variableTypeName)
    {
        if (!StringUtils.hasText(variableTypeName))
        {
            return false;
        }
        String normalized = variableTypeName.trim().toLowerCase(Locale.ROOT);
        return "string".equals(normalized) || "longstring".equals(normalized);
    }

    /**
     * 校验流程变量元数据并按变量名建立唯一索引。
     *
     * @param rows List&lt;HistoricVariableInstance&gt;，Flowable 历史变量结果
     * @param instanceId String，变量必须所属的流程实例主键
     * @param taskId String，期望任务主键；流程变量场景为空
     * @return Map&lt;String, HistoricVariableInstance&gt;，变量名唯一索引
     */
    private Map<String, HistoricVariableInstance> indexVariables(
            List<HistoricVariableInstance> rows, String instanceId, String taskId)
    {
        Map<String, HistoricVariableInstance> indexed = new LinkedHashMap<>();
        for (HistoricVariableInstance variable : rows)
        {
            if (variable == null || !instanceId.equals(variable.getProcessInstanceId())
                    || !Objects.equals(taskId, variable.getTaskId())
                    || !StringUtils.hasText(variable.getVariableName()))
            {
                throw dataError("流程变量关联数据异常");
            }
            if (indexed.putIfAbsent(variable.getVariableName(), variable) != null)
            {
                throw dataError("流程变量名称不唯一");
            }
        }
        return indexed;
    }

    /**
     * 按历史活动顺序构建具有合法不可变提交快照的开始节点和已完成任务表单。
     *
     * @param activities List&lt;HistoricActivityInstance&gt;，有界历史活动
     * @param tasksById Map&lt;String, HistoricTaskInstance&gt;，真实历史任务主键索引
     * @param process org.flowable.bpmn.model.Process，目标定义的 BPMN 流程
     * @param snapshots Map&lt;NodeFormKey, SnapshotSchema&gt;，部署表单快照索引
     * @param variables VariableStore，当前变量与正式提交快照索引
     * @param deploymentId String，流程实例所属部署主键
     * @param budget DetailResponseBudget，详情累计正文大小预算
     * @return List&lt;WorkflowProcessFormSnapshotView&gt;，仅包含可证明提交值的历史表单
     */
    private List<WorkflowProcessFormSnapshotView> buildExecutedForms(
            List<HistoricActivityInstance> activities,
            Map<String, HistoricTaskInstance> tasksById,
            org.flowable.bpmn.model.Process process,
            Map<NodeFormKey, SnapshotSchema> snapshots, VariableStore variables,
            String deploymentId, DetailResponseBudget budget)
    {
        List<WorkflowProcessFormSnapshotView> forms = new ArrayList<>();
        Set<String> usedSubmissionIds = new HashSet<>();
        for (HistoricActivityInstance activity : activities)
        {
            FlowElement element = process.getFlowElement(activity.getActivityId(), true);
            if (!(element instanceof StartEvent) && !(element instanceof UserTask))
            {
                continue;
            }
            String formKey = formKey(element);
            if (!StringUtils.hasText(formKey))
            {
                continue;
            }

            StoredSubmission submission;
            if (element instanceof StartEvent)
            {
                if (activity.getEndTime() == null)
                {
                    throw dataError("开始节点历史状态异常");
                }
                submission = variables.startSubmission();
                if (submission == null)
                {
                    // 旧实例没有正式开始提交快照时不返回伪造的最终变量历史值。
                    continue;
                }
                if (!activity.getActivityId().equals(submission.snapshot().nodeKey()))
                {
                    throw dataError("流程开始表单提交快照与历史节点关系不一致");
                }
            }
            else
            {
                if (!StringUtils.hasText(activity.getTaskId()))
                {
                    throw dataError("用户任务活动缺少任务关联");
                }
                HistoricTaskInstance historicTask = tasksById.get(activity.getTaskId());
                if (historicTask == null)
                {
                    throw dataError("用户任务活动与历史任务关系异常");
                }
                boolean activityFinished = activity.getEndTime() != null;
                boolean taskFinished = historicTask.getEndTime() != null;
                if (activityFinished != taskFinished)
                {
                    throw dataError("用户任务活动与历史任务结束状态不一致");
                }
                if (!taskFinished)
                {
                    // 活动任务只允许通过 currentTaskForm 读取当前值，不能进入历史提交列表。
                    continue;
                }
                submission = variables.taskSubmissions().get(activity.getTaskId());
                if (submission == null)
                {
                    // 兼容升级前实例：没有正式提交快照就不声称拥有历史表单值。
                    continue;
                }
            }
            if (!usedSubmissionIds.add(submission.detailId()))
            {
                throw dataError("流程表单提交快照被多个历史活动复用");
            }
            forms.add(buildSubmittedFormView(activity.getActivityId(), activity.getId(),
                    activity.getTaskId(), element, formKey, snapshots, submission,
                    deploymentId, budget));
        }
        return List.copyOf(forms);
    }

    /**
     * 构建请求指定任务的活动表单或已提交不可变表单。
     *
     * @param task WorkflowTaskAccessSnapshot，已完成对象授权和实例关系核验的任务
     * @param tasksById Map&lt;String, HistoricTaskInstance&gt;，实例历史任务索引
     * @param process org.flowable.bpmn.model.Process，目标定义的 BPMN 流程
     * @param snapshots Map&lt;NodeFormKey, SnapshotSchema&gt;，部署表单快照索引
     * @param variables VariableStore，当前变量与正式提交快照索引
     * @param deploymentId String，流程实例所属部署主键
     * @param budget DetailResponseBudget，详情累计正文大小预算
     * @return WorkflowProcessFormSnapshotView，合法任务表单；无表单或旧实例无提交快照时返回 null
     */
    private WorkflowProcessFormSnapshotView buildCurrentTaskForm(
            WorkflowTaskAccessSnapshot task,
            Map<String, HistoricTaskInstance> tasksById,
            org.flowable.bpmn.model.Process process,
            Map<NodeFormKey, SnapshotSchema> snapshots, VariableStore variables,
            String deploymentId, DetailResponseBudget budget)
    {
        if (!StringUtils.hasText(task.taskDefinitionKey()))
        {
            throw dataError("任务缺少 BPMN 节点关联");
        }
        HistoricTaskInstance historicTask = tasksById.get(task.taskId());
        if (historicTask == null
                || !task.taskDefinitionKey().equals(historicTask.getTaskDefinitionKey()))
        {
            throw dataError("任务与历史任务关系不一致");
        }
        boolean historicFinished = historicTask.getEndTime() != null;
        if (task.active() == historicFinished || (task.endTime() != null) != historicFinished)
        {
            throw dataError("任务活动状态与历史状态不一致");
        }

        FlowElement element = process.getFlowElement(task.taskDefinitionKey(), true);
        if (!(element instanceof UserTask))
        {
            throw dataError("任务与 BPMN 用户节点关系不一致");
        }
        String formKey = formKey(element);
        if (!StringUtils.hasText(formKey))
        {
            return null;
        }
        if (task.active())
        {
            return buildActiveFormView(task.processInstanceId(), task.taskDefinitionKey(),
                    task.taskId(), element, formKey, snapshots, variables, budget);
        }
        StoredSubmission submission = variables.taskSubmissions().get(task.taskId());
        return submission == null ? null
                : buildSubmittedFormView(task.taskDefinitionKey(), null, task.taskId(),
                        element, formKey, snapshots, submission, deploymentId, budget);
    }

    /**
     * 从当前变量元数据构建活动任务表单，时间必须保持为空以表明尚未提交。
     *
     * @param instanceId String，已经完成对象授权的流程实例主键
     * @param activityId String，活动任务 BPMN 节点主键
     * @param taskId String，真实活动任务主键
     * @param element FlowElement，BPMN 用户任务元素
     * @param formKey String，BPMN 表单键
     * @param snapshots Map&lt;NodeFormKey, SnapshotSchema&gt;，部署表单快照索引
     * @param variables VariableStore，当前变量元数据索引
     * @param budget DetailResponseBudget，详情累计正文大小预算
     * @return WorkflowProcessFormSnapshotView，当前值回显且 snapshotTime 为 null 的任务表单
     */
    private WorkflowProcessFormSnapshotView buildActiveFormView(String instanceId,
            String activityId, String taskId, FlowElement element, String formKey,
            Map<NodeFormKey, SnapshotSchema> snapshots, VariableStore variables,
            DetailResponseBudget budget)
    {
        SnapshotSchema schema = requireSnapshotSchema(element, formKey, snapshots);
        boolean taskLocal = isTaskLocal(element);
        Map<String, HistoricVariableInstance> source = taskLocal
                ? variables.taskVariables().getOrDefault(taskId, Map.of())
                : variables.processVariables();
        Map<String, JsonNode> values = buildSafeValues(instanceId, taskId, taskLocal,
                schema.variableNames(), source, budget);
        return toFormView(activityId, taskId, schema.snapshot(), taskLocal, values,
                null, budget);
    }

    /**
     * 校验提交快照与部署、节点、任务和历史更新的强关联后构建只读历史表单。
     *
     * @param activityId String，BPMN 节点主键
     * @param historicActivityInstanceId String，Flowable 历史活动实例主键；当前任务场景允许为空
     * @param taskId String，用户任务主键；开始节点为空
     * @param element FlowElement，开始节点或用户任务元素
     * @param formKey String，BPMN 表单键
     * @param snapshots Map&lt;NodeFormKey, SnapshotSchema&gt;，部署表单快照索引
     * @param submission StoredSubmission，历史变量中的不可变提交快照
     * @param deploymentId String，流程实例所属部署主键
     * @param budget DetailResponseBudget，详情累计正文大小预算
     * @return WorkflowProcessFormSnapshotView，提交当时的真实字段值和写入时间
     */
    private WorkflowProcessFormSnapshotView buildSubmittedFormView(String activityId,
            String historicActivityInstanceId, String taskId, FlowElement element,
            String formKey, Map<NodeFormKey, SnapshotSchema> snapshots,
            StoredSubmission submission, String deploymentId, DetailResponseBudget budget)
    {
        SnapshotSchema schema = requireSnapshotSchema(element, formKey, snapshots);
        SubmissionSnapshot submitted = submission.snapshot();
        boolean taskLocal = element instanceof UserTask && isTaskLocal(element);
        SnapshotKind expectedKind = element instanceof StartEvent
                ? SnapshotKind.START : SnapshotKind.TASK;
        if (submitted.kind() != expectedKind
                || !deploymentId.equals(submitted.deploymentId())
                || !schema.snapshot().getFormId().equals(submitted.formId())
                || !formKey.equals(submitted.formKey())
                || !element.getId().equals(submitted.nodeKey())
                || !Objects.equals(taskId, submitted.taskId())
                || taskLocal != submitted.taskLocal()
                || !Objects.equals(taskId, submission.taskId())
                || (StringUtils.hasText(submission.activityInstanceId())
                        && StringUtils.hasText(historicActivityInstanceId)
                        && !historicActivityInstanceId.equals(submission.activityInstanceId())))
        {
            throw dataError("流程表单提交快照与历史节点关系不一致");
        }
        Map<String, JsonNode> values = buildSubmittedValues(
                schema.variableNames(), submitted.values(), budget);
        return toFormView(activityId, taskId, schema.snapshot(), taskLocal, values,
                submission.submittedAt(), budget);
    }

    /**
     * 查询当前节点的不可变部署表单 schema。
     *
     * @param element FlowElement，开始节点或用户任务元素
     * @param formKey String，BPMN 表单键
     * @param snapshots Map&lt;NodeFormKey, SnapshotSchema&gt;，部署快照联合索引
     * @return SnapshotSchema，与节点和表单键完全匹配的部署快照
     */
    private SnapshotSchema requireSnapshotSchema(FlowElement element, String formKey,
            Map<NodeFormKey, SnapshotSchema> snapshots)
    {
        SnapshotSchema schema = snapshots.get(new NodeFormKey(element.getId(), formKey));
        if (schema == null)
        {
            throw dataError("流程节点缺少部署表单快照");
        }
        return schema;
    }

    /**
     * 按部署 schema 顺序复制提交值，并拒绝快照携带未声明或内部字段。
     *
     * @param allowedNames Set&lt;String&gt;，部署表单声明字段名
     * @param submittedValues Map&lt;String, JsonNode&gt;，提交快照中的安全字段值
     * @param budget DetailResponseBudget，详情累计变量 JSON 大小预算
     * @return Map&lt;String, JsonNode&gt;，仅包含 schema 字段的不可变有序映射
     */
    private Map<String, JsonNode> buildSubmittedValues(Set<String> allowedNames,
            Map<String, JsonNode> submittedValues, DetailResponseBudget budget)
    {
        for (String submittedName : submittedValues.keySet())
        {
            if (!allowedNames.contains(submittedName) || isInternalVariableName(submittedName))
            {
                throw dataError("流程表单提交快照包含未声明字段");
            }
        }
        Map<String, JsonNode> values = new LinkedHashMap<>();
        for (String allowedName : allowedNames)
        {
            JsonNode value = submittedValues.get(allowedName);
            if (value != null && !isInternalVariableName(allowedName))
            {
                JsonNode copied = value.deepCopy();
                budget.addVariableBytes(serializedSize(copied));
                values.put(allowedName, copied);
            }
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * 统一组装表单视图并累计部署表单正文预算。
     *
     * @param activityId String，BPMN 节点主键
     * @param taskId String，用户任务主键；开始节点为空
     * @param snapshot WfDeployForm，不可变部署表单快照
     * @param taskLocal boolean，业务字段是否使用任务局部作用域
     * @param values Map&lt;String, JsonNode&gt;，当前值或正式提交值
     * @param snapshotTime Instant，正式提交写入时间；活动任务为空
     * @param budget DetailResponseBudget，详情累计正文大小预算
     * @return WorkflowProcessFormSnapshotView，防御复制后的表单视图
     */
    private WorkflowProcessFormSnapshotView toFormView(String activityId, String taskId,
            WfDeployForm snapshot, boolean taskLocal, Map<String, JsonNode> values,
            Instant snapshotTime, DetailResponseBudget budget)
    {
        budget.addFormBytes(snapshot.getContent());
        return new WorkflowProcessFormSnapshotView(activityId, taskId, snapshot.getFormId(),
                snapshot.getFormKey(), snapshot.getNodeKey(), snapshot.getFormName(),
                snapshot.getNodeName(), snapshot.getContent(), taskLocal, values, snapshotTime);
    }

    /**
     * 按授权作用域和部署 schema 白名单两阶段读取活动表单当前值。
     *
     * longString、JSON 及实际使用 Blob 的 string 永不调用 Flowable getValue；普通标量也必须先
     * 与 ACT_HI_VARINST 元数据核对并确认没有字节数组关联，才允许由 Flowable 初始化值。
     *
     * @param instanceId String，已经完成对象授权的流程实例主键
     * @param taskId String，真实活动任务主键
     * @param taskLocal boolean，true 使用任务局部作用域，false 使用流程根作用域
     * @param allowedNames Set&lt;String&gt;，部署表单快照声明的字段名
     * @param source Map&lt;String, HistoricVariableInstance&gt;，对应变量作用域的 Flowable 元数据
     * @param budget DetailResponseBudget，详情累计变量 JSON 大小预算
     * @return Map&lt;String, JsonNode&gt;，按 schema 顺序返回的安全字段值
     */
    private Map<String, JsonNode> buildSafeValues(String instanceId, String taskId,
            boolean taskLocal, Set<String> allowedNames,
            Map<String, HistoricVariableInstance> source, DetailResponseBudget budget)
    {
        Map<String, HistoricVariableInstance> safeVariables = new LinkedHashMap<>();
        for (String variableName : allowedNames)
        {
            if (isInternalVariableName(variableName))
            {
                continue;
            }
            requireSafeJsonKey(variableName);
            HistoricVariableInstance variable = source.get(variableName);
            if (variable == null || !isSafeVariableType(variable.getVariableTypeName()))
            {
                // 不支持的类型只保留元数据且永不初始化，避免 serializable 或自定义类型执行反序列化。
                continue;
            }
            if (!StringUtils.hasText(variable.getId()) || variable.getId().length() > MAX_ID_LENGTH)
            {
                throw dataError("活动表单变量主键异常");
            }
            safeVariables.put(variableName, variable);
        }
        if (safeVariables.isEmpty())
        {
            return Map.of();
        }

        List<String> safeNames = List.copyOf(safeVariables.keySet());
        List<WorkflowCurrentVariableMetadataRow> metadataRows = historicVariableMapper
                .selectCurrentVariableMetadata(instanceId, taskId, taskLocal, safeNames,
                        safeNames.size() + 1);
        if (metadataRows == null || metadataRows.size() > safeNames.size())
        {
            throw dataError("活动表单变量元数据数量异常");
        }

        Map<String, WorkflowCurrentVariableMetadataRow> metadataByName = new LinkedHashMap<>();
        List<String> rawBodyIds = new ArrayList<>();
        long totalStoredBytes = 0L;
        for (WorkflowCurrentVariableMetadataRow metadata : metadataRows)
        {
            HistoricVariableInstance variable = metadata == null
                    ? null : safeVariables.get(metadata.variableName());
            validateCurrentVariableMetadata(metadata, variable, instanceId, taskId, taskLocal);
            String normalizedType = normalizeVariableType(metadata.variableTypeName());
            if (requiresControlledRawBody(metadata, normalizedType))
            {
                long storedBytes = validateCurrentRawStorage(metadata, normalizedType);
                totalStoredBytes = addBoundedStorageBytes(totalStoredBytes, storedBytes,
                        MAX_TOTAL_CURRENT_VARIABLE_STORED_BYTES,
                        "活动表单变量累计正文超过安全上限");
                rawBodyIds.add(metadata.variableId());
            }
            else
            {
                validateCurrentScalarStorage(metadata);
            }
            if (metadataByName.putIfAbsent(metadata.variableName(), metadata) != null)
            {
                throw dataError("活动表单变量名称不唯一");
            }
        }
        if (metadataByName.size() != safeVariables.size())
        {
            throw dataError("活动表单变量元数据不完整");
        }

        Map<String, WorkflowHistoricVariableBodyRow> rawBodies = rawBodyIds.isEmpty()
                ? Map.of() : loadCurrentVariableBodies(instanceId, taskId, taskLocal,
                        safeNames, rawBodyIds);
        Map<String, JsonNode> values = new LinkedHashMap<>();
        for (String variableName : allowedNames)
        {
            HistoricVariableInstance variable = safeVariables.get(variableName);
            if (variable == null)
            {
                continue;
            }
            WorkflowCurrentVariableMetadataRow metadata = metadataByName.get(variableName);
            String normalizedType = normalizeVariableType(metadata.variableTypeName());
            JsonNode safeValue = requiresControlledRawBody(metadata, normalizedType)
                    ? decodeCurrentRawValue(metadata, rawBodies.get(metadata.variableId()),
                            normalizedType)
                    : toSafeJson(variable.getValue(), 0, new SafeJsonCounter());
            if (safeValue != null)
            {
                budget.addVariableBytes(serializedSize(safeValue));
                values.put(variableName, safeValue);
            }
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * 核对数据库元数据与 Flowable 禁止初始化查询返回的变量身份和作用域。
     *
     * @param metadata WorkflowCurrentVariableMetadataRow，数据库第一阶段元数据
     * @param variable HistoricVariableInstance，同名 Flowable 变量元数据
     * @param instanceId String，已经完成对象授权的流程实例主键
     * @param taskId String，真实活动任务主键
     * @param taskLocal boolean，期望的变量作用域
     * @return 无返回值，身份、类型或作用域不一致时抛出 HTTP 500
     */
    private void validateCurrentVariableMetadata(WorkflowCurrentVariableMetadataRow metadata,
            HistoricVariableInstance variable, String instanceId, String taskId,
            boolean taskLocal)
    {
        if (metadata == null || variable == null
                || !StringUtils.hasText(metadata.variableId())
                || metadata.variableId().length() > MAX_ID_LENGTH
                || !metadata.variableId().equals(variable.getId())
                || !instanceId.equals(metadata.processInstanceId())
                || !Objects.equals(metadata.variableName(), variable.getVariableName())
                || !normalizeVariableType(metadata.variableTypeName()).equals(
                        normalizeVariableType(variable.getVariableTypeName())))
        {
            throw dataError("活动表单变量元数据关联异常");
        }
        if (taskLocal)
        {
            if (!taskId.equals(metadata.taskId()))
            {
                throw dataError("活动表单任务局部变量作用域异常");
            }
        }
        else if (metadata.taskId() != null || !instanceId.equals(metadata.executionId())
                || metadata.subScopeId() != null)
        {
            throw dataError("活动表单流程变量作用域异常");
        }
    }

    /**
     * 判断活动变量是否必须绕开 Flowable getValue 并读取受控物理正文。
     *
     * string 正常行内存储可安全由 StringType 返回；一旦出现 BYTEARRAY_ID_ 或任一 Blob
     * 统计字段，就必须按 Flowable 8 的字符串 Blob 形态处理，不能让类型名掩盖物理存储。
     *
     * @param metadata WorkflowCurrentVariableMetadataRow，数据库第一阶段存储元数据
     * @param normalizedType String，已规范化的 Flowable 变量类型名
     * @return boolean，必须执行两阶段受控正文读取时返回 true
     */
    private boolean requiresControlledRawBody(WorkflowCurrentVariableMetadataRow metadata,
            String normalizedType)
    {
        if (RAW_BODY_VARIABLE_TYPES.contains(normalizedType))
        {
            return true;
        }
        return "string".equals(normalizedType)
                && (metadata.byteArrayId() != null
                        || Integer.valueOf(1).equals(metadata.byteArrayPresent())
                        || Integer.valueOf(1).equals(metadata.byteArrayBodyPresent())
                        || metadata.storedBytes() != null);
    }

    /**
     * 校验普通标量变量没有 Blob 关联或异常辅助正文后，才允许调用 Flowable getValue。
     *
     * @param metadata WorkflowCurrentVariableMetadataRow，活动变量存储元数据
     * @return 无返回值，检测到字节正文、超限文本或列状态矛盾时抛出 HTTP 500
     */
    private void validateCurrentScalarStorage(WorkflowCurrentVariableMetadataRow metadata)
    {
        boolean textPresent = requireStorageFlag(metadata.textPresent());
        boolean text2Present = requireStorageFlag(metadata.text2Present());
        boolean byteArrayPresent = requireStorageFlag(metadata.byteArrayPresent());
        boolean byteArrayBodyPresent = requireStorageFlag(metadata.byteArrayBodyPresent());
        if (text2Present || metadata.byteArrayId() != null || byteArrayPresent
                || byteArrayBodyPresent || metadata.storedBytes() != null
                || (textPresent && (metadata.textBytes() == null || metadata.textBytes() < 0
                        || metadata.textBytes() > MAX_VARIABLE_TEXT_BYTES))
                || (!textPresent && metadata.textBytes() != null))
        {
            throw dataError("活动表单标量变量存储结构异常");
        }
    }

    /**
     * 校验 string Blob、longString、json 或 longJson 的互斥正文列、Blob 关系和单项大小。
     *
     * @param metadata WorkflowCurrentVariableMetadataRow，活动变量存储元数据
     * @param normalizedType String，已规范化的 Flowable 变量类型名
     * @return long，第二阶段将实际物化的正文存储字节数
     */
    private long validateCurrentRawStorage(WorkflowCurrentVariableMetadataRow metadata,
            String normalizedType)
    {
        boolean textPresent = requireStorageFlag(metadata.textPresent());
        boolean text2Present = requireStorageFlag(metadata.text2Present());
        boolean byteArrayPresent = requireStorageFlag(metadata.byteArrayPresent());
        boolean byteArrayBodyPresent = requireStorageFlag(metadata.byteArrayBodyPresent());
        if (text2Present)
        {
            throw dataError("活动表单变量正文存储结构异常");
        }
        if ("string".equals(normalizedType) || "longstring".equals(normalizedType))
        {
            if (textPresent || metadata.textBytes() != null
                    || !StringUtils.hasText(metadata.byteArrayId())
                    || metadata.byteArrayId().length() > MAX_ID_LENGTH
                    || !byteArrayPresent || !byteArrayBodyPresent
                    || metadata.storedBytes() == null || metadata.storedBytes() < 1
                    || metadata.storedBytes() > MAX_CURRENT_VARIABLE_SERIALIZED_BYTES)
            {
                throw dataError("活动表单字符串 Blob 存储结构异常");
            }
            return metadata.storedBytes();
        }

        boolean textStorageValid = textPresent && metadata.textBytes() != null
                && metadata.textBytes() >= 1
                && metadata.textBytes() <= MAX_CURRENT_VARIABLE_BODY_BYTES
                && metadata.byteArrayId() == null && !byteArrayPresent
                && !byteArrayBodyPresent && metadata.storedBytes() == null;
        boolean blobStorageValid = !textPresent && metadata.textBytes() == null
                && StringUtils.hasText(metadata.byteArrayId())
                && metadata.byteArrayId().length() <= MAX_ID_LENGTH
                && byteArrayPresent && byteArrayBodyPresent
                && metadata.storedBytes() != null && metadata.storedBytes() >= 1
                && metadata.storedBytes() <= MAX_CURRENT_VARIABLE_BODY_BYTES;
        if (textStorageValid == blobStorageValid)
        {
            throw dataError("活动表单 JSON 变量存储结构异常");
        }
        return textStorageValid ? metadata.textBytes() : metadata.storedBytes();
    }

    /**
     * 按授权作用域、schema 白名单和已验证变量主键分批读取活动变量正文。
     *
     * @param instanceId String，已经完成对象授权的流程实例主键
     * @param taskId String，真实活动任务主键
     * @param taskLocal boolean，期望的变量作用域
     * @param variableNames List&lt;String&gt;，部署表单 schema 白名单内的安全变量名
     * @param rowIds List&lt;String&gt;，通过第一阶段元数据和容量门禁的变量主键
     * @return Map&lt;String, WorkflowHistoricVariableBodyRow&gt;，与输入主键一一对应的正文索引
     */
    private Map<String, WorkflowHistoricVariableBodyRow> loadCurrentVariableBodies(
            String instanceId, String taskId, boolean taskLocal, List<String> variableNames,
            List<String> rowIds)
    {
        Map<String, WorkflowHistoricVariableBodyRow> bodies = new LinkedHashMap<>();
        for (int offset = 0; offset < rowIds.size(); offset += VARIABLE_BODY_QUERY_BATCH_SIZE)
        {
            int end = Math.min(offset + VARIABLE_BODY_QUERY_BATCH_SIZE, rowIds.size());
            List<String> batch = List.copyOf(rowIds.subList(offset, end));
            List<WorkflowHistoricVariableBodyRow> batchRows = historicVariableMapper
                    .selectCurrentVariableBodies(instanceId, taskId, taskLocal,
                            variableNames, batch);
            indexBodyRows(batchRows, new LinkedHashSet<>(batch), bodies,
                    "活动表单变量正文关联异常");
        }
        if (bodies.size() != rowIds.size())
        {
            throw dataError("活动表单变量正文关联异常");
        }
        return Collections.unmodifiableMap(bodies);
    }

    /**
     * 解码并再次核验活动变量正文，字符串 Blob 只允许单一 String，JSON 使用严格解析器。
     *
     * @param metadata WorkflowCurrentVariableMetadataRow，第一阶段已校验元数据
     * @param body WorkflowHistoricVariableBodyRow，第二阶段受控正文
     * @param normalizedType String，已规范化的 Flowable 变量类型名
     * @return JsonNode，完成大小、结构和危险键门禁的当前变量值
     */
    private JsonNode decodeCurrentRawValue(WorkflowCurrentVariableMetadataRow metadata,
            WorkflowHistoricVariableBodyRow body, String normalizedType)
    {
        if (body == null || !metadata.variableId().equals(body.rowId()))
        {
            throw dataError("活动表单变量正文关联异常");
        }
        if ("string".equals(normalizedType) || "longstring".equals(normalizedType))
        {
            byte[] serialized = body.storedBytes();
            if (body.storedText() != null || serialized == null
                    || serialized.length != metadata.storedBytes())
            {
                throw dataError("活动表单字符串 Blob 正文异常");
            }
            return toSafeJson(deserializeStoredString(serialized), 0,
                    new SafeJsonCounter());
        }

        JsonNode parsed;
        try
        {
            if (metadata.textPresent() == 1)
            {
                String json = body.storedText();
                if (json == null || body.storedBytes() != null
                        || json.getBytes(StandardCharsets.UTF_8).length != metadata.textBytes())
                {
                    throw dataError("活动表单 JSON 变量正文异常");
                }
                parsed = safeJsonMapper.readTree(json);
            }
            else
            {
                byte[] json = body.storedBytes();
                if (body.storedText() != null || json == null
                        || json.length != metadata.storedBytes())
                {
                    throw dataError("活动表单 JSON 变量正文异常");
                }
                parsed = safeJsonMapper.readTree(json);
            }
        }
        catch (IOException exception)
        {
            ServiceException failure = dataError("活动表单 JSON 变量正文损坏");
            failure.initCause(exception);
            throw failure;
        }
        if (parsed == null)
        {
            throw dataError("活动表单 JSON 变量正文损坏");
        }
        JsonNode safe = toSafeJson(parsed, 0, new SafeJsonCounter());
        if (safe == null)
        {
            throw dataError("活动表单 JSON 变量包含不支持的节点");
        }
        return safe;
    }

    /**
     * 判断变量是否属于引擎固定字段或工作流服务端保留命名空间。
     *
     * @param variableName String，部署 schema 或历史变量中的字段名
     * @return boolean，不得向详情响应暴露时返回 true
     */
    private boolean isInternalVariableName(String variableName)
    {
        return INTERNAL_VARIABLE_NAMES.contains(variableName)
                || WorkflowFormSubmissionSnapshotCodec.isReservedVariableName(variableName);
    }

    /**
     * 判断 Flowable 变量类型是否允许安全读取值。
     *
     * @param variableTypeName String，Flowable 变量类型名
     * @return boolean，仅标量、时间和 JSON 类型返回 true
     */
    private boolean isSafeVariableType(String variableTypeName)
    {
        return SAFE_VARIABLE_TYPES.contains(normalizeVariableType(variableTypeName));
    }

    /**
     * 将 Flowable 变量类型名规范化为稳定的小写比较值。
     *
     * @param variableTypeName String，数据库或 Flowable API 返回的变量类型名
     * @return String，去除首尾空白并使用 ROOT locale 转小写；空值返回空字符串
     */
    private String normalizeVariableType(String variableTypeName)
    {
        return StringUtils.hasText(variableTypeName)
                ? variableTypeName.trim().toLowerCase(Locale.ROOT) : "";
    }

    /**
     * 把数据库 0/1 存储状态转换为布尔值，并拒绝空值或其他统计结果。
     *
     * @param flag Integer，SQL CASE 返回的存储状态
     * @return boolean，1 返回 true，0 返回 false
     */
    private boolean requireStorageFlag(Integer flag)
    {
        if (flag == null || (flag != 0 && flag != 1))
        {
            throw dataError("流程变量存储统计异常");
        }
        return flag == 1;
    }

    /**
     * 在不发生 long 溢出的前提下累计正文大小并执行固定上限门禁。
     *
     * @param current long，已经累计的正文存储字节数
     * @param addition long，本行将新增的正文存储字节数
     * @param maximum long，当前读取场景允许的最大累计字节数
     * @param message String，超过上限时返回的稳定数据异常提示
     * @return long，完成上限校验后的新累计值
     */
    private long addBoundedStorageBytes(long current, long addition, long maximum,
            String message)
    {
        if (current < 0 || addition < 0 || maximum < 0 || addition > maximum - current)
        {
            throw dataError(message);
        }
        return current + addition;
    }

    /**
     * 校验一批正文查询结果只包含期望主键，且每个主键最多出现一次。
     *
     * @param rows List&lt;WorkflowHistoricVariableBodyRow&gt;，Mapper 返回的正文行
     * @param expectedIds Set&lt;String&gt;，本批第一阶段已验证主键集合
     * @param target Map&lt;String, WorkflowHistoricVariableBodyRow&gt;，跨批次正文唯一索引
     * @param message String，关联异常时返回的稳定提示
     * @return 无返回值，空结果、越界主键或重复主键均抛出 HTTP 500
     */
    private void indexBodyRows(List<WorkflowHistoricVariableBodyRow> rows,
            Set<String> expectedIds, Map<String, WorkflowHistoricVariableBodyRow> target,
            String message)
    {
        if (rows == null || rows.size() != expectedIds.size())
        {
            throw dataError(message);
        }
        for (WorkflowHistoricVariableBodyRow row : rows)
        {
            if (row == null || !StringUtils.hasText(row.rowId())
                    || !expectedIds.contains(row.rowId())
                    || target.putIfAbsent(row.rowId(), row) != null)
            {
                throw dataError(message);
            }
        }
    }

    /**
     * 将已通过 Flowable 类型门禁的值递归转换为有深度和规模限制的 JSON 节点。
     *
     * @param value Object，标量、时间、JsonNode、Map 或 Collection 值
     * @param depth int，当前递归深度
     * @param counter SafeJsonCounter，单个变量共享的 JSON 节点计数器
     * @return JsonNode，安全 JSON；不支持的运行时类型返回 null
     */
    private JsonNode toSafeJson(Object value, int depth, SafeJsonCounter counter)
    {
        if (depth > MAX_VARIABLE_DEPTH)
        {
            throw dataError("表单变量 JSON 深度超过安全上限");
        }
        counter.increment();
        if (counter.value() > MAX_VARIABLE_NODES)
        {
            throw dataError("表单变量 JSON 节点数超过安全上限");
        }
        if (value == null)
        {
            return NullNode.getInstance();
        }
        if (value instanceof JsonNode node)
        {
            return sanitizeJsonNode(node, depth, counter);
        }
        if (value instanceof CharSequence text)
        {
            requireSafeText(text.toString());
            return TextNode.valueOf(text.toString());
        }
        if (value instanceof Boolean bool)
        {
            return BooleanNode.valueOf(bool);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long)
        {
            return LongNode.valueOf(((Number) value).longValue());
        }
        if (value instanceof BigInteger integer)
        {
            return BigIntegerNode.valueOf(integer);
        }
        if (value instanceof BigDecimal decimal)
        {
            return DecimalNode.valueOf(decimal);
        }
        if (value instanceof Float || value instanceof Double)
        {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number))
            {
                return null;
            }
            return DoubleNode.valueOf(number);
        }
        if (value instanceof Date date)
        {
            return TextNode.valueOf(date.toInstant().toString());
        }
        if (value instanceof Instant || value instanceof LocalDate
                || value instanceof LocalDateTime || value instanceof OffsetDateTime
                || value instanceof ZonedDateTime || value instanceof UUID)
        {
            return TextNode.valueOf(value.toString());
        }
        if (value instanceof Map<?, ?> map)
        {
            if (map.size() > MAX_VARIABLE_CONTAINER_SIZE)
            {
                throw dataError("表单变量 JSON 对象成员过多");
            }
            ObjectNode object = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<?, ?> entry : map.entrySet())
            {
                if (!(entry.getKey() instanceof String key))
                {
                    return null;
                }
                requireSafeJsonKey(key);
                JsonNode child = toSafeJson(entry.getValue(), depth + 1, counter);
                if (child == null)
                {
                    return null;
                }
                object.set(key, child);
            }
            return object;
        }
        if (value instanceof Collection<?> collection)
        {
            if (collection.size() > MAX_VARIABLE_CONTAINER_SIZE)
            {
                throw dataError("表单变量 JSON 数组成员过多");
            }
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            for (Object item : collection)
            {
                JsonNode child = toSafeJson(item, depth + 1, counter);
                if (child == null)
                {
                    return null;
                }
                array.add(child);
            }
            return array;
        }
        return null;
    }

    /**
     * 深复制并限制 Flowable JSON 变量返回的原生 JsonNode。
     *
     * @param node JsonNode，待安全复制的 JSON 节点
     * @param depth int，当前递归深度
     * @param counter SafeJsonCounter，单个变量共享的节点计数器
     * @return JsonNode，完成深度、成员数和文本大小门禁的副本
     */
    private JsonNode sanitizeJsonNode(JsonNode node, int depth, SafeJsonCounter counter)
    {
        if (node.isFloatingPointNumber())
        {
            // Jackson 节点可以承载非标准 JSON 浮点值，必须在详情序列化前拒绝 NaN 和 Infinity。
            return Double.isFinite(node.doubleValue()) ? node.deepCopy() : null;
        }
        if (node.isNull() || node.isBoolean() || node.isIntegralNumber())
        {
            return node.deepCopy();
        }
        if (node.isTextual())
        {
            requireSafeText(node.textValue());
            return TextNode.valueOf(node.textValue());
        }
        if (node.isBinary() || node.isPojo() || node.isMissingNode())
        {
            return null;
        }
        if (node.isArray())
        {
            if (node.size() > MAX_VARIABLE_CONTAINER_SIZE)
            {
                throw dataError("表单变量 JSON 数组成员过多");
            }
            ArrayNode copied = JsonNodeFactory.instance.arrayNode();
            for (JsonNode child : node)
            {
                JsonNode safeChild = toSafeJson(child, depth + 1, counter);
                if (safeChild == null)
                {
                    return null;
                }
                copied.add(safeChild);
            }
            return copied;
        }
        if (node.isObject())
        {
            if (node.size() > MAX_VARIABLE_CONTAINER_SIZE)
            {
                throw dataError("表单变量 JSON 对象成员过多");
            }
            ObjectNode copied = JsonNodeFactory.instance.objectNode();
            node.properties().forEach(entry ->
            {
                requireSafeJsonKey(entry.getKey());
                JsonNode safeChild = toSafeJson(entry.getValue(), depth + 1, counter);
                if (safeChild == null)
                {
                    throw dataError("表单变量包含不支持的 JSON 节点");
                }
                copied.set(entry.getKey(), safeChild);
            });
            return copied;
        }
        return null;
    }

    /**
     * 校验变量文本或 JSON 键的 UTF-8 大小。
     *
     * @param value String，待校验文本
     * @return 无返回值，超过单值上限时拒绝整个详情
     */
    private void requireSafeText(String value)
    {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_VARIABLE_TEXT_BYTES)
        {
            throw dataError("表单变量文本超过安全上限");
        }
    }

    /**
     * 校验 JSON 对象键的大小并递归拒绝可改变前端对象原型语义的危险名称。
     *
     * @param key String，Map 或 JsonNode 任意层级的对象键
     * @return 无返回值，空键、超限键或原型污染键会拒绝整个详情
     */
    private void requireSafeJsonKey(String key)
    {
        if (!StringUtils.hasText(key)
                || FORBIDDEN_JSON_KEYS.contains(key.toLowerCase(Locale.ROOT)))
        {
            throw dataError("表单变量 JSON 对象字段名异常");
        }
        requireSafeText(key);
    }

    /**
     * 计算安全 JsonNode 的真实 UTF-8 序列化大小。
     *
     * @param node JsonNode，已经过结构门禁的变量值
     * @return int，JSON UTF-8 字节数
     */
    private int serializedSize(JsonNode node)
    {
        try
        {
            return safeJsonMapper.writeValueAsBytes(node).length;
        }
        catch (JsonProcessingException exception)
        {
            throw dataError("表单变量 JSON 序列化失败");
        }
    }

    /**
     * 构建开始、用户任务和结束节点时间线，实际完成人优先使用 completedBy。
     *
     * @param activities List&lt;HistoricActivityInstance&gt;，实例全部历史活动
     * @param tasksById Map&lt;String, HistoricTaskInstance&gt;，历史任务主键索引
     * @param commentsByTask Map&lt;String, List&lt;WorkflowProcessCommentView&gt;&gt;，受控意见索引
     * @param instance WorkflowProcessAccessSnapshot，实例授权快照
     * @param startUserName String，发起人显示名称
     * @param userNames Map&lt;String, String&gt;，详情内用户名缓存
     * @return List&lt;WorkflowProcessActivityView&gt;，按执行时间排序的业务时间线
     */
    private List<WorkflowProcessActivityView> buildTimeline(
            List<HistoricActivityInstance> activities,
            Map<String, HistoricTaskInstance> tasksById,
            Map<String, List<WorkflowProcessCommentView>> commentsByTask,
            WorkflowProcessAccessSnapshot instance, String startUserName,
            Map<String, String> userNames)
    {
        List<WorkflowProcessActivityView> timeline = new ArrayList<>();
        for (HistoricActivityInstance activity : activities)
        {
            String type = activity.getActivityType();
            if (!"startEvent".equals(type) && !"userTask".equals(type)
                    && !"endEvent".equals(type))
            {
                continue;
            }
            HistoricTaskInstance task = StringUtils.hasText(activity.getTaskId())
                    ? tasksById.get(activity.getTaskId()) : null;
            if ("userTask".equals(type) && task == null)
            {
                throw dataError("用户任务活动缺少历史任务数据");
            }
            String assigneeId = "startEvent".equals(type) ? instance.startUserId()
                    : firstText(task == null ? null : task.getAssignee(), activity.getAssignee());
            String completedById = firstText(task == null ? null : task.getCompletedBy(),
                    activity.getCompletedBy());
            String assigneeName = "startEvent".equals(type) ? startUserName
                    : resolveUserName(assigneeId, userNames);
            String completedByName = resolveUserName(completedById, userNames);
            List<WorkflowCandidateIdentityView> candidates = task == null
                    ? List.of() : extractCandidates(task);
            List<WorkflowProcessCommentView> comments = task == null ? List.of()
                    : commentsByTask.getOrDefault(task.getId(), List.of());
            String deleteReason = firstText(task == null ? null : task.getDeleteReason(),
                    activity.getDeleteReason());
            timeline.add(new WorkflowProcessActivityView(activity.getActivityId(),
                    activity.getActivityName(), type, activity.getTaskId(), assigneeId,
                    assigneeName, completedById, completedByName, candidates, comments,
                    toInstant(activity.getStartTime()), toInstant(activity.getEndTime()),
                    activity.getDurationInMillis(), deleteReason));
        }
        return List.copyOf(timeline);
    }

    /**
     * 从预加载或历史 API 中提取候选用户与候选组，不暴露其他 identity link 类型。
     *
     * @param task HistoricTaskInstance，已完成实例关系核验的历史任务
     * @return List&lt;WorkflowCandidateIdentityView&gt;，去重后的候选身份集合
     */
    private List<WorkflowCandidateIdentityView> extractCandidates(HistoricTaskInstance task)
    {
        List<? extends IdentityLinkInfo> links = task.getIdentityLinks();
        if (links == null)
        {
            links = historyService.getHistoricIdentityLinksForTask(task.getId());
        }
        if (links == null || links.size() > MAX_CANDIDATES_PER_TASK)
        {
            throw dataError("任务候选身份数量超过安全上限");
        }
        Set<WorkflowCandidateIdentityView> candidates = new LinkedHashSet<>();
        for (IdentityLinkInfo link : links)
        {
            if (link == null || !"candidate".equals(link.getType()))
            {
                continue;
            }
            if (StringUtils.hasText(link.getUserId()))
            {
                candidates.add(new WorkflowCandidateIdentityView("user",
                        safeIdentity(link.getUserId())));
            }
            if (StringUtils.hasText(link.getGroupId()))
            {
                candidates.add(new WorkflowCandidateIdentityView("group",
                        safeIdentity(link.getGroupId())));
            }
        }
        return List.copyOf(candidates);
    }

    /**
     * 验证候选身份长度并去除首尾空白。
     *
     * @param identity String，Flowable 用户或组身份
     * @return String，可安全回显的身份文本
     */
    private String safeIdentity(String identity)
    {
        String normalized = identity.trim();
        if (normalized.length() > MAX_ID_LENGTH)
        {
            throw dataError("任务候选身份长度异常");
        }
        return normalized;
    }

    /**
     * 根据全部历史活动和任务意见构建 Viewer 状态集合。
     *
     * @param activities List&lt;HistoricActivityInstance&gt;，实例全部历史活动
     * @param tasksById Map&lt;String, HistoricTaskInstance&gt;，历史任务主键索引
     * @param commentsByTask Map&lt;String, List&lt;WorkflowProcessCommentView&gt;&gt;，受控意见索引
     * @return WorkflowProcessViewerView，已完成、未完成、驳回和退回活动集合
     */
    private WorkflowProcessViewerView buildViewer(List<HistoricActivityInstance> activities,
            Map<String, HistoricTaskInstance> tasksById,
            Map<String, List<WorkflowProcessCommentView>> commentsByTask)
    {
        Set<String> finishedActivities = new LinkedHashSet<>();
        Set<String> finishedSequenceFlows = new LinkedHashSet<>();
        Set<String> unfinishedActivities = new LinkedHashSet<>();
        Set<String> rejectedActivities = new LinkedHashSet<>();
        Set<String> returnedActivities = new LinkedHashSet<>();
        for (HistoricActivityInstance activity : activities)
        {
            if (activity.getEndTime() == null)
            {
                unfinishedActivities.add(activity.getActivityId());
            }
            else if (SequenceFlow.class.getSimpleName().equalsIgnoreCase(activity.getActivityType())
                    || "sequenceFlow".equals(activity.getActivityType()))
            {
                finishedSequenceFlows.add(activity.getActivityId());
            }
            else
            {
                finishedActivities.add(activity.getActivityId());
            }
        }
        tasksById.values().forEach(task ->
        {
            List<WorkflowProcessCommentView> taskComments =
                    commentsByTask.getOrDefault(task.getId(), List.of());
            if (taskComments.stream().anyMatch(comment -> "2".equals(comment.type()))
                    || containsAction(task.getDeleteReason(), "return", "退回"))
            {
                returnedActivities.add(task.getTaskDefinitionKey());
            }
            if (taskComments.stream().anyMatch(comment -> "3".equals(comment.type()))
                    || containsAction(task.getDeleteReason(), "reject", "驳回"))
            {
                rejectedActivities.add(task.getTaskDefinitionKey());
            }
        });
        rejectedActivities.remove(null);
        returnedActivities.remove(null);
        return new WorkflowProcessViewerView(finishedActivities, finishedSequenceFlows,
                unfinishedActivities, rejectedActivities, returnedActivities);
    }

    /**
     * 判断删除原因是否包含指定英文或中文业务动作。
     *
     * @param value String，Flowable 任务删除原因，允许为空
     * @param englishToken String，英文动作关键字
     * @param chineseToken String，中文动作关键字
     * @return boolean，忽略英文大小写后命中任一关键字时返回 true
     */
    private boolean containsAction(String value, String englishToken, String chineseToken)
    {
        return StringUtils.hasText(value)
                && (value.toLowerCase(Locale.ROOT).contains(englishToken)
                        || value.contains(chineseToken));
    }

    /**
     * 读取开始节点或用户任务的表单键。
     *
     * @param element FlowElement，BPMN 开始节点或用户任务
     * @return String，节点表单键；未配置时为空
     */
    private String formKey(FlowElement element)
    {
        if (element instanceof StartEvent startEvent)
        {
            return startEvent.getFormKey();
        }
        if (element instanceof UserTask userTask)
        {
            return userTask.getFormKey();
        }
        return null;
    }

    /**
     * 判断用户任务是否要求从任务局部变量回显表单值。
     *
     * @param element FlowElement，BPMN 用户任务元素
     * @return boolean，localScope 属性为 true 或 1 时返回 true
     */
    private boolean isTaskLocal(FlowElement element)
    {
        Map<String, List<ExtensionAttribute>> attributes = element.getAttributes();
        if (attributes == null)
        {
            return false;
        }
        List<ExtensionAttribute> localScope = attributes.get("localScope");
        if (localScope == null || localScope.isEmpty() || localScope.get(0) == null)
        {
            return false;
        }
        String value = localScope.get(0).getValue();
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    /**
     * 查询历史用户当前显示名称，用户已删除或主键异常时保留原始主键。
     *
     * @param userId String，Flowable 历史用户主键，允许为空
     * @param cache Map&lt;String, String&gt;，单次详情用户名缓存
     * @return String，用户昵称、原始主键或 null
     */
    private String resolveUserName(String userId, Map<String, String> cache)
    {
        if (!StringUtils.hasText(userId))
        {
            return null;
        }
        if (cache.containsKey(userId))
        {
            return cache.get(userId);
        }
        String displayName = userId;
        try
        {
            SysUser user = userService.selectUserById(Long.valueOf(userId));
            if (user != null && StringUtils.hasText(user.getNickName()))
            {
                displayName = user.getNickName();
            }
        }
        catch (NumberFormatException ignored)
        {
            // 存量异常身份保留原值，避免猜测名称并便于迁移问题追踪。
        }
        cache.put(userId, displayName);
        return displayName;
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
     * 返回第一个非空文本，不改变历史值内容。
     *
     * @param primary String，优先值
     * @param fallback String，备用值
     * @return String，第一个非空值或 null
     */
    private String firstText(String primary, String fallback)
    {
        return StringUtils.hasText(primary) ? primary
                : StringUtils.hasText(fallback) ? fallback : null;
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
     * 将可空 Date 转为不可变 Instant。
     *
     * @param value Date，Flowable 历史时间，允许为空
     * @return Instant，不可变时间或 null
     */
    private Instant toInstant(Date value)
    {
        return value == null ? null : value.toInstant();
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

    /**
     * 部署快照联合主键。
     *
     * @param nodeKey String，BPMN 节点主键
     * @param formKey String，BPMN 表单键
     */
    private record NodeFormKey(String nodeKey, String formKey)
    {
    }

    /**
     * 不可变部署快照及从 schema 提取的字段白名单。
     *
     * @param snapshot WfDeployForm，部署时固化的快照实体
     * @param variableNames Set&lt;String&gt;，表单组件声明的字段名
     */
    private record SnapshotSchema(WfDeployForm snapshot, Set<String> variableNames)
    {
    }

    /**
     * 当前变量元数据与正式提交快照索引。
     *
     * @param processVariables Map&lt;String, HistoricVariableInstance&gt;，当前流程变量名索引
     * @param taskVariables Map&lt;String, Map&lt;String, HistoricVariableInstance&gt;&gt;，当前任务局部变量索引
     * @param startSubmission StoredSubmission，开始表单正式提交快照；旧实例允许为空
     * @param taskSubmissions Map&lt;String, StoredSubmission&gt;，按真实任务主键索引的正式提交快照
     */
    private record VariableStore(
            Map<String, HistoricVariableInstance> processVariables,
            Map<String, Map<String, HistoricVariableInstance>> taskVariables,
            StoredSubmission startSubmission,
            Map<String, StoredSubmission> taskSubmissions)
    {
    }

    /**
     * 历史变量查询解析后的提交快照索引。
     *
     * @param startSubmission StoredSubmission，唯一开始提交；旧实例允许为空
     * @param taskSubmissions Map&lt;String, StoredSubmission&gt;，任务主键到唯一提交的映射
     */
    private record SubmissionSnapshotIndex(StoredSubmission startSubmission,
            Map<String, StoredSubmission> taskSubmissions)
    {
    }

    /**
     * 提交快照正文及其不可伪造的 Flowable 历史关联元数据。
     *
     * @param snapshot SubmissionSnapshot，已严格解码的服务端快照正文
     * @param submittedAt Instant，HistoricVariableUpdate 的真实写入时间
     * @param detailId String，历史变量更新唯一主键
     * @param activityInstanceId String，Flowable 记录的活动实例主键；允许为空
     * @param taskId String，任务局部历史关联主键；开始快照为空
     */
    private record StoredSubmission(SubmissionSnapshot snapshot, Instant submittedAt,
            String detailId, String activityInstanceId, String taskId)
    {
    }

    /** 单个变量 JSON 节点计数器。 */
    private static final class SafeJsonCounter
    {
        /** 当前已访问 JSON 节点数。 */
        private int value;

        /**
         * 增加一个已访问 JSON 节点。
         *
         * @return 无返回值
         */
        private void increment()
        {
            value++;
        }

        /**
         * 获取当前已访问 JSON 节点数。
         *
         * @return int，JSON 节点数
         */
        private int value()
        {
            return value;
        }
    }

    /** 单次详情响应的表单和变量累计正文预算。 */
    private final class DetailResponseBudget
    {
        /** 已计入响应的表单 JSON 字节数。 */
        private int formBytes;

        /** 已计入响应的变量 JSON 字节数。 */
        private int variableBytes;

        /**
         * 累加一次实际会进入响应的表单快照正文大小。
         *
         * @param content String，部署表单快照 JSON
         * @return 无返回值，累计超过上限时拒绝详情
         */
        private void addFormBytes(String content)
        {
            formBytes = checkedAdd(formBytes, content.getBytes(StandardCharsets.UTF_8).length,
                    MAX_TOTAL_FORM_BYTES, "流程详情表单正文总量超过安全上限");
        }

        /**
         * 累加一次实际会进入响应的变量 JSON 大小。
         *
         * @param bytes int，安全变量 JSON 字节数
         * @return 无返回值，累计超过上限时拒绝详情
         */
        private void addVariableBytes(int bytes)
        {
            variableBytes = checkedAdd(variableBytes, bytes, MAX_TOTAL_VARIABLE_BYTES,
                    "流程详情变量正文总量超过安全上限");
        }

        /**
         * 防溢出累加响应正文预算。
         *
         * @param current int，当前累计字节数
         * @param added int，本次新增字节数
         * @param limit int，允许的累计上限
         * @param message String，超限时稳定提示
         * @return int，新的累计字节数
         */
        private int checkedAdd(int current, int added, int limit, String message)
        {
            if (added < 0 || current > limit - added)
            {
                throw dataError(message);
            }
            return current + added;
        }
    }
}

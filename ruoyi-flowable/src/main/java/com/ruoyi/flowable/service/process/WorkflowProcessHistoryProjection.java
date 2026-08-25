package com.ruoyi.flowable.service.process;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.task.Comment;
import org.flowable.identitylink.api.IdentityLinkInfo;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessSnapshot;
import com.ruoyi.flowable.domain.vo.WorkflowCandidateIdentityView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessActivityView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessCommentView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessRelationView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessViewerView;
import com.ruoyi.system.service.ISysUserService;

/**
 * 已授权流程详情的历史活动、任务、意见、时间线、Viewer 和父子关系投影组件。
 *
 * 该组件不建立事务或执行权限判断，只接受详情服务已经完成对象授权的实例快照，并沿用调用方
 * 建立的同一只读事务。所有历史读取均有固定数量和正文预算，关联漂移时失败关闭。
 */
@Component
public class WorkflowProcessHistoryProjection
{
    /** 单个详情允许读取的最大历史活动数。 */
    static final int MAX_ACTIVITY_ROWS = 1000;

    /** 单个详情允许读取的最大历史任务数。 */
    static final int MAX_TASK_ROWS = 500;

    /** 单个详情允许投影的父子流程实例总数。 */
    static final int MAX_PROCESS_RELATION_ROWS = 200;

    /** 单个详情允许读取的最大原始意见数。 */
    static final int MAX_COMMENT_ROWS = 1000;

    /** 单个任务允许返回的最大候选身份数。 */
    static final int MAX_CANDIDATES_PER_TASK = 100;

    /** 单条审批意见正文最大 UTF-8 字节数。 */
    static final int MAX_COMMENT_BYTES = 8 * 1024;

    /** 单个详情全部意见正文最大 UTF-8 字节数。 */
    static final int MAX_TOTAL_COMMENT_BYTES = 512 * 1024;

    /** 引擎身份字段的最大字符数。 */
    private static final int MAX_ID_LENGTH = 255;

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

    private final HistoryService historyService;

    private final TaskService taskService;

    private final RepositoryService repositoryService;

    private final ISysUserService userService;

    /** 严格拒绝重复字段和根节点后尾随内容的意见审计 JSON 解析器。 */
    private final ObjectMapper safeJsonMapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /**
     * 创建流程详情历史投影组件。
     *
     * @param historyService HistoryService，历史实例、活动、任务和候选身份公共 API
     * @param taskService TaskService，流程意见公共 API
     * @param repositoryService RepositoryService，父子实例引用定义查询 API
     * @param userService ISysUserService，历史用户显示名称查询服务
     * @return 无返回值，构造后由 Spring 管理该组件
     */
    public WorkflowProcessHistoryProjection(HistoryService historyService,
            TaskService taskService, RepositoryService repositoryService,
            ISysUserService userService)
    {
        this.historyService = historyService;
        this.taskService = taskService;
        this.repositoryService = repositoryService;
        this.userService = userService;
    }

    /**
     * 按原有固定顺序读取活动、任务和意见，并建立供表单与历史展示共同使用的不可变索引。
     *
     * @param instance WorkflowProcessAccessSnapshot，详情服务已经完成对象授权的实例快照
     * @return HistoryData，有界活动、任务索引和受控意见索引
     */
    HistoryData loadHistory(WorkflowProcessAccessSnapshot instance)
    {
        List<HistoricActivityInstance> activities = loadActivities(instance);
        List<HistoricTaskInstance> tasks = loadTasks(instance);
        Map<String, HistoricTaskInstance> tasksById = indexTasks(tasks);
        Map<String, List<WorkflowProcessCommentView>> commentsByTask =
                loadComments(instance.processInstanceId(), tasksById.keySet());
        return new HistoryData(activities, tasksById, commentsByTask);
    }

    /**
     * 从同一份历史数据生成发起人名称、业务时间线和 Viewer 状态，避免详情编排层理解历史结构。
     *
     * @param history HistoryData，已经完成数量与关联门禁的历史数据
     * @param instance WorkflowProcessAccessSnapshot，已授权实例快照
     * @param applicationReturned boolean，当前是否仍处于申请人退回修改阶段
     * @return HistoryPresentation，用户名称、时间线和 Viewer 投影
     */
    HistoryPresentation projectPresentation(HistoryData history,
            WorkflowProcessAccessSnapshot instance, boolean applicationReturned)
    {
        Map<String, String> userNames = new HashMap<>();
        String startUserName = resolveUserName(instance.startUserId(), userNames);
        List<WorkflowProcessActivityView> timeline = buildTimeline(history.activities(),
                history.tasksById(), history.commentsByTask(), instance,
                startUserName, userNames);
        WorkflowProcessViewerView viewer = buildViewer(history.activities(),
                history.tasksById(), history.commentsByTask(), applicationReturned);
        return new HistoryPresentation(startUserName, timeline, viewer);
    }

    /**
     * 从 Flowable 历史实例表构建并核对同一根执行树的父子流程关系。
     *
     * @param requestedInstanceId String，已通过对象授权的详情实例主键
     * @return List&lt;WorkflowProcessRelationView&gt;，按开始时间和实例主键稳定排序的关系节点
     */
    List<WorkflowProcessRelationView> buildProcessRelations(String requestedInstanceId)
    {
        HistoricProcessInstance requested = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(requestedInstanceId).singleResult();
        if (requested == null || !StringUtils.hasText(requested.getId()))
        {
            throw dataError("流程实例关系记录不存在");
        }
        // 历史 API 不暴露 rootProcessInstanceId，先沿 superProcessInstanceId 向上追溯唯一根节点。
        HistoricProcessInstance root = requested;
        LinkedHashSet<String> ancestors = new LinkedHashSet<>();
        ancestors.add(requested.getId().trim());
        while (StringUtils.hasText(root.getSuperProcessInstanceId()))
        {
            String parentId = root.getSuperProcessInstanceId().trim();
            if (!ancestors.add(parentId) || ancestors.size() > MAX_PROCESS_RELATION_ROWS)
            {
                throw dataError("流程父子关系存在循环或规模超过详情上限");
            }
            root = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(parentId).singleResult();
            if (root == null || !parentId.equals(root.getId()))
            {
                throw dataError("流程父实例关系记录不存在");
            }
        }
        String rootInstanceId = root.getId().trim();

        // 再从根按直接父实例广度遍历，覆盖运行中和已结束子流程并拒绝静默截断。
        LinkedHashMap<String, HistoricProcessInstance> instancesById = new LinkedHashMap<>();
        instancesById.put(rootInstanceId, root);
        List<String> pendingParents = new ArrayList<>();
        pendingParents.add(rootInstanceId);
        for (int index = 0; index < pendingParents.size(); index++)
        {
            String parentId = pendingParents.get(index);
            int remainingCapacity = MAX_PROCESS_RELATION_ROWS - instancesById.size();
            List<HistoricProcessInstance> children = historyService.createHistoricProcessInstanceQuery()
                    .superProcessInstanceId(parentId).listPage(0, remainingCapacity + 1);
            if (children == null || children.size() > remainingCapacity)
            {
                throw dataError("流程父子关系规模超过详情上限");
            }
            for (HistoricProcessInstance child : children)
            {
                if (child == null || !StringUtils.hasText(child.getId())
                        || !parentId.equals(child.getSuperProcessInstanceId())
                        || instancesById.putIfAbsent(child.getId().trim(), child) != null)
                {
                    throw dataError("流程父子关系存在空值、重复或父实例不一致");
                }
                pendingParents.add(child.getId().trim());
            }
        }
        if (!instancesById.containsKey(requestedInstanceId))
        {
            throw dataError("流程详情实例不属于返回的父子执行树");
        }

        List<WorkflowProcessRelationView> result = new ArrayList<>();
        for (HistoricProcessInstance instance : instancesById.values())
        {
            String instanceId = instance.getId().trim();
            String parentId = StringUtils.hasText(instance.getSuperProcessInstanceId())
                    ? instance.getSuperProcessInstanceId().trim() : null;
            boolean rootNode = rootInstanceId.equals(instanceId);
            if ((rootNode && parentId != null) || (!rootNode
                    && (parentId == null || !instancesById.containsKey(parentId))))
            {
                throw dataError("流程父子实例关系不完整");
            }
            ProcessDefinition relationDefinition = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionId(instance.getProcessDefinitionId()).singleResult();
            if (relationDefinition == null)
            {
                throw dataError("流程父子关系引用的定义不存在");
            }
            result.add(new WorkflowProcessRelationView(instanceId, parentId, rootInstanceId,
                    relationDefinition.getId(), relationDefinition.getKey(), relationDefinition.getName(),
                    relationDefinition.getVersion(), instance.getBusinessKey(),
                    WorkflowProcessStatusNormalizer.normalize(instance.getBusinessStatus(),
                            instance.getState(), toInstant(instance.getEndTime()), instance.getDeleteReason()),
                    toInstant(instance.getStartTime()), toInstant(instance.getEndTime()),
                    requestedInstanceId.equals(instanceId)));
        }
        result.sort(Comparator.comparing(WorkflowProcessRelationView::startTime,
                Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(WorkflowProcessRelationView::processInstanceId));
        return List.copyOf(result);
    }

    /**
     * 沿 Flowable 历史父子关系收集当前子流程的祖先部署，用于识别 inheritVariables 复制的内部快照。
     *
     * @param instanceId String，已经完成对象授权的当前流程实例主键
     * @return Set&lt;String&gt;，从直接父实例到根实例的唯一部署主键；根实例返回空集合
     */
    Set<String> loadAncestorDeploymentIds(String instanceId)
    {
        HistoricProcessInstance current = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(instanceId).singleResult();
        if (current == null || !instanceId.equals(current.getId()))
        {
            throw dataError("流程实例关系记录不存在");
        }
        LinkedHashSet<String> visitedInstanceIds = new LinkedHashSet<>();
        LinkedHashSet<String> deploymentIds = new LinkedHashSet<>();
        visitedInstanceIds.add(instanceId);
        while (StringUtils.hasText(current.getSuperProcessInstanceId()))
        {
            String parentId = current.getSuperProcessInstanceId().trim();
            if (!visitedInstanceIds.add(parentId)
                    || visitedInstanceIds.size() > MAX_PROCESS_RELATION_ROWS)
            {
                throw dataError("流程父子关系存在循环或规模超过详情上限");
            }
            current = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(parentId).singleResult();
            if (current == null || !parentId.equals(current.getId()))
            {
                throw dataError("流程父实例关系记录不存在");
            }
            ProcessDefinition definition = requireDefinition(current.getProcessDefinitionId());
            if (!StringUtils.hasText(definition.getDeploymentId()))
            {
                throw dataError("流程父实例定义缺少部署关联");
            }
            deploymentIds.add(definition.getDeploymentId().trim());
        }
        return Set.copyOf(deploymentIds);
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
        catch (JacksonException exception)
        {
            // 历史纯文本可能以左花括号开头；解析失败仍按原始业务意见兼容展示。
        }
        return message;
    }

    /**
     * 根据全部历史数据构建开始、用户任务和结束节点时间线。
     *
     * @param activities List&lt;HistoricActivityInstance&gt;，实例历史活动
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
     * @param applicationReturned boolean，当前是否仍处于申请人退回修改阶段
     * @return WorkflowProcessViewerView，已完成、未完成、驳回和退回活动集合
     */
    private WorkflowProcessViewerView buildViewer(List<HistoricActivityInstance> activities,
            Map<String, HistoricTaskInstance> tasksById,
            Map<String, List<WorkflowProcessCommentView>> commentsByTask,
            boolean applicationReturned)
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
        if (!applicationReturned)
        {
            // 重新提交后首审批节点会复用退回前的 BPMN id；当前办理态必须覆盖历史退回轨迹。
            returnedActivities.removeAll(unfinishedActivities);
        }
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
     * 查询并核验父实例引用的流程定义。
     *
     * @param definitionId String，历史实例记录中的流程定义主键
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
     * 创建历史关联或投影数据异常。
     *
     * @param message String，稳定业务提示
     * @return ServiceException，HTTP 500 业务异常
     */
    private ServiceException dataError(String message)
    {
        return new ServiceException(message, HttpStatus.ERROR);
    }

    /** 已完成数量与关联门禁的历史活动、任务和意见。 */
    record HistoryData(List<HistoricActivityInstance> activities,
            Map<String, HistoricTaskInstance> tasksById,
            Map<String, List<WorkflowProcessCommentView>> commentsByTask)
    {
    }

    /** 可直接进入详情 VO 的历史展示字段。 */
    record HistoryPresentation(String startUserName,
            List<WorkflowProcessActivityView> timeline,
            WorkflowProcessViewerView viewer)
    {
    }
}

package com.ruoyi.flowable.service.process;

import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.PageWindow;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.dataError;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.loadUserNames;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.optionalText;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.requirePage;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.resolveCategoryDeploymentIds;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.resolveDeploymentCategory;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.toInstant;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.validateRange;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskInfo;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.page.PageResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowAssignedTaskQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowClaimableTaskQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowCompletedTaskQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowAssignedTaskView;
import com.ruoyi.flowable.domain.vo.WorkflowClaimableTaskView;
import com.ruoyi.flowable.domain.vo.WorkflowCompletedTaskExportView;
import com.ruoyi.flowable.domain.vo.WorkflowCompletedTaskView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.service.task.WorkflowTaskRevokePreparation;
import com.ruoyi.flowable.service.task.WorkflowTaskUnclaimPolicy;
import com.ruoyi.flowable.mapper.WorkflowMultiInstanceUserMapper;

/**
 * 待办、可认领和已办任务查询服务。
 */
@Service
public class WorkflowProcessTaskQueryService
{
    private final WorkflowEngineOperations engineOperations;
    private final RepositoryService repositoryService;
    private final HistoryService historyService;
    private final TaskService taskService;
    private final WorkflowIdentityResolver identityResolver;
    private final WorkflowMultiInstanceUserMapper userMapper;
    private final WorkflowTaskRevokePreparation revokePreparation;

    /** 待办能力、详情快照与写入门禁共享的取消认领纯规则组件。 */
    private final WorkflowTaskUnclaimPolicy unclaimPolicy;

    /**
     * 创建流程任务查询服务。
     *
     * 构造后由 Spring 管理该服务
     */
    public WorkflowProcessTaskQueryService(WorkflowEngineOperations engineOperations,
            RepositoryService repositoryService, HistoryService historyService,
            TaskService taskService, WorkflowIdentityResolver identityResolver,
            WorkflowMultiInstanceUserMapper userMapper, WorkflowTaskRevokePreparation revokePreparation,
            WorkflowTaskUnclaimPolicy unclaimPolicy)
    {
        this.engineOperations = engineOperations;
        this.repositoryService = repositoryService;
        this.historyService = historyService;
        this.taskService = taskService;
        this.identityResolver = identityResolver;
        this.userMapper = userMapper;
        this.revokePreparation = revokePreparation;
        this.unclaimPolicy = unclaimPolicy;
    }

    /**
     * 分页查询当前用户作为 assignee 的活动待办任务。
     *
     * @param filter WorkflowAssignedTaskQueryDto，流程、任务和创建时间条件，允许为空
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return PageResult&lt;WorkflowAssignedTaskView&gt;，仅包含当前办理人任务的分页结果
     */
    public PageResult<WorkflowAssignedTaskView> listAssigned(
            WorkflowAssignedTaskQueryDto filter, int pageNum, int pageSize)
    {
        PageWindow page = requirePage(pageNum, pageSize);
        return engineOperations.read(() ->
        {
            WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
            TaskQuery query = buildAssignedQuery(filter, actor.userId());
            Set<String> categoryDeploymentIds = resolveCategoryDeploymentIds(repositoryService,
                    filter == null ? null : filter.category());
            if (categoryDeploymentIds != null && categoryDeploymentIds.isEmpty())
            {
                return new PageResult<>(List.of(), 0);
            }
            if (categoryDeploymentIds != null)
            {
                // TaskInfoQuery 的 processCategoryIn 仍连接定义分类，必须按正式部署主键限定。
                query.deploymentIdIn(categoryDeploymentIds);
            }
            long total = query.count();
            if (total == 0 || page.offset() >= total)
            {
                return new PageResult<>(List.of(), total);
            }
            List<Task> tasks = query.listPage(page.offset(), page.pageSize());
            Map<String, TaskContext> contexts = loadTaskContexts(tasks);
            List<WorkflowAssignedTaskView> rows = tasks.stream()
                    .map(task -> toAssignedView(task, contexts.get(task.getId()), actor.userId()))
                    .toList();
            return new PageResult<>(rows, total);
        });
    }

    /**
     * 分页查询当前用户或其有效 ROLE/DEPT 候选组可认领的未分配活动任务；
     * 当前用户缺少完整五项认领权限时返回真实空页。
     *
     * @param filter WorkflowClaimableTaskQueryDto，流程、任务和创建时间条件，允许为空
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return PageResult&lt;WorkflowClaimableTaskView&gt;，直接候选用户与候选组并集分页结果
     */
    public PageResult<WorkflowClaimableTaskView> listClaimable(
            WorkflowClaimableTaskQueryDto filter, int pageNum, int pageSize)
    {
        PageWindow page = requirePage(pageNum, pageSize);
        return engineOperations.read(() ->
        {
            WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
            // claimEligibleUserIds 是当前用户实时五项权限的正式查询结果，不信任菜单可见性或旧登录会话。
            Set<String> claimEligibleUserIds = identityResolver.resolveClaimEligibleUserIds(
                    List.of(actor.userId()));
            if (!claimEligibleUserIds.contains(actor.userId()))
            {
                // 菜单允许进入待签页不代表账号能完成认领和后续审批；页面只返回真实可执行任务。
                return new PageResult<>(List.of(), 0);
            }
            TaskQuery query = buildClaimableQuery(filter, actor);
            Set<String> categoryDeploymentIds = resolveCategoryDeploymentIds(repositoryService,
                    filter == null ? null : filter.category());
            if (categoryDeploymentIds != null && categoryDeploymentIds.isEmpty())
            {
                return new PageResult<>(List.of(), 0);
            }
            if (categoryDeploymentIds != null)
            {
                // 待签与待办使用同一部署分类口径，候选身份条件仍由原生查询固定。
                query.deploymentIdIn(categoryDeploymentIds);
            }
            long total = query.count();
            if (total == 0 || page.offset() >= total)
            {
                return new PageResult<>(List.of(), total);
            }
            List<Task> tasks = query.listPage(page.offset(), page.pageSize());
            Map<String, TaskContext> contexts = loadTaskContexts(tasks);
            List<WorkflowClaimableTaskView> rows = tasks.stream()
                    .map(task -> toClaimableView(task, contexts.get(task.getId())))
                    .toList();
            return new PageResult<>(rows, total);
        });
    }

    /**
     * 分页查询 Flowable 记录为当前用户真实完成的历史任务。
     *
     * @param filter WorkflowCompletedTaskQueryDto，流程、任务和完成时间条件，允许为空
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return PageResult&lt;WorkflowCompletedTaskView&gt;，按 completedBy 固定当前用户的分页结果
     */
    public PageResult<WorkflowCompletedTaskView> listCompleted(
            WorkflowCompletedTaskQueryDto filter, int pageNum, int pageSize)
    {
        PageWindow page = requirePage(pageNum, pageSize);
        return engineOperations.read(() ->
        {
            WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
            CompletedTaskPage completedPage = loadCompletedTaskPage(
                    filter, page, actor.userId());
            if (completedPage.tasks().isEmpty())
            {
                return new PageResult<>(List.of(), completedPage.total());
            }
            List<WorkflowCompletedTaskView> rows = completedPage.tasks().stream()
                    .map(task -> toCompletedView(task,
                            completedPage.contextsByTaskId().get(task.getId()), actor))
                    .toList();
            return new PageResult<>(rows, completedPage.total());
        });
    }

    /**
     * 分页查询当前用户真实完成的历史任务并直接生成导出视图，不装载撤回能力事实。
     *
     * @param filter WorkflowCompletedTaskQueryDto，流程、任务和完成时间条件，允许为空
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return PageResult&lt;WorkflowCompletedTaskExportView&gt;，与已办列表相同身份和分页口径的导出页
     */
    public PageResult<WorkflowCompletedTaskExportView> listCompletedForExport(
            WorkflowCompletedTaskQueryDto filter, int pageNum, int pageSize)
    {
        PageWindow page = requirePage(pageNum, pageSize);
        return engineOperations.read(() ->
        {
            WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
            CompletedTaskPage completedPage = loadCompletedTaskPage(
                    filter, page, actor.userId());
            List<WorkflowCompletedTaskExportView> rows = completedPage.tasks().stream()
                    .map(task -> toCompletedExportView(task,
                            completedPage.contextsByTaskId().get(task.getId())))
                    .toList();
            return new PageResult<>(rows, completedPage.total());
        });
    }

    /**
     * 构造当前 assignee 活动待办的原生查询。
     *
     * @param filter WorkflowAssignedTaskQueryDto，任务条件，允许为空
     * @param currentUserId String，服务端解析的当前用户主键
     * @return TaskQuery，固定 active 和 taskAssignee 的确定顺序查询
     */
    private TaskQuery buildAssignedQuery(WorkflowAssignedTaskQueryDto filter, String currentUserId)
    {
        TaskQuery query = taskService.createTaskQuery()
                .active()
                .taskAssignee(currentUserId);
        applyTaskFilter(query, filter == null ? null : filter.processKey(),
                filter == null ? null : filter.processName(),
                filter == null ? null : filter.category(),
                filter == null ? null : filter.taskName(),
                filter == null ? null : filter.createdAfter(),
                filter == null ? null : filter.createdBefore());
        return query.orderByTaskCreateTime().desc().orderByTaskId().desc();
    }

    /**
     * 构造当前直接候选用户及有效候选组可认领任务的原生查询。
     *
     * @param filter WorkflowClaimableTaskQueryDto，任务条件，允许为空
     * @param actor WorkflowCurrentIdentity，当前有效用户及候选组
     * @return TaskQuery，固定 active、unassigned 和候选身份并集的确定顺序查询
     */
    private TaskQuery buildClaimableQuery(WorkflowClaimableTaskQueryDto filter,
            WorkflowCurrentIdentity actor)
    {
        TaskQuery query = taskService.createTaskQuery()
                .active()
                .taskUnassigned()
                .taskCandidateUser(actor.userId());
        if (!actor.candidateGroups().isEmpty())
        {
            // Flowable 8 的同一 candidate 查询会把 candidateUser 与 candidateGroupIn 生成为 OR 条件。
            query.taskCandidateGroupIn(actor.candidateGroups());
        }
        applyTaskFilter(query, filter == null ? null : filter.processKey(),
                filter == null ? null : filter.processName(),
                filter == null ? null : filter.category(),
                filter == null ? null : filter.taskName(),
                filter == null ? null : filter.createdAfter(),
                filter == null ? null : filter.createdBefore());
        return query.orderByTaskCreateTime().desc().orderByTaskId().desc();
    }

    /**
     * 向活动任务查询应用不涉及身份的业务筛选条件。
     *
     * @param query TaskQuery，已固定身份范围的活动任务查询
     * @param processKey String，流程标识模糊条件，允许为空
     * @param processName String，流程名称模糊条件，允许为空
     * @param category String，正式部署分类精确条件，允许为空
     * @param taskName String，任务名称模糊条件，允许为空
     * @param createdAfter Instant，任务创建时间下界，允许为空
     * @param createdBefore Instant，任务创建时间上界，允许为空
     * @return 无返回值，条件直接写入 query
     */
    private void applyTaskFilter(TaskQuery query, String processKey, String processName,
            String category, String taskName, Instant createdAfter, Instant createdBefore)
    {
        validateRange(createdAfter, createdBefore, "任务创建时间范围不合法");
        String normalizedKey = optionalText(processKey, "流程标识过长");
        String normalizedProcessName = optionalText(processName, "流程名称过长");
        // 分类部署解析在查询构造后执行，此处仍先完成长度校验以保持非法请求失败语义。
        optionalText(category, "流程分类过长");
        String normalizedTaskName = optionalText(taskName, "任务名称过长");
        if (normalizedKey != null)
        {
            query.processDefinitionKeyLike("%" + normalizedKey + "%");
        }
        if (normalizedProcessName != null)
        {
            query.processDefinitionNameLike("%" + normalizedProcessName + "%");
        }
        if (normalizedTaskName != null)
        {
            query.taskNameLike("%" + normalizedTaskName + "%");
        }
        if (createdAfter != null)
        {
            query.taskCreatedAfter(Date.from(createdAfter));
        }
        if (createdBefore != null)
        {
            query.taskCreatedBefore(Date.from(createdBefore));
        }
    }

    /**
     * 构造当前用户真实完成任务的原生历史查询。
     *
     * @param filter WorkflowCompletedTaskQueryDto，历史任务条件，允许为空
     * @param currentUserId String，服务端解析的当前用户主键
     * @return HistoricTaskInstanceQuery，固定 finished 和 taskCompletedBy 的确定顺序查询
     */
    private HistoricTaskInstanceQuery buildCompletedQuery(
            WorkflowCompletedTaskQueryDto filter, String currentUserId)
    {
        HistoricTaskInstanceQuery query = historyService.createHistoricTaskInstanceQuery()
                .finished()
                .taskCompletedBy(currentUserId);
        if (filter != null)
        {
            validateRange(filter.completedAfter(), filter.completedBefore(), "任务完成时间范围不合法");
            String processKey = optionalText(filter.processKey(), "流程标识过长");
            String processName = optionalText(filter.processName(), "流程名称过长");
            // 即使分类没有部署，其余过滤条件和分类自身也必须先完成参数校验。
            optionalText(filter.category(), "流程分类过长");
            String taskName = optionalText(filter.taskName(), "任务名称过长");
            if (processKey != null)
            {
                query.processDefinitionKeyLike("%" + processKey + "%");
            }
            if (processName != null)
            {
                query.processDefinitionNameLike("%" + processName + "%");
            }
            if (taskName != null)
            {
                query.taskNameLike("%" + taskName + "%");
            }
            if (filter.completedAfter() != null)
            {
                query.taskCompletedAfter(Date.from(filter.completedAfter()));
            }
            if (filter.completedBefore() != null)
            {
                query.taskCompletedBefore(Date.from(filter.completedBefore()));
            }
        }
        return query.orderByHistoricTaskInstanceEndTime().desc()
                .orderByTaskId().desc();
    }

    /**
     * 复用同一查询、分类、分页与上下文装载链读取一页已办任务事实。
     *
     * @param filter WorkflowCompletedTaskQueryDto，历史任务筛选条件，允许为空
     * @param page PageWindow，已校验的分页窗口
     * @param currentUserId String，服务端解析的当前用户主键
     * @return CompletedTaskPage，任务、上下文和真实总量组成的不可变分页事实
     */
    private CompletedTaskPage loadCompletedTaskPage(
            WorkflowCompletedTaskQueryDto filter, PageWindow page, String currentUserId)
    {
        HistoricTaskInstanceQuery query = buildCompletedQuery(filter, currentUserId);
        Set<String> categoryDeploymentIds = resolveCategoryDeploymentIds(repositoryService,
                filter == null ? null : filter.category());
        if (categoryDeploymentIds != null && categoryDeploymentIds.isEmpty())
        {
            return new CompletedTaskPage(List.of(), Map.of(), 0);
        }
        if (categoryDeploymentIds != null)
        {
            // 历史任务与活动任务使用同一发布分类口径，避免列表和导出出现筛选漂移。
            query.deploymentIdIn(categoryDeploymentIds);
        }
        long total = query.count();
        if (total == 0 || page.offset() >= total)
        {
            return new CompletedTaskPage(List.of(), Map.of(), total);
        }
        List<HistoricTaskInstance> tasks = query.listPage(page.offset(), page.pageSize());
        Map<String, TaskContext> contexts = loadTaskContexts(tasks);
        return new CompletedTaskPage(tasks, contexts, total);
    }

    /**
     * 将当前办理人的活动任务转换为不可变待办视图。
     *
     * @param task Task，taskAssignee 已固定当前用户的活动任务
     * @param context TaskContext，当前页批量装载并完成关系核验的任务上下文
     * @param currentUserId String，服务端解析并固定查询范围的当前用户主键
     * @return WorkflowAssignedTaskView，活动待办视图
     */
    private WorkflowAssignedTaskView toAssignedView(Task task, TaskContext context,
            String currentUserId)
    {
        // 历史实例结束或删除即代表流程已离开活动态；任务本身来自 active + assignee 页查询。
        boolean processActive = context.instance().getEndTime() == null
                && !StringUtils.hasText(context.instance().getDeleteReason());
        String delegationState = task.getDelegationState() == null
                ? null : task.getDelegationState().name();
        boolean unclaimAllowed = unclaimPolicy.isUnclaimAllowed(
                processActive, true, task.isSuspended(), currentUserId,
                task.getAssignee(), task.getOwner(), delegationState,
                task.getClaimedBy(), task.getClaimTime() != null);
        return new WorkflowAssignedTaskView(task.getId(), task.getName(), task.getTaskDefinitionKey(),
                task.getAssignee(), task.getOwner(), context.definition().getId(),
                context.definition().getKey(), context.definition().getName(),
                context.definition().getVersion(), context.category(),
                context.definition().getDeploymentId(), task.getProcessInstanceId(),
                context.instance().getBusinessKey(), context.instance().getStartUserId(),
                context.startUserName(), toInstant(task.getCreateTime()), toInstant(task.getDueDate()),
                task.getClaimedBy(), toInstant(task.getClaimTime()), unclaimAllowed);
    }

    /**
     * 将当前身份可认领的活动任务转换为不可变待签视图。
     *
     * @param task Task，候选身份查询返回的未分配活动任务
     * @param context TaskContext，当前页批量装载并完成关系核验的任务上下文
     * @return WorkflowClaimableTaskView，未分配待签任务视图
     */
    private WorkflowClaimableTaskView toClaimableView(Task task, TaskContext context)
    {
        return new WorkflowClaimableTaskView(task.getId(), task.getName(), task.getTaskDefinitionKey(),
                context.definition().getId(), context.definition().getKey(),
                context.definition().getName(), context.definition().getVersion(), context.category(),
                context.definition().getDeploymentId(), task.getProcessInstanceId(),
                context.instance().getBusinessKey(), context.instance().getStartUserId(),
                context.startUserName(), toInstant(task.getCreateTime()), toInstant(task.getDueDate()));
    }

    /**
     * 将当前用户真实完成的历史任务转换为不可变已办视图。
     *
     * @param task HistoricTaskInstance，taskCompletedBy 已固定当前用户的历史任务
     * @param context TaskContext，当前页批量装载并完成关系核验的任务上下文
     * @param actor WorkflowCurrentIdentity，本页已解析的当前用户身份
     * @return WorkflowCompletedTaskView，真实已办任务视图
     */
    private WorkflowCompletedTaskView toCompletedView(HistoricTaskInstance task,
            TaskContext context, WorkflowCurrentIdentity actor)
    {
        // 能力字段调用正式撤回准备路径；页面快照只用于隐藏入口，提交时仍会再次加锁校验。
        boolean revocable = revokePreparation.isProcessRevocable(task, actor);
        return new WorkflowCompletedTaskView(task.getId(), task.getName(), task.getTaskDefinitionKey(),
                task.getAssignee(), task.getCompletedBy(), context.definition().getId(),
                context.definition().getKey(), context.definition().getName(),
                context.definition().getVersion(), context.category(),
                context.definition().getDeploymentId(), task.getProcessInstanceId(),
                context.instance().getBusinessKey(), context.instance().getStartUserId(),
                context.startUserName(), toInstant(task.getCreateTime()), toInstant(task.getEndTime()),
                task.getDurationInMillis(), revocable);
    }

    /**
     * 将已办分页事实直接转换为既有导出视图，明确跳过撤回能力计算。
     *
     * @param task HistoricTaskInstance，taskCompletedBy 已固定当前用户的历史任务
     * @param context TaskContext，当前页批量装载并完成关系核验的任务上下文
     * @return WorkflowCompletedTaskExportView，列定义与现有 Excel 导出保持一致的视图
     */
    private WorkflowCompletedTaskExportView toCompletedExportView(
            HistoricTaskInstance task, TaskContext context)
    {
        return new WorkflowCompletedTaskExportView(task.getId(), context.definition().getName(),
                task.getName(), context.definition().getVersion(), context.startUserName(),
                task.getCompletedBy(), toInstant(task.getCreateTime()),
                toInstant(task.getEndTime()), task.getDurationInMillis());
    }

    /**
     * 一次性批量查询当前任务页的定义、历史实例和部署，并完成全部关联核验。
     *
     * @param tasks List&lt;? extends TaskInfo&gt;，当前页活动或历史任务
     * @return Map&lt;String, TaskContext&gt;，任务主键到已核验上下文的不可变映射
     */
    private Map<String, TaskContext> loadTaskContexts(List<? extends TaskInfo> tasks)
    {
        Set<String> definitionIds = new LinkedHashSet<>();
        Set<String> instanceIds = new LinkedHashSet<>();
        for (TaskInfo task : tasks)
        {
            if (!StringUtils.hasText(task.getProcessDefinitionId())
                    || !StringUtils.hasText(task.getProcessInstanceId()))
            {
                throw dataError("任务关联数据异常");
            }
            definitionIds.add(task.getProcessDefinitionId());
            instanceIds.add(task.getProcessInstanceId());
        }
        if (tasks.isEmpty())
        {
            return Map.of();
        }

        // 按当前页定义主键批量读取；缺失关联仍是业务错误。
        List<ProcessDefinition> definitions = repositoryService.createProcessDefinitionQuery()
                .processDefinitionIds(definitionIds)
                .list();
        Map<String, ProcessDefinition> definitionsById = new HashMap<>();
        Set<String> deploymentIds = new LinkedHashSet<>();
        for (ProcessDefinition definition : definitions)
        {
            definitionsById.put(definition.getId(), definition);
            if (!StringUtils.hasText(definition.getDeploymentId()))
            {
                throw dataError("流程部署关联主键为空");
            }
            deploymentIds.add(definition.getDeploymentId());
        }
        for (String definitionId : definitionIds)
        {
            if (!definitionsById.containsKey(definitionId))
            {
                throw new ServiceException("流程定义不存在或已被删除", HttpStatus.NOT_FOUND);
            }
        }

        // instancesById 来自一次历史查询；任何缺行都保持整页 500 数据异常合同。
        List<HistoricProcessInstance> instances = historyService
                .createHistoricProcessInstanceQuery()
                .processInstanceIds(instanceIds)
                .list();
        Map<String, HistoricProcessInstance> instancesById = new HashMap<>();
        Set<String> startUserIds = new LinkedHashSet<>();
        for (HistoricProcessInstance instance : instances)
        {
            instancesById.put(instance.getId(), instance);
            startUserIds.add(instance.getStartUserId());
        }
        for (String instanceId : instanceIds)
        {
            if (!instancesById.containsKey(instanceId))
            {
                throw dataError("任务缺少所属历史流程实例");
            }
        }

        // deploymentsById 只按定义中的真实部署主键一次性读取，分类不得回退到其他对象。
        List<Deployment> deployments = repositoryService.createDeploymentQuery()
                .deploymentIds(new ArrayList<>(deploymentIds))
                .list();
        Map<String, Deployment> deploymentsById = new HashMap<>();
        for (Deployment deployment : deployments)
        {
            deploymentsById.put(deployment.getId(), deployment);
        }
        for (String deploymentId : deploymentIds)
        {
            if (!deploymentsById.containsKey(deploymentId))
            {
                throw dataError("任务缺少所属流程部署");
            }
        }

        Map<String, String> userNames = loadUserNames(userMapper, startUserIds);
        Map<String, TaskContext> contexts = new LinkedHashMap<>();
        for (TaskInfo task : tasks)
        {
            ProcessDefinition definition = definitionsById.get(task.getProcessDefinitionId());
            HistoricProcessInstance instance = instancesById.get(task.getProcessInstanceId());
            if (!definition.getId().equals(instance.getProcessDefinitionId()))
            {
                throw dataError("任务与流程实例定义关系不一致");
            }
            // 历史实例 deploymentId 由同一个 ACT_RE_PROCDEF 关联产生，不再重复核验。
            Deployment deployment = deploymentsById.get(definition.getDeploymentId());
            // 展示和筛选共享 Deployment.category 口径，禁止回退出一个无法筛选的旧分类。
            String category = resolveDeploymentCategory(deployment.getCategory());
            String startUserName = userNames.get(instance.getStartUserId());
            contexts.put(task.getId(), new TaskContext(definition, instance,
                    category, startUserName));
        }
        return Map.copyOf(contexts);
    }

    /**
     * 已办列表与导出共享的一页历史任务事实。
     *
     * @param tasks List&lt;HistoricTaskInstance&gt;，确定顺序的当前页已结束任务
     * @param contextsByTaskId Map&lt;String, TaskContext&gt;，任务主键到批量关联上下文的映射
     * @param total long，过滤条件下的真实任务总量
     */
    private record CompletedTaskPage(
            List<HistoricTaskInstance> tasks,
            Map<String, TaskContext> contextsByTaskId,
            long total)
    {
        /**
         * 冻结已办分页事实，防止列表和导出转换阶段改写共享结果。
         *
         * 构造时复制任务和上下文集合
         */
        private CompletedTaskPage
        {
            tasks = List.copyOf(tasks);
            contextsByTaskId = Map.copyOf(contextsByTaskId);
        }
    }

    /**
     * 已完成服务端关系核验的任务上下文。
     *
     * @param definition ProcessDefinition，任务所属流程定义
     * @param instance HistoricProcessInstance，任务所属历史实例
     * @param category String，部署优先且已排除 BPMN 命名空间 URI 的业务分类编码
     * @param startUserName String，历史发起人显示名称
     */
    private record TaskContext(ProcessDefinition definition, HistoricProcessInstance instance,
            String category, String startUserName)
    {
    }

}

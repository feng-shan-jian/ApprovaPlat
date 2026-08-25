package com.ruoyi.flowable.service.process;

import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.PageWindow;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.checkedCount;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.checkedRows;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.dataError;
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
import com.ruoyi.common.core.domain.entity.SysUser;
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
import com.ruoyi.flowable.service.task.WorkflowTaskLifecycleService;
import com.ruoyi.system.service.ISysUserService;

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
    private final ISysUserService userService;
    private final WorkflowTaskLifecycleService taskLifecycleService;

    /**
     * 创建流程任务查询服务。
     *
     * @param engineOperations WorkflowEngineOperations，统一只读事务和异常翻译边界
     * @param repositoryService RepositoryService，Flowable 流程定义和部署公共 API
     * @param historyService HistoryService，Flowable 历史实例与历史任务公共 API
     * @param taskService TaskService，Flowable 活动任务公共 API
     * @param identityResolver WorkflowIdentityResolver，当前有效用户及候选组解析器
     * @param userService ISysUserService，历史发起人显示名称查询服务
     * @param taskLifecycleService WorkflowTaskLifecycleService，复用正式撤回校验计算已办能力
     * @return 无返回值，构造后由 Spring 管理该服务
     */
    public WorkflowProcessTaskQueryService(WorkflowEngineOperations engineOperations,
            RepositoryService repositoryService, HistoryService historyService,
            TaskService taskService, WorkflowIdentityResolver identityResolver,
            ISysUserService userService, WorkflowTaskLifecycleService taskLifecycleService)
    {
        this.engineOperations = engineOperations;
        this.repositoryService = repositoryService;
        this.historyService = historyService;
        this.taskService = taskService;
        this.identityResolver = identityResolver;
        this.userService = userService;
        this.taskLifecycleService = taskLifecycleService;
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
            long total = checkedCount(query.count());
            if (total == 0 || page.offset() >= total)
            {
                return new PageResult<>(List.of(), total);
            }
            List<Task> tasks = checkedRows(query.listPage(page.offset(), page.pageSize()), page.pageSize());
            UserNameCache userNameCache = new UserNameCache();
            Map<String, TaskContext> contexts = loadTaskContexts(tasks, userNameCache);
            List<WorkflowAssignedTaskView> rows = tasks.stream()
                    .map(task -> toAssignedView(task, contexts.get(task.getId())))
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
            long total = checkedCount(query.count());
            if (total == 0 || page.offset() >= total)
            {
                return new PageResult<>(List.of(), total);
            }
            List<Task> tasks = checkedRows(query.listPage(page.offset(), page.pageSize()), page.pageSize());
            UserNameCache userNameCache = new UserNameCache();
            Map<String, TaskContext> contexts = loadTaskContexts(tasks, userNameCache);
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
                            completedPage.contextsByTaskId().get(task.getId())))
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
        long total = checkedCount(query.count());
        if (total == 0 || page.offset() >= total)
        {
            return new CompletedTaskPage(List.of(), Map.of(), total);
        }
        List<HistoricTaskInstance> tasks = checkedRows(
                query.listPage(page.offset(), page.pageSize()), page.pageSize());
        UserNameCache userNameCache = new UserNameCache();
        Map<String, TaskContext> contexts = loadTaskContexts(tasks, userNameCache);
        return new CompletedTaskPage(tasks, contexts, total);
    }

    /**
     * 将当前办理人的活动任务转换为不可变待办视图。
     *
     * @param task Task，taskAssignee 已固定当前用户的活动任务
     * @param context TaskContext，当前页批量装载并完成关系核验的任务上下文
     * @return WorkflowAssignedTaskView，活动待办视图
     */
    private WorkflowAssignedTaskView toAssignedView(Task task, TaskContext context)
    {
        return new WorkflowAssignedTaskView(task.getId(), task.getName(), task.getTaskDefinitionKey(),
                task.getAssignee(), task.getOwner(), context.definition().getId(),
                context.definition().getKey(), context.definition().getName(),
                context.definition().getVersion(), context.category(),
                context.definition().getDeploymentId(), task.getProcessInstanceId(),
                context.instance().getBusinessKey(), context.instance().getStartUserId(),
                context.startUserName(), toInstant(task.getCreateTime()), toInstant(task.getDueDate()),
                task.getClaimedBy(), toInstant(task.getClaimTime()));
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
        if (StringUtils.hasText(task.getAssignee()))
        {
            throw dataError("待签任务已经存在办理人");
        }
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
     * @return WorkflowCompletedTaskView，真实已办任务视图
     */
    private WorkflowCompletedTaskView toCompletedView(HistoricTaskInstance task,
            TaskContext context)
    {
        // 能力字段调用正式撤回准备路径；页面快照只用于隐藏入口，提交时仍会再次加锁校验。
        boolean revocable = taskLifecycleService.isProcessRevocable(
                task.getProcessInstanceId(), task.getId());
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
     * @param userNameCache UserNameCache，保持既有逐用户显示名称解析规则的页内缓存
     * @return Map&lt;String, TaskContext&gt;，任务主键到已核验上下文的不可变映射
     */
    private Map<String, TaskContext> loadTaskContexts(List<? extends TaskInfo> tasks,
            UserNameCache userNameCache)
    {
        // tasksById 同时保留当前页顺序并检测重复任务主键，避免覆盖后返回错误上下文。
        Map<String, TaskInfo> tasksById = new LinkedHashMap<>();
        Set<String> definitionIds = new LinkedHashSet<>();
        Set<String> instanceIds = new LinkedHashSet<>();
        for (TaskInfo task : tasks)
        {
            if (task == null || !StringUtils.hasText(task.getId())
                    || !StringUtils.hasText(task.getProcessDefinitionId())
                    || !StringUtils.hasText(task.getProcessInstanceId())
                    || tasksById.put(task.getId(), task) != null)
            {
                throw dataError("任务关联数据异常");
            }
            definitionIds.add(task.getProcessDefinitionId());
            instanceIds.add(task.getProcessInstanceId());
        }
        if (tasksById.isEmpty())
        {
            return Map.of();
        }

        // definitionsById 只接受本页请求范围内、主键唯一且部署关系可继续核验的定义。
        List<ProcessDefinition> definitions = repositoryService.createProcessDefinitionQuery()
                .processDefinitionIds(definitionIds)
                .list();
        if (definitions == null || definitions.size() > definitionIds.size())
        {
            throw dataError("流程定义批量查询结果异常");
        }
        Map<String, ProcessDefinition> definitionsById = new HashMap<>();
        Set<String> deploymentIds = new LinkedHashSet<>();
        for (ProcessDefinition definition : definitions)
        {
            if (definition == null || !StringUtils.hasText(definition.getId())
                    || !definitionIds.contains(definition.getId())
                    || definitionsById.put(definition.getId(), definition) != null)
            {
                throw dataError("流程定义批量查询结果异常");
            }
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
        if (instances == null || instances.size() > instanceIds.size())
        {
            throw dataError("历史流程实例批量查询结果异常");
        }
        Map<String, HistoricProcessInstance> instancesById = new HashMap<>();
        for (HistoricProcessInstance instance : instances)
        {
            if (instance == null || !StringUtils.hasText(instance.getId())
                    || !instanceIds.contains(instance.getId())
                    || instancesById.put(instance.getId(), instance) != null)
            {
                throw dataError("历史流程实例批量查询结果异常");
            }
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
        if (deployments == null || deployments.size() > deploymentIds.size())
        {
            throw dataError("流程部署批量查询结果异常");
        }
        Map<String, Deployment> deploymentsById = new HashMap<>();
        for (Deployment deployment : deployments)
        {
            if (deployment == null || !StringUtils.hasText(deployment.getId())
                    || !deploymentIds.contains(deployment.getId())
                    || deploymentsById.put(deployment.getId(), deployment) != null)
            {
                throw dataError("流程部署批量查询结果异常");
            }
        }
        for (String deploymentId : deploymentIds)
        {
            if (!deploymentsById.containsKey(deploymentId))
            {
                throw dataError("任务缺少所属流程部署");
            }
        }

        Map<String, TaskContext> contexts = new LinkedHashMap<>();
        for (TaskInfo task : tasksById.values())
        {
            ProcessDefinition definition = definitionsById.get(task.getProcessDefinitionId());
            HistoricProcessInstance instance = instancesById.get(task.getProcessInstanceId());
            if (!definition.getId().equals(instance.getProcessDefinitionId()))
            {
                throw dataError("任务与流程实例定义关系不一致");
            }
            if (!definition.getDeploymentId().equals(instance.getDeploymentId()))
            {
                throw dataError("任务与流程实例部署关系不一致");
            }
            Deployment deployment = deploymentsById.get(definition.getDeploymentId());
            // 展示和筛选共享 Deployment.category 口径，禁止回退出一个无法筛选的旧分类。
            String category = resolveDeploymentCategory(deployment.getCategory());
            String startUserName = resolveUserName(instance.getStartUserId(), userNameCache);
            contexts.put(task.getId(), new TaskContext(definition, instance,
                    category, startUserName));
        }
        return Map.copyOf(contexts);
    }

    /**
     * 查询历史发起人的当前显示名称，缺失用户以原始主键回显而不篡改历史关联。
     *
     * @param userId String，Flowable 历史发起人主键，允许为空
     * @param userNameCache UserNameCache，当前页用户名缓存
     * @return String，用户昵称、原始主键或 null
     */
    private String resolveUserName(String userId, UserNameCache userNameCache)
    {
        if (!StringUtils.hasText(userId))
        {
            return null;
        }
        if (userNameCache.userNames.containsKey(userId))
        {
            return userNameCache.userNames.get(userId);
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
            // 存量异常身份不做名称猜测，保留原值便于迁移对账和问题追踪。
        }
        userNameCache.userNames.put(userId, displayName);
        return displayName;
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
         * @param tasks List&lt;HistoricTaskInstance&gt;，当前页历史任务
         * @param contextsByTaskId Map&lt;String, TaskContext&gt;，任务上下文映射
         * @param total long，真实总量
         * @return 无返回值，构造时复制任务和上下文集合
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

    /** 当前页用户名缓存，保持历史用户逐对象查询和原始主键回显规则不变。 */
    private static final class UserNameCache
    {
        /** 历史用户主键到显示名称的当前页映射。 */
        private final Map<String, String> userNames = new HashMap<>();
    }
}

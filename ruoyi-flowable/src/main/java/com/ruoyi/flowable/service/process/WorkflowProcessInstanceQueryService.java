package com.ruoyi.flowable.service.process;

import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.PageWindow;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.checkedCount;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.checkedRows;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.dataError;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.invalidArgument;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.optionalText;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.requirePage;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.resolveCategoryDeploymentIds;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.resolveDeploymentCategory;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.safeVersion;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.toInstant;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.validateRange;

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
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.page.PageResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCopy;
import com.ruoyi.flowable.domain.dto.WorkflowCopyQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowManagedProcessQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowOwnedProcessQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowCopyView;
import com.ruoyi.flowable.domain.vo.WorkflowManagedProcessView;
import com.ruoyi.flowable.domain.vo.WorkflowOwnedProcessView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.system.service.ISysUserService;

/**
 * 我的流程、管理员实例列表与抄送记录查询服务。
 */
@Service
public class WorkflowProcessInstanceQueryService
{
    /** 单个流程实例允许返回的最大当前任务数。 */
    private static final int MAX_RUNTIME_TASKS_PER_INSTANCE = 200;

    private final WorkflowEngineOperations engineOperations;
    private final RepositoryService repositoryService;
    private final HistoryService historyService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final WorkflowIdentityResolver identityResolver;
    private final WfCopyMapper copyMapper;
    private final ISysUserService userService;

    /**
     * 创建流程实例与抄送查询服务。
     *
     * @param engineOperations WorkflowEngineOperations，统一只读和写事务及异常翻译边界
     * @param repositoryService RepositoryService，Flowable 部署公共 API
     * @param historyService HistoryService，Flowable 历史实例公共 API
     * @param runtimeService RuntimeService，Flowable 实例实时挂起状态公共 API
     * @param taskService TaskService，Flowable 当前任务公共 API
     * @param identityResolver WorkflowIdentityResolver，当前有效用户解析器
     * @param copyMapper WfCopyMapper，正式抄送记录 Mapper
     * @param userService ISysUserService，历史发起人显示名称查询服务
     * @return 无返回值，构造后由 Spring 管理该服务
     */
    public WorkflowProcessInstanceQueryService(WorkflowEngineOperations engineOperations,
            RepositoryService repositoryService, HistoryService historyService,
            RuntimeService runtimeService, TaskService taskService,
            WorkflowIdentityResolver identityResolver, WfCopyMapper copyMapper,
            ISysUserService userService)
    {
        this.engineOperations = engineOperations;
        this.repositoryService = repositoryService;
        this.historyService = historyService;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.identityResolver = identityResolver;
        this.copyMapper = copyMapper;
        this.userService = userService;
    }

    /**
     * 分页查询当前用户真实发起的流程实例。
     *
     * @param filter WorkflowOwnedProcessQueryDto，流程与开始时间条件，允许为空
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return PageResult&lt;WorkflowOwnedProcessView&gt;，服务端固定发起人的分页结果
     */
    public PageResult<WorkflowOwnedProcessView> listOwned(
            WorkflowOwnedProcessQueryDto filter, int pageNum, int pageSize)
    {
        PageWindow page = requirePage(pageNum, pageSize);
        return engineOperations.read(() ->
        {
            WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
            HistoricProcessInstanceQuery query = buildOwnedQuery(filter, actor.userId());
            Set<String> categoryDeploymentIds = resolveCategoryDeploymentIds(repositoryService,
                    filter == null ? null : filter.category());
            if (categoryDeploymentIds != null && categoryDeploymentIds.isEmpty())
            {
                return new PageResult<>(List.of(), 0);
            }
            if (categoryDeploymentIds != null)
            {
                // 历史实例分类必须服从发布时冻结的 Deployment.category。
                query.deploymentIdIn(new ArrayList<>(categoryDeploymentIds));
            }
            long total = checkedCount(query.count());
            if (total == 0 || page.offset() >= total)
            {
                return new PageResult<>(List.of(), total);
            }
            List<HistoricProcessInstance> instances = checkedRows(
                    query.listPage(page.offset(), page.pageSize()), page.pageSize());
            InstancePageFacts facts = loadInstancePageFacts(instances);
            List<WorkflowOwnedProcessView> rows = instances.stream()
                    .map(instance -> toOwnedView(instance, facts))
                    .toList();
            return new PageResult<>(rows, total);
        });
    }

    /**
     * 分页查询流程管理员可运维的全部历史与运行实例。
     *
     * @param filter WorkflowManagedProcessQueryDto，实例、定义、发起人和开始时间条件，允许为空
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return PageResult&lt;WorkflowManagedProcessView&gt;，不按当前发起人缩小的管理员分页结果
     */
    public PageResult<WorkflowManagedProcessView> listManaged(
            WorkflowManagedProcessQueryDto filter, int pageNum, int pageSize)
    {
        PageWindow page = requirePage(pageNum, pageSize);
        return engineOperations.read(() ->
        {
            // 即使 Controller 已做权限校验，也必须解析当前有效身份，禁止停用或删除账号继续读取全量实例。
            identityResolver.resolveCurrentIdentity();
            HistoricProcessInstanceQuery query = buildManagedQuery(filter);
            Set<String> categoryDeploymentIds = resolveCategoryDeploymentIds(repositoryService,
                    filter == null ? null : filter.category());
            if (categoryDeploymentIds != null && categoryDeploymentIds.isEmpty())
            {
                return new PageResult<>(List.of(), 0);
            }
            if (categoryDeploymentIds != null)
            {
                // 管理员列表与普通工作台使用同一正式部署分类口径。
                query.deploymentIdIn(new ArrayList<>(categoryDeploymentIds));
            }
            long total = checkedCount(query.count());
            if (total == 0 || page.offset() >= total)
            {
                return new PageResult<>(List.of(), total);
            }
            List<HistoricProcessInstance> instances = checkedRows(
                    query.listPage(page.offset(), page.pageSize()), page.pageSize());
            InstancePageFacts facts = loadInstancePageFacts(instances);
            UserNameCache userNameCache = new UserNameCache();
            List<WorkflowManagedProcessView> rows = instances.stream()
                    .map(instance -> toManagedView(instance, userNameCache, facts))
                    .toList();
            return new PageResult<>(rows, total);
        });
    }

    /**
     * 分页查询正式业务表中抄送给当前用户的有效记录。
     *
     * @param filter WorkflowCopyQueryDto，抄送业务条件，允许为空且不包含 userId
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return PageResult&lt;WorkflowCopyView&gt;，服务端固定接收人的抄送分页结果
     */
    public PageResult<WorkflowCopyView> listCopies(
            WorkflowCopyQueryDto filter, int pageNum, int pageSize)
    {
        PageWindow page = requirePage(pageNum, pageSize);
        return engineOperations.read(() ->
        {
            WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
            long currentUserId = Long.parseLong(actor.userId());
            WfCopy trustedFilter = toCopyFilter(filter);
            long total = checkedCount(copyMapper.countListByUserId(currentUserId, trustedFilter));
            if (total == 0 || page.offset() >= total)
            {
                return new PageResult<>(List.of(), total);
            }
            List<WfCopy> copies = checkedRows(copyMapper.selectPageByUserId(
                    currentUserId, trustedFilter, page.offset(), page.pageSize()), page.pageSize());
            List<WorkflowCopyView> rows = new ArrayList<>(copies.size());
            for (WfCopy copy : copies)
            {
                if (copy == null || copy.getUserId() == null || copy.getUserId() != currentUserId)
                {
                    // 即使 Mapper 配置被误改，也不能把其他接收人的抄送正文泄露给当前用户。
                    throw dataError("抄送记录接收人关联异常");
                }
                rows.add(toCopyView(copy));
            }
            return new PageResult<>(rows, total);
        });
    }

    /**
     * 由当前认证接收人原子标记首次阅读并返回数据库最终状态。
     *
     * @param copyId Long，抄送记录主键
     * @return WorkflowCopyView，首次时间已持久化的当前用户抄送视图
     */
    public WorkflowCopyView markCopyRead(Long copyId)
    {
        if (copyId == null || copyId <= 0)
        {
            throw invalidArgument("抄送记录主键不合法");
        }
        return engineOperations.writeAsCurrentUser(actor ->
        {
            long currentUserId = Long.parseLong(actor.userId());
            // UPDATE 同时携带 user_id 和未读条件，越权请求不会先探测记录是否存在。
            copyMapper.markRead(copyId, currentUserId, actor.userId());
            WfCopy copy = copyMapper.selectByIdAndUserId(copyId, currentUserId);
            if (copy == null)
            {
                throw new ServiceException("抄送记录不存在", HttpStatus.NOT_FOUND);
            }
            if (!"1".equals(copy.getReadStatus()) || copy.getReadTime() == null)
            {
                throw dataError("抄送首次阅读状态异常");
            }
            return toCopyView(copy);
        });
    }

    /**
     * 构造当前用户发起实例的原生历史查询。
     *
     * @param filter WorkflowOwnedProcessQueryDto，流程实例条件，允许为空
     * @param currentUserId String，服务端解析的当前用户主键
     * @return HistoricProcessInstanceQuery，固定 startedBy 且顺序确定的查询
     */
    private HistoricProcessInstanceQuery buildOwnedQuery(
            WorkflowOwnedProcessQueryDto filter, String currentUserId)
    {
        HistoricProcessInstanceQuery query = historyService.createHistoricProcessInstanceQuery()
                .startedBy(currentUserId);
        if (filter != null)
        {
            validateRange(filter.startedAfter(), filter.startedBefore(), "流程开始时间范围不合法");
            String processKey = optionalText(filter.processKey(), "流程标识过长");
            String processName = optionalText(filter.processName(), "流程名称过长");
            String businessKey = optionalText(filter.businessKey(), "业务主键过长");
            // 分类部署解析在查询构造后执行，但非法请求的其余参数必须先完成校验。
            optionalText(filter.category(), "流程分类过长");
            if (processKey != null)
            {
                query.processDefinitionKey(processKey);
            }
            if (processName != null)
            {
                query.processDefinitionNameLike("%" + processName + "%");
            }
            if (businessKey != null)
            {
                query.processInstanceBusinessKey(businessKey);
            }
            if (filter.startedAfter() != null)
            {
                query.startedAfter(Date.from(filter.startedAfter()));
            }
            if (filter.startedBefore() != null)
            {
                query.startedBefore(Date.from(filter.startedBefore()));
            }
        }
        return query.orderByProcessInstanceStartTime().desc()
                .orderByProcessInstanceId().desc();
    }

    /**
     * 构造流程管理员跨用户实例查询；发起人条件只接受若依数字用户主键。
     *
     * @param filter WorkflowManagedProcessQueryDto，管理员实例运维条件，允许为空
     * @return HistoricProcessInstanceQuery，不带当前用户 startedBy 限制且顺序确定的查询
     */
    private HistoricProcessInstanceQuery buildManagedQuery(WorkflowManagedProcessQueryDto filter)
    {
        HistoricProcessInstanceQuery query = historyService.createHistoricProcessInstanceQuery();
        if (filter != null)
        {
            validateRange(filter.startedAfter(), filter.startedBefore(), "流程开始时间范围不合法");
            String instanceId = optionalText(filter.processInstanceId(), "流程实例主键过长");
            String processKey = optionalText(filter.processKey(), "流程标识过长");
            String processName = optionalText(filter.processName(), "流程名称过长");
            String businessKey = optionalText(filter.businessKey(), "业务主键过长");
            String startUserId = normalizeOptionalUserId(filter.startUserId());
            // 分类部署解析在查询构造后执行，但管理员的其余条件必须先完整校验。
            optionalText(filter.category(), "流程分类过长");
            if (instanceId != null)
            {
                query.processInstanceId(instanceId);
            }
            if (processKey != null)
            {
                query.processDefinitionKey(processKey);
            }
            if (processName != null)
            {
                query.processDefinitionNameLike("%" + processName + "%");
            }
            if (businessKey != null)
            {
                query.processInstanceBusinessKey(businessKey);
            }
            if (startUserId != null)
            {
                query.startedBy(startUserId);
            }
            if (filter.startedAfter() != null)
            {
                query.startedAfter(Date.from(filter.startedAfter()));
            }
            if (filter.startedBefore() != null)
            {
                query.startedBefore(Date.from(filter.startedBefore()));
            }
        }
        return query.orderByProcessInstanceStartTime().desc()
                .orderByProcessInstanceId().desc();
    }

    /**
     * 将抄送查询 DTO 转为 Mapper 使用的可信条件对象，明确不写入 userId。
     *
     * @param filter WorkflowCopyQueryDto，客户端业务筛选条件，允许为空
     * @return WfCopy，仅包含允许筛选字段的内部条件对象
     */
    private WfCopy toCopyFilter(WorkflowCopyQueryDto filter)
    {
        WfCopy trusted = new WfCopy();
        if (filter == null)
        {
            return trusted;
        }
        trusted.setTitle(optionalText(filter.title(), "抄送标题过长"));
        trusted.setProcessName(optionalText(filter.processName(), "流程名称过长"));
        trusted.setOriginatorName(optionalText(filter.originatorName(), "发起人名称过长"));
        trusted.setCategoryId(optionalText(filter.categoryId(), "流程分类过长"));
        String readStatus = optionalText(filter.readStatus(), "阅读状态不合法");
        if (readStatus != null && !Set.of("0", "1").contains(readStatus))
        {
            throw invalidArgument("阅读状态必须为 0 或 1");
        }
        trusted.setReadStatus(readStatus);
        return trusted;
    }

    /**
     * 将当前用户发起的历史实例转换为不可变工作台视图。
     *
     * @param instance HistoricProcessInstance，startedBy 已固定当前用户的历史实例
     * @param facts InstancePageFacts，当前页一次性取得的任务、分类和挂起状态
     * @return WorkflowOwnedProcessView，含活动任务名称和稳定流程状态的视图
     */
    private WorkflowOwnedProcessView toOwnedView(HistoricProcessInstance instance,
            InstancePageFacts facts)
    {
        if (instance == null || !StringUtils.hasText(instance.getId()))
        {
            throw dataError("历史流程实例数据异常");
        }
        String category = facts.deploymentCategories().get(instance.getDeploymentId());
        List<String> taskNames = facts.runtimeTaskNames().getOrDefault(instance.getId(), List.of());
        Boolean runtimeSuspended = facts.runtimeSuspensionStates().get(instance.getId());
        return new WorkflowOwnedProcessView(instance.getId(), instance.getProcessDefinitionId(),
                instance.getProcessDefinitionKey(), instance.getProcessDefinitionName(),
                safeVersion(instance.getProcessDefinitionVersion()), category,
                instance.getDeploymentId(), instance.getBusinessKey(), instance.getStartUserId(),
                toInstant(instance.getStartTime()), toInstant(instance.getEndTime()),
                instance.getDurationInMillis(),
                normalizeProcessState(instance, runtimeSuspended), taskNames);
    }

    /**
     * 将任意发起人的历史实例转换为流程管理员运维视图。
     *
     * @param instance HistoricProcessInstance，管理员查询返回的历史实例
     * @param userNameCache UserNameCache，当前页发起人显示名称缓存
     * @param facts InstancePageFacts，当前页一次性取得的任务、分类和挂起状态
     * @return WorkflowManagedProcessView，含发起人、活动节点和稳定状态的运维视图
     */
    private WorkflowManagedProcessView toManagedView(HistoricProcessInstance instance,
            UserNameCache userNameCache, InstancePageFacts facts)
    {
        if (instance == null || !StringUtils.hasText(instance.getId()))
        {
            throw dataError("历史流程实例数据异常");
        }
        String category = facts.deploymentCategories().get(instance.getDeploymentId());
        List<String> taskNames = facts.runtimeTaskNames().getOrDefault(instance.getId(), List.of());
        Boolean runtimeSuspended = facts.runtimeSuspensionStates().get(instance.getId());
        String startUserName = resolveUserName(instance.getStartUserId(), userNameCache);
        return new WorkflowManagedProcessView(instance.getId(), instance.getProcessDefinitionId(),
                instance.getProcessDefinitionKey(), instance.getProcessDefinitionName(),
                safeVersion(instance.getProcessDefinitionVersion()), category,
                instance.getDeploymentId(), instance.getBusinessKey(), instance.getStartUserId(),
                startUserName, toInstant(instance.getStartTime()), toInstant(instance.getEndTime()),
                instance.getDurationInMillis(),
                normalizeProcessState(instance, runtimeSuspended), taskNames);
    }

    /**
     * 一次性装载当前实例页转换视图所需的全部引擎事实。
     *
     * @param instances List&lt;HistoricProcessInstance&gt;，当前页历史实例
     * @return InstancePageFacts，批量任务名称、正式部署分类和运行时挂起状态
     */
    private InstancePageFacts loadInstancePageFacts(List<HistoricProcessInstance> instances)
    {
        return new InstancePageFacts(loadRuntimeTaskNamesByInstance(instances),
                loadDeploymentCategories(instances), loadRuntimeSuspensionStates(instances));
    }

    /**
     * 使用一个有界 TaskQuery 批量读取当前页全部实例的未结束任务名称。
     * 查询不得添加 active，挂起任务仍是需要展示的当前环节。
     *
     * @param instances List&lt;HistoricProcessInstance&gt;，当前页已完成分页门禁的历史实例
     * @return Map&lt;String, List&lt;String&gt;&gt;，实例 ID 到稳定排序任务名称的不可变映射
     */
    private Map<String, List<String>> loadRuntimeTaskNamesByInstance(
            List<HistoricProcessInstance> instances)
    {
        LinkedHashSet<String> instanceIds = new LinkedHashSet<>();
        for (HistoricProcessInstance instance : instances)
        {
            if (instance == null || !StringUtils.hasText(instance.getId())
                    || !instanceIds.add(instance.getId()))
            {
                throw dataError("历史流程实例数据异常");
            }
        }
        if (instanceIds.isEmpty())
        {
            return Map.of();
        }

        // pageTaskLimit 多取一条，用一次有界查询识别当前页任务总量越界。
        int pageTaskLimit = instanceIds.size() * MAX_RUNTIME_TASKS_PER_INSTANCE + 1;
        TaskQuery runtimeTaskQuery = taskService.createTaskQuery()
                .processInstanceIdIn(new ArrayList<>(instanceIds))
                .orderByTaskCreateTime().asc()
                .orderByTaskId().asc();
        List<Task> tasks = checkedRows(runtimeTaskQuery.listPage(0, pageTaskLimit), pageTaskLimit);
        if (tasks.size() == pageTaskLimit)
        {
            throw dataError("当前页流程实例的当前任务总数超过安全上限");
        }

        Map<String, List<String>> mutableNames = new LinkedHashMap<>();
        instanceIds.forEach(instanceId -> mutableNames.put(instanceId, new ArrayList<>()));
        Map<String, Integer> taskCounts = new HashMap<>();
        Set<String> taskIds = new LinkedHashSet<>();
        for (Task task : tasks)
        {
            if (task == null || !StringUtils.hasText(task.getId())
                    || !taskIds.add(task.getId())
                    || !instanceIds.contains(task.getProcessInstanceId()))
            {
                throw dataError("当前任务数据异常");
            }
            int taskCount = taskCounts.merge(task.getProcessInstanceId(), 1, Integer::sum);
            if (taskCount > MAX_RUNTIME_TASKS_PER_INSTANCE)
            {
                throw dataError("单个流程实例的当前任务数量超过安全上限");
            }
            if (StringUtils.hasText(task.getName()))
            {
                // Flowable 已按创建时间、任务主键升序返回，分组追加必须保持该顺序。
                mutableNames.get(task.getProcessInstanceId()).add(task.getName());
            }
        }

        Map<String, List<String>> names = new LinkedHashMap<>();
        mutableNames.forEach((instanceId, taskNames) ->
                names.put(instanceId, List.copyOf(taskNames)));
        return Map.copyOf(names);
    }

    /**
     * 批量读取当前页实例所属部署，并只保留可作为业务分类的 Deployment.category。
     * 存量部署缺失、分类为空或绝对 URI 时返回空分类，不回退历史定义分类。
     *
     * @param instances List&lt;HistoricProcessInstance&gt;，当前页历史实例
     * @return Map&lt;String, String&gt;，部署 ID 到规范业务分类的不可变映射
     */
    private Map<String, String> loadDeploymentCategories(
            List<HistoricProcessInstance> instances)
    {
        LinkedHashSet<String> deploymentIds = new LinkedHashSet<>();
        for (HistoricProcessInstance instance : instances)
        {
            if (instance != null && StringUtils.hasText(instance.getDeploymentId()))
            {
                deploymentIds.add(instance.getDeploymentId());
            }
        }
        if (deploymentIds.isEmpty())
        {
            return Map.of();
        }

        List<Deployment> deployments = repositoryService.createDeploymentQuery()
                .deploymentIds(new ArrayList<>(deploymentIds))
                .list();
        if (deployments == null || deployments.size() > deploymentIds.size())
        {
            throw dataError("流程部署批量查询结果异常");
        }
        Map<String, String> categories = new HashMap<>();
        Set<String> returnedDeploymentIds = new LinkedHashSet<>();
        for (Deployment deployment : deployments)
        {
            if (deployment == null || !StringUtils.hasText(deployment.getId())
                    || !deploymentIds.contains(deployment.getId())
                    || !returnedDeploymentIds.add(deployment.getId()))
            {
                throw dataError("流程部署批量查询结果异常");
            }
            String category = resolveDeploymentCategory(deployment.getCategory());
            if (category != null)
            {
                categories.put(deployment.getId(), category);
            }
        }
        return Map.copyOf(categories);
    }

    /**
     * 批量读取当前页仍在运行的实例挂起状态，避免把历史表中的 RUNNING 误当作实时状态。
     *
     * @param instances List&lt;HistoricProcessInstance&gt;，当前页已完成数量门禁的历史实例
     * @return Map&lt;String, Boolean&gt;，运行时实例 ID 到挂起标志的不可变映射；已结束实例不在映射中
     */
    private Map<String, Boolean> loadRuntimeSuspensionStates(
            List<HistoricProcessInstance> instances)
    {
        Set<String> instanceIds = new LinkedHashSet<>();
        for (HistoricProcessInstance instance : instances)
        {
            if (instance == null || !StringUtils.hasText(instance.getId())
                    || !instanceIds.add(instance.getId()))
            {
                throw dataError("历史流程实例数据异常");
            }
        }
        if (instanceIds.isEmpty())
        {
            return Map.of();
        }

        // Flowable 的历史实例 state 在挂起时仍可能为 RUNNING，真实状态必须从运行时表一次性读取。
        List<ProcessInstance> runtimeInstances = checkedRows(runtimeService
                .createProcessInstanceQuery()
                .processInstanceIds(instanceIds)
                .list(), instanceIds.size());
        Map<String, Boolean> states = new HashMap<>();
        for (ProcessInstance runtimeInstance : runtimeInstances)
        {
            if (runtimeInstance == null || !StringUtils.hasText(runtimeInstance.getId())
                    || !instanceIds.contains(runtimeInstance.getId())
                    || states.put(runtimeInstance.getId(), runtimeInstance.isSuspended()) != null)
            {
                throw dataError("运行时流程实例数据异常");
            }
        }
        return Map.copyOf(states);
    }

    /**
     * 将正式抄送实体转换为不暴露可变领域对象的不可变视图。
     *
     * @param copy WfCopy，已经过当前接收人复核的有效抄送记录
     * @return WorkflowCopyView，抄送记录不可变视图
     */
    private WorkflowCopyView toCopyView(WfCopy copy)
    {
        return new WorkflowCopyView(copy.getCopyId(), copy.getTitle(), copy.getProcessId(),
                copy.getProcessName(), copy.getCategoryId(), copy.getDeploymentId(),
                copy.getInstanceId(), copy.getTaskId(), copy.getUserId(), copy.getOriginatorId(),
                copy.getOriginatorName(), copy.getSourceType(), copy.getTriggerType(),
                copy.getTriggerNodeId(), copy.getTriggerNodeName(), copy.getReadStatus(),
                toInstant(copy.getReadTime()), toInstant(copy.getCreateTime()));
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
     * 将 Flowable 历史状态和服务端业务状态规范为统一的小写状态。
     *
     * @param instance HistoricProcessInstance，历史流程实例
     * @param runtimeSuspended Boolean，运行时实例是否挂起；实例已结束时为空
     * @return String，稳定的流程业务状态或兼容的小写引擎状态
     */
    private String normalizeProcessState(HistoricProcessInstance instance,
            Boolean runtimeSuspended)
    {
        String engineState = runtimeSuspended == null ? instance.getState()
                : runtimeSuspended ? "suspended" : "running";
        return WorkflowProcessStatusNormalizer.normalize(instance.getBusinessStatus(),
                engineState, toInstant(instance.getEndTime()), instance.getDeleteReason());
    }

    /**
     * 规范管理员筛选中的可选若依用户主键，禁止负数、零值和非数字身份进入引擎查询。
     *
     * @param value String，页面身份目录返回或直接请求提交的可选用户主键
     * @return String，规范为十进制字符串的正整数用户主键或 null
     */
    private String normalizeOptionalUserId(String value)
    {
        String normalized = optionalText(value, "发起人主键不合法");
        if (normalized == null)
        {
            return null;
        }
        try
        {
            long userId = Long.parseLong(normalized);
            if (userId <= 0)
            {
                throw invalidArgument("发起人主键不合法");
            }
            return Long.toString(userId);
        }
        catch (NumberFormatException exception)
        {
            throw invalidArgument("发起人主键不合法");
        }
    }

    /**
     * 当前实例页转换视图所需的批量事实，禁止视图转换阶段再次访问引擎查询。
     *
     * @param runtimeTaskNames Map&lt;String, List&lt;String&gt;&gt;，实例 ID 到当前任务名称
     * @param deploymentCategories Map&lt;String, String&gt;，部署 ID 到正式业务分类
     * @param runtimeSuspensionStates Map&lt;String, Boolean&gt;，运行实例 ID 到挂起状态
     */
    private record InstancePageFacts(
            Map<String, List<String>> runtimeTaskNames,
            Map<String, String> deploymentCategories,
            Map<String, Boolean> runtimeSuspensionStates)
    {
    }

    /** 当前页用户名缓存，保持历史用户逐对象查询和原始主键回显规则不变。 */
    private static final class UserNameCache
    {
        /** 历史用户主键到显示名称的当前页映射。 */
        private final Map<String, String> userNames = new HashMap<>();
    }
}

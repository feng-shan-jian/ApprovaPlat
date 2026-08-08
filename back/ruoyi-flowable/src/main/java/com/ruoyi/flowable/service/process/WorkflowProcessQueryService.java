package com.ruoyi.flowable.service.process;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessSnapshot;
import com.ruoyi.flowable.domain.WfCopy;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.dto.WorkflowAssignedTaskQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowBpmnXmlQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowClaimableTaskQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowCompletedTaskQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowCopyQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowManagedProcessQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowOwnedProcessQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowProcessFormQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowStartableProcessQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowAssignedTaskView;
import com.ruoyi.flowable.domain.vo.WorkflowClaimableTaskView;
import com.ruoyi.flowable.domain.vo.WorkflowCompletedTaskView;
import com.ruoyi.flowable.domain.vo.WorkflowCopyView;
import com.ruoyi.flowable.domain.vo.WorkflowManagedProcessView;
import com.ruoyi.flowable.domain.vo.WorkflowOwnedProcessView;
import com.ruoyi.flowable.domain.vo.WorkflowPageResult;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormView;
import com.ruoyi.flowable.domain.vo.WorkflowStartMultiInstanceAssignmentView;
import com.ruoyi.flowable.domain.vo.WorkflowStartableDefinitionView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.flowable.mapper.WfDeployFormMapper;
import com.ruoyi.flowable.service.model.WorkflowDeploymentService;
import com.ruoyi.flowable.service.model.WorkflowFormSourceType;
import com.ruoyi.flowable.service.identity.WorkflowParticipantRuleRuntimeService;
import com.ruoyi.flowable.service.task.WorkflowTaskLifecycleService;
import com.ruoyi.flowable.service.task.WorkflowStartMultiInstanceContract;
import com.ruoyi.system.service.ISysUserService;

/**
 * 流程工作台七类列表、部署表单快照与 BPMN 可见性查询服务。
 */
@Service
public class WorkflowProcessQueryService
{
    /** 单页最大记录数，避免工作台查询被用作无界导出。 */
    static final int MAX_PAGE_SIZE = 200;

    /** 可发起定义授权过滤前允许扫描的最大基础定义数。 */
    static final int MAX_STARTABLE_SCAN = 10_000;

    /** 可发起定义每次从 Flowable 拉取的固定分块大小。 */
    static final int STARTABLE_SCAN_CHUNK = 200;

    /** 普通查询文本允许的最大字符数。 */
    private static final int MAX_FILTER_LENGTH = 255;

    /** 正式表单节点的批量默认字段权限属性主键。 */
    private static final String FORM_PERMISSION_DEFAULT_ID = "approva_permission_default";

    /** 正式表单节点的逐字段权限属性主键前缀。 */
    private static final String FORM_PERMISSION_FIELD_ID_PREFIX = "approva_permission_field_";

    private final WorkflowEngineOperations engineOperations;

    private final RepositoryService repositoryService;

    private final HistoryService historyService;

    private final RuntimeService runtimeService;

    private final TaskService taskService;

    private final WorkflowIdentityResolver identityResolver;

    private final WorkflowProcessAccessService processAccessService;

    private final WorkflowDeploymentService deploymentService;

    private final WfDeployFormMapper deployFormMapper;

    private final WfCopyMapper copyMapper;

    private final ISysUserService userService;

    private final WorkflowTaskLifecycleService taskLifecycleService;

    /** 部署快照发起范围只读过滤服务；旧直接构造单元测试时可为空。 */
    private WorkflowParticipantRuleRuntimeService participantRuleRuntimeService;

    /**
     * 创建流程工作台查询服务。
     *
     * @param engineOperations WorkflowEngineOperations，统一只读事务和异常翻译边界
     * @param repositoryService RepositoryService，Flowable 流程定义与部署公共 API
     * @param historyService HistoryService，Flowable 历史实例与历史任务公共 API
     * @param runtimeService RuntimeService，Flowable 实例实时挂起状态公共 API
     * @param taskService TaskService，Flowable 活动任务公共 API
     * @param identityResolver WorkflowIdentityResolver，当前有效用户及候选组解析器
     * @param processAccessService WorkflowProcessAccessService，实例对象级读取授权服务
     * @param deploymentService WorkflowDeploymentService，安全 BPMN 读取服务
     * @param deployFormMapper WfDeployFormMapper，不可变部署表单快照 Mapper
     * @param copyMapper WfCopyMapper，正式抄送记录 Mapper
     * @param userService ISysUserService，历史发起人显示名称查询服务
     * @param taskLifecycleService WorkflowTaskLifecycleService，复用正式撤回校验计算已办能力
     * @return 无返回值，构造后由 Spring 管理该服务
     */
    public WorkflowProcessQueryService(WorkflowEngineOperations engineOperations,
            RepositoryService repositoryService, HistoryService historyService,
            RuntimeService runtimeService, TaskService taskService,
            WorkflowIdentityResolver identityResolver,
             WorkflowProcessAccessService processAccessService,
             WorkflowDeploymentService deploymentService, WfDeployFormMapper deployFormMapper,
             WfCopyMapper copyMapper, ISysUserService userService,
             WorkflowTaskLifecycleService taskLifecycleService)
    {
        this.engineOperations = engineOperations;
        this.repositoryService = repositoryService;
        this.historyService = historyService;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.identityResolver = identityResolver;
        this.processAccessService = processAccessService;
        this.deploymentService = deploymentService;
        this.deployFormMapper = deployFormMapper;
        this.copyMapper = copyMapper;
        this.userService = userService;
        this.taskLifecycleService = taskLifecycleService;
    }

    /**
     * 延迟注入发起范围过滤服务，保留既有直接构造测试兼容性。
     * @param participantRuleRuntimeService WorkflowParticipantRuleRuntimeService，发起范围运行服务
     * @return void，生产 Spring 容器启动后必须完成注入
     */
    @Autowired
    public void setParticipantRuleRuntimeService(
            WorkflowParticipantRuleRuntimeService participantRuleRuntimeService)
    {
        this.participantRuleRuntimeService = participantRuleRuntimeService;
    }

    /**
     * 分页查询当前用户可发起的最新激活流程定义。
     *
     * @param filter WorkflowStartableProcessQueryDto，流程标识、名称和分类条件，允许为空
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return WorkflowPageResult&lt;WorkflowStartableDefinitionView&gt;，授权过滤后的真实分页结果
     */
    public WorkflowPageResult<WorkflowStartableDefinitionView> listStartable(
            WorkflowStartableProcessQueryDto filter, int pageNum, int pageSize)
    {
        PageWindow page = requirePage(pageNum, pageSize);
        return engineOperations.read(() ->
        {
            WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
            ProcessDefinitionQuery query = buildStartableBaseQuery(filter);
            long baseTotal = checkedCount(query.count());
            if (baseTotal == 0)
            {
                return new WorkflowPageResult<>(List.of(), 0);
            }
            if (baseTotal > MAX_STARTABLE_SCAN)
            {
                // Flowable 原生 startableByUserOrGroups 会漏掉无 starter 限制的公开定义，禁止截断扫描后返回虚假 total。
                throw new ServiceException("可发起流程范围过大，请增加筛选条件", HttpStatus.BAD_REQUEST);
            }
            return filterStartableDefinitions(query, actor, baseTotal, page);
        });
    }

    /**
     * 分页查询当前用户真实发起的流程实例。
     *
     * @param filter WorkflowOwnedProcessQueryDto，流程与开始时间条件，允许为空
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return WorkflowPageResult&lt;WorkflowOwnedProcessView&gt;，服务端固定发起人的分页结果
     */
    public WorkflowPageResult<WorkflowOwnedProcessView> listOwned(
            WorkflowOwnedProcessQueryDto filter, int pageNum, int pageSize)
    {
        PageWindow page = requirePage(pageNum, pageSize);
        return engineOperations.read(() ->
        {
            WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
            HistoricProcessInstanceQuery query = buildOwnedQuery(filter, actor.userId());
            long total = checkedCount(query.count());
            if (total == 0 || page.offset() >= total)
            {
                return new WorkflowPageResult<>(List.of(), total);
            }
            List<HistoricProcessInstance> instances = checkedRows(
                    query.listPage(page.offset(), page.pageSize()), page.pageSize());
            Map<String, Boolean> runtimeSuspensionStates =
                    loadRuntimeSuspensionStates(instances);
            List<WorkflowOwnedProcessView> rows = instances.stream()
                    .map(instance -> toOwnedView(instance,
                            runtimeSuspensionStates.get(instance.getId())))
                    .toList();
            return new WorkflowPageResult<>(rows, total);
        });
    }

    /**
     * 分页查询流程管理员可运维的全部历史与运行实例。
     *
     * @param filter WorkflowManagedProcessQueryDto，实例、定义、发起人和开始时间条件，允许为空
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return WorkflowPageResult&lt;WorkflowManagedProcessView&gt;，不按当前发起人缩小的管理员分页结果
     */
    public WorkflowPageResult<WorkflowManagedProcessView> listManaged(
            WorkflowManagedProcessQueryDto filter, int pageNum, int pageSize)
    {
        PageWindow page = requirePage(pageNum, pageSize);
        return engineOperations.read(() ->
        {
            // 即使 Controller 已做权限校验，也必须解析当前有效身份，禁止停用或删除账号继续读取全量实例。
            identityResolver.resolveCurrentIdentity();
            HistoricProcessInstanceQuery query = buildManagedQuery(filter);
            long total = checkedCount(query.count());
            if (total == 0 || page.offset() >= total)
            {
                return new WorkflowPageResult<>(List.of(), total);
            }
            List<HistoricProcessInstance> instances = checkedRows(
                    query.listPage(page.offset(), page.pageSize()), page.pageSize());
            Map<String, Boolean> runtimeSuspensionStates =
                    loadRuntimeSuspensionStates(instances);
            EnrichmentCache cache = new EnrichmentCache();
            List<WorkflowManagedProcessView> rows = instances.stream()
                    .map(instance -> toManagedView(instance, cache,
                            runtimeSuspensionStates.get(instance.getId())))
                    .toList();
            return new WorkflowPageResult<>(rows, total);
        });
    }

    /**
     * 分页查询当前用户作为 assignee 的活动待办任务。
     *
     * @param filter WorkflowAssignedTaskQueryDto，流程、任务和创建时间条件，允许为空
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return WorkflowPageResult&lt;WorkflowAssignedTaskView&gt;，仅包含当前办理人任务的分页结果
     */
    public WorkflowPageResult<WorkflowAssignedTaskView> listAssigned(
            WorkflowAssignedTaskQueryDto filter, int pageNum, int pageSize)
    {
        PageWindow page = requirePage(pageNum, pageSize);
        return engineOperations.read(() ->
        {
            WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
            TaskQuery query = buildAssignedQuery(filter, actor.userId());
            long total = checkedCount(query.count());
            if (total == 0 || page.offset() >= total)
            {
                return new WorkflowPageResult<>(List.of(), total);
            }
            List<Task> tasks = checkedRows(query.listPage(page.offset(), page.pageSize()), page.pageSize());
            EnrichmentCache cache = new EnrichmentCache();
            List<WorkflowAssignedTaskView> rows = tasks.stream()
                    .map(task -> toAssignedView(task, cache))
                    .toList();
            return new WorkflowPageResult<>(rows, total);
        });
    }

    /**
     * 分页查询当前用户或其有效 ROLE/DEPT 候选组可认领的未分配活动任务；
     * 当前用户缺少完整五项认领权限时返回真实空页。
     *
     * @param filter WorkflowClaimableTaskQueryDto，流程、任务和创建时间条件，允许为空
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return WorkflowPageResult&lt;WorkflowClaimableTaskView&gt;，直接候选用户与候选组并集分页结果
     */
    public WorkflowPageResult<WorkflowClaimableTaskView> listClaimable(
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
                return new WorkflowPageResult<>(List.of(), 0);
            }
            TaskQuery query = buildClaimableQuery(filter, actor);
            long total = checkedCount(query.count());
            if (total == 0 || page.offset() >= total)
            {
                return new WorkflowPageResult<>(List.of(), total);
            }
            List<Task> tasks = checkedRows(query.listPage(page.offset(), page.pageSize()), page.pageSize());
            EnrichmentCache cache = new EnrichmentCache();
            List<WorkflowClaimableTaskView> rows = tasks.stream()
                    .map(task -> toClaimableView(task, cache))
                    .toList();
            return new WorkflowPageResult<>(rows, total);
        });
    }

    /**
     * 分页查询 Flowable 记录为当前用户真实完成的历史任务。
     *
     * @param filter WorkflowCompletedTaskQueryDto，流程、任务和完成时间条件，允许为空
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return WorkflowPageResult&lt;WorkflowCompletedTaskView&gt;，按 completedBy 固定当前用户的分页结果
     */
    public WorkflowPageResult<WorkflowCompletedTaskView> listCompleted(
            WorkflowCompletedTaskQueryDto filter, int pageNum, int pageSize)
    {
        PageWindow page = requirePage(pageNum, pageSize);
        return engineOperations.read(() ->
        {
            WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
            HistoricTaskInstanceQuery query = buildCompletedQuery(filter, actor.userId());
            long total = checkedCount(query.count());
            if (total == 0 || page.offset() >= total)
            {
                return new WorkflowPageResult<>(List.of(), total);
            }
            List<HistoricTaskInstance> tasks = checkedRows(
                    query.listPage(page.offset(), page.pageSize()), page.pageSize());
            EnrichmentCache cache = new EnrichmentCache();
            List<WorkflowCompletedTaskView> rows = tasks.stream()
                    .map(task -> toCompletedView(task, cache))
                    .toList();
            return new WorkflowPageResult<>(rows, total);
        });
    }

    /**
     * 分页查询正式业务表中抄送给当前用户的有效记录。
     *
     * @param filter WorkflowCopyQueryDto，抄送业务条件，允许为空且不包含 userId
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return WorkflowPageResult&lt;WorkflowCopyView&gt;，服务端固定接收人的抄送分页结果
     */
    public WorkflowPageResult<WorkflowCopyView> listCopies(
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
                return new WorkflowPageResult<>(List.of(), total);
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
            return new WorkflowPageResult<>(rows, total);
        });
    }

    /**
     * 按定义、部署和可选实例关系返回开始节点的不可变部署表单快照。
     *
     * @param request WorkflowProcessFormQueryDto，定义、部署及可选实例关系参数
     * @return WorkflowProcessFormView，只来源于 wf_deploy_form.content 的部署快照
     */
    public WorkflowProcessFormView getProcessForm(WorkflowProcessFormQueryDto request)
    {
        if (request == null)
        {
            throw invalidArgument("流程表单查询参数不能为空");
        }
        String definitionId = requireText(request.definitionId(), "流程定义主键不能为空");
        String deploymentId = requireText(request.deploymentId(), "流程部署主键不能为空");
        String instanceId = optionalText(request.processInstanceId(), "流程实例主键过长");
        return engineOperations.read(() ->
        {
            ProcessDefinition definition = requireDefinition(definitionId, null);
            requireSame(deploymentId, definition.getDeploymentId(), "流程定义与部署关系不一致");
            if (instanceId == null)
            {
                WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
                assertStartableDefinition(definition, actor);
            }
            else
            {
                WorkflowProcessAccessSnapshot instance = processAccessService.requireReadableInstance(instanceId);
                requireInstanceRelation(instance, definition);
                requireSame(deploymentId, instance.deploymentId(), "流程实例与部署关系不一致");
            }
            WfDeployForm snapshot = requireStartFormSnapshot(definition, deploymentId);
            // 只有发起场景投影部署 BPMN 中的专用成员字段，实例详情不接受再次选人。
            List<WorkflowStartMultiInstanceAssignmentView> startAssignments = instanceId == null
                    ? WorkflowStartMultiInstanceContract.describe(
                            repositoryService.getBpmnModel(definitionId), definition.getKey())
                    : List.of();
            return new WorkflowProcessFormView(definitionId, deploymentId, instanceId,
                    snapshot.getSourceType(), snapshot.getFormId(), snapshot.getFormKey(), snapshot.getNodeKey(),
                    snapshot.getFormName(), snapshot.getNodeName(), snapshot.getContent(),
                    toInstant(snapshot.getCreateTime()), startAssignments);
        });
    }

    /**
     * 在发起场景执行可发起校验，或在详情场景执行实例对象授权后安全读取 BPMN XML。
     *
     * @param request WorkflowBpmnXmlQueryDto，定义主键及可选实例主键
     * @return String，经过 WorkflowDeploymentService 安全校验的 UTF-8 BPMN XML
     */
    public String getBpmnXml(WorkflowBpmnXmlQueryDto request)
    {
        if (request == null)
        {
            throw invalidArgument("BPMN 查询参数不能为空");
        }
        String definitionId = requireText(request.definitionId(), "流程定义主键不能为空");
        String instanceId = optionalText(request.processInstanceId(), "流程实例主键过长");
        return engineOperations.read(() ->
        {
            ProcessDefinition definition = requireDefinition(definitionId, null);
            if (instanceId == null)
            {
                WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
                assertStartableDefinition(definition, actor);
            }
            else
            {
                WorkflowProcessAccessSnapshot instance = processAccessService.requireReadableInstance(instanceId);
                requireInstanceRelation(instance, definition);
            }
            // XML 正文只经已有限流、安全解析和 Flowable 校验的部署服务输出。
            return deploymentService.getBpmnXml(definitionId);
        });
    }

    /**
     * 构造可发起定义的基础查询，不使用会漏掉公开定义的 startableByUserOrGroups。
     *
     * @param filter WorkflowStartableProcessQueryDto，流程定义条件，允许为空
     * @return ProcessDefinitionQuery，最新、激活且顺序确定的基础查询
     */
    private ProcessDefinitionQuery buildStartableBaseQuery(WorkflowStartableProcessQueryDto filter)
    {
        ProcessDefinitionQuery query = repositoryService.createProcessDefinitionQuery()
                .latestVersion()
                .active();
        if (filter != null)
        {
            String processKey = optionalText(filter.processKey(), "流程标识过长");
            String processName = optionalText(filter.processName(), "流程名称过长");
            String category = optionalText(filter.category(), "流程分类过长");
            if (processKey != null)
            {
                query.processDefinitionKeyLike("%" + processKey + "%");
            }
            if (processName != null)
            {
                query.processDefinitionNameLike("%" + processName + "%");
            }
            if (category != null)
            {
                query.processDefinitionCategory(category);
            }
        }
        return query.orderByProcessDefinitionKey().asc()
                .orderByProcessDefinitionId().asc();
    }

    /**
     * 分块扫描基础定义并按 starter identity link 计算真实可发起总数和当前页。
     *
     * @param query ProcessDefinitionQuery，已限定最新激活定义的基础查询
     * @param actor WorkflowCurrentIdentity，当前有效用户及候选组
     * @param baseTotal long，基础定义真实总数
     * @param page PageWindow，目标分页窗口
     * @return WorkflowPageResult&lt;WorkflowStartableDefinitionView&gt;，授权过滤后的真实分页结果
     */
    private WorkflowPageResult<WorkflowStartableDefinitionView> filterStartableDefinitions(
            ProcessDefinitionQuery query, WorkflowCurrentIdentity actor, long baseTotal, PageWindow page)
    {
        List<WorkflowStartableDefinitionView> rows = new ArrayList<>(page.pageSize());
        long visibleTotal = 0;
        int baseOffset = 0;
        while (baseOffset < baseTotal)
        {
            int batchLimit = (int) Math.min(STARTABLE_SCAN_CHUNK, baseTotal - baseOffset);
            List<ProcessDefinition> definitions = checkedRows(
                    query.listPage(baseOffset, batchLimit), batchLimit);
            if (definitions.isEmpty())
            {
                throw dataError("流程定义分页计数与结果不一致");
            }
            for (ProcessDefinition definition : definitions)
            {
                if (isDefinitionStartable(definition, actor))
                {
                    if (visibleTotal >= page.offset() && rows.size() < page.pageSize())
                    {
                        rows.add(toStartableView(definition));
                    }
                    visibleTotal++;
                }
            }
            baseOffset += definitions.size();
        }
        return new WorkflowPageResult<>(rows, visibleTotal);
    }

    /**
     * 判断流程定义对当前身份是否公开或存在匹配的候选发起用户、角色、部门。
     *
     * @param definition ProcessDefinition，待判定流程定义
     * @param actor WorkflowCurrentIdentity，当前有效用户及候选组
     * @return boolean，无 starter 限制或显式身份命中时返回 true
     */
    private boolean isDefinitionStartable(ProcessDefinition definition, WorkflowCurrentIdentity actor)
    {
        if (definition == null || !StringUtils.hasText(definition.getId()))
        {
            throw dataError("流程定义数据异常");
        }
        if (participantRuleRuntimeService != null)
        {
            // 新部署定义统一读取不可变规则快照，列表、表单预览与真实发起使用同一授权来源。
            Boolean snapshotDecision = participantRuleRuntimeService
                    .canStartIfManaged(actor, definition);
            if (snapshotDecision != null)
            {
                return snapshotDecision;
            }
        }
        List<IdentityLink> links = repositoryService.getIdentityLinksForProcessDefinition(definition.getId());
        if (links == null)
        {
            throw dataError("流程发起权限数据异常");
        }
        if (links.isEmpty())
        {
            return true;
        }
        Set<String> groups = actor.candidateGroups();
        for (IdentityLink link : links)
        {
            if (link != null && (actor.userId().equals(link.getUserId())
                    || groups.contains(link.getGroupId())))
            {
                return true;
            }
        }
        return false;
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
            String category = optionalText(filter.category(), "流程分类过长");
            String businessKey = optionalText(filter.businessKey(), "业务主键过长");
            if (processKey != null)
            {
                query.processDefinitionKey(processKey);
            }
            if (processName != null)
            {
                query.processDefinitionNameLike("%" + processName + "%");
            }
            if (category != null)
            {
                query.processDefinitionCategory(category);
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
            String category = optionalText(filter.category(), "流程分类过长");
            String businessKey = optionalText(filter.businessKey(), "业务主键过长");
            String startUserId = normalizeOptionalUserId(filter.startUserId());
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
            if (category != null)
            {
                query.processDefinitionCategory(category);
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
     * @param category String，流程分类精确条件，允许为空
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
        String normalizedCategory = optionalText(category, "流程分类过长");
        String normalizedTaskName = optionalText(taskName, "任务名称过长");
        if (normalizedKey != null)
        {
            query.processDefinitionKeyLike("%" + normalizedKey + "%");
        }
        if (normalizedProcessName != null)
        {
            query.processDefinitionNameLike("%" + normalizedProcessName + "%");
        }
        if (normalizedCategory != null)
        {
            query.processCategoryIn(List.of(normalizedCategory));
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
            String category = optionalText(filter.category(), "流程分类过长");
            String taskName = optionalText(filter.taskName(), "任务名称过长");
            if (processKey != null)
            {
                query.processDefinitionKeyLike("%" + processKey + "%");
            }
            if (processName != null)
            {
                query.processDefinitionNameLike("%" + processName + "%");
            }
            if (category != null)
            {
                query.processCategoryIn(List.of(category));
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
        trusted.setProcessId(optionalText(filter.processId(), "流程定义主键过长"));
        trusted.setProcessName(optionalText(filter.processName(), "流程名称过长"));
        trusted.setOriginatorName(optionalText(filter.originatorName(), "发起人名称过长"));
        trusted.setInstanceId(optionalText(filter.instanceId(), "流程实例主键过长"));
        trusted.setTaskId(optionalText(filter.taskId(), "任务主键过长"));
        trusted.setCategoryId(optionalText(filter.categoryId(), "流程分类过长"));
        trusted.setDeploymentId(optionalText(filter.deploymentId(), "部署主键过长"));
        return trusted;
    }

    /**
     * 将可发起流程定义及其部署转换为不可变视图。
     *
     * @param definition ProcessDefinition，已通过发起授权的最新激活定义
     * @return WorkflowStartableDefinitionView，可发起流程定义视图
     */
    private WorkflowStartableDefinitionView toStartableView(ProcessDefinition definition)
    {
        Deployment deployment = repositoryService.createDeploymentQuery()
                .deploymentId(definition.getDeploymentId())
                .singleResult();
        if (deployment == null)
        {
            throw dataError("流程定义缺少部署数据");
        }
        String category = StringUtils.hasText(definition.getCategory())
                ? definition.getCategory() : deployment.getCategory();
        return new WorkflowStartableDefinitionView(definition.getId(), definition.getKey(),
                definition.getName(), category, definition.getVersion(),
                definition.getDeploymentId(), toInstant(deployment.getDeploymentTime()));
    }

    /**
     * 将当前用户发起的历史实例转换为不可变工作台视图。
     *
     * @param instance HistoricProcessInstance，startedBy 已固定当前用户的历史实例
     * @param runtimeSuspended Boolean，运行时实例是否挂起；实例已结束时为空
     * @return WorkflowOwnedProcessView，含活动任务名称和稳定流程状态的视图
     */
    private WorkflowOwnedProcessView toOwnedView(HistoricProcessInstance instance,
            Boolean runtimeSuspended)
    {
        if (instance == null || !StringUtils.hasText(instance.getId()))
        {
            throw dataError("历史流程实例数据异常");
        }
        List<String> taskNames = loadRuntimeTaskNames(instance.getId());
        return new WorkflowOwnedProcessView(instance.getId(), instance.getProcessDefinitionId(),
                instance.getProcessDefinitionKey(), instance.getProcessDefinitionName(),
                safeVersion(instance.getProcessDefinitionVersion()), instance.getProcessDefinitionCategory(),
                instance.getDeploymentId(), instance.getBusinessKey(), instance.getStartUserId(),
                toInstant(instance.getStartTime()), toInstant(instance.getEndTime()),
                instance.getDurationInMillis(),
                normalizeProcessState(instance, runtimeSuspended), taskNames);
    }

    /**
     * 将任意发起人的历史实例转换为流程管理员运维视图。
     *
     * @param instance HistoricProcessInstance，管理员查询返回的历史实例
     * @param cache EnrichmentCache，当前页发起人显示名称缓存
     * @param runtimeSuspended Boolean，运行时实例是否挂起；实例已结束时为空
     * @return WorkflowManagedProcessView，含发起人、活动节点和稳定状态的运维视图
     */
    private WorkflowManagedProcessView toManagedView(HistoricProcessInstance instance,
            EnrichmentCache cache, Boolean runtimeSuspended)
    {
        if (instance == null || !StringUtils.hasText(instance.getId()))
        {
            throw dataError("历史流程实例数据异常");
        }
        List<String> taskNames = loadRuntimeTaskNames(instance.getId());
        String startUserName = resolveUserName(instance.getStartUserId(), cache);
        return new WorkflowManagedProcessView(instance.getId(), instance.getProcessDefinitionId(),
                instance.getProcessDefinitionKey(), instance.getProcessDefinitionName(),
                safeVersion(instance.getProcessDefinitionVersion()), instance.getProcessDefinitionCategory(),
                instance.getDeploymentId(), instance.getBusinessKey(), instance.getStartUserId(),
                startUserName, toInstant(instance.getStartTime()), toInstant(instance.getEndTime()),
                instance.getDurationInMillis(),
                normalizeProcessState(instance, runtimeSuspended), taskNames);
    }

    /**
     * 查询单个流程实例的全部运行时任务名称并执行数量和空对象门禁。
     * 挂起任务仍是未结束的当前环节，不能使用 active 过滤后错误显示为空。
     *
     * @param processInstanceId String，已经从历史实例读取的真实流程实例主键
     * @return List&lt;String&gt;，按创建时间和任务主键排序的不可变当前任务名称
     */
    private List<String> loadRuntimeTaskNames(String processInstanceId)
    {
        TaskQuery runtimeTaskQuery = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .orderByTaskCreateTime().asc()
                .orderByTaskId().asc();
        long runtimeTaskCount = checkedCount(runtimeTaskQuery.count());
        if (runtimeTaskCount > MAX_PAGE_SIZE)
        {
            throw dataError("单个流程实例的当前任务数量超过安全上限");
        }
        List<Task> checkedTasks = runtimeTaskCount == 0 ? List.of()
                : checkedRows(runtimeTaskQuery.listPage(0, (int) runtimeTaskCount),
                        (int) runtimeTaskCount);
        return checkedTasks.stream()
                .map(Task::getName)
                .filter(StringUtils::hasText)
                .toList();
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
     * 将当前办理人的活动任务转换为不可变待办视图。
     *
     * @param task Task，taskAssignee 已固定当前用户的活动任务
     * @param cache EnrichmentCache，当前页定义、实例和用户名缓存
     * @return WorkflowAssignedTaskView，活动待办视图
     */
    private WorkflowAssignedTaskView toAssignedView(Task task, EnrichmentCache cache)
    {
        TaskContext context = requireTaskContext(task, cache);
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
     * @param cache EnrichmentCache，当前页定义、实例和用户名缓存
     * @return WorkflowClaimableTaskView，未分配待签任务视图
     */
    private WorkflowClaimableTaskView toClaimableView(Task task, EnrichmentCache cache)
    {
        if (StringUtils.hasText(task.getAssignee()))
        {
            throw dataError("待签任务已经存在办理人");
        }
        TaskContext context = requireTaskContext(task, cache);
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
     * @param cache EnrichmentCache，当前页定义、实例和用户名缓存
     * @return WorkflowCompletedTaskView，真实已办任务视图
     */
    private WorkflowCompletedTaskView toCompletedView(HistoricTaskInstance task,
            EnrichmentCache cache)
    {
        TaskContext context = requireTaskContext(task, cache);
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
     * 查询并核验任务所属定义、实例和历史发起人，禁止伪造或缺失关联静默进入页面。
     *
     * @param task org.flowable.task.api.TaskInfo，活动或历史任务公共信息
     * @param cache EnrichmentCache，当前页关联对象缓存
     * @return TaskContext，核验后的定义、实例、分类及发起人名称
     */
    private TaskContext requireTaskContext(org.flowable.task.api.TaskInfo task,
            EnrichmentCache cache)
    {
        if (task == null || !StringUtils.hasText(task.getId())
                || !StringUtils.hasText(task.getProcessDefinitionId())
                || !StringUtils.hasText(task.getProcessInstanceId()))
        {
            throw dataError("任务关联数据异常");
        }
        ProcessDefinition definition = requireDefinition(task.getProcessDefinitionId(), cache);
        HistoricProcessInstance instance = requireHistoricInstance(task.getProcessInstanceId(), cache);
        requireSame(definition.getId(), instance.getProcessDefinitionId(), "任务与流程实例定义关系不一致");
        requireSame(definition.getDeploymentId(), instance.getDeploymentId(), "任务与流程实例部署关系不一致");
        String category = StringUtils.hasText(definition.getCategory())
                ? definition.getCategory() : instance.getProcessDefinitionCategory();
        String startUserName = resolveUserName(instance.getStartUserId(), cache);
        return new TaskContext(definition, instance, category, startUserName);
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
                copy.getOriginatorName(), toInstant(copy.getCreateTime()));
    }

    /**
     * 校验定义为同一租户下最新激活版本且当前身份具备真实发起权限。
     *
     * @param definition ProcessDefinition，待发起或预览的流程定义
     * @param actor WorkflowCurrentIdentity，当前有效用户及候选组
     * @return 无返回值，非法版本、状态或权限分别抛出稳定业务异常
     */
    private void assertStartableDefinition(ProcessDefinition definition, WorkflowCurrentIdentity actor)
    {
        ProcessDefinitionQuery latestQuery = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(definition.getKey())
                .latestVersion();
        if (StringUtils.hasText(definition.getTenantId()))
        {
            latestQuery.processDefinitionTenantId(definition.getTenantId());
        }
        else
        {
            latestQuery.processDefinitionWithoutTenantId();
        }
        ProcessDefinition latest = latestQuery.singleResult();
        if (latest == null || !definition.getId().equals(latest.getId()))
        {
            throw new ServiceException("流程定义不是最新版本", HttpStatus.CONFLICT);
        }
        if (definition.isSuspended())
        {
            throw new ServiceException("流程定义已挂起", HttpStatus.CONFLICT);
        }
        if (!isDefinitionStartable(definition, actor))
        {
            throw new ServiceException("当前用户无权发起该流程", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 查询并核验指定定义开始节点对应的唯一部署表单快照。
     *
     * @param definition ProcessDefinition，已完成关系与权限校验的流程定义
     * @param deploymentId String，定义所属真实部署主键
     * @return WfDeployForm，仅来自 wf_deploy_form 的开始节点快照
     */
    private WfDeployForm requireStartFormSnapshot(ProcessDefinition definition, String deploymentId)
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
        List<StartEvent> startEvents = process.findFlowElementsOfType(StartEvent.class, true);
        if (startEvents == null || startEvents.size() != 1)
        {
            throw dataError("流程开始节点数据异常");
        }
        StartEvent startEvent = startEvents.get(0);
        if (!StringUtils.hasText(startEvent.getId()))
        {
            throw dataError("流程开始节点缺少表单配置");
        }
        String formKey = resolveFormKey(startEvent);
        List<WfDeployForm> snapshots = deployFormMapper.selectByDeploymentId(deploymentId);
        if (snapshots == null)
        {
            throw dataError("部署表单快照查询异常");
        }
        List<WfDeployForm> matches = snapshots.stream()
                .filter(snapshot -> snapshot != null
                        && deploymentId.equals(snapshot.getDeployId())
                        && startEvent.getId().equals(snapshot.getNodeKey())
                        && formKey.equals(snapshot.getFormKey()))
                .toList();
        if (matches.size() != 1)
        {
            throw dataError("流程开始表单快照不存在或不唯一");
        }
        WfDeployForm snapshot = matches.get(0);
        if (!WorkflowFormSourceType.isConsistent(snapshot.getSourceType(), snapshot.getFormId())
                || !StringUtils.hasText(snapshot.getContent()))
        {
            throw dataError("流程开始表单快照内容异常");
        }
        return snapshot;
    }

    /**
     * 解析开始节点实际使用的部署表单键，并区分正式模板权限元数据与内嵌 FormData。
     *
     * @param startEvent StartEvent，定义中唯一开始节点
     * @return String，正式模板原始 formKey 或内嵌表单稳定键
     */
    private String resolveFormKey(StartEvent startEvent)
    {
        boolean hasTemplate = StringUtils.hasText(startEvent.getFormKey());
        boolean hasProperties = startEvent.getFormProperties() != null
                && !startEvent.getFormProperties().isEmpty();
        if (hasTemplate && hasProperties
                && !hasOnlyTemplatePermissionProperties(startEvent))
        {
            // 正式表单仅允许携带部署时已校验的字段权限描述，普通 FormData 仍属于双重来源。
            throw dataError("流程开始节点表单来源异常");
        }
        boolean hasEmbedded = !hasTemplate && hasProperties;
        if (hasTemplate == hasEmbedded)
        {
            throw dataError("流程开始节点表单来源异常");
        }
        return hasTemplate ? startEvent.getFormKey()
                : WorkflowFormSourceType.EMBEDDED_FORM_KEY;
    }

    /**
     * 判断正式表单开始节点的全部 FormProperty 是否均为受控字段权限描述。
     *
     * @param startEvent StartEvent，已绑定正式 formKey 且携带 FormProperty 的开始节点
     * @return boolean，属性主键全部属于批量默认或逐字段权限命名空间时返回 true
     */
    private boolean hasOnlyTemplatePermissionProperties(StartEvent startEvent)
    {
        return startEvent.getFormProperties().stream().allMatch(property ->
        {
            if (property == null || !StringUtils.hasText(property.getId()))
            {
                return false;
            }
            String propertyId = property.getId().trim();
            return FORM_PERMISSION_DEFAULT_ID.equals(propertyId)
                    || propertyId.startsWith(FORM_PERMISSION_FIELD_ID_PREFIX);
        });
    }

    /**
     * 核验实例授权快照与请求定义、部署的服务端真实关联。
     *
     * @param instance WorkflowProcessAccessSnapshot，已通过对象授权的实例快照
     * @param definition ProcessDefinition，客户端声明并由服务端查询的定义
     * @return 无返回值，任何关联不一致时拒绝请求
     */
    private void requireInstanceRelation(WorkflowProcessAccessSnapshot instance,
            ProcessDefinition definition)
    {
        requireSame(definition.getId(), instance.processDefinitionId(), "流程实例与定义关系不一致");
        requireSame(definition.getDeploymentId(), instance.deploymentId(), "流程实例与部署关系不一致");
    }

    /**
     * 查询流程定义，并在当前页内复用已核验结果。
     *
     * @param definitionId String，流程定义主键
     * @param cache EnrichmentCache，当前页缓存；单对象查询时允许为空
     * @return ProcessDefinition，存在且主键有效的流程定义
     */
    private ProcessDefinition requireDefinition(String definitionId, EnrichmentCache cache)
    {
        if (!StringUtils.hasText(definitionId))
        {
            throw dataError("流程定义关联主键为空");
        }
        if (cache != null && cache.definitions.containsKey(definitionId))
        {
            return cache.definitions.get(definitionId);
        }
        ProcessDefinition definition = repositoryService.getProcessDefinition(definitionId);
        if (definition == null)
        {
            throw new ServiceException("流程定义不存在或已被删除", HttpStatus.NOT_FOUND);
        }
        if (cache != null)
        {
            cache.definitions.put(definitionId, definition);
        }
        return definition;
    }

    /**
     * 查询任务所属历史实例，并在当前页内复用已核验结果。
     *
     * @param instanceId String，流程实例主键
     * @param cache EnrichmentCache，当前页关联对象缓存
     * @return HistoricProcessInstance，任务所属历史实例
     */
    private HistoricProcessInstance requireHistoricInstance(String instanceId, EnrichmentCache cache)
    {
        if (cache.instances.containsKey(instanceId))
        {
            return cache.instances.get(instanceId);
        }
        HistoricProcessInstance instance = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(instanceId)
                .singleResult();
        if (instance == null)
        {
            throw dataError("任务缺少所属历史流程实例");
        }
        cache.instances.put(instanceId, instance);
        return instance;
    }

    /**
     * 查询历史发起人的当前显示名称，缺失用户以原始主键回显而不篡改历史关联。
     *
     * @param userId String，Flowable 历史发起人主键，允许为空
     * @param cache EnrichmentCache，当前页用户名缓存
     * @return String，用户昵称、原始主键或 null
     */
    private String resolveUserName(String userId, EnrichmentCache cache)
    {
        if (!StringUtils.hasText(userId))
        {
            return null;
        }
        if (cache.userNames.containsKey(userId))
        {
            return cache.userNames.get(userId);
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
        cache.userNames.put(userId, displayName);
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
     * 校验页码、页大小和整数偏移边界。
     *
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return PageWindow，可直接传给 Flowable listPage 或 Mapper 的安全窗口
     */
    private PageWindow requirePage(int pageNum, int pageSize)
    {
        if (pageNum <= 0 || pageSize <= 0 || pageSize > MAX_PAGE_SIZE)
        {
            throw invalidArgument("分页参数不合法");
        }
        long offset = (long) (pageNum - 1) * pageSize;
        if (offset > Integer.MAX_VALUE)
        {
            throw invalidArgument("分页偏移量过大");
        }
        return new PageWindow((int) offset, pageSize);
    }

    /**
     * 校验查询返回的总数非负。
     *
     * @param count long，Flowable 或 Mapper 返回的计数
     * @return long，原始非负计数
     */
    private long checkedCount(long count)
    {
        if (count < 0)
        {
            throw dataError("工作流分页总数异常");
        }
        return count;
    }

    /**
     * 校验分页查询结果非空引用且未超过请求上限。
     *
     * @param rows List&lt;T&gt;，Flowable 或 Mapper 返回的当前页
     * @param limit int，本次请求最大记录数
     * @param <T> 当前页数据类型
     * @return List&lt;T&gt;，原始合法结果集合
     */
    private <T> List<T> checkedRows(List<T> rows, int limit)
    {
        if (rows == null || rows.size() > limit)
        {
            throw dataError("工作流分页结果异常");
        }
        return rows;
    }

    /**
     * 校验时间范围下界不得晚于上界。
     *
     * @param lower Instant，时间下界，允许为空
     * @param upper Instant，时间上界，允许为空
     * @param message String，校验失败的稳定提示
     * @return 无返回值，范围非法时抛出 400
     */
    private void validateRange(Instant lower, Instant upper, String message)
    {
        if (lower != null && upper != null && lower.isAfter(upper))
        {
            throw invalidArgument(message);
        }
    }

    /**
     * 校验必填文本并规范首尾空白。
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
     * 规范可选文本并限制查询长度。
     *
     * @param value String，允许为空的待规范文本
     * @param message String，文本过长时稳定提示
     * @return String，去除首尾空白的文本或 null
     */
    private String optionalText(String value, String message)
    {
        if (!StringUtils.hasText(value))
        {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_FILTER_LENGTH)
        {
            throw invalidArgument(message);
        }
        return normalized;
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
     * 校验两个服务端关系主键完全一致。
     *
     * @param expected String，可信对象中的期望主键
     * @param actual String，待核验对象中的实际主键
     * @param message String，关系不一致的稳定提示
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
     * 将可空 Date 转换为不可变 Instant。
     *
     * @param value Date，引擎或业务实体时间，允许为空
     * @return Instant，不可变时间或 null
     */
    private Instant toInstant(Date value)
    {
        return value == null ? null : value.toInstant();
    }

    /**
     * 将可空流程版本转换为视图整数，缺失版本视为关联数据异常。
     *
     * @param version Integer，Flowable 历史定义版本
     * @return int，非负流程版本
     */
    private int safeVersion(Integer version)
    {
        if (version == null || version < 0)
        {
            throw dataError("历史流程定义版本异常");
        }
        return version;
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
     * 创建引擎与业务对象关联数据异常。
     *
     * @param message String，稳定业务提示
     * @return ServiceException，HTTP 500 业务异常
     */
    private ServiceException dataError(String message)
    {
        return new ServiceException(message, HttpStatus.ERROR);
    }

    /**
     * 安全分页窗口。
     *
     * @param offset int，从零开始的分页偏移
     * @param pageSize int，每页最大记录数
     */
    private record PageWindow(int offset, int pageSize)
    {
    }

    /**
     * 已完成服务端关系核验的任务上下文。
     *
     * @param definition ProcessDefinition，任务所属流程定义
     * @param instance HistoricProcessInstance，任务所属历史实例
     * @param category String，定义或历史实例中的流程分类
     * @param startUserName String，历史发起人显示名称
     */
    private record TaskContext(ProcessDefinition definition, HistoricProcessInstance instance,
            String category, String startUserName)
    {
    }

    /** 当前页关联对象缓存，避免同一实例的并行任务重复查询定义、实例和用户。 */
    private static final class EnrichmentCache
    {
        /** 流程定义主键到定义对象的当前页映射。 */
        private final Map<String, ProcessDefinition> definitions = new HashMap<>();

        /** 流程实例主键到历史实例的当前页映射。 */
        private final Map<String, HistoricProcessInstance> instances = new HashMap<>();

        /** 历史用户主键到显示名称的当前页映射。 */
        private final Map<String, String> userNames = new HashMap<>();
    }
}

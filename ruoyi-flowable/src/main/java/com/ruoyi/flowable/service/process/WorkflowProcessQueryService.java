package com.ruoyi.flowable.service.process;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import org.flowable.task.api.TaskInfo;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.page.PageResult;
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
import com.ruoyi.flowable.domain.vo.WorkflowCompletedTaskExportView;
import com.ruoyi.flowable.domain.vo.WorkflowCompletedTaskView;
import com.ruoyi.flowable.domain.vo.WorkflowCopyView;
import com.ruoyi.flowable.domain.vo.WorkflowManagedProcessView;
import com.ruoyi.flowable.domain.vo.WorkflowOwnedProcessView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormView;
import com.ruoyi.flowable.domain.vo.WorkflowStartMultiInstanceAssignmentView;
import com.ruoyi.flowable.domain.vo.WorkflowStartableDefinitionView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
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

    /** 单个流程实例允许返回的最大当前任务数。 */
    private static final int MAX_RUNTIME_TASKS_PER_INSTANCE = 200;

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

    private final WorkflowDeploymentArtifactRepository artifactRepository;

    private final WfCopyMapper copyMapper;

    private final ISysUserService userService;

    private final WorkflowTaskLifecycleService taskLifecycleService;

    /** 部署快照发起范围只读过滤服务。 */
    private final WorkflowParticipantRuleRuntimeService participantRuleRuntimeService;

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
     * @param artifactRepository WorkflowDeploymentArtifactRepository，不可变部署表单资源仓库
     * @param copyMapper WfCopyMapper，正式抄送记录 Mapper
     * @param userService ISysUserService，历史发起人显示名称查询服务
     * @param taskLifecycleService WorkflowTaskLifecycleService，复用正式撤回校验计算已办能力
     * @param participantRuleRuntimeService WorkflowParticipantRuleRuntimeService，发起范围运行服务
     * @return 无返回值，构造后由 Spring 管理该服务
     */
    public WorkflowProcessQueryService(WorkflowEngineOperations engineOperations,
            RepositoryService repositoryService, HistoryService historyService,
            RuntimeService runtimeService, TaskService taskService,
            WorkflowIdentityResolver identityResolver,
             WorkflowProcessAccessService processAccessService,
             WorkflowDeploymentService deploymentService,
              WorkflowDeploymentArtifactRepository artifactRepository,
              WfCopyMapper copyMapper, ISysUserService userService,
              WorkflowTaskLifecycleService taskLifecycleService,
              WorkflowParticipantRuleRuntimeService participantRuleRuntimeService)
    {
        this.engineOperations = engineOperations;
        this.repositoryService = repositoryService;
        this.historyService = historyService;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.identityResolver = identityResolver;
        this.processAccessService = processAccessService;
        this.deploymentService = deploymentService;
        this.artifactRepository = artifactRepository;
        this.copyMapper = copyMapper;
        this.userService = userService;
        this.taskLifecycleService = taskLifecycleService;
        this.participantRuleRuntimeService = participantRuleRuntimeService;
    }

    /**
     * 分页查询当前用户可发起的最新激活流程定义。
     *
     * @param filter WorkflowStartableProcessQueryDto，流程标识、名称和分类条件，允许为空
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return PageResult&lt;WorkflowStartableDefinitionView&gt;，授权过滤后的真实分页结果
     */
    public PageResult<WorkflowStartableDefinitionView> listStartable(
            WorkflowStartableProcessQueryDto filter, int pageNum, int pageSize)
    {
        PageWindow page = requirePage(pageNum, pageSize);
        return engineOperations.read(() ->
        {
            WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
            StartableDefinitionSelection selection = scanStartableDefinitions(filter, actor, page);
            return new PageResult<>(toStartableViews(selection.definitions()), selection.visibleTotal());
        });
    }

    /**
     * 一次扫描导出当前用户可发起的全部有界最新激活流程定义。
     *
     * @param filter WorkflowStartableProcessQueryDto，流程标识、名称和分类条件，允许为空
     * @return List&lt;WorkflowStartableDefinitionView&gt;，与分页列表权限和稳定顺序完全一致的不可变全集
     */
    public List<WorkflowStartableDefinitionView> listStartableForExport(
            WorkflowStartableProcessQueryDto filter)
    {
        return engineOperations.read(() ->
        {
            WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
            StartableDefinitionSelection selection = scanStartableDefinitions(
                    filter, actor, new PageWindow(0, MAX_STARTABLE_SCAN));
            return toStartableViews(selection.definitions());
        });
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
            Set<String> categoryDeploymentIds = resolveCategoryDeploymentIds(
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
            Set<String> categoryDeploymentIds = resolveCategoryDeploymentIds(
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
            Set<String> categoryDeploymentIds = resolveCategoryDeploymentIds(
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
            Set<String> categoryDeploymentIds = resolveCategoryDeploymentIds(
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
     * 按定义、部署和可选实例关系返回开始节点的不可变部署表单快照。
     *
     * @param request WorkflowProcessFormQueryDto，定义、部署及可选实例关系参数
     * @return WorkflowProcessFormView，只来源于 Flowable 业务制品 forms-v1.json 的部署快照
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
            ProcessDefinition definition = requireDefinition(definitionId);
            requireSame(deploymentId, definition.getDeploymentId(), "流程定义与部署关系不一致");
            if (instanceId == null)
            {
                WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
                assertLatestActiveDefinition(definition);
                assertPreviewStartPermission(definition, actor);
                return loadStartForm(definition).form();
            }
            WorkflowProcessAccessSnapshot instance = processAccessService.requireReadableInstance(instanceId);
            requireInstanceRelation(instance, definition);
            requireSame(deploymentId, instance.deploymentId(), "流程实例与部署关系不一致");
            BpmnModel model = requireBpmnModel(definition);
            WfDeployForm snapshot = requireStartFormSnapshot(definition, deploymentId, model);
            return new WorkflowProcessFormView(definitionId, deploymentId, instanceId,
                    snapshot.getSourceType(), snapshot.getFormId(), snapshot.getFormKey(), snapshot.getNodeKey(),
                    snapshot.getFormName(), snapshot.getNodeName(), snapshot.getContent(),
                    toInstant(snapshot.getCreateTime()), List.of());
        });
    }

    /**
     * 在调用方已经建立的当前用户事务中一次完成正式发起授权、BPMN 与开始表单装载。
     *
     * @param actor WorkflowCurrentIdentity，外层写事务已经核验的当前有效身份
     * @param definition ProcessDefinition，调用方从 Flowable 仓库取得的真实流程定义
     * @return StartFormLoad，同一次仓库读取的 BPMN 模型与不可变开始表单视图
     */
    StartFormLoad loadStartFormInCurrentTransaction(WorkflowCurrentIdentity actor,
            ProcessDefinition definition)
    {
        if (actor == null || definition == null
                || !StringUtils.hasText(definition.getId())
                || !StringUtils.hasText(definition.getDeploymentId()))
        {
            throw dataError("流程发起上下文数据异常");
        }
        assertLatestActiveDefinition(definition);
        assertStartPermissionForWrite(definition, actor);
        return loadStartForm(definition);
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
            ProcessDefinition definition = requireDefinition(definitionId);
            if (instanceId == null)
            {
                WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
                assertLatestActiveDefinition(definition);
                assertPreviewStartPermission(definition, actor);
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
            // 分类查询可能返回空集合，必须先校验其余条件，禁止用空页掩盖非法请求。
            optionalText(filter.category(), "流程分类过长");
            if (processKey != null)
            {
                query.processDefinitionKeyLike("%" + processKey + "%");
            }
            if (processName != null)
            {
                query.processDefinitionNameLike("%" + processName + "%");
            }
        }
        return query.orderByProcessDefinitionKey().asc()
                .orderByProcessDefinitionId().asc();
    }

    /**
     * 构造、分块读取并一次授权扫描可发起定义，供分页列表和导出共享。
     *
     * @param filter WorkflowStartableProcessQueryDto，流程定义条件，允许为空
     * @param actor WorkflowCurrentIdentity，当前有效用户及候选组
     * @param page PageWindow，本次需要收集的可见结果窗口
     * @return StartableDefinitionSelection，真实可见总数及当前窗口定义
     */
    private StartableDefinitionSelection scanStartableDefinitions(
            WorkflowStartableProcessQueryDto filter, WorkflowCurrentIdentity actor, PageWindow page)
    {
        ProcessDefinitionQuery query = buildStartableBaseQuery(filter);
        // categoryDeploymentIds 为 null 表示未筛选，空集合表示正式部署中没有该业务分类。
        Set<String> categoryDeploymentIds = resolveCategoryDeploymentIds(
                filter == null ? null : filter.category());
        if (categoryDeploymentIds != null && categoryDeploymentIds.isEmpty())
        {
            return new StartableDefinitionSelection(List.of(), 0);
        }
        if (categoryDeploymentIds != null)
        {
            // 业务分类属于部署元数据，不能用可能保存 targetNamespace 的定义分类筛选。
            query.deploymentIds(categoryDeploymentIds);
        }
        long baseTotal = checkedCount(query.count());
        if (baseTotal == 0)
        {
            return new StartableDefinitionSelection(List.of(), 0);
        }
        if (baseTotal > MAX_STARTABLE_SCAN)
        {
            // Flowable 原生 startableByUserOrGroups 会漏掉公开定义，禁止截断扫描后返回虚假 total。
            throw new ServiceException("可发起流程范围过大，请增加筛选条件", HttpStatus.BAD_REQUEST);
        }

        List<ProcessDefinition> definitions = loadStartableDefinitions(query, baseTotal);
        // 整批快照授权只调用一次；Map 缺席的定义才允许进入历史 starter identity link 兜底。
        Map<String, Boolean> managedDecisions = participantRuleRuntimeService
                .resolveManagedStartDecisions(actor, definitions);
        if (managedDecisions == null)
        {
            throw dataError("流程发起范围批量判定结果异常");
        }

        List<ProcessDefinition> selected = new ArrayList<>(
                Math.min(page.pageSize(), definitions.size()));
        long visibleTotal = 0;
        for (ProcessDefinition definition : definitions)
        {
            if (!isDefinitionStartable(definition, actor, managedDecisions))
            {
                continue;
            }
            if (visibleTotal >= page.offset() && selected.size() < page.pageSize())
            {
                selected.add(definition);
            }
            visibleTotal++;
        }
        return new StartableDefinitionSelection(List.copyOf(selected), visibleTotal);
    }

    /**
     * 按固定 200 条分块装载一次扫描的全部有界基础定义。
     *
     * @param query ProcessDefinitionQuery，已限定最新、激活、分类和文本筛选的稳定查询
     * @param baseTotal long，执行扫描前取得的真实基础定义总数
     * @return List&lt;ProcessDefinition&gt;，按流程 key、定义 ID 排序且数量与 count 一致的不可变定义集合
     */
    private List<ProcessDefinition> loadStartableDefinitions(
            ProcessDefinitionQuery query, long baseTotal)
    {
        List<ProcessDefinition> definitions = new ArrayList<>((int) baseTotal);
        int baseOffset = 0;
        while (baseOffset < baseTotal)
        {
            int batchLimit = (int) Math.min(STARTABLE_SCAN_CHUNK, baseTotal - baseOffset);
            List<ProcessDefinition> batch = checkedRows(
                    query.listPage(baseOffset, batchLimit), batchLimit);
            if (batch.isEmpty())
            {
                throw dataError("流程定义分页计数与结果不一致");
            }
            definitions.addAll(batch);
            baseOffset += batch.size();
        }
        if (definitions.size() != baseTotal)
        {
            throw dataError("流程定义分页计数与结果不一致");
        }
        return List.copyOf(definitions);
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
        // 新部署定义统一读取不可变规则快照，列表、表单预览与真实发起使用同一授权来源。
        Boolean snapshotDecision = participantRuleRuntimeService
                .canStartIfManaged(actor, definition);
        if (snapshotDecision != null)
        {
            return snapshotDecision;
        }
        return isHistoricalDefinitionStartable(definition, actor);
    }

    /**
     * 使用整批部署快照决定判断定义权限，只有决定缺席时才执行历史兼容查询。
     *
     * @param definition ProcessDefinition，当前有界扫描中的流程定义
     * @param actor WorkflowCurrentIdentity，当前有效用户及候选组
     * @param managedDecisions Map&lt;String, Boolean&gt;，受管定义的正式整批授权决定
     * @return boolean，新版正式决定或历史 starter identity link 兼容结果
     */
    private boolean isDefinitionStartable(ProcessDefinition definition, WorkflowCurrentIdentity actor,
            Map<String, Boolean> managedDecisions)
    {
        if (definition == null || !StringUtils.hasText(definition.getId()))
        {
            throw dataError("流程定义数据异常");
        }
        if (managedDecisions.containsKey(definition.getId()))
        {
            Boolean decision = managedDecisions.get(definition.getId());
            if (decision == null)
            {
                throw dataError("流程发起范围批量判定结果异常");
            }
            // 新版 false 是正式拒绝，绝对不能继续进入历史 starter identity link 兜底。
            return decision;
        }
        return isHistoricalDefinitionStartable(definition, actor);
    }

    /**
     * 对历史未托管定义执行原 Flowable starter identity link 发起权限兼容。
     *
     * @param definition ProcessDefinition，已确认没有业务资源子部署的历史定义
     * @param actor WorkflowCurrentIdentity，当前有效用户及候选组
     * @return boolean，无 starter 限制或显式用户、候选组命中时返回 true
     */
    private boolean isHistoricalDefinitionStartable(
            ProcessDefinition definition, WorkflowCurrentIdentity actor)
    {
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
        Set<String> categoryDeploymentIds = resolveCategoryDeploymentIds(
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
     * 将业务分类编码解析为 Flowable 正式部署主键集合。
     *
     * @param category String，分类目录提交的业务分类编码，允许为空
     * @return Set&lt;String&gt;，null 表示未启用分类筛选；空集合表示分类下没有部署
     */
    private Set<String> resolveCategoryDeploymentIds(String category)
    {
        String normalizedCategory = optionalText(category, "流程分类过长");
        if (normalizedCategory == null)
        {
            return null;
        }
        List<Deployment> deployments = repositoryService.createDeploymentQuery()
                .deploymentCategory(normalizedCategory)
                .list();
        if (deployments == null)
        {
            throw dataError("流程分类部署查询结果异常");
        }
        // deploymentIds 是分类筛选最终写入定义/任务原生查询的正式部署范围。
        Set<String> deploymentIds = new LinkedHashSet<>();
        for (Deployment deployment : deployments)
        {
            if (deployment == null || !StringUtils.hasText(deployment.getId())
                    || !normalizedCategory.equals(deployment.getCategory())
                    || !deploymentIds.add(deployment.getId()))
            {
                throw dataError("流程分类部署数据异常");
            }
        }
        return Set.copyOf(deploymentIds);
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
     * 批量装载可见定义的部署并转换为不可变视图，转换阶段不再逐行访问引擎。
     *
     * @param definitions List&lt;ProcessDefinition&gt;，已通过发起授权且保持稳定顺序的定义
     * @return List&lt;WorkflowStartableDefinitionView&gt;，分类、部署时间和部署主键完整的不可变视图列表
     */
    private List<WorkflowStartableDefinitionView> toStartableViews(
            List<ProcessDefinition> definitions)
    {
        if (definitions.isEmpty())
        {
            return List.of();
        }
        Map<String, Deployment> deployments = loadStartableDeployments(definitions);
        List<WorkflowStartableDefinitionView> rows = new ArrayList<>(definitions.size());
        for (ProcessDefinition definition : definitions)
        {
            Deployment deployment = deployments.get(definition.getDeploymentId());
            if (deployment == null)
            {
                throw dataError("流程定义缺少部署数据");
            }
            // 只返回发布事务写入的正式部署分类，定义分类可能是 targetNamespace，不能作为回退值。
            String category = resolveDeploymentCategory(deployment.getCategory());
            rows.add(new WorkflowStartableDefinitionView(definition.getId(), definition.getKey(),
                    definition.getName(), category, definition.getVersion(),
                    definition.getDeploymentId(), toInstant(deployment.getDeploymentTime())));
        }
        return List.copyOf(rows);
    }

    /**
     * 按最多 200 个部署主键批量读取可发起视图需要的部署元数据。
     *
     * 当前分页最多 200 行，因此只产生一次 deploymentIds 查询；导出超过 200 行时按相同上限分块。
     *
     * @param definitions List&lt;ProcessDefinition&gt;，待投影的已授权定义
     * @return Map&lt;String, Deployment&gt;，部署主键到唯一 Flowable 部署的不可变映射
     */
    private Map<String, Deployment> loadStartableDeployments(List<ProcessDefinition> definitions)
    {
        LinkedHashSet<String> deploymentIds = new LinkedHashSet<>();
        for (ProcessDefinition definition : definitions)
        {
            if (definition == null || !StringUtils.hasText(definition.getDeploymentId()))
            {
                throw dataError("流程定义部署关系异常");
            }
            deploymentIds.add(definition.getDeploymentId());
        }

        List<String> orderedIds = new ArrayList<>(deploymentIds);
        LinkedHashMap<String, Deployment> deployments = new LinkedHashMap<>();
        for (int offset = 0; offset < orderedIds.size(); offset += STARTABLE_SCAN_CHUNK)
        {
            int end = Math.min(offset + STARTABLE_SCAN_CHUNK, orderedIds.size());
            List<String> batchIds = orderedIds.subList(offset, end);
            List<Deployment> batch = repositoryService.createDeploymentQuery()
                    .deploymentIds(new ArrayList<>(batchIds))
                    .list();
            if (batch == null || batch.size() > batchIds.size())
            {
                throw dataError("流程部署批量查询结果异常");
            }
            Set<String> requestedBatchIds = Set.copyOf(batchIds);
            for (Deployment deployment : batch)
            {
                if (deployment == null || !StringUtils.hasText(deployment.getId())
                        || !requestedBatchIds.contains(deployment.getId())
                        || deployments.put(deployment.getId(), deployment) != null)
                {
                    throw dataError("流程部署批量查询结果异常");
                }
            }
        }
        if (deployments.size() != deploymentIds.size())
        {
            throw dataError("流程定义缺少部署数据");
        }
        return Map.copyOf(deployments);
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
     * 校验定义仍是同一租户下的最新激活版本，不混入任何授权副作用。
     *
     * @param definition ProcessDefinition，待发起或预览的真实流程定义
     * @return void，非最新版本或挂起状态分别抛出既有 409 异常
     */
    private void assertLatestActiveDefinition(ProcessDefinition definition)
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
    }

    /**
     * 以纯只读方式校验表单和 BPMN 预览权限，拒绝时保持原有通用 403 契约。
     *
     * @param definition ProcessDefinition，已通过最新激活状态校验的流程定义
     * @param actor WorkflowCurrentIdentity，只读边界内解析的当前有效身份
     * @return void，受管规则或历史 starter identity link 拒绝时抛出无 subCode 的 403
     */
    private void assertPreviewStartPermission(ProcessDefinition definition,
            WorkflowCurrentIdentity actor)
    {
        Boolean managedDecision = participantRuleRuntimeService
                .canStartIfManaged(actor, definition);
        if (Boolean.FALSE.equals(managedDecision)
                || (managedDecision == null
                    && !isHistoricalDefinitionStartable(definition, actor)))
        {
            throw new ServiceException("当前用户无权发起该流程", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 在真实发起写入前执行正式授权，保留拒绝指标和专用稳定 subCode。
     *
     * @param definition ProcessDefinition，已通过最新激活状态校验的流程定义
     * @param actor WorkflowCurrentIdentity，外层写事务已经核验的当前有效身份
     * @return void，受管规则拒绝沿用 assertCanStart 异常，历史拒绝沿用通用 403
     */
    private void assertStartPermissionForWrite(ProcessDefinition definition,
            WorkflowCurrentIdentity actor)
    {
        // 受管部署的允许或异常拒绝均是正式决定；只有 null 才进入历史兼容路径。
        if (participantRuleRuntimeService.assertCanStart(actor, definition) == null
                && !isHistoricalDefinitionStartable(definition, actor))
        {
            throw new ServiceException("当前用户无权发起该流程", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 使用一次 BPMN Model 读取组装开始节点表单与多实例字段，不负责事务或授权。
     *
     * @param definition ProcessDefinition，已经完成结构和对应场景授权校验的真实定义
     * @return StartFormLoad，同一次模型读取生成的 BPMN 模型和开始表单视图
     */
    private StartFormLoad loadStartForm(ProcessDefinition definition)
    {
        BpmnModel model = requireBpmnModel(definition);
        WfDeployForm snapshot = requireStartFormSnapshot(definition,
                definition.getDeploymentId(), model);
        List<WorkflowStartMultiInstanceAssignmentView> startAssignments =
                WorkflowStartMultiInstanceContract.describe(model, definition.getKey());
        WorkflowProcessFormView form = new WorkflowProcessFormView(definition.getId(),
                definition.getDeploymentId(), null, snapshot.getSourceType(),
                snapshot.getFormId(), snapshot.getFormKey(), snapshot.getNodeKey(),
                snapshot.getFormName(), snapshot.getNodeName(), snapshot.getContent(),
                toInstant(snapshot.getCreateTime()), startAssignments);
        return new StartFormLoad(model, form);
    }

    /**
     * 读取并核验流程定义的唯一 BPMN 公共模型。
     *
     * @param definition ProcessDefinition，已经完成定义关系校验的真实流程定义
     * @return BpmnModel，可同时用于开始节点、表单快照与多实例字段解析的模型
     */
    private BpmnModel requireBpmnModel(ProcessDefinition definition)
    {
        BpmnModel model = repositoryService.getBpmnModel(definition.getId());
        if (model == null)
        {
            throw dataError("流程定义缺少 BPMN 模型");
        }
        return model;
    }

    /**
     * 查询并核验指定定义开始节点对应的唯一部署表单快照。
     *
     * @param definition ProcessDefinition，已完成关系与权限校验的流程定义
     * @param deploymentId String，定义所属真实部署主键
     * @param model BpmnModel，本次发起准备已经唯一读取并校验存在的流程模型
     * @return WfDeployForm，仅来自 Flowable 业务制品 forms-v1.json 的开始节点快照
     */
    private WfDeployForm requireStartFormSnapshot(ProcessDefinition definition,
            String deploymentId, BpmnModel model)
    {
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
        List<WfDeployForm> snapshots = artifactRepository.selectForms(deploymentId);
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
     * 承载一次正式发起准备读取的 BPMN 模型和开始表单，不引入额外行为边界。
     *
     * @param bpmnModel BpmnModel，本次准备唯一读取的流程模型
     * @param form WorkflowProcessFormView，基于同一模型和部署制品生成的开始表单
     */
    record StartFormLoad(BpmnModel bpmnModel, WorkflowProcessFormView form) { }

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
     * 按主键查询单个流程定义，供表单和 BPMN XML 读取复用。
     *
     * @param definitionId String，流程定义主键
     * @return ProcessDefinition，存在且主键有效的流程定义
     */
    private ProcessDefinition requireDefinition(String definitionId)
    {
        if (!StringUtils.hasText(definitionId))
        {
            throw dataError("流程定义关联主键为空");
        }
        ProcessDefinition definition = repositoryService.getProcessDefinition(definitionId);
        if (definition == null)
        {
            throw new ServiceException("流程定义不存在或已被删除", HttpStatus.NOT_FOUND);
        }
        return definition;
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
     * 规范正式部署中的业务分类，禁止 BPMN 命名空间 URI 进入页面。
     *
     * @param category String，发布事务写入 Deployment.category 的业务分类编码
     * @return String，规范后的正式分类编码；字段为空或绝对 URI 时返回 null
     */
    private String resolveDeploymentCategory(String category)
    {
        if (!StringUtils.hasText(category))
        {
            return null;
        }
        String normalized = category.trim();
        // Deployment.category 若被错误写成带协议的绝对 URI，也不能作为业务分类回显。
        if (normalized.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*$"))
        {
            return null;
        }
        return normalized;
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
     * 一次完整权限扫描选出的定义窗口及真实可见总数。
     *
     * @param definitions List&lt;ProcessDefinition&gt;，保持基础查询稳定顺序的当前窗口定义
     * @param visibleTotal long，扫描全部基础定义后得到的真实可见总数
     */
    private record StartableDefinitionSelection(
            List<ProcessDefinition> definitions, long visibleTotal)
    {
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

package com.ruoyi.flowable.service.process;

import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.PageWindow;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.dataError;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.invalidArgument;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.optionalText;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.requirePage;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.requireSame;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.requireText;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.resolveCategoryDeploymentIds;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.resolveDeploymentCategory;
import static com.ruoyi.flowable.service.process.WorkflowProcessQuerySupport.toInstant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.page.PageResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessSnapshot;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.WfProcessDraft;
import com.ruoyi.flowable.mapper.WfProcessDraftMapper.DraftListRow;
import com.ruoyi.flowable.domain.dto.WorkflowBpmnXmlQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowProcessFormQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowStartableProcessQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormView;
import com.ruoyi.flowable.domain.vo.WorkflowStartMultiInstanceAssignmentView;
import com.ruoyi.flowable.domain.vo.WorkflowStartableDefinitionView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.service.identity.WorkflowParticipantRuleRuntimeService;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.model.WorkflowDeploymentService;
import com.ruoyi.flowable.service.model.WorkflowFormSourceType;
import com.ruoyi.flowable.service.task.WorkflowStartMultiInstanceContract;

/**
 * 可发起定义、部署表单快照与 BPMN 预览查询服务。
 */
@Service
public class WorkflowProcessDefinitionQueryService
{
    /** 可发起定义授权过滤前允许扫描的最大基础定义数。 */
    static final int MAX_STARTABLE_SCAN = 10_000;

    /** 可发起定义每次从 Flowable 拉取的固定分块大小。 */
    static final int STARTABLE_SCAN_CHUNK = 200;

    /** 正式表单节点的批量默认字段权限属性主键。 */
    private static final String FORM_PERMISSION_DEFAULT_ID = "approva_permission_default";

    /** 正式表单节点的逐字段权限属性主键前缀。 */
    private static final String FORM_PERMISSION_FIELD_ID_PREFIX = "approva_permission_field_";

    /** 流程定义不是当前最新版时返回的稳定机器子码。 */
    private static final String DEFINITION_VERSION_EXPIRED_SUB_CODE =
            "PROCESS_DEFINITION_VERSION_EXPIRED";

    /** 流程定义已删除、挂起或仓库当前不可用时返回的稳定机器子码。 */
    private static final String DEFINITION_UNAVAILABLE_SUB_CODE =
            "PROCESS_DEFINITION_UNAVAILABLE";

    /** 客户端声明部署与服务端定义部署不一致时返回的稳定机器子码。 */
    private static final String DEFINITION_RELATION_CONFLICT_SUB_CODE =
            "PROCESS_DEFINITION_RELATION_CONFLICT";

    private final WorkflowEngineOperations engineOperations;
    private final RepositoryService repositoryService;
    private final WorkflowIdentityResolver identityResolver;
    private final WorkflowProcessAccessService processAccessService;
    private final WorkflowDeploymentService deploymentService;
    private final WorkflowDeploymentArtifactRepository artifactRepository;

    /** 部署快照发起范围只读过滤服务。 */
    private final WorkflowParticipantRuleRuntimeService participantRuleRuntimeService;

    /**
     * 创建可发起定义与预览查询服务。
     *
     * 构造后由 Spring 管理该服务
     */
    public WorkflowProcessDefinitionQueryService(WorkflowEngineOperations engineOperations,
            RepositoryService repositoryService, WorkflowIdentityResolver identityResolver,
            WorkflowProcessAccessService processAccessService,
            WorkflowDeploymentService deploymentService,
            WorkflowDeploymentArtifactRepository artifactRepository,
            WorkflowParticipantRuleRuntimeService participantRuleRuntimeService)
    {
        this.engineOperations = engineOperations;
        this.repositoryService = repositoryService;
        this.identityResolver = identityResolver;
        this.processAccessService = processAccessService;
        this.deploymentService = deploymentService;
        this.artifactRepository = artifactRepository;
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
            requireDefinitionDeploymentRelation(deploymentId, definition.getDeploymentId());
            if (instanceId == null)
            {
                WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
                return loadStartFormForPreviewInCurrentTransaction(actor, definition).form();
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

    /** 草稿创建/保存和独立预览共用当前身份，不重入公开查询或重新取得 actor。 */
    StartFormLoad loadStartFormForPreviewInCurrentTransaction(WorkflowCurrentIdentity actor,
            ProcessDefinition definition)
    {
        assertLatestActiveDefinition(definition);
        assertPreviewStartPermission(definition, actor);
        return loadStartForm(definition);
    }

    /** 从草稿的权威定义关系加载本次实际需要的表单，并确认显示过的正文没有损坏。 */
    StartFormLoad loadDraftFormInCurrentTransaction(WorkflowCurrentIdentity actor, WfProcessDraft draft)
    {
        ProcessDefinition definition = requireDefinition(draft.processDefinitionId());
        requireDraftDefinitionRelation(draft, definition);
        StartFormLoad loaded = loadStartFormForPreviewInCurrentTransaction(actor, definition);
        requireDraftSnapshot(draft, loaded.form());
        return loaded;
    }

    /** 持久化草稿与真实定义是两个独立来源，只在这个绑定边界判断版本和部署。 */
    static void requireDraftDefinitionRelation(WfProcessDraft draft, ProcessDefinition definition)
    {
        if (!draft.processDefinitionKey().equals(definition.getKey())
                || draft.processDefinitionVersion() != definition.getVersion())
        {
            throw new ServiceException("草稿绑定的流程定义版本已失效", HttpStatus.CONFLICT)
                    .setSubCode("DRAFT_DEFINITION_VERSION_EXPIRED");
        }
        if (!draft.deploymentId().equals(definition.getDeploymentId()))
        {
            throw new ServiceException("流程定义部署关系已发生变化", HttpStatus.CONFLICT)
                    .setSubCode(DEFINITION_RELATION_CONFLICT_SUB_CODE);
        }
    }

    /** 正文决定用户所填写的字段契约；其余冻结元数据不再与唯一部署生产者逐字段对账。 */
    static void requireDraftSnapshot(WfProcessDraft draft, WorkflowProcessFormView form)
    {
        if (!form.content().equals(draft.formSnapshot()))
        {
            throw new ServiceException("草稿绑定的部署表单快照已失效", HttpStatus.CONFLICT)
                    .setSubCode("DRAFT_SNAPSHOT_MISMATCH");
        }
    }

    /** 当前页一次读取不同定义、对应最新版本与发起范围；不为摘要装载表单正文。 */
    Map<String, String> draftUnavailableReasons(WorkflowCurrentIdentity actor, List<DraftListRow> drafts)
    {
        if (drafts.isEmpty()) return Map.of();
        Set<String> ids = drafts.stream().map(DraftListRow::processDefinitionId).collect(Collectors.toSet());
        List<ProcessDefinition> definitions = repositoryService.createProcessDefinitionQuery()
                .processDefinitionIds(ids).list();
        Map<String, ProcessDefinition> byId = definitions.stream().collect(
                Collectors.toMap(ProcessDefinition::getId, definition -> definition));
        Set<String> latestIds = repositoryService.createProcessDefinitionQuery()
                .processDefinitionIds(ids).processDefinitionWithoutTenantId().latestVersion().list()
                .stream().map(ProcessDefinition::getId).collect(Collectors.toSet());
        List<ProcessDefinition> active = definitions.stream()
                .filter(definition -> latestIds.contains(definition.getId()) && !definition.isSuspended()).toList();
        Map<String, Boolean> permissions = active.isEmpty() ? Map.of()
                : participantRuleRuntimeService.resolveManagedStartDecisions(actor, active);
        Map<String, String> reasons = new LinkedHashMap<>();
        for (DraftListRow draft : drafts)
        {
            ProcessDefinition definition = byId.get(draft.processDefinitionId());
            String reason = null;
            if (definition == null) reason = "流程定义已删除";
            else if (!draft.processDefinitionKey().equals(definition.getKey())
                    || draft.processDefinitionVersion() != definition.getVersion()
                    || !latestIds.contains(definition.getId())) reason = "流程定义版本已过期";
            else if (!draft.deploymentId().equals(definition.getDeploymentId())) reason = "流程定义部署关系已变化";
            else if (definition.isSuspended()) reason = "流程定义当前不可用";
            else if (permissions.get(definition.getId()) == null) throw dataError("流程发起范围批量判定结果异常");
            else if (!permissions.get(definition.getId())) reason = "当前用户已无权发起该流程";
            if (reason != null) reasons.put(draft.draftId(), reason);
        }
        return reasons;
    }

    /**
     * 在 Flowable 已通过受控 CallActivity 创建子流程的同一命令内读取其正式开始表单。
     *
     * @param processDefinitionId String，子流程实例携带的精确流程定义主键
     * @return WorkflowProcessFormView，与子定义、部署和唯一开始节点严格绑定的表单快照
     */
    WorkflowProcessFormView loadCallActivityStartForm(String processDefinitionId)
    {
        // 调用活动授权和精确版本已在父流程部署与运行阶段校验，此处不重复套用人工发起权限。
        ProcessDefinition definition = requireDefinition(
                requireText(processDefinitionId, "子流程定义主键不能为空"));
        return loadStartForm(definition).form();
    }

    /**
     * 在发起场景执行可发起校验，或在详情场景执行实例对象授权后安全读取 BPMN XML。
     *
     * @param request WorkflowBpmnXmlQueryDto，定义主键及可选实例主键
     * @return String，有界读取并严格解码的已部署 UTF-8 BPMN XML
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
            // 本次授权完成后读取既有资源，展示不重复执行作者或部署校验。
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
        Set<String> categoryDeploymentIds = resolveCategoryDeploymentIds(repositoryService,
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
        long baseTotal = query.count();
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
        // 整批快照授权只调用一次，并要求返回值覆盖全部定义，避免逐定义查询或身份推断。
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
     * @return List&lt;ProcessDefinition&gt;，按流程 key、定义 ID 排序的有界定义集合
     */
    private List<ProcessDefinition> loadStartableDefinitions(
            ProcessDefinitionQuery query, long baseTotal)
    {
        List<ProcessDefinition> definitions = new ArrayList<>((int) baseTotal);
        int baseOffset = 0;
        while (baseOffset < baseTotal)
        {
            int batchLimit = (int) Math.min(STARTABLE_SCAN_CHUNK, baseTotal - baseOffset);
            List<ProcessDefinition> batch = query.listPage(baseOffset, batchLimit);
            if (batch.isEmpty())
            {
                break;
            }
            definitions.addAll(batch);
            baseOffset += batch.size();
        }
        return List.copyOf(definitions);
    }

    /**
     * 使用整批部署快照判断定义权限，结果必须覆盖当前扫描的全部定义。
     *
     * @param definition ProcessDefinition，当前有界扫描中的流程定义
     * @param actor WorkflowCurrentIdentity，当前有效用户及候选组
     * @param managedDecisions Map&lt;String, Boolean&gt;，受管定义的正式整批授权决定
     * @return boolean，正式部署快照给出的允许或拒绝决定
     */
    private boolean isDefinitionStartable(ProcessDefinition definition, WorkflowCurrentIdentity actor,
            Map<String, Boolean> managedDecisions)
    {
        if (!managedDecisions.containsKey(definition.getId()))
        {
            throw dataError("流程发起范围批量判定结果缺失");
        }
        Boolean decision = managedDecisions.get(definition.getId());
        if (decision == null)
        {
            throw dataError("流程发起范围批量判定结果异常");
        }
        return decision;
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
            if (!StringUtils.hasText(definition.getDeploymentId()))
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
            for (Deployment deployment : batch)
            {
                deployments.put(deployment.getId(), deployment);
            }
        }
        if (deployments.size() != deploymentIds.size())
        {
            throw dataError("流程定义缺少部署数据");
        }
        return Map.copyOf(deployments);
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
        if (latest == null)
        {
            throw new ServiceException("流程定义当前不可用", HttpStatus.CONFLICT)
                    .setSubCode(DEFINITION_UNAVAILABLE_SUB_CODE);
        }
        if (!definition.getId().equals(latest.getId()))
        {
            throw new ServiceException("流程定义不是最新版本", HttpStatus.CONFLICT)
                    .setSubCode(DEFINITION_VERSION_EXPIRED_SUB_CODE);
        }
        if (definition.isSuspended())
        {
            throw new ServiceException("流程定义已挂起", HttpStatus.CONFLICT)
                    .setSubCode(DEFINITION_UNAVAILABLE_SUB_CODE);
        }
    }

    /**
     * 以纯只读方式校验表单和 BPMN 预览权限，拒绝时保持原有通用 403 契约。
     *
     * @param definition ProcessDefinition，已通过最新激活状态校验的流程定义
     * @param actor WorkflowCurrentIdentity，只读边界内解析的当前有效身份
     * @return void，正式部署规则拒绝时抛出无 subCode 的 403，快照缺失直接失败
     */
    private void assertPreviewStartPermission(ProcessDefinition definition,
            WorkflowCurrentIdentity actor)
    {
        Boolean decision = participantRuleRuntimeService
                .canStartIfManaged(actor, definition);
        if (!Boolean.TRUE.equals(decision))
        {
            throw new ServiceException("当前用户无权发起该流程", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 在真实发起写入前执行正式授权，保留拒绝指标和专用稳定 subCode。
     *
     * @param definition ProcessDefinition，已通过最新激活状态校验的流程定义
     * @param actor WorkflowCurrentIdentity，外层写事务已经核验的当前有效身份
     * @return void，规则拒绝沿用 assertCanStart 异常，快照缺失按数据一致性错误失败
     */
    private void assertStartPermissionForWrite(ProcessDefinition definition,
            WorkflowCurrentIdentity actor)
    {
        participantRuleRuntimeService.assertCanStart(actor, definition);
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
        // 发起表单属于主流程；展开子流程内部的开始节点不参与主流程唯一性校验。
        List<StartEvent> startEvents = process.findFlowElementsOfType(StartEvent.class, false);
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
        List<WfDeployForm> snapshots = artifactRepository.openRead(deploymentId).forms();
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
    record StartFormLoad(BpmnModel bpmnModel, WorkflowProcessFormView form)
    {
        /** 草稿/提交需要处理字段时才解析一次；纯预览和 CallActivity 保持各自的原读取契约。 */
        JsonNode parseFormRoot()
        {
            try
            {
                return WorkflowFormTemplateValidator.decode(form.content());
            }
            catch (ServiceException exception)
            {
                ServiceException failure = dataError("流程部署表单快照结构异常");
                failure.initCause(exception);
                throw failure;
            }
        }
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
     * 核验表单查询声明的部署主键仍与服务端流程定义关系一致。
     *
     * @param requestedDeploymentId String，调用方声明并已完成非空校验的部署主键
     * @param definitionDeploymentId String，Flowable 当前定义返回的部署主键
     * @return void，部署关系漂移时抛出带稳定子码的 HTTP 409
     */
    private void requireDefinitionDeploymentRelation(String requestedDeploymentId,
            String definitionDeploymentId)
    {
        if (!StringUtils.hasText(requestedDeploymentId)
                || !requestedDeploymentId.equals(definitionDeploymentId))
        {
            throw new ServiceException("流程定义与部署关系不一致", HttpStatus.CONFLICT)
                    .setSubCode(DEFINITION_RELATION_CONFLICT_SUB_CODE);
        }
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
        ProcessDefinition definition;
        try
        {
            definition = repositoryService.getProcessDefinition(definitionId);
        }
        catch (FlowableObjectNotFoundException exception)
        {
            ServiceException unavailable = new ServiceException(
                    "流程定义不存在或已被删除", HttpStatus.NOT_FOUND)
                    .setSubCode(DEFINITION_UNAVAILABLE_SUB_CODE);
            unavailable.initCause(exception);
            throw unavailable;
        }
        if (definition == null)
        {
            throw new ServiceException("流程定义不存在或已被删除", HttpStatus.NOT_FOUND)
                    .setSubCode(DEFINITION_UNAVAILABLE_SUB_CODE);
        }
        return definition;
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
}

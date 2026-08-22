package com.ruoyi.flowable.service.process;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.LinkedHashSet;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.page.PageResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfProcessDraft;
import com.ruoyi.flowable.domain.WorkflowProcessDraftStatus;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftCreateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftSaveRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftSubmitRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessFormQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDraftSubmitView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDraftSummaryView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDraftView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormView;
import com.ruoyi.flowable.domain.vo.WorkflowStartMultiInstanceAssignmentView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfProcessDraftMapper;
import com.ruoyi.flowable.mapper.WorkflowProcessDefinitionLockMapper;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;

/**
 * 企业流程申请草稿的本人授权、CAS 保存、附件对账和正式提交服务。
 */
@Service
public class WorkflowProcessDraftService
{
    /** 草稿列表单页上限。 */
    private static final int MAX_PAGE_SIZE = 200;
    /** 草稿正文类型令牌，拒绝解析任意 Java 类型。 */
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    /** 发起成员按活动持久化 JSON 的固定反序列化类型。 */
    private static final TypeReference<Map<String, List<Long>>> MEMBER_MAP_TYPE =
            new TypeReference<>() { };
    /** 部署 BPMN 发起成员字段快照 JSON 的固定反序列化类型。 */
    private static final TypeReference<List<WorkflowStartMultiInstanceAssignmentView>>
            ASSIGNMENT_LIST_TYPE = new TypeReference<>() { };

    private final WorkflowEngineOperations engineOperations;
    private final WorkflowIdentityResolver identityResolver;
    private final RepositoryService repositoryService;
    private final WorkflowProcessQueryService processQueryService;
    private final WorkflowProcessStartService processStartService;
    private final WorkflowStartVariableValidator variableValidator;
    private final WorkflowAttachmentService attachmentService;
    private final WfProcessDraftMapper draftMapper;
    /** 与部署删除共用的 Flowable 部署行锁，阻止创建孤儿活动草稿。 */
    private final WorkflowProcessDefinitionLockMapper processDefinitionLockMapper;
    private final ObjectMapper objectMapper = JsonMapper.shared();

    /**
     * 创建流程申请草稿领域服务。
     *
     * @param engineOperations WorkflowEngineOperations，统一身份和事务边界
     * @param identityResolver WorkflowIdentityResolver，当前有效用户解析器
     * @param repositoryService RepositoryService，真实流程定义查询 API
     * @param processQueryService WorkflowProcessQueryService，starter 和部署表单快照门禁
     * @param processStartService WorkflowProcessStartService，真实 Flowable 发起服务
     * @param variableValidator WorkflowStartVariableValidator，草稿和正式字段校验器
     * @param attachmentService WorkflowAttachmentService，草稿附件对账和迁移服务
     * @param draftMapper WfProcessDraftMapper，草稿正式持久化 Mapper
     * @param processDefinitionLockMapper WorkflowProcessDefinitionLockMapper，Flowable 部署行锁 Mapper
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowProcessDraftService(WorkflowEngineOperations engineOperations,
            WorkflowIdentityResolver identityResolver, RepositoryService repositoryService,
            WorkflowProcessQueryService processQueryService,
            WorkflowProcessStartService processStartService,
            WorkflowStartVariableValidator variableValidator,
            WorkflowAttachmentService attachmentService, WfProcessDraftMapper draftMapper,
            WorkflowProcessDefinitionLockMapper processDefinitionLockMapper)
    {
        this.engineOperations = engineOperations;
        this.identityResolver = identityResolver;
        this.repositoryService = repositoryService;
        this.processQueryService = processQueryService;
        this.processStartService = processStartService;
        this.variableValidator = variableValidator;
        this.attachmentService = attachmentService;
        this.draftMapper = draftMapper;
        this.processDefinitionLockMapper = processDefinitionLockMapper;
    }

    /**
     * 分页查询当前有效用户自己的活动草稿，并返回实时定义可用性。
     *
     * @param filter WorkflowProcessDraftQueryDto，流程名称和更新时间条件
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数
     * @return PageResult&lt;WorkflowProcessDraftSummaryView&gt;，本人草稿分页
     */
    public PageResult<WorkflowProcessDraftSummaryView> list(
            WorkflowProcessDraftQueryDto filter, int pageNum, int pageSize)
    {
        requirePage(pageNum, pageSize);
        PageResult<WfProcessDraft> draftPage = engineOperations.read(() ->
        {
            long ownerUserId = Long.parseLong(identityResolver.resolveCurrentIdentity().userId());
            String processName = escapeLike(optionalText(filter == null ? null : filter.processName(), 255));
            LocalDateTime updatedAfter = toLocal(filter == null ? null : filter.updatedAfter());
            LocalDateTime updatedBefore = toLocal(filter == null ? null : filter.updatedBefore());
            requireDateRange(updatedAfter, updatedBefore);
            long total = draftMapper.countOwnedActive(ownerUserId, processName,
                    updatedAfter, updatedBefore);
            if (total == 0)
            {
                return new PageResult<>(List.of(), 0);
            }
            int offset = Math.multiplyExact(pageNum - 1, pageSize);
            List<WfProcessDraft> rows = draftMapper.selectOwnedActivePage(ownerUserId,
                    processName, updatedAfter, updatedBefore, offset, pageSize);
            return new PageResult<>(rows, total);
        });
        // 实时可用性查询可能稳定返回定义删除、停用或过期；必须在本人数据事务提交后投影，
        // 避免内层预期业务异常把草稿列表的共享只读事务标记为 rollback-only。
        return new PageResult<>(draftPage.rows().stream().map(this::toSummary).toList(),
                draftPage.total());
    }

    /**
     * 查询当前有效用户自己的草稿详情。
     *
     * @param draftId String，草稿 UUID
     * @return WorkflowProcessDraftView，包含不可变表单快照和字段回显
     */
    public WorkflowProcessDraftView get(String draftId)
    {
        String normalizedId = requireDraftId(draftId);
        WfProcessDraft draft = engineOperations.read(() ->
        {
            long ownerUserId = Long.parseLong(identityResolver.resolveCurrentIdentity().userId());
            WfProcessDraft ownedDraft = requireOwned(draftMapper.selectOwnedById(
                    normalizedId, ownerUserId));
            if (ownedDraft.draftStatus() == WorkflowProcessDraftStatus.DELETED)
            {
                throw notFound();
            }
            return ownedDraft;
        });
        // 详情与列表保持同一事务语义：本人授权查询完成后再计算实时定义可用性。
        return toView(draft);
    }

    /**
     * 以当前可发起定义和不可变部署表单快照创建本人草稿。
     *
     * @param request WorkflowProcessDraftCreateRequest，定义、业务主键和草稿字段值
     * @return WorkflowProcessDraftView，新建草稿详情
     */
    public WorkflowProcessDraftView create(WorkflowProcessDraftCreateRequest request)
    {
        if (request == null)
        {
            throw badRequest("草稿创建参数不能为空");
        }
        String definitionId = requireText(request.processDefinitionId(), 255,
                "流程定义主键不能为空");
        String businessKey = optionalText(request.businessKey(), 255);
        return engineOperations.writeAsCurrentUser(actor ->
        {
            ProcessDefinition definition = requireDefinition(definitionId);
            // 与部署删除统一先锁 ACT_RE_DEPLOYMENT，再读取部署快照并写入 wf_process_draft。
            String deploymentId = lockDraftDeployment(definition);
            WorkflowProcessFormView form = processQueryService.getProcessForm(
                    new WorkflowProcessFormQueryDto(definitionId,
                            deploymentId, null));
            WorkflowValidatedStartVariables validated = variableValidator.validateForDraft(
                    form.content(), request.variables());
            Map<String, List<Long>> memberSelections = normalizeDraftSelections(
                    form.startMultiInstanceAssignments(), request.multiInstanceUserIds());
            String draftId = UUID.randomUUID().toString();
            WfProcessDraft draft = new WfProcessDraft(draftId, Long.valueOf(actor.userId()),
                    definition.getId(), definition.getKey(), definition.getVersion(),
                    deploymentId, defaultText(definition.getName(), definition.getKey()),
                    form.sourceType(), form.formId(), form.formKey(), form.nodeKey(),
                    defaultText(form.formName(), definition.getName()),
                    defaultText(form.nodeName(), ""), toLocal(form.snapshotTime()), form.content(),
                    WorkflowProcessDraftChecksum.sha256(form),
                    writeJson(form.startMultiInstanceAssignments()),
                    writeJson(validated.variables()), writeJson(memberSelections),
                    businessKey, WorkflowProcessDraftStatus.ACTIVE, 1L, null, null, null,
                    null, null);
            requireOne(draftMapper.insert(draft), "流程申请草稿写入失败");
            // 先落草稿主行满足附件外键，再绑定首次保存前已经上传的 TEMP 附件；失败时同事务回滚。
            attachmentService.reconcileDraftAttachments(actor.userId(), draftId,
                    validated.attachmentIdsByField());
            return toView(requireOwned(draftMapper.selectOwnedById(draftId,
                    Long.valueOf(actor.userId()))));
        });
    }

    /**
     * 以 CAS 保存本人活动草稿，允许缺少正式必填项但不允许非法字段或附件。
     *
     * @param draftId String，草稿 UUID
     * @param request WorkflowProcessDraftSaveRequest，期望版本和草稿正文
     * @return WorkflowProcessDraftView，保存后的最新草稿
     */
    public WorkflowProcessDraftView save(String draftId, WorkflowProcessDraftSaveRequest request)
    {
        String normalizedId = requireDraftId(draftId);
        requireSaveRequest(request);
        return engineOperations.writeAsCurrentUser(actor ->
        {
            long ownerUserId = Long.parseLong(actor.userId());
            WfProcessDraft current = requireOwned(draftMapper.selectOwnedByIdForUpdate(
                    normalizedId, ownerUserId));
            requireActiveRevision(current, request.expectedVersion());
            WorkflowProcessFormView liveForm = requireLiveSnapshot(current);
            WorkflowValidatedStartVariables validated = variableValidator.validateForDraft(
                    liveForm.content(), request.variables());
            Map<String, List<Long>> memberSelections = normalizeDraftSelections(
                    liveForm.startMultiInstanceAssignments(), request.multiInstanceUserIds());
            attachmentService.reconcileDraftAttachments(actor.userId(), normalizedId,
                    validated.attachmentIdsByField());
            requireCas(draftMapper.updateActive(normalizedId, ownerUserId,
                    request.expectedVersion(), writeJson(validated.variables()),
                    writeJson(memberSelections),
                    optionalText(request.businessKey(), 255)));
            return toView(requireOwned(draftMapper.selectOwnedById(normalizedId, ownerUserId)));
        });
    }

    /**
     * 以 CAS 软删除本人活动草稿，并把 DRAFT 附件转为现有清理器可处理的 DELETED。
     *
     * @param draftId String，草稿 UUID
     * @param expectedVersion long，客户端最后读取的版本
     * @return void，成功后草稿不再出现在列表或详情
     */
    public void delete(String draftId, long expectedVersion)
    {
        String normalizedId = requireDraftId(draftId);
        if (expectedVersion < 1)
        {
            throw badRequest("草稿版本必须大于0");
        }
        engineOperations.writeAsCurrentUser(actor ->
        {
            long ownerUserId = Long.parseLong(actor.userId());
            WfProcessDraft current = requireOwned(draftMapper.selectOwnedByIdForUpdate(
                    normalizedId, ownerUserId));
            requireActiveRevision(current, expectedVersion);
            attachmentService.deleteDraftAttachments(actor.userId(), normalizedId);
            requireCas(draftMapper.markDeleted(normalizedId, ownerUserId, expectedVersion));
            return null;
        });
    }

    /**
     * 以草稿行锁实现重复提交幂等，并在同一事务创建实例、迁移附件和置为已提交。
     *
     * @param draftId String，草稿 UUID
     * @param request WorkflowProcessDraftSubmitRequest，期望版本和完整正式字段值
     * @return WorkflowProcessDraftSubmitView，首次和重复提交均返回同一实例
     */
    public WorkflowProcessDraftSubmitView submit(String draftId,
            WorkflowProcessDraftSubmitRequest request)
    {
        String normalizedId = requireDraftId(draftId);
        if (request == null || request.expectedVersion() < 1)
        {
            throw badRequest("草稿提交参数或版本不合法");
        }
        return engineOperations.writeAsCurrentUser(actor ->
        {
            long ownerUserId = Long.parseLong(actor.userId());
            // 先做本人普通读只用于定位不可变 deploymentId；ACTIVE 路径随后必须按部署、草稿顺序重锁复核。
            WfProcessDraft located = requireOwned(draftMapper.selectOwnedById(
                    normalizedId, ownerUserId));
            if (located.draftStatus() == WorkflowProcessDraftStatus.SUBMITTED)
            {
                // SUBMITTED 不会再次产生实例，且部署允许被后续删除；直接返回持久化幂等结果。
                return submittedView(located);
            }
            if (located.draftStatus() != WorkflowProcessDraftStatus.ACTIVE)
            {
                requireActiveRevision(located, request.expectedVersion());
            }
            String deploymentId = requireDraftDeploymentId(located.deploymentId());
            lockDraftDeployment(deploymentId);
            WfProcessDraft current = requireOwned(draftMapper.selectOwnedByIdForUpdate(
                    normalizedId, ownerUserId));
            assertLocatedDraftRelation(located, current, ownerUserId);
            if (current.draftStatus() == WorkflowProcessDraftStatus.SUBMITTED)
            {
                return submittedView(current);
            }
            requireActiveRevision(current, request.expectedVersion());
            Map<String, List<Long>> memberSelections = normalizeDraftSelections(
                    readAssignments(current.startMultiInstanceAssignments()),
                    request.multiInstanceUserIds());
            // 业务主键只规范化一次；启动结果同时带回唯一一次 schema 校验得到的正式字段。
            String businessKey = optionalText(request.businessKey(), 255);
            WorkflowProcessStartService.DraftStartResult started = processStartService
                    .startDraft(actor, current, businessKey, request.variables(),
                            memberSelections);
            requireCas(draftMapper.markSubmitted(normalizedId, ownerUserId,
                    request.expectedVersion(), started.instance().id(),
                    writeJson(started.normalizedVariables()), writeJson(memberSelections),
                    businessKey));
            return new WorkflowProcessDraftSubmitView(normalizedId, started.instance().id(),
                    started.instance().processDefinitionId(), current.revisionNo() + 1);
        });
    }

    /**
     * 将本人持久化草稿、冻结表单和实时可用性转换为详情视图。
     *
     * @param draft WfProcessDraft，已通过所有者校验的正式草稿记录
     * @return WorkflowProcessDraftView，可供页面回显且包含实时编辑、提交能力的详情
     */
    private WorkflowProcessDraftView toView(WfProcessDraft draft)
    {
        Availability availability = availability(draft);
        WorkflowProcessFormView form = new WorkflowProcessFormView(
                draft.processDefinitionId(), draft.deploymentId(), null, draft.sourceType(),
                draft.formId(), draft.formKey(), draft.startNodeKey(), draft.formName(),
                draft.nodeName(), draft.formSnapshot(), toInstant(draft.snapshotCreateTime()),
                readAssignments(draft.startMultiInstanceAssignments()));
        return new WorkflowProcessDraftView(draft.draftId(), draft.processDefinitionId(),
                draft.processDefinitionKey(), draft.processDefinitionVersion(),
                draft.deploymentId(), draft.processName(), draft.sourceType(), draft.formId(),
                draft.formKey(), draft.startNodeKey(), draft.formName(), draft.nodeName(),
                toInstant(draft.snapshotCreateTime()), form, readMap(draft.formValues()),
                readMemberMap(draft.multiInstanceUserIds()), draft.businessKey(),
                draft.draftStatus().name(), draft.revisionNo(),
                draft.submittedProcessInstanceId(), toInstant(draft.createTime()),
                toInstant(draft.updateTime()), availability.editable(),
                availability.submittable(), availability.reason());
    }

    /**
     * 将本人持久化草稿转换为不包含表单正文的列表摘要。
     *
     * @param draft WfProcessDraft，已通过所有者校验的正式草稿记录
     * @return WorkflowProcessDraftSummaryView，包含实时可用性和版本信息的列表项
     */
    private WorkflowProcessDraftSummaryView toSummary(WfProcessDraft draft)
    {
        Availability availability = availability(draft);
        return new WorkflowProcessDraftSummaryView(draft.draftId(), draft.processName(),
                draft.processDefinitionKey(), draft.processDefinitionVersion(),
                draft.draftStatus().name(), draft.revisionNo(), draft.businessKey(),
                toInstant(draft.createTime()), toInstant(draft.updateTime()),
                availability.editable(), availability.submittable(), availability.reason());
    }

    /**
     * 实时计算草稿定义、发起权限和冻结快照可用性，查询过程不改变持久化状态。
     *
     * @param draft WfProcessDraft，需要判断是否仍可编辑和提交的本人草稿
     * @return Availability，页面能力开关及不可用原因的稳定投影
     */
    private Availability availability(WfProcessDraft draft)
    {
        if (draft.draftStatus() != WorkflowProcessDraftStatus.ACTIVE)
        {
            return new Availability(false, false,
                    draft.draftStatus() == WorkflowProcessDraftStatus.SUBMITTED
                            ? "草稿已提交" : "草稿已删除");
        }
        try
        {
            requireLiveSnapshot(draft);
            return new Availability(true, true, null);
        }
        catch (ServiceException exception)
        {
            int status = exception.getCode() == null ? HttpStatus.ERROR : exception.getCode();
            if (status == HttpStatus.FORBIDDEN)
            {
                return new Availability(false, false, "当前用户已无权发起该流程");
            }
            if (status == HttpStatus.NOT_FOUND)
            {
                return new Availability(false, false, "流程定义已删除");
            }
            if (status == HttpStatus.CONFLICT && exception.getMessage().contains("最新"))
            {
                return new Availability(false, false, "流程定义版本已过期");
            }
            if (status == HttpStatus.CONFLICT && exception.getMessage().contains("挂起"))
            {
                return new Availability(false, false, "流程定义已停用");
            }
            return new Availability(false, false, "流程定义当前不可用");
        }
    }

    /**
     * 读取当前仍可发起的同一部署表单，并逐项复核草稿冻结关系没有漂移。
     *
     * @param draft WfProcessDraft，保存了定义、部署和表单校验和的正式草稿
     * @return WorkflowProcessFormView，通过权限、状态和快照一致性校验的实时表单
     */
    private WorkflowProcessFormView requireLiveSnapshot(WfProcessDraft draft)
    {
        WorkflowProcessFormView form = processQueryService.getProcessForm(
                new WorkflowProcessFormQueryDto(draft.processDefinitionId(),
                        draft.deploymentId(), null));
        if (!draft.deploymentId().equals(form.deploymentId())
                || !draft.sourceType().equals(form.sourceType())
                || !Objects.equals(draft.formId(), form.formId())
                || !draft.formKey().equals(form.formKey())
                || !draft.startNodeKey().equals(form.nodeKey())
                || !draft.formSnapshot().equals(form.content())
                || !readAssignments(draft.startMultiInstanceAssignments()).equals(
                    form.startMultiInstanceAssignments())
                || !draft.formSnapshotSha256().equals(WorkflowProcessDraftChecksum.sha256(form)))
        {
            throw conflict("草稿绑定的部署表单快照已失效", "DRAFT_SNAPSHOT_MISMATCH");
        }
        return form;
    }

    /**
     * 校验草稿仍为活动状态且客户端期望版本与正式 CAS 版本一致。
     *
     * @param draft WfProcessDraft，已锁定或当前事务内读取的本人草稿
     * @param expectedVersion long，客户端最后读取并随请求提交的版本号
     * @return void，状态或版本不一致时抛出稳定冲突子码
     */
    private void requireActiveRevision(WfProcessDraft draft, long expectedVersion)
    {
        if (draft.draftStatus() != WorkflowProcessDraftStatus.ACTIVE)
        {
            throw conflict("流程申请草稿当前状态不允许操作", "DRAFT_STATE_CONFLICT");
        }
        if (draft.revisionNo() != expectedVersion)
        {
            throw conflict("流程申请草稿已被其他会话更新，请刷新后重试",
                    "DRAFT_VERSION_CONFLICT");
        }
    }

    /**
     * 校验草稿保存请求存在且携带正数期望版本。
     *
     * @param request WorkflowProcessDraftSaveRequest，客户端提交的草稿保存请求
     * @return void，请求或版本不合法时抛出参数异常
     */
    private void requireSaveRequest(WorkflowProcessDraftSaveRequest request)
    {
        if (request == null || request.expectedVersion() < 1)
        {
            throw badRequest("草稿保存参数或版本不合法");
        }
    }

    /**
     * 锁定草稿绑定的 Flowable 部署行，并确认定义查询后部署没有被并发删除。
     *
     * @param definition ProcessDefinition，客户端选择且服务端已重新查询的流程定义
     * @return String，仍存在并持锁到草稿写事务结束的部署主键
     */
    private String lockDraftDeployment(ProcessDefinition definition)
    {
        String deploymentId = requireDraftDeploymentId(definition.getDeploymentId());
        lockDraftDeployment(deploymentId);
        return deploymentId;
    }

    /**
     * 锁定指定部署主键，使 ACTIVE 草稿提交和部署删除使用相同的首把行锁。
     *
     * @param deploymentId String，已经过非空和长度校验的 Flowable 部署主键
     * @return void，成功时行锁保持到外层写事务结束
     */
    private void lockDraftDeployment(String deploymentId)
    {
        String lockedDeploymentId = processDefinitionLockMapper
                .selectDeploymentIdForUpdate(deploymentId);
        if (!deploymentId.equals(lockedDeploymentId))
        {
            throw conflict("流程定义部署状态已变化，请刷新后重试",
                    "DRAFT_DEFINITION_UNAVAILABLE");
        }
    }

    /**
     * 校验草稿持久化的部署主键可安全用于 Flowable 行锁查询。
     *
     * @param deploymentId String，普通读草稿或真实定义携带的部署主键
     * @return String，合法且保持原值的部署主键
     */
    private String requireDraftDeploymentId(String deploymentId)
    {
        if (!StringUtils.hasText(deploymentId) || deploymentId.length() > 64)
        {
            throw dataError("流程定义部署信息异常");
        }
        return deploymentId;
    }

    /**
     * 复核普通定位读与锁定当前读仍指向同一所有者、定义版本和部署。
     *
     * @param located WfProcessDraft，取得部署锁前读取的本人草稿定位快照
     * @param current WfProcessDraft，取得部署锁后锁定的当前草稿
     * @param ownerUserId long，事务内重新核验的正式用户主键
     * @return void，关系未发生非法变化时正常返回
     */
    private void assertLocatedDraftRelation(WfProcessDraft located,
            WfProcessDraft current, long ownerUserId)
    {
        if (!Long.valueOf(ownerUserId).equals(current.ownerUserId())
                || !located.draftId().equals(current.draftId())
                || !located.processDefinitionId().equals(current.processDefinitionId())
                || !located.processDefinitionKey().equals(current.processDefinitionKey())
                || located.processDefinitionVersion() != current.processDefinitionVersion()
                || !located.deploymentId().equals(current.deploymentId()))
        {
            throw dataError("流程申请草稿定义关系已损坏");
        }
    }

    /**
     * 将已提交草稿转换为重复提交的稳定幂等结果。
     *
     * @param draft WfProcessDraft，状态为 SUBMITTED 的本人持久化草稿
     * @return WorkflowProcessDraftSubmitView，首次提交创建的同一实例信息
     */
    private WorkflowProcessDraftSubmitView submittedView(WfProcessDraft draft)
    {
        if (!StringUtils.hasText(draft.submittedProcessInstanceId()))
        {
            throw dataError("已提交草稿缺少流程实例关联");
        }
        return new WorkflowProcessDraftSubmitView(draft.draftId(),
                draft.submittedProcessInstanceId(), draft.processDefinitionId(),
                draft.revisionNo());
    }

    /**
     * 从 Flowable 仓库查询必须存在的真实流程定义。
     *
     * @param definitionId String，客户端选择并已规范化的流程定义主键
     * @return ProcessDefinition，当前仓库中的真实流程定义
     */
    private ProcessDefinition requireDefinition(String definitionId)
    {
        ProcessDefinition definition = repositoryService.getProcessDefinition(definitionId);
        if (definition == null)
        {
            throw new ServiceException("流程定义不存在或已被删除", HttpStatus.NOT_FOUND)
                    .setSubCode("DRAFT_DEFINITION_UNAVAILABLE");
        }
        return definition;
    }

    /**
     * 要求 Mapper 查询结果为当前用户可见的本人草稿。
     *
     * @param draft WfProcessDraft，可空；已带所有者过滤条件的 Mapper 查询结果
     * @return WfProcessDraft，确认存在的本人草稿
     */
    private WfProcessDraft requireOwned(WfProcessDraft draft)
    {
        if (draft == null)
        {
            throw notFound();
        }
        return draft;
    }

    /**
     * 将受控草稿字段或成员映射序列化为正式 JSON 存储值。
     *
     * @param value Object，可空；需要持久化的受控领域值
     * @return String，结构稳定的 JSON；空值保存为空对象
     */
    private String writeJson(Object value)
    {
        try
        {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        }
        catch (JacksonException exception)
        {
            throw badRequest("流程申请草稿字段无法序列化");
        }
    }

    /**
     * 将数据库中的受控字段 JSON 解析为不可修改的字段映射。
     *
     * @param json String，正式草稿表保存的字段 JSON
     * @return Map&lt;String,Object&gt;，保持原字段顺序的不可变映射
     */
    private Map<String, Object> readMap(String json)
    {
        try
        {
            return Collections.unmodifiableMap(new LinkedHashMap<>(
                    objectMapper.readValue(json, MAP_TYPE)));
        }
        catch (RuntimeException exception)
        {
            throw dataError("流程申请草稿字段数据损坏");
        }
    }

    /**
     * 解析并冻结草稿保存的发起成员映射。
     *
     * @param json String，数据库中的活动到正式用户主键数组 JSON
     * @return Map&lt;String,List&lt;Long&gt;&gt;，保持节点和用户顺序的不可变映射
     */
    private Map<String, List<Long>> readMemberMap(String json)
    {
        try
        {
            Map<String, List<Long>> parsed = objectMapper.readValue(json, MEMBER_MAP_TYPE);
            LinkedHashMap<String, List<Long>> copied = new LinkedHashMap<>();
            parsed.forEach((activityId, userIds) -> copied.put(activityId,
                    userIds == null ? null : List.copyOf(userIds)));
            return Collections.unmodifiableMap(copied);
        }
        catch (RuntimeException exception)
        {
            throw dataError("流程申请草稿发起成员数据损坏");
        }
    }

    /**
     * 解析创建草稿时冻结的发起成员字段投影。
     *
     * @param json String，数据库中的部署 BPMN 成员字段 JSON
     * @return List&lt;WorkflowStartMultiInstanceAssignmentView&gt;，不可变字段列表
     */
    private List<WorkflowStartMultiInstanceAssignmentView> readAssignments(String json)
    {
        try
        {
            return List.copyOf(objectMapper.readValue(json, ASSIGNMENT_LIST_TYPE));
        }
        catch (RuntimeException exception)
        {
            throw dataError("流程申请草稿发起成员字段快照损坏");
        }
    }

    /**
     * 按部署 BPMN 字段白名单规范草稿成员，允许尚未满足正式最少人数。
     *
     * @param assignments List&lt;WorkflowStartMultiInstanceAssignmentView&gt;，不可变部署成员字段
     * @param selections Map&lt;String,List&lt;Long&gt;&gt;，客户端草稿成员值，允许为空或不完整
     * @return Map&lt;String,List&lt;Long&gt;&gt;，覆盖全部受控活动且保持顺序的规范映射
     */
    private Map<String, List<Long>> normalizeDraftSelections(
            List<WorkflowStartMultiInstanceAssignmentView> assignments,
            Map<String, List<Long>> selections)
    {
        Map<String, List<Long>> source = selections == null ? Map.of() : selections;
        LinkedHashSet<String> expectedActivityIds = new LinkedHashSet<>();
        assignments.forEach(assignment -> expectedActivityIds.add(assignment.activityId()));
        if (!expectedActivityIds.containsAll(source.keySet()))
        {
            throw badRequest("草稿包含未知的发起会签或或签节点");
        }
        LinkedHashMap<String, List<Long>> normalized = new LinkedHashMap<>();
        for (WorkflowStartMultiInstanceAssignmentView assignment : assignments)
        {
            List<Long> userIds = source.getOrDefault(assignment.activityId(), List.of());
            if (userIds == null || userIds.size() > assignment.maxUsers())
            {
                throw badRequest("草稿发起会签或或签成员数量不合法");
            }
            LinkedHashSet<Long> uniqueUserIds = new LinkedHashSet<>();
            for (Long userId : userIds)
            {
                if (userId == null || userId <= 0 || !uniqueUserIds.add(userId))
                {
                    throw badRequest("草稿发起会签或或签成员不合法");
                }
            }
            normalized.put(assignment.activityId(), List.copyOf(uniqueUserIds));
        }
        return Collections.unmodifiableMap(normalized);
    }

    /**
     * 校验草稿列表页码与单页数量处于服务端允许范围。
     *
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，1 至服务端上限的单页记录数
     * @return void，范围不合法时抛出参数异常
     */
    private void requirePage(int pageNum, int pageSize)
    {
        if (pageNum < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE)
        {
            throw badRequest("草稿分页参数不合法");
        }
    }

    /**
     * 校验可选草稿更新时间起止范围没有倒置。
     *
     * @param begin LocalDateTime，可空；更新时间下界
     * @param end LocalDateTime，可空；更新时间上界
     * @return void，起始时间晚于结束时间时抛出参数异常
     */
    private void requireDateRange(LocalDateTime begin, LocalDateTime end)
    {
        if (begin != null && end != null && begin.isAfter(end))
        {
            throw badRequest("草稿更新时间范围不合法");
        }
    }

    /**
     * 校验并规范化客户端提交的 UUID 格式草稿主键。
     *
     * @param draftId String，可空；客户端提交的原始草稿主键
     * @return String，标准小写连字符 UUID 文本
     */
    private String requireDraftId(String draftId)
    {
        try
        {
            return UUID.fromString(draftId == null ? "" : draftId.trim()).toString();
        }
        catch (IllegalArgumentException exception)
        {
            throw badRequest("流程申请草稿主键不合法");
        }
    }

    /**
     * 规范化必填文本并执行最大长度校验。
     *
     * @param value String，可空；客户端提交的原始文本
     * @param maxLength int，正式字段允许的最大字符数
     * @param message String，文本为空时返回的稳定错误提示
     * @return String，去除首尾空白后的合法文本
     */
    private String requireText(String value, int maxLength, String message)
    {
        String normalized = optionalText(value, maxLength);
        if (normalized == null)
        {
            throw badRequest(message);
        }
        return normalized;
    }

    /**
     * 规范化可选文本并拒绝超过正式字段上限的值。
     *
     * @param value String，可空；客户端提交的原始文本
     * @param maxLength int，正式字段允许的最大字符数
     * @return String，去除首尾空白的文本；无有效内容时为 null
     */
    private String optionalText(String value, int maxLength)
    {
        if (!StringUtils.hasText(value))
        {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength)
        {
            throw badRequest("草稿文本长度超过限制");
        }
        return normalized;
    }

    /**
     * 为草稿列表的 LIKE 参数转义反斜线及 SQL 通配符。
     *
     * @param value String，可空；已经完成长度校验的流程名称条件
     * @return String，可安全交给 Mapper ESCAPE 语义使用的文本；空值保持 null
     */
    private String escapeLike(String value)
    {
        return value == null ? null : value.replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * 依次选择主值、后备值或空串作为稳定展示文本。
     *
     * @param value String，可空；优先使用的展示文本
     * @param fallback String，可空；主值为空时使用的后备文本
     * @return String，保证非 null 的展示文本
     */
    private String defaultText(String value, String fallback)
    {
        return StringUtils.hasText(value) ? value : StringUtils.hasText(fallback) ? fallback : "";
    }

    /**
     * 按服务运行时区将 API Instant 转换为数据库本地时间。
     *
     * @param value Instant，可空；API 时间戳
     * @return LocalDateTime，数据库本地时间；输入为空时为 null
     */
    private LocalDateTime toLocal(Instant value)
    {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }

    /**
     * 按服务运行时区将数据库本地时间转换为 API Instant。
     *
     * @param value LocalDateTime，可空；数据库时间值
     * @return Instant，API 时间戳；输入为空时为 null
     */
    private Instant toInstant(LocalDateTime value)
    {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    /**
     * 要求 Mapper 业务写入精确影响一行，防止静默丢失持久化事实。
     *
     * @param rows int，Mapper 返回的受影响行数
     * @param message String，行数异常时使用的稳定数据错误提示
     * @return void，受影响行数不是一时抛出数据异常
     */
    private void requireOne(int rows, String message)
    {
        if (rows != 1)
        {
            throw dataError(message);
        }
    }

    /**
     * 将草稿条件更新未精确命中一行翻译为稳定 CAS 冲突。
     *
     * @param rows int，带所有者、状态和版本条件的更新行数
     * @return void，未精确更新一行时抛出版本冲突
     */
    private void requireCas(int rows)
    {
        if (rows != 1)
        {
            throw conflict("流程申请草稿已被其他会话更新，请刷新后重试",
                    "DRAFT_VERSION_CONFLICT");
        }
    }

    /**
     * 构造草稿请求参数不合法的 400 业务异常。
     *
     * @param message String，可向调用方返回的稳定业务提示
     * @return ServiceException，HTTP 400 业务异常
     */
    private ServiceException badRequest(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 构造不泄露越权草稿是否存在的稳定 404 业务异常。
     *
     * @return ServiceException，带 DRAFT_NOT_FOUND 子码的 HTTP 404 业务异常
     */
    private ServiceException notFound()
    {
        return new ServiceException("流程申请草稿不存在", HttpStatus.NOT_FOUND)
                .setSubCode("DRAFT_NOT_FOUND");
    }

    /**
     * 构造带稳定子码的草稿状态或并发 409 业务异常。
     *
     * @param message String，可向调用方返回的稳定业务提示
     * @param subCode String，前端可据此分支处理的冲突子码
     * @return ServiceException，HTTP 409 业务异常
     */
    private ServiceException conflict(String message, String subCode)
    {
        return new ServiceException(message, HttpStatus.CONFLICT).setSubCode(subCode);
    }

    /**
     * 构造草稿正式持久化数据缺失、损坏或写入异常。
     *
     * @param message String，不泄露表单正文的稳定错误提示
     * @return ServiceException，HTTP 500 业务异常
     */
    private ServiceException dataError(String message)
    {
        return new ServiceException(message, HttpStatus.ERROR);
    }

    /** 草稿实时可用性。 */
    private record Availability(boolean editable, boolean submittable, String reason) { }
}

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
import tools.jackson.databind.node.ObjectNode;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfProcessDraft;
import com.ruoyi.flowable.domain.WfProcessDraftAudit;
import com.ruoyi.flowable.domain.WorkflowProcessDraftStatus;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftCreateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftSaveRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftSubmitRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessFormQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowPageResult;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDraftSubmitView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDraftSummaryView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDraftView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormView;
import com.ruoyi.flowable.domain.vo.WorkflowStartMultiInstanceAssignmentView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowProcessInstanceSnapshot;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfProcessDraftAuditMapper;
import com.ruoyi.flowable.mapper.WfProcessDraftMapper;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;

/**
 * 企业流程申请草稿的本人授权、CAS 保存、附件对账、审计和正式提交服务。
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
    private final WfProcessDraftAuditMapper auditMapper;
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
     * @param auditMapper WfProcessDraftAuditMapper，草稿业务审计 Mapper
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowProcessDraftService(WorkflowEngineOperations engineOperations,
            WorkflowIdentityResolver identityResolver, RepositoryService repositoryService,
            WorkflowProcessQueryService processQueryService,
            WorkflowProcessStartService processStartService,
            WorkflowStartVariableValidator variableValidator,
            WorkflowAttachmentService attachmentService, WfProcessDraftMapper draftMapper,
            WfProcessDraftAuditMapper auditMapper)
    {
        this.engineOperations = engineOperations;
        this.identityResolver = identityResolver;
        this.repositoryService = repositoryService;
        this.processQueryService = processQueryService;
        this.processStartService = processStartService;
        this.variableValidator = variableValidator;
        this.attachmentService = attachmentService;
        this.draftMapper = draftMapper;
        this.auditMapper = auditMapper;
    }

    /**
     * 分页查询当前有效用户自己的活动草稿，并返回实时定义可用性。
     *
     * @param filter WorkflowProcessDraftQueryDto，流程名称和更新时间条件
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数
     * @return WorkflowPageResult&lt;WorkflowProcessDraftSummaryView&gt;，本人草稿分页
     */
    public WorkflowPageResult<WorkflowProcessDraftSummaryView> list(
            WorkflowProcessDraftQueryDto filter, int pageNum, int pageSize)
    {
        requirePage(pageNum, pageSize);
        WorkflowPageResult<WfProcessDraft> draftPage = engineOperations.read(() ->
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
                return new WorkflowPageResult<>(List.of(), 0);
            }
            int offset = Math.multiplyExact(pageNum - 1, pageSize);
            List<WfProcessDraft> rows = draftMapper.selectOwnedActivePage(ownerUserId,
                    processName, updatedAfter, updatedBefore, offset, pageSize);
            return new WorkflowPageResult<>(rows, total);
        });
        // 实时可用性查询可能稳定返回定义删除、停用或过期；必须在本人数据事务提交后投影，
        // 避免内层预期业务异常把草稿列表的共享只读事务标记为 rollback-only。
        return new WorkflowPageResult<>(draftPage.rows().stream().map(this::toSummary).toList(),
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
            WorkflowProcessFormView form = processQueryService.getProcessForm(
                    new WorkflowProcessFormQueryDto(definitionId,
                            definition.getDeploymentId(), null));
            WorkflowValidatedStartVariables validated = variableValidator.validateForDraft(
                    form.content(), request.variables());
            Map<String, List<Long>> memberSelections = normalizeDraftSelections(
                    form.startMultiInstanceAssignments(), request.multiInstanceUserIds());
            String draftId = UUID.randomUUID().toString();
            WfProcessDraft draft = new WfProcessDraft(draftId, Long.valueOf(actor.userId()),
                    definition.getId(), definition.getKey(), definition.getVersion(),
                    definition.getDeploymentId(), defaultText(definition.getName(), definition.getKey()),
                    form.sourceType(), form.formId(), form.formKey(), form.nodeKey(),
                    defaultText(form.formName(), definition.getName()),
                    defaultText(form.nodeName(), ""), toLocal(form.snapshotTime()), form.content(),
                    WorkflowProcessDraftChecksum.sha256(form),
                    writeJson(form.startMultiInstanceAssignments()),
                    writeJson(validated.variables()), writeJson(memberSelections),
                    businessKey, WorkflowProcessDraftStatus.ACTIVE, 1L, null, null, null,
                    null, null);
            requireOne(draftMapper.insert(draft), "流程申请草稿写入失败");
            insertAudit(draft, "CREATED", null, WorkflowProcessDraftStatus.ACTIVE,
                    null, 1L, null);
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
            insertAudit(current, "SAVED", WorkflowProcessDraftStatus.ACTIVE,
                    WorkflowProcessDraftStatus.ACTIVE, current.revisionNo(),
                    current.revisionNo() + 1, null);
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
            insertAudit(current, "DELETED", WorkflowProcessDraftStatus.ACTIVE,
                    WorkflowProcessDraftStatus.DELETED, current.revisionNo(),
                    current.revisionNo() + 1, null);
            return null;
        });
    }

    /**
     * 以草稿行锁实现重复提交幂等，并在同一事务创建实例、迁移附件、审计和置为已提交。
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
            WfProcessDraft current = requireOwned(draftMapper.selectOwnedByIdForUpdate(
                    normalizedId, ownerUserId));
            if (current.draftStatus() == WorkflowProcessDraftStatus.SUBMITTED)
            {
                if (!StringUtils.hasText(current.submittedProcessInstanceId()))
                {
                    throw dataError("已提交草稿缺少流程实例关联");
                }
                return new WorkflowProcessDraftSubmitView(normalizedId,
                        current.submittedProcessInstanceId(), current.processDefinitionId(),
                        current.revisionNo());
            }
            requireActiveRevision(current, request.expectedVersion());
            Map<String, List<Long>> memberSelections = normalizeDraftSelections(
                    readAssignments(current.startMultiInstanceAssignments()),
                    request.multiInstanceUserIds());
            // startDraft 内部重新执行身份、starter、最新版锁、快照、正式必填和附件完整性校验。
            WorkflowProcessInstanceSnapshot instance = processStartService.startDraft(current,
                    optionalText(request.businessKey(), 255), request.variables(),
                    memberSelections);
            WorkflowValidatedStartVariables validated = variableValidator.validateForStart(
                    current.formSnapshot(), request.variables());
            requireCas(draftMapper.markSubmitted(normalizedId, ownerUserId,
                    request.expectedVersion(), instance.id(), writeJson(validated.variables()),
                    writeJson(memberSelections),
                    optionalText(request.businessKey(), 255)));
            insertAudit(current, "SUBMITTED", WorkflowProcessDraftStatus.ACTIVE,
                    WorkflowProcessDraftStatus.SUBMITTED, current.revisionNo(),
                    current.revisionNo() + 1, instance.id());
            return new WorkflowProcessDraftSubmitView(normalizedId, instance.id(),
                    instance.processDefinitionId(), current.revisionNo() + 1);
        });
    }

    /** 将持久化草稿转换为详情视图。 */
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

    /** 将持久化草稿转换为列表视图。 */
    private WorkflowProcessDraftSummaryView toSummary(WfProcessDraft draft)
    {
        Availability availability = availability(draft);
        return new WorkflowProcessDraftSummaryView(draft.draftId(), draft.processName(),
                draft.processDefinitionKey(), draft.processDefinitionVersion(),
                draft.draftStatus().name(), draft.revisionNo(), draft.businessKey(),
                toInstant(draft.createTime()), toInstant(draft.updateTime()),
                availability.editable(), availability.submittable(), availability.reason());
    }

    /** 实时计算草稿定义、权限和快照可用性，详情展示不改变持久化状态。 */
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

    /** 读取当前仍可发起的同一部署快照并逐项复核草稿冻结关系。 */
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

    /** 写入不含表单明文的状态迁移审计。 */
    private void insertAudit(WfProcessDraft draft, String action,
            WorkflowProcessDraftStatus fromStatus, WorkflowProcessDraftStatus toStatus,
            Long fromRevision, long toRevision, String processInstanceId)
    {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("processDefinitionId", draft.processDefinitionId());
        detail.put("deploymentId", draft.deploymentId());
        detail.put("formSnapshotSha256", draft.formSnapshotSha256());
        WfProcessDraftAudit audit = new WfProcessDraftAudit(draft.draftId(),
                draft.ownerUserId(), action, fromStatus == null ? null : fromStatus.name(),
                toStatus.name(), fromRevision, toRevision, processInstanceId,
                writeJson(detail));
        requireOne(auditMapper.insert(audit), "流程申请草稿审计写入失败");
    }

    /** 校验草稿仍为活动状态且 CAS 版本一致。 */
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

    /** 校验保存请求。 */
    private void requireSaveRequest(WorkflowProcessDraftSaveRequest request)
    {
        if (request == null || request.expectedVersion() < 1)
        {
            throw badRequest("草稿保存参数或版本不合法");
        }
    }

    /** 查询必须存在的真实流程定义。 */
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

    /** 查询必须存在的本人草稿。 */
    private WfProcessDraft requireOwned(WfProcessDraft draft)
    {
        if (draft == null)
        {
            throw notFound();
        }
        return draft;
    }

    /** 序列化受控 JSON。 */
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

    /** 解析数据库中的受控字段 JSON。 */
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

    /** 校验分页参数。 */
    private void requirePage(int pageNum, int pageSize)
    {
        if (pageNum < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE)
        {
            throw badRequest("草稿分页参数不合法");
        }
    }

    /** 校验时间范围。 */
    private void requireDateRange(LocalDateTime begin, LocalDateTime end)
    {
        if (begin != null && end != null && begin.isAfter(end))
        {
            throw badRequest("草稿更新时间范围不合法");
        }
    }

    /** 校验 UUID 格式草稿主键。 */
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

    /** 校验必填文本。 */
    private String requireText(String value, int maxLength, String message)
    {
        String normalized = optionalText(value, maxLength);
        if (normalized == null)
        {
            throw badRequest(message);
        }
        return normalized;
    }

    /** 规范化可选文本。 */
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

    /** 为列表 LIKE 参数转义通配符。 */
    private String escapeLike(String value)
    {
        return value == null ? null : value.replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_");
    }

    /** 选择非空展示文本。 */
    private String defaultText(String value, String fallback)
    {
        return StringUtils.hasText(value) ? value : StringUtils.hasText(fallback) ? fallback : "";
    }

    /** Instant 转数据库本地时间。 */
    private LocalDateTime toLocal(Instant value)
    {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }

    /** 数据库本地时间转 API Instant。 */
    private Instant toInstant(LocalDateTime value)
    {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    /** 要求 Mapper 单行写入成功。 */
    private void requireOne(int rows, String message)
    {
        if (rows != 1)
        {
            throw dataError(message);
        }
    }

    /** 将条件更新失败翻译为 CAS 冲突。 */
    private void requireCas(int rows)
    {
        if (rows != 1)
        {
            throw conflict("流程申请草稿已被其他会话更新，请刷新后重试",
                    "DRAFT_VERSION_CONFLICT");
        }
    }

    /** 构造 400 业务异常。 */
    private ServiceException badRequest(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /** 构造不泄露越权对象存在性的 404。 */
    private ServiceException notFound()
    {
        return new ServiceException("流程申请草稿不存在", HttpStatus.NOT_FOUND)
                .setSubCode("DRAFT_NOT_FOUND");
    }

    /** 构造稳定 409 子码异常。 */
    private ServiceException conflict(String message, String subCode)
    {
        return new ServiceException(message, HttpStatus.CONFLICT).setSubCode(subCode);
    }

    /** 构造服务端持久化数据异常。 */
    private ServiceException dataError(String message)
    {
        return new ServiceException(message, HttpStatus.ERROR);
    }

    /** 草稿实时可用性。 */
    private record Availability(boolean editable, boolean submittable, String reason) { }
}

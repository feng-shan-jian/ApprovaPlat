package com.ruoyi.flowable.service.process;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WorkflowProcessDefinitionLockRow;
import com.ruoyi.flowable.domain.WfProcessDraft;
import com.ruoyi.flowable.domain.dto.StartProcessRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessFormQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowProcessInstanceSnapshot;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;
import com.ruoyi.flowable.mapper.WorkflowProcessDefinitionLockMapper;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;
import com.ruoyi.flowable.service.identity.WorkflowParticipantRuleRuntimeService;
import com.ruoyi.flowable.service.task.WorkflowStartMultiInstanceContract;

/**
 * 流程发起的定义授权、部署快照校验、变量门禁和真实引擎写入服务。
 */
@Service
public class WorkflowProcessStartService
{
    /** BPMN initiator 与历史详情统一使用的服务端发起人变量名。 */
    public static final String INITIATOR_VARIABLE = "initiator";

    /** 兼容旧业务查询并由服务端维护的流程状态变量名。 */
    public static final String PROCESS_STATUS_VARIABLE = "processStatus";

    /** 新发起实例明确写入的运行中状态值。 */
    public static final String RUNNING_STATUS = "running";

    /** Flowable 流程定义和业务主键对应数据库列的安全字符上限。 */
    private static final int MAX_ENGINE_ID_LENGTH = 255;

    private final WorkflowEngineOperations engineOperations;
    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final WorkflowProcessQueryService processQueryService;
    private final WorkflowStartVariableValidator variableValidator;
    private final WorkflowAttachmentService attachmentService;
    private final WorkflowProcessDefinitionLockMapper definitionLockMapper;
    private final WorkflowUserSelectionValidator userSelectionValidator;

    /** 流程级发起范围运行授权服务。 */
    private final WorkflowParticipantRuleRuntimeService participantRuleRuntimeService;

    /**
     * 创建真实流程发起服务。
     *
     * @param engineOperations WorkflowEngineOperations，统一事务、当前身份和引擎异常边界
     * @param repositoryService RepositoryService，流程定义和状态公共 API
     * @param runtimeService RuntimeService，真实发起流程实例公共 API
     * @param processQueryService WorkflowProcessQueryService，starter 授权与部署开始表单快照门禁
     * @param variableValidator WorkflowStartVariableValidator，开始表单变量 schema 验证器
     * @param attachmentService WorkflowAttachmentService，临时附件校验、投影和事务绑定服务
     * @param definitionLockMapper WorkflowProcessDefinitionLockMapper，key 发起最终版本当前读与行锁
     * @param userSelectionValidator WorkflowUserSelectionValidator，发起时会签或或签成员审批资格校验器
     * @param participantRuleRuntimeService WorkflowParticipantRuleRuntimeService，部署快照与实时组织授权服务
     * @return 无返回值，构造后由 Spring 管理该服务
     */
    public WorkflowProcessStartService(WorkflowEngineOperations engineOperations,
            RepositoryService repositoryService, RuntimeService runtimeService,
            WorkflowProcessQueryService processQueryService,
            WorkflowStartVariableValidator variableValidator,
            WorkflowAttachmentService attachmentService,
            WorkflowProcessDefinitionLockMapper definitionLockMapper,
            WorkflowUserSelectionValidator userSelectionValidator,
            WorkflowParticipantRuleRuntimeService participantRuleRuntimeService)
    {
        this.engineOperations = engineOperations;
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.processQueryService = processQueryService;
        this.variableValidator = variableValidator;
        this.attachmentService = attachmentService;
        this.definitionLockMapper = definitionLockMapper;
        this.userSelectionValidator = userSelectionValidator;
        this.participantRuleRuntimeService = participantRuleRuntimeService;
    }

    /**
     * 以当前正式用户在单一事务内校验定义、starter、部署快照和变量后发起流程。
     *
     * @param request StartProcessRequest，定义主键、可选业务主键和开始表单变量
     * @return WorkflowProcessInstanceSnapshot，新实例的稳定不可变快照
     */
    public WorkflowProcessInstanceSnapshot start(StartProcessRequest request)
    {
        if (request == null)
        {
            throw new ServiceException("流程发起参数不能为空", HttpStatus.BAD_REQUEST);
        }
        String definitionId = requireText(request.processDefinitionId(),
                "流程定义主键不能为空", MAX_ENGINE_ID_LENGTH);
        String businessKey = optionalText(request.businessKey(),
                "流程业务主键长度不能超过" + MAX_ENGINE_ID_LENGTH, MAX_ENGINE_ID_LENGTH);

        return engineOperations.writeAsCurrentUser(actor -> startInCurrentTransaction(
                actor, definitionId, businessKey, request.variables(),
                request.multiInstanceUserIds(), null));
    }

    /**
     * 通过默认租户下的流程定义 key 发起最新激活版本，保留参考工程的精确 void 兼容签名。
     *
     * @param procDefKey String，流程定义 key，不是流程定义主键
     * @param variables Map&lt;String, Object&gt;，开始表单业务变量；允许为 null
     * @return void，无返回值；成功实例通过标准运行时、历史和业务查询链读取
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public void startProcessByDefKey(String procDefKey, Map<String, Object> variables)
    {
        startProcessByDefKey(procDefKey, null, variables);
    }

    /**
     * 通过默认租户下的流程定义 key 和可选业务主键发起最新激活版本。
     *
     * @param procDefKey String，流程定义 key，不是流程定义主键
     * @param businessKey String，可为空的业务主键
     * @param variables Map&lt;String, Object&gt;，开始表单业务变量；允许为 null
     * @return void，无返回值；发起、附件绑定或审计失败时同一事务整体回滚
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public void startProcessByDefKey(String procDefKey, String businessKey,
            Map<String, Object> variables)
    {
        String normalizedProcessKey = requireText(procDefKey,
                "流程定义标识不能为空", MAX_ENGINE_ID_LENGTH);
        String normalizedBusinessKey = optionalText(businessKey,
                "流程业务主键长度不能超过" + MAX_ENGINE_ID_LENGTH, MAX_ENGINE_ID_LENGTH);

        engineOperations.writeAsCurrentUser(actor ->
        {
            // key 解析必须和 starter、变量、附件及真实引擎写入处于同一事务和同一认证身份中。
            ProcessDefinition selectedDefinition = requireLatestActiveDefaultTenantDefinition(
                    normalizedProcessKey);
            return startInCurrentTransaction(actor, selectedDefinition.getId(),
                    normalizedBusinessKey, variables, Map.of(), normalizedProcessKey);
        });
    }

    /**
     * 在草稿服务已经锁定草稿行的同一写事务中重新校验全部正式规则并创建唯一实例。
     *
     * @param draft WfProcessDraft，包含定义版本和不可变部署表单快照的本人活动草稿
     * @param businessKey String，本次正式提交采用的业务主键
     * @param variables Map&lt;String,Object&gt;，本次正式提交采用的完整字段值
     * @return WorkflowProcessInstanceSnapshot，真实 Flowable 实例快照
     */
    public WorkflowProcessInstanceSnapshot startDraft(WfProcessDraft draft,
            String businessKey, Map<String, Object> variables,
            Map<String, java.util.List<Long>> multiInstanceUserIds)
    {
        if (draft == null)
        {
            throw new ServiceException("流程申请草稿不能为空", HttpStatus.BAD_REQUEST);
        }
        String normalizedBusinessKey = optionalText(businessKey,
                "流程业务主键长度不能超过" + MAX_ENGINE_ID_LENGTH, MAX_ENGINE_ID_LENGTH);
        return engineOperations.writeAsCurrentUser(actor -> startDraftInCurrentTransaction(
                actor, draft, normalizedBusinessKey, variables, multiInstanceUserIds));
    }

    /**
     * 执行草稿提交专用的最新版锁、部署快照复核、正式校验、引擎写入和附件迁移。
     *
     * @param actor WorkflowCurrentIdentity，同一事务内重新核验的当前身份
     * @param draft WfProcessDraft，已经由草稿服务锁定的持久化草稿
     * @param businessKey String，规范化业务主键
     * @param variables Map&lt;String,Object&gt;，完整提交字段值
     * @return WorkflowProcessInstanceSnapshot，新实例稳定快照
     */
    private WorkflowProcessInstanceSnapshot startDraftInCurrentTransaction(
            WorkflowCurrentIdentity actor, WfProcessDraft draft, String businessKey,
            Map<String, Object> variables,
            Map<String, java.util.List<Long>> multiInstanceUserIds)
    {
        if (!String.valueOf(draft.ownerUserId()).equals(actor.userId()))
        {
            throw new ServiceException("当前用户无权提交该流程申请草稿", HttpStatus.FORBIDDEN);
        }
        String deploymentId = requireText(draft.deploymentId(),
                "草稿绑定的流程部署关系异常", MAX_ENGINE_ID_LENGTH, HttpStatus.ERROR);
        // 草稿服务已经按 deployment->draft 取得同一锁；这里重入锁保证直接 Java 调用也不能绕过协议。
        lockDeploymentForStart(deploymentId, "DRAFT_DEFINITION_UNAVAILABLE");
        ProcessDefinition definition = requireExistingDefinition(draft.processDefinitionId());
        if (!draft.processDefinitionKey().equals(definition.getKey())
                || draft.processDefinitionVersion() != definition.getVersion()
                || !deploymentId.equals(definition.getDeploymentId()))
        {
            throw new ServiceException("草稿绑定的流程定义版本已失效", HttpStatus.CONFLICT)
                    .setSubCode("DRAFT_DEFINITION_VERSION_EXPIRED");
        }
        if (StringUtils.hasText(definition.getTenantId()))
        {
            throw new ServiceException("草稿绑定的流程租户关系异常", HttpStatus.ERROR);
        }
        // 持有默认租户 key 的最新定义范围锁，防止复核后并发部署新版本。
        try
        {
            assertLatestDefaultTenantDefinition(draft.processDefinitionKey(),
                    draft.processDefinitionId(), draft.deploymentId());
        }
        catch (ServiceException exception)
        {
            if (Integer.valueOf(HttpStatus.CONFLICT).equals(exception.getCode()))
            {
                exception.setSubCode(exception.getMessage().contains("挂起")
                        ? "DRAFT_DEFINITION_UNAVAILABLE"
                        : "DRAFT_DEFINITION_VERSION_EXPIRED");
            }
            throw exception;
        }
        WorkflowProcessFormView startForm = processQueryService.getProcessForm(
                new WorkflowProcessFormQueryDto(draft.processDefinitionId(),
                        draft.deploymentId(), null));
        assertSnapshotRelation(startForm, draft.processDefinitionId(), draft.deploymentId());
        String currentChecksum = WorkflowProcessDraftChecksum.sha256(startForm);
        if (!draft.sourceType().equals(startForm.sourceType())
                || !java.util.Objects.equals(draft.formId(), startForm.formId())
                || !draft.formKey().equals(startForm.formKey())
                || !draft.startNodeKey().equals(startForm.nodeKey())
                || !draft.formSnapshotSha256().equals(currentChecksum)
                || !draft.formSnapshot().equals(startForm.content()))
        {
            throw new ServiceException("草稿绑定的部署表单快照已失效", HttpStatus.CONFLICT)
                    .setSubCode("DRAFT_SNAPSHOT_MISMATCH");
        }
        WorkflowValidatedStartVariables validated = variableValidator.validateForStart(
                startForm.content(), variables);
        // 提交正文可以包含刚上传的 TEMP 附件；先对账为 DRAFT，再生成引擎安全投影。
        attachmentService.reconcileDraftAttachments(actor.userId(), draft.draftId(),
                validated.attachmentIdsByField());
        Map<String, Object> clientVariables = attachmentService.prepareDraftStartVariables(
                actor.userId(), draft.draftId(), validated.variables(),
                validated.attachmentIdsByField());
        ProcessDefinition activeDefinition = requireActiveDefinition(draft.processDefinitionId());
        if (!draft.deploymentId().equals(activeDefinition.getDeploymentId()))
        {
            throw new ServiceException("流程定义部署关系已发生变化", HttpStatus.CONFLICT);
        }
        // 草稿提交必须重新按当前组织身份校验发起范围，不能沿用创建或保存草稿时的历史权限。
        participantRuleRuntimeService.assertCanStart(actor, activeDefinition);
        LinkedHashMap<String, Object> engineVariables = new LinkedHashMap<>(clientVariables);
        // 草稿成员字段不进入普通变量白名单；提交时按部署模型和最新审批资格生成保留变量。
        engineVariables.putAll(WorkflowStartMultiInstanceContract.prepareVariables(
                repositoryService.getBpmnModel(draft.processDefinitionId()),
                definition.getKey(), multiInstanceUserIds, userSelectionValidator));
        engineVariables.put(INITIATOR_VARIABLE, actor.userId());
        engineVariables.put(PROCESS_STATUS_VARIABLE, RUNNING_STATUS);
        engineVariables.put(WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME,
                WorkflowFormSubmissionSnapshotCodec.encodeStart(draft.deploymentId(),
                        startForm.sourceType(), startForm.formId(), startForm.formKey(),
                        startForm.nodeKey(), clientVariables));
        ProcessInstance instance = runtimeService.startProcessInstanceById(
                draft.processDefinitionId(), businessKey,
                Collections.unmodifiableMap(engineVariables));
        WorkflowProcessInstanceSnapshot snapshot = toSnapshot(instance,
                draft.processDefinitionId(), businessKey);
        attachmentService.bindDraftStartAttachments(actor.userId(), draft.draftId(),
                snapshot.id(), startForm.nodeKey(), validated.attachmentIdsByField());
        return snapshot;
    }

    /**
     * 在已建立的当前用户写事务内执行 definitionId 与 definitionKey 共用的完整发起链。
     *
     * @param actor WorkflowCurrentIdentity，事务内重新核验的正式当前用户
     * @param definitionId String，服务端已选定的流程定义主键
     * @param businessKey String，规范化后的可选业务主键
     * @param variables Map&lt;String, Object&gt;，待按部署表单 schema 校验的客户端变量
     * @param multiInstanceUserIds Map&lt;String,List&lt;Long&gt;&gt;，发起时按活动选择的多实例成员
     * @param expectedDefaultTenantKey String，key 兼容入口使用的默认租户定义 key；按 ID 发起时为 null
     * @return WorkflowProcessInstanceSnapshot，新实例的稳定不可变快照
     */
    private WorkflowProcessInstanceSnapshot startInCurrentTransaction(
            WorkflowCurrentIdentity actor, String definitionId, String businessKey,
            Map<String, Object> variables, Map<String, java.util.List<Long>> multiInstanceUserIds,
            String expectedDefaultTenantKey)
    {
        // 首次查询只用于取得服务端真实 deploymentId，客户端不能声明或替换部署关系。
        ProcessDefinition selectedDefinition = requireExistingDefinition(definitionId);
        String deploymentId = requireText(selectedDefinition.getDeploymentId(),
                "流程定义部署关系异常", MAX_ENGINE_ID_LENGTH, HttpStatus.ERROR);
        // 所有正式发起入口先持有部署生命周期锁，使删除与实例创建形成单一线性化顺序。
        lockDeploymentForStart(deploymentId, null);

        // 复用查询服务已经审计的 latest、active、starter identity link 和开始节点快照门禁。
        WorkflowProcessFormView startForm = processQueryService.getProcessForm(
                new WorkflowProcessFormQueryDto(definitionId, deploymentId, null));
        assertSnapshotRelation(startForm, definitionId, deploymentId);
        WorkflowValidatedStartVariables validatedVariables = variableValidator.validateForStart(
                startForm.content(), variables);
        Map<String, Object> clientVariables = attachmentService.prepareStartVariables(
                actor.userId(), validatedVariables.variables(),
                validatedVariables.attachmentIdsByField());

        // 变量校验完成后重新按 active 查询，阻止校验期间被挂起或删除的定义继续发起。
        ProcessDefinition activeDefinition = requireActiveDefinition(definitionId);
        if (!deploymentId.equals(activeDefinition.getDeploymentId()))
        {
            throw new ServiceException("流程定义部署关系已发生变化", HttpStatus.CONFLICT);
        }
        if (expectedDefaultTenantKey != null)
        {
            // 锁定当前已提交最新版并持有 key 范围锁，避免旧快照漏检或复核后又插入新版本。
            assertLatestDefaultTenantDefinition(expectedDefaultTenantKey,
                    definitionId, deploymentId);
        }

        // 发起范围在引擎写入前按不可变部署快照和当前有效组织授权，拒绝请求不得创建实例。
        participantRuleRuntimeService.assertCanStart(actor, activeDefinition);

        LinkedHashMap<String, Object> engineVariables = new LinkedHashMap<>(clientVariables);
        // 发起多实例字段不属于通用表单变量；必须按部署 BPMN 精确校验后由服务端生成保留变量。
        engineVariables.putAll(WorkflowStartMultiInstanceContract.prepareVariables(
                repositoryService.getBpmnModel(definitionId), activeDefinition.getKey(),
                multiInstanceUserIds, userSelectionValidator));
        engineVariables.put(INITIATOR_VARIABLE, actor.userId());
        engineVariables.put(PROCESS_STATUS_VARIABLE, RUNNING_STATUS);
        // 快照与业务变量随同一次 start 命令写入，启动或附件绑定失败时由外层事务整体回滚。
        engineVariables.put(WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME,
                WorkflowFormSubmissionSnapshotCodec.encodeStart(deploymentId,
                        startForm.sourceType(), startForm.formId(), startForm.formKey(),
                        startForm.nodeKey(), clientVariables));
        Map<String, Object> immutableVariables = Collections.unmodifiableMap(engineVariables);

        ProcessInstance processInstance = runtimeService.startProcessInstanceById(
                definitionId, businessKey, immutableVariables);
        WorkflowProcessInstanceSnapshot snapshot = toSnapshot(
                processInstance, definitionId, businessKey);
        // 实例主键取自 RuntimeService，节点 key 取自部署快照；任一附件失败都会回滚本次引擎发起。
        attachmentService.bindStartAttachments(actor.userId(), snapshot.id(),
                startForm.nodeKey(), validatedVariables.attachmentIdsByField());
        return snapshot;
    }

    /**
     * 解析默认租户下指定 key 的最新定义，并拒绝无定义或最新版挂起状态。
     *
     * @param processKey String，已经过长度和空值校验的流程定义 key
     * @return ProcessDefinition，默认租户下最新且激活的流程定义
     */
    private ProcessDefinition requireLatestActiveDefaultTenantDefinition(String processKey)
    {
        ProcessDefinition latestDefinition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(processKey)
                .processDefinitionWithoutTenantId()
                .latestVersion()
                .singleResult();
        if (latestDefinition == null)
        {
            throw new ServiceException("流程定义不存在或已被删除", HttpStatus.NOT_FOUND);
        }
        if (!StringUtils.hasText(latestDefinition.getId())
                || !processKey.equals(latestDefinition.getKey())
                || StringUtils.hasText(latestDefinition.getTenantId()))
        {
            throw new ServiceException("流程定义解析结果异常", HttpStatus.ERROR);
        }
        if (latestDefinition.isSuspended())
        {
            throw new ServiceException("流程定义已挂起", HttpStatus.CONFLICT);
        }
        return latestDefinition;
    }

    /**
     * 通过 MySQL 锁定当前读复核 key 入口仍指向同一默认租户最新激活定义。
     * 锁随外层发起事务提交或回滚释放，使并发部署与实例创建具备确定线性化顺序。
     *
     * @param processKey String，兼容入口解析的流程定义 key
     * @param expectedDefinitionId String，事务开始阶段选定的流程定义主键
     * @param expectedDeploymentId String，事务开始阶段解析的部署主键
     * @return void，无返回值；并发部署导致最新版变化时抛出 HTTP 409
     */
    private void assertLatestDefaultTenantDefinition(String processKey,
            String expectedDefinitionId, String expectedDeploymentId)
    {
        WorkflowProcessDefinitionLockRow latestDefinition = definitionLockMapper
                .selectLatestDefaultTenantDefinitionForUpdate(processKey);
        if (latestDefinition == null
                || !expectedDefinitionId.equals(latestDefinition.definitionId()))
        {
            throw new ServiceException("流程定义最新版已发生变化", HttpStatus.CONFLICT);
        }
        if (!StringUtils.hasText(latestDefinition.deploymentId())
                || !expectedDeploymentId.equals(latestDefinition.deploymentId()))
        {
            throw new ServiceException("流程定义部署关系已发生变化", HttpStatus.CONFLICT);
        }
        if (!Integer.valueOf(1).equals(latestDefinition.suspensionState()))
        {
            if (Integer.valueOf(2).equals(latestDefinition.suspensionState()))
            {
                throw new ServiceException("流程定义已挂起", HttpStatus.CONFLICT);
            }
            throw new ServiceException("流程定义状态数据异常", HttpStatus.ERROR);
        }
    }

    /**
     * 锁定实例目标部署并确认定义查询后部署仍存在。
     *
     * @param deploymentId String，由真实流程定义或持久化草稿解析的部署主键
     * @param conflictSubCode String，调用域需要返回的稳定冲突子码；普通发起可为空
     * @return void，成功时部署锁保持到外层发起事务提交或回滚
     */
    private void lockDeploymentForStart(String deploymentId, String conflictSubCode)
    {
        String lockedDeploymentId = definitionLockMapper
                .selectDeploymentIdForUpdate(deploymentId);
        if (deploymentId.equals(lockedDeploymentId))
        {
            return;
        }
        ServiceException conflict = new ServiceException(
                "流程定义部署状态已变化，请刷新后重试", HttpStatus.CONFLICT);
        if (StringUtils.hasText(conflictSubCode))
        {
            conflict.setSubCode(conflictSubCode);
        }
        throw conflict;
    }

    /**
     * 查询客户端选择的真实流程定义，用于服务端解析部署关系。
     *
     * @param definitionId String，已校验的流程定义主键
     * @return ProcessDefinition，存在的 Flowable 流程定义
     */
    private ProcessDefinition requireExistingDefinition(String definitionId)
    {
        ProcessDefinition definition = repositoryService.getProcessDefinition(definitionId);
        if (definition == null)
        {
            throw new ServiceException("流程定义不存在或已被删除", HttpStatus.NOT_FOUND);
        }
        return definition;
    }

    /**
     * 在实际 start 前重新查询激活定义，并区分不存在与挂起/状态竞争。
     *
     * @param definitionId String，已完成 starter 与快照校验的定义主键
     * @return ProcessDefinition，仍处于激活状态的同一定义
     */
    private ProcessDefinition requireActiveDefinition(String definitionId)
    {
        ProcessDefinitionQuery query = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(definitionId)
                .active();
        ProcessDefinition activeDefinition = query.singleResult();
        if (activeDefinition != null)
        {
            if (activeDefinition.isSuspended())
            {
                throw new ServiceException("流程定义状态数据异常", HttpStatus.ERROR);
            }
            return activeDefinition;
        }

        ProcessDefinition existingDefinition = repositoryService.getProcessDefinition(definitionId);
        if (existingDefinition == null)
        {
            throw new ServiceException("流程定义不存在或已被删除", HttpStatus.NOT_FOUND);
        }
        if (existingDefinition.isSuspended())
        {
            throw new ServiceException("流程定义已挂起", HttpStatus.CONFLICT);
        }
        throw new ServiceException("流程定义状态已发生变化，请刷新后重试", HttpStatus.CONFLICT);
    }

    /**
     * 复核查询服务返回的快照确实属于当前定义和服务端解析的部署。
     *
     * @param startForm WorkflowProcessFormView，查询服务返回的开始表单快照
     * @param definitionId String，当前流程定义主键
     * @param deploymentId String，定义所属服务端部署主键
     * @return void，关联或正文异常时抛出服务端数据异常
     */
    private void assertSnapshotRelation(WorkflowProcessFormView startForm,
            String definitionId, String deploymentId)
    {
        if (startForm == null
                || !definitionId.equals(startForm.definitionId())
                || !deploymentId.equals(startForm.deploymentId())
                || startForm.processInstanceId() != null
                || !StringUtils.hasText(startForm.content()))
        {
            throw new ServiceException("流程部署表单快照关联异常", HttpStatus.ERROR);
        }
    }

    /**
     * 将 Flowable 可变运行实例转换为模块稳定快照，并拒绝异常返回或并发挂起。
     *
     * @param processInstance ProcessInstance，RuntimeService 返回的新实例
     * @param definitionId String，实际发起的流程定义主键
     * @param businessKey String，规范化后的可选业务主键
     * @return WorkflowProcessInstanceSnapshot，新实例不可变快照
     */
    private WorkflowProcessInstanceSnapshot toSnapshot(ProcessInstance processInstance,
            String definitionId, String businessKey)
    {
        if (processInstance == null || !StringUtils.hasText(processInstance.getId())
                || !definitionId.equals(processInstance.getProcessDefinitionId()))
        {
            throw new ServiceException("流程实例创建结果异常", HttpStatus.ERROR);
        }
        if (processInstance.isSuspended())
        {
            // 新实例若在同一命令中已被挂起，回滚本次发起，避免返回不可办理实例。
            throw new ServiceException("流程定义状态已发生变化，请刷新后重试", HttpStatus.CONFLICT);
        }
        return new WorkflowProcessInstanceSnapshot(processInstance.getId(), definitionId,
                businessKey, false);
    }

    /**
     * 校验必填文本并规范化首尾空白。
     *
     * @param value String，待校验文本
     * @param message String，空值或过长时的稳定提示
     * @param maxLength int，允许的最大字符数
     * @return String，去除首尾空白后的文本
     */
    private String requireText(String value, String message, int maxLength)
    {
        return requireText(value, message, maxLength, HttpStatus.BAD_REQUEST);
    }

    /**
     * 使用指定错误码校验内部或客户端必填文本。
     *
     * @param value String，待校验文本
     * @param message String，空值或过长时的稳定提示
     * @param maxLength int，允许的最大字符数
     * @param status int，校验失败时的 HTTP 语义
     * @return String，去除首尾空白后的文本
     */
    private String requireText(String value, String message, int maxLength, int status)
    {
        if (!StringUtils.hasText(value))
        {
            throw new ServiceException(message, status);
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength)
        {
            throw new ServiceException(message, status);
        }
        return normalized;
    }

    /**
     * 规范化可选文本，空白按未提供处理并限制数据库列长度。
     *
     * @param value String，可为空的原始文本
     * @param message String，文本过长时的稳定提示
     * @param maxLength int，允许的最大字符数
     * @return String，null 或规范化后的非空文本
     */
    private String optionalText(String value, String message, int maxLength)
    {
        if (!StringUtils.hasText(value))
        {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength)
        {
            throw new ServiceException(message, HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }
}

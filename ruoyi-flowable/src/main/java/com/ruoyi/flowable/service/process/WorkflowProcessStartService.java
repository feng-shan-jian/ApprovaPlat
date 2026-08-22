package com.ruoyi.flowable.service.process;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WorkflowProcessDefinitionLockRow;
import com.ruoyi.flowable.domain.WfProcessDraft;
import com.ruoyi.flowable.domain.dto.StartProcessRequest;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowProcessInstanceSnapshot;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;
import com.ruoyi.flowable.mapper.WorkflowProcessDefinitionLockMapper;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;
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

    /**
     * 创建真实流程发起服务。
     *
     * @param engineOperations WorkflowEngineOperations，统一事务、当前身份和引擎异常边界
     * @param repositoryService RepositoryService，流程定义和状态公共 API
     * @param runtimeService RuntimeService，真实发起流程实例公共 API
     * @param processQueryService WorkflowProcessQueryService，starter 授权与部署开始表单快照门禁
     * @param variableValidator WorkflowStartVariableValidator，开始表单变量 schema 验证器
     * @param attachmentService WorkflowAttachmentService，临时附件校验、投影和事务绑定服务
     * @param definitionLockMapper WorkflowProcessDefinitionLockMapper，草稿提交最新版定义当前读和部署生命周期行锁
     * @param userSelectionValidator WorkflowUserSelectionValidator，发起时会签或或签成员审批资格校验器
     * @return 无返回值，构造后由 Spring 管理该服务
     */
    public WorkflowProcessStartService(WorkflowEngineOperations engineOperations,
            RepositoryService repositoryService, RuntimeService runtimeService,
            WorkflowProcessQueryService processQueryService,
            WorkflowStartVariableValidator variableValidator,
            WorkflowAttachmentService attachmentService,
            WorkflowProcessDefinitionLockMapper definitionLockMapper,
            WorkflowUserSelectionValidator userSelectionValidator)
    {
        this.engineOperations = engineOperations;
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.processQueryService = processQueryService;
        this.variableValidator = variableValidator;
        this.attachmentService = attachmentService;
        this.definitionLockMapper = definitionLockMapper;
        this.userSelectionValidator = userSelectionValidator;
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
                request.multiInstanceUserIds()));
    }

    /**
     * 在草稿服务已经锁定草稿行的同一写事务中重新校验全部正式规则并创建唯一实例。
     *
     * @param actor WorkflowCurrentIdentity，外层写事务已经核验的当前有效身份
     * @param draft WfProcessDraft，包含定义版本和不可变部署表单快照的本人活动草稿
     * @param businessKey String，本次正式提交采用的业务主键
     * @param variables Map&lt;String,Object&gt;，本次正式提交采用的完整字段值
     * @param multiInstanceUserIds Map&lt;String,List&lt;Long&gt;&gt;，已规范化的多实例人员选择
     * @return DraftStartResult，真实实例快照及唯一一次 schema 校验得到的规范化变量
     */
    DraftStartResult startDraft(WorkflowCurrentIdentity actor, WfProcessDraft draft,
            String businessKey, Map<String, Object> variables,
            Map<String, java.util.List<Long>> multiInstanceUserIds)
    {
        if (actor == null || draft == null)
        {
            throw new ServiceException("流程申请草稿不能为空", HttpStatus.BAD_REQUEST);
        }
        if (!String.valueOf(draft.ownerUserId()).equals(actor.userId()))
        {
            throw new ServiceException("当前用户无权提交该流程申请草稿", HttpStatus.FORBIDDEN);
        }
        String deploymentId = requireText(draft.deploymentId(),
                "草稿绑定的流程部署关系异常", MAX_ENGINE_ID_LENGTH, HttpStatus.ERROR);
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
        WorkflowProcessQueryService.StartFormLoad formLoad = processQueryService
                .loadStartFormInCurrentTransaction(actor, definition);
        WorkflowProcessFormView startForm = formLoad.form();
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
        // 同一批锁定结果完成 TEMP->DRAFT、移除项处理、完整性校验和安全变量投影。
        Map<String, Object> clientVariables = attachmentService.prepareDraftSubmissionVariables(
                actor.userId(), draft.draftId(), validated.variables(),
                validated.attachmentIdsByField());
        ProcessDefinition activeDefinition = requireActiveDefinition(draft.processDefinitionId());
        if (!draft.deploymentId().equals(activeDefinition.getDeploymentId()))
        {
            throw new ServiceException("流程定义部署关系已发生变化", HttpStatus.CONFLICT);
        }
        WorkflowProcessInstanceSnapshot snapshot = startEngine(actor, activeDefinition,
                businessKey, formLoad.bpmnModel(), startForm, clientVariables,
                multiInstanceUserIds);
        attachmentService.bindDraftStartAttachments(actor.userId(), draft.draftId(),
                snapshot.id(), startForm.nodeKey(), validated.attachmentIdsByField());
        return new DraftStartResult(snapshot, validated.variables());
    }

    /**
     * 在已建立的当前用户写事务内执行完整发起链。
     *
     * @param actor WorkflowCurrentIdentity，事务内重新核验的正式当前用户
     * @param definitionId String，服务端已选定的流程定义主键
     * @param businessKey String，规范化后的可选业务主键
     * @param variables Map&lt;String, Object&gt;，待按部署表单 schema 校验的客户端变量
     * @param multiInstanceUserIds Map&lt;String,List&lt;Long&gt;&gt;，发起时按活动选择的多实例成员
     * @return WorkflowProcessInstanceSnapshot，新实例的稳定不可变快照
     */
    private WorkflowProcessInstanceSnapshot startInCurrentTransaction(
            WorkflowCurrentIdentity actor, String definitionId, String businessKey,
            Map<String, Object> variables, Map<String, java.util.List<Long>> multiInstanceUserIds)
    {
        // 首次查询只用于取得服务端真实 deploymentId，客户端不能声明或替换部署关系。
        ProcessDefinition selectedDefinition = requireExistingDefinition(definitionId);
        String deploymentId = requireText(selectedDefinition.getDeploymentId(),
                "流程定义部署关系异常", MAX_ENGINE_ID_LENGTH, HttpStatus.ERROR);
        // 所有正式发起入口先持有部署生命周期锁，使删除与实例创建形成单一线性化顺序。
        lockDeploymentForStart(deploymentId);

        // 当前写事务直接复用同一身份、定义和 BPMN 模型完成唯一一次正式表单与授权判定。
        WorkflowProcessQueryService.StartFormLoad formLoad = processQueryService
                .loadStartFormInCurrentTransaction(actor, selectedDefinition);
        WorkflowProcessFormView startForm = formLoad.form();
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
        WorkflowProcessInstanceSnapshot snapshot = startEngine(actor, activeDefinition,
                businessKey, formLoad.bpmnModel(), startForm, clientVariables,
                multiInstanceUserIds);
        // 实例主键取自 RuntimeService，节点 key 取自部署快照；任一附件失败都会回滚本次引擎发起。
        attachmentService.bindStartAttachments(actor.userId(), snapshot.id(),
                startForm.nodeKey(), validatedVariables.attachmentIdsByField());
        return snapshot;
    }

    /**
     * 将两个入口已经准备完成的确定数据合并为唯一 Flowable 实例启动命令。
     *
     * @param actor WorkflowCurrentIdentity，外层写事务已经核验的当前发起人
     * @param definition ProcessDefinition，启动前重新确认激活且部署关系不变的定义
     * @param businessKey String，仅由入口规范化一次的可选业务主键
     * @param bpmnModel BpmnModel，开始表单装载阶段唯一读取的 BPMN 模型
     * @param startForm WorkflowProcessFormView，同一模型对应的不可变部署表单快照
     * @param clientVariables Map&lt;String,Object&gt;，schema 与附件安全投影完成后的业务变量
     * @param multiInstanceUserIds Map&lt;String,List&lt;Long&gt;&gt;，已由入口规范化的多实例成员
     * @return WorkflowProcessInstanceSnapshot，真实引擎创建并经关系校验的实例快照
     */
    private WorkflowProcessInstanceSnapshot startEngine(WorkflowCurrentIdentity actor,
            ProcessDefinition definition, String businessKey, BpmnModel bpmnModel,
            WorkflowProcessFormView startForm, Map<String, Object> clientVariables,
            Map<String, java.util.List<Long>> multiInstanceUserIds)
    {
        LinkedHashMap<String, Object> engineVariables = new LinkedHashMap<>(clientVariables);
        // 多实例保留变量必须基于同一次模型读取，并在引擎写入前实时复核人员审批资格。
        engineVariables.putAll(WorkflowStartMultiInstanceContract.prepareVariables(
                bpmnModel, definition.getKey(), multiInstanceUserIds, userSelectionValidator));
        engineVariables.put(INITIATOR_VARIABLE, actor.userId());
        engineVariables.put(PROCESS_STATUS_VARIABLE, RUNNING_STATUS);
        engineVariables.put(WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME,
                WorkflowFormSubmissionSnapshotCodec.encodeStart(definition.getDeploymentId(),
                        startForm.sourceType(), startForm.formId(), startForm.formKey(),
                        startForm.nodeKey(), clientVariables));
        ProcessInstance instance = runtimeService.startProcessInstanceById(definition.getId(),
                businessKey, Collections.unmodifiableMap(engineVariables));
        return toSnapshot(instance, definition.getId(), businessKey);
    }

    /**
     * 通过 MySQL 锁定当前读复核草稿仍指向同一默认租户最新激活定义。
     * 锁随外层发起事务提交或回滚释放，使并发部署与草稿提交具备确定线性化顺序。
     *
     * @param processKey String，草稿持久化的流程定义 key
     * @param expectedDefinitionId String，草稿绑定的流程定义主键
     * @param expectedDeploymentId String，草稿绑定的部署主键
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
     * @return void，成功时部署锁保持到外层发起事务提交或回滚
     */
    private void lockDeploymentForStart(String deploymentId)
    {
        String lockedDeploymentId = definitionLockMapper
                .selectDeploymentIdForUpdate(deploymentId);
        if (deploymentId.equals(lockedDeploymentId))
        {
            return;
        }
        throw new ServiceException("流程定义部署状态已变化，请刷新后重试",
                HttpStatus.CONFLICT);
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

    /**
     * 传递草稿启动实例与同一次 schema 校验得到的规范变量。
     *
     * @param instance WorkflowProcessInstanceSnapshot，真实 Flowable 实例快照
     * @param normalizedVariables Map&lt;String,Object&gt;，供草稿 SUBMITTED 持久化的规范字段
     */
    record DraftStartResult(WorkflowProcessInstanceSnapshot instance,
            Map<String, Object> normalizedVariables) { }
}

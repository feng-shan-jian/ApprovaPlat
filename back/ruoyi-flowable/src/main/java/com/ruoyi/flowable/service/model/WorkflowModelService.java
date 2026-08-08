package com.ruoyi.flowable.service.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.FlowableListener;
import org.flowable.bpmn.model.GraphicInfo;
import org.flowable.bpmn.model.ImplementationType;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.DeploymentBuilder;
import org.flowable.engine.repository.Model;
import org.flowable.engine.repository.ModelQuery;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCategory;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.WfForm;
import com.ruoyi.flowable.domain.WorkflowModelLockRow;
import com.ruoyi.flowable.domain.WorkflowModelSaveRecord;
import com.ruoyi.flowable.domain.dto.WorkflowModelDto;
import com.ruoyi.flowable.domain.vo.WorkflowBpmnValidationIssue;
import com.ruoyi.flowable.domain.vo.WorkflowBpmnValidationReport;
import com.ruoyi.flowable.domain.vo.WorkflowModelView;
import com.ruoyi.flowable.domain.vo.WorkflowPageResult;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.mapper.WfCategoryMapper;
import com.ruoyi.flowable.mapper.WfDeployFormMapper;
import com.ruoyi.flowable.mapper.WfFormMapper;
import com.ruoyi.flowable.mapper.WorkflowModelSaveMapper;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.process.WorkflowStartVariableValidator;

/**
 * 流程模型版本、BPMN 设计和部署快照的业务服务。
 */
@Service
public class WorkflowModelService
{
    /** 单页允许返回的最大记录数，防止无界查询拖垮引擎数据库。 */
    static final int MAX_PAGE_SIZE = 200;

    /** Flowable 模型 key 同时作为部署 key 和 BPMN 资源名前缀，必须限制为安全字符。 */
    private static final Pattern MODEL_KEY_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,127}");

    /** 有效业务数据的逻辑删除标志。 */
    private static final String ACTIVE_DEL_FLAG = "0";

    /** 流程级表单模式编码。 */
    private static final int PROCESS_FORM_TYPE = 0;

    /** 支持的表单模式最小值。 */
    private static final int MIN_FORM_TYPE = 0;

    /** 支持的表单模式最大值。 */
    private static final int MAX_FORM_TYPE = 2;

    /** ACT_RE_MODEL.NAME_ 的字符上限。 */
    private static final int MODEL_TEXT_MAX_LENGTH = 255;

    /** wf_category.code 的字符上限，模型分类必须能无损关联业务分类表。 */
    private static final int CATEGORY_CODE_MAX_LENGTH = 64;

    /** 为 createUser、formType、formId 和 JSON 转义预留空间后的描述上限。 */
    private static final int DESCRIPTION_MAX_LENGTH = 3000;

    /** ACT_RE_MODEL.META_INFO_ 的字符上限。 */
    private static final int META_INFO_MAX_LENGTH = 4000;

    /** 初始用户任务必须使用的受控任务监听 Bean 表达式。 */
    private static final String USER_TASK_LISTENER_EXPRESSION = "${userTaskListener}";

    /** 初始用户任务必须完整注册的身份审计生命周期事件。 */
    private static final List<String> USER_TASK_LISTENER_EVENTS = List.of(
            TaskListener.EVENTNAME_CREATE,
            TaskListener.EVENTNAME_ASSIGNMENT,
            TaskListener.EVENTNAME_COMPLETE);

    /** 保存幂等载荷使用的稳定摘要算法。 */
    private static final String SAVE_PAYLOAD_DIGEST_ALGORITHM = "SHA-256";

    private final WorkflowEngineOperations engineOperations;

    private final RepositoryService repositoryService;

    private final WorkflowBpmnService bpmnService;

    private final WorkflowBpmnIdentityValidator bpmnIdentityValidator;

    private final WorkflowModelSaveMapper modelSaveMapper;

    private final WfCategoryMapper categoryMapper;

    private final WfFormMapper formMapper;

    private final WfDeployFormMapper deployFormMapper;

    private final WorkflowFormTemplateValidator formTemplateValidator;

    private final WorkflowStartVariableValidator startVariableValidator;

    private final WorkflowExtensionDeploymentService extensionDeploymentService;

    /** 受控重复审批循环的执行模型编译和部署快照服务。 */
    private final WorkflowControlledLoopDeploymentService controlledLoopDeploymentService;

    /** 排他和包容网关的受控条件编译及部署快照服务。 */
    private WorkflowConditionDeploymentService conditionDeploymentService;

    private final WorkflowDmnDecisionService dmnDecisionService;

    /** 部署时锁定自定义表单字段精确版本；旧构造测试可为空。 */
    private final WorkflowFormFieldExtensionService formFieldExtensionService;

    /** 部署时把调用活动编译为精确定义引用；旧构造测试可为空。 */
    private final WorkflowCallActivityReferenceService callActivityReferenceService;

    /** 审批 SLA 真实边界定时器编译和部署快照服务；兼容旧单元构造时可为空。 */
    private WorkflowTaskSlaDeploymentService taskSlaDeploymentService;

    /** 发起范围和单实例任务参与者规则编译及部署快照服务；兼容旧单元构造时可为空。 */
    private WorkflowParticipantRuleDeploymentService participantRuleDeploymentService;

    /** Flowable 模型 metaInfo 的 Jackson 3 结构化读写器。 */
    private final ObjectMapper metadataMapper = JsonMapper.shared();

    /**
     * 创建流程模型服务。
     *
     * @param engineOperations WorkflowEngineOperations，统一事务、身份和异常边界
     * @param repositoryService RepositoryService，Flowable 8 仓储公共 API
     * @param bpmnService WorkflowBpmnService，BPMN 安全解析和业务校验组件
     * @param bpmnIdentityValidator WorkflowBpmnIdentityValidator，部署前静态身份主数据校验器
     * @param modelSaveMapper WorkflowModelSaveMapper，模型版本当前读锁与保存幂等数据访问层
     * @param categoryMapper WfCategoryMapper，工作流分类数据访问层
     * @param formMapper WfFormMapper，可编辑表单模板数据访问层
     * @param deployFormMapper WfDeployFormMapper，部署表单快照数据访问层
     * @param formTemplateValidator WorkflowFormTemplateValidator，表单 JSON 安全结构验证器
     * @param extensionDeploymentService WorkflowExtensionDeploymentService，扩展编译和版本快照服务
     * @param controlledLoopDeploymentService WorkflowControlledLoopDeploymentService，受控循环编译和快照服务
     * @param dmnDecisionService WorkflowDmnDecisionService，DMN 精确引用编译和冻结服务
     * @param formFieldExtensionService WorkflowFormFieldExtensionService，自定义字段部署冻结服务
     * @param callActivityReferenceService WorkflowCallActivityReferenceService，调用活动精确版本编译与保护服务
     * @return 无返回值，构造后由 Spring 管理该服务
     */
    @Autowired
    public WorkflowModelService(WorkflowEngineOperations engineOperations,
            RepositoryService repositoryService, WorkflowBpmnService bpmnService,
            WorkflowBpmnIdentityValidator bpmnIdentityValidator,
            WorkflowModelSaveMapper modelSaveMapper,
            WfCategoryMapper categoryMapper, WfFormMapper formMapper,
            WfDeployFormMapper deployFormMapper,
            WorkflowFormTemplateValidator formTemplateValidator,
            WorkflowExtensionDeploymentService extensionDeploymentService,
            WorkflowControlledLoopDeploymentService controlledLoopDeploymentService,
            WorkflowDmnDecisionService dmnDecisionService,
            WorkflowFormFieldExtensionService formFieldExtensionService,
            WorkflowCallActivityReferenceService callActivityReferenceService)
    {
        this.engineOperations = engineOperations;
        this.repositoryService = repositoryService;
        this.bpmnService = bpmnService;
        this.bpmnIdentityValidator = bpmnIdentityValidator;
        this.modelSaveMapper = modelSaveMapper;
        this.categoryMapper = categoryMapper;
        this.formMapper = formMapper;
        this.deployFormMapper = deployFormMapper;
        this.formTemplateValidator = formTemplateValidator;
        this.startVariableValidator = new WorkflowStartVariableValidator(formTemplateValidator);
        this.extensionDeploymentService = extensionDeploymentService;
        this.controlledLoopDeploymentService = controlledLoopDeploymentService;
        this.dmnDecisionService = dmnDecisionService;
        this.formFieldExtensionService = formFieldExtensionService;
        this.callActivityReferenceService = callActivityReferenceService;
    }

    /**
     * 延迟注入 SLA 部署编译器，保留既有大量直接构造单元测试的兼容性。
     * @param taskSlaDeploymentService WorkflowTaskSlaDeploymentService，真实边界定时器编译和快照服务
     * @return void，生产 Spring 容器启动后必须完成注入
     */
    @Autowired
    public void setTaskSlaDeploymentService(
            WorkflowTaskSlaDeploymentService taskSlaDeploymentService)
    {
        this.taskSlaDeploymentService = taskSlaDeploymentService;
    }

    /**
     * 延迟注入参与者规则部署服务，保留既有直接构造单元测试兼容性。
     * @param participantRuleDeploymentService WorkflowParticipantRuleDeploymentService，规则编译与快照服务
     * @return void，生产 Spring 容器启动后必须完成注入
     */
    @Autowired
    public void setParticipantRuleDeploymentService(
            WorkflowParticipantRuleDeploymentService participantRuleDeploymentService)
    {
        this.participantRuleDeploymentService = participantRuleDeploymentService;
    }

    /**
     * 延迟注入条件分支编译器，避免扩大既有直接构造单元测试的构造参数。
     * @param conditionDeploymentService WorkflowConditionDeploymentService，受控条件编译服务
     * @return void，生产 Spring 容器启动后必须完成注入
     */
    @Autowired
    public void setConditionDeploymentService(
            WorkflowConditionDeploymentService conditionDeploymentService)
    {
        this.conditionDeploymentService = conditionDeploymentService;
    }

    /**
     * 兼容不涉及自定义表单字段的既有纯单元测试构造方式。
     * @param engineOperations WorkflowEngineOperations，统一事务、身份和异常边界
     * @param repositoryService RepositoryService，Flowable 仓储 API
     * @param bpmnService WorkflowBpmnService，BPMN 校验服务
     * @param bpmnIdentityValidator WorkflowBpmnIdentityValidator，身份校验器
     * @param modelSaveMapper WorkflowModelSaveMapper，模型保存数据访问层
     * @param categoryMapper WfCategoryMapper，分类数据访问层
     * @param formMapper WfFormMapper，表单数据访问层
     * @param deployFormMapper WfDeployFormMapper，部署表单快照数据访问层
     * @param formTemplateValidator WorkflowFormTemplateValidator，表单安全验证器
     * @param extensionDeploymentService WorkflowExtensionDeploymentService，扩展部署服务
     * @param dmnDecisionService WorkflowDmnDecisionService，DMN 冻结服务
     * @return 无返回值，仅为既有测试保留
     */
    public WorkflowModelService(WorkflowEngineOperations engineOperations,
            RepositoryService repositoryService, WorkflowBpmnService bpmnService,
            WorkflowBpmnIdentityValidator bpmnIdentityValidator,
            WorkflowModelSaveMapper modelSaveMapper,
            WfCategoryMapper categoryMapper, WfFormMapper formMapper,
            WfDeployFormMapper deployFormMapper,
            WorkflowFormTemplateValidator formTemplateValidator,
            WorkflowExtensionDeploymentService extensionDeploymentService,
            WorkflowDmnDecisionService dmnDecisionService)
    {
        this(engineOperations, repositoryService, bpmnService, bpmnIdentityValidator,
                modelSaveMapper, categoryMapper, formMapper, deployFormMapper,
                formTemplateValidator, extensionDeploymentService, null,
                dmnDecisionService, null, null);
    }

    /**
     * 查询每个模型 key 的最新版本，并使用 Flowable 原生 count/listPage 完成分页。
     *
     * @param filter WorkflowModelDto，模型 key、名称和分类查询条件，允许为空
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return WorkflowPageResult&lt;WorkflowModelView&gt;，最新模型分页结果
     */
    public WorkflowPageResult<WorkflowModelView> list(WorkflowModelDto filter, int pageNum, int pageSize)
    {
        PageWindow page = requirePage(pageNum, pageSize);
        return engineOperations.read(() ->
        {
            ModelQuery query = buildModelQuery(filter, true).orderByCreateTime().desc();
            long total = query.count();
            if (total == 0)
            {
                return new WorkflowPageResult<>(List.of(), 0);
            }
            List<WorkflowModelView> rows = query.listPage(page.offset(), page.pageSize()).stream()
                    .map(model -> toView(model, null, null))
                    .toList();
            return new WorkflowPageResult<>(rows, total);
        });
    }

    /**
     * 查询指定模型 key 的历史版本，结果不包含当前最新版本。
     *
     * @param filter WorkflowModelDto，必须包含模型 key，可附带名称和分类条件
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return WorkflowPageResult&lt;WorkflowModelView&gt;，旧版本模型分页结果
     */
    public WorkflowPageResult<WorkflowModelView> historyList(WorkflowModelDto filter,
            int pageNum, int pageSize)
    {
        PageWindow page = requirePage(pageNum, pageSize);
        String modelKey = requireText(filter == null ? null : filter.getModelKey(), "模型标识不能为空");
        return engineOperations.read(() ->
        {
            ModelQuery query = buildModelQuery(filter, false).modelKey(modelKey)
                    .orderByModelVersion().desc();
            long versionCount = query.count();
            long historyTotal = Math.max(0, versionCount - 1);
            if (historyTotal == 0 || page.offset() >= historyTotal)
            {
                return new WorkflowPageResult<>(List.of(), historyTotal);
            }

            // 排序后的第 0 条是最新版本，历史分页必须整体向后偏移一条。
            List<WorkflowModelView> rows = query.listPage(page.offset() + 1, page.pageSize()).stream()
                    .map(model -> toView(model, null, null))
                    .toList();
            return new WorkflowPageResult<>(rows, historyTotal);
        });
    }

    /**
     * 查询模型详情、BPMN XML 以及模型级流程表单内容。
     *
     * @param modelId String，Flowable 模型主键
     * @return WorkflowModelView，模型详情视图
     */
    public WorkflowModelView getModel(String modelId)
    {
        String normalizedId = requireText(modelId, "模型主键不能为空");
        return engineOperations.read(() ->
        {
            Model model = requireModel(normalizedId);
            String bpmnXml = decodeOptionalBpmn(repositoryService.getModelEditorSource(normalizedId));
            ObjectNode metadata = readMetadata(model);
            String content = null;
            Integer formType = optionalInt(metadata, "formType");
            Long formId = optionalLong(metadata, "formId");
            if (Integer.valueOf(PROCESS_FORM_TYPE).equals(formType) && formId != null)
            {
                WfForm form = requireActiveForm(formId);
                content = form.getContent();
            }
            return toView(model, bpmnXml, content);
        });
    }

    /**
     * 查询模型编辑器保存的 BPMN XML。
     *
     * @param modelId String，Flowable 模型主键
     * @return String，UTF-8 BPMN XML；兼容旧空模型时返回空串
     */
    public String getBpmnXml(String modelId)
    {
        String normalizedId = requireText(modelId, "模型主键不能为空");
        return engineOperations.read(() ->
        {
            requireModel(normalizedId);
            byte[] source = repositoryService.getModelEditorSource(normalizedId);
            if (source == null || source.length == 0)
            {
                // 旧系统允许先建空模型，返回空串后由设计器生成初始图，避免兼容模型无法首次打开。
                return "";
            }
            return bpmnService.validateDraft(source).bpmnXml();
        });
    }

    /**
     * 原子创建流程模型及可直接打开的初始 BPMN，并记录可信当前用户到 metaInfo。
     *
     * @param request WorkflowModelDto，模型名称、key、分类和可选表单元数据
     * @return String，新建 Flowable 模型主键
     */
    public String createModel(WorkflowModelDto request)
    {
        requireRequest(request);
        String modelName = requireBoundedText(request.getModelName(), "模型名称不能为空",
                "模型名称过长", MODEL_TEXT_MAX_LENGTH);
        String modelKey = requireModelKey(request.getModelKey());
        String category = requireBoundedText(request.getCategory(), "流程分类不能为空",
                "流程分类编码过长", CATEGORY_CODE_MAX_LENGTH);

        return engineOperations.writeAsCurrentUser(identity ->
        {
            validateMetadataRequest(request);
            requireActiveCategory(category);
            if (repositoryService.createModelQuery().modelKey(modelKey).count() > 0)
            {
                throw new ServiceException("模型标识已存在", HttpStatus.CONFLICT);
            }
            Model model = repositoryService.newModel();
            model.setName(modelName);
            model.setKey(modelKey);
            model.setCategory(category);
            model.setMetaInfo(writeMetadata(newMetadata(identity, request)));

            // 初始 BPMN 在任何数据库写入前完成草稿安全校验，避免保存无法被设计器读取的半成品模型。
            byte[] initialBpmn = createInitialBpmn(modelKey, modelName, request.getFormId());
            bpmnService.validateDraft(initialBpmn);
            repositoryService.saveModel(model);
            String savedModelId = model.getId();
            if (!hasText(savedModelId))
            {
                throw new ServiceException("流程模型保存结果不完整", HttpStatus.ERROR);
            }
            // 模型行和编辑器源码使用同一事务；源码写入失败时必须整体回滚，不能留下无法设计的模型。
            repositoryService.addModelEditorSource(savedModelId, initialBpmn);
            return savedModelId;
        });
    }

    /**
     * 修改模型名称、分类和 metaInfo，禁止改变既有模型的版本分组 key。
     *
     * @param request WorkflowModelDto，模型主键和待修改元数据
     * @return 无返回值
     */
    public void updateModel(WorkflowModelDto request)
    {
        requireRequest(request);
        String modelId = requireText(request.getModelId(), "模型主键不能为空");
        engineOperations.writeAsCurrentUser(identity ->
        {
            validateMetadataRequest(request);
            Model model = requireModel(modelId);
            if (hasText(request.getModelKey()) && !model.getKey().equals(request.getModelKey().trim()))
            {
                throw new ServiceException("模型标识不允许修改", HttpStatus.CONFLICT);
            }
            if (hasText(request.getCategory()))
            {
                String category = requireBoundedText(request.getCategory(), "流程分类不能为空",
                        "流程分类编码过长", CATEGORY_CODE_MAX_LENGTH);
                requireActiveCategory(category);
                model.setCategory(category);
            }
            if (hasText(request.getModelName()))
            {
                model.setName(requireBoundedText(request.getModelName(), "模型名称不能为空",
                        "模型名称过长", MODEL_TEXT_MAX_LENGTH));
            }
            ObjectNode metadata = readMetadata(model);
            mergeMetadata(metadata, request);
            model.setMetaInfo(writeMetadata(metadata));
            repositoryService.saveModel(model);
            return null;
        });
    }

    /**
     * 安全校验并保存 BPMN；已部署或历史版本会自动另存为新的最高版本。
     *
     * @param request WorkflowModelDto，模型主键、BPMN XML 和新版本标志
     * @return String，实际保存 BPMN 的模型主键
     */
    public String saveModel(WorkflowModelDto request)
    {
        requireRequest(request);
        String requestId = requireSaveRequestId(request.getSaveRequestId());
        String modelId = requireText(request.getModelId(), "模型主键不能为空");
        String bpmnXml = requireText(request.getBpmnXml(), "BPMN XML 不能为空");
        byte[] bpmnBytes = bpmnXml.getBytes(StandardCharsets.UTF_8);
        String payloadSha256 = createSavePayloadSha256(
                modelId, bpmnBytes, request.getNewVersion());

        return engineOperations.writeAsCurrentUser(identity ->
        {
            // 幂等行是固定锁顺序的第一把锁；同 requestId 的并发或延迟重放只能进入一次真实写入。
            modelSaveMapper.ensureSaveRequest(
                    requestId, identity.userId(), modelId, payloadSha256);
            WorkflowModelSaveRecord saveRecord = modelSaveMapper
                    .selectSaveRequestForUpdate(requestId);
            requireMatchingSaveRecord(
                    saveRecord, requestId, identity.userId(), modelId, payloadSha256);
            if (hasText(saveRecord.savedModelId()))
            {
                return saveRecord.savedModelId();
            }

            // 普通 Flowable 查询只用于取得版本组 key；加锁后的业务判断只相信数据库当前读投影。
            Model sourceSnapshot = requireModel(modelId);
            WorkflowBpmnDocument document = bpmnService.validateForSave(bpmnBytes);
            // 保存继续执行身份与表单门禁；执行兼容性由部署门禁负责，round-trip-only 元素可保留在作者 XML。
            validateDeploymentReferences(document);
            LockedModelVersions lockedModels = lockModelVersions(sourceSnapshot);
            WorkflowModelLockRow source = lockedModels.source();
            WorkflowModelLockRow latest = lockedModels.latest();
            requireActiveCategory(source.category());
            boolean createVersion = shouldCreateSaveVersion(
                    source, latest, request.getNewVersion());

            Model target = sourceSnapshot;
            if (createVersion)
            {
                target = copyAsNextVersion(source, latest.version());
            }
            String processName = requireBoundedText(
                    firstExecutableProcessName(document, source.name()),
                    "流程名称不能为空", "流程名称过长", MODEL_TEXT_MAX_LENGTH);
            target.setName(processName);
            repositoryService.saveModel(target);
            String savedModelId = requireText(target.getId(), "流程模型保存结果不完整");
            repositoryService.addModelEditorSource(savedModelId, bpmnBytes);
            // 模型、编辑器源码和幂等结果必须在同一事务完成；更新数异常时整体回滚。
            if (modelSaveMapper.completeSaveRequest(requestId, savedModelId) != 1)
            {
                throw new ServiceException("流程模型保存幂等状态异常", HttpStatus.ERROR);
            }
            return savedModelId;
        });
    }

    /**
     * 按保存安全门禁校验 BPMN，并返回 Flowable 8 部署兼容性诊断。
     *
     * @param bpmnXml String，待校验的完整 BPMN 2.0 XML
     * @return WorkflowBpmnValidationReport，安全可保存时 valid=true，不可部署元素以 WARNING 返回
     */
    public WorkflowBpmnValidationReport validateBpmn(String bpmnXml)
    {
        String source = requireText(bpmnXml, "BPMN XML 不能为空");
        try
        {
            return engineOperations.read(() ->
            {
                WorkflowBpmnDocument document = bpmnService.validateForSave(
                        source.getBytes(StandardCharsets.UTF_8));
                validateDeploymentReferences(document);
                List<WorkflowBpmnValidationIssue> compatibilityIssues =
                        bpmnService.deploymentCompatibilityIssues(document);
                if (compatibilityIssues.isEmpty())
                {
                    bpmnService.validateDeployable(document);
                }
                return new WorkflowBpmnValidationReport(true, compatibilityIssues);
            });
        }
        catch (ServiceException exception)
        {
            WorkflowBpmnValidationIssue issue = new WorkflowBpmnValidationIssue(
                    validationCode(exception), "ERROR", null,
                    requireText(exception.getMessage(), "BPMN 校验失败"));
            return new WorkflowBpmnValidationReport(false, List.of(issue));
        }
    }

    /**
     * 判断本次保存是否必须写入新的模型版本，所有状态均来自已锁定的数据库当前读。
     *
     * @param source WorkflowModelLockRow，当前设计页来源模型的锁定投影
     * @param latest WorkflowModelLockRow，同 key 当前最高版本的锁定投影
     * @param requestedNewVersion Boolean，兼容旧客户端的显式另存标志
     * @return boolean，true 表示保存结果必须创建最高新版本并由前端切换到返回主键
     */
    private boolean shouldCreateSaveVersion(WorkflowModelLockRow source,
            WorkflowModelLockRow latest, Boolean requestedNewVersion)
    {
        if (Boolean.TRUE.equals(requestedNewVersion) || hasText(source.deploymentId()))
        {
            return true;
        }
        // 历史版本不允许被静默覆盖；保存应形成新的最高版本，避免用户还要手动“设为最新”。
        return !source.modelId().equals(latest.modelId());
    }

    /**
     * 校验并规范化一次保存意图使用的 UUID 幂等键。
     *
     * @param requestId String，客户端为本次保存意图生成的 UUID
     * @return String，小写规范形式且版本、变体均符合接口契约的 UUID
     */
    private String requireSaveRequestId(String requestId)
    {
        String normalized = requireText(requestId, "保存请求主键不能为空");
        try
        {
            UUID parsed = UUID.fromString(normalized);
            String canonical = parsed.toString();
            if (!canonical.equalsIgnoreCase(normalized)
                    || parsed.version() < 1 || parsed.version() > 5
                    || parsed.variant() != 2)
            {
                throw new IllegalArgumentException("unsupported UUID");
            }
            return canonical;
        }
        catch (IllegalArgumentException exception)
        {
            throw new ServiceException("保存请求主键必须为 UUID", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 为来源模型、规范化新版本标志和 BPMN 字节生成稳定保存载荷摘要。
     *
     * @param modelId String，保存请求最初指向的 Flowable 模型主键
     * @param bpmnBytes byte[]，经 UTF-8 编码的完整 BPMN XML
     * @param requestedNewVersion Boolean，兼容旧客户端的显式新版本标志
     * @return String，64 位小写十六进制 SHA-256 摘要
     */
    private String createSavePayloadSha256(String modelId, byte[] bpmnBytes,
            Boolean requestedNewVersion)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance(SAVE_PAYLOAD_DIGEST_ALGORITHM);
            // NUL 分隔固定字段，且把 null 与 false 统一为相同业务语义，保证失败重试得到同一摘要。
            digest.update(modelId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Boolean.TRUE.equals(requestedNewVersion) ? (byte) '1' : (byte) '0');
            digest.update((byte) 0);
            digest.update(bpmnBytes);
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException exception)
        {
            ServiceException failure = new ServiceException(
                    "流程模型保存摘要初始化失败", HttpStatus.ERROR);
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 校验锁定的幂等记录仍属于同一用户、来源模型和保存载荷。
     *
     * @param saveRecord WorkflowModelSaveRecord，数据库当前读锁定的幂等记录
     * @param requestId String，本次请求的规范 UUID
     * @param userId String，事务内重新解析的可信用户主键
     * @param sourceModelId String，请求最初指向的 Flowable 模型主键
     * @param payloadSha256 String，本次规范保存载荷摘要
     * @return 无返回值；记录缺失或 requestId 被不同请求复用时抛出稳定业务异常
     */
    private void requireMatchingSaveRecord(WorkflowModelSaveRecord saveRecord,
            String requestId, String userId, String sourceModelId, String payloadSha256)
    {
        if (saveRecord == null || !Objects.equals(saveRecord.requestId(), requestId))
        {
            throw new ServiceException("流程模型保存幂等记录异常", HttpStatus.ERROR);
        }
        if (!Objects.equals(saveRecord.userId(), userId)
                || !Objects.equals(saveRecord.sourceModelId(), sourceModelId)
                || !Objects.equals(saveRecord.payloadSha256(), payloadSha256))
        {
            throw new ServiceException("保存请求主键已被其他请求使用", HttpStatus.CONFLICT);
        }
    }

    /**
     * 按稳定版本组锚点、最高版本、来源模型的固定顺序执行数据库当前读锁，并复核版本组状态。
     *
     * @param sourceSnapshot Model，普通 Flowable 查询取得且仅用于定位版本组的来源快照
     * @return LockedModelVersions，锁定后的来源模型和当前最高版本投影
     */
    private LockedModelVersions lockModelVersions(Model sourceSnapshot)
    {
        String sourceModelId = requireText(sourceSnapshot.getId(), "模型主键不能为空");
        String modelKey = requireModelKey(sourceSnapshot.getKey());

        // groupAnchor 是不会随新版本向右移动的最早版本行，先锁它可串行化同 key 的版本分配。
        WorkflowModelLockRow groupAnchor = modelSaveMapper
                .selectOldestDefaultTenantModelForUpdate(modelKey);
        if (groupAnchor == null)
        {
            throw new ServiceException("流程模型版本状态异常", HttpStatus.CONFLICT);
        }
        if (!modelKey.equals(groupAnchor.modelKey())
                || !Objects.equals(groupAnchor.tenantId(), ""))
        {
            throw new ServiceException("流程模型版本组已发生变化", HttpStatus.CONFLICT);
        }
        if (groupAnchor.version() == null || groupAnchor.version() <= 0)
        {
            throw new ServiceException("流程模型版本状态异常", HttpStatus.ERROR);
        }
        WorkflowModelLockRow latest = modelSaveMapper
                .selectLatestDefaultTenantModelForUpdate(modelKey);
        WorkflowModelLockRow source = modelSaveMapper
                .selectDefaultTenantModelForUpdate(sourceModelId);
        if (source == null)
        {
            throw new ServiceException("流程模型不存在", HttpStatus.NOT_FOUND);
        }
        if (latest == null)
        {
            throw new ServiceException("流程模型版本状态异常", HttpStatus.CONFLICT);
        }
        if (!sourceModelId.equals(source.modelId())
                || !modelKey.equals(source.modelKey())
                || !modelKey.equals(latest.modelKey())
                || !Objects.equals(source.tenantId(), "")
                || !Objects.equals(latest.tenantId(), ""))
        {
            throw new ServiceException("流程模型版本组已发生变化", HttpStatus.CONFLICT);
        }
        if (source.version() == null || source.version() <= 0
                || latest.version() == null || latest.version() < source.version())
        {
            throw new ServiceException("流程模型版本状态异常", HttpStatus.ERROR);
        }
        if (groupAnchor.version() > source.version()
                || groupAnchor.version() > latest.version())
        {
            throw new ServiceException("流程模型版本状态异常", HttpStatus.ERROR);
        }
        return new LockedModelVersions(source, latest);
    }

    /**
     * 将一个旧模型版本复制为当前最新版本，并再次执行 BPMN 安全校验。
     *
     * @param modelId String，待提升的旧模型主键
     * @return String，新创建的最新模型主键
     */
    public String promoteToLatest(String modelId)
    {
        String normalizedId = requireText(modelId, "模型主键不能为空");
        return engineOperations.writeAsCurrentUser(identity ->
        {
            Model source = requireModel(normalizedId);
            Model latest = latestModel(source.getKey());
            if (latest != null && latest.getId().equals(source.getId()))
            {
                throw new ServiceException("当前版本已是最新版", HttpStatus.CONFLICT);
            }
            byte[] sourceBytes = requireModelSource(source.getId());
            bpmnService.validate(sourceBytes);
            Model promoted = copyAsNextVersion(source);
            repositoryService.saveModel(promoted);
            repositoryService.addModelEditorSource(promoted.getId(), sourceBytes);
            return promoted.getId();
        });
    }

    /**
     * 安全删除未部署且没有任何流程定义引用的模型，批量操作先完整预检再删除。
     *
     * @param modelIds Collection&lt;String&gt;，待删除 Flowable 模型主键集合
     * @return 无返回值
     */
    public void deleteModels(Collection<String> modelIds)
    {
        List<String> normalizedIds = requireIds(modelIds, "模型主键不能为空");
        engineOperations.writeAsCurrentUser(identity ->
        {
            List<Model> models = new ArrayList<>(normalizedIds.size());
            for (String modelId : normalizedIds)
            {
                Model model = requireModel(modelId);
                if (isDeployed(model))
                {
                    throw new ServiceException("已部署模型不能删除", HttpStatus.CONFLICT);
                }
                assertNoDeployedDefinition(model);
                models.add(model);
            }
            for (Model model : models)
            {
                repositoryService.deleteModel(model.getId());
            }
            return null;
        });
    }

    /**
     * 校验模型、分类和全部节点表单后部署 BPMN，保存快照并停用同流程标识的历史定义。
     *
     * @param modelId String，待部署 Flowable 模型主键
     * @return String，新 Flowable 部署主键
     */
    public String deployModel(String modelId)
    {
        String normalizedId = requireText(modelId, "模型主键不能为空");
        return engineOperations.writeAsCurrentUser(identity ->
        {
            // 部署与模型保存共用稳定版本组锁，禁止同 key 并发发布出多个活动最新版。
            Model model = lockDeployableModel(normalizedId);
            if (isDeployed(model))
            {
                throw new ServiceException("当前模型版本已经部署", HttpStatus.CONFLICT);
            }
            requireActiveCategory(model.getCategory());
            byte[] bpmnBytes = requireModelSource(normalizedId);
            // 作者资源允许受控 SendTask 等待部署编译；先走作者门禁，再由扩展编译器生成可执行 BPMN，
            // 最终资源仍必须经过 validateCompiledDeployment 的 Flowable 官方规则校验。
            WorkflowBpmnDocument document = bpmnService.validateForSave(bpmnBytes);
            List<FormSnapshotSource> snapshotSources = validateDeploymentReferences(document);
            WorkflowPreparedExtensionDeployment extensionDeployment =
                    extensionDeploymentService.prepare(document, identity.userId());
            List<WorkflowControlledLoopFormSchema> formSchemas =
                    buildControlledLoopFormSchemas(snapshotSources);
            WorkflowPreparedConditionDeployment conditionDeployment =
                    conditionDeploymentService == null
                            ? new WorkflowPreparedConditionDeployment(
                                    extensionDeployment.compiledBpmn(), List.of())
                            : conditionDeploymentService.prepare(document,
                                    extensionDeployment.compiledBpmn(), formSchemas,
                                    identity.userId());
            WorkflowPreparedControlledLoopDeployment controlledLoopDeployment =
                    controlledLoopDeploymentService == null
                            ? new WorkflowPreparedControlledLoopDeployment(
                                    conditionDeployment.compiledBpmn(), List.of())
                            : controlledLoopDeploymentService.prepare(document,
                                    conditionDeployment.compiledBpmn(),
                                    formSchemas, identity.userId());
            WorkflowPreparedParticipantRuleDeployment participantDeployment =
                    participantRuleDeploymentService == null
                            ? new WorkflowPreparedParticipantRuleDeployment(
                                    controlledLoopDeployment.compiledBpmn(), List.of())
                            : participantRuleDeploymentService.prepare(document,
                                    controlledLoopDeployment.compiledBpmn(),
                                    buildParticipantFormVariables(snapshotSources),
                                    identity.userId());
            WorkflowPreparedDmnDeployment dmnDeployment =
                    dmnDecisionService.prepare(participantDeployment.compiledBpmn());
            byte[] executableBpmn = callActivityReferenceService == null
                    ? dmnDeployment.compiledBpmn()
                    : callActivityReferenceService.freezeReferences(dmnDeployment.compiledBpmn());
            WorkflowPreparedSlaDeployment slaDeployment = taskSlaDeploymentService == null
                    ? new WorkflowPreparedSlaDeployment(executableBpmn, List.of())
                    : taskSlaDeploymentService.prepare(executableBpmn, identity.userId());
            executableBpmn = slaDeployment.compiledBpmn();
            // 最终执行资源必须再次通过编译阶段门禁，确保循环与 SLA 作者属性均已剥离，且生成结构满足 Flowable 校验。
            bpmnService.validateCompiledDeployment(executableBpmn);
            Map<String, List<ProcessDefinition>> activeHistoryByKey =
                    loadActiveDefinitionsByProcessKey(document);

            DeploymentBuilder builder = repositoryService.createDeployment()
                    .name(model.getName())
                    .key(model.getKey())
                    .category(model.getCategory())
                    .addBytes(model.getKey() + ".bpmn20.xml", executableBpmn);
            Deployment deployment = builder.deploy();
            if (deployment == null || !hasText(deployment.getId()))
            {
                throw new ServiceException("流程部署结果不完整", HttpStatus.CONFLICT);
            }

            List<ProcessDefinition> definitions = repositoryService.createProcessDefinitionQuery()
                    .deploymentId(deployment.getId())
                    .list();
            long executableCount = document.bpmnModel().getProcesses().stream()
                    .filter(Process::isExecutable)
                    .count();
            if (definitions.size() != executableCount)
            {
                throw new ServiceException("流程部署定义数量不一致", HttpStatus.CONFLICT);
            }
            for (ProcessDefinition definition : definitions)
            {
                repositoryService.setProcessDefinitionCategory(definition.getId(), model.getCategory());
            }

            List<WfDeployForm> snapshots = buildSnapshots(deployment.getId(), snapshotSources, identity);
            int inserted = snapshots.isEmpty() ? 0 : deployFormMapper.insertBatch(snapshots);
            if (inserted != snapshots.size())
            {
                throw new ServiceException("部署表单快照保存不完整", HttpStatus.CONFLICT);
            }
            // 表单字段与服务任务共享部署扩展台账，版本引用保护和删除门禁必须覆盖两类来源。
            List<WfDeployExtensionSnapshot> formFieldSnapshots = snapshotSources.stream()
                    .flatMap(source -> source.extensionSnapshots().stream())
                    .peek(snapshot -> snapshot.setCreateBy(identity.userId()))
                    .toList();
            extensionDeploymentService.persist(deployment.getId(), extensionDeployment,
                    formFieldSnapshots);
            if (conditionDeploymentService != null)
            {
                conditionDeploymentService.persist(deployment.getId(), conditionDeployment);
            }
            if (controlledLoopDeploymentService != null)
            {
                controlledLoopDeploymentService.persist(deployment.getId(), controlledLoopDeployment);
            }
            if (participantRuleDeploymentService != null)
            {
                participantRuleDeploymentService.persist(deployment.getId(), participantDeployment);
            }
            dmnDecisionService.persist(deployment.getId(), dmnDeployment, identity.userId());
            if (taskSlaDeploymentService != null)
            {
                // Flowable 部署与 SLA 快照共享外层事务，任一快照失败都会回滚部署和所有关联数据。
                taskSlaDeploymentService.persist(deployment.getId(), slaDeployment);
            }

            // 记录最近一次部署关系，后续模型编辑和安全删除据此执行状态门禁。
            model.setDeploymentId(deployment.getId());
            repositoryService.saveModel(model);

            // 新定义默认保持活动；旧定义只禁止承接新实例，不冻结仍在办理的历史版本实例。
            Set<String> newDefinitionIds = definitions.stream()
                    .map(ProcessDefinition::getId).collect(java.util.stream.Collectors.toSet());
            Set<String> frozenCallTargets = callActivityReferenceService == null
                    ? Set.of() : callActivityReferenceService.frozenTargetDefinitionIds();
            activeHistoryByKey.values().stream().flatMap(Collection::stream)
                    .filter(definition -> !newDefinitionIds.contains(definition.getId()))
                    // 被已发布父流程精确引用的定义必须保持可调用；业务发起链仍只允许最新版。
                    .filter(definition -> !frozenCallTargets.contains(definition.getId()))
                    .forEach(definition -> repositoryService.suspendProcessDefinitionById(
                            definition.getId(), false, null));
            return deployment.getId();
        });
    }

    /**
     * 锁定待部署模型所属版本组并重新读取模型，串行化同流程标识的保存与部署。
     *
     * @param modelId String，待部署 Flowable 模型主键
     * @return Model，锁内重新读取且尚未部署的模型
     */
    private Model lockDeployableModel(String modelId)
    {
        Model snapshot = requireModel(modelId);
        String modelKey = requireText(snapshot.getKey(), "流程模型标识不能为空");
        WorkflowModelLockRow groupAnchor = modelSaveMapper
                .selectOldestDefaultTenantModelForUpdate(modelKey);
        WorkflowModelLockRow lockedModel = modelSaveMapper
                .selectDefaultTenantModelForUpdate(modelId);
        if (groupAnchor == null || lockedModel == null
                || !Objects.equals(modelKey, lockedModel.modelKey()))
        {
            throw new ServiceException("流程模型版本状态异常", HttpStatus.CONFLICT);
        }
        if (hasText(lockedModel.deploymentId()))
        {
            throw new ServiceException("当前模型版本已经部署", HttpStatus.CONFLICT);
        }
        Model current = requireModel(modelId);
        if (!Objects.equals(lockedModel.modelKey(), current.getKey())
                || !Objects.equals(lockedModel.version(), current.getVersion()))
        {
            throw new ServiceException("流程模型版本状态异常", HttpStatus.CONFLICT);
        }
        return current;
    }

    /**
     * 查询本次 BPMN 中每个可执行流程标识当前仍活动的历史定义。
     *
     * @param document WorkflowBpmnDocument，已通过安全与结构校验的部署文档
     * @return Map&lt;String, List&lt;ProcessDefinition&gt;&gt;，流程标识到部署前活动定义的稳定映射
     */
    private Map<String, List<ProcessDefinition>> loadActiveDefinitionsByProcessKey(
            WorkflowBpmnDocument document)
    {
        Map<String, List<ProcessDefinition>> definitionsByKey = new LinkedHashMap<>();
        document.bpmnModel().getProcesses().stream().filter(Process::isExecutable).forEach(process ->
        {
            String processKey = requireText(process.getId(), "可执行流程标识不能为空");
            List<ProcessDefinition> activeDefinitions = repositoryService
                    .createProcessDefinitionQuery()
                    .processDefinitionKey(processKey)
                    .active()
                    .list();
            definitionsByKey.put(processKey, activeDefinitions == null
                    ? List.of() : List.copyOf(activeDefinitions));
        });
        return Map.copyOf(definitionsByKey);
    }

    /**
     * 构造模型原生查询并应用可选条件。
     *
     * @param filter WorkflowModelDto，模型查询条件，允许为空
     * @param latestOnly boolean，是否只查询每个 key 的最新版本
     * @return ModelQuery，尚未执行的 Flowable 原生查询
     */
    private ModelQuery buildModelQuery(WorkflowModelDto filter, boolean latestOnly)
    {
        ModelQuery query = repositoryService.createModelQuery();
        if (latestOnly)
        {
            query.latestVersion();
        }
        if (filter == null)
        {
            return query;
        }
        if (hasText(filter.getModelKey()))
        {
            query.modelKey(filter.getModelKey().trim());
        }
        if (hasText(filter.getModelName()))
        {
            query.modelNameLike("%" + filter.getModelName().trim() + "%");
        }
        if (hasText(filter.getCategory()))
        {
            query.modelCategory(filter.getCategory().trim());
        }
        return query;
    }

    /**
     * 将 Flowable Model 和受控 metaInfo 转换为模块视图。
     *
     * @param model Model，Flowable 模型实体
     * @param bpmnXml String，详情 BPMN XML，列表场景为空
     * @param content String，模型级表单内容，列表场景为空
     * @return WorkflowModelView，不暴露 Flowable 内部实体的不可变视图
     */
    private WorkflowModelView toView(Model model, String bpmnXml, String content)
    {
        ObjectNode metadata = readMetadata(model);
        return new WorkflowModelView(model.getId(), model.getName(), model.getKey(),
                model.getCategory(), model.getVersion(), optionalInt(metadata, "formType"),
                optionalLong(metadata, "formId"), optionalText(metadata, "description"),
                model.getCreateTime(), model.getLastUpdateTime(), bpmnXml, content, isDeployed(model));
    }

    /**
     * 查询必须存在的 Flowable 模型。
     *
     * @param modelId String，Flowable 模型主键
     * @return Model，存在的 Flowable 模型
     */
    private Model requireModel(String modelId)
    {
        Model model = repositoryService.getModel(modelId);
        if (model == null)
        {
            throw new ServiceException("流程模型不存在或已被删除", HttpStatus.NOT_FOUND);
        }
        return model;
    }

    /**
     * 查询并校验有效工作流分类。
     *
     * @param categoryCode String，工作流分类编码
     * @return WfCategory，未逻辑删除的分类
     */
    private WfCategory requireActiveCategory(String categoryCode)
    {
        String normalizedCode = requireText(categoryCode, "流程分类不能为空");
        WfCategory category = categoryMapper.selectByCode(normalizedCode);
        if (category == null || !ACTIVE_DEL_FLAG.equals(category.getDelFlag()))
        {
            throw new ServiceException("流程分类不存在或已停用", HttpStatus.NOT_FOUND);
        }
        return category;
    }

    /**
     * 查询并校验有效表单模板。
     *
     * @param formId Long，表单模板主键
     * @return WfForm，未逻辑删除且内容完整的表单模板
     */
    private WfForm requireActiveForm(Long formId)
    {
        if (formId == null || formId <= 0)
        {
            throw new ServiceException("表单主键不合法", HttpStatus.BAD_REQUEST);
        }
        WfForm form = formMapper.selectById(formId);
        if (form == null || !ACTIVE_DEL_FLAG.equals(form.getDelFlag()))
        {
            throw new ServiceException("流程表单不存在或已停用", HttpStatus.NOT_FOUND);
        }
        if (!hasText(form.getFormName()) || !hasText(form.getContent()))
        {
            throw new ServiceException("流程表单模板不完整", HttpStatus.CONFLICT);
        }
        // 部署时重新验证数据库中的真实模板，阻止历史脏数据或绕库写入进入不可变快照。
        formTemplateValidator.validate(form.getContent());
        return form;
    }

    /**
     * 批量加载 BPMN 节点引用的表单模板，并在部署前固定读取结果。
     *
     * @param references List&lt;WorkflowBpmnFormReference&gt;，BPMN 中按节点顺序解析出的表单引用
     * @return List&lt;FormSnapshotSource&gt;，用于部署后生成不可变快照的来源数据
     */
    private List<FormSnapshotSource> loadSnapshotSources(List<WorkflowBpmnFormReference> references)
    {
        Map<Long, WfForm> formsById = new LinkedHashMap<>();
        List<FormSnapshotSource> sources = new ArrayList<>(references.size());
        for (WorkflowBpmnFormReference reference : references)
        {
            if (reference.sourceType() == WorkflowFormSourceType.TEMPLATE)
            {
                WfForm form = formsById.computeIfAbsent(reference.formId(), this::requireActiveForm);
                sources.add(new FormSnapshotSource(reference, form.getFormName(),
                        form.getContent(), List.of()));
            }
            else
            {
                // 内嵌表单直接来自本次安全解析的作者 XML，不创建虚假的 wf_form 主数据。
                String formName = hasText(reference.nodeName())
                        ? reference.nodeName() + "内嵌表单"
                        : reference.nodeKey() + "内嵌表单";
                WorkflowFrozenFormContent frozen = formFieldExtensionService == null
                        ? new WorkflowFrozenFormContent(reference.embeddedContent(), List.of())
                        : formFieldExtensionService.freezeEmbeddedContentWithSnapshots(
                                reference.embeddedContent(), reference.processKey(),
                                reference.nodeKey());
                // 冻结后再执行正式表单验证，防止版本元数据更新破坏渲染协议。
                formTemplateValidator.validate(frozen.content());
                sources.add(new FormSnapshotSource(reference, formName, frozen.content(),
                        frozen.extensionSnapshots()));
            }
        }
        return List.copyOf(sources);
    }

    /**
     * 重新核验 BPMN 静态身份和全部正式表单，并返回可用于部署快照的固定来源。
     *
     * @param document WorkflowBpmnDocument，已通过 XML、安全和 Flowable 结构校验的文档
     * @return List&lt;FormSnapshotSource&gt;，本次一致性视图中的表单快照来源
     */
    private List<FormSnapshotSource> validateDeploymentReferences(WorkflowBpmnDocument document)
    {
        // 身份主数据可能在设计期间停用，每次保存、校验和部署都必须从正式表重新核验。
        bpmnIdentityValidator.validate(document);
        dmnDecisionService.validateReferences(document);
        List<FormSnapshotSource> sources = loadSnapshotSources(document.formReferences());
        if (conditionDeploymentService != null)
        {
            // 保存、校验和部署共用冻结表单字段门禁，不能只在最终 deploy 才发现非法规则。
            conditionDeploymentService.validate(document,
                    buildControlledLoopFormSchemas(sources));
        }
        return sources;
    }

    /**
     * 把服务端业务校验异常归类为客户端稳定诊断编码，不暴露内部异常类型。
     *
     * @param exception ServiceException，BPMN、身份或表单共同门禁产生的预期业务异常
     * @return String，稳定的大类诊断编码
     */
    private String validationCode(ServiceException exception)
    {
        if (hasText(exception.getSubCode()))
        {
            return exception.getSubCode();
        }
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (message.contains("表单"))
        {
            return "BPMN_FORM_INVALID";
        }
        if (message.contains("用户") || message.contains("候选")
                || message.contains("办理") || message.contains("角色")
                || message.contains("部门"))
        {
            return "BPMN_IDENTITY_INVALID";
        }
        if (message.contains("XML") || message.contains("DTD")
                || message.contains("实体"))
        {
            return "BPMN_XML_INVALID";
        }
        return "BPMN_CONTRACT_INVALID";
    }

    /**
     * 使用部署主键和可信操作人生成完整表单快照集合。
     *
     * @param deploymentId String，刚创建的 Flowable 部署主键
     * @param sources List&lt;FormSnapshotSource&gt;，部署前读取的节点和表单模板
     * @param identity WorkflowCurrentIdentity，事务内重新核验的当前用户身份
     * @return List&lt;WfDeployForm&gt;，待批量持久化的不可变部署快照
     */
    private List<WfDeployForm> buildSnapshots(String deploymentId, List<FormSnapshotSource> sources,
            WorkflowCurrentIdentity identity)
    {
        Date now = new Date();
        List<WfDeployForm> snapshots = new ArrayList<>(sources.size());
        for (FormSnapshotSource source : sources)
        {
            WfDeployForm snapshot = new WfDeployForm();
            snapshot.setDeployId(deploymentId);
            snapshot.setSourceType(source.reference().sourceType().name());
            snapshot.setFormId(source.reference().formId());
            snapshot.setFormKey(source.reference().formKey());
            snapshot.setNodeKey(source.reference().nodeKey());
            snapshot.setNodeName(source.reference().nodeName());
            snapshot.setFormName(source.formName());
            snapshot.setContent(source.content());
            snapshot.setDelFlag(ACTIVE_DEL_FLAG);
            snapshot.setCreateBy(identity.userId());
            snapshot.setCreateTime(now);
            snapshots.add(snapshot);
        }
        return snapshots;
    }

    /**
     * 从本次部署已经冻结的节点表单来源提取受控循环判断字段白名单。
     *
     * @param sources List&lt;FormSnapshotSource&gt;，部署事务内固定读取的节点表单来源
     * @return List&lt;WorkflowControlledLoopFormSchema&gt;，按节点顺序返回的不可变字段白名单
     */
    private List<WorkflowControlledLoopFormSchema> buildControlledLoopFormSchemas(
            List<FormSnapshotSource> sources)
    {
        List<WorkflowControlledLoopFormSchema> schemas = new ArrayList<>(sources.size());
        for (FormSnapshotSource source : sources)
        {
            WorkflowBpmnFormReference reference = source.reference();
            schemas.add(new WorkflowControlledLoopFormSchema(reference.processKey(),
                    reference.nodeKey(), startVariableValidator
                            .describeControlledLoopFields(source.content())));
        }
        return List.copyOf(schemas);
    }

    /**
     * 汇总同一可执行流程全部正式部署表单变量，供 FORM_USER 规则只能引用真实字段的门禁使用。
     * @param sources List&lt;FormSnapshotSource&gt;，部署事务内固定读取的表单来源
     * @return Map&lt;String,Set&lt;String&gt;&gt;，流程 key 到不可变表单变量集合
     */
    private Map<String, Set<String>> buildParticipantFormVariables(
            List<FormSnapshotSource> sources)
    {
        Map<String, LinkedHashSet<String>> mutable = new LinkedHashMap<>();
        for (FormSnapshotSource source : sources)
        {
            mutable.computeIfAbsent(source.reference().processKey(), ignored -> new LinkedHashSet<>())
                    .addAll(formTemplateValidator.extractVariableNames(source.content()));
        }
        Map<String, Set<String>> result = new LinkedHashMap<>();
        mutable.forEach((processKey, variables) -> result.put(processKey, Set.copyOf(variables)));
        return Map.copyOf(result);
    }

    /**
     * 复制模型元数据并创建大于当前最新版本号的新模型。
     *
     * @param source Model，作为内容来源的任意模型版本
     * @return Model，已完整设置版本字段但尚未保存的新模型
     */
    private Model copyAsNextVersion(Model source)
    {
        Model latest = latestModel(source.getKey());
        int latestVersion = latest == null || latest.getVersion() == null ? 0 : latest.getVersion();
        Model target = repositoryService.newModel();
        target.setName(source.getName());
        target.setKey(source.getKey());
        target.setCategory(source.getCategory());
        target.setMetaInfo(source.getMetaInfo());
        target.setVersion(latestVersion + 1);
        return target;
    }

    /**
     * 使用已锁定来源投影和最高版本号创建下一模型版本，禁止回退到普通快照查询。
     *
     * @param source WorkflowModelLockRow，当前设计页来源模型的数据库锁定投影
     * @param latestVersion Integer，同 key 当前锁定的最高业务版本号
     * @return Model，复制完整元数据并设置为最高版本加一的新模型
     */
    private Model copyAsNextVersion(WorkflowModelLockRow source, Integer latestVersion)
    {
        if (latestVersion == null || latestVersion <= 0 || latestVersion == Integer.MAX_VALUE)
        {
            throw new ServiceException("流程模型版本号不可继续递增", HttpStatus.CONFLICT);
        }
        Model target = repositoryService.newModel();
        target.setName(source.name());
        target.setKey(source.modelKey());
        target.setCategory(source.category());
        target.setMetaInfo(source.metaInfo());
        target.setTenantId(source.tenantId());
        target.setVersion(latestVersion + 1);
        return target;
    }

    /**
     * 查询指定模型 key 的最新版本。
     *
     * @param modelKey String，模型版本分组标识
     * @return Model，最新模型；不存在时返回 null
     */
    private Model latestModel(String modelKey)
    {
        return repositoryService.createModelQuery()
                .modelKey(modelKey)
                .latestVersion()
                .singleResult();
    }

    /**
     * 校验模型没有部署关系，也没有由其 BPMN process key 产生的定义或部署。
     *
     * @param model Model，待删除且尚未报告部署关系的模型
     * @return 无返回值
     */
    private void assertNoDeployedDefinition(Model model)
    {
        byte[] source = repositoryService.getModelEditorSource(model.getId());
        if (source == null || source.length == 0)
        {
            return;
        }
        // 删除草稿只需安全解析并提取 process key；不能要求待删除内容先满足部署表单门禁。
        WorkflowBpmnDocument document = bpmnService.validateDraft(source);
        Set<String> processKeys = new LinkedHashSet<>();
        for (Process process : document.bpmnModel().getProcesses())
        {
            if (hasText(process.getId()))
            {
                processKeys.add(process.getId());
            }
        }
        for (String processKey : processKeys)
        {
            long definitionCount = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(processKey)
                    .count();
            long deploymentCount = repositoryService.createDeploymentQuery()
                    .processDefinitionKey(processKey)
                    .count();
            if (definitionCount > 0 || deploymentCount > 0)
            {
                throw new ServiceException("模型对应流程已部署，不能删除", HttpStatus.CONFLICT);
            }
        }
    }

    /**
     * 判断模型是否由字段或 Flowable 原生查询报告为已部署。
     *
     * @param model Model，待判断的 Flowable 模型
     * @return boolean，true 表示模型已关联部署
     */
    private boolean isDeployed(Model model)
    {
        if (hasText(model.getDeploymentId()))
        {
            return true;
        }
        return repositoryService.createModelQuery()
                .modelId(model.getId())
                .deployed()
                .count() > 0;
    }

    /**
     * 获取模型编辑器源码并拒绝空设计。
     *
     * @param modelId String，Flowable 模型主键
     * @return byte[]，模型 BPMN 原始字节
     */
    private byte[] requireModelSource(String modelId)
    {
        byte[] source = repositoryService.getModelEditorSource(modelId);
        if (source == null || source.length == 0)
        {
            throw new ServiceException("请先完成流程设计", HttpStatus.BAD_REQUEST);
        }
        return source;
    }

    /**
     * 解码详情场景中的可选 BPMN，空设计返回 null，存在设计时复用草稿安全校验。
     *
     * @param source byte[]，模型编辑器源码，允许为空
     * @return String，安全校验后的 BPMN XML 或 null
     */
    private String decodeOptionalBpmn(byte[] source)
    {
        if (source == null || source.length == 0)
        {
            return null;
        }
        return bpmnService.validateDraft(source).bpmnXml();
    }

    /**
     * 使用 Flowable 公共 BPMN 模型 API 生成包含开始、审批和结束节点的初始可编辑流程。
     *
     * @param modelKey String，已经通过模型标识规则校验的流程主键
     * @param modelName String，已经通过长度校验的流程显示名称
     * @param formId Long，可选的流程级发起表单主键；为空时保留为待设计草稿
     * @return byte[]，包含稳定画布坐标的 UTF-8 BPMN 2.0 XML
     */
    private byte[] createInitialBpmn(String modelKey, String modelName, Long formId)
    {
        BpmnModel bpmnModel = new BpmnModel();
        bpmnModel.setTargetNamespace("http://ruoyi.example/workflow");

        Process process = new Process();
        process.setId(modelKey);
        process.setName(modelName);
        process.setExecutable(true);
        bpmnModel.addProcess(process);

        StartEvent startEvent = new StartEvent();
        startEvent.setId("start");
        startEvent.setName("提交申请");
        if (formId != null)
        {
            startEvent.setFormKey("key_" + formId);
        }

        UserTask reviewTask = new UserTask();
        reviewTask.setId("review");
        reviewTask.setName("审批");
        // 新建模型立即冻结完整作者规则，未经页面编辑直接保存、重开或部署也不会产生无人任务。
        WorkflowParticipantRuleBpmnContract.addInitialAuthorRules(process, reviewTask);
        List<FlowableListener> taskListeners = new ArrayList<>();
        for (String event : USER_TASK_LISTENER_EVENTS)
        {
            // 初始草稿也必须满足正式部署契约，避免用户新建模型后未经编辑便生成不可发布 BPMN。
            FlowableListener listener = new FlowableListener();
            listener.setEvent(event);
            listener.setImplementationType(
                    ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
            listener.setImplementation(USER_TASK_LISTENER_EXPRESSION);
            taskListeners.add(listener);
        }
        reviewTask.setTaskListeners(taskListeners);

        EndEvent endEvent = new EndEvent();
        endEvent.setId("end");
        endEvent.setName("结束");

        SequenceFlow startToReview = new SequenceFlow(startEvent.getId(), reviewTask.getId());
        startToReview.setId("flow_start_review");
        SequenceFlow reviewToEnd = new SequenceFlow(reviewTask.getId(), endEvent.getId());
        reviewToEnd.setId("flow_review_end");

        process.addFlowElement(startEvent);
        process.addFlowElement(startToReview);
        process.addFlowElement(reviewTask);
        process.addFlowElement(reviewToEnd);
        process.addFlowElement(endEvent);

        // 固定初始图坐标，确保 bpmn-js 首次导入后节点完整可见且不会互相遮挡。
        bpmnModel.addGraphicInfo(startEvent.getId(), new GraphicInfo(160, 172, 36, 36));
        bpmnModel.addGraphicInfo(reviewTask.getId(), new GraphicInfo(270, 150, 80, 100));
        bpmnModel.addGraphicInfo(endEvent.getId(), new GraphicInfo(450, 172, 36, 36));
        bpmnModel.addFlowGraphicInfoList(startToReview.getId(),
                List.of(new GraphicInfo(196, 190), new GraphicInfo(270, 190)));
        bpmnModel.addFlowGraphicInfoList(reviewToEnd.getId(),
                List.of(new GraphicInfo(370, 190), new GraphicInfo(450, 190)));

        return new BpmnXMLConverter().convertToXML(bpmnModel, StandardCharsets.UTF_8.name());
    }

    /**
     * 从安全校验结果选择首个可执行流程名称，空名称时回退到当前模型名。
     *
     * @param document WorkflowBpmnDocument，安全校验后的 BPMN 文档
     * @param fallbackName String，BPMN 未配置名称时使用的模型名
     * @return String，模型保存后的显示名称
     */
    private String firstExecutableProcessName(WorkflowBpmnDocument document, String fallbackName)
    {
        return document.bpmnModel().getProcesses().stream()
                .filter(Process::isExecutable)
                .map(Process::getName)
                .filter(WorkflowModelService::hasText)
                .map(String::trim)
                .findFirst()
                .orElse(fallbackName);
    }

    /**
     * 创建新模型的结构化 metaInfo。
     *
     * @param identity WorkflowCurrentIdentity，事务内重新核验的当前用户身份
     * @param request WorkflowModelDto，模型元数据请求
     * @return ObjectNode，包含创建人及可选业务字段的 JSON 对象
     */
    private ObjectNode newMetadata(WorkflowCurrentIdentity identity, WorkflowModelDto request)
    {
        ObjectNode metadata = metadataMapper.createObjectNode();
        metadata.put("createUser", identity.userId());
        mergeMetadata(metadata, request);
        return metadata;
    }

    /**
     * 将请求中明确提供的业务字段合并到模型 metaInfo，并保留未知兼容字段。
     *
     * @param metadata ObjectNode，已有或新建的模型元数据对象
     * @param request WorkflowModelDto，包含可选描述和表单元数据的请求
     * @return 无返回值
     */
    private void mergeMetadata(ObjectNode metadata, WorkflowModelDto request)
    {
        if (request.getDescription() != null)
        {
            String description = request.getDescription().trim();
            if (description.length() > DESCRIPTION_MAX_LENGTH)
            {
                throw new ServiceException("模型描述过长", HttpStatus.BAD_REQUEST);
            }
            metadata.put("description", description);
        }
        if (request.getFormType() != null)
        {
            metadata.put("formType", request.getFormType());
            // 切换到外置或节点独立表单时，显式移除旧流程级 formId，避免元数据状态漂移。
            if (request.getFormType() != PROCESS_FORM_TYPE && request.getFormId() == null)
            {
                metadata.remove("formId");
            }
        }
        if (request.getFormId() != null)
        {
            metadata.put("formId", request.getFormId());
        }
    }

    /**
     * 解析 Flowable 模型 metaInfo，拒绝损坏或字段类型漂移的数据。
     *
     * @param model Model，包含 metaInfo 的 Flowable 模型
     * @return ObjectNode，可保留未知字段的结构化元数据
     */
    private ObjectNode readMetadata(Model model)
    {
        if (!hasText(model.getMetaInfo()))
        {
            return metadataMapper.createObjectNode();
        }
        try
        {
            JsonNode parsed = metadataMapper.readTree(model.getMetaInfo());
            if (!(parsed instanceof ObjectNode metadata))
            {
                throw metadataFailure(null);
            }
            // 读取时立即校验受控字段，避免损坏元数据在不同接口表现不一致。
            optionalText(metadata, "createUser");
            optionalText(metadata, "description");
            optionalInt(metadata, "formType");
            optionalLong(metadata, "formId");
            return metadata;
        }
        catch (JacksonException exception)
        {
            throw metadataFailure(exception);
        }
    }

    /**
     * 将结构化 metaInfo 序列化为 Flowable 存储字符串。
     *
     * @param metadata ObjectNode，待保存的结构化模型元数据
     * @return String，JSON 格式 metaInfo
     */
    private String writeMetadata(ObjectNode metadata)
    {
        try
        {
            String serialized = metadataMapper.writeValueAsString(metadata);
            if (serialized.length() > META_INFO_MAX_LENGTH)
            {
                throw new ServiceException("流程模型元数据过长", HttpStatus.BAD_REQUEST);
            }
            return serialized;
        }
        catch (JacksonException exception)
        {
            throw metadataFailure(exception);
        }
    }

    /**
     * 读取可选字符串元数据并校验字段类型。
     *
     * @param metadata ObjectNode，模型元数据
     * @param fieldName String，待读取字段名
     * @return String，字段文本或 null
     */
    private String optionalText(ObjectNode metadata, String fieldName)
    {
        JsonNode value = metadata.get(fieldName);
        if (value == null || value.isNull())
        {
            return null;
        }
        if (!value.isTextual())
        {
            throw metadataFailure(null);
        }
        return value.textValue();
    }

    /**
     * 读取可选整型元数据并校验字段类型。
     *
     * @param metadata ObjectNode，模型元数据
     * @param fieldName String，待读取字段名
     * @return Integer，字段值或 null
     */
    private Integer optionalInt(ObjectNode metadata, String fieldName)
    {
        JsonNode value = metadata.get(fieldName);
        if (value == null || value.isNull())
        {
            return null;
        }
        if (!value.isInt())
        {
            throw metadataFailure(null);
        }
        return value.intValue();
    }

    /**
     * 读取可选长整型元数据并校验字段类型和正数约束。
     *
     * @param metadata ObjectNode，模型元数据
     * @param fieldName String，待读取字段名
     * @return Long，字段值或 null
     */
    private Long optionalLong(ObjectNode metadata, String fieldName)
    {
        JsonNode value = metadata.get(fieldName);
        if (value == null || value.isNull())
        {
            return null;
        }
        if (!value.canConvertToLong() || !value.isIntegralNumber() || value.longValue() <= 0)
        {
            throw metadataFailure(null);
        }
        return value.longValue();
    }

    /**
     * 构造不泄露原始 JSON 的稳定元数据损坏异常。
     *
     * @param cause Throwable，内部解析异常，允许为空
     * @return ServiceException，HTTP 500 稳定业务异常
     */
    private ServiceException metadataFailure(Throwable cause)
    {
        ServiceException exception = new ServiceException("流程模型元数据损坏", HttpStatus.ERROR);
        if (cause != null)
        {
            exception.initCause(cause);
        }
        return exception;
    }

    /**
     * 校验请求对象存在。
     *
     * @param request WorkflowModelDto，待校验请求
     * @return 无返回值
     */
    private void requireRequest(WorkflowModelDto request)
    {
        if (request == null)
        {
            throw new ServiceException("模型请求不能为空", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 校验请求中的表单模式、表单主键及真实表单引用。
     *
     * @param request WorkflowModelDto，包含可选表单元数据的请求
     * @return 无返回值
     */
    private void validateMetadataRequest(WorkflowModelDto request)
    {
        Integer formType = request.getFormType();
        if (formType != null && (formType < MIN_FORM_TYPE || formType > MAX_FORM_TYPE))
        {
            throw new ServiceException("表单类型不合法", HttpStatus.BAD_REQUEST);
        }
        Long formId = request.getFormId();
        if (formId != null)
        {
            requireActiveForm(formId);
        }
        if (Integer.valueOf(PROCESS_FORM_TYPE).equals(formType) && formId == null)
        {
            throw new ServiceException("流程表单主键不能为空", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 校验页码和页大小并计算安全 offset。
     *
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return PageWindow，经过边界和溢出校验的分页窗口
     */
    private PageWindow requirePage(int pageNum, int pageSize)
    {
        if (pageNum <= 0 || pageSize <= 0 || pageSize > MAX_PAGE_SIZE)
        {
            throw new ServiceException("分页参数不合法", HttpStatus.BAD_REQUEST);
        }
        long offset = (long) (pageNum - 1) * pageSize;
        if (offset > Integer.MAX_VALUE)
        {
            throw new ServiceException("分页偏移量过大", HttpStatus.BAD_REQUEST);
        }
        return new PageWindow((int) offset, pageSize);
    }

    /**
     * 校验模型 key 并返回规范值。
     *
     * @param modelKey String，请求中的模型 key
     * @return String，可安全用于 Flowable 查询和 BPMN 资源名的模型 key
     */
    private String requireModelKey(String modelKey)
    {
        String normalized = requireText(modelKey, "模型标识不能为空");
        if (!MODEL_KEY_PATTERN.matcher(normalized).matches())
        {
            throw new ServiceException("模型标识格式不合法", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 校验必填文本及数据库字符上限并返回规范值。
     *
     * @param value String，待校验文本
     * @param blankMessage String，空文本的稳定提示
     * @param tooLongMessage String，超过字符上限的稳定提示
     * @param maxLength int，数据库允许的最大字符数
     * @return String，规范化且未超过上限的文本
     */
    private String requireBoundedText(String value, String blankMessage,
            String tooLongMessage, int maxLength)
    {
        String normalized = requireText(value, blankMessage);
        if (normalized.length() > maxLength)
        {
            throw new ServiceException(tooLongMessage, HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 校验文本非空并去除首尾空白。
     *
     * @param value String，待校验文本
     * @param message String，校验失败的稳定提示
     * @return String，规范化后的非空文本
     */
    private String requireText(String value, String message)
    {
        if (!hasText(value))
        {
            throw new ServiceException(message, HttpStatus.BAD_REQUEST);
        }
        return value.trim();
    }

    /**
     * 校验并去重业务主键集合。
     *
     * @param ids Collection&lt;String&gt;，请求主键集合
     * @param message String，空集合或空主键的稳定提示
     * @return List&lt;String&gt;，保持请求顺序的规范主键集合
     */
    private List<String> requireIds(Collection<String> ids, String message)
    {
        if (ids == null || ids.isEmpty())
        {
            throw new ServiceException(message, HttpStatus.BAD_REQUEST);
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String id : ids)
        {
            normalized.add(requireText(id, message));
        }
        return List.copyOf(normalized);
    }

    /**
     * 判断文本是否包含非空白字符。
     *
     * @param value String，待判断文本
     * @return boolean，true 表示文本非空白
     */
    private static boolean hasText(String value)
    {
        return value != null && !value.isBlank();
    }

    /**
     * 同一模型版本组的固定顺序当前读结果。
     *
     * @param source WorkflowModelLockRow，保存入口最初指向的来源模型
     * @param latest WorkflowModelLockRow，同 key 当前最高模型版本
     */
    private record LockedModelVersions(WorkflowModelLockRow source,
            WorkflowModelLockRow latest)
    {
    }

    /**
     * 安全分页窗口。
     *
     * @param offset int，Flowable listPage 起始偏移
     * @param pageSize int，Flowable listPage 最大记录数
     */
    private record PageWindow(int offset, int pageSize)
    {
    }

    /**
     * 部署快照的节点引用和表单模板固定读取结果。
     *
     * @param reference WorkflowBpmnFormReference，BPMN 节点表单引用
     * @param formName String，部署时固化的表单名称
     * @param content String，部署时固化的正式表单 JSON
     * @param extensionSnapshots List&lt;WfDeployExtensionSnapshot&gt;，内嵌自定义字段的精确版本快照
     */
    private record FormSnapshotSource(WorkflowBpmnFormReference reference,
            String formName, String content,
            List<WfDeployExtensionSnapshot> extensionSnapshots)
    {
    }
}

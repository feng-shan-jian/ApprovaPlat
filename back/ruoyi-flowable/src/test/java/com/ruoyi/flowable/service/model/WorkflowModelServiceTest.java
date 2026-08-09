package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.FlowableListener;
import org.flowable.bpmn.model.ImplementationType;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.IdentityService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.DeploymentBuilder;
import org.flowable.engine.repository.Model;
import org.flowable.engine.repository.ModelQuery;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCategory;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.WfForm;
import com.ruoyi.flowable.domain.WorkflowModelLockRow;
import com.ruoyi.flowable.domain.WorkflowModelSaveRecord;
import com.ruoyi.flowable.domain.dto.WorkflowModelDto;
import com.ruoyi.flowable.domain.vo.WorkflowBpmnValidationIssue;
import com.ruoyi.flowable.domain.vo.WorkflowPageResult;
import com.ruoyi.flowable.domain.vo.WorkflowBpmnValidationReport;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfCategoryMapper;
import com.ruoyi.flowable.mapper.WfDeployFormMapper;
import com.ruoyi.flowable.mapper.WfFormMapper;
import com.ruoyi.flowable.mapper.WorkflowModelSaveMapper;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;

class WorkflowModelServiceTest
{
    private RepositoryService repositoryService;

    private WorkflowBpmnService bpmnService;

    private WfCategoryMapper categoryMapper;

    private WfFormMapper formMapper;

    private WfDeployFormMapper deployFormMapper;

    private WorkflowFormTemplateValidator formTemplateValidator;

    private WorkflowBpmnIdentityValidator bpmnIdentityValidator;

    private WorkflowModelSaveMapper modelSaveMapper;

    private WorkflowExtensionDeploymentService extensionDeploymentService;

    private WorkflowDmnDecisionService dmnDecisionService;

    private WorkflowModelService service;

    /**
     * 为每个测试创建真实执行边界、可信身份和外部依赖替身。
     *
     * @return 无返回值；初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        // 单元测试使用 Flowable 公共 API 替身，显式绑定生产写边界要求的可重复读事务特征。
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                Connection.TRANSACTION_REPEATABLE_READ);
        repositoryService = mock(RepositoryService.class);
        bpmnService = mock(WorkflowBpmnService.class);
        categoryMapper = mock(WfCategoryMapper.class);
        formMapper = mock(WfFormMapper.class);
        deployFormMapper = mock(WfDeployFormMapper.class);
        formTemplateValidator = mock(WorkflowFormTemplateValidator.class);
        bpmnIdentityValidator = mock(WorkflowBpmnIdentityValidator.class);
        modelSaveMapper = mock(WorkflowModelSaveMapper.class);
        extensionDeploymentService = mock(WorkflowExtensionDeploymentService.class);
        dmnDecisionService = mock(WorkflowDmnDecisionService.class);
        when(extensionDeploymentService.prepare(any(), eq("7"))).thenAnswer(invocation ->
        {
            WorkflowBpmnDocument document = invocation.getArgument(0);
            return new WorkflowPreparedExtensionDeployment(
                    document.bpmnXml().getBytes(StandardCharsets.UTF_8), List.of());
        });
        when(dmnDecisionService.prepare(any(byte[].class))).thenAnswer(invocation ->
                new WorkflowPreparedDmnDeployment(invocation.getArgument(0), List.of()));
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity("7", Set.of("ROLE2")));
        IdentityService identityService = mock(IdentityService.class);
        WorkflowAuthenticationContext authenticationContext = new WorkflowAuthenticationContext(
                identityService, new WorkflowIdentityCodec());
        WorkflowEngineOperations operations = new WorkflowEngineOperations(authenticationContext,
                new WorkflowExceptionTranslator(), identityResolver);
        service = new WorkflowModelService(operations, repositoryService, bpmnService,
                bpmnIdentityValidator, modelSaveMapper, categoryMapper, formMapper, deployFormMapper,
                formTemplateValidator, extensionDeploymentService, dmnDecisionService);
    }

    /**
     * 清理当前测试线程绑定的事务特征，防止影响后续测试。
     *
     * @return 无返回值；清理后线程不再携带模拟事务状态
     */
    @AfterEach
    void clearTransactionCharacteristics()
    {
        TransactionSynchronizationManager.clear();
    }

    /**
     * 验证最新模型列表严格先 count 再按计算 offset 调用 listPage。
     *
     * @return 无返回值；分页调用或视图映射不正确时测试失败
     */
    @Test
    void listsLatestModelsWithNativePagination()
    {
        ModelQuery query = modelQuery();
        when(repositoryService.createModelQuery()).thenReturn(query);
        when(query.count()).thenReturn(25L);
        Model model = model("model-1", "expense", "报销审批", "finance", 3, "deployment-1");
        when(model.getMetaInfo()).thenReturn("{\"description\":\"费用审批\",\"formType\":0,\"formId\":12}");
        when(query.listPage(20, 10)).thenReturn(List.of(model));

        WorkflowPageResult<?> result = service.list(new WorkflowModelDto(), 3, 10);

        assertThat(result.total()).isEqualTo(25);
        assertThat(result.rows()).hasSize(1);
        verify(query).count();
        verify(query).listPage(20, 10);
    }

    /**
     * 验证显式校验复用 BPMN、身份和表单共同门禁，并返回无问题的结构化报告。
     * @return void，校验通过仍产生写入或未执行共同门禁时测试失败
     */
    @Test
    void validatesBpmnThroughSharedDeploymentGate()
    {
        WorkflowBpmnDocument document = new WorkflowBpmnDocument(
                new BpmnModel(), "<definitions />", List.of());
        when(bpmnService.validateForSave(any(byte[].class))).thenReturn(document);
        when(bpmnService.deploymentCompatibilityIssues(document)).thenReturn(List.of());

        WorkflowBpmnValidationReport report = service.validateBpmn("<definitions />");

        assertThat(report.valid()).isTrue();
        assertThat(report.issues()).isEmpty();
        verify(bpmnIdentityValidator).validate(document);
        verify(bpmnService).validateDeployable(document);
        verify(repositoryService, never()).saveModel(any());
    }

    /**
     * 验证显式校验把 round-trip-only 元素作为可保存但不可部署的精确警告返回。
     *
     * @return void，警告丢失、valid 被误置为 false 或错误调用部署校验时测试失败
     */
    @Test
    void returnsDeploymentCompatibilityWarningForRoundTripOnlyElement()
    {
        WorkflowBpmnDocument document = new WorkflowBpmnDocument(
                new BpmnModel(), "<definitions />", List.of());
        WorkflowBpmnValidationIssue warning = new WorkflowBpmnValidationIssue(
                "BPMN_ELEMENT_NOT_EXECUTABLE", "WARNING", "complexGateway_1",
                "ComplexGateway 可编辑和导出，但 Flowable 8 不支持部署执行");
        when(bpmnService.validateForSave(any(byte[].class))).thenReturn(document);
        when(bpmnService.deploymentCompatibilityIssues(document))
                .thenReturn(List.of(warning));

        WorkflowBpmnValidationReport report = service.validateBpmn("<definitions />");

        assertThat(report.valid()).isTrue();
        assertThat(report.issues()).containsExactly(warning);
        verify(bpmnIdentityValidator).validate(document);
        verify(bpmnService, never()).validateDeployable(document);
        verify(repositoryService, never()).saveModel(any());
    }

    /**
     * 验证预期 BPMN 业务失败转换为稳定诊断，不把异常伪装为接口成功且 valid=true。
     * @return void，错误分类或消息缺失时测试失败
     */
    @Test
    void returnsStructuredIssueForInvalidBpmn()
    {
        when(bpmnService.validateForSave(any(byte[].class))).thenThrow(
                new ServiceException("BPMN XML 不允许 DTD 或实体声明", 400));

        WorkflowBpmnValidationReport report = service.validateBpmn("<!DOCTYPE x>");

        assertThat(report.valid()).isFalse();
        assertThat(report.issues()).singleElement().satisfies(issue ->
        {
            assertThat(issue.code()).isEqualTo("BPMN_XML_INVALID");
            assertThat(issue.severity()).isEqualTo("ERROR");
            assertThat(issue.elementId()).isNull();
            assertThat(issue.message()).contains("DTD");
        });
    }

    /**
     * 验证创建模型会在写事务内校验分类和表单并记录可信当前用户。
     *
     * @return 无返回值；真实引用或元数据写入不正确时测试失败
     */
    @Test
    void createsModelWithTrustedMetadataAndRealForm()
            throws Exception
    {
        WorkflowModelDto request = createRequest();
        request.setDescription("费用申请");
        request.setFormType(0);
        request.setFormId(12L);
        when(categoryMapper.selectByCode("finance")).thenReturn(activeCategory("finance"));
        when(formMapper.selectById(12L)).thenReturn(activeForm(12L, "报销表单", "{}"));
        ModelQuery duplicateQuery = modelQuery();
        when(repositoryService.createModelQuery()).thenReturn(duplicateQuery);
        when(duplicateQuery.count()).thenReturn(0L);
        Model target = mock(Model.class);
        when(target.getId()).thenReturn("model-1");
        when(repositoryService.newModel()).thenReturn(target);
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);

        assertThat(service.createModel(request)).isEqualTo("model-1");

        verify(target).setMetaInfo(metadata.capture());
        JsonNode json = JsonMapper.shared().readTree(metadata.getValue());
        assertThat(json.get("createUser").asText()).isEqualTo("7");
        assertThat(json.get("formId").asLong()).isEqualTo(12L);
        verify(repositoryService).saveModel(target);

        ArgumentCaptor<byte[]> initialSource = ArgumentCaptor.forClass(byte[].class);
        verify(bpmnService).validateDraft(initialSource.capture());
        verify(repositoryService).addModelEditorSource("model-1", initialSource.getValue());
        when(repositoryService.validateProcess(any(BpmnModel.class))).thenReturn(List.of());
        WorkflowBpmnDocument initialDocument = new WorkflowBpmnService(repositoryService)
                .validate(initialSource.getValue());
        assertThat(initialDocument.bpmnModel().getProcessById("expense")).isNotNull();
        assertThat(initialDocument.bpmnModel().getGraphicInfo("review")).isNotNull();
        StartEvent initialStartEvent = (StartEvent) initialDocument.bpmnModel()
                .getFlowElement("start");
        UserTask initialReviewTask = (UserTask) initialDocument.bpmnModel()
                .getFlowElement("review");
        EndEvent initialEndEvent = (EndEvent) initialDocument.bpmnModel()
                .getFlowElement("end");
        // 初始 XML 必须同时包含顺序流正向引用和节点反向引用，避免设计器首次打开即产生虚假断连错误。
        assertThat(initialStartEvent.getOutgoingFlows())
                .extracting(SequenceFlow::getId)
                .containsExactly("flow_start_review");
        assertThat(initialReviewTask.getIncomingFlows())
                .extracting(SequenceFlow::getId)
                .containsExactly("flow_start_review");
        assertThat(initialReviewTask.getOutgoingFlows())
                .extracting(SequenceFlow::getId)
                .containsExactly("flow_review_end");
        assertThat(initialEndEvent.getIncomingFlows())
                .extracting(SequenceFlow::getId)
                .containsExactly("flow_review_end");
        assertThat(initialReviewTask.getTaskListeners())
                .extracting(FlowableListener::getEvent)
                .containsExactly("create", "assignment", "complete");
        assertThat(initialReviewTask.getTaskListeners()).allSatisfy(listener ->
        {
            assertThat(listener.getImplementationType()).isEqualTo(
                    ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
            assertThat(listener.getImplementation()).isEqualTo("${userTaskListener}");
        });
        assertThat(initialDocument.formReferences()).containsExactly(
                new WorkflowBpmnFormReference(WorkflowFormSourceType.TEMPLATE, 12L,
                        "key_12", "start", "提交申请", null, "expense"));
    }

    /**
     * 验证节点表单模式会持久化可打开的无发起表单草稿，而完整校验仍拒绝直接部署该草稿。
     *
     * @return 无返回值；草稿未落库或正式表单门禁被放松时测试失败
     */
    @Test
    void createsEditableDraftWithoutProcessForm()
    {
        WorkflowModelDto request = createRequest();
        request.setFormType(2);
        when(categoryMapper.selectByCode("finance")).thenReturn(activeCategory("finance"));
        ModelQuery duplicateQuery = modelQuery();
        when(repositoryService.createModelQuery()).thenReturn(duplicateQuery);
        when(duplicateQuery.count()).thenReturn(0L);
        Model target = mock(Model.class);
        when(target.getId()).thenReturn("model-draft");
        when(repositoryService.newModel()).thenReturn(target);
        ArgumentCaptor<byte[]> initialSource = ArgumentCaptor.forClass(byte[].class);

        assertThat(service.createModel(request)).isEqualTo("model-draft");

        verify(repositoryService).addModelEditorSource(eq("model-draft"), initialSource.capture());
        when(repositoryService.validateProcess(any(BpmnModel.class))).thenReturn(List.of());
        WorkflowBpmnService realValidator = new WorkflowBpmnService(repositoryService);
        assertThat(realValidator.validateDraft(initialSource.getValue()).formReferences()).isEmpty();
        assertThatThrownBy(() -> realValidator.validate(initialSource.getValue()))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getMessage()).contains("开始节点"));
    }

    /**
     * 验证旧系统遗留的空编辑器源码返回空串，使前端能够生成初始草稿。
     *
     * @return 无返回值；旧空模型仍返回 404 或绕过模型存在性校验时测试失败
     */
    @Test
    void returnsEmptyBpmnForLegacyUndesignedModel()
    {
        Model model = model("model-legacy", "expense", "报销审批", "finance", 1, null);
        when(repositoryService.getModel("model-legacy")).thenReturn(model);
        when(repositoryService.getModelEditorSource("model-legacy")).thenReturn(null);

        assertThat(service.getBpmnXml("model-legacy")).isEmpty();

        verify(bpmnService, never()).validateDraft(any());
    }

    /**
     * 验证同一个 modelKey 不能创建第二个初始模型，避免 latestVersion 歧义。
     *
     * @return 无返回值；重复模型未被阻止时测试失败
     */
    @Test
    void rejectsDuplicateInitialModelKey()
    {
        WorkflowModelDto request = createRequest();
        when(categoryMapper.selectByCode("finance")).thenReturn(activeCategory("finance"));
        ModelQuery duplicateQuery = modelQuery();
        when(repositoryService.createModelQuery()).thenReturn(duplicateQuery);
        when(duplicateQuery.count()).thenReturn(1L);

        assertThatThrownBy(() -> service.createModel(request))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(409));
        verify(repositoryService, never()).newModel();
    }

    /**
     * 验证创建模型时由服务层拒绝超过业务分类表字段上限的分类编码。
     *
     * @return 无返回值；服务层允许写入无法关联 wf_category.code 的编码时测试失败
     */
    @Test
    void rejectsOversizedCategoryWhenCreatingModel()
    {
        WorkflowModelDto request = createRequest();
        request.setCategory("c".repeat(65));

        assertThatThrownBy(() -> service.createModel(request))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(400));
        verify(categoryMapper, never()).selectByCode(any());
        verify(repositoryService, never()).newModel();
    }

    /**
     * 验证修改模型时由服务层拒绝超过业务分类表字段上限的分类编码。
     *
     * @return 无返回值；超长分类进入 Flowable 模型保存流程时测试失败
     */
    @Test
    void rejectsOversizedCategoryWhenUpdatingModel()
    {
        WorkflowModelDto request = new WorkflowModelDto();
        request.setModelId("model-1");
        request.setCategory("c".repeat(65));
        Model model = model("model-1", "expense", "报销审批", "finance", 1, null);
        when(repositoryService.getModel("model-1")).thenReturn(model);

        assertThatThrownBy(() -> service.updateModel(request))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(400));
        verify(categoryMapper, never()).selectByCode(any());
        verify(repositoryService, never()).saveModel(any());
    }

    /**
     * 验证切换到节点独立表单时清除旧流程级 formId，同时保留未知兼容字段。
     *
     * @return 无返回值；metaInfo 合并语义不正确时测试失败
     */
    @Test
    void clearsObsoleteProcessFormIdWhenChangingFormType()
            throws Exception
    {
        WorkflowModelDto request = new WorkflowModelDto();
        request.setModelId("model-1");
        request.setFormType(2);
        Model model = model("model-1", "expense", "报销审批", "finance", 1, null);
        when(model.getMetaInfo()).thenReturn("{\"formType\":0,\"formId\":12,\"futureField\":\"keep\"}");
        when(repositoryService.getModel("model-1")).thenReturn(model);
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);

        service.updateModel(request);

        verify(model).setMetaInfo(metadata.capture());
        JsonNode json = JsonMapper.shared().readTree(metadata.getValue());
        assertThat(json.get("formType").asInt()).isEqualTo(2);
        assertThat(json.has("formId")).isFalse();
        assertThat(json.get("futureField").asText()).isEqualTo("keep");
    }

    /**
     * 验证已部署模型保存时自动创建新版本并返回新模型主键。
     *
     * @return 无返回值；已部署来源被覆盖或未切换到新版本时测试失败
     */
    @Test
    void savesDeployedModelAsNewVersionAutomatically()
    {
        WorkflowModelDto request = new WorkflowModelDto();
        request.setSaveRequestId(UUID.randomUUID().toString());
        request.setModelId("model-1");
        request.setBpmnXml("<definitions/>");
        request.setNewVersion(false);
        Model source = model("model-1", "expense", "报销审批", "finance", 1, "deployment-1");
        when(repositoryService.getModel("model-1")).thenReturn(source);
        when(categoryMapper.selectByCode("finance")).thenReturn(activeCategory("finance"));
        when(bpmnService.validateForSave(any(byte[].class))).thenReturn(document(List.of()));
        WorkflowModelLockRow lockedSource = lockedModel(
                "model-1", "expense", "报销审批", "finance", 1, "deployment-1");
        stubPendingSaveRequest(request, lockedSource, lockedSource, 1);
        Model target = mock(Model.class);
        when(target.getId()).thenReturn("model-2");
        when(repositoryService.newModel()).thenReturn(target);

        assertThat(service.saveModel(request)).isEqualTo("model-2");

        verify(target).setVersion(2);
        verify(repositoryService).saveModel(target);
        verify(repositoryService).addModelEditorSource("model-2",
                "<definitions/>".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证另存新版本使用当前最高版本加一，而不是旧来源版本简单加一。
     *
     * @return 无返回值；版本号或源码写入不正确时测试失败
     */
    @Test
    void savesAsVersionAfterCurrentLatest()
    {
        WorkflowModelDto request = new WorkflowModelDto();
        request.setSaveRequestId(UUID.randomUUID().toString());
        request.setModelId("model-1");
        request.setBpmnXml("<definitions/>");
        request.setNewVersion(true);
        Model source = model("model-1", "expense", "旧报销", "finance", 1, "deployment-1");
        when(source.getMetaInfo()).thenReturn("{}");
        when(repositoryService.getModel("model-1")).thenReturn(source);
        when(categoryMapper.selectByCode("finance")).thenReturn(activeCategory("finance"));
        when(bpmnService.validateForSave(any(byte[].class))).thenReturn(document(List.of()));
        WorkflowModelLockRow lockedSource = lockedModel(
                "model-1", "expense", "旧报销", "finance", 1, "deployment-1");
        WorkflowModelLockRow lockedLatest = lockedModel(
                "model-3", "expense", "当前报销", "finance", 3, null);
        stubPendingSaveRequest(request, lockedSource, lockedLatest, 1);
        Model target = mock(Model.class);
        when(target.getId()).thenReturn("model-4");
        when(repositoryService.newModel()).thenReturn(target);

        assertThat(service.saveModel(request)).isEqualTo("model-4");

        verify(target).setVersion(4);
        verify(repositoryService).saveModel(target);
        verify(repositoryService).addModelEditorSource("model-4",
                "<definitions/>".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证历史版本即使按覆盖保存提交，也会自动复制为新的最高版本。
     *
     * @return 无返回值；历史版本被原地覆盖或未按最高版本递增时测试失败
     */
    @Test
    void savesHistoricalModelAsNewLatestVersionAutomatically()
    {
        WorkflowModelDto request = new WorkflowModelDto();
        request.setSaveRequestId(UUID.randomUUID().toString());
        request.setModelId("model-1");
        request.setBpmnXml("<definitions/>");
        request.setNewVersion(false);
        Model source = model("model-1", "expense", "旧报销", "finance", 1, null);
        when(repositoryService.getModel("model-1")).thenReturn(source);
        when(categoryMapper.selectByCode("finance")).thenReturn(activeCategory("finance"));
        when(bpmnService.validateForSave(any(byte[].class))).thenReturn(document(List.of()));
        WorkflowModelLockRow lockedSource = lockedModel(
                "model-1", "expense", "旧报销", "finance", 1, null);
        WorkflowModelLockRow lockedLatest = lockedModel(
                "model-3", "expense", "当前报销", "finance", 3, null);
        stubPendingSaveRequest(request, lockedSource, lockedLatest, 1);
        Model target = mock(Model.class);
        when(target.getId()).thenReturn("model-4");
        when(repositoryService.newModel()).thenReturn(target);

        assertThat(service.saveModel(request)).isEqualTo("model-4");

        verify(target).setVersion(4);
        verify(repositoryService).saveModel(target);
        verify(repositoryService).addModelEditorSource("model-4",
                "<definitions/>".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证最新且未部署的模型仍按普通保存覆盖当前版本。
     *
     * @return 无返回值；未部署最新版被错误另存时测试失败
     */
    @Test
    void overwritesCurrentUndeployedLatestModel()
    {
        WorkflowModelDto request = new WorkflowModelDto();
        request.setSaveRequestId(UUID.randomUUID().toString());
        request.setModelId("model-3");
        request.setBpmnXml("<definitions/>");
        request.setNewVersion(false);
        Model source = model("model-3", "expense", "当前报销", "finance", 3, null);
        when(repositoryService.getModel("model-3")).thenReturn(source);
        when(categoryMapper.selectByCode("finance")).thenReturn(activeCategory("finance"));
        when(bpmnService.validateForSave(any(byte[].class))).thenReturn(document(List.of()));
        WorkflowModelLockRow lockedSource = lockedModel(
                "model-3", "expense", "当前报销", "finance", 3, null);
        stubPendingSaveRequest(request, lockedSource, lockedSource, 1);

        assertThat(service.saveModel(request)).isEqualTo("model-3");

        verify(repositoryService, never()).newModel();
        verify(repositoryService).saveModel(source);
        verify(repositoryService).addModelEditorSource("model-3",
                "<definitions/>".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证普通快照仍指向原最新版、锁内当前读已出现更高版本时不会覆盖来源。
     *
     * @return 无返回值；来源被原地覆盖或新版本未按锁内最高版本递增时测试失败
     */
    @Test
    void createsNextVersionWhenLockedSourceHasBecomeHistorical()
    {
        WorkflowModelDto request = saveRequest("model-3", false);
        Model sourceSnapshot = model("model-3", "expense", "快照报销", "finance", 3, null);
        when(repositoryService.getModel("model-3")).thenReturn(sourceSnapshot);
        when(categoryMapper.selectByCode("finance")).thenReturn(activeCategory("finance"));
        when(bpmnService.validateForSave(any(byte[].class))).thenReturn(document(List.of()));
        WorkflowModelLockRow lockedSource = lockedModel(
                "model-3", "expense", "锁内来源", "finance", 3, null);
        WorkflowModelLockRow lockedLatest = lockedModel(
                "model-4", "expense", "并发最新版", "finance", 4, null);
        stubPendingSaveRequest(request, lockedSource, lockedLatest, 1);
        Model target = mock(Model.class);
        when(target.getId()).thenReturn("model-5");
        when(repositoryService.newModel()).thenReturn(target);

        assertThat(service.saveModel(request)).isEqualTo("model-5");

        verify(target).setVersion(5);
        verify(repositoryService).saveModel(target);
        verify(repositoryService, never()).saveModel(sourceSnapshot);
        verify(repositoryService).addModelEditorSource("model-5",
                "<definitions/>".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证已完成幂等请求直接返回首次保存结果，不再读取或写入 Flowable 模型。
     *
     * @return 无返回值；重放再次执行模型保存或源码写入时测试失败
     */
    @Test
    void returnsCompletedSaveRequestWithoutModelWrite()
    {
        WorkflowModelDto request = saveRequest("model-1", false);
        stubSaveRecord(request, "model-2");

        assertThat(service.saveModel(request)).isEqualTo("model-2");

        verify(repositoryService, never()).getModel(any());
        verify(repositoryService, never()).saveModel(any());
        verify(repositoryService, never()).addModelEditorSource(any(), any());
        verify(modelSaveMapper, never()).selectOldestDefaultTenantModelForUpdate(any());
        verify(modelSaveMapper, never()).selectLatestDefaultTenantModelForUpdate(any());
        verify(modelSaveMapper, never()).selectDefaultTenantModelForUpdate(any());
        verify(modelSaveMapper, never()).completeSaveRequest(any(), any());
    }

    /**
     * 验证同一个 requestId 被不同保存载荷复用时返回冲突且不产生模型副作用。
     *
     * @return 无返回值；冲突请求进入模型读写链或未返回 409 时测试失败
     */
    @Test
    void rejectsSaveRequestIdReusedWithDifferentPayload()
    {
        WorkflowModelDto request = saveRequest("model-1", false);
        when(modelSaveMapper.ensureSaveRequest(
                eq(request.getSaveRequestId()), eq("7"), eq("model-1"), any()))
                .thenReturn(1);
        when(modelSaveMapper.selectSaveRequestForUpdate(request.getSaveRequestId()))
                .thenReturn(new WorkflowModelSaveRecord(
                        request.getSaveRequestId(), "7", "model-1", "0".repeat(64), null));

        assertThatThrownBy(() -> service.saveModel(request))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(409));

        verify(repositoryService, never()).getModel(any());
        verify(repositoryService, never()).saveModel(any());
        verify(modelSaveMapper, never()).selectOldestDefaultTenantModelForUpdate(any());
        verify(modelSaveMapper, never()).selectLatestDefaultTenantModelForUpdate(any());
        verify(modelSaveMapper, never()).completeSaveRequest(any(), any());
    }

    /**
     * 验证保存严格按幂等行、稳定版本组锚点、最高版本、来源模型顺序加锁。
     *
     * @return 无返回值；移动的最高版本范围在稳定锚点之前加锁时测试失败
     */
    @Test
    void locksStableGroupAnchorBeforeLatestAndSource()
    {
        WorkflowModelDto request = saveRequest("model-3", false);
        Model sourceSnapshot = model("model-3", "expense", "快照报销", "finance", 3, null);
        when(repositoryService.getModel("model-3")).thenReturn(sourceSnapshot);
        when(categoryMapper.selectByCode("finance")).thenReturn(activeCategory("finance"));
        when(bpmnService.validateForSave(any(byte[].class))).thenReturn(document(List.of()));
        WorkflowModelLockRow groupAnchor = lockedModel(
                "model-1", "expense", "初始报销", "finance", 1, "deployment-1");
        WorkflowModelLockRow lockedSource = lockedModel(
                "model-3", "expense", "锁内来源", "finance", 3, null);
        WorkflowModelLockRow lockedLatest = lockedModel(
                "model-4", "expense", "并发最新版", "finance", 4, null);
        stubSaveRecord(request, null);
        when(modelSaveMapper.selectOldestDefaultTenantModelForUpdate("expense"))
                .thenReturn(groupAnchor);
        when(modelSaveMapper.selectLatestDefaultTenantModelForUpdate("expense"))
                .thenReturn(lockedLatest);
        when(modelSaveMapper.selectDefaultTenantModelForUpdate("model-3"))
                .thenReturn(lockedSource);
        when(modelSaveMapper.completeSaveRequest(eq(request.getSaveRequestId()), any()))
                .thenReturn(1);
        Model target = mock(Model.class);
        when(target.getId()).thenReturn("model-5");
        when(repositoryService.newModel()).thenReturn(target);

        assertThat(service.saveModel(request)).isEqualTo("model-5");

        var lockOrder = inOrder(modelSaveMapper);
        lockOrder.verify(modelSaveMapper).ensureSaveRequest(
                eq(request.getSaveRequestId()), eq("7"), eq("model-3"), any());
        lockOrder.verify(modelSaveMapper).selectSaveRequestForUpdate(request.getSaveRequestId());
        lockOrder.verify(modelSaveMapper).selectOldestDefaultTenantModelForUpdate("expense");
        lockOrder.verify(modelSaveMapper).selectLatestDefaultTenantModelForUpdate("expense");
        lockOrder.verify(modelSaveMapper).selectDefaultTenantModelForUpdate("model-3");
        lockOrder.verify(modelSaveMapper).completeSaveRequest(request.getSaveRequestId(), "model-5");
    }

    /**
     * 验证版本组锚点缺失时在读取移动的最高版本范围前立即终止保存。
     *
     * @return 无返回值；缺失锚点仍进入最高版本锁或产生模型副作用时测试失败
     */
    @Test
    void rejectsMissingGroupAnchorBeforeLatestRangeLock()
    {
        WorkflowModelDto request = saveRequest("model-3", false);
        Model sourceSnapshot = model("model-3", "expense", "快照报销", "finance", 3, null);
        when(repositoryService.getModel("model-3")).thenReturn(sourceSnapshot);
        when(bpmnService.validateForSave(any(byte[].class))).thenReturn(document(List.of()));
        stubSaveRecord(request, null);
        when(modelSaveMapper.selectOldestDefaultTenantModelForUpdate("expense"))
                .thenReturn(null);

        assertThatThrownBy(() -> service.saveModel(request))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(409));

        verify(modelSaveMapper, never()).selectLatestDefaultTenantModelForUpdate(any());
        verify(modelSaveMapper, never()).selectDefaultTenantModelForUpdate(any());
        verify(repositoryService, never()).saveModel(any());
        verify(repositoryService, never()).addModelEditorSource(any(), any());
        verify(modelSaveMapper, never()).completeSaveRequest(any(), any());
    }

    /**
     * 验证模型和源码写入后无法完成幂等状态时保存整体报告失败。
     *
     * @return 无返回值；完成数异常仍返回成功或未执行真实模型写入时测试失败
     */
    @Test
    void failsSaveWhenIdempotencyCompletionUpdatesNoRow()
    {
        WorkflowModelDto request = saveRequest("model-3", false);
        Model source = model("model-3", "expense", "当前报销", "finance", 3, null);
        when(repositoryService.getModel("model-3")).thenReturn(source);
        when(categoryMapper.selectByCode("finance")).thenReturn(activeCategory("finance"));
        when(bpmnService.validateForSave(any(byte[].class))).thenReturn(document(List.of()));
        WorkflowModelLockRow lockedSource = lockedModel(
                "model-3", "expense", "当前报销", "finance", 3, null);
        stubPendingSaveRequest(request, lockedSource, lockedSource, 0);

        assertThatThrownBy(() -> service.saveModel(request))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(500);
                    assertThat(exception.getMessage()).isEqualTo("流程模型保存幂等状态异常");
                });

        verify(repositoryService).saveModel(source);
        verify(repositoryService).addModelEditorSource("model-3",
                "<definitions/>".getBytes(StandardCharsets.UTF_8));
        verify(modelSaveMapper).completeSaveRequest(request.getSaveRequestId(), "model-3");
    }

    /**
     * 验证提升旧版本时只保存一次新模型，避免无意义推进 Flowable 修订号和更新时间。
     *
     * @return 无返回值；新模型被重复保存或源码未复制时测试失败
     */
    @Test
    void promotesOldVersionWithSingleModelSave()
    {
        Model source = model("model-1", "expense", "旧报销", "finance", 1, null);
        Model latest = model("model-3", "expense", "当前报销", "finance", 3, null);
        when(repositoryService.getModel("model-1")).thenReturn(source);
        ModelQuery latestQuery = modelQuery();
        when(repositoryService.createModelQuery()).thenReturn(latestQuery);
        when(latestQuery.singleResult()).thenReturn(latest);
        byte[] sourceBytes = "<definitions/>".getBytes(StandardCharsets.UTF_8);
        when(repositoryService.getModelEditorSource("model-1")).thenReturn(sourceBytes);
        when(bpmnService.validate(sourceBytes)).thenReturn(document(List.of()));
        Model promoted = mock(Model.class);
        when(promoted.getId()).thenReturn("model-4");
        when(repositoryService.newModel()).thenReturn(promoted);

        assertThat(service.promoteToLatest("model-1")).isEqualTo("model-4");

        verify(promoted).setVersion(4);
        verify(repositoryService, times(1)).saveModel(promoted);
        verify(repositoryService).addModelEditorSource("model-4", sourceBytes);
    }

    /**
     * 验证模型报告部署关系时安全删除直接返回冲突。
     *
     * @return 无返回值；已部署模型被删除时测试失败
     */
    @Test
    void rejectsDeletingDeployedModel()
    {
        Model model = model("model-1", "expense", "报销审批", "finance", 1, "deployment-1");
        when(repositoryService.getModel("model-1")).thenReturn(model);

        assertThatThrownBy(() -> service.deleteModels(List.of("model-1")))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(409));
        verify(repositoryService, never()).deleteModel(any());
    }

    /**
     * 验证未设计、未部署且没有定义引用的模型可通过非级联模型 API 删除。
     *
     * @return 无返回值；安全模型删除未执行时测试失败
     */
    @Test
    void deletesUndeployedModelAfterPrecheck()
    {
        Model model = model("model-1", "expense", "报销审批", "finance", 1, null);
        when(repositoryService.getModel("model-1")).thenReturn(model);
        ModelQuery deployedQuery = modelQuery();
        when(repositoryService.createModelQuery()).thenReturn(deployedQuery);
        when(deployedQuery.count()).thenReturn(0L);
        when(repositoryService.getModelEditorSource("model-1")).thenReturn(null);

        service.deleteModels(List.of("model-1"));

        verify(repositoryService).deleteModel("model-1");
    }

    /**
     * 验证删除含安全草稿资源的未部署模型不会错误要求开始节点已经配置流程表单。
     *
     * @return 无返回值；删除草稿复用保存或部署门禁时测试失败
     */
    @Test
    void deletesSafeDraftWithoutRequiringDeployableStartForm()
    {
        Model model = model("model-1", "expense", "报销审批", "finance", 1, null);
        byte[] source = "<definitions/>".getBytes(StandardCharsets.UTF_8);
        when(repositoryService.getModel("model-1")).thenReturn(model);
        ModelQuery deployedQuery = modelQuery();
        when(repositoryService.createModelQuery()).thenReturn(deployedQuery);
        when(deployedQuery.count()).thenReturn(0L);
        when(repositoryService.getModelEditorSource("model-1")).thenReturn(source);
        WorkflowBpmnDocument draft = new WorkflowBpmnDocument(
                new BpmnModel(), "<definitions/>", List.of());
        when(bpmnService.validateDraft(source)).thenReturn(draft);

        service.deleteModels(List.of("model-1"));

        verify(bpmnService).validateDraft(source);
        verify(bpmnService, never()).validate(source);
        verify(repositoryService).deleteModel("model-1");
    }

    /**
     * 验证部署会保存不可变快照并自动停用旧定义，同时不冻结旧版本在途实例。
     *
     * @return 无返回值；部署或快照闭环不完整时测试失败
     */
    @Test
    void deploysModelAndPersistsImmutableFormSnapshots()
    {
        Model model = model("model-1", "expense", "报销审批", "finance", 2, null);
        when(repositoryService.getModel("model-1")).thenReturn(model);
        WorkflowModelLockRow lockedModel = lockedModel(
                "model-1", "expense", "报销审批", "finance", 2, null);
        when(modelSaveMapper.selectOldestDefaultTenantModelForUpdate("expense"))
                .thenReturn(lockedModel);
        when(modelSaveMapper.selectDefaultTenantModelForUpdate("model-1"))
                .thenReturn(lockedModel);
        ModelQuery deployedQuery = modelQuery();
        when(repositoryService.createModelQuery()).thenReturn(deployedQuery);
        when(deployedQuery.count()).thenReturn(0L);
        when(categoryMapper.selectByCode("finance")).thenReturn(activeCategory("finance"));
        byte[] source = "<definitions/>".getBytes(StandardCharsets.UTF_8);
        when(repositoryService.getModelEditorSource("model-1")).thenReturn(source);
        List<WorkflowBpmnFormReference> references = List.of(
                new WorkflowBpmnFormReference(1L, "key_1", "start", "提交"),
                new WorkflowBpmnFormReference(WorkflowFormSourceType.EMBEDDED, null,
                        WorkflowFormSourceType.EMBEDDED_FORM_KEY, "approve", "审批",
                        "{\"fields\":[]}"));
        when(bpmnService.validateForSave(source)).thenReturn(document(references));
        when(formMapper.selectById(1L)).thenReturn(activeForm(1L, "申请表", "{\"v\":1}"));

        DeploymentBuilder builder = mock(DeploymentBuilder.class);
        when(repositoryService.createDeployment()).thenReturn(builder);
        when(builder.name("报销审批")).thenReturn(builder);
        when(builder.key("expense")).thenReturn(builder);
        when(builder.category("finance")).thenReturn(builder);
        when(builder.addBytes("expense.bpmn20.xml", source)).thenReturn(builder);
        Deployment deployment = mock(Deployment.class);
        when(deployment.getId()).thenReturn("deployment-1");
        when(builder.deploy()).thenReturn(deployment);
        ProcessDefinition oldDefinition = mock(ProcessDefinition.class);
        when(oldDefinition.getId()).thenReturn("expense:1:9");
        ProcessDefinitionQuery activeDefinitionQuery = mock(ProcessDefinitionQuery.class);
        when(activeDefinitionQuery.processDefinitionKey("expense"))
                .thenReturn(activeDefinitionQuery);
        when(activeDefinitionQuery.active()).thenReturn(activeDefinitionQuery);
        when(activeDefinitionQuery.list()).thenReturn(List.of(oldDefinition));
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getId()).thenReturn("expense:2:10");
        when(definition.getKey()).thenReturn("expense");
        ProcessDefinitionQuery deployedDefinitionQuery = mock(ProcessDefinitionQuery.class);
        when(deployedDefinitionQuery.deploymentId("deployment-1"))
                .thenReturn(deployedDefinitionQuery);
        when(deployedDefinitionQuery.list()).thenReturn(List.of(definition));
        when(repositoryService.createProcessDefinitionQuery())
                .thenReturn(activeDefinitionQuery, deployedDefinitionQuery);
        when(deployFormMapper.insertBatch(anyList())).thenAnswer(invocation ->
                ((List<?>) invocation.getArgument(0)).size());
        ArgumentCaptor<List<WfDeployForm>> snapshots = snapshotCaptor();

        assertThat(service.deployModel("model-1")).isEqualTo("deployment-1");

        verify(repositoryService).setProcessDefinitionCategory("expense:2:10", "finance");
        verify(repositoryService).suspendProcessDefinitionById("expense:1:9", false, null);
        verify(formTemplateValidator).validate("{\"v\":1}");
        verify(formTemplateValidator).validate("{\"fields\":[]}");
        verify(deployFormMapper).insertBatch(snapshots.capture());
        verify(extensionDeploymentService).persist(eq("deployment-1"), any(), anyList());
        verify(dmnDecisionService).persist(eq("deployment-1"), any(), eq("7"));
        assertThat(snapshots.getValue()).extracting(WfDeployForm::getContent)
                .containsExactly("{\"v\":1}", "{\"fields\":[]}");
        assertThat(snapshots.getValue()).extracting(WfDeployForm::getSourceType)
                .containsExactly("TEMPLATE", "EMBEDDED");
        assertThat(snapshots.getValue()).extracting(WfDeployForm::getFormId)
                .containsExactly(1L, null);
        assertThat(snapshots.getValue()).allSatisfy(snapshot ->
        {
            assertThat(snapshot.getDeployId()).isEqualTo("deployment-1");
            assertThat(snapshot.getCreateBy()).isEqualTo("7");
        });
        verify(model).setDeploymentId("deployment-1");
        verify(repositoryService).saveModel(model);
    }

    /**
     * 验证损坏 metaInfo 返回稳定 500，且不会泄露原始 JSON 内容。
     *
     * @return 无返回值；损坏元数据被静默忽略或原文泄露时测试失败
     */
    @Test
    void reportsMalformedStoredMetadataAsStableFailure()
    {
        Model model = model("model-1", "expense", "报销审批", "finance", 1, null);
        when(model.getMetaInfo()).thenReturn("{secret-invalid-json");
        when(repositoryService.getModel("model-1")).thenReturn(model);
        when(repositoryService.getModelEditorSource("model-1")).thenReturn(null);

        assertThatThrownBy(() -> service.getModel("model-1"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(500);
                    assertThat(exception.getMessage()).isEqualTo("流程模型元数据损坏");
                    assertThat(exception.getMessage()).doesNotContain("secret");
                });
    }

    /**
     * 创建支持链式调用的 Flowable 模型查询替身。
     *
     * @return ModelQuery，所有本测试使用的方法均返回自身的查询替身
     */
    private ModelQuery modelQuery()
    {
        ModelQuery query = mock(ModelQuery.class);
        when(query.latestVersion()).thenReturn(query);
        when(query.modelId(any())).thenReturn(query);
        when(query.modelKey(any())).thenReturn(query);
        when(query.modelNameLike(any())).thenReturn(query);
        when(query.modelCategory(any())).thenReturn(query);
        when(query.deployed()).thenReturn(query);
        when(query.orderByCreateTime()).thenReturn(query);
        when(query.orderByModelVersion()).thenReturn(query);
        when(query.asc()).thenReturn(query);
        when(query.desc()).thenReturn(query);
        return query;
    }

    /**
     * 构造一次具有稳定 UUID、来源模型和 BPMN 正文的保存请求。
     *
     * @param modelId String，保存请求指向的来源模型主键
     * @param newVersion boolean，是否显式要求创建新版本
     * @return WorkflowModelDto，可直接提交给模型服务的保存请求
     */
    private WorkflowModelDto saveRequest(String modelId, boolean newVersion)
    {
        WorkflowModelDto request = new WorkflowModelDto();
        request.setSaveRequestId(UUID.randomUUID().toString());
        request.setModelId(modelId);
        request.setBpmnXml("<definitions/>");
        request.setNewVersion(newVersion);
        return request;
    }

    /**
     * 模拟幂等登记和锁定读取，并使用服务真实传入的摘要构造匹配记录。
     *
     * @param request WorkflowModelDto，包含幂等主键和保存载荷的请求
     * @param savedModelId String，已完成请求的结果模型主键；待处理请求为 null
     * @return 无返回值；后续服务调用将读取匹配的持久化幂等投影
     */
    private void stubSaveRecord(WorkflowModelDto request, String savedModelId)
    {
        // payloadSha256 保存服务端实际生成的摘要，测试不复制生产摘要算法。
        AtomicReference<String> payloadSha256 = new AtomicReference<>();
        when(modelSaveMapper.ensureSaveRequest(
                eq(request.getSaveRequestId()), eq("7"), eq(request.getModelId()), any()))
                .thenAnswer(invocation ->
                {
                    payloadSha256.set(invocation.getArgument(3, String.class));
                    return 1;
                });
        when(modelSaveMapper.selectSaveRequestForUpdate(request.getSaveRequestId()))
                .thenAnswer(invocation -> new WorkflowModelSaveRecord(
                        request.getSaveRequestId(), "7", request.getModelId(),
                        payloadSha256.get(), savedModelId));
    }

    /**
     * 模拟待处理幂等请求、固定顺序的三条模型锁查询和最终完成更新。
     *
     * @param request WorkflowModelDto，待执行的保存请求
     * @param source WorkflowModelLockRow，锁内来源模型当前读投影
     * @param latest WorkflowModelLockRow，锁内最高版本当前读投影
     * @param completionCount int，完成幂等请求时数据库返回的影响行数
     * @return 无返回值；保存链将使用给定锁状态和完成结果
     */
    private void stubPendingSaveRequest(WorkflowModelDto request,
            WorkflowModelLockRow source, WorkflowModelLockRow latest, int completionCount)
    {
        stubSaveRecord(request, null);
        when(modelSaveMapper.selectOldestDefaultTenantModelForUpdate(source.modelKey()))
                .thenReturn(source);
        when(modelSaveMapper.selectLatestDefaultTenantModelForUpdate(source.modelKey()))
                .thenReturn(latest);
        when(modelSaveMapper.selectDefaultTenantModelForUpdate(source.modelId()))
                .thenReturn(source);
        when(modelSaveMapper.completeSaveRequest(eq(request.getSaveRequestId()), any()))
                .thenReturn(completionCount);
    }

    /**
     * 构造默认租户下可用于保存决策的模型当前读锁投影。
     *
     * @param id String，模型主键
     * @param key String，模型版本分组 key
     * @param name String，模型名称
     * @param category String，分类编码
     * @param version int，业务版本号
     * @param deploymentId String，部署主键；未部署时为 null
     * @return WorkflowModelLockRow，字段完整且租户为空字符串的锁投影
     */
    private WorkflowModelLockRow lockedModel(String id, String key, String name,
            String category, int version, String deploymentId)
    {
        return new WorkflowModelLockRow(id, version, key, version, deploymentId,
                name, category, "{}", "");
    }

    /**
     * 创建具有稳定字段行为的 Flowable 模型替身。
     *
     * @param id String，模型主键
     * @param key String，模型 key
     * @param name String，模型名称
     * @param category String，分类编码
     * @param version int，模型版本
     * @param deploymentId String，关联部署主键，允许为空
     * @return Model，字段 getter 已配置的模型替身
     */
    private Model model(String id, String key, String name, String category,
            int version, String deploymentId)
    {
        Model model = mock(Model.class);
        when(model.getId()).thenReturn(id);
        when(model.getKey()).thenReturn(key);
        when(model.getName()).thenReturn(name);
        when(model.getCategory()).thenReturn(category);
        when(model.getVersion()).thenReturn(version);
        when(model.getDeploymentId()).thenReturn(deploymentId);
        when(model.getCreateTime()).thenReturn(new Date(1_000));
        when(model.getLastUpdateTime()).thenReturn(new Date(2_000));
        when(model.getMetaInfo()).thenReturn("{}");
        return model;
    }

    /**
     * 构造有效工作流分类测试对象。
     *
     * @param code String，分类编码
     * @return WfCategory，逻辑删除标志为 0 的分类
     */
    private WfCategory activeCategory(String code)
    {
        WfCategory category = new WfCategory();
        category.setCode(code);
        category.setCategoryName("财务审批");
        category.setDelFlag("0");
        return category;
    }

    /**
     * 构造有效且内容完整的表单测试对象。
     *
     * @param formId Long，表单主键
     * @param formName String，表单名称
     * @param content String，表单 JSON 快照正文
     * @return WfForm，逻辑删除标志为 0 的表单
     */
    private WfForm activeForm(Long formId, String formName, String content)
    {
        WfForm form = new WfForm();
        form.setFormId(formId);
        form.setFormName(formName);
        form.setContent(content);
        form.setDelFlag("0");
        return form;
    }

    /**
     * 构造模型创建测试请求。
     *
     * @return WorkflowModelDto，包含名称、key 和分类的有效请求
     */
    private WorkflowModelDto createRequest()
    {
        WorkflowModelDto request = new WorkflowModelDto();
        request.setModelName("报销审批");
        request.setModelKey("expense");
        request.setCategory("finance");
        return request;
    }

    /**
     * 构造包含一个可执行流程的已校验 BPMN 文档替身数据。
     *
     * @param references List&lt;WorkflowBpmnFormReference&gt;，节点表单引用
     * @return WorkflowBpmnDocument，供模型服务测试使用的 BPMN 文档
     */
    private WorkflowBpmnDocument document(List<WorkflowBpmnFormReference> references)
    {
        BpmnModel bpmnModel = new BpmnModel();
        Process process = new Process();
        process.setId("expense");
        process.setName("报销审批");
        process.setExecutable(true);
        bpmnModel.addProcess(process);
        return new WorkflowBpmnDocument(bpmnModel, "<definitions/>", references);
    }

    /**
     * 创建部署表单快照列表捕获器并集中处理泛型擦除告警。
     *
     * @return ArgumentCaptor&lt;List&lt;WfDeployForm&gt;&gt;，快照批量参数捕获器
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<List<WfDeployForm>> snapshotCaptor()
    {
        return ArgumentCaptor.forClass((Class) List.class);
    }
}

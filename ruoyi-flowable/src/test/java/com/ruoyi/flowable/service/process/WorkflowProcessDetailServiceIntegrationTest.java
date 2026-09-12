package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.task.Comment;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessSnapshot;
import com.ruoyi.flowable.authorization.WorkflowTaskAccessSnapshot;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDetailQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowControlledLoopStateView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessActivityView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessCommentView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDetailView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormSnapshotView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WorkflowHistoricVariableMapper;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.model.WorkflowDeploymentService;
import com.ruoyi.flowable.service.notification.WorkflowManualUrgeService;
import com.ruoyi.flowable.service.notification.WorkflowNotificationOutboxService;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;
import com.ruoyi.flowable.service.identity.WorkflowParticipantRuleRuntimeService;
import com.ruoyi.flowable.service.task.WorkflowAutomaticCopyService;
import com.ruoyi.flowable.service.task.WorkflowControlledLoopService;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceService;
import com.ruoyi.flowable.service.task.WorkflowTaskLifecycleService;
import com.ruoyi.flowable.service.task.WorkflowTaskUnclaimPolicy;
import com.ruoyi.system.service.ISysUserService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 使用真实 Flowable 8、H2 历史表和正式 MyBatis Mapper 验证流程详情读取边界。
 */
class WorkflowProcessDetailServiceIntegrationTest
{
    /**
     * 实际加载多份部署快照，验证三种字段目录共享每份快照唯一一次真实解析及校验的根节点。
     * @return void，解析次数、节点复用或快照损坏错误分类不符时测试失败
     */
    @Test
    void loadsSchemasFromOneValidatedRootPerSnapshot()
    {
        WorkflowFormTemplateValidator template = spy(new WorkflowFormTemplateValidator());
        ObjectMapper parser = spy((ObjectMapper) ReflectionTestUtils.getField(template, "objectMapper"));
        ReflectionTestUtils.setField(template, "objectMapper", parser);
        WorkflowProcessFormDetailProjection projection = new WorkflowProcessFormDetailProjection(
                artifactRepository, template,
                new WorkflowProcessVariableProjection(historyService, historicVariableMapper),
                mock(WorkflowAttachmentService.class));
        List<WfDeployForm> rows = formSnapshots();
        when(artifactRepository.selectForms(deploymentId)).thenReturn(rows);
        assertThat(projection.loadSchemas(deploymentId)).isNotNull();
        verify(template, times(rows.size())).validate(anyString());
        verify(parser, times(rows.size())).readTree(anyString());
        ArgumentCaptor<JsonNode> all = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<JsonNode> readable = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<JsonNode> attachments = ArgumentCaptor.forClass(JsonNode.class);
        verify(template, times(rows.size())).extractVariableNames(all.capture());
        verify(template, times(rows.size())).extractReadableVariableNames(readable.capture());
        verify(template, times(rows.size())).extractAttachmentVariableNames(attachments.capture());
        for (int index = 0; index < rows.size(); index++)
        {
            assertThat(all.getAllValues().get(index)).isSameAs(readable.getAllValues().get(index))
                    .isSameAs(attachments.getAllValues().get(index));
        }
        rows.get(0).setContent("{");
        assertThatThrownBy(() -> projection.loadSchemas(deploymentId))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(400);
                    assertThat(exception.getMessage()).isEqualTo("表单内容必须是合法且无重复键的 JSON 对象");
                });
    }

    private ProcessEngine processEngine;
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private TaskService taskService;
    private HistoryService historyService;
    private SqlSession mapperSession;
    private WorkflowHistoricVariableMapper historicVariableMapper;
    private WorkflowProcessAccessService processAccessService;
    private WorkflowDeploymentArtifactRepository artifactRepository;
    private WorkflowControlledLoopService controlledLoopService;
    private WorkflowMultiInstanceService multiInstanceService;
    private WorkflowTaskLifecycleService taskLifecycleService;
    private WorkflowManualUrgeService manualUrgeService;
    private WorkflowProcessDetailService service;
    private String deploymentId;

    /**
     * 创建 FULL 历史级别的真实内存引擎，部署测试流程并装配正式详情服务。
     *
     * @return void，无返回值；每个测试使用独立数据库和 Mapper 会话
     */
    @BeforeEach
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void setUp()
    {
        ObjectProvider<WorkflowAutomaticCopyService> copyProvider = mock(ObjectProvider.class);
        ObjectProvider<WorkflowNotificationService> notificationProvider = mock(ObjectProvider.class);
        ObjectProvider<WorkflowNotificationOutboxService> outboxProvider = mock(ObjectProvider.class);
        ObjectProvider<WorkflowProcessDefinitionQueryService> definitionProvider = mock(ObjectProvider.class);
        ObjectProvider<WorkflowStartVariableValidator> variableValidatorProvider = mock(ObjectProvider.class);
        when(copyProvider.getObject()).thenReturn(mock(WorkflowAutomaticCopyService.class));
        when(notificationProvider.getObject()).thenReturn(mock(WorkflowNotificationService.class));
        when(outboxProvider.getObject()).thenReturn(mock(WorkflowNotificationOutboxService.class));
        WorkflowStartVariableValidator startVariableValidator =
                new WorkflowStartVariableValidator(new WorkflowFormTemplateValidator());
        when(variableValidatorProvider.getObject()).thenReturn(startVariableValidator);
        WorkflowCallActivityStartListener callActivityStartListener =
                new WorkflowCallActivityStartListener(
                        definitionProvider, variableValidatorProvider);
        WorkflowProcessCompletionStatusListener completionStatusListener =
                new WorkflowProcessCompletionStatusListener(
                        copyProvider, notificationProvider, outboxProvider);

        ProcessEngineConfiguration engineConfiguration = ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration()
                .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
                .setHistory("full");
        engineConfiguration.setEventListeners(
                List.of(callActivityStartListener, completionStatusListener));
        processEngine = engineConfiguration.buildProcessEngine();
        repositoryService = processEngine.getRepositoryService();
        runtimeService = processEngine.getRuntimeService();
        taskService = processEngine.getTaskService();
        historyService = spy(processEngine.getHistoryService());

        Deployment deployment = repositoryService.createDeployment()
                .addString("detail-variable-loading.bpmn20.xml", BPMN)
                .deploy();
        deploymentId = deployment.getId();
        historicVariableMapper = spy(createHistoricVariableMapper(
                processEngine.getProcessEngineConfiguration().getDataSource()));

        WorkflowEngineOperations engineOperations = mock(WorkflowEngineOperations.class);
        when(engineOperations.read(any(Supplier.class))).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        processAccessService = mock(WorkflowProcessAccessService.class);
        artifactRepository = mock(WorkflowDeploymentArtifactRepository.class);
        when(artifactRepository.selectForms(deploymentId)).thenReturn(formSnapshots());
        WorkflowDeploymentService deploymentService = mock(WorkflowDeploymentService.class);
        when(deploymentService.getBpmnXml(anyString())).thenReturn(BPMN);
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new com.ruoyi.flowable.identity.WorkflowCurrentIdentity(
                        "approver", Set.of()));
        WorkflowProcessDefinitionQueryService definitionQueryService =
                new WorkflowProcessDefinitionQueryService(engineOperations, repositoryService,
                        identityResolver, processAccessService, deploymentService,
                        artifactRepository, mock(WorkflowParticipantRuleRuntimeService.class));
        when(definitionProvider.getObject()).thenReturn(definitionQueryService);
        multiInstanceService = mock(WorkflowMultiInstanceService.class);
        taskLifecycleService = mock(WorkflowTaskLifecycleService.class);
        when(taskLifecycleService.isTaskReturnAllowed(anyString())).thenReturn(false);
        when(taskLifecycleService.isApplicationResubmitAllowed(anyString())).thenReturn(false);
        manualUrgeService = mock(WorkflowManualUrgeService.class);
        controlledLoopService = mock(WorkflowControlledLoopService.class);
        when(controlledLoopService.buildStates(anyString(), anyString(), anyString(), any()))
                .thenReturn(List.of());
        // 变量存储解码使用独立直接协作者，仍共享同一个真实 Flowable 引擎与生产 Mapper。
        WorkflowProcessVariableProjection variableProjection =
                new WorkflowProcessVariableProjection(historyService, historicVariableMapper);
        WorkflowProcessFormDetailProjection formProjection =
                new WorkflowProcessFormDetailProjection(artifactRepository,
                        new WorkflowFormTemplateValidator(), variableProjection,
                        mock(WorkflowAttachmentService.class));
        WorkflowProcessHistoryProjection historyProjection =
                new WorkflowProcessHistoryProjection(historyService, taskService,
                        repositoryService, mock(ISysUserService.class));

        service = new WorkflowProcessDetailService(engineOperations, processAccessService,
                repositoryService, taskService, deploymentService, variableProjection,
                formProjection, historyProjection, multiInstanceService,
                taskLifecycleService, identityResolver, manualUrgeService,
                controlledLoopService);
    }

    /**
     * 关闭 Mapper 会话和真实 Flowable 引擎，避免测试间共享数据库或连接。
     *
     * @return void，无返回值
     */
    @AfterEach
    void tearDown()
    {
        if (mapperSession != null)
        {
            mapperSession.close();
        }
        if (processEngine != null)
        {
            processEngine.close();
        }
    }

    /**
     * 验证活动非局部表单只读取流程根变量，任务同名局部值不会覆盖返回结果。
     *
     * @return void，作用域、值投影或当前查询次数不符时测试失败
     */
    @Test
    void readsOnlyRootVariablesForActiveGlobalForm()
    {
        ProcessInstance instance = startMainProcess();
        Task task = activeTask(instance.getId(), "globalTask");
        runtimeService.setVariable(instance.getId(), "value", "root-current");
        taskService.setVariableLocal(task.getId(), "value", "wrong-task-local");

        WorkflowProcessDetailView detail = readDetail(instance.getId(), task.getId(), "running");

        assertThat(detail.formDetail().nodeTaskForm().taskLocal()).isFalse();
        assertThat(detail.formDetail().nodeTaskForm().values().get("value").textValue())
                .isEqualTo("root-current");
        assertThat(detail.formDetail().applicationForm().nodeKey()).isEqualTo("mainStart");
        assertThat(detail.formDetail().historyForms()).isEmpty();
        assertThat(detail.formDetail().returnedApplication()).isFalse();
        assertThat(detail.currentTask().unclaimAllowed()).isFalse();
        assertThat(detail.resubmitAllowed()).isFalse();
        verify(historyService).createHistoricVariableInstanceQuery();
        verify(historicVariableMapper).selectCurrentVariableMetadata(
                eq(instance.getId()), eq(task.getId()), eq(false), eq(List.of("value")), eq(2));
    }

    /**
     * 验证详情 currentTask 原样返回共享规则基于真实 claim 元数据生成的能力。
     *
     * @return void，真实认领任务未在详情中返回 unclaimAllowed=true 时测试失败
     */
    @Test
    void returnsUnclaimCapabilityOnCurrentTaskSnapshot()
    {
        ProcessInstance instance = startMainProcess();
        Task task = activeTask(instance.getId(), "globalTask");
        // 测试夹具先清除 BPMN 静态分配，再通过 Flowable 公共 claim 形成完整真实认领元数据。
        taskService.unclaim(task.getId());
        taskService.claim(task.getId(), "approver");

        WorkflowProcessDetailView detail = readDetail(
                instance.getId(), task.getId(), "running");

        assertThat(detail.currentTask().claimedBy()).isEqualTo("approver");
        assertThat(detail.currentTask().claimTime()).isNotNull();
        assertThat(detail.currentTask().unclaimAllowed()).isTrue();
    }

    /**
     * 验证普通本人待办、委派待办和非本人待办分别投影完整动作能力的 true/false 双向事实。
     *
     * @return void，页面仍需根据 assignee、owner 或 delegation 重建任一能力时测试失败
     */
    @Test
    void projectsAllTaskActionCapabilitiesFromAuthoritativeFacts()
    {
        ProcessInstance ownedInstance = startMainProcess();
        Task ownedTask = activeTask(ownedInstance.getId(), "globalTask");
        when(taskLifecycleService.isTaskReturnAllowed(ownedTask.getId())).thenReturn(true);

        WorkflowProcessDetailView owned = readDetail(
                ownedInstance.getId(), ownedTask.getId(), "running");
        assertThat(owned.completeAllowed()).isTrue();
        assertThat(owned.rejectAllowed()).isTrue();
        assertThat(owned.returnAllowed()).isTrue();
        assertThat(owned.delegateAllowed()).isTrue();
        assertThat(owned.transferAllowed()).isTrue();
        assertThat(owned.resolveAllowed()).isFalse();

        ProcessInstance delegatedInstance = startMainProcess();
        Task delegatedTask = activeTask(delegatedInstance.getId(), "globalTask");
        taskService.delegateTask(delegatedTask.getId(), "approver");
        WorkflowProcessDetailView delegated = readDetail(
                delegatedInstance.getId(), delegatedTask.getId(), "running");
        assertThat(delegated.completeAllowed()).isFalse();
        assertThat(delegated.rejectAllowed()).isFalse();
        assertThat(delegated.returnAllowed()).isFalse();
        assertThat(delegated.delegateAllowed()).isFalse();
        assertThat(delegated.transferAllowed()).isFalse();
        assertThat(delegated.resolveAllowed()).isTrue();

        ProcessInstance foreignInstance = startMainProcess();
        Task foreignTask = activeTask(foreignInstance.getId(), "globalTask");
        taskService.setAssignee(foreignTask.getId(), "other-user");
        WorkflowProcessDetailView foreign = readDetail(
                foreignInstance.getId(), foreignTask.getId(), "running");
        assertThat(foreign.completeAllowed()).isFalse();
        assertThat(foreign.rejectAllowed()).isFalse();
        assertThat(foreign.returnAllowed()).isFalse();
        assertThat(foreign.delegateAllowed()).isFalse();
        assertThat(foreign.transferAllowed()).isFalse();
        assertThat(foreign.resolveAllowed()).isFalse();
    }

    /**
     * 验证详情完成对象授权和根关系核验后，直接投影人工催办正式服务的 true 能力。
     *
     * @return void，运行中根流程未调用正式能力服务或返回值被改写时测试失败
     */
    @Test
    void projectsManualUrgeCapabilityForAuthorizedRunningRoot()
    {
        ProcessInstance instance = startMainProcess();
        when(manualUrgeService.isUrgeAllowed(instance.getId())).thenReturn(true);

        WorkflowProcessDetailView detail = readDetail(instance.getId(), null, "running");

        assertThat(detail.urgeAllowed()).isTrue();
        verify(manualUrgeService).isUrgeAllowed(instance.getId());
    }

    /**
     * 验证挂起和已结束根流程固定投影 false，且不进入人工催办数据库准备链。
     *
     * @return void，任一不可执行状态调用正式能力服务或返回 true 时测试失败
     */
    @Test
    void keepsSuspendedAndCompletedProcessUrgeCapabilityFalse()
    {
        ProcessInstance suspended = startMainProcess();
        runtimeService.suspendProcessInstanceById(suspended.getId());
        WorkflowProcessDetailView suspendedDetail = readDetail(
                suspended.getId(), null, "running");

        String terminalSnapshot = WorkflowFormSubmissionSnapshotCodec.encodeStart(
                deploymentId, "TEMPLATE", 7L, "terminal-start-form", "terminalStart", Map.of());
        ProcessInstance completed = runtimeService.createProcessInstanceBuilder()
                .processDefinitionKey("detailTerminal")
                .businessStatus("running")
                .variables(Map.of(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE, "running",
                        WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME, terminalSnapshot))
                .start();
        WorkflowProcessDetailView completedDetail = readDetail(
                completed.getId(), null, "completed");

        assertThat(suspendedDetail.processStatus()).isEqualTo("suspended");
        assertThat(suspendedDetail.urgeAllowed()).isFalse();
        assertThat(completedDetail.processStatus()).isEqualTo("completed");
        assertThat(completedDetail.urgeAllowed()).isFalse();
        verify(manualUrgeService, never()).isUrgeAllowed(anyString());
    }

    /**
     * 验证 CallActivity 子流程详情固定投影 false，根流程身份不能由子流程页面代替。
     *
     * @return void，子流程触发正式能力查询或返回 true 时测试失败
     */
    @Test
    void keepsCallActivityChildUrgeCapabilityFalse()
    {
        ProcessInstance root = startCallActivityParent();
        ProcessInstance child = runtimeService.createProcessInstanceQuery()
                .superProcessInstanceId(root.getId()).singleResult();
        assertThat(child).isNotNull();
        assertThat(child.getBusinessStatus()).isEqualTo("running");
        assertThat(runtimeService.getVariable(child.getId(),
                WorkflowProcessStartService.PROCESS_STATUS_VARIABLE)).isEqualTo("running");
        WorkflowFormSubmissionSnapshotCodec.SubmissionSnapshot childSnapshot =
                WorkflowFormSubmissionSnapshotCodec.decode((String) runtimeService.getVariable(
                        child.getId(), WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME));
        assertThat(childSnapshot.nodeKey()).isEqualTo("childStart");
        assertThat(childSnapshot.values().get("childRequest").textValue())
                .isEqualTo("采购申请-2026");

        WorkflowProcessDetailView detail = readDetail(child.getId(), null, "running");

        assertThat(detail.urgeAllowed()).isFalse();
        verify(manualUrgeService, never()).isUrgeAllowed(anyString());
    }

    /**
     * 验证 CallActivity 子流程自然完成时自身双状态收敛为 completed，父流程完成不替代子事实。
     *
     * @return void，子流程历史仍为 running、缺少状态变量或开始快照时测试失败
     */
    @Test
    void completesCallActivityChildWithItsOwnFormalLifecycle()
    {
        ProcessInstance root = startCallActivityParent();
        ProcessInstance child = runtimeService.createProcessInstanceQuery()
                .superProcessInstanceId(root.getId()).singleResult();
        assertThat(child).isNotNull();

        taskService.complete(activeTask(child.getId(), "childTask").getId());

        HistoricProcessInstance completedChild = historyService
                .createHistoricProcessInstanceQuery().processInstanceId(child.getId()).singleResult();
        assertThat(completedChild.getEndTime()).isNotNull();
        assertThat(completedChild.getBusinessStatus()).isEqualTo("completed");
        assertThat(historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(child.getId())
                .variableName(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE)
                .singleResult().getValue()).isEqualTo("completed");
        assertThat(historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(child.getId())
                .variableName(WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME)
                .singleResult()).isNotNull();
    }

    /**
     * 验证 CallActivity 缺失或类型非法的必填输入映射会回滚父子创建命令且不留下任务与历史。
     *
     * @return void，任一失败启动留下运行实例、子任务、历史实例或历史任务时测试失败
     */
    @Test
    void rollsBackCallActivityWhenRequiredInputMappingIsMissingOrInvalid()
    {
        assertThatThrownBy(() -> startRejectedCallActivityParent(
                "detailMissingParent", Map.of()))
                .hasStackTraceContaining("开始表单必填字段不能为空: childRequest");
        assertNoCallActivityStartSideEffects("detailMissingParent");

        assertThatThrownBy(() -> startRejectedCallActivityParent(
                "detailInvalidParent", Map.of("invalidParentRequest", 42)))
                .hasStackTraceContaining("流程变量类型不合法: childRequest");
        assertNoCallActivityStartSideEffects("detailInvalidParent");
    }

    /**
     * 验证实例对象授权失败时不会提前计算催办能力。
     *
     * @return void，未授权请求触发人工催办准备链或异常被吞掉时测试失败
     */
    @Test
    void authorizesProcessBeforeCalculatingManualUrgeCapability()
    {
        ServiceException forbidden = new ServiceException("无权查看流程", 403);
        when(processAccessService.requireReadableInstance("forbidden-instance"))
                .thenThrow(forbidden);

        assertThatThrownBy(() -> service.getDetail(
                new WorkflowProcessDetailQueryDto("forbidden-instance", null)))
                .isSameAs(forbidden);
        verify(manualUrgeService, never()).isUrgeAllowed(anyString());
    }

    /**
     * 验证活动 task-local 表单只读取当前 taskId，流程根值和已完成任务同名局部值均被隔离。
     *
     * @return void，任务作用域或当前值投影不符时测试失败
     */
    @Test
    void readsOnlyCurrentTaskVariablesForActiveLocalForm()
    {
        ProcessInstance instance = startMainProcess();
        Task completed = activeTask(instance.getId(), "globalTask");
        taskService.setVariableLocal(completed.getId(), "value", "wrong-completed-local");
        completeWithSnapshot(completed, 2L, "global-form", "globalTask", false,
                Map.of("value", "global-submitted"));
        Task active = activeTask(instance.getId(), "localTask");
        runtimeService.setVariable(instance.getId(), "value", "wrong-root");
        taskService.setVariableLocal(active.getId(), "value", "current-local");

        WorkflowProcessDetailView detail = readDetail(instance.getId(), active.getId(), "running");

        assertThat(detail.formDetail().nodeTaskForm().taskLocal()).isTrue();
        assertThat(detail.formDetail().nodeTaskForm().values().get("value").textValue())
                .isEqualTo("current-local");
        assertThat(detail.formDetail().applicationForm().nodeKey()).isEqualTo("mainStart");
        assertThat(detail.formDetail().historyForms())
                .extracting(WorkflowProcessFormSnapshotView::taskId)
                .containsExactly(completed.getId());
        verify(historyService).createHistoricVariableInstanceQuery();
        verify(historicVariableMapper).selectCurrentVariableMetadata(
                eq(instance.getId()), eq(active.getId()), eq(true), eq(List.of("value")), eq(2));
    }

    /**
     * 验证已完成任务只投影完成时正式提交快照，不查询后来变化的流程当前变量。
     *
     * @return void，历史值被当前值污染或发生当前变量查询时测试失败
     */
    @Test
    void completedTaskUsesSubmissionSnapshotWithoutCurrentVariableQuery()
    {
        ProcessInstance instance = startMainProcess();
        Task completed = activeTask(instance.getId(), "globalTask");
        runtimeService.setVariable(instance.getId(), "value", "submitted-value");
        completeWithSnapshot(completed, 2L, "global-form", "globalTask", false,
                Map.of("value", "submitted-value"));
        runtimeService.setVariable(instance.getId(), "value", "later-current-value");

        WorkflowProcessDetailView detail = readDetail(
                instance.getId(), completed.getId(), "running");

        assertThat(detail.formDetail().nodeTaskForm().snapshotTime()).isNotNull();
        assertThat(detail.formDetail().nodeTaskForm().values().get("value").textValue())
                .isEqualTo("submitted-value");
        assertThat(detail.formDetail().historyForms()).isEmpty();
        assertThat(detail.resubmitAllowed()).isFalse();
        verifyNoCurrentVariableRead();
    }

    /**
     * 验证退回修改继续继承开始提交快照，且不会为随后活动任务加载当前变量。
     *
     * @return void，退回表单来源或查询边界改变时测试失败
     */
    @Test
    void returnedStartFormKeepsSubmissionSnapshotWithoutCurrentVariableQuery()
    {
        ProcessInstance instance = startMainProcess();
        Task active = activeTask(instance.getId(), "globalTask");
        runtimeService.setVariable(instance.getId(), "startValue", "wrong-current-start");

        WorkflowProcessDetailView detail = readDetail(
                instance.getId(), active.getId(), "returned");

        assertThat(detail.formDetail().applicationForm().nodeKey()).isEqualTo("mainStart");
        assertThat(detail.formDetail().applicationForm().taskId()).isEqualTo(active.getId());
        assertThat(detail.formDetail().applicationForm().snapshotTime()).isNull();
        assertThat(detail.formDetail().applicationForm().values().get("startValue").textValue())
                .isEqualTo("start-submitted");
        assertThat(detail.formDetail().nodeTaskForm()).isNull();
        assertThat(detail.formDetail().historyForms()).isEmpty();
        assertThat(detail.formDetail().returnedApplication()).isTrue();
        assertThat(detail.resubmitAllowed()).isFalse();
        // 没有申请人局部标记的 returned 任务仍按原路径解析正式多实例 capability。
        verify(multiInstanceService).getOptionalState(active.getId());
        verify(taskLifecycleService, never())
                .isApplicationResubmitAllowed(anyString());
        verifyNoCurrentVariableRead();
    }

    /**
     * 验证 returned 阶段带申请人局部标记的唯一待修改任务不会读取正式多实例轮次状态。
     *
     * @return void，临时申请人任务被误投影为 ACTIVE 多实例轮次时测试失败
     */
    @Test
    void returnedApplicantTaskSkipsFormalMultiInstanceProjection()
    {
        String applicantUserId = "applicant";
        processEngine.getIdentityService().setAuthenticatedUserId(applicantUserId);
        ProcessInstance instance;
        try
        {
            instance = startMainProcess();
        }
        finally
        {
            processEngine.getIdentityService().setAuthenticatedUserId(null);
        }
        Task active = activeTask(instance.getId(), "globalTask");
        taskService.setAssignee(active.getId(), applicantUserId);
        taskService.setVariableLocal(active.getId(),
                "__ruoyi_workflow_return_applicant", applicantUserId);
        when(taskLifecycleService.isApplicationResubmitAllowed(active.getId()))
                .thenReturn(true);

        WorkflowProcessDetailView detail = readDetail(
                instance.getId(), active.getId(), "returned");

        assertThat(detail.multiInstanceState()).isNull();
        assertThat(detail.resubmitAllowed()).isTrue();
        assertThat(detail.formDetail().returnedApplication()).isTrue();
        assertThat(detail.formDetail().applicationForm().taskId()).isEqualTo(active.getId());
        assertThat(detail.formDetail().nodeTaskForm()).isNull();
        verify(multiInstanceService, never()).getOptionalState(anyString());
        verify(taskLifecycleService).isApplicationResubmitAllowed(active.getId());
    }

    /**
     * 验证没有正式开始提交快照时按数据一致性异常拒绝详情。
     *
     * @return void，缺失强制快照被伪装为正常详情时测试失败
     */
    @Test
    void rejectsMissingApplicationSnapshot()
    {
        ProcessInstance instance = startMainProcessWithoutApplicationSnapshot();
        Task active = activeTask(instance.getId(), "globalTask");
        runtimeService.setVariable(instance.getId(), "value", "current-without-start");

        assertThatThrownBy(() -> readDetail(
                instance.getId(), active.getId(), "running"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("提交快照");
    }

    /**
     * 验证同一实例出现两个版本关系矛盾的开始提交快照时继续按数据损坏失败关闭。
     *
     * @return void，多个正式开始快照被静默选择或降级时测试失败
     */
    @Test
    void rejectsMultipleContradictoryApplicationSnapshots()
    {
        ProcessInstance instance = startMainProcess();
        Task active = activeTask(instance.getId(), "globalTask");
        String contradictory = WorkflowFormSubmissionSnapshotCodec.encodeStart(
                deploymentId, "TEMPLATE", 1L, "start-form", "differentStart",
                Map.of("startValue", "second-start"));
        runtimeService.setVariable(instance.getId(),
                WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME, contradictory);

        assertThatThrownBy(() -> readDetail(
                instance.getId(), active.getId(), "running"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(500);
                    assertThat(exception.getMessage())
                            .isEqualTo("流程开始表单提交快照版本关联不一致");
                });
    }

    /**
     * 验证退回申请态缺少正式开始提交快照时拒绝生成可编辑申请表单。
     *
     * @return void，退回态缺失开始快照仍返回详情时测试失败
     */
    @Test
    void rejectsReturnedApplicationWithoutApplicationSnapshot()
    {
        ProcessInstance instance = startMainProcessWithoutApplicationSnapshot();
        Task active = activeTask(instance.getId(), "globalTask");

        assertThatThrownBy(() -> readDetail(
                instance.getId(), active.getId(), "returned"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(500);
                    assertThat(exception.getMessage()).isEqualTo("流程缺少正式表单提交快照");
                });
    }

    /**
     * 验证新增表单语义只重组同一次投影结果，不增加部署、历史、快照 Mapper 或当前变量查询。
     *
     * @return void，任一既有读取边界被重复调用时测试失败
     */
    @Test
    void reusesExistingProjectionWithoutAdditionalFormOrVariableQueries()
    {
        ProcessInstance instance = startMainProcess();
        Task active = activeTask(instance.getId(), "globalTask");
        runtimeService.setVariable(instance.getId(), "value", "counted-current");

        WorkflowProcessDetailView detail = readDetail(
                instance.getId(), active.getId(), "running");

        assertThat(detail.formDetail().nodeTaskForm()).isNotNull();
        verify(artifactRepository, times(1)).selectForms(deploymentId);
        verify(historyService, times(1)).createHistoricActivityInstanceQuery();
        verify(historyService, times(1)).createHistoricTaskInstanceQuery();
        verify(historyService, times(1)).createHistoricVariableInstanceQuery();
        verify(historicVariableMapper, times(1)).selectSubmissionMetadata(
                eq(instance.getId()), eq(WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME),
                eq(WorkflowProcessVariableProjection.MAX_VARIABLE_UPDATE_ROWS + 1));
        verify(historicVariableMapper, times(1)).selectSubmissionBodies(
                eq(instance.getId()), eq(WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME),
                anyList());
        verify(historicVariableMapper, times(1)).selectCurrentVariableMetadata(
                eq(instance.getId()), eq(active.getId()), eq(false),
                eq(List.of("value")), eq(2));
        verify(historicVariableMapper, never()).selectCurrentVariableBodies(
                anyString(), anyString(), anyBoolean(), anyList(), anyList());
    }

    /**
     * 验证临时申请人任务残留候选关系时详情 capability 为 false，且不调用正式重提准备链。
     *
     * @return void，候选关系漂移误报能力或触发正式能力查询时测试失败
     */
    @Test
    void returnedApplicantTaskDisallowsCandidateIdentityDrift()
    {
        String applicantUserId = "applicant";
        processEngine.getIdentityService().setAuthenticatedUserId(applicantUserId);
        ProcessInstance instance;
        try
        {
            instance = startMainProcess();
        }
        finally
        {
            processEngine.getIdentityService().setAuthenticatedUserId(null);
        }
        Task active = activeTask(instance.getId(), "globalTask");
        taskService.setAssignee(active.getId(), applicantUserId);
        taskService.setVariableLocal(active.getId(),
                "__ruoyi_workflow_return_applicant", applicantUserId);
        taskService.addCandidateUser(active.getId(), "stale-candidate");

        WorkflowProcessDetailView detail = readDetail(
                instance.getId(), active.getId(), "returned");

        assertThat(detail.resubmitAllowed()).isFalse();
        verify(taskLifecycleService, never())
                .isApplicationResubmitAllowed(anyString());
    }

    /**
     * 验证受控循环新一轮仍从上一轮正式 task-local 提交继承缺失字段，当前查询只绑定新任务。
     *
     * @return void，循环继承或当前任务隔离行为改变时测试失败
     */
    @Test
    void controlledLoopKeepsPreviousSubmissionInheritance()
    {
        ProcessInstance instance = startLoopProcess();
        Task previous = activeTask(instance.getId(), "loopTask");
        runtimeService.setVariable(instance.getId(), "repeat", true);
        completeWithSnapshot(previous, 5L, "loop-form", "loopTask", true,
                Map.of("decision", "redo"));
        Task active = activeTask(instance.getId(), "loopTask");
        when(controlledLoopService.buildStates(
                deploymentId, "detailLoop", instance.getId(), "loopTask"))
                .thenReturn(List.of(new WorkflowControlledLoopStateView(
                        "loopTask", "循环审批", "decision", "redo", "approved",
                        3, 1, 2, true, List.of())));

        WorkflowProcessDetailView detail = readDetail(instance.getId(), active.getId(), "running");

        assertThat(detail.formDetail().nodeTaskForm().taskLocal()).isTrue();
        assertThat(detail.formDetail().nodeTaskForm().values().get("decision").textValue())
                .isEqualTo("redo");
        verify(historyService).createHistoricVariableInstanceQuery();
        verify(historicVariableMapper, never()).selectCurrentVariableMetadata(
                anyString(), anyString(), eq(true), anyList(), anyInt());
    }

    /**
     * 验证无任务详情和活动无表单详情均不会创建当前历史变量查询。
     *
     * @return void，任一无效入口触发当前变量读取时测试失败
     */
    @Test
    void noTaskAndNoFormDetailsDoNotQueryCurrentVariables()
    {
        ProcessInstance withoutTaskRequest = startMainProcess();
        WorkflowProcessDetailView noTaskDetail = readDetail(
                withoutTaskRequest.getId(), null, "running");
        assertThat(noTaskDetail.resubmitAllowed()).isFalse();
        verifyNoCurrentVariableRead();

        ProcessInstance withoutForm = startMainProcess();
        Task global = activeTask(withoutForm.getId(), "globalTask");
        completeWithSnapshot(global, 2L, "global-form", "globalTask", false,
                Map.of("value", "global-submitted"));
        Task local = activeTask(withoutForm.getId(), "localTask");
        completeWithSnapshot(local, 3L, "local-form", "localTask", true,
                Map.of("value", "local-submitted"));
        Task noForm = activeTask(withoutForm.getId(), "noFormTask");
        clearReadInvocations();

        WorkflowProcessDetailView detail = readDetailWithoutClearing(
                withoutForm.getId(), noForm.getId(), "running");

        assertThat(detail.formDetail().nodeTaskForm()).isNull();
        verifyNoCurrentVariableRead();
    }

    /**
     * 验证详情只投影规范化意见，保持排序、类型过滤、Viewer 判断和原始审计存储不变。
     *
     * @return void，原始审计泄露、兼容意见变化、顺序变化或读取产生写副作用时测试失败
     */
    @Test
    void projectsSafeOpinionsWithoutChangingStoredAuditComments()
    {
        ProcessInstance instance = startMainProcess();
        Task task = activeTask(instance.getId(), "globalTask");
        Instant baseTime = Instant.now().plusSeconds(60);

        processEngine.getProcessEngineConfiguration().getClock()
                .setCurrentTime(Date.from(baseTime.plusSeconds(40)));
        String structuredAudit = "{\"action\":\"COMPLETE\","
                + "\"actorUserId\":\"internal-actor\","
                + "\"targetUserId\":\"internal-target\","
                + "\"beforeRevision\":7,\"afterRevision\":8,"
                + "\"opinion\":\"  结构化意见\\n  \"}";
        Comment structured = taskService.addComment(task.getId(), instance.getId(),
                "COMPLETE", structuredAudit);

        processEngine.getProcessEngineConfiguration().getClock()
                .setCurrentTime(Date.from(baseTime.plusSeconds(30)));
        String actionWithoutOpinion = "{\"action\":\"RETURN\","
                + "\"actorUserId\":\"hidden-returner\","
                + "\"targetNodeKey\":\"hidden-target\",\"revision\":9,"
                + "\"opinion\":null}";
        Comment systemAudit = taskService.addComment(task.getId(), instance.getId(),
                "RETURN", actionWithoutOpinion);

        processEngine.getProcessEngineConfiguration().getClock()
                .setCurrentTime(Date.from(baseTime.plusSeconds(50)));
        Comment filtered = taskService.addComment(task.getId(), instance.getId(),
                "internal", "x".repeat(WorkflowProcessHistoryProjection.MAX_COMMENT_BYTES + 1));
        Map<String, String> storedBefore = commentBodies(instance.getId());

        WorkflowProcessDetailView detail = readDetail(
                instance.getId(), task.getId(), "returned");
        WorkflowProcessActivityView taskActivity = detail.historyProcNodeList().stream()
                .filter(activity -> task.getId().equals(activity.taskId()))
                .findFirst().orElseThrow();
        List<WorkflowProcessCommentView> comments = taskActivity.comments();

        assertThat(comments).extracting(WorkflowProcessCommentView::commentId)
                .containsExactly(systemAudit.getId(), structured.getId());
        Map<String, WorkflowProcessCommentView> commentsById = new LinkedHashMap<>();
        comments.forEach(comment -> commentsById.put(comment.commentId(), comment));
        assertThat(commentsById.get(structured.getId()).opinion()).isEqualTo("结构化意见");
        assertThat(commentsById.get(systemAudit.getId()).opinion()).isNull();
        assertThat(commentsById).doesNotContainKey(filtered.getId());
        assertThat(detail.flowViewer().returnedActivityIds()).contains("globalTask");

        JsonNode structuredJson = JsonMapper.shared().valueToTree(
                commentsById.get(structured.getId()));
        assertThat(structuredJson.propertyNames()).containsExactlyInAnyOrder(
                "commentId", "taskId", "type", "typeName", "opinion", "userId", "time");
        assertThat(structuredJson.toString()).doesNotContain(
                "internal-actor", "internal-target", "beforeRevision", "afterRevision",
                "message", "audit");
        JsonNode systemJson = JsonMapper.shared().valueToTree(
                commentsById.get(systemAudit.getId()));
        assertThat(systemJson.path("opinion").isNull()).isTrue();
        assertThat(systemJson.toString()).doesNotContain(
                "hidden-returner", "hidden-target", "revision", "action");

        assertThat(commentBodies(instance.getId())).isEqualTo(storedBefore);
    }

    @Test
    void acceptsFormalProcessTerminationAuditWithoutTreatingReasonAsTaskOpinion()
    {
        for (String action : List.of("CANCEL", "TERMINATE"))
        {
            ProcessInstance instance = startMainProcess();
            Task task = activeTask(instance.getId(), "globalTask");
            String audit = JsonMapper.shared().createObjectNode().put("action", action)
                    .put("actorUserId", "100").put("processStatus", "CANCEL".equals(action) ? "canceled" : "terminated")
                    .put("reason", "正式流程结束原因").put("wasSuspended", false)
                    .put("requestedInstanceId", instance.getId()).put("rootInstanceId", instance.getId())
                    .put("processTreeInstanceCount", 1).toString();
            taskService.addComment(null, instance.getId(), action, audit);
            Map<String, String> before = commentBodies(instance.getId());
            var detail = readDetail(instance.getId(), task.getId(), "running");
            assertThat(detail.historyProcNodeList()).allSatisfy(node -> assertThat(node.comments()).isEmpty());
            assertThat(commentBodies(instance.getId())).isEqualTo(before);
            // 相同 JSON 冒充任务审批意见时仍拒绝，不放宽 task opinion 正式协议。
            taskService.addComment(task.getId(), instance.getId(), action, audit);
            assertThatThrownBy(() -> readDetail(instance.getId(), task.getId(), "running"))
                    .hasMessage("流程审批意见结构化审计字段不完整");
        }
    }

    /**
     * 验证旧数字类型、纯文本正文和畸形 JSON 均不能进入正式历史投影。
     *
     * @return void，任一旧 comment 协议仍被容错展示时测试失败
     */
    @Test
    void rejectsLegacyAndMalformedComments()
    {
        List<List<String>> invalidComments = List.of(
                List.of("1", "{\"action\":\"COMPLETE\",\"actorUserId\":\"100\"}",
                        "流程存在旧数字意见类型，请先完成数据迁移"),
                List.of("2", "{\"action\":\"RETURN\",\"actorUserId\":\"100\",\"opinion\":\"旧类型\"}",
                        "流程存在旧数字意见类型，请先完成数据迁移"),
                List.of("COMPLETE", "旧版纯文本意见", "流程审批意见不是合法结构化审计"),
                List.of("RETURN", "{畸形正文", "流程审批意见不是合法结构化审计"));
        for (List<String> invalid : invalidComments)
        {
            ProcessInstance instance = startMainProcess();
            Task task = activeTask(instance.getId(), "globalTask");
            taskService.addComment(task.getId(), instance.getId(),
                    invalid.get(0), invalid.get(1));
            assertThatThrownBy(() -> readDetail(
                    instance.getId(), task.getId(), "running"))
                    .hasMessage(invalid.get(2))
                    .isInstanceOfSatisfying(ServiceException.class,
                            exception -> assertThat(exception.getCode()).isEqualTo(500));
        }
    }

    /**
     * 验证详情仍按 Flowable 原始正文执行单条 8 KiB 与累计 512 KiB 精确门禁。
     *
     * @return void，超大单条被放行、累计边界变化或门禁改为只计算 opinion 时测试失败
     */
    @Test
    void retainsRawCommentBodyCapacityGates()
    {
        ProcessInstance oversizedInstance = startMainProcess();
        Task oversizedTask = activeTask(oversizedInstance.getId(), "globalTask");
        taskService.addComment(oversizedTask.getId(), oversizedInstance.getId(), "COMPLETE",
                structuredCommentBody(WorkflowProcessHistoryProjection.MAX_COMMENT_BYTES + 1));

        assertThatThrownBy(() -> readDetail(oversizedInstance.getId(),
                oversizedTask.getId(), "running"))
                .isInstanceOf(ServiceException.class)
                .hasMessage("单条流程审批意见超过安全上限");

        ProcessInstance cumulativeInstance = startMainProcess();
        Task cumulativeTask = activeTask(cumulativeInstance.getId(), "globalTask");
        String fullSizeBody = structuredCommentBody(
                WorkflowProcessHistoryProjection.MAX_COMMENT_BYTES);
        int exactLimitCount = WorkflowProcessHistoryProjection.MAX_TOTAL_COMMENT_BYTES
                / WorkflowProcessHistoryProjection.MAX_COMMENT_BYTES;
        for (int index = 0; index < exactLimitCount; index++)
        {
            taskService.addComment(cumulativeTask.getId(), cumulativeInstance.getId(),
                    "COMPLETE", fullSizeBody);
        }

        WorkflowProcessDetailView exactLimitDetail = readDetail(cumulativeInstance.getId(),
                cumulativeTask.getId(), "running");
        WorkflowProcessActivityView cumulativeActivity = exactLimitDetail.historyProcNodeList()
                .stream().filter(activity -> cumulativeTask.getId().equals(activity.taskId()))
                .findFirst().orElseThrow();
        assertThat(cumulativeActivity.comments()).hasSize(exactLimitCount);

        taskService.addComment(cumulativeTask.getId(), cumulativeInstance.getId(),
                "COMPLETE", "z");
        assertThatThrownBy(() -> readDetail(cumulativeInstance.getId(),
                cumulativeTask.getId(), "running"))
                .isInstanceOf(ServiceException.class)
                .hasMessage("流程审批意见正文总量超过安全上限");
    }

    /**
     * 使用 Flowable 数据源和正式 XML 映射创建独立 MyBatis Mapper。
     *
     * @param dataSource DataSource，当前真实内存 Flowable 引擎的数据源
     * @return WorkflowHistoricVariableMapper，直接读取 ACT_HI_DETAIL/ACT_HI_VARINST 的 Mapper
     */
    private WorkflowHistoricVariableMapper createHistoricVariableMapper(DataSource dataSource)
    {
        Environment environment = new Environment("flowable-detail-it",
                new JdbcTransactionFactory(), dataSource);
        org.apache.ibatis.session.Configuration configuration =
                new org.apache.ibatis.session.Configuration(environment);
        configuration.addMapper(WorkflowHistoricVariableMapper.class);
        String resource = "mapper/flowable/WorkflowHistoricVariableMapper.xml";
        try (InputStream input = WorkflowProcessDetailServiceIntegrationTest.class
                .getClassLoader().getResourceAsStream(resource))
        {
            if (input == null)
            {
                throw new IllegalStateException("测试无法加载正式历史变量 Mapper");
            }
            new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
        catch (IOException | RuntimeException exception)
        {
            throw new IllegalStateException("测试读取或关闭正式历史变量 Mapper 失败", exception);
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        mapperSession = factory.openSession(true);
        return mapperSession.getMapper(WorkflowHistoricVariableMapper.class);
    }

    /**
     * 读取 Flowable 当前保存的全部原始 comment 正文并按主键建立快照。
     *
     * @param processInstanceId String，待核对的真实流程实例主键
     * @return Map&lt;String,String&gt;，comment 主键到原始 fullMessage 的稳定映射
     */
    private Map<String, String> commentBodies(String processInstanceId)
    {
        Map<String, String> bodies = new LinkedHashMap<>();
        for (Comment comment : taskService.getProcessInstanceComments(processInstanceId))
        {
            bodies.put(comment.getId(), comment.getFullMessage());
        }
        return bodies;
    }

    /**
     * 构造指定 UTF-8 总字节数、但只含短 opinion 的结构化原始审计正文。
     *
     * @param totalBytes int，期望的完整 JSON UTF-8 字节数
     * @return String，使用 ASCII padding 精确补足后的结构化 comment 正文
     */
    private String structuredCommentBody(int totalBytes)
    {
        String prefix = "{\"action\":\"COMPLETE\",\"actorUserId\":\"100\","
                + "\"opinion\":\"ok\",\"padding\":\"";
        String suffix = "\"}";
        int paddingBytes = totalBytes - prefix.getBytes(StandardCharsets.UTF_8).length
                - suffix.getBytes(StandardCharsets.UTF_8).length;
        if (paddingBytes < 0)
        {
            throw new IllegalArgumentException("结构化意见目标字节数过小");
        }
        return prefix + "x".repeat(paddingBytes) + suffix;
    }

    /**
     * 启动主流程，并把开始表单正式提交快照作为真实 Flowable 变量原子写入。
     *
     * @return ProcessInstance，停留在 globalTask 的活动实例
     */
    private ProcessInstance startMainProcess()
    {
        String snapshot = WorkflowFormSubmissionSnapshotCodec.encodeStart(
                deploymentId, "TEMPLATE", 1L, "start-form", "mainStart",
                Map.of("startValue", "start-submitted"));
        return runtimeService.createProcessInstanceBuilder()
                .processDefinitionKey("detailMain")
                .businessStatus("running")
                .variables(Map.of("startValue", "start-current",
                        WorkflowProcessStartService.PROCESS_STATUS_VARIABLE, "running",
                        WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME, snapshot))
                .start();
    }

    /**
     * 启动不含正式开始提交快照的升级前主流程，用于验证明确缺失和退回失败关闭语义。
     *
     * @return ProcessInstance，停留在 globalTask 且没有内部开始提交变量的真实实例
     */
    private ProcessInstance startMainProcessWithoutApplicationSnapshot()
    {
        return runtimeService.createProcessInstanceBuilder()
                .processDefinitionKey("detailMain")
                .businessStatus("running")
                .variables(Map.of("startValue", "snapshot-missing-current",
                        WorkflowProcessStartService.PROCESS_STATUS_VARIABLE, "running"))
                .start();
    }

    /**
     * 启动受控循环流程，并写入该流程自己的开始提交快照。
     *
     * @return ProcessInstance，停留在第一轮 loopTask 的活动实例
     */
    private ProcessInstance startLoopProcess()
    {
        String snapshot = WorkflowFormSubmissionSnapshotCodec.encodeStart(
                deploymentId, "TEMPLATE", 4L, "loop-start-form", "loopStart",
                Map.of("startValue", "loop-start-submitted"));
        return runtimeService.createProcessInstanceBuilder()
                .processDefinitionKey("detailLoop")
                .businessStatus("running")
                .variables(Map.of(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE, "running",
                        WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME, snapshot))
                .start();
    }

    /**
     * 通过真实 CallActivity 启动父子流程，根实例只携带自己的正式状态和开始提交快照。
     *
     * @return ProcessInstance，停留在 childTask 且已由全局生命周期监听器初始化子流程的根实例
     */
    private ProcessInstance startCallActivityParent()
    {
        String parentValue = "采购申请-2026";
        String parentSnapshot = WorkflowFormSubmissionSnapshotCodec.encodeStart(
                deploymentId, "TEMPLATE", 8L, "parent-start-form", "parentStart",
                Map.of("parentRequest", parentValue));
        return runtimeService.createProcessInstanceBuilder()
                .processDefinitionKey("detailParent")
                .businessStatus("running")
                .variables(Map.of(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE, "running",
                        WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME, parentSnapshot,
                        "parentRequest", parentValue))
                .start();
    }

    /**
     * 启动预期被子流程正式开始表单门禁拒绝的 CallActivity 父流程。
     *
     * @param processDefinitionKey String，缺失值或非法值测试父流程标识
     * @param parentVariables Map&lt;String,Object&gt;，Flowable 输入映射读取的父流程变量
     * @return ProcessInstance，只有门禁错误未回滚时才可能返回的父实例
     */
    private ProcessInstance startRejectedCallActivityParent(String processDefinitionKey,
            Map<String, Object> parentVariables)
    {
        Map<String, Object> variables = new LinkedHashMap<>(parentVariables);
        variables.put(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE, "running");
        return runtimeService.createProcessInstanceBuilder()
                .processDefinitionKey(processDefinitionKey)
                .businessStatus("running")
                .variables(variables)
                .start();
    }

    /**
     * 核对失败的 CallActivity 启动没有提交父子运行态、任务或历史半成品。
     *
     * @param parentProcessKey String，本次预期失败的父流程定义 key
     * @return void，任一父子副作用存在时断言失败
     */
    private void assertNoCallActivityStartSideEffects(String parentProcessKey)
    {
        assertThat(runtimeService.createProcessInstanceQuery()
                .processDefinitionKey(parentProcessKey).count()).isZero();
        assertThat(runtimeService.createProcessInstanceQuery()
                .processDefinitionKey("detailChild").count()).isZero();
        assertThat(taskService.createTaskQuery()
                .processDefinitionKey("detailChild").count()).isZero();
        assertThat(historyService.createHistoricProcessInstanceQuery()
                .processDefinitionKey(parentProcessKey).count()).isZero();
        assertThat(historyService.createHistoricProcessInstanceQuery()
                .processDefinitionKey("detailChild").count()).isZero();
        assertThat(historyService.createHistoricTaskInstanceQuery()
                .processDefinitionKey("detailChild").count()).isZero();
    }

    /**
     * 查询流程当前唯一指定节点任务。
     *
     * @param processInstanceId String，真实流程实例主键
     * @param taskDefinitionKey String，期望的 BPMN 用户任务节点主键
     * @return Task，唯一活动任务
     */
    private Task activeTask(String processInstanceId, String taskDefinitionKey)
    {
        Task task = taskService.createTaskQuery().processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey).active().singleResult();
        assertThat(task).as("活动任务 " + taskDefinitionKey).isNotNull();
        return task;
    }

    /**
     * 将业务字段提交快照写为真实 task-local 历史更新后完成任务。
     *
     * @param task Task，待完成的真实活动任务
     * @param formId Long，部署表单来源主键
     * @param formKey String，部署表单键
     * @param nodeKey String，BPMN 用户任务节点主键
     * @param taskLocal boolean，业务字段是否为 task-local 作用域
     * @param values Map&lt;String,Object&gt;，完成时的正式安全字段值
     * @return void，无返回值
     */
    private void completeWithSnapshot(Task task, Long formId, String formKey,
            String nodeKey, boolean taskLocal, Map<String, Object> values)
    {
        String snapshot = WorkflowFormSubmissionSnapshotCodec.encodeTask(
                deploymentId, "TEMPLATE", formId, formKey, nodeKey, task.getId(), taskLocal,
                values);
        taskService.setVariableLocal(task.getId(),
                WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME, snapshot);
        taskService.complete(task.getId());
    }

    /**
     * 清空准备阶段调用记录后，配置对象授权并读取流程详情。
     *
     * @param processInstanceId String，真实流程实例主键
     * @param taskId String，可选的真实任务主键
     * @param businessStatus String，本次授权快照使用的业务状态
     * @return WorkflowProcessDetailView，正式服务返回的详情
     */
    private WorkflowProcessDetailView readDetail(String processInstanceId, String taskId,
            String businessStatus)
    {
        clearReadInvocations();
        return readDetailWithoutClearing(processInstanceId, taskId, businessStatus);
    }

    /**
     * 配置真实实例与任务对应的授权快照并调用详情服务，不改动已有调用计数。
     *
     * @param processInstanceId String，真实流程实例主键
     * @param taskId String，可选的真实任务主键
     * @param businessStatus String，本次授权快照使用的业务状态
     * @return WorkflowProcessDetailView，正式服务返回的详情
     */
    private WorkflowProcessDetailView readDetailWithoutClearing(String processInstanceId,
            String taskId, String businessStatus)
    {
        HistoricProcessInstance instance = processEngine.getHistoryService()
                .createHistoricProcessInstanceQuery().processInstanceId(processInstanceId)
                .singleResult();
        ProcessDefinition definition = repositoryService.getProcessDefinition(
                instance.getProcessDefinitionId());
        when(processAccessService.requireReadableInstance(processInstanceId)).thenReturn(
                new WorkflowProcessAccessSnapshot(processInstanceId, definition.getId(),
                        definition.getDeploymentId(), instance.getBusinessKey(),
                        instance.getStartUserId(), instance.getStartTime().toInstant(),
                        instance.getEndTime() == null ? null : instance.getEndTime().toInstant(),
                        instance.getDeleteReason(), businessStatus,
                        processState(processInstanceId, instance)));
        if (taskId != null)
        {
            HistoricTaskInstance historicTask = processEngine.getHistoryService()
                    .createHistoricTaskInstanceQuery().taskId(taskId).singleResult();
            Task activeTask = taskService.createTaskQuery().taskId(taskId).singleResult();
            String assignee = activeTask == null
                    ? historicTask.getAssignee() : activeTask.getAssignee();
            String owner = activeTask == null
                    ? historicTask.getOwner() : activeTask.getOwner();
            String delegationState = activeTask == null || activeTask.getDelegationState() == null
                    ? null : activeTask.getDelegationState().name();
            String claimedBy = activeTask == null
                    ? historicTask.getClaimedBy() : activeTask.getClaimedBy();
            Date claimTime = activeTask == null
                    ? historicTask.getClaimTime() : activeTask.getClaimTime();
            boolean processActive = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(processInstanceId).active().count() == 1;
            boolean unclaimAllowed = activeTask != null
                    && new WorkflowTaskUnclaimPolicy().isUnclaimAllowed(
                            processActive, true, activeTask.isSuspended(), "approver",
                            assignee, owner, delegationState, claimedBy, claimTime != null);
            when(processAccessService.requireReadableTask(taskId)).thenReturn(
                    new WorkflowTaskAccessSnapshot(taskId, processInstanceId,
                             definition.getId(), historicTask.getTaskDefinitionKey(),
                             historicTask.getName(), assignee,
                             owner, delegationState, activeTask != null,
                             historicTask.getCreateTime().toInstant(),
                             historicTask.getEndTime() == null ? null
                                     : historicTask.getEndTime().toInstant(),
                             claimedBy, claimTime == null ? null : claimTime.toInstant(),
                             unclaimAllowed));
        }
        return service.getDetail(new WorkflowProcessDetailQueryDto(processInstanceId, taskId));
    }

    /**
     * 从真实运行时和历史实例确定授权快照使用的 Flowable 稳定状态。
     * @param processInstanceId String，真实流程实例主键
     * @param instance HistoricProcessInstance，已读取的历史流程实例
     * @return String，running、suspended 或 completed
     */
    private String processState(String processInstanceId, HistoricProcessInstance instance)
    {
        if (instance.getEndTime() != null)
        {
            return "completed";
        }
        ProcessInstance runtime = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        return runtime != null && runtime.isSuspended() ? "suspended" : "running";
    }

    /**
     * 清空当前变量读取相关 spy 记录，排除测试准备数据查询的影响。
     *
     * @return void，无返回值
     */
    private void clearReadInvocations()
    {
        clearInvocations(historyService, historicVariableMapper);
    }

    /**
     * 断言详情阶段完全没有当前变量查询；正式提交快照 Mapper 调用不在此禁用范围内。
     *
     * @return void，创建历史变量查询或调用当前变量 SQL 时测试失败
     */
    private void verifyNoCurrentVariableRead()
    {
        verify(historyService, never()).createHistoricVariableInstanceQuery();
        verify(historicVariableMapper, never()).selectCurrentVariableMetadata(
                anyString(), anyString(), anyBoolean(), anyList(), anyInt());
        verify(historicVariableMapper, never()).selectCurrentVariableBodies(
                anyString(), anyString(), anyBoolean(), anyList(), anyList());
    }

    /**
     * 创建与 BPMN 节点、表单键及测试部署严格绑定的不可变表单快照。
     *
     * @param formId Long，模板表单主键
     * @param formKey String，BPMN 表单键
     * @param nodeKey String，BPMN 节点主键
     * @param fieldName String，唯一可读业务字段名
     * @param required boolean，字段是否属于开始表单必填输入
     * @return WfDeployForm，供详情服务执行正式 schema 白名单投影
     */
    private WfDeployForm formSnapshot(Long formId, String formKey, String nodeKey,
            String fieldName, boolean required)
    {
        WfDeployForm snapshot = new WfDeployForm();
        snapshot.setDeployId(deploymentId);
        snapshot.setSourceType("TEMPLATE");
        snapshot.setFormId(formId);
        snapshot.setFormKey(formKey);
        snapshot.setNodeKey(nodeKey);
        snapshot.setFormName(nodeKey + "表单");
        snapshot.setNodeName(nodeKey);
        snapshot.setContent(fieldName == null ? "{\"fields\":[]}" :
                "{\"fields\":[{\"vModel\":\"" + fieldName
                        + "\",\"layout\":\"colFormItem\","
                        + "\"tag\":\"el-input\",\"workflowHidden\":false,"
                        + "\"workflowReadable\":true,\"workflowWritable\":true,"
                        + "\"required\":" + required + "}]}");
        snapshot.setCreateTime(Date.from(Instant.parse("2026-08-21T00:00:00Z")));
        return snapshot;
    }

    /**
     * 返回两个真实测试流程需要的全部部署表单快照。
     *
     * @return List&lt;WfDeployForm&gt;，节点和表单键联合唯一的正式快照集合
     */
    private List<WfDeployForm> formSnapshots()
    {
        return List.of(
                formSnapshot(1L, "start-form", "mainStart", "startValue", false),
                formSnapshot(2L, "global-form", "globalTask", "value", false),
                formSnapshot(3L, "local-form", "localTask", "value", false),
                formSnapshot(4L, "loop-start-form", "loopStart", "startValue", false),
                formSnapshot(5L, "loop-form", "loopTask", "decision", false),
                formSnapshot(6L, "child-start-form", "childStart", "childRequest", true),
                formSnapshot(7L, "terminal-start-form", "terminalStart", null, false),
                formSnapshot(8L, "parent-start-form", "parentStart", "parentRequest", true));
    }

    /** 真实 Flowable 测试流程：主链覆盖全局、task-local、无表单，循环链覆盖同节点新一轮继承。 */
    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
              xmlns:flowable="http://flowable.org/bpmn" targetNamespace="detail-variable-it">
              <process id="detailMain" name="详情变量主流程" isExecutable="true">
                <startEvent id="mainStart" flowable:formKey="start-form"/>
                <userTask id="globalTask" name="全局表单" flowable:formKey="global-form"
                  flowable:assignee="approver"/>
                <userTask id="localTask" name="局部表单" flowable:formKey="local-form"
                  flowable:localScope="true" flowable:assignee="approver"/>
                <userTask id="noFormTask" name="无表单" flowable:assignee="approver"/>
                <endEvent id="mainEnd"/>
                <sequenceFlow id="mainFlow1" sourceRef="mainStart" targetRef="globalTask"/>
                <sequenceFlow id="mainFlow2" sourceRef="globalTask" targetRef="localTask"/>
                <sequenceFlow id="mainFlow3" sourceRef="localTask" targetRef="noFormTask"/>
                <sequenceFlow id="mainFlow4" sourceRef="noFormTask" targetRef="mainEnd"/>
              </process>
              <process id="detailLoop" name="详情变量循环流程" isExecutable="true">
                <startEvent id="loopStart" flowable:formKey="loop-start-form"/>
                <userTask id="loopTask" name="循环审批" flowable:formKey="loop-form"
                  flowable:localScope="true" flowable:assignee="approver"/>
                <exclusiveGateway id="loopGateway" default="loopExit"/>
                <endEvent id="loopEnd"/>
                <sequenceFlow id="loopEnter" sourceRef="loopStart" targetRef="loopTask"/>
                <sequenceFlow id="loopDecision" sourceRef="loopTask" targetRef="loopGateway"/>
                <sequenceFlow id="loopRepeat" sourceRef="loopGateway" targetRef="loopTask">
                  <conditionExpression xsi:type="tFormalExpression"><![CDATA[${repeat == true}]]></conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="loopExit" sourceRef="loopGateway" targetRef="loopEnd"/>
              </process>
              <process id="detailParent" name="详情父流程" isExecutable="true">
                <startEvent id="parentStart" flowable:formKey="parent-start-form"/>
                <callActivity id="callChild" name="调用子流程" calledElement="detailChild">
                  <extensionElements>
                    <flowable:in source="parentRequest" target="childRequest"/>
                  </extensionElements>
                </callActivity>
                <endEvent id="parentEnd"/>
                <sequenceFlow id="parentFlow1" sourceRef="parentStart" targetRef="callChild"/>
                <sequenceFlow id="parentFlow2" sourceRef="callChild" targetRef="parentEnd"/>
              </process>
              <process id="detailChild" name="详情子流程" isExecutable="true">
                <startEvent id="childStart" flowable:formKey="child-start-form"/>
                <userTask id="childTask" name="子流程审批" flowable:assignee="approver"/>
                <endEvent id="childEnd"/>
                <sequenceFlow id="childFlow1" sourceRef="childStart" targetRef="childTask"/>
                <sequenceFlow id="childFlow2" sourceRef="childTask" targetRef="childEnd"/>
              </process>
              <process id="detailMissingParent" name="缺失映射值父流程" isExecutable="true">
                <startEvent id="missingParentStart"/>
                <callActivity id="callMissingChild" calledElement="detailChild">
                  <extensionElements>
                    <flowable:in source="missingParentRequest" target="childRequest"/>
                  </extensionElements>
                </callActivity>
                <endEvent id="missingParentEnd"/>
                <sequenceFlow id="missingParentFlow1" sourceRef="missingParentStart" targetRef="callMissingChild"/>
                <sequenceFlow id="missingParentFlow2" sourceRef="callMissingChild" targetRef="missingParentEnd"/>
              </process>
              <process id="detailInvalidParent" name="非法映射值父流程" isExecutable="true">
                <startEvent id="invalidParentStart"/>
                <callActivity id="callInvalidChild" calledElement="detailChild">
                  <extensionElements>
                    <flowable:in source="invalidParentRequest" target="childRequest"/>
                  </extensionElements>
                </callActivity>
                <endEvent id="invalidParentEnd"/>
                <sequenceFlow id="invalidParentFlow1" sourceRef="invalidParentStart" targetRef="callInvalidChild"/>
                <sequenceFlow id="invalidParentFlow2" sourceRef="callInvalidChild" targetRef="invalidParentEnd"/>
              </process>
              <process id="detailTerminal" name="详情终态流程" isExecutable="true">
                <startEvent id="terminalStart" flowable:formKey="terminal-start-form"/>
                <endEvent id="terminalEnd"/>
                <sequenceFlow id="terminalFlow" sourceRef="terminalStart" targetRef="terminalEnd"/>
              </process>
            </definitions>
            """;
}

package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.authorization.*;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.dto.*;
import com.ruoyi.flowable.engine.*;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.mapper.WfMultiInstanceRoundMapper;
import com.ruoyi.flowable.mapper.WorkflowMultiInstanceUserMapper;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;
import com.ruoyi.flowable.service.model.*;
import com.ruoyi.flowable.service.notification.*;
import com.ruoyi.flowable.service.task.*;
import com.ruoyi.flowable.testsupport.*;

/** 真实引擎、正式轮次 Mapper 和迁移服务验证详情事实复用；身份目录、通知及表单呈现不在此边界内。 */
class WorkflowRuntimeFactReuseIntegrationTest
{
    private static final String FORM = """
            {"fields":[{"vModel":"title","layout":"colFormItem","tag":"el-input","required":true,
              "workflowHidden":false,"workflowReadable":true,"workflowWritable":true}]}
            """;

    @ParameterizedTest(name = "{0} {1}: shared detail, return/resubmit and stale revision")
    @CsvSource({"h2,ALL", "h2,ANY", "mysql,ALL", "mysql,ANY"})
    void sharedFactsStillUseAuthoritativeRoundTrip(String database, String mode)
    {
        var currentUser = new AtomicReference<>("100");
        var lifecycleRef = new AtomicReference<WorkflowMultiInstanceRoundLifecycleService>();
        var coordinator = new WorkflowMultiInstanceTransitionCoordinator();
        var selection = mock(WorkflowUserSelectionValidator.class);
        when(selection.requireApprovalEligibleUserIds(anyList())).thenAnswer(invocation ->
                ((java.util.Collection<?>) invocation.getArgument(0)).stream().map(Object::toString).toList());
        TaskListener created = task -> lifecycleRef.get().onTaskCreated(new WorkflowTaskEventSnapshot(
                task.getId(), task.getProcessInstanceId(), task.getProcessDefinitionId(),
                task.getTaskDefinitionKey(), task.getExecutionId(), task.getAssignee(), null));
        TaskListener completed = task -> lifecycleRef.get().onTaskCompleted(new WorkflowTaskEventSnapshot(
                task.getId(), task.getProcessInstanceId(), task.getProcessDefinitionId(),
                task.getTaskDefinitionKey(), task.getExecutionId(), task.getAssignee(),
                (Integer) task.getVariableLocal(WorkflowMultiInstanceVariables.COMPLETION_REVISION_VARIABLE)));
        Map<Object, Object> beans = Map.of("multiInstanceHandler", new WorkflowMultiInstanceHandler(selection, coordinator),
                "roundCreated", created, "roundCompleted", completed);
        try (var infra = start(database, beans))
        {
            if (database.equals("h2")) infra.jdbcTemplate().execute(ROUND_SCHEMA);
            var engine = infra.processEngine();
            RepositoryService repository = spy(engine.getRepositoryService());
            RuntimeService runtime = spy(engine.getRuntimeService());
            TaskService tasks = spy(engine.getTaskService());
            var history = spy(engine.getHistoryService());
            var identities = mock(WorkflowIdentityResolver.class);
            when(identities.resolveCurrentIdentity()).thenAnswer(ignored -> new WorkflowCurrentIdentity(currentUser.get(), Set.of()));
            when(identities.resolveApprovalEligibleUserIds(anyCollection())).thenAnswer(invocation ->
                    Set.copyOf(invocation.getArgument(0)));
            when(identities.resolveActiveUserIds(anyCollection(), anyCollection())).thenAnswer(invocation ->
                    Set.copyOf(invocation.getArgument(0)));
            var operations = infra.transactionalProxy(new WorkflowEngineOperations(
                    new WorkflowAuthenticationContext(engine.getIdentityService(), new WorkflowIdentityCodec()),
                    new WorkflowExceptionTranslator(), identities));
            var snapshotReader = spy(new WorkflowMultiInstanceRuntimeSnapshotReader(repository, runtime, tasks));
            var roundSession = new org.mybatis.spring.SqlSessionTemplate(WorkflowH2SchemaMapperSupport.createSessionFactory(
                    infra.dataSource(), new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),
                    "runtime-facts", WfMultiInstanceRoundMapper.class, "mapper/flowable/WfMultiInstanceRoundMapper.xml"));
            var rounds = new WorkflowMultiInstanceRoundRepository(roundSession.getMapper(WfMultiInstanceRoundMapper.class));
            var lifecycle = spy(new WorkflowMultiInstanceRoundLifecycleService(rounds, snapshotReader, coordinator));
            lifecycleRef.set(lifecycle);
            var termination = new WorkflowMultiInstanceRoundTerminationService(rounds, snapshotReader, coordinator, runtime, tasks);
            @SuppressWarnings("unchecked")
            org.springframework.beans.factory.ObjectProvider<WorkflowMultiInstanceRoundTerminationService> provider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            when(provider.getObject()).thenReturn(termination);
            runtime.addEventListener(new com.ruoyi.flowable.listener.WorkflowMultiInstanceRoundInterruptionListener(provider));
            var reader = new WorkflowTaskRuntimeReader(runtime, tasks, history);
            var bpmnReader = new WorkflowTaskBpmnReader(repository);
            var returnedState = new WorkflowReturnedTaskStateService(new WorkflowReturnedAssignmentCodec(), runtime, tasks, identities);
            var movement = new WorkflowTaskMovementPolicy();
            var groups = new WorkflowMultiInstanceGroupTransitionService(rounds, snapshotReader, lifecycle, coordinator,
                    returnedState, reader, runtime, mock(WorkflowTaskSlaRuntimeService.class), bpmnReader, movement);
            var names = mock(WorkflowMultiInstanceUserMapper.class);
            var mi = new WorkflowMultiInstanceService(operations, identities, selection, names, runtime, tasks, history,
                    snapshotReader, lifecycle);
            var notifications = mock(WorkflowNotificationService.class);
            var copies = mock(WorkflowTaskCopyService.class);
            when(copies.prepare(any(), any(), any(), anyList())).thenReturn(WorkflowTaskCopyService.CopyPlan.empty());
            var returnPreparation = new WorkflowTaskReturnPreparation(operations, reader,
                    bpmnReader, movement, returnedState, groups);
            var returns = new WorkflowTaskReturnApplicationService(operations, returnPreparation, reader,
                    returnedState, new WorkflowTaskActionAuditWriter(tasks),
                    new WorkflowTaskConcurrencyExecutor(), groups, copies, notifications, runtime);
            var artifacts = spy(new WorkflowDeploymentArtifactRepository(repository));
            var attachments = mock(WorkflowAttachmentService.class);
            when(attachments.prepareTaskVariables(anyString(), anyString(), anyMap(), anyMap()))
                    .thenAnswer(invocation -> invocation.getArgument(2));
            var completions = new WorkflowTaskCompletionApplicationService(operations,
                    reader, bpmnReader, artifacts, new WorkflowStartVariableValidator(new WorkflowFormTemplateValidator()),
                    attachments, copies, new WorkflowNextTaskAssignmentService(selection, tasks, runtime, snapshotReader), mi,
                    new WorkflowControlledLoopService(runtime, tasks, mock(com.ruoyi.flowable.mapper.WfControlledLoopExecutionMapper.class)),
                    new WorkflowTaskActionAuditWriter(tasks), new WorkflowTaskConcurrencyExecutor(), runtime, tasks, identities);
            var resubmitPreparation = new WorkflowApplicationResubmitPreparation(operations,
                    reader, returnedState, groups, artifacts, runtime);
            var resubmits = new WorkflowApplicationResubmitApplicationService(operations, resubmitPreparation,
                    reader, returnedState, new WorkflowTaskActionAuditWriter(tasks), new WorkflowTaskConcurrencyExecutor(),
                    groups, new WorkflowStartVariableValidator(new WorkflowFormTemplateValidator()),
                    attachments, notifications, runtime);
            var access = mock(WorkflowProcessAccessService.class);
            when(access.requireReadableTask(anyString(), any(), any())).thenAnswer(invocation -> taskSnapshot(
                    engine.getTaskService().createTaskQuery().taskId(invocation.getArgument(0)).singleResult()));
            when(access.requireReadableTask(any(Task.class), any(), any())).thenAnswer(invocation -> taskSnapshot(invocation.getArgument(0)));
            var forms = mock(WorkflowProcessFormDetailProjection.class);
            when(forms.project(any())).thenReturn(new WorkflowProcessFormDetailProjection.FormProjection(
                    mock(com.ruoyi.flowable.domain.vo.WorkflowProcessFormDetailView.class)));
            var deployments = mock(WorkflowDeploymentService.class);
            when(deployments.getBpmnXml(any(org.flowable.engine.repository.ProcessDefinition.class)))
                    .thenAnswer(invocation -> {
                        try (var input = repository.getProcessModel(((org.flowable.engine.repository.ProcessDefinition)
                                invocation.getArgument(0)).getId()))
                        {
                            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        }
                    });
            var variables = mock(WorkflowProcessVariableProjection.class);
            var projection = spy(new WorkflowProcessHistoryProjection(history, tasks, repository, names));
            var details = spy(new WorkflowProcessDetailService(operations, access, repository, tasks, deployments, artifacts,
                    variables, forms,
                    projection, mi, returnPreparation, resubmitPreparation,
                    returnedState, identities, mock(WorkflowManualUrgeService.class), mock(WorkflowControlledLoopService.class)));

            // 全部 fixture 与写动作处于独立事务；MySQL 使用已有结构并在测试后回滚全部部署、轮次和引擎数据。
            String key = "facts_" + UUID.randomUUID().toString().replace("-", "");
            var fixtureDeployment = new AtomicReference<String>();
            var nested = new org.springframework.transaction.support.TransactionTemplate(infra.transactionManager());
            nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
            infra.transactionTemplate().executeWithoutResult(status -> {
                status.setRollbackOnly();
                var deployment = repository.createDeployment().name(key)
                        .addString(key + ".bpmn20.xml", bpmn(key, mode)).deploy();
                fixtureDeployment.set(deployment.getId());
                WfDeployForm startForm = new WfDeployForm();
                startForm.setDeployId(deployment.getId()); startForm.setNodeKey("start");
                startForm.setSourceType("TEMPLATE"); startForm.setFormId(1L); startForm.setFormKey("startForm");
                startForm.setFormName("申请"); startForm.setNodeName("开始"); startForm.setContent(FORM);
                WfDeployForm reviewForm = new WfDeployForm();
                reviewForm.setDeployId(deployment.getId()); reviewForm.setNodeKey("review");
                reviewForm.setSourceType("TEMPLATE"); reviewForm.setFormId(2L); reviewForm.setFormKey("reviewForm");
                reviewForm.setFormName("审批"); reviewForm.setNodeName("Review"); reviewForm.setContent(FORM);
                artifacts.persist(deployment.getId(), new WorkflowDeploymentArtifacts(List.of(startForm, reviewForm), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of()));
                String submission = WorkflowFormSubmissionSnapshotCodec.encodeStart(deployment.getId(), "TEMPLATE", 1L,
                        "startForm", "start", Map.of("title", "original"));
                var instance = operations.writeAsCurrentUser(actor -> runtime.createProcessInstanceBuilder()
                        .processDefinitionKey(key).businessStatus("running").variables(Map.of(
                                WorkflowMultiInstanceVariables.userCollectionName("review"), List.of(200L, 300L),
                                WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME, submission)).start());
                currentUser.set("200");
                Task source = tasks.createTaskQuery().processInstanceId(instance.getId()).taskAssignee("200").singleResult();
                var authorized = instanceSnapshot(instance, repository, history);
                clearInvocations(repository, runtime, tasks, history, snapshotReader, lifecycle, artifacts);
                var read = operations.read(() -> details.buildDetail(authorized, source.getId(), identities.resolveCurrentIdentity()));
                var metrics = mock(com.ruoyi.flowable.runtime.WorkflowRuntimeMetrics.class);
                when(metrics.readHealthSnapshot()).thenReturn(new com.ruoyi.flowable.runtime.WorkflowRuntimeHealthSnapshot(
                        false, -1, 0, false, false));
                var waiting = new WorkflowProcessWaitingService(access, operations, runtime, repository,
                        engine.getManagementService(), metrics, new com.ruoyi.flowable.config.WorkflowRuntimeProperties());
                assertThat(waiting.project(authorized, read.model()).waits()).isEmpty();
                assertThat(read.detail().returnAllowed()).isTrue();
                assertThat(read.detail().multiInstanceState().mode()).isEqualTo(mode);
                assertThat(read.detail().multiInstanceState().members()).hasSize(2);
                assertThat(read.detail().nextUserAssignmentPolicy()).isEqualTo(WorkflowNextTaskAssignmentContract.NextUserAssignmentPolicy.DISABLED);
                verify(repository, times(1)).getProcessDefinition(instance.getProcessDefinitionId());
                verify(repository, times(1)).getBpmnModel(instance.getProcessDefinitionId());
                verify(history, times(1)).createHistoricTaskInstanceQuery();
                verify(tasks, times(1)).createTaskQuery();
                verify(lifecycle, times(1)).requireActiveRound(any());
                for (String name : List.of(WorkflowMultiInstanceVariables.memberSnapshotName("review"),
                        WorkflowMultiInstanceVariables.modeName("review"), WorkflowMultiInstanceVariables.revisionName("review")))
                    verify(runtime, times(1)).getVariable(instance.getId(), name);

                when(access.requireReadableInstance(instance.getId(), identities.resolveCurrentIdentity())).thenReturn(authorized);
                var observations = new WorkflowProcessObservationService(operations, details, waiting, access, identities);
                clearInvocations(details, forms, variables, deployments, projection, artifacts, repository, tasks, history);
                var light = observations.queryRuntime(new WorkflowProcessDetailQueryDto(instance.getId(), source.getId()));
                assertThat(light.runtime()).usingRecursiveComparison().comparingOnlyFields(
                        "processInstanceId", "processStatus", "currentTask", "nextUserAssignmentPolicy", "multiInstanceState",
                        "completeAllowed", "rejectAllowed", "returnAllowed", "delegateAllowed", "transferAllowed",
                        "resolveAllowed", "resubmitAllowed", "urgeAllowed").isEqualTo(read.detail());
                assertThat(light.waiting().waits()).isEmpty();
                verify(details, never()).buildDetail(any(), any(), any());
                verifyNoInteractions(forms, variables, deployments, artifacts);
                verify(projection, never()).loadHistory(any());
                verify(projection, never()).loadProcessRelations(any(), any());
                verify(repository, times(1)).getBpmnModel(instance.getProcessDefinitionId());
                verify(repository, times(1)).getProcessDefinition(instance.getProcessDefinitionId());
                verify(tasks, times(1)).createTaskQuery();
                // 退回规则必须识别真实首审任务；轻量保留这一有界读取，省去活动、意见与正文。
                verify(history, times(1)).createHistoricTaskInstanceQuery();
                verify(history, never()).createHistoricActivityInstanceQuery();
                assertThat(tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build()
                        .valueToTree(light).get("runtime").has("model")).isFalse();

                // 旧 capability 不是写授权；挂起后正式写必须重查并拒绝。
                runtime.suspendProcessInstanceById(instance.getId());
                assertThatThrownBy(() -> nested.executeWithoutResult(inner ->
                        returns.returnTask(new WorkflowTaskReturnRequest(source.getId(), "suspended", List.of()))))
                        .isInstanceOf(ServiceException.class);
                var suspended = instanceSnapshot(runtime.createProcessInstanceQuery().processInstanceId(instance.getId()).singleResult(), repository, history);
                clearInvocations(history, artifacts);
                var suspendedState = operations.read(() -> details.readRuntime(suspended, source.getId(), identities.resolveCurrentIdentity()));
                assertThat(suspendedState.processStatus()).isEqualTo("suspended");
                assertThat(suspendedState.completeAllowed()).isFalse();
                assertThat(suspendedState.returnAllowed()).isFalse();
                verify(history, never()).createHistoricTaskInstanceQuery();
                verifyNoInteractions(artifacts);
                runtime.activateProcessInstanceById(instance.getId());
                returns.returnTask(new WorkflowTaskReturnRequest(source.getId(), "return", List.of()));
                assertThatThrownBy(() -> nested.executeWithoutResult(inner ->
                        returns.returnTask(new WorkflowTaskReturnRequest(source.getId(), "stale", List.of()))))
                        .isInstanceOf(ServiceException.class);
                currentUser.set("100");
                ProcessInstance returned = runtime.createProcessInstanceQuery().processInstanceId(instance.getId()).singleResult();
                assertThat(returned.getBusinessStatus()).isEqualTo("returned");
                var returnedAccess = instanceSnapshot(returned, repository, history);
                clearInvocations(repository, tasks, artifacts, snapshotReader);
                var returnedRead = operations.read(() -> details.buildDetail(returnedAccess, null, identities.resolveCurrentIdentity()));
                assertThat(returnedRead.detail().resubmitAllowed()).isTrue();
                assertThat(returnedRead.detail().multiInstanceState()).isNull();
                verify(repository, times(1)).getBpmnModel(instance.getProcessDefinitionId());
                verify(tasks, times(1)).createTaskQuery();
                verify(artifacts, times(1)).openRead(deployment.getId());
                verify(snapshotReader, never()).readTask(any(), any(), any(), any());
                clearInvocations(forms, variables, deployments, projection, artifacts);
                var returnedLight = operations.read(() -> details.readRuntime(returnedAccess, null, identities.resolveCurrentIdentity()));
                assertThat(returnedLight.resubmitAllowed()).isEqualTo(returnedRead.detail().resubmitAllowed());
                assertThat(returnedLight.currentTask().taskId()).isEqualTo(returnedRead.detail().currentTask().taskId());
                verifyNoInteractions(forms, variables, deployments);
                verify(projection, never()).loadHistory(any());
                verify(artifacts, times(1)).openRead(deployment.getId());
                String applicantTask = returnedRead.detail().currentTask().taskId();
                Object assignment = tasks.getVariableLocal(applicantTask, WorkflowReturnedApplicationProtocol.RETURN_ASSIGNMENT_VARIABLE);
                tasks.removeVariableLocal(applicantTask, WorkflowReturnedApplicationProtocol.RETURN_ASSIGNMENT_VARIABLE);
                var ineligible = operations.read(() -> details.buildDetail(returnedAccess, null, identities.resolveCurrentIdentity()));
                assertThat(ineligible.detail().resubmitAllowed()).isFalse();
                assertThat(ineligible.detail().multiInstanceState()).isNull();
                verify(snapshotReader, never()).readTask(any(), any(), any(), any());
                tasks.setVariableLocal(applicantTask, WorkflowReturnedApplicationProtocol.RETURN_ASSIGNMENT_VARIABLE, assignment);
                resubmits.resubmitApplication(new WorkflowApplicationResubmitRequest(applicantTask, Map.of("title", "edited")));
                assertThat(tasks.createTaskQuery().processInstanceId(instance.getId()).list()).hasSize(2);
                assertThat(rounds.findByProcessInstanceId(instance.getId())).extracting(round -> round.status().name())
                        .containsExactly("REOPENED", "ACTIVE");
                currentUser.set("200");
                Task reopened = tasks.createTaskQuery().processInstanceId(instance.getId()).taskAssignee("200").singleResult();
                var before = mi.getState(reopened.getId());
                mi.adjust(new WorkflowMultiInstanceAdjustmentRequest(reopened.getId(), WorkflowMultiInstanceAdjustmentAction.ADD,
                        (long) before.revision(), "add", List.of(400L), null));
                assertThatThrownBy(() -> nested.executeWithoutResult(inner -> mi.adjust(new WorkflowMultiInstanceAdjustmentRequest(reopened.getId(),
                        WorkflowMultiInstanceAdjustmentAction.ADD, (long) before.revision(), "stale", List.of(500L), null))))
                        .isInstanceOfSatisfying(ServiceException.class, failure ->
                                assertThat(failure.getSubCode()).isEqualTo(WorkflowMultiInstanceService.REVISION_CONFLICT_SUB_CODE));
                assertThat(mi.getState(reopened.getId()).members()).hasSize(3);
                var revised = operations.read(() -> details.readRuntime(instanceSnapshot(
                        runtime.createProcessInstanceQuery().processInstanceId(instance.getId()).singleResult(), repository, history),
                        reopened.getId(), identities.resolveCurrentIdentity()));
                assertThat(revised.multiInstanceState().revision()).isGreaterThan(before.revision());
                assertThat(revised.multiInstanceState().members()).hasSize(3);

                var completionState = mi.getState(reopened.getId());
                var variablesBefore = runtime.getVariables(instance.getId());
                var roundsBefore = rounds.findByProcessInstanceId(instance.getId());
                clearInvocations(attachments, copies);
                assertThatThrownBy(() -> nested.executeWithoutResult(inner -> completions.completeTask(new WorkflowTaskCompleteRequest(reopened.getId(),
                        "stale complete", Map.of("title", "stale"), List.of(), List.of(), (long) before.revision()))))
                        .isInstanceOfSatisfying(ServiceException.class, failure ->
                                assertThat(failure.getSubCode()).isEqualTo(WorkflowMultiInstanceService.REVISION_CONFLICT_SUB_CODE));
                assertThat(runtime.getVariables(instance.getId())).isEqualTo(variablesBefore);
                assertThat(rounds.findByProcessInstanceId(instance.getId())).isEqualTo(roundsBefore);
                assertThat(tasks.getTaskComments(reopened.getId())).isEmpty();
                assertThat(tasks.getVariableLocal(reopened.getId(), WorkflowMultiInstanceVariables.COMPLETION_REVISION_VARIABLE)).isNull();
                verify(attachments, never()).bindTaskAttachments(any(), any(), any(), any(), any());
                verify(copies, never()).persist(any());

                // 完成、监听器、写后对账都已发生才注入失败；共享事务保存点必须撤销引擎和正式轮次写入。
                var completeRequest = new WorkflowTaskCompleteRequest(reopened.getId(), "complete", Map.of("title", "approved"),
                        List.of(), List.of(), (long) completionState.revision());
                doAnswer(invocation -> {
                    assertThat(history.createHistoricTaskInstanceQuery().taskId(reopened.getId()).finished().count()).isOne();
                    assertThat(rounds.findByProcessInstanceId(instance.getId()).get(1).revision())
                            .isEqualTo(completionState.revision() + 1);
                    throw new ServiceException("completion persistence failure", 500);
                }).when(copies).persist(any());
                assertThatThrownBy(() -> nested.executeWithoutResult(inner -> completions.completeTask(completeRequest)))
                        .isInstanceOf(ServiceException.class).hasMessage("completion persistence failure");
                doNothing().when(copies).persist(any());
                // 保存点不会关闭外层 MyBatis 会话；清除查询缓存后验证实际回滚，并模拟下次请求的新会话。
                roundSession.clearCache();
                assertThat(runtime.getVariables(instance.getId())).isEqualTo(variablesBefore);
                assertThat(rounds.findByProcessInstanceId(instance.getId())).isEqualTo(roundsBefore);
                assertThat(tasks.createTaskQuery().processInstanceId(instance.getId()).count()).isEqualTo(3);
                assertThat(history.createHistoricTaskInstanceQuery().taskId(reopened.getId()).finished().count()).isZero();
                assertThat(tasks.getTaskComments(reopened.getId())).isEmpty();
                assertThat(tasks.getVariablesLocal(reopened.getId())).doesNotContainKeys(
                        WorkflowMultiInstanceVariables.COMPLETION_REVISION_VARIABLE,
                        WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME);

                // 仅统计 complete 命令之前的准备读取；监听器及写后读取不得算作可删除冗余。
                clearInvocations(repository, runtime, snapshotReader, artifacts);
                doAnswer(invocation -> {
                    verify(repository, times(1)).getProcessDefinition(instance.getProcessDefinitionId());
                    verify(repository, times(1)).getBpmnModel(instance.getProcessDefinitionId());
                    verify(runtime, times(1)).createProcessInstanceQuery();
                    verify(artifacts, times(1)).openRead(deployment.getId());
                    verify(repository, times(1)).createDeploymentQuery();
                    verify(repository, times(1)).getResourceAsStream(anyString(), eq("approvaplat/manifest-v1.json"));
                    verify(repository, times(1)).getResourceAsStream(anyString(), eq("approvaplat/forms-v1.json"));
                    verify(repository, times(1)).getResourceAsStream(anyString(), eq("approvaplat/controlled-loops-v1.json"));
                    verify(snapshotReader, times(1)).readTask(any(Task.class), any(ProcessInstance.class),
                            any(WorkflowTaskBpmnSnapshot.class), isNull());
                    return null;
                }).when(attachments).bindTaskAttachments(any(), any(), any(), any(), any());
                completions.completeTask(completeRequest);
                doNothing().when(attachments).bindTaskAttachments(any(), any(), any(), any(), any());
                assertThat(history.createHistoricTaskInstanceQuery().taskId(reopened.getId()).finished().singleResult().getCompletedBy())
                        .isEqualTo("200");
                assertThat(tasks.getTaskComments(reopened.getId(), "COMPLETE")).hasSize(1);
                if (mode.equals("ALL"))
                {
                    verify(snapshotReader, times(1)).readTask(any(Task.class)); // 写后重新装载当前事实。
                    assertThat(tasks.createTaskQuery().processInstanceId(instance.getId()).count()).isEqualTo(2);
                    assertThat(rounds.findByProcessInstanceId(instance.getId()).get(1).status().name()).isEqualTo("ACTIVE");
                    for (String member : List.of("300", "400"))
                    {
                        currentUser.set(member);
                        Task remaining = tasks.createTaskQuery().processInstanceId(instance.getId()).taskAssignee(member).singleResult();
                        var live = mi.getState(remaining.getId());
                        completions.completeTask(new WorkflowTaskCompleteRequest(remaining.getId(), "complete remaining",
                                Map.of("title", "approved"), List.of(), List.of(), (long) live.revision()));
                    }
                }
                assertThat(runtime.createProcessInstanceQuery().processInstanceId(instance.getId()).count()).isZero();
                assertThat(tasks.createTaskQuery().processInstanceId(instance.getId()).count()).isZero();
                assertThat(history.createHistoricProcessInstanceQuery().processInstanceId(instance.getId()).finished().count()).isOne();
                assertThat(rounds.findByProcessInstanceId(instance.getId()).get(1).status().name()).isEqualTo("COMPLETED");
                currentUser.set("200");
                if (database.equals("mysql")) verifyUrgeTree(infra, operations, repository, runtime, tasks, identities, key);
            });
            assertThat(repository.createDeploymentQuery().deploymentName(key).count()).isZero();
            assertThat(infra.jdbcTemplate().queryForObject("select count(*) from wf_multi_instance_round where deploy_id=?",
                    Integer.class, fixtureDeployment.get())).isZero();
        }
    }

    private static void verifyUrgeTree(WorkflowFlowableEngineTestSupport infra, WorkflowEngineOperations operations,
            RepositoryService repository, RuntimeService runtime, TaskService tasks, WorkflowIdentityResolver identities,
            String prefix)
    {
        var jdbc = spy(infra.jdbcTemplate());
        String recipient = jdbc.queryForObject("select cast(min(user_id) as char) from sys_user where status='0' and del_flag='0'", String.class);
        String key = prefix + "U";
        repository.createDeployment().name(prefix).addString(key + ".bpmn20.xml", """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="urge">
                  <process id="%s" isExecutable="true"><startEvent id="s"/><parallelGateway id="fork"/>
                    <sequenceFlow id="sf" sourceRef="s" targetRef="fork"/>
                    <userTask id="a" flowable:assignee="%s"/><userTask id="b" flowable:assignee="0%s"/>
                    <callActivity id="call" calledElement="%sC"/>
                    <sequenceFlow id="fa" sourceRef="fork" targetRef="a"/><sequenceFlow id="fb" sourceRef="fork" targetRef="b"/>
                    <sequenceFlow id="fc" sourceRef="fork" targetRef="call"/>
                    <sequenceFlow id="ae" sourceRef="a" targetRef="e"/><sequenceFlow id="be" sourceRef="b" targetRef="e"/>
                    <sequenceFlow id="ce" sourceRef="call" targetRef="e"/><endEvent id="e"/></process>
                  <process id="%sC" isExecutable="true"><startEvent id="cs"/><userTask id="child" flowable:assignee="%s"/>
                    <sequenceFlow id="csc" sourceRef="cs" targetRef="child"/><sequenceFlow id="cce" sourceRef="child" targetRef="cend"/><endEvent id="cend"/></process>
                </definitions>
                """.formatted(key, recipient, recipient, key, key, recipient)).deploy();
        ProcessInstance root = operations.writeAsCurrentUser(actor -> runtime.createProcessInstanceBuilder()
                .processDefinitionKey(key).businessStatus("running").start());
        var permission = mock(com.ruoyi.system.service.SysPermissionEvaluator.class);
        when(permission.hasPermi(anyString())).thenReturn(true);
        var notifications = mock(WorkflowNotificationService.class);
        when(notifications.registerManualUrge(any())).thenAnswer(invocation ->
                ((WorkflowManualUrgeRegistration) invocation.getArgument(0)).recipientUserIds());
        var redis = mock(com.ruoyi.flowable.runtime.WorkflowRedisAtomicOperations.class);
        when(redis.setIfAbsent(anyString(), any())).thenReturn(new com.ruoyi.flowable.runtime.WorkflowRedisAtomicOperations.ExpiringSetResult(true, 0));
        var identityMapper = spy(WorkflowH2SchemaMapperSupport.createSpringMapper(infra.dataSource(),
                "urge-identities", com.ruoyi.flowable.mapper.WorkflowIdentityMapper.class,
                "mapper/flowable/WorkflowIdentityMapper.xml"));
        var urges = new WorkflowManualUrgeService(jdbc, identityMapper, operations, permission, notifications, redis,
                new com.ruoyi.flowable.config.WorkflowNotificationProperties(), mock(com.ruoyi.flowable.runtime.WorkflowNotificationMetrics.class));
        clearInvocations(jdbc);
        assertThat(urges.isUrgeAllowed(root, identities.resolveCurrentIdentity())).isTrue();
        assertThat(assignedQueries(jdbc)).hasSize(1).allMatch(sql -> !sql.endsWith("for share"));
        clearInvocations(jdbc);
        assertThat(urges.urge(new WorkflowManualUrgeRequest(root.getId(), "query boundaries")).recipientCount()).isEqualTo(1);
        assertThat(assignedQueries(jdbc)).hasSize(1).allMatch(sql -> sql.endsWith("for share"));
        var registrations = org.mockito.ArgumentCaptor.forClass(WorkflowManualUrgeRegistration.class);
        verify(notifications, times(3)).registerManualUrge(registrations.capture());
        assertThat(registrations.getAllValues().stream().map(WorkflowManualUrgeRegistration::processInstanceId).distinct()).hasSize(2);
        // 候选任务必须逐任务检查关系，即使候选用户相同也不能复用另一个任务的候选结论。
        for (Task task : tasks.createTaskQuery().processInstanceId(root.getId()).list())
        {
            tasks.setAssignee(task.getId(), null);
            tasks.addCandidateUser(task.getId(), recipient);
        }
        clearInvocations(jdbc);
        assertThat(urges.isUrgeAllowed(root, identities.resolveCurrentIdentity())).isTrue();
        verify(identityMapper, times(2)).selectClaimEligibleTaskCandidates(anyString(), eq(false));
        when(permission.hasPermi(anyString())).thenReturn(false);
        runtime.suspendProcessInstanceById(root.getId());
        assertThatThrownBy(() -> urges.urge(new WorkflowManualUrgeRequest(root.getId(), "precedence")))
                .isInstanceOf(ServiceException.class).hasMessage("挂起流程不能催办");
    }

    private static List<String> assignedQueries(org.springframework.jdbc.core.JdbcTemplate jdbc)
    {
        return mockingDetails(jdbc).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("queryForList")
                        && call.getArgument(0).toString().startsWith("select cast(user_id as char)"))
                .map(call -> call.getArgument(0).toString()).toList();
    }

    private static WorkflowFlowableEngineTestSupport start(String database, Map<Object, Object> beans)
    {
        if (database.equals("h2")) return WorkflowFlowableEngineTestSupport.start("runtime-facts", beans);
        String url = System.getenv("WORKFLOW_MYSQL_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "isolated MySQL fixture required");
        assertThat(url).matches("jdbc:mysql://[^/]+/[A-Za-z0-9_]*_it(?:\\?.*)?");
        return WorkflowFlowableEngineTestSupport.start(new DriverManagerDataSource(url,
                System.getenv("WORKFLOW_MYSQL_TEST_USERNAME"), System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD")), beans);
    }

    private static WorkflowTaskAccessSnapshot taskSnapshot(Task task)
    {
        return new WorkflowTaskAccessSnapshot(task.getId(), task.getProcessInstanceId(), task.getProcessDefinitionId(),
                task.getTaskDefinitionKey(), task.getName(), task.getAssignee(), task.getOwner(),
                task.getDelegationState() == null ? null : task.getDelegationState().name(), true,
                task.getCreateTime().toInstant(), null, null, null, false, task);
    }

    private static WorkflowProcessAccessSnapshot instanceSnapshot(ProcessInstance instance, RepositoryService repository,
            org.flowable.engine.HistoryService history)
    {
        var historic = history.createHistoricProcessInstanceQuery().processInstanceId(instance.getId()).singleResult();
        return new WorkflowProcessAccessSnapshot(instance.getId(), instance.getProcessDefinitionId(),
                repository.getProcessDefinition(instance.getProcessDefinitionId()).getDeploymentId(), instance.getBusinessKey(),
                instance.getStartUserId(), historic.getStartTime().toInstant(), null, null, instance.getBusinessStatus(),
                instance.isSuspended() ? "suspended" : "running", instance, historic);
    }

    private static String bpmn(String key, String mode)
    {
        return """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="facts">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/><sequenceFlow id="begin" sourceRef="start" targetRef="review"/>
                    <userTask id="review" name="Review" flowable:assignee="${assignee}" flowable:formKey="reviewForm">
                      <extensionElements><flowable:taskListener event="create" delegateExpression="${roundCreated}"/>
                        <flowable:taskListener event="complete" delegateExpression="${roundCompleted}"/></extensionElements>
                      <multiInstanceLoopCharacteristics isSequential="false" flowable:collection="${multiInstanceHandler.getUserIds(execution)}" flowable:elementVariable="assignee">
                        <completionCondition>%s</completionCondition>
                      </multiInstanceLoopCharacteristics>
                    </userTask>
                    <sequenceFlow id="done" sourceRef="review" targetRef="end"/><endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, mode.equals("ALL") ? WorkflowMultiInstanceModelContract.ALL_COMPLETION_CONDITION
                        : WorkflowMultiInstanceModelContract.ANY_COMPLETION_CONDITION);
    }

    private static final String ROUND_SCHEMA = """
            create table wf_multi_instance_round (
              round_id bigint generated by default as identity primary key, deploy_id varchar(64) not null,
              process_definition_id varchar(64) not null, process_instance_id varchar(64) not null,
              activity_id varchar(255) not null, root_execution_id varchar(64) not null unique,
              round_no int not null, mode varchar(3) not null, members_json varchar(4000) not null,
              revision_no int not null, round_status varchar(16) not null,
              return_source_task_id varchar(64), return_actor_user_id varchar(64), applicant_task_id varchar(64),
              create_time timestamp(3) not null, return_time timestamp(3), reopen_time timestamp(3),
              complete_time timestamp(3), terminate_time timestamp(3), unique(process_instance_id, activity_id, round_no))
            """;
}

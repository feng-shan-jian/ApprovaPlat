package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployParticipantRule;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;
import com.ruoyi.flowable.listener.WorkflowUserTaskListener;
import com.ruoyi.flowable.mapper.WfMultiInstanceRoundMapper;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;
import com.ruoyi.flowable.runtime.WorkflowParticipantResolutionMetrics;
import com.ruoyi.flowable.service.identity.WorkflowParticipantRuleRuntimeService;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifacts;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;

/**
 * 真实 Flowable 触发完整生产监听入口，验证未认领候选任务、普通轮次判断及身份失败回滚。
 */
class WorkflowCandidateTaskCreationIntegrationTest
{
    private WorkflowFlowableEngineTestSupport harness;
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private TaskService taskService;
    private WorkflowIdentityResolver identityResolver;
    private WorkflowDeploymentArtifactRepository artifactRepository;
    private WorkflowMultiInstanceRuntimeSnapshotReader snapshotReader;
    private WorkflowUserSelectionValidator selectionValidator;

    /** 复用现有引擎、延迟绑定和正式 Mapper 基础设施，仅隔离目录及无关外部协作者。 */
    @BeforeEach
    void setUp()
    {
        var binding = new WorkflowMultiInstanceEngineHarness.LateBindingTaskListener();
        identityResolver = mock(WorkflowIdentityResolver.class);
        selectionValidator = new WorkflowUserSelectionValidator(identityResolver);
        harness = WorkflowFlowableEngineTestSupport.start("candidate-creation",
                Map.of("userTaskListener", binding, "multiInstanceHandler",
                        new WorkflowMultiInstanceHandler(selectionValidator, new WorkflowMultiInstanceTransitionCoordinator()),
                        "workflowSlaTimerDelegate", new com.ruoyi.flowable.listener.WorkflowSlaTimerDelegate(mock(WorkflowTaskSlaRuntimeService.class))));
        repositoryService = harness.processEngine().getRepositoryService();
        runtimeService = harness.processEngine().getRuntimeService();
        taskService = spy(harness.processEngine().getTaskService());
        when(identityResolver.resolveClaimEligibleUserIds(anyCollection())).thenReturn(Set.of());
        when(identityResolver.resolveClaimEligibleCandidateGroups(anyCollection())).thenReturn(Set.of());
        artifactRepository = new WorkflowDeploymentArtifactRepository(repositoryService);
        harness.jdbcTemplate().execute("set mode MySQL");
        WorkflowH2SchemaMapperSupport.executeSchema(harness.dataSource(),
                WorkflowH2SchemaMapperSupport.MULTI_INSTANCE_ROUND_SCHEMA);
        WfMultiInstanceRoundMapper mapper = WorkflowH2SchemaMapperSupport.createSpringMapper(
                harness.dataSource(), "candidate-round", WfMultiInstanceRoundMapper.class,
                "mapper/flowable/WfMultiInstanceRoundMapper.xml");
        snapshotReader = new WorkflowMultiInstanceRuntimeSnapshotReader(
                repositoryService, runtimeService, taskService);
        var rounds = new WorkflowMultiInstanceRoundLifecycleService(
                new WorkflowMultiInstanceRoundRepository(mapper), snapshotReader,
                new WorkflowMultiInstanceTransitionCoordinator());
        var participants = new WorkflowParticipantRuleRuntimeService(repositoryService,
                runtimeService, artifactRepository, mock(WorkflowIdentityMapper.class),
                identityResolver, mock(WorkflowParticipantResolutionMetrics.class));
        binding.bind(new WorkflowUserTaskListener(new WorkflowUserTaskAuditService(
                taskService, new WorkflowIdentityCodec()),
                mock(WorkflowTaskSlaRuntimeService.class), participants,
                mock(WorkflowAutomaticCopyService.class),
                mock(WorkflowNotificationService.class), rounds));
    }

    /** 每例关闭独占 H2 和引擎，不在共享 Harness 中增加候选业务配置。 */
    @AfterEach
    void tearDown()
    {
        org.flowable.common.engine.impl.identity.Authentication.setAuthenticatedUserId(null);
        if (harness != null)
        {
            harness.close();
        }
    }

    /** DelegateTask.setAssignee 在 create 中直接更新对象；create 审计记录最终办理人。 */
    @Test
    void fixedAssignmentRecordsFinalAssigneeAtCreate()
    {
        deployChain(false);
        Task task = startChain();
        assertThat(task.getAssignee()).isEqualTo("201");
        assertThat(taskService.getTaskComments(task.getId(), WorkflowUserTaskAuditService.COMMENT_TYPE))
                .singleElement().satisfies(comment -> assertThat(comment.getFullMessage())
                        .contains("\"assigneeUserId\":\"201\"", "USER_TASK_CREATE"));
        verify(identityResolver, times(1)).resolveApprovalEligibleUserIds(List.of("201"));
    }

    /** 候选方式只依赖认领查询；直接办理无需 claim 权限。多实例到达时仍由 handler 判断当前成员。 */
    @ParameterizedTest
    @ValueSource(strings = { "single", "candidate", "multi" })
    void nextAssignmentUsesOnlyTheRequiredQualification(String mode)
    {
        deployChain("multi".equals(mode));
        Task source = startChain();
        List<Long> selected = "single".equals(mode) ? List.of(301L) : List.of(302L, 301L);
        List<String> ids = selected.stream().map(String::valueOf).toList();
        if ("candidate".equals(mode))
        {
            when(identityResolver.resolveApprovalEligibleUserIds(ids)).thenThrow(
                    new org.springframework.dao.DataAccessResourceFailureException("unneeded approval query"));
            when(identityResolver.resolveClaimEligibleUserIds(ids)).thenReturn(new java.util.LinkedHashSet<>(ids));
        }
        else
        {
            when(identityResolver.resolveApprovalEligibleUserIds(ids)).thenReturn(new java.util.LinkedHashSet<>(ids));
        }
        completeWithNext(source, selected);
        List<Task> tasks = taskService.createTaskQuery().processInstanceId(source.getProcessInstanceId()).list();
        if ("multi".equals(mode))
        {
            assertThat(tasks).extracting(Task::getAssignee).containsExactlyInAnyOrderElementsOf(ids);
            assertThat(snapshotReader.readTask(tasks.get(0)).members()).containsExactlyElementsOf(ids);
        }
        else
        {
            Task next = tasks.get(0);
            assertThat(next.getAssignee()).isEqualTo("single".equals(mode) ? "301" : null);
            assertThat(taskService.getIdentityLinksForTask(next.getId()).stream()
                    .filter(link -> IdentityLinkType.CANDIDATE.equals(link.getType())).map(link -> link.getUserId()).toList())
                    .containsExactlyInAnyOrderElementsOf("candidate".equals(mode) ? ids : List.of());
        }
        for (Task next : tasks)
            assertThat(taskService.getTaskComments(next.getId(), WorkflowUserTaskAuditService.COMMENT_TYPE)).isNotEmpty();
        if ("single".equals(mode)) assertThat(taskService.getTaskComments(tasks.get(0).getId(), WorkflowUserTaskAuditService.COMMENT_TYPE))
                .anySatisfy(comment -> assertThat(comment.getFullMessage()).contains("USER_TASK_ASSIGNMENT", "\"assigneeUserId\":\"301\""));
        if ("candidate".equals(mode)) verify(identityResolver, never()).resolveApprovalEligibleUserIds(ids);
        else verify(identityResolver, never()).resolveClaimEligibleUserIds(ids);
    }

    @ParameterizedTest
    @ValueSource(strings = { "duplicate", "negative", "null", "overflow", "qualification", "database" })
    void invalidNextSelectionLeavesSourceAndAuditUnchanged(String failure)
    {
        deployChain(false);
        Task source = startChain();
        int before = taskService.getTaskComments(source.getId()).size();
        List<Long> selected = switch (failure)
        {
            case "duplicate" -> List.of(301L, 301L);
            case "negative" -> List.of(-1L);
            case "null" -> java.util.Arrays.asList((Long) null);
            case "overflow" -> java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList();
            default -> List.of(301L);
        };
        RuntimeException database = new org.springframework.dao.DataAccessResourceFailureException("identity database unavailable");
        if ("database".equals(failure)) when(identityResolver.resolveApprovalEligibleUserIds(List.of("301"))).thenThrow(database);
        var assertion = assertThatThrownBy(() -> completeWithNext(source, selected));
        if ("database".equals(failure)) assertion.isSameAs(database);
        else assertion.isInstanceOf(ServiceException.class).satisfies(error -> assertThat(((ServiceException) error).getCode()).isEqualTo(400));
        assertThat(taskService.createTaskQuery().taskId(source.getId()).count()).isOne();
        assertThat(taskService.getTaskComments(source.getId())).hasSize(before);
        assertThat(taskService.createTaskQuery().processInstanceId(source.getProcessInstanceId()).count()).isOne();
    }

    @Test
    void rejectsUnsupportedTopologyBeforeReadingSelectedUsers()
    {
        deployChain(false, true);
        Task source = startChain();
        var process = repositoryService.getBpmnModel(source.getProcessDefinitionId()).getMainProcess();
        var sourceNode = (org.flowable.bpmn.model.UserTask) process.getFlowElement("review");
        var assignment = new WorkflowNextTaskAssignmentService(selectionValidator, taskService, runtimeService, snapshotReader);
        assertThatThrownBy(() -> assignment.prepare(source, process, sourceNode, List.of(301L)))
                .isInstanceOf(ServiceException.class).satisfies(error -> assertThat(((ServiceException) error).getCode()).isEqualTo(409));
        verify(identityResolver, never()).resolveApprovalEligibleUserIds(List.of("301"));
        assertThat(taskService.createTaskQuery().taskId(source.getId()).count()).isOne();
    }

    @Test
    void creationAuditWriteFailureRollsBackEntireCreation()
    {
        deployChain(false);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("audit storage unavailable"))
                .when(taskService).addComment(anyString(), anyString(), eq(WorkflowUserTaskAuditService.COMMENT_TYPE), anyString());
        assertThatThrownBy(this::startChain).hasMessageContaining("audit storage unavailable");
        assertNoProcessWrites();
    }

    /** 系统查询错误直接穿过参与者解析；没有候选身份和任何成功任务审计残留。 */
    @Test
    void participantDatabaseFailureKeepsOriginalCause()
    {
        deploy(false);
        var failure = new org.springframework.dao.DataAccessResourceFailureException("candidate database unavailable");
        when(identityResolver.resolveClaimEligibleUserIds(anyCollection())).thenThrow(failure);
        assertThatThrownBy(() -> runtimeService.startProcessInstanceByKey("candidateTaskCreation", Map.of("initiator", "100")))
                .isSameAs(failure);
        assertNoProcessWrites();
    }

    /** 正式 SLA 编译器生成的升级任务由真实边界作业创建，冻结用户在到达时仍需具备办理资格。 */
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void generatedSlaEscalationChecksUserAtTimerArrival(boolean revoked)
    {
        deployChain(false);
        var converter = new org.flowable.bpmn.converter.BpmnXMLConverter();
        var definition = repositoryService.createProcessDefinitionQuery().processDefinitionKey("assignmentChain").singleResult();
        var bytes = converter.convertToXML(repositoryService.getBpmnModel(definition.getId()));
        var model = converter.convertToBpmnModel(new org.flowable.common.engine.impl.util.io.BytesStreamSource(bytes), false, true);
        model.getMainProcess().findFlowElementsOfType(org.flowable.bpmn.model.UserTask.class)
                .forEach(task -> task.setTaskListeners(List.of()));
        var review = (org.flowable.bpmn.model.UserTask) model.getMainProcess().getFlowElement("review");
        var properties = new org.flowable.bpmn.model.ExtensionElement();
        properties.setName("properties"); properties.setNamespace("http://flowable.org/bpmn");
        Map.of("enabled", "true", "calendarKey", "WORKDAY", "reminderMinutes", "10", "reminderRepeatMinutes", "10",
                "maxReminders", "1", "escalationMinutes", "30", "escalationUserId", "301", "escalationEventCode", "")
                .forEach((key, value) ->
                {
                    var property = new org.flowable.bpmn.model.ExtensionElement();
                    property.setName("property"); property.setNamespace("http://flowable.org/bpmn");
                    property.addAttribute(new org.flowable.bpmn.model.ExtensionAttribute("name", "approva.sla." + key));
                    property.addAttribute(new org.flowable.bpmn.model.ExtensionAttribute("value", value));
                    properties.addChildElement(property);
                });
        review.addExtensionElement(properties);
        var calendars = mock(com.ruoyi.flowable.service.model.WorkflowBusinessCalendarService.class);
        var calendar = new com.ruoyi.flowable.domain.WfBusinessCalendar();
        calendar.setCalendarKey("WORKDAY"); calendar.setTimezone("Asia/Shanghai"); calendar.setWorkingDays("1,2,3,4,5");
        calendar.setWorkStart("09:00"); calendar.setWorkEnd("18:00"); calendar.setDays(List.of());
        when(calendars.requireEnabled("WORKDAY")).thenReturn(calendar);
        when(calendars.resolveDueAt(eq("WORKDAY"), org.mockito.ArgumentMatchers.any(java.time.Instant.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(i -> ((java.time.Instant) i.getArgument(1)).plusSeconds(((Integer) i.getArgument(2)) * 60L));
        when(identityResolver.resolveApprovalEligibleUserIds(Set.of("301"))).thenReturn(Set.of("301"));
        new com.ruoyi.flowable.service.model.WorkflowTaskSlaDeploymentService(calendars,
                mock(com.ruoyi.flowable.service.model.WorkflowBpmnEventCodeService.class), identityResolver).prepare(model, "201");
        new com.ruoyi.flowable.service.model.WorkflowRuntimeTaskListenerCompiler().compile(model);
        var configuration = (org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl) harness.processEngine().getProcessEngineConfiguration();
        configuration.setBusinessCalendarManager(new com.ruoyi.flowable.config.WorkflowSlaBusinessCalendarManager(configuration::getClock, calendars));
        configuration.getJobServiceConfiguration().setBusinessCalendarManager(configuration.getBusinessCalendarManager());
        String originalDeployment = definition.getDeploymentId();
        String deployment = repositoryService.createDeployment().addBpmnModel("sla-assignment.bpmn20.xml", model).deploy().getId();
        var rule = artifactRepository.selectTaskParticipantRule(originalDeployment, "assignmentChain", "review");
        artifactRepository.persist(deployment, new WorkflowDeploymentArtifacts(List.of(), List.of(), List.of(), List.of(rule),
                List.of(), List.of(), List.of(), List.of()));
        Task source = startChain();
        when(identityResolver.resolveApprovalEligibleUserIds(List.of("301"))).thenReturn(revoked ? Set.of() : Set.of("301"));
        var management = harness.processEngine().getManagementService();
        var timer = management.createTimerJobQuery().processInstanceId(source.getProcessInstanceId()).list().stream()
                .filter(job -> job.getElementId().contains("escalation")).findFirst().orElseThrow();
        String executable = management.moveTimerToExecutableJob(timer.getId()).getId();
        if (revoked)
        {
            assertThatThrownBy(() -> management.executeJob(executable)).hasRootCauseMessage("用户任务办理身份无效");
            assertThat(taskService.createTaskQuery().taskId(source.getId()).count()).isOne();
            assertThat(taskService.createTaskQuery().processInstanceId(source.getProcessInstanceId()).count()).isOne();
        }
        else
        {
            management.executeJob(executable);
            Task escalated = taskService.createTaskQuery().processInstanceId(source.getProcessInstanceId()).singleResult();
            assertThat(escalated.getTaskDefinitionKey()).contains("escalation");
            assertThat(escalated.getAssignee()).isEqualTo("301");
            assertThat(taskService.getTaskComments(escalated.getId(), WorkflowUserTaskAuditService.COMMENT_TYPE))
                    .anySatisfy(comment -> assertThat(comment.getFullMessage()).contains("USER_TASK_CREATE", "\"assigneeUserId\":\"301\""));
        }
    }

    private Task startChain()
    {
        String id = runtimeService.startProcessInstanceByKey("assignmentChain", Map.of("initiator", "100")).getId();
        return taskService.createTaskQuery().processInstanceId(id).singleResult();
    }

    private void completeWithNext(Task source, List<Long> selected)
    {
        org.flowable.common.engine.impl.identity.Authentication.setAuthenticatedUserId("201");
        harness.transactionTemplate().executeWithoutResult(status ->
        {
            var process = repositoryService.getBpmnModel(source.getProcessDefinitionId()).getMainProcess();
            var assignment = new WorkflowNextTaskAssignmentService(selectionValidator, taskService, runtimeService, snapshotReader);
            var plan = assignment.prepare(source, process, (org.flowable.bpmn.model.UserTask) process.getFlowElement("review"), selected);
            taskService.complete(source.getId());
            assignment.apply(plan);
        });
    }

    private void deployChain(boolean multi)
    {
        deployChain(multi, false);
    }

    private void deployChain(boolean multi, boolean conditional)
    {
        String listeners = "<extensionElements><flowable:taskListener event=\"create\" delegateExpression=\"${userTaskListener}\"/>"
                + "<flowable:taskListener event=\"assignment\" delegateExpression=\"${userTaskListener}\"/>"
                + "<flowable:taskListener event=\"complete\" delegateExpression=\"${userTaskListener}\"/></extensionElements>";
        String loop = multi ? "<multiInstanceLoopCharacteristics isSequential=\"false\" flowable:collection=\"${multiInstanceHandler.getUserIds(execution)}\" flowable:elementVariable=\"assignee\">"
                + "<completionCondition>${nrOfCompletedInstances == nrOfInstances}</completionCondition></multiInstanceLoopCharacteristics>" : "";
        String xml = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" xmlns:flowable=\"http://flowable.org/bpmn\" targetNamespace=\"test\">"
                + "<process id=\"assignmentChain\" isExecutable=\"true\"><startEvent id=\"start\"/><sequenceFlow sourceRef=\"start\" targetRef=\"review\"/>"
                + "<userTask id=\"review\">" + listeners + "</userTask><sequenceFlow sourceRef=\"review\" targetRef=\"next\">"
                + (conditional ? "<conditionExpression>${amount > 0}</conditionExpression>" : "") + "</sequenceFlow>"
                + "<userTask id=\"next\" " + (multi ? "flowable:assignee=\"${assignee}\"" : "") + ">" + listeners + loop
                + "</userTask><sequenceFlow sourceRef=\"next\" targetRef=\"end\"/><endEvent id=\"end\"/></process></definitions>";
        String deploymentId = repositoryService.createDeployment().addString("assignment-chain.bpmn20.xml", xml).deploy().getId();
        var rules = new java.util.ArrayList<WfDeployParticipantRule>();
        for (String id : multi ? List.of("review") : List.of("review", "next"))
        {
            WfDeployParticipantRule rule = new WfDeployParticipantRule();
            rule.setProcessKey("assignmentChain"); rule.setActivityId(id); rule.setActivityName(id);
            rule.setRuleScope("TASK"); rule.setAssignmentMode("ASSIGNEE"); rule.setRuleType("FIXED_USER");
            rule.setTargetIds("201"); rule.setNoMatchPolicy("FAIL"); rule.setRuleVersion(1); rule.setChecksum(id); rule.setCreateBy("100");
            rules.add(rule);
        }
        artifactRepository.persist(deploymentId, new WorkflowDeploymentArtifacts(List.of(), List.of(), List.of(), rules,
                List.of(), List.of(), List.of(), List.of()));
        when(identityResolver.resolveApprovalEligibleUserIds(List.of("201"))).thenReturn(Set.of("201"));
    }

    /** 候选用户和候选组均保留未认领事实，跳过轮次后仍持久化正式创建审计。 */
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void createsUnclaimedCandidateAndContinuesAuditWithoutRound(boolean group)
    {
        deploy(group);
        allowCandidate(group);

        String instanceId = runtimeService.startProcessInstanceByKey("candidateTaskCreation",
                Map.of("initiator", "100", "requestTitle", "candidate regression")).getId();

        Task task = taskService.createTaskQuery().processInstanceId(instanceId).singleResult();
        assertThat(task).isNotNull();
        assertThat(task.getAssignee()).isNull();
        assertThat(task.getClaimedBy()).isNull();
        assertThat(task.getClaimTime()).isNull();
        assertThat(taskService.getIdentityLinksForTask(task.getId()).stream()
                .filter(link -> IdentityLinkType.CANDIDATE.equals(link.getType())).toList())
                .singleElement().satisfies(link ->
                {
                    assertThat(link.getUserId()).isEqualTo(group ? null : "201");
                    assertThat(link.getGroupId()).isEqualTo(group ? "ROLE2" : null);
                });
        assertThat(snapshotReader.readTask(task)).isNull();
        assertThat(harness.jdbcTemplate().queryForObject(
                "select count(*) from wf_multi_instance_round", Long.class)).isZero();
        assertThat(taskService.getTaskComments(task.getId(), WorkflowUserTaskAuditService.COMMENT_TYPE))
                .singleElement().satisfies(comment -> assertThat(comment.getFullMessage())
                        .contains("\"action\":\"USER_TASK_CREATE\"", "\"taskId\":\"" + task.getId() + "\"")
                        .doesNotContain("assigneeUserId"));
    }

    /** 参与者解析没有合法候选身份时拒绝创建，运行、历史和业务轮次均不残留。 */
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void rollsBackWhenNoCandidateIsEligible(boolean group)
    {
        deploy(group);

        assertThatThrownBy(() -> runtimeService.startProcessInstanceByKey("candidateTaskCreation",
                Map.of("initiator", "100", "requestTitle", "must roll back")))
                .isInstanceOf(ServiceException.class)
                .hasMessage("候选规则没有解析出有效认领身份")
                .satisfies(error -> assertThat(((ServiceException) error).getCode()).isEqualTo(409));

        assertNoProcessWrites();
    }

    /** 同次分配的资格决定直接落库；后续请求才重新取得资格。 */
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void recordsResolvedCandidateWithoutRepeatingItsQualification(boolean group)
    {
        deploy(group);
        if (group)
        {
            when(identityResolver.resolveClaimEligibleCandidateGroups(anyCollection()))
                    .thenReturn(Set.of("ROLE2"), Set.of());
        }
        else
        {
            when(identityResolver.resolveClaimEligibleUserIds(anyCollection()))
                    .thenReturn(Set.of("201"), Set.of());
        }

        String instanceId = runtimeService.startProcessInstanceByKey("candidateTaskCreation",
                Map.of("initiator", "100")).getId();
        Task task = taskService.createTaskQuery().processInstanceId(instanceId).singleResult();
        assertThat(task.getAssignee()).isNull();
        assertThat(taskService.getIdentityLinksForTask(task.getId()))
                .anySatisfy(link -> assertThat(group ? link.getGroupId() : link.getUserId())
                        .isEqualTo(group ? "ROLE2" : "201"));
        assertThat(taskService.getTaskComments(task.getId(), WorkflowUserTaskAuditService.COMMENT_TYPE))
                .singleElement().satisfies(comment -> assertThat(comment.getFullMessage())
                        .contains("USER_TASK_CREATE"));
        if (group) verify(identityResolver, times(1)).resolveClaimEligibleCandidateGroups(anyCollection());
        else verify(identityResolver, times(1)).resolveClaimEligibleUserIds(anyCollection());
    }

    /** 创建真实 BPMN 和正式参与者部署制品，候选链接只能由生产参与者服务生成。 */
    private void deploy(boolean group)
    {
        String deploymentId = repositoryService.createDeployment().addClasspathResource(
                "bpmn/workflow-candidate-task-creation.bpmn20.xml").deploy().getId();
        WfDeployParticipantRule rule = new WfDeployParticipantRule();
        rule.setProcessKey("candidateTaskCreation");
        rule.setActivityId("review");
        rule.setActivityName("候选审批");
        rule.setRuleScope("TASK");
        rule.setAssignmentMode("CANDIDATE");
        rule.setRuleType(group ? "CANDIDATE_GROUPS" : "CANDIDATE_USERS");
        rule.setTargetIds(group ? "ROLE2" : "201");
        rule.setNoMatchPolicy("FAIL");
        rule.setRuleVersion(1);
        rule.setChecksum("candidate-creation-" + group);
        rule.setCreateBy("100");
        artifactRepository.persist(deploymentId, new WorkflowDeploymentArtifacts(
                List.of(), List.of(), List.of(), List.of(rule),
                List.of(), List.of(), List.of(), List.of()));
    }

    /** 只配置测试身份目录中的真实资格查询边界，不替代监听或轮次判断。 */
    private void allowCandidate(boolean group)
    {
        if (group)
        {
            when(identityResolver.resolveClaimEligibleCandidateGroups(anyCollection()))
                    .thenReturn(Set.of("ROLE2"));
        }
        else
        {
            when(identityResolver.resolveClaimEligibleUserIds(anyCollection()))
                    .thenReturn(Set.of("201"));
        }
    }

    /** 只读核对共享事务中的运行、历史、候选链接、变量、审计及轮次全部回滚。 */
    private void assertNoProcessWrites()
    {
        for (String table : List.of("ACT_RU_EXECUTION", "ACT_RU_TASK", "ACT_RU_VARIABLE",
                "ACT_RU_IDENTITYLINK", "ACT_HI_PROCINST", "ACT_HI_TASKINST", "ACT_HI_ACTINST",
                "ACT_HI_VARINST", "ACT_HI_IDENTITYLINK", "ACT_HI_COMMENT", "wf_multi_instance_round"))
        {
            assertThat(harness.jdbcTemplate().queryForObject("select count(*) from " + table, Long.class))
                    .as(table).isZero();
        }
    }
}

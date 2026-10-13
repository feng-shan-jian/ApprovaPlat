package com.ruoyi.flowable.service.task;

import com.ruoyi.common.testsupport.WorkflowMysqlTargetGuard;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.flowable.bpmn.model.*;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.job.api.Job;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.config.WorkflowSlaBusinessCalendarManager;
import com.ruoyi.flowable.domain.WfDeployTaskSla;
import com.ruoyi.flowable.domain.WfTaskSlaExecution;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.listener.WorkflowSlaTimerDelegate;
import com.ruoyi.flowable.mapper.WfTaskSlaMapper;
import com.ruoyi.flowable.service.model.*;
import com.ruoyi.flowable.service.notification.WorkflowNotificationOutboxService;
import com.ruoyi.flowable.service.notification.WorkflowNotificationWriter;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;

/** Real MySQL SLA Mapper, frozen resources, timer commands and mandatory Inbox share one transaction. */
@EnabledIfEnvironmentVariable(named = "WORKFLOW_MYSQL_TEST_URL", matches = ".+")
class WorkflowTaskSlaRuntimeMysqlTest
{
    private static final Instant START = Instant.parse("2026-10-02T08:50:00Z"); // Friday 16:50 Shanghai.
    private WorkflowFlowableEngineTestSupport fixture;
    private JdbcTemplate jdbc;
    private WfTaskSlaMapper mapper;
    private WorkflowTaskSlaRuntimeService sla;
    private WorkflowNotificationWriter writer;
    private String deployment;
    private String definition;
    private String instance;
    private Task task;
    private String calendarKey;
    private long calendarId;
    private final long user = 700_000_000L + ThreadLocalRandom.current().nextLong(100_000_000L);
    private final String suffix = UUID.randomUUID().toString().replace("-", "");

    @BeforeEach
    void setUp()
    {
        String url = System.getenv("WORKFLOW_MYSQL_TEST_URL");
        WorkflowMysqlTargetGuard.verify(url);
        var holder = new AtomicReference<WorkflowTaskSlaRuntimeService>();
        TaskListener listener = event -> holder.get().onTaskEvent(event.getEventName(), event.getId(),
                event.getProcessInstanceId(), event.getProcessDefinitionId(), event.getTaskDefinitionKey(), event.getAssignee());
        fixture = WorkflowFlowableEngineTestSupport.start(new DriverManagerDataSource(url,
                System.getenv("WORKFLOW_MYSQL_TEST_USERNAME"), System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD")),
                Map.of("slaLifecycle", listener, "workflowSlaTimerDelegate", new TimerBridge(holder)));
        jdbc = fixture.jdbcTemplate();
        var engine = fixture.processEngine();
        ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
        configuration.getClock().setCurrentTime(Date.from(START));
        mapper = WorkflowH2SchemaMapperSupport.createSpringMapper(fixture.dataSource(), "sla-runtime", WfTaskSlaMapper.class,
                "mapper/flowable/WfTaskSlaMapper.xml");
        var calendars = new WorkflowBusinessCalendarService(mock(WorkflowEngineOperations.class), mapper);
        var calendarManager = new WorkflowSlaBusinessCalendarManager(configuration::getClock, calendars);
        // The shared fixture has already built the engine; mirror the pre-build production registration in both owners.
        configuration.setBusinessCalendarManager(calendarManager);
        configuration.getJobServiceConfiguration().setBusinessCalendarManager(calendarManager);
        var artifacts = new WorkflowDeploymentArtifactRepository(engine.getRepositoryService());
        writer = fixture.transactionalProxy(new WorkflowNotificationWriter(jdbc, mock(WorkflowNotificationOutboxService.class)));
        sla = fixture.transactionalProxy(new WorkflowTaskSlaRuntimeService(engine.getRepositoryService(), engine.getManagementService(),
                configuration, calendars, mock(WorkflowEngineOperations.class), mapper, artifacts, writer));
        holder.set(sla);
        for (long id : List.of(user, user + 1)) jdbc.update("insert into sys_user(user_id,user_name,nick_name,status,del_flag) values (?,?,'SLA contract','0','0')", id, "sla-" + id);
        calendarKey = "SLA_" + suffix.toUpperCase();
        jdbc.update("insert into wf_business_calendar(calendar_key,calendar_name,timezone,working_days,work_start,work_end,status,create_by) values (?,'SLA contract','Asia/Shanghai','1,2,3,4,5','09:00','17:00','ENABLED',?)", calendarKey, Long.toString(user));
        calendarId = jdbc.queryForObject("select calendar_id from wf_business_calendar where calendar_key=?", Long.class, calendarKey);
        jdbc.update("insert into wf_business_calendar_day(calendar_id,calendar_date,working_day,day_name) values (?,'2026-10-05',0,'Contract holiday')", calendarId);
        BpmnModel model = model();
        var resolver = mock(WorkflowIdentityResolver.class);
        org.mockito.Mockito.when(resolver.resolveApprovalEligibleUserIds(java.util.Set.of(Long.toString(user + 1))))
                .thenReturn(java.util.Set.of(Long.toString(user + 1)));
        var compiler = new WorkflowTaskSlaDeploymentService(calendars, mock(WorkflowBpmnEventCodeService.class), resolver);
        List<WfDeployTaskSla> snapshots = compiler.prepare(model, Long.toString(user));
        for (UserTask userTask : model.getMainProcess().findFlowElementsOfType(UserTask.class))
        {
            for (String event : List.of("create", "assignment", "complete"))
            {
                var taskListener = new FlowableListener(); taskListener.setEvent(event);
                taskListener.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
                taskListener.setImplementation("${slaLifecycle}"); userTask.getTaskListeners().add(taskListener);
            }
        }
        deployment = engine.getRepositoryService().createDeployment().addBpmnModel("sla.bpmn20.xml", model).deploy().getId();
        definition = engine.getRepositoryService().createProcessDefinitionQuery().deploymentId(deployment).singleResult().getId();
        snapshots.forEach(snapshot -> snapshot.setDeploymentId(deployment));
        artifacts.persist(deployment, new WorkflowDeploymentArtifacts(null, null, null, null, null, null, null, snapshots));
        tx(() -> instance = engine.getRuntimeService().startProcessInstanceById(definition).getId());
        task = engine.getTaskService().createTaskQuery().processInstanceId(instance).taskDefinitionKey("review").singleResult();
    }

    @AfterEach
    void close()
    {
        if (fixture == null) return;
        try
        {
            if (instance != null)
            {
                jdbc.update("delete from wf_notification_inbox where process_instance_id=?", instance);
                jdbc.update("delete a from wf_task_sla_audit a join wf_task_sla_execution e on e.sla_execution_id=a.sla_execution_id where e.process_instance_id=?", instance);
                jdbc.update("delete from wf_task_sla_execution where process_instance_id=?", instance);
            }
            if (deployment != null)
            {
                var repository = fixture.processEngine().getRepositoryService();
                new WorkflowDeploymentArtifactRepository(repository).delete(deployment);
                repository.deleteDeployment(deployment, true);
            }
            jdbc.update("delete from wf_business_calendar_day where calendar_id=?", calendarId);
            jdbc.update("delete from wf_business_calendar where calendar_id=?", calendarId);
            jdbc.update("delete from wf_notification_preference where user_id in (?,?)", user, user + 1);
            jdbc.update("delete from sys_user where user_id in (?,?)", user, user + 1);
        }
        finally { fixture.processEngine().getProcessEngineConfiguration().getClock().reset(); fixture.close(); }
    }

    @Test
    void createsOnceAssignsAndFreezesCalendarDatesMatchingActualTimers()
    {
        WfTaskSlaExecution created = execution();
        assertThat(created.getRevision()).isZero();
        assertThat(created.getStartedAt()).isEqualTo(utc(START));
        assertThat(created.getReminderDueAt()).isEqualTo(utc(Instant.parse("2026-10-06T01:20:00Z")));
        assertThat(created.getEscalationDueAt()).isEqualTo(utc(Instant.parse("2026-10-06T02:20:00Z")));
        assertThat(timer("reminder_1").getDuedate().toInstant()).isEqualTo(created.getReminderDueAt().toInstant(ZoneOffset.UTC));
        assertThat(timer("escalation").getDuedate().toInstant()).isEqualTo(created.getEscalationDueAt().toInstant(ZoneOffset.UTC));
        assertThat(audit("CREATE")).isOne();
        tx(() -> sla.onTaskEvent("create", task.getId(), instance, definition, "review", Long.toString(user)));
        assertThat(audit("CREATE")).isOne();
        tx(() -> fixture.processEngine().getTaskService().setAssignee(task.getId(), Long.toString(user + 1)));
        assertThat(execution().getAssigneeUserId()).isEqualTo(Long.toString(user + 1));
        assertThat(execution().getRevision()).isEqualTo(1);
        assertThat(audit("ASSIGN")).isOne();
        jdbc.update("update wf_business_calendar set work_end='12:00' where calendar_id=?", calendarId);
        assertThat(execution().getEscalationDueAt()).isEqualTo(created.getEscalationDueAt());
        assertThat(timer("escalation").getDuedate().toInstant()).isEqualTo(created.getEscalationDueAt().toInstant(ZoneOffset.UTC));
    }

    @Test
    void remindersAreOrderedIdempotentAndCompletionDropsLateTimers()
    {
        fire("reminder_1");
        assertThat(execution().getRemindersSent()).isEqualTo(1);
        assertThat(audit("REMINDER")).isOne();
        assertThat(inbox("REMINDER")).isOne();
        tx(() -> sla.handleTimer(instance, definition, "review", "REMINDER", 1, null));
        assertThat(execution().getRevision()).isEqualTo(1);
        assertThat(audit("REMINDER")).isOne();
        fire("reminder_2");
        assertThat(execution().getRemindersSent()).isEqualTo(2);
        assertThat(inbox("REMINDER")).isEqualTo(2);
        tx(() -> fixture.processEngine().getTaskService().complete(task.getId()));
        assertThat(execution().getStatus()).isEqualTo("COMPLETED");
        assertThat(audit("COMPLETE")).isOne();
        assertThat(fixture.processEngine().getManagementService().createTimerJobQuery().processInstanceId(instance).count()).isZero();
        tx(() -> sla.handleTimer(instance, definition, "review", "ESCALATE", 0, Long.toString(user + 1)));
        tx(() -> sla.handleTimer(instance, definition, "review", "REMINDER", 2, null));
        assertThat(inbox("ESCALATE")).isZero();
        assertThat(audit("REMINDER")).isEqualTo(2);
    }

    @Test
    void escalationInterruptsOriginalTaskAndIgnoresLateReminderUntilGeneratedTaskCompletes()
    {
        fire("escalation");
        assertThat(execution().getStatus()).isEqualTo("ESCALATED");
        assertThat(fixture.processEngine().getTaskService().createTaskQuery().taskId(task.getId()).count()).isZero();
        Task escalation = fixture.processEngine().getTaskService().createTaskQuery().processInstanceId(instance).singleResult();
        assertThat(escalation.getAssignee()).isEqualTo(Long.toString(user + 1));
        assertThat(inbox("ESCALATE")).isOne();
        tx(() -> sla.handleTimer(instance, definition, "review", "REMINDER", 1, null));
        tx(() -> sla.handleTimer(instance, definition, "review", "ESCALATE", 0, Long.toString(user + 1)));
        assertThat(audit("ESCALATE")).isOne();
        assertThat(audit("REMINDER")).isZero();
        tx(() -> fixture.processEngine().getTaskService().complete(escalation.getId()));
        assertThat(execution().getStatus()).isEqualTo("COMPLETED");
        assertThat(audit("COMPLETE")).isOne();
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void pauseAcrossDaysMovesEveryTimerAndFrozenDueDateExactlyOnce(boolean multipleTasks)
    {
        if (multipleTasks)
        {
            tx(() -> {
                sla.completeControlledWithdrawal(instance, List.of(task.getId()), Long.toString(user), WorkflowTaskSlaRuntimeService.ControlledWithdrawal.GROUP_RETURN);
                fixture.processEngine().getRuntimeService().createChangeActivityStateBuilder().processInstanceId(instance)
                        .moveSingleActivityIdToActivityIds("review", List.of("review", "parallelReview")).changeState();
            });
        }
        var before = mapper.selectActiveExecutionsForInstanceForUpdate(instance);
        assertThat(before).hasSize(multipleTasks ? 2 : 1);
        var dates = fixture.processEngine().getManagementService().createTimerJobQuery().processInstanceId(instance).list().stream()
                .collect(java.util.stream.Collectors.toMap(Job::getElementId, job -> job.getDuedate().toInstant()));
        assertThat(dates).hasSize(before.size() * 3);
        advance(START.plusSeconds(60));
        tx(() -> { fixture.processEngine().getRuntimeService().suspendProcessInstanceById(instance); sla.pauseInstance(instance, Long.toString(user)); });
        tx(() -> sla.pauseInstance(instance, Long.toString(user)));
        for (var original : before) assertThat(audit(original.getSlaExecutionId(), "PAUSE")).isOne();
        Duration pause = Duration.ofDays(3).plusMillis(321);
        advance(START.plusSeconds(60).plus(pause));
        tx(() -> { fixture.processEngine().getRuntimeService().activateProcessInstanceById(instance); sla.resumeInstance(instance, Long.toString(user)); });
        tx(() -> sla.resumeInstance(instance, Long.toString(user)));
        for (var original : before)
        {
            var resumed = mapper.selectExecutionByTaskForUpdate(original.getTaskId());
            assertThat(resumed.getPausedMillis()).isEqualTo(pause.toMillis());
            assertThat(resumed.getPausedAt()).isNull();
            assertThat(resumed.getReminderDueAt()).isEqualTo(original.getReminderDueAt().plusNanos(pause.toNanos()));
            assertThat(resumed.getEscalationDueAt()).isEqualTo(original.getEscalationDueAt().plusNanos(pause.toNanos()));
            assertThat(audit(original.getSlaExecutionId(), "RESUME")).isOne();
        }
        for (Job job : fixture.processEngine().getManagementService().createTimerJobQuery().processInstanceId(instance).list())
            assertThat(job.getDuedate().toInstant()).isEqualTo(dates.get(job.getElementId()).plus(pause));
    }

    @Test
    void controlledReturnAndResubmitClosePreciseOldTasksBeforeNewSlaIsCreated()
    {
        tx(() -> {
            sla.completeControlledWithdrawal(instance, List.of(task.getId()), Long.toString(user), WorkflowTaskSlaRuntimeService.ControlledWithdrawal.GROUP_RETURN);
            fixture.processEngine().getRuntimeService().createChangeActivityStateBuilder().processInstanceId(instance)
                    .moveSingleActivityIdToActivityIds("review", List.of("review", "parallelReview")).changeState();
        });
        List<Task> group = fixture.processEngine().getTaskService().createTaskQuery().processInstanceId(instance).list();
        assertThat(group).hasSize(2);
        assertThat(fixture.processEngine().getManagementService().createTimerJobQuery().processInstanceId(instance).count()).isEqualTo(6);
        tx(() -> {
            sla.completeControlledWithdrawal(instance, List.of(group.get(0).getId(), group.get(1).getId(), group.get(0).getId()), Long.toString(user), WorkflowTaskSlaRuntimeService.ControlledWithdrawal.GROUP_RETURN);
            fixture.processEngine().getRuntimeService().createChangeActivityStateBuilder().processInstanceId(instance)
                    .moveActivityIdsToSingleActivityId(List.of("review", "parallelReview"), "edit").changeState();
        });
        assertThat(execution().getStatus()).isEqualTo("COMPLETED");
        for (Task withdrawn : group) assertThat(mapper.selectExecutionByTaskForUpdate(withdrawn.getId()).getStatus()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select detail from wf_task_sla_audit where sla_execution_id=? and action_type='COMPLETE'", String.class, execution().getSlaExecutionId())).contains("整组退回");
        assertThat(fixture.processEngine().getManagementService().createTimerJobQuery().processInstanceId(instance).count()).isZero();
        Task edit = fixture.processEngine().getTaskService().createTaskQuery().processInstanceId(instance).singleResult();
        tx(() -> {
            sla.completeControlledWithdrawal(instance, List.of(edit.getId()), Long.toString(user), WorkflowTaskSlaRuntimeService.ControlledWithdrawal.GROUP_RESUBMIT);
            fixture.processEngine().getRuntimeService().createChangeActivityStateBuilder().processInstanceId(instance).moveActivityIdTo("edit", "review").changeState();
        });
        Task next = fixture.processEngine().getTaskService().createTaskQuery().processInstanceId(instance).singleResult();
        assertThat(next.getId()).isNotEqualTo(task.getId());
        assertThat(mapper.selectExecutionByTaskForUpdate(next.getId()).getStatus()).isEqualTo("ACTIVE");
        assertThat(execution().getStatus()).isEqualTo("COMPLETED");
        assertThat(fixture.processEngine().getManagementService().createTimerJobQuery().processInstanceId(instance).count()).isEqualTo(3);
        tx(() -> {
            sla.completeControlledWithdrawal(instance, List.of(next.getId()), Long.toString(user), WorkflowTaskSlaRuntimeService.ControlledWithdrawal.GROUP_RESUBMIT);
            fixture.processEngine().getRuntimeService().createChangeActivityStateBuilder().processInstanceId(instance).moveActivityIdTo("review", "edit").changeState();
        });
        assertThat(mapper.selectExecutionByTaskForUpdate(next.getId()).getStatus()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select detail from wf_task_sla_audit where sla_execution_id=? and action_type='COMPLETE'", String.class, mapper.selectExecutionByTaskForUpdate(next.getId()).getSlaExecutionId())).contains("重提");
    }

    @Test
    void missingTransactionIsRejectedAndDatabaseInboxFailureRollsBackActualTimerCommand()
    {
        assertThatThrownBy(() -> sla.handleTimer(instance, definition, "review", "REMINDER", 1, null)).isInstanceOf(IllegalTransactionStateException.class);
        String constraint = "sla_inbox_" + suffix;
        jdbc.execute("alter table wf_notification_inbox add constraint " + constraint + " check (recipient_user_id <> " + user + ")");
        Job due = timer("reminder_1");
        advance(due.getDuedate().toInstant());
        String executable = fixture.processEngine().getManagementService().moveTimerToExecutableJob(due.getId()).getId();
        try
        {
            assertThatThrownBy(() -> fixture.processEngine().getManagementService().executeJob(executable))
                    .isInstanceOf(RuntimeException.class).hasStackTraceContaining(constraint);
            assertThat(execution().getRevision()).isZero();
            assertThat(execution().getRemindersSent()).isZero();
            assertThat(audit("REMINDER")).isZero();
            assertThat(inbox("REMINDER")).isZero();
            assertThat(fixture.processEngine().getTaskService().createTaskQuery().taskId(task.getId()).count()).isOne();
            // Flowable rolls back the command, then records a retry in a separate transaction.
            assertThat(fixture.processEngine().getManagementService().createJobQuery().jobId(executable).count()).isZero();
            Job retry = timer("reminder_1");
            assertThat(retry.getRetries()).isEqualTo(due.getRetries() - 1);
            assertThat(fixture.processEngine().getManagementService().getTimerJobExceptionStacktrace(retry.getId())).contains(constraint);
            assertThat(fixture.processEngine().getManagementService().createDeadLetterJobQuery().processInstanceId(instance).count()).isZero();
            assertThatThrownBy(() -> tx(() -> {
                fixture.processEngine().getRuntimeService().setVariable(instance, "slaBusinessWrite", "must rollback");
                sla.handleTimer(instance, definition, "review", "REMINDER", 1, null);
            })).isInstanceOf(RuntimeException.class);
            assertThat(fixture.processEngine().getRuntimeService().hasVariable(instance, "slaBusinessWrite")).isFalse();
        }
        finally { jdbc.execute("alter table wf_notification_inbox drop check " + constraint); }
        fire("reminder_1");
        assertThat(execution().getRemindersSent()).isEqualTo(1);
        assertThat(audit("REMINDER")).isOne();
        assertThat(inbox("REMINDER")).isOne();
    }

    @Test
    void mandatoryInboxIgnoresPreferenceAndUsesFrozenEscalationRecipientWhenUnassigned()
    {
        jdbc.update("insert into wf_notification_preference(user_id,inbox_enabled) values (?,0)", user + 1);
        tx(() -> fixture.processEngine().getTaskService().setAssignee(task.getId(), null));
        fire("reminder_1");
        assertThat(jdbc.queryForObject("select recipient_user_id from wf_notification_inbox where process_instance_id=?", Long.class, instance)).isEqualTo(user + 1);
        assertThat(audit("REMINDER")).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = { "disabled", "deleted" })
    void inactiveRequiredRecipientRollsBackSlaAndAudit(String kind)
    {
        jdbc.update("update sys_user set " + ("disabled".equals(kind) ? "status='1'" : "del_flag='2'") + " where user_id=?", user);
        assertThatThrownBy(() -> tx(() -> sla.handleTimer(instance, definition, "review", "REMINDER", 1, null)))
                .isInstanceOf(ServiceException.class).hasMessage("审批 SLA 通知接收人无效");
        assertThat(execution().getRevision()).isZero();
        assertThat(audit("REMINDER")).isZero();
        assertThat(inbox("REMINDER")).isZero();
    }

    @Test
    void concurrentSameOrdinalSerializesOnExecutionRowWithoutDuplicateAuditOrInbox() throws Exception
    {
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        Callable<Boolean> reminder = () -> { barrier.await(10, TimeUnit.SECONDS); tx(() -> sla.handleTimer(instance, definition, "review", "REMINDER", 1, null)); return true; };
        try
        {
            var first = pool.submit(reminder); var second = pool.submit(reminder);
            assertThat(first.get(20, TimeUnit.SECONDS)).isTrue(); assertThat(second.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(execution().getRevision()).isEqualTo(1);
            assertThat(execution().getRemindersSent()).isEqualTo(1);
            assertThat(audit("REMINDER")).isOne();
            assertThat(inbox("REMINDER")).isOne();
        }
        finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test
    void invalidAndOutOfOrderTimerCommandsDoNotAdvanceState()
    {
        for (int ordinal : List.of(0, 3)) assertThatThrownBy(() -> tx(() -> sla.handleTimer(instance, definition, "review", "REMINDER", ordinal, null))).hasMessage("审批 SLA 提醒序号异常");
        assertThatThrownBy(() -> tx(() -> sla.handleTimer(instance, definition, "review", "REMINDER", 2, null))).hasMessage("审批 SLA 状态已发生变化，请刷新后重试");
        assertThatThrownBy(() -> tx(() -> sla.handleTimer(instance, definition, "review", "OTHER", 0, null))).hasMessage("审批 SLA 定时动作异常");
        assertThatThrownBy(() -> tx(() -> sla.handleTimer(instance, definition, "review", "ESCALATE", 1, null))).hasMessage("审批 SLA 定时动作异常");
        assertThat(execution().getRevision()).isZero();
        assertThat(audit("REMINDER")).isZero();
        assertThat(inbox("REMINDER")).isZero();
    }

    private BpmnModel model()
    {
        var model = new BpmnModel();
        var process = new org.flowable.bpmn.model.Process(); process.setId("sla_" + suffix); process.setName("SLA contract"); process.setExecutable(true); model.addProcess(process);
        var start = new StartEvent(); start.setId("start"); process.addFlowElement(start);
        var review = new UserTask(); review.setId("review"); review.setName("Review"); review.setAssignee(Long.toString(user)); process.addFlowElement(review);
        var edit = new UserTask(); edit.setId("edit"); edit.setAssignee(Long.toString(user)); process.addFlowElement(edit);
        var parallel = new UserTask(); parallel.setId("parallelReview"); parallel.setName("Parallel review"); parallel.setAssignee(Long.toString(user + 1)); process.addFlowElement(parallel);
        var end = new EndEvent(); end.setId("end"); process.addFlowElement(end);
        var first = new SequenceFlow("start", "review"); first.setId("a"); process.addFlowElement(first);
        var second = new SequenceFlow("review", "end"); second.setId("b"); process.addFlowElement(second);
        var properties = new ExtensionElement(); properties.setName("properties"); properties.setNamespace("http://flowable.org/bpmn"); properties.setNamespacePrefix("flowable");
        Map<String, String> values = Map.of("enabled", "true", "calendarKey", calendarKey, "reminderMinutes", "30", "reminderRepeatMinutes", "30", "maxReminders", "2", "escalationMinutes", "90", "escalationUserId", Long.toString(user + 1), "escalationEventCode", "");
        values.forEach((key, value) -> { var property = new ExtensionElement(); property.setName("property"); property.setNamespace("http://flowable.org/bpmn"); property.setNamespacePrefix("flowable"); property.addAttribute(new ExtensionAttribute("name", "approva.sla." + key)); property.addAttribute(new ExtensionAttribute("value", value)); properties.addChildElement(property); });
        review.addExtensionElement(properties);
        parallel.addExtensionElement(properties.clone());
        return model;
    }

    private void tx(Runnable action) { fixture.transactionTemplate().executeWithoutResult(status -> action.run()); }
    private WfTaskSlaExecution execution() { return mapper.selectExecutionByTaskForUpdate(task.getId()); }
    private Job timer(String key) { return fixture.processEngine().getManagementService().createTimerJobQuery().processInstanceId(instance).list().stream().filter(job -> job.getElementId().contains(key)).findFirst().orElseThrow(); }
    private void fire(String key) { Job timer = timer(key); advance(timer.getDuedate().toInstant()); String job = fixture.processEngine().getManagementService().moveTimerToExecutableJob(timer.getId()).getId(); fixture.processEngine().getManagementService().executeJob(job); }
    private void advance(Instant time) { fixture.processEngine().getProcessEngineConfiguration().getClock().setCurrentTime(Date.from(time)); }
    private int audit(String type) { return audit(execution().getSlaExecutionId(), type); }
    private int audit(Long executionId, String type) { return jdbc.queryForObject("select count(*) from wf_task_sla_audit where sla_execution_id=? and action_type=?", Integer.class, executionId, type); }
    private int inbox(String type) { return jdbc.queryForObject("select count(*) from wf_notification_inbox where process_instance_id=? and event_type=?", Integer.class, instance, type); }
    private static LocalDateTime utc(Instant time) { return LocalDateTime.ofInstant(time, ZoneOffset.UTC); }
    public static class TimerBridge
    {
        private final AtomicReference<WorkflowTaskSlaRuntimeService> service;
        public TimerBridge(AtomicReference<WorkflowTaskSlaRuntimeService> service) { this.service = service; }
        public Object executeTimer(DelegateExecution execution, String task, String action, Integer ordinal, String recipient)
        { return new WorkflowSlaTimerDelegate(service.get()).executeTimer(execution, task, action, ordinal, recipient); }
    }
}

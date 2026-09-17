package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.impl.persistence.entity.ModelEntity;
import org.flowable.job.api.Job;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.config.WorkflowSlaBusinessCalendarManager;
import com.ruoyi.flowable.domain.*;
import com.ruoyi.flowable.domain.dto.*;
import com.ruoyi.flowable.engine.*;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.listener.*;
import com.ruoyi.flowable.mapper.*;
import com.ruoyi.flowable.runtime.WorkflowParticipantResolutionMetrics;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.identity.WorkflowParticipantRuleRuntimeService;
import com.ruoyi.flowable.service.model.*;
import com.ruoyi.flowable.service.notification.*;
import com.ruoyi.flowable.testsupport.*;

/**
 * 正式作者保存/部署 -> Flowable 创建任务/边界 timer -> SLA、审计和必达站内信。
 * H2 只适配 DATE_FORMAT/SHA2 方言；身份、表单目录及无关抄送/普通通知为替身。
 * MySQL 子类复用全部行为判据，使用正式表、SQL、事务和函数。
 */
public class WorkflowTaskSlaCalendarIntegrationTest
{
    protected WorkflowFlowableEngineTestSupport support;
    protected ProcessEngine engine;
    protected JdbcTemplate jdbc;
    protected WorkflowBusinessCalendarService calendars;
    protected WfTaskSlaMapper mapper;
    protected WorkflowTaskSlaRuntimeService sla;
    private WorkflowModelService models;
    private WorkflowEngineOperations operations;
    private WorkflowDeploymentArtifactRepository artifacts;
    private final List<WfBusinessCalendar> reads = new ArrayList<>();
    private Long calendarId;
    private int sequence;

    @BeforeEach void setup() throws Exception
    {
        support = startDatabase(); engine = support.processEngine(); jdbc = support.jdbcTemplate();
        prepareSchema();
        for (int id : List.of(100, 301)) jdbc.update("insert into sys_user(user_id,user_name,nick_name,status,del_flag) values(?,?,?,'0','0')", id, "sla" + id, "SLA " + id);
        var repository = engine.getRepositoryService();
        artifacts = new WorkflowDeploymentArtifactRepository(repository);
        var identities = mock(WorkflowIdentityResolver.class);
        when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("100", Set.of()));
        when(identities.resolveApprovalEligibleUserIds(anyCollection())).thenAnswer(i -> Set.copyOf(i.getArgument(0)));
        operations = support.transactionalProxy(new WorkflowEngineOperations(new WorkflowAuthenticationContext(engine.getIdentityService(), new WorkflowIdentityCodec()), new WorkflowExceptionTranslator(), identities));
        WfTaskSlaMapper actual = WorkflowH2SchemaMapperSupport.createSpringMapper(support.dataSource(), "sla", WfTaskSlaMapper.class, "mapper/flowable/WfTaskSlaMapper.xml");
        mapper = spy(actual);
        doAnswer(i -> {
            WfBusinessCalendar value = actual.selectCalendarByKey(i.getArgument(0));
            if (value == null) return null;
            WfBusinessCalendar observed = spy(value); reads.add(observed); return observed;
        }).when(mapper).selectCalendarByKey(anyString());
        calendars = spy(new WorkflowBusinessCalendarService(operations, mapper));
        calendarId = calendars.create(request("Asia/Shanghai", "09:00", "18:00", false));
        var writer = support.transactionalProxy(new WorkflowNotificationWriter(jdbc, null));
        sla = support.transactionalProxy(new WorkflowTaskSlaRuntimeService(repository, engine.getManagementService(), engine.getProcessEngineConfiguration(), calendars, operations, mapper, artifacts, writer));
        var identityMapper = mock(WorkflowIdentityMapper.class);
        var participants = new WorkflowParticipantRuleRuntimeService(repository, engine.getRuntimeService(), artifacts, identityMapper, identities, mock(WorkflowParticipantResolutionMetrics.class));
        var listener = new WorkflowUserTaskListener(new WorkflowUserTaskAuditService(engine.getTaskService(), new WorkflowIdentityCodec()), sla, participants,
                mock(WorkflowAutomaticCopyService.class), mock(WorkflowNotificationService.class), mock(WorkflowMultiInstanceRoundLifecycleService.class));
        var configuration = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
        configuration.getExpressionManager().setBeans(Map.of("userTaskListener", listener, "workflowSlaTimerDelegate", new WorkflowSlaTimerDelegate(sla)));
        configuration.setBusinessCalendarManager(new WorkflowSlaBusinessCalendarManager(configuration::getClock, calendars));
        configuration.getJobServiceConfiguration().setBusinessCalendarManager(configuration.getBusinessCalendarManager());
        var categories = mock(WfCategoryMapper.class);
        var category = new WfCategory(); category.setCode("test"); category.setCategoryName("SLA 验收"); category.setDelFlag("0");
        when(categories.selectByCode("test")).thenReturn(category);
        var forms = mock(WfFormMapper.class);
        var form = new WfForm(); form.setFormId(7L); form.setFormName("申请"); form.setDelFlag("0");
        form.setContent("{\"fields\":[{\"layout\":\"colFormItem\",\"tag\":\"el-input\",\"vModel\":\"note\",\"workflowHidden\":false,\"workflowReadable\":true,\"workflowWritable\":true}]}");
        when(forms.selectById(7L)).thenReturn(form);
        var fields = new WorkflowFormFieldExtensionService(null);
        var templates = new WorkflowFormTemplateValidator();
        models = new WorkflowModelService(operations, repository, new WorkflowBpmnService(repository, fields, null), new WorkflowBpmnIdentityValidator(identityMapper),
                categories, forms, artifacts, templates, new WorkflowExtensionDeploymentService(null, null, null, null), new WorkflowControlledLoopDeploymentService(),
                new WorkflowConditionDeploymentService(), new WorkflowParticipantRuleDeploymentService(identityMapper), new WorkflowDmnDecisionService(operations, null, artifacts), fields,
                new WorkflowCallActivityReferenceService(repository, operations, identities, participants, artifacts, templates), new WorkflowTaskSlaDeploymentService(calendars, null, identities), new WorkflowRuntimeTaskListenerCompiler());
    }

    protected WorkflowFlowableEngineTestSupport startDatabase() throws Exception
    { return WorkflowFlowableEngineTestSupport.start("c11", Map.of()); }
    protected void prepareSchema()
    {
        jdbc.execute("set mode MySQL");
        WorkflowH2SchemaMapperSupport.executeSchema(support.dataSource(), WorkflowH2SchemaMapperSupport.TASK_SLA_SCHEMA);
        WorkflowH2SchemaMapperSupport.executeSchema(support.dataSource(), "db/h2/wf_sla_calendar_integration.sql");
    }
    protected void closeDatabase() { }
    @AfterEach void teardown()
    {
        try { if (support != null) support.close(); }
        finally { closeDatabase(); }
    }

    @ParameterizedTest
    @CsvSource({
        "Asia/Shanghai,09:00,18:00,2026-09-18T09:30:00Z,60,180,2026-09-21T01:30:00Z,2026-09-21T03:30:00Z",
        "Asia/Shanghai,09:00,18:00,2026-09-18T00:00:00Z,60,180,2026-09-18T02:00:00Z,2026-09-18T04:00:00Z",
        "Asia/Shanghai,09:00,18:00,2026-09-18T10:00:00Z,60,180,2026-09-21T02:00:00Z,2026-09-21T04:00:00Z",
        "America/New_York,00:00,04:00,2026-03-08T05:00:00Z,120,180,2026-03-08T07:00:00Z,2026-03-08T08:00:00Z",
        "America/New_York,00:00,04:00,2026-11-01T04:00:00Z,120,180,2026-11-01T06:00:00Z,2026-11-01T07:00:00Z",
        "America/New_York,09:00,18:00,2026-03-06T22:30:00Z,60,180,2026-03-09T13:30:00Z,2026-03-09T15:30:00Z",
        "America/New_York,09:00,18:00,2026-10-30T21:30:00Z,60,180,2026-11-02T14:30:00Z,2026-11-02T16:30:00Z"
    })
    void realTimersAndUtcExecutionRespectWindowsWeekendsAndDst(String zone, String open, String close, String start, int reminder, int escalation, String first, String last)
    {
        calendars.update(calendarId, request(zone, open, close, "00:00".equals(open)));
        String deployment = deploy(reminder, escalation, false);
        resetReadObservation();
        Task task = start(deployment, start);
        assertDue(task, first, last);
        verify(calendars, times(1)).resolveSlaDueDates("WORKDAY", Instant.parse(start), reminder, escalation);
        // Two reminders and one escalation each use Flowable's independent single-point entry.
        verify(calendars, times(3)).resolveDueAt(eq("WORKDAY"), eq(Instant.parse(start)), anyInt());
        verify(mapper, times(4)).selectCalendarByKey("WORKDAY");
        assertThat(reads).hasSize(4);
        reads.forEach(this::assertParsedOnce);
    }

    @Test void holidayAndMakeupOverrideWeekAndOnePairConsumesOneActualRead()
    {
        var base = request("Asia/Shanghai", "09:00", "18:00", false);
        calendars.update(calendarId, new WorkflowBusinessCalendarRequest(base.calendarKey(), base.calendarName(), base.timezone(), base.workingDays(), base.workStart(), base.workEnd(), null,
                List.of(new WorkflowBusinessCalendarRequest.CalendarDayRequest(LocalDate.parse("2026-09-21"), false, "假日"),
                        new WorkflowBusinessCalendarRequest.CalendarDayRequest(LocalDate.parse("2026-09-19"), true, "补班"))));
        resetReadObservation();
        var dates = calendars.resolveSlaDueDates("WORKDAY", Instant.parse("2026-09-18T09:30:00Z"), 60, 660);
        assertThat(dates.reminderDueAt()).isEqualTo(Instant.parse("2026-09-19T01:30:00Z"));
        assertThat(dates.escalationDueAt()).isEqualTo(Instant.parse("2026-09-22T02:30:00Z"));
        verify(mapper, times(1)).selectCalendarByKey("WORKDAY");
        assertThat(reads).singleElement().satisfies(this::assertParsedOnce);
        assertDue(start(deploy(60, 660, false), "2026-09-18T09:30:00Z"), "2026-09-19T01:30:00Z", "2026-09-22T02:30:00Z");
    }

    @Test void actualReminderRepeatAndEscalationWriteNotificationsAuditAndCompleteGeneratedTask()
    {
        Task task = start(deploy(60, 180, false), "2026-09-18T09:30:00Z");
        fire(task, "reminder_1");
        assertThat(execution(task).getRemindersSent()).isOne();
        assertThat(engine.getTaskService().createTaskQuery().taskId(task.getId()).count()).isOne();
        assertNotification(task, "REMINDER", 100, 1);
        support.transactionTemplate().executeWithoutResult(status -> sla.handleTimer(task.getProcessInstanceId(), task.getProcessDefinitionId(), "review", "REMINDER", 1, null));
        assertThat(count("wf_notification_inbox")).isOne();
        fire(task, "reminder_2");
        assertThat(execution(task).getRemindersSent()).isEqualTo(2);
        assertNotification(task, "REMINDER", 100, 2);
        fire(task, "escalation");
        assertThat(execution(task).getStatus()).isEqualTo("ESCALATED");
        Task upgraded = task(task.getProcessInstanceId());
        assertThat(upgraded.getTaskDefinitionKey()).contains("escalation");
        assertThat(upgraded.getAssignee()).isEqualTo("301");
        assertNotification(task, "ESCALATE", 301, 1);
        assertThat(audits(task)).containsExactly("CREATE:0", "REMINDER:1", "REMINDER:2", "ESCALATE:0");
        support.transactionTemplate().executeWithoutResult(status -> sla.handleTimer(task.getProcessInstanceId(), task.getProcessDefinitionId(), "review", "REMINDER", 2, null));
        assertThat(count("wf_notification_inbox")).isEqualTo(3);
        complete(upgraded);
        assertThat(execution(task).getStatus()).isEqualTo("COMPLETED");
        assertThat(engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(task.getProcessInstanceId()).finished().count()).isOne();
        assertThat(engine.getManagementService().createTimerJobQuery().processInstanceId(task.getProcessInstanceId()).count()).isZero();
    }

    @Test void disabledCalendarRejectsNewDeploymentButUpdatesOnlySubsequentTasksAndIndependentCalls()
    {
        String deployment = deploy(60, 180, true);
        String pendingModel = save(60, 180, false);
        Task first = start(deployment, "2026-09-18T09:30:00Z");
        var originalJobs = jobs(first).stream().map(job -> job.getId() + ":" + job.getDuedate().toInstant()).toList();
        calendars.update(calendarId, request("Asia/Shanghai", "10:00", "12:00", false));
        calendars.changeStatus(calendarId, false);
        long deployments = engine.getRepositoryService().createDeploymentQuery().count();
        assertThatThrownBy(() -> models.deployModel(pendingModel)).hasMessageContaining("业务日历未启用或不存在");
        assertThat(engine.getRepositoryService().createDeploymentQuery().count()).isEqualTo(deployments);
        assertDue(first, "2026-09-21T01:30:00Z", "2026-09-21T03:30:00Z");
        assertThat(jobs(first).stream().map(job -> job.getId() + ":" + job.getDuedate().toInstant()).toList()).isEqualTo(originalJobs);
        assertThat(artifacts.selectTaskSlaSnapshot(deployment, engine.getRepositoryService().getProcessDefinition(first.getProcessDefinitionId()).getKey(), "review").getWorkStart()).isEqualTo("09:00");
        complete(first);
        assertThat(execution(first).getStatus()).isEqualTo("COMPLETED");
        Task second = task(first.getProcessInstanceId());
        assertThat(second.getTaskDefinitionKey()).isEqualTo("next");
        assertDue(second, "2026-09-21T03:00:00Z", "2026-09-22T03:00:00Z");
        assertThat(calendars.resolveDueAt("WORKDAY", Instant.parse("2026-09-18T09:30:00Z"), 60)).isEqualTo(Instant.parse("2026-09-21T03:00:00Z"));
        fire(second, "reminder_1");
        assertThat(execution(second).getRemindersSent()).isOne();
    }

    @ParameterizedTest @ValueSource(strings = { "missing", "timezone", "weekdays", "scan" })
    void unreadableCalendarRollsBackActualTaskCreationAndLeavesNoPartialClock(String failure)
    {
        String deployment = deploy(60, 180, false);
        switch (failure)
        {
            case "missing" -> jdbc.update("delete from wf_business_calendar where calendar_id=?", calendarId);
            case "timezone" -> jdbc.update("update wf_business_calendar set timezone='No/SuchZone' where calendar_id=?", calendarId);
            case "weekdays" -> jdbc.update("update wf_business_calendar set working_days='1,1' where calendar_id=?", calendarId);
            case "scan" -> calendars.update(calendarId, new WorkflowBusinessCalendarRequest("WORKDAY", "稀疏工作周", "UTC", List.of(1), "09:00", "09:01", null, List.of()));
            default -> throw new AssertionError(failure);
        }
        // 180 minutes fits the sparse calendar; a separate long SLA exceeds the retained scan bound.
        if ("scan".equals(failure)) deployment = deploy(60, 6000, false);
        String id = deployment;
        Throwable error = catchThrowable(() -> start(id, "2026-09-18T09:30:00Z"));
        String expected = "missing".equals(failure) ? "审批 SLA 运行日历不存在" : "scan".equals(failure) ? "审批 SLA 业务日历无法解析到期时间" : "业务日历正式数据异常";
        assertThat(causes(error)).anySatisfy(cause -> assertThat(cause).isInstanceOfSatisfying(ServiceException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo(500); assertThat(exception.getMessage()).isEqualTo(expected);
        }));
        assertNoInstanceWrites();
    }

    @Test void databaseFailureKeepsSqlCauseAndRollsBackCreation()
    {
        String deployment = deploy(60, 180, false);
        jdbc.execute("alter table wf_business_calendar rename to c11_calendar_unavailable");
        try
        {
            Throwable error = catchThrowable(() -> start(deployment, "2026-09-18T09:30:00Z"));
            assertThat(causes(error)).anyMatch(java.sql.SQLException.class::isInstance);
            assertNoInstanceWrites();
        }
        finally { jdbc.execute("alter table c11_calendar_unavailable rename to wf_business_calendar"); }
    }

    @ParameterizedTest @CsvSource({ "reminder_1,recipient", "escalation,recipient", "reminder_1,database", "escalation,database" })
    void failedRequiredNotificationRollsBackTimerAuditStateAndEngineThenRetrySucceeds(String action, String failure)
    {
        Task task = start(deploy(60, 180, false), "2026-09-18T09:30:00Z");
        int recipient = "escalation".equals(action) ? 301 : 100;
        if ("recipient".equals(failure)) jdbc.update("update sys_user set status='1' where user_id=?", recipient);
        else jdbc.execute("alter table wf_notification_inbox rename to c11_inbox_unavailable");
        String executable = executable(task, action);
        Throwable error = catchThrowable(() -> engine.getManagementService().executeJob(executable));
        if ("recipient".equals(failure)) assertThat(causes(error)).anySatisfy(cause -> assertThat(cause).isInstanceOf(ServiceException.class).hasMessage("审批 SLA 通知接收人无效"));
        else
        {
            jdbc.execute("alter table c11_inbox_unavailable rename to wf_notification_inbox");
            assertThat(causes(error)).anyMatch(java.sql.SQLException.class::isInstance);
        }
        assertThat(execution(task).getStatus()).isEqualTo("ACTIVE");
        assertThat(execution(task).getRemindersSent()).isZero();
        assertThat(execution(task).getRevision()).isZero();
        assertThat(audits(task)).containsExactly("CREATE:0");
        assertThat(count("wf_notification_inbox")).isZero();
        assertThat(task(task.getProcessInstanceId()).getId()).isEqualTo(task.getId());
        assertThat(engine.getManagementService().createTimerJobQuery().processInstanceId(task.getProcessInstanceId()).list())
                .anySatisfy(job -> { assertThat(job.getElementId()).contains(action); assertThat(job.getRetries()).isEqualTo(2); });
        jdbc.update("update sys_user set status='0' where user_id=?", recipient);
        fire(task, action);
        assertThat(execution(task).getStatus()).isEqualTo("escalation".equals(action) ? "ESCALATED" : "ACTIVE");
        assertNotification(task, "escalation".equals(action) ? "ESCALATE" : "REMINDER", recipient, 1);
    }

    @Test void createAuditDatabaseFailureRollsBackClockTaskAndHistory()
    {
        String deployment = deploy(60, 180, false);
        jdbc.execute("alter table wf_task_sla_audit rename to c11_audit_unavailable");
        Throwable error;
        try { error = catchThrowable(() -> start(deployment, "2026-09-18T09:30:00Z")); }
        finally { jdbc.execute("alter table c11_audit_unavailable rename to wf_task_sla_audit"); }
        assertThat(causes(error)).anyMatch(java.sql.SQLException.class::isInstance);
        assertNoInstanceWrites();
    }

    @Test void failedSecondTaskCalendarRestoresFirstTaskCompletionAndAudit()
    {
        Task first = start(deploy(60, 180, true), "2026-09-18T09:30:00Z");
        var due = jobs(first).stream().map(Job::getId).toList();
        jdbc.update("update wf_business_calendar set working_days='8' where calendar_id=?", calendarId);
        assertThatThrownBy(() -> complete(first)).hasMessageContaining("业务日历正式数据异常");
        assertThat(task(first.getProcessInstanceId()).getId()).isEqualTo(first.getId());
        assertThat(execution(first).getStatus()).isEqualTo("ACTIVE");
        assertThat(audits(first)).containsExactly("CREATE:0");
        assertThat(count("wf_task_sla_execution")).isOne();
        assertThat(jobs(first).stream().map(Job::getId).toList()).containsExactlyInAnyOrderElementsOf(due);
    }

    @Test void minuteBoundariesRejectBeforeReadAndSingleEntryRemainsIndependent()
    {
        Instant now = Instant.parse("2026-09-18T01:00:00Z");
        resetReadObservation();
        for (int invalid : List.of(0, -1, 2635201, Integer.MAX_VALUE))
        {
            assertThatThrownBy(() -> calendars.resolveSlaDueDates("WORKDAY", now, 1, invalid)).isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(400));
            assertThatThrownBy(() -> calendars.resolveDueAt("WORKDAY", now, invalid)).hasMessage("审批 SLA 工作分钟不合法");
        }
        assertThatThrownBy(() -> calendars.resolveSlaDueDates("WORKDAY", null, 1, 2)).hasMessage("审批 SLA 工作分钟不合法");
        verify(mapper, never()).selectCalendarByKey(anyString());
        calendars.update(calendarId, request("UTC", "00:00", "23:59", true));
        assertThat(calendars.resolveDueAt("WORKDAY", Instant.parse("2026-01-01T00:00:00Z"), 2635200)).isEqualTo(Instant.parse("2031-01-06T06:31:00Z"));
        resetReadObservation();
        assertThat(calendars.resolveDueAt("WORKDAY", now, 1)).isEqualTo(now.plusSeconds(60));
        assertThat(reads).singleElement().satisfies(this::assertParsedOnce);
    }

    protected String deploy(int reminder, int escalation, boolean chain) { return models.deployModel(save(reminder, escalation, chain)); }
    private String save(int reminder, int escalation, boolean chain)
    {
        var repository = engine.getRepositoryService(); String key = "slaCalendar" + (++sequence);
        String xml = author(key, reminder, escalation, chain);
        var model = repository.newModel(); model.setKey(key); model.setName(key); model.setCategory("test"); model.setMetaInfo("{}"); repository.saveModel(model);
        repository.addModelEditorSource(model.getId(), xml.getBytes(StandardCharsets.UTF_8));
        var dto = new WorkflowModelDto(); dto.setModelId(model.getId()); dto.setExpectedRevision(((ModelEntity) repository.getModel(model.getId())).getRevision()); dto.setBpmnXml(xml);
        models.saveModel(dto);
        assertThat(models.preflight(model.getId(), ((ModelEntity) repository.getModel(model.getId())).getRevision(), xml).valid()).isTrue();
        return model.getId();
    }
    protected Task start(String deployment, String time)
    {
        engine.getProcessEngineConfiguration().getClock().setCurrentTime(Date.from(Instant.parse(time)));
        var definition = engine.getRepositoryService().createProcessDefinitionQuery().deploymentId(deployment).singleResult();
        String instance = operations.writeAsCurrentUser(actor -> engine.getRuntimeService().startProcessInstanceById(definition.getId(), Map.of("initiator", "100", "note", "验收")).getId());
        return task(instance);
    }
    private Task task(String instance) { return engine.getTaskService().createTaskQuery().processInstanceId(instance).singleResult(); }
    private void complete(Task task) { operations.writeAsCurrentUser(actor -> { engine.getTaskService().complete(task.getId()); return null; }); }
    protected WfTaskSlaExecution execution(Task task) { return mapper.selectExecutionByTaskForUpdate(task.getId()); }
    private List<Job> jobs(Task task) { return engine.getManagementService().createTimerJobQuery().processInstanceId(task.getProcessInstanceId()).orderByJobDuedate().asc().list(); }
    protected void fire(Task task, String part) { engine.getManagementService().executeJob(executable(task, part)); }
    private String executable(Task task, String part)
    {
        Job timer = jobs(task).stream().filter(job -> job.getElementId().contains(part)).findFirst().orElseThrow();
        engine.getProcessEngineConfiguration().getClock().setCurrentTime(timer.getDuedate());
        return engine.getManagementService().moveTimerToExecutableJob(timer.getId()).getId();
    }
    private void assertDue(Task task, String reminder, String escalation)
    {
        assertThat(execution(task).getReminderDueAt()).isEqualTo(LocalDateTime.ofInstant(Instant.parse(reminder), ZoneOffset.UTC));
        assertThat(execution(task).getEscalationDueAt()).isEqualTo(LocalDateTime.ofInstant(Instant.parse(escalation), ZoneOffset.UTC));
        assertThat(jobs(task)).hasSize(3);
        assertThat(jobs(task).stream().filter(job -> job.getElementId().contains("reminder_1")).findFirst().orElseThrow().getDuedate().toInstant()).isEqualTo(Instant.parse(reminder));
        assertThat(jobs(task).stream().filter(job -> job.getElementId().contains("escalation")).findFirst().orElseThrow().getDuedate().toInstant()).isEqualTo(Instant.parse(escalation));
    }
    private void assertNotification(Task task, String action, int recipient, int count)
    {
        assertThat(jdbc.queryForObject("select count(*) from wf_notification_inbox where source_type='SLA' and event_type=? and recipient_user_id=? and task_id=? and process_instance_id=? and read_status='UNREAD'", Long.class, action, recipient, task.getId(), task.getProcessInstanceId())).isEqualTo(count);
        assertThat(jdbc.queryForObject("select count(*) from wf_notification_inbox n join wf_task_sla_audit a on n.source_id=cast(a.audit_id as char) where n.task_id=?", Long.class, task.getId())).isEqualTo(execution(task).getRemindersSent() + ("ESCALATED".equals(execution(task).getStatus()) ? 1 : 0));
    }
    private List<String> audits(Task task) { return jdbc.queryForList("select concat(action_type,':',action_ordinal) from wf_task_sla_audit where sla_execution_id=? order by audit_id", String.class, execution(task).getSlaExecutionId()); }
    private long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
    private void assertNoInstanceWrites()
    {
        for (String table : List.of("wf_task_sla_execution", "wf_task_sla_audit", "wf_notification_inbox", "ACT_RU_TASK", "ACT_RU_EXECUTION", "ACT_RU_TIMER_JOB", "ACT_HI_PROCINST", "ACT_HI_COMMENT")) assertThat(count(table)).as(table).isZero();
    }
    private void resetReadObservation() { reads.clear(); clearInvocations(mapper, calendars); }
    private void assertParsedOnce(WfBusinessCalendar calendar)
    {
        verify(calendar, times(1)).getTimezone(); verify(calendar, times(1)).getWorkingDays();
        verify(calendar, times(1)).getWorkStart(); verify(calendar, times(1)).getWorkEnd(); verify(calendar, times(1)).getDays();
    }
    private List<Throwable> causes(Throwable error)
    {
        assertThat(error).isNotNull(); var result = new ArrayList<Throwable>();
        for (Throwable current = error; current != null; current = current.getCause()) result.add(current);
        return result;
    }
    private WorkflowBusinessCalendarRequest request(String zone, String open, String close, boolean everyDay)
    { return new WorkflowBusinessCalendarRequest("WORKDAY", "工作日", zone, everyDay ? List.of(1,2,3,4,5,6,7) : List.of(1,2,3,4,5), open, close, null, List.of()); }
    private String author(String key, int reminder, int escalation, boolean chain)
    {
        String properties = "<extensionElements><flowable:properties>" + rule("assignment", "STARTER") + property("assignment.formField", "")
                + property("sla.enabled", "true") + property("sla.calendarKey", "WORKDAY") + property("sla.reminderMinutes", reminder) + property("sla.reminderRepeatMinutes", 30)
                + property("sla.maxReminders", 2) + property("sla.escalationMinutes", escalation) + property("sla.escalationUserId", 301) + property("sla.escalationEventCode", "") + "</flowable:properties></extensionElements>";
        return """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="urn:c11">
            <process id="%s" isExecutable="true"><extensionElements><flowable:properties>%s</flowable:properties></extensionElements>
            <startEvent id="start" flowable:formKey="key_7"><extensionElements><flowable:formProperty id="approva_permission_default" type="string" readable="true" writable="true"/><flowable:formProperty id="approva_permission_field_note" variable="note" type="string" readable="true" writable="true"/></extensionElements></startEvent>
            <userTask id="review" name="审批">%s</userTask><endEvent id="end"/>
            <sequenceFlow id="begin" sourceRef="start" targetRef="review"/><sequenceFlow id="finish" sourceRef="review" targetRef="%s"/>%s
            </process></definitions>
            """.formatted(key, rule("startScope", "PUBLIC"), properties, chain ? "next" : "end", chain ? "<userTask id=\"next\" name=\"后续审批\">" + properties + "</userTask><sequenceFlow id=\"nextEnd\" sourceRef=\"next\" targetRef=\"end\"/>" : "").trim();
    }
    private String rule(String scope, String type) { return property(scope + ".ruleVersion", 1) + property(scope + ".type", type) + property(scope + ".targetIds", "") + property(scope + ".noMatchPolicy", "FAIL"); }
    private String property(String name, Object value) { return "<flowable:property name=\"approva." + name + "\" value=\"" + value + "\"/>"; }

    /** H2 SQL function adapters only; production MySQL functions run in the inherited IT. */
    public static String formatTime(String value, String format) { return LocalTime.parse(value).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")); }
    public static String sha2(String value, int bits) throws java.security.NoSuchAlgorithmException
    { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-" + bits).digest(value.getBytes(StandardCharsets.UTF_8))); }
}

package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.job.api.Job;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.service.model.WorkflowPreparedSlaDeployment;
import com.ruoyi.flowable.service.model.WorkflowTaskSlaDeploymentService;
import com.ruoyi.flowable.service.task.WorkflowTaskSlaRuntimeService;

/**
 * 使用真实 MySQL、Flowable 定时作业和异步执行器验证审批 SLA 完整运行闭环。
 */
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(classes = RuoYiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.datasource.druid.master.url=${FLOWABLE_IT_JDBC_URL}",
            "spring.datasource.druid.master.username=${FLOWABLE_IT_USERNAME}",
            "spring.datasource.druid.master.password=${FLOWABLE_IT_PASSWORD}",
            "spring.data.redis.database=${FLOWABLE_IT_REDIS_DATABASE:15}",
            "token.secret=Y29ubmVjdG9yLWJwbW4tZXZlbnQtY2hhaW4taXQtdG9rZW4tc2VjcmV0LWNvbm5lY3Rvci1icG1uLWV2ZW50LWNoYWluLWl0LXRva2VuLXNlY3JldA==",
            "flowable.database-schema-update=false",
            "flowable.async-executor-activate=true",
            "flowable.async-history-executor-activate=false",
            "spring.quartz.auto-startup=false"
        })
class WorkflowTaskSlaMySqlIT
{
    private static final String PREFIX = "workflow-sla-it-";

    @Autowired private ProcessEngine processEngine;
    @Autowired private RepositoryService repositoryService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private TaskService taskService;
    @Autowired private ManagementService managementService;
    @Autowired private WorkflowTaskSlaDeploymentService deploymentService;
    @Autowired private WorkflowTaskSlaRuntimeService runtimeSlaService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private final String runId = UUID.randomUUID().toString().replace("-", "");
    private String calendarKey;
    private String approverUserId;
    private String deploymentId;
    private Instant originalClock;

    /**
     * 创建全周业务日历并选取具备真实审批权限的有效用户。
     * @return void，正式主数据不完整时测试立即失败
     */
    @BeforeEach
    void setUp()
    {
        originalClock = engineConfiguration().getClock().getCurrentTime().toInstant();
        calendarKey = ("SLA_IT_" + runId).toUpperCase();
        jdbc.update("insert into wf_business_calendar "
                + "(calendar_key,calendar_name,timezone,working_days,work_start,work_end,status,description,create_by,create_time,update_by) "
                + "values (?,?,'UTC','1,2,3,4,5,6,7','00:00','23:59','ENABLED','真实 SLA IT','1',current_timestamp(3),'')",
                calendarKey, "SLA IT " + runId);
        approverUserId = jdbc.queryForObject(
                "select cast(min(u.user_id) as char) from sys_user u "
                + "join sys_user_role ur on ur.user_id=u.user_id "
                + "join sys_role r on r.role_id=ur.role_id "
                + "join sys_role_menu rm on rm.role_id=r.role_id "
                + "join sys_menu m on m.menu_id=rm.menu_id "
                + "where u.status='0' and u.del_flag='0' and r.status='0' and r.del_flag='0' "
                + "and m.status='0' and m.perms='workflow:process:approval'",
                String.class);
        assertThat(approverUserId).isNotBlank();
    }

    /**
     * 精确清理本测试部署、SLA 正式数据和日历，并恢复 Flowable 引擎时钟。
     * @return void，清理仅匹配本轮稳定前缀
     */
    @AfterEach
    void tearDown()
    {
        engineConfiguration().getClock().setCurrentTime(Date.from(originalClock));
        if (deploymentId != null)
        {
            jdbc.update("delete n from wf_task_sla_notification n join wf_task_sla_audit a on a.audit_id=n.audit_id "
                    + "join wf_task_sla_execution e on e.sla_execution_id=a.sla_execution_id where e.deployment_id=?", deploymentId);
            jdbc.update("delete a from wf_task_sla_audit a join wf_task_sla_execution e on e.sla_execution_id=a.sla_execution_id where e.deployment_id=?", deploymentId);
            jdbc.update("delete from wf_task_sla_execution where deployment_id=?", deploymentId);
            jdbc.update("delete from wf_deploy_task_sla where deployment_id=?", deploymentId);
            if (repositoryService.createDeploymentQuery().deploymentId(deploymentId).count() > 0)
            {
                repositoryService.deleteDeployment(deploymentId, true);
            }
        }
        jdbc.update("delete from wf_business_calendar_day where calendar_id in "
                + "(select calendar_id from wf_business_calendar where calendar_key=?)", calendarKey);
        jdbc.update("delete from wf_business_calendar where calendar_key=?", calendarKey);
        Authentication.setAuthenticatedUserId(null);
    }

    /**
     * 验证真实异步执行器依次执行两个非中断提醒和中断升级，并创建人工升级任务。
     * @return void，作业、通知、审计、原任务或升级任务状态不一致时失败
     */
    @Test
    void executesRepeatedRemindersAndHumanEscalationThroughAsyncExecutor()
    {
        ProcessInstance instance = deployAndStart(2, 3);
        Instant start = engineConfiguration().getClock().getCurrentTime().toInstant();

        advanceClock(start.plusSeconds(90));
        await(() -> countAudit(instance.getId(), "REMINDER") == 1, Duration.ofSeconds(15));
        assertThat(taskService.createTaskQuery().processInstanceId(instance.getId()).count()).isEqualTo(1);

        advanceClock(start.plusSeconds(150));
        await(() -> countAudit(instance.getId(), "REMINDER") == 2, Duration.ofSeconds(15));
        assertThat(countNotifications(instance.getId(), "REMINDER")).isEqualTo(2);

        advanceClock(start.plusSeconds(240));
        await(() -> countAudit(instance.getId(), "ESCALATE") == 1, Duration.ofSeconds(15));
        List<Task> tasks = taskService.createTaskQuery().processInstanceId(instance.getId()).list();
        assertThat(tasks).singleElement().satisfies(task ->
        {
            assertThat(task.getTaskDefinitionKey()).contains("approva_sla_approve_escalation_user_task");
            assertThat(task.getAssignee()).isEqualTo(approverUserId);
        });
        assertThat(countNotifications(instance.getId(), "ESCALATE")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from wf_task_sla_execution where process_instance_id=?",
                String.class, instance.getId())).isEqualTo("ESCALATED");
    }

    /**
     * 验证重复并发提醒只有一套状态、审计和通知副作用。
     * @return void，唯一键、行锁或幂等状态条件失效时失败
     * @throws Exception 并发线程执行失败
     */
    @Test
    void deduplicatesConcurrentReminderInRealTransactions() throws Exception
    {
        ProcessInstance instance = deployAndStart(1, 3);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try
        {
            Future<?> first = executor.submit(() -> invokeReminder(start, instance.getId()));
            Future<?> second = executor.submit(() -> invokeReminder(start, instance.getId()));
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        finally
        {
            executor.shutdownNow();
        }
        assertThat(countAudit(instance.getId(), "REMINDER")).isEqualTo(1);
        assertThat(countNotifications(instance.getId(), "REMINDER")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select reminders_sent from wf_task_sla_execution where process_instance_id=?",
                Integer.class, instance.getId())).isEqualTo(1);
    }

    /**
     * 验证通知失败使提醒状态和审计完整回滚，并验证任务完成后定时器无副作用。
     * @return void，部分提交或完成后残留作业产生记录时失败
     */
    @Test
    void rollsBackNotificationFailureAndCancelsJobsAfterCompletion()
    {
        ProcessInstance instance = deployAndStart(1, 3);
        jdbc.update("update wf_task_sla_execution set assignee_user_id='999999999999999999' where process_instance_id=?",
                instance.getId());
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                runtimeSlaService.handleTimer(instance.getId(), instance.getProcessDefinitionId(),
                        "approve", "REMINDER", 1, null)))
                .isInstanceOf(ServiceException.class).hasMessageContaining("接收人无效");
        assertThat(countAudit(instance.getId(), "REMINDER")).isZero();
        assertThat(countNotifications(instance.getId(), "REMINDER")).isZero();
        assertThat(jdbc.queryForObject("select reminders_sent from wf_task_sla_execution where process_instance_id=?",
                Integer.class, instance.getId())).isZero();

        jdbc.update("update wf_task_sla_execution set assignee_user_id=? where process_instance_id=?",
                approverUserId, instance.getId());
        Task task = taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult();
        Authentication.setAuthenticatedUserId(approverUserId);
        taskService.complete(task.getId());
        Authentication.setAuthenticatedUserId(null);
        assertThat(managementService.createTimerJobQuery().processInstanceId(instance.getId()).count()).isZero();
        transaction.executeWithoutResult(status -> runtimeSlaService.handleTimer(
                instance.getId(), instance.getProcessDefinitionId(), "approve", "REMINDER", 1, null));
        assertThat(countAudit(instance.getId(), "REMINDER")).isZero();
    }

    /**
     * 验证挂起期间真实 timer job 不执行，恢复时作业与 SLA 到期时间同步平移；非法模型部署前零副作用拒绝。
     * @return void，作业未平移、挂起产生通知或非法模型进入仓储时失败
     */
    @Test
    void reschedulesRealTimerJobsOnResumeAndRejectsIllegalDeployment()
    {
        ProcessInstance instance = deployAndStart(1, 3);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Job before = managementService.createTimerJobQuery().processInstanceId(instance.getId())
                .orderByJobDuedate().asc().list().get(0);
        transaction.executeWithoutResult(status ->
        {
            runtimeService.suspendProcessInstanceById(instance.getId());
            runtimeSlaService.pauseInstance(instance.getId(), approverUserId);
        });
        Instant resumeClock = engineConfiguration().getClock().getCurrentTime().toInstant()
                .plus(Duration.ofHours(2));
        advanceClock(resumeClock);
        transaction.executeWithoutResult(status ->
        {
            runtimeService.activateProcessInstanceById(instance.getId());
            runtimeSlaService.resumeInstance(instance.getId(), approverUserId);
        });
        Job shifted = managementService.createTimerJobQuery().processInstanceId(instance.getId())
                .orderByJobDuedate().asc().list().get(0);
        assertThat(Duration.between(before.getDuedate().toInstant(), shifted.getDuedate().toInstant()))
                .isGreaterThanOrEqualTo(Duration.ofMinutes(119));
        assertThat(countAudit(instance.getId(), "PAUSE")).isEqualTo(1);
        assertThat(countAudit(instance.getId(), "RESUME")).isEqualTo(1);

        long deploymentsBefore = repositoryService.createDeploymentQuery().count();
        assertThatThrownBy(() -> deploymentService.prepare(
                bpmn(2, 2).getBytes(StandardCharsets.UTF_8), approverUserId))
                .isInstanceOf(ServiceException.class).hasMessageContaining("晚于最后一次提醒");
        assertThat(repositoryService.createDeploymentQuery().count()).isEqualTo(deploymentsBefore);
    }

    /** @param start CountDownLatch，并发起点；@param instanceId String，实例主键；@return void，事务动作完成。 */
    private void invokeReminder(CountDownLatch start, String instanceId)
    {
        try
        {
            start.await(5, TimeUnit.SECONDS);
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            {
                String definitionId = jdbc.queryForObject(
                        "select process_definition_id from wf_task_sla_execution where process_instance_id=?",
                        String.class, instanceId);
                runtimeSlaService.handleTimer(instanceId, definitionId, "approve",
                        "REMINDER", 1, null);
            });
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    /** @param reminders int，提醒次数；@param escalationMinutes int，升级分钟；@return ProcessInstance，真实运行实例。 */
    private ProcessInstance deployAndStart(int reminders, int escalationMinutes)
    {
        WorkflowPreparedSlaDeployment prepared = deploymentService.prepare(
                bpmn(reminders, escalationMinutes).getBytes(StandardCharsets.UTF_8),
                approverUserId);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        deploymentId = transaction.execute(status ->
        {
            Deployment deployment = repositoryService.createDeployment()
                    .name(PREFIX + runId).key(PREFIX + runId)
                    .addBytes("sla.bpmn20.xml", prepared.compiledBpmn()).deploy();
            deploymentService.persist(deployment.getId(), prepared);
            return deployment.getId();
        });
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "sla_" + runId, java.util.Map.of("initiator", approverUserId));
        assertThat(instance).isNotNull();
        assertThat(managementService.createTimerJobQuery().processInstanceId(instance.getId()).count())
                .isEqualTo(reminders + 1L);
        return instance;
    }

    /** @param reminders int，提醒次数；@param escalationMinutes int，升级分钟；@return String，完整作者 BPMN。 */
    private String bpmn(int reminders, int escalationMinutes)
    {
        return ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:flowable="http://flowable.org/bpmn" targetNamespace="Examples">
                  <process id="sla_%s" isExecutable="true">
                    <startEvent id="start"/><sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="审批" flowable:assignee="%s">
                      <extensionElements>
                        <flowable:taskListener event="create" delegateExpression="${userTaskListener}"/>
                        <flowable:taskListener event="assignment" delegateExpression="${userTaskListener}"/>
                        <flowable:taskListener event="complete" delegateExpression="${userTaskListener}"/>
                        <flowable:properties>
                          <flowable:property name="approva.sla.enabled" value="true"/>
                          <flowable:property name="approva.sla.calendarKey" value="%s"/>
                          <flowable:property name="approva.sla.reminderMinutes" value="1"/>
                          <flowable:property name="approva.sla.reminderRepeatMinutes" value="1"/>
                          <flowable:property name="approva.sla.maxReminders" value="%d"/>
                          <flowable:property name="approva.sla.escalationMinutes" value="%d"/>
                          <flowable:property name="approva.sla.escalationUserId" value="%s"/>
                          <flowable:property name="approva.sla.escalationEventCode" value=""/>
                        </flowable:properties>
                      </extensionElements>
                    </userTask>
                    <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/><endEvent id="end"/>
                  </process>
                </definitions>
                """).formatted(runId, approverUserId, calendarKey, reminders,
                        escalationMinutes, approverUserId);
    }

    /** @param target Instant，新的引擎时钟；@return void，异步执行器按该时钟获取到期作业。 */
    private void advanceClock(Instant target)
    {
        engineConfiguration().getClock().setCurrentTime(Date.from(target));
    }

    /** @param condition BooleanSupplier，完成条件；@param timeout Duration，最大等待；@return void，超时断言失败。 */
    private void await(BooleanSupplier condition, Duration timeout)
    {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline))
        {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(100L); }
            catch (InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
        assertThat(condition.getAsBoolean()).as("真实异步 SLA 动作应在超时前完成").isTrue();
    }

    /** @param instanceId String，实例主键；@param action String，动作；@return int，真实审计数量。 */
    private int countAudit(String instanceId, String action)
    {
        return jdbc.queryForObject("select count(*) from wf_task_sla_audit a join wf_task_sla_execution e "
                + "on e.sla_execution_id=a.sla_execution_id where e.process_instance_id=? and a.action_type=?",
                Integer.class, instanceId, action);
    }

    /** @param instanceId String，实例主键；@param action String，动作；@return int，真实通知数量。 */
    private int countNotifications(String instanceId, String action)
    {
        return jdbc.queryForObject("select count(*) from wf_task_sla_notification n "
                + "join wf_task_sla_audit a on a.audit_id=n.audit_id "
                + "join wf_task_sla_execution e on e.sla_execution_id=a.sla_execution_id "
                + "where e.process_instance_id=? and n.action_type=?",
                Integer.class, instanceId, action);
    }

    /** @return SpringProcessEngineConfiguration，真实可控时钟与异步执行器配置。 */
    private SpringProcessEngineConfiguration engineConfiguration()
    {
        return (SpringProcessEngineConfiguration) processEngine.getProcessEngineConfiguration();
    }
}

package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.Set;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.flowable.domain.dto.WorkflowManualUrgeRequest;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService.DeliveryOutcome;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService.OutboxRow;

/**
 * 使用真实 MySQL、Flowable 事务和正式通知表验证普通审批通知可靠闭环。
 */
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(classes = RuoYiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.datasource.druid.master.url=${FLOWABLE_IT_JDBC_URL}",
            "spring.datasource.druid.master.username=${FLOWABLE_IT_USERNAME}",
            "spring.datasource.druid.master.password=${FLOWABLE_IT_PASSWORD}",
            "spring.data.redis.database=${FLOWABLE_IT_REDIS_DATABASE:15}",
            "token.secret=Y29kZXgtd29ya2Zsb3ctbm90aWZpY2F0aW9uLW15c3FsLWl0LXNlY3JldC1jb2RleC13b3JrZmxvdy1ub3RpZmljYXRpb24=",
            "flowable.database-schema-update=false",
            "flowable.async-executor-activate=false",
            "flowable.async-history-executor-activate=false",
            "flowable.notification.worker-enabled=false",
            "spring.quartz.auto-startup=false"
        })
class WorkflowNotificationMySqlIT
{
    @Autowired private RepositoryService repositoryService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private TaskService taskService;
    @Autowired private WorkflowNotificationService notificationService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    /** 本轮测试稳定前缀，只清理自身 Flowable 与 wf_copy 数据。 */
    private final String runId = UUID.randomUUID().toString().replace("-", "");
    private String starterUserId;
    private String approverUserId;
    private String deploymentId;
    private ProcessInstance instance;

    /**
     * 清空隔离库通知队列并选择真实有效的发起人和审批人。
     * @return void，正式用户或通知基线缺失时测试立即失败
     */
    @BeforeEach
    void setUp()
    {
        jdbc.update("delete from wf_notification_delivery_audit");
        jdbc.update("delete from wf_notification_inbox");
        jdbc.update("delete from wf_notification_outbox");
        jdbc.update("delete from wf_notification_urge_audit");
        starterUserId = activeUserForPermission("workflow:process:start");
        approverUserId = activeUserForPermissionExcluding(
                "workflow:process:approval", starterUserId);
        assertThat(starterUserId).isNotBlank();
        assertThat(approverUserId).isNotBlank();
        assertThat(jdbc.queryForObject(
                "select count(*) from wf_notification_policy where status='ENABLED'",
                Integer.class)).isGreaterThanOrEqualTo(15);
    }

    /**
     * 删除本轮流程、抄送和通知记录，并清理 Flowable 认证上下文。
     * @return void，仅操作隔离库和本轮 runId 对象
     */
    @AfterEach
    void tearDown()
    {
        Authentication.setAuthenticatedUserId(null);
        SecurityContextHolder.clearContext();
        jdbc.update("delete from wf_notification_delivery_audit");
        jdbc.update("delete from wf_notification_inbox");
        jdbc.update("delete from wf_notification_outbox");
        jdbc.update("delete from wf_notification_urge_audit");
        jdbc.update("delete from wf_copy where copy_event_id like ?", "COPY_IT_" + runId + "%");
        if (deploymentId != null
                && repositoryService.createDeploymentQuery().deploymentId(deploymentId).count() > 0)
        {
            repositoryService.deleteDeployment(deploymentId, true);
        }
    }

    /**
     * 验证任务到达幂等、站内持久化、租约过期接管和重复投递不重复建信。
     * @return void，任一 outbox、inbox 或状态事实不一致时失败
     */
    @Test
    void deliversArrivalExactlyOnceAndTakesOverExpiredLease()
    {
        startProcess();
        assertThat(countOutbox("TASK_ARRIVED")).isEqualTo(1);
        Task task = taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult();

        // 同一任务到达事件再次进入事务时必须命中相同幂等键，不新增 outbox。
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                notificationService.onTaskEvent("create", task.getId(), task.getProcessInstanceId(),
                        task.getProcessDefinitionId(), task.getTaskDefinitionKey(), task.getName(),
                        task.getAssignee(), task.getOwner()));
        assertThat(countOutbox("TASK_ARRIVED")).isEqualTo(1);

        OutboxRow firstClaim = notificationService.claimNext("worker-a");
        assertThat(firstClaim).isNotNull();
        notificationService.deliverInbox(firstClaim, "worker-a");
        assertThat(countInbox()).isEqualTo(1);

        // 模拟进程在提交站内信后状态确认前重启，过期租约由新 worker 接管且 inbox 唯一键去重。
        jdbc.update("update wf_notification_outbox set status='DELIVERING',processed_time=null," +
                "lease_owner='dead-worker',lease_expires_at=?,revision=revision+1 where outbox_id=?",
                Timestamp.from(Instant.now().minusSeconds(10)), firstClaim.outboxId());
        OutboxRow takeover = notificationService.claimNext("worker-b");
        assertThat(takeover).isNotNull();
        notificationService.deliverInbox(takeover, "worker-b");
        assertThat(countInbox()).isEqualTo(1);
        assertThat(outboxStatus(firstClaim.outboxId())).isEqualTo("PROCESSED");
    }

    /**
     * 验证失效站内接收人进入死信，临时 SMTP 失败有界重试并允许管理员补偿。
     * @return void，失败状态、次数或补偿状态不一致时失败
     */
    @Test
    void deadLettersInvalidRecipientAndRetriesTransientDelivery()
    {
        long inboxOutboxId = insertOutbox("INBOX", approverUserId, 3);
        String originalStatus = jdbc.queryForObject(
                "select status from sys_user where user_id=?", String.class, approverUserId);
        try
        {
            jdbc.update("update sys_user set status='1' where user_id=?", approverUserId);
            OutboxRow invalid = notificationService.claimNext("worker-invalid");
            notificationService.deliverInbox(invalid, "worker-invalid");
            assertThat(outboxStatus(inboxOutboxId)).isEqualTo("DEAD_LETTER");
            assertThat(countInbox()).isZero();
        }
        finally
        {
            jdbc.update("update sys_user set status=? where user_id=?", originalStatus, approverUserId);
        }

        long emailOutboxId = insertOutbox("EMAIL", approverUserId, 2);
        OutboxRow first = notificationService.claimNext("worker-retry");
        notificationService.completeDelivery(first, "worker-retry",
                DeliveryOutcome.failure("SMTP_TIMEOUT", "SMTP 投递超时", false));
        assertThat(outboxStatus(emailOutboxId)).isEqualTo("RETRYING");
        jdbc.update("update wf_notification_outbox set next_attempt_at=? where outbox_id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), emailOutboxId);
        OutboxRow second = notificationService.claimNext("worker-retry");
        notificationService.completeDelivery(second, "worker-retry",
                DeliveryOutcome.failure("SMTP_5XX", "SMTP 服务暂时不可用", false));
        assertThat(outboxStatus(emailOutboxId)).isEqualTo("DEAD_LETTER");
        authenticate(starterUserId);
        notificationService.compensate(emailOutboxId);
        SecurityContextHolder.clearContext();
        assertThat(outboxStatus(emailOutboxId)).isEqualTo("RETRYING");
    }

    /**
     * 验证流程事务回滚不留下结果通知，并验证正式 wf_copy 事实的幂等消费入口。
     * @return void，回滚或 copy-created 幂等契约失效时失败
     */
    @Test
    void rollsBackWithFlowableTransactionAndConsumesFormalCopyFact()
    {
        startProcess();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status ->
        {
            notificationService.onProcessResult("PROCESS_CANCELED",
                    instance.getProcessDefinitionId(), instance.getId());
            status.setRollbackOnly();
        });
        assertThat(countOutbox("PROCESS_CANCELED")).isZero();

        jdbc.update("insert into wf_copy (copy_event_id,title,process_id,process_name,category_id," +
                "deployment_id,instance_id,task_id,user_id,originator_id,originator_name,create_by," +
                "create_time,remark,del_flag) values (?,?,?,?,?,?,?,?,?,?,?,?,current_timestamp(3),?,'0')",
                "COPY_IT_" + runId, "审批抄送", instance.getProcessDefinitionId(),
                "通知集成测试", "", deploymentId, instance.getId(), null,
                Long.valueOf(approverUserId), Long.valueOf(starterUserId), starterUserId,
                starterUserId, "通知入口集成测试");
        long copyId = jdbc.queryForObject(
                "select copy_id from wf_copy where copy_event_id=? and user_id=?",
                Long.class, "COPY_IT_" + runId, Long.valueOf(approverUserId));
        transaction.executeWithoutResult(status ->
        {
            assertThat(notificationService.onCopyCreated(copyId)).isEqualTo(1);
            assertThat(notificationService.onCopyCreated(copyId)).isZero();
        });
        assertThat(countOutbox("COPY_CREATED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from wf_copy where copy_id=?",
                Integer.class, copyId)).isEqualTo(1);
    }

    /**
     * 验证发起人催办真实待办、频率限制、越权拒绝和流程结束拒绝，且催办不改变审批状态。
     * @return void，权限、状态、审计或 outbox 任一事实不一致时失败
     */
    @Test
    void enforcesUrgeAuthorizationFrequencyAndRunningState()
    {
        startProcess();
        Task task = taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult();
        authenticate(starterUserId);
        var first = notificationService.urge(new WorkflowManualUrgeRequest(
                instance.getId(), "请及时处理真实待办"));
        assertThat(first.get("recipientCount")).isEqualTo(1);
        assertThat(countOutbox("MANUAL_URGE")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from wf_notification_urge_audit where process_instance_id=?",
                Integer.class, instance.getId())).isEqualTo(1);
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId()).count()).isEqualTo(1);
        assertThat(taskService.createTaskQuery().taskId(task.getId()).count()).isEqualTo(1);

        assertThatThrownBy(() -> notificationService.urge(new WorkflowManualUrgeRequest(
                instance.getId(), "重复催办")))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(((ServiceException) exception).getCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        authenticate(approverUserId);
        assertThatThrownBy(() -> notificationService.urge(new WorkflowManualUrgeRequest(
                instance.getId(), "越权催办")))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(((ServiceException) exception).getCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        SecurityContextHolder.clearContext();
        Authentication.setAuthenticatedUserId(approverUserId);
        taskService.complete(task.getId());
        Authentication.setAuthenticatedUserId(null);
        authenticate(starterUserId);
        assertThatThrownBy(() -> notificationService.urge(new WorkflowManualUrgeRequest(
                instance.getId(), "结束后催办")))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(((ServiceException) exception).getCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * 部署并启动带固定 userTaskListener 的真实流程。
     * @return void，创建的部署和实例写入当前测试字段
     */
    private void startProcess()
    {
        String processKey = "notification_" + runId;
        String bpmn = ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:flowable="http://flowable.org/bpmn" targetNamespace="Examples">
                  <process id="%s" name="通知集成测试" isExecutable="true">
                    <startEvent id="start"/><sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="真实审批" flowable:assignee="%s">
                      <extensionElements>
                        <flowable:taskListener event="create" delegateExpression="${userTaskListener}"/>
                        <flowable:taskListener event="assignment" delegateExpression="${userTaskListener}"/>
                        <flowable:taskListener event="complete" delegateExpression="${userTaskListener}"/>
                      </extensionElements>
                    </userTask>
                    <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/><endEvent id="end"/>
                  </process>
                </definitions>
                """).formatted(processKey, approverUserId);
        Deployment deployment = repositoryService.createDeployment()
                .name("workflow-notification-it-" + runId)
                .addBytes("notification.bpmn20.xml", bpmn.getBytes(StandardCharsets.UTF_8))
                .deploy();
        deploymentId = deployment.getId();
        Authentication.setAuthenticatedUserId(starterUserId);
        instance = runtimeService.startProcessInstanceByKey(processKey);
        Authentication.setAuthenticatedUserId(null);
        assertThat(instance).isNotNull();
    }

    /**
     * 查询具备指定实时菜单权限的最小有效用户。
     * @param permission String，正式 sys_menu 权限标识
     * @return String，规范用户主键
     */
    private String activeUserForPermission(String permission)
    {
        return jdbc.queryForObject("select cast(min(u.user_id) as char) from sys_user u " +
                "join sys_user_role ur on ur.user_id=u.user_id join sys_role r on r.role_id=ur.role_id " +
                "join sys_role_menu rm on rm.role_id=r.role_id join sys_menu m on m.menu_id=rm.menu_id " +
                "where u.status='0' and u.del_flag='0' and r.status='0' and r.del_flag='0' " +
                "and m.status='0' and m.perms=?", String.class, permission);
    }

    /**
     * 查询具备指定权限且不是给定用户的有效用户，用于真实越权分支。
     * @param permission String，正式 sys_menu 权限标识
     * @param excludedUserId String，不允许命中的用户主键
     * @return String，另一名规范用户主键
     */
    private String activeUserForPermissionExcluding(String permission, String excludedUserId)
    {
        return jdbc.queryForObject("select cast(min(u.user_id) as char) from sys_user u " +
                "join sys_user_role ur on ur.user_id=u.user_id join sys_role r on r.role_id=ur.role_id " +
                "join sys_role_menu rm on rm.role_id=r.role_id join sys_menu m on m.menu_id=rm.menu_id " +
                "where u.status='0' and u.del_flag='0' and r.status='0' and r.del_flag='0' " +
                "and m.status='0' and m.perms=? and u.user_id<>?", String.class,
                permission, Long.valueOf(excludedUserId));
    }

    /** @param eventType String，事件类型；@return int，当前隔离测试 outbox 数量。 */
    private int countOutbox(String eventType)
    {
        return jdbc.queryForObject("select count(*) from wf_notification_outbox where event_type=?",
                Integer.class, eventType);
    }

    /** @return int，当前隔离测试 inbox 数量。 */
    private int countInbox()
    {
        return jdbc.queryForObject("select count(*) from wf_notification_inbox", Integer.class);
    }

    /** @param outboxId long，outbox 主键；@return String，正式状态。 */
    private String outboxStatus(long outboxId)
    {
        return jdbc.queryForObject("select status from wf_notification_outbox where outbox_id=?",
                String.class, outboxId);
    }

    /**
     * 插入一条只用于 worker 失败路径的正式 outbox 记录。
     * @param channel String，INBOX 或 EMAIL
     * @param recipientUserId String，真实用户主键
     * @param maxAttempts int，最大尝试次数
     * @return long，新增 outbox 主键
     */
    private long insertOutbox(String channel, String recipientUserId, int maxAttempts)
    {
        String key = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("insert into wf_notification_outbox (idempotency_key,event_type,channel," +
                "recipient_user_id,process_definition_key,process_instance_id,title,content,route_path," +
                "status,attempt_count,max_attempts,next_attempt_at,revision,create_time) " +
                "values (?,'MANUAL_URGE',?,?,? ,?,'测试通知','脱敏测试正文','/workflow/process-detail/test'," +
                "'PENDING',0,?,current_timestamp(3),0,current_timestamp(3))",
                key, channel, Long.valueOf(recipientUserId), "notification-it", runId, maxAttempts);
        return jdbc.queryForObject("select outbox_id from wf_notification_outbox where idempotency_key=?",
                Long.class, key);
    }

    /**
     * 为需要当前用户审计的服务方法建立真实用户主键对应的 Spring Security 身份。
     * @param userId String，隔离库中的有效用户主键
     * @return void，身份在测试完成或显式调用后清理
     */
    private void authenticate(String userId)
    {
        long numericUserId = Long.parseLong(userId);
        SysUser user = new SysUser();
        user.setUserId(numericUserId);
        user.setUserName("workflow_notification_it_" + userId);
        user.setNickName("通知集成测试用户");
        LoginUser loginUser = new LoginUser(numericUserId, null, user, Set.of());
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        loginUser, null, loginUser.getAuthorities());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }
}

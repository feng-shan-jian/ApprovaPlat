package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.beans.factory.annotation.Value;
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
import com.ruoyi.flowable.domain.dto.WorkflowNotificationPolicyRequest;
import com.ruoyi.flowable.engine.WorkflowProcessEngineAdapter;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.service.notification.WorkflowManualUrgeService;
import com.ruoyi.flowable.service.notification.WorkflowNotificationDeliveryCoordinator;
import com.ruoyi.flowable.service.notification.WorkflowNotificationDeliveryResult;
import com.ruoyi.flowable.service.notification.WorkflowNotificationOutboxRecord;
import com.ruoyi.flowable.service.notification.WorkflowNotificationOutboxService;
import com.ruoyi.flowable.service.notification.WorkflowNotificationPolicyService;
import com.ruoyi.flowable.service.notification.WorkflowNotificationRegistrar;
import com.ruoyi.flowable.service.task.WorkflowTaskCopyAction;
import com.ruoyi.flowable.service.task.WorkflowTaskCopyService;

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
            "flowable.it.expected-schema=${FLOWABLE_IT_EXPECTED_SCHEMA}",
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
    /** 仅允许显式带有验收或集成测试语义的 ApprovaPlat 隔离库执行全量通知清理。 */
    private static final Pattern ISOLATED_SCHEMA_PATTERN = Pattern.compile(
            "^(?:approvaplat_(?:accept|acceptance|it|test)_[a-z0-9]+(?:_[a-z0-9]+)*"
                    + "|ry_vue(?:_codex)?_flowable_it"
                    + "|wf_arch_[0-9]{8}_flowable_it)$");

    /** 本轮通知事实的稳定过滤条件，兼容真实流程事实和直接插入的 worker 失败事实。 */
    private static final String OWN_OUTBOX_FILTER =
            "(o.process_instance_id=? or (o.process_definition_key like 'notification%' " +
                    "and locate(?,o.process_definition_key)>0))";

    /** 同一 Failsafe JVM 只重置一次共享通知队列，后续用例继续按 runId 精确清理。 */
    private static boolean sharedNotificationQueueReset;

    @Autowired private RepositoryService repositoryService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private TaskService taskService;
    @Autowired private WorkflowNotificationRegistrar notificationRegistrar;
    @Autowired private WorkflowNotificationPolicyService notificationPolicyService;
    @Autowired private WorkflowNotificationOutboxService notificationOutboxService;
    @Autowired private WorkflowNotificationDeliveryCoordinator notificationDeliveryCoordinator;
    @Autowired private WorkflowManualUrgeService manualUrgeService;
    @Autowired private WorkflowProcessEngineAdapter workflowProcessEngineAdapter;
    @Autowired private WorkflowTaskCopyService taskCopyService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Value("${flowable.it.expected-schema}")
    private String expectedSchema;

    /** 本轮测试稳定前缀，用于隔离通知、Flowable 与 wf_copy 事实。 */
    private final String runId = UUID.randomUUID().toString().replace("-", "");
    private String starterUserId;
    private String approverUserId;
    private String copyUserId;
    private String transferTargetUserId;
    private String deploymentId;
    private final List<String> deploymentIds = new ArrayList<>();
    private final List<String> processInstanceIds = new ArrayList<>();
    /** 本轮测试创建的真实权限用户主键，按外键逆序精确回收。 */
    private final List<Long> permissionActorUserIds = new ArrayList<>();
    private ProcessInstance instance;

    /**
     * 校验测试库连接、清理本轮残留事实并选择真实有效的发起人和审批人。
     * @return void，正式用户或通知基线缺失时测试立即失败
     */
    @BeforeEach
    void setUp()
    {
        if (!sharedNotificationQueueReset)
        {
            clearSharedNotificationQueue();
            sharedNotificationQueueReset = true;
        }
        clearNotificationFacts();
        createPermissionActors();
        starterUserId = activeUserForPermission("workflow:process:start");
        approverUserId = activeUserForPermissionExcluding(
                "workflow:process:approval", starterUserId);
        copyUserId = copyEligibleUser();
        transferTargetUserId = activeUserForPermissionExcluding(
                "workflow:process:approval", approverUserId);
        assertThat(starterUserId).isNotBlank();
        assertThat(approverUserId).isNotBlank();
        assertThat(copyUserId).isNotBlank();
        assertThat(transferTargetUserId).isNotBlank().isNotEqualTo(approverUserId);
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
        clearNotificationFacts();
        jdbc.update("delete from wf_notification_policy where process_definition_key like ?",
                "notification_policy_" + runId + "%");
        jdbc.update("delete from wf_copy where copy_event_id like ?", "COPY_IT_" + runId + "%");
        for (String processInstanceId : processInstanceIds)
        {
            // 自动和人工抄送使用正式事件键，按本轮实例主键清理可覆盖全部测试事实。
            jdbc.update("delete from wf_copy where instance_id=?", processInstanceId);
        }
        for (String id : deploymentIds)
        {
            if (repositoryService.createDeploymentQuery().deploymentId(id).count() > 0)
            {
                repositoryService.deleteDeployment(id, true);
            }
        }
        deletePermissionActors();
    }

    /**
     * 在单个数据库事务中按外键依赖顺序清理本轮通知测试事实。
     * @return void，先精确删除本轮 inbox 子表，再删除本轮 outbox 主表
     */
    private void clearNotificationFacts()
    {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
        {
            // 库名查询与通知表清理必须复用同一事务连接，避免连接池切换绕过 fail-closed 门禁。
            requireNotificationCleanupSchema();
            // 先按本轮 outbox 关联删除 inbox，满足旧版本外键约束且不触碰共享库其他通知。
            jdbc.update("delete n from wf_notification_inbox n " +
                    "join wf_notification_outbox o on o.outbox_id=n.outbox_id where " +
                    OWN_OUTBOX_FILTER, runId, runId);
            assertThat(countInbox()).isZero();
            // outbox 与其子表清理共用提交边界，避免留下半清理状态。
            jdbc.update("delete o from wf_notification_outbox o where " + OWN_OUTBOX_FILTER,
                    runId, runId);
            assertThat(countOwnedOutbox()).isZero();
        });
    }

    /**
     * 在通知 IT 首次执行前清理同一隔离库中其他集成测试遗留的通知投递事实。
     * @return void，仅在期望库与当前库一致且具备隔离测试命名时清空 inbox/outbox
     */
    private void clearSharedNotificationQueue()
    {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
        {
            // 全套 IT 共享同一临时库，先删除投影再删除 outbox，避免历史候选影响租约顺序。
            requireNotificationCleanupSchema();
            jdbc.update("delete from wf_notification_inbox");
            jdbc.update("delete from wf_notification_outbox");
            assertThat(jdbc.queryForObject("select count(*) from wf_notification_inbox",
                    Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from wf_notification_outbox",
                    Integer.class)).isZero();
        });
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
        assertThat(jdbc.queryForObject("select count(*) from wf_notification_outbox where " +
                "process_instance_id=? and " +
                "event_type in ('TASK_CLAIMED','TASK_UNCLAIMED','TASK_DELEGATED'," +
                "'TASK_DELEGATION_RESOLVED','TASK_TRANSFERRED')", Integer.class,
                instance.getId())).isZero();
        Task task = taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult();

        // 同一任务到达事件再次进入事务时必须命中相同幂等键，不新增 outbox。
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                notificationRegistrar.onTaskEvent("create", task.getId(), task.getProcessInstanceId(),
                        task.getProcessDefinitionId(), task.getTaskDefinitionKey(), task.getName(),
                        task.getAssignee(), task.getOwner()));
        assertThat(countOutbox("TASK_ARRIVED")).isEqualTo(1);

        WorkflowNotificationOutboxRecord firstClaim = claimNextForRun("worker-a");
        assertThat(firstClaim).isNotNull();
        notificationDeliveryCoordinator.deliverClaimed(firstClaim, "worker-a");
        assertThat(countInbox()).isEqualTo(1);

        // 模拟进程在提交站内信后状态确认前重启，过期租约由新 worker 接管且 inbox 唯一键去重。
        jdbc.update("update wf_notification_outbox set status='DELIVERING',processed_time=null," +
                "lease_owner='dead-worker',lease_expires_at=?,revision=revision+1 where outbox_id=?",
                Timestamp.from(Instant.now().minusSeconds(10)), firstClaim.outboxId());
        WorkflowNotificationOutboxRecord takeover = claimNextForRun("worker-b");
        assertThat(takeover).isNotNull();
        notificationDeliveryCoordinator.deliverClaimed(takeover, "worker-b");
        assertThat(countInbox()).isEqualTo(1);
        assertThat(outboxStatus(firstClaim.outboxId())).isEqualTo("PROCESSED");
    }

    /**
     * 验证两个独立事务并发登记同一来源事件时，唯一键重放只把真实插入方计为新增。
     * @return void，重复 outbox、重复 ENQUEUE 审计或异常后的事实复核失效时测试失败
     * @throws Exception 并发执行或等待失败
     */
    @Test
    void deduplicatesConcurrentOutboxRegistrationAcrossTransactions() throws Exception
    {
        startProcess();
        Task task = taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
        {
            // 同一事务连接先校验隔离库，再清理本用例要重新并发登记的到达事实。
            requireNotificationCleanupSchema();
            jdbc.update("delete from wf_notification_outbox where task_id=? and event_type='TASK_ARRIVED'",
                    task.getId());
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            Future<Integer> first = executor.submit(() -> concurrentTaskArrival(ready, start, task));
            Future<Integer> second = executor.submit(() -> concurrentTaskArrival(ready, start, task));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            // 普通 INSERT 与 DuplicateKeyException 分支各返回一次，且两个事务都必须正常提交。
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(1, 0);
        }
        finally
        {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from wf_notification_outbox " +
                "where task_id=? and event_type='TASK_ARRIVED'", Integer.class, task.getId())).isEqualTo(1);
    }

    /**
     * 查询当前 JDBC 会话的真实库名，并在执行通知事实精确清理前做 fail-closed 校验。
     *
     * @return void，期望库缺失、当前库不一致或库名不具备隔离测试语义时立即拒绝清理
     */
    private void requireNotificationCleanupSchema()
    {
        String currentSchema = jdbc.queryForObject("select database()", String.class);
        requireIsolatedNotificationSchema(expectedSchema, currentSchema);
    }

    /**
     * 校验通知 MySQL IT 的期望库、当前库和测试命名契约。
     *
     * @param expectedSchema String，必填环境变量 FLOWABLE_IT_EXPECTED_SCHEMA 映射出的期望库名
     * @param currentSchema String，当前 JDBC 连接执行 select database() 得到的真实库名
     * @return void，两个库名完全一致且符合验收或集成测试命名时正常返回
     */
    static void requireIsolatedNotificationSchema(String expectedSchema, String currentSchema)
    {
        if (expectedSchema == null || expectedSchema.isBlank())
        {
            throw new IllegalStateException("FLOWABLE_IT_EXPECTED_SCHEMA 必须显式配置");
        }
        if (!expectedSchema.equals(currentSchema))
        {
            throw new IllegalStateException("通知 MySQL IT 当前库与期望隔离库不一致");
        }
        if (!ISOLATED_SCHEMA_PATTERN.matcher(expectedSchema).matches())
        {
            throw new IllegalStateException("通知 MySQL IT 仅允许使用明确命名的隔离测试或验收库");
        }
    }

    /**
     * 验证首次登记后修改通知策略，重复业务事件仍保留原 outbox 的标题、正文和重试上限。
     * @return void，策略重算覆盖冻结快照或合法重放返回错误时测试失败
     */
    @Test
    void replaysFrozenOutboxAfterPolicyUpdate()
    {
        String processKey = "notification_policy_" + runId + "_replay";
        authenticate(starterUserId);
        Map<String, Object> saved = notificationPolicyService.savePolicy(
                new WorkflowNotificationPolicyRequest(null, "PROCESS", processKey, null,
                        "TASK_ARRIVED", "TASK_RECIPIENT", "INBOX", null,
                        "首次-{{taskName}}", "首次正文-{{processInstanceId}}", 3,
                        "ENABLED", null));
        SecurityContextHolder.clearContext();
        startSingleProcess(processKey, "策略重放流程", true, null);
        Task task = taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult();
        Map<String, Object> frozen = jdbc.queryForMap("select outbox_id,title,content,max_attempts " +
                "from wf_notification_outbox where task_id=? and event_type='TASK_ARRIVED'", task.getId());

        authenticate(starterUserId);
        notificationPolicyService.savePolicy(new WorkflowNotificationPolicyRequest(
                ((Number) saved.get("policyId")).longValue(), "PROCESS", processKey, null,
                "TASK_ARRIVED", "TASK_RECIPIENT", "INBOX", null,
                "更新-{{taskName}}", "更新正文-{{processInstanceId}}", 9,
                "ENABLED", ((Number) saved.get("revision")).intValue()));
        SecurityContextHolder.clearContext();

        Integer replayed = new TransactionTemplate(transactionManager).execute(status ->
                notificationRegistrar.onTaskEvent("create", task.getId(), task.getProcessInstanceId(),
                        task.getProcessDefinitionId(), task.getTaskDefinitionKey(), task.getName(),
                        task.getAssignee(), task.getOwner()));
        assertThat(replayed).isZero();
        assertThat(jdbc.queryForMap("select outbox_id,title,content,max_attempts " +
                "from wf_notification_outbox where task_id=? and event_type='TASK_ARRIVED'", task.getId()))
                .isEqualTo(frozen);
    }

    /**
     * 验证相同来源键若被错误绑定到另一真实流程实例，稳定业务身份校验会失败并回滚。
     * @return void，进程实例或路由错配被当作合法幂等重放时测试失败
     */
    @Test
    void rejectsStableIdentityMismatchOnReplay()
    {
        startProcess();
        ProcessInstance originalInstance = instance;
        Task originalTask = taskService.createTaskQuery()
                .processInstanceId(originalInstance.getId()).singleResult();
        long originalOutboxId = jdbc.queryForObject("select outbox_id from wf_notification_outbox " +
                "where task_id=? and event_type='TASK_ARRIVED'", Long.class, originalTask.getId());
        ProcessInstance alternateInstance = startAdditionalInstance(originalInstance.getProcessDefinitionId());
        int outboxCountBefore = countOutbox("TASK_ARRIVED");

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                notificationRegistrar.onTaskEvent("create", originalTask.getId(), alternateInstance.getId(),
                        originalTask.getProcessDefinitionId(), originalTask.getTaskDefinitionKey(),
                        originalTask.getName(), originalTask.getAssignee(), originalTask.getOwner())))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("幂等事实不一致");
        assertThat(countOutbox("TASK_ARRIVED")).isEqualTo(outboxCountBefore);
        assertThat(jdbc.queryForObject("select process_instance_id from wf_notification_outbox " +
                "where outbox_id=?", String.class, originalOutboxId)).isEqualTo(originalInstance.getId());
    }

    /**
     * 验证同一 outbox 已存在错误站内信投影时不会被幂等分支吞掉并假完成。
     * @return void，错配事实未回滚或 outbox 被标记成功时测试失败
     */
    @Test
    void rejectsMismatchedExistingInboxWithoutFakeSuccess()
    {
        long outboxId = insertOutbox("INBOX", approverUserId, 3);
        WorkflowNotificationOutboxRecord claimed = claimNextForRun("worker-inbox-mismatch");
        assertThat(claimed).isNotNull();
        jdbc.update("insert into wf_notification_inbox (outbox_id,notification_key,source_type,source_id," +
                "recipient_user_id,event_type,title,content,process_instance_id,task_id,route_path," +
                "read_status,create_time) " +
                "select outbox_id,sha2(concat_ws('|',source_type,source_id,event_type),256)," +
                "source_type,source_id,recipient_user_id,'TASK_COMPLETED','错误标题',content," +
                "process_instance_id,task_id,route_path,'UNREAD',current_timestamp(3) " +
                "from wf_notification_outbox where outbox_id=?", outboxId);

        notificationDeliveryCoordinator.deliverClaimed(claimed, "worker-inbox-mismatch");
        assertThat(outboxStatus(outboxId)).isEqualTo("RETRYING");
        assertThat(jdbc.queryForObject("select last_error_code from wf_notification_outbox " +
                "where outbox_id=?", String.class, outboxId))
                .isEqualTo("DELIVERY_INTERNAL_ERROR");
        assertThat(jdbc.queryForObject("select processed_time is null from wf_notification_outbox " +
                "where outbox_id=?", Boolean.class, outboxId)).isTrue();
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
            WorkflowNotificationOutboxRecord invalid = claimNextForRun("worker-invalid");
            notificationDeliveryCoordinator.deliverClaimed(invalid, "worker-invalid");
            assertThat(outboxStatus(inboxOutboxId)).isEqualTo("DEAD_LETTER");
            assertThat(countInbox()).isZero();
        }
        finally
        {
            jdbc.update("update sys_user set status=? where user_id=?", originalStatus, approverUserId);
        }

        long emailOutboxId = insertOutbox("EMAIL", approverUserId, 2);
        WorkflowNotificationOutboxRecord first = claimNextForRun("worker-retry");
        notificationOutboxService.completeDelivery(first, "worker-retry",
                WorkflowNotificationDeliveryResult.failure(
                        "SMTP_TIMEOUT", "SMTP 投递超时", false));
        assertThat(outboxStatus(emailOutboxId)).isEqualTo("RETRYING");
        jdbc.update("update wf_notification_outbox set next_attempt_at=? where outbox_id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), emailOutboxId);
        WorkflowNotificationOutboxRecord second = claimNextForRun("worker-retry");
        assertThat(second.attemptCount()).isEqualTo(2);
        assertThat(second.totalAttemptCount()).isEqualTo(2);
        // 模拟最终一次 SMTP 已开始但进程退出；租约接管只能死信，不得把次数增加到上限之外。
        jdbc.update("update wf_notification_outbox set lease_expires_at=? where outbox_id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), emailOutboxId);
        assertThat(claimNextForRun("worker-final-lease")).isNull();
        assertThat(outboxStatus(emailOutboxId)).isEqualTo("DEAD_LETTER");
        assertOutboxSequence(emailOutboxId, 1, 2, 2);
        authenticate(starterUserId);
        notificationOutboxService.compensate(emailOutboxId);
        SecurityContextHolder.clearContext();
        assertThat(outboxStatus(emailOutboxId)).isEqualTo("RETRYING");
        assertOutboxSequence(emailOutboxId, 2, 0, 2);
        assertThat(jdbc.queryForObject("select processed_time is null from wf_notification_outbox " +
                "where outbox_id=?", Boolean.class, emailOutboxId)).isTrue();

        WorkflowNotificationOutboxRecord cycleTwoFirst = claimNextForRun("worker-cycle-two");
        notificationOutboxService.completeDelivery(cycleTwoFirst, "worker-cycle-two",
                WorkflowNotificationDeliveryResult.failure(
                        "SMTP_TIMEOUT", "SMTP 投递超时", false));
        jdbc.update("update wf_notification_outbox set next_attempt_at=? where outbox_id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), emailOutboxId);
        WorkflowNotificationOutboxRecord cycleTwoSecond = claimNextForRun("worker-cycle-two");
        notificationOutboxService.completeDelivery(cycleTwoSecond, "worker-cycle-two",
                WorkflowNotificationDeliveryResult.failure(
                        "SMTP_5XX", "SMTP 服务暂时不可用", false));
        assertOutboxSequence(emailOutboxId, 2, 2, 4);
        authenticate(starterUserId);
        notificationOutboxService.compensate(emailOutboxId);
        SecurityContextHolder.clearContext();
        assertOutboxSequence(emailOutboxId, 3, 0, 4);
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
            notificationRegistrar.onProcessResult("PROCESS_CANCELED",
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
            assertThat(notificationRegistrar.onCopyCreated(copyId)).isEqualTo(1);
            assertThat(notificationRegistrar.onCopyCreated(copyId)).isZero();
        });
        assertThat(countOutbox("COPY_CREATED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from wf_copy where copy_id=?",
                Integer.class, copyId)).isEqualTo(1);
    }

    /**
     * 验证自动与手工抄送均从正式 wf_copy 事实登记通知，通知登记失败会回滚抄送写入。
     * @return void，任一链路缺少正式事实、outbox 或失败零副作用时测试失败
     */
    @Test
    void createsCopyNotificationsAndRollsBackRegistrationFailure()
    {
        startProcessWithAutomaticArrival();
        Task task = taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult();
        assertThat(jdbc.queryForObject("select count(*) from wf_copy where copy_event_id=? " +
                "and user_id=? and source_type='AUTO' and trigger_type='NODE_ARRIVED'",
                Integer.class, "TASK_ARRIVED:" + task.getId(), Long.valueOf(copyUserId)))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from wf_notification_outbox where " +
                "process_instance_id=? and event_type='COPY_CREATED' and recipient_user_id=?",
                Integer.class, instance.getId(), Long.valueOf(copyUserId))).isEqualTo(1);

        WorkflowCurrentIdentity actor = new WorkflowCurrentIdentity(approverUserId, Set.of());
        WorkflowTaskCopyService.CopyPlan failingPlan = taskCopyService.prepare(
                WorkflowTaskCopyAction.REJECT, task, actor, List.of(Long.valueOf(copyUserId)));
        String failingEvent = failingPlan.copies().get(0).getCopyEventId();
        failingPlan.copies().get(0).setProcessId("missing-definition-" + runId);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> taskCopyService.persist(failingPlan)))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select count(*) from wf_copy where copy_event_id=?",
                Integer.class, failingEvent)).isZero();

        WorkflowTaskCopyService.CopyPlan successfulPlan = taskCopyService.prepare(
                WorkflowTaskCopyAction.COMPLETE, task, actor, List.of(Long.valueOf(copyUserId)));
        Authentication.setAuthenticatedUserId(approverUserId);
        try
        {
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            {
                taskService.complete(task.getId());
                taskCopyService.persist(successfulPlan);
            });
        }
        finally
        {
            Authentication.setAuthenticatedUserId(null);
        }
        assertThat(jdbc.queryForObject("select count(*) from wf_copy where copy_event_id=? " +
                "and user_id=? and source_type='MANUAL' and trigger_type='MANUAL_COMPLETE'",
                Integer.class, "TASK_COMPLETED:" + task.getId(), Long.valueOf(copyUserId)))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from wf_notification_outbox where " +
                "process_instance_id=? and event_type='COPY_CREATED' and recipient_user_id=?",
                Integer.class, instance.getId(), Long.valueOf(copyUserId))).isEqualTo(2);
    }

    /**
     * 验证全部实际通道关闭时催办返回 409，且事务不留下空 outbox。
     * @return void，关闭偏好仍返回成功或产生副作用时测试失败
     */
    @Test
    void rejectsUrgeWithoutDeliverableChannelAndLeavesNoOutbox()
    {
        startProcess();
        PreferenceSnapshot original = preferenceSnapshot(Long.valueOf(approverUserId));
        try
        {
            jdbc.update("insert into wf_notification_preference " +
                    "(user_id,inbox_enabled,email_enabled,sms_enabled,revision,update_time) " +
                    "values (?,0,1,0,0,current_timestamp(3)) on duplicate key update " +
                    "inbox_enabled=0,sms_enabled=0,revision=revision+1,update_time=current_timestamp(3)",
                    Long.valueOf(approverUserId));
            authenticate(starterUserId);
            assertThatThrownBy(() -> manualUrgeService.urge(new WorkflowManualUrgeRequest(
                    instance.getId(), "无可投递通道")))
                    .isInstanceOf(ServiceException.class)
                    .satisfies(exception -> assertThat(((ServiceException) exception).getCode())
                            .isEqualTo(HttpStatus.CONFLICT));
            assertThat(countOutbox("MANUAL_URGE")).isZero();
        }
        finally
        {
            SecurityContextHolder.clearContext();
            restorePreference(Long.valueOf(approverUserId), original);
        }
    }

    /**
     * 验证服务端固定通道顺序、直接参数门禁及模板渲染后的确定性字符上限。
     * @return void，DDL 非规范值、超长策略或渲染隐式截断进入数据库时测试失败
     */
    @Test
    void normalizesPolicyAndBoundsRenderedPayloads()
    {
        String processKey = "notification_policy_" + runId;
        authenticate(starterUserId);
        Map<String, Object> saved = notificationPolicyService.savePolicy(
                new WorkflowNotificationPolicyRequest(null, "PROCESS", processKey, null,
                        "TASK_ARRIVED", "TASK_RECIPIENT", "EMAIL,INBOX", null,
                        "{{processName}}{{processName}}", "{{processName}}".repeat(20),
                        6, "ENABLED", null));
        assertThat(saved.get("channels")).isEqualTo("INBOX,EMAIL");
        assertThatThrownBy(() -> notificationPolicyService.savePolicy(
                new WorkflowNotificationPolicyRequest(null, "PROCESS",
                        processKey + "_invalid_attempt", null, "TASK_ARRIVED",
                        "TASK_RECIPIENT", "INBOX", null, "标题", "正文", null,
                        "ENABLED", null)))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(((ServiceException) exception).getCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> notificationPolicyService.savePolicy(
                new WorkflowNotificationPolicyRequest(null, "PROCESS",
                        processKey + "_invalid_title", null, "TASK_ARRIVED",
                        "TASK_RECIPIENT", "INBOX", null, "x".repeat(161), "正文", 6,
                        "ENABLED", null)))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(((ServiceException) exception).getCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        SecurityContextHolder.clearContext();

        startSingleProcess(processKey, "长流程".repeat(80), true, null);
        assertThat(jdbc.queryForObject("select max(char_length(title)) from wf_notification_outbox " +
                "where process_definition_key=? and event_type='TASK_ARRIVED'",
                Integer.class, processKey)).isEqualTo(160);
        assertThat(jdbc.queryForObject("select max(char_length(content)) from wf_notification_outbox " +
                "where process_definition_key=? and event_type='TASK_ARRIVED'",
                Integer.class, processKey)).isEqualTo(700);
        assertThat(jdbc.queryForObject("select count(*) from wf_notification_outbox where " +
                "process_definition_key=? and event_type='TASK_ARRIVED'",
                Integer.class, processKey)).isEqualTo(2);
    }

    /**
     * 验证两个并发催办由运行实例锁与 Redis 原子冷却共同串行化，只有一方生成正式 outbox。
     * @return void，并发请求双成功、双失败或产生重复副作用时测试失败
     * @throws Exception 并发执行或等待失败
     */
    @Test
    void serializesConcurrentUrgesByFrequency() throws Exception
    {
        startProcess();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            Future<Integer> first = executor.submit(() -> concurrentUrge(ready, start, "并发催办一"));
            Future<Integer> second = executor.submit(() -> concurrentUrge(ready, start, "并发催办二"));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, HttpStatus.TOO_MANY_REQUESTS);
        }
        finally
        {
            executor.shutdownNow();
        }
        assertThat(countOutbox("MANUAL_URGE")).isEqualTo(1);
    }

    /**
     * 验证人工催办与任务完成竞争时，最终不存在指向已结束待办的可投递 outbox。
     * @return void，死锁、完成失败或遗留旧待办通知时测试失败
     * @throws Exception 并发执行或等待失败
     */
    @Test
    void serializesUrgeAgainstTaskCompletion() throws Exception
    {
        startProcess();
        String processInstanceId = instance.getId();
        Task task = taskService.createTaskQuery().processInstanceId(processInstanceId).singleResult();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            Future<Integer> urge = executor.submit(() -> concurrentUrge(
                    ready, start, "与完成竞争的催办"));
            Future<Void> completion = executor.submit(() ->
            {
                concurrentComplete(ready, start, task.getId());
                return null;
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            completion.get(20, TimeUnit.SECONDS);
            assertThat(urge.get(20, TimeUnit.SECONDS)).isIn(0, HttpStatus.CONFLICT);
        }
        finally
        {
            executor.shutdownNow();
        }
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).count()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from wf_notification_outbox " +
                "where process_instance_id=? and event_type='MANUAL_URGE' " +
                "and status in ('PENDING','RETRYING','DELIVERING')",
                Integer.class, processInstanceId)).isZero();
    }

    /**
     * 验证人工催办与永久转办竞争时，催办只面向最终办理人或被同事务取消。
     * @return void，转办失败或旧办理人仍有可投递催办时测试失败
     * @throws Exception 并发执行或等待失败
     */
    @Test
    void serializesUrgeAgainstTaskTransfer() throws Exception
    {
        startProcess();
        Task task = taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            Future<Integer> urge = executor.submit(() -> concurrentUrge(
                    ready, start, "与转办竞争的催办"));
            Future<Void> transfer = executor.submit(() ->
            {
                concurrentTransfer(ready, start, task.getId());
                return null;
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            transfer.get(20, TimeUnit.SECONDS);
            assertThat(urge.get(20, TimeUnit.SECONDS)).isZero();
        }
        finally
        {
            executor.shutdownNow();
        }
        assertThat(taskService.createTaskQuery().taskId(task.getId()).singleResult().getAssignee())
                .isEqualTo(transferTargetUserId);
        assertThat(jdbc.queryForObject("select count(*) from wf_notification_outbox where " +
                "process_instance_id=? and event_type='MANUAL_URGE' and recipient_user_id=? " +
                "and status in ('PENDING','RETRYING','DELIVERING')", Integer.class,
                instance.getId(), Long.valueOf(approverUserId))).isZero();
    }

    /**
     * 验证并行待办指向同一用户时接收人数按真实 outbox 用户去重，而非按任务数计数。
     * @return void，接收人数或 outbox 数量与实际持久化事实不一致时测试失败
     */
    @Test
    void countsUniqueRecipientsFromActualUrgeOutboxes()
    {
        startParallelProcess();
        authenticate(starterUserId);
        var result = manualUrgeService.urge(new WorkflowManualUrgeRequest(
                instance.getId(), "并行待办催办"));
        assertThat(result.recipientCount()).isEqualTo(1);
        assertThat(result.outboxCount()).isEqualTo(2);
        assertThat(result.urgeEventKey()).startsWith("URGE:");
        assertThat(countOutbox("MANUAL_URGE")).isEqualTo(2);
    }

    /**
     * 验证发起人催办真实待办、频率限制、越权拒绝和流程结束拒绝，且催办不改变审批状态。
     * @return void，权限、状态、Redis 冷却或 outbox 任一事实不一致时失败
     */
    @Test
    void enforcesUrgeAuthorizationFrequencyAndRunningState()
    {
        startProcess();
        Task task = taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult();
        // 清除节点到达通知，确保后续 claimNext 领取的是通过真实催办入口登记的目标 outbox。
        clearNotificationFacts();
        authenticate(starterUserId);
        var first = manualUrgeService.urge(new WorkflowManualUrgeRequest(
                instance.getId(), "请及时处理真实待办"));
        assertThat(first.recipientCount()).isEqualTo(1);
        assertThat(first.outboxCount()).isEqualTo(1);
        assertThat(first.urgeEventKey()).startsWith("URGE:");
        assertThat(countOutbox("MANUAL_URGE")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) " +
                "from wf_notification_outbox where process_instance_id=? " +
                "and event_type='MANUAL_URGE' and source_id like ? " +
                "and content like ?", Integer.class, instance.getId(),
                first.urgeEventKey() + ":%", "%请及时处理真实待办%")).isEqualTo(1);
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId()).count()).isEqualTo(1);
        assertThat(taskService.createTaskQuery().taskId(task.getId()).count()).isEqualTo(1);
        long urgeOutboxId = jdbc.queryForObject("select outbox_id from wf_notification_outbox " +
                "where process_instance_id=? and event_type='MANUAL_URGE'",
                Long.class, instance.getId());
        String terminalWorkerId = "worker-terminal-task";
        WorkflowNotificationOutboxRecord activeUrge = claimNextForRun(terminalWorkerId);
        assertThat(activeUrge).isNotNull();
        assertThat(activeUrge.outboxId()).isEqualTo(urgeOutboxId);
        assertThat(outboxStatus(urgeOutboxId)).isEqualTo("DELIVERING");

        assertThatThrownBy(() -> manualUrgeService.urge(new WorkflowManualUrgeRequest(
                instance.getId(), "重复催办")))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception ->
                {
                    ServiceException serviceException = (ServiceException) exception;
                    assertThat(serviceException.getCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(serviceException.getSubCode())
                            .isEqualTo("WORKFLOW_URGE_COOLDOWN_ACTIVE");
                });

        authenticate(approverUserId);
        assertThatThrownBy(() -> manualUrgeService.urge(new WorkflowManualUrgeRequest(
                instance.getId(), "越权催办")))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(((ServiceException) exception).getCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        SecurityContextHolder.clearContext();
        Authentication.setAuthenticatedUserId(approverUserId);
        taskService.complete(task.getId());
        Authentication.setAuthenticatedUserId(null);
        assertThat(outboxStatus(urgeOutboxId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForMap("select lease_owner,lease_expires_at,last_error_code " +
                "from wf_notification_outbox where outbox_id=?", urgeOutboxId))
                .containsEntry("last_error_code", "BUSINESS_OBJECT_COMPLETED")
                .containsEntry("lease_owner", null)
                .containsEntry("lease_expires_at", null);
        // 终态事务撤销活动租约后，成功或失败回写都不得覆盖 CANCELLED，也不得创建站内信。
        assertThatThrownBy(() -> notificationDeliveryCoordinator.deliverClaimed(
                activeUrge, terminalWorkerId))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(((ServiceException) exception).getCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> notificationOutboxService.completeDelivery(
                activeUrge, terminalWorkerId, WorkflowNotificationDeliveryResult.delivered()))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(((ServiceException) exception).getCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> notificationOutboxService.completeDelivery(
                activeUrge, terminalWorkerId, WorkflowNotificationDeliveryResult.failure(
                        "SMTP_DELIVERY_FAILED", "SMTP 投递失败", false)))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(((ServiceException) exception).getCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        assertThat(countInbox()).isZero();
        assertThat(outboxStatus(urgeOutboxId)).isEqualTo("CANCELLED");
        authenticate(starterUserId);
        assertThatThrownBy(() -> manualUrgeService.urge(new WorkflowManualUrgeRequest(
                instance.getId(), "结束后催办")))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(((ServiceException) exception).getCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * 验证没有 complete 任务监听器时，根流程完成监听器仍会取消尚未投递的催办。
     * @return void，流程结束后催办仍为待投递状态时测试失败
     */
    @Test
    void cancelsPendingUrgeWhenProcessCompletes()
    {
        startProcessWithoutCompleteListener();
        Task task = taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult();
        // 清除节点到达通知，确保活动租约来自本次真实人工催办。
        clearNotificationFacts();
        authenticate(starterUserId);
        manualUrgeService.urge(new WorkflowManualUrgeRequest(instance.getId(), "流程结束取消"));
        long urgeOutboxId = jdbc.queryForObject("select outbox_id from wf_notification_outbox " +
                "where process_instance_id=? and event_type='MANUAL_URGE'",
                Long.class, instance.getId());
        WorkflowNotificationOutboxRecord activeUrge = claimNextForRun("worker-terminal-process");
        assertThat(activeUrge).isNotNull();
        assertThat(activeUrge.outboxId()).isEqualTo(urgeOutboxId);
        assertThat(outboxStatus(urgeOutboxId)).isEqualTo("DELIVERING");
        SecurityContextHolder.clearContext();
        Authentication.setAuthenticatedUserId(approverUserId);
        try
        {
            taskService.complete(task.getId());
        }
        finally
        {
            Authentication.setAuthenticatedUserId(null);
        }
        assertThat(outboxStatus(urgeOutboxId)).isEqualTo("CANCELLED");
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId()).count()).isZero();
    }

    /**
     * 部署并启动带固定 userTaskListener 的真实流程。
     * @return void，创建的部署和实例写入当前测试字段
     */
    private void startProcess()
    {
        startSingleProcess(true, null);
    }

    /** @return void，启动包含节点到达自动抄送规则的单任务流程。 */
    private void startProcessWithAutomaticArrival()
    {
        String rules = "{\"version\":1,\"rules\":[{\"id\":\"arrival\"," +
                "\"trigger\":\"NODE_ARRIVED\",\"recipients\":[{\"type\":\"USER\"," +
                "\"values\":[\"" + copyUserId + "\"]}]}]}";
        startSingleProcess(true, rules);
    }

    /** @return void，启动不含 complete 监听器的流程以隔离验证流程级取消。 */
    private void startProcessWithoutCompleteListener()
    {
        startSingleProcess(false, null);
    }

    /**
     * 部署并启动可选自动抄送及 complete 监听器的真实单任务流程。
     * @param includeCompleteListener boolean，是否由任务完成监听器处理取消
     * @param automaticRules String，可空的受控自动抄送 JSON
     * @return void，部署和实例写入当前测试字段
     */
    private void startSingleProcess(boolean includeCompleteListener, String automaticRules)
    {
        String processKey = "notification_" + runId + "_" + deploymentIds.size();
        startSingleProcess(processKey, "通知集成测试", includeCompleteListener, automaticRules);
    }

    /**
     * 使用指定流程 key 和名称部署单任务流程，供策略作用域及渲染边界测试复用。
     * @param processKey String，唯一流程定义 key
     * @param processName String，流程显示名称
     * @param includeCompleteListener boolean，是否包含完成监听器
     * @param automaticRules String，可空自动抄送规则
     * @return void，部署和实例写入当前测试字段
     */
    private void startSingleProcess(String processKey, String processName,
            boolean includeCompleteListener, String automaticRules)
    {
        String ruleElement = automaticRules == null ? "" :
                "<flowable:properties><flowable:property name=\"approva.autoCopyRules\" value='" +
                        automaticRules + "'/></flowable:properties>";
        String completeListener = includeCompleteListener
                ? "<flowable:taskListener event=\"complete\" delegateExpression=\"${userTaskListener}\"/>"
                : "";
        String bpmn = ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:flowable="http://flowable.org/bpmn" targetNamespace="Examples">
                  <process id="%s" name="%s" isExecutable="true">
                    <startEvent id="start"/><sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="真实审批" flowable:assignee="%s">
                      <extensionElements>
                        %s
                        <flowable:taskListener event="create" delegateExpression="${userTaskListener}"/>
                        <flowable:taskListener event="assignment" delegateExpression="${userTaskListener}"/>
                        %s
                      </extensionElements>
                    </userTask>
                    <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/><endEvent id="end"/>
                  </process>
                </definitions>
                """).formatted(processKey, processName, approverUserId, ruleElement, completeListener);
        deployAndStart(processKey, bpmn);
    }

    /**
     * 部署并启动两个并行待办指向同一审批人的真实流程。
     * @return void，部署和实例写入当前测试字段
     */
    private void startParallelProcess()
    {
        String processKey = "notification_parallel_" + runId + "_" + deploymentIds.size();
        String bpmn = ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:flowable="http://flowable.org/bpmn" targetNamespace="Examples">
                  <process id="%s" name="并行通知集成测试" isExecutable="true">
                    <startEvent id="start"/><sequenceFlow id="f1" sourceRef="start" targetRef="fork"/>
                    <parallelGateway id="fork"/>
                    <sequenceFlow id="f2" sourceRef="fork" targetRef="approveA"/>
                    <sequenceFlow id="f3" sourceRef="fork" targetRef="approveB"/>
                    <userTask id="approveA" name="并行审批A" flowable:assignee="%s">
                      <extensionElements>
                        <flowable:taskListener event="create" delegateExpression="${userTaskListener}"/>
                        <flowable:taskListener event="assignment" delegateExpression="${userTaskListener}"/>
                        <flowable:taskListener event="complete" delegateExpression="${userTaskListener}"/>
                      </extensionElements>
                    </userTask>
                    <userTask id="approveB" name="并行审批B" flowable:assignee="%s">
                      <extensionElements>
                        <flowable:taskListener event="create" delegateExpression="${userTaskListener}"/>
                        <flowable:taskListener event="assignment" delegateExpression="${userTaskListener}"/>
                        <flowable:taskListener event="complete" delegateExpression="${userTaskListener}"/>
                      </extensionElements>
                    </userTask>
                    <sequenceFlow id="f4" sourceRef="approveA" targetRef="join"/>
                    <sequenceFlow id="f5" sourceRef="approveB" targetRef="join"/>
                    <parallelGateway id="join"/><sequenceFlow id="f6" sourceRef="join" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """).formatted(processKey, approverUserId, approverUserId);
        deployAndStart(processKey, bpmn);
    }

    /**
     * 保存部署并以真实发起人及运行状态变量启动实例。
     * @param processKey String，唯一流程定义 key
     * @param bpmn String，正式 BPMN XML
     * @return void，实例启动失败时测试失败
     */
    private void deployAndStart(String processKey, String bpmn)
    {
        Deployment deployment = repositoryService.createDeployment()
                .name("workflow-notification-it-" + processKey)
                .addBytes(processKey + ".bpmn20.xml", bpmn.getBytes(StandardCharsets.UTF_8))
                .deploy();
        deploymentId = deployment.getId();
        deploymentIds.add(deploymentId);
        Authentication.setAuthenticatedUserId(starterUserId);
        try
        {
            instance = runtimeService.startProcessInstanceByKey(
                    processKey, Map.of("processStatus", "running"));
        }
        finally
        {
            Authentication.setAuthenticatedUserId(null);
        }
        assertThat(instance).isNotNull();
        processInstanceIds.add(instance.getId());
    }

    /**
     * 在当前真实部署上再启动一个实例，用于验证相同来源键不得串绑其他流程实例。
     * @param processDefinitionId String，已经部署且可执行的流程定义主键
     * @return ProcessInstance，新启动并纳入本轮清理范围的真实运行实例
     */
    private ProcessInstance startAdditionalInstance(String processDefinitionId)
    {
        Authentication.setAuthenticatedUserId(starterUserId);
        ProcessInstance additional;
        try
        {
            additional = runtimeService.startProcessInstanceById(
                    processDefinitionId, Map.of("processStatus", "running"));
        }
        finally
        {
            Authentication.setAuthenticatedUserId(null);
        }
        assertThat(additional).isNotNull();
        processInstanceIds.add(additional.getId());
        return additional;
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

    /**
     * 查询具备抄送工作台和流程详情完整权限的有效用户，管理员主键 1 亦按生产契约可用。
     * @return String，自动抄送可见用户主键
     */
    private String copyEligibleUser()
    {
        return jdbc.queryForObject("select cast(min(u.user_id) as char) from sys_user u " +
                "where u.status='0' and u.del_flag='0' and (u.user_id=1 or 2=(select count(distinct m.perms) " +
                "from sys_user_role ur join sys_role r on r.role_id=ur.role_id " +
                "join sys_role_menu rm on rm.role_id=r.role_id join sys_menu m on m.menu_id=rm.menu_id " +
                "where ur.user_id=u.user_id and r.status='0' and r.del_flag='0' and r.role_id<>1 " +
                "and m.status='0' and m.perms in ('workflow:process:copyList','workflow:process:query')))",
                String.class);
    }

    /**
     * 统计本轮指定事件类型的 outbox 数量。
     * @param eventType String，事件类型
     * @return int，仅包含 runId 所属真实流程或直接插入事实的数量
     */
    private int countOutbox(String eventType)
    {
        return jdbc.queryForObject("select count(*) from wf_notification_outbox o where event_type=? " +
                "and " + OWN_OUTBOX_FILTER, Integer.class, eventType, runId, runId);
    }

    /**
     * 在隔离库创建一个发起人和两个审批人，确保权限与并发转办测试不依赖预置业务账号。
     * @return void，角色基线缺失或用户创建失败时立即失败
     */
    private void createPermissionActors()
    {
        permissionActorUserIds.clear();
        createPermissionActor("starter", "workflow_starter");
        createPermissionActor("approver", "workflow_approver");
        createPermissionActor("transfer", "workflow_approver");
    }

    /**
     * 创建真实启用用户并绑定现有工作流角色。
     * @param suffix String，当前测试用户的稳定业务后缀
     * @param roleKey String，必须存在且启用的正式角色标识
     * @return long，新建用户主键
     */
    private long createPermissionActor(String suffix, String roleKey)
    {
        String userName = permissionActorPrefix() + suffix;
        Long roleId = jdbc.queryForObject("select role_id from sys_role " +
                "where role_key=? and status='0' and del_flag='0'", Long.class, roleKey);
        assertThat(roleId).as(roleKey + " 正式角色").isNotNull();
        jdbc.update("insert into sys_user (user_name,nick_name,password,status,del_flag,create_by,create_time) " +
                "values (?,?,'','0','0','workflow_notification_it',current_timestamp(3))",
                userName, "通知集成测试" + suffix);
        Long userId = jdbc.queryForObject("select user_id from sys_user where user_name=?",
                Long.class, userName);
        assertThat(userId).isNotNull().isPositive();
        jdbc.update("insert into sys_user_role (user_id,role_id) values (?,?)", userId, roleId);
        permissionActorUserIds.add(userId);
        return userId;
    }

    /**
     * 按通知偏好、角色关系、用户的外键顺序回收本轮临时权限账号。
     * @return void，任一用户或角色关系残留时测试失败
     */
    private void deletePermissionActors()
    {
        for (Long userId : permissionActorUserIds)
        {
            jdbc.update("delete from wf_notification_preference where user_id=?", userId);
            jdbc.update("delete from sys_user_role where user_id=?", userId);
            jdbc.update("delete from sys_user where user_id=?", userId);
        }
        assertThat(jdbc.queryForObject("select count(*) from sys_user where user_name like ?",
                Integer.class, permissionActorPrefix() + "%")).isZero();
        permissionActorUserIds.clear();
    }

    /**
     * 生成满足 sys_user.user_name 长度约束的本轮临时账号前缀。
     * @return String，包含 runId 前十二位的隔离前缀
     */
    private String permissionActorPrefix()
    {
        return "wfnotif_" + runId.substring(0, 12) + "_";
    }

    /**
     * 统计本轮 outbox 投影出的 inbox 数量。
     * @return int，仅包含 runId 所属通知事实的站内信数量
     */
    private int countInbox()
    {
        return jdbc.queryForObject("select count(*) from wf_notification_inbox n " +
                "join wf_notification_outbox o on o.outbox_id=n.outbox_id where " +
                OWN_OUTBOX_FILTER, Integer.class, runId, runId);
    }

    /**
     * 统计本轮全部 outbox 数量，供精确清理后验证不留测试事实。
     * @return int，仅包含 runId 所属真实流程或直接插入事实的数量
     */
    private int countOwnedOutbox()
    {
        return jdbc.queryForObject("select count(*) from wf_notification_outbox o where " +
                OWN_OUTBOX_FILTER, Integer.class, runId, runId);
    }

    /**
     * 在共享通知队列中只领取本轮测试事实。
     * @param workerId String，本次测试使用的 worker 标识
     * @return WorkflowNotificationOutboxRecord，本轮可领取通知；本轮没有到期事实时为 null
     */
    private WorkflowNotificationOutboxRecord claimNextForRun(String workerId)
    {
        // 类级初始化已隔离共享队列，正式领取后仍核对 runId，防止未来测试并发时误消费。
        WorkflowNotificationOutboxRecord claimed = notificationOutboxService.claimNext(workerId);
        if (claimed == null)
        {
            return null;
        }
        assertThat(jdbc.queryForObject("select count(*) from wf_notification_outbox o " +
                "where o.outbox_id=? and " + OWN_OUTBOX_FILTER,
                Integer.class, claimed.outboxId(), runId, runId)).isOne();
        return claimed;
    }

    /** @param outboxId long，outbox 主键；@return String，正式状态。 */
    private String outboxStatus(long outboxId)
    {
        return jdbc.queryForObject("select status from wf_notification_outbox where outbox_id=?",
                String.class, outboxId);
    }

    /**
     * 核对 outbox 当前周期、周期内次数和跨周期累计次数。
     * @param outboxId long，outbox 主键
     * @param cycle int，预期投递周期
     * @param attempt int，预期周期内次数
     * @param total int，预期累计次数
     * @return void，任一序号不一致时测试失败
     */
    private void assertOutboxSequence(long outboxId, int cycle, int attempt, int total)
    {
        Map<String, Object> sequence = jdbc.queryForMap("select delivery_cycle,attempt_count," +
                "total_attempt_count from wf_notification_outbox where outbox_id=?", outboxId);
        assertThat(((Number) sequence.get("delivery_cycle")).intValue()).isEqualTo(cycle);
        assertThat(((Number) sequence.get("attempt_count")).intValue()).isEqualTo(attempt);
        assertThat(((Number) sequence.get("total_attempt_count")).intValue()).isEqualTo(total);
    }

    /**
     * 读取用户通知偏好快照，供关闭通道场景无损恢复正式主数据。
     * @param userId Long，真实用户主键
     * @return PreferenceSnapshot，存在标记及原始字段
     */
    private PreferenceSnapshot preferenceSnapshot(Long userId)
    {
        List<PreferenceSnapshot> rows = jdbc.query("select inbox_enabled,email_enabled,sms_enabled,revision," +
                "update_time from wf_notification_preference where user_id=?",
                (result, rowNum) -> new PreferenceSnapshot(true,
                        result.getInt("inbox_enabled"), result.getInt("email_enabled"),
                        result.getInt("sms_enabled"), result.getInt("revision"),
                        result.getTimestamp("update_time")), userId);
        return rows.isEmpty() ? new PreferenceSnapshot(false, 1, 1, 0, 0, null) : rows.get(0);
    }

    /**
     * 恢复测试前用户通知偏好，不保留测试配置。
     * @param userId Long，真实用户主键
     * @param snapshot PreferenceSnapshot，测试前快照
     * @return void，恢复失败时测试失败
     */
    private void restorePreference(Long userId, PreferenceSnapshot snapshot)
    {
        if (!snapshot.exists())
        {
            jdbc.update("delete from wf_notification_preference where user_id=?", userId);
            return;
        }
        jdbc.update("update wf_notification_preference set inbox_enabled=?,email_enabled=?,sms_enabled=?," +
                "revision=?,update_time=? where user_id=?", snapshot.inboxEnabled(),
                snapshot.emailEnabled(), snapshot.smsEnabled(), snapshot.revision(),
                snapshot.updateTime(), userId);
    }

    /**
     * 在独立事务中同步登记同一真实任务的到达事件。
     * @param ready CountDownLatch，两个登记线程就绪门闩
     * @param start CountDownLatch，统一起跑门闩
     * @param task Task，当前真实活动任务快照
     * @return int，本事务实际新增的 outbox 数量
     * @throws Exception 等待中断或事务执行异常
     */
    private int concurrentTaskArrival(CountDownLatch ready, CountDownLatch start, Task task)
            throws Exception
    {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS))
        {
            throw new IllegalStateException("并发通知登记起跑超时");
        }
        Integer inserted = new TransactionTemplate(transactionManager).execute(status ->
                notificationRegistrar.onTaskEvent("create", task.getId(), task.getProcessInstanceId(),
                        task.getProcessDefinitionId(), task.getTaskDefinitionKey(), task.getName(),
                        task.getAssignee(), task.getOwner()));
        if (inserted == null)
        {
            throw new IllegalStateException("并发通知登记没有返回结果");
        }
        return inserted;
    }

    /**
     * 在独立线程建立真实身份并执行一次同步起跑的催办。
     * @param ready CountDownLatch，两个线程就绪门闩
     * @param start CountDownLatch，统一起跑门闩
     * @param reason String，当前请求原因
     * @return int，成功为 0，业务拒绝返回 HTTP 状态码
     * @throws Exception 等待中断或催办执行异常
     */
    private int concurrentUrge(CountDownLatch ready, CountDownLatch start, String reason)
            throws Exception
    {
        authenticate(starterUserId);
        ready.countDown();
        try
        {
            if (!start.await(10, TimeUnit.SECONDS))
            {
                throw new IllegalStateException("并发催办起跑超时");
            }
            manualUrgeService.urge(new WorkflowManualUrgeRequest(instance.getId(), reason));
            return 0;
        }
        catch (ServiceException exception)
        {
            return exception.getCode();
        }
        finally
        {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 在独立线程建立当前办理人身份，并与催办同步竞争完成真实任务。
     * @param ready CountDownLatch，两个业务线程就绪门闩
     * @param start CountDownLatch，统一起跑门闩
     * @param taskId String，当前真实活动任务主键
     * @return void，任务完成失败时异常交给 Future 使测试失败
     * @throws Exception 等待中断或完成异常
     */
    private void concurrentComplete(CountDownLatch ready, CountDownLatch start, String taskId)
            throws Exception
    {
        authenticate(approverUserId);
        ready.countDown();
        try
        {
            if (!start.await(10, TimeUnit.SECONDS))
            {
                throw new IllegalStateException("并发任务完成起跑超时");
            }
            workflowProcessEngineAdapter.completeTask(taskId, Map.of());
        }
        finally
        {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 在独立线程建立当前办理人身份，并与催办同步竞争永久转办真实任务。
     * @param ready CountDownLatch，两个业务线程就绪门闩
     * @param start CountDownLatch，统一起跑门闩
     * @param taskId String，当前真实活动任务主键
     * @return void，转办失败时异常交给 Future 使测试失败
     * @throws Exception 等待中断或转办异常
     */
    private void concurrentTransfer(CountDownLatch ready, CountDownLatch start, String taskId)
            throws Exception
    {
        authenticate(approverUserId);
        ready.countDown();
        try
        {
            if (!start.await(10, TimeUnit.SECONDS))
            {
                throw new IllegalStateException("并发任务转办起跑超时");
            }
            workflowProcessEngineAdapter.transferTaskForCurrentUser(
                    taskId, transferTargetUserId, "并发转办验证");
        }
        finally
        {
            SecurityContextHolder.clearContext();
        }
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
        jdbc.update("insert into wf_notification_outbox (idempotency_key,source_type,source_id," +
                "event_type,channel," +
                "recipient_user_id,process_definition_key,process_instance_id,title,content,route_path," +
                "status,attempt_count,max_attempts,next_attempt_at,revision,create_time) " +
                "values (?,'APPROVAL',?,'MANUAL_URGE',?,?,? ,?,'测试通知','脱敏测试正文'," +
                "'/workflow/process-detail/test'," +
                "'PENDING',0,?,current_timestamp(3),0,current_timestamp(3))",
                key, runId + ":MANUAL_URGE", channel, Long.valueOf(recipientUserId),
                "notification-it", runId, maxAttempts);
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

    /** 用户通知偏好恢复快照。 */
    private record PreferenceSnapshot(boolean exists, int inboxEnabled, int emailEnabled,
            int smsEnabled, int revision, Timestamp updateTime) {}
}

package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowRuntimeEventRequest;
import com.ruoyi.flowable.domain.vo.WorkflowRuntimeEventView;
import com.ruoyi.flowable.service.process.WorkflowRuntimeEventService;
import com.ruoyi.flowable.service.process.WorkflowIntegrationCredentialService;

/**
 * 使用真实 MySQL 和 Flowable 8 验证外部运行事件、Token 与幂等审计闭环。
 */
@SpringBootTest(classes = RuoYiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.datasource.druid.master.url=${FLOWABLE_IT_JDBC_URL}",
            "spring.datasource.druid.master.username=${FLOWABLE_IT_USERNAME}",
            "spring.datasource.druid.master.password=${FLOWABLE_IT_PASSWORD}",
            "spring.data.redis.database=${FLOWABLE_IT_REDIS_DATABASE:15}",
            "token.secret=d29ya2Zsb3ctcnVudGltZS1ldmVudC1pdC10b2tlbi1zZWNyZXQtd29ya2Zsb3ctcnVudGltZS1ldmVudC1pdC10b2tlbi1zZWNyZXQtd29ya2Zsb3ctcnVudGltZS1ldmVudC1pdA==",
            "flowable.database-schema-update=false",
            "flowable.async-executor-activate=false",
            "flowable.async-history-executor-activate=false",
            "spring.quartz.auto-startup=false"
        })
class WorkflowRuntimeEventMySqlIT
{
    private static final String PREFIX = "workflow-runtime-event-it-";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private WorkflowRuntimeEventService runtimeEventService;
    @Autowired
    private WorkflowIntegrationCredentialService credentialService;
    @Autowired
    private StringRedisTemplate redisTemplate;

    private final String runId = UUID.randomUUID().toString().replace("-", "");
    private String messageProcessKey;
    private String signalProcessKey;
    private String receiveProcessKey;
    private String messageName;
    private String signalName;
    private Deployment deployment;
    private Long credentialId;
    private String token;

    /**
     * 部署三类真实 BPMN 并创建只存哈希的集成凭据。
     * @return void，环境或正式数据缺失时立即失败
     * @throws Exception SHA-256 算法不可用时抛出
     */
    @BeforeEach
    void setUp() throws Exception
    {
        messageProcessKey = PREFIX + "message-" + runId;
        signalProcessKey = PREFIX + "signal-" + runId;
        receiveProcessKey = PREFIX + "receive-" + runId;
        messageName = PREFIX + "message-name-" + runId;
        signalName = PREFIX + "signal-name-" + runId;
        deployment = deploy();

        token = "integration_" + UUID.randomUUID().toString().replace("-", "");
        String actorUserId = jdbc.queryForObject(
                "select cast(min(user_id) as char) from sys_user where status='0' and del_flag='0'",
                String.class);
        jdbc.update("insert into wf_integration_credential "
                + "(credential_name, token_prefix, token_hash, scopes, allowed_variables, "
                + "rate_limit_per_minute, revision_no, "
                + "create_by, create_time) values (?, ?, ?, 'MESSAGE,RECEIVE,SIGNAL', "
                + "'approved,amount', 100, 1, ?, current_timestamp(3))",
                PREFIX + runId, token.substring(0, 12), sha256(token), actorUserId);
        credentialId = jdbc.queryForObject(
                "select credential_id from wf_integration_credential where token_prefix = ?",
                Long.class, token.substring(0, 12));
        assertThat(credentialId).isPositive();
    }

    /**
     * 精确清理本轮事件台账、凭据和 Flowable 部署。
     * @return void，清理后不得残留本轮正式数据
     */
    @AfterEach
    void tearDown()
    {
        if (credentialId != null)
        {
            Set<String> rateKeys = redisTemplate.keys(
                    "workflow:credential:rate:" + credentialId + ":*");
            if (rateKeys != null && !rateKeys.isEmpty())
            {
                redisTemplate.delete(rateKeys);
            }
            jdbc.update("delete from wf_runtime_event_request where credential_id = ?", credentialId);
            jdbc.update("delete from wf_integration_credential where credential_id = ?", credentialId);
        }
        if (deployment != null && processEngine.getRepositoryService().createDeploymentQuery()
                .deploymentId(deployment.getId()).count() == 1)
        {
            processEngine.getRepositoryService().deleteDeployment(deployment.getId(), true);
        }
        assertThat(jdbc.queryForObject("select count(*) from wf_integration_credential "
                + "where credential_name = ?", Integer.class, PREFIX + runId)).isZero();
    }

    /**
     * 验证消息订阅被唯一消费、变量落入引擎且相同 requestId 返回原成功结果。
     * @return void，消息、变量、历史实例或幂等台账不一致时失败
     */
    @Test
    void consumesMessageAndReplaysProcessedRequest()
    {
        ProcessInstance instance = start(messageProcessKey, "message-business-" + runId);
        String requestId = UUID.randomUUID().toString();
        WorkflowRuntimeEventRequest request = request(requestId, messageName,
                instance.getId(), null, Map.of("approved", true, "amount", 12));

        WorkflowRuntimeEventView first = runtimeEventService.publish(token, "MESSAGE", request);
        WorkflowRuntimeEventView replay = runtimeEventService.publish(token, "MESSAGE", request);

        assertThat(first.status()).isEqualTo("PROCESSED");
        assertThat(replay.requestId()).isEqualTo(first.requestId());
        assertThat(replay.matchedExecutionId()).isEqualTo(first.matchedExecutionId());
        assertThat(processEngine.getHistoryService().createHistoricProcessInstanceQuery()
                .processInstanceId(instance.getId()).finished().singleResult()).isNotNull();
        assertThat(processEngine.getHistoryService().createHistoricVariableInstanceQuery()
                .processInstanceId(instance.getId()).variableName("approved").singleResult()
                .getValue()).isEqualTo(true);
        assertThat(countRequest(requestId, "PROCESSED")).isEqualTo(1);
    }

    /**
     * 验证信号订阅按流程实例定向消费，不广播到同名其他实例。
     * @return void，目标实例未结束或非目标实例被误消费时失败
     */
    @Test
    void consumesSignalOnlyForCorrelatedInstance()
    {
        ProcessInstance target = start(signalProcessKey, "signal-target-" + runId);
        ProcessInstance other = start(signalProcessKey, "signal-other-" + runId);

        runtimeEventService.publish(token, "SIGNAL", request(UUID.randomUUID().toString(),
                signalName, target.getId(), null, Map.of("approved", true)));

        assertThat(processEngine.getRuntimeService().createProcessInstanceQuery()
                .processInstanceId(target.getId()).count()).isZero();
        assertThat(processEngine.getRuntimeService().createProcessInstanceQuery()
                .processInstanceId(other.getId()).count()).isEqualTo(1);
    }

    /**
     * 验证 ReceiveTask 只能按真实类型和唯一 execution 触发。
     * @return void，ReceiveTask 未结束或变量未持久化时失败
     */
    @Test
    void triggersReceiveTaskWithWhitelistedVariables()
    {
        ProcessInstance instance = start(receiveProcessKey, "receive-business-" + runId);

        WorkflowRuntimeEventView result = runtimeEventService.publish(token, "RECEIVE",
                request(UUID.randomUUID().toString(), "receiveWait", instance.getId(), null,
                        Map.of("amount", 99)));

        assertThat(result.status()).isEqualTo("PROCESSED");
        assertThat(processEngine.getHistoryService().createHistoricProcessInstanceQuery()
                .processInstanceId(instance.getId()).finished().singleResult()).isNotNull();
        assertThat(processEngine.getHistoryService().createHistoricVariableInstanceQuery()
                .processInstanceId(instance.getId()).variableName("amount").singleResult()
                .getValue()).isEqualTo(99);
    }

    /**
     * 验证重复业务键和未授权变量均返回稳定错误、保留失败台账且不消费等待节点。
     * @return void，歧义或变量越权产生引擎副作用时失败
     */
    @Test
    void rejectsAmbiguityAndUnauthorizedVariableWithZeroEngineSideEffects()
    {
        String duplicateBusinessKey = "duplicate-business-" + runId;
        ProcessInstance first = start(messageProcessKey, duplicateBusinessKey);
        ProcessInstance second = start(messageProcessKey, duplicateBusinessKey);
        String ambiguousRequestId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> runtimeEventService.publish(token, "MESSAGE",
                request(ambiguousRequestId, messageName, null, duplicateBusinessKey, Map.of())))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getSubCode()).isEqualTo("RUNTIME_EVENT_INSTANCE_AMBIGUOUS");
                });
        assertThat(processEngine.getRuntimeService().createProcessInstanceQuery()
                .processInstanceIds(java.util.Set.of(first.getId(), second.getId())).count())
                .isEqualTo(2);
        assertThat(countRequest(ambiguousRequestId, "FAILED")).isEqualTo(1);

        ProcessInstance variableTarget = start(receiveProcessKey, "variable-business-" + runId);
        String deniedRequestId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> runtimeEventService.publish(token, "RECEIVE",
                request(deniedRequestId, "receiveWait", variableTarget.getId(), null,
                        Map.of("notAllowed", "blocked"))))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getSubCode())
                                .isEqualTo("RUNTIME_EVENT_VARIABLE_DENIED"));
        assertThat(processEngine.getRuntimeService().createExecutionQuery()
                .processInstanceId(variableTarget.getId()).activityId("receiveWait").count())
                .isEqualTo(1);
        assertThat(countRequest(deniedRequestId, "FAILED")).isEqualTo(1);
    }

    /**
     * 验证相同 requestId 不同载荷返回 409，首次成功台账不可被覆盖。
     * @return void，冲突请求改变首次结果或产生第二次引擎消费时失败
     */
    @Test
    void rejectsIdempotencyPayloadConflictWithoutOverwritingFirstResult()
    {
        ProcessInstance instance = start(receiveProcessKey, "idempotency-business-" + runId);
        String requestId = UUID.randomUUID().toString();
        WorkflowRuntimeEventRequest original = request(requestId, "receiveWait",
                instance.getId(), null, Map.of("amount", 1));
        runtimeEventService.publish(token, "RECEIVE", original);

        assertThatThrownBy(() -> runtimeEventService.publish(token, "RECEIVE",
                request(requestId, "receiveWait", instance.getId(), null,
                        Map.of("amount", 2))))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getSubCode())
                                .isEqualTo("RUNTIME_EVENT_IDEMPOTENCY_CONFLICT"));
        assertThat(countRequest(requestId, "PROCESSED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select result_code from wf_runtime_event_request "
                + "where request_id = ?", String.class, requestId))
                .isEqualTo("EVENT_PROCESSED");
    }

    /**
     * 验证轮换后旧 Token、吊销 Token、到期 Token 和超过限流均在引擎查询前拒绝。
     * @return void，任一失效 Token 仍可消费等待节点时失败
     * @throws Exception SHA-256 算法不可用时抛出
     */
    @Test
    void enforcesRotationRevocationExpiryAndRateLimit() throws Exception
    {
        ProcessInstance instance = start(receiveProcessKey, "token-state-business-" + runId);
        String rotated = "rotated_tok_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("update wf_integration_credential set token_prefix=?, token_hash=?, "
                + "revision_no=revision_no+1 where credential_id=?",
                rotated.substring(0, 12), sha256(rotated), credentialId);

        assertUnauthorized(token, instance);
        // 先把创建时间回拨，再设置已到期时间，既满足正式表 expires_at > create_time 约束又覆盖认证到期分支。
        jdbc.update("update wf_integration_credential set "
                + "create_time=date_sub(now(3), interval 2 minute), "
                + "expires_at=date_sub(now(3), interval 1 minute) where credential_id=?",
                credentialId);
        assertUnauthorized(rotated, instance);
        jdbc.update("update wf_integration_credential set expires_at=null, revoked_at=now(3) "
                + "where credential_id=?", credentialId);
        assertUnauthorized(rotated, instance);

        jdbc.update("update wf_integration_credential set revoked_at=null, rate_limit_per_minute=1 "
                + "where credential_id=?", credentialId);
        String missingRequestId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> runtimeEventService.publish(rotated, "RECEIVE",
                request(missingRequestId, "receiveWait", "missing-instance", null, Map.of())))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> runtimeEventService.publish(rotated, "RECEIVE",
                request(UUID.randomUUID().toString(), "receiveWait", instance.getId(), null, Map.of())))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(exception.getSubCode()).isEqualTo("INTEGRATION_RATE_LIMITED");
                });
        assertThat(processEngine.getRuntimeService().createExecutionQuery()
                .processInstanceId(instance.getId()).activityId("receiveWait").count())
                .isEqualTo(1);
        Set<String> rateKeys = redisTemplate.keys(
                "workflow:credential:rate:" + credentialId + ":2:*");
        assertThat(rateKeys).hasSize(1);
        assertThat(redisTemplate.opsForValue().get(rateKeys.iterator().next())).isEqualTo("2");
    }

    /**
     * 使用真实 Redis 并发认证，验证 Lua 计数不丢失且超过固定限额的请求全部返回 429。
     * @return void，允许数、拒绝数、Redis 最终计数或最近使用时间不一致时失败
     * @throws Exception 并发任务等待超时或执行异常时抛出
     */
    @Test
    void countsConcurrentCredentialRequestsAtomicallyInRedis() throws Exception
    {
        final int requestCount = 64;
        final int allowedLimit = 32;
        jdbc.update("update wf_integration_credential set rate_limit_per_minute=?, "
                + "last_used_at=null where credential_id=?", allowedLimit, credentialId);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(16);
        try
        {
            for (int index = 0; index < requestCount; index++)
            {
                executor.submit(() ->
                {
                    try
                    {
                        start.await();
                        credentialService.authenticateAndConsume(token, "RECEIVE");
                        allowed.incrementAndGet();
                    }
                    catch (ServiceException exception)
                    {
                        if (Integer.valueOf(HttpStatus.TOO_MANY_REQUESTS).equals(exception.getCode()))
                        {
                            limited.incrementAndGet();
                        }
                        else
                        {
                            unexpected.incrementAndGet();
                        }
                    }
                    catch (Exception exception)
                    {
                        unexpected.incrementAndGet();
                    }
                });
            }
            start.countDown();
        }
        finally
        {
            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(allowed).hasValue(allowedLimit);
        assertThat(limited).hasValue(requestCount - allowedLimit);
        assertThat(unexpected).hasValue(0);
        Set<String> rateKeys = redisTemplate.keys(
                "workflow:credential:rate:" + credentialId + ":1:*");
        assertThat(rateKeys).hasSize(1);
        assertThat(redisTemplate.opsForValue().get(rateKeys.iterator().next()))
                .isEqualTo(Integer.toString(requestCount));
        assertThat(jdbc.queryForObject("select last_used_at is not null "
                + "from wf_integration_credential where credential_id=?",
                Boolean.class, credentialId)).isTrue();
    }

    /**
     * 使用给定定义和业务键启动真实流程实例。
     * @param processKey String，已部署流程 key
     * @param businessKey String，本轮业务键
     * @return ProcessInstance，等待外部事件的活动实例
     */
    private ProcessInstance start(String processKey, String businessKey)
    {
        ProcessInstance instance = processEngine.getRuntimeService()
                .startProcessInstanceByKey(processKey, businessKey);
        assertThat(instance).isNotNull();
        return instance;
    }

    /**
     * 构造统一运行事件 DTO。
     * @param requestId String，规范 UUID 幂等键
     * @param eventName String，事件名或 activityId
     * @param processInstanceId String，可空实例主键
     * @param businessKey String，可空业务键
     * @param variables Map&lt;String,Object&gt;，白名单变量
     * @return WorkflowRuntimeEventRequest，可直接调用领域服务的请求
     */
    private WorkflowRuntimeEventRequest request(String requestId, String eventName,
            String processInstanceId, String businessKey, Map<String, Object> variables)
    {
        return new WorkflowRuntimeEventRequest(requestId, eventName, processInstanceId,
                businessKey, variables);
    }

    /**
     * 断言失效 Token 返回统一 401 且目标 ReceiveTask 仍等待。
     * @param candidateToken String，待验证 Token
     * @param instance ProcessInstance，目标等待实例
     * @return void，认证或零副作用契约不满足时失败
     */
    private void assertUnauthorized(String candidateToken, ProcessInstance instance)
    {
        assertThatThrownBy(() -> runtimeEventService.publish(candidateToken, "RECEIVE",
                request(UUID.randomUUID().toString(), "receiveWait", instance.getId(), null,
                        Map.of())))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThat(processEngine.getRuntimeService().createExecutionQuery()
                .processInstanceId(instance.getId()).activityId("receiveWait").count())
                .isEqualTo(1);
    }

    /**
     * 查询指定 requestId 和状态的精确台账行数。
     * @param requestId String，幂等请求 UUID
     * @param status String，PROCESSED 或 FAILED
     * @return int，精确匹配行数
     */
    private int countRequest(String requestId, String status)
    {
        return jdbc.queryForObject("select count(*) from wf_runtime_event_request "
                + "where request_id=? and status=?", Integer.class, requestId, status);
    }

    /**
     * 部署包含消息、信号和 ReceiveTask 的三个真实可执行流程。
     * @return Deployment，单次测试唯一部署
     */
    private Deployment deploy()
    {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "targetNamespace=\"ApprovaPlatRuntimeEventIT\">"
                + "<message id=\"messageRef\" name=\"" + messageName + "\"/>"
                + "<signal id=\"signalRef\" name=\"" + signalName + "\"/>"
                + waitingProcess(messageProcessKey,
                        "<intermediateCatchEvent id=\"messageWait\"><messageEventDefinition "
                                + "messageRef=\"messageRef\"/></intermediateCatchEvent>",
                        "messageWait")
                + waitingProcess(signalProcessKey,
                        "<intermediateCatchEvent id=\"signalWait\"><signalEventDefinition "
                                + "signalRef=\"signalRef\"/></intermediateCatchEvent>",
                        "signalWait")
                + waitingProcess(receiveProcessKey, "<receiveTask id=\"receiveWait\"/>",
                        "receiveWait")
                + "</definitions>";
        return processEngine.getRepositoryService().createDeployment()
                .name(PREFIX + runId).addBytes(PREFIX + runId + ".bpmn20.xml",
                        xml.getBytes(StandardCharsets.UTF_8)).deploy();
    }

    /**
     * 生成开始、等待、结束的最小真实流程 XML。
     * @param processKey String，唯一流程 key
     * @param waitElement String，消息、信号或 ReceiveTask XML
     * @param waitId String，等待节点 id
     * @return String，可嵌入 definitions 的 process XML
     */
    private String waitingProcess(String processKey, String waitElement, String waitId)
    {
        // BPMN XML 的 id 在整个 definitions 文档内必须全局唯一，三条流程不能复用 start/end/flow id。
        String suffix = processKey.replaceAll("[^A-Za-z0-9_]", "_");
        String startId = "start_" + suffix;
        String endId = "end_" + suffix;
        String firstFlowId = "flow_start_" + suffix;
        String secondFlowId = "flow_end_" + suffix;
        return "<process id=\"" + processKey + "\" isExecutable=\"true\">"
                + "<startEvent id=\"" + startId + "\"/>" + waitElement + "<endEvent id=\""
                + endId + "\"/>"
                + "<sequenceFlow id=\"" + firstFlowId + "\" sourceRef=\"" + startId
                + "\" targetRef=\"" + waitId + "\"/>"
                + "<sequenceFlow id=\"" + secondFlowId + "\" sourceRef=\"" + waitId
                + "\" targetRef=\"" + endId + "\"/>"
                + "</process>";
    }

    /**
     * 计算与生产认证服务一致的原始 Token SHA-256。
     * @param value String，测试 Token 正文
     * @return String，64 位小写摘要
     * @throws Exception SHA-256 算法不可用时抛出
     */
    private String sha256(String value) throws Exception
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}

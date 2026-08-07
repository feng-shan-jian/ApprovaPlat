package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.RuoYiApplication;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 通过真实 HTTP、Spring Security、MySQL 和 Flowable 8 验证匿名运行事件入口。
 */
@SpringBootTest(classes = RuoYiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.druid.master.url=${FLOWABLE_IT_JDBC_URL}",
            "spring.datasource.druid.master.username=${FLOWABLE_IT_USERNAME}",
            "spring.datasource.druid.master.password=${FLOWABLE_IT_PASSWORD}",
            "spring.datasource.druid.stat-view-servlet.enabled=false",
            "spring.datasource.druid.web-stat-filter.enabled=false",
            "spring.data.redis.database=${FLOWABLE_IT_REDIS_DATABASE:15}",
            "token.secret=d29ya2Zsb3ctcnctcnVudGltZS1odHRwLWl0LXNlY3JldC13b3JrZmxvdy1ydW50aW1lLWh0dHAtaXQtc2VjcmV0LXdvcmZsb3ctcnVudGltZS1odHRwLWl0LXNlY3JldA==",
            "flowable.database-schema-update=false",
            "flowable.async-executor-activate=false",
            "flowable.async-history-executor-activate=false",
            "spring.quartz.auto-startup=false",
            "spring.task.scheduling.enabled=false",
            "ruoyi.profile=target/workflow-runtime-event-http/profile",
            "logging.level.com.ruoyi=warn"
        })
class WorkflowRuntimeEventHttpIT
{
    /** 本测试创建的正式数据统一前缀，清理和残留断言只命中本轮数据。 */
    private static final String PREFIX = "workflow-runtime-event-http-it-";
    /** 真实 HTTP 连接及请求总超时。 */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);

    @LocalServerPort
    private int serverPort;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ProcessEngine processEngine;

    /** 本轮唯一标识，避免并发或失败重跑命中其他测试记录。 */
    private final String runId = UUID.randomUUID().toString().replace("-", "");
    /** 本轮创建的凭据主键，按外键顺序用于精确清理。 */
    private final List<Long> credentialIds = new ArrayList<>();
    private final ObjectMapper objectMapper = JsonMapper.shared();
    private HttpClient httpClient;
    private Deployment deployment;
    private String processKey;

    /**
     * 部署最小 ReceiveTask 流程并初始化不携带 Cookie 或自动重试的 HTTP 客户端。
     * @return void，真实服务、数据库或引擎不可用时立即失败
     */
    @BeforeEach
    void setUp()
    {
        processKey = PREFIX + "receive-" + runId;
        deployment = deployReceiveProcess();
        httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * 按运行事件、凭据、部署的依赖顺序清理正式数据并断言零残留。
     * @return void，任何本轮 fixture 未清理时测试失败
     */
    @AfterEach
    void tearDown()
    {
        for (Long credentialId : credentialIds)
        {
            jdbc.update("delete from wf_runtime_event_request where credential_id = ?",
                    credentialId);
            jdbc.update("delete from wf_integration_credential where credential_id = ?",
                    credentialId);
        }
        if (deployment != null && processEngine.getRepositoryService().createDeploymentQuery()
                .deploymentId(deployment.getId()).count() == 1)
        {
            processEngine.getRepositoryService().deleteDeployment(deployment.getId(), true);
        }
        assertThat(jdbc.queryForObject("select count(*) from wf_integration_credential "
                + "where credential_name like ?", Integer.class, PREFIX + runId + "%"))
                .isZero();
    }

    /**
     * 验证匿名入口仍强制独立 Token，并覆盖范围、吊销、到期与零引擎副作用。
     * @return void，错误业务码、台账或等待执行发生漂移时测试失败
     * @throws Exception 真实 HTTP 或摘要计算失败时抛出
     */
    @Test
    void rejectsMissingScopedRevokedAndExpiredTokensWithoutConsumingExecution()
            throws Exception
    {
        ProcessInstance instance = start("rejected-" + runId);
        String requestId = UUID.randomUUID().toString();
        String body = requestBody(requestId, instance.getId(), Map.of());

        assertBusinessCode(post(null, body), 401, "INTEGRATION_TOKEN_INVALID");
        String messageOnly = createCredential("MESSAGE", null, null);
        assertBusinessCode(post(messageOnly, body), 403, "INTEGRATION_SCOPE_DENIED");
        String revoked = createCredential("RECEIVE", "now(3)", null);
        assertBusinessCode(post(revoked, body), 401, "INTEGRATION_TOKEN_INVALID");
        String expired = createCredential("RECEIVE", null,
                "date_sub(now(3), interval 1 minute)");
        assertBusinessCode(post(expired, body), 401, "INTEGRATION_TOKEN_INVALID");

        assertThat(processEngine.getRuntimeService().createExecutionQuery()
                .processInstanceId(instance.getId()).activityId("receiveWait").count())
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from wf_runtime_event_request "
                + "where request_id = ?", Integer.class, requestId)).isZero();
    }

    /**
     * 验证有效 Token 通过真实 HTTP 消费 ReceiveTask，并稳定重放成功结果、拒绝摘要冲突。
     * @return void，响应、Flowable 状态或正式幂等台账不一致时测试失败
     * @throws Exception 真实 HTTP 或摘要计算失败时抛出
     */
    @Test
    void consumesReceiveTaskAndEnforcesHttpIdempotency() throws Exception
    {
        ProcessInstance instance = start("success-" + runId);
        String token = createCredential("RECEIVE", null, null);
        String requestId = UUID.randomUUID().toString();
        String body = requestBody(requestId, instance.getId(), Map.of("approved", true));

        JsonNode first = assertBusinessCode(post(token, body), 200, null);
        JsonNode replay = assertBusinessCode(post(token, body), 200, null);
        assertThat(first.path("data").path("status").asText()).isEqualTo("PROCESSED");
        assertThat(first.path("data").path("requestId").asText()).isEqualTo(requestId);
        assertThat(first.path("data").path("createTime").isMissingNode()).isFalse();
        assertThat(first.path("data").path("completeTime").isMissingNode()).isFalse();
        assertThat(replay.path("data")).isEqualTo(first.path("data"));
        assertThat(processEngine.getRuntimeService().createProcessInstanceQuery()
                .processInstanceId(instance.getId()).count()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from wf_runtime_event_request "
                + "where request_id = ? and status = 'PROCESSED'", Integer.class, requestId))
                .isEqualTo(1);

        String conflictingBody = requestBody(requestId, instance.getId(),
                Map.of("approved", false));
        assertBusinessCode(post(token, conflictingBody), 409,
                "RUNTIME_EVENT_IDEMPOTENCY_CONFLICT");
        assertThat(jdbc.queryForObject("select count(*) from wf_runtime_event_request "
                + "where request_id = ?", Integer.class, requestId)).isEqualTo(1);
    }

    /**
     * 创建只存 SHA-256 和前缀的正式测试凭据。
     * @param scopes String，逗号分隔事件范围
     * @param revokedExpression String，可空 SQL 时间表达式
     * @param expiresExpression String，可空 SQL 到期时间表达式
     * @return String，仅保存在测试进程内的明文 Token
     * @throws Exception SHA-256 算法不可用时抛出
     */
    private String createCredential(String scopes, String revokedExpression,
            String expiresExpression) throws Exception
    {
        String token = UUID.randomUUID().toString().replace("-", "") + runId.substring(0, 8);
        String actorUserId = jdbc.queryForObject(
                "select cast(min(user_id) as char) from sys_user where status='0' and del_flag='0'",
                String.class);
        String revoked = revokedExpression == null ? "null" : revokedExpression;
        String expires = expiresExpression == null ? "null" : expiresExpression;
        jdbc.update("insert into wf_integration_credential "
                        + "(credential_name, token_prefix, token_hash, scopes, allowed_variables, "
                        + "rate_limit_per_minute, rate_window_start, rate_window_count, expires_at, "
                        + "revoked_at, revision_no, create_by, create_time) values (?, ?, ?, ?, "
                        + "'approved', 100, current_timestamp(3), 0, " + expires + ", " + revoked
                        + ", 1, ?, date_sub(current_timestamp(3), interval 2 minute))",
                PREFIX + runId + "-" + credentialIds.size(), token.substring(0, 12),
                sha256(token), scopes, actorUserId);
        Long credentialId = jdbc.queryForObject(
                "select credential_id from wf_integration_credential where token_prefix = ?",
                Long.class, token.substring(0, 12));
        assertThat(credentialId).isPositive();
        credentialIds.add(credentialId);
        return token;
    }

    /**
     * 启动一个真实等待实例。
     * @param businessKey String，本轮唯一业务键
     * @return ProcessInstance，停留在 ReceiveTask 的活动实例
     */
    private ProcessInstance start(String businessKey)
    {
        ProcessInstance instance = processEngine.getRuntimeService()
                .startProcessInstanceByKey(processKey, businessKey);
        assertThat(processEngine.getRuntimeService().createExecutionQuery()
                .processInstanceId(instance.getId()).activityId("receiveWait").count())
                .isEqualTo(1);
        return instance;
    }

    /**
     * 生成不含 XML 外部实体且 ID 全局唯一的最小可执行 ReceiveTask 流程。
     * @return Deployment，Flowable 正式部署
     */
    private Deployment deployReceiveProcess()
    {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "targetNamespace=\"http://approvaplat.local/runtime-http-it\">"
                + "<process id=\"" + processKey + "\" isExecutable=\"true\">"
                + "<startEvent id=\"start\"/><receiveTask id=\"receiveWait\"/>"
                + "<endEvent id=\"end\"/><sequenceFlow id=\"flow1\" sourceRef=\"start\" "
                + "targetRef=\"receiveWait\"/><sequenceFlow id=\"flow2\" "
                + "sourceRef=\"receiveWait\" targetRef=\"end\"/></process></definitions>";
        return processEngine.getRepositoryService().createDeployment()
                .name(PREFIX + runId).addBytes(processKey + ".bpmn20.xml",
                        xml.getBytes(StandardCharsets.UTF_8)).deploy();
    }

    /**
     * 构造运行事件 JSON 请求体。
     * @param requestId String，幂等 UUID
     * @param processInstanceId String，精确活动实例主键
     * @param variables Map&lt;String,Object&gt;，白名单标量变量
     * @return String，JSON 请求正文
     * @throws Exception JSON 序列化失败时抛出
     */
    private String requestBody(String requestId, String processInstanceId,
            Map<String, Object> variables) throws Exception
    {
        return objectMapper.writeValueAsString(Map.of(
                "requestId", requestId, "eventName", "receiveWait",
                "processInstanceId", processInstanceId, "variables", variables));
    }

    /**
     * 向真实匿名入口发送请求，只有显式传入 Token 时才添加独立认证头。
     * @param token String，可空集成 Token
     * @param body String，JSON 请求正文
     * @return HttpResponse&lt;String&gt;，保留传输状态与业务正文
     * @throws Exception 网络请求失败或线程中断时抛出
     */
    private HttpResponse<String> post(String token, String body) throws Exception
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + serverPort + "/workflow/runtime-event/receive"))
                .timeout(HTTP_TIMEOUT).header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (token != null)
        {
            builder.header("X-Integration-Token", token);
        }
        return httpClient.send(builder.POST(HttpRequest.BodyPublishers.ofString(body,
                        StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * 断言 RuoYi 统一响应的传输状态、业务码与可选稳定子码。
     * @param response HttpResponse&lt;String&gt;，真实 HTTP 响应
     * @param expectedCode int，预期业务 code
     * @param expectedSubCode String，可空稳定业务子码
     * @return JsonNode，已解析完整响应
     * @throws Exception JSON 解析失败时抛出
     */
    private JsonNode assertBusinessCode(HttpResponse<String> response, int expectedCode,
            String expectedSubCode) throws Exception
    {
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.path("code").asInt()).isEqualTo(expectedCode);
        if (expectedSubCode != null)
        {
            assertThat(json.path("subCode").asText()).isEqualTo(expectedSubCode);
        }
        return json;
    }

    /**
     * 计算与生产 Token 服务一致的小写 SHA-256。
     * @param value String，明文 Token
     * @return String，64 位摘要
     * @throws Exception 当前 JDK 不支持 SHA-256 时抛出
     */
    private String sha256(String value) throws Exception
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}

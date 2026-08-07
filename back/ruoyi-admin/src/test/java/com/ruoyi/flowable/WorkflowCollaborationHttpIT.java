package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.system.service.ISysConfigService;

/**
 * Participant、MessageFlow 与事务 outbox 的真实 MySQL、HTTP、Redis 和 Flowable 8 验收。
 */
@SpringBootTest(
    classes = RuoYiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.datasource.druid.master.url=${FLOWABLE_COLLABORATION_JDBC_URL}",
        "spring.datasource.druid.master.username=${FLOWABLE_COLLABORATION_DB_USERNAME}",
        "spring.datasource.druid.master.password=${FLOWABLE_COLLABORATION_DB_PASSWORD}",
        "spring.datasource.druid.stat-view-servlet.enabled=false",
        "spring.datasource.druid.web-stat-filter.enabled=false",
        "spring.data.redis.host=${FLOWABLE_COLLABORATION_REDIS_HOST}",
        "spring.data.redis.port=${FLOWABLE_COLLABORATION_REDIS_PORT}",
        "spring.data.redis.password=${FLOWABLE_COLLABORATION_REDIS_PASSWORD:}",
        "spring.data.redis.database=${FLOWABLE_COLLABORATION_REDIS_DATABASE}",
        "token.secret=${FLOWABLE_COLLABORATION_TOKEN_SECRET}",
        "flowable.collaboration.expected-schema=${FLOWABLE_COLLABORATION_EXPECTED_SCHEMA}",
        "flowable.collaboration.accounts-registered=${FLOWABLE_RBAC_ACCOUNTS_REGISTERED:false}",
        "flowable.collaboration.worker-initial-delay=PT0.2S",
        "flowable.collaboration.worker-fixed-delay=PT0.2S",
        "flowable.collaboration.max-retry-delay=PT1S",
        "flowable.collaboration.lease-duration=PT10S",
        "flowable.database-schema-update=false",
        "flowable.async-executor-activate=false",
        "flowable.async-history-executor-activate=false",
        "spring.quartz.auto-startup=false",
        "spring.task.scheduling.enabled=true",
        "ruoyi.profile=target/workflow-collaboration/profile",
        "logging.level.com.ruoyi=warn"
    }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkflowCollaborationHttpIT
{
    private static final String TEST_ACCOUNT_PASSWORD = "wang";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration EVENTUALLY_TIMEOUT = Duration.ofSeconds(30);
    private static final String COLLABORATION_PATH =
            "/workflow/runtime-event/collaboration/message";

    @LocalServerPort
    private int serverPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private ISysConfigService sysConfigService;

    @Value("${flowable.collaboration.expected-schema}")
    private String expectedSchema;

    @Value("${flowable.collaboration.accounts-registered}")
    private boolean accountsRegistered;

    private final ObjectMapper objectMapper = JsonMapper.shared();
    private final List<String> loginTokens = new ArrayList<>();
    private final List<String> modelIds = new ArrayList<>();
    private final List<String> deploymentIds = new ArrayList<>();
    private HttpClient httpClient;
    private String runId;
    private String categoryCode;
    private String sourceProcessKey;
    private String targetProcessKey;
    private String messageName;
    private String businessKey;
    private String integrationToken;
    private String adminToken;
    private String auditorToken;
    private String starterToken;
    private long adminUserId;
    private long credentialId;
    private long endpointId;
    /** 本场景正式流程级开始表单主键，供两个 Participant 的启动校验复用。 */
    private long formId;
    /** 验收前暂存系统验证码开关，清理阶段恢复基线配置。 */
    private String originalCaptchaEnabled;

    /**
     * 核验隔离基线、预登记角色和真实登录，再创建正式集成凭据与认证端点。
     * @return void，环境或登录链不完整时整类测试失败
     * @throws Exception HTTP、JSON 或数据库操作失败时抛出
     */
    @BeforeAll
    void prepareEnvironment() throws Exception
    {
        assertThat(jdbcTemplate.queryForObject("select database()", String.class))
                .isEqualTo(expectedSchema);
        assertThat(accountsRegistered).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema=database()",
                Integer.class)).isEqualTo(99);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema=database() "
                        + "and table_name like 'wf\\_%'", Integer.class)).isEqualTo(32);

        runId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        categoryCode = "collaboration_" + runId;
        sourceProcessKey = "collaborationSource" + runId;
        targetProcessKey = "collaborationTarget" + runId;
        messageName = "approval.notice." + runId;
        businessKey = "COLLABORATION-" + runId;
        integrationToken = requireEnvironment("WORKFLOW_CONNECTOR_SECRET_COLLABORATION_IT");
        assertThat(integrationToken).hasSizeGreaterThanOrEqualTo(12);
        httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        // 真实登录验收不走验证码分支，但必须显式改动正式配置并在结束时恢复，避免依赖宿主默认值。
        originalCaptchaEnabled = jdbcTemplate.queryForObject(
                "select config_value from sys_config where config_key=?",
                String.class, "sys.account.captchaEnabled");
        assertThat(jdbcTemplate.update(
                "update sys_config set config_value='false' where config_key=?",
                "sys.account.captchaEnabled")).isEqualTo(1);
        sysConfigService.resetConfigCache();
        assertCaptchaDisabled();

        String adminUsername = requireEnvironment("FLOWABLE_RBAC_WORKFLOW_ADMIN_USERNAME");
        adminToken = login(adminUsername);
        auditorToken = login(requireEnvironment("FLOWABLE_RBAC_WORKFLOW_AUDITOR_USERNAME"));
        starterToken = login(requireEnvironment("FLOWABLE_RBAC_WORKFLOW_STARTER_USERNAME"));
        adminUserId = requireEnabledUser(adminUsername, "workflow_admin");

        assertThat(jdbcTemplate.update(
                "insert into wf_category(category_name,code,create_by,del_flag) "
                        + "values(?,?,?,'0')",
                "多池协作真实验收", categoryCode, String.valueOf(adminUserId))).isOne();
        assertThat(jdbcTemplate.update(
                "insert into wf_form(form_name,content,create_by,del_flag) values(?,?,?,'0')",
                "多池协作真实验收表单-" + runId, "{\"fields\":[]}",
                String.valueOf(adminUserId))).isOne();
        formId = jdbcTemplate.queryForObject(
                "select form_id from wf_form where form_name=? and del_flag='0' order by form_id desc limit 1",
                Long.class, "多池协作真实验收表单-" + runId);
        assertThat(jdbcTemplate.update(
                "insert into wf_integration_credential(credential_name,token_prefix,token_hash,"
                        + "scopes,allowed_variables,rate_limit_per_minute,create_by) "
                        + "values(?,?,?,'MESSAGE','approvalCode',1000,?)",
                "协作真实验收集成账号", integrationToken.substring(0, 12),
                sha256(integrationToken), String.valueOf(adminUserId))).isOne();
        credentialId = jdbcTemplate.queryForObject(
                "select credential_id from wf_integration_credential where token_prefix=?",
                Long.class, integrationToken.substring(0, 12));

        JsonNode endpoint = requireCode(jsonRequest("POST", "/workflow/connector", adminToken,
                objectMapper.createObjectNode()
                        .put("endpointKey", "collaboration.endpoint." + runId)
                        .put("endpointName", "协作真实验收端点")
                        .put("baseUrl", "http://127.0.0.1:" + serverPort)
                        .set("allowedMethods", objectMapper.createArrayNode().add("POST"))
                        .put("pathPrefix", "/workflow/runtime-event")
                        .put("authType", "API_KEY")
                        .put("secretRef", "WORKFLOW_CONNECTOR_SECRET_COLLABORATION_IT")
                        .put("apiKeyHeader", "X-Integration-Token")
                        .put("connectTimeoutMs", 1000)
                        .put("requestTimeoutMs", 5000)
                        .put("networkScope", "PRIVATE").toString()), 200);
        endpointId = endpoint.path("data").path("endpointId").longValue();
        assertThat(endpointId).isPositive();
    }

    /**
     * 清理本类创建的流程、协作台账、凭据、端点和登录态。
     * @return void，清理不完整时验收失败
     * @throws Exception HTTP 注销或仓储清理失败时抛出
     */
    @AfterAll
    void cleanup() throws Exception
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        for (String deploymentId : deploymentIds)
        {
            runtimeService.createProcessInstanceQuery().deploymentId(deploymentId).list()
                    .forEach(instance -> runtimeService.deleteProcessInstance(
                            instance.getId(), "协作验收清理"));
            // 本测试直接使用 Flowable 仓储清理，因此必须先删除平台侧部署快照，避免隔离库残留孤儿数据。
            jdbcTemplate.update("delete from wf_deploy_form where deploy_id=?", deploymentId);
            jdbcTemplate.update("delete from wf_deploy_extension_snapshot where deploy_id=?",
                    deploymentId);
            if (repositoryService.createDeploymentQuery().deploymentId(deploymentId).count() == 1)
            {
                repositoryService.deleteDeployment(deploymentId, true);
            }
        }
        if (!deploymentIds.isEmpty())
        {
            String placeholders = String.join(",",
                    deploymentIds.stream().map(value -> "?").toList());
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_deploy_form where deploy_id in (" + placeholders + ")",
                    Integer.class, deploymentIds.toArray())).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_deploy_extension_snapshot where deploy_id in ("
                            + placeholders + ")", Integer.class, deploymentIds.toArray())).isZero();
        }
        jdbcTemplate.update("delete from wf_collaboration_message_audit where message_id in "
                + "(select message_id from wf_collaboration_message where credential_id=?) "
                + "or message_id in (select message_id from wf_collaboration_outbox "
                + "where endpoint_id=?)", credentialId, endpointId);
        jdbcTemplate.update("delete from wf_collaboration_message where credential_id=?",
                credentialId);
        jdbcTemplate.update("delete from wf_collaboration_outbox where endpoint_id=?", endpointId);
        jdbcTemplate.update("delete from wf_collaboration_channel where target_process_definition_key=?",
                targetProcessKey);
        jdbcTemplate.update("delete from wf_connector_invocation where target_key=?",
                "collaboration.endpoint." + runId);
        jdbcTemplate.update("delete from wf_connector_endpoint where endpoint_id=?", endpointId);
        jdbcTemplate.update("delete from wf_integration_credential where credential_id=?", credentialId);
        jdbcTemplate.update("delete from wf_form where form_id=?", formId);
        for (String modelId : modelIds)
        {
            if (repositoryService.createModelQuery().modelId(modelId).count() == 1)
            {
                repositoryService.deleteModel(modelId);
            }
        }
        if (!modelIds.isEmpty())
        {
            jdbcTemplate.update("delete from wf_model_save_idempotency where source_model_id in ("
                    + String.join(",", modelIds.stream().map(value -> "?").toList()) + ")",
                    modelIds.toArray());
        }
        jdbcTemplate.update("delete from wf_category where code=?", categoryCode);
        for (String token : loginTokens)
        {
            requireCode(jsonRequest("POST", "/logout", token, null), 200);
        }
        if (originalCaptchaEnabled != null)
        {
            // 恢复验收前的正式配置，避免真实测试污染后续登录或其他测试。
            jdbcTemplate.update("update sys_config set config_value=? where config_key=?",
                    originalCaptchaEnabled, "sys.account.captchaEnabled");
            sysConfigService.resetConfigCache();
        }
    }

    /**
     * 通过真实模型 API 部署双池，验证 outbox 自投递、严格顺序、幂等、死信、补偿与 RBAC。
     * @return void，任一数据库、Flowable 或 HTTP 状态不一致时失败
     * @throws Exception HTTP、JSON 或等待条件失败时抛出
     */
    @Test
    void deliversDualPoolMessagesWithOrderingDeadLetterAndRbac() throws Exception
    {
        String deploymentId = deployDualPoolModel();
        ProcessDefinition sourceDefinition = requireDefinition(deploymentId, sourceProcessKey);
        ProcessDefinition targetDefinition = requireDefinition(deploymentId, targetProcessKey);
        RuntimeService runtimeService = processEngine.getRuntimeService();

        String firstReceiverId = runtimeService.startProcessInstanceById(
                targetDefinition.getId(), businessKey).getId();
        String senderId = runtimeService.startProcessInstanceById(sourceDefinition.getId(),
                businessKey, Map.of("approvalCode", "APPROVED")).getId();
        await("事务 outbox 必须自动投递并完成目标 ReceiveTask", () ->
                jdbcTemplate.queryForObject(
                        "select count(*) from wf_collaboration_outbox where "
                                + "source_process_instance_id=? and status='PROCESSED'",
                        Integer.class, senderId) == 1
                && runtimeService.createProcessInstanceQuery().processInstanceId(firstReceiverId).count() == 0);
        String firstMessageId = jdbcTemplate.queryForObject(
                "select message_id from wf_collaboration_outbox where source_process_instance_id=?",
                String.class, senderId);
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(senderId).count())
                .isZero();
        assertThat(jdbcTemplate.queryForMap(
                "select status,attempt_count,sequence_no from wf_collaboration_outbox "
                        + "where message_id=?", firstMessageId))
                .containsEntry("status", "PROCESSED")
                .containsEntry("attempt_count", 1)
                .containsEntry("sequence_no", 1L);
        assertThat(jdbcTemplate.queryForMap(
                "select status,attempt_count,sequence_no,matched_process_instance_id "
                        + "from wf_collaboration_message where message_id=?", firstMessageId))
                .containsEntry("status", "PROCESSED")
                .containsEntry("attempt_count", 1)
                .containsEntry("sequence_no", 1L)
                .containsEntry("matched_process_instance_id", firstReceiverId);

        // 先提交序号 3，接收端必须持久化等待而不能越过尚未到达的序号 2。
        String thirdMessageId = UUID.randomUUID().toString();
        JsonNode third = collaborationRequest(thirdMessageId, 3, "ORDERED", 3);
        requireCode(integrationRequest(third), 200);
        assertThat(jdbcTemplate.queryForMap(
                "select status,attempt_count from wf_collaboration_message where message_id=?",
                thirdMessageId)).containsEntry("status", "RETRYING")
                .containsEntry("attempt_count", 0);

        // 序号 2 在没有接收实例时只尝试一次并进入死信，后序消息继续保持阻塞。
        String secondMessageId = UUID.randomUUID().toString();
        JsonNode second = collaborationRequest(secondMessageId, 2, "COMPENSATE", 1);
        requireCode(integrationRequest(second), 200);
        assertThat(jdbcTemplate.queryForMap(
                "select status,attempt_count from wf_collaboration_message where message_id=?",
                secondMessageId)).containsEntry("status", "DEAD_LETTER")
                .containsEntry("attempt_count", 1);
        Thread.sleep(1200L);
        assertThat(jdbcTemplate.queryForObject(
                "select attempt_count from wf_collaboration_message where message_id=?",
                Integer.class, thirdMessageId)).isZero();

        requireCode(jsonRequest("POST", "/workflow/collaboration/inbound/"
                + encode(secondMessageId) + "/retry", auditorToken, null), 403);
        String compensatedReceiverId = runtimeService.startProcessInstanceById(
                targetDefinition.getId(), businessKey).getId();
        requireCode(jsonRequest("POST", "/workflow/collaboration/inbound/"
                + encode(secondMessageId) + "/retry", adminToken, null), 200);
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(compensatedReceiverId).count())
                .isZero();

        String thirdReceiverId = runtimeService.startProcessInstanceById(
                targetDefinition.getId(), businessKey).getId();
        await("前序补偿成功后后台 worker 必须自动消费乱序消息", () ->
                "PROCESSED".equals(jdbcTemplate.queryForObject(
                        "select status from wf_collaboration_message where message_id=?",
                        String.class, thirdMessageId))
                && runtimeService.createProcessInstanceQuery()
                        .processInstanceId(thirdReceiverId).count() == 0);
        assertThat(jdbcTemplate.queryForObject(
                "select inbound_sequence from wf_collaboration_channel where "
                        + "target_process_definition_key=? and correlation_value=?",
                Long.class, targetProcessKey, businessKey)).isEqualTo(3L);

        // 同 messageId 和同载荷重放返回首次结果；篡改变量的重放被 409 拒绝且不新增业务行。
        requireCode(integrationRequest(third), 200);
        JsonNode tampered = collaborationRequest(thirdMessageId, 3, "TAMPERED", 3);
        requireCode(integrationRequest(tampered), 409);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_collaboration_message where message_id=?",
                Integer.class, thirdMessageId)).isOne();

        requireCode(jsonRequest("GET", "/workflow/collaboration/outbox", adminToken, null), 200);
        requireCode(jsonRequest("GET", "/workflow/collaboration/inbound", auditorToken, null), 200);
        requireCode(jsonRequest("GET", "/workflow/collaboration/" + encode(thirdMessageId)
                + "/audit", auditorToken, null), 200);
        requireCode(jsonRequest("GET", "/workflow/collaboration/inbound", starterToken, null), 403);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_collaboration_message_audit where message_id=?",
                Integer.class, thirdMessageId)).isGreaterThanOrEqualTo(3);
    }

    /**
     * 创建、保存并部署一个含两个可执行 Participant 的正式模型。
     * @return String，真实 Flowable deploymentId
     * @throws Exception 模型 HTTP 操作失败时抛出
     */
    private String deployDualPoolModel() throws Exception
    {
        JsonNode created = requireCode(jsonRequest("POST", "/workflow/model", adminToken,
                objectMapper.createObjectNode().put("modelName", "多池协作真实验收")
                        .put("modelKey", sourceProcessKey).put("category", categoryCode)
                        .put("description", "Participant/MessageFlow 真实运行验收")
                        .put("formType", 0).put("formId", formId)
                        .toString()), 200);
        String modelId = created.path("data").path("modelId").asText();
        assertThat(modelId).isNotBlank();
        modelIds.add(modelId);
        JsonNode saved = requireCode(jsonRequest("POST", "/workflow/model/save", adminToken,
                objectMapper.createObjectNode().put("requestId", UUID.randomUUID().toString())
                        .put("modelId", modelId).put("bpmnXml", dualPoolBpmn())
                        .put("newVersion", false).toString()), 200);
        assertThat(saved.path("data").path("modelId").asText()).isEqualTo(modelId);
        JsonNode deployed = requireCode(jsonRequest("POST", "/workflow/model/deploy?modelId="
                + encode(modelId), adminToken, null), 200);
        String deploymentId = deployed.path("data").path("deploymentId").asText();
        assertThat(deploymentId).isNotBlank();
        deploymentIds.add(deploymentId);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_deploy_extension_snapshot where deploy_id=? "
                        + "and implementation_key='COLLABORATION_OUTBOX_V1'",
                Integer.class, deploymentId)).isOne();
        return deploymentId;
    }

    /**
     * 生成可保存、可重开的双池作者 BPMN，MessageFlow 与 outbox 配置使用同一消息名和目标流程。
     * @return String，完整 BPMN 2.0 XML
     */
    private String dualPoolBpmn()
    {
        String config = "{\"endpointKey\":\"collaboration.endpoint." + runId
                + "\",\"path\":\"" + COLLABORATION_PATH + "\",\"messageName\":\""
                + messageName + "\",\"targetProcessDefinitionKey\":\"" + targetProcessKey
                + "\",\"variableNames\":[\"approvalCode\"],\"maxAttempts\":3}";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="https://approvaplat.example/collaboration-http-it">
                  <message id="approvalMessage" name="%s"/>
                  <collaboration id="collaboration">
                    <participant id="sourcePool" name="发送方" processRef="%s"/>
                    <participant id="targetPool" name="接收方" processRef="%s"/>
                    <messageFlow id="approvalMessageFlow" name="%s" messageRef="approvalMessage"
                                 sourceRef="sendApproval" targetRef="receiveApproval"/>
                  </collaboration>
                  <process id="%s" name="发送方流程" isExecutable="true">
                    <startEvent id="sourceStart" flowable:formKey="key_%d"/>
                    <sequenceFlow id="sourceToSend" sourceRef="sourceStart" targetRef="sendApproval"/>
                    <sendTask id="sendApproval" name="可靠发送审批结果">
                      <extensionElements>
                        <flowable:field name="approvaExtensionKey">
                          <flowable:string>approva.collaboration-outbox</flowable:string>
                        </flowable:field>
                        <flowable:field name="approvaExtensionConfig">
                          <flowable:string><![CDATA[%s]]></flowable:string>
                        </flowable:field>
                      </extensionElements>
                    </sendTask>
                    <sequenceFlow id="sendToSourceEnd" sourceRef="sendApproval" targetRef="sourceEnd"/>
                    <endEvent id="sourceEnd"/>
                  </process>
                  <process id="%s" name="接收方流程" isExecutable="true">
                    <startEvent id="targetStart" flowable:formKey="key_%d"/>
                    <sequenceFlow id="targetToReceive" sourceRef="targetStart" targetRef="receiveApproval"/>
                    <receiveTask id="receiveApproval" name="接收审批结果"/>
                    <sequenceFlow id="receiveToTargetEnd" sourceRef="receiveApproval" targetRef="targetEnd"/>
                    <endEvent id="targetEnd"/>
                  </process>
                </definitions>
                """.formatted(messageName, sourceProcessKey, targetProcessKey, messageName,
                sourceProcessKey, formId, config, targetProcessKey, formId);
    }

    /**
     * 构造一条正式协作协议请求。
     * @param messageId String，幂等消息主键
     * @param sequenceNo long，同一业务键内连续序号
     * @param approvalCode String，凭据白名单变量值
     * @param maxAttempts int，有界消费次数
     * @return JsonNode，可直接发送的请求对象
     */
    private JsonNode collaborationRequest(String messageId, long sequenceNo,
            String approvalCode, int maxAttempts)
    {
        return objectMapper.createObjectNode().put("messageId", messageId)
                .put("messageName", messageName)
                .put("sourceProcessDefinitionKey", sourceProcessKey)
                .put("targetProcessDefinitionKey", targetProcessKey)
                .put("correlationKey", businessKey).put("sequenceNo", sequenceNo)
                .set("variables", objectMapper.createObjectNode()
                        .put("approvalCode", approvalCode))
                .put("maxAttempts", maxAttempts);
    }

    /**
     * 使用真实 X-Integration-Token 调用匿名协作入口。
     * @param body JsonNode，协作协议正文
     * @return JsonNode，若依统一响应
     * @throws Exception HTTP 或 JSON 处理失败时抛出
     */
    private JsonNode integrationRequest(JsonNode body) throws Exception
    {
        HttpRequest request = HttpRequest.newBuilder(baseUri(COLLABORATION_PATH))
                .timeout(HTTP_TIMEOUT).header("Accept", "application/json")
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Integration-Token", integrationToken)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("HTTP response body: %s", response.body()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    /**
     * 查询部署中的唯一流程定义。
     * @param deploymentId String，Flowable 部署主键
     * @param processKey String，目标 Participant 流程 key
     * @return ProcessDefinition，唯一已部署定义
     */
    private ProcessDefinition requireDefinition(String deploymentId, String processKey)
    {
        ProcessDefinition definition = processEngine.getRepositoryService()
                .createProcessDefinitionQuery().deploymentId(deploymentId)
                .processDefinitionKey(processKey).singleResult();
        assertThat(definition).isNotNull();
        return definition;
    }

    /**
     * 在有界时间内等待真实后台 worker 和数据库状态收敛。
     * @param description String，超时时展示的业务条件
     * @param condition BooleanSupplier，无副作用状态查询
     * @return void，条件成立时返回
     * @throws InterruptedException 当前线程中断时抛出
     */
    private void await(String description, BooleanSupplier condition) throws InterruptedException
    {
        long deadline = System.nanoTime() + EVENTUALLY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline)
        {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100L);
        }
        assertThat(condition.getAsBoolean()).as(description).isTrue();
    }

    /**
     * 调用真实验证码入口并要求隔离环境关闭验证码。
     * @return void，验证码开启时失败
     * @throws Exception HTTP 或 JSON 处理失败时抛出
     */
    private void assertCaptchaDisabled() throws Exception
    {
        assertThat(requireCode(jsonRequest("GET", "/captchaImage", null, null), 200)
                .path("captchaEnabled").asBoolean(true)).isFalse();
    }

    /**
     * 通过真实登录入口创建 Redis 登录态。
     * @param username String，预登记用户名
     * @return String，服务端签发 JWT
     * @throws Exception HTTP 或 JSON 处理失败时抛出
     */
    private String login(String username) throws Exception
    {
        JsonNode response = requireCode(jsonRequest("POST", "/login", null,
                objectMapper.createObjectNode().put("username", username)
                        .put("password", TEST_ACCOUNT_PASSWORD).put("code", "")
                        .put("uuid", "").toString()), 200);
        String token = response.path("token").asText();
        assertThat(token).isNotBlank();
        loginTokens.add(token);
        return token;
    }

    /**
     * 核对预登记用户只绑定期望工作流角色。
     * @param username String，预登记用户名
     * @param roleKey String，唯一期望角色键
     * @return long，正式用户主键
     */
    private long requireEnabledUser(String username, String roleKey)
    {
        List<Long> ids = jdbcTemplate.queryForList(
                "select user_id from sys_user where user_name=? and status='0' and del_flag='0'",
                Long.class, username);
        assertThat(ids).singleElement();
        long userId = ids.get(0);
        assertThat(jdbcTemplate.queryForList(
                "select r.role_key from sys_role r join sys_user_role ur on ur.role_id=r.role_id "
                        + "where ur.user_id=? and r.role_key like 'workflow_%'",
                String.class, userId)).containsExactly(roleKey);
        return userId;
    }

    /**
     * 从当前进程读取非空强制环境变量。
     * @param name String，环境变量名
     * @return String，非空原值，调用方不得输出
     */
    private String requireEnvironment(String name)
    {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new AssertionError("缺少强制环境变量: " + name);
        return value;
    }

    /**
     * 发送真实登录用户 HTTP 请求并解析统一 JSON 响应。
     * @param method String，GET 或 POST
     * @param path String，相对路径，可含查询参数
     * @param token String，可空 JWT
     * @param body String，可空 JSON 正文
     * @return JsonNode，统一业务响应
     * @throws IOException 网络或响应读取失败时抛出
     * @throws InterruptedException 当前线程被中断时抛出
     */
    private JsonNode jsonRequest(String method, String path, String token, String body)
            throws IOException, InterruptedException
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri(path))
                .timeout(HTTP_TIMEOUT).header("Accept", "application/json")
                .header("User-Agent", "workflow-collaboration-http-it");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json; charset=UTF-8");
        HttpRequest request = "GET".equals(method) ? builder.GET().build()
                : builder.POST(body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    /**
     * 断言若依统一业务码。
     * @param response JsonNode，统一响应
     * @param expectedCode int，期望业务码
     * @return JsonNode，原响应
     */
    private JsonNode requireCode(JsonNode response, int expectedCode)
    {
        assertThat(response.path("code").asInt())
                .as("业务响应=%s", response)
                .isEqualTo(expectedCode);
        return response;
    }

    /** @param path String，相对路径；@return URI，当前随机端口服务地址。 */
    private URI baseUri(String path)
    {
        return URI.create("http://127.0.0.1:" + serverPort + path);
    }

    /** @param value String，路径或查询值；@return String，UTF-8 URL 编码结果。 */
    private String encode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** @param value String，待摘要文本；@return String，64 位小写 SHA-256。 */
    private String sha256(String value) throws Exception
    {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}

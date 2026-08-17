package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Model;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.task.api.Task;
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
import com.ruoyi.flowable.domain.WfDeployControlledLoop;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;

/**
 * 受控重复审批循环通过真实 HTTP、Spring Security、Redis Token、Flowable 8 和 MySQL 的完整验收。
 */
@SpringBootTest(
    classes = RuoYiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.datasource.druid.master.url=${FLOWABLE_RBAC_JDBC_URL}",
        "spring.datasource.druid.master.username=${FLOWABLE_RBAC_DB_USERNAME}",
        "spring.datasource.druid.master.password=${FLOWABLE_RBAC_DB_PASSWORD}",
        "spring.datasource.druid.stat-view-servlet.enabled=false",
        "spring.datasource.druid.web-stat-filter.enabled=false",
        "spring.data.redis.host=${FLOWABLE_RBAC_REDIS_HOST}",
        "spring.data.redis.port=${FLOWABLE_RBAC_REDIS_PORT}",
        "spring.data.redis.password=${FLOWABLE_RBAC_REDIS_PASSWORD:}",
        "spring.data.redis.database=${FLOWABLE_RBAC_REDIS_DATABASE}",
        "token.secret=${FLOWABLE_RBAC_TOKEN_SECRET}",
        "flowable.controlled-loop.expected-schema=${FLOWABLE_RBAC_EXPECTED_SCHEMA}",
        "flowable.controlled-loop.accounts-registered=${FLOWABLE_RBAC_ACCOUNTS_REGISTERED:false}",
        "flowable.database-schema-update=false",
        "flowable.async-executor-activate=false",
        "flowable.async-history-executor-activate=false",
        "spring.quartz.auto-startup=false",
        "spring.task.scheduling.enabled=false",
        "ruoyi.profile=target/workflow-controlled-loop/profile",
        "logging.level.com.ruoyi=warn"
    }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkflowControlledLoopHttpIT
{
    /** 真实 HTTP 请求和并发结果允许等待的最长时间。 */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    /** 本测试创建的数据统一前缀，失败清理和残留核对只命中该范围。 */
    private static final String FIXTURE_PREFIX = "controlled-loop-http-it-";

    /** 循环用户任务固定节点标识。 */
    private static final String REVIEW_ACTIVITY_ID = "rectifyReview";

    @LocalServerPort
    private int serverPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProcessEngine processEngine;

    @Value("${flowable.controlled-loop.expected-schema}")
    private String expectedSchema;

    @Value("${flowable.controlled-loop.accounts-registered}")
    private boolean accountsRegistered;

    @Autowired
    private WorkflowDeploymentArtifactRepository artifactRepository;

    /** 不持有 Cookie 且不自动重试的真实 HTTP 客户端。 */
    private HttpClient httpClient;

    /** 测试专用 Jackson 3 解析器。 */
    private final ObjectMapper objectMapper = JsonMapper.shared();

    /** 本类创建的所有真实登录 Token，AfterAll 逐一注销。 */
    private final List<String> loginTokens = new ArrayList<>();

    /** 当前测试运行的唯一短后缀。 */
    private String runId;

    /** 正式分类编码。 */
    private String categoryCode;

    /** BPMN 流程标识和模型版本分组标识。 */
    private String processKey;

    /** 真实 Flowable 模型主键。 */
    private String modelId;

    /** 真实 Flowable 部署主键。 */
    private String deploymentId;

    /** 真实 Flowable 流程定义主键。 */
    private String processDefinitionId;

    /** 真实 Flowable 流程实例主键。 */
    private String processInstanceId;

    /** 五角色中的流程设计者 Token。 */
    private String designerToken;

    /** 五角色中的流程发起人 Token。 */
    private String starterToken;

    /** 五角色中的流程审批人主 Token。 */
    private String approverToken;

    /** 同一审批人的第二个 Token，用于绕过前端重复提交缓存并真实制造并发。 */
    private String concurrentApproverToken;

    /** 五角色中的流程管理员 Token。 */
    private String adminToken;

    /** 预登记流程发起人正式用户主键。 */
    private long starterUserId;

    /** 预登记流程审批人正式用户主键。 */
    private long approverUserId;

    /** 预登记流程审批人用户可见名称。 */
    private String approverDisplayName;

    /**
     * 核验隔离 schema、验证码和预登记角色账号，再建立真实 JWT/Redis 登录态。
     *
     * @return void，无返回值；环境、账号或登录链不满足时整类测试失败
     * @throws Exception 真实 HTTP、JSON 或数据库读取失败时抛出
     */
    @BeforeAll
    void prepareEnvironmentAndLogin() throws Exception
    {
        assertThat(jdbcTemplate.queryForObject("select database()", String.class))
                .as("受控循环 IT 只能连接显式批准的隔离 schema")
                .isEqualTo(expectedSchema);
        assertThat(accountsRegistered)
                .as("首次运行前必须登记五角色验收账号")
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema = database() "
                        + "and table_name='wf_controlled_loop_execution'",
                Integer.class)).as("受控循环运行表必须已应用到真实 MySQL").isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema = database() "
                        + "and table_name='wf_deploy_controlled_loop'",
                Integer.class)).as("旧受控循环快照表必须已退出基线").isZero();

        runId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        categoryCode = "controlled_loop_" + runId;
        processKey = "controlledLoopHttp" + runId;
        httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        assertCaptchaDisabled();

        String designerUsername = requireEnvironment("FLOWABLE_RBAC_WORKFLOW_DESIGNER_USERNAME");
        String starterUsername = requireEnvironment("FLOWABLE_RBAC_WORKFLOW_STARTER_USERNAME");
        String approverUsername = requireEnvironment("FLOWABLE_RBAC_WORKFLOW_APPROVER_USERNAME");
        String adminUsername = requireEnvironment("FLOWABLE_RBAC_WORKFLOW_ADMIN_USERNAME");
        designerToken = login(designerUsername);
        starterToken = login(starterUsername);
        approverToken = login(approverUsername);
        // 两个独立 JWT 共用同一正式用户，保证并发请求都能进入真实任务事务。
        concurrentApproverToken = login(approverUsername);
        adminToken = login(adminUsername);

        starterUserId = requireEnabledUser(starterUsername, "workflow_starter");
        approverUserId = requireEnabledUser(approverUsername, "workflow_approver");
        approverDisplayName = jdbcTemplate.queryForObject(
                "select nick_name from sys_user where user_id = ?", String.class,
                approverUserId);
        assertThat(approverDisplayName).isNotBlank();

        // 分类不是本能力的被测对象，使用正式表夹具并在类级清理中精确删除。
        assertThat(jdbcTemplate.update(
                "insert into wf_category (category_name, code, create_by, del_flag) "
                        + "values (?, ?, ?, '0')",
                "受控整改循环真实验收", categoryCode, String.valueOf(approverUserId)))
                .isOne();
    }

    /**
     * 强制清理本类创建的流程、模型、快照和分类，并注销全部 Redis Token。
     *
     * @return void，无返回值；任何残留或注销失败都会使验收失败
     * @throws Exception HTTP 注销或 JSON 解析失败时抛出
     */
    @AfterAll
    void cleanupFixtureAndLogout() throws Exception
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        if (deploymentId != null)
        {
            artifactRepository.delete(deploymentId);
            if (repositoryService.createDeploymentQuery().deploymentId(deploymentId).count() == 1)
            {
                // 仅在主测试未走完正式删除链时兜底级联删除本类唯一部署。
                repositoryService.deleteDeployment(deploymentId, true);
            }
        }
        if (processInstanceId != null)
        {
            jdbcTemplate.update("delete from wf_controlled_loop_execution "
                    + "where process_instance_id = ?", processInstanceId);
        }
        if (modelId != null && repositoryService.createModelQuery().modelId(modelId).count() == 1)
        {
            repositoryService.deleteModel(modelId);
        }
        if (categoryCode != null)
        {
            jdbcTemplate.update("delete from wf_category where code = ?", categoryCode);
        }

        List<String> logoutFailures = new ArrayList<>();
        for (String token : loginTokens)
        {
            try
            {
                JsonNode response = jsonRequest("POST", "/logout", token, null);
                if (response.path("code").asInt() != 200)
                {
                    logoutFailures.add("logout code=" + response.path("code").asInt());
                }
            }
            catch (RuntimeException | IOException | InterruptedException exception)
            {
                logoutFailures.add("logout exception=" + exception.getClass().getSimpleName());
                if (exception instanceof InterruptedException)
                {
                    Thread.currentThread().interrupt();
                }
            }
        }
        loginTokens.clear();

        assertThat(logoutFailures).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_category where code = ?", Integer.class,
                categoryCode)).isZero();
        if (processInstanceId != null)
        {
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_controlled_loop_execution "
                            + "where process_instance_id = ?",
                    Integer.class, processInstanceId)).isZero();
        }
        if (deploymentId != null)
        {
            assertThat(artifactRepository.selectControlledLoops(deploymentId)).isEmpty();
        }
    }

    /**
     * 验证作者配置、部署编译、并发整改、表单继承、上限回滚、退出、详情审计和删除保护的完整闭环。
     *
     * @return void，无返回值；任一 HTTP、Flowable、MySQL、权限、状态或审计关系漂移时失败
     * @throws Exception 真实 HTTP、并发等待、XML 读取或 JSON 解析失败时抛出
     */
    @Test
    void executesControlledRectificationLoopThroughRealHttpAndMySql() throws Exception
    {
        createSaveAndDeployModel();
        assertCompiledDeploymentAndSnapshot();

        processInstanceId = startProcess();
        Task firstTask = singleActiveReviewTask();
        assertThat(firstTask.getAssignee()).isEqualTo(String.valueOf(approverUserId));

        // 两个独立 Token 同时完成同一任务，数据库和 Flowable 只能接受一次真实整改。
        List<JsonNode> concurrentResults = completeConcurrently(firstTask.getId(), "RECTIFY",
                "第一轮整改材料");
        assertThat(concurrentResults).filteredOn(result -> result.path("code").asInt() == 200)
                .hasSize(1);
        assertThat(concurrentResults).filteredOn(result -> result.path("code").asInt() == 409)
                .hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_controlled_loop_execution "
                        + "where process_instance_id = ? and outcome = 'REPEAT'",
                Integer.class, processInstanceId)).isOne();

        Task secondTask = singleActiveReviewTask();
        assertThat(secondTask.getId()).isNotEqualTo(firstTask.getId());
        JsonNode secondRoundDetail = requireCode(getDetail(approverToken, secondTask.getId()), 200)
                .path("data");
        assertThat(secondRoundDetail.path("nextUserAssignmentPolicy").asText())
                .isEqualTo("DISABLED");
        assertThat(secondRoundDetail.path("nextUserSelectionRequired").asBoolean()).isFalse();
        assertThat(secondRoundDetail.path("currentTaskForm").path("taskLocal").asBoolean())
                .isTrue();
        assertThat(secondRoundDetail.path("currentTaskForm").path("values")
                .path("reviewResult").asText()).isEqualTo("RECTIFY");
        assertThat(secondRoundDetail.path("currentTaskForm").path("values")
                .path("rectifyNote").asText()).isEqualTo("第一轮整改材料");
        assertLoopState(secondRoundDetail, true, 1, 2, List.of("REPEAT"));

        SideEffectSnapshot beforeLimit = snapshotFailedCompletionSideEffects(secondTask.getId());
        JsonNode limitFailure = completeTask(approverToken, secondTask.getId(), "RECTIFY",
                "第二轮仍需整改");
        requireCode(limitFailure, 409);
        assertThat(limitFailure.path("subCode").asText())
                .isEqualTo("CONTROLLED_LOOP_LIMIT_REACHED");
        assertThat(snapshotFailedCompletionSideEffects(secondTask.getId()))
                .as("达到最大轮次的失败提交不得写审计、意见、变量、表单快照或任务状态")
                .isEqualTo(beforeLimit);

        requireCode(completeTask(approverToken, secondTask.getId(), "PASS",
                "整改完成，准予退出"), 200);
        assertThat(processEngine.getRuntimeService().createProcessInstanceQuery()
                .processInstanceId(processInstanceId).count()).isZero();
        assertThat(processEngine.getHistoryService().createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).finished().count()).isOne();

        JsonNode completedDetail = requireCode(getDetail(approverToken, secondTask.getId()), 200)
                .path("data");
        assertThat(completedDetail.path("processStatus").asText()).isEqualTo("completed");
        assertLoopState(completedDetail, false, 2, 2, List.of("REPEAT", "EXIT"));
        assertThat(completedDetail.path("bpmnXml").asText())
                .contains("exclusiveGateway", "__approva_loop_")
                .doesNotContain("approva.controlledLoop", "standardLoopCharacteristics");
        assertCompletedBy(completedDetail, firstTask.getId());
        assertCompletedBy(completedDetail, secondTask.getId());

        JsonNode protectedDeletion = delete("/workflow/deploy/" + encode(deploymentId),
                designerToken);
        requireCode(protectedDeletion, 409);
        assertThat(artifactRepository.selectControlledLoops(deploymentId)).hasSize(1);

        JsonNode historyDeletion = delete(
                "/workflow/process/instance/" + encode(processInstanceId), adminToken);
        requireCode(historyDeletion, 200);
        assertThat(historyDeletion.path("data").path("deletedHistoryCount").asInt()).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_controlled_loop_execution "
                        + "where process_instance_id = ?",
                Integer.class, processInstanceId)).isZero();

        requireCode(delete("/workflow/deploy/" + encode(deploymentId), designerToken), 200);
        assertThat(artifactRepository.selectControlledLoops(deploymentId)).isEmpty();
        requireCode(delete("/workflow/model/" + encode(modelId), designerToken), 200);
        assertThat(processEngine.getRepositoryService().createModelQuery()
                .modelId(modelId).count()).isZero();
    }

    /**
     * 通过真实模型新增、保存和部署 API 创建本轮受控循环定义。
     *
     * @return void，无返回值；任一 API 未返回正式主键时失败
     * @throws Exception HTTP 或 JSON 处理失败时抛出
     */
    private void createSaveAndDeployModel() throws Exception
    {
        JsonNode createBody = objectMapper.createObjectNode()
                .put("modelName", "受控整改循环真实验收-" + runId)
                .put("modelKey", processKey)
                .put("category", categoryCode)
                .put("description", "验证受控重复审批与整改循环完整业务闭环")
                .put("formType", 2);
        JsonNode created = requireCode(jsonRequest("POST", "/workflow/model",
                designerToken, createBody.toString()), 200);
        modelId = created.path("data").path("modelId").asText();
        assertThat(modelId).isNotBlank();
        JsonNode modelDetail = requireCode(jsonRequest("GET",
                "/workflow/model/" + encode(modelId), designerToken, null), 200);
        String expectedBpmnSha256 = modelDetail.path("data").path("bpmnSha256").asText();
        assertThat(expectedBpmnSha256).hasSize(64);

        String authorBpmn = controlledLoopAuthorBpmn();
        JsonNode saveBody = objectMapper.createObjectNode()
                .put("modelId", modelId)
                .put("bpmnXml", authorBpmn)
                .put("expectedBpmnSha256", expectedBpmnSha256)
                .put("newVersion", false);
        JsonNode saved = requireCode(jsonRequest("POST", "/workflow/model/save",
                designerToken, saveBody.toString()), 200);
        assertThat(saved.path("data").path("modelId").asText()).isEqualTo(modelId);

        JsonNode deployed = requireCode(jsonRequest("POST",
                "/workflow/model/deploy?modelId=" + encode(modelId),
                designerToken, null), 200);
        deploymentId = deployed.path("data").path("deploymentId").asText();
        assertThat(deploymentId).isNotBlank();
        ProcessDefinition definition = processEngine.getRepositoryService()
                .createProcessDefinitionQuery().deploymentId(deploymentId).singleResult();
        assertThat(definition).isNotNull();
        processDefinitionId = definition.getId();
        // starter identity link 是正式发起授权数据；测试夹具只给预登记发起人追加本定义权限。
        processEngine.getRepositoryService().addCandidateStarterUser(
                processDefinitionId, String.valueOf(starterUserId));
    }

    /**
     * 核对部署快照、作者属性剥离和服务端固定网关/回线执行资源。
     *
     * @return void，无返回值；部署资源或正式快照不符合编译契约时失败
     * @throws IOException 部署资源无法读取时抛出
     */
    private void assertCompiledDeploymentAndSnapshot() throws IOException
    {
        WfDeployControlledLoop snapshot = artifactRepository.selectControlledLoop(
                deploymentId, processKey, REVIEW_ACTIVITY_ID);
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.getProcessKey()).isEqualTo(processKey);
        assertThat(snapshot.getActivityId()).isEqualTo(REVIEW_ACTIVITY_ID);
        assertThat(snapshot.getDecisionVariable()).isEqualTo("reviewResult");
        assertThat(snapshot.getRepeatValue()).isEqualTo("RECTIFY");
        assertThat(snapshot.getExitValue()).isEqualTo("PASS");
        assertThat(snapshot.getMaxIterations()).isEqualTo(2);
        assertThat(snapshot.getRouteVariable())
                .matches("__approva_loop_route_[0-9a-f]{24}");
        assertThat(snapshot.getIterationVariable())
                .matches("__approva_loop_iteration_[0-9a-f]{24}");

        ProcessDefinition definition = processEngine.getRepositoryService()
                .getProcessDefinition(processDefinitionId);
        try (InputStream stream = processEngine.getRepositoryService().getResourceAsStream(
                deploymentId, definition.getResourceName()))
        {
            assertThat(stream).isNotNull();
            String compiled = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(compiled).contains("exclusiveGateway", "conditionExpression",
                    "__approva_loop_route_", "targetRef=\"" + REVIEW_ACTIVITY_ID + "\"")
                    .doesNotContain("approva.controlledLoop", "standardLoopCharacteristics");
        }
        Model model = processEngine.getRepositoryService().getModel(modelId);
        assertThat(model).isNotNull();
        byte[] editorSource = processEngine.getRepositoryService().getModelEditorSource(modelId);
        assertThat(editorSource).isNotNull();
        String author = new String(editorSource, StandardCharsets.UTF_8);
        assertThat(author).contains("approva.controlledLoop.enabled",
                "approva.controlledLoop.maxIterations")
                .doesNotContain("standardLoopCharacteristics");
    }

    /**
     * 由预登记流程发起人通过真实 API 发起实例。
     *
     * @return String，真实流程实例主键
     * @throws Exception HTTP 或 JSON 处理失败时抛出
     */
    private String startProcess() throws Exception
    {
        JsonNode body = objectMapper.createObjectNode()
                .put("businessKey", FIXTURE_PREFIX + runId)
                .set("variables", objectMapper.createObjectNode()
                        .put("requestReason", "供应商资料需要受控整改"));
        JsonNode response = requireCode(jsonRequest("POST",
                "/workflow/process/start/" + encode(processDefinitionId),
                starterToken, body.toString()), 200);
        String instanceId = response.path("data").path("processInstanceId").asText();
        assertThat(instanceId).isNotBlank();
        return instanceId;
    }

    /**
     * 查询当前实例唯一活动的整改审批任务。
     *
     * @return Task，真实活动任务
     */
    private Task singleActiveReviewTask()
    {
        List<Task> tasks = processEngine.getTaskService().createTaskQuery()
                .processInstanceId(processInstanceId).active().list();
        assertThat(tasks).singleElement().satisfies(task ->
                assertThat(task.getTaskDefinitionKey()).isEqualTo(REVIEW_ACTIVITY_ID));
        return tasks.get(0);
    }

    /**
     * 使用同一正式审批人的两个独立 Token 并发完成首轮任务。
     *
     * @param taskId String，首轮真实任务主键
     * @param decision String，循环判断值
     * @param note String，任务表单说明
     * @return List&lt;JsonNode&gt;，两个真实 HTTP 业务响应
     * @throws Exception 并发等待、HTTP 或 JSON 处理失败时抛出
     */
    private List<JsonNode> completeConcurrently(String taskId, String decision, String note)
            throws Exception
    {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try
        {
            Future<JsonNode> first = executor.submit(() -> concurrentComplete(
                    approverToken, taskId, decision, note, ready, start));
            Future<JsonNode> second = executor.submit(() -> concurrentComplete(
                    concurrentApproverToken, taskId, decision, note, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(HTTP_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                    second.get(HTTP_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
        }
        finally
        {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * 在并发工作线程中等待统一开始信号后提交一次任务完成请求。
     *
     * @param token String，当前线程使用的独立审批人 Token
     * @param taskId String，目标任务主键
     * @param decision String，循环判断值
     * @param note String，任务表单说明
     * @param ready CountDownLatch，线程就绪信号
     * @param start CountDownLatch，统一释放信号
     * @return JsonNode，真实 HTTP 业务响应
     * @throws Exception 等待、HTTP 或 JSON 处理失败时抛出
     */
    private JsonNode concurrentComplete(String token, String taskId, String decision,
            String note, CountDownLatch ready, CountDownLatch start) throws Exception
    {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS))
        {
            throw new IllegalStateException("等待并发任务完成开始信号超时");
        }
        return completeTask(token, taskId, decision, note);
    }

    /**
     * 通过真实任务完成 API 提交受部署表单约束的循环判断和值。
     *
     * @param token String，审批人真实 Token
     * @param taskId String，真实活动任务主键
     * @param decision String，RECTIFY 或 PASS
     * @param note String，本轮审批说明
     * @return JsonNode，统一业务响应
     * @throws Exception HTTP 或 JSON 处理失败时抛出
     */
    private JsonNode completeTask(String token, String taskId, String decision, String note)
            throws Exception
    {
        JsonNode body = objectMapper.createObjectNode()
                .put("taskId", taskId)
                .put("comment", "受控循环验收：" + decision)
                .set("variables", objectMapper.createObjectNode()
                        .put("reviewResult", decision)
                        .put("rectifyNote", note));
        return jsonRequest("POST", "/workflow/task/complete", token, body.toString());
    }

    /**
     * 获取对象授权后的真实流程详情。
     *
     * @param token String，具有实例对象读取权限的 Token
     * @param taskId String，可为活动或历史任务主键
     * @return JsonNode，统一业务响应
     * @throws Exception HTTP 或 JSON 处理失败时抛出
     */
    private JsonNode getDetail(String token, String taskId) throws Exception
    {
        return jsonRequest("GET", "/workflow/process/detail?procInsId="
                + encode(processInstanceId) + "&taskId=" + encode(taskId), token, null);
    }

    /**
     * 记录上限失败前后必须保持不变的正式状态。
     *
     * @param taskId String，第二轮活动任务主键
     * @return SideEffectSnapshot，任务、审计、意见、局部变量和服务端保留变量快照
     */
    private SideEffectSnapshot snapshotFailedCompletionSideEffects(String taskId)
    {
        WfDeployControlledLoop loopConfig = artifactRepository.selectControlledLoop(
                deploymentId, processKey, REVIEW_ACTIVITY_ID);
        assertThat(loopConfig).isNotNull();
        String routeVariable = loopConfig.getRouteVariable();
        String iterationVariable = loopConfig.getIterationVariable();
        Map<String, Object> processVariables = processEngine.getRuntimeService()
                .getVariables(processInstanceId, List.of(routeVariable, iterationVariable));
        return new SideEffectSnapshot(
                processEngine.getTaskService().createTaskQuery().taskId(taskId).active().count(),
                jdbcTemplate.queryForObject(
                        "select count(*) from wf_controlled_loop_execution "
                                + "where process_instance_id = ?",
                        Integer.class, processInstanceId),
                processEngine.getTaskService().getProcessInstanceComments(processInstanceId).size(),
                processEngine.getTaskService().getVariablesLocal(taskId),
                new LinkedHashMap<>(processVariables),
                processEngine.getHistoryService().createHistoricVariableInstanceQuery()
                        .taskId(taskId).count());
    }

    /**
     * 核对详情中的循环轮次、活动状态和逐轮结果。
     *
     * @param detail JsonNode，详情 data 节点
     * @param active boolean，循环节点当前是否活动
     * @param completed int，已完成轮次
     * @param current int，当前显示轮次
     * @param outcomes List&lt;String&gt;，按轮次期望的 REPEAT/EXIT 结果
     * @return void，无返回值；详情投影不一致时失败
     */
    private void assertLoopState(JsonNode detail, boolean active, int completed, int current,
            List<String> outcomes)
    {
        JsonNode states = detail.path("controlledLoopStates");
        assertThat(states.isArray()).isTrue();
        assertThat(states.size()).isOne();
        JsonNode state = states.get(0);
        assertThat(state.path("activityId").asText()).isEqualTo(REVIEW_ACTIVITY_ID);
        assertThat(state.path("maxIterations").asInt()).isEqualTo(2);
        assertThat(state.path("completedIterations").asInt()).isEqualTo(completed);
        assertThat(state.path("currentIteration").asInt()).isEqualTo(current);
        assertThat(state.path("active").asBoolean()).isEqualTo(active);
        List<String> actualOutcomes = new ArrayList<>();
        state.path("rounds").forEach(round -> actualOutcomes.add(round.path("outcome").asText()));
        assertThat(actualOutcomes).containsExactlyElementsOf(outcomes);
    }

    /**
     * 核对指定历史任务由真实审批人完成并已解析用户可见名称。
     *
     * @param detail JsonNode，已结束流程详情 data 节点
     * @param taskId String，待核对的历史任务主键
     * @return void，无返回值；completedBy 或名称映射不一致时失败
     */
    private void assertCompletedBy(JsonNode detail, String taskId)
    {
        List<JsonNode> matches = new ArrayList<>();
        detail.path("historyProcNodeList").forEach(activity ->
        {
            if (taskId.equals(activity.path("taskId").asText()))
            {
                matches.add(activity);
            }
        });
        assertThat(matches).singleElement().satisfies(activity ->
        {
            assertThat(activity.path("completedById").asText())
                    .isEqualTo(String.valueOf(approverUserId));
            assertThat(activity.path("completedByName").asText())
                    .isEqualTo(approverDisplayName);
        });
    }

    /**
     * 生成只包含服务端白名单语义的作者 BPMN，不声明标准循环或任意表达式。
     *
     * @return String，可由模型保存服务校验并在部署时编译的 BPMN XML
     */
    private String controlledLoopAuthorBpmn()
    {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="https://approvaplat.example/controlled-loop-http-it">
                  <process id="%s" name="受控整改循环真实验收" isExecutable="true">
                    <startEvent id="start" name="提交申请" flowable:initiator="initiator">
                      <extensionElements>
                        <flowable:formProperty id="requestReason" name="申请原因" type="string"
                                               readable="true" writable="true" required="true"/>
                      </extensionElements>
                    </startEvent>
                    <sequenceFlow id="toReview" sourceRef="start" targetRef="%s"/>
                    <userTask id="%s" name="整改审批" flowable:assignee="%d"
                              flowable:localScope="true">
                      <extensionElements>
                        <flowable:formProperty id="reviewResult" name="审批结论" type="enum"
                                               readable="true" writable="true" required="true">
                          <flowable:value id="RECTIFY" name="继续整改"/>
                          <flowable:value id="PASS" name="整改通过"/>
                        </flowable:formProperty>
                        <flowable:formProperty id="rectifyNote" name="整改说明" type="string"
                                               readable="true" writable="true" required="true"/>
                        <flowable:properties>
                          <flowable:property name="approva.controlledLoop.enabled" value="true"/>
                          <flowable:property name="approva.controlledLoop.decisionVariable" value="reviewResult"/>
                          <flowable:property name="approva.controlledLoop.repeatValue" value="RECTIFY"/>
                          <flowable:property name="approva.controlledLoop.exitValue" value="PASS"/>
                          <flowable:property name="approva.controlledLoop.maxIterations" value="2"/>
                        </flowable:properties>
                        <flowable:taskListener event="create" delegateExpression="${userTaskListener}"/>
                        <flowable:taskListener event="assignment" delegateExpression="${userTaskListener}"/>
                        <flowable:taskListener event="complete" delegateExpression="${userTaskListener}"/>
                      </extensionElements>
                    </userTask>
                    <sequenceFlow id="toEnd" sourceRef="%s" targetRef="end"/>
                    <endEvent id="end" name="结束"/>
                  </process>
                </definitions>
                """.formatted(processKey, REVIEW_ACTIVITY_ID, REVIEW_ACTIVITY_ID,
                approverUserId, REVIEW_ACTIVITY_ID);
    }

    /**
     * 调用真实验证码入口并要求隔离环境预先关闭验证码。
     *
     * @return void，无返回值；验证码开启时失败且不修改 sys_config
     * @throws Exception HTTP 或 JSON 处理失败时抛出
     */
    private void assertCaptchaDisabled() throws Exception
    {
        JsonNode response = jsonRequest("GET", "/captchaImage", null, null);
        requireCode(response, 200);
        assertThat(response.path("captchaEnabled").asBoolean(true)).isFalse();
    }

    /**
     * 通过真实 /login、MySQL 密码散列和 Redis Token 创建登录态。
     *
     * @param username String，预登记用户名；不得写入日志和断言信息
     * @return String，服务端签发并已写入 Redis 的 JWT
     * @throws Exception HTTP 或 JSON 处理失败时抛出
     */
    private String login(String username) throws Exception
    {
        // 登录口令只从本机忽略配置读取，禁止真实 HTTP 验收把共享 fixture 凭据写入仓库。
        String accountPassword = requireEnvironment(
                "FLOWABLE_RBAC_WORKFLOW_ADMIN_PASSWORD");
        String body = objectMapper.createObjectNode()
                .put("username", username)
                .put("password", accountPassword)
                .put("code", "")
                .put("uuid", "")
                .toString();
        JsonNode response = requireCode(jsonRequest("POST", "/login", null, body), 200);
        String token = response.path("token").asText();
        assertThat(token).isNotBlank();
        loginTokens.add(token);
        return token;
    }

    /**
     * 核对预登记账号唯一、启用、未删除且绑定期望工作流角色。
     *
     * @param username String，预登记用户名
     * @param roleKey String，唯一期望工作流角色键
     * @return long，正式用户主键
     */
    private long requireEnabledUser(String username, String roleKey)
    {
        List<Long> userIds = jdbcTemplate.queryForList(
                "select user_id from sys_user where user_name = ? and status = '0' "
                        + "and del_flag = '0'", Long.class, username);
        assertThat(userIds).singleElement();
        long userId = userIds.get(0);
        assertThat(jdbcTemplate.queryForList(
                "select r.role_key from sys_role r join sys_user_role ur "
                        + "on ur.role_id = r.role_id where ur.user_id = ? "
                        + "and r.role_key like 'workflow_%' and r.status = '0' "
                        + "and r.del_flag = '0'",
                String.class, userId)).containsExactly(roleKey);
        return userId;
    }

    /**
     * 从当前进程读取强制环境变量，错误信息只包含变量名。
     *
     * @param variableName String，环境变量名
     * @return String，非空原始值；调用方不得输出
     */
    private String requireEnvironment(String variableName)
    {
        String value = System.getenv(variableName);
        if (value == null || value.isBlank())
        {
            throw new AssertionError("缺少强制受控循环 IT 环境变量: " + variableName);
        }
        return value;
    }

    /**
     * 发送真实 JSON HTTP 请求并保留若依统一业务响应。
     *
     * @param method String，GET、POST 或 DELETE
     * @param path String，以斜线开头的相对路径，可包含查询参数
     * @param token String，可为空的真实 JWT
     * @param body String，可为空的 UTF-8 JSON 正文
     * @return JsonNode，已解析统一响应
     * @throws IOException 网络或响应读取失败时抛出
     * @throws InterruptedException 当前线程被中断时抛出
     */
    private JsonNode jsonRequest(String method, String path, String token, String body)
            throws IOException, InterruptedException
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri(path))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "workflow-controlled-loop-http-it");
        if (token != null)
        {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null)
        {
            builder.header("Content-Type", "application/json; charset=UTF-8");
        }
        HttpRequest request = switch (method)
        {
            case "GET" -> builder.GET().build();
            case "POST" -> builder.POST(body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            case "DELETE" -> builder.DELETE().build();
            default -> throw new IllegalArgumentException("不支持的 HTTP 方法: " + method);
        };
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        try
        {
            return objectMapper.readTree(response.body());
        }
        catch (RuntimeException exception)
        {
            throw new IllegalStateException("受控循环 HTTP 响应不是合法 JSON", exception);
        }
    }

    /**
     * 发送真实 DELETE 请求。
     *
     * @param path String，以斜线开头的相对路径
     * @param token String，具有目标删除权限的 JWT
     * @return JsonNode，统一业务响应
     * @throws Exception HTTP 或 JSON 处理失败时抛出
     */
    private JsonNode delete(String path, String token) throws Exception
    {
        return jsonRequest("DELETE", path, token, null);
    }

    /**
     * 断言若依统一业务 code 并返回原节点。
     *
     * @param response JsonNode，统一业务响应
     * @param expectedCode int，期望业务 code
     * @return JsonNode，原响应节点
     */
    private JsonNode requireCode(JsonNode response, int expectedCode)
    {
        assertThat(response.path("code").asInt()).isEqualTo(expectedCode);
        return response;
    }

    /**
     * 将路径或查询参数值编码为 UTF-8。
     *
     * @param value String，待编码值
     * @return String，URL 编码文本
     */
    private String encode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 构造当前随机端口的真实 HTTP URI。
     *
     * @param path String，以斜线开头的相对路径
     * @return URI，127.0.0.1 随机端口地址
     */
    private URI baseUri(String path)
    {
        if (!path.startsWith("/"))
        {
            throw new IllegalArgumentException("受控循环 IT 相对路径必须以斜线开头");
        }
        return URI.create("http://127.0.0.1:" + serverPort + path);
    }

    /**
     * 最大轮次失败前后的正式副作用快照。
     *
     * @param activeTaskCount long，目标任务活动数量
     * @param loopExecutionCount int，正式循环审计数量
     * @param commentCount int，实例 Flowable comment 数量
     * @param taskLocalVariables Map&lt;String,Object&gt;，第二轮任务局部变量
     * @param reservedProcessVariables Map&lt;String,Object&gt;，服务端循环保留变量
     * @param historicTaskVariableCount long，第二轮历史局部变量数量
     */
    private record SideEffectSnapshot(long activeTaskCount, int loopExecutionCount,
            int commentCount, Map<String, Object> taskLocalVariables,
            Map<String, Object> reservedProcessVariables, long historicTaskVariableCount)
    {
        /**
         * 复制可变变量映射，保证失败前后比较不受 Flowable 返回对象后续变化影响。
         *
         * @return 无返回值，构造后两个变量映射均为不可变副本
         */
        private SideEffectSnapshot
        {
            taskLocalVariables = Map.copyOf(taskLocalVariables);
            reservedProcessVariables = Map.copyOf(reservedProcessVariables);
        }
    }
}

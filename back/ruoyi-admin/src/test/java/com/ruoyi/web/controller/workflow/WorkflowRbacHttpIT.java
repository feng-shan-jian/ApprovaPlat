package com.ruoyi.web.controller.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.web.controller.workflow.WorkflowRbacMatrix.Access;
import com.ruoyi.web.controller.workflow.WorkflowRbacMatrix.Endpoint;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentStorage;

/**
 * 五角色通过真实 HTTP、Spring Security、JWT/Redis Token 和隔离 MySQL 执行的 URL 拒绝矩阵。
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
        "flowable.rbac.expected-schema=${FLOWABLE_RBAC_EXPECTED_SCHEMA}",
        "flowable.rbac.expected-redis-database=${FLOWABLE_RBAC_REDIS_DATABASE}",
        "flowable.rbac.accounts-registered=${FLOWABLE_RBAC_ACCOUNTS_REGISTERED:false}",
        "flowable.database-schema-update=false",
        "flowable.async-executor-activate=false",
        "flowable.async-history-executor-activate=false",
        "spring.quartz.auto-startup=false",
        "spring.task.scheduling.enabled=false",
        "ruoyi.profile=target/workflow-rbac/profile",
        "logging.level.com.ruoyi=warn"
    }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkflowRbacHttpIT
{
    /** 五角色本地验收账号统一密码，禁止测试任务生成随机复杂密码。 */
    private static final String TEST_ACCOUNT_PASSWORD = "wang";

    /** 五角色预登记账号的用户名环境变量契约，不包含任何真实账号值。 */
    private static final List<AccountEnvironment> ACCOUNT_ENVIRONMENTS = List.of(
            new AccountEnvironment("workflow_admin",
                    "FLOWABLE_RBAC_WORKFLOW_ADMIN_USERNAME"),
            new AccountEnvironment("workflow_designer",
                    "FLOWABLE_RBAC_WORKFLOW_DESIGNER_USERNAME"),
            new AccountEnvironment("workflow_starter",
                    "FLOWABLE_RBAC_WORKFLOW_STARTER_USERNAME"),
            new AccountEnvironment("workflow_approver",
                    "FLOWABLE_RBAC_WORKFLOW_APPROVER_USERNAME"),
            new AccountEnvironment("workflow_auditor",
                    "FLOWABLE_RBAC_WORKFLOW_AUDITOR_USERNAME"));

    /** 业务副作用快照允许读取的表名格式，表名本身来自 information_schema。 */
    private static final Pattern SAFE_TABLE_NAME = Pattern.compile("[A-Za-z0-9_]+");

    /** 拒绝探针使用的无效字符串主键，不与正式 fixture 产生对象关系。 */
    private static final String STRING_ID = "rbac-denied-probe";

    /** 拒绝探针使用的规范附件 UUID，仅用于通过 Web 参数绑定。 */
    private static final String ATTACHMENT_ID =
            "00000000-0000-4000-8000-000000000000";

    /** 拒绝矩阵测试报告的模块 target 相对路径。 */
    private static final Path REPORT_PATH = Path.of(
            "target", "workflow-rbac", "workflow-rbac-http-report.json");

    /** 真实 HTTP 请求的连接和整体验收超时。 */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    @LocalServerPort
    private int serverPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private WorkflowAttachmentStorage attachmentStorage;

    /** 测试专用 JSON 解析器，不依赖应用使用的 Jackson 3 Spring Bean。 */
    private final ObjectMapper objectMapper = JsonMapper.shared();

    @Value("${flowable.rbac.expected-schema}")
    private String expectedSchema;

    @Value("${flowable.rbac.expected-redis-database}")
    private int expectedRedisDatabase;

    /** 操作员确认五角色账号已在首次使用前登记到 Git 忽略文件。 */
    @Value("${flowable.rbac.accounts-registered}")
    private boolean accountsRegistered;

    /** 真实 HttpClient，不配置 Cookie 或重试，所有身份仅通过 Authorization 头传递。 */
    private HttpClient httpClient;

    /** 70 个正式入口的机器可读矩阵。 */
    private List<Endpoint> matrix;

    /** 正式菜单 SQL 解析出的五角色 workflow 权限集合。 */
    private Map<String, Set<String>> rolePermissions;

    /** 登录成功后按角色保存的短期 JWT，结束时逐一调用真实 logout 清理 Redis。 */
    private final Map<String, String> roleTokens = new LinkedHashMap<>();

    /** 五角色正式用户主键，仅用于构造对象授权和任务参与关系。 */
    private final Map<String, Long> roleUserIds = new LinkedHashMap<>();

    /** 五角色正式账号名，仅用于核对服务端审计，不写入机器可读报告。 */
    private final Map<String, String> roleUsernames = new LinkedHashMap<>();

    /** ALLOW 单元的真实 Flowable、MySQL、附件和审计 fixture 执行器。 */
    private WorkflowRbacAllowFixture allowFixture;

    /** 本类创建任何业务 fixture 前的精确残留基线。 */
    private Map<String, Long> initialBusinessSnapshot;

    /**
     * 验证隔离基础设施和预登记五角色账号，然后通过真实登录 API 获取 Token。
     *
     * @return void，无返回值；环境变量、schema、Redis、账号或权限任一不符合即显式失败
     * @throws Exception 真实 HTTP、JSON 或基础设施读取失败时测试失败
     */
    @BeforeAll
    void prepareEnvironmentAndLoginFiveRoles() throws Exception
    {
        matrix = WorkflowRbacMatrix.load();
        rolePermissions = WorkflowRbacMatrix.loadRolePermissions();
        httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        assertThat(matrix).hasSize(70);
        assertThat(jdbcTemplate.queryForObject("select database()", String.class))
                .as("RBAC IT 只能连接显式批准的隔离 schema")
                .isEqualTo(expectedSchema);
        assertThat(expectedRedisDatabase)
                .as("RBAC IT 禁止使用默认 Redis database 0")
                .isPositive();
        assertThat(accountsRegistered)
                .as("首次登录前必须设置 FLOWABLE_RBAC_ACCOUNTS_REGISTERED=true")
                .isTrue();
        try (RedisConnection connection = redisConnectionFactory.getConnection())
        {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
        assertCaptchaDisabledForAutomation();

        initialBusinessSnapshot = snapshotWorkflowTables();

        Set<Long> accountIds = new LinkedHashSet<>();
        for (AccountEnvironment account : ACCOUNT_ENVIRONMENTS)
        {
            String username = requireEnvironment(account.usernameVariable());
            long userId = validatePreRegisteredAccount(account.roleKey(), username);
            assertThat(accountIds.add(userId))
                    .as("五角色必须使用五个不同的预登记账号")
                    .isTrue();
            roleUserIds.put(account.roleKey(), userId);
            roleUsernames.put(account.roleKey(), username);

            String token = login(account.roleKey(), username, TEST_ACCOUNT_PASSWORD);
            validateAuthenticatedRoleAndPermissions(account.roleKey(), token);
            roleTokens.put(account.roleKey(), token);
        }
        assertThat(roleTokens).containsOnlyKeys(WorkflowRbacMatrix.ROLE_KEYS);
        assertThat(roleUserIds).containsOnlyKeys(WorkflowRbacMatrix.ROLE_KEYS);
        assertThat(roleUsernames).containsOnlyKeys(WorkflowRbacMatrix.ROLE_KEYS);

        allowFixture = new WorkflowRbacAllowFixture(serverPort, HTTP_TIMEOUT,
                httpClient, objectMapper, jdbcTemplate, processEngine, attachmentStorage,
                roleTokens, roleUserIds, roleUsernames);
        allowFixture.prepare();
    }

    /**
     * 逐一注销本测试创建的五个登录 Token，避免在隔离 Redis 中留下在线会话。
     *
     * @return void，无返回值；任何真实 logout 失败都会使测试失败
     * @throws Exception logout HTTP 或 JSON 响应无法读取时测试失败
     */
    @AfterAll
    void logoutFiveRoles() throws Exception
    {
        List<String> failures = new ArrayList<>();
        if (allowFixture != null)
        {
            try
            {
                allowFixture.cleanup();
            }
            catch (RuntimeException cleanupFailure)
            {
                failures.add("fixture cleanup="
                        + cleanupFailure.getClass().getSimpleName());
            }
        }
        for (Map.Entry<String, String> entry : roleTokens.entrySet())
        {
            try
            {
                HttpResponse<String> response = send(HttpRequest.newBuilder(baseUri("/logout"))
                        .timeout(HTTP_TIMEOUT)
                        .header("Authorization", "Bearer " + entry.getValue())
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build());
                Integer bodyCode = responseBodyCode(response);
                if (response.statusCode() != 200 || bodyCode == null || bodyCode != 200)
                {
                    failures.add(entry.getKey() + " logout transport="
                            + response.statusCode() + ", bodyCode=" + bodyCode);
                }

                // 注销成功后复用同一 JWT 访问真实认证入口，确认对应 Redis 会话已删除而非仅返回成功文案。
                HttpResponse<String> invalidatedResponse = send(HttpRequest.newBuilder(
                                baseUri("/getInfo"))
                        .timeout(HTTP_TIMEOUT)
                        .header("Authorization", "Bearer " + entry.getValue())
                        .header("Accept", "application/json")
                        .GET()
                        .build());
                Integer invalidatedCode = responseBodyCode(invalidatedResponse);
                if (invalidatedResponse.statusCode() != 200 || invalidatedCode == null
                        || invalidatedCode != 401)
                {
                    failures.add(entry.getKey() + " token invalidation transport="
                            + invalidatedResponse.statusCode() + ", bodyCode="
                            + invalidatedCode);
                }
            }
            catch (IOException | InterruptedException exception)
            {
                failures.add(entry.getKey() + " logout exception="
                        + exception.getClass().getSimpleName());
                if (exception instanceof InterruptedException)
                {
                    Thread.currentThread().interrupt();
                }
            }
        }
        roleTokens.clear();
        roleUserIds.clear();
        roleUsernames.clear();
        assertThat(failures).isEmpty();
    }

    /**
     * 验证已结束流程的不可撤回历史任务不会使已办列表或导出在事务提交阶段返回 500。
     *
     * @return void，无返回值；真实 HTTP、能力字段或 XLSX 内容不符合契约时测试失败
     * @throws Exception HTTP、JSON、XLSX 或审计读取失败时测试失败
     */
    @Test
    @Order(10)
    void returnsFinishedListAndExportForNonRevocableCompletedTask() throws Exception
    {
        allowFixture.verifyFinishedEndpointsForNonRevocableTask("workflow_approver");
    }

    /**
     * 验证角色映射被撤销后，已签发 Token 不能继续执行真实审批动作。
     *
     * @return void，无返回值；旧 Token 未立即拒绝、产生副作用或角色映射未恢复时测试失败
     * @throws Exception 真实 HTTP、JSON 或数据库快照读取失败时测试失败
     */
    @Test
    @Order(15)
    void deniesExistingTokenImmediatelyAfterWorkflowRoleRevocation() throws Exception
    {
        String roleKey = "workflow_approver";
        Long userId = roleUserIds.get(roleKey);
        String token = roleTokens.get(roleKey);
        WorkflowRbacAllowFixture.RevocationActionFixture fixture =
                allowFixture.prepareRoleRevocationAction(roleKey);
        Long roleId = jdbcTemplate.queryForObject(
                "select role_id from sys_role where role_key = ? and status = '0' "
                        + "and del_flag = '0'",
                Long.class, roleKey);
        assertThat(userId).isNotNull();
        assertThat(token).isNotBlank();
        assertThat(roleId).isNotNull();

        // 业务快照不包含本用例主动修改的 sys_user_role，只冻结审批动作可能触及的正式数据。
        Map<String, Object> before = snapshotRevokedTaskAction(fixture);
        int removed = jdbcTemplate.update(
                "delete from sys_user_role where user_id = ? and role_id = ?",
                userId, roleId);
        assertThat(removed).as("必须精确撤销当前审批角色映射").isEqualTo(1);
        try
        {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "taskId", fixture.taskId(),
                    "comment", "RBAC旧Token撤权拒绝",
                    "variables", Map.of("note", "不得写入"),
                    "copyUserIds", List.of(),
                    "nextUserIds", List.of()));
            HttpResponse<String> response = send(authorizedRequest(
                            "/workflow/task/complete", token)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            requestBody, StandardCharsets.UTF_8))
                    .build());

            assertThat(response.statusCode()).as("旧 Token 撤权拒绝传输状态")
                    .isEqualTo(200);
            assertThat(responseBodyCode(response)).as("旧 Token 撤权拒绝业务状态")
                    .isEqualTo(403);
            assertThat(snapshotRevokedTaskAction(fixture))
                    .as("撤权拒绝不得修改任务、执行树、变量、历史、评论、附件或操作日志")
                    .containsExactlyInAnyOrderEntriesOf(before);
        }
        finally
        {
            // 无论 HTTP 或快照断言是否失败，都先恢复预登记职责映射，避免污染后续矩阵。
            int restored = jdbcTemplate.update(
                    "insert into sys_user_role(user_id, role_id) values (?, ?)",
                    userId, roleId);
            assertThat(restored).as("审批角色映射必须在 finally 中恢复").isEqualTo(1);
        }
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_user_role where user_id = ? and role_id = ?",
                Integer.class, userId, roleId)).isEqualTo(1);
    }

    /**
     * 验证用户被停用后，已签发 Token 不能继续执行真实审批动作。
     *
     * @return void，无返回值；旧 Token 未立即拒绝、产生副作用或用户状态未恢复时测试失败
     * @throws Exception 真实 HTTP、JSON 或数据库快照读取失败时测试失败
     */
    @Test
    @Order(16)
    void deniesExistingTokenImmediatelyAfterWorkflowUserIsDisabled() throws Exception
    {
        String roleKey = "workflow_approver";
        Long userId = roleUserIds.get(roleKey);
        String token = roleTokens.get(roleKey);
        assertThat(userId).isNotNull();
        assertThat(token).isNotBlank();
        // 为停用探针创建真实活动审批任务，后续快照覆盖该实例的完整运行时与历史数据。
        WorkflowRbacAllowFixture.RevocationActionFixture fixture =
                allowFixture.prepareRoleRevocationAction(roleKey);
        String originalStatus = jdbcTemplate.queryForObject(
                "select status from sys_user where user_id = ? and del_flag = '0'",
                String.class, userId);
        assertThat(originalStatus).as("停用探针开始前账号必须处于启用状态").isEqualTo("0");

        // sys_user 不属于审批副作用快照；其状态由本用例主动修改并在 finally 中恢复。
        Map<String, Object> before = snapshotRevokedTaskAction(fixture);
        try
        {
            int disabled = jdbcTemplate.update(
                    "update sys_user set status = '1' "
                            + "where user_id = ? and status = ? and del_flag = '0'",
                    userId, originalStatus);
            assertThat(disabled).as("必须精确停用当前审批账号").isEqualTo(1);

            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "taskId", fixture.taskId(),
                    "comment", "RBAC旧Token停用拒绝",
                    "variables", Map.of("note", "不得写入"),
                    "copyUserIds", List.of(),
                    "nextUserIds", List.of()));
            HttpResponse<String> response = send(authorizedRequest(
                            "/workflow/task/complete", token)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            requestBody, StandardCharsets.UTF_8))
                    .build());

            assertThat(response.statusCode()).as("停用用户旧 Token 拒绝传输状态")
                    .isEqualTo(200);
            assertThat(responseBodyCode(response)).as("停用用户旧 Token 拒绝业务状态")
                    .isEqualTo(403);
            assertThat(snapshotRevokedTaskAction(fixture))
                    .as("停用拒绝不得修改任务、执行树、变量、历史、评论、附件或操作日志")
                    .containsExactlyInAnyOrderEntriesOf(before);
        }
        finally
        {
            // 无论 HTTP 或快照断言是否失败，都恢复原状态并立即回读，避免污染后续五角色矩阵。
            jdbcTemplate.update("update sys_user set status = ? where user_id = ?",
                    originalStatus, userId);
            assertThat(jdbcTemplate.queryForObject(
                    "select status from sys_user where user_id = ?",
                    String.class, userId)).isEqualTo(originalStatus);
        }
    }

    /**
     * 验证流程管理员旧 Token 同时保留 cancel 权限时，撤销 terminate 仍会立即禁止结束他人实例。
     *
     * @return void，无返回值；细粒度撤权未生效、失败审计缺失、实例产生副作用或菜单映射未恢复时测试失败
     * @throws Exception 真实 HTTP、JSON 或数据库快照读取失败时测试失败
     */
    @Test
    @Order(17)
    void deniesExistingTokenTerminateAfterFineGrainedPermissionRevocation()
            throws Exception
    {
        String roleKey = "workflow_admin";
        String token = roleTokens.get(roleKey);
        Long actorUserId = roleUserIds.get(roleKey);
        String cancelPermission = "workflow:process:cancel";
        String terminatePermission = "workflow:process:terminate";
        assertThat(token).isNotBlank();
        assertThat(actorUserId).isNotNull();

        // 先冻结登录时 Token 权限快照，明确本用例不是重新登录后的普通拒绝。
        Set<String> tokenPermissions = readTokenWorkflowPermissions(roleKey, token);
        assertThat(tokenPermissions).contains(cancelPermission, terminatePermission);
        RoleMenuGrant cancelGrant = requireRoleMenuGrant(roleKey, cancelPermission);
        RoleMenuGrant terminateGrant = requireRoleMenuGrant(roleKey, terminatePermission);
        assertThat(cancelGrant.roleId()).isEqualTo(terminateGrant.roleId());

        // 实例由其他账号发起且当前任务不属于管理员，cancel 不能替代跨实例 terminate。
        WorkflowRbacAllowFixture.RevocationActionFixture fixture =
                allowFixture.prepareRoleRevocationAction("workflow_approver");
        assertUnrelatedInstance(fixture, actorUserId);
        Map<String, Object> before = snapshotRevokedTaskAction(fixture);

        int removed = jdbcTemplate.update(
                "delete from sys_role_menu where role_id = ? and menu_id = ?",
                terminateGrant.roleId(), terminateGrant.menuId());
        try
        {
            assertThat(removed).as("必须只撤销 terminate 的单条角色菜单映射").isEqualTo(1);
            assertThat(countRoleMenuGrant(terminateGrant)).isZero();
            assertThat(countRoleMenuGrant(cancelGrant))
                    .as("细粒度撤权期间必须保留 cancel 映射").isEqualTo(1);

            // 撤权后禁止调用会刷新登录态的 getInfo，直接复用撤权前已确认含双权限的同一 JWT。
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "instanceId", fixture.instanceId(),
                    "reason", "RBAC旧Token细粒度终止撤权拒绝"));
            // 终止入口带 @Log，记录请求前日志上界以精确识别本次异步失败审计。
            Long auditFloorValue = jdbcTemplate.queryForObject(
                    "select coalesce(max(oper_id), 0) from sys_oper_log", Long.class);
            long auditFloor = auditFloorValue == null ? 0L : auditFloorValue;
            HttpResponse<String> response = send(authorizedRequest(
                            "/workflow/instance/terminate", token)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            requestBody, StandardCharsets.UTF_8))
                    .build());

            assertThat(response.statusCode()).as("旧 Token 终止撤权拒绝传输状态")
                    .isEqualTo(200);
            assertThat(responseBodyCode(response)).as("旧 Token 终止撤权拒绝业务状态")
                    .isEqualTo(403);
            assertFailedOperationAudit(roleUsernames.get(roleKey),
                    "/workflow/instance/terminate",
                    "com.ruoyi.web.controller.workflow.WfInstanceController.terminate()",
                    "无权结束当前流程实例", auditFloor);
            Map<String, Object> after = snapshotRevokedTaskAction(fixture);
            assertRevokedSnapshotUnchangedExceptOperationAudit(before, after, 1L);
        }
        finally
        {
            // 无论请求或副作用断言是否失败，都恢复唯一 terminate 映射，避免污染 350/350 矩阵。
            if (removed == 1)
            {
                int restored = jdbcTemplate.update(
                        "insert into sys_role_menu(role_id, menu_id) values (?, ?)",
                        terminateGrant.roleId(), terminateGrant.menuId());
                assertThat(restored).as("terminate 角色菜单映射必须在 finally 中恢复")
                        .isEqualTo(1);
            }
            assertThat(countRoleMenuGrant(terminateGrant))
                    .as("finally 结束前 terminate 映射必须唯一恢复").isEqualTo(1);
            assertThat(countRoleMenuGrant(cancelGrant))
                    .as("finally 结束前 cancel 映射必须保持唯一").isEqualTo(1);
        }
    }

    /**
     * 验证流程管理员旧 Token 保留 query 权限时，撤销 state 会立即禁止读取无关系实例详情。
     *
     * @return void，无返回值；旧 state 快照绕过对象授权、产生副作用或菜单映射未恢复时测试失败
     * @throws Exception 真实 HTTP、JSON 或数据库快照读取失败时测试失败
     */
    @Test
    @Order(18)
    void deniesExistingTokenCrossInstanceDetailAfterStatePermissionRevocation()
            throws Exception
    {
        String roleKey = "workflow_admin";
        String token = roleTokens.get(roleKey);
        Long actorUserId = roleUserIds.get(roleKey);
        String queryPermission = "workflow:process:query";
        String statePermission = "workflow:process:state";
        assertThat(token).isNotBlank();
        assertThat(actorUserId).isNotNull();

        Set<String> tokenPermissions = readTokenWorkflowPermissions(roleKey, token);
        assertThat(tokenPermissions).contains(queryPermission, statePermission);
        RoleMenuGrant queryGrant = requireRoleMenuGrant(roleKey, queryPermission);
        RoleMenuGrant stateGrant = requireRoleMenuGrant(roleKey, statePermission);
        assertThat(queryGrant.roleId()).isEqualTo(stateGrant.roleId());

        // 保持 query 通过 URL 门禁，目标实例则不提供发起、参与、办理或抄送关系。
        WorkflowRbacAllowFixture.RevocationActionFixture fixture =
                allowFixture.prepareRoleRevocationAction("workflow_approver");
        assertUnrelatedInstance(fixture, actorUserId);
        Map<String, Object> before = snapshotRevokedTaskAction(fixture);

        int removed = jdbcTemplate.update(
                "delete from sys_role_menu where role_id = ? and menu_id = ?",
                stateGrant.roleId(), stateGrant.menuId());
        try
        {
            assertThat(removed).as("必须只撤销 state 的单条角色菜单映射").isEqualTo(1);
            assertThat(countRoleMenuGrant(stateGrant)).isZero();
            assertThat(countRoleMenuGrant(queryGrant))
                    .as("细粒度撤权期间必须保留详情 query 映射").isEqualTo(1);

            // 撤权后不触发 getInfo 权限刷新，直接以撤权前含 query+state 的同一 JWT 请求详情。
            HttpResponse<String> response = send(authorizedRequest(
                            "/workflow/process/detail?procInsId=" + fixture.instanceId(), token)
                    .GET()
                    .build());

            assertThat(response.statusCode()).as("旧 Token 跨实例详情撤权拒绝传输状态")
                    .isEqualTo(200);
            assertThat(responseBodyCode(response)).as("旧 Token 跨实例详情撤权拒绝业务状态")
                    .isEqualTo(403);
            assertThat(snapshotRevokedTaskAction(fixture))
                    .as("详情撤权拒绝不得修改运行树、历史、变量、评论、附件或操作日志")
                    .containsExactlyInAnyOrderEntriesOf(before);
        }
        finally
        {
            // 恢复且回读唯一 state 映射，保证后续管理员 ALLOW 单元仍使用正式权限全集。
            if (removed == 1)
            {
                int restored = jdbcTemplate.update(
                        "insert into sys_role_menu(role_id, menu_id) values (?, ?)",
                        stateGrant.roleId(), stateGrant.menuId());
                assertThat(restored).as("state 角色菜单映射必须在 finally 中恢复")
                        .isEqualTo(1);
            }
            assertThat(countRoleMenuGrant(stateGrant))
                    .as("finally 结束前 state 映射必须唯一恢复").isEqualTo(1);
            assertThat(countRoleMenuGrant(queryGrant))
                    .as("finally 结束前 query 映射必须保持唯一").isEqualTo(1);
        }
    }

    /**
     * 真实执行 184 个 URL 权限拒绝单元和 166 个允许单元，并在两阶段之间冻结拒绝零副作用证据。
     *
     * @return void，无返回值；拒绝语义、报告数量或业务表零副作用任一失败即测试失败
     * @throws Exception HTTP、JSON、SQL 快照或报告写入失败时测试失败
     */
    @Test
    @Order(20)
    void executesCompleteFiveRoleHttpMatrixWithRealBusinessFixtures()
            throws Exception
    {
        Map<String, Long> beforeDenied = snapshotWorkflowTables();
        List<CellResult> results = new ArrayList<>(350);
        List<String> failures = new ArrayList<>();

        // 第一阶段只执行 URL 权限拒绝，随后立即对账，避免 ALLOW 的正式业务写入掩盖拒绝副作用。
        for (Endpoint endpoint : matrix)
        {
            for (String roleKey : WorkflowRbacMatrix.ROLE_KEYS)
            {
                Access access = endpoint.roleAccess().get(roleKey);
                if (access != Access.DENY)
                {
                    continue;
                }
                CellResult result = executeDeniedCell(roleKey, endpoint,
                        roleTokens.get(roleKey));
                results.add(result);
                if (!"PASSED".equals(result.executionStatus()))
                {
                    failures.add(roleKey + " -> " + endpoint.key()
                            + " transport=" + result.transportStatus()
                            + ", bodyCode=" + result.bodyCode());
                }
            }
        }

        Map<String, Long> afterDenied = snapshotWorkflowTables();
        assertThat(afterDenied)
                .as("权限拒绝路径不得修改 Flowable、wf_* 或工作流操作日志行数")
                .containsExactlyInAnyOrderEntriesOf(beforeDenied);

        // 第二阶段为每个 ALLOW 单元创建独立真实对象状态，执行 HTTP 后核对引擎、业务表、文件和审计。
        for (Endpoint endpoint : matrix)
        {
            for (String roleKey : WorkflowRbacMatrix.ROLE_KEYS)
            {
                if (endpoint.roleAccess().get(roleKey) != Access.ALLOW)
                {
                    continue;
                }
                WorkflowRbacAllowFixture.Execution execution =
                        allowFixture.execute(roleKey, endpoint);
                CellResult result = CellResult.allowed(roleKey, endpoint, execution);
                results.add(result);
                if (!"PASSED".equals(result.executionStatus()))
                {
                    failures.add(roleKey + " -> " + endpoint.key()
                            + " transport=" + result.transportStatus()
                            + ", bodyCode=" + result.bodyCode()
                            + ", reason=" + result.reason());
                }
            }
        }

        allowFixture.verifySupplementalObjectAuthorizationDenials();
        allowFixture.cleanup();
        Map<String, Long> afterCleanup = snapshotWorkflowTables();
        assertThat(afterCleanup)
                .as("ALLOW fixture 清理后不得残留流程、模型、附件、业务表或操作日志")
                .containsExactlyInAnyOrderEntriesOf(initialBusinessSnapshot);

        writeReport(results, beforeDenied, afterDenied);

        assertThat(results).hasSize(350);
        assertThat(results.stream().filter(result -> "PASSED".equals(
                result.executionStatus()))).hasSize(350);
        assertThat(results.stream().filter(result -> "NOT_EXECUTED_FIXTURE_REQUIRED".equals(
                result.executionStatus()))).isEmpty();
        assertThat(failures).isEmpty();
    }

    /**
     * 调用真实验证码接口并要求隔离验收环境预先关闭验证码。
     *
     * @return void，无返回值；验证码开启时显式失败，不跳过五角色登录
     * @throws Exception 验证码 HTTP 或 JSON 响应无法读取时测试失败
     */
    private void assertCaptchaDisabledForAutomation() throws Exception
    {
        HttpResponse<String> response = send(HttpRequest.newBuilder(baseUri("/captchaImage"))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build());
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body.path("code").asInt()).isEqualTo(200);
        assertThat(body.path("captchaEnabled").asBoolean(true))
                .as("隔离 RBAC 环境必须预先关闭验证码，测试不会修改 sys_config")
                .isFalse();
    }

    /**
     * 从当前进程读取一个强制环境变量，空值与缺失值均按环境阻断失败。
     *
     * @param variableName String，允许出现在错误信息中的环境变量名
     * @return String，原样环境变量值；调用方不得记录账号或密码内容
     */
    private String requireEnvironment(String variableName)
    {
        String value = System.getenv(variableName);
        if (value == null || value.isBlank())
        {
            throw new AssertionError("缺少强制 RBAC IT 环境变量: " + variableName);
        }
        return value;
    }

    /**
     * 核对账号已存在、启用、非超级管理员且仅绑定期望受管工作流角色。
     *
     * @param roleKey String，当前账号必须绑定的受管角色键
     * @param username String，从环境变量读取但不会写入报告的预登记用户名
     * @return long，已核验且五角色间必须唯一的正式用户主键
     */
    private long validatePreRegisteredAccount(String roleKey, String username)
    {
        List<AccountRow> users = jdbcTemplate.query(
                "select user_id, status, del_flag from sys_user where user_name = ?",
                (resultSet, rowNumber) -> new AccountRow(
                        resultSet.getLong("user_id"),
                        resultSet.getString("status"),
                        resultSet.getString("del_flag")),
                username);
        assertThat(users)
                .as(roleKey + " 必须对应唯一预登记账号，测试不会创建或重置账号")
                .hasSize(1);
        AccountRow user = users.get(0);
        assertThat(user.userId()).as(roleKey + " 禁止使用 user_id=1 超级管理员")
                .isNotEqualTo(1L);
        assertThat(user.status()).as(roleKey + " 账号必须启用").isEqualTo("0");
        assertThat(user.deleted()).as(roleKey + " 账号不得逻辑删除").isEqualTo("0");

        Set<String> managedRoles = new LinkedHashSet<>(jdbcTemplate.queryForList(
                "select r.role_key from sys_role r "
                        + "join sys_user_role ur on ur.role_id = r.role_id "
                        + "where ur.user_id = ? and r.status = '0' and r.del_flag = '0' "
                        + "and r.role_key in (?, ?, ?, ?, ?)",
                String.class, user.userId(),
                WorkflowRbacMatrix.ROLE_KEYS.get(0),
                WorkflowRbacMatrix.ROLE_KEYS.get(1),
                WorkflowRbacMatrix.ROLE_KEYS.get(2),
                WorkflowRbacMatrix.ROLE_KEYS.get(3),
                WorkflowRbacMatrix.ROLE_KEYS.get(4)));
        assertThat(managedRoles).as(roleKey + " 账号的受管工作流角色必须唯一")
                .containsExactly(roleKey);
        return user.userId();
    }

    /**
     * 通过真实 /login、AuthenticationManager、MySQL 密码散列与 Redis Token 创建登录态。
     *
     * @param roleKey String，错误定位使用的角色键
     * @param username String，预登记用户名，不写入测试报告
     * @param password String，预登记密码，只用于本次请求且不得记录
     * @return String，服务端生成并已写入 Redis 的 JWT
     * @throws Exception 登录 HTTP 或 JSON 响应无法读取时测试失败
     */
    private String login(String roleKey, String username, String password) throws Exception
    {
        String body = objectMapper.createObjectNode()
                .put("username", username)
                .put("password", password)
                .put("code", "")
                .put("uuid", "")
                .toString();
        HttpResponse<String> response = send(HttpRequest.newBuilder(baseUri("/login"))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(response.statusCode()).as(roleKey + " 登录传输状态").isEqualTo(200);
        assertThat(json.path("code").asInt()).as(roleKey + " 登录业务状态").isEqualTo(200);
        String token = json.path("token").asText();
        assertThat(token).as(roleKey + " 必须返回真实 Token").isNotBlank();
        return token;
    }

    /**
     * 使用刚创建的 Token 调用 /getInfo，核对角色和正式 workflow 权限全集。
     *
     * @param roleKey String，当前预期的唯一受管工作流角色
     * @param token String，真实登录 API 返回的 JWT
     * @return void，无返回值；Token、角色或菜单权限漂移时测试失败
     * @throws Exception getInfo HTTP 或 JSON 响应无法读取时测试失败
     */
    private void validateAuthenticatedRoleAndPermissions(String roleKey, String token)
            throws Exception
    {
        HttpResponse<String> response = send(authorizedRequest("/getInfo", token)
                .GET().build());
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(response.statusCode()).as(roleKey + " getInfo 传输状态").isEqualTo(200);
        assertThat(json.path("code").asInt()).as(roleKey + " getInfo 业务状态").isEqualTo(200);

        Set<String> managedRoles = jsonArrayToSet(json.path("roles")).stream()
                .filter(WorkflowRbacMatrix.ROLE_KEYS::contains)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> actualWorkflowPermissions = jsonArrayToSet(json.path("permissions")).stream()
                .filter(permission -> permission.startsWith("workflow:"))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        assertThat(managedRoles).as(roleKey + " Token 中受管角色")
                .containsExactly(roleKey);
        assertThat(actualWorkflowPermissions).as(roleKey + " Token 中 workflow 权限")
                .containsExactlyInAnyOrderElementsOf(rolePermissions.get(roleKey));
    }

    /**
     * 在撤权前调用真实 getInfo，确认即将复用的 Token 已持有完整 workflow 权限。
     *
     * @param roleKey String，错误定位使用的受管角色键
     * @param token String，登录后已写入 Redis 且本用例不会刷新的 JWT
     * @return Set&lt;String&gt;，撤权前已同步进 Redis 登录态的工作流权限集合
     * @throws Exception getInfo HTTP 或 JSON 响应无法读取时测试失败
     */
    private Set<String> readTokenWorkflowPermissions(String roleKey, String token)
            throws Exception
    {
        // getInfo 会按正式主数据刷新 Redis 登录态；本方法只允许在撤权前调用。
        HttpResponse<String> response = send(authorizedRequest("/getInfo", token)
                .GET().build());
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(response.statusCode()).as(roleKey + " 旧 Token getInfo 传输状态")
                .isEqualTo(200);
        assertThat(json.path("code").asInt()).as(roleKey + " 旧 Token getInfo 业务状态")
                .isEqualTo(200);
        return jsonArrayToSet(json.path("permissions")).stream()
                .filter(permission -> permission.startsWith("workflow:"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * 从正式角色、菜单和关联表中读取一条唯一的有效工作流授权。
     *
     * @param roleKey String，受管角色 key
     * @param permission String，需要精确撤销并恢复的菜单权限码
     * @return RoleMenuGrant，正式 role_id 与 menu_id 组合
     */
    private RoleMenuGrant requireRoleMenuGrant(String roleKey, String permission)
    {
        List<RoleMenuGrant> grants = jdbcTemplate.query(
                "select role_info.role_id, menu_info.menu_id "
                        + "from sys_role role_info "
                        + "join sys_role_menu role_menu on role_menu.role_id = role_info.role_id "
                        + "join sys_menu menu_info on menu_info.menu_id = role_menu.menu_id "
                        + "where role_info.role_key = ? and role_info.status = '0' "
                        + "and role_info.del_flag = '0' and menu_info.status = '0' "
                        + "and menu_info.perms = ?",
                (resultSet, rowNumber) -> new RoleMenuGrant(
                        resultSet.getLong("role_id"), resultSet.getLong("menu_id")),
                roleKey, permission);
        assertThat(grants).as(roleKey + " 的 " + permission + " 正式菜单映射")
                .hasSize(1);
        return grants.get(0);
    }

    /**
     * 回读指定正式角色菜单授权的当前行数。
     *
     * @param grant RoleMenuGrant，待核对的 role_id 与 menu_id
     * @return int，当前 sys_role_menu 精确映射行数
     */
    private int countRoleMenuGrant(RoleMenuGrant grant)
    {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from sys_role_menu where role_id = ? and menu_id = ?",
                Integer.class, grant.roleId(), grant.menuId());
        return count == null ? 0 : count;
    }

    /**
     * 证明目标流程不是当前管理员发起、参与、办理或接收抄送的对象。
     *
     * @param fixture RevocationActionFixture，真实活动流程实例与任务主键
     * @param actorUserId Long，待验证没有对象关系的正式用户主键
     * @return void，无返回值；任一对象关系命中时测试失败
     */
    private void assertUnrelatedInstance(
            WorkflowRbacAllowFixture.RevocationActionFixture fixture,
            Long actorUserId)
    {
        String actorId = String.valueOf(actorUserId);
        String startUserId = jdbcTemplate.queryForObject(
                "select START_USER_ID_ from ACT_HI_PROCINST where PROC_INST_ID_ = ?",
                String.class, fixture.instanceId());
        assertThat(startUserId).as("目标实例必须由其他用户发起").isNotEqualTo(actorId);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from ACT_RU_TASK where PROC_INST_ID_ = ? "
                        + "and (ASSIGNEE_ = ? or OWNER_ = ?)",
                Long.class, fixture.instanceId(), actorId, actorId))
                .as("目标实例活动任务不得属于当前管理员").isZero();
        assertThat(processEngine.getHistoryService().createHistoricProcessInstanceQuery()
                .processInstanceId(fixture.instanceId()).involvedUser(actorId).count())
                .as("目标实例历史参与关系不得包含当前管理员").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_copy where instance_id = ? and user_id = ? "
                        + "and del_flag = '0'",
                Long.class, fixture.instanceId(), actorUserId))
                .as("目标实例有效抄送关系不得包含当前管理员").isZero();
    }

    /**
     * 把 JSON 字符串数组转换为不重复集合，拒绝缺失或非数组协议。
     *
     * @param node JsonNode，getInfo 返回的 roles 或 permissions 节点
     * @return Set&lt;String&gt;，保持响应顺序的字符串集合
     */
    private Set<String> jsonArrayToSet(JsonNode node)
    {
        assertThat(node.isArray()).isTrue();
        Set<String> values = new LinkedHashSet<>();
        StreamSupport.stream(node.spliterator(), false)
                .forEach(value -> values.add(value.asText()));
        return values;
    }

    /**
     * 执行单个预期拒绝单元，同时捕获传输状态和 AjaxResult 业务状态。
     *
     * @param roleKey String，发起请求的角色键
     * @param endpoint Endpoint，待探测的正式 HTTP 入口
     * @param token String，该角色真实登录后获得的 JWT
     * @return CellResult，PASSED 或 FAILED 的脱敏执行结果
     */
    private CellResult executeDeniedCell(String roleKey, Endpoint endpoint, String token)
    {
        try
        {
            HttpResponse<String> response = send(buildDeniedProbe(endpoint, token));
            Integer bodyCode = responseBodyCode(response);
            boolean passed = response.statusCode() == 200
                    && bodyCode != null && bodyCode == 403;
            return new CellResult(roleKey, endpoint.controller(), endpoint.handler(),
                    endpoint.httpMethod(), endpoint.path(), Access.DENY.name(),
                    passed ? "PASSED" : "FAILED", response.statusCode(), bodyCode,
                    passed ? "URL_PERMISSION_REJECTED" : "UNEXPECTED_REJECTION_PROTOCOL");
        }
        catch (IOException exception)
        {
            return CellResult.failed(roleKey, endpoint, "HTTP_IO_FAILURE");
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            return CellResult.failed(roleKey, endpoint, "HTTP_INTERRUPTED");
        }
    }

    /**
     * 为一个拒绝单元构造能通过 Web 参数绑定、但不会命中真实业务对象的 HTTP 请求。
     *
     * @param endpoint Endpoint，包含动词和路径模板的矩阵入口
     * @param token String，当前角色的真实 JWT
     * @return HttpRequest，携带有效最小参数及 Authorization 的拒绝探针
     */
    private HttpRequest buildDeniedProbe(Endpoint endpoint, String token)
    {
        String path = probePath(endpoint);
        ProbePayload payload = probePayload(endpoint);
        HttpRequest.Builder builder = authorizedRequest(path, token);
        if (payload.contentType() != null)
        {
            builder.header("Content-Type", payload.contentType());
        }
        return builder.method(endpoint.httpMethod(), payload.bodyPublisher()).build();
    }

    /**
     * 将 mapping 路径变量替换为固定无效主键，并补齐 Web 层必需查询参数。
     *
     * @param endpoint Endpoint，待探测入口
     * @return String，可直接请求且不引用正式业务 fixture 的相对 URL
     */
    private String probePath(Endpoint endpoint)
    {
        String path = endpoint.path()
                .replace("{attachmentId}", ATTACHMENT_ID)
                .replace("{categoryIds}", "1")
                .replace("{categoryId}", "1")
                .replace("{formIds}", "1")
                .replace("{formId}", "1")
                .replace("{deployIds}", STRING_ID)
                .replace("{definitionId}", STRING_ID)
                .replace("{modelIds}", STRING_ID)
                .replace("{modelId}", STRING_ID)
                .replace("{processDefId}", STRING_ID)
                .replace("{instanceIds}", STRING_ID)
                .replace("{processId}", STRING_ID)
                .replace("{taskId}", STRING_ID);
        return switch (endpoint.key())
        {
            case "WfDeployController#publishList" -> path + "?processKey=rbac_denied_probe";
            case "WfDeployController#changeState" -> path
                    + "?state=active&definitionId=" + STRING_ID;
            case "WfIdentityController#options" -> path + "?type=user&pageNum=1&pageSize=1";
            case "WfModelController#latest", "WfModelController#deployModel" -> path
                    + "?modelId=" + STRING_ID;
            case "WfProcessController#getForm" -> path
                    + "?definitionId=" + STRING_ID + "&deployId=" + STRING_ID;
            case "WfProcessController#detail" -> path + "?procInsId=" + STRING_ID;
            default -> path;
        };
    }

    /**
     * 为拒绝探针提供通过反序列化和 Bean Validation 的最小请求体。
     *
     * @param endpoint Endpoint，待探测入口
     * @return ProbePayload，JSON、multipart 或空请求体及对应 Content-Type
     */
    private ProbePayload probePayload(Endpoint endpoint)
    {
        if ("WfAttachmentController#upload".equals(endpoint.key()))
        {
            String boundary = "WorkflowRbacDeniedBoundary";
            String multipart = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"fieldName\"\r\n\r\n"
                    + "rbacProbe\r\n"
                    + "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"probe.txt\"\r\n"
                    + "Content-Type: text/plain\r\n\r\n"
                    + "permission probe\r\n"
                    + "--" + boundary + "--\r\n";
            return new ProbePayload("multipart/form-data; boundary=" + boundary,
                    HttpRequest.BodyPublishers.ofString(multipart, StandardCharsets.UTF_8));
        }
        String json = jsonProbeBody(endpoint.key());
        if (json == null)
        {
            return new ProbePayload(null, HttpRequest.BodyPublishers.noBody());
        }
        return new ProbePayload("application/json; charset=UTF-8",
                HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
    }

    /**
     * 返回指定写入口通过 Web 校验所需的最小 JSON；无请求体入口返回 null。
     *
     * @param endpointKey String，Controller#handler 稳定入口键
     * @return String，固定无效业务主键的 JSON 或 null
     */
    private String jsonProbeBody(String endpointKey)
    {
        return switch (endpointKey)
        {
            case "WfCategoryController#add" ->
                "{\"categoryName\":\"RBAC拒绝探针\",\"code\":\"rbac_denied_probe\"}";
            case "WfCategoryController#edit" ->
                "{\"categoryId\":1,\"categoryName\":\"RBAC拒绝探针\","
                        + "\"code\":\"rbac_denied_probe\"}";
            case "WfFormController#add" ->
                "{\"formName\":\"RBAC拒绝探针\",\"content\":\"[]\"}";
            case "WfFormController#edit" ->
                "{\"formId\":1,\"formName\":\"RBAC拒绝探针\","
                        + "\"content\":\"[]\"}";
            case "WfInstanceController#updateState" ->
                "{\"instanceId\":\"" + STRING_ID + "\",\"state\":\"active\"}";
            case "WfInstanceController#terminate" ->
                "{\"instanceId\":\"" + STRING_ID + "\","
                        + "\"reason\":\"RBAC拒绝探针\"}";
            case "WfModelController#add" ->
                "{\"modelName\":\"RBAC拒绝探针\",\"modelKey\":\"rbac_denied_probe\","
                        + "\"category\":\"rbac_denied_probe\"}";
            case "WfModelController#edit" ->
                "{\"modelId\":\"" + STRING_ID + "\","
                        + "\"modelName\":\"RBAC拒绝探针\"}";
            case "WfModelController#save" ->
                "{\"requestId\":\"" + UUID.randomUUID() + "\","
                        + "\"modelId\":\"" + STRING_ID + "\","
                        + "\"bpmnXml\":\"<definitions/>\",\"newVersion\":false}";
            case "WfProcessController#start" -> "{}";
            case "WfTaskController#stopProcess" ->
                "{\"procInsId\":\"" + STRING_ID + "\","
                        + "\"comment\":\"RBAC拒绝探针\"}";
            case "WfTaskController#revokeProcess" ->
                "{\"procInsId\":\"" + STRING_ID + "\",\"taskId\":\""
                        + STRING_ID + "\",\"comment\":\"RBAC拒绝探针\"}";
            case "WfTaskController#adjustMultiInstance" ->
                "{\"taskId\":\"" + STRING_ID + "\",\"action\":\"ADD\","
                        + "\"expectedRevision\":0,\"comment\":\"RBAC拒绝探针\","
                        + "\"userIds\":[1]}";
            case "WfTaskController#complete" ->
                "{\"taskId\":\"" + STRING_ID + "\","
                        + "\"comment\":\"RBAC拒绝探针\",\"variables\":{},"
                        + "\"copyUserIds\":[],\"nextUserIds\":[]}";
            case "WfTaskController#reject" ->
                "{\"taskId\":\"" + STRING_ID + "\","
                        + "\"comment\":\"RBAC拒绝探针\",\"copyUserIds\":[]}";
            case "WfTaskController#returnTask" ->
                "{\"taskId\":\"" + STRING_ID + "\",\"targetKey\":\"review\","
                        + "\"comment\":\"RBAC拒绝探针\",\"copyUserIds\":[]}";
            case "WfTaskController#returnList", "WfTaskController#claim",
                    "WfTaskController#unClaim" ->
                "{\"taskId\":\"" + STRING_ID + "\"}";
            case "WfTaskController#resolve" ->
                "{\"taskId\":\"" + STRING_ID + "\","
                        + "\"comment\":\"RBAC拒绝探针\",\"copyUserIds\":[]}";
            case "WfTaskController#delegate", "WfTaskController#transfer" ->
                "{\"taskId\":\"" + STRING_ID + "\",\"userId\":1,"
                        + "\"comment\":\"RBAC拒绝探针\",\"copyUserIds\":[]}";
            default -> null;
        };
    }

    /**
     * 读取当前 schema 全部 ACT_*、wf_* 及工作流操作日志的行数快照。
     *
     * @return Map&lt;String, Long&gt;，按表名排序的零副作用对账快照
     */
    private Map<String, Long> snapshotWorkflowTables()
    {
        List<String> allTables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables "
                        + "where table_schema = ? and table_type = 'BASE TABLE'",
                String.class, expectedSchema);
        Map<String, Long> snapshot = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String table : allTables)
        {
            String normalized = table.toLowerCase(Locale.ROOT);
            if (!(normalized.startsWith("act_") || normalized.startsWith("wf_")
                    || "sys_oper_log".equals(normalized)))
            {
                continue;
            }
            if (!SAFE_TABLE_NAME.matcher(table).matches())
            {
                throw new AssertionError("information_schema 返回了非法表名");
            }
            Long count = jdbcTemplate.queryForObject(
                    "select count(*) from `" + table + "`", Long.class);
            snapshot.put(table, count == null ? 0L : count);
        }
        assertThat(snapshot.keySet()).anyMatch(table ->
                table.toLowerCase(Locale.ROOT).startsWith("act_ru_"));
        assertThat(snapshot).containsKey("sys_oper_log");
        return Map.copyOf(snapshot);
    }

    /**
     * 冻结一次审批完成可能修改的全表行数与目标实例行级状态。
     *
     * @param fixture RevocationActionFixture，真实实例和活动任务主键
     * @return Map&lt;String, Object&gt;，可直接做深度相等比较的稳定数据库快照
     */
    private Map<String, Object> snapshotRevokedTaskAction(
            WorkflowRbacAllowFixture.RevocationActionFixture fixture)
    {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("workflowTableRows", snapshotWorkflowTables());
        snapshot.put("runtimeTask", jdbcTemplate.queryForList(
                "select ID_, REV_, EXECUTION_ID_, PROC_INST_ID_, TASK_DEF_KEY_, "
                        + "OWNER_, ASSIGNEE_, DELEGATION_, SUSPENSION_STATE_, "
                        + "VAR_COUNT_, ID_LINK_COUNT_, SUB_TASK_COUNT_ "
                        + "from ACT_RU_TASK where ID_ = ?",
                fixture.taskId()));
        snapshot.put("runtimeExecutions", jdbcTemplate.queryForList(
                "select ID_, REV_, PARENT_ID_, SUPER_EXEC_, ACT_ID_, IS_ACTIVE_, "
                        + "IS_CONCURRENT_, IS_SCOPE_, IS_MI_ROOT_, SUSPENSION_STATE_, "
                        + "TASK_COUNT_, JOB_COUNT_, VAR_COUNT_, ID_LINK_COUNT_ "
                        + "from ACT_RU_EXECUTION where PROC_INST_ID_ = ? order by ID_",
                fixture.instanceId()));
        snapshot.put("runtimeVariables", jdbcTemplate.queryForList(
                "select ID_, REV_, NAME_, TYPE_, EXECUTION_ID_, TASK_ID_, "
                        + "TEXT_, TEXT2_, LONG_, DOUBLE_ "
                        + "from ACT_RU_VARIABLE where PROC_INST_ID_ = ? order by ID_",
                fixture.instanceId()));
        snapshot.put("runtimeIdentityLinks", jdbcTemplate.queryForList(
                "select ID_, REV_, GROUP_ID_, TYPE_, USER_ID_, TASK_ID_, PROC_INST_ID_ "
                        + "from ACT_RU_IDENTITYLINK where PROC_INST_ID_ = ? or TASK_ID_ = ? "
                        + "order by ID_",
                fixture.instanceId(), fixture.taskId()));
        snapshot.put("historicTask", jdbcTemplate.queryForList(
                "select ID_, REV_, EXECUTION_ID_, PROC_INST_ID_, TASK_DEF_KEY_, "
                        + "OWNER_, ASSIGNEE_, END_TIME_, DELETE_REASON_, COMPLETED_BY_ "
                        + "from ACT_HI_TASKINST where ID_ = ?",
                fixture.taskId()));
        snapshot.put("historicProcess", jdbcTemplate.queryForList(
                "select ID_, REV_, PROC_INST_ID_, END_TIME_, END_ACT_ID_, DELETE_REASON_ "
                        + "from ACT_HI_PROCINST where PROC_INST_ID_ = ?",
                fixture.instanceId()));
        snapshot.put("historicVariables", jdbcTemplate.queryForList(
                "select ID_, REV_, NAME_, VAR_TYPE_, TASK_ID_, EXECUTION_ID_, "
                        + "TEXT_, TEXT2_, LONG_, DOUBLE_ "
                        + "from ACT_HI_VARINST where PROC_INST_ID_ = ? order by ID_",
                fixture.instanceId()));
        snapshot.put("historicComments", jdbcTemplate.queryForList(
                "select ID_, TYPE_, TIME_, USER_ID_, TASK_ID_, PROC_INST_ID_, ACTION_, MESSAGE_ "
                        + "from ACT_HI_COMMENT where PROC_INST_ID_ = ? order by ID_",
                fixture.instanceId()));
        return Map.copyOf(snapshot);
    }

    /**
     * 等待 @Log 异步落库，并精确核对一次被业务层拒绝的失败操作审计。
     *
     * @param operatorName String，真实登录操作员账号名
     * @param requestPath String，不含查询参数的真实请求路径
     * @param expectedMethod String，期望记录的 Controller 方法全名
     * @param expectedError String，期望记录的稳定业务拒绝原因
     * @param beforeLogId long，请求发送前的最大操作日志主键
     * @return void，无返回值；五秒内未出现唯一且字段完整的失败审计时测试失败
     * @throws InterruptedException 等待异步日志期间线程被中断时抛出
     */
    private void assertFailedOperationAudit(String operatorName, String requestPath,
            String expectedMethod, String expectedError, long beforeLogId)
            throws InterruptedException
    {
        assertThat(operatorName).isNotBlank();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        Long matchingCount = 0L;
        do
        {
            matchingCount = jdbcTemplate.queryForObject(
                    "select count(*) from sys_oper_log where oper_id > ? "
                            + "and oper_name = ? and oper_url = ?",
                    Long.class, beforeLogId, operatorName, requestPath);
            if (matchingCount != null && matchingCount > 0L)
            {
                break;
            }
            Thread.sleep(50L);
        }
        while (System.nanoTime() < deadline);

        assertThat(matchingCount).as("细粒度权限拒绝必须持久化唯一失败操作审计")
                .isEqualTo(1L);
        Long exactCount = jdbcTemplate.queryForObject(
                "select count(*) from sys_oper_log where oper_id > ? "
                        + "and oper_name = ? and oper_url = ? and status = 1 "
                        + "and request_method = 'POST' and method = ? and error_msg = ?",
                Long.class, beforeLogId, operatorName, requestPath,
                expectedMethod, expectedError);
        assertThat(exactCount).as("失败操作审计的状态、入口和拒绝原因必须完整")
                .isEqualTo(1L);
    }

    /**
     * 核对撤权拒绝前后所有业务数据保持不变，仅允许已单独验证的操作审计按预期增长。
     *
     * @param before Map&lt;String, Object&gt;，请求前业务表与目标实例快照
     * @param after Map&lt;String, Object&gt;，失败审计落库后的业务表与目标实例快照
     * @param expectedAuditDelta long，sys_oper_log 允许增加的精确行数
     * @return void，无返回值；任何领域数据变化或审计增量不符时测试失败
     */
    private void assertRevokedSnapshotUnchangedExceptOperationAudit(
            Map<String, Object> before, Map<String, Object> after,
            long expectedAuditDelta)
    {
        Map<String, Object> normalizedBefore = new LinkedHashMap<>(before);
        Map<String, Object> normalizedAfter = new LinkedHashMap<>(after);
        Object beforeRowsValue = normalizedBefore.remove("workflowTableRows");
        Object afterRowsValue = normalizedAfter.remove("workflowTableRows");
        assertThat(beforeRowsValue).isInstanceOf(Map.class);
        assertThat(afterRowsValue).isInstanceOf(Map.class);

        Map<Object, Object> beforeRows = new LinkedHashMap<>((Map<?, ?>) beforeRowsValue);
        Map<Object, Object> afterRows = new LinkedHashMap<>((Map<?, ?>) afterRowsValue);
        Object beforeAuditValue = beforeRows.remove("sys_oper_log");
        Object afterAuditValue = afterRows.remove("sys_oper_log");
        assertThat(beforeAuditValue).isInstanceOf(Number.class);
        assertThat(afterAuditValue).isInstanceOf(Number.class);

        // 操作审计是拒绝请求的安全证据，不属于审批领域状态；其余全表及行级快照必须逐项相同。
        assertThat(afterRows).containsExactlyInAnyOrderEntriesOf(beforeRows);
        assertThat(normalizedAfter).containsExactlyInAnyOrderEntriesOf(normalizedBefore);
        long beforeAuditCount = ((Number) beforeAuditValue).longValue();
        long afterAuditCount = ((Number) afterAuditValue).longValue();
        assertThat(afterAuditCount).as("失败终止请求只能新增一条操作审计")
                .isEqualTo(beforeAuditCount + expectedAuditDelta);
    }

    /**
     * 将 350 单元执行状态、传输/业务状态及副作用快照写入 target JSON 报告。
     *
     * @param results List&lt;CellResult&gt;，按入口和五角色固定顺序排列的结果
     * @param before Map&lt;String, Long&gt;，拒绝探针前业务表行数
     * @param after Map&lt;String, Long&gt;，拒绝探针后业务表行数
     * @return void，无返回值；报告目录或 JSON 无法写入时测试失败
     * @throws IOException target 报告无法创建或序列化时抛出
     */
    private void writeReport(List<CellResult> results, Map<String, Long> before,
            Map<String, Long> after) throws IOException
    {
        Path parent = REPORT_PATH.getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }
        long passed = results.stream()
                .filter(result -> "PASSED".equals(result.executionStatus())).count();
        long notExecuted = results.stream()
                .filter(result -> "NOT_EXECUTED_FIXTURE_REQUIRED".equals(
                        result.executionStatus())).count();
        long failed = results.stream()
                .filter(result -> "FAILED".equals(result.executionStatus())).count();
        MatrixReport report = new MatrixReport(
                "workflow-rbac-http-report/v1", Instant.now().toString(),
                expectedSchema, expectedRedisDatabase, 9, 70, 5, 350,
                passed, notExecuted, failed, before.equals(after),
                before, after, List.copyOf(results));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(REPORT_PATH.toFile(), report);
    }

    /**
     * 创建包含真实服务地址、公共请求头和超时的认证请求 Builder。
     *
     * @param path String，以斜线开头的相对 URL
     * @param token String，真实登录 API 返回的 JWT
     * @return HttpRequest.Builder，尚未指定 HTTP 动词和请求体的 Builder
     */
    private HttpRequest.Builder authorizedRequest(String path, String token)
    {
        return HttpRequest.newBuilder(baseUri(path))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                // 拒绝矩阵同时覆盖 JSON、文件和 PNG 入口，通配 Accept 确保先匹配真实路由再执行方法权限校验。
                .header("Accept", "*/*")
                .header("User-Agent", "workflow-rbac-http-it");
    }

    /**
     * 把相对 URL 转换为当前 SpringBootTest 随机真实端口 URI。
     *
     * @param path String，以斜线开头的相对 URL
     * @return URI，指向 127.0.0.1 随机真实端口的请求地址
     */
    private URI baseUri(String path)
    {
        if (!path.startsWith("/"))
        {
            throw new IllegalArgumentException("RBAC IT 相对 URL 必须以斜线开头");
        }
        return URI.create("http://127.0.0.1:" + serverPort + path);
    }

    /**
     * 发送真实 HTTP 请求并以 UTF-8 字符串接收响应。
     *
     * @param request HttpRequest，已完整构造的真实请求
     * @return HttpResponse&lt;String&gt;，保留传输状态和响应正文
     * @throws IOException 网络或响应读取失败
     * @throws InterruptedException 当前测试线程被中断
     */
    private HttpResponse<String> send(HttpRequest request)
            throws IOException, InterruptedException
    {
        return httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * 从 AjaxResult JSON 中读取业务 code；二进制或非法 JSON 返回 null。
     *
     * @param response HttpResponse&lt;String&gt;，真实 HTTP 响应
     * @return Integer，响应业务 code；正文不是含整数 code 的 JSON 时返回 null
     */
    private Integer responseBodyCode(HttpResponse<String> response)
    {
        try
        {
            JsonNode code = objectMapper.readTree(response.body()).path("code");
            return code.isIntegralNumber() ? code.intValue() : null;
        }
        catch (JacksonException exception)
        {
            return null;
        }
    }

    /**
     * 角色及其预登记账号环境变量名，不保存任何真实账号值。
     *
     * @param roleKey String，受管工作流角色键
     * @param usernameVariable String，用户名环境变量名
     */
    private record AccountEnvironment(String roleKey, String usernameVariable)
    {
    }

    /**
     * 从正式 sys_user 读取的最小账号状态投影。
     *
     * @param userId long，若依正式用户主键
     * @param status String，0 表示启用
     * @param deleted String，0 表示未逻辑删除
     */
    private record AccountRow(long userId, String status, String deleted)
    {
    }

    /**
     * 一条需要在细粒度撤权测试中精确删除并恢复的正式角色菜单映射。
     *
     * @param roleId long，workflow_admin 的正式角色主键
     * @param menuId long，单个工作流权限的正式菜单主键
     */
    private record RoleMenuGrant(long roleId, long menuId)
    {
    }

    /**
     * 拒绝探针请求体与 Content-Type 的不可变组合。
     *
     * @param contentType String，请求 Content-Type；空值表示不发送该请求头
     * @param bodyPublisher HttpRequest.BodyPublisher，真实请求体发布器
     */
    private record ProbePayload(String contentType,
            HttpRequest.BodyPublisher bodyPublisher)
    {
    }

    /**
     * 单个角色与入口矩阵单元的脱敏执行结果。
     *
     * @param roleKey String，五角色之一
     * @param controller String，Controller 简单类名
     * @param handler String，映射方法名
     * @param httpMethod String，大写 HTTP 动词
     * @param pathTemplate String，固定 mapping 路径模板
     * @param expectedAccess String，ALLOW 或 DENY
     * @param executionStatus String，PASSED、FAILED 或 NOT_EXECUTED_FIXTURE_REQUIRED
     * @param transportStatus Integer，真实 HTTP 状态；未执行时为空
     * @param bodyCode Integer，AjaxResult 业务状态；未执行或非 JSON 时为空
     * @param reason String，稳定机器可读原因
     */
    private record CellResult(String roleKey, String controller, String handler,
            String httpMethod, String pathTemplate, String expectedAccess,
            String executionStatus, Integer transportStatus, Integer bodyCode,
            String reason)
    {
        /**
         * 为允许路径创建明确的未执行结果，禁止把缺少 fixture 伪装为成功。
         *
         * @param roleKey String，五角色之一
         * @param endpoint Endpoint，URL 层允许但本基础测试未执行的入口
         * @return CellResult，NOT_EXECUTED_FIXTURE_REQUIRED 结果
         */
        static CellResult allowed(String roleKey, Endpoint endpoint,
                WorkflowRbacAllowFixture.Execution execution)
        {
            return new CellResult(roleKey, endpoint.controller(), endpoint.handler(),
                    endpoint.httpMethod(), endpoint.path(), Access.ALLOW.name(),
                    execution.passed() ? "PASSED" : "FAILED",
                    execution.transportStatus(), execution.bodyCode(), execution.reason());
        }

        /**
         * 为网络异常创建不含异常文本和凭据的稳定失败结果。
         *
         * @param roleKey String，五角色之一
         * @param endpoint Endpoint，执行失败的拒绝入口
         * @param reason String，脱敏失败分类
         * @return CellResult，FAILED 结果
         */
        static CellResult failed(String roleKey, Endpoint endpoint, String reason)
        {
            return new CellResult(roleKey, endpoint.controller(), endpoint.handler(),
                    endpoint.httpMethod(), endpoint.path(), Access.DENY.name(),
                    "FAILED", null, null, reason);
        }
    }

    /**
     * 一次真实 HTTP 矩阵执行的机器可读报告。
     *
     * @param schemaVersion String，报告结构版本
     * @param generatedAt String，UTC ISO-8601 生成时间
     * @param databaseSchema String，已核验的隔离 schema 名
     * @param redisDatabase int，已核验的隔离 Redis database
     * @param controllerCount int，冻结 Controller 数量
     * @param endpointCount int，冻结 mapping 数量
     * @param roleCount int，职责分离角色数量
     * @param matrixCellCount int，完整矩阵单元数
     * @param passedCount long，真实执行且通过的拒绝单元数
     * @param notExecutedCount long，缺少业务 fixture 的允许单元数
     * @param failedCount long，真实执行失败单元数
     * @param zeroSideEffects boolean，前后业务表行数是否完全一致
     * @param tableRowsBefore Map&lt;String, Long&gt;，执行前业务表快照
     * @param tableRowsAfter Map&lt;String, Long&gt;，执行后业务表快照
     * @param cells List&lt;CellResult&gt;，350 个脱敏矩阵结果
     */
    private record MatrixReport(String schemaVersion, String generatedAt,
            String databaseSchema, int redisDatabase, int controllerCount,
            int endpointCount, int roleCount, int matrixCellCount,
            long passedCount, long notExecutedCount, long failedCount,
            boolean zeroSideEffects, Map<String, Long> tableRowsBefore,
            Map<String, Long> tableRowsAfter, List<CellResult> cells)
    {
    }
}

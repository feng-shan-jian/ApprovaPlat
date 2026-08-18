package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.Model;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.flowable.domain.WfSqlDataSource;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.model.WorkflowSqlDataSourceService;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.service.ISysConfigService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 通过真实登录、JWT、HTTP、MySQL 和 Flowable 验证连接器发布执行边界。
 */
@SpringBootTest(classes = RuoYiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.druid.master.url=${FLOWABLE_IT_JDBC_URL}",
            "spring.datasource.druid.master.username=${FLOWABLE_IT_USERNAME}",
            "spring.datasource.druid.master.password=${FLOWABLE_IT_PASSWORD}",
            "spring.datasource.druid.stat-view-servlet.enabled=false",
            "spring.datasource.druid.web-stat-filter.enabled=false",
            "spring.data.redis.host=${FLOWABLE_RBAC_REDIS_HOST:127.0.0.1}",
            "spring.data.redis.port=${FLOWABLE_RBAC_REDIS_PORT:6379}",
            "spring.data.redis.password=${FLOWABLE_RBAC_REDIS_PASSWORD:}",
            "spring.data.redis.database=${FLOWABLE_IT_REDIS_DATABASE:15}",
            "token.secret=d29ya2Zsb3ctY29ubmVjdG9yLXB1YmxpY2F0aW9uLWl0LXNlY3JldC13b3JrZmxvdy1jb25uZWN0b3ItcHVibGljYXRpb24taXQtc2VjcmV0",
            "flowable.connector-publication.expected-schema=${FLOWABLE_IT_EXPECTED_SCHEMA}",
            "flowable.database-schema-update=false",
            "flowable.async-executor-activate=false",
            "flowable.async-history-executor-activate=false",
            "spring.quartz.auto-startup=false",
            "spring.task.scheduling.enabled=false",
            "ruoyi.profile=target/workflow-connector-publication/profile",
            "logging.level.com.ruoyi=warn"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkflowConnectorPublicationHttpIT
{
    /** 真实 HTTP 请求的最大等待时间。 */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    /** 本轮模型、目录、分类和用户共同使用的稳定测试前缀。 */
    private static final String FIXTURE_PREFIX = "connector-publication-http-it-";

    @LocalServerPort
    private int serverPort;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private WorkflowDeploymentArtifactRepository artifactRepository;

    @Autowired
    private ISysConfigService sysConfigService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Value("${flowable.connector-publication.expected-schema}")
    private String expectedSchema;

    /** 本轮唯一标识，所有清理和残留断言均以此限定。 */
    private final String runId = UUID.randomUUID().toString().replace("-", "")
            .substring(0, 12);

    /** 三个被拒绝模型的 Flowable 主键。 */
    private final List<String> modelIds = new ArrayList<>();

    /** 三个被拒绝模型的稳定流程 key。 */
    private final List<String> processKeys = new ArrayList<>();

    private final ObjectMapper objectMapper = JsonMapper.shared();
    private HttpClient httpClient;
    private String fixturePrefix;
    private String categoryCode;
    private String dataSourceKey;
    private String temporaryUsername;
    private Long temporaryUserId;
    private Long dataSourceId;
    private String loginToken;
    private String loginTokenId;
    private String originalCaptchaEnabled;

    /**
     * 创建临时设计者、外库目录、分类和真实 Redis 登录态。
     * @return void，环境或正式主数据基线不满足时测试立即失败
     * @throws Exception HTTP 登录或 JSON 处理失败时抛出
     */
    @BeforeAll
    void prepareEnvironment() throws Exception
    {
        assertThat(jdbc.queryForObject("select database()", String.class))
                .as("连接器发布 IT 必须连接显式指定的真实 MySQL schema")
                .isEqualTo(expectedSchema);
        fixturePrefix = FIXTURE_PREFIX + runId;
        categoryCode = "connector_publication_" + runId;
        dataSourceKey = "connector.publication." + runId;
        temporaryUsername = "wfpub_" + runId;
        httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build();

        // 登录链必须显式关闭验证码并在清理阶段恢复，禁止依赖宿主环境偶然配置。
        originalCaptchaEnabled = jdbc.queryForObject(
                "select config_value from sys_config where config_key=?",
                String.class, "sys.account.captchaEnabled");
        assertThat(jdbc.update("update sys_config set config_value='false' where config_key=?",
                "sys.account.captchaEnabled")).isOne();
        sysConfigService.resetConfigCache();
        assertThat(requireCode(jsonRequest("GET", "/captchaImage", null, null), 200)
                .path("captchaEnabled").asBoolean(true)).isFalse();

        String temporaryPassword = createTemporaryDesigner();
        createCategory();
        createExternalSqlDataSource();
        loginToken = login(temporaryPassword);
        temporaryPassword = null;

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + loginToken);
        LoginUser loginUser = tokenService.getLoginUser(request);
        assertThat(loginUser).isNotNull();
        loginTokenId = loginUser.getToken();
        assertThat(loginTokenId).isNotBlank();
        assertThat(redisCache.hasKey(CacheConstants.LOGIN_TOKEN_KEY + loginTokenId)).isTrue();
    }

    /**
     * 清理本轮模型、潜在部署、扩展快照、目录、分类、临时用户和 Redis Token。
     * @return void，任一带本轮前缀的持久化事实残留时测试失败
     * @throws Exception HTTP 注销或 JSON 处理失败时抛出
     */
    @AfterAll
    void cleanupFixture() throws Exception
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();

        if (loginToken != null)
        {
            requireCode(jsonRequest("POST", "/logout", loginToken, null), 200);
        }
        if (loginTokenId != null)
        {
            assertThat(redisCache.hasKey(CacheConstants.LOGIN_TOKEN_KEY + loginTokenId)).isFalse();
        }

        // 失败发布理论上不会生成部署；仍按本轮流程 key 收集潜在部分事实，确保异常中断可恢复。
        Set<String> deploymentIds = new LinkedHashSet<>(jdbc.queryForList(
                "select distinct DEPLOYMENT_ID_ from ACT_RE_PROCDEF where KEY_ like ?",
                String.class, fixturePrefix + "%"));
        deploymentIds.addAll(jdbc.queryForList(
                "select ID_ from ACT_RE_DEPLOYMENT where KEY_ like ?",
                String.class, fixturePrefix + "%"));
        deploymentIds.addAll(findArtifactParentDeploymentIds(fixturePrefix));
        for (String deploymentId : deploymentIds)
        {
            artifactRepository.delete(deploymentId);
            if (repositoryService.createDeploymentQuery().deploymentId(deploymentId).count() == 1)
            {
                repositoryService.deleteDeployment(deploymentId, true);
            }
        }

        for (String modelId : modelIds)
        {
            if (repositoryService.createModelQuery().modelId(modelId).count() == 1)
            {
                repositoryService.deleteModel(modelId);
            }
        }
        if (dataSourceId != null)
        {
            jdbc.update("delete from wf_sql_datasource where datasource_id=?", dataSourceId);
        }
        if (categoryCode != null)
        {
            jdbc.update("delete from wf_category where code=?", categoryCode);
        }
        if (temporaryUserId != null)
        {
            // 登录和操作日志没有用户外键，但仍按临时用户名精确回收本轮测试事实。
            jdbc.update("delete from sys_logininfor where user_name=?", temporaryUsername);
            jdbc.update("delete from sys_oper_log where oper_name=?", temporaryUsername);
            jdbc.update("delete from sys_user_role where user_id=?", temporaryUserId);
            jdbc.update("delete from sys_user where user_id=?", temporaryUserId);
        }
        if (originalCaptchaEnabled != null)
        {
            jdbc.update("update sys_config set config_value=? where config_key=?",
                    originalCaptchaEnabled, "sys.account.captchaEnabled");
            sysConfigService.resetConfigCache();
        }

        assertThat(jdbc.queryForObject("select count(*) from ACT_RE_MODEL where KEY_ like ?",
                Integer.class, fixturePrefix + "%")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ACT_RE_PROCDEF where KEY_ like ?",
                Integer.class, fixturePrefix + "%")).isZero();
        assertThat(countExtensionSnapshots(fixturePrefix)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from wf_sql_datasource where datasource_key=?",
                Integer.class, dataSourceKey)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from wf_category where code=?",
                Integer.class, categoryCode)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sys_user where user_name=?",
                Integer.class, temporaryUsername)).isZero();
    }

    /**
     * 验证任意 Class、任意 Bean 表达式和外库非幂等写都不能通过真实模型发布入口。
     * @return void，任一非法模型生成 Deployment、流程定义或扩展快照时测试失败
     * @throws Exception HTTP、Flowable 仓储或 JSON 操作失败时抛出
     */
    @Test
    void rejectsUnregisteredImplementationsAndNonIdempotentExternalSqlThroughPublicationHttp()
            throws Exception
    {
        assertDeploymentRejected("class", "任意 Java Class",
                unregisteredClassBpmn(processKey("class")), "受控扩展注册表");
        assertDeploymentRejected("expression", "任意 Bean 表达式",
                unregisteredExpressionBpmn(processKey("expression")), "受控扩展注册表");
        assertDeploymentRejected("sql", "外库非幂等 SQL",
                nonIdempotentExternalSqlBpmn(processKey("sql")), "幂等 INSERT");
    }

    /**
     * 通过真实 create/save HTTP 建立模型，再注入持久化篡改并调用真实 deploy HTTP 验证发布门禁。
     * @param suffix String，本场景流程 key 后缀
     * @param modelName String，模型显示名称
     * @param unsafeBpmn String，模拟保存后被篡改的非法作者 BPMN
     * @param expectedMessage String，发布拒绝消息片段
     * @return void，发布必须返回 400 且数据库保持零部署副作用
     * @throws Exception HTTP 或 Flowable 仓储操作失败时抛出
     */
    private void assertDeploymentRejected(String suffix, String modelName,
            String unsafeBpmn, String expectedMessage) throws Exception
    {
        String processKey = processKey(suffix);
        processKeys.add(processKey);
        JsonNode created = requireCode(jsonRequest("POST", "/workflow/model", loginToken,
                objectMapper.createObjectNode()
                        .put("modelName", modelName + "-" + runId)
                        .put("modelKey", processKey)
                        .put("category", categoryCode)
                        .put("description", "连接器发布执行边界真实 HTTP 验收")
                        .put("formType", 2)
                        .toString()), 200);
        String modelId = created.path("data").path("modelId").asText();
        assertThat(modelId).isNotBlank();
        modelIds.add(modelId);

        JsonNode detail = requireCode(jsonRequest("GET", "/workflow/model/" + encode(modelId),
                loginToken, null), 200);
        int expectedRevision = detail.path("data").path("revision").asInt();
        assertThat(expectedRevision).isPositive();
        JsonNode saved = requireCode(jsonRequest("POST", "/workflow/model/save", loginToken,
                objectMapper.createObjectNode()
                        .put("modelId", modelId)
                        .put("bpmnXml", safeBpmn(processKey))
                        .put("expectedRevision", expectedRevision)
                        .toString()), 200);
        assertThat(saved.path("data").path("modelId").asText()).isEqualTo(modelId);

        // 模拟数据库或内部调用绕过保存门禁后的持久化篡改，发布边界必须独立重新校验源码。
        RepositoryService repositoryService = processEngine.getRepositoryService();
        repositoryService.addModelEditorSource(modelId,
                unsafeBpmn.getBytes(StandardCharsets.UTF_8));
        JsonNode rejected = requireCode(jsonRequest("POST",
                "/workflow/model/deploy?modelId=" + encode(modelId), loginToken, null), 400);
        assertThat(rejected.path("msg").asText()).contains(expectedMessage);

        Model model = repositoryService.getModel(modelId);
        assertThat(model).isNotNull();
        assertThat(model.getDeploymentId()).isNull();
        assertThat(repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(processKey).count()).isZero();
        assertThat(countExtensionSnapshots(processKey)).isZero();
    }

    /**
     * 创建启用的临时流程设计者并绑定现有 workflow_designer 角色。
     * @return String，仅用于本轮真实登录的随机明文口令
     */
    private String createTemporaryDesigner()
    {
        Long roleId = jdbc.queryForObject("select role_id from sys_role "
                + "where role_key='workflow_designer' and status='0' and del_flag='0'",
                Long.class);
        assertThat(roleId).isNotNull().isPositive();
        // 若依登录前置校验要求明文口令不超过 20 字符，散列必须使用运行时同一 BCrypt 实现。
        String password = "WfP!" + runId;
        String passwordHash = passwordEncoder.encode(password);
        assertThat(jdbc.update("insert into sys_user "
                + "(user_name,nick_name,password,status,del_flag,create_by,create_time) "
                + "values (?,?,?,'0','0','workflow_connector_publication_it',current_timestamp(3))",
                temporaryUsername, "连接器发布临时设计者", passwordHash)).isOne();
        temporaryUserId = jdbc.queryForObject("select user_id from sys_user where user_name=?",
                Long.class, temporaryUsername);
        assertThat(temporaryUserId).isNotNull().isPositive();
        assertThat(jdbc.update("insert into sys_user_role(user_id,role_id) values (?,?)",
                temporaryUserId, roleId)).isOne();
        return password;
    }

    /**
     * 从 Flowable 官方业务资源子部署中查找包含指定流程 key 前缀的父部署。
     * @param processKeyPrefix String，待匹配的完整流程 key 或本轮流程 key 前缀
     * @return Set&lt;String&gt;，需要清理或核验的父流程部署主键集合
     */
    private Set<String> findArtifactParentDeploymentIds(String processKeyPrefix)
    {
        Set<String> deploymentIds = new LinkedHashSet<>();
        List<Deployment> artifactDeployments = processEngine.getRepositoryService()
                .createDeploymentQuery()
                .deploymentCategory("APPROVAPLAT_WORKFLOW_ARTIFACTS")
                .list();
        for (Deployment artifactDeployment : artifactDeployments)
        {
            String parentDeploymentId = artifactDeployment.getParentDeploymentId();
            if (parentDeploymentId != null && artifactRepository
                    .selectExtensionSnapshots(parentDeploymentId).stream()
                    .anyMatch(snapshot -> snapshot.getProcessKey() != null
                            && snapshot.getProcessKey().startsWith(processKeyPrefix)))
            {
                deploymentIds.add(parentDeploymentId);
            }
        }
        return deploymentIds;
    }

    /**
     * 统计 Flowable 官方业务资源中匹配指定流程 key 的扩展快照数量。
     * @param processKeyPrefix String，待匹配的完整流程 key 或本轮流程 key 前缀
     * @return long，匹配扩展快照数量
     */
    private long countExtensionSnapshots(String processKeyPrefix)
    {
        long snapshotCount = 0;
        for (String deploymentId : findArtifactParentDeploymentIds(processKeyPrefix))
        {
            snapshotCount += artifactRepository.selectExtensionSnapshots(deploymentId).stream()
                    .filter(snapshot -> snapshot.getProcessKey() != null
                            && snapshot.getProcessKey().startsWith(processKeyPrefix))
                    .count();
        }
        return snapshotCount;
    }

    /**
     * 创建模型 API 所需的正式分类夹具。
     * @return void，分类写入失败时测试立即失败
     */
    private void createCategory()
    {
        assertThat(jdbc.update("insert into wf_category "
                + "(category_name,code,create_by,del_flag) values (?,?,?,'0')",
                "连接器发布真实验收", categoryCode, String.valueOf(temporaryUserId))).isOne();
    }

    /**
     * 创建仅用于部署期幂等校验的外库 SQL 数据源目录，不解析或连接凭据正文。
     * @return void，目录摘要或持久化失败时测试立即失败
     */
    private void createExternalSqlDataSource()
    {
        WfSqlDataSource source = new WfSqlDataSource();
        source.setDataSourceKey(dataSourceKey);
        source.setDataSourceName("连接器发布外库门禁");
        source.setConnectionType("EXTERNAL");
        source.setJdbcUrlRef("WORKFLOW_SQL_JDBC_URL_PUBLICATION_IT");
        source.setUsernameRef("WORKFLOW_SQL_USERNAME_PUBLICATION_IT");
        source.setPasswordRef("WORKFLOW_SQL_PASSWORD_PUBLICATION_IT");
        source.setAllowedTables("wf_connector_publication_target");
        source.setConnectTimeoutMs(1000);
        source.setQueryTimeoutSeconds(10);
        source.setRevisionNo(1);
        String checksum = WorkflowSqlDataSourceService.dataSourceChecksum(source);
        assertThat(jdbc.update("insert into wf_sql_datasource "
                + "(datasource_key,datasource_name,connection_type,jdbc_url_ref,username_ref,"
                + "password_ref,allowed_tables,connect_timeout_ms,query_timeout_seconds,"
                + "revision_no,status,checksum,create_by,create_time,update_by,update_time) "
                + "values (?,?,?,?,?,?,?,?,?,1,'ENABLED',?,?,current_timestamp(3),'',null)",
                dataSourceKey, source.getDataSourceName(), source.getConnectionType(),
                source.getJdbcUrlRef(), source.getUsernameRef(), source.getPasswordRef(),
                source.getAllowedTables(), source.getConnectTimeoutMs(),
                source.getQueryTimeoutSeconds(), checksum, String.valueOf(temporaryUserId))).isOne();
        dataSourceId = jdbc.queryForObject(
                "select datasource_id from wf_sql_datasource where datasource_key=?",
                Long.class, dataSourceKey);
        assertThat(dataSourceId).isNotNull().isPositive();
    }

    /**
     * 通过真实 /login 和数据库 BCrypt 密码建立 JWT/Redis 登录态。
     * @param password String，本轮临时用户随机口令
     * @return String，服务端签发的 JWT
     * @throws Exception HTTP 或 JSON 处理失败时抛出
     */
    private String login(String password) throws Exception
    {
        JsonNode response = requireCode(jsonRequest("POST", "/login", null,
                objectMapper.createObjectNode()
                        .put("username", temporaryUsername)
                        .put("password", password)
                        .put("code", "")
                        .put("uuid", "")
                        .toString()), 200);
        String token = response.path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    /**
     * 生成保存阶段合法的最小作者 BPMN，发布测试随后替换为受检篡改版本。
     * @param processKey String，本场景唯一流程 key
     * @return String，包含开始表单的可保存 BPMN
     */
    private String safeBpmn(String processKey)
    {
        return bpmn(processKey, "", "end");
    }

    /**
     * 生成引用未登记 Java Class 的作者 BPMN。
     * @param processKey String，本场景唯一流程 key
     * @return String，发布阶段必须拒绝的 BPMN
     */
    private String unregisteredClassBpmn(String processKey)
    {
        return bpmn(processKey,
                "<serviceTask id=\"unsafeTask\" name=\"任意类\" "
                        + "flowable:class=\"com.example.UnreviewedDelegate\"/>\n"
                        + "    <sequenceFlow id=\"toEnd\" sourceRef=\"unsafeTask\" targetRef=\"end\"/>",
                "unsafeTask");
    }

    /**
     * 生成引用未登记 Spring Bean 表达式的作者 BPMN。
     * @param processKey String，本场景唯一流程 key
     * @return String，发布阶段必须拒绝的 BPMN
     */
    private String unregisteredExpressionBpmn(String processKey)
    {
        return bpmn(processKey,
                "<serviceTask id=\"unsafeTask\" name=\"任意 Bean\" "
                        + "flowable:delegateExpression=\"${unreviewedBean}\"/>\n"
                        + "    <sequenceFlow id=\"toEnd\" sourceRef=\"unsafeTask\" targetRef=\"end\"/>",
                "unsafeTask");
    }

    /**
     * 生成已登记 SQL Delegate 下的外库普通 UPDATE，部署期必须按幂等契约拒绝。
     * @param processKey String，本场景唯一流程 key
     * @return String，包含非幂等外库写配置的作者 BPMN
     */
    private String nonIdempotentExternalSqlBpmn(String processKey)
    {
        String config = "{\"dataSourceKey\":\"" + dataSourceKey + "\","
                + "\"sql\":\"update wf_connector_publication_target set result_value = "
                + ":resultValue where business_key = :businessKey\","
                + "\"parameters\":{\"resultValue\":\"resultValue\","
                + "\"businessKey\":\"businessKey\"},"
                + "\"idempotencyColumn\":\"business_key\",\"maxRows\":1}";
        String task = "<serviceTask id=\"unsafeTask\" name=\"外库非幂等写\" "
                + "flowable:async=\"true\" "
                + "flowable:delegateExpression=\"${workflowExtensionDelegate}\">"
                + "<extensionElements>"
                + "<flowable:field name=\"approvaExtensionKey\" "
                + "stringValue=\"approva.sql-connector\"/>"
                + "<flowable:field name=\"approvaExtensionConfig\"><flowable:string><![CDATA["
                + config + "]]></flowable:string></flowable:field>"
                + "</extensionElements></serviceTask>\n"
                + "    <sequenceFlow id=\"toEnd\" sourceRef=\"unsafeTask\" targetRef=\"end\"/>";
        return bpmn(processKey, task, "unsafeTask");
    }

    /**
     * 包装带开始表单的最小 BPMN，并按场景插入可空服务任务。
     * @param processKey String，唯一流程 key
     * @param taskXml String，可空服务任务和到结束节点的连线 XML
     * @param firstTarget String，开始节点首个目标元素 ID
     * @return String，完整 UTF-8 BPMN 2.0 XML
     */
    private String bpmn(String processKey, String taskXml, String firstTarget)
    {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="https://approvaplat.example/connector-publication-it">
                  <process id="%s" name="连接器发布执行边界" isExecutable="true">
                    <startEvent id="start" name="提交">
                      <extensionElements>
                        <flowable:formProperty id="requestReason" name="申请原因" type="string"
                                               readable="true" writable="true" required="true"/>
                      </extensionElements>
                    </startEvent>
                    <sequenceFlow id="fromStart" sourceRef="start" targetRef="%s"/>
                    %s
                    <endEvent id="end" name="结束"/>
                  </process>
                </definitions>
                """.formatted(processKey, firstTarget, taskXml);
    }

    /**
     * 返回当前场景稳定流程 key。
     * @param suffix String，class、expression 或 sql
     * @return String，带本轮前缀的流程 key
     */
    private String processKey(String suffix)
    {
        return fixturePrefix + "-" + suffix;
    }

    /**
     * 发送真实 HTTP 请求并解析若依统一 JSON 响应。
     * @param method String，GET 或 POST
     * @param path String，相对路径，可包含查询参数
     * @param token String，可空 JWT
     * @param body String，可空 JSON 正文
     * @return JsonNode，服务端统一响应
     * @throws IOException 网络或响应读取失败时抛出
     * @throws InterruptedException 当前线程被中断时抛出
     */
    private JsonNode jsonRequest(String method, String path, String token, String body)
            throws IOException, InterruptedException
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri(path))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "workflow-connector-publication-http-it");
        if (token != null)
        {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null)
        {
            builder.header("Content-Type", "application/json; charset=UTF-8");
        }
        HttpRequest request = "GET".equals(method)
                ? builder.GET().build()
                : builder.POST(body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    /**
     * 断言若依统一业务码并返回原响应。
     * @param response JsonNode，服务端统一响应
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

    /**
     * 生成当前随机端口的真实 HTTP URI。
     * @param path String，相对路径
     * @return URI，本机随机端口地址
     */
    private URI baseUri(String path)
    {
        return URI.create("http://127.0.0.1:" + serverPort + path);
    }

    /**
     * 对路径或查询参数执行 UTF-8 URL 编码。
     * @param value String，待编码值
     * @return String，URL 编码结果
     */
    private String encode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

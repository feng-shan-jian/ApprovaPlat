package com.ruoyi.web.controller.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.Model;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.DelegationState;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentStorage;
import com.ruoyi.flowable.service.process.WorkflowCollaborationChannelService;
import com.ruoyi.web.controller.workflow.WorkflowRbacMatrix.Endpoint;

/**
 * 为五角色 RBAC ALLOW 单元创建真实 Flowable/MySQL/附件对象并执行真实 HTTP。
 * fixture 只使用预登记账号，不创建、重置或修改任何账号凭据。
 */
final class WorkflowRbacAllowFixture
{
    /** 本测试全部正式数据使用的稳定前缀，清理和残留断言均以精确 ID 为主。 */
    private static final String PREFIX = "workflow-rbac-allow-it-";

    /** 真实 BPMN classpath 资源。 */
    private static final String BPMN_RESOURCE =
            "processes/flowable-rbac-allow-it.bpmn20.xml";

    /** 四个基础流程定义 key。 */
    private static final String START_KEY = "workflowRbacStartIntegration";
    private static final String SERIAL_KEY = "workflowRbacSerialIntegration";
    private static final String CLAIM_KEY = "workflowRbacClaimIntegration";
    private static final String MULTI_KEY = "workflowRbacMultiIntegration";

    /** 部署表单使用的最小安全 schema，note 字段供发起、完成和变量读取对账。 */
    private static final String FORM_CONTENT =
            "{\"fields\":[{\"__config__\":{\"layout\":\"colFormItem\","
                    + "\"tag\":\"el-input\"},\"__vModel__\":\"note\"}]}";

    /** 真实 HTTP 发起定义的最小表单 schema，任务办理人变量必须由部署快照显式授权。 */
    private static final String START_FORM_CONTENT =
            "{\"fields\":[{\"__config__\":{\"layout\":\"colFormItem\","
                    + "\"tag\":\"el-input\"},\"__vModel__\":\"note\"},"
                    + "{\"__config__\":{\"layout\":\"colFormItem\","
                    + "\"tag\":\"el-input\",\"required\":true},"
                    + "\"__vModel__\":\"reviewAssignee\",\"maxlength\":32}]}";

    /** 上传和下载逐字节核对的固定 ASCII 正文前缀。 */
    private static final byte[] ATTACHMENT_BYTES =
            "workflow-rbac-real-attachment".getBytes(StandardCharsets.US_ASCII);

    /** PNG 文件头。 */
    private static final byte[] PNG_SIGNATURE =
            new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

    /** 需要由 @Log 持久化成功操作日志的正式入口。 */
    private static final Set<String> AUDITED_ENDPOINTS = Set.of(
            "WfAttachmentController#upload", "WfAttachmentController#remove",
            "WfCategoryController#export", "WfCategoryController#add",
            "WfCategoryController#edit", "WfCategoryController#remove",
            "WfDeployController#changeState", "WfDeployController#remove",
            "WfFormController#export", "WfFormController#add",
            "WfFormController#edit", "WfFormController#remove",
            "WfInstanceController#updateState", "WfInstanceController#terminate",
            "WfModelController#add", "WfModelController#edit",
            "WfModelController#save", "WfModelController#latest",
            "WfModelController#remove", "WfModelController#deployModel",
            "WfModelController#export", "WfExtensionController#create",
            "WfExtensionController#createVersion", "WfExtensionController#changeStatus",
            "WfExtensionController#remove",
            "WfConnectorController#create", "WfConnectorController#update",
            "WfConnectorController#status",
            "WfDmnController#deploy", "WfDmnController#delete",
            "WfSqlDataSourceController#create", "WfSqlDataSourceController#update",
            "WfSqlDataSourceController#status",
            "WfIntegrationCredentialController#create",
            "WfIntegrationCredentialController#rotate",
            "WfIntegrationCredentialController#revoke",
            "WfProcessController#startExport",
            "WfProcessController#ownExport", "WfProcessController#managedExport",
            "WfProcessController#todoExport", "WfProcessController#claimExport",
            "WfProcessController#finishedExport", "WfProcessController#copyExport",
            "WfProcessController#start", "WfProcessController#deleteHistory",
            "WfProcessDraftController#create", "WfProcessDraftController#save",
            "WfProcessDraftController#delete", "WfProcessDraftController#submit",
            "WfBpmnEventController#createCode", "WfBpmnEventController#updateCode",
            "WfBpmnEventController#changeCodeStatus",
            "WfTaskSlaController#createCalendar", "WfTaskSlaController#updateCalendar",
            "WfTaskSlaController#changeCalendarStatus",
            "WfTaskSlaController#markNotificationRead",
            "WfNotificationController#urge", "WfNotificationController#savePolicy",
            "WfNotificationController#compensate",
            "WfCollaborationController#retryInbound",
            "WfCollaborationController#retryOutbox",
            "WfCollaborationController#cancelOutbox",
            "WfTaskController#stopProcess", "WfTaskController#revokeProcess",
            "WfTaskController#processVariables",
            "WfTaskController#getMultiInstanceState",
            "WfTaskController#adjustMultiInstance", "WfTaskController#complete",
            "WfTaskController#reject", "WfTaskController#returnTask",
            "WfTaskController#resubmit", "WfTaskController#claim",
            "WfTaskController#unClaim", "WfTaskController#resolve",
            "WfTaskController#delegate", "WfTaskController#transfer",
            "WfTaskController#diagram");

    private final int serverPort;
    private final Duration httpTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ProcessEngine processEngine;
    private final WorkflowAttachmentStorage attachmentStorage;
    private final Map<String, String> roleTokens;
    private final Map<String, Long> roleUserIds;
    private final Map<String, String> roleUsernames;
    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final IdentityService identityService;

    /** 每个 fixture 名称和业务主键的单调序号，避免 RepeatSubmit 与唯一键碰撞。 */
    private final AtomicInteger sequence = new AtomicInteger();

    /** 精确清理集合；只登记本类已确认创建成功的对象主键。 */
    private final Set<String> deploymentIds = new LinkedHashSet<>();
    /** 本 fixture 创建的全部流程实例，历史被业务入口删除后仍保留精确通知清理边界。 */
    private final Set<String> processInstanceIds = new LinkedHashSet<>();
    /** 按本 fixture 流程实例反查得到的通知 outbox 主键，只用于严格 FK 顺序清理。 */
    private final Set<Long> notificationOutboxIds = new LinkedHashSet<>();
    /** 申请草稿及其不可变审计按真实 UUID 精确回收。 */
    private final Set<String> processDraftIds = new LinkedHashSet<>();
    /** BPMN 事件目录与运行审计只登记本 fixture 生成的主键。 */
    private final Set<Long> bpmnEventCodeIds = new LinkedHashSet<>();
    private final Set<Long> bpmnEventAuditIds = new LinkedHashSet<>();
    /** SLA 日历与运行执行按外键父记录主键精确清理。 */
    private final Set<Long> businessCalendarIds = new LinkedHashSet<>();
    private final Set<Long> taskSlaExecutionIds = new LinkedHashSet<>();
    /** 新增通知策略使用唯一 PROCESS 作用域，避免触碰正式默认策略。 */
    private final Set<Long> notificationPolicyIds = new LinkedHashSet<>();
    private final Set<String> modelIds = new LinkedHashSet<>();
    /** 本轮真实保存 API 使用的幂等请求主键，仅用于精确回收测试创建的正式记录。 */
    private final Set<String> modelSaveRequestIds = new LinkedHashSet<>();
    private final Set<Long> categoryIds = new LinkedHashSet<>();
    private final Set<Long> formIds = new LinkedHashSet<>();
    private final Set<Long> copyIds = new LinkedHashSet<>();
    private final Set<String> attachmentIds = new LinkedHashSet<>();
    private final Set<String> attachmentStorageKeys = new LinkedHashSet<>();
    private final Set<Long> operationLogIds = new LinkedHashSet<>();
    private final Set<Long> initialQuotaGuardOwners = new LinkedHashSet<>();

    /** 主部署的四个定义，所有流程业务 fixture 只从该部署启动。 */
    private final Map<String, ProcessDefinition> definitions = new LinkedHashMap<>();

    private String runId;
    private String mainDeploymentId;
    private String commonCategoryCode;
    private long commonFormId;
    private long operationLogFloor;
    private boolean prepared;
    private boolean cleaned;

    /**
     * 创建 ALLOW fixture 执行器。
     *
     * @param serverPort int，SpringBootTest 随机真实端口
     * @param httpTimeout Duration，单次真实 HTTP 超时
     * @param httpClient HttpClient，不带 Cookie 和重试的客户端
     * @param objectMapper ObjectMapper，测试侧 Jackson 3 解析器
     * @param jdbcTemplate JdbcTemplate，隔离 MySQL 连接
     * @param processEngine ProcessEngine，共享真实 Flowable 引擎
     * @param attachmentStorage WorkflowAttachmentStorage，测试专用私有附件目录
     * @param roleTokens Map，五角色真实 JWT
     * @param roleUserIds Map，五角色正式用户主键
     * @param roleUsernames Map，五角色正式账号名
     * @return 无返回值，构造后需调用 prepare
     */
    WorkflowRbacAllowFixture(int serverPort, Duration httpTimeout,
            HttpClient httpClient, ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate, ProcessEngine processEngine,
            WorkflowAttachmentStorage attachmentStorage,
            Map<String, String> roleTokens, Map<String, Long> roleUserIds,
            Map<String, String> roleUsernames)
    {
        this.serverPort = serverPort;
        this.httpTimeout = httpTimeout;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.processEngine = processEngine;
        this.attachmentStorage = attachmentStorage;
        this.roleTokens = Map.copyOf(roleTokens);
        this.roleUserIds = Map.copyOf(roleUserIds);
        this.roleUsernames = Map.copyOf(roleUsernames);
        this.repositoryService = processEngine.getRepositoryService();
        this.runtimeService = processEngine.getRuntimeService();
        this.taskService = processEngine.getTaskService();
        this.historyService = processEngine.getHistoryService();
        this.identityService = processEngine.getIdentityService();
    }

    /**
     * 建立唯一分类、表单、主 BPMN 部署及全部节点部署快照。
     *
     * @return void，无返回值；任何残留或正式持久化失败立即终止
     */
    void prepare()
    {
        assertThat(prepared).isFalse();
        runId = UUID.randomUUID().toString().replace("-", "");
        operationLogFloor = maxOperationLogId();
        initialQuotaGuardOwners.addAll(jdbcTemplate.queryForList(
                "select owner_user_id from wf_attachment_quota_guard",
                Long.class));
        assertThat(repositoryService.createDeploymentQuery()
                .deploymentNameLike(PREFIX + "%").count())
                .as("RBAC ALLOW IT 启动前不得存在同前缀残留部署")
                .isZero();

        CategoryRow category = insertCategory("基础分类");
        commonCategoryCode = category.code();
        commonFormId = insertForm("基础表单").id();

        Deployment deployment = repositoryService.createDeployment()
                .name(PREFIX + runId)
                .category(commonCategoryCode)
                .addClasspathResource(BPMN_RESOURCE)
                .deploy();
        mainDeploymentId = deployment.getId();
        deploymentIds.add(mainDeploymentId);
        for (ProcessDefinition definition : repositoryService
                .createProcessDefinitionQuery().deploymentId(mainDeploymentId).list())
        {
            definitions.put(definition.getKey(), definition);
        }
        assertThat(definitions).containsOnlyKeys(START_KEY, SERIAL_KEY, CLAIM_KEY, MULTI_KEY);

        insertSnapshot("key_92001", "start", "发起申请", START_FORM_CONTENT);
        insertSnapshot("key_92002", "startReview", "申请复核", FORM_CONTENT);
        insertSnapshot("key_92011", "serialStart", "发起申请", FORM_CONTENT);
        insertSnapshot("key_92012", "firstReview", "初审", FORM_CONTENT);
        insertSnapshot("key_92013", "secondReview", "复审", FORM_CONTENT);
        insertSnapshot("key_92020", "claimStart", "开始", FORM_CONTENT);
        insertSnapshot("key_92021", "claimReview", "候选审批", FORM_CONTENT);
        insertSnapshot("key_92030", "multiStart", "开始", FORM_CONTENT);
        insertSnapshot("key_92031", "multiSource", "选择会签人", FORM_CONTENT);
        insertSnapshot("key_92032", "multiReview", "动态会签", FORM_CONTENT);
        prepared = true;
    }

    /**
     * 执行一个 ALLOW 单元并返回不含账号、Token、对象 ID 或响应正文的结果。
     *
     * @param roleKey String，五角色之一
     * @param endpoint Endpoint，当前正式入口
     * @return Execution，真实传输/业务状态及稳定结果分类
     */
    Execution execute(String roleKey, Endpoint endpoint)
    {
        try
        {
            assertThat(prepared).isTrue();
            assertThat(cleaned).isFalse();
            return switch (endpoint.controller())
            {
                case "WfAttachmentController" -> executeAttachment(roleKey, endpoint);
                case "WfCategoryController" -> executeCategory(roleKey, endpoint);
                case "WfDeployController" -> executeDeploy(roleKey, endpoint);
                case "WfDesignerController" -> executeDesigner(roleKey, endpoint);
                case "WfConnectorController" -> executeConnector(roleKey, endpoint);
                case "WfDmnController" -> executeDmn(roleKey, endpoint);
                case "WfExtensionController" -> executeExtension(roleKey, endpoint);
                case "WfSqlDataSourceController" -> executeSqlDataSource(roleKey, endpoint);
                case "WfIntegrationCredentialController" -> executeIntegrationCredential(roleKey, endpoint);
                case "WfRuntimeEventAuditController" -> executeRuntimeEventAudit(roleKey, endpoint);
                case "WfBpmnEventController" -> executeBpmnEvent(roleKey, endpoint);
                case "WfCallActivityController" -> executeCallActivity(roleKey, endpoint);
                case "WfTaskSlaController" -> executeTaskSla(roleKey, endpoint);
                case "WfCollaborationController" -> executeCollaboration(roleKey, endpoint);
                case "WfNotificationController" -> executeNotification(roleKey, endpoint);
                case "WfFormController" -> executeForm(roleKey, endpoint);
                case "WfIdentityController" -> executeIdentity(roleKey, endpoint);
                case "WfInstanceController" -> executeInstance(roleKey, endpoint);
                case "WfModelController" -> executeModel(roleKey, endpoint);
                case "WfProcessController" -> executeProcess(roleKey, endpoint);
                case "WfProcessDraftController" -> executeProcessDraft(roleKey, endpoint);
                case "WfTaskController" -> executeTask(roleKey, endpoint);
                default -> throw new AssertionError("未知 ALLOW Controller");
            };
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            return Execution.failed("ALLOW_HTTP_INTERRUPTED");
        }
        catch (IOException exception)
        {
            return Execution.failed("ALLOW_HTTP_IO_FAILURE");
        }
        catch (AllowHttpResponseException exception)
        {
            // 仅保留状态码和稳定分类，禁止把响应正文、账号或对象主键写入报告。
            return Execution.failedHttp(exception.transportStatus(),
                    exception.bodyCode(), exception.reason());
        }
        catch (AllowFixtureAssertionException exception)
        {
            return Execution.failed(exception.reason());
        }
        catch (AssertionError exception)
        {
            return Execution.failed("ALLOW_FIXTURE_ASSERTION_FAILED");
        }
        catch (RuntimeException exception)
        {
            return Execution.failed("ALLOW_FIXTURE_RUNTIME_FAILURE");
        }
    }

    /**
     * 执行集成账号脱敏列表、一次性 Token 创建、轮换和吊销真实链路。
     * @param roleKey String，当前管理员角色
     * @param endpoint Endpoint，集成账号管理入口
     * @return Execution，真实 HTTP、数据库状态和 Token 保密断言结果
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeIntegrationCredential(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        String name = PREFIX + "credential-" + sequence.incrementAndGet();
        Long credentialId = null;
        try
        {
            return switch (endpoint.handler())
            {
                case "list" ->
                {
                    credentialId = insertIntegrationCredentialFixture(name);
                    JsonNode body = callJson(roleKey, endpoint,
                            "/workflow/integration-credential/list", null);
                    requireFixture(arrayContains(body.path("data"), "credentialId",
                            String.valueOf(credentialId)), "INTEGRATION_LIST_ROW_MISSING");
                    yield Execution.passedJson();
                }
                case "create" ->
                {
                    JsonNode body = callJson(roleKey, endpoint,
                            "/workflow/integration-credential",
                            json(Map.of("credentialName", name,
                                    "scopes", List.of("MESSAGE", "RECEIVE"),
                                    "allowedVariables", List.of("amount", "approved"),
                                    "rateLimitPerMinute", 60)));
                    credentialId = body.path("data").path("credentialId").asLong();
                    String token = body.path("data").path("token").asText();
                    requireFixture(credentialId > 0 && token.length() >= 32,
                            "INTEGRATION_CREATE_SECRET_MISSING");
                    Map<String, Object> stored = jdbcTemplate.queryForMap(
                            "select token_prefix, token_hash from wf_integration_credential "
                                    + "where credential_id = ?", credentialId);
                    requireFixture(token.startsWith(String.valueOf(stored.get("token_prefix")))
                                    && !token.equals(String.valueOf(stored.get("token_hash"))),
                            "INTEGRATION_CREATE_PLAINTEXT_PERSISTED");
                    yield Execution.passedJson();
                }
                case "rotate" ->
                {
                    credentialId = insertIntegrationCredentialFixture(name);
                    JsonNode body = callJson(roleKey, endpoint,
                            "/workflow/integration-credential/" + credentialId + "/rotate", "{}");
                    String token = body.path("data").path("token").asText();
                    Map<String, Object> stored = jdbcTemplate.queryForMap(
                            "select revision_no, token_prefix, token_hash "
                                    + "from wf_integration_credential where credential_id = ?",
                            credentialId);
                    requireFixture(((Number) stored.get("revision_no")).intValue() == 2
                                    && token.length() >= 32
                                    && token.startsWith(String.valueOf(stored.get("token_prefix")))
                                    && !"a".repeat(64).equals(stored.get("token_hash")),
                            "INTEGRATION_ROTATE_STATE_MISMATCH");
                    yield Execution.passedJson();
                }
                case "revoke" ->
                {
                    credentialId = insertIntegrationCredentialFixture(name);
                    callJson(roleKey, endpoint,
                            "/workflow/integration-credential/" + credentialId, null);
                    Integer revoked = jdbcTemplate.queryForObject(
                            "select count(*) from wf_integration_credential "
                                    + "where credential_id = ? and revoked_at is not null",
                            Integer.class, credentialId);
                    requireFixture(Integer.valueOf(1).equals(revoked),
                            "INTEGRATION_REVOKE_STATE_MISSING");
                    yield Execution.passedJson();
                }
                default -> throw new AssertionError("未知集成账号 ALLOW 入口");
            };
        }
        finally
        {
            cleanupIntegrationCredentialFixture(credentialId, name);
        }
    }

    /**
     * 执行运行事件审计只读入口并核对失败台账已脱敏返回。
     * @param roleKey String，管理员或审计角色
     * @param endpoint Endpoint，运行事件审计列表入口
     * @return Execution，真实 HTTP 和正式数据库行对账结果
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeRuntimeEventAudit(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        String name = PREFIX + "runtime-audit-" + sequence.incrementAndGet();
        Long credentialId = insertIntegrationCredentialFixture(name);
        String requestId = UUID.randomUUID().toString();
        try
        {
            jdbcTemplate.update("insert into wf_runtime_event_request "
                            + "(request_id, credential_id, event_type, event_name, "
                            + "correlation_type, correlation_value, variables_sha256, status, "
                            + "result_code, result_summary, create_time, complete_time) "
                            + "values (?, ?, 'MESSAGE', 'rbac-audit-message', 'BUSINESS_KEY', "
                            + "?, ?, 'FAILED', 'RBAC_AUDIT_FIXTURE', '测试审计摘要', now(3), now(3))",
                    requestId, credentialId, PREFIX + runId, "b".repeat(64));
            JsonNode body = callJson(roleKey, endpoint,
                    "/workflow/runtime-event-audit/list", null);
            requireFixture(arrayContains(body.path("data"), "requestId", requestId),
                    "RUNTIME_EVENT_AUDIT_ROW_MISSING");
            String serialized = body.path("data").toString();
            requireFixture(!serialized.contains("token_hash")
                            && !serialized.contains("variables_sha256"),
                    "RUNTIME_EVENT_AUDIT_SENSITIVE_FIELD_EXPOSED");
            return Execution.passedJson();
        }
        finally
        {
            jdbcTemplate.update("delete from wf_runtime_event_request where request_id = ?",
                    requestId);
            cleanupIntegrationCredentialFixture(credentialId, name);
        }
    }

    /**
     * 直接插入只含 Token 摘要的有效凭据，供管理和审计 HTTP fixture 使用。
     * @param name String，本轮唯一账号名称
     * @return Long，正式凭据主键
     */
    private Long insertIntegrationCredentialFixture(String name)
    {
        String prefix = "rb" + String.format("%010d", sequence.incrementAndGet());
        jdbcTemplate.update("insert into wf_integration_credential "
                        + "(credential_name, token_prefix, token_hash, scopes, allowed_variables, "
                        + "rate_limit_per_minute, rate_window_start, rate_window_count, "
                        + "revision_no, create_by, create_time) values (?, ?, ?, "
                        + "'MESSAGE,RECEIVE,SIGNAL', 'amount,approved', 100, now(3), 0, 1, ?, now(3))",
                name, prefix, "a".repeat(64),
                String.valueOf(roleUserIds.get("workflow_admin")));
        Long credentialId = jdbcTemplate.queryForObject(
                "select credential_id from wf_integration_credential where token_prefix = ?",
                Long.class, prefix);
        assertThat(credentialId).isPositive();
        return credentialId;
    }

    /**
     * 按主键和唯一名称精确清理凭据及其运行事件台账。
     * @param credentialId Long，可空正式凭据主键
     * @param name String，本轮唯一账号名称
     * @return void，清理后同名正式行必须为零
     */
    private void cleanupIntegrationCredentialFixture(Long credentialId, String name)
    {
        if (credentialId != null && credentialId > 0)
        {
            jdbcTemplate.update("delete from wf_runtime_event_request where credential_id = ?",
                    credentialId);
            jdbcTemplate.update("delete from wf_integration_credential "
                    + "where credential_id = ? and credential_name = ?", credentialId, name);
        }
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_integration_credential where credential_name = ?",
                Integer.class, name)).isZero();
    }

    /**
     * 执行申请草稿创建、本人列表、详情、乐观锁保存、软删除和正式提交链路。
     *
     * @param roleKey String，当前管理员或发起人角色
     * @param endpoint Endpoint，草稿正式入口
     * @return Execution，真实 HTTP、草稿审计和 Flowable 实例对账结果
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeProcessDraft(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        if ("create".equals(endpoint.handler()))
        {
            DraftFixture draft = createDraft(roleKey);
            assertDraftState(draft.id(), roleKey, "ACTIVE", 1L, null);
            return Execution.passedJson();
        }

        DraftFixture draft = createDraft(roleKey);
        return switch (endpoint.handler())
        {
            case "list" ->
            {
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/process/draft/list?processName="
                                + encode(definition(START_KEY).getName())
                                + "&pageNum=1&pageSize=20", null);
                assertRowsContain(body, "draftId", draft.id());
                yield Execution.passedJson();
            }
            case "get" ->
            {
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/process/draft/" + encode(draft.id()), null);
                requireFixture(draft.id().equals(body.path("data").path("draftId").asText())
                                && draft.businessKey().equals(
                                        body.path("data").path("businessKey").asText())
                                && "ACTIVE".equals(
                                        body.path("data").path("status").asText()),
                        "DRAFT_DETAIL_STATE_MISMATCH");
                yield Execution.passedJson();
            }
            case "save" ->
            {
                String updatedBusinessKey = uniqueBusinessKey("draft-saved");
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/process/draft/" + encode(draft.id()),
                        draftMutationJson(roleKey, 1L, updatedBusinessKey, "草稿保存后字段"));
                requireFixture(body.path("data").path("revisionNo").asLong() == 2L,
                        "DRAFT_SAVE_RESPONSE_REVISION_MISMATCH");
                assertDraftState(draft.id(), roleKey, "ACTIVE", 2L, null);
                requireFixture(updatedBusinessKey.equals(jdbcTemplate.queryForObject(
                                "select business_key from wf_process_draft where draft_id = ?",
                                String.class, draft.id())),
                        "DRAFT_SAVE_BUSINESS_KEY_MISMATCH");
                assertDraftAudit(draft.id(), "SAVED", 2L);
                yield Execution.passedJson();
            }
            case "delete" ->
            {
                callJson(roleKey, endpoint,
                        "/workflow/process/draft/" + encode(draft.id())
                                + "?expectedVersion=1", null);
                assertDraftState(draft.id(), roleKey, "DELETED", 2L, null);
                assertDraftAudit(draft.id(), "DELETED", 2L);
                yield Execution.passedJson();
            }
            case "submit" ->
            {
                String businessKey = uniqueBusinessKey("draft-submitted");
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/process/draft/" + encode(draft.id()) + "/submit",
                        draftMutationJson(roleKey, 1L, businessKey, "草稿正式提交"));
                String instanceId = body.path("data").path("processInstanceId").asText();
                trackProcessInstance(instanceId);
                assertDraftState(draft.id(), roleKey, "SUBMITTED", 2L, instanceId);
                assertDraftAudit(draft.id(), "SUBMITTED", 2L);
                requireFixture(runtimeService.createProcessInstanceQuery()
                                .processInstanceId(instanceId).count() == 1L
                                && taskService.createTaskQuery()
                                        .processInstanceId(instanceId).active().count() == 1L,
                        "DRAFT_SUBMIT_FLOWABLE_STATE_MISMATCH");
                yield Execution.passedJson();
            }
            default -> throw new AssertionError("未知申请草稿 ALLOW 入口");
        };
    }

    /**
     * 通过正式草稿 API 创建当前角色自己的活动草稿并登记精确 UUID。
     *
     * @param roleKey String，草稿所有者角色
     * @return DraftFixture，草稿 UUID、业务主键和初始 revision
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private DraftFixture createDraft(String roleKey)
            throws IOException, InterruptedException
    {
        String businessKey = uniqueBusinessKey("draft");
        String body = json(Map.of(
                "processDefinitionId", definition(START_KEY).getId(),
                "businessKey", businessKey,
                "variables", draftVariables(roleKey, "草稿初始字段"),
                "multiInstanceUserIds", Map.of()));
        JsonNode response = callJsonRaw(roleKey, "/workflow/process/draft", "POST", body, true);
        String draftId = response.path("data").path("draftId").asText();
        requireFixture(!draftId.isBlank()
                        && response.path("data").path("revisionNo").asLong() == 1L,
                "DRAFT_CREATE_RESPONSE_MISMATCH");
        processDraftIds.add(draftId);
        assertDraftAudit(draftId, "CREATED", 1L);
        return new DraftFixture(draftId, businessKey);
    }

    /**
     * 生成草稿保存或提交的完整字段 JSON，保持开始表单与正式发起校验一致。
     *
     * @param ownerRole String，草稿所有者角色，用于保持审批人字段与当前草稿一致
     * @param expectedVersion long，客户端最后读取的草稿版本
     * @param businessKey String，本次保存或提交采用的业务主键
     * @param note String，开始表单 note 字段
     * @return String，可直接发送的 UTF-8 JSON 正文
     */
    private String draftMutationJson(String ownerRole, long expectedVersion,
            String businessKey, String note)
    {
        return json(Map.of(
                "expectedVersion", expectedVersion,
                "businessKey", businessKey,
                "variables", draftVariables(ownerRole, note),
                "multiInstanceUserIds", Map.of()));
    }

    /**
     * 生成开始表单允许的草稿字段，审批人始终来自正式五角色账号。
     *
     * @param ownerRole String，草稿所有者角色；只用于选择可办理审批人
     * @param note String，业务备注字段
     * @return Map&lt;String,Object&gt;，通过部署表单白名单的字段映射
     */
    private Map<String, Object> draftVariables(String ownerRole, String note)
    {
        String assigneeRole = approvalAssigneeRole(ownerRole);
        return Map.of("note", note, "reviewAssignee",
                String.valueOf(roleUserIds.get(assigneeRole)));
    }

    /**
     * 对账草稿所有者、状态、乐观锁和已提交实例，禁止仅凭 HTTP 200 判定成功。
     *
     * @param draftId String，草稿 UUID
     * @param ownerRole String，期望所有者角色
     * @param status String，期望 ACTIVE、DELETED 或 SUBMITTED
     * @param revision long，期望 revision
     * @param processInstanceId String，提交状态的真实实例主键；其他状态为空
     * @return void，任一正式字段不一致即失败
     */
    private void assertDraftState(String draftId, String ownerRole, String status,
            long revision, String processInstanceId)
    {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select owner_user_id, draft_status, revision_no, "
                        + "submitted_process_instance_id from wf_process_draft where draft_id = ?",
                draftId);
        requireFixture(((Number) row.get("owner_user_id")).longValue()
                                == roleUserIds.get(ownerRole)
                        && status.equals(row.get("draft_status"))
                        && ((Number) row.get("revision_no")).longValue() == revision
                        && java.util.Objects.equals(processInstanceId,
                                row.get("submitted_process_instance_id")),
                "DRAFT_PERSISTED_STATE_MISMATCH");
    }

    /**
     * 核对草稿不可变审计中的动作与目标 revision。
     *
     * @param draftId String，草稿 UUID
     * @param action String，CREATED、SAVED、DELETED 或 SUBMITTED
     * @param revision long，动作后的目标 revision
     * @return void，审计缺失或重复即失败
     */
    private void assertDraftAudit(String draftId, String action, long revision)
    {
        requireFixture(jdbcTemplate.queryForObject(
                        "select count(*) from wf_process_draft_audit where draft_id = ? "
                                + "and action_type = ? and to_revision = ?",
                        Long.class, draftId, action, revision) == 1L,
                "DRAFT_AUDIT_MISSING");
    }

    /**
     * 查询调用活动可引用的真实已发布定义、状态和部署表单字段目录。
     *
     * @param roleKey String，当前流程管理员或设计者
     * @param endpoint Endpoint，调用活动目录入口
     * @return Execution，真实授权目录对账结果
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeCallActivity(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        ProcessDefinition target = definition(START_KEY);
        JsonNode body = callJson(roleKey, endpoint,
                "/workflow/call-activity/catalog?keyword=" + encode(START_KEY), null);
        requireFixture(arrayContains(body.path("data"), "definitionId", target.getId())
                        && arrayContains(body.path("data"), "processKey", START_KEY),
                "CALL_ACTIVITY_CATALOG_TARGET_MISSING");
        JsonNode option = findArrayObject(body.path("data"), "definitionId", target.getId());
        requireFixture(option != null
                        && "ACTIVE".equals(option.path("status").asText())
                        && option.path("inputFields").isArray()
                        && option.path("outputFields").isArray(),
                "CALL_ACTIVITY_CATALOG_CONTRACT_MISMATCH");
        return Execution.passedJson();
    }

    /**
     * 执行 BPMN 错误/升级目录、运行审计和本人通知已读真实链路。
     *
     * @param roleKey String，当前允许角色
     * @param endpoint Endpoint，BPMN 事件正式入口
     * @return Execution，真实 HTTP 与正式表对账结果
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeBpmnEvent(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        return switch (endpoint.handler())
        {
            case "createCode" ->
            {
                BpmnEventCodeFixture code = createBpmnEventCode(roleKey, "BPMN事件新增");
                assertBpmnEventCode(code, "ENABLED", "BPMN事件新增");
                yield Execution.passedJson();
            }
            case "listCodes" ->
            {
                BpmnEventCodeFixture code = createBpmnEventCode("workflow_admin", "BPMN目录列表");
                JsonNode body = callJson(roleKey, endpoint, "/workflow/bpmn-event/codes", null);
                requireFixture(arrayContains(body.path("data"), "eventCodeId",
                                String.valueOf(code.id())),
                        "BPMN_EVENT_CODE_LIST_ROW_MISSING");
                yield Execution.passedJson();
            }
            case "codeOptions" ->
            {
                BpmnEventCodeFixture code = createBpmnEventCode("workflow_admin", "BPMN设计选项");
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/bpmn-event/codes/options/ERROR", null);
                requireFixture(arrayContains(body.path("data"), "eventCode", code.code()),
                        "BPMN_EVENT_CODE_OPTION_MISSING");
                yield Execution.passedJson();
            }
            case "updateCode" ->
            {
                BpmnEventCodeFixture code = createBpmnEventCode(roleKey, "BPMN事件修改前");
                callJson(roleKey, endpoint,
                        "/workflow/bpmn-event/codes/" + code.id(),
                        bpmnEventCodeJson(code.code(), "BPMN事件修改后"));
                assertBpmnEventCode(code, "ENABLED", "BPMN事件修改后");
                yield Execution.passedJson();
            }
            case "changeCodeStatus" ->
            {
                BpmnEventCodeFixture code = createBpmnEventCode(roleKey, "BPMN事件停用");
                callJson(roleKey, endpoint,
                        "/workflow/bpmn-event/codes/" + code.id() + "/status",
                        json(Map.of("enabled", false)));
                assertBpmnEventCode(code, "DISABLED", "BPMN事件停用");
                yield Execution.passedJson();
            }
            case "audit" ->
            {
                BpmnEventRuntimeFixture fixture = insertBpmnEventRuntimeFixture(roleKey, false);
                JsonNode body = callJson(roleKey, endpoint, "/workflow/bpmn-event/audit", null);
                requireFixture(arrayContains(body.path("data"), "auditId",
                                String.valueOf(fixture.auditId())),
                        "BPMN_EVENT_AUDIT_ROW_MISSING");
                yield Execution.passedJson();
            }
            case "myNotifications" ->
            {
                BpmnEventRuntimeFixture fixture = insertBpmnEventRuntimeFixture(roleKey, true);
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/bpmn-event/notifications/my", null);
                requireFixture(arrayContains(body.path("data"), "notificationId",
                                String.valueOf(fixture.notificationId())),
                        "BPMN_EVENT_NOTIFICATION_ROW_MISSING");
                yield Execution.passedJson();
            }
            case "markRead" ->
            {
                BpmnEventRuntimeFixture fixture = insertBpmnEventRuntimeFixture(roleKey, true);
                callJson(roleKey, endpoint,
                        "/workflow/bpmn-event/notifications/" + fixture.notificationId()
                                + "/read", null);
                requireFixture(jdbcTemplate.queryForObject(
                                "select count(*) from wf_bpmn_event_notification "
                                        + "where notification_id = ? and recipient_user_id = ? "
                                        + "and read_status = 'READ' and read_time is not null",
                                Long.class, fixture.notificationId(),
                                String.valueOf(roleUserIds.get(roleKey))) == 1L,
                        "BPMN_EVENT_NOTIFICATION_READ_STATE_MISMATCH");
                yield Execution.passedJson();
            }
            default -> throw new AssertionError("未知 BPMN 事件 ALLOW 入口");
        };
    }

    /**
     * 通过正式管理 API 创建唯一 ERROR 编码并登记生成主键。
     *
     * @param roleKey String，具新增权限的管理员或设计者角色
     * @param eventName String，用户可见事件名称
     * @return BpmnEventCodeFixture，目录主键、稳定编码和名称
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private BpmnEventCodeFixture createBpmnEventCode(String roleKey, String eventName)
            throws IOException, InterruptedException
    {
        String eventCode = "RBAC_" + runId.substring(0, 12).toUpperCase()
                + "_" + sequence.incrementAndGet();
        JsonNode body = callJsonRaw(roleKey, "/workflow/bpmn-event/codes", "POST",
                bpmnEventCodeJson(eventCode, eventName), true);
        long eventCodeId = body.path("data").path("eventCodeId").asLong();
        requireFixture(eventCodeId > 0L, "BPMN_EVENT_CODE_ID_MISSING");
        bpmnEventCodeIds.add(eventCodeId);
        return new BpmnEventCodeFixture(eventCodeId, eventCode, eventName);
    }

    /**
     * 生成 BPMN 事件目录新增或修改请求。
     *
     * @param eventCode String，发布后不可变稳定编码
     * @param eventName String，用户可见名称
     * @return String，完整 JSON 请求正文
     */
    private String bpmnEventCodeJson(String eventCode, String eventName)
    {
        return json(Map.of("eventType", "ERROR", "eventCode", eventCode,
                "eventName", eventName, "notificationPolicy", "INITIATOR",
                "description", "RBAC真实目录管理"));
    }

    /**
     * 核对 BPMN 事件目录的稳定编码、名称和启停状态。
     *
     * @param fixture BpmnEventCodeFixture，目录主键和稳定字段
     * @param status String，期望 ENABLED 或 DISABLED
     * @param eventName String，期望用户可见名称
     * @return void，正式目录字段不一致即失败
     */
    private void assertBpmnEventCode(BpmnEventCodeFixture fixture, String status,
            String eventName)
    {
        requireFixture(jdbcTemplate.queryForObject(
                        "select count(*) from wf_bpmn_event_code where event_code_id = ? "
                                + "and event_type = 'ERROR' and event_code = ? "
                                + "and event_name = ? and status = ?",
                        Long.class, fixture.id(), fixture.code(), eventName, status) == 1L,
                "BPMN_EVENT_CODE_PERSISTED_STATE_MISMATCH");
    }

    /**
     * 基于真实 Flowable 实例写入一条专用运行审计，并按需创建当前用户通知。
     *
     * @param recipientRole String，通知接收角色；仅审计时仍用于实例办理人选择
     * @param withNotification boolean，是否创建当前用户未读通知
     * @return BpmnEventRuntimeFixture，审计和可选通知主键
     */
    private BpmnEventRuntimeFixture insertBpmnEventRuntimeFixture(String recipientRole,
            boolean withNotification)
    {
        ProcessFixture process = startStart("workflow_starter");
        Task task = taskService.createTaskQuery().taskId(process.taskId()).singleResult();
        requireFixture(task != null, "BPMN_EVENT_RUNTIME_TASK_MISSING");
        GeneratedKeyHolder auditKey = new GeneratedKeyHolder();
        jdbcTemplate.update(connection ->
        {
            PreparedStatement statement = connection.prepareStatement(
                    "insert into wf_bpmn_event_audit "
                            + "(idempotency_key, deployment_id, process_instance_id, "
                            + "process_definition_id, execution_id, source_element_id, "
                            + "source_type, event_type, event_code, event_name, match_status, "
                            + "boundary_event_id, interrupting, message_summary, "
                            + "initiator_user_id, create_time) "
                            + "values (?, ?, ?, ?, ?, ?, 'MANUAL', 'ERROR', "
                            + "'APPROVAL_BUSINESS_ERROR', '审批业务校验失败', 'UNMATCHED', "
                            + "null, null, 'RBAC真实运行审计', ?, current_timestamp(3))",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, randomHash());
            statement.setString(2, process.deploymentId());
            statement.setString(3, process.instanceId());
            statement.setString(4, process.definitionId());
            statement.setString(5, task.getExecutionId());
            statement.setString(6, task.getTaskDefinitionKey());
            statement.setString(7, String.valueOf(roleUserIds.get("workflow_starter")));
            return statement;
        }, auditKey);
        long auditId = auditKey.getKey().longValue();
        bpmnEventAuditIds.add(auditId);
        Long notificationId = null;
        if (withNotification)
        {
            GeneratedKeyHolder notificationKey = new GeneratedKeyHolder();
            jdbcTemplate.update(connection ->
            {
                PreparedStatement statement = connection.prepareStatement(
                        "insert into wf_bpmn_event_notification "
                                + "(audit_id, recipient_user_id, title, content, read_status, "
                                + "create_time) values (?, ?, 'RBAC事件通知', "
                                + "'RBAC真实事件通知正文', 'UNREAD', current_timestamp(3))",
                        Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, auditId);
                statement.setString(2, String.valueOf(roleUserIds.get(recipientRole)));
                return statement;
            }, notificationKey);
            notificationId = notificationKey.getKey().longValue();
        }
        return new BpmnEventRuntimeFixture(auditId, notificationId);
    }

    /**
     * 执行审批 SLA 业务日历、运行台账、审计和本人通知真实入口。
     *
     * @param roleKey String，当前允许角色
     * @param endpoint Endpoint，SLA 正式入口
     * @return Execution，真实 HTTP 与正式表状态对账结果
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeTaskSla(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        return switch (endpoint.handler())
        {
            case "createCalendar" ->
            {
                BusinessCalendarFixture calendar = createBusinessCalendar(roleKey,
                        "RBAC日历新增");
                assertBusinessCalendar(calendar, "RBAC日历新增", "ENABLED");
                yield Execution.passedJson();
            }
            case "listCalendars" ->
            {
                BusinessCalendarFixture calendar = createBusinessCalendar(
                        "workflow_admin", "RBAC日历列表");
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/sla/calendars", null);
                requireFixture(arrayContains(body.path("data"), "calendarId",
                                String.valueOf(calendar.id())),
                        "SLA_CALENDAR_LIST_ROW_MISSING");
                yield Execution.passedJson();
            }
            case "listEnabledCalendars" ->
            {
                BusinessCalendarFixture calendar = createBusinessCalendar(
                        "workflow_admin", "RBAC日历选项");
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/sla/calendars/enabled", null);
                requireFixture(arrayContains(body.path("data"), "calendarKey", calendar.key()),
                        "SLA_CALENDAR_OPTION_MISSING");
                yield Execution.passedJson();
            }
            case "updateCalendar" ->
            {
                BusinessCalendarFixture calendar = createBusinessCalendar(roleKey,
                        "RBAC日历修改前");
                callJson(roleKey, endpoint,
                        "/workflow/sla/calendars/" + calendar.id(),
                        businessCalendarJson(calendar.key(), "RBAC日历修改后"));
                assertBusinessCalendar(calendar, "RBAC日历修改后", "ENABLED");
                requireFixture(jdbcTemplate.queryForObject(
                                "select count(*) from wf_business_calendar_day "
                                        + "where calendar_id = ? and calendar_date = '2027-01-01' "
                                        + "and working_day = 0",
                                Long.class, calendar.id()) == 1L,
                        "SLA_CALENDAR_DAY_REPLACEMENT_MISSING");
                yield Execution.passedJson();
            }
            case "changeCalendarStatus" ->
            {
                BusinessCalendarFixture calendar = createBusinessCalendar(roleKey,
                        "RBAC日历停用");
                callJson(roleKey, endpoint,
                        "/workflow/sla/calendars/" + calendar.id() + "/status",
                        json(Map.of("enabled", false)));
                assertBusinessCalendar(calendar, "RBAC日历停用", "DISABLED");
                yield Execution.passedJson();
            }
            case "listExecutions" ->
            {
                TaskSlaRuntimeFixture fixture = insertTaskSlaRuntimeFixture(roleKey, false);
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/sla/executions", null);
                requireFixture(arrayContains(body.path("data"), "slaExecutionId",
                                String.valueOf(fixture.executionId())),
                        "SLA_EXECUTION_ROW_MISSING");
                yield Execution.passedJson();
            }
            case "listAudits" ->
            {
                TaskSlaRuntimeFixture fixture = insertTaskSlaRuntimeFixture(roleKey, false);
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/sla/audits", null);
                requireFixture(arrayContains(body.path("data"), "auditId",
                                String.valueOf(fixture.auditId())),
                        "SLA_AUDIT_ROW_MISSING");
                yield Execution.passedJson();
            }
            case "myNotifications" ->
            {
                TaskSlaRuntimeFixture fixture = insertTaskSlaRuntimeFixture(roleKey, true);
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/sla/notifications", null);
                requireFixture(arrayContains(body.path("data"), "notificationId",
                                String.valueOf(fixture.notificationId())),
                        "SLA_NOTIFICATION_ROW_MISSING");
                yield Execution.passedJson();
            }
            case "markNotificationRead" ->
            {
                TaskSlaRuntimeFixture fixture = insertTaskSlaRuntimeFixture(roleKey, true);
                callJson(roleKey, endpoint,
                        "/workflow/sla/notifications/" + fixture.notificationId() + "/read",
                        null);
                requireFixture(jdbcTemplate.queryForObject(
                                "select count(*) from wf_task_sla_notification "
                                        + "where notification_id = ? and recipient_user_id = ? "
                                        + "and read_status = 'READ' and read_time is not null",
                                Long.class, fixture.notificationId(),
                                String.valueOf(roleUserIds.get(roleKey))) == 1L,
                        "SLA_NOTIFICATION_READ_STATE_MISMATCH");
                yield Execution.passedJson();
            }
            default -> throw new AssertionError("未知审批 SLA ALLOW 入口");
        };
    }

    /**
     * 通过正式 SLA 管理 API 创建唯一业务日历并登记主键。
     *
     * @param roleKey String，具新增权限的管理员或设计者角色
     * @param calendarName String，用户可见日历名称
     * @return BusinessCalendarFixture，正式主键与稳定编码
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private BusinessCalendarFixture createBusinessCalendar(String roleKey,
            String calendarName) throws IOException, InterruptedException
    {
        String calendarKey = "RBAC_" + runId.substring(0, 10).toUpperCase()
                + "_" + sequence.incrementAndGet();
        JsonNode body = callJsonRaw(roleKey, "/workflow/sla/calendars", "POST",
                businessCalendarJson(calendarKey, calendarName), true);
        long calendarId = body.path("data").asLong();
        requireFixture(calendarId > 0L, "SLA_CALENDAR_ID_MISSING");
        businessCalendarIds.add(calendarId);
        return new BusinessCalendarFixture(calendarId, calendarKey);
    }

    /**
     * 生成含工作周、时区和节假日覆盖的完整日历请求。
     *
     * @param calendarKey String，发布后不可修改的稳定编码
     * @param calendarName String，用户可见名称
     * @return String，完整 JSON 请求正文
     */
    private String businessCalendarJson(String calendarKey, String calendarName)
    {
        Map<String, Object> day = Map.of("calendarDate", "2027-01-01",
                "workingDay", false, "dayName", "元旦");
        return json(Map.of("calendarKey", calendarKey, "calendarName", calendarName,
                "timezone", "Asia/Shanghai", "workingDays", List.of(1, 2, 3, 4, 5),
                "workStart", "09:00", "workEnd", "18:00",
                "description", "RBAC真实业务日历", "days", List.of(day)));
    }

    /**
     * 核对日历稳定编码、名称、状态和规则，不以 Controller 返回值替代持久化证据。
     *
     * @param fixture BusinessCalendarFixture，日历主键和编码
     * @param calendarName String，期望名称
     * @param status String，期望 ENABLED 或 DISABLED
     * @return void，正式主表字段不一致即失败
     */
    private void assertBusinessCalendar(BusinessCalendarFixture fixture,
            String calendarName, String status)
    {
        requireFixture(jdbcTemplate.queryForObject(
                        "select count(*) from wf_business_calendar where calendar_id = ? "
                                + "and calendar_key = ? and calendar_name = ? and status = ? "
                                + "and timezone = 'Asia/Shanghai' and working_days = '1,2,3,4,5'",
                        Long.class, fixture.id(), fixture.key(), calendarName, status) == 1L,
                "SLA_CALENDAR_PERSISTED_STATE_MISMATCH");
    }

    /**
     * 基于真实活动任务建立一条 SLA 执行、REMINDER 审计和可选本人通知。
     *
     * @param recipientRole String，通知接收角色
     * @param withNotification boolean，是否创建未读通知
     * @return TaskSlaRuntimeFixture，执行、审计和可选通知主键
     */
    private TaskSlaRuntimeFixture insertTaskSlaRuntimeFixture(String recipientRole,
            boolean withNotification)
    {
        ProcessFixture process = startStart("workflow_starter");
        Task task = taskService.createTaskQuery().taskId(process.taskId()).singleResult();
        requireFixture(task != null, "SLA_RUNTIME_TASK_MISSING");
        GeneratedKeyHolder executionKey = new GeneratedKeyHolder();
        jdbcTemplate.update(connection ->
        {
            PreparedStatement statement = connection.prepareStatement(
                    "insert into wf_task_sla_execution "
                            + "(deployment_id, process_instance_id, process_definition_id, "
                            + "task_id, task_definition_key, assignee_user_id, status, "
                            + "started_at, reminder_due_at, escalation_due_at, reminders_sent, "
                            + "paused_millis, revision, update_time) "
                            + "values (?, ?, ?, ?, ?, ?, 'ACTIVE', current_timestamp(3), "
                            + "date_add(current_timestamp(3), interval 30 minute), "
                            + "date_add(current_timestamp(3), interval 60 minute), 1, 0, 1, "
                            + "current_timestamp(3))",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, process.deploymentId());
            statement.setString(2, process.instanceId());
            statement.setString(3, process.definitionId());
            statement.setString(4, task.getId());
            statement.setString(5, task.getTaskDefinitionKey());
            statement.setString(6, task.getAssignee());
            return statement;
        }, executionKey);
        long executionId = executionKey.getKey().longValue();
        taskSlaExecutionIds.add(executionId);

        GeneratedKeyHolder auditKey = new GeneratedKeyHolder();
        jdbcTemplate.update(connection ->
        {
            PreparedStatement statement = connection.prepareStatement(
                    "insert into wf_task_sla_audit "
                            + "(sla_execution_id, action_type, action_ordinal, actor_user_id, "
                            + "detail, create_time) values (?, 'REMINDER', 1, null, "
                            + "'RBAC真实SLA提醒', current_timestamp(3))",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, executionId);
            return statement;
        }, auditKey);
        long auditId = auditKey.getKey().longValue();

        Long notificationId = null;
        if (withNotification)
        {
            GeneratedKeyHolder notificationKey = new GeneratedKeyHolder();
            jdbcTemplate.update(connection ->
            {
                PreparedStatement statement = connection.prepareStatement(
                        "insert into wf_task_sla_notification "
                                + "(audit_id, recipient_user_id, action_type, title, content, "
                                + "read_status, create_time) values (?, ?, 'REMINDER', "
                                + "'RBAC SLA提醒', 'RBAC真实SLA提醒正文', 'UNREAD', "
                                + "current_timestamp(3))",
                        Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, auditId);
                statement.setString(2, String.valueOf(roleUserIds.get(recipientRole)));
                return statement;
            }, notificationKey);
            notificationId = notificationKey.getKey().longValue();
        }
        return new TaskSlaRuntimeFixture(executionId, auditId, notificationId);
    }

    /**
     * 执行审批通知站内信、偏好、策略、人工催办和死信补偿真实链路。
     *
     * @param roleKey String，当前允许角色
     * @param endpoint Endpoint，审批通知正式入口
     * @return Execution，真实 HTTP 与正式通知表对账结果
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeNotification(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        return switch (endpoint.handler())
        {
            case "inbox" ->
            {
                NotificationOutboxFixture fixture = insertNotificationFixture(roleKey, true);
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/notification/inbox?readStatus=UNREAD&limit=100", null);
                requireFixture(arrayContains(body.path("data").path("items"),
                                "notificationId", String.valueOf(fixture.notificationId())),
                        "NOTIFICATION_INBOX_ROW_MISSING");
                requireFixture(body.path("data").path("unreadCount").asInt() >= 1,
                        "NOTIFICATION_INBOX_UNREAD_COUNT_MISMATCH");
                yield Execution.passedJson();
            }
            case "markRead" ->
            {
                NotificationOutboxFixture fixture = insertNotificationFixture(roleKey, true);
                callJson(roleKey, endpoint,
                        "/workflow/notification/inbox/" + fixture.notificationId() + "/read",
                        null);
                requireFixture(jdbcTemplate.queryForObject(
                                "select count(*) from wf_notification_inbox "
                                        + "where notification_id = ? and recipient_user_id = ? "
                                        + "and read_status = 'READ' and read_time is not null",
                                Long.class, fixture.notificationId(),
                                roleUserIds.get(roleKey)) == 1L,
                        "NOTIFICATION_INBOX_READ_STATE_MISMATCH");
                yield Execution.passedJson();
            }
            case "markAllRead" ->
            {
                List<NotificationReadSnapshot> originalRows =
                        snapshotNotificationReads(roleUserIds.get(roleKey));
                NotificationOutboxFixture fixture = insertNotificationFixture(roleKey, true);
                try
                {
                    JsonNode body = callJson(roleKey, endpoint,
                            "/workflow/notification/inbox/read-all", null);
                    requireFixture(body.path("data").asInt() >= 1,
                            "NOTIFICATION_READ_ALL_COUNT_MISMATCH");
                    requireFixture(jdbcTemplate.queryForObject(
                                    "select count(*) from wf_notification_inbox "
                                            + "where notification_id = ? and read_status = 'READ' "
                                            + "and read_time is not null",
                                    Long.class, fixture.notificationId()) == 1L,
                            "NOTIFICATION_READ_ALL_STATE_MISMATCH");
                }
                finally
                {
                    // read-all 会触及当前用户全部历史通知，必须逐行恢复运行前正式状态。
                    restoreNotificationReads(roleUserIds.get(roleKey), originalRows);
                }
                yield Execution.passedJson();
            }
            case "preference" ->
            {
                NotificationPreferenceSnapshot snapshot =
                        notificationPreference(roleUserIds.get(roleKey));
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/notification/preference", null);
                requireFixture(body.path("data").path("inboxEnabled").asBoolean()
                                == snapshot.inboxEnabled()
                                && body.path("data").path("emailEnabled").asBoolean()
                                        == snapshot.emailEnabled()
                                && body.path("data").path("revision").asInt()
                                        == snapshot.revision(),
                        "NOTIFICATION_PREFERENCE_RESPONSE_MISMATCH");
                yield Execution.passedJson();
            }
            case "savePreference" ->
            {
                long userId = roleUserIds.get(roleKey);
                NotificationPreferenceSnapshot snapshot = notificationPreference(userId);
                boolean targetInbox = !snapshot.inboxEnabled();
                boolean targetEmail = !snapshot.emailEnabled();
                try
                {
                    JsonNode body = callJson(roleKey, endpoint,
                            "/workflow/notification/preference",
                            json(Map.of("inboxEnabled", targetInbox,
                                    "emailEnabled", targetEmail,
                                    "expectedRevision", snapshot.revision())));
                    requireFixture(body.path("data").path("inboxEnabled").asBoolean()
                                    == targetInbox
                                    && body.path("data").path("emailEnabled").asBoolean()
                                            == targetEmail
                                    && body.path("data").path("revision").asInt()
                                            == snapshot.revision() + 1,
                            "NOTIFICATION_PREFERENCE_SAVE_RESPONSE_MISMATCH");
                    requireFixture(jdbcTemplate.queryForObject(
                                    "select count(*) from wf_notification_preference "
                                            + "where user_id = ? and inbox_enabled = ? "
                                            + "and email_enabled = ? and revision = ?",
                                    Long.class, userId, targetInbox, targetEmail,
                                    snapshot.revision() + 1) == 1L,
                            "NOTIFICATION_PREFERENCE_SAVE_STATE_MISMATCH");
                }
                finally
                {
                    restoreNotificationPreference(userId, snapshot);
                }
                yield Execution.passedJson();
            }
            case "urge" ->
            {
                ProcessFixture process = startStart(roleKey);
                Task task = taskService.createTaskQuery().taskId(process.taskId()).singleResult();
                requireFixture(task != null && task.getAssignee() != null,
                        "NOTIFICATION_URGE_ACTIVE_TASK_MISSING");
                long recipientUserId = Long.parseLong(task.getAssignee());
                NotificationPreferenceSnapshot snapshot =
                        notificationPreference(recipientUserId);
                try
                {
                    forceNotificationInboxEnabled(recipientUserId);
                    JsonNode body = callJson(roleKey, endpoint,
                            "/workflow/notification/urge",
                            json(Map.of("processInstanceId", process.instanceId(),
                                    "reason", "RBAC真实人工催办")));
                    long urgeId = body.path("data").path("urgeId").asLong();
                    int outboxCount = body.path("data").path("outboxCount").asInt();
                    requireFixture(urgeId > 0L && outboxCount > 0
                                    && body.path("data").path("recipientCount").asInt() >= 1,
                            "NOTIFICATION_URGE_RESPONSE_MISMATCH");
                    requireFixture(jdbcTemplate.queryForObject(
                                    "select count(*) from wf_notification_urge_audit "
                                            + "where urge_id = ? and process_instance_id = ? "
                                            + "and actor_user_id = ? and reason = ?",
                                    Long.class, urgeId, process.instanceId(),
                                    roleUserIds.get(roleKey), "RBAC真实人工催办") == 1L,
                            "NOTIFICATION_URGE_AUDIT_MISSING");
                    List<Long> outboxIds = jdbcTemplate.queryForList(
                            "select outbox_id from wf_notification_outbox "
                                    + "where process_instance_id = ? and event_type = 'MANUAL_URGE'",
                            Long.class, process.instanceId());
                    notificationOutboxIds.addAll(outboxIds);
                    requireFixture(outboxIds.size() == outboxCount,
                            "NOTIFICATION_URGE_OUTBOX_COUNT_MISMATCH");
                    requireFixture(taskService.createTaskQuery().taskId(task.getId())
                                    .active().count() == 1L,
                            "NOTIFICATION_URGE_CHANGED_TASK_STATE");
                }
                finally
                {
                    restoreNotificationPreference(recipientUserId, snapshot);
                }
                yield Execution.passedJson();
            }
            case "policies" ->
            {
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/notification/policies", null);
                JsonNode defaultPolicy = findArrayObject(body.path("data"),
                        "eventType", "MANUAL_URGE");
                requireFixture(defaultPolicy != null
                                && "DEFAULT".equals(
                                        defaultPolicy.path("scopeType").asText())
                                && "ENABLED".equals(
                                        defaultPolicy.path("status").asText()),
                        "NOTIFICATION_DEFAULT_POLICY_MISSING");
                yield Execution.passedJson();
            }
            case "savePolicy" ->
            {
                String eventType = "workflow_designer".equals(roleKey)
                        ? "PROCESS_COMPLETED" : "COPY_CREATED";
                Map<String, Object> request = new LinkedHashMap<>();
                request.put("scopeType", "PROCESS");
                request.put("processDefinitionKey", START_KEY);
                request.put("eventType", eventType);
                request.put("recipientRules", "INITIATOR");
                request.put("channels", "INBOX");
                request.put("titleTemplate", notificationPolicyTitleTemplate());
                request.put("contentTemplate", notificationPolicyContentTemplate());
                request.put("maxAttempts", 3);
                request.put("status", "ENABLED");
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/notification/policies", json(request));
                long policyId = body.path("data").path("policyId").asLong();
                requireFixture(policyId > 0L, "NOTIFICATION_POLICY_ID_MISSING");
                notificationPolicyIds.add(policyId);
                requireFixture(jdbcTemplate.queryForObject(
                                "select count(*) from wf_notification_policy "
                                        + "where policy_id = ? and scope_type = 'PROCESS' "
                                        + "and process_definition_key = ? "
                                        + "and task_definition_key is null "
                                        + "and event_type = ? "
                                        + "and recipient_rules = 'INITIATOR' "
                                        + "and channels = 'INBOX' and max_attempts = 3 "
                                        + "and status = 'ENABLED' and revision = 0",
                                Long.class, policyId, START_KEY, eventType) == 1L,
                        "NOTIFICATION_POLICY_PERSISTED_STATE_MISMATCH");
                yield Execution.passedJson();
            }
            case "outbox" ->
            {
                NotificationOutboxFixture fixture = insertNotificationFixture(roleKey, false);
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/notification/outbox", null);
                JsonNode row = findArrayObject(body.path("data"), "outboxId",
                        String.valueOf(fixture.outboxId()));
                requireFixture(row != null
                                && "DEAD_LETTER".equals(row.path("status").asText())
                                && row.path("deliveryCycle").asInt() == 1,
                        "NOTIFICATION_OUTBOX_ROW_MISSING");
                yield Execution.passedJson();
            }
            case "compensate" ->
            {
                NotificationOutboxFixture fixture = insertNotificationFixture(roleKey, false);
                callJson(roleKey, endpoint,
                        "/workflow/notification/outbox/" + fixture.outboxId()
                                + "/compensate", null);
                requireFixture(jdbcTemplate.queryForObject(
                                "select count(*) from wf_notification_outbox "
                                        + "where outbox_id = ? and status = 'RETRYING' "
                                        + "and delivery_cycle = 2 and attempt_count = 0 "
                                        + "and total_attempt_count = 3 and revision = 1 "
                                        + "and processed_time is null",
                                Long.class, fixture.outboxId()) == 1L,
                        "NOTIFICATION_COMPENSATE_STATE_MISMATCH");
                requireFixture(jdbcTemplate.queryForObject(
                                "select count(*) from wf_notification_delivery_audit "
                                        + "where outbox_id = ? and action_type = 'COMPENSATE' "
                                        + "and delivery_cycle = 2 and attempt_no = 0 "
                                        + "and total_attempt_no = 3 "
                                        + "and from_status = 'DEAD_LETTER' "
                                        + "and to_status = 'RETRYING'",
                                Long.class, fixture.outboxId()) == 1L,
                        "NOTIFICATION_COMPENSATE_AUDIT_MISSING");
                yield Execution.passedJson();
            }
            default -> throw new AssertionError("未知审批通知 ALLOW 入口");
        };
    }

    /**
     * 建立一条与真实 Flowable 实例关联的通知 outbox，并按需建立未读站内信。
     *
     * @param recipientRole String，正式通知接收角色
     * @param withInbox boolean，true 创建 PROCESSED outbox 和未读 inbox；false 创建死信
     * @return NotificationOutboxFixture，outbox、可选站内信和真实流程对象
     */
    private NotificationOutboxFixture insertNotificationFixture(String recipientRole,
            boolean withInbox)
    {
        ProcessFixture process = startStart("workflow_starter");
        String status = withInbox ? "PROCESSED" : "DEAD_LETTER";
        int attempts = withInbox ? 1 : 3;
        GeneratedKeyHolder outboxKey = new GeneratedKeyHolder();
        jdbcTemplate.update(connection ->
        {
            PreparedStatement statement = connection.prepareStatement(
                    "insert into wf_notification_outbox "
                            + "(idempotency_key,event_type,channel,recipient_user_id,"
                            + "process_definition_key,process_instance_id,task_id,"
                            + "task_definition_key,actor_user_id,title,content,route_path,"
                            + "status,delivery_cycle,attempt_count,total_attempt_count,"
                            + "max_attempts,next_attempt_at,last_error_code,last_error_summary,"
                            + "revision,create_time,processed_time) "
                            + "values (?,'COPY_CREATED','INBOX',?,?,?,?,?,?,"
                            + "'RBAC审批通知','RBAC真实审批通知正文',?, ?,1,?,?,3,"
                            + "date_add(current_timestamp(3), interval 1 day),?,?,0,"
                            + "current_timestamp(3),current_timestamp(3))",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, randomHash());
            statement.setLong(2, roleUserIds.get(recipientRole));
            statement.setString(3, START_KEY);
            statement.setString(4, process.instanceId());
            statement.setString(5, process.taskId());
            statement.setString(6, process.taskDefinitionKey());
            statement.setString(7,
                    String.valueOf(roleUserIds.get("workflow_starter")));
            statement.setString(8,
                    "/workflow/process/detail?procInsId=" + process.instanceId());
            statement.setString(9, status);
            statement.setInt(10, attempts);
            statement.setInt(11, attempts);
            statement.setString(12, withInbox ? null : "RBAC_DELIVERY_EXHAUSTED");
            statement.setString(13, withInbox ? null : "RBAC有界投递已耗尽");
            return statement;
        }, outboxKey);
        long outboxId = outboxKey.getKey().longValue();
        notificationOutboxIds.add(outboxId);

        Long notificationId = null;
        if (withInbox)
        {
            GeneratedKeyHolder inboxKey = new GeneratedKeyHolder();
            jdbcTemplate.update(connection ->
            {
                PreparedStatement statement = connection.prepareStatement(
                        "insert into wf_notification_inbox "
                                + "(outbox_id,recipient_user_id,event_type,title,content,"
                                + "process_instance_id,task_id,route_path,read_status,"
                                + "create_time,read_time) values (?,?,'COPY_CREATED',"
                                + "'RBAC审批通知','RBAC真实审批通知正文',?,?,?,"
                                + "'UNREAD',current_timestamp(3),null)",
                        Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, outboxId);
                statement.setLong(2, roleUserIds.get(recipientRole));
                statement.setString(3, process.instanceId());
                statement.setString(4, process.taskId());
                statement.setString(5,
                        "/workflow/process/detail?procInsId=" + process.instanceId());
                return statement;
            }, inboxKey);
            notificationId = inboxKey.getKey().longValue();
        }
        return new NotificationOutboxFixture(outboxId, notificationId, process);
    }

    /**
     * 读取用户通知偏好；没有正式行时返回服务公开的默认值与 revision 0。
     *
     * @param userId long，正式用户主键
     * @return NotificationPreferenceSnapshot，是否存在及完整可恢复字段
     */
    private NotificationPreferenceSnapshot notificationPreference(long userId)
    {
        List<NotificationPreferenceSnapshot> rows = jdbcTemplate.query(
                "select inbox_enabled,email_enabled,revision,update_time "
                        + "from wf_notification_preference where user_id = ?",
                (result, rowNum) -> new NotificationPreferenceSnapshot(true,
                        result.getBoolean("inbox_enabled"),
                        result.getBoolean("email_enabled"),
                        result.getInt("revision"), result.getTimestamp("update_time")),
                userId);
        return rows.isEmpty()
                ? new NotificationPreferenceSnapshot(false, true, true, 0, null)
                : rows.get(0);
    }

    /**
     * 为人工催办临时确保接收人的站内通道开启，调用完成后必须按快照恢复。
     *
     * @param userId long，真实活动任务接收用户主键
     * @return void，无返回值；只修改该用户正式偏好行
     */
    private void forceNotificationInboxEnabled(long userId)
    {
        int updated = jdbcTemplate.update(
                "update wf_notification_preference set inbox_enabled = 1 "
                        + "where user_id = ?", userId);
        if (updated == 0)
        {
            jdbcTemplate.update("insert into wf_notification_preference "
                            + "(user_id,inbox_enabled,email_enabled,revision,update_time) "
                            + "values (?,1,1,0,current_timestamp(3))",
                    userId);
        }
    }

    /**
     * 精确恢复通知偏好写入前的存在性、通道开关、revision 和更新时间。
     *
     * @param userId long，正式用户主键
     * @param snapshot NotificationPreferenceSnapshot，写入前完整快照
     * @return void，无返回值；不会影响其他用户偏好
     */
    private void restoreNotificationPreference(long userId,
            NotificationPreferenceSnapshot snapshot)
    {
        if (!snapshot.exists())
        {
            jdbcTemplate.update("delete from wf_notification_preference where user_id = ?",
                    userId);
            return;
        }
        jdbcTemplate.update("update wf_notification_preference set inbox_enabled = ?,"
                        + "email_enabled = ?,revision = ?,update_time = ? where user_id = ?",
                snapshot.inboxEnabled(), snapshot.emailEnabled(), snapshot.revision(),
                snapshot.updateTime(), userId);
    }

    /**
     * 快照当前用户运行前全部通知阅读状态，供 read-all 后无损恢复存量数据。
     *
     * @param userId long，正式用户主键
     * @return List&lt;NotificationReadSnapshot&gt;，按通知主键稳定排序的状态快照
     */
    private List<NotificationReadSnapshot> snapshotNotificationReads(long userId)
    {
        return jdbcTemplate.query(
                "select notification_id,read_status,read_time "
                        + "from wf_notification_inbox where recipient_user_id = ? "
                        + "order by notification_id",
                (result, rowNum) -> new NotificationReadSnapshot(
                        result.getLong("notification_id"),
                        result.getString("read_status"),
                        result.getTimestamp("read_time")), userId);
    }

    /**
     * 将 read-all 触及的运行前站内信逐行恢复，不修改本 fixture 新建通知。
     *
     * @param userId long，正式用户主键
     * @param snapshots List，运行前通知阅读状态
     * @return void，无返回值；每次更新同时限定通知主键与所属用户
     */
    private void restoreNotificationReads(long userId,
            List<NotificationReadSnapshot> snapshots)
    {
        for (NotificationReadSnapshot snapshot : snapshots)
        {
            jdbcTemplate.update("update wf_notification_inbox set read_status = ?,"
                            + "read_time = ? where notification_id = ? "
                            + "and recipient_user_id = ?",
                    snapshot.readStatus(), snapshot.readTime(),
                    snapshot.notificationId(), userId);
        }
    }

    /**
     * 执行协作入站、出站、审计、补偿与取消真实管理链路。
     *
     * @param roleKey String，当前允许角色
     * @param endpoint Endpoint，协作管理正式入口
     * @return Execution，真实 HTTP、状态机和审计对账结果
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeCollaboration(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        CollaborationFixture fixture = createCollaborationFixture();
        try
        {
            return switch (endpoint.handler())
            {
                case "inbound" ->
                {
                    insertCollaborationInbound(fixture, "RETRYING");
                    JsonNode body = callJson(roleKey, endpoint,
                            "/workflow/collaboration/inbound", null);
                    JsonNode row = findArrayObject(body.path("data"), "messageId",
                            fixture.messageId());
                    requireFixture(row != null
                                    && "RETRYING".equals(row.path("status").asText()),
                            "COLLABORATION_INBOUND_ROW_MISSING");
                    yield Execution.passedJson();
                }
                case "outbox" ->
                {
                    insertCollaborationOutbox(fixture, "PENDING");
                    JsonNode body = callJson(roleKey, endpoint,
                            "/workflow/collaboration/outbox", null);
                    JsonNode row = findArrayObject(body.path("data"), "messageId",
                            fixture.messageId());
                    requireFixture(row != null
                                    && "PENDING".equals(row.path("status").asText()),
                            "COLLABORATION_OUTBOX_ROW_MISSING");
                    yield Execution.passedJson();
                }
                case "audit" ->
                {
                    insertCollaborationInbound(fixture, "RETRYING");
                    insertCollaborationAudit(fixture.messageId(), "INBOUND", "RETRY",
                            "RECEIVED", "RETRYING", 1);
                    JsonNode body = callJson(roleKey, endpoint,
                            "/workflow/collaboration/" + fixture.messageId() + "/audit",
                            null);
                    requireFixture(arrayContains(body.path("data"), "messageId",
                                    fixture.messageId())
                                    && arrayContains(body.path("data"), "action", "RETRY"),
                            "COLLABORATION_AUDIT_ROW_MISSING");
                    yield Execution.passedJson();
                }
                case "retryInbound" ->
                {
                    insertCollaborationInbound(fixture, "DEAD_LETTER");
                    insertCollaborationAudit(fixture.messageId(), "INBOUND", "DEAD_LETTER",
                            "RETRYING", "DEAD_LETTER", 1);
                    callJson(roleKey, endpoint,
                            "/workflow/collaboration/inbound/" + fixture.messageId()
                                    + "/retry", null);
                    requireFixture(jdbcTemplate.queryForObject(
                                    "select count(*) from wf_collaboration_message "
                                            + "where message_id = ? and status = 'DEAD_LETTER' "
                                            + "and attempt_count = 1 and max_attempts = 1 "
                                            + "and compensation_count = 1 and revision_no = 2 "
                                            + "and complete_time is not null",
                                    Long.class, fixture.messageId()) == 1L,
                            "COLLABORATION_INBOUND_RETRY_STATE_MISMATCH");
                    requireFixture(jdbcTemplate.queryForObject(
                                    "select count(*) from wf_collaboration_message_audit "
                                            + "where message_id = ? and action = 'COMPENSATE' "
                                            + "and from_status = 'DEAD_LETTER' "
                                            + "and to_status = 'RETRYING'",
                                    Long.class, fixture.messageId()) == 1L,
                            "COLLABORATION_INBOUND_COMPENSATE_AUDIT_MISSING");
                    yield Execution.passedJson();
                }
                case "retryOutbox" ->
                {
                    insertCollaborationOutbox(fixture, "DEAD_LETTER");
                    insertCollaborationAudit(fixture.messageId(), "OUTBOUND", "DEAD_LETTER",
                            "DELIVERING", "DEAD_LETTER", 1);
                    callJson(roleKey, endpoint,
                            "/workflow/collaboration/outbox/" + fixture.messageId()
                                    + "/retry", null);
                    requireFixture(jdbcTemplate.queryForObject(
                                    "select count(*) from wf_collaboration_outbox "
                                            + "where message_id = ? and status = 'RETRYING' "
                                            + "and attempt_count = 0 and compensation_count = 1 "
                                            + "and revision_no = 1 and complete_time is null",
                                    Long.class, fixture.messageId()) == 1L,
                            "COLLABORATION_OUTBOX_RETRY_STATE_MISMATCH");
                    requireFixture(jdbcTemplate.queryForObject(
                                    "select count(*) from wf_collaboration_message_audit "
                                            + "where message_id = ? and direction = 'OUTBOUND' "
                                            + "and action = 'COMPENSATE' "
                                            + "and from_status = 'DEAD_LETTER' "
                                            + "and to_status = 'RETRYING'",
                                    Long.class, fixture.messageId()) == 1L,
                            "COLLABORATION_OUTBOX_COMPENSATE_AUDIT_MISSING");
                    yield Execution.passedJson();
                }
                case "cancelOutbox" ->
                {
                    insertCollaborationOutbox(fixture, "PENDING");
                    insertCollaborationAudit(fixture.messageId(), "OUTBOUND", "ENQUEUE",
                            null, "PENDING", 0);
                    callJson(roleKey, endpoint,
                            "/workflow/collaboration/outbox/" + fixture.messageId()
                                    + "/cancel", null);
                    requireFixture(jdbcTemplate.queryForObject(
                                    "select count(*) from wf_collaboration_outbox "
                                            + "where message_id = ? and status = 'CANCELLED' "
                                            + "and revision_no = 1 and complete_time is not null",
                                    Long.class, fixture.messageId()) == 1L,
                            "COLLABORATION_OUTBOX_CANCEL_STATE_MISMATCH");
                    requireFixture(jdbcTemplate.queryForObject(
                                    "select count(*) from wf_collaboration_message_audit "
                                            + "where message_id = ? and action = 'CANCEL' "
                                            + "and from_status = 'PENDING' "
                                            + "and to_status = 'CANCELLED'",
                                    Long.class, fixture.messageId()) == 1L,
                            "COLLABORATION_OUTBOX_CANCEL_AUDIT_MISSING");
                    yield Execution.passedJson();
                }
                default -> throw new AssertionError("未知协作管理 ALLOW 入口");
            };
        }
        finally
        {
            cleanupCollaborationFixture(fixture);
        }
    }

    /**
     * 为单个协作入口建立唯一凭据、HTTP 端点和严格顺序通道。
     *
     * @return CollaborationFixture，全部正式父对象及消息主键
     */
    private CollaborationFixture createCollaborationFixture()
    {
        String credentialName = uniqueName("协作凭据");
        String endpointKey = uniqueCode("collab-endpoint");
        Long credentialId = null;
        Long endpointId = null;
        String channelId = null;
        String correlationKey = uniqueBusinessKey("collaboration");
        try
        {
            credentialId = insertIntegrationCredentialFixture(credentialName);
            endpointId = insertConnectorFixture(endpointKey);
            channelId = WorkflowCollaborationChannelService.channelId(
                    START_KEY, "BUSINESS_KEY", correlationKey);
            jdbcTemplate.update("insert into wf_collaboration_channel "
                            + "(channel_id,target_process_definition_key,correlation_type,"
                            + "correlation_value,outbound_sequence,inbound_sequence,revision_no,"
                            + "create_time) values (?,?,'BUSINESS_KEY',?,1,0,0,"
                            + "current_timestamp(3))",
                    channelId, START_KEY, correlationKey);
            return new CollaborationFixture(UUID.randomUUID().toString(), channelId,
                    credentialId, credentialName, endpointId, endpointKey, correlationKey);
        }
        catch (RuntimeException | AssertionError exception)
        {
            if (channelId != null)
            {
                jdbcTemplate.update(
                        "delete from wf_collaboration_channel where channel_id = ?",
                        channelId);
            }
            cleanupConnectorFixture(endpointId, endpointKey);
            cleanupIntegrationCredentialFixture(credentialId, credentialName);
            throw exception;
        }
    }

    /**
     * 插入一条满足正式约束的入站消息，用于列表、审计或死信补偿入口。
     *
     * @param fixture CollaborationFixture，消息及父对象
     * @param status String，RETRYING 或 DEAD_LETTER
     * @return void，无返回值；状态字段保持完整一致
     */
    private void insertCollaborationInbound(CollaborationFixture fixture, String status)
    {
        boolean deadLetter = "DEAD_LETTER".equals(status);
        requireFixture(deadLetter || "RETRYING".equals(status),
                "COLLABORATION_INBOUND_FIXTURE_STATUS_INVALID");
        jdbcTemplate.update("insert into wf_collaboration_message "
                        + "(message_id,credential_id,actor_user_id,channel_id,sequence_no,"
                        + "message_name,source_process_definition_key,"
                        + "target_process_definition_key,correlation_key,"
                        + "target_process_instance_id,variables_json,payload_sha256,status,"
                        + "attempt_count,max_attempts,compensation_count,revision_no,"
                        + "last_error_code,last_error_summary,create_time,next_attempt_time,"
                        + "complete_time) values (?,?,?,?,1,'RBAC_MESSAGE',?,?,?,null,"
                        + "json_object(),?,?,?, ?,0,0,?,?,current_timestamp(3),"
                        + "case when ? then null else date_add(current_timestamp(3), interval 1 day) end,"
                        + "case when ? then current_timestamp(3) else null end)",
                fixture.messageId(), fixture.credentialId(),
                String.valueOf(roleUserIds.get("workflow_admin")), fixture.channelId(),
                START_KEY, START_KEY, fixture.correlationKey(), randomHash(), status,
                deadLetter ? 1 : 0, deadLetter ? 1 : 3,
                deadLetter ? "RBAC_TARGET_NOT_FOUND" : null,
                deadLetter ? "RBAC目标流程不存在" : null, deadLetter, deadLetter);
    }

    /**
     * 插入与真实 Flowable 实例、execution 和冻结端点关联的协作 outbox。
     *
     * @param fixture CollaborationFixture，消息及父对象
     * @param status String，PENDING 或 DEAD_LETTER
     * @return void，无返回值；真实源实例由统一清理集合回收
     */
    private void insertCollaborationOutbox(CollaborationFixture fixture, String status)
    {
        boolean deadLetter = "DEAD_LETTER".equals(status);
        requireFixture(deadLetter || "PENDING".equals(status),
                "COLLABORATION_OUTBOX_FIXTURE_STATUS_INVALID");
        ProcessFixture process = startStart("workflow_starter");
        Task task = taskService.createTaskQuery().taskId(process.taskId()).singleResult();
        requireFixture(task != null && task.getExecutionId() != null,
                "COLLABORATION_OUTBOX_SOURCE_EXECUTION_MISSING");
        jdbcTemplate.update("insert into wf_collaboration_outbox "
                        + "(message_id,channel_id,sequence_no,source_process_definition_key,"
                        + "source_process_instance_id,source_execution_id,source_element_id,"
                        + "message_name,target_process_definition_key,correlation_key,"
                        + "endpoint_id,endpoint_revision,request_path,delivery_config_json,"
                        + "variables_json,payload_sha256,status,attempt_count,max_attempts,"
                        + "compensation_count,revision_no,next_attempt_time,last_error_code,"
                        + "last_error_summary,create_time,complete_time) values (?,?,1,?,?,?,?,"
                        + "'RBAC_MESSAGE',?,?,?,1,'/api/collaboration',json_object(),"
                        + "json_object(),?,?,?,1,0,0,"
                        + "date_add(current_timestamp(3), interval 1 day),?,?,"
                        + "current_timestamp(3),case when ? then current_timestamp(3) else null end)",
                fixture.messageId(), fixture.channelId(), START_KEY,
                process.instanceId(), task.getExecutionId(),
                process.taskDefinitionKey() + "-send", START_KEY,
                fixture.correlationKey(), fixture.endpointId(), randomHash(), status,
                deadLetter ? 1 : 0,
                deadLetter ? "RBAC_REMOTE_REJECTED" : null,
                deadLetter ? "RBAC远端拒绝" : null, deadLetter);
    }

    /**
     * 写入一条脱敏协作状态审计，供真实审计查询和状态迁移断言使用。
     *
     * @param messageId String，入站或出站消息 UUID
     * @param direction String，INBOUND 或 OUTBOUND
     * @param action String，稳定动作编码
     * @param fromStatus String，可空迁移前状态
     * @param toStatus String，迁移后状态
     * @param attemptNo int，对应投递次数
     * @return void，无返回值；正文不含凭据或业务变量
     */
    private void insertCollaborationAudit(String messageId, String direction,
            String action, String fromStatus, String toStatus, int attemptNo)
    {
        jdbcTemplate.update("insert into wf_collaboration_message_audit "
                        + "(message_id,direction,action,actor_type,actor_id,from_status,"
                        + "to_status,attempt_no,error_code,summary,create_time) "
                        + "values (?,?,?,'SYSTEM','rbac-fixture',?,?,?,null,"
                        + "'RBAC真实协作审计',current_timestamp(3))",
                messageId, direction, action, fromStatus, toStatus, attemptNo);
    }

    /**
     * 按消息、通道、端点和凭据外键顺序精确回收单个协作入口数据。
     *
     * @param fixture CollaborationFixture，当前入口全部正式对象主键
     * @return void，无返回值；清理后消息及父对象均必须为零
     */
    private void cleanupCollaborationFixture(CollaborationFixture fixture)
    {
        jdbcTemplate.update("delete from wf_collaboration_message_audit where message_id = ?",
                fixture.messageId());
        jdbcTemplate.update("delete from wf_collaboration_message where message_id = ?",
                fixture.messageId());
        jdbcTemplate.update("delete from wf_collaboration_outbox where message_id = ?",
                fixture.messageId());
        jdbcTemplate.update("delete from wf_collaboration_channel where channel_id = ?",
                fixture.channelId());
        cleanupConnectorFixture(fixture.endpointId(), fixture.endpointKey());
        cleanupIntegrationCredentialFixture(fixture.credentialId(),
                fixture.credentialName());
        requireFixture(jdbcTemplate.queryForObject(
                        "select count(*) from wf_collaboration_message_audit "
                                + "where message_id = ?",
                        Long.class, fixture.messageId()) == 0L,
                "COLLABORATION_AUDIT_CLEANUP_FAILED");
        requireFixture(jdbcTemplate.queryForObject(
                        "select count(*) from wf_collaboration_channel where channel_id = ?",
                        Long.class, fixture.channelId()) == 0L,
                "COLLABORATION_CHANNEL_CLEANUP_FAILED");
    }

    /**
     * 执行扩展注册表只读入口和真实目录/版本/状态写入，并精确清理测试数据。
     *
     * @param roleKey String，当前角色
     * @param endpoint Endpoint，扩展注册表入口
     * @return Execution，真实 HTTP 与数据库对账结果
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeExtension(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        return switch (endpoint.handler())
        {
            case "javaOptions" ->
            {
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/extension/options/java", null);
                assertThat(body.path("data").isArray()).isTrue();
                assertThat(body.path("data").toString()).contains("approva.set-variable");
                yield Execution.passedJson();
            }
            case "celOptions" ->
            {
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/extension/options/cel", null);
                assertThat(body.path("data").isArray()).isTrue();
                assertThat(body.path("data").toString()).contains("approva.cel-expression");
                yield Execution.passedJson();
            }
            case "httpOptions" ->
            {
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/extension/options/http", null);
                assertThat(body.path("data").isArray()).isTrue();
                assertThat(body.path("data").toString()).contains("approva.http-connector");
                yield Execution.passedJson();
            }
            case "sqlOptions" ->
            {
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/extension/options/sql", null);
                assertThat(body.path("data").isArray()).isTrue();
                assertThat(body.path("data").toString()).contains("approva.sql-connector");
                yield Execution.passedJson();
            }
            case "formFieldOptions" ->
            {
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/extension/options/form-field", null);
                assertThat(body.path("data").isArray()).isTrue();
                assertThat(body.path("data").toString()).contains("approva.form.textarea");
                yield Execution.passedJson();
            }
            case "list" ->
            {
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/extension/list", null);
                assertThat(body.path("data").isArray()).isTrue();
                assertThat(body.path("data").toString()).contains("approva.set-variable");
                yield Execution.passedJson();
            }
            case "installedJavaHandlers" ->
            {
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/extension/installed-handlers/java", null);
                assertThat(body.path("data").isArray()).isTrue();
                assertThat(body.path("data").toString()).contains("SET_VARIABLE");
                yield Execution.passedJson();
            }
            case "create" -> executeExtensionCreate(roleKey, endpoint);
            case "createVersion" -> executeExtensionVersionCreate(roleKey, endpoint);
            case "changeStatus" -> executeExtensionStatusChange(roleKey, endpoint);
            case "remove" -> executeExtensionRemove(roleKey, endpoint);
            default -> throw new AssertionError("未知扩展注册表入口");
        };
    }

    /**
     * 执行 HTTP 端点列表、设计选项和真实新增、修订、停用链路，并精确清理夹具。
     * @param roleKey String，当前角色
     * @param endpoint Endpoint，连接端点入口
     * @return Execution，真实 JSON 成功结果
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeConnector(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        String key = PREFIX + "connector-" + UUID.randomUUID().toString().substring(0, 8);
        Long endpointId = null;
        try
        {
            if ("create".equals(endpoint.handler()))
            {
                JsonNode body = callJson(roleKey, endpoint, "/workflow/connector",
                        connectorJson(key, "RBAC 端点新增", "/api", 1000, 3000));
                endpointId = body.path("data").path("endpointId").longValue();
                assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from wf_connector_endpoint where endpoint_id = ? "
                                + "and endpoint_key = ? and revision_no = 1 and status = 'ENABLED'",
                        Integer.class, endpointId, key)).isEqualTo(1);
                return Execution.passedJson();
            }

            endpointId = insertConnectorFixture(key);
            return switch (endpoint.handler())
            {
                case "list" ->
                {
                    JsonNode body = callJson(roleKey, endpoint,
                            "/workflow/connector/list", null);
                    assertThat(arrayContains(body.path("data"), "endpointId",
                            String.valueOf(endpointId))).isTrue();
                    yield Execution.passedJson();
                }
                case "options" ->
                {
                    JsonNode body = callJson(roleKey, endpoint,
                            "/workflow/connector/options", null);
                    assertThat(arrayContains(body.path("data"), "endpointId",
                            String.valueOf(endpointId))).isTrue();
                    yield Execution.passedJson();
                }
                case "update" ->
                {
                    callJson(roleKey, endpoint, "/workflow/connector/" + endpointId,
                            connectorJson(key, "RBAC 端点修订", "/api/v2", 1200, 3500));
                    assertThat(jdbcTemplate.queryForMap(
                            "select endpoint_name, path_prefix, revision_no from "
                                    + "wf_connector_endpoint where endpoint_id = ?", endpointId))
                            .containsEntry("endpoint_name", "RBAC 端点修订")
                            .containsEntry("path_prefix", "/api/v2")
                            .containsEntry("revision_no", 2);
                    yield Execution.passedJson();
                }
                case "status" ->
                {
                    callJson(roleKey, endpoint,
                            "/workflow/connector/" + endpointId + "/status",
                            json(Map.of("enabled", false)));
                    assertThat(jdbcTemplate.queryForObject(
                            "select status from wf_connector_endpoint where endpoint_id = ?",
                            String.class, endpointId)).isEqualTo("DISABLED");
                    yield Execution.passedJson();
                }
                default -> throw new AssertionError("未知连接端点入口");
            };
        }
        finally
        {
            cleanupConnectorFixture(endpointId, key);
        }
    }

    /**
     * 执行 DMN 来源目录、设计选项、官方部署和受保护删除的真实 HTTP 链路。
     * @param roleKey String，当前管理员或流程设计者角色
     * @param endpoint Endpoint，DMN 管理入口
     * @return Execution，真实 HTTP、Flowable DMN 表和清理对账结果
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeDmn(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String decisionKey = "rbacDecision" + suffix;
        String resourceName = decisionKey + ".dmn";
        String deploymentId = null;
        try
        {
            if ("deploy".equals(endpoint.handler()))
            {
                JsonNode body = callJson(roleKey, endpoint, "/workflow/dmn",
                        dmnDeploymentJson(resourceName, decisionKey));
                deploymentId = body.path("data").path("deploymentId").asText();
                assertThat(deploymentId).isNotBlank();
                assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from ACT_DMN_DEPLOYMENT where ID_ = ?",
                        Integer.class, deploymentId)).isEqualTo(1);
                return Execution.passedJson();
            }

            JsonNode created = callJsonRaw(roleKey, "/workflow/dmn", "POST",
                    dmnDeploymentJson(resourceName, decisionKey), false);
            deploymentId = created.path("data").path("deploymentId").asText();
            final String fixtureDeploymentId = deploymentId;
            return switch (endpoint.handler())
            {
                case "list" ->
                {
                    JsonNode body = callJson(roleKey, endpoint, "/workflow/dmn/list", null);
                    assertThat(arrayContains(body.path("data"), "deploymentId",
                            fixtureDeploymentId)).isTrue();
                    yield Execution.passedJson();
                }
                case "options" ->
                {
                    JsonNode body = callJson(roleKey, endpoint, "/workflow/dmn/options", null);
                    assertThat(arrayContains(body.path("data"), "deploymentId",
                            fixtureDeploymentId)).isTrue();
                    yield Execution.passedJson();
                }
                case "delete" ->
                {
                    callJson(roleKey, endpoint, "/workflow/dmn/" + fixtureDeploymentId, null);
                    assertThat(jdbcTemplate.queryForObject(
                            "select count(*) from ACT_DMN_DEPLOYMENT where ID_ = ?",
                            Integer.class, fixtureDeploymentId)).isZero();
                    deploymentId = null;
                    yield Execution.passedJson();
                }
                default -> throw new AssertionError("未知 DMN 管理入口");
            };
        }
        finally
        {
            if (deploymentId != null && !deploymentId.isBlank()
                    && jdbcTemplate.queryForObject(
                            "select count(*) from ACT_DMN_DEPLOYMENT where ID_ = ?",
                            Integer.class, deploymentId) > 0)
            {
                callJsonRaw(roleKey, "/workflow/dmn/" + deploymentId,
                        "DELETE", null, false);
            }
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from ACT_DMN_DEPLOYMENT where NAME_ = ?",
                    Integer.class, resourceName)).isZero();
        }
    }

    /**
     * 生成可由 Flowable 8 官方 DMN Engine 部署的单规则决策请求。
     * @param resourceName String，唯一 .dmn 资源名
     * @param decisionKey String，唯一决策 key
     * @return String，包含资源元数据和完整 XML 的 JSON 请求
     */
    private String dmnDeploymentJson(String resourceName, String decisionKey)
    {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" "
                + "id=\"definitions_" + decisionKey + "\" name=\"RBAC Decision\" "
                + "namespace=\"https://approvaplat.local/rbac/dmn/" + decisionKey + "\">"
                + "<decision id=\"" + decisionKey + "\" name=\"RBAC Decision\">"
                + "<decisionTable id=\"table_" + decisionKey + "\" hitPolicy=\"FIRST\">"
                + "<input id=\"input_amount\"><inputExpression id=\"expr_amount\" "
                + "typeRef=\"integer\"><text>amount</text></inputExpression></input>"
                + "<output id=\"output_result\" name=\"result\" typeRef=\"string\"/>"
                + "<rule id=\"rule_default\"><inputEntry id=\"input_default\"><text>-</text>"
                + "</inputEntry><outputEntry id=\"output_default\"><text>\"ok\"</text>"
                + "</outputEntry></rule></decisionTable></decision></definitions>";
        return json(Map.of("resourceName", resourceName, "category", "rbac", "dmnXml", xml));
    }

    /**
     * 直接插入满足正式约束的启用端点，为只读、修订和状态接口提供前置对象。
     * @param key String，测试唯一端点键
     * @return Long，数据库生成端点主键
     */
    private Long insertConnectorFixture(String key)
    {
        jdbcTemplate.update("insert into wf_connector_endpoint "
                + "(endpoint_key, endpoint_name, base_url, allowed_methods, path_prefix, "
                + "auth_type, secret_ref, api_key_header, connect_timeout_ms, request_timeout_ms, "
                + "network_scope, revision_no, status, checksum, create_by, create_time, "
                + "update_by, update_time) values (?, 'RBAC 端点夹具', 'http://127.0.0.1:9', "
                + "'POST', '/api', 'NONE', null, null, 1000, 3000, 'PRIVATE', 1, 'ENABLED', "
                + "?, 'rbac-fixture', current_timestamp(3), '', null)", key, "0".repeat(64));
        return jdbcTemplate.queryForObject(
                "select endpoint_id from wf_connector_endpoint where endpoint_key = ?",
                Long.class, key);
    }

    /**
     * 生成连接端点正式 API 使用的 JSON 请求。
     * @param key String，稳定端点键
     * @param name String，端点名称
     * @param pathPrefix String，允许路径前缀
     * @param connectTimeout int，连接超时毫秒
     * @param requestTimeout int，请求超时毫秒
     * @return String，字段完整且不含密钥正文的 JSON
     */
    private String connectorJson(String key, String name, String pathPrefix,
            int connectTimeout, int requestTimeout)
    {
        return json(Map.of(
                "endpointKey", key,
                "endpointName", name,
                "baseUrl", "http://127.0.0.1:9",
                "allowedMethods", List.of("POST"),
                "pathPrefix", pathPrefix,
                "authType", "NONE",
                "connectTimeoutMs", connectTimeout,
                "requestTimeoutMs", requestTimeout,
                "networkScope", "PRIVATE"));
    }

    /**
     * 精确删除当前测试创建且尚无调用台账引用的连接端点。
     * @param endpointId Long，测试端点主键；创建失败时可为空
     * @param key String，测试唯一端点键
     * @return void，清理后同键端点必须为零
     */
    private void cleanupConnectorFixture(Long endpointId, String key)
    {
        if (endpointId != null && endpointId > 0)
        {
            jdbcTemplate.update("delete from wf_connector_endpoint "
                    + "where endpoint_id = ? and endpoint_key = ?", endpointId, key);
        }
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_connector_endpoint where endpoint_key = ?",
                Integer.class, key)).isZero();
    }

    /**
     * 执行 SQL 数据源管理的列表、选项、新增、修订和停用真实链路。
     * @param roleKey String，当前角色
     * @param endpoint Endpoint，SQL 数据源入口
     * @return Execution，真实 HTTP 与数据库对账结果
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeSqlDataSource(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        String key = PREFIX + "sql-" + UUID.randomUUID().toString().substring(0, 8);
        Long dataSourceId = null;
        try
        {
            if ("create".equals(endpoint.handler()))
            {
                JsonNode body = callJson(roleKey, endpoint, "/workflow/sql-datasource",
                        sqlDataSourceJson(key, "RBAC SQL 新增", List.of("wf_copy"), 1000, 10));
                dataSourceId = body.path("data").path("dataSourceId").longValue();
                assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from wf_sql_datasource where datasource_id = ? "
                                + "and datasource_key = ? and revision_no = 1 and status = 'ENABLED'",
                        Integer.class, dataSourceId, key)).isEqualTo(1);
                return Execution.passedJson();
            }

            dataSourceId = insertSqlDataSourceFixture(key);
            final Long fixtureId = dataSourceId;
            return switch (endpoint.handler())
            {
                case "list" ->
                {
                    JsonNode body = callJson(roleKey, endpoint,
                            "/workflow/sql-datasource/list", null);
                    assertThat(arrayContains(body.path("data"), "dataSourceId",
                            String.valueOf(fixtureId))).isTrue();
                    yield Execution.passedJson();
                }
                case "options" ->
                {
                    JsonNode body = callJson(roleKey, endpoint,
                            "/workflow/sql-datasource/options", null);
                    assertThat(arrayContains(body.path("data"), "dataSourceId",
                            String.valueOf(fixtureId))).isTrue();
                    yield Execution.passedJson();
                }
                case "update" ->
                {
                    callJson(roleKey, endpoint, "/workflow/sql-datasource/" + fixtureId,
                            sqlDataSourceJson(key, "RBAC SQL 修订",
                                    List.of("wf_copy", "wf_form"), 1200, 12));
                    assertThat(jdbcTemplate.queryForMap(
                            "select datasource_name, allowed_tables, revision_no "
                                    + "from wf_sql_datasource where datasource_id = ?", fixtureId))
                            .containsEntry("datasource_name", "RBAC SQL 修订")
                            .containsEntry("allowed_tables", "wf_copy,wf_form")
                            .containsEntry("revision_no", 2);
                    yield Execution.passedJson();
                }
                case "status" ->
                {
                    callJson(roleKey, endpoint,
                            "/workflow/sql-datasource/" + fixtureId + "/status",
                            json(Map.of("enabled", false)));
                    assertThat(jdbcTemplate.queryForObject(
                            "select status from wf_sql_datasource where datasource_id = ?",
                            String.class, fixtureId)).isEqualTo("DISABLED");
                    yield Execution.passedJson();
                }
                default -> throw new AssertionError("未知 SQL 数据源入口");
            };
        }
        finally
        {
            cleanupSqlDataSourceFixture(dataSourceId, key);
        }
    }

    /**
     * 插入满足正式约束的主库 SQL 数据源夹具。
     * @param key String，测试唯一数据源键
     * @return Long，数据库生成的数据源主键
     */
    private Long insertSqlDataSourceFixture(String key)
    {
        jdbcTemplate.update("insert into wf_sql_datasource "
                + "(datasource_key, datasource_name, connection_type, jdbc_url_ref, username_ref, "
                + "password_ref, allowed_tables, connect_timeout_ms, query_timeout_seconds, "
                + "revision_no, status, checksum, create_by, create_time, update_by, update_time) "
                + "values (?, 'RBAC SQL 夹具', 'PRIMARY', null, null, null, 'wf_copy', "
                + "1000, 10, 1, 'ENABLED', ?, 'rbac-fixture', current_timestamp(3), '', null)",
                key, "0".repeat(64));
        return jdbcTemplate.queryForObject(
                "select datasource_id from wf_sql_datasource where datasource_key = ?",
                Long.class, key);
    }

    /**
     * 生成 SQL 数据源正式 API 使用的 JSON 请求。
     * @param key String，稳定数据源键
     * @param name String，显示名称
     * @param tables List&lt;String&gt;，表白名单
     * @param connectTimeout int，连接超时毫秒
     * @param queryTimeout int，查询超时秒
     * @return String，不含凭据正文的字段完整 JSON
     */
    private String sqlDataSourceJson(String key, String name, List<String> tables,
            int connectTimeout, int queryTimeout)
    {
        return json(Map.of(
                "dataSourceKey", key,
                "dataSourceName", name,
                "connectionType", "PRIMARY",
                "allowedTables", tables,
                "connectTimeoutMs", connectTimeout,
                "queryTimeoutSeconds", queryTimeout));
    }

    /**
     * 精确删除当前测试创建且尚未被部署引用的 SQL 数据源。
     * @param dataSourceId Long，数据源主键；创建失败时可为空
     * @param key String，测试唯一数据源键
     * @return void，清理后同键记录必须为零
     */
    private void cleanupSqlDataSourceFixture(Long dataSourceId, String key)
    {
        if (dataSourceId != null && dataSourceId > 0)
        {
            jdbcTemplate.update("delete from wf_sql_datasource "
                    + "where datasource_id = ? and datasource_key = ?", dataSourceId, key);
        }
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_sql_datasource where datasource_key = ?",
                Integer.class, key)).isZero();
    }

    /**
     * 通过正式接口创建无版本目录并在断言后按生成主键清理。
     *
     * @param roleKey String，当前角色
     * @param endpoint Endpoint，目录创建入口
     * @return Execution，真实 JSON 成功结果
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeExtensionCreate(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        String key = PREFIX + "extension-create-" + UUID.randomUUID().toString().substring(0, 8);
        Long extensionId = null;
        try
        {
            JsonNode body = callJson(roleKey, endpoint, "/workflow/extension",
                    json(Map.of("extensionKey", key, "extensionName", "RBAC 扩展创建验收",
                            "extensionType", "JAVA", "description", "矩阵临时数据")));
            extensionId = body.path("data").path("extensionId").longValue();
            assertThat(extensionId).isPositive();
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_bpmn_extension where extension_id = ? "
                            + "and extension_key = ? and status = 'ENABLED'",
                    Integer.class, extensionId, key)).isEqualTo(1);
            return Execution.passedJson();
        }
        finally
        {
            cleanupExtensionFixture(extensionId, key);
        }
    }

    /**
     * 创建临时目录后通过正式接口发布不可变版本，并按外键顺序清理。
     *
     * @param roleKey String，当前角色
     * @param endpoint Endpoint，版本发布入口
     * @return Execution，真实 JSON 成功结果
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeExtensionVersionCreate(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        String key = PREFIX + "extension-version-" + UUID.randomUUID().toString().substring(0, 8);
        Long extensionId = insertExtensionFixture(key);
        try
        {
            JsonNode body = callJson(roleKey, endpoint,
                    "/workflow/extension/" + extensionId + "/versions",
                    json(Map.of("implementationKey", "SET_VARIABLE")));
            long versionId = body.path("data").path("versionId").longValue();
            assertThat(versionId).isPositive();
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_bpmn_extension_version where version_id = ? "
                            + "and extension_id = ? and implementation_key = 'SET_VARIABLE'",
                    Integer.class, versionId, extensionId)).isEqualTo(1);
            return Execution.passedJson();
        }
        finally
        {
            cleanupExtensionFixture(extensionId, key);
        }
    }

    /**
     * 创建临时目录后通过正式接口停用，并验证历史外对象不受影响后清理。
     *
     * @param roleKey String，当前角色
     * @param endpoint Endpoint，状态修改入口
     * @return Execution，真实 JSON 成功结果
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeExtensionStatusChange(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        String key = PREFIX + "extension-status-" + UUID.randomUUID().toString().substring(0, 8);
        Long extensionId = insertExtensionFixture(key);
        try
        {
            callJson(roleKey, endpoint, "/workflow/extension/" + extensionId + "/status",
                    json(Map.of("enabled", false)));
            assertThat(jdbcTemplate.queryForObject(
                    "select status from wf_bpmn_extension where extension_id = ?",
                    String.class, extensionId)).isEqualTo("DISABLED");
            return Execution.passedJson();
        }
        finally
        {
            cleanupExtensionFixture(extensionId, key);
        }
    }

    /**
     * 创建已停用临时目录后通过正式删除接口清理，并验证目录真实消失。
     *
     * @param roleKey String，当前角色
     * @param endpoint Endpoint，目录删除入口
     * @return Execution，真实 JSON 成功结果
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeExtensionRemove(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        String key = PREFIX + "extension-remove-" + UUID.randomUUID().toString().substring(0, 8);
        Long extensionId = insertExtensionFixture(key);
        try
        {
            jdbcTemplate.update("update wf_bpmn_extension set status = 'DISABLED' "
                    + "where extension_id = ?", extensionId);
            callJson(roleKey, endpoint, "/workflow/extension/" + extensionId, null);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_bpmn_extension where extension_id = ?",
                    Integer.class, extensionId)).isZero();
            extensionId = null;
            return Execution.passedJson();
        }
        finally
        {
            cleanupExtensionFixture(extensionId, key);
        }
    }

    /**
     * 直接插入受控测试目录，为版本和状态 HTTP 入口提供精确前置对象。
     *
     * @param key String，带测试前缀的唯一扩展键
     * @return Long，数据库生成目录主键
     */
    private Long insertExtensionFixture(String key)
    {
        jdbcTemplate.update("insert into wf_bpmn_extension "
                        + "(extension_key, extension_name, extension_type, status, description, "
                        + "create_by, create_time, update_by, update_time) "
                        + "values (?, 'RBAC 扩展验收', 'JAVA', 'ENABLED', '矩阵临时数据', "
                        + "'rbac-fixture', current_timestamp(3), '', null)", key);
        return jdbcTemplate.queryForObject(
                "select extension_id from wf_bpmn_extension where extension_key = ?",
                Long.class, key);
    }

    /**
     * 按外键顺序精确删除当前测试创建的扩展版本和目录，并断言零残留。
     *
     * @param extensionId Long，测试目录主键；接口未成功生成时可为空
     * @param key String，带测试前缀的唯一扩展键
     * @return void，无返回值；清理不完整时测试失败
     */
    private void cleanupExtensionFixture(Long extensionId, String key)
    {
        if (extensionId != null && extensionId > 0)
        {
            jdbcTemplate.update(
                    "delete from wf_bpmn_extension_version where extension_id = ?", extensionId);
            jdbcTemplate.update(
                    "delete from wf_bpmn_extension where extension_id = ? and extension_key = ?",
                    extensionId, key);
        }
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_bpmn_extension where extension_key = ?",
                Integer.class, key)).isZero();
    }

    /**
     * 执行设计器偏好查询和真实数据库写入，并在断言后恢复测试账号原始偏好。
     *
     * @param roleKey String，当前角色
     * @param endpoint Endpoint，设计器偏好入口
     * @return Execution，成功执行结果
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeDesigner(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        long userId = roleUserIds.get(roleKey);
        return switch (endpoint.handler())
        {
            case "getPreference" ->
            {
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/designer/preference", null);
                assertThat(body.path("data").path("theme").asText())
                        .isIn("LIGHT", "DARK", "SYSTEM");
                yield Execution.passedJson();
            }
            case "savePreference" ->
            {
                // 偏好属于正式用户数据，矩阵验收必须在 finally 中逐字段恢复原始状态。
                List<Map<String, Object>> original = jdbcTemplate.queryForList(
                        "select theme, grid_enabled, minimap_enabled, lint_enabled, "
                                + "token_simulation_enabled, properties_collapsed, "
                                + "create_time, update_time from wf_designer_preference "
                                + "where user_id = ?",
                        userId);
                try
                {
                    JsonNode body = callJson(roleKey, endpoint,
                            "/workflow/designer/preference",
                            json(Map.of("theme", "DARK", "gridEnabled", false,
                                    "minimapEnabled", true, "lintEnabled", true,
                                    "tokenSimulationEnabled", false,
                                    "propertiesCollapsed", true)));
                    assertThat(body.path("data").path("theme").asText()).isEqualTo("DARK");
                    assertThat(jdbcTemplate.queryForObject(
                            "select theme from wf_designer_preference where user_id = ?",
                            String.class, userId)).isEqualTo("DARK");
                    yield Execution.passedJson();
                }
                finally
                {
                    restoreDesignerPreference(userId, original);
                }
            }
            default -> throw new AssertionError("未知设计器偏好入口");
        };
    }

    /**
     * 恢复设计器偏好验收前的精确数据库状态，避免 RBAC 测试污染正式用户设置。
     *
     * @param userId long，当前测试角色的正式用户主键
     * @param original List&lt;Map&lt;String, Object&gt;&gt;，写入前零行或唯一一行快照
     * @return void，无返回值；恢复失败时由测试直接失败
     */
    private void restoreDesignerPreference(long userId,
            List<Map<String, Object>> original)
    {
        if (original.isEmpty())
        {
            jdbcTemplate.update("delete from wf_designer_preference where user_id = ?", userId);
            return;
        }
        Map<String, Object> row = original.get(0);
        jdbcTemplate.update(
                "update wf_designer_preference set theme = ?, grid_enabled = ?, "
                        + "minimap_enabled = ?, lint_enabled = ?, token_simulation_enabled = ?, "
                        + "properties_collapsed = ?, create_time = ?, update_time = ? "
                        + "where user_id = ?",
                row.get("theme"), row.get("grid_enabled"), row.get("minimap_enabled"),
                row.get("lint_enabled"), row.get("token_simulation_enabled"),
                row.get("properties_collapsed"), row.get("create_time"),
                row.get("update_time"), userId);
    }

    /**
     * 执行附件上传、对象授权元数据、逐字节下载和所有者删除。
     *
     * @param roleKey String，当前角色
     * @param endpoint Endpoint，附件入口
     * @return Execution，成功执行结果
     * @throws IOException HTTP 或文件响应读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeAttachment(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        return switch (endpoint.handler())
        {
            case "upload" ->
            {
                UploadedAttachment attachment = upload(roleKey,
                        bytesFor(roleKey, endpoint.handler()), true);
                assertThat(jdbcTemplate.queryForObject(
                        "select attachment_status from wf_attachment where attachment_id = ?",
                        String.class, attachment.id())).isEqualTo("TEMP");
                yield Execution.passedJson();
            }
            case "metadata" ->
            {
                UploadedAttachment attachment = readableAttachment(roleKey,
                        bytesFor(roleKey, endpoint.handler()));
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/attachment/" + attachment.id(), null);
                assertThat(body.path("data").path("attachmentId").asText())
                        .isEqualTo(attachment.id());
                assertThat(body.path("data").path("fileSize").asLong())
                        .isEqualTo(attachment.bytes().length);
                yield Execution.passedJson();
            }
            case "download" ->
            {
                UploadedAttachment attachment = readableAttachment(roleKey,
                        bytesFor(roleKey, endpoint.handler()));
                HttpResponse<byte[]> response = callBinary(roleKey, endpoint,
                        "/workflow/attachment/" + attachment.id() + "/content", null);
                assertThat(response.headers().firstValue("Content-Type").orElse(""))
                        .startsWith("application/octet-stream");
                assertThat(response.body()).containsExactly(attachment.bytes());
                yield Execution.passedBinary();
            }
            case "remove" ->
            {
                UploadedAttachment attachment = upload(roleKey,
                        bytesFor(roleKey, endpoint.handler()), false);
                callJson(roleKey, endpoint,
                        "/workflow/attachment/" + attachment.id(), null);
                assertThat(jdbcTemplate.queryForObject(
                        "select attachment_status from wf_attachment where attachment_id = ?",
                        String.class, attachment.id())).isEqualTo("DELETED");
                yield Execution.passedJson();
            }
            default -> throw new AssertionError("未知附件入口");
        };
    }

    /**
     * 执行分类列表、详情、导出和真实增改删。
     *
     * @param roleKey String，当前角色
     * @param endpoint Endpoint，分类入口
     * @return Execution，成功执行结果
     * @throws IOException HTTP 或 XLSX 解析失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeCategory(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        return switch (endpoint.handler())
        {
            case "list" ->
            {
                CategoryRow row = insertCategory("列表分类");
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/category/list?code=" + encode(row.code())
                                + "&pageNum=1&pageSize=10", null);
                assertRowsContain(body, "categoryId", String.valueOf(row.id()));
                yield Execution.passedJson();
            }
            case "listAll" ->
            {
                CategoryRow row = insertCategory("选择分类");
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/category/listAll?code=" + encode(row.code()), null);
                assertThat(arrayContains(body.path("data"), "categoryId",
                        String.valueOf(row.id()))).isTrue();
                yield Execution.passedJson();
            }
            case "export" ->
            {
                CategoryRow row = insertCategory("导出分类");
                HttpResponse<byte[]> response = callBinary(roleKey, endpoint,
                        "/workflow/category/export?code=" + encode(row.code()), null);
                assertWorkbookContains(response.body(), row.code());
                yield Execution.passedBinary();
            }
            case "getInfo" ->
            {
                CategoryRow row = insertCategory("详情分类");
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/category/" + row.id(), null);
                assertThat(body.path("data").path("categoryId").asLong())
                        .isEqualTo(row.id());
                yield Execution.passedJson();
            }
            case "add" ->
            {
                String code = uniqueCode("cat_add");
                String name = uniqueName("新增分类");
                JsonNode body = callJson(roleKey, endpoint, "/workflow/category",
                        json(Map.of("categoryName", name, "code", code,
                                "remark", "RBAC真实新增")));
                long id = body.path("data").path("categoryId").asLong();
                categoryIds.add(id);
                assertThat(jdbcTemplate.queryForObject(
                        "select create_by from wf_category where category_id = ?",
                        String.class, id)).isEqualTo(roleUsernames.get(roleKey));
                yield Execution.passedJson();
            }
            case "edit" ->
            {
                CategoryRow row = insertCategory("修改前分类");
                String changed = uniqueName("修改后分类");
                callJson(roleKey, endpoint, "/workflow/category",
                        json(Map.of("categoryId", row.id(), "categoryName", changed,
                                "code", row.code(), "remark", "RBAC真实修改")));
                assertThat(jdbcTemplate.queryForMap(
                        "select category_name, update_by from wf_category where category_id = ?",
                        row.id())).containsEntry("category_name", changed)
                        .containsEntry("update_by", roleUsernames.get(roleKey));
                yield Execution.passedJson();
            }
            case "remove" ->
            {
                CategoryRow row = insertCategory("删除分类");
                callJson(roleKey, endpoint, "/workflow/category/" + row.id(), null);
                assertThat(jdbcTemplate.queryForObject(
                        "select del_flag from wf_category where category_id = ?",
                        String.class, row.id())).isEqualTo("2");
                yield Execution.passedJson();
            }
            default -> throw new AssertionError("未知分类入口");
        };
    }

    /**
     * 执行表单列表、详情、导出和真实增改删。
     *
     * @param roleKey String，当前角色
     * @param endpoint Endpoint，表单入口
     * @return Execution，成功执行结果
     * @throws IOException HTTP 或 XLSX 解析失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeForm(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        return switch (endpoint.handler())
        {
            case "list" ->
            {
                FormRow row = insertForm("列表表单");
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/form/list?formName=" + encode(row.name())
                                + "&pageNum=1&pageSize=10", null);
                assertRowsContain(body, "formId", String.valueOf(row.id()));
                yield Execution.passedJson();
            }
            case "export" ->
            {
                FormRow row = insertForm("导出表单");
                HttpResponse<byte[]> response = callBinary(roleKey, endpoint,
                        "/workflow/form/export?formName=" + encode(row.name()), null);
                assertWorkbookContains(response.body(), row.name());
                yield Execution.passedBinary();
            }
            case "getInfo" ->
            {
                FormRow row = insertForm("详情表单");
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/form/" + row.id(), null);
                assertThat(body.path("data").path("formId").asLong()).isEqualTo(row.id());
                assertThat(body.path("data").path("content").asText())
                        .isEqualTo(FORM_CONTENT);
                yield Execution.passedJson();
            }
            case "add" ->
            {
                String name = uniqueName("新增表单");
                JsonNode body = callJson(roleKey, endpoint, "/workflow/form",
                        json(Map.of("formName", name, "content", FORM_CONTENT,
                                "remark", "RBAC真实新增")));
                long id = body.path("data").path("formId").asLong();
                formIds.add(id);
                assertThat(jdbcTemplate.queryForObject(
                        "select create_by from wf_form where form_id = ?",
                        String.class, id)).isEqualTo(roleUsernames.get(roleKey));
                yield Execution.passedJson();
            }
            case "edit" ->
            {
                FormRow row = insertForm("修改前表单");
                String changed = uniqueName("修改后表单");
                callJson(roleKey, endpoint, "/workflow/form",
                        json(Map.of("formId", row.id(), "formName", changed,
                                "content", FORM_CONTENT, "remark", "RBAC真实修改")));
                assertThat(jdbcTemplate.queryForMap(
                        "select form_name, update_by from wf_form where form_id = ?",
                        row.id())).containsEntry("form_name", changed)
                        .containsEntry("update_by", roleUsernames.get(roleKey));
                yield Execution.passedJson();
            }
            case "remove" ->
            {
                FormRow row = insertForm("删除表单");
                callJson(roleKey, endpoint, "/workflow/form/" + row.id(), null);
                assertThat(jdbcTemplate.queryForObject(
                        "select del_flag from wf_form where form_id = ?",
                        String.class, row.id())).isEqualTo("2");
                yield Execution.passedJson();
            }
            default -> throw new AssertionError("未知表单入口");
        };
    }

    /**
     * 执行部署列表、版本、状态、XML 和无引用部署删除。
     *
     * @param roleKey String，当前角色
     * @param endpoint Endpoint，部署入口
     * @return Execution，成功执行结果
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeDeploy(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        ProcessDefinition main = definition(START_KEY);
        return switch (endpoint.handler())
        {
            case "list" ->
            {
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/deploy/list?processKey=" + START_KEY
                                + "&pageNum=1&pageSize=10", null);
                assertRowsContain(body, "definitionId", main.getId());
                yield Execution.passedJson();
            }
            case "publishList" ->
            {
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/deploy/publishList?processKey=" + START_KEY
                                + "&pageNum=1&pageSize=10", null);
                assertRowsContain(body, "definitionId", main.getId());
                yield Execution.passedJson();
            }
            case "changeState" ->
            {
                Deployment temporary = deployTemporaryBpmn();
                ProcessDefinition target = repositoryService.createProcessDefinitionQuery()
                        .deploymentId(temporary.getId()).processDefinitionKey(START_KEY)
                        .singleResult();
                callJson(roleKey, endpoint,
                        "/workflow/deploy/changeState?state=suspended&definitionId="
                                + encode(target.getId()), null);
                assertThat(repositoryService.createProcessDefinitionQuery()
                        .processDefinitionId(target.getId()).singleResult().isSuspended()).isTrue();
                repositoryService.activateProcessDefinitionById(target.getId(), true, null);
                repositoryService.deleteDeployment(temporary.getId(), true);
                deploymentIds.remove(temporary.getId());
                yield Execution.passedJson();
            }
            case "getBpmnXml" ->
            {
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/deploy/bpmnXml/" + encode(main.getId()), null);
                assertThat(body.path("data").asText()).contains(START_KEY);
                yield Execution.passedJson();
            }
            case "remove" ->
            {
                Deployment temporary = deployTemporaryBpmn();
                callJson(roleKey, endpoint,
                        "/workflow/deploy/" + encode(temporary.getId()), null);
                assertThat(repositoryService.createDeploymentQuery()
                        .deploymentId(temporary.getId()).count()).isZero();
                deploymentIds.remove(temporary.getId());
                yield Execution.passedJson();
            }
            default -> throw new AssertionError("未知部署入口");
        };
    }

    /**
     * 执行正式用户目录查询并确认返回当前预登记启用用户。
     *
     * @param roleKey String，当前角色
     * @param endpoint Endpoint，身份目录入口
     * @return Execution，成功执行结果
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeIdentity(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        if ("resolveOptions".equals(endpoint.handler()))
        {
            String userId = String.valueOf(roleUserIds.get(roleKey));
            JsonNode body = callJson(roleKey, endpoint,
                    "/workflow/identity/options/resolve",
                    "{\"type\":\"user\",\"capability\":\"\",\"values\":[\""
                            + userId + "\"]}");
            assertThat(body.path("data").path(0).path("value").asText())
                    .isEqualTo(userId);
            assertThat(body.path("data").path(0).path("available").asBoolean()).isTrue();
            return Execution.passedJson();
        }
        JsonNode body = callJson(roleKey, endpoint,
                "/workflow/identity/options?type=user&keyword="
                        + encode(roleUsernames.get(roleKey)) + "&pageNum=1&pageSize=20", null);
        assertRowsContain(body, "value", String.valueOf(roleUserIds.get(roleKey)));
        return Execution.passedJson();
    }

    /**
     * 执行管理员实例状态切换及管理员/发起人终止。
     *
     * @param roleKey String，当前角色
     * @param endpoint Endpoint，实例入口
     * @return Execution，成功执行结果
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeInstance(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        return switch (endpoint.handler())
        {
            case "updateState" ->
            {
                ProcessFixture fixture = startSerial("workflow_starter",
                        "workflow_approver", "workflow_approver");
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/instance/updateState",
                        json(Map.of("instanceId", fixture.instanceId(),
                                "state", "suspended")));
                assertThat(body.path("data").path("state").asText())
                        .isEqualToIgnoringCase("suspended");
                assertThat(runtimeService.createProcessInstanceQuery()
                        .processInstanceId(fixture.instanceId()).singleResult().isSuspended())
                        .isTrue();
                runtimeService.activateProcessInstanceById(fixture.instanceId());
                yield Execution.passedJson();
            }
            case "terminate" ->
            {
                String starter = "workflow_starter".equals(roleKey)
                        ? roleKey : "workflow_starter";
                ProcessFixture fixture = startSerial(starter,
                        "workflow_approver", "workflow_approver");
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/instance/terminate",
                        json(Map.of("instanceId", fixture.instanceId(),
                                "reason", "RBAC真实终止")));
                String expected = "workflow_starter".equals(roleKey)
                        ? "canceled" : "terminated";
                assertThat(body.path("data").path("processStatus").asText())
                        .isEqualToIgnoringCase(expected);
                assertThat(runtimeService.createProcessInstanceQuery()
                        .processInstanceId(fixture.instanceId()).count()).isZero();
                yield Execution.passedJson();
            }
            default -> throw new AssertionError("未知实例入口");
        };
    }

    /**
     * 执行模型全部读写、版本提升、部署和 XLSX 导出。
     *
     * @param roleKey String，当前角色
     * @param endpoint Endpoint，模型入口
     * @return Execution，成功执行结果
     * @throws IOException HTTP 或 XLSX 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeModel(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        return switch (endpoint.handler())
        {
            case "list" ->
            {
                ModelFixture fixture = createModel(roleKey, "列表模型");
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/model/list?modelKey=" + encode(fixture.key())
                                + "&pageNum=1&pageSize=10", null);
                assertRowsContain(body, "modelId", fixture.id());
                yield Execution.passedJson();
            }
            case "historyList" ->
            {
                ModelFixture original = createModel(roleKey, "历史模型");
                ModelFixture latest = saveNewModelVersion(roleKey, original);
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/model/historyList?modelKey=" + encode(original.key())
                                + "&pageNum=1&pageSize=10", null);
                assertRowsContain(body, "modelId", original.id());
                assertThat(latest.id()).isNotEqualTo(original.id());
                yield Execution.passedJson();
            }
            case "getInfo" ->
            {
                ModelFixture fixture = createModel(roleKey, "详情模型");
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/model/" + fixture.id(), null);
                assertThat(body.path("data").path("modelId").asText())
                        .isEqualTo(fixture.id());
                yield Execution.passedJson();
            }
            case "getBpmnXml" ->
            {
                ModelFixture fixture = createModel(roleKey, "XML模型");
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/model/bpmnXml/" + fixture.id(), null);
                assertThat(body.path("data").asText()).contains(fixture.key());
                yield Execution.passedJson();
            }
            case "add" ->
            {
                ModelFixture fixture = createModel(roleKey, "新增模型");
                assertThat(repositoryService.createModelQuery().modelId(fixture.id()).count())
                        .isEqualTo(1L);
                yield Execution.passedJson();
            }
            case "edit" ->
            {
                ModelFixture fixture = createModel(roleKey, "修改前模型");
                String changed = uniqueName("修改后模型");
                callJson(roleKey, endpoint, "/workflow/model",
                        json(Map.of("modelId", fixture.id(), "modelName", changed,
                                "modelKey", fixture.key(), "category", commonCategoryCode,
                                "description", "RBAC真实修改", "formType", 0,
                                "formId", commonFormId)));
                assertThat(repositoryService.getModel(fixture.id()).getName())
                        .isEqualTo(changed);
                yield Execution.passedJson();
            }
            case "save" ->
            {
                ModelFixture fixture = createModel(roleKey, "保存模型");
                String xml = deployableModelXml(roleKey, fixture);
                JsonNode body = callJson(roleKey, endpoint, "/workflow/model/save",
                        json(Map.of("requestId", newModelSaveRequestId(),
                                "modelId", fixture.id(), "bpmnXml", xml,
                                "newVersion", false)));
                assertThat(body.path("data").path("modelId").asText())
                        .isEqualTo(fixture.id());
                assertThat(modelXml(fixture.id())).contains(fixture.key());
                yield Execution.passedJson();
            }
            case "validate" ->
            {
                ModelFixture fixture = createModel(roleKey, "校验模型");
                JsonNode body = callJson(roleKey, endpoint, "/workflow/model/validate",
                        json(Map.of("bpmnXml", deployableModelXml(roleKey, fixture))));
                assertThat(body.path("data").path("valid").asBoolean()).isTrue();
                // 校验通过时仍要求返回稳定空数组，避免前端对缺失字段做额外兼容。
                JsonNode issues = body.path("data").path("issues");
                assertThat(issues.isArray()).isTrue();
                assertThat(issues.size()).isZero();
                yield Execution.passedJson();
            }
            case "latest" ->
            {
                ModelFixture original = createModel(roleKey, "提升模型");
                saveNewModelVersion(roleKey, original);
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/model/latest?modelId=" + original.id(), null);
                String promotedId = body.path("data").path("modelId").asText();
                modelIds.add(promotedId);
                assertThat(repositoryService.getModel(promotedId).getVersion())
                        .isGreaterThan(repositoryService.getModel(original.id()).getVersion());
                yield Execution.passedJson();
            }
            case "remove" ->
            {
                ModelFixture fixture = createModel(roleKey, "删除模型");
                callJson(roleKey, endpoint, "/workflow/model/" + fixture.id(), null);
                assertThat(repositoryService.createModelQuery().modelId(fixture.id()).count())
                        .isZero();
                modelIds.remove(fixture.id());
                yield Execution.passedJson();
            }
            case "deployModel" ->
            {
                ModelFixture fixture = createModel(roleKey, "部署模型");
                saveDeployableModel(roleKey, fixture);
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/model/deploy?modelId=" + fixture.id(), null);
                String deploymentId = body.path("data").path("deploymentId").asText();
                deploymentIds.add(deploymentId);
                assertThat(repositoryService.createDeploymentQuery()
                        .deploymentId(deploymentId).count()).isEqualTo(1L);
                yield Execution.passedJson();
            }
            case "export" ->
            {
                ModelFixture fixture = createModel(roleKey, "导出模型");
                HttpResponse<byte[]> response = callBinary(roleKey, endpoint,
                        "/workflow/model/export?modelKey=" + encode(fixture.key()), null);
                assertWorkbookContains(response.body(), fixture.key());
                yield Execution.passedBinary();
            }
            default -> throw new AssertionError("未知模型入口");
        };
    }

    /**
     * 执行七类工作台、七类导出、发起表单、真实发起、历史删除、BPMN 与详情。
     *
     * @param roleKey String，当前角色
     * @param endpoint Endpoint，流程入口
     * @return Execution，成功执行结果
     * @throws IOException HTTP 或 XLSX 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeProcess(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        return switch (endpoint.handler())
        {
            case "startProcessList" ->
            {
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/process/list?processKey=" + START_KEY
                                + "&pageNum=1&pageSize=10", null);
                assertRowsContain(body, "definitionId", definition(START_KEY).getId());
                yield Execution.passedJson();
            }
            case "ownProcessList" ->
            {
                ProcessFixture fixture = startStart(roleKey);
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/process/ownList?businessKey="
                                + encode(fixture.businessKey()) + "&pageNum=1&pageSize=10", null);
                assertRowsContain(body, "processInstanceId", fixture.instanceId());
                yield Execution.passedJson();
            }
            case "managedProcessList" ->
            {
                ProcessFixture fixture = startStart("workflow_starter");
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/process/manageList?processInstanceId="
                                + fixture.instanceId() + "&pageNum=1&pageSize=10", null);
                assertRowsContain(body, "processInstanceId", fixture.instanceId());
                yield Execution.passedJson();
            }
            case "todoProcessList" ->
            {
                String taskRole = approvalAssigneeRole(roleKey);
                ProcessFixture fixture = startSerial("workflow_starter", taskRole,
                        otherRole(taskRole));
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/process/todoList?processKey=" + SERIAL_KEY
                                + "&pageNum=1&pageSize=200", null);
                if ("workflow_auditor".equals(roleKey))
                {
                    // 审计员有入口权限但没有办理权限，必须返回成功空集且不能泄露他人待办。
                    assertRowsExcludeAndEmpty(body, "taskId", fixture.taskId());
                }
                else
                {
                    assertRowsContain(body, "taskId", fixture.taskId());
                }
                yield Execution.passedJson();
            }
            case "claimProcessList" ->
            {
                // 审计员只有入口读取权限，不能伪装成候选办理人；由合法审批人承载目标任务。
                ProcessFixture fixture = startClaim("workflow_starter",
                        approvalAssigneeRole(roleKey));
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/process/claimList?processKey=" + CLAIM_KEY
                                + "&pageNum=1&pageSize=200", null);
                if ("workflow_auditor".equals(roleKey))
                {
                    // 入口可访问不扩大个人对象范围，审计员不得看到他人的候选任务。
                    assertRowsExcludeAndEmpty(body, "taskId", fixture.taskId());
                }
                else
                {
                    assertRowsContain(body, "taskId", fixture.taskId());
                }
                yield Execution.passedJson();
            }
            case "finishedProcessList" ->
            {
                String taskRole = approvalAssigneeRole(roleKey);
                ProcessFixture fixture = completedFirstTask(taskRole);
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/process/finishedList?processKey=" + SERIAL_KEY
                                + "&pageNum=1&pageSize=200", null);
                if ("workflow_auditor".equals(roleKey))
                {
                    // 审计员可以访问已办入口，但不得看到由其他审批人完成的个人已办任务。
                    assertRowsExcludeAndEmpty(body, "taskId", fixture.taskId());
                }
                else
                {
                    assertRowsContain(body, "taskId", fixture.taskId());
                }
                yield Execution.passedJson();
            }
            case "copyProcessList" ->
            {
                ProcessFixture fixture = startStart("workflow_starter");
                CopyRow copy = insertCopy(fixture, roleKey);
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/process/copyList?instanceId=" + fixture.instanceId()
                                + "&pageNum=1&pageSize=10", null);
                assertRowsContain(body, "copyId", String.valueOf(copy.id()));
                yield Execution.passedJson();
            }
            case "markCopyRead" ->
            {
                ProcessFixture fixture = startStart("workflow_starter");
                CopyRow copy = insertCopy(fixture, roleKey);
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/process/copy/" + copy.id() + "/read", null);
                requireFixture(body.path("data").path("copyId").asLong() == copy.id()
                                && "1".equals(body.path("data").path("readStatus").asText())
                                && !body.path("data").path("readTime").isMissingNode()
                                && !body.path("data").path("readTime").isNull(),
                        "COPY_READ_RESPONSE_STATE_MISMATCH");
                requireFixture(jdbcTemplate.queryForObject(
                                "select count(*) from wf_copy where copy_id = ? and user_id = ? "
                                        + "and read_status = '1' and read_time is not null",
                                Long.class, copy.id(), roleUserIds.get(roleKey)) == 1L,
                        "COPY_READ_PERSISTED_STATE_MISMATCH");
                yield Execution.passedJson();
            }
            case "startExport" ->
            {
                HttpResponse<byte[]> response = callBinary(roleKey, endpoint,
                        "/workflow/process/startExport?processKey=" + START_KEY, null);
                assertWorkbookContains(response.body(), definition(START_KEY).getId());
                yield Execution.passedBinary();
            }
            case "ownExport" ->
            {
                ProcessFixture fixture = startStart(roleKey);
                HttpResponse<byte[]> response = callBinary(roleKey, endpoint,
                        "/workflow/process/ownExport?businessKey="
                                + encode(fixture.businessKey()), null);
                assertWorkbookContains(response.body(), fixture.instanceId());
                yield Execution.passedBinary();
            }
            case "managedExport" ->
            {
                ProcessFixture fixture = startStart("workflow_starter");
                HttpResponse<byte[]> response = callBinary(roleKey, endpoint,
                        "/workflow/process/manageExport?processInstanceId="
                                + fixture.instanceId(), null);
                assertWorkbookContains(response.body(), fixture.instanceId());
                yield Execution.passedBinary();
            }
            case "todoExport" ->
            {
                String taskRole = approvalAssigneeRole(roleKey);
                ProcessFixture fixture = startSerial("workflow_starter", taskRole,
                        otherRole(taskRole));
                HttpResponse<byte[]> response = callBinary(roleKey, endpoint,
                        "/workflow/process/todoExport?processKey=" + SERIAL_KEY, null);
                if ("workflow_auditor".equals(roleKey))
                {
                    assertWorkbookExcludes(response.body(), fixture.taskId());
                }
                else
                {
                    assertWorkbookContains(response.body(), fixture.taskId());
                }
                yield Execution.passedBinary();
            }
            case "claimExport" ->
            {
                // 与候选列表使用同一对象规则，审计员不得被构造成无资格候选人。
                ProcessFixture fixture = startClaim("workflow_starter",
                        approvalAssigneeRole(roleKey));
                HttpResponse<byte[]> response = callBinary(roleKey, endpoint,
                        "/workflow/process/claimExport?processKey=" + CLAIM_KEY, null);
                if ("workflow_auditor".equals(roleKey))
                {
                    assertWorkbookExcludes(response.body(), fixture.taskId());
                }
                else
                {
                    assertWorkbookContains(response.body(), fixture.taskId());
                }
                yield Execution.passedBinary();
            }
            case "finishedExport" ->
            {
                String taskRole = approvalAssigneeRole(roleKey);
                ProcessFixture fixture = completedFirstTask(taskRole);
                HttpResponse<byte[]> response = callBinary(roleKey, endpoint,
                        "/workflow/process/finishedExport?processKey=" + SERIAL_KEY, null);
                if ("workflow_auditor".equals(roleKey))
                {
                    assertWorkbookExcludes(response.body(), fixture.taskId());
                }
                else
                {
                    assertWorkbookContains(response.body(), fixture.taskId());
                }
                yield Execution.passedBinary();
            }
            case "copyExport" ->
            {
                ProcessFixture fixture = startStart("workflow_starter");
                insertCopy(fixture, roleKey);
                HttpResponse<byte[]> response = callBinary(roleKey, endpoint,
                        "/workflow/process/copyExport?instanceId="
                                + fixture.instanceId(), null);
                assertWorkbookContains(response.body(), fixture.instanceId());
                yield Execution.passedBinary();
            }
            case "getForm" ->
            {
                ProcessDefinition definition = definition(START_KEY);
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/process/getProcessForm?definitionId="
                                + encode(definition.getId()) + "&deployId="
                                + encode(mainDeploymentId), null);
                assertThat(body.path("data").path("formKey").asText())
                        .isEqualTo("key_92001");
                assertThat(body.path("data").path("content").asText())
                        .isEqualTo(START_FORM_CONTENT);
                yield Execution.passedJson();
            }
            case "start" ->
            {
                ProcessDefinition definition = definition(START_KEY);
                String businessKey = uniqueBusinessKey("http-start");
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/process/start/" + encode(definition.getId()),
                        json(Map.of("businessKey", businessKey,
                                "variables", Map.of("note", "RBAC真实发起",
                                        "reviewAssignee", String.valueOf(roleUserIds.get(
                                                approvalAssigneeRole(roleKey)))))));
                String instanceId = body.path("data").path("processInstanceId").asText();
                trackProcessInstance(instanceId);
                requireFixture(!instanceId.isBlank(),
                        "ALLOW_FIXTURE_START_INSTANCE_ID_MISSING");
                HistoricProcessInstance historic = historyService
                        .createHistoricProcessInstanceQuery().processInstanceId(instanceId)
                        .singleResult();
                requireFixture(historic != null, "ALLOW_FIXTURE_START_HISTORY_MISSING");
                requireFixture(String.valueOf(roleUserIds.get(roleKey))
                                .equals(historic.getStartUserId()),
                        "ALLOW_FIXTURE_START_USER_MISMATCH");
                requireFixture(businessKey.equals(historic.getBusinessKey()),
                        "ALLOW_FIXTURE_START_BUSINESS_KEY_MISMATCH");
                requireFixture(taskService.createTaskQuery()
                                .processInstanceId(instanceId).count() == 1L,
                        "ALLOW_FIXTURE_START_ACTIVE_TASK_COUNT_MISMATCH");
                yield Execution.passedJson();
            }
            case "deleteHistory" ->
            {
                ProcessFixture fixture = startStart("workflow_starter");
                // 发起人与办理人权限分离，使用实际具审批资格的任务 assignee 完成后再验历史删除。
                directComplete(fixture.taskId(), "workflow_admin");
                assertThat(historyService.createHistoricProcessInstanceQuery()
                        .processInstanceId(fixture.instanceId()).finished().count())
                        .isEqualTo(1L);
                callJson(roleKey, endpoint,
                        "/workflow/process/instance/" + fixture.instanceId(), null);
                assertThat(historyService.createHistoricProcessInstanceQuery()
                        .processInstanceId(fixture.instanceId()).count()).isZero();
                yield Execution.passedJson();
            }
            case "getBpmnXml" ->
            {
                ProcessDefinition definition = definition(START_KEY);
                String path = "/workflow/process/bpmnXml/" + encode(definition.getId());
                if ("workflow_approver".equals(roleKey)
                        || "workflow_auditor".equals(roleKey))
                {
                    ProcessFixture fixture = startStart(roleKey);
                    path += "?procInsId=" + fixture.instanceId();
                }
                JsonNode body = callJson(roleKey, endpoint, path, null);
                assertThat(body.path("data").asText()).contains(START_KEY);
                yield Execution.passedJson();
            }
            case "detail" ->
            {
                ProcessFixture fixture;
                if ("workflow_admin".equals(roleKey))
                {
                    // 管理员对象授权覆盖：读取他人实例。
                    fixture = startStart("workflow_starter");
                }
                else if ("workflow_starter".equals(roleKey))
                {
                    // 发起人对象授权覆盖：读取本人实例。
                    fixture = startStart(roleKey);
                }
                else if ("workflow_approver".equals(roleKey))
                {
                    // 当前办理人对象授权覆盖。
                    fixture = startSerial("workflow_starter", roleKey, roleKey);
                }
                else
                {
                    // 审计员通过正式抄送业务关系读取实例，不伪造其不具备的任务办理资格。
                    fixture = startStart("workflow_starter");
                    insertCopy(fixture, roleKey);
                }
                String detailPath = "/workflow/process/detail?procInsId="
                        + fixture.instanceId();
                if (!"workflow_starter".equals(roleKey)
                        && !"workflow_auditor".equals(roleKey))
                {
                    // 发起人和抄送审计员只验证实例级读取，不携带他人 taskId 扩大对象范围。
                    detailPath += "&taskId=" + fixture.taskId();
                }
                JsonNode body = callJson(roleKey, endpoint, detailPath, null);
                assertThat(body.path("data").path("processInstanceId").asText())
                        .isEqualTo(fixture.instanceId());
                yield Execution.passedJson();
            }
            default -> throw new AssertionError("未知流程入口");
        };
    }

    /**
     * 执行取消、撤回、动态多实例、全部任务动作、变量与 PNG 流程图。
     *
     * @param roleKey String，当前角色
     * @param endpoint Endpoint，任务入口
     * @return Execution，成功执行结果
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private Execution executeTask(String roleKey, Endpoint endpoint)
            throws IOException, InterruptedException
    {
        return switch (endpoint.handler())
        {
            case "stopProcess" ->
            {
                // 取消服务要求真实发起人；workflow_admin URL 权限不扩大该对象规则。
                String taskRole = approvalAssigneeRole(roleKey);
                ProcessFixture fixture = startSerial(roleKey, taskRole,
                        otherRole(taskRole));
                callJson(roleKey, endpoint, "/workflow/task/stopProcess",
                        json(Map.of("procInsId", fixture.instanceId(),
                                "comment", "RBAC真实取消")));
                assertThat(runtimeService.createProcessInstanceQuery()
                        .processInstanceId(fixture.instanceId()).count()).isZero();
                assertHistoricStatus(fixture.instanceId(), "canceled");
                assertHasComments(fixture.instanceId());
                yield Execution.passedJson();
            }
            case "revokeProcess" ->
            {
                ProcessFixture fixture = startSerial("workflow_starter", roleKey,
                        otherRole(roleKey));
                directComplete(fixture.taskId(), roleKey);
                callJson(roleKey, endpoint, "/workflow/task/revokeProcess",
                        json(Map.of("procInsId", fixture.instanceId(),
                                "taskId", fixture.taskId(),
                                "comment", "RBAC真实撤回")));
                Task restored = taskService.createTaskQuery()
                        .processInstanceId(fixture.instanceId())
                        .taskDefinitionKey("firstReview").singleResult();
                assertThat(restored).isNotNull();
                assertThat(restored.getAssignee())
                        .isEqualTo(String.valueOf(roleUserIds.get(roleKey)));
                assertHasComments(fixture.instanceId());
                yield Execution.passedJson();
            }
            case "processVariables" ->
            {
                ProcessFixture fixture;
                if ("workflow_starter".equals(roleKey))
                {
                    fixture = completedTaskForStarterVariableRead();
                }
                else if ("workflow_auditor".equals(roleKey))
                {
                    // 历史任务沿用实例参与授权，正式抄送关系允许审计员读取安全变量投影。
                    fixture = completedTaskForStarterVariableRead();
                    insertCopy(fixture, roleKey);
                }
                else
                {
                    fixture = startSerial("workflow_starter", roleKey, roleKey);
                }
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/task/processVariables/" + fixture.taskId(), null);
                assertThat(body.path("data").path("note").asText())
                        .isEqualTo("RBAC变量");
                yield Execution.passedJson();
            }
            case "getMultiInstanceState" ->
            {
                MultiFixture fixture = startMulti(roleKey);
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/task/multiInstance/" + fixture.actorTaskId(), null);
                JsonNode members = body.path("data").path("members");
                assertThat(members.isArray()).isTrue();
                assertThat(members.size()).isOne();
                assertThat(arrayContains(members, "userId",
                        String.valueOf(fixture.actorUserId()))).isTrue();
                assertThat(arrayContains(members, "activeTaskId",
                        fixture.actorTaskId())).isTrue();
                assertThat(body.path("data").path("revision").asLong()).isNotNegative();
                yield Execution.passedJson();
            }
            case "adjustMultiInstance" ->
            {
                MultiFixture fixture = startMulti(roleKey);
                JsonNode state = callJsonRaw(roleKey,
                        "/workflow/task/multiInstance/" + fixture.actorTaskId(),
                        "GET", null, true);
                long revision = state.path("data").path("revision").asLong();
                JsonNode body = callJson(roleKey, endpoint,
                        "/workflow/task/multiInstance/adjust",
                        json(Map.of("taskId", fixture.actorTaskId(), "action", "ADD",
                                "expectedRevision", revision,
                                "comment", "RBAC真实加签",
                                "userIds", List.of(fixture.addUserId()))));
                assertThat(body.path("data").path("revision").asLong())
                        .isGreaterThan(revision);
                JsonNode members = body.path("data").path("members");
                assertThat(members.size()).isEqualTo(2);
                assertThat(arrayContains(members, "userId",
                        String.valueOf(fixture.actorUserId()))).isTrue();
                assertThat(arrayContains(members, "userId",
                        String.valueOf(fixture.addUserId()))).isTrue();
                assertThat(taskService.createTaskQuery()
                        .processInstanceId(fixture.instanceId())
                        .taskDefinitionKey("multiReview")
                        .taskAssignee(String.valueOf(fixture.addUserId())).count()).isOne();
                assertHasComments(fixture.instanceId());
                yield Execution.passedJson();
            }
            case "complete" ->
            {
                ProcessFixture fixture = startSerial("workflow_starter", roleKey,
                        otherRole(roleKey));
                callJson(roleKey, endpoint, "/workflow/task/complete",
                        completeBody(fixture.taskId(), "RBAC真实完成", List.of(), List.of()));
                assertThat(taskService.createTaskQuery().taskId(fixture.taskId()).count())
                        .isZero();
                assertThat(taskService.createTaskQuery()
                        .processInstanceId(fixture.instanceId())
                        .taskDefinitionKey("secondReview").count()).isEqualTo(1L);
                assertCompletedBy(fixture.taskId(), roleKey);
                assertHasComments(fixture.instanceId());
                yield Execution.passedJson();
            }
            case "reject" ->
            {
                ProcessFixture fixture = startSerial("workflow_starter", roleKey,
                        otherRole(roleKey));
                callJson(roleKey, endpoint, "/workflow/task/reject",
                        json(Map.of("taskId", fixture.taskId(),
                                "comment", "RBAC真实驳回", "copyUserIds", List.of())));
                assertThat(runtimeService.createProcessInstanceQuery()
                        .processInstanceId(fixture.instanceId()).count()).isZero();
                assertHistoricStatus(fixture.instanceId(), "rejected");
                assertHasComments(fixture.instanceId());
                yield Execution.passedJson();
            }
            case "returnTask" ->
            {
                ProcessFixture fixture = secondTaskReady(roleKey);
                callJson(roleKey, endpoint, "/workflow/task/return",
                        json(Map.of("taskId", fixture.taskId(),
                                "comment", "RBAC真实退回", "copyUserIds", List.of())));
                Task returned = taskService.createTaskQuery()
                        .processInstanceId(fixture.instanceId())
                        .taskDefinitionKey("firstReview").singleResult();
                assertThat(returned).isNotNull();
                assertHasComments(fixture.instanceId());
                yield Execution.passedJson();
            }
            case "resubmit" ->
            {
                ProcessDefinition definition = definition(START_KEY);
                String taskRole = approvalAssigneeRole(roleKey);
                String businessKey = uniqueBusinessKey("http-resubmit");
                JsonNode startBody = callJsonRaw(roleKey,
                        "/workflow/process/start/" + encode(definition.getId()), "POST",
                        json(Map.of("businessKey", businessKey,
                                "variables", Map.of("note", "退回前内容",
                                        "reviewAssignee", String.valueOf(
                                                roleUserIds.get(taskRole))))), true);
                String instanceId = startBody.path("data").path("processInstanceId").asText();
                trackProcessInstance(instanceId);
                Task approvalTask = taskService.createTaskQuery()
                        .processInstanceId(instanceId).active().singleResult();
                requireFixture(approvalTask != null,
                        "ALLOW_FIXTURE_RESUBMIT_APPROVAL_TASK_MISSING");
                callJsonRaw(taskRole, "/workflow/task/return", "POST",
                        json(Map.of("taskId", approvalTask.getId(),
                                "comment", "请修改原表单", "copyUserIds", List.of())), true);
                Task returnedTask = taskService.createTaskQuery()
                        .processInstanceId(instanceId).active()
                        .taskAssignee(String.valueOf(roleUserIds.get(roleKey))).singleResult();
                requireFixture(returnedTask != null,
                        "ALLOW_FIXTURE_RESUBMIT_RETURNED_TASK_MISSING");

                callJson(roleKey, endpoint, "/workflow/task/resubmit",
                        json(Map.of("taskId", returnedTask.getId(),
                                "variables", Map.of("note", "修改后内容",
                                        "reviewAssignee", String.valueOf(
                                                roleUserIds.get(taskRole))))));
                Task restoredTask = taskService.createTaskQuery()
                        .processInstanceId(instanceId).active().singleResult();
                requireFixture(restoredTask != null
                                && String.valueOf(roleUserIds.get(taskRole))
                                        .equals(restoredTask.getAssignee()),
                        "ALLOW_FIXTURE_RESUBMIT_ASSIGNMENT_NOT_RESTORED");
                requireFixture("running".equals(runtimeService.getVariable(
                                instanceId, "processStatus")),
                        "ALLOW_FIXTURE_RESUBMIT_STATUS_NOT_RUNNING");
                yield Execution.passedJson();
            }
            case "claim" ->
            {
                ProcessFixture fixture = startClaim("workflow_starter", roleKey);
                callJson(roleKey, endpoint, "/workflow/task/claim",
                        json(Map.of("taskId", fixture.taskId())));
                assertThat(taskService.createTaskQuery().taskId(fixture.taskId())
                        .singleResult().getAssignee())
                        .isEqualTo(String.valueOf(roleUserIds.get(roleKey)));
                assertHasComments(fixture.instanceId());
                yield Execution.passedJson();
            }
            case "unClaim" ->
            {
                ProcessFixture fixture = startClaim("workflow_starter", roleKey);
                taskService.claim(fixture.taskId(), String.valueOf(roleUserIds.get(roleKey)));
                callJson(roleKey, endpoint, "/workflow/task/unClaim",
                        json(Map.of("taskId", fixture.taskId())));
                assertThat(taskService.createTaskQuery().taskId(fixture.taskId())
                        .singleResult().getAssignee()).isNull();
                assertHasComments(fixture.instanceId());
                yield Execution.passedJson();
            }
            case "resolve" ->
            {
                ProcessFixture fixture = startSerial("workflow_starter",
                        otherRole(roleKey), otherRole(roleKey));
                taskService.delegateTask(fixture.taskId(),
                        String.valueOf(roleUserIds.get(roleKey)));
                callJson(roleKey, endpoint, "/workflow/task/resolve",
                        json(Map.of("taskId", fixture.taskId(),
                                "comment", "RBAC真实委派办结", "copyUserIds", List.of())));
                Task resolved = taskService.createTaskQuery().taskId(fixture.taskId())
                        .singleResult();
                assertThat(resolved.getDelegationState()).isEqualTo(DelegationState.RESOLVED);
                assertThat(resolved.getAssignee()).isEqualTo(resolved.getOwner());
                yield Execution.passedJson();
            }
            case "delegate" ->
            {
                ProcessFixture fixture = startSerial("workflow_starter", roleKey,
                        otherRole(roleKey));
                String targetRole = otherRole(roleKey);
                callJson(roleKey, endpoint, "/workflow/task/delegate",
                        json(Map.of("taskId", fixture.taskId(),
                                "userId", roleUserIds.get(targetRole),
                                "comment", "RBAC真实委派", "copyUserIds", List.of())));
                Task delegated = taskService.createTaskQuery().taskId(fixture.taskId())
                        .singleResult();
                assertThat(delegated.getAssignee())
                        .isEqualTo(String.valueOf(roleUserIds.get(targetRole)));
                assertThat(delegated.getOwner())
                        .isEqualTo(String.valueOf(roleUserIds.get(roleKey)));
                assertThat(delegated.getDelegationState()).isEqualTo(DelegationState.PENDING);
                // 目标用户必须能通过真实 HTTP 完成委派办理，避免只验证 assignee 字段变化。
                callJsonRaw(targetRole, "/workflow/task/resolve", "POST",
                        json(Map.of("taskId", fixture.taskId(),
                                "comment", "RBAC真实委派办结", "copyUserIds", List.of())), true);
                Task resolved = taskService.createTaskQuery().taskId(fixture.taskId())
                        .singleResult();
                assertThat(resolved.getDelegationState()).isEqualTo(DelegationState.RESOLVED);
                assertThat(resolved.getAssignee()).isEqualTo(resolved.getOwner());
                assertHasComments(fixture.instanceId());
                yield Execution.passedJson();
            }
            case "transfer" ->
            {
                ProcessFixture fixture = startSerial("workflow_starter", roleKey,
                        otherRole(roleKey));
                String targetRole = otherRole(roleKey);
                callJson(roleKey, endpoint, "/workflow/task/transfer",
                        json(Map.of("taskId", fixture.taskId(),
                                "userId", roleUserIds.get(targetRole),
                                "comment", "RBAC真实转办", "copyUserIds", List.of())));
                assertThat(taskService.createTaskQuery().taskId(fixture.taskId())
                        .singleResult().getAssignee())
                        .isEqualTo(String.valueOf(roleUserIds.get(targetRole)));
                // 转办目标必须能通过真实 HTTP 完成该任务，证明实时办理资格和后续动作链一致。
                callJsonRaw(targetRole, "/workflow/task/complete", "POST",
                        completeBody(fixture.taskId(), "RBAC转办后真实完成",
                                List.of(), List.of()), true);
                assertThat(taskService.createTaskQuery().taskId(fixture.taskId()).count())
                        .isZero();
                assertCompletedBy(fixture.taskId(), targetRole);
                assertHasComments(fixture.instanceId());
                yield Execution.passedJson();
            }
            case "diagram" ->
            {
                ProcessFixture fixture = startStart(roleKey);
                HttpResponse<byte[]> response = callBinary(roleKey, endpoint,
                        "/workflow/task/diagram/" + fixture.instanceId(), null);
                assertThat(response.headers().firstValue("Content-Type").orElse(""))
                        .startsWith("image/png");
                assertThat(response.body()).startsWith(PNG_SIGNATURE);
                yield Execution.passedBinary();
            }
            default -> throw new AssertionError("未知任务入口");
        };
    }

    /**
     * 补充验证“有 URL 权限但与对象无关系”仍返回 403，且引擎和业务表零变化。
     *
     * @return void，无返回值；任一对象越权或副作用出现即失败
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    void verifySupplementalObjectAuthorizationDenials()
            throws IOException, InterruptedException
    {
        ProcessFixture unrelated = startStart("workflow_admin");
        UploadedAttachment privateAttachment = upload("workflow_admin",
                bytesFor("workflow_admin", "object-denial"), false);
        Map<String, Long> before = snapshotDomainRows();

        HttpResponse<byte[]> detail = sendRequest("workflow_starter",
                "/workflow/process/detail?procInsId=" + unrelated.instanceId()
                        + "&taskId=" + unrelated.taskId(), "GET", null, null);
        assertBusinessCode(detail, 403);

        HttpResponse<byte[]> attachment = sendRequest("workflow_starter",
                "/workflow/attachment/" + privateAttachment.id(), "GET", null, null);
        assertBusinessCode(attachment, 403);

        assertThat(snapshotDomainRows())
                .as("对象授权拒绝不得改变 Flowable 或 wf_* 正式数据")
                .containsExactlyInAnyOrderEntriesOf(before);
    }

    /**
     * 通过真实 HTTP 查询和导出一条流程已结束、因此不可撤回的已办任务。
     *
     * @param roleKey String，完成第一审批节点且具备已办查询、导出权限的角色
     * @return void，无返回值；接口 500、能力字段漂移或工作簿缺少目标任务时测试失败
     * @throws IOException HTTP、JSON 或 XLSX 读取失败
     * @throws InterruptedException 请求线程中断
     */
    void verifyFinishedEndpointsForNonRevocableTask(String roleKey)
            throws IOException, InterruptedException
    {
        ProcessFixture completedSource = completedFirstTask(roleKey);
        Task successor = taskService.createTaskQuery()
                .processInstanceId(completedSource.instanceId())
                .active()
                .singleResult();
        assertThat(successor).as("串行流程第一节点完成后必须生成唯一后继任务").isNotNull();
        String successorRole = otherRole(roleKey);
        directComplete(successor.getId(), successorRole);
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(completedSource.instanceId()).count())
                .as("后继完成后流程必须真实结束，来源已办任务因此不可撤回")
                .isZero();

        JsonNode listBody = callJsonRaw(roleKey,
                "/workflow/process/finishedList?processKey=" + SERIAL_KEY
                        + "&pageNum=1&pageSize=200",
                "GET", null, false);
        JsonNode completedRow = null;
        for (JsonNode row : listBody.path("rows"))
        {
            if (completedSource.taskId().equals(row.path("taskId").asText()))
            {
                completedRow = row;
                break;
            }
        }
        assertThat(completedRow)
                .as("已办列表必须返回当前用户真实完成的来源任务")
                .isNotNull();
        assertThat(completedRow.path("revocable").asBoolean())
                .as("已结束流程的历史任务必须降级为不可撤回而不是触发事务回滚")
                .isFalse();

        HttpResponse<byte[]> exportResponse = sendWithAudit(roleKey,
                "/workflow/process/finishedExport?processKey=" + SERIAL_KEY,
                "POST", null, null, true);
        assertThat(exportResponse.statusCode()).isEqualTo(200);
        assertThat(exportResponse.headers().firstValue("Content-Type").orElse(""))
                .contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertWorkbookContains(exportResponse.body(), completedSource.taskId());
    }

    /**
     * 创建一条由指定审批角色办理的真实活动任务，供旧 Token 撤权测试使用。
     *
     * @param roleKey String，必须具备 workflow:process:approval 的五角色 key
     * @return RevocationActionFixture，流程实例与活动任务主键
     */
    RevocationActionFixture prepareRoleRevocationAction(String roleKey)
    {
        ProcessFixture fixture = startSerial("workflow_starter", roleKey,
                otherRole(roleKey));
        return new RevocationActionFixture(fixture.instanceId(), fixture.taskId());
    }

    /**
     * 登记本 fixture 成功创建的流程实例，使运行、已结束及历史已删除实例共享同一清理边界。
     *
     * @param processInstanceId String，真实 Flowable 流程实例主键
     * @return void，无返回值；空主键表示实例创建响应不完整并立即失败
     */
    private void trackProcessInstance(String processInstanceId)
    {
        requireFixture(processInstanceId != null && !processInstanceId.isBlank(),
                "ALLOW_FIXTURE_PROCESS_INSTANCE_ID_MISSING");
        processInstanceIds.add(processInstanceId);
    }

    /**
     * 从仍存在的运行、历史实例恢复本轮前缀对象，覆盖成功落库但响应断言前中断的创建路径。
     *
     * @return void，无返回值；恢复结果逐一进入同一精确实例集合
     */
    private void recoverProcessInstanceIds()
    {
        List<String> historicIds = jdbcTemplate.queryForList(
                "select PROC_INST_ID_ from ACT_HI_PROCINST where BUSINESS_KEY_ like ?",
                String.class, PREFIX + "%");
        List<String> runtimeIds = jdbcTemplate.queryForList(
                "select PROC_INST_ID_ from ACT_RU_EXECUTION where ID_ = PROC_INST_ID_ "
                        + "and BUSINESS_KEY_ like ?",
                String.class, PREFIX + "%");
        historicIds.forEach(this::trackProcessInstance);
        runtimeIds.forEach(this::trackProcessInstance);
    }

    /**
     * 按新增审批能力的精确主键和外键顺序回收草稿、事件、SLA、策略与催办数据。
     *
     * @return void，无返回值；只删除本 fixture 登记对象并逐类执行零残留断言
     */
    private void cleanupExtendedApprovalFixtures()
    {
        // 恢复“数据库已提交但 HTTP 响应断言前中断”的新增对象，查询均受本轮稳定前缀或实例集合约束。
        processDraftIds.addAll(jdbcTemplate.queryForList(
                "select draft_id from wf_process_draft where business_key like ? "
                        + "and owner_user_id in (" + placeholders(roleUserIds.size()) + ")",
                String.class, concatParameters(PREFIX + "%", roleUserIds.values())));
        bpmnEventCodeIds.addAll(jdbcTemplate.queryForList(
                "select event_code_id from wf_bpmn_event_code where event_code like ?",
                Long.class, "RBAC_" + runId.substring(0, 12).toUpperCase() + "_%"));
        businessCalendarIds.addAll(jdbcTemplate.queryForList(
                "select calendar_id from wf_business_calendar where calendar_key like ?",
                Long.class, "RBAC_" + runId.substring(0, 10).toUpperCase() + "_%"));
        // 恢复范围必须带本轮 runId 标记，避免测试中断后误删共享库中的既有正式策略。
        List<Object> policyRecoveryParameters = new ArrayList<>();
        policyRecoveryParameters.add(START_KEY);
        policyRecoveryParameters.add(notificationPolicyTitleTemplate());
        policyRecoveryParameters.add(notificationPolicyContentTemplate());
        roleUserIds.values().stream().map(String::valueOf)
                .forEach(policyRecoveryParameters::add);
        notificationPolicyIds.addAll(jdbcTemplate.queryForList(
                "select policy_id from wf_notification_policy "
                        + "where scope_type = 'PROCESS' and process_definition_key = ? "
                        + "and task_definition_key is null "
                        + "and event_type in ('COPY_CREATED','PROCESS_COMPLETED') "
                        + "and recipient_rules = 'INITIATOR' and channels = 'INBOX' "
                        + "and title_template = ? and content_template = ? "
                        + "and create_by in (" + placeholders(roleUserIds.size()) + ")",
                Long.class, policyRecoveryParameters.toArray()));
        if (!processInstanceIds.isEmpty())
        {
            Object[] instanceIds = processInstanceIds.toArray();
            String instancePlaceholders = placeholders(processInstanceIds.size());
            bpmnEventAuditIds.addAll(jdbcTemplate.queryForList(
                    "select audit_id from wf_bpmn_event_audit where process_instance_id in ("
                            + instancePlaceholders + ")",
                    Long.class, instanceIds));
            taskSlaExecutionIds.addAll(jdbcTemplate.queryForList(
                    "select sla_execution_id from wf_task_sla_execution "
                            + "where process_instance_id in (" + instancePlaceholders + ")",
                    Long.class, instanceIds));
        }

        if (!processInstanceIds.isEmpty())
        {
            jdbcTemplate.update("delete from wf_notification_urge_audit "
                            + "where process_instance_id in ("
                            + placeholders(processInstanceIds.size()) + ")",
                    processInstanceIds.toArray());
        }
        if (!notificationPolicyIds.isEmpty())
        {
            jdbcTemplate.update("delete from wf_notification_policy where policy_id in ("
                            + placeholders(notificationPolicyIds.size()) + ")",
                    notificationPolicyIds.toArray());
        }
        if (!bpmnEventAuditIds.isEmpty())
        {
            String auditPlaceholders = placeholders(bpmnEventAuditIds.size());
            Object[] auditIds = bpmnEventAuditIds.toArray();
            jdbcTemplate.update("delete from wf_bpmn_event_notification "
                    + "where audit_id in (" + auditPlaceholders + ")", auditIds);
            jdbcTemplate.update("delete from wf_bpmn_event_audit where audit_id in ("
                    + auditPlaceholders + ")", auditIds);
        }
        if (!bpmnEventCodeIds.isEmpty())
        {
            jdbcTemplate.update("delete from wf_bpmn_event_code where event_code_id in ("
                            + placeholders(bpmnEventCodeIds.size()) + ")",
                    bpmnEventCodeIds.toArray());
        }
        if (!taskSlaExecutionIds.isEmpty())
        {
            String executionPlaceholders = placeholders(taskSlaExecutionIds.size());
            Object[] executionIds = taskSlaExecutionIds.toArray();
            jdbcTemplate.update("delete from wf_task_sla_notification where audit_id in ("
                    + "select audit_id from wf_task_sla_audit where sla_execution_id in ("
                    + executionPlaceholders + "))", executionIds);
            jdbcTemplate.update("delete from wf_task_sla_audit where sla_execution_id in ("
                    + executionPlaceholders + ")", executionIds);
            jdbcTemplate.update("delete from wf_task_sla_execution where sla_execution_id in ("
                    + executionPlaceholders + ")", executionIds);
        }
        if (!businessCalendarIds.isEmpty())
        {
            String calendarPlaceholders = placeholders(businessCalendarIds.size());
            Object[] calendarIds = businessCalendarIds.toArray();
            jdbcTemplate.update("delete from wf_business_calendar_day where calendar_id in ("
                    + calendarPlaceholders + ")", calendarIds);
            jdbcTemplate.update("delete from wf_business_calendar where calendar_id in ("
                    + calendarPlaceholders + ")", calendarIds);
        }
        if (!processDraftIds.isEmpty())
        {
            String draftPlaceholders = placeholders(processDraftIds.size());
            Object[] draftIds = processDraftIds.toArray();
            jdbcTemplate.update("delete from wf_process_draft_audit where draft_id in ("
                    + draftPlaceholders + ")", draftIds);
            jdbcTemplate.update("delete from wf_process_draft where draft_id in ("
                    + draftPlaceholders + ")", draftIds);
        }

        assertTrackedRowsRemoved("wf_notification_policy", "policy_id",
                notificationPolicyIds, "ALLOW fixture 不得残留审批通知策略");
        assertTrackedRowsRemoved("wf_bpmn_event_audit", "audit_id",
                bpmnEventAuditIds, "ALLOW fixture 不得残留 BPMN 事件审计");
        assertTrackedRowsRemoved("wf_bpmn_event_code", "event_code_id",
                bpmnEventCodeIds, "ALLOW fixture 不得残留 BPMN 事件目录");
        assertTrackedRowsRemoved("wf_task_sla_execution", "sla_execution_id",
                taskSlaExecutionIds, "ALLOW fixture 不得残留 SLA 执行");
        assertTrackedRowsRemoved("wf_business_calendar", "calendar_id",
                businessCalendarIds, "ALLOW fixture 不得残留 SLA 日历");
        assertTrackedRowsRemoved("wf_process_draft", "draft_id",
                processDraftIds, "ALLOW fixture 不得残留申请草稿");
        if (!processInstanceIds.isEmpty())
        {
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_notification_urge_audit "
                            + "where process_instance_id in ("
                            + placeholders(processInstanceIds.size()) + ")",
                    Long.class, processInstanceIds.toArray()))
                    .as("ALLOW fixture 不得残留人工催办审计").isZero();
        }
    }

    /**
     * 对一组已登记正式主键执行通用零残留断言。
     *
     * @param table String，固定测试代码内正式表名
     * @param column String，固定测试代码内主键列名
     * @param ids Set，当前 fixture 精确主键集合
     * @param description String，断言失败时的中文业务描述
     * @return void，无返回值；空集合不执行查询
     */
    private void assertTrackedRowsRemoved(String table, String column,
            Set<?> ids, String description)
    {
        if (ids.isEmpty())
        {
            return;
        }
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where " + column + " in ("
                        + placeholders(ids.size()) + ")",
                Long.class, ids.toArray())).as(description).isZero();
    }

    /**
     * 按本 fixture 实例反查通知 outbox，并依照外键顺序精确删除审计、站内信和 outbox。
     *
     * @return void，无返回值；不会按时间或全表删除，任一已跟踪通知事实残留即失败
     */
    private void cleanupTrackedNotifications()
    {
        if (processInstanceIds.isEmpty())
        {
            return;
        }
        notificationOutboxIds.addAll(jdbcTemplate.queryForList(
                "select outbox_id from wf_notification_outbox where process_instance_id in ("
                        + placeholders(processInstanceIds.size()) + ") order by outbox_id",
                Long.class, processInstanceIds.toArray()));
        if (notificationOutboxIds.isEmpty())
        {
            return;
        }

        String outboxPlaceholders = placeholders(notificationOutboxIds.size());
        Object[] outboxIds = notificationOutboxIds.toArray();
        // 三步均限定已跟踪 outbox 主键；子表先于父表，保留验收库预存的取消通知及其审计。
        jdbcTemplate.update("delete from wf_notification_delivery_audit where outbox_id in ("
                + outboxPlaceholders + ")", outboxIds);
        jdbcTemplate.update("delete from wf_notification_inbox where outbox_id in ("
                + outboxPlaceholders + ")", outboxIds);
        jdbcTemplate.update("delete from wf_notification_outbox where outbox_id in ("
                + outboxPlaceholders + ")", outboxIds);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_notification_delivery_audit where outbox_id in ("
                        + outboxPlaceholders + ")",
                Long.class, outboxIds)).as("ALLOW fixture 不得残留通知投递审计").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_notification_inbox where outbox_id in ("
                        + outboxPlaceholders + ")",
                Long.class, outboxIds)).as("ALLOW fixture 不得残留通知站内信").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_notification_outbox where outbox_id in ("
                        + outboxPlaceholders + ")",
                Long.class, outboxIds)).as("ALLOW fixture 不得残留通知 outbox").isZero();
    }

    /**
     * 精确清理本类创建的审批扩展数据、附件、抄送、部署、模型、表单、配额和日志。
     *
     * @return void，无返回值；清理后仍有任一精确对象残留即失败
     */
    void cleanup()
    {
        if (cleaned)
        {
            return;
        }

        // 先恢复成功创建但响应断言前中断的实例，再按实例精确清理通知；部署删除后无法可靠恢复已删历史实例。
        recoverProcessInstanceIds();
        cleanupExtendedApprovalFixtures();
        cleanupTrackedNotifications();

        // 即使请求已落库但后续响应或审计断言失败，也按测试专用文件名前缀和五角色边界恢复清理清单。
        List<Map<String, Object>> recoverableAttachments = jdbcTemplate.queryForList(
                "select attachment_id, storage_key from wf_attachment "
                        + "where original_name like ? and owner_user_id in ("
                        + placeholders(roleUserIds.size()) + ")",
                concatParameters(PREFIX + "%", roleUserIds.values()));
        for (Map<String, Object> attachment : recoverableAttachments)
        {
            attachmentIds.add(String.valueOf(attachment.get("attachment_id")));
            attachmentStorageKeys.add(String.valueOf(attachment.get("storage_key")));
        }

        // 先删除物理附件，再删除元数据，避免数据库清理后失去受控 storage_key。
        for (String storageKey : List.copyOf(attachmentStorageKeys))
        {
            attachmentStorage.delete(storageKey);
        }
        for (String attachmentId : List.copyOf(attachmentIds))
        {
            jdbcTemplate.update("delete from wf_attachment where attachment_id = ?",
                    attachmentId);
        }
        for (Long copyId : List.copyOf(copyIds))
        {
            jdbcTemplate.update("delete from wf_copy where copy_id = ?", copyId);
        }

        // Flowable 部署级联清理运行、历史和定义；业务快照必须单独按部署主键删除。
        List<String> deployments = new ArrayList<>(deploymentIds);
        deployments.sort(Comparator.comparing(id -> id.equals(mainDeploymentId) ? 1 : 0));
        for (String deploymentId : deployments)
        {
            // 参与者解析审计及规则快照不受 Flowable 部署表外键管理，必须按本 fixture 部署主键先行清理。
            jdbcTemplate.update("delete from wf_participant_resolution_audit where deploy_id = ?",
                    deploymentId);
            jdbcTemplate.update("delete from wf_deploy_participant_rule where deploy_id = ?",
                    deploymentId);
            jdbcTemplate.update("delete from wf_deploy_form where deploy_id = ?",
                    deploymentId);
            if (repositoryService.createDeploymentQuery()
                    .deploymentId(deploymentId).count() > 0L)
            {
                repositoryService.deleteDeployment(deploymentId, true);
            }
        }
        for (String modelId : List.copyOf(modelIds))
        {
            if (repositoryService.createModelQuery().modelId(modelId).count() > 0L)
            {
                repositoryService.deleteModel(modelId);
            }
        }
        // 幂等记录是模型的审计软引用，不随 ACT_RE_MODEL 级联；fixture 必须按已登记 UUID 精确回收。
        for (String requestId : List.copyOf(modelSaveRequestIds))
        {
            jdbcTemplate.update(
                    "delete from wf_model_save_idempotency where request_id = ?",
                    requestId);
        }
        for (Long formId : List.copyOf(formIds))
        {
            jdbcTemplate.update("delete from wf_form where form_id = ?", formId);
        }
        for (Long categoryId : List.copyOf(categoryIds))
        {
            jdbcTemplate.update("delete from wf_category where category_id = ?", categoryId);
        }

        // 上传首次为用户建立的 guard 行不属于账号主数据，仅删除测试前不存在的五角色行。
        for (Long userId : roleUserIds.values())
        {
            if (!initialQuotaGuardOwners.contains(userId))
            {
                jdbcTemplate.update(
                        "delete from wf_attachment_quota_guard where owner_user_id = ?",
                        userId);
            }
        }

        collectFixtureOperationLogs();
        for (Long operationLogId : List.copyOf(operationLogIds))
        {
            jdbcTemplate.update("delete from sys_oper_log where oper_id = ?",
                    operationLogId);
        }

        assertThat(repositoryService.createDeploymentQuery()
                .deploymentNameLike(PREFIX + "%").count()).isZero();
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKeyLike(PREFIX + "%").count()).isZero();
        assertThat(historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKeyLike(PREFIX + "%").count()).isZero();
        if (!attachmentIds.isEmpty())
        {
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_attachment where attachment_id in ("
                            + placeholders(attachmentIds.size()) + ")",
                    Long.class, attachmentIds.toArray())).isZero();
        }
        if (!modelSaveRequestIds.isEmpty())
        {
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_model_save_idempotency where request_id in ("
                            + placeholders(modelSaveRequestIds.size()) + ")",
                    Long.class, modelSaveRequestIds.toArray())).isZero();
        }
        if (!processInstanceIds.isEmpty())
        {
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_notification_outbox where process_instance_id in ("
                            + placeholders(processInstanceIds.size()) + ")",
                    Long.class, processInstanceIds.toArray()))
                    .as("ALLOW fixture 流程实例不得残留通知 outbox")
                    .isZero();
        }
        if (!deploymentIds.isEmpty())
        {
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_participant_resolution_audit where deploy_id in ("
                            + placeholders(deploymentIds.size()) + ")",
                    Long.class, deploymentIds.toArray()))
                    .as("ALLOW fixture 部署不得残留参与者解析审计")
                    .isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_deploy_participant_rule where deploy_id in ("
                            + placeholders(deploymentIds.size()) + ")",
                    Long.class, deploymentIds.toArray()))
                    .as("ALLOW fixture 部署不得残留参与者规则快照")
                    .isZero();
        }
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_oper_log where oper_id > ? "
                        + "and oper_url like '/workflow/%' and oper_name in ("
                        + placeholders(roleUsernames.size()) + ")",
                Long.class, concatParameters(operationLogFloor,
                        roleUsernames.values()))).isZero();

        deleteBoundedProfileDirectory();
        cleaned = true;
    }

    /**
     * 通过真实 HTTP 创建 Flowable 模型，并登记模型及操作日志主键。
     *
     * @param roleKey String，模型创建角色
     * @param label String，测试模型中文语义标签
     * @return ModelFixture，模型 ID、key 和名称
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private ModelFixture createModel(String roleKey, String label)
            throws IOException, InterruptedException
    {
        String key = uniqueCode("model");
        String name = uniqueName(label);
        JsonNode body = callJsonRaw(roleKey, "/workflow/model", "POST",
                json(Map.of("modelName", name, "modelKey", key,
                        "category", commonCategoryCode, "description", "RBAC真实模型",
                        "formType", 0, "formId", commonFormId)), true);
        String id = body.path("data").path("modelId").asText();
        assertThat(id).isNotBlank();
        modelIds.add(id);
        return new ModelFixture(id, key, name);
    }

    /**
     * 使用模型现有安全 BPMN 创建真实新版本。
     *
     * @param roleKey String，保存角色
     * @param original ModelFixture，原模型
     * @return ModelFixture，新版本模型
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private ModelFixture saveNewModelVersion(String roleKey, ModelFixture original)
            throws IOException, InterruptedException
    {
        JsonNode body = callJsonRaw(roleKey, "/workflow/model/save", "POST",
                json(Map.of("requestId", newModelSaveRequestId(),
                        "modelId", original.id(),
                        "bpmnXml", deployableModelXml(roleKey, original),
                        "newVersion", true)), true);
        String id = body.path("data").path("modelId").asText();
        modelIds.add(id);
        return new ModelFixture(id, original.key(), original.name());
    }

    /**
     * 通过 Flowable BPMN 结构化模型补齐真实办理人和任务表单，再经正式保存 API 持久化可部署版本。
     *
     * @param roleKey String，模型创建、保存和部署请求使用的角色
     * @param fixture ModelFixture，待补齐的真实模型
     * @return void，无返回值；模型结构、保存响应或审计异常时断言失败
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private void saveDeployableModel(String roleKey, ModelFixture fixture)
            throws IOException, InterruptedException
    {
        String deployableXml = deployableModelXml(roleKey, fixture);
        JsonNode body = callJsonRaw(roleKey, "/workflow/model/save", "POST",
                json(Map.of("requestId", newModelSaveRequestId(),
                        "modelId", fixture.id(), "bpmnXml", deployableXml,
                        "newVersion", false)), true);
        assertThat(body.path("data").path("modelId").asText()).isEqualTo(fixture.id());
    }

    /**
     * 将模型编辑器中的作者 XML 结构化补齐为通过正式身份和表单门禁的 BPMN。
     *
     * @param roleKey String，当前保存角色；用于选择具备办理资格的真实审批角色
     * @param fixture ModelFixture，待转换模型的 ID、key 和名称
     * @return String，保留原扩展元素并补齐所有 UserTask 办理人与正式表单键的 UTF-8 XML
     */
    private String deployableModelXml(String roleKey, ModelFixture fixture)
    {
        byte[] source = repositoryService.getModelEditorSource(fixture.id());
        assertThat(source).isNotNull().isNotEmpty();

        // 通过 Flowable 公共模型 API 修改节点，避免字符串替换破坏命名空间、监听器或 BPMN DI。
        BpmnXMLConverter converter = new BpmnXMLConverter();
        BpmnModel bpmnModel = converter.convertToBpmnModel(
                () -> new ByteArrayInputStream(source), true, true);
        assertThat(bpmnModel.getMainProcess().getId()).isEqualTo(fixture.key());
        List<UserTask> userTasks = bpmnModel.getMainProcess()
                .findFlowElementsOfType(UserTask.class, true);
        assertThat(userTasks).as("模型必须包含可验证的 UserTask").isNotEmpty();

        // 设计员可以保存和部署模型但不一定具备办理资格，因此统一绑定真实审批角色。
        String assigneeRole = approvalAssigneeRole(roleKey);
        String assigneeUserId = String.valueOf(roleUserIds.get(assigneeRole));
        String formKey = "key_" + commonFormId;
        for (UserTask userTask : userTasks)
        {
            userTask.setAssignee(assigneeUserId);
            userTask.setFormKey(formKey);
        }

        return new String(converter.convertToXML(bpmnModel,
                StandardCharsets.UTF_8.name()), StandardCharsets.UTF_8);
    }

    /**
     * 生成并登记本轮真实模型保存请求的 UUID，确保持久化幂等记录可按请求边界精确清理。
     *
     * @return String，符合后端保存契约的随机 UUID
     */
    private String newModelSaveRequestId()
    {
        // requestId 同时是业务幂等键和测试清理边界，必须在发出 HTTP 请求前登记。
        String requestId = UUID.randomUUID().toString();
        modelSaveRequestIds.add(requestId);
        return requestId;
    }

    /**
     * 读取 Flowable 模型编辑器真实 BPMN 字节。
     *
     * @param modelId String，模型主键
     * @return String，UTF-8 BPMN XML
     */
    private String modelXml(String modelId)
    {
        byte[] source = repositoryService.getModelEditorSource(modelId);
        assertThat(source).isNotNull().isNotEmpty();
        return new String(source, StandardCharsets.UTF_8);
    }

    /**
     * 部署一份临时同资源 BPMN，供状态和删除入口使用；调用方必须在单元结束时删除。
     *
     * @return Deployment，已登记到精确清理集合的部署
     */
    private Deployment deployTemporaryBpmn()
    {
        Deployment deployment = repositoryService.createDeployment()
                .name(PREFIX + "temporary-" + sequence.incrementAndGet())
                .category(commonCategoryCode)
                .addClasspathResource(BPMN_RESOURCE)
                .deploy();
        deploymentIds.add(deployment.getId());
        return deployment;
    }

    /**
     * 创建真实发起实例，并把活动任务分配给具备实时审批资格的预登记角色。
     *
     * @param starterRole String，真实发起角色
     * @return ProcessFixture，保持指定发起人且任务办理人合法的运行实例
     */
    private ProcessFixture startStart(String starterRole)
    {
        String taskRole = approvalAssigneeRole(starterRole);
        return startProcess(START_KEY, starterRole,
                Map.of("reviewAssignee", String.valueOf(roleUserIds.get(taskRole)),
                        "note", "RBAC变量", "processStatus", "running"));
    }

    /**
     * 创建串行两节点实例并由服务端表达式分配两个真实办理人。
     *
     * @param starterRole String，真实发起角色
     * @param firstRole String，第一节点办理角色
     * @param secondRole String，第二节点办理角色
     * @return ProcessFixture，第一活动任务 fixture
     */
    private ProcessFixture startSerial(String starterRole, String firstRole,
            String secondRole)
    {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("firstAssignee", String.valueOf(roleUserIds.get(firstRole)));
        variables.put("secondAssignee", String.valueOf(roleUserIds.get(secondRole)));
        variables.put("note", "RBAC变量");
        variables.put("processStatus", "running");
        return startProcess(SERIAL_KEY, starterRole, variables);
    }

    /**
     * 创建直接候选用户任务，任务保持未认领。
     *
     * @param starterRole String，真实发起角色
     * @param candidateRole String，直接候选角色
     * @return ProcessFixture，候选任务 fixture
     */
    private ProcessFixture startClaim(String starterRole, String candidateRole)
    {
        return startProcess(CLAIM_KEY, starterRole,
                Map.of("candidateUser", String.valueOf(roleUserIds.get(candidateRole)),
                        "note", "RBAC变量", "processStatus", "running"));
    }

    /**
     * 通过主部署定义 ID 启动实例，避免临时同 key 部署改变 fixture 语义。
     *
     * @param processKey String，主部署流程 key
     * @param starterRole String，真实发起角色
     * @param variables Map，服务端表达式和业务变量
     * @return ProcessFixture，首个活动任务
     */
    private ProcessFixture startProcess(String processKey, String starterRole,
            Map<String, Object> variables)
    {
        String businessKey = uniqueBusinessKey(processKey);
        identityService.setAuthenticatedUserId(
                String.valueOf(roleUserIds.get(starterRole)));
        try
        {
            ProcessInstance instance = runtimeService.startProcessInstanceById(
                    definition(processKey).getId(), businessKey, variables);
            trackProcessInstance(instance.getId());
            Task task = taskService.createTaskQuery()
                    .processInstanceId(instance.getId()).active().singleResult();
            assertThat(task).isNotNull();
            return new ProcessFixture(instance.getId(), instance.getProcessDefinitionId(),
                    mainDeploymentId, businessKey, task.getId(), task.getTaskDefinitionKey());
        }
        finally
        {
            identityService.setAuthenticatedUserId(null);
        }
    }

    /**
     * 创建并完成第一节点，返回原历史任务 ID 供已办或历史变量对象授权使用。
     *
     * @param actorRole String，真实完成人角色
     * @return ProcessFixture，taskId 指向已完成第一节点
     */
    private ProcessFixture completedFirstTask(String actorRole)
    {
        ProcessFixture fixture = startSerial("workflow_starter", actorRole,
                otherRole(actorRole));
        directComplete(fixture.taskId(), actorRole);
        runtimeService.setVariable(fixture.instanceId(), "note", "RBAC变量");
        assertCompletedBy(fixture.taskId(), actorRole);
        return fixture;
    }

    /**
     * 由真实审批角色通过正式完成 API 生成不可变提交快照，供流程发起人读取历史变量。
     *
     * @return ProcessFixture，taskId 指向已由审批人完成且发起人可读的第一节点
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private ProcessFixture completedTaskForStarterVariableRead()
            throws IOException, InterruptedException
    {
        String actorRole = "workflow_approver";
        ProcessFixture fixture = startSerial("workflow_starter", actorRole,
                "workflow_admin");
        callJsonRaw(actorRole, "/workflow/task/complete", "POST",
                completeBody(fixture.taskId(), "RBAC历史变量提交", List.of(), List.of()), true);
        assertCompletedBy(fixture.taskId(), actorRole);
        return fixture;
    }

    /**
     * 创建由同一角色完成第一节点并正在办理第二节点的可退回状态。
     *
     * @param actorRole String，第一、第二节点办理角色
     * @return ProcessFixture，taskId 指向活动第二节点
     */
    private ProcessFixture secondTaskReady(String actorRole)
    {
        ProcessFixture first = startSerial("workflow_starter", actorRole, actorRole);
        directComplete(first.taskId(), actorRole);
        Task second = taskService.createTaskQuery()
                .processInstanceId(first.instanceId())
                .taskDefinitionKey("secondReview").singleResult();
        assertThat(second).isNotNull();
        return new ProcessFixture(first.instanceId(), first.definitionId(),
                first.deploymentId(), first.businessKey(), second.getId(),
                second.getTaskDefinitionKey());
    }

    /**
     * 完成多实例前置任务，使用真实 HTTP 写入单个初始成员并返回当前角色的会签任务。
     *
     * @param actorRole String，前置任务及一个会签实例的办理角色
     * @return MultiFixture，活动会签任务和另一名具备正式审批资格的已登记用户
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private MultiFixture startMulti(String actorRole)
            throws IOException, InterruptedException
    {
        ProcessFixture source = startProcess(MULTI_KEY, "workflow_starter",
                Map.of("sourceAssignee", String.valueOf(roleUserIds.get(actorRole)),
                        "note", "RBAC变量", "processStatus", "running"));
        Long actorUserId = roleUserIds.get(actorRole);
        // RBAC 环境只保证两名审批角色；先建立单成员多实例，再用另一名审批角色验证真实加签。
        Long addUserId = roleUserIds.get(approvalPeerRole(actorRole));
        callJsonRaw(actorRole, "/workflow/task/complete", "POST",
                completeBody(source.taskId(), "RBAC进入会签", List.of(),
                        List.of(actorUserId)), true);
        Task actorTask = taskService.createTaskQuery()
                .processInstanceId(source.instanceId())
                .taskDefinitionKey("multiReview")
                .taskAssignee(String.valueOf(actorUserId)).singleResult();
        assertThat(actorTask).isNotNull();
        return new MultiFixture(source.instanceId(), actorTask.getId(), actorUserId,
                addUserId);
    }

    /**
     * 选择与当前动态多实例办理角色互补的正式审批角色。
     *
     * @param actorRole String，当前办理角色，只允许 workflow_admin 或 workflow_approver
     * @return String，另一个具备 workflow:process:approval 权限的五角色 key
     */
    private String approvalPeerRole(String actorRole)
    {
        return switch (actorRole)
        {
            case "workflow_admin" -> "workflow_approver";
            case "workflow_approver" -> "workflow_admin";
            default -> throw new AssertionError("动态多实例 ALLOW fixture 仅支持正式审批角色");
        };
    }

    /**
     * 直接通过 Flowable 公共 API 完成 fixture 前置任务，并冻结 completedBy。
     *
     * @param taskId String，活动任务主键
     * @param actorRole String，真实完成角色
     * @return void，无返回值
     */
    private void directComplete(String taskId, String actorRole)
    {
        // 显式传入真实办理人，确保 Flowable 8 将 completedBy 持久化到历史任务。
        String actorUserId = String.valueOf(roleUserIds.get(actorRole));
        identityService.setAuthenticatedUserId(actorUserId);
        try
        {
            taskService.complete(taskId, actorUserId, Map.of("note", "RBAC变量"));
        }
        finally
        {
            identityService.setAuthenticatedUserId(null);
        }
    }

    /**
     * 构造完整任务完成 JSON，请求中只保留正式字段。
     *
     * @param taskId String，活动任务主键
     * @param comment String，审批意见
     * @param copyUserIds List，抄送用户
     * @param nextUserIds List，动态下一办理人
     * @return String，UTF-8 JSON 正文
     */
    private String completeBody(String taskId, String comment,
            List<Long> copyUserIds, List<Long> nextUserIds)
    {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", taskId);
        body.put("comment", comment);
        body.put("variables", Map.of("note", "RBAC变量"));
        body.put("copyUserIds", copyUserIds);
        body.put("nextUserIds", nextUserIds);
        return json(body);
    }

    /**
     * 上传真实 multipart 附件并登记数据库主键和物理 storage_key。
     *
     * @param roleKey String，上传所有者角色
     * @param bytes byte[]，真实文件正文
     * @param targetCell boolean，是否为 upload 矩阵本身，仅用于生成可定位文件名
     * @return UploadedAttachment，正式附件 ID、storage_key 和原始字节
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private UploadedAttachment upload(String roleKey, byte[] bytes, boolean targetCell)
            throws IOException, InterruptedException
    {
        String boundary = "WorkflowRbacAllow" + sequence.incrementAndGet();
        String filename = PREFIX + (targetCell ? "target-" : "fixture-")
                + sequence.incrementAndGet() + ".txt";
        // 防重复提交切面会纳入普通表单字段；每次上传使用唯一字段值以区分真实请求。
        String fieldName = "evidence_" + sequence.incrementAndGet();
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"fieldName\"\r\n\r\n"
                + fieldName + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\n"
                + "Content-Type: text/plain\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        byte[] multipart = concatenate(head.getBytes(StandardCharsets.US_ASCII),
                bytes, tail.getBytes(StandardCharsets.US_ASCII));
        long beforeLogId = maxOperationLogId();
        HttpResponse<byte[]> response = sendRequest(roleKey,
                "/workflow/attachment", "POST", multipart,
                "multipart/form-data; boundary=" + boundary);
        JsonNode body = parseSuccessfulJson(response);
        String attachmentId = body.path("data").path("attachmentId").asText();
        assertThat(attachmentId).isNotBlank();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select storage_key, owner_user_id, file_size, attachment_status "
                        + "from wf_attachment where attachment_id = ?", attachmentId);
        assertThat(((Number) row.get("owner_user_id")).longValue())
                .isEqualTo(roleUserIds.get(roleKey));
        assertThat(((Number) row.get("file_size")).longValue()).isEqualTo(bytes.length);
        assertThat(row.get("attachment_status")).isEqualTo("TEMP");
        String storageKey = String.valueOf(row.get("storage_key"));
        attachmentIds.add(attachmentId);
        attachmentStorageKeys.add(storageKey);
        // 先登记正式落库和落盘对象，再等待异步审计；审计失败时 AfterAll 仍能精确清理附件。
        assertSuccessfulAudit(roleKey, "/workflow/attachment", beforeLogId);
        return new UploadedAttachment(attachmentId, storageKey, bytes.clone());
    }

    /**
     * 为当前角色创建可读取附件；审计角色通过正式抄送关系读取他人已绑定附件。
     *
     * @param roleKey String，元数据或下载调用角色
     * @param bytes byte[]，附件正文
     * @return UploadedAttachment，可通过对象授权读取的附件
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private UploadedAttachment readableAttachment(String roleKey, byte[] bytes)
            throws IOException, InterruptedException
    {
        if (!"workflow_auditor".equals(roleKey))
        {
            return upload(roleKey, bytes, false);
        }
        UploadedAttachment attachment = upload("workflow_starter", bytes, false);
        ProcessFixture process = startStart("workflow_starter");
        int updated = jdbcTemplate.update(
                "update wf_attachment set attachment_status = 'BOUND', "
                        + "process_instance_id = ?, node_key = 'start', "
                        + "bound_time = current_timestamp(3), update_time = current_timestamp(3) "
                        + "where attachment_id = ? and attachment_status = 'TEMP'",
                process.instanceId(), attachment.id());
        assertThat(updated).isEqualTo(1);
        insertCopy(process, roleKey);
        return attachment;
    }

    /**
     * 插入一个正式有效分类并返回生成主键。
     *
     * @param label String，分类名称语义
     * @return CategoryRow，分类主键、名称和唯一编码
     */
    private CategoryRow insertCategory(String label)
    {
        String name = uniqueName(label);
        String code = uniqueCode("category");
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        int rows = jdbcTemplate.update(connection ->
        {
            PreparedStatement statement = connection.prepareStatement(
                    "insert into wf_category "
                            + "(category_name, code, create_by, remark, del_flag) "
                            + "values (?, ?, 'workflow-rbac-it', 'RBAC真实fixture', '0')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name);
            statement.setString(2, code);
            return statement;
        }, keyHolder);
        assertThat(rows).isEqualTo(1);
        long id = keyHolder.getKey().longValue();
        categoryIds.add(id);
        return new CategoryRow(id, name, code);
    }

    /**
     * 插入一个正式安全表单并返回生成主键。
     *
     * @param label String，表单名称语义
     * @return FormRow，表单主键和名称
     */
    private FormRow insertForm(String label)
    {
        String name = uniqueName(label);
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        int rows = jdbcTemplate.update(connection ->
        {
            PreparedStatement statement = connection.prepareStatement(
                    "insert into wf_form "
                            + "(form_name, content, create_by, remark, del_flag) "
                            + "values (?, ?, 'workflow-rbac-it', 'RBAC真实fixture', '0')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name);
            statement.setString(2, FORM_CONTENT);
            return statement;
        }, keyHolder);
        assertThat(rows).isEqualTo(1);
        long id = keyHolder.getKey().longValue();
        formIds.add(id);
        return new FormRow(id, name);
    }

    /**
     * 为主部署写入一个 BPMN 节点对应的不可变表单快照。
     *
     * @param formKey String，BPMN formKey
     * @param nodeKey String，BPMN 节点 key
     * @param nodeName String，节点显示名称
     * @param content String，该节点部署时固化且已经过正式模板门禁的表单 schema
     * @return void，无返回值
     */
    private void insertSnapshot(String formKey, String nodeKey, String nodeName,
            String content)
    {
        int rows = jdbcTemplate.update(
                "insert into wf_deploy_form "
                        + "(deploy_id, form_id, form_key, node_key, form_name, node_name, "
                        + "content, create_by, del_flag) values (?, ?, ?, ?, ?, ?, ?, ?, '0')",
                mainDeploymentId, commonFormId, formKey, nodeKey,
                "RBAC部署表单", nodeName, content, "workflow-rbac-it");
        assertThat(rows).isEqualTo(1);
    }

    /**
     * 为指定实例与接收角色插入正式 wf_copy 对象授权记录。
     *
     * @param fixture ProcessFixture，抄送关联流程
     * @param recipientRole String，接收角色
     * @return CopyRow，正式抄送主键
     */
    private CopyRow insertCopy(ProcessFixture fixture, String recipientRole)
    {
        HistoricProcessInstance historic = historyService
                .createHistoricProcessInstanceQuery()
                .processInstanceId(fixture.instanceId()).singleResult();
        assertThat(historic).isNotNull();
        long originatorId = Long.parseLong(historic.getStartUserId());
        String originatorName = jdbcTemplate.queryForObject(
                "select user_name from sys_user where user_id = ?",
                String.class, originatorId);
        String eventId = PREFIX + "copy-" + sequence.incrementAndGet();
        String title = uniqueName("RBAC抄送");
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        int rows = jdbcTemplate.update(connection ->
        {
            PreparedStatement statement = connection.prepareStatement(
                    "insert into wf_copy "
                            + "(copy_event_id, title, process_id, process_name, category_id, "
                            + "deployment_id, instance_id, task_id, user_id, originator_id, "
                            + "originator_name, create_by, del_flag) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'workflow-rbac-it', '0')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, eventId);
            statement.setString(2, title);
            statement.setString(3, fixture.definitionId());
            statement.setString(4, definitionById(fixture.definitionId()).getName());
            statement.setString(5, commonCategoryCode);
            statement.setString(6, fixture.deploymentId());
            statement.setString(7, fixture.instanceId());
            statement.setString(8, fixture.taskId());
            statement.setLong(9, roleUserIds.get(recipientRole));
            statement.setLong(10, originatorId);
            statement.setString(11, originatorName);
            return statement;
        }, keyHolder);
        assertThat(rows).isEqualTo(1);
        long id = keyHolder.getKey().longValue();
        copyIds.add(id);
        return new CopyRow(id, title);
    }

    /**
     * 调用 JSON 矩阵入口并核对传输、业务状态及必要操作日志。
     *
     * @param roleKey String，当前角色
     * @param endpoint Endpoint，正式矩阵入口
     * @param path String，已替换路径变量和查询条件的相对 URL
     * @param body String，可为空的 JSON 正文
     * @return JsonNode，成功 AjaxResult
     * @throws IOException HTTP 或 JSON 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private JsonNode callJson(String roleKey, Endpoint endpoint, String path,
            String body) throws IOException, InterruptedException
    {
        return callJsonRaw(roleKey, path, endpoint.httpMethod(), body,
                AUDITED_ENDPOINTS.contains(endpoint.key()));
    }

    /**
     * 调用 fixture 准备所需的真实 JSON 接口。
     *
     * @param roleKey String，当前角色
     * @param path String，相对 URL
     * @param method String，HTTP 动词
     * @param body String，可为空的 JSON 正文
     * @param audited boolean，是否要求成功操作日志
     * @return JsonNode，成功 AjaxResult
     * @throws IOException HTTP 或 JSON 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private JsonNode callJsonRaw(String roleKey, String path, String method,
            String body, boolean audited) throws IOException, InterruptedException
    {
        byte[] bytes = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        long beforeLogId = maxOperationLogId();
        HttpResponse<byte[]> response = sendRequest(roleKey, path, method, bytes,
                body == null ? null : "application/json; charset=UTF-8");
        // 必须先解析业务响应；失败请求只能要求失败审计，不能被“缺少成功审计”遮蔽真实状态码。
        JsonNode parsedBody = parseSuccessfulJson(response);
        if (audited)
        {
            assertSuccessfulAudit(roleKey, URI.create(baseUrl() + path).getRawPath(),
                    beforeLogId);
        }
        return parsedBody;
    }

    /**
     * 调用 XLSX、PNG 或附件二进制入口并核对传输与必要操作日志。
     *
     * @param roleKey String，当前角色
     * @param endpoint Endpoint，正式矩阵入口
     * @param path String，相对 URL
     * @param body byte[]，可为空的请求体
     * @return HttpResponse，真实二进制响应
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private HttpResponse<byte[]> callBinary(String roleKey, Endpoint endpoint,
            String path, byte[] body) throws IOException, InterruptedException
    {
        long beforeLogId = maxOperationLogId();
        HttpResponse<byte[]> response = sendRequest(roleKey, path,
                endpoint.httpMethod(), body, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isNotEmpty();
        if (AUDITED_ENDPOINTS.contains(endpoint.key()))
        {
            assertSuccessfulAudit(roleKey, URI.create(baseUrl() + path).getRawPath(),
                    beforeLogId);
        }
        return response;
    }

    /**
     * 发送请求并在需要时等待 @Log 异步持久化成功审计。
     *
     * @param roleKey String，当前角色
     * @param path String，相对 URL
     * @param method String，HTTP 动词
     * @param body byte[]，可为空请求体
     * @param contentType String，可为空 Content-Type
     * @param audited boolean，是否要求成功操作日志
     * @return HttpResponse，真实字节响应
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private HttpResponse<byte[]> sendWithAudit(String roleKey, String path,
            String method, byte[] body, String contentType, boolean audited)
            throws IOException, InterruptedException
    {
        long beforeLogId = maxOperationLogId();
        HttpResponse<byte[]> response = sendRequest(roleKey, path, method, body, contentType);
        if (audited)
        {
            // 操作日志保存 HttpServletRequest#getRequestURI() 的原始转义路径；
            // Flowable 定义 ID 含冒号，必须保留 %3A 才能与正式落库的 oper_url 精确对账。
            String auditedRequestPath = URI.create(baseUrl() + path).getRawPath();
            assertSuccessfulAudit(roleKey, auditedRequestPath, beforeLogId);
        }
        return response;
    }

    /**
     * 通过当前角色 JWT 向随机真实端口发送一次不重试请求。
     *
     * @param roleKey String，当前角色
     * @param path String，相对 URL
     * @param method String，HTTP 动词
     * @param body byte[]，可为空请求体
     * @param contentType String，可为空 Content-Type
     * @return HttpResponse，真实字节响应
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private HttpResponse<byte[]> sendRequest(String roleKey, String path,
            String method, byte[] body, String contentType)
            throws IOException, InterruptedException
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(httpTimeout)
                .header("Authorization", "Bearer " + roleTokens.get(roleKey))
                .header("Accept", "*/*")
                .header("User-Agent", "workflow-rbac-allow-it");
        if (contentType != null)
        {
            builder.header("Content-Type", contentType);
        }
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        return httpClient.send(builder.method(method, publisher).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    /**
     * 解析真实 JSON 响应并同时核对 HTTP 与 AjaxResult 成功状态。
     *
     * @param response HttpResponse&lt;byte[]&gt;，真实 HTTP 字节响应
     * @return JsonNode，业务 code 为 200 的完整 AjaxResult
     */
    private JsonNode parseSuccessfulJson(HttpResponse<byte[]> response)
    {
        int transportStatus = response.statusCode();
        if (response.body() == null || response.body().length == 0)
        {
            throw new AllowHttpResponseException(transportStatus, null,
                    "ALLOW_JSON_EMPTY_RESPONSE");
        }

        JsonNode body;
        try
        {
            body = objectMapper.readTree(response.body());
        }
        catch (JacksonException exception)
        {
            // 响应正文可能包含敏感业务数据，只报告其不是合法 JSON，不透传正文或解析异常消息。
            throw new AllowHttpResponseException(transportStatus, null,
                    "ALLOW_JSON_INVALID_RESPONSE");
        }
        Integer bodyCode = body != null && body.path("code").canConvertToInt()
                ? body.path("code").intValue() : null;
        if (transportStatus != 200)
        {
            throw new AllowHttpResponseException(transportStatus, bodyCode,
                    "ALLOW_HTTP_STATUS_REJECTED");
        }
        if (bodyCode == null)
        {
            throw new AllowHttpResponseException(transportStatus, null,
                    "ALLOW_JSON_CODE_MISSING");
        }
        if (bodyCode != 200)
        {
            throw new AllowHttpResponseException(transportStatus, bodyCode,
                    "ALLOW_BUSINESS_CODE_REJECTED");
        }
        return body;
    }

    /**
     * 核对拒绝分支的真实 HTTP 传输和 AjaxResult 业务状态。
     *
     * @param response HttpResponse&lt;byte[]&gt;，对象授权拒绝响应
     * @param expectedCode int，期望业务状态码
     * @return void，无返回值；传输或业务状态不符时断言失败
     * @throws IOException 响应不是合法 JSON 时抛出
     */
    private void assertBusinessCode(HttpResponse<byte[]> response, int expectedCode)
            throws IOException
    {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isNotEmpty();
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("code").asInt()).isEqualTo(expectedCode);
    }

    /**
     * 等待异步 @Log 落库并核对操作人、请求路径和成功状态，随后登记精确日志主键。
     *
     * @param roleKey String，发起当前操作的五角色 key
     * @param requestPath String，不含查询参数的真实请求路径
     * @param beforeLogId long，请求发送前的最大操作日志主键
     * @return void，无返回值；五秒内没有匹配成功审计时断言失败
     * @throws InterruptedException 等待异步日志期间线程被中断
     */
    private void assertSuccessfulAudit(String roleKey, String requestPath,
            long beforeLogId) throws InterruptedException
    {
        String operatorName = roleUsernames.get(roleKey);
        assertThat(operatorName).isNotBlank();
        long waitNanos = Math.min(httpTimeout.toNanos(), Duration.ofSeconds(5).toNanos());
        long deadline = System.nanoTime() + waitNanos;
        List<Long> matchingLogIds = List.of();
        do
        {
            matchingLogIds = jdbcTemplate.queryForList(
                    "select oper_id from sys_oper_log where oper_id > ? "
                            + "and oper_name = ? and oper_url = ? and status = 0 "
                            + "order by oper_id desc limit 1",
                    Long.class, beforeLogId, operatorName, requestPath);
            if (!matchingLogIds.isEmpty())
            {
                operationLogIds.add(matchingLogIds.get(0));
                return;
            }
            Thread.sleep(50L);
        }
        while (System.nanoTime() < deadline);
        assertThat(matchingLogIds)
                .as("真实工作流写操作必须持久化成功审计日志")
                .isNotEmpty();
    }

    /**
     * 读取当前操作日志最大主键，作为异步审计请求的严格下界。
     *
     * @return long，当前最大 oper_id；空表返回 0
     */
    private long maxOperationLogId()
    {
        Long maximum = jdbcTemplate.queryForObject(
                "select coalesce(max(oper_id), 0) from sys_oper_log", Long.class);
        return maximum == null ? 0L : maximum;
    }

    /**
     * 汇总本轮五角色工作流请求产生的全部操作日志主键，供最终精确清理。
     *
     * @return void，无返回值；账号映射为空时不执行动态 IN 查询
     */
    private void collectFixtureOperationLogs()
    {
        if (roleUsernames.isEmpty())
        {
            return;
        }
        List<Long> ids = jdbcTemplate.queryForList(
                "select oper_id from sys_oper_log where oper_id > ? "
                        + "and oper_url like '/workflow/%' and oper_name in ("
                        + placeholders(roleUsernames.size()) + ")",
                Long.class, concatParameters(operationLogFloor,
                        roleUsernames.values()));
        operationLogIds.addAll(ids);
    }

    /**
     * 快照当前 schema 中全部 Flowable 与 wf_* 正式表行数，证明对象拒绝零副作用。
     *
     * @return Map&lt;String, Long&gt;，表名到当前行数的不可变映射
     */
    private Map<String, Long> snapshotDomainRows()
    {
        String schema = jdbcTemplate.queryForObject("select database()", String.class);
        assertThat(schema).isNotBlank();
        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables "
                        + "where table_schema = ? and table_type = 'BASE TABLE' "
                        + "order by table_name",
                String.class, schema);
        Map<String, Long> snapshot = new LinkedHashMap<>();
        for (String table : tables)
        {
            String normalized = table.toLowerCase(java.util.Locale.ROOT);
            if (!normalized.startsWith("act_") && !normalized.startsWith("wf_"))
            {
                continue;
            }
            if (!table.matches("[A-Za-z0-9_]+"))
            {
                throw new AssertionError("information_schema 返回了非法表名");
            }
            Long count = jdbcTemplate.queryForObject(
                    "select count(*) from `" + table + "`", Long.class);
            snapshot.put(table, count == null ? 0L : count);
        }
        assertThat(snapshot.keySet()).anyMatch(name ->
                name.toLowerCase(java.util.Locale.ROOT).startsWith("act_ru_"));
        return Map.copyOf(snapshot);
    }

    /**
     * 删除测试独占 profile 目录，且在遍历前再次核对目标位于固定 target 边界内。
     *
     * @return void，无返回值；边界不符或文件删除失败时终止清理
     */
    private void deleteBoundedProfileDirectory()
    {
        Path boundedRoot = Path.of("target", "workflow-rbac")
                .toAbsolutePath().normalize();
        Path profileRoot = boundedRoot.resolve("profile").normalize();
        if (!profileRoot.startsWith(boundedRoot)
                || !boundedRoot.equals(profileRoot.getParent())
                || !"workflow-rbac".equals(String.valueOf(boundedRoot.getFileName())))
        {
            throw new AssertionError("RBAC IT profile 清理路径越界");
        }
        if (!Files.exists(profileRoot))
        {
            return;
        }
        try (var paths = Files.walk(profileRoot))
        {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
        catch (IOException exception)
        {
            throw new IllegalStateException("RBAC IT profile 清理失败", exception);
        }
    }

    /**
     * 生成参数化 IN 子句占位符，禁止空集合形成非法 SQL。
     *
     * @param count int，占位符数量，必须大于零
     * @return String，以逗号分隔的问号占位符
     */
    private String placeholders(int count)
    {
        if (count <= 0)
        {
            throw new IllegalArgumentException("IN 子句占位符数量必须大于零");
        }
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    /**
     * 合并固定首参数和动态参数集合，保持 JdbcTemplate 参数顺序稳定。
     *
     * @param first Object，SQL 的第一个固定参数
     * @param remaining Iterable&lt;?&gt;，其余动态参数
     * @return Object[]，可直接传给 JdbcTemplate 的参数数组
     */
    private Object[] concatParameters(Object first, Iterable<?> remaining)
    {
        List<Object> parameters = new ArrayList<>();
        parameters.add(first);
        remaining.forEach(parameters::add);
        return parameters.toArray();
    }

    /**
     * 生成带本轮唯一标记的通知策略标题模板，供创建、恢复和清理使用同一边界。
     *
     * @return String，包含完整 runId 和正式流程名称变量的标题模板
     */
    private String notificationPolicyTitleTemplate()
    {
        return "RBAC通知[" + runId + "]：{{processName}}";
    }

    /**
     * 生成带本轮唯一标记的通知策略正文模板，防止恢复查询命中既有正式策略。
     *
     * @return String，包含完整 runId 和正式流程名称变量的正文模板
     */
    private String notificationPolicyContentTemplate()
    {
        return "RBAC流程[" + runId + "]{{processName}}产生真实通知。";
    }

    /**
     * 为单个角色和入口生成可逐字节辨识的真实附件正文。
     *
     * @param roleKey String，五角色 key
     * @param handler String，矩阵 handler 名称
     * @return byte[]，仅含 ASCII 且本轮唯一的附件正文
     */
    private byte[] bytesFor(String roleKey, String handler)
    {
        byte[] suffix = ("-" + roleKey + "-" + handler + "-"
                + sequence.incrementAndGet()).getBytes(StandardCharsets.US_ASCII);
        return concatenate(ATTACHMENT_BYTES, suffix);
    }

    /**
     * 按顺序拼接多个字节数组，不共享调用方可变缓冲区。
     *
     * @param parts byte[][]，待拼接字节数组
     * @return byte[]，长度等于全部输入长度之和的新数组
     */
    private byte[] concatenate(byte[]... parts)
    {
        int length = 0;
        for (byte[] part : parts)
        {
            length = Math.addExact(length, part.length);
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] part : parts)
        {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    /**
     * 生成带本轮标识且满足业务名称列长度的唯一中文名称。
     *
     * @param label String，名称业务语义
     * @return String，本轮唯一名称
     */
    private String uniqueName(String label)
    {
        return PREFIX + label + "-" + runId.substring(0, 12) + "-"
                + sequence.incrementAndGet();
    }

    /**
     * 生成仅含英文、数字和下划线的业务唯一编码。
     *
     * @param kind String，编码对象类型
     * @return String，本轮唯一业务编码
     */
    private String uniqueCode(String kind)
    {
        return "rbac_" + kind + "_" + runId.substring(0, 12) + "_"
                + sequence.incrementAndGet();
    }

    /**
     * 生成可由 Flowable 查询前缀精确识别的唯一业务主键。
     *
     * @param label String，流程 fixture 类型
     * @return String，本轮唯一 businessKey
     */
    private String uniqueBusinessKey(String label)
    {
        return PREFIX + runId + "-" + label + "-" + sequence.incrementAndGet();
    }

    /**
     * 使用 UTF-8 编码 URL 查询值或路径段。
     *
     * @param value String，原始值
     * @return String，application/x-www-form-urlencoded 兼容编码结果
     */
    private String encode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 使用测试侧 ObjectMapper 序列化正式请求对象。
     *
     * @param value Object，请求 DTO 等价结构
     * @return String，UTF-8 JSON 文本
     */
    private String json(Object value)
    {
        try
        {
            return objectMapper.writeValueAsString(value);
        }
        catch (JacksonException exception)
        {
            throw new IllegalStateException("RBAC IT JSON 序列化失败", exception);
        }
    }

    /**
     * 核对 TableDataInfo 顶层 rows 中存在指定正式业务行。
     *
     * @param body JsonNode，成功列表响应
     * @param field String，业务主键字段名
     * @param expected String，期望字段文本
     * @return void，无返回值；rows 缺失或目标行不存在时断言失败
     */
    private void assertRowsContain(JsonNode body, String field, String expected)
    {
        JsonNode rows = body.path("rows");
        assertThat(rows.isArray()).isTrue();
        assertThat(arrayContains(rows, field, expected)).isTrue();
    }

    /**
     * 执行不包含敏感值的 fixture 阶段断言，失败时把稳定分类写入机器报告。
     *
     * @param condition boolean，当前持久化或响应关系是否满足契约
     * @param reason String，不拼接账号、Token、对象主键或响应正文的稳定分类
     * @return void，条件不满足时抛出脱敏 fixture 断言异常
     */
    private void requireFixture(boolean condition, String reason)
    {
        if (!condition)
        {
            throw new AllowFixtureAssertionException(reason);
        }
    }

    /**
     * 核对个人任务列表成功返回空集，并确认同流程中他人的目标任务没有越权泄露。
     *
     * @param body JsonNode，成功列表响应
     * @param field String，需要核对的业务主键字段名
     * @param excluded String，绝不能出现在当前用户结果中的他人业务主键
     * @return void，无返回值；rows 非数组、目标泄露或列表非空时断言失败
     */
    private void assertRowsExcludeAndEmpty(JsonNode body, String field, String excluded)
    {
        JsonNode rows = body.path("rows");
        assertThat(rows.isArray()).isTrue();
        assertThat(arrayContains(rows, field, excluded)).isFalse();
        assertThat(rows).isEmpty();
        assertThat(body.path("total").asLong()).isZero();
    }

    /**
     * 在 JSON 数组中按字段文本查找目标对象。
     *
     * @param array JsonNode，待检索数组
     * @param field String，对象字段名
     * @param expected String，期望字段文本
     * @return boolean，至少一个对象字段完全相等时返回 true
     */
    private boolean arrayContains(JsonNode array, String field, String expected)
    {
        if (!array.isArray())
        {
            return false;
        }
        for (JsonNode element : array)
        {
            if (expected.equals(element.path(field).asText()))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * 在 JSON 数组中返回字段文本完全匹配的首个业务对象。
     *
     * @param array JsonNode，待检索的正式接口数组
     * @param field String，对象字段名
     * @param expected String，期望字段文本
     * @return JsonNode，首个匹配对象；数组无效或不存在匹配项时返回 null
     */
    private JsonNode findArrayObject(JsonNode array, String field, String expected)
    {
        if (!array.isArray())
        {
            return null;
        }
        for (JsonNode element : array)
        {
            if (expected.equals(element.path(field).asText()))
            {
                return element;
            }
        }
        return null;
    }

    /**
     * 生成满足正式 SHA-256 字段格式约束的随机 64 位小写十六进制值。
     *
     * @return String，长度为 64 且不含分隔符的小写十六进制文本
     */
    private String randomHash()
    {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 使用 Apache POI 解析真实 XLSX 并确认目标业务值已写入任一单元格。
     *
     * @param workbookBytes byte[]，HTTP 导出响应正文
     * @param expected String，必须出现在工作簿中的业务值
     * @return void，无返回值；工作簿损坏或目标值缺失时失败
     * @throws IOException XLSX 无法解析或关闭时抛出
     */
    private void assertWorkbookContains(byte[] workbookBytes, String expected)
            throws IOException
    {
        assertThat(workbookContains(workbookBytes, expected))
                .as("真实导出工作簿必须包含目标业务值").isTrue();
    }

    /**
     * 使用 Apache POI 解析真实 XLSX 并确认他人业务值没有进入当前用户导出。
     *
     * @param workbookBytes byte[]，HTTP 导出响应正文
     * @param excluded String，禁止出现在工作簿中的他人业务值
     * @return void，无返回值；工作簿损坏或出现越权业务值时失败
     * @throws IOException XLSX 无法解析或关闭时抛出
     */
    private void assertWorkbookExcludes(byte[] workbookBytes, String excluded)
            throws IOException
    {
        assertThat(workbookContains(workbookBytes, excluded))
                .as("个人任务导出不得包含其他办理人的业务值").isFalse();
    }

    /**
     * 解析真实 XLSX 并检索指定业务值，供包含与排除断言共享同一严格解析路径。
     *
     * @param workbookBytes byte[]，HTTP 导出响应正文
     * @param expected String，需要检索的业务值
     * @return boolean，任一工作表单元格包含目标值时为 true
     * @throws IOException XLSX 无法解析或关闭时抛出
     */
    private boolean workbookContains(byte[] workbookBytes, String expected)
            throws IOException
    {
        assertThat(workbookBytes).isNotEmpty();
        DataFormatter formatter = new DataFormatter();
        boolean found = false;
        try (Workbook workbook = WorkbookFactory.create(
                new ByteArrayInputStream(workbookBytes)))
        {
            assertThat(workbook.getNumberOfSheets()).isPositive();
            for (var sheet : workbook)
            {
                for (var row : sheet)
                {
                    for (var cell : row)
                    {
                        if (formatter.formatCellValue(cell).contains(expected))
                        {
                            found = true;
                            break;
                        }
                    }
                    if (found)
                    {
                        break;
                    }
                }
                if (found)
                {
                    break;
                }
            }
        }
        return found;
    }

    /**
     * 从主部署冻结映射读取指定流程定义。
     *
     * @param key String，BPMN process key
     * @return ProcessDefinition，主部署中的唯一流程定义
     */
    private ProcessDefinition definition(String key)
    {
        ProcessDefinition definition = definitions.get(key);
        assertThat(definition).as("主部署流程定义必须存在").isNotNull();
        return definition;
    }

    /**
     * 按定义主键读取真实仓库流程定义。
     *
     * @param definitionId String，Flowable 流程定义主键
     * @return ProcessDefinition，当前仓库中的唯一流程定义
     */
    private ProcessDefinition definitionById(String definitionId)
    {
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(definitionId).singleResult();
        assertThat(definition).isNotNull();
        return definition;
    }

    /**
     * 选择与当前角色不同且已预登记的确定性辅助角色。
     *
     * @param roleKey String，需要排除的角色
     * @return String，不同于入参的已登记角色 key
     */
    private String otherRole(String roleKey)
    {
        // 所有任务 assignee、委派、转办和下一办理人 fixture 都必须具备实时审批资格。
        for (String candidate : List.of("workflow_admin", "workflow_approver"))
        {
            if (!candidate.equals(roleKey) && roleUserIds.containsKey(candidate))
            {
                return candidate;
            }
        }
        throw new AssertionError("缺少可用辅助角色");
    }

    /**
     * 为任意发起或读取角色选择具备实时审批资格的合法任务办理角色。
     *
     * @param roleKey String，当前五角色 key
     * @return String，当前角色本身具审批资格时保持不变，否则返回流程管理员
     */
    private String approvalAssigneeRole(String roleKey)
    {
        assertThat(roleUserIds).containsKey(roleKey);
        if ("workflow_admin".equals(roleKey) || "workflow_approver".equals(roleKey))
        {
            return roleKey;
        }
        // 发起人和审计员只建立 initiator/copy/candidate 关系，不能伪装成正式任务办理人。
        return "workflow_admin";
    }

    /**
     * 从 Flowable 历史变量核对流程最终业务状态。
     *
     * @param instanceId String，流程实例主键
     * @param expectedStatus String，期望 processStatus
     * @return void，无返回值；变量缺失或状态不符时断言失败
     */
    private void assertHistoricStatus(String instanceId, String expectedStatus)
    {
        var statusVariable = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(instanceId).variableName("processStatus").singleResult();
        assertThat(statusVariable).isNotNull();
        assertThat(String.valueOf(statusVariable.getValue())).isEqualTo(expectedStatus);
    }

    /**
     * 核对指定流程已持久化至少一条正式审批意见或状态审计。
     *
     * @param instanceId String，流程实例主键
     * @return void，无返回值；没有任何流程意见时断言失败
     */
    private void assertHasComments(String instanceId)
    {
        assertThat(taskService.getProcessInstanceComments(instanceId)).isNotEmpty();
    }

    /**
     * 核对历史任务 completedBy 等于真实操作角色主键。
     *
     * @param taskId String，已结束历史任务主键
     * @param roleKey String，期望真实完成人角色
     * @return void，无返回值；历史任务或 completedBy 不符时断言失败
     */
    private void assertCompletedBy(String taskId, String roleKey)
    {
        HistoricTaskInstance task = historyService.createHistoricTaskInstanceQuery()
                .taskId(taskId).singleResult();
        assertThat(task).isNotNull();
        assertThat(task.getCompletedBy())
                .isEqualTo(String.valueOf(roleUserIds.get(roleKey)));
    }

    /**
     * 构造当前 SpringBootTest 随机端口的回环基础地址。
     *
     * @return String，以 http://127.0.0.1 开头且不含尾斜线的基础地址
     */
    private String baseUrl()
    {
        assertThat(serverPort).isPositive();
        return "http://127.0.0.1:" + serverPort;
    }

    /**
     * 单个 ALLOW 单元的脱敏传输与业务结果。
     *
     * @param passed boolean，真实业务断言是否全部通过
     * @param transportStatus Integer，HTTP 状态；前置异常时为空
     * @param bodyCode Integer，AjaxResult code；二进制或前置异常时为空
     * @param reason String，不含凭据和对象主键的稳定原因
     */
    record Execution(boolean passed, Integer transportStatus, Integer bodyCode,
            String reason)
    {
        /**
         * 创建 JSON ALLOW 成功结果。
         *
         * @return Execution，HTTP 200、业务 200 的成功结果
         */
        static Execution passedJson()
        {
            return new Execution(true, 200, 200, "ALLOW_JSON_PASSED");
        }

        /**
         * 创建二进制 ALLOW 成功结果。
         *
         * @return Execution，HTTP 200 且无 AjaxResult code 的成功结果
         */
        static Execution passedBinary()
        {
            return new Execution(true, 200, null, "ALLOW_BINARY_PASSED");
        }

        /**
         * 创建不泄露响应正文、账号、Token 或对象主键的失败结果。
         *
         * @param reason String，稳定失败分类
         * @return Execution，未通过的脱敏结果
         */
        static Execution failed(String reason)
        {
            return new Execution(false, null, null, reason);
        }

        /**
         * 创建不含响应正文的 HTTP 或业务拒绝结果。
         *
         * @param transportStatus int，真实 HTTP 状态码
         * @param bodyCode Integer，AjaxResult code；无法安全解析时为空
         * @param reason String，不含业务数据的稳定失败分类
         * @return Execution，保留状态码但不泄露响应正文的失败结果
         */
        static Execution failedHttp(int transportStatus, Integer bodyCode,
                String reason)
        {
            return new Execution(false, transportStatus, bodyCode, reason);
        }
    }

    /**
     * 在 ALLOW 请求返回非成功响应时携带脱敏状态，供最终报告精确定位失败层级。
     */
    private static final class AllowHttpResponseException extends AssertionError
    {
        private static final long serialVersionUID = 1L;

        /** HTTP 传输状态，不包含响应正文。 */
        private final int transportStatus;

        /** AjaxResult 业务码；正文为空或不是规范 JSON 时为空。 */
        private final Integer bodyCode;

        /** 稳定失败分类，不拼接服务端异常消息或任何业务主键。 */
        private final String reason;

        /**
         * 创建脱敏 ALLOW 响应异常。
         *
         * @param transportStatus int，真实 HTTP 状态码
         * @param bodyCode Integer，AjaxResult code；无法安全解析时为空
         * @param reason String，不含响应正文和业务标识的稳定失败分类
         * @return 无返回值，构造后由 execute 转换为机器可读结果
         */
        private AllowHttpResponseException(int transportStatus, Integer bodyCode,
                String reason)
        {
            super(reason);
            this.transportStatus = transportStatus;
            this.bodyCode = bodyCode;
            this.reason = reason;
        }

        /**
         * 返回真实 HTTP 状态码。
         *
         * @return int，HTTP 状态码
         */
        private int transportStatus()
        {
            return transportStatus;
        }

        /**
         * 返回脱敏业务码。
         *
         * @return Integer，AjaxResult code；无法解析时为空
         */
        private Integer bodyCode()
        {
            return bodyCode;
        }

        /**
         * 返回稳定失败分类。
         *
         * @return String，不含响应正文或对象标识的失败分类
         */
        private String reason()
        {
            return reason;
        }
    }

    /**
     * 在 HTTP 成功后的正式持久化关系断言失败时携带脱敏阶段分类。
     */
    private static final class AllowFixtureAssertionException extends AssertionError
    {
        private static final long serialVersionUID = 1L;

        /** 稳定阶段分类，不包含实际业务数据。 */
        private final String reason;

        /**
         * 创建脱敏 fixture 断言异常。
         *
         * @param reason String，不含账号、Token、对象主键或响应正文的稳定分类
         * @return 无返回值，构造后由 execute 转换为机器可读结果
         */
        private AllowFixtureAssertionException(String reason)
        {
            super(reason);
            this.reason = reason;
        }

        /**
         * 返回稳定失败分类。
         *
         * @return String，不含实际业务数据的阶段分类
         */
        private String reason()
        {
            return reason;
        }
    }

    /**
     * 分类正式数据 fixture。
     *
     * @param id long，wf_category 主键
     * @param name String，分类名称
     * @param code String，唯一分类编码
     */
    private record CategoryRow(long id, String name, String code)
    {
    }

    /**
     * 表单正式数据 fixture。
     *
     * @param id long，wf_form 主键
     * @param name String，表单名称
     */
    private record FormRow(long id, String name)
    {
    }

    /**
     * 申请草稿正式数据 fixture。
     *
     * @param id String，wf_process_draft UUID 主键
     * @param businessKey String，草稿当前业务主键
     */
    private record DraftFixture(String id, String businessKey)
    {
    }

    /**
     * BPMN 事件编码正式目录 fixture。
     *
     * @param id long，wf_bpmn_event_code 主键
     * @param code String，唯一事件编码
     * @param name String，用户可见事件名称
     */
    private record BpmnEventCodeFixture(long id, String code, String name)
    {
    }

    /**
     * BPMN 事件运行审计与本人通知 fixture。
     *
     * @param auditId long，wf_bpmn_event_audit 主键
     * @param notificationId Long，可空 wf_bpmn_event_notification 主键
     */
    private record BpmnEventRuntimeFixture(long auditId, Long notificationId)
    {
    }

    /**
     * SLA 业务日历正式数据 fixture。
     *
     * @param id long，wf_business_calendar 主键
     * @param key String，发布后不可变日历编码
     */
    private record BusinessCalendarFixture(long id, String key)
    {
    }

    /**
     * SLA 运行执行、审计和本人通知 fixture。
     *
     * @param executionId long，wf_task_sla_execution 主键
     * @param auditId long，wf_task_sla_audit 主键
     * @param notificationId Long，可空 wf_task_sla_notification 主键
     */
    private record TaskSlaRuntimeFixture(long executionId, long auditId,
            Long notificationId)
    {
    }

    /**
     * 普通审批通知 outbox 与可选站内信 fixture。
     *
     * @param outboxId long，wf_notification_outbox 主键
     * @param notificationId Long，可空 wf_notification_inbox 主键
     * @param process ProcessFixture，通知关联的真实 Flowable 实例
     */
    private record NotificationOutboxFixture(long outboxId, Long notificationId,
            ProcessFixture process)
    {
    }

    /**
     * 用户通知偏好运行前完整快照。
     *
     * @param exists boolean，运行前是否已有正式偏好行
     * @param inboxEnabled boolean，站内通道开关
     * @param emailEnabled boolean，邮件通道开关
     * @param revision int，乐观锁版本
     * @param updateTime Timestamp，运行前更新时间；不存在时为空
     */
    private record NotificationPreferenceSnapshot(boolean exists,
            boolean inboxEnabled, boolean emailEnabled, int revision,
            Timestamp updateTime)
    {
    }

    /**
     * 单条站内通知运行前阅读状态快照。
     *
     * @param notificationId long，wf_notification_inbox 主键
     * @param readStatus String，UNREAD 或 READ
     * @param readTime Timestamp，首次阅读时间；未读时为空
     */
    private record NotificationReadSnapshot(long notificationId,
            String readStatus, Timestamp readTime)
    {
    }

    /**
     * 单个协作管理入口的正式父对象与消息主键。
     *
     * @param messageId String，入站或出站消息 UUID
     * @param channelId String，严格顺序通道 SHA-256 主键
     * @param credentialId Long，集成凭据主键
     * @param credentialName String，唯一凭据名称
     * @param endpointId Long，冻结 HTTP 端点主键
     * @param endpointKey String，唯一端点编码
     * @param correlationKey String，目标流程业务关联键
     */
    private record CollaborationFixture(String messageId, String channelId,
            Long credentialId, String credentialName, Long endpointId,
            String endpointKey, String correlationKey)
    {
    }

    /**
     * Flowable 模型 fixture。
     *
     * @param id String，模型主键
     * @param key String，模型业务 key
     * @param name String，模型名称
     */
    private record ModelFixture(String id, String key, String name)
    {
    }

    /**
     * 单流程实例及当前或历史任务 fixture。
     *
     * @param instanceId String，流程实例主键
     * @param definitionId String，流程定义主键
     * @param deploymentId String，部署主键
     * @param businessKey String，业务主键
     * @param taskId String，当前或目标历史任务主键
     * @param taskDefinitionKey String，任务 BPMN 节点 key
     */
    private record ProcessFixture(String instanceId, String definitionId,
            String deploymentId, String businessKey, String taskId,
            String taskDefinitionKey)
    {
    }

    /**
     * 旧 Token 撤权测试使用的最小正式对象投影。
     *
     * @param instanceId String，真实流程实例主键
     * @param taskId String，当前活动任务主键
     */
    record RevocationActionFixture(String instanceId, String taskId)
    {
    }

    /**
     * 动态多实例 fixture。
     *
     * @param instanceId String，流程实例主键
     * @param actorTaskId String，当前操作角色的活动会签任务主键
     * @param actorUserId Long，初始会签成员的正式用户主键
     * @param addUserId Long，动态加签的另一名正式审批用户主键
     */
    private record MultiFixture(String instanceId, String actorTaskId, Long actorUserId,
            Long addUserId)
    {
    }

    /**
     * 已落库并落盘的附件 fixture。
     *
     * @param id String，wf_attachment 业务主键
     * @param storageKey String，私有存储对象键
     * @param bytes byte[]，用于逐字节下载核对的原始正文
     */
    private record UploadedAttachment(String id, String storageKey, byte[] bytes)
    {
        /**
         * 隔离调用方缓冲区，防止测试断言期间附件正文被外部修改。
         *
         * @param id String，附件业务主键
         * @param storageKey String，私有对象键
         * @param bytes byte[]，附件原始正文
         * @return 无返回值，record 构造完成后通过访问器读取
         */
        private UploadedAttachment
        {
            bytes = bytes.clone();
        }

        /**
         * 返回附件正文副本，禁止调用方修改 fixture 内部缓冲区。
         *
         * @return byte[]，附件正文副本
         */
        @Override
        public byte[] bytes()
        {
            return bytes.clone();
        }
    }

    /**
     * 抄送正式数据 fixture。
     *
     * @param id long，wf_copy 主键
     * @param title String，抄送标题
     */
    private record CopyRow(long id, String title)
    {
    }
}

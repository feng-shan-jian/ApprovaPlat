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
            "WfModelController#export", "WfProcessController#startExport",
            "WfProcessController#ownExport", "WfProcessController#managedExport",
            "WfProcessController#todoExport", "WfProcessController#claimExport",
            "WfProcessController#finishedExport", "WfProcessController#copyExport",
            "WfProcessController#start", "WfProcessController#deleteHistory",
            "WfTaskController#stopProcess", "WfTaskController#revokeProcess",
            "WfTaskController#processVariables",
            "WfTaskController#getMultiInstanceState",
            "WfTaskController#adjustMultiInstance", "WfTaskController#complete",
            "WfTaskController#reject", "WfTaskController#returnTask",
            "WfTaskController#returnList", "WfTaskController#claim",
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
                case "WfFormController" -> executeForm(roleKey, endpoint);
                case "WfIdentityController" -> executeIdentity(roleKey, endpoint);
                case "WfInstanceController" -> executeInstance(roleKey, endpoint);
                case "WfModelController" -> executeModel(roleKey, endpoint);
                case "WfProcessController" -> executeProcess(roleKey, endpoint);
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
                String xml = modelXml(fixture.id());
                JsonNode body = callJson(roleKey, endpoint, "/workflow/model/save",
                        json(Map.of("requestId", newModelSaveRequestId(),
                                "modelId", fixture.id(), "bpmnXml", xml,
                                "newVersion", false)));
                assertThat(body.path("data").path("modelId").asText())
                        .isEqualTo(fixture.id());
                assertThat(modelXml(fixture.id())).contains(fixture.key());
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
                assertThat(body.path("data").path("members").isArray()).isTrue();
                assertThat(body.path("data").path("members").size()).isEqualTo(2);
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
                assertThat(body.path("data").path("members").size()).isEqualTo(3);
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
                                "targetKey", "firstReview",
                                "comment", "RBAC真实退回", "copyUserIds", List.of())));
                Task returned = taskService.createTaskQuery()
                        .processInstanceId(fixture.instanceId())
                        .taskDefinitionKey("firstReview").singleResult();
                assertThat(returned).isNotNull();
                assertHasComments(fixture.instanceId());
                yield Execution.passedJson();
            }
            case "returnList" ->
            {
                ProcessFixture fixture = secondTaskReady(roleKey);
                JsonNode body = callJson(roleKey, endpoint, "/workflow/task/returnList",
                        json(Map.of("taskId", fixture.taskId())));
                assertThat(arrayContains(body.path("data"), "id", "firstReview")).isTrue();
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
     * 精确清理本类创建的附件、抄送、部署、模型、模型保存幂等记录、表单、分类、
     * 配额行和操作日志。
     *
     * @return void，无返回值；清理后仍有任一精确对象残留即失败
     */
    void cleanup()
    {
        if (cleaned)
        {
            return;
        }

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
                        "modelId", original.id(), "bpmnXml", modelXml(original.id()),
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
        byte[] source = repositoryService.getModelEditorSource(fixture.id());
        assertThat(source).isNotNull().isNotEmpty();

        // 通过 Flowable 公共模型 API 修改节点，避免字符串替换破坏命名空间或 BPMN DI。
        BpmnXMLConverter converter = new BpmnXMLConverter();
        BpmnModel bpmnModel = converter.convertToBpmnModel(
                () -> new ByteArrayInputStream(source), true, true);
        assertThat(bpmnModel.getMainProcess().getId()).isEqualTo(fixture.key());
        assertThat(bpmnModel.getMainProcess().getFlowElement("review"))
                .isInstanceOf(UserTask.class);
        UserTask reviewTask = (UserTask) bpmnModel.getMainProcess().getFlowElement("review");
        // 设计员可以部署模型但不具备任务办理资格，模型中的办理人必须使用正式审批角色。
        String assigneeRole = approvalAssigneeRole(roleKey);
        reviewTask.setAssignee(String.valueOf(roleUserIds.get(assigneeRole)));
        reviewTask.setFormKey("key_" + commonFormId);

        String deployableXml = new String(
                converter.convertToXML(bpmnModel, StandardCharsets.UTF_8.name()),
                StandardCharsets.UTF_8);
        JsonNode body = callJsonRaw(roleKey, "/workflow/model/save", "POST",
                json(Map.of("requestId", newModelSaveRequestId(),
                        "modelId", fixture.id(), "bpmnXml", deployableXml,
                        "newVersion", false)), true);
        assertThat(body.path("data").path("modelId").asText()).isEqualTo(fixture.id());
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
     * 完成多实例前置任务，使用真实 HTTP 写入 nextUserIds 后返回当前角色的会签任务。
     *
     * @param actorRole String，前置任务及一个会签实例的办理角色
     * @return MultiFixture，活动会签任务和第三个具备正式审批资格的用户
     * @throws IOException HTTP 读取失败
     * @throws InterruptedException 请求线程中断
     */
    private MultiFixture startMulti(String actorRole)
            throws IOException, InterruptedException
    {
        ProcessFixture source = startProcess(MULTI_KEY, "workflow_starter",
                Map.of("sourceAssignee", String.valueOf(roleUserIds.get(actorRole)),
                        "note", "RBAC变量", "processStatus", "running"));
        // 动态多实例的初始成员必须同时具备真实审批权限，不能复用仅用于对象授权的普通辅助角色。
        String peerRole = approvalPeerRole(actorRole);
        Long actorUserId = roleUserIds.get(actorRole);
        Long peerUserId = roleUserIds.get(peerRole);
        Long addUserId = findAdditionalApprovalUser(actorUserId, peerUserId);
        callJsonRaw(actorRole, "/workflow/task/complete", "POST",
                completeBody(source.taskId(), "RBAC进入会签", List.of(),
                        List.of(actorUserId, peerUserId)), true);
        Task actorTask = taskService.createTaskQuery()
                .processInstanceId(source.instanceId())
                .taskDefinitionKey("multiReview")
                .taskAssignee(String.valueOf(actorUserId)).singleResult();
        assertThat(actorTask).isNotNull();
        return new MultiFixture(source.instanceId(), actorTask.getId(), addUserId);
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
     * 从真实用户、角色和菜单关系中选择第三名已登记审批用户。
     *
     * @param actorUserId Long，当前会签办理人用户主键
     * @param peerUserId Long，另一名初始会签办理人用户主键
     * @return Long，排除两名初始成员后的启用且具审批权限用户主键
     */
    private Long findAdditionalApprovalUser(Long actorUserId, Long peerUserId)
    {
        // 直接查询正式主数据关系，保证加签 fixture 与生产资格校验使用同一权限事实来源。
        List<Long> candidates = jdbcTemplate.queryForList(
                "select distinct u.user_id from sys_user u "
                        + "inner join sys_user_role ur on ur.user_id = u.user_id "
                        + "inner join sys_role r on r.role_id = ur.role_id "
                        + "inner join sys_role_menu rm on rm.role_id = r.role_id "
                        + "inner join sys_menu m on m.menu_id = rm.menu_id "
                        + "where u.status = '0' and u.del_flag = '0' "
                        + "and r.status = '0' and r.del_flag = '0' and m.status = '0' "
                        + "and m.perms = 'workflow:process:approval' "
                        + "and u.user_id not in (?, ?) order by u.user_id limit 1",
                Long.class, actorUserId, peerUserId);
        assertThat(candidates)
                .as("动态加签必须复用已登记且具真实审批资格的第三名用户")
                .singleElement();
        return candidates.get(0);
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
        HttpResponse<byte[]> response = sendWithAudit(roleKey, path, method, bytes,
                body == null ? null : "application/json; charset=UTF-8", audited);
        return parseSuccessfulJson(response);
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
        HttpResponse<byte[]> response = sendWithAudit(roleKey, path,
                endpoint.httpMethod(), body, null,
                AUDITED_ENDPOINTS.contains(endpoint.key()));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isNotEmpty();
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
     * @param addUserId Long，动态加签的第三名正式审批用户主键
     */
    private record MultiFixture(String instanceId, String actorTaskId, Long addUserId)
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

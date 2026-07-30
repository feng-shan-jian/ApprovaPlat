package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.Deployment;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.domain.dto.StartProcessRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskCompleteRequest;
import com.ruoyi.flowable.domain.vo.WorkflowAttachmentView;
import com.ruoyi.flowable.engine.WorkflowProcessInstanceSnapshot;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentDownload;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentStorage;
import com.ruoyi.flowable.service.process.WorkflowFormSubmissionSnapshotCodec;
import com.ruoyi.flowable.service.process.WorkflowProcessInstanceService;
import com.ruoyi.flowable.service.process.WorkflowProcessStartService;
import com.ruoyi.flowable.service.task.WorkflowTaskLifecycleService;

/**
 * 工作流附件上传、授权、真实 Flowable 绑定和跨引擎事务回滚集成测试。
 */
@SpringBootTest(
        classes = RuoYiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.datasource.druid.master.url=${FLOWABLE_IT_JDBC_URL}",
            "spring.datasource.druid.master.username=${FLOWABLE_IT_USERNAME}",
            "spring.datasource.druid.master.password=${FLOWABLE_IT_PASSWORD}",
            "spring.data.redis.database=${FLOWABLE_IT_REDIS_DATABASE:15}",
            "flowable.it.expected-schema=${FLOWABLE_IT_EXPECTED_SCHEMA}",
            "flowable.it.ddl-jdbc-url=${FLOWABLE_IT_DDL_JDBC_URL}",
            "flowable.it.ddl-username=${FLOWABLE_IT_DDL_USERNAME}",
            "flowable.it.ddl-password=${FLOWABLE_IT_DDL_PASSWORD}",
            // 固定公开材料只用于装配 TokenService，本 IT 不创建登录 Token，禁止复用于任何部署环境。
        "token.secret=eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eA==",
            "flowable.database-schema-update=false",
            "flowable.async-executor-activate=false",
            "flowable.async-history-executor-activate=false",
            "spring.quartz.auto-startup=false",
            "flowable.attachment.cleanup-initial-delay=PT6H",
            "flowable.attachment.cleanup-fixed-delay=PT6H"
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class WorkflowAttachmentIT
{
    /** 集成测试只允许连接的数据库名称后缀。 */
    private static final String SAFE_SCHEMA_SUFFIX = "_flowable_it";
    /** 附件上传者和流程发起人的正式测试用户主键。 */
    private static final long OWNER_USER_ID = 121L;
    /** 用于验证临时附件对象级越权的第二个正式用户主键。 */
    private static final long FOREIGN_USER_ID = 122L;
    /** 隔离 schema 内满足部署快照正数约束的测试表单主键。 */
    private static final long FORM_ID = 81_001L;
    /** 只针对本测试第二个附件制造绑定失败的临时检查约束名。 */
    private static final String FAILURE_CHECK_CONSTRAINT = "wf_attachment_it_fail_second";
    /** 测试部署名固定前缀，用于残留检查和受控清理。 */
    private static final String DEPLOYMENT_NAME_PREFIX = "workflow-attachment-it-";
    /** BPMN 开始节点使用的部署表单键。 */
    private static final String START_FORM_KEY = "key_p14_start_form";
    /** BPMN 审核任务使用的部署表单键。 */
    private static final String REVIEW_FORM_KEY = "key_p14_review_form";
    /** BPMN 确认任务使用的部署表单键。 */
    private static final String CONFIRM_FORM_KEY = "key_p14_confirm_form";
    /** 包含必填文本、金额和最多两个正式附件 UUID 的开始表单快照。 */
    private static final String START_FORM_CONTENT = """
            {"fields":[
              {"__config__":{"layout":"colFormItem","tag":"el-input","required":true},
               "__vModel__":"reason","maxlength":100},
              {"__config__":{"layout":"colFormItem","tag":"el-input-number","required":true},
               "__vModel__":"amount","min":0,"max":10000},
              {"__config__":{"layout":"colFormItem","tag":"el-upload","required":true},
               "__vModel__":"files","limit":2}
            ]}
            """;
    /** 任务节点允许复用同字段历史附件、绑定新附件或显式提交空引用。 */
    private static final String TASK_FORM_CONTENT = """
            {"fields":[
              {"__config__":{"layout":"colFormItem","tag":"el-upload","required":false},
               "__vModel__":"files","limit":3}
            ]}
            """;
    /** 每个测试类独占且位于系统临时目录的若依 profile。 */
    private static final Path PROFILE_ROOT = createProfileRoot();

    private final ProcessEngine processEngine;
    private final DataSource dynamicDataSource;
    private final WorkflowAttachmentService attachmentService;
    private final WorkflowAttachmentStorage attachmentStorage;
    private final WorkflowAttachmentProperties attachmentProperties;
    private final WorkflowProcessStartService processStartService;
    private final WorkflowTaskLifecycleService taskLifecycleService;
    private final WorkflowProcessInstanceService processInstanceService;
    private final WfAttachmentMapper attachmentMapper;
    private final ObjectMapper objectMapper = JsonMapper.shared();
    private final String expectedSchema;
    /** 仅用于隔离 schema 故障约束的 JDBC URL，不得复用应用运行账号。 */
    private final String ddlJdbcUrl;
    /** 仅具隔离 schema ALTER 权限的故障注入账号。 */
    private final String ddlUsername;
    /** 故障注入账号密码，只由测试环境变量注入。 */
    private final String ddlPassword;

    /**
     * 把附件私有存储根切换到当前测试类独占的系统临时目录。
     * @param registry DynamicPropertyRegistry，Spring 测试动态属性注册器
     * @return void，无返回值
     */
    @DynamicPropertySource
    static void registerProfileRoot(DynamicPropertyRegistry registry)
    {
        registry.add("ruoyi.profile", () -> PROFILE_ROOT.toString());
    }

    /**
     * 创建完整应用上下文中的真实附件和 Flowable 集成测试。
     * @param processEngine ProcessEngine，应用正式 Flowable 引擎
     * @param dynamicDataSource DataSource，若依动态主数据源
     * @param attachmentService WorkflowAttachmentService，正式附件领域服务
     * @param attachmentStorage WorkflowAttachmentStorage，临时 profile 私有存储
     * @param attachmentProperties WorkflowAttachmentProperties，附件配额动态测试配置
     * @param processStartService WorkflowProcessStartService，真实流程发起服务
     * @param taskLifecycleService WorkflowTaskLifecycleService，真实任务完成与附件绑定服务
     * @param processInstanceService WorkflowProcessInstanceService，受控历史删除服务
     * @param attachmentMapper WfAttachmentMapper，真实附件引用统计 Mapper
     * @param expectedSchema String，显式声明的隔离 schema 名称
     * @param ddlJdbcUrl String，独立 DDL 故障注入连接 URL
     * @param ddlUsername String，仅具隔离 schema ALTER 权限的账号
     * @param ddlPassword String，由测试环境变量注入的 DDL 账号密码
     * @return 无返回值，构造后由 Spring 测试容器管理
     */
    @Autowired
    WorkflowAttachmentIT(ProcessEngine processEngine,
            @Qualifier("dynamicDataSource") DataSource dynamicDataSource,
            WorkflowAttachmentService attachmentService,
            WorkflowAttachmentStorage attachmentStorage,
            WorkflowAttachmentProperties attachmentProperties,
            WorkflowProcessStartService processStartService,
            WorkflowTaskLifecycleService taskLifecycleService,
             WorkflowProcessInstanceService processInstanceService,
             WfAttachmentMapper attachmentMapper,
             @Value("${flowable.it.expected-schema}") String expectedSchema,
             @Value("${flowable.it.ddl-jdbc-url}") String ddlJdbcUrl,
             @Value("${flowable.it.ddl-username}") String ddlUsername,
             @Value("${flowable.it.ddl-password}") String ddlPassword)
    {
        this.processEngine = processEngine;
        this.dynamicDataSource = dynamicDataSource;
        this.attachmentService = attachmentService;
        this.attachmentStorage = attachmentStorage;
        this.attachmentProperties = attachmentProperties;
        this.processStartService = processStartService;
        this.taskLifecycleService = taskLifecycleService;
        this.processInstanceService = processInstanceService;
        this.attachmentMapper = attachmentMapper;
        this.expectedSchema = expectedSchema;
        this.ddlJdbcUrl = ddlJdbcUrl;
        this.ddlUsername = ddlUsername;
        this.ddlPassword = ddlPassword;
    }

    /**
     * 在任何业务写入前核对当前连接、schema 后缀和固定测试主键均处于隔离状态。
     * @return void，连接到非测试库或发现前次残留时立即失败
     * @throws SQLException 获取 JDBC catalog 失败
     */
    @BeforeEach
    void verifyIsolatedEnvironment() throws SQLException
    {
        assertThat(expectedSchema).isNotBlank();
        assertThat(expectedSchema.toLowerCase(Locale.ROOT)).endsWith(SAFE_SCHEMA_SUFFIX);
        try (Connection connection = dynamicDataSource.getConnection())
        {
            assertThat(connection.getCatalog()).isEqualTo(expectedSchema);
        }
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dynamicDataSource);
        assertThat(jdbcTemplate.queryForObject("select database()", String.class))
                .isEqualTo(expectedSchema);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_user where user_id in (?, ?)", Long.class,
                OWNER_USER_ID, FOREIGN_USER_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_form where form_id = ?", Long.class, FORM_ID))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_attachment_quota_guard "
                        + "where owner_user_id in (?, ?)", Long.class,
                OWNER_USER_ID, FOREIGN_USER_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from information_schema.table_constraints "
                        + "where constraint_schema = ? and table_name = 'wf_attachment' "
                        + "and constraint_name = ? and constraint_type = 'CHECK'",
                Long.class, expectedSchema, FAILURE_CHECK_CONSTRAINT)).isZero();
    }

    /**
     * 验证同一用户三个并发事务在 TEMP 数量上限为两个时只能提交两个附件。
     * @return void，数据库串行配额门禁失效或测试数据未清理时测试失败
     * @throws Exception 并发线程等待、结果收集或磁盘残留核验失败
     */
    @Test
    void serializesConcurrentUploadsByTemporaryCountQuota() throws Exception
    {
        verifyConcurrentTemporaryQuota(2, attachmentProperties.getMaxTemporaryBytes(),
                List.of(
                        "one1".getBytes(StandardCharsets.UTF_8),
                        "two2".getBytes(StandardCharsets.UTF_8),
                        "tri3".getBytes(StandardCharsets.UTF_8)),
                2L, 8L);
    }

    /**
     * 验证同一用户三个并发事务在 TEMP 累计八字节上限下只能提交两个四字节附件。
     * @return void，累计字节配额发生并发超卖或测试数据未清理时测试失败
     * @throws Exception 并发线程等待、结果收集或磁盘残留核验失败
     */
    @Test
    void serializesConcurrentUploadsByTemporaryByteQuota() throws Exception
    {
        verifyConcurrentTemporaryQuota(100, 8L,
                List.of(
                        "four".getBytes(StandardCharsets.UTF_8),
                        "byte".getBytes(StandardCharsets.UTF_8),
                        "size".getBytes(StandardCharsets.UTF_8)),
                2L, 8L);
    }

    /**
     * 验证两个不同用户的并发上传共享同一全局容量锁，容量只能被一个事务提交占用。
     * @return void，跨用户并发可超卖全局容量或清理后残留测试数据时测试失败
     * @throws Exception 并发线程等待、结果收集或磁盘残留核验失败
     */
    @Test
    void serializesDifferentUsersByGlobalCapacityQuota() throws Exception
    {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dynamicDataSource);
        int originalMaxTemporaryCount = attachmentProperties.getMaxTemporaryCount();
        long originalMaxTemporaryBytes = attachmentProperties.getMaxTemporaryBytes();
        long originalMaxTotalBytes = attachmentProperties.getMaxTotalBytes();
        try
        {
            insertTestUsers(jdbcTemplate);
            // 全局容量必须计入库内既有正式占用，本竞态只额外开放一个四字节文件的容量。
            long existingLiveBytes = jdbcTemplate.queryForObject(
                    "select coalesce(sum(file_size), 0) from wf_attachment "
                            + "where storage_deleted_time is null",
                    Long.class);
            long expectedLiveBytes = Math.addExact(existingLiveBytes, 4L);
            attachmentProperties.setMaxTemporaryCount(100);
            attachmentProperties.setMaxTemporaryBytes(1024L);
            attachmentProperties.setMaxTotalBytes(expectedLiveBytes);

            List<ConcurrentUploadOutcome> outcomes = uploadConcurrently(
                    List.of("own1".getBytes(StandardCharsets.UTF_8),
                            "for2".getBytes(StandardCharsets.UTF_8)),
                    List.of(OWNER_USER_ID, FOREIGN_USER_ID));
            List<WorkflowAttachmentView> successfulAttachments = outcomes.stream()
                    .map(ConcurrentUploadOutcome::attachment)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            List<RuntimeException> failures = outcomes.stream()
                    .map(ConcurrentUploadOutcome::failure)
                    .filter(java.util.Objects::nonNull)
                    .toList();

            assertThat(successfulAttachments).hasSize(1);
            assertThat(failures).singleElement().isInstanceOfSatisfying(
                    ServiceException.class, exception ->
                    {
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(exception.getMessage())
                                .isEqualTo("工作流附件全局存储容量已达到上限");
                    });
            assertThat(jdbcTemplate.queryForObject(
                    "select coalesce(sum(file_size), 0) from wf_attachment "
                            + "where storage_deleted_time is null",
                    Long.class)).isEqualTo(expectedLiveBytes);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(distinct owner_user_id) from wf_attachment "
                            + "where owner_user_id in (?, ?)",
                    Long.class, OWNER_USER_ID, FOREIGN_USER_ID)).isEqualTo(1L);

            // 失败上传的事务会整体回滚其用户 guard，只允许全局 guard 与成功用户 guard 留存。
            Long successfulOwnerUserId = jdbcTemplate.queryForObject(
                    "select owner_user_id from wf_attachment "
                            + "where storage_deleted_time is null "
                            + "and owner_user_id in (?, ?)",
                    Long.class, OWNER_USER_ID, FOREIGN_USER_ID);
            assertThat(successfulOwnerUserId).isIn(OWNER_USER_ID, FOREIGN_USER_ID);
            long failedOwnerUserId = successfulOwnerUserId == OWNER_USER_ID
                    ? FOREIGN_USER_ID : OWNER_USER_ID;
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_attachment_quota_guard where owner_user_id = 0",
                    Long.class)).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_attachment_quota_guard where owner_user_id = ?",
                    Long.class, successfulOwnerUserId)).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_attachment_quota_guard where owner_user_id = ?",
                    Long.class, failedOwnerUserId)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_attachment_quota_guard "
                            + "where owner_user_id in (0, ?, ?)",
                    Long.class, OWNER_USER_ID, FOREIGN_USER_ID)).isEqualTo(2L);
        }
        finally
        {
            SecurityContextHolder.clearContext();
            try
            {
                cleanupConcurrentQuotaTestData(jdbcTemplate);
            }
            finally
            {
                attachmentProperties.setMaxTemporaryCount(originalMaxTemporaryCount);
                attachmentProperties.setMaxTemporaryBytes(originalMaxTemporaryBytes);
                attachmentProperties.setMaxTotalBytes(originalMaxTotalBytes);
            }

            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_attachment where owner_user_id in (?, ?)",
                    Long.class, OWNER_USER_ID, FOREIGN_USER_ID)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_attachment_quota_guard "
                            + "where owner_user_id in (?, ?)",
                    Long.class, OWNER_USER_ID, FOREIGN_USER_ID)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_attachment_quota_guard where owner_user_id = 0",
                    Long.class)).isEqualTo(1L);
            assertThat(countStoredRegularFiles()).isZero();
        }
    }

    /**
     * 通过真实磁盘、MySQL、身份门禁和 Flowable 公共 API 验证附件完整业务闭环。
     * @return void，持久化、越权、变量投影、绑定或事务回滚任一契约不满足时测试失败
     * @throws IOException 测试清理阶段遍历独占附件目录失败
     * @throws SQLException 独立 DDL 故障约束创建或移除失败
     */
    @Test
    void persistsAuthorizesCompletesTasksReusesAndRollsBackAttachments()
            throws IOException, SQLException
    {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dynamicDataSource);
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        HistoryService historyService = processEngine.getHistoryService();
        TaskService taskService = processEngine.getTaskService();
        List<String> attachmentIds = new ArrayList<>();
        Map<String, String> storageKeysById = new LinkedHashMap<>();
        String deploymentId = null;
        String successfulBusinessKey = "attachment-success-" + UUID.randomUUID();
        String rollbackBusinessKey = "attachment-rollback-" + UUID.randomUUID();
        String tamperedStartBusinessKey = "attachment-tampered-start-" + UUID.randomUUID();
        boolean failureConstraintCreated = false;

        try
        {
            insertTestUsers(jdbcTemplate);
            setSecurityContextUser(OWNER_USER_ID);

            byte[] successfulContent = "%PDF-1.4\nworkflow start attachment integration"
                    .getBytes(StandardCharsets.UTF_8);
            WorkflowAttachmentView successfulStartAttachment = attachmentService.uploadTemporary(
                    "files", new MockMultipartFile("file", "invoice.pdf",
                            "text/html", successfulContent));
            attachmentIds.add(successfulStartAttachment.attachmentId());
            storageKeysById.put(successfulStartAttachment.attachmentId(),
                    queryStorageKey(jdbcTemplate, successfulStartAttachment.attachmentId()));
            assertPersistedTemporaryAttachment(jdbcTemplate, successfulStartAttachment,
                    successfulContent);

            // 切换到第二个真实用户，证明临时附件读取不依赖客户端声明的 owner。
            setSecurityContextUser(FOREIGN_USER_ID);
            assertThatThrownBy(() -> attachmentService.getReadableMetadata(
                    successfulStartAttachment.attachmentId()))
                    .isInstanceOfSatisfying(ServiceException.class, exception ->
                    {
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(exception.getMessage()).isEqualTo("无权访问当前工作流附件");
                    });

            Deployment deployment = repositoryService.createDeployment()
                    .name(DEPLOYMENT_NAME_PREFIX + UUID.randomUUID())
                    .addClasspathResource("processes/flowable-attachment-task-it.bpmn20.xml")
                    .deploy();
            deploymentId = deployment.getId();
            var processDefinition = repositoryService.createProcessDefinitionQuery()
                    .deploymentId(deploymentId)
                    .singleResult();
            assertThat(processDefinition).as("附件 IT 部署必须只产生一个流程定义").isNotNull();
            repositoryService.addCandidateStarterUser(
                    processDefinition.getId(), String.valueOf(OWNER_USER_ID));
            insertStartFormSnapshot(jdbcTemplate, deploymentId);

            setSecurityContextUser(OWNER_USER_ID);
            byte[] originalStartIntegrityContent = "start-integrity-a"
                    .getBytes(StandardCharsets.UTF_8);
            byte[] replacedStartIntegrityContent = "start-integrity-b"
                    .getBytes(StandardCharsets.UTF_8);
            WorkflowAttachmentView tamperedStartAttachment = attachmentService.uploadTemporary(
                    "files", new MockMultipartFile("file", "tampered-start.txt",
                            "text/plain", originalStartIntegrityContent));
            attachmentIds.add(tamperedStartAttachment.attachmentId());
            String tamperedStartStorageKey = queryStorageKey(
                    jdbcTemplate, tamperedStartAttachment.attachmentId());
            storageKeysById.put(tamperedStartAttachment.attachmentId(),
                    tamperedStartStorageKey);
            overwriteStoredAttachmentSameLength(tamperedStartStorageKey,
                    originalStartIntegrityContent, replacedStartIntegrityContent);

            // 文件正文被同长度替换时，发起事务必须连同已创建的引擎实例整体回滚。
            assertThatThrownBy(() -> processStartService.start(
                    new StartProcessRequest(processDefinition.getId(),
                            tamperedStartBusinessKey,
                            Map.of("reason", "验证发起附件摘要", "amount", 1,
                                    "files", List.of(
                                            tamperedStartAttachment.attachmentId())))))
                    .isInstanceOfSatisfying(ServiceException.class, exception ->
                    {
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
                        assertThat(exception.getMessage())
                                .isEqualTo("工作流附件文件完整性校验失败");
                    });
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceBusinessKey(tamperedStartBusinessKey).count()).isZero();
            assertThat(historyService.createHistoricProcessInstanceQuery()
                    .processInstanceBusinessKey(tamperedStartBusinessKey).count()).isZero();
            assertRolledBackTemporaryAttachment(
                    jdbcTemplate, tamperedStartAttachment.attachmentId());

            WorkflowProcessInstanceSnapshot successfulInstance = processStartService.start(
                    new StartProcessRequest(processDefinition.getId(), successfulBusinessKey,
                            Map.of("reason", "采购设备", "amount", 1280,
                                    "files", List.of(successfulStartAttachment.attachmentId()))));
            assertPersistedBoundAttachment(jdbcTemplate, runtimeService,
                    successfulInstance, successfulStartAttachment, successfulContent);

            byte[] taskContent = "task attachment".getBytes(StandardCharsets.UTF_8);
            WorkflowAttachmentView successfulTaskAttachment = attachmentService.uploadTemporary(
                    "files", new MockMultipartFile("file", "review.txt",
                            "text/plain", taskContent));
            attachmentIds.add(successfulTaskAttachment.attachmentId());
            storageKeysById.put(successfulTaskAttachment.attachmentId(),
                    queryStorageKey(jdbcTemplate, successfulTaskAttachment.attachmentId()));
            Task successfulReviewTask = requireSingleTask(
                    taskService, successfulInstance.id(), "review");
            taskLifecycleService.completeTask(new WorkflowTaskCompleteRequest(
                    successfulReviewTask.getId(), "审核附件通过",
                    Map.of("files", List.of(successfulStartAttachment.attachmentId(),
                            successfulTaskAttachment.attachmentId()))));

            assertPersistedTaskBoundAttachment(jdbcTemplate, successfulInstance.id(),
                    successfulReviewTask, successfulTaskAttachment);
            assertStartAttachmentAttributionUnchanged(jdbcTemplate,
                    successfulStartAttachment.attachmentId(), successfulInstance.id());
            assertRuntimeAttachmentIds(runtimeService, successfulInstance.id(),
                    successfulStartAttachment.attachmentId(),
                    successfulTaskAttachment.attachmentId());

            // 后续节点显式移除全部引用只覆盖流程变量，不删除或解绑已形成的审计证据。
            Task successfulConfirmTask = requireSingleTask(
                    taskService, successfulInstance.id(), "confirm");
            taskLifecycleService.completeTask(new WorkflowTaskCompleteRequest(
                    successfulConfirmTask.getId(), "确认归档", Map.of("files", List.of())));
            assertThat(historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(successfulInstance.id()).singleResult().getEndTime())
                    .isNotNull();
            assertHistoricAttachmentVariableEmpty(historyService, successfulInstance.id());
            assertThat(attachmentMapper.countBoundByProcessInstanceIds(
                    Set.of(successfulInstance.id()))).isEqualTo(2L);
            assertHistoryDeletionBlockedByBoundAttachments(jdbcTemplate, historyService,
                    successfulInstance, 2L);

            WorkflowAttachmentView rollbackStartAttachment = attachmentService.uploadTemporary(
                    "files", new MockMultipartFile("file", "rollback-start.txt",
                            "text/plain", "start".getBytes(StandardCharsets.UTF_8)));
            attachmentIds.add(rollbackStartAttachment.attachmentId());
            storageKeysById.put(rollbackStartAttachment.attachmentId(),
                    queryStorageKey(jdbcTemplate, rollbackStartAttachment.attachmentId()));
            WorkflowProcessInstanceSnapshot rollbackInstance = processStartService.start(
                    new StartProcessRequest(processDefinition.getId(), rollbackBusinessKey,
                            Map.of("reason", "验证任务整体回滚", "amount", 2,
                                    "files", List.of(rollbackStartAttachment.attachmentId()))));
            Task rollbackReviewTask = requireSingleTask(
                    taskService, rollbackInstance.id(), "review");
            int baselineCommentCount = taskService.getTaskComments(
                    rollbackReviewTask.getId()).size();

            byte[] originalTaskIntegrityContent = "task-integrity-a"
                    .getBytes(StandardCharsets.UTF_8);
            byte[] replacedTaskIntegrityContent = "task-integrity-b"
                    .getBytes(StandardCharsets.UTF_8);
            WorkflowAttachmentView tamperedTaskAttachment = attachmentService.uploadTemporary(
                    "files", new MockMultipartFile("file", "tampered-task.txt",
                            "text/plain", originalTaskIntegrityContent));
            attachmentIds.add(tamperedTaskAttachment.attachmentId());
            String tamperedTaskStorageKey = queryStorageKey(
                    jdbcTemplate, tamperedTaskAttachment.attachmentId());
            storageKeysById.put(tamperedTaskAttachment.attachmentId(),
                    tamperedTaskStorageKey);
            overwriteStoredAttachmentSameLength(tamperedTaskStorageKey,
                    originalTaskIntegrityContent, replacedTaskIntegrityContent);

            // 任务附件摘要失败必须回滚意见、变量、任务完成和附件状态迁移。
            assertThatThrownBy(() -> taskLifecycleService.completeTask(
                    new WorkflowTaskCompleteRequest(rollbackReviewTask.getId(),
                            "验证任务附件摘要",
                            Map.of("files", List.of(
                                    rollbackStartAttachment.attachmentId(),
                                    tamperedTaskAttachment.attachmentId())))))
                    .isInstanceOfSatisfying(ServiceException.class, exception ->
                    {
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
                        assertThat(exception.getMessage())
                                .isEqualTo("工作流附件文件完整性校验失败");
                    });
            assertTaskAndVariableUnchangedAfterFailure(taskService, runtimeService,
                    rollbackReviewTask, baselineCommentCount,
                    rollbackStartAttachment.attachmentId());
            assertThat(historyService.createHistoricTaskInstanceQuery()
                    .taskId(rollbackReviewTask.getId()).finished().count()).isZero();
            assertRolledBackTemporaryAttachment(
                    jdbcTemplate, tamperedTaskAttachment.attachmentId());

            // 已绑定附件可由后续办理人复用，但绝不允许跨流程实例借用。
            assertThatThrownBy(() -> taskLifecycleService.completeTask(
                    new WorkflowTaskCompleteRequest(rollbackReviewTask.getId(), "跨实例引用",
                            Map.of("files", List.of(
                                    successfulStartAttachment.attachmentId())))))
                    .isInstanceOfSatisfying(ServiceException.class, exception ->
                    {
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(exception.getMessage()).isEqualTo("无权访问当前工作流附件");
                    });
            assertTaskAndVariableUnchangedAfterFailure(taskService, runtimeService,
                    rollbackReviewTask, baselineCommentCount,
                    rollbackStartAttachment.attachmentId());

            WorkflowAttachmentView firstRollbackAttachment = attachmentService.uploadTemporary(
                    "files", new MockMultipartFile("file", "rollback-first.txt",
                            "text/plain", "first".getBytes(StandardCharsets.UTF_8)));
            WorkflowAttachmentView secondRollbackAttachment = attachmentService.uploadTemporary(
                    "files", new MockMultipartFile("file", "rollback-second.txt",
                            "text/plain", "second".getBytes(StandardCharsets.UTF_8)));
            attachmentIds.add(firstRollbackAttachment.attachmentId());
            attachmentIds.add(secondRollbackAttachment.attachmentId());
            storageKeysById.put(firstRollbackAttachment.attachmentId(),
                    queryStorageKey(jdbcTemplate, firstRollbackAttachment.attachmentId()));
            storageKeysById.put(secondRollbackAttachment.attachmentId(),
                    queryStorageKey(jdbcTemplate, secondRollbackAttachment.attachmentId()));

            createSecondBindingFailureConstraint(secondRollbackAttachment.attachmentId());
            failureConstraintCreated = true;
            WorkflowTaskCompleteRequest rollbackRequest = new WorkflowTaskCompleteRequest(
                    rollbackReviewTask.getId(), "触发第二附件数据库失败",
                    Map.of("files", List.of(rollbackStartAttachment.attachmentId(),
                            firstRollbackAttachment.attachmentId(),
                            secondRollbackAttachment.attachmentId())));

            assertThatThrownBy(() -> taskLifecycleService.completeTask(rollbackRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasRootCauseInstanceOf(SQLException.class)
                    .rootCause()
                    .hasMessageContaining(FAILURE_CHECK_CONSTRAINT);

            assertTaskAndVariableUnchangedAfterFailure(taskService, runtimeService,
                    rollbackReviewTask, baselineCommentCount,
                    rollbackStartAttachment.attachmentId());
            assertThat(historyService.createHistoricTaskInstanceQuery()
                    .taskId(rollbackReviewTask.getId()).finished().count()).isZero();
            assertRolledBackTemporaryAttachment(
                    jdbcTemplate, firstRollbackAttachment.attachmentId());
            assertRolledBackTemporaryAttachment(
                    jdbcTemplate, secondRollbackAttachment.attachmentId());
            assertStartAttachmentAttributionUnchanged(jdbcTemplate,
                    rollbackStartAttachment.attachmentId(), rollbackInstance.id());
        }
        finally
        {
            SecurityContextHolder.clearContext();
            if (failureConstraintCreated)
            {
                dropSecondBindingFailureConstraint();
            }
            deleteStoredFiles(storageKeysById);
            if (!attachmentIds.isEmpty())
            {
                jdbcTemplate.update("delete from wf_attachment where attachment_id in ("
                        + String.join(",", attachmentIds.stream().map(id -> "?").toList())
                        + ")", attachmentIds.toArray());
            }
            if (deploymentId != null)
            {
                jdbcTemplate.update("delete from wf_deploy_form where deploy_id = ?", deploymentId);
            }
            jdbcTemplate.update("delete from wf_form where form_id = ?", FORM_ID);
            deleteDeploymentIfPresent(repositoryService, deploymentId);
            jdbcTemplate.update(
                    "delete from wf_attachment_quota_guard where owner_user_id in (?, ?)",
                    OWNER_USER_ID, FOREIGN_USER_ID);
            jdbcTemplate.update("delete from sys_user where user_id in (?, ?)",
                    OWNER_USER_ID, FOREIGN_USER_ID);

            if (!attachmentIds.isEmpty())
            {
                assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from wf_attachment where attachment_id in ("
                                + String.join(",", attachmentIds.stream()
                                        .map(id -> "?").toList())
                                + ")", Long.class, attachmentIds.toArray())).isZero();
            }
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceBusinessKey(successfulBusinessKey).count()).isZero();
            assertThat(historyService.createHistoricProcessInstanceQuery()
                    .processInstanceBusinessKey(successfulBusinessKey).count()).isZero();
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceBusinessKey(rollbackBusinessKey).count()).isZero();
            assertThat(historyService.createHistoricProcessInstanceQuery()
                    .processInstanceBusinessKey(rollbackBusinessKey).count()).isZero();
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceBusinessKey(tamperedStartBusinessKey).count()).isZero();
            assertThat(historyService.createHistoricProcessInstanceQuery()
                    .processInstanceBusinessKey(tamperedStartBusinessKey).count()).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_attachment_quota_guard "
                            + "where owner_user_id in (?, ?)", Long.class,
                    OWNER_USER_ID, FOREIGN_USER_ID)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from sys_user where user_id in (?, ?)", Long.class,
                    OWNER_USER_ID, FOREIGN_USER_ID)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from information_schema.table_constraints "
                            + "where constraint_schema = ? and table_name = 'wf_attachment' "
                            + "and constraint_name = ? and constraint_type = 'CHECK'",
                    Long.class, expectedSchema, FAILURE_CHECK_CONSTRAINT)).isZero();
            assertThat(countStoredRegularFiles()).isZero();
        }
    }

    /**
     * 测试类结束后删除独占 profile，确保磁盘不残留上传文件或临时目录。
     * @return void，无返回值
     * @throws IOException 遍历或删除临时目录失败
     */
    @AfterAll
    static void deleteProfileRoot() throws IOException
    {
        if (!Files.exists(PROFILE_ROOT))
        {
            return;
        }
        try (var paths = Files.walk(PROFILE_ROOT))
        {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * 在系统临时目录创建当前测试类独占的 profile 根。
     * @return Path，已创建且规范化的绝对目录
     */
    private static Path createProfileRoot()
    {
        try
        {
            return Files.createTempDirectory("approvaplat-workflow-attachment-it-")
                    .toAbsolutePath().normalize();
        }
        catch (IOException exception)
        {
            throw new ExceptionInInitializerError(exception);
        }
    }

    /**
     * 在真实 MySQL 事务和私有磁盘上并发上传，并核对成功数、聚合字节和 guard 行。
     * @param maxTemporaryCount int，测试期间单用户 TEMP 数量上限
     * @param maxTemporaryBytes long，测试期间单用户 TEMP 累计字节上限
     * @param contents List&lt;byte[]&gt;，每个并发请求的真实文件正文
     * @param expectedCount long，事务提交后预期 TEMP 行数
     * @param expectedBytes long，事务提交后预期 TEMP 文件累计字节数
     * @return void，配额超卖、错误码不稳定或数据库与磁盘清理不完整时测试失败
     * @throws Exception 并发线程等待、结果收集或磁盘残留核验失败
     */
    private void verifyConcurrentTemporaryQuota(int maxTemporaryCount,
            long maxTemporaryBytes, List<byte[]> contents,
            long expectedCount, long expectedBytes) throws Exception
    {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dynamicDataSource);
        int originalMaxTemporaryCount = attachmentProperties.getMaxTemporaryCount();
        long originalMaxTemporaryBytes = attachmentProperties.getMaxTemporaryBytes();
        try
        {
            insertTestUsers(jdbcTemplate);
            attachmentProperties.setMaxTemporaryCount(maxTemporaryCount);
            attachmentProperties.setMaxTemporaryBytes(maxTemporaryBytes);

            List<ConcurrentUploadOutcome> outcomes = uploadConcurrently(contents);
            List<WorkflowAttachmentView> successfulAttachments = outcomes.stream()
                    .map(ConcurrentUploadOutcome::attachment)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            List<RuntimeException> failures = outcomes.stream()
                    .map(ConcurrentUploadOutcome::failure)
                    .filter(java.util.Objects::nonNull)
                    .toList();

            assertThat(successfulAttachments).hasSize(Math.toIntExact(expectedCount));
            assertThat(failures).hasSize(contents.size() - Math.toIntExact(expectedCount));
            failures.forEach(failure -> assertThat(failure)
                    .isInstanceOfSatisfying(ServiceException.class, exception ->
                    {
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(exception.getMessage())
                                .isEqualTo("工作流临时附件数量或总大小已达到上限");
                    }));

            Map<String, Object> usage = jdbcTemplate.queryForMap(
                    "select count(*) as temporary_count, "
                            + "coalesce(sum(file_size), 0) as temporary_bytes "
                            + "from wf_attachment where owner_user_id = ? "
                            + "and attachment_status = 'TEMP'",
                    OWNER_USER_ID);
            assertThat(((Number) usage.get("temporary_count")).longValue())
                    .isEqualTo(expectedCount);
            assertThat(((Number) usage.get("temporary_bytes")).longValue())
                    .isEqualTo(expectedBytes);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_attachment_quota_guard "
                            + "where owner_user_id = ?", Long.class, OWNER_USER_ID))
                    .isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_attachment_quota_guard where owner_user_id = 0",
                    Long.class)).isEqualTo(1L);
        }
        finally
        {
            SecurityContextHolder.clearContext();
            try
            {
                cleanupConcurrentQuotaTestData(jdbcTemplate);
            }
            finally
            {
                // 配额配置是应用单例状态，清理失败也不能污染同一 JVM 中的后续测试。
                attachmentProperties.setMaxTemporaryCount(originalMaxTemporaryCount);
                attachmentProperties.setMaxTemporaryBytes(originalMaxTemporaryBytes);
            }

            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_attachment where owner_user_id = ?",
                    Long.class, OWNER_USER_ID)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_attachment_quota_guard "
                            + "where owner_user_id = ?", Long.class, OWNER_USER_ID)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_attachment_quota_guard where owner_user_id = 0",
                    Long.class)).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from sys_user where user_id in (?, ?)", Long.class,
                    OWNER_USER_ID, FOREIGN_USER_ID)).isZero();
            assertThat(countStoredRegularFiles()).isZero();
        }
    }

    /**
     * 让全部上传线程就绪后同时进入正式附件服务，并为每个线程独立设置和清理身份上下文。
     * @param contents List&lt;byte[]&gt;，每个并发请求的附件正文
     * @return List&lt;ConcurrentUploadOutcome&gt;，按请求顺序保存成功附件或运行时失败
     * @throws Exception 线程未按时就绪、结果超时或执行器无法终止
     */
    private List<ConcurrentUploadOutcome> uploadConcurrently(List<byte[]> contents)
            throws Exception
    {
        return uploadConcurrently(contents,
                contents.stream().map(content -> OWNER_USER_ID).toList());
    }

    /**
     * 让不同身份的上传线程同时进入正式附件服务，验证全局 guard 可跨用户串行化容量检查。
     * @param contents List&lt;byte[]&gt;，每个并发请求的附件正文
     * @param userIds List&lt;Long&gt;，与正文顺序一一对应的正式用户主键
     * @return List&lt;ConcurrentUploadOutcome&gt;，按请求顺序保存成功附件或运行时失败
     * @throws Exception 参数不一致、线程未按时就绪、结果超时或执行器无法终止
     */
    private List<ConcurrentUploadOutcome> uploadConcurrently(List<byte[]> contents,
            List<Long> userIds) throws Exception
    {
        if (contents.size() != userIds.size() || contents.isEmpty())
        {
            throw new IllegalArgumentException("并发附件正文与用户必须非空且一一对应");
        }
        ExecutorService executor = Executors.newFixedThreadPool(contents.size());
        CountDownLatch ready = new CountDownLatch(contents.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ConcurrentUploadOutcome>> futures = new ArrayList<>();
        try
        {
            for (int index = 0; index < contents.size(); index++)
            {
                int requestIndex = index;
                byte[] content = contents.get(index);
                Long userId = userIds.get(index);
                futures.add(executor.submit(() ->
                {
                    setSecurityContextUser(userId);
                    ready.countDown();
                    try
                    {
                        if (!start.await(15L, TimeUnit.SECONDS))
                        {
                            throw new IllegalStateException("并发附件上传启动超时");
                        }
                        WorkflowAttachmentView attachment = attachmentService.uploadTemporary(
                                "files", new MockMultipartFile("file",
                                        "quota-" + requestIndex + ".txt",
                                        "text/plain", content));
                        return new ConcurrentUploadOutcome(attachment, null);
                    }
                    catch (InterruptedException exception)
                    {
                        Thread.currentThread().interrupt();
                        return new ConcurrentUploadOutcome(null,
                                new IllegalStateException("并发附件上传线程被中断", exception));
                    }
                    catch (RuntimeException failure)
                    {
                        return new ConcurrentUploadOutcome(null, failure);
                    }
                    finally
                    {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }

            assertThat(ready.await(15L, TimeUnit.SECONDS))
                    .as("全部附件上传线程必须先进入就绪状态")
                    .isTrue();
            start.countDown();
            List<ConcurrentUploadOutcome> outcomes = new ArrayList<>();
            for (Future<ConcurrentUploadOutcome> future : futures)
            {
                outcomes.add(future.get(30L, TimeUnit.SECONDS));
            }
            return List.copyOf(outcomes);
        }
        finally
        {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15L, TimeUnit.SECONDS))
                    .as("并发附件上传线程池必须按时退出")
                    .isTrue();
        }
    }

    /**
     * 删除两个并发配额测试用户的附件文件、元数据、用户 guard 行和正式测试账号。
     * @param jdbcTemplate JdbcTemplate，隔离 schema 的真实 JDBC 客户端
     * @return void，清理失败时抛出数据库或存储异常
     */
    private void cleanupConcurrentQuotaTestData(JdbcTemplate jdbcTemplate)
    {
        List<String> storageKeys = jdbcTemplate.queryForList(
                "select storage_key from wf_attachment where owner_user_id in (?, ?)",
                String.class, OWNER_USER_ID, FOREIGN_USER_ID);
        storageKeys.forEach(attachmentStorage::delete);
        jdbcTemplate.update("delete from wf_attachment where owner_user_id in (?, ?)",
                OWNER_USER_ID, FOREIGN_USER_ID);
        jdbcTemplate.update("delete from wf_attachment_quota_guard where owner_user_id in (?, ?)",
                OWNER_USER_ID, FOREIGN_USER_ID);
        jdbcTemplate.update("delete from sys_user where user_id in (?, ?)",
                OWNER_USER_ID, FOREIGN_USER_ID);
    }

    /**
     * 统计测试独占 profile 下尚未清理的所有普通文件，包括上传临时文件。
     * @return long，当前测试 profile 内普通文件数量
     * @throws IOException 遍历测试 profile 失败
     */
    private long countStoredRegularFiles() throws IOException
    {
        if (!Files.exists(PROFILE_ROOT))
        {
            return 0L;
        }
        try (var paths = Files.walk(PROFILE_ROOT))
        {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    /**
     * 写入两个有效若依用户，身份解析器后续会再次从正式主数据核验状态。
     * @param jdbcTemplate JdbcTemplate，隔离 schema 的真实 JDBC 客户端
     * @return void，无返回值
     */
    private void insertTestUsers(JdbcTemplate jdbcTemplate)
    {
        int owner = jdbcTemplate.update(
                "insert into sys_user (user_id, dept_id, user_name, nick_name, status, del_flag) "
                        + "values (?, 100, ?, ?, '0', '0')",
                OWNER_USER_ID, "wf_attach_owner_121", "附件集成测试上传者");
        int foreign = jdbcTemplate.update(
                "insert into sys_user (user_id, dept_id, user_name, nick_name, status, del_flag) "
                        + "values (?, 100, ?, ?, '0', '0')",
                FOREIGN_USER_ID, "wf_attach_foreign_122", "附件集成测试第二用户");
        assertThat(owner).isEqualTo(1);
        assertThat(foreign).isEqualTo(1);
    }

    /**
     * 将指定有效用户写入当前线程 Spring SecurityContext。
     * @param userId long，已存在且有效的 sys_user 主键
     * @return void，无返回值
     */
    private void setSecurityContextUser(long userId)
    {
        setSecurityContextUser(userId, Set.of());
    }

    /**
     * 将指定有效用户和本场景授权集合写入当前线程 Spring SecurityContext。
     * @param userId long，已存在且有效的 sys_user 主键
     * @param permissions Set&lt;String&gt;，服务端领域权限门禁使用的正式权限码集合
     * @return void，无返回值
     */
    private void setSecurityContextUser(long userId, Set<String> permissions)
    {
        SysUser user = new SysUser(userId);
        user.setUserName("workflow_attachment_it_" + userId);
        user.setNickName("附件集成测试用户 " + userId);
        LoginUser loginUser = new LoginUser(userId, 100L, user, permissions);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        loginUser, null, loginUser.getAuthorities());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    /**
     * 验证已完成流程的历史删除在任何写入前被已绑定附件审计门禁拒绝。
     * @param jdbcTemplate JdbcTemplate，隔离 schema 的真实 JDBC 客户端
     * @param historyService HistoryService，读取拒绝前后真实历史实例
     * @param instance WorkflowProcessInstanceSnapshot，已经成功绑定附件的流程实例
     * @param expectedAttachmentCount long，该实例应保留的 BOUND 附件数量
     * @return void，无返回值
     */
    private void assertHistoryDeletionBlockedByBoundAttachments(JdbcTemplate jdbcTemplate,
            HistoryService historyService, WorkflowProcessInstanceSnapshot instance,
            long expectedAttachmentCount)
    {
        assertThat(historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(instance.id()).singleResult().getEndTime()).isNotNull();

        assertThat(attachmentMapper.countBoundByProcessInstanceIds(
                Set.of(instance.id(), "missing-instance")))
                .isEqualTo(expectedAttachmentCount);
        assertThat(attachmentMapper.countBoundByProcessInstanceIds(Set.of())).isZero();
        // 本用例验证附件审计冲突，复用真实超级管理员避免伪造普通用户的数据库菜单授权。
        setSecurityContextUser(1L, Set.of("workflow:process:remove"));
        try
        {
            assertThatThrownBy(() -> processInstanceService.deleteCompletedHistory(
                    List.of(instance.id())))
                    .isInstanceOfSatisfying(ServiceException.class, exception ->
                    {
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(exception.getMessage())
                                .isEqualTo("流程历史存在已绑定附件，不能删除");
                    });

            assertThat(historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(instance.id()).count()).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_attachment where attachment_status = 'BOUND' "
                            + "and process_instance_id = ?",
                    Long.class, instance.id())).isEqualTo(expectedAttachmentCount);
        }
        finally
        {
            // 后续附件链继续由原发起人办理，避免管理员上下文泄漏到下一段场景。
            setSecurityContextUser(OWNER_USER_ID);
        }
    }

    /**
     * 写入可编辑表单和当前部署开始、审核、确认三个不可变表单快照。
     * @param jdbcTemplate JdbcTemplate，隔离 schema 的真实 JDBC 客户端
     * @param deploymentId String，真实 Flowable 部署主键
     * @return void，无返回值
     */
    private void insertStartFormSnapshot(JdbcTemplate jdbcTemplate, String deploymentId)
    {
        assertThat(jdbcTemplate.update(
                "insert into wf_form (form_id, form_name, content, create_by, del_flag) "
                        + "values (?, ?, ?, ?, '0')",
                FORM_ID, "附件集成测试表单", START_FORM_CONTENT,
                "workflow-attachment-it")).isEqualTo(1);
        assertThat(jdbcTemplate.update(
                "insert into wf_deploy_form "
                        + "(deploy_id, form_id, form_key, node_key, form_name, node_name, "
                        + "content, create_by, del_flag) "
                        + "values (?, ?, ?, 'start', ?, ?, ?, ?, '0')",
                deploymentId, FORM_ID, START_FORM_KEY, "附件集成测试表单", "发起申请",
                START_FORM_CONTENT, "workflow-attachment-it")).isEqualTo(1);
        assertThat(jdbcTemplate.update(
                "insert into wf_deploy_form "
                        + "(deploy_id, form_id, form_key, node_key, form_name, node_name, "
                        + "content, create_by, del_flag) "
                        + "values (?, ?, ?, 'review', ?, ?, ?, ?, '0')",
                deploymentId, FORM_ID, REVIEW_FORM_KEY, "附件审核表单", "审核附件",
                TASK_FORM_CONTENT, "workflow-attachment-it")).isEqualTo(1);
        assertThat(jdbcTemplate.update(
                "insert into wf_deploy_form "
                        + "(deploy_id, form_id, form_key, node_key, form_name, node_name, "
                        + "content, create_by, del_flag) "
                        + "values (?, ?, ?, 'confirm', ?, ?, ?, ?, '0')",
                deploymentId, FORM_ID, CONFIRM_FORM_KEY, "附件确认表单", "确认归档",
                TASK_FORM_CONTENT, "workflow-attachment-it")).isEqualTo(1);
    }

    /**
     * 核对临时附件元数据和磁盘内容均来自服务端真实计算。
     * @param jdbcTemplate JdbcTemplate，隔离 schema 的真实 JDBC 客户端
     * @param attachment WorkflowAttachmentView，上传接口返回的安全元数据
     * @param expectedContent byte[]，测试上传的真实文件内容
     * @return void，无返回值
     */
    private void assertPersistedTemporaryAttachment(JdbcTemplate jdbcTemplate,
            WorkflowAttachmentView attachment, byte[] expectedContent)
    {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select owner_user_id, field_name, original_name, storage_key, content_type, "
                        + "file_size, sha256, attachment_status, process_instance_id "
                        + "from wf_attachment where attachment_id = ?",
                attachment.attachmentId());
        assertThat(((Number) row.get("owner_user_id")).longValue()).isEqualTo(OWNER_USER_ID);
        assertThat(row.get("field_name")).isEqualTo("files");
        assertThat(row.get("original_name")).isEqualTo("invoice.pdf");
        assertThat(row.get("attachment_status")).isEqualTo("TEMP");
        assertThat(row.get("process_instance_id")).isNull();
        assertThat(attachment.processInstanceId()).isNull();
        assertThat(attachment.taskId()).isNull();
        assertThat(attachment.nodeKey()).isNull();
        assertThat(((Number) row.get("file_size")).longValue()).isEqualTo(expectedContent.length);
        assertThat(row.get("sha256")).isEqualTo(attachment.sha256());
        assertThat(row.get("content_type")).isEqualTo(attachment.contentType());
        assertThat(row.get("storage_key").toString())
                .doesNotContain("invoice", "profile", "http", "..", "\\");

        WorkflowAttachmentDownload download = attachmentService.openReadableDownload(
                attachment.attachmentId());
        assertThat(download.fileSize()).isEqualTo(expectedContent.length);
        assertThat(download.sha256()).isEqualTo(attachment.sha256());
        assertThat(readBytes(download.content())).isEqualTo(expectedContent);
    }

    /**
     * 核对成功发起后的附件绑定、真实运行变量和物理变量表安全投影。
     * @param jdbcTemplate JdbcTemplate，隔离 schema 的真实 JDBC 客户端
     * @param runtimeService RuntimeService，真实 Flowable 运行时服务
     * @param instance WorkflowProcessInstanceSnapshot，新建流程实例快照
     * @param attachment WorkflowAttachmentView，已绑定的上传附件
     * @param expectedContent byte[]，预期仍可下载的原始文件内容
     * @return void，无返回值
     */
    private void assertPersistedBoundAttachment(JdbcTemplate jdbcTemplate,
            RuntimeService runtimeService, WorkflowProcessInstanceSnapshot instance,
            WorkflowAttachmentView attachment, byte[] expectedContent)
    {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select attachment_status, process_instance_id, task_id, node_key, "
                        + "bound_time, storage_deleted_time "
                        + "from wf_attachment where attachment_id = ?",
                attachment.attachmentId());
        assertThat(row.get("attachment_status")).isEqualTo("BOUND");
        assertThat(row.get("process_instance_id")).isEqualTo(instance.id());
        assertThat(row.get("task_id")).isNull();
        assertThat(row.get("node_key")).isEqualTo("start");
        assertThat(row.get("bound_time")).isNotNull();
        assertThat(row.get("storage_deleted_time")).isNull();

        JsonNode attachmentVariable = objectMapper.valueToTree(
                runtimeService.getVariable(instance.id(), "files"));
        assertThat(attachmentVariable.isArray()).isTrue();
        assertThat(attachmentVariable.size()).isEqualTo(1);
        JsonNode projection = attachmentVariable.path(0);
        assertThat(projection.size()).isEqualTo(6);
        assertThat(projection.path("attachmentId").asText())
                .isEqualTo(attachment.attachmentId());
        assertThat(projection.path("fieldName").asText()).isEqualTo("files");
        assertThat(projection.path("originalName").asText()).isEqualTo("invoice.pdf");
        assertThat(projection.path("contentType").asText()).isEqualTo(attachment.contentType());
        assertThat(projection.path("fileSize").asLong()).isEqualTo(expectedContent.length);
        assertThat(projection.path("sha256").asText()).isEqualTo(attachment.sha256());
        assertThat(projection.toString()).doesNotContain(
                "storageKey", "ownerUserId", "processInstanceId", "workflow-attachments", "url");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from act_ru_variable "
                        + "where proc_inst_id_ = ? and name_ = 'files'",
                Long.class, instance.id())).isEqualTo(1L);

        WorkflowAttachmentDownload download = attachmentService.openReadableDownload(
                attachment.attachmentId());
        assertThat(readBytes(download.content())).isEqualTo(expectedContent);
        WorkflowAttachmentView boundMetadata = attachmentService.getReadableMetadata(
                attachment.attachmentId());
        assertThat(boundMetadata.processInstanceId()).isEqualTo(instance.id());
        assertThat(boundMetadata.taskId()).isNull();
        assertThat(boundMetadata.nodeKey()).isEqualTo("start");
    }

    /**
     * 查询流程实例在指定 BPMN 节点的唯一活动任务，并核对真实办理人。
     * @param taskService TaskService，Flowable 任务公共 API
     * @param processInstanceId String，真实流程实例主键
     * @param nodeKey String，预期活动任务 BPMN 节点 key
     * @return Task，已核对实例、节点和办理人的活动任务
     */
    private Task requireSingleTask(TaskService taskService, String processInstanceId,
            String nodeKey)
    {
        Task task = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(nodeKey)
                .active()
                .singleResult();
        assertThat(task).as("指定实例和节点必须存在唯一活动任务").isNotNull();
        assertThat(task.getProcessInstanceId()).isEqualTo(processInstanceId);
        assertThat(task.getTaskDefinitionKey()).isEqualTo(nodeKey);
        assertThat(task.getAssignee()).isEqualTo(String.valueOf(OWNER_USER_ID));
        return task;
    }

    /**
     * 核对任务完成新附件真实绑定到实例、任务和 BPMN 节点，且安全元数据可回显归属。
     * @param jdbcTemplate JdbcTemplate，隔离 schema 的真实 JDBC 客户端
     * @param processInstanceId String，附件所属流程实例主键
     * @param task Task，首次提交附件的真实任务
     * @param attachment WorkflowAttachmentView，任务完成前上传的临时附件
     * @return void，数据库或接口归属字段不一致时测试失败
     */
    private void assertPersistedTaskBoundAttachment(JdbcTemplate jdbcTemplate,
            String processInstanceId, Task task, WorkflowAttachmentView attachment)
    {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select attachment_status, process_instance_id, task_id, node_key, bound_time "
                        + "from wf_attachment where attachment_id = ?",
                attachment.attachmentId());
        assertThat(row.get("attachment_status")).isEqualTo("BOUND");
        assertThat(row.get("process_instance_id")).isEqualTo(processInstanceId);
        assertThat(row.get("task_id")).isEqualTo(task.getId());
        assertThat(row.get("node_key")).isEqualTo(task.getTaskDefinitionKey());
        assertThat(row.get("bound_time")).isNotNull();

        WorkflowAttachmentView metadata = attachmentService.getReadableMetadata(
                attachment.attachmentId());
        assertThat(metadata.processInstanceId()).isEqualTo(processInstanceId);
        assertThat(metadata.taskId()).isEqualTo(task.getId());
        assertThat(metadata.nodeKey()).isEqualTo(task.getTaskDefinitionKey());
    }

    /**
     * 核对复用或后续表单移除引用均不会改写开始附件的首次节点归属。
     * @param jdbcTemplate JdbcTemplate，隔离 schema 的真实 JDBC 客户端
     * @param attachmentId String，开始节点附件 UUID
     * @param processInstanceId String，附件所属流程实例主键
     * @return void，开始附件被二次绑定或解绑时测试失败
     */
    private void assertStartAttachmentAttributionUnchanged(JdbcTemplate jdbcTemplate,
            String attachmentId, String processInstanceId)
    {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select attachment_status, process_instance_id, task_id, node_key "
                        + "from wf_attachment where attachment_id = ?", attachmentId);
        assertThat(row.get("attachment_status")).isEqualTo("BOUND");
        assertThat(row.get("process_instance_id")).isEqualTo(processInstanceId);
        assertThat(row.get("task_id")).isNull();
        assertThat(row.get("node_key")).isEqualTo("start");
    }

    /**
     * 核对运行时上传字段只保存六项安全投影，并保持客户端提交的附件顺序。
     * @param runtimeService RuntimeService，真实 Flowable 运行时公共 API
     * @param processInstanceId String，待核对流程实例主键
     * @param expectedAttachmentIds String[]，预期安全投影中的附件 UUID 顺序
     * @return void，变量缺失、泄露内部字段或顺序漂移时测试失败
     */
    private void assertRuntimeAttachmentIds(RuntimeService runtimeService,
            String processInstanceId, String... expectedAttachmentIds)
    {
        JsonNode variable = objectMapper.valueToTree(
                runtimeService.getVariable(processInstanceId, "files"));
        assertThat(variable.isArray()).isTrue();
        List<String> actualIds = new ArrayList<>();
        variable.forEach(item ->
        {
            assertThat(item.size()).isEqualTo(6);
            assertThat(item.toString()).doesNotContain(
                    "storageKey", "ownerUserId", "processInstanceId", "taskId", "url");
            actualIds.add(item.path("attachmentId").asText());
        });
        assertThat(actualIds).containsExactly(expectedAttachmentIds);
    }

    /**
     * 核对流程结束时最后一次表单提交已把上传字段持久化为空安全数组。
     * @param historyService HistoryService，Flowable 历史公共 API
     * @param processInstanceId String，已结束流程实例主键
     * @return void，历史变量未覆盖为空数组或记录缺失时测试失败
     */
    private void assertHistoricAttachmentVariableEmpty(HistoryService historyService,
            String processInstanceId)
    {
        var historicVariable = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId)
                .variableName("files")
                .singleResult();
        assertThat(historicVariable).isNotNull();
        JsonNode value = objectMapper.valueToTree(historicVariable.getValue());
        assertThat(value.isArray()).isTrue();
        assertThat(value).isEmpty();
    }

    /**
     * 核对任务完成失败后活动任务、意见数量和原流程变量均保持事务前状态。
     * @param taskService TaskService，真实任务与意见查询 API
     * @param runtimeService RuntimeService，真实流程变量查询 API
     * @param originalTask Task，失败前的活动任务快照
     * @param expectedCommentCount int，失败前任务意见数量
     * @param expectedAttachmentId String，失败前流程变量中的唯一附件 UUID
     * @return void，任一 Flowable 副作用发生部分提交时测试失败
     */
    private void assertTaskAndVariableUnchangedAfterFailure(TaskService taskService,
            RuntimeService runtimeService, Task originalTask, int expectedCommentCount,
            String expectedAttachmentId)
    {
        Task activeTask = taskService.createTaskQuery()
                .taskId(originalTask.getId()).active().singleResult();
        assertThat(activeTask).isNotNull();
        assertThat(activeTask.getTaskDefinitionKey()).isEqualTo("review");
        assertThat(taskService.getTaskComments(originalTask.getId()))
                .hasSize(expectedCommentCount);
        assertThat(taskService.getVariableLocal(originalTask.getId(),
                WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME)).isNull();
        assertRuntimeAttachmentIds(runtimeService, originalTask.getProcessInstanceId(),
                expectedAttachmentId);
    }

    /**
     * 创建只在第二个指定任务附件转为 BOUND 时失败的 MySQL 检查约束。
     * 该约束通过独立且只具 ALTER 权限的测试账号创建，应用 DML 账号始终保持最小权限；
     * 用于精确验证第一个附件更新后第二个附件写入失败时的整体事务回滚。
     * @param failingAttachmentId String，规范 UUID 格式的第二个附件主键
     * @return void，无返回值
     * @throws SQLException DDL 连接、schema 门禁或临时约束创建失败
     */
    private void createSecondBindingFailureConstraint(String failingAttachmentId)
            throws SQLException
    {
        assertThat(failingAttachmentId)
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
                        + "[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        try (Connection connection = openDdlConnection();
                var statement = connection.createStatement())
        {
            statement.executeUpdate("alter table wf_attachment add constraint "
                    + FAILURE_CHECK_CONSTRAINT + " check (not (attachment_status = 'BOUND' "
                    + "and attachment_id = '" + failingAttachmentId + "'))");
        }
    }

    /**
     * 使用独立 DDL 账号移除本测试创建的临时检查约束。
     * @return void，无返回值
     * @throws SQLException DDL 连接、schema 门禁或约束移除失败
     */
    private void dropSecondBindingFailureConstraint() throws SQLException
    {
        try (Connection connection = openDdlConnection();
                var statement = connection.createStatement())
        {
            statement.executeUpdate("alter table wf_attachment drop check "
                    + FAILURE_CHECK_CONSTRAINT);
        }
    }

    /**
     * 创建仅供故障注入使用的 DDL 连接，并在执行任何 ALTER 前核对固定测试 schema。
     * @return Connection，catalog 已严格等于 expectedSchema 的独立 JDBC 连接
     * @throws SQLException 连接失败或 catalog 不符合隔离门禁
     */
    private Connection openDdlConnection() throws SQLException
    {
        assertThat(ddlJdbcUrl).isNotBlank();
        assertThat(ddlUsername).isNotBlank();
        assertThat(ddlPassword).isNotBlank();
        Connection connection = DriverManager.getConnection(
                ddlJdbcUrl, ddlUsername, ddlPassword);
        if (!expectedSchema.equals(connection.getCatalog()))
        {
            connection.close();
            throw new SQLException("DDL 故障注入连接未指向显式隔离 schema");
        }
        return connection;
    }

    /**
     * 断言绑定异常后附件仍处于原始 TEMP 状态且未产生任何实例关联。
     * @param jdbcTemplate JdbcTemplate，隔离 schema 的真实 JDBC 客户端
     * @param attachmentId String，待核对的附件 UUID
     * @return void，无返回值
     */
    private void assertRolledBackTemporaryAttachment(JdbcTemplate jdbcTemplate,
            String attachmentId)
    {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select attachment_status, process_instance_id, task_id, node_key, "
                        + "bound_time, storage_deleted_time "
                        + "from wf_attachment where attachment_id = ?", attachmentId);
        assertThat(row.get("attachment_status")).isEqualTo("TEMP");
        assertThat(row.get("process_instance_id")).isNull();
        assertThat(row.get("task_id")).isNull();
        assertThat(row.get("node_key")).isNull();
        assertThat(row.get("bound_time")).isNull();
        assertThat(row.get("storage_deleted_time")).isNull();
    }

    /**
     * 查询测试清理所需的内部存储键，不把该值暴露到业务响应断言中。
     * @param jdbcTemplate JdbcTemplate，隔离 schema 的真实 JDBC 客户端
     * @param attachmentId String，附件 UUID
     * @return String，数据库持久化的私有相对对象键
     */
    private String queryStorageKey(JdbcTemplate jdbcTemplate, String attachmentId)
    {
        return jdbcTemplate.queryForObject(
                "select storage_key from wf_attachment where attachment_id = ?",
                String.class, attachmentId);
    }

    /**
     * 保持文件长度不变地覆盖私有正文，证明绑定校验依赖真实 SHA-256 而非仅依赖大小。
     * @param storageKey String，数据库中的私有相对对象键
     * @param originalContent byte[]，上传时用于生成正式摘要的原始正文
     * @param replacementContent byte[]，与原文等长但摘要不同的替换正文
     * @return void，测试前置条件不满足或覆盖失败时抛出运行时异常
     */
    private void overwriteStoredAttachmentSameLength(String storageKey,
            byte[] originalContent, byte[] replacementContent)
    {
        assertThat(replacementContent).hasSameSizeAs(originalContent);
        assertThat(replacementContent).isNotEqualTo(originalContent);
        Path privateRoot = PROFILE_ROOT
                .resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME)
                .toAbsolutePath().normalize();
        Path storedFile = privateRoot.resolve(storageKey).normalize();
        assertThat(storedFile).startsWith(privateRoot);
        try
        {
            Files.write(storedFile, replacementContent,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            assertThat(Files.size(storedFile)).isEqualTo(originalContent.length);
        }
        catch (IOException exception)
        {
            throw new IllegalStateException("无法覆盖附件集成测试文件", exception);
        }
    }

    /**
     * 删除本测试已记录的全部私有文件，文件不存在按幂等清理成功处理。
     * @param storageKeysById Map&lt;String, String&gt;，附件 UUID 到内部存储键映射
     * @return void，无返回值
     */
    private void deleteStoredFiles(Map<String, String> storageKeysById)
    {
        storageKeysById.values().forEach(attachmentStorage::delete);
    }

    /**
     * 级联删除测试部署以及该部署产生的运行和历史实例。
     * @param repositoryService RepositoryService，真实 Flowable 仓储服务
     * @param deploymentId String，可为空的测试部署主键
     * @return void，无返回值
     */
    private void deleteDeploymentIfPresent(RepositoryService repositoryService,
            String deploymentId)
    {
        if (deploymentId != null && repositoryService.createDeploymentQuery()
                .deploymentId(deploymentId).count() == 1L)
        {
            repositoryService.deleteDeployment(deploymentId, true);
        }
    }

    /**
     * 读取已由存储边界验证的测试私有文件。
     * @param path Path，真实私有文件路径
     * @return byte[]，文件完整字节内容
     */
    private byte[] readBytes(InputStream content)
    {
        try (InputStream input = content)
        {
            return input.readAllBytes();
        }
        catch (IOException exception)
        {
            throw new IllegalStateException("无法读取附件集成测试文件", exception);
        }
    }

    /**
     * 单个并发上传请求的互斥结果：成功时仅 attachment 非空，失败时仅 failure 非空。
     * @param attachment WorkflowAttachmentView，事务已提交的安全附件视图
     * @param failure RuntimeException，事务已回滚的稳定运行时异常
     */
    private record ConcurrentUploadOutcome(WorkflowAttachmentView attachment,
            RuntimeException failure)
    {
    }
}

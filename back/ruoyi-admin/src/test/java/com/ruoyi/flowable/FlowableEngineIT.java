package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flowable.common.engine.impl.json.jackson3.Jackson3VariableJsonMapper;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.Model;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.task.Comment;
import org.flowable.job.service.impl.asyncexecutor.AsyncExecutor;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.task.api.DelegationState;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ruoyi.RuoYiApplication;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WorkflowInstanceState;
import com.ruoyi.flowable.domain.dto.StartProcessRequest;
import com.ruoyi.flowable.domain.dto.WorkflowModelDto;
import com.ruoyi.flowable.domain.dto.WorkflowInstanceStateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowInstanceTerminateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessCancelRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDetailQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowProcessRevokeRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskClaimRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskCompleteRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskDelegateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskRejectRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskReturnRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskTransferRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskUnclaimRequest;
import com.ruoyi.flowable.domain.vo.WorkflowIdentityOptionView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormSnapshotView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowProcessEngineAdapter;
import com.ruoyi.flowable.engine.WorkflowProcessInstanceSnapshot;
import com.ruoyi.flowable.engine.WorkflowTaskSnapshot;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;
import com.ruoyi.flowable.service.model.WorkflowModelService;
import com.ruoyi.flowable.service.process.WorkflowFormSubmissionSnapshotCodec;
import com.ruoyi.flowable.service.process.WorkflowProcessDetailService;
import com.ruoyi.flowable.service.process.WorkflowProcessInstanceService;
import com.ruoyi.flowable.service.process.WorkflowProcessStartService;
import com.ruoyi.flowable.service.task.WorkflowTaskActionService;
import com.ruoyi.flowable.service.task.WorkflowTaskLifecycleService;
import com.ruoyi.flowable.service.task.WorkflowTaskReadService;
import com.ruoyi.flowable.service.task.WorkflowUserTaskAuditService;

@SpringBootTest(
    classes = RuoYiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        "spring.datasource.druid.master.url=${FLOWABLE_IT_JDBC_URL}",
        "spring.datasource.druid.master.username=${FLOWABLE_IT_USERNAME}",
        "spring.datasource.druid.master.password=${FLOWABLE_IT_PASSWORD}",
        "spring.data.redis.database=${FLOWABLE_IT_REDIS_DATABASE:15}",
        "flowable.it.expected-schema=${FLOWABLE_IT_EXPECTED_SCHEMA}",
        "flowable.it.expected-redis-database=${FLOWABLE_IT_REDIS_DATABASE:15}",
        // 固定公开材料只用于装配 TokenService，本 IT 不创建登录 Token，禁止复用于任何部署环境。
        "token.secret=eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eA==",
        "flowable.database-schema-update=false",
        "flowable.async-executor-activate=true",
        "flowable.async-history-executor-activate=false",
        "spring.quartz.auto-startup=false"
    }
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FlowableEngineIT
{
    /** 集成测试部署名称前缀，用于在执行前后识别测试残留。 */
    private static final String DEPLOYMENT_NAME_PREFIX = "approvaplat-flowable-engine-it";
    /** 集成测试业务主键前缀，用于隔离并核对运行与历史实例。 */
    private static final String BUSINESS_KEY_PREFIX = "engine-it-";
    /** P14 真实发起流程开始节点使用的不可变表单键。 */
    private static final String PROCESS_START_FORM_KEY = "key_p14_start_form";
    /** P14 写入 wf_form 与 wf_deploy_form 的同一份开始表单 JSON。 */
    private static final String PROCESS_START_FORM_CONTENT = """
        {"fields":[
          {"__config__":{"layout":"colFormItem","tag":"el-input","required":true},
           "__vModel__":"reason","maxlength":100},
          {"__config__":{"layout":"colFormItem","tag":"el-input-number","required":true},
           "__vModel__":"amount","min":0,"max":10000}
        ]}
        """;
    /** 生命周期串行流程开始节点使用的不可变表单键。 */
    private static final String LIFECYCLE_START_FORM_KEY = "key_91001";
    /** 生命周期串行流程初审节点使用的不可变表单键。 */
    private static final String LIFECYCLE_FIRST_FORM_KEY = "key_91002";
    /** 生命周期串行流程复审节点使用的不可变表单键。 */
    private static final String LIFECYCLE_FINAL_FORM_KEY = "key_91003";
    /** 生命周期串行流程开始节点的字段白名单。 */
    private static final String LIFECYCLE_START_FORM_CONTENT = """
        {"fields":[
          {"__config__":{"layout":"colFormItem","tag":"el-input","required":true},
           "__vModel__":"requestReason","maxlength":100}
        ]}
        """;
    /** 生命周期串行流程初审节点的字段白名单。 */
    private static final String LIFECYCLE_FIRST_FORM_CONTENT = """
        {"fields":[
          {"__config__":{"layout":"colFormItem","tag":"el-input","required":true},
           "__vModel__":"firstDecision","maxlength":100}
        ]}
        """;
    /** 生命周期串行流程复审节点的字段白名单。 */
    private static final String LIFECYCLE_FINAL_FORM_CONTENT = """
        {"fields":[
          {"__config__":{"layout":"colFormItem","tag":"el-input","required":true},
           "__vModel__":"finalDecision","maxlength":100}
        ]}
        """;
    /** 历史详情真实集成流程开始节点表单键。 */
    private static final String DETAIL_HISTORY_START_FORM_KEY = "key_92001";
    /** 历史详情真实集成流程初审节点表单键。 */
    private static final String DETAIL_HISTORY_FIRST_FORM_KEY = "key_92002";
    /** 历史详情真实集成流程复审节点表单键。 */
    private static final String DETAIL_HISTORY_SECOND_FORM_KEY = "key_92003";
    /** 历史详情真实集成流程开始字段 schema。 */
    private static final String DETAIL_HISTORY_START_FORM_CONTENT = """
        {"fields":[
          {"__config__":{"layout":"colFormItem","tag":"el-input","required":true},
           "__vModel__":"requestTitle","maxlength":100}
        ]}
        """;
    /** 两个审批节点共用同名字段，用于证明后序提交不会污染前序快照。 */
    private static final String DETAIL_HISTORY_TASK_FORM_CONTENT = """
        {"fields":[
          {"__config__":{"layout":"colFormItem","tag":"el-input","required":true},
           "__vModel__":"sharedValue","maxlength":10000}
        ]}
        """;

    /** 活动变量安全集成场景使用的四字段部署表单 schema。 */
    private static final String DETAIL_RAW_VALUE_TASK_FORM_CONTENT = """
        {"fields":[
          {"__config__":{"layout":"colFormItem","tag":"el-input"},
           "__vModel__":"sharedValue"},
          {"__config__":{"layout":"colFormItem","tag":"el-input"},
           "__vModel__":"cumulativePayloadOne"},
          {"__config__":{"layout":"colFormItem","tag":"el-input"},
           "__vModel__":"cumulativePayloadTwo"},
          {"__config__":{"layout":"colFormItem","tag":"el-input"},
           "__vModel__":"cumulativePayloadThree"}
        ]}
        """;

    /** 单条历史提交快照允许的最大 UTF-8 文本字节数。 */
    private static final int HISTORY_SNAPSHOT_TEXT_LIMIT_BYTES = 2 * 1024 * 1024;
    /** 单条历史提交快照允许的最大 Java 序列化 Blob 字节数。 */
    private static final int HISTORY_SNAPSHOT_BLOB_LIMIT_BYTES =
        HISTORY_SNAPSHOT_TEXT_LIMIT_BYTES * 3 / 2 + 1024;
    /** 历史详情元数据门禁必须拒绝的超限字符串 Blob 字节数。 */
    private static final int OVERSIZED_HISTORY_SNAPSHOT_BYTES = 4 * 1024 * 1024;
    /** 两行各自合法但合计超过 4 MiB 的历史快照单行 Blob 字节数。 */
    private static final int CUMULATIVE_HISTORY_SNAPSHOT_ROW_BYTES =
        2 * 1024 * 1024 + 1024;
    /** 活动 JSON 变量单行正文的生产门禁字节数。 */
    private static final int CURRENT_VARIABLE_SINGLE_BODY_LIMIT_BYTES = 1024 * 1024;
    /** 单次活动表单全部原始正文的生产累计门禁字节数。 */
    private static final int CURRENT_VARIABLE_TOTAL_BODY_LIMIT_BYTES = 2 * 1024 * 1024;
    /** 三条 json Blob 各自使用的 ASCII 文本长度，确保单行合法且累计超过 2 MiB。 */
    private static final int CUMULATIVE_CURRENT_JSON_TEXT_LENGTH = 700_000;
    /** 记录 Flowable 是否在受控正文读取前执行了测试对象的 readObject。 */
    private static final AtomicBoolean DESERIALIZATION_CANARY_TRIGGERED = new AtomicBoolean();
    /** 跨 Spring 上下文验证使用的 JSON 变量名。 */
    private static final String JSON_VARIABLE_NAME = "approvalPayload";
    /** Redis 自动清理验证使用的唯一测试键。 */
    private static final String REDIS_MARKER_KEY = "flowable:it:cleanup-marker";
    /** 模型保存并发集成场景使用的完整可执行 BPMN，不依赖测试 schema 中的表单正文。 */
    private static final String MODEL_SAVE_BPMN_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                     xmlns:flowable="http://flowable.org/bpmn"
                     targetNamespace="https://approvaplat.example/model-save-integration-test">
          <process id="modelSaveConcurrency" name="模型保存并发集成测试" isExecutable="true">
            <startEvent id="start" name="提交" flowable:formKey="key_1"/>
            <sequenceFlow id="toEnd" sourceRef="start" targetRef="end"/>
            <endEvent id="end"/>
          </process>
        </definitions>
        """;
    /** timer/job 自动执行的最大等待时间，覆盖执行器一次正常轮询周期。 */
    private static final Duration ASYNC_EXECUTION_TIMEOUT = Duration.ofSeconds(40);
    /** Mapper 集成测试用户主键，依次表示两个正常用户、停用用户、删除用户、停用部门用户和删除部门用户。 */
    private static final List<Long> IDENTITY_TEST_USER_IDS = List.of(91L, 92L, 93L, 94L, 95L, 96L);
    /** Mapper 集成测试角色主键，依次表示两个正常角色、停用角色和删除角色。 */
    private static final List<Long> IDENTITY_TEST_ROLE_IDS = List.of(91L, 92L, 93L, 94L);
    /** Mapper 集成测试部门主键，依次表示两个正常部门、停用部门和删除部门。 */
    private static final List<Long> IDENTITY_TEST_DEPT_IDS = List.of(191L, 192L, 193L, 194L);
    /** 适配器越权门禁使用的有效非候选用户主键，必须低于 sys_user 当前 AUTO_INCREMENT。 */
    private static final long ADAPTER_NON_CANDIDATE_USER_ID = 90L;
    /** 首个测试执行前记录的 Flowable 全表行数基线。 */
    private static volatile Map<String, Long> baselineTableCounts;
    /** 完整应用启动后写入的配置、字典等 Redis 基线键数量。 */
    private static volatile Long baselineRedisKeyCount;
    /** 跨上下文测试保留的正式数据库记录定位信息。 */
    private static volatile RestartState restartState;

    private final ProcessEngine processEngine;
    private final SpringProcessEngineConfiguration engineConfiguration;
    private final DataSource dynamicDataSource;
    private final PlatformTransactionManager transactionManager;
    private final WorkflowAuthenticationContext authenticationContext;
    private final WorkflowIdentityMapper workflowIdentityMapper;
    private final WorkflowEngineOperations workflowEngineOperations;
    private final WorkflowProcessEngineAdapter workflowProcessEngineAdapter;
    private final WorkflowProcessStartService workflowProcessStartService;
    /** 真实模型保存服务，用于验证 MySQL 行锁、Flowable 模型和幂等记录的同事务闭环。 */
    private final WorkflowModelService workflowModelService;
    /** 真实历史提交快照详情服务。 */
    private final WorkflowProcessDetailService workflowProcessDetailService;
    private final WorkflowProcessInstanceService workflowProcessInstanceService;
    private final WorkflowTaskActionService workflowTaskActionService;
    private final WorkflowTaskLifecycleService workflowTaskLifecycleService;
    private final WorkflowTaskReadService workflowTaskReadService;
    private final ConfigurableApplicationContext applicationContext;
    private final RedisConnectionFactory redisConnectionFactory;
    private final RedisTemplate<Object, Object> redisTemplate;
    /** 集成测试允许访问的专用 schema，禁止复用业务库。 */
    private final String expectedSchema;
    /** 集成测试允许清理的 Redis database，必须固定为 15。 */
    private final int expectedRedisDatabase;

    /**
     * 创建完整若依应用的 Flowable 集成测试实例。
     *
     * @param processEngine ProcessEngine，应用自动配置的流程引擎
     * @param engineConfiguration SpringProcessEngineConfiguration，引擎实际配置
     * @param dynamicDataSource DataSource，若依主从路由数据源
     * @param transactionManager PlatformTransactionManager，应用统一事务管理器
     * @param authenticationContext WorkflowAuthenticationContext，工作流认证上下文
     * @param workflowIdentityMapper WorkflowIdentityMapper，工作流身份主数据查询 Mapper
     * @param workflowEngineOperations WorkflowEngineOperations，统一只读事务与异常翻译边界
     * @param workflowProcessEngineAdapter WorkflowProcessEngineAdapter，P2 流程引擎公共适配器
     * @param workflowProcessStartService WorkflowProcessStartService，P14 真实流程发起服务
     * @param workflowModelService WorkflowModelService，真实流程模型保存服务
     * @param workflowProcessDetailService WorkflowProcessDetailService，历史表单快照详情服务
     * @param workflowProcessInstanceService WorkflowProcessInstanceService，I01/I02/P15 实例写服务
     * @param workflowTaskActionService WorkflowTaskActionService，T09-T12 任务动作应用服务
     * @param workflowTaskLifecycleService WorkflowTaskLifecycleService，完成及状态迁移应用服务
     * @param workflowTaskReadService WorkflowTaskReadService，变量投影和流程图读取服务
     * @param applicationContext ConfigurableApplicationContext，当前 Spring 应用上下文
     * @param redisConnectionFactory RedisConnectionFactory，当前 Redis 连接工厂
     * @param redisTemplate RedisTemplate，写入自动清理验证键的正式 Redis 客户端
     * @param expectedSchema String，集成测试允许访问的专用 schema
     * @param expectedRedisDatabase int，集成测试允许清理的 Redis database
     * @return 无返回值，构造完成后由 Spring 测试容器管理
     */
    @Autowired
    FlowableEngineIT(
        ProcessEngine processEngine,
        SpringProcessEngineConfiguration engineConfiguration,
        @Qualifier("dynamicDataSource") DataSource dynamicDataSource,
        PlatformTransactionManager transactionManager,
        WorkflowAuthenticationContext authenticationContext,
        WorkflowIdentityMapper workflowIdentityMapper,
        WorkflowEngineOperations workflowEngineOperations,
        WorkflowProcessEngineAdapter workflowProcessEngineAdapter,
        WorkflowProcessStartService workflowProcessStartService,
        WorkflowModelService workflowModelService,
        WorkflowProcessDetailService workflowProcessDetailService,
        WorkflowProcessInstanceService workflowProcessInstanceService,
        WorkflowTaskActionService workflowTaskActionService,
        WorkflowTaskLifecycleService workflowTaskLifecycleService,
        WorkflowTaskReadService workflowTaskReadService,
        ConfigurableApplicationContext applicationContext,
        RedisConnectionFactory redisConnectionFactory,
        @Qualifier("redisTemplate") RedisTemplate<Object, Object> redisTemplate,
        @Value("${flowable.it.expected-schema}") String expectedSchema,
        @Value("${flowable.it.expected-redis-database}") int expectedRedisDatabase
    )
    {
        this.processEngine = processEngine;
        this.engineConfiguration = engineConfiguration;
        this.dynamicDataSource = dynamicDataSource;
        this.transactionManager = transactionManager;
        this.authenticationContext = authenticationContext;
        this.workflowIdentityMapper = workflowIdentityMapper;
        this.workflowEngineOperations = workflowEngineOperations;
        this.workflowProcessEngineAdapter = workflowProcessEngineAdapter;
        this.workflowProcessStartService = workflowProcessStartService;
        this.workflowModelService = workflowModelService;
        this.workflowProcessDetailService = workflowProcessDetailService;
        this.workflowProcessInstanceService = workflowProcessInstanceService;
        this.workflowTaskActionService = workflowTaskActionService;
        this.workflowTaskLifecycleService = workflowTaskLifecycleService;
        this.workflowTaskReadService = workflowTaskReadService;
        this.applicationContext = applicationContext;
        this.redisConnectionFactory = redisConnectionFactory;
        this.redisTemplate = redisTemplate;
        this.expectedSchema = expectedSchema;
        this.expectedRedisDatabase = expectedRedisDatabase;
    }

    /**
     * 在每个场景前校验数据库、事务、JSON mapper 和 Redis 隔离边界，并记录数据库与缓存基线。
     *
     * @return 无返回值；隔离配置错误或发现前序残留时测试立即失败
     */
    @BeforeEach
    void verifyIsolationBeforeEachTest() throws SQLException
    {
        assertSharedInfrastructure();
        assertRedisIsolation();
        captureRedisBaselineOnce();
        assertThat(redisTemplate.hasKey(REDIS_MARKER_KEY)).as("测试专属 Redis 键不得由前序场景残留").isFalse();
        assertThat(redisDatabaseSize()).as("Redis 必须恢复到完整应用启动后的缓存基线")
            .isEqualTo(baselineRedisKeyCount);
        captureDatabaseBaselineOnce();
    }

    /**
     * 在每个场景结束后删除测试专属 Redis 键，并验证应用自身缓存仍保持启动基线。
     *
     * @return 无返回值；不会删除完整应用启动时加载的配置和字典缓存
     */
    @AfterEach
    void cleanDedicatedRedisDatabaseAfterEachTest()
    {
        assertRedisIsolation();
        redisTemplate.delete(REDIS_MARKER_KEY);
        assertThat(redisTemplate.hasKey(REDIS_MARKER_KEY)).as("集成测试结束后不得残留测试专属 Redis 键").isFalse();
        assertThat(redisDatabaseSize()).as("清理测试键后必须恢复应用缓存基线")
            .isEqualTo(baselineRedisKeyCount);
    }

    /**
     * 验证完整若依应用中的数据源、事务、认证身份和基础流程执行闭环。
     *
     * @return 无返回值；任一真实集成门禁失败时测试失败
     */
    @Test
    @Order(10)
    void executesProcessWithSharedDataSourceTransactionAndAuthentication()
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        TaskService taskService = processEngine.getTaskService();
        HistoryService historyService = processEngine.getHistoryService();
        Deployment deployment = repositoryService.createDeployment()
            .name(DEPLOYMENT_NAME_PREFIX + "-core")
            .addClasspathResource("processes/flowable-engine-it.bpmn20.xml")
            .deploy();

        try
        {
            String businessKey = BUSINESS_KEY_PREFIX + UUID.randomUUID();
            ProcessInstance instance = authenticationContext.runAs("1", () ->
                runtimeService.startProcessInstanceByKey(
                    "flowableEngineIntegration",
                    businessKey,
                    Map.of("initiator", "1")
                )
            );
            var task = taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult();
            assertThat(task).isNotNull();
            taskService.claim(task.getId(), "1");
            authenticationContext.runAs("1", () -> taskService.complete(task.getId()));

            assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(instance.getId()).count()).isZero();
            var history = historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).finished().singleResult();
            assertThat(history).isNotNull();
            assertThat(history.getStartUserId()).isEqualTo("1");

            assertEngineTransactionRollsBack(runtimeService, historyService);
        }
        finally
        {
            // 部署及其运行、历史数据必须级联删除，最终行数基线会再次验证没有遗漏。
            repositoryService.deleteDeployment(deployment.getId(), true);
        }
    }

    /**
     * 使用真实 MySQL 连接验证嵌套只读边界共享同一事务，并在预期业务异常降级后正常提交。
     *
     * @return 无返回值；事务未激活、连接发生切换或提交阶段出现 rollback-only 时测试失败
     */
    @Test
    @Order(15)
    void commitsOuterReadAfterNestedExpectedServiceException()
    {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dynamicDataSource);

        boolean revocable = workflowEngineOperations.read(() ->
        {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("外层 read 必须开启真实 Spring 事务").isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                .as("外层 read 必须保持只读事务语义").isTrue();
            // MySQL connection_id 是当前物理连接标识，用于证明嵌套调用加入同一事务资源。
            Long outerConnectionId = jdbcTemplate.queryForObject(
                "select connection_id()", Long.class);
            assertThat(outerConnectionId).isNotNull().isPositive();

            return workflowEngineOperations.readWithServiceExceptionHandler(() ->
            {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("内层能力判断必须加入外层事务").isTrue();
                assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                    .as("内层能力判断不得改变只读属性").isTrue();
                Long nestedConnectionId = jdbcTemplate.queryForObject(
                    "select connection_id()", Long.class);
                assertThat(nestedConnectionId)
                    .as("外层 read 与内层能力判断必须复用同一 MySQL 连接")
                    .isEqualTo(outerConnectionId);
                assertThat(jdbcTemplate.queryForObject("select database()", String.class))
                    .isEqualTo(expectedSchema);
                throw new ServiceException("预期不可撤回状态", HttpStatus.CONFLICT);
            }, exception ->
            {
                assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("业务异常必须在事务代理返回前完成降级").isTrue();
                assertThat(jdbcTemplate.queryForObject(
                    "select connection_id()", Long.class)).isEqualTo(outerConnectionId);
                return false;
            });
        });

        // 外层代理能够正常返回即证明共享事务未被标记 rollback-only；随后再次读取确认连接池可用。
        assertThat(revocable).isFalse();
        assertThat(jdbcTemplate.queryForObject("select database()", String.class))
            .isEqualTo(expectedSchema);
    }

    /**
     * 验证测试环境按需启用真实异步执行器，timer 到期后由 executor 自动获取并完成流程。
     *
     * @return 无返回值；测试禁止调用 ManagementService.executeJob 手动伪造执行结果
     */
    @Test
    @Order(20)
    void executesTimerJobThroughActiveAsyncExecutor()
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        HistoryService historyService = processEngine.getHistoryService();
        ManagementService managementService = processEngine.getManagementService();
        AsyncExecutor asyncExecutor = engineConfiguration.getJobServiceConfiguration().getAsyncExecutor();

        assertThat(asyncExecutor).isNotNull();
        assertThat(asyncExecutor.isActive()).as("P1 timer 门禁必须由测试专用 executor 真实执行").isTrue();

        Deployment deployment = repositoryService.createDeployment()
            .name(DEPLOYMENT_NAME_PREFIX + "-timer")
            .addClasspathResource("processes/flowable-timer-it.bpmn20.xml")
            .deploy();

        try
        {
            String businessKey = BUSINESS_KEY_PREFIX + "timer-" + UUID.randomUUID();
            ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "flowableTimerIntegration",
                businessKey
            );

            assertThat(managementService.createTimerJobQuery().processInstanceId(instance.getId()).count())
                .as("流程启动后必须真实写入 timer job")
                .isEqualTo(1L);

            awaitCondition(
                "异步执行器应在超时前获取 timer/job 并结束流程",
                ASYNC_EXECUTION_TIMEOUT,
                () -> historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(instance.getId()).finished().count() == 1L
            );

            assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(instance.getId()).count()).isZero();
            assertThat(managementService.createTimerJobQuery().processInstanceId(instance.getId()).count()).isZero();
            assertThat(managementService.createJobQuery().processInstanceId(instance.getId()).count()).isZero();
            assertThat(managementService.createDeadLetterJobQuery().processInstanceId(instance.getId()).count()).isZero();
        }
        finally
        {
            repositoryService.deleteDeployment(deployment.getId(), true);
        }
    }

    /**
     * 在第一个 Spring 上下文中持久化 Jackson 3 JsonNode 变量，并保留流程记录供重启后读取。
     *
     * @return 无返回值；方法结束后由 @DirtiesContext 关闭当前 Spring 上下文
     */
    @Test
    @Order(30)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void persistsJsonVariableBeforeSpringContextRestart()
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        Deployment deployment = repositoryService.createDeployment()
            .name(DEPLOYMENT_NAME_PREFIX + "-restart")
            .addClasspathResource("processes/flowable-engine-it.bpmn20.xml")
            .deploy();

        try
        {
            ObjectNode approvalPayload = createApprovalPayload();
            String businessKey = BUSINESS_KEY_PREFIX + "restart-" + UUID.randomUUID();
            ProcessInstance instance = authenticationContext.runAs("1", () ->
                runtimeService.startProcessInstanceByKey(
                    "flowableEngineIntegration",
                    businessKey,
                    Map.of(JSON_VARIABLE_NAME, approvalPayload)
                )
            );

            assertThat(runtimeService.getVariable(instance.getId(), JSON_VARIABLE_NAME)).isInstanceOf(JsonNode.class);
            restartState = new RestartState(
                deployment.getId(),
                instance.getId(),
                businessKey,
                applicationContext
            );
        }
        catch (RuntimeException | Error exception)
        {
            repositoryService.deleteDeployment(deployment.getId(), true);
            throw exception;
        }
    }

    /**
     * 在全新的 Spring 上下文中读取前一上下文持久化的 JSON 变量，完成流程后级联清理记录。
     *
     * @return 无返回值；旧上下文未关闭、变量类型漂移或历史变量不可读时测试失败
     */
    @Test
    @Order(31)
    void readsJsonVariableAfterSpringContextRestart()
    {
        RestartState state = restartState;
        assertThat(state).as("重启前测试必须成功留下待读取的流程状态").isNotNull();
        if (state == null)
        {
            return;
        }

        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        TaskService taskService = processEngine.getTaskService();
        HistoryService historyService = processEngine.getHistoryService();

        try
        {
            assertThat(applicationContext).isNotSameAs(state.previousContext());
            assertThat(state.previousContext().isActive()).as("@DirtiesContext 必须真实关闭旧 Spring 上下文").isFalse();

            Object persistedValue = runtimeService.getVariable(state.processInstanceId(), JSON_VARIABLE_NAME);
            assertApprovalPayload(persistedValue);

            var task = taskService.createTaskQuery().processInstanceId(state.processInstanceId()).singleResult();
            assertThat(task).isNotNull();
            taskService.claim(task.getId(), "1");
            authenticationContext.runAs("1", () -> taskService.complete(task.getId()));

            var historicVariable = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(state.processInstanceId())
                .variableName(JSON_VARIABLE_NAME)
                .singleResult();
            assertThat(historicVariable).isNotNull();
            assertApprovalPayload(historicVariable.getValue());
            assertThat(historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(state.businessKey()).finished().count()).isEqualTo(1L);
        }
        finally
        {
            deleteDeploymentIfPresent(repositoryService, state.deploymentId());
            restartState = null;
        }
    }

    /**
     * 写入专用 Redis 测试键，验证 @AfterEach 会自动清空 DB 15 而不依赖测试主体手工删除。
     *
     * @return 无返回值；本方法故意保留键，由统一清理钩子负责删除
     */
    @Test
    @Order(40)
    void writesRedisMarkerForAutomaticCleanup()
    {
        redisTemplate.opsForValue().set(REDIS_MARKER_KEY, "must-be-cleaned");
        assertThat(redisTemplate.hasKey(REDIS_MARKER_KEY)).isTrue();
        assertThat(redisDatabaseSize()).isEqualTo(baselineRedisKeyCount + 1L);
    }

    /**
     * 在真实 MySQL 事务中验证身份目录和身份解析 Mapper 的状态过滤、分页、映射及排序契约。
     *
     * @return 无返回值；任何 SQL 契约不符、事务未回滚或主数据残留都会使集成门禁失败
     */
    @Test
    @Order(45)
    void filtersWorkflowIdentityMasterDataThroughRealMySqlMapper()
    {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dynamicDataSource);
        assertNoWorkflowIdentityTestData(jdbcTemplate);

        // 记录正式表自增基线，确认显式测试主键不会在回滚后留下元数据副作用。
        Map<String, Long> autoIncrementBaseline = readWorkflowIdentityAutoIncrementState(jdbcTemplate);
        assertThat(autoIncrementBaseline.get("sys_user")).isGreaterThan(IDENTITY_TEST_USER_IDS.get(5));
        assertThat(autoIncrementBaseline.get("sys_role")).isGreaterThan(IDENTITY_TEST_ROLE_IDS.get(3));
        assertThat(autoIncrementBaseline.get("sys_dept")).isGreaterThan(IDENTITY_TEST_DEPT_IDS.get(3));

        TransactionTemplate transactionTemplate = repeatableReadTransactionTemplate();
        try
        {
            transactionTemplate.executeWithoutResult(status ->
            {
                try
                {
                    insertWorkflowIdentityTestData(jdbcTemplate);

                    // 用户目录只过滤用户自身启停和删除状态；部门无效但用户仍有效的 95、96 仍应可被选为办理人。
                    assertThat(workflowIdentityMapper.countActiveIdentityOptions(
                        "user", "flowable_it_user")).isEqualTo(4L);
                    List<WorkflowIdentityOptionView> firstUserPage =
                        workflowIdentityMapper.selectActiveIdentityOptions(
                            "user", "flowable_it_user", 0L, 2);
                    List<WorkflowIdentityOptionView> secondUserPage =
                        workflowIdentityMapper.selectActiveIdentityOptions(
                            "user", "flowable_it_user", 2L, 2);
                    assertThat(firstUserPage).containsExactly(
                        new WorkflowIdentityOptionView(
                            "91", "正常用户一 (flowable_it_user_91)", "user"),
                        new WorkflowIdentityOptionView(
                            "92", "正常用户二 (flowable_it_user_92)", "user"));
                    assertThat(secondUserPage).containsExactly(
                        new WorkflowIdentityOptionView(
                            "95", "停用部门用户 (flowable_it_user_95)", "user"),
                        new WorkflowIdentityOptionView(
                            "96", "删除部门用户 (flowable_it_user_96)", "user"));
                    assertThat(firstUserPage.get(0).getClass())
                        .as("MyBatis constructor resultMap 必须映射为正式 record")
                        .isEqualTo(WorkflowIdentityOptionView.class);
                    assertThat(workflowIdentityMapper.countActiveIdentityOptions(
                        "user", "user_92")).isEqualTo(1L);
                    assertThat(workflowIdentityMapper.countActiveIdentityOptions(
                        "user", "user_93")).isZero();
                    assertThat(workflowIdentityMapper.countActiveIdentityOptions(
                        "user", "user_94")).isZero();
                    assertThat(workflowIdentityMapper.selectActiveIdentityOptions(
                        "user", "user_93", 0L, 10)).isEmpty();
                    assertThat(workflowIdentityMapper.selectActiveIdentityOptions(
                        "user", "user_94", 0L, 10)).isEmpty();

                    // 审批目录和写命令共用实时 RBAC 语义：仅有效角色授权用户及用户 1 超级管理员合格。
                    assertThat(workflowIdentityMapper.countApprovalEligibleUserOptions(
                        "flowable_it_user")).isEqualTo(1L);
                    assertThat(workflowIdentityMapper.selectApprovalEligibleUserOptions(
                        "flowable_it_user", 0L, 10)).containsExactly(
                            new WorkflowIdentityOptionView(
                                "91", "正常用户一 (flowable_it_user_91)", "user"));
                    assertThat(workflowIdentityMapper.selectApprovalEligibleUserIdsByUserIds(
                        List.of(96L, 95L, 94L, 93L, 92L, 91L, 1L)))
                        .containsExactly(1L, 91L);

                    // 完整认领资格允许跨有效角色聚合五项权限；缺少直接办理权限的 92 不能成为候选人。
                    assertThat(workflowIdentityMapper.countClaimEligibleIdentityOptions(
                        "user", "flowable_it_user")).isEqualTo(1L);
                    assertThat(workflowIdentityMapper.selectClaimEligibleIdentityOptions(
                        "user", "flowable_it_user", 0L, 10)).containsExactly(
                            new WorkflowIdentityOptionView(
                                "91", "正常用户一 (flowable_it_user_91)", "user"));
                    assertThat(workflowIdentityMapper.selectClaimEligibleUserIdsByUserIds(
                        List.of(96L, 95L, 94L, 93L, 92L, 91L, 1L)))
                        .containsExactly(1L, 91L);

                    // 角色目录按 role_sort、role_id 稳定排序，并把有效角色编码成 Flowable ROLE 候选组。
                    assertThat(workflowIdentityMapper.countActiveIdentityOptions(
                        "role", "Flowable IT")).isEqualTo(2L);
                    assertThat(workflowIdentityMapper.selectActiveIdentityOptions(
                        "role", "Flowable IT", 0L, 1)).containsExactly(
                            new WorkflowIdentityOptionView(
                                "ROLE91", "角色: Flowable IT 正常角色一", "role"));
                    assertThat(workflowIdentityMapper.selectActiveIdentityOptions(
                        "role", "Flowable IT", 1L, 1)).containsExactly(
                            new WorkflowIdentityOptionView(
                                "ROLE92", "角色: Flowable IT 正常角色二", "role"));
                    assertThat(workflowIdentityMapper.countActiveIdentityOptions(
                        "role", "active_two")).isEqualTo(1L);
                    assertThat(workflowIdentityMapper.countActiveIdentityOptions(
                        "role", "inactive")).isZero();
                    assertThat(workflowIdentityMapper.countActiveIdentityOptions(
                        "role", "deleted")).isZero();
                    assertThat(workflowIdentityMapper.selectActiveIdentityOptions(
                        "role", "inactive", 0L, 10)).isEmpty();
                    assertThat(workflowIdentityMapper.selectActiveIdentityOptions(
                        "role", "deleted", 0L, 10)).isEmpty();

                    // 两个有效角色都含有用户 91，资格来自该用户跨角色聚合后的完整五项权限。
                    assertThat(workflowIdentityMapper.countClaimEligibleIdentityOptions(
                        "role", "Flowable IT")).isEqualTo(2L);
                    assertThat(workflowIdentityMapper.selectClaimEligibleIdentityOptions(
                        "role", "Flowable IT", 0L, 10)).containsExactly(
                            new WorkflowIdentityOptionView(
                                "ROLE91", "角色: Flowable IT 正常角色一", "role"),
                            new WorkflowIdentityOptionView(
                                "ROLE92", "角色: Flowable IT 正常角色二", "role"));
                    assertThat(workflowIdentityMapper.selectClaimEligibleRoleIdsByRoleIds(
                        List.of(94L, 92L, 93L, 91L))).containsExactly(91L, 92L);

                    // 部门目录按 order_num、dept_id 稳定排序，并把有效部门编码成 Flowable DEPT 候选组。
                    assertThat(workflowIdentityMapper.countActiveIdentityOptions(
                        "dept", "Flowable IT")).isEqualTo(2L);
                    assertThat(workflowIdentityMapper.selectActiveIdentityOptions(
                        "dept", "Flowable IT", 0L, 1)).containsExactly(
                            new WorkflowIdentityOptionView(
                                "DEPT191", "部门: Flowable IT 正常部门一", "dept"));
                    assertThat(workflowIdentityMapper.selectActiveIdentityOptions(
                        "dept", "Flowable IT", 1L, 1)).containsExactly(
                            new WorkflowIdentityOptionView(
                                "DEPT192", "部门: Flowable IT 正常部门二", "dept"));
                    assertThat(workflowIdentityMapper.countActiveIdentityOptions(
                        "dept", "正常部门二")).isEqualTo(1L);
                    assertThat(workflowIdentityMapper.countActiveIdentityOptions(
                        "dept", "停用部门")).isZero();
                    assertThat(workflowIdentityMapper.countActiveIdentityOptions(
                        "dept", "删除部门")).isZero();
                    assertThat(workflowIdentityMapper.selectActiveIdentityOptions(
                        "dept", "停用部门", 0L, 10)).isEmpty();
                    assertThat(workflowIdentityMapper.selectActiveIdentityOptions(
                        "dept", "删除部门", 0L, 10)).isEmpty();

                    // 只有部门 191 含完整认领资格用户；部门 192 的用户 92 仅有两项认领菜单权限。
                    assertThat(workflowIdentityMapper.countClaimEligibleIdentityOptions(
                        "dept", "Flowable IT")).isEqualTo(1L);
                    assertThat(workflowIdentityMapper.selectClaimEligibleIdentityOptions(
                        "dept", "Flowable IT", 0L, 10)).containsExactly(
                            new WorkflowIdentityOptionView(
                                "DEPT191", "部门: Flowable IT 正常部门一", "dept"));
                    assertThat(workflowIdentityMapper.selectClaimEligibleDeptIdsByDeptIds(
                        List.of(194L, 192L, 193L, 191L))).containsExactly(191L);

                    // 打乱多值入参顺序，确保 SQL 独立完成启停/删除过滤并按主键稳定升序返回。
                    assertThat(workflowIdentityMapper.selectActiveUserIdsByUserIds(
                        List.of(96L, 94L, 92L, 95L, 91L, 93L)))
                        .containsExactly(91L, 92L, 95L, 96L);
                    assertThat(workflowIdentityMapper.selectActiveUserIdsByRoleIds(
                        List.of(94L, 92L, 93L, 91L)))
                        .containsExactly(91L, 92L);
                    assertThat(workflowIdentityMapper.selectActiveUserIdsByDeptIds(
                        List.of(194L, 192L, 193L, 191L)))
                        .containsExactly(91L, 92L);

                    // 同一用户关联两个有效角色以及停用、删除角色，结果必须去除无效角色并保持升序。
                    assertThat(workflowIdentityMapper.selectActiveRoleIdsByUserId(91L))
                        .containsExactly(91L, 92L);
                    assertThat(workflowIdentityMapper.selectActiveRoleIdsByUserId(93L)).isEmpty();
                    assertThat(workflowIdentityMapper.selectActiveRoleIdsByUserId(94L)).isEmpty();

                    // 部门反查同时验证用户状态与部门状态，任一侧无效都不得形成候选部门。
                    assertThat(workflowIdentityMapper.selectActiveDeptIdsByUserId(91L)).containsExactly(191L);
                    assertThat(workflowIdentityMapper.selectActiveDeptIdsByUserId(93L)).isEmpty();
                    assertThat(workflowIdentityMapper.selectActiveDeptIdsByUserId(94L)).isEmpty();
                    assertThat(workflowIdentityMapper.selectActiveDeptIdsByUserId(95L)).isEmpty();
                    assertThat(workflowIdentityMapper.selectActiveDeptIdsByUserId(96L)).isEmpty();
                }
                finally
                {
                    // 正常和断言失败分支都显式标记回滚，禁止测试主数据进入事务提交路径。
                    status.setRollbackOnly();
                }
            });
        }
        finally
        {
            // 事务外复核数据行和自增元数据，断言失败时同样执行，防止测试污染后续场景。
            assertNoWorkflowIdentityTestData(jdbcTemplate);
            assertThat(readWorkflowIdentityAutoIncrementState(jdbcTemplate))
                .containsExactlyInAnyOrderEntriesOf(autoIncrementBaseline);
        }
    }

    /**
     * 通过真实 MySQL、Spring SecurityContext 和 Flowable 公共 API 验证 P2 适配器的权限与状态边界。
     *
     * @return 无返回值；越权、挂起、委派、恢复或清理任一真实链路不符合契约时测试失败
     */
    @Test
    @Order(47)
    void enforcesWorkflowAdapterAuthorizationAndStateTransitionsThroughRealEngine()
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        TaskService taskService = processEngine.getTaskService();
        HistoryService historyService = processEngine.getHistoryService();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dynamicDataSource);
        String businessKey = BUSINESS_KEY_PREFIX + "adapter-" + UUID.randomUUID();
        String deploymentId = null;
        boolean temporaryUserInserted = false;
        boolean temporaryApprovalRoleLinked = false;

        // 显式低位主键不得占用正式自增区间，也不得覆盖隔离库中已有的任何用户。
        Long userAutoIncrementBaseline = readWorkflowIdentityAutoIncrementState(jdbcTemplate).get("sys_user");
        assertThat(userAutoIncrementBaseline).isGreaterThan(ADAPTER_NON_CANDIDATE_USER_ID);
        assertTemporaryWorkflowUserAbsent(jdbcTemplate);

        try
        {
            int insertedRows = jdbcTemplate.update(
                "insert into sys_user (user_id, dept_id, user_name, nick_name, status, del_flag) "
                    + "values (?, null, ?, ?, '0', '0')",
                ADAPTER_NON_CANDIDATE_USER_ID,
                "flowable_adapter_it_90",
                "Flowable 适配器非候选用户"
            );
            temporaryUserInserted = insertedRows == 1;
            assertThat(insertedRows).as("适配器门禁必须写入一条真实有效用户主数据").isEqualTo(1);
            temporaryApprovalRoleLinked = linkTemporaryApprovalRole(
                jdbcTemplate, ADAPTER_NON_CANDIDATE_USER_ID, "适配器委派目标用户");

            // 测试 fixture 直接通过 Flowable 公共 API 建立；本场景只验证 Adapter 的任务权限与状态边界。
            setSecurityContextUser(1L);
            Deployment deployment = repositoryService.createDeployment()
                .name(DEPLOYMENT_NAME_PREFIX + "-adapter")
                .addClasspathResource("processes/flowable-engine-it.bpmn20.xml")
                .deploy();
            deploymentId = deployment.getId();
            var processDefinition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deploymentId)
                .singleResult();
            assertThat(processDefinition).isNotNull();

            String processInstanceId = startProcessAsUser(runtimeService,
                processDefinition.getId(), businessKey, Map.of("source", "adapter-it")).getId();
            Task task = taskService.createTaskQuery().processInstanceId(processInstanceId).singleResult();
            assertThat(task).isNotNull();
            String taskId = task.getId();

            // 查询结果必须是模块自有快照，Flowable 可变实体不能越过适配层。
            assertThat(workflowProcessEngineAdapter.findActiveProcessInstance(processInstanceId))
                .hasValueSatisfying(snapshot -> assertActiveProcessSnapshot(
                    snapshot, processInstanceId, processDefinition.getId(), businessKey));
            assertThat(workflowProcessEngineAdapter.findActiveTask(taskId))
                .hasValueSatisfying(snapshot -> assertTaskSnapshot(
                    snapshot, taskId, processInstanceId, null, null));

            // 有效但非候选用户必须收到 403，失败命令不得改变任务办理人与活动状态。
            setSecurityContextUser(ADAPTER_NON_CANDIDATE_USER_ID);
            assertWorkflowBusinessError(
                () -> workflowProcessEngineAdapter.claimTaskForCurrentUser(taskId),
                HttpStatus.FORBIDDEN,
                "无权执行当前工作流操作"
            );
            Task rejectedClaimTask = taskService.createTaskQuery().taskId(taskId).singleResult();
            assertThat(rejectedClaimTask).isNotNull();
            assertThat(rejectedClaimTask.getAssignee()).isNull();
            assertThat(rejectedClaimTask.isSuspended()).isFalse();

            // BPMN 的直接候选用户可以认领，随后非办理人不得取消认领或完成任务。
            setSecurityContextUser(1L);
            workflowProcessEngineAdapter.claimTaskForCurrentUser(taskId);
            assertThat(workflowProcessEngineAdapter.findActiveTask(taskId))
                .hasValueSatisfying(snapshot -> assertTaskSnapshot(
                    snapshot, taskId, processInstanceId, "1", null));

            setSecurityContextUser(ADAPTER_NON_CANDIDATE_USER_ID);
            assertWorkflowBusinessError(
                () -> workflowProcessEngineAdapter.unclaimTaskForCurrentUser(taskId),
                HttpStatus.FORBIDDEN,
                "无权执行当前工作流操作"
            );
            assertWorkflowBusinessError(
                () -> workflowProcessEngineAdapter.completeTask(taskId, Map.of()),
                HttpStatus.FORBIDDEN,
                "无权执行当前工作流操作"
            );
            assertThat(taskService.createTaskQuery().taskId(taskId).singleResult().getAssignee()).isEqualTo("1");

            // 当前办理人可以取消认领；候选关系仍在，因此同一合法用户可以再次认领。
            setSecurityContextUser(1L);
            workflowProcessEngineAdapter.unclaimTaskForCurrentUser(taskId);
            assertThat(taskService.createTaskQuery().taskId(taskId).singleResult().getAssignee()).isNull();
            workflowProcessEngineAdapter.claimTaskForCurrentUser(taskId);
            assertThat(taskService.createTaskQuery().taskId(taskId).singleResult().getAssignee()).isEqualTo("1");

            // 挂起实例不属于 active 查询结果，任何适配器任务命令都必须按状态冲突返回 409。
            runtimeService.suspendProcessInstanceById(processInstanceId);
            assertThat(workflowProcessEngineAdapter.findActiveProcessInstance(processInstanceId)).isEmpty();
            assertThat(workflowProcessEngineAdapter.findActiveTask(taskId)).isEmpty();
            assertWorkflowBusinessError(
                () -> workflowProcessEngineAdapter.completeTask(taskId, Map.of()),
                HttpStatus.CONFLICT,
                "工作流状态已发生变化，请刷新后重试"
            );
            Task suspendedTask = taskService.createTaskQuery().taskId(taskId).singleResult();
            assertThat(suspendedTask).isNotNull();
            assertThat(suspendedTask.isSuspended()).isTrue();

            // 恢复后建立真实 PENDING 委派；受托人必须先 resolve，不能直接完成。
            runtimeService.activateProcessInstanceById(processInstanceId);
            assertThat(workflowProcessEngineAdapter.findActiveProcessInstance(processInstanceId)).isPresent();
            authenticationContext.runAs("1", () ->
                taskService.delegateTask(taskId, String.valueOf(ADAPTER_NON_CANDIDATE_USER_ID)));
            Task pendingTask = taskService.createTaskQuery().taskId(taskId).singleResult();
            assertThat(pendingTask.getOwner()).isEqualTo("1");
            assertThat(pendingTask.getAssignee()).isEqualTo(String.valueOf(ADAPTER_NON_CANDIDATE_USER_ID));
            assertThat(pendingTask.getDelegationState()).isEqualTo(DelegationState.PENDING);

            setSecurityContextUser(ADAPTER_NON_CANDIDATE_USER_ID);
            assertWorkflowBusinessError(
                () -> workflowProcessEngineAdapter.completeTask(taskId, Map.of("approved", true)),
                HttpStatus.CONFLICT,
                "工作流状态已发生变化，请刷新后重试"
            );
            Task rejectedPendingTask = taskService.createTaskQuery().taskId(taskId).singleResult();
            assertThat(rejectedPendingTask.getDelegationState()).isEqualTo(DelegationState.PENDING);
            assertThat(rejectedPendingTask.getAssignee())
                .isEqualTo(String.valueOf(ADAPTER_NON_CANDIDATE_USER_ID));

            // 受托人按 Flowable 标准语义 resolve 后，任务回到原办理人并由适配器合法完成。
            authenticationContext.runAs(String.valueOf(ADAPTER_NON_CANDIDATE_USER_ID), () ->
                taskService.resolveTask(taskId));
            assertThat(workflowProcessEngineAdapter.findActiveTask(taskId))
                .hasValueSatisfying(snapshot -> assertTaskSnapshot(
                    snapshot, taskId, processInstanceId, "1", DelegationState.RESOLVED.name()));
            setSecurityContextUser(1L);
            workflowProcessEngineAdapter.completeTask(taskId, Map.of("approved", true));

            assertThat(workflowProcessEngineAdapter.findActiveTask(taskId)).isEmpty();
            assertThat(workflowProcessEngineAdapter.findActiveProcessInstance(processInstanceId)).isEmpty();
            assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).count())
                .isZero();
            assertThat(historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).finished().count()).isEqualTo(1L);
        }
        finally
        {
            // 无论业务断言在哪一步失败，都先释放线程安全身份，再级联清理部署和临时正式主数据。
            SecurityContextHolder.clearContext();
            try
            {
                if (deploymentId != null)
                {
                    deleteDeploymentIfPresent(repositoryService, deploymentId);
                }
            }
            finally
            {
                if (temporaryApprovalRoleLinked || temporaryUserInserted)
                {
                    jdbcTemplate.update("delete from sys_user_role where user_id = ?",
                        ADAPTER_NON_CANDIDATE_USER_ID);
                }
                if (temporaryUserInserted)
                {
                    jdbcTemplate.update("delete from sys_user where user_id = ?", ADAPTER_NON_CANDIDATE_USER_ID);
                }
            }

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertTemporaryWorkflowUserAbsent(jdbcTemplate);
            assertThat(readWorkflowIdentityAutoIncrementState(jdbcTemplate).get("sys_user"))
                .isEqualTo(userAutoIncrementBaseline);
            assertThat(repositoryService.createDeploymentQuery()
                .deploymentNameLike(DEPLOYMENT_NAME_PREFIX + "-adapter%").count()).isZero();
            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).count()).isZero();
            assertThat(historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).count()).isZero();
        }
    }

    /**
     * 通过真实 starter 授权、部署表单快照、MySQL 变量表和 Flowable 引擎验证 P14 发起服务。
     *
     * @return 无返回值；越权、变量落库、非法字段零残留或清理任一契约不满足时测试失败
     */
    @Test
    @Order(48)
    void startsProcessThroughDeploymentSnapshotAndRejectsInvalidVariables()
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        HistoryService historyService = processEngine.getHistoryService();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dynamicDataSource);
        String authorizedBusinessKey = BUSINESS_KEY_PREFIX + "start-authorized-" + UUID.randomUUID();
        String unauthorizedBusinessKey = BUSINESS_KEY_PREFIX + "start-forbidden-" + UUID.randomUUID();
        String invalidBusinessKey = BUSINESS_KEY_PREFIX + "start-invalid-" + UUID.randomUUID();
        String deploymentId = null;
        Long formId = null;
        boolean temporaryUserInserted = false;
        Long userAutoIncrementBaseline = readWorkflowIdentityAutoIncrementState(jdbcTemplate).get("sys_user");
        assertThat(userAutoIncrementBaseline).isGreaterThan(ADAPTER_NON_CANDIDATE_USER_ID);
        assertTemporaryWorkflowUserAbsent(jdbcTemplate);

        try
        {
            Deployment deployment = repositoryService.createDeployment()
                .name(DEPLOYMENT_NAME_PREFIX + "-process-start")
                .addClasspathResource("processes/flowable-process-start-it.bpmn20.xml")
                .deploy();
            deploymentId = deployment.getId();
            var processDefinition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deploymentId)
                .singleResult();
            assertThat(processDefinition).as("P14 测试部署必须只产生一个流程定义").isNotNull();

            // 直接 starter identity link 只授权管理员，公开定义与客户端伪造身份均不得绕过门禁。
            repositoryService.addCandidateStarterUser(processDefinition.getId(), "1");
            formId = insertProcessStartFormSnapshot(jdbcTemplate, deploymentId);
            int insertedRows = jdbcTemplate.update(
                "insert into sys_user (user_id, dept_id, user_name, nick_name, status, del_flag) "
                    + "values (?, null, ?, ?, '0', '0')",
                ADAPTER_NON_CANDIDATE_USER_ID,
                "flowable_start_it_90",
                "流程发起非授权用户"
            );
            temporaryUserInserted = insertedRows == 1;
            assertThat(insertedRows).as("starter 越权门禁必须使用真实有效用户").isEqualTo(1);

            setSecurityContextUser(ADAPTER_NON_CANDIDATE_USER_ID);
            assertWorkflowBusinessError(
                () -> workflowProcessStartService.start(new StartProcessRequest(
                    processDefinition.getId(), unauthorizedBusinessKey,
                    Map.of("reason", "越权发起", "amount", 1))),
                HttpStatus.FORBIDDEN,
                "当前用户无权发起该流程"
            );
            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(unauthorizedBusinessKey).count()).isZero();
            assertThat(historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(unauthorizedBusinessKey).count()).isZero();

            setSecurityContextUser(1L);
            WorkflowProcessInstanceSnapshot snapshot = workflowProcessStartService.start(
                new StartProcessRequest(processDefinition.getId(), authorizedBusinessKey,
                    Map.of("reason", "采购生产设备", "amount", 1280))
            );
            assertActiveProcessSnapshot(snapshot, snapshot.id(), processDefinition.getId(), authorizedBusinessKey);

            // 同时经 RuntimeService 与引擎物理变量表核对，证明四个字段已经真实持久化而非内存回显。
            assertThat(runtimeService.getVariables(snapshot.id()))
                .containsEntry("reason", "采购生产设备")
                .containsEntry("amount", 1280)
                .containsEntry(WorkflowProcessStartService.INITIATOR_VARIABLE, "1")
                .containsEntry(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE,
                    WorkflowProcessStartService.RUNNING_STATUS);
            Long persistedVariableCount = jdbcTemplate.queryForObject(
                "select count(*) from act_ru_variable "
                    + "where proc_inst_id_ = ? and name_ in (?, ?, ?, ?)",
                Long.class,
                snapshot.id(),
                "reason",
                "amount",
                WorkflowProcessStartService.INITIATOR_VARIABLE,
                WorkflowProcessStartService.PROCESS_STATUS_VARIABLE
            );
            assertThat(persistedVariableCount).as("发起变量必须真实写入 ACT_RU_VARIABLE").isEqualTo(4L);
            assertThat(historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(snapshot.id()).singleResult().getStartUserId()).isEqualTo("1");

            assertWorkflowBusinessError(
                () -> workflowProcessStartService.start(new StartProcessRequest(
                    processDefinition.getId(), invalidBusinessKey,
                    Map.of("reason", "采购生产设备", "amount", 1280, "unexpected", true))),
                HttpStatus.BAD_REQUEST,
                "流程变量字段不在开始表单中: unexpected"
            );
            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(invalidBusinessKey).count()).isZero();
            assertThat(historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(invalidBusinessKey).count()).isZero();
        }
        finally
        {
            SecurityContextHolder.clearContext();
            try
            {
                if (deploymentId != null)
                {
                    jdbcTemplate.update("delete from wf_deploy_form where deploy_id = ?", deploymentId);
                }
            }
            finally
            {
                try
                {
                    if (formId != null)
                    {
                        jdbcTemplate.update("delete from wf_form where form_id = ?", formId);
                    }
                }
                finally
                {
                    try
                    {
                        if (deploymentId != null)
                        {
                            deleteDeploymentIfPresent(repositoryService, deploymentId);
                        }
                    }
                    finally
                    {
                        if (temporaryUserInserted)
                        {
                            jdbcTemplate.update("delete from sys_user where user_id = ?",
                                ADAPTER_NON_CANDIDATE_USER_ID);
                        }
                    }
                }
            }
        }

        assertThat(runtimeService.createProcessInstanceQuery()
            .processInstanceBusinessKey(authorizedBusinessKey).count()).isZero();
        assertThat(historyService.createHistoricProcessInstanceQuery()
            .processInstanceBusinessKey(authorizedBusinessKey).count()).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from wf_deploy_form where deploy_id = ?", Long.class, deploymentId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from wf_form where form_id = ?", Long.class, formId)).isZero();
        assertTemporaryWorkflowUserAbsent(jdbcTemplate);
        assertThat(readWorkflowIdentityAutoIncrementState(jdbcTemplate).get("sys_user"))
            .isEqualTo(userAutoIncrementBaseline);
    }

    /**
     * 通过两个真实工作线程竞争同一任务，验证只有一次认领成功且只持久化一条 CLAIM 审计。
     *
     * @return 无返回值；并发结果、任务状态、审计 JSON 或物理表记录不符合契约时测试失败
     * @throws Exception 等待并发任务或关闭线程池失败时抛出
     */
    @Test
    @Order(49)
    void persistsSingleClaimAuditForConcurrentClaimAttempts() throws Exception
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        TaskService taskService = processEngine.getTaskService();
        HistoryService historyService = processEngine.getHistoryService();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dynamicDataSource);
        String businessKey = BUSINESS_KEY_PREFIX + "task-claim-" + UUID.randomUUID();
        String deploymentId = null;
        String processInstanceId = null;

        try
        {
            setSecurityContextUser(1L);
            Deployment deployment = repositoryService.createDeployment()
                .name(DEPLOYMENT_NAME_PREFIX + "-task-claim")
                .addClasspathResource("processes/flowable-engine-it.bpmn20.xml")
                .deploy();
            deploymentId = deployment.getId();
            var processDefinition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deploymentId)
                .singleResult();
            assertThat(processDefinition).as("并发认领测试必须只部署一个流程定义").isNotNull();
            processInstanceId = startProcessAsUser(runtimeService,
                processDefinition.getId(), businessKey,
                Map.of("source", "task-claim-it")).getId();
            Task task = taskService.createTaskQuery().processInstanceId(processInstanceId).singleResult();
            assertThat(task).as("并发认领测试必须进入候选任务").isNotNull();
            String taskId = task.getId();
            long claimCommandCommentBaseline = countPersistedComments(
                jdbcTemplate, processInstanceId);
            assertThat(taskService.getProcessInstanceComments(
                processInstanceId, WorkflowUserTaskAuditService.COMMENT_TYPE))
                .extracting(comment -> readAuditPayload(comment).path("action").asText())
                .containsExactly("USER_TASK_CREATE");

            CountDownLatch workersReady = new CountDownLatch(2);
            CountDownLatch releaseWorkers = new CountDownLatch(1);
            ExecutorService claimExecutor = Executors.newFixedThreadPool(2);
            try
            {
                Future<ClaimAttemptResult> firstAttempt = claimExecutor.submit(
                    () -> attemptConcurrentClaim(taskId, workersReady, releaseWorkers));
                Future<ClaimAttemptResult> secondAttempt = claimExecutor.submit(
                    () -> attemptConcurrentClaim(taskId, workersReady, releaseWorkers));
                assertThat(workersReady.await(10, TimeUnit.SECONDS))
                    .as("两个认领线程必须在超时前同时就绪").isTrue();
                releaseWorkers.countDown();

                List<ClaimAttemptResult> attempts = List.of(firstAttempt.get(), secondAttempt.get());
                assertThat(attempts).filteredOn(ClaimAttemptResult::successful).hasSize(1);
                assertThat(attempts).filteredOn(attempt -> !attempt.successful())
                    .singleElement()
                    .satisfies(attempt ->
                    {
                        assertThat(attempt.error()).isNotNull();
                        assertThat(attempt.error().getCode()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(attempt.error().getMessage())
                            .isEqualTo("工作流状态已发生变化，请刷新后重试");
                    });
            }
            finally
            {
                releaseWorkers.countDown();
                claimExecutor.shutdownNow();
                assertThat(claimExecutor.awaitTermination(10, TimeUnit.SECONDS))
                    .as("并发认领线程池必须完整退出").isTrue();
            }

            Task claimedTask = taskService.createTaskQuery().taskId(taskId).singleResult();
            assertThat(claimedTask).isNotNull();
            assertThat(claimedTask.getAssignee()).isEqualTo("1");
            assertThat(taskService.getProcessInstanceComments(
                processInstanceId, WorkflowUserTaskAuditService.COMMENT_TYPE))
                .extracting(comment -> readAuditPayload(comment).path("action").asText())
                .containsExactlyInAnyOrder("USER_TASK_CREATE", "USER_TASK_ASSIGNMENT");
            assertAuditComment(taskService, processInstanceId, taskId,
                "1", "CLAIM", "1", null, "用户认领任务");
            assertPersistedCommentCount(jdbcTemplate, processInstanceId,
                claimCommandCommentBaseline + 2L);

            // 继续通过真实应用服务取消认领，核对任务状态和第二条审计记录保持同一事务结果。
            long unclaimCommandCommentBaseline = countPersistedComments(
                jdbcTemplate, processInstanceId);
            workflowTaskActionService.unclaim(new WorkflowTaskUnclaimRequest(taskId));
            Task unclaimedTask = taskService.createTaskQuery().taskId(taskId).singleResult();
            assertThat(unclaimedTask).isNotNull();
            assertThat(unclaimedTask.getAssignee()).isNull();
            assertAuditComment(taskService, processInstanceId, taskId,
                "1", "UNCLAIM", "1", null, "用户取消认领任务");
            assertPersistedCommentCount(jdbcTemplate, processInstanceId,
                unclaimCommandCommentBaseline + 2L);
        }
        finally
        {
            SecurityContextHolder.clearContext();
            if (deploymentId != null)
            {
                deleteDeploymentIfPresent(repositoryService, deploymentId);
            }
        }

        assertThat(repositoryService.createDeploymentQuery()
            .deploymentNameLike(DEPLOYMENT_NAME_PREFIX + "-task-claim%").count()).isZero();
        assertThat(runtimeService.createProcessInstanceQuery()
            .processInstanceBusinessKey(businessKey).count()).isZero();
        assertThat(historyService.createHistoricProcessInstanceQuery()
            .processInstanceBusinessKey(businessKey).count()).isZero();
        if (processInstanceId != null)
        {
            assertPersistedCommentCount(jdbcTemplate, processInstanceId, 0L);
        }
    }

    /**
     * 通过真实外层事务验证委派状态、审计和抄送同步回滚，再验证正常委派、resolve、转办及抄送落库。
     *
     * @return 无返回值；回滚原子性、目标用户解析、状态机、审计或抄送不符合契约时测试失败
     */
    @Test
    @Order(49)
    void rollsBackDelegationAndPersistsDelegateAndTransferAudits()
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        TaskService taskService = processEngine.getTaskService();
        HistoryService historyService = processEngine.getHistoryService();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dynamicDataSource);
        String delegateBusinessKey = BUSINESS_KEY_PREFIX + "task-delegate-" + UUID.randomUUID();
        String transferBusinessKey = BUSINESS_KEY_PREFIX + "task-transfer-" + UUID.randomUUID();
        String deploymentId = null;
        String delegateProcessInstanceId = null;
        String transferProcessInstanceId = null;
        boolean temporaryUserInserted = false;
        boolean temporaryApprovalRoleLinked = false;
        Long userAutoIncrementBaseline = readWorkflowIdentityAutoIncrementState(jdbcTemplate).get("sys_user");

        assertThat(userAutoIncrementBaseline).isGreaterThan(ADAPTER_NON_CANDIDATE_USER_ID);
        assertTemporaryWorkflowUserAbsent(jdbcTemplate);
        try
        {
            int insertedRows = jdbcTemplate.update(
                "insert into sys_user (user_id, dept_id, user_name, nick_name, status, del_flag) "
                    + "values (?, null, ?, ?, '0', '0')",
                ADAPTER_NON_CANDIDATE_USER_ID,
                "flowable_task_action_it_90",
                "Flowable 任务动作目标用户"
            );
            temporaryUserInserted = insertedRows == 1;
            assertThat(insertedRows).as("委派和转办必须使用正式启用用户主数据").isEqualTo(1);
            temporaryApprovalRoleLinked = linkTemporaryApprovalRole(
                jdbcTemplate, ADAPTER_NON_CANDIDATE_USER_ID, "委派和转办目标用户");

            setSecurityContextUser(1L);
            Deployment deployment = repositoryService.createDeployment()
                .name(DEPLOYMENT_NAME_PREFIX + "-task-actions")
                .addClasspathResource("processes/flowable-engine-it.bpmn20.xml")
                .deploy();
            deploymentId = deployment.getId();
            var processDefinition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deploymentId)
                .singleResult();
            assertThat(processDefinition).as("任务动作测试必须只部署一个流程定义").isNotNull();

            delegateProcessInstanceId = startProcessAsUser(runtimeService,
                processDefinition.getId(), delegateBusinessKey,
                Map.of("source", "task-delegate-it")).getId();
            Task delegateTask = taskService.createTaskQuery()
                .processInstanceId(delegateProcessInstanceId).singleResult();
            assertThat(delegateTask).isNotNull();
            String delegateTaskId = delegateTask.getId();
            workflowTaskActionService.claim(new WorkflowTaskClaimRequest(delegateTaskId));
            long delegateCommandCommentBaseline = countPersistedComments(
                jdbcTemplate, delegateProcessInstanceId);

            TransactionTemplate transactionTemplate = repeatableReadTransactionTemplate();
            assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
            {
                workflowTaskActionService.delegate(new WorkflowTaskDelegateRequest(
                    delegateTaskId, ADAPTER_NON_CANDIDATE_USER_ID, "该委派必须回滚",
                    List.of(ADAPTER_NON_CANDIDATE_USER_ID)));
                throw new IllegalStateException("force task delegation rollback");
            })).isInstanceOf(IllegalStateException.class);

            // 外层事务失败后，办理人、owner、委派状态和 comment 必须一起恢复到委派前状态。
            Task rolledBackTask = taskService.createTaskQuery().taskId(delegateTaskId).singleResult();
            assertThat(rolledBackTask).isNotNull();
            assertThat(rolledBackTask.getAssignee()).isEqualTo("1");
            assertThat(rolledBackTask.getOwner()).isNull();
            assertThat(rolledBackTask.getDelegationState()).isNull();
            assertThat(taskService.getProcessInstanceComments(delegateProcessInstanceId))
                .noneMatch(comment -> "该委派必须回滚".equals(
                    readAuditPayload(comment).path("opinion").asText()));
            assertPersistedCommentCount(jdbcTemplate, delegateProcessInstanceId,
                delegateCommandCommentBaseline);
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_copy where instance_id = ?",
                Long.class, delegateProcessInstanceId)).isZero();

            workflowTaskActionService.delegate(new WorkflowTaskDelegateRequest(
                delegateTaskId, ADAPTER_NON_CANDIDATE_USER_ID, "请代为核验",
                List.of(ADAPTER_NON_CANDIDATE_USER_ID)));
            Task pendingTask = taskService.createTaskQuery().taskId(delegateTaskId).singleResult();
            assertThat(pendingTask).isNotNull();
            assertThat(pendingTask.getAssignee()).isEqualTo(String.valueOf(ADAPTER_NON_CANDIDATE_USER_ID));
            assertThat(pendingTask.getOwner()).isEqualTo("1");
            assertThat(pendingTask.getDelegationState()).isEqualTo(DelegationState.PENDING);
            assertAuditComment(taskService, delegateProcessInstanceId, delegateTaskId,
                "4", "DELEGATE", "1", String.valueOf(ADAPTER_NON_CANDIDATE_USER_ID), "请代为核验");
            assertPersistedCommentCount(jdbcTemplate, delegateProcessInstanceId,
                delegateCommandCommentBaseline + 2L);
            assertWorkflowCopy(jdbcTemplate, delegateProcessInstanceId, delegateTaskId,
                ADAPTER_NON_CANDIDATE_USER_ID, "DELEGATE");

            setSecurityContextUser(ADAPTER_NON_CANDIDATE_USER_ID);
            workflowProcessEngineAdapter.resolveTaskForCurrentUser(
                    delegateTaskId, "真实引擎委派办结");
            Task resolvedTask = taskService.createTaskQuery().taskId(delegateTaskId).singleResult();
            assertThat(resolvedTask).isNotNull();
            assertThat(resolvedTask.getAssignee()).isEqualTo("1");
            assertThat(resolvedTask.getOwner()).isEqualTo("1");
            assertThat(resolvedTask.getDelegationState()).isEqualTo(DelegationState.RESOLVED);

            setSecurityContextUser(1L);
            transferProcessInstanceId = startProcessAsUser(runtimeService,
                processDefinition.getId(), transferBusinessKey,
                Map.of("source", "task-transfer-it")).getId();
            Task transferTask = taskService.createTaskQuery()
                .processInstanceId(transferProcessInstanceId).singleResult();
            assertThat(transferTask).isNotNull();
            String transferTaskId = transferTask.getId();
            workflowTaskActionService.claim(new WorkflowTaskClaimRequest(transferTaskId));
            long transferCommandCommentBaseline = countPersistedComments(
                jdbcTemplate, transferProcessInstanceId);
            workflowTaskActionService.transfer(new WorkflowTaskTransferRequest(
                transferTaskId, ADAPTER_NON_CANDIDATE_USER_ID, "调整至目标办理人",
                List.of(ADAPTER_NON_CANDIDATE_USER_ID)));

            Task transferredTask = taskService.createTaskQuery().taskId(transferTaskId).singleResult();
            assertThat(transferredTask).isNotNull();
            assertThat(transferredTask.getAssignee()).isEqualTo(String.valueOf(ADAPTER_NON_CANDIDATE_USER_ID));
            assertThat(transferredTask.getOwner()).isNull();
            assertThat(transferredTask.getDelegationState()).isNull();
            assertAuditComment(taskService, transferProcessInstanceId, transferTaskId,
                "5", "TRANSFER", "1", String.valueOf(ADAPTER_NON_CANDIDATE_USER_ID), "调整至目标办理人");
            // 已认领任务转办会依次触发 unclaim、setAssignee 的 assignment listener，再写入 TRANSFER 命令审计。
            // 三条 comment 都是正式引擎副作用，必须按固定增量精确核对，防止任一审计记录丢失。
            assertPersistedCommentCount(jdbcTemplate, transferProcessInstanceId,
                transferCommandCommentBaseline + 3L);
            assertWorkflowCopy(jdbcTemplate, transferProcessInstanceId, transferTaskId,
                ADAPTER_NON_CANDIDATE_USER_ID, "TRANSFER");
        }
        finally
        {
            SecurityContextHolder.clearContext();
            try
            {
                deleteWorkflowCopies(jdbcTemplate, delegateProcessInstanceId,
                    transferProcessInstanceId);
                if (deploymentId != null)
                {
                    deleteDeploymentIfPresent(repositoryService, deploymentId);
                }
            }
            finally
            {
                if (temporaryApprovalRoleLinked || temporaryUserInserted)
                {
                    jdbcTemplate.update("delete from sys_user_role where user_id = ?",
                        ADAPTER_NON_CANDIDATE_USER_ID);
                }
                if (temporaryUserInserted)
                {
                    jdbcTemplate.update("delete from sys_user where user_id = ?", ADAPTER_NON_CANDIDATE_USER_ID);
                }
            }
        }

        assertTemporaryWorkflowUserAbsent(jdbcTemplate);
        assertThat(readWorkflowIdentityAutoIncrementState(jdbcTemplate).get("sys_user"))
            .isEqualTo(userAutoIncrementBaseline);
        assertThat(repositoryService.createDeploymentQuery()
            .deploymentNameLike(DEPLOYMENT_NAME_PREFIX + "-task-actions%").count()).isZero();
        assertThat(runtimeService.createProcessInstanceQuery()
            .processInstanceBusinessKey(delegateBusinessKey).count()).isZero();
        assertThat(runtimeService.createProcessInstanceQuery()
            .processInstanceBusinessKey(transferBusinessKey).count()).isZero();
        assertThat(historyService.createHistoricProcessInstanceQuery()
            .processInstanceBusinessKey(delegateBusinessKey).count()).isZero();
        assertThat(historyService.createHistoricProcessInstanceQuery()
            .processInstanceBusinessKey(transferBusinessKey).count()).isZero();
        if (delegateProcessInstanceId != null)
        {
            assertPersistedCommentCount(jdbcTemplate, delegateProcessInstanceId, 0L);
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_copy where instance_id = ?",
                Long.class, delegateProcessInstanceId)).isZero();
        }
        if (transferProcessInstanceId != null)
        {
            assertPersistedCommentCount(jdbcTemplate, transferProcessInstanceId, 0L);
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_copy where instance_id = ?",
                Long.class, transferProcessInstanceId)).isZero();
        }
    }

    /**
     * 在真实串行流程中验证完成、动态下一办理人、抄送、退回、撤回、驳回和取消的权限、冲突及事务原子性。
     *
     * @return 无返回值；任一状态、身份、抄送、审计、重复提交或回滚契约不符合时测试失败
     */
    @Test
    @Order(50)
    void executesSerialLifecycleCommandsWithAtomicAuditsAndConflictGuards()
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        TaskService taskService = processEngine.getTaskService();
        HistoryService historyService = processEngine.getHistoryService();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dynamicDataSource);
        String deploymentId = null;
        List<Long> formIds = List.of();
        boolean temporaryUserInserted = false;
        boolean temporaryApprovalRoleLinked = false;
        Long userAutoIncrementBaseline = readWorkflowIdentityAutoIncrementState(jdbcTemplate).get("sys_user");

        assertThat(userAutoIncrementBaseline).isGreaterThan(ADAPTER_NON_CANDIDATE_USER_ID);
        assertTemporaryWorkflowUserAbsent(jdbcTemplate);
        try
        {
            Deployment deployment = repositoryService.createDeployment()
                .name(DEPLOYMENT_NAME_PREFIX + "-lifecycle-serial")
                .addClasspathResource("processes/flowable-task-lifecycle-it.bpmn20.xml")
                .deploy();
            deploymentId = deployment.getId();
            formIds = insertLifecycleFormSnapshots(jdbcTemplate, deploymentId);
            var processDefinition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deploymentId)
                .singleResult();
            assertThat(processDefinition).as("串行生命周期测试必须只部署一个流程定义").isNotNull();

            int insertedRows = jdbcTemplate.update(
                "insert into sys_user (user_id, dept_id, user_name, nick_name, status, del_flag) "
                    + "values (?, null, ?, ?, '0', '0')",
                ADAPTER_NON_CANDIDATE_USER_ID,
                "flowable_lifecycle_it_90",
                "生命周期越权用户"
            );
            temporaryUserInserted = insertedRows == 1;
            assertThat(insertedRows).as("生命周期越权门禁必须使用真实有效用户").isEqualTo(1);
            temporaryApprovalRoleLinked = linkTemporaryApprovalRole(
                jdbcTemplate, ADAPTER_NON_CANDIDATE_USER_ID, "生命周期动态办理人");
            setSecurityContextUser(1L);

            // 完成动作先在外层事务中强制失败，任务、变量和 comment 必须整体恢复。
            String completionBusinessKey = BUSINESS_KEY_PREFIX + "lifecycle-complete-" + UUID.randomUUID();
            ProcessInstance completionInstance = startProcessAsUser(
                runtimeService, processDefinition.getId(), completionBusinessKey,
                Map.of("requestReason", "验证完成事务", "hiddenSecret", "不得回显"));
            Task completionFirstTask = requireSingleTask(taskService, completionInstance.getId(), "firstReview");
            String completionFirstTaskId = completionFirstTask.getId();
            long completionRollbackCommentBaseline = countPersistedComments(
                jdbcTemplate, completionInstance.getId());
            TransactionTemplate transactionTemplate = repeatableReadTransactionTemplate();
            assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
            {
                workflowTaskLifecycleService.completeTask(new WorkflowTaskCompleteRequest(
                    completionFirstTaskId, "该完成动作必须回滚", Map.of("firstDecision", "同意"),
                    List.of(ADAPTER_NON_CANDIDATE_USER_ID),
                    List.of(ADAPTER_NON_CANDIDATE_USER_ID)));
                throw new IllegalStateException("force lifecycle completion rollback");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(taskService.createTaskQuery().taskId(completionFirstTaskId).singleResult())
                .as("外层事务失败后原任务必须恢复为活动态").isNotNull();
            assertThat(runtimeService.hasVariable(completionInstance.getId(), "firstDecision")).isFalse();
            assertPersistedCommentCount(jdbcTemplate, completionInstance.getId(),
                completionRollbackCommentBaseline);
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_copy where instance_id = ?",
                Long.class, completionInstance.getId())).isZero();

            workflowTaskLifecycleService.completeTask(new WorkflowTaskCompleteRequest(
                completionFirstTaskId, "初审通过", Map.of("firstDecision", "同意"),
                List.of(ADAPTER_NON_CANDIDATE_USER_ID), List.of(1L)));
            Task completionFinalTask = requireSingleTask(
                taskService, completionInstance.getId(), "finalReview");
            // 首个任务完成后实例仍在运行，processStatus 不能提前污染为 completed。
            assertThat(runtimeService.getVariable(completionInstance.getId(),
                WorkflowProcessStartService.PROCESS_STATUS_VARIABLE)).isEqualTo("running");
            assertWorkflowCopy(jdbcTemplate, completionInstance.getId(), completionFirstTaskId,
                ADAPTER_NON_CANDIDATE_USER_ID, "COMPLETE");
            assertLifecycleAuditComment(taskService, completionInstance.getId(),
                completionFirstTaskId, "1", "COMPLETE", "初审通过", null, null, null);
            long duplicateCompletionCommentBaseline = countPersistedComments(
                jdbcTemplate, completionInstance.getId());
            assertWorkflowBusinessError(
                () -> workflowTaskLifecycleService.completeTask(new WorkflowTaskCompleteRequest(
                    completionFirstTaskId, "重复完成", Map.of("firstDecision", "同意"))),
                HttpStatus.CONFLICT,
                "工作流状态已发生变化，请刷新后重试"
            );
            assertPersistedCommentCount(jdbcTemplate, completionInstance.getId(),
                duplicateCompletionCommentBaseline);

            workflowTaskLifecycleService.returnTask(new WorkflowTaskReturnRequest(
                completionFinalTask.getId(), "资料需要补充",
                List.of(ADAPTER_NON_CANDIDATE_USER_ID)));
            requireSingleTask(taskService, completionInstance.getId(), "firstReview");
            assertLifecycleAuditComment(taskService, completionInstance.getId(),
                completionFinalTask.getId(), "2", "RETURN", "资料需要补充", "firstReview", null, null);
            assertWorkflowCopy(jdbcTemplate, completionInstance.getId(), completionFinalTask.getId(),
                ADAPTER_NON_CANDIDATE_USER_ID, "RETURN");

            // 多人动态选择必须覆盖 BPMN 静态 assignee 为候选用户，真实认领和完成后流程才结束。
            String dynamicBusinessKey = BUSINESS_KEY_PREFIX + "lifecycle-dynamic-" + UUID.randomUUID();
            ProcessInstance dynamicInstance = startProcessAsUser(
                runtimeService, processDefinition.getId(), dynamicBusinessKey,
                Map.of("requestReason", "验证动态下一办理人"));
            Task dynamicFirstTask = requireSingleTask(taskService, dynamicInstance.getId(), "firstReview");
            workflowTaskLifecycleService.completeTask(new WorkflowTaskCompleteRequest(
                dynamicFirstTask.getId(), "指定两名下一办理人", Map.of("firstDecision", "同意"),
                List.of(), List.of(1L, ADAPTER_NON_CANDIDATE_USER_ID)));
            List<Task> dynamicNextTasks = taskService.createTaskQuery()
                .processInstanceId(dynamicInstance.getId()).active().list();
            assertThat(dynamicNextTasks).hasSize(1);
            Task dynamicFinalTask = dynamicNextTasks.get(0);
            assertThat(dynamicFinalTask.getTaskDefinitionKey()).isEqualTo("finalReview");
            assertThat(dynamicFinalTask.getAssignee()).isNull();
            assertThat(taskService.getIdentityLinksForTask(dynamicFinalTask.getId()).stream()
                .filter(link -> "candidate".equals(link.getType()))
                .map(link -> link.getUserId())
                .filter(java.util.Objects::nonNull)
                .toList()).containsExactlyInAnyOrder(
                    "1", String.valueOf(ADAPTER_NON_CANDIDATE_USER_ID));

            setSecurityContextUser(ADAPTER_NON_CANDIDATE_USER_ID);
            workflowTaskActionService.claim(new WorkflowTaskClaimRequest(dynamicFinalTask.getId()));
            workflowTaskLifecycleService.completeTask(new WorkflowTaskCompleteRequest(
                dynamicFinalTask.getId(), "动态办理完成", Map.of("finalDecision", "同意")));
            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(dynamicInstance.getId()).count()).isZero();
            // 最终任务自然结束实例后，正式历史变量必须与引擎 completed 终态一致。
            assertThat(historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(dynamicInstance.getId())
                .variableName(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE)
                .singleResult().getValue()).isEqualTo("completed");
            setSecurityContextUser(1L);

            // 撤回必须重建本人刚完成的来源任务，并在后继任务上保存来源历史任务主键。
            String revokeBusinessKey = BUSINESS_KEY_PREFIX + "lifecycle-revoke-" + UUID.randomUUID();
            ProcessInstance revokeInstance = startProcessAsUser(
                runtimeService, processDefinition.getId(), revokeBusinessKey,
                Map.of("requestReason", "验证撤回"));
            Task revokeFirstTask = requireSingleTask(taskService, revokeInstance.getId(), "firstReview");
            workflowTaskLifecycleService.completeTask(new WorkflowTaskCompleteRequest(
                revokeFirstTask.getId(), "初审完成", Map.of("firstDecision", "同意")));
            Task revokeFinalTask = requireSingleTask(taskService, revokeInstance.getId(), "finalReview");
            assertThat(revokeFinalTask.getState()).as("未办理直接后继必须保持 Flowable CREATED 状态")
                .isEqualTo(Task.CREATED);
            assertThat(revokeFinalTask.getClaimTime()).isNull();
            assertThat(revokeFinalTask.getInProgressStartTime()).isNull();
            workflowTaskLifecycleService.revokeProcess(new WorkflowProcessRevokeRequest(
                revokeInstance.getId(), revokeFirstTask.getId(), "撤回重新核验"));
            requireSingleTask(taskService, revokeInstance.getId(), "firstReview");
            assertLifecycleAuditComment(taskService, revokeInstance.getId(),
                revokeFinalTask.getId(), "7", "REVOKE", "撤回重新核验",
                "firstReview", revokeFirstTask.getId(), null);

            // 驳回整实例形成独立 rejected 终态，流程状态变量和结构化意见必须一起提交。
            String rejectBusinessKey = BUSINESS_KEY_PREFIX + "lifecycle-reject-" + UUID.randomUUID();
            ProcessInstance rejectInstance = startProcessAsUser(
                runtimeService, processDefinition.getId(), rejectBusinessKey,
                Map.of("requestReason", "验证驳回"));
            Task rejectTask = requireSingleTask(taskService, rejectInstance.getId(), "firstReview");
            workflowTaskLifecycleService.rejectTask(new WorkflowTaskRejectRequest(
                rejectTask.getId(), "申请不符合要求",
                List.of(ADAPTER_NON_CANDIDATE_USER_ID)));
            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(rejectInstance.getId()).count()).isZero();
            assertThat(historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(rejectInstance.getId())
                .variableName(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE)
                .singleResult().getValue()).isEqualTo("rejected");
            assertLifecycleAuditComment(taskService, rejectInstance.getId(), rejectTask.getId(),
                "3", "REJECT", "申请不符合要求", "firstReview", null, null);
            assertWorkflowCopy(jdbcTemplate, rejectInstance.getId(), rejectTask.getId(),
                ADAPTER_NON_CANDIDATE_USER_ID, "REJECT");
            assertWorkflowBusinessError(
                () -> workflowTaskLifecycleService.rejectTask(new WorkflowTaskRejectRequest(
                    rejectTask.getId(), "重复驳回")),
                HttpStatus.CONFLICT,
                "工作流状态已发生变化，请刷新后重试"
            );

            // 取消先验证普通非发起人越权不产生副作用，再由真实发起人提交并验证删除原因。
            String cancelBusinessKey = BUSINESS_KEY_PREFIX + "lifecycle-cancel-" + UUID.randomUUID();
            ProcessInstance cancelInstance = startProcessAsUser(
                runtimeService, processDefinition.getId(), cancelBusinessKey,
                Map.of("requestReason", "验证取消"));
            Task cancelTask = requireSingleTask(taskService, cancelInstance.getId(), "firstReview");
            long unauthorizedCancelCommentBaseline = countPersistedComments(
                jdbcTemplate, cancelInstance.getId());
            setSecurityContextUser(ADAPTER_NON_CANDIDATE_USER_ID);
            assertWorkflowBusinessError(
                () -> workflowTaskLifecycleService.cancelProcess(new WorkflowProcessCancelRequest(
                    cancelInstance.getId(), "越权取消")),
                HttpStatus.FORBIDDEN,
                "无权执行当前工作流操作"
            );
            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(cancelInstance.getId()).count()).isEqualTo(1L);
            assertPersistedCommentCount(jdbcTemplate, cancelInstance.getId(),
                unauthorizedCancelCommentBaseline);

            setSecurityContextUser(1L);
            workflowTaskLifecycleService.cancelProcess(new WorkflowProcessCancelRequest(
                cancelInstance.getId(), "发起人主动取消"));
            var canceledHistory = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(cancelInstance.getId()).singleResult();
            assertThat(canceledHistory).isNotNull();
            JsonNode cancelDeleteAudit = readAuditPayload(canceledHistory.getDeleteReason());
            assertThat(cancelDeleteAudit.path("action").asText()).isEqualTo("CANCEL");
            assertThat(cancelDeleteAudit.path("actorUserId").asText()).isEqualTo("1");
            assertThat(cancelDeleteAudit.path("opinion").asText()).isEqualTo("发起人主动取消");
            assertThat(historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(cancelInstance.getId())
                .variableName(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE)
                .singleResult().getValue()).isEqualTo("canceled");
            assertLifecycleAuditComment(taskService, cancelInstance.getId(), cancelTask.getId(),
                "6", "CANCEL", "发起人主动取消", null, null, false);
            assertWorkflowBusinessError(
                () -> workflowTaskLifecycleService.cancelProcess(new WorkflowProcessCancelRequest(
                    cancelInstance.getId(), "重复取消")),
                HttpStatus.CONFLICT,
                "工作流状态已发生变化，请刷新后重试"
            );
        }
        finally
        {
            SecurityContextHolder.clearContext();
            try
            {
                cleanupLifecycleDeployment(jdbcTemplate, repositoryService, deploymentId, formIds);
            }
            finally
            {
                if (temporaryApprovalRoleLinked || temporaryUserInserted)
                {
                    jdbcTemplate.update("delete from sys_user_role where user_id = ?",
                        ADAPTER_NON_CANDIDATE_USER_ID);
                }
                if (temporaryUserInserted)
                {
                    jdbcTemplate.update("delete from sys_user where user_id = ?",
                        ADAPTER_NON_CANDIDATE_USER_ID);
                }
            }
        }

        assertTemporaryWorkflowUserAbsent(jdbcTemplate);
        assertThat(readWorkflowIdentityAutoIncrementState(jdbcTemplate).get("sys_user"))
            .isEqualTo(userAutoIncrementBaseline);
        assertThat(repositoryService.createDeploymentQuery()
            .deploymentNameLike(DEPLOYMENT_NAME_PREFIX + "-lifecycle-serial%").count()).isZero();
    }

    /**
     * 通过真实并行和顺序多实例执行树验证退回保持保守拒绝、驳回整实例原子结束。
     *
     * @return 无返回值；退回产生副作用或驳回遗留活动 execution、错误终态时测试失败
     */
    @Test
    @Order(51)
    void rejectsUnsafeReturnsAndAtomicallyRejectsParallelAndMultiInstanceTrees()
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        TaskService taskService = processEngine.getTaskService();
        HistoryService historyService = processEngine.getHistoryService();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dynamicDataSource);
        String deploymentId = null;
        String parallelBusinessKey = BUSINESS_KEY_PREFIX + "lifecycle-parallel-" + UUID.randomUUID();
        String multiBusinessKey = BUSINESS_KEY_PREFIX + "lifecycle-multi-" + UUID.randomUUID();
        String parallelRevokeBusinessKey = BUSINESS_KEY_PREFIX
            + "lifecycle-parallel-revoke-" + UUID.randomUUID();

        try
        {
            setSecurityContextUser(1L);
            Deployment deployment = repositoryService.createDeployment()
                .name(DEPLOYMENT_NAME_PREFIX + "-lifecycle-unsafe")
                .addClasspathResource("processes/flowable-task-unsafe-it.bpmn20.xml")
                .deploy();
            deploymentId = deployment.getId();
            var parallelDefinition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deploymentId)
                .processDefinitionKey("flowableParallelLifecycleIntegration")
                .singleResult();
            var multiDefinition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deploymentId)
                .processDefinitionKey("flowableMultiInstanceLifecycleIntegration")
                .singleResult();
            var parallelRevokeDefinition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deploymentId)
                .processDefinitionKey("flowableParallelRevokeIntegration")
                .singleResult();
            assertThat(parallelDefinition).isNotNull();
            assertThat(multiDefinition).isNotNull();
            assertThat(parallelRevokeDefinition).isNotNull();

            ProcessInstance parallelInstance = startProcessAsUser(
                runtimeService, parallelDefinition.getId(), parallelBusinessKey, Map.of());
            List<Task> parallelTasks = taskService.createTaskQuery()
                .processInstanceId(parallelInstance.getId()).active().list();
            assertThat(parallelTasks).hasSize(2);
            Task parallelTask = parallelTasks.stream()
                .filter(task -> "parallelFirst".equals(task.getTaskDefinitionKey()))
                .findFirst()
                .orElseThrow();
            long parallelReturnCommentBaseline = countPersistedComments(
                jdbcTemplate, parallelInstance.getId());
            assertWorkflowBusinessError(
                () -> workflowTaskLifecycleService.returnTask(new WorkflowTaskReturnRequest(
                    parallelTask.getId(), "并行结构禁止退回")),
                HttpStatus.CONFLICT,
                "工作流状态已发生变化，请刷新后重试"
            );
            assertThat(taskService.createTaskQuery()
                .processInstanceId(parallelInstance.getId()).active().count()).isEqualTo(2L);
            assertPersistedCommentCount(jdbcTemplate, parallelInstance.getId(),
                parallelReturnCommentBaseline);
            workflowTaskLifecycleService.rejectTask(new WorkflowTaskRejectRequest(
                parallelTask.getId(), "并行实例整体驳回"));
            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(parallelInstance.getId()).count()).isZero();
            assertThat(historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(parallelInstance.getId())
                .variableName(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE)
                .singleResult().getValue()).isEqualTo("rejected");
            assertPersistedCommentCount(jdbcTemplate, parallelInstance.getId(),
                parallelReturnCommentBaseline + 2L);

            // 真实并行后继均未认领、未开始时，单次引擎命令必须合并回唯一来源任务。
            ProcessInstance parallelRevokeInstance = startProcessAsUser(
                runtimeService, parallelRevokeDefinition.getId(), parallelRevokeBusinessKey,
                Map.of());
            Task revokeSource = requireSingleTask(
                taskService, parallelRevokeInstance.getId(), "revokeSource");
            assertThat(revokeSource.getState()).isEqualTo(Task.CREATED);
            workflowTaskLifecycleService.completeTask(new WorkflowTaskCompleteRequest(
                revokeSource.getId(), "来源提交", Map.of()));
            List<Task> revokeSuccessors = taskService.createTaskQuery()
                .processInstanceId(parallelRevokeInstance.getId()).active().list();
            assertThat(revokeSuccessors).hasSize(2).allSatisfy(successor ->
            {
                assertThat(successor.getState()).isEqualTo(Task.CREATED);
                assertThat(successor.getClaimTime()).isNull();
                assertThat(successor.getInProgressStartTime()).isNull();
                assertThat(successor.getDelegationState()).isNull();
            });
            long parallelRevokeCommentBaseline = countPersistedComments(
                jdbcTemplate, parallelRevokeInstance.getId());
            workflowTaskLifecycleService.revokeProcess(new WorkflowProcessRevokeRequest(
                parallelRevokeInstance.getId(), revokeSource.getId(), "并行后继原子撤回"));
            Task restoredSource = requireSingleTask(
                taskService, parallelRevokeInstance.getId(), "revokeSource");
            assertThat(restoredSource.getId()).isNotEqualTo(revokeSource.getId());
            assertThat(restoredSource.getState()).isEqualTo(Task.CREATED);
            assertThat(runtimeService.getActiveActivityIds(parallelRevokeInstance.getId()))
                .containsExactly("revokeSource");
            assertThat(historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(parallelRevokeInstance.getId()).finished().list())
                .extracting(HistoricTaskInstance::getId)
                .contains(revokeSource.getId())
                .containsAll(revokeSuccessors.stream().map(Task::getId).toList());
            for (Task revokeSuccessor : revokeSuccessors)
            {
                assertLifecycleAuditCommentForTask(taskService, parallelRevokeInstance.getId(),
                    revokeSuccessor.getId(), "7", "REVOKE", "并行后继原子撤回",
                    "revokeSource", revokeSource.getId(), null);
            }
            assertPersistedCommentCount(jdbcTemplate, parallelRevokeInstance.getId(),
                parallelRevokeCommentBaseline + 4L);

            ProcessInstance multiInstance = startProcessAsUser(
                runtimeService, multiDefinition.getId(), multiBusinessKey,
                Map.of("reviewers", List.of("1", "1")));
            Task multiTask = requireSingleTask(taskService, multiInstance.getId(), "multiReview");
            long multiReturnCommentBaseline = countPersistedComments(
                jdbcTemplate, multiInstance.getId());
            assertWorkflowBusinessError(
                () -> workflowTaskLifecycleService.returnTask(new WorkflowTaskReturnRequest(
                    multiTask.getId(), "多实例结构禁止退回")),
                HttpStatus.CONFLICT,
                "当前流程结构不支持该流转操作"
            );
            assertThat(taskService.createTaskQuery().taskId(multiTask.getId()).count()).isEqualTo(1L);
            assertPersistedCommentCount(jdbcTemplate, multiInstance.getId(),
                multiReturnCommentBaseline);
            workflowTaskLifecycleService.rejectTask(new WorkflowTaskRejectRequest(
                multiTask.getId(), "多实例整体驳回"));
            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(multiInstance.getId()).count()).isZero();
            assertThat(historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(multiInstance.getId())
                .variableName(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE)
                .singleResult().getValue()).isEqualTo("rejected");
            assertPersistedCommentCount(jdbcTemplate, multiInstance.getId(),
                multiReturnCommentBaseline + 1L);
        }
        finally
        {
            SecurityContextHolder.clearContext();
            if (deploymentId != null)
            {
                deleteDeploymentIfPresent(repositoryService, deploymentId);
            }
        }

        assertThat(runtimeService.createProcessInstanceQuery()
            .processInstanceBusinessKey(parallelBusinessKey).count()).isZero();
        assertThat(runtimeService.createProcessInstanceQuery()
            .processInstanceBusinessKey(multiBusinessKey).count()).isZero();
        assertThat(runtimeService.createProcessInstanceQuery()
            .processInstanceBusinessKey(parallelRevokeBusinessKey).count()).isZero();
    }

    /**
     * 通过真实 ServiceTask、边界事件和 CallActivity 验证退回零副作用及根业务实例驳回/取消/终止。
     *
     * @return 无返回值；不可逆路径被放行、子流程单独删除或根执行树残留时测试失败
     */
    @Test
    @Order(52)
    void rejectsIrreversibleReturnsAndRejectsCallActivityRootInstance()
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        TaskService taskService = processEngine.getTaskService();
        HistoryService historyService = processEngine.getHistoryService();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dynamicDataSource);
        String deploymentId = null;
        String serviceBusinessKey = BUSINESS_KEY_PREFIX
            + "service-return-" + UUID.randomUUID();
        String boundaryBusinessKey = BUSINESS_KEY_PREFIX
            + "boundary-return-" + UUID.randomUUID();
        String callBusinessKey = BUSINESS_KEY_PREFIX
            + "call-reject-" + UUID.randomUUID();
        String callCancelBusinessKey = BUSINESS_KEY_PREFIX
            + "call-cancel-" + UUID.randomUUID();
        String callTerminateBusinessKey = BUSINESS_KEY_PREFIX
            + "call-terminate-" + UUID.randomUUID();

        try
        {
            setSecurityContextUser(1L);
            Deployment deployment = repositoryService.createDeployment()
                .name(DEPLOYMENT_NAME_PREFIX + "-lifecycle-call-and-return")
                .addClasspathResource("processes/flowable-task-unsafe-it.bpmn20.xml")
                .deploy();
            deploymentId = deployment.getId();
            var serviceDefinition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deploymentId)
                .processDefinitionKey("flowableServiceReturnIntegration")
                .singleResult();
            var boundaryDefinition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deploymentId)
                .processDefinitionKey("flowableBoundaryReturnIntegration")
                .singleResult();
            var callParentDefinition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deploymentId)
                .processDefinitionKey("flowableCallParentLifecycleIntegration")
                .singleResult();
            assertThat(serviceDefinition).isNotNull();
            assertThat(boundaryDefinition).isNotNull();
            assertThat(callParentDefinition).isNotNull();

            // ServiceTask 已真实执行后，重新回到来源任务会重复外部副作用，必须不返回候选节点。
            ProcessInstance serviceInstance = startProcessAsUser(
                runtimeService, serviceDefinition.getId(), serviceBusinessKey, Map.of());
            Task serviceSource = requireSingleTask(
                taskService, serviceInstance.getId(), "serviceReturnSource");
            authenticationContext.runAs("1", () -> taskService.complete(serviceSource.getId()));
            Task serviceCurrent = requireSingleTask(
                taskService, serviceInstance.getId(), "serviceReturnCurrent");
            long serviceExecutionCount = runtimeService.createExecutionQuery()
                .processInstanceId(serviceInstance.getId()).count();
            long serviceCommentCount = jdbcTemplate.queryForObject(
                "select count(*) from ACT_HI_COMMENT where PROC_INST_ID_ = ?",
                Long.class, serviceInstance.getId());
            assertWorkflowBusinessError(
                () -> workflowTaskLifecycleService.returnTask(new WorkflowTaskReturnRequest(
                    serviceCurrent.getId(), "禁止重复执行服务任务")),
                HttpStatus.CONFLICT,
                "当前流程结构不支持该流转操作"
            );
            assertThat(taskService.createTaskQuery().taskId(serviceCurrent.getId()).count()).isOne();
            assertThat(runtimeService.createExecutionQuery()
                .processInstanceId(serviceInstance.getId()).count()).isEqualTo(serviceExecutionCount);
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from ACT_HI_COMMENT where PROC_INST_ID_ = ?",
                Long.class, serviceInstance.getId())).isEqualTo(serviceCommentCount);

            // 历史来源任务带边界 timer 时，即使 timer 已随完成取消也不能重新创建该执行边界。
            ProcessInstance boundaryInstance = startProcessAsUser(
                runtimeService, boundaryDefinition.getId(), boundaryBusinessKey, Map.of());
            Task boundarySource = requireSingleTask(
                taskService, boundaryInstance.getId(), "boundaryReturnSource");
            authenticationContext.runAs("1", () -> taskService.complete(boundarySource.getId()));
            Task boundaryCurrent = requireSingleTask(
                taskService, boundaryInstance.getId(), "boundaryReturnCurrent");
            long boundaryCommentCount = jdbcTemplate.queryForObject(
                "select count(*) from ACT_HI_COMMENT where PROC_INST_ID_ = ?",
                Long.class, boundaryInstance.getId());
            assertWorkflowBusinessError(
                () -> workflowTaskLifecycleService.returnTask(new WorkflowTaskReturnRequest(
                    boundaryCurrent.getId(), "禁止重建边界事件")),
                HttpStatus.CONFLICT,
                "当前流程结构不支持该流转操作"
            );
            assertThat(taskService.createTaskQuery().taskId(boundaryCurrent.getId()).count()).isOne();
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from ACT_HI_COMMENT where PROC_INST_ID_ = ?",
                Long.class, boundaryInstance.getId())).isEqualTo(boundaryCommentCount);

            // 子流程任务上的驳回必须提升到根实例，并级联删除所有 CallActivity 子执行。
            ProcessInstance callRootInstance = startProcessAsUser(
                runtimeService, callParentDefinition.getId(), callBusinessKey, Map.of());
            Set<String> callProcessInstanceIds = new LinkedHashSet<>();
            callProcessInstanceIds.add(callRootInstance.getId());
            runtimeService.createExecutionQuery()
                .rootProcessInstanceId(callRootInstance.getId())
                .list()
                .stream()
                .map(org.flowable.engine.runtime.Execution::getProcessInstanceId)
                .filter(java.util.Objects::nonNull)
                .forEach(callProcessInstanceIds::add);
            List<ProcessInstance> callProcessTree = runtimeService.createProcessInstanceQuery()
                .processInstanceIds(callProcessInstanceIds)
                .active()
                .list();
            assertThat(callProcessTree).hasSize(2);
            ProcessInstance childProcessInstance = callProcessTree.stream()
                .filter(instance -> !callRootInstance.getId().equals(instance.getId()))
                .findFirst()
                .orElseThrow();
            String childProcessInstanceId = childProcessInstance.getId();
            assertThat(childProcessInstance.getRootProcessInstanceId())
                .isEqualTo(callRootInstance.getId());
            assertThat(childProcessInstance.getSuperExecutionId()).isNotBlank();
            Task childTask = taskService.createTaskQuery()
                .processInstanceId(childProcessInstanceId)
                .active()
                .singleResult();
            assertThat(childTask).isNotNull();
            assertThat(childTask.getTaskDefinitionKey()).isEqualTo("callChildReview");
            long childRejectCommentBaseline = countPersistedComments(
                jdbcTemplate, childProcessInstanceId);

            workflowTaskLifecycleService.rejectTask(new WorkflowTaskRejectRequest(
                childTask.getId(), "子流程任务整体驳回"));

            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(callRootInstance.getId()).count()).isZero();
            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(childProcessInstanceId).count()).isZero();
            assertThat(runtimeService.createExecutionQuery()
                .rootProcessInstanceId(callRootInstance.getId()).count()).isZero();
            assertThat(taskService.createTaskQuery()
                .processInstanceIdIn(callProcessInstanceIds)
                .count()).isZero();
            var historicRoot = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(callRootInstance.getId()).singleResult();
            assertThat(historicRoot).isNotNull();
            assertThat(historicRoot.getEndTime()).isNotNull();
            assertThat(historicRoot.getBusinessStatus()).isEqualTo("rejected");
            assertThat(historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(callRootInstance.getId())
                .variableName(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE)
                .singleResult().getValue()).isEqualTo("rejected");
            assertLifecycleAuditCommentForTask(taskService, childProcessInstanceId,
                childTask.getId(), "3", "REJECT", "子流程任务整体驳回",
                "callChildReview", null, null);
            assertPersistedCommentCount(jdbcTemplate, childProcessInstanceId,
                childRejectCommentBaseline + 1L);

            // 直接 API 即使传入 CallActivity 子实例 ID，也必须以根发起人授权并原子取消整棵树。
            ProcessInstance cancelRootInstance = startProcessAsUser(
                runtimeService, callParentDefinition.getId(), callCancelBusinessKey, Map.of());
            Set<String> cancelProcessInstanceIds = new LinkedHashSet<>();
            cancelProcessInstanceIds.add(cancelRootInstance.getId());
            runtimeService.createExecutionQuery()
                .rootProcessInstanceId(cancelRootInstance.getId())
                .list()
                .stream()
                .map(org.flowable.engine.runtime.Execution::getProcessInstanceId)
                .filter(java.util.Objects::nonNull)
                .forEach(cancelProcessInstanceIds::add);
            List<ProcessInstance> cancelProcessTree = runtimeService.createProcessInstanceQuery()
                .processInstanceIds(cancelProcessInstanceIds)
                .active()
                .list();
            assertThat(cancelProcessTree).hasSize(2);
            ProcessInstance cancelChildInstance = cancelProcessTree.stream()
                .filter(instance -> !cancelRootInstance.getId().equals(instance.getId()))
                .findFirst()
                .orElseThrow();
            String cancelChildInstanceId = cancelChildInstance.getId();
            Task cancelChildTask = taskService.createTaskQuery()
                .processInstanceId(cancelChildInstanceId)
                .active()
                .singleResult();
            assertThat(cancelChildTask).isNotNull();
            long childCancelCommentBaseline = countPersistedComments(
                jdbcTemplate, cancelChildInstanceId);

            workflowTaskLifecycleService.cancelProcess(new WorkflowProcessCancelRequest(
                cancelChildInstanceId, "子流程入口取消整体"));

            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(cancelRootInstance.getId()).count()).isZero();
            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(cancelChildInstanceId).count()).isZero();
            assertThat(runtimeService.createExecutionQuery()
                .rootProcessInstanceId(cancelRootInstance.getId()).count()).isZero();
            assertThat(taskService.createTaskQuery()
                .processInstanceIdIn(cancelProcessInstanceIds).count()).isZero();
            var historicCancelRoot = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(cancelRootInstance.getId()).singleResult();
            assertThat(historicCancelRoot).isNotNull();
            assertThat(historicCancelRoot.getEndTime()).isNotNull();
            assertThat(historicCancelRoot.getBusinessStatus()).isEqualTo("canceled");
            JsonNode cancelDeleteAudit = readAuditPayload(historicCancelRoot.getDeleteReason());
            assertThat(cancelDeleteAudit.path("action").asText()).isEqualTo("CANCEL");
            assertThat(cancelDeleteAudit.path("opinion").asText())
                .isEqualTo("子流程入口取消整体");
            assertLifecycleAuditCommentForTask(taskService, cancelChildInstanceId,
                cancelChildTask.getId(), "6", "CANCEL", "子流程入口取消整体",
                null, null, false);
            assertPersistedCommentCount(jdbcTemplate, cancelChildInstanceId,
                childCancelCommentBaseline + 1L);

            // 管理员实例 API 同样接受子实例 ID，但必须只对根实例写终态并级联删除完整执行树。
            ProcessInstance terminateRootInstance = startProcessAsUser(
                runtimeService, callParentDefinition.getId(), callTerminateBusinessKey, Map.of());
            Set<String> terminateProcessInstanceIds = new LinkedHashSet<>();
            terminateProcessInstanceIds.add(terminateRootInstance.getId());
            runtimeService.createExecutionQuery()
                .rootProcessInstanceId(terminateRootInstance.getId())
                .list()
                .stream()
                .map(org.flowable.engine.runtime.Execution::getProcessInstanceId)
                .filter(java.util.Objects::nonNull)
                .forEach(terminateProcessInstanceIds::add);
            List<ProcessInstance> terminateProcessTree = runtimeService
                .createProcessInstanceQuery()
                .processInstanceIds(terminateProcessInstanceIds)
                .active()
                .list();
            assertThat(terminateProcessTree).hasSize(2);
            ProcessInstance terminateChildInstance = terminateProcessTree.stream()
                .filter(instance -> !terminateRootInstance.getId().equals(instance.getId()))
                .findFirst()
                .orElseThrow();
            String terminateChildInstanceId = terminateChildInstance.getId();
            setSecurityContextUser(1L, Set.of("workflow:process:terminate"));
            long terminateRootCommentBaseline = countPersistedComments(
                jdbcTemplate, terminateRootInstance.getId());

            var termination = workflowProcessInstanceService.terminate(
                new WorkflowInstanceTerminateRequest(terminateChildInstanceId,
                    "子流程入口管理员终止整体"));

            assertThat(termination.instanceId()).isEqualTo(terminateRootInstance.getId());
            assertThat(termination.processStatus()).isEqualTo("terminated");
            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(terminateRootInstance.getId()).count()).isZero();
            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(terminateChildInstanceId).count()).isZero();
            assertThat(runtimeService.createExecutionQuery()
                .rootProcessInstanceId(terminateRootInstance.getId()).count()).isZero();
            assertThat(taskService.createTaskQuery()
                .processInstanceIdIn(terminateProcessInstanceIds).count()).isZero();
            assertProcessTerminationAudit(jdbcTemplate, terminateRootInstance.getId(),
                "terminated", "1", "TERMINATE", "子流程入口管理员终止整体", false,
                terminateRootCommentBaseline);
            Comment terminateAudit = taskService.getProcessInstanceComments(
                terminateRootInstance.getId(), "6").get(0);
            JsonNode terminateAuditPayload = readAuditPayload(terminateAudit);
            assertThat(terminateAuditPayload.path("requestedInstanceId").asText())
                .isEqualTo(terminateChildInstanceId);
            assertThat(terminateAuditPayload.path("rootInstanceId").asText())
                .isEqualTo(terminateRootInstance.getId());
            assertThat(terminateAuditPayload.path("processTreeInstanceCount").asInt())
                .isEqualTo(2);
        }
        finally
        {
            SecurityContextHolder.clearContext();
            if (deploymentId != null)
            {
                deleteDeploymentIfPresent(repositoryService, deploymentId);
            }
        }

        assertThat(runtimeService.createProcessInstanceQuery()
            .processInstanceBusinessKey(serviceBusinessKey).count()).isZero();
        assertThat(runtimeService.createProcessInstanceQuery()
            .processInstanceBusinessKey(boundaryBusinessKey).count()).isZero();
        assertThat(runtimeService.createProcessInstanceQuery()
            .processInstanceBusinessKey(callBusinessKey).count()).isZero();
        assertThat(runtimeService.createProcessInstanceQuery()
            .processInstanceBusinessKey(callCancelBusinessKey).count()).isZero();
        assertThat(runtimeService.createProcessInstanceQuery()
            .processInstanceBusinessKey(callTerminateBusinessKey).count()).isZero();
    }

    /**
     * 使用真实 MySQL 行锁确定性制造撤回与认领竞态，验证只允许一个业务动作提交。
     *
     * @return 无返回值；双成功、非 409 失败、任务状态或审计漂移时测试失败
     * @throws Exception 数据库锁、并发线程或结果等待失败时传播给 JUnit
     */
    @Test
    @Order(53)
    void serializesRevokeAgainstConcurrentClaim() throws Exception
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        TaskService taskService = processEngine.getTaskService();
        String deploymentId = null;
        List<String> raceBusinessKeys = new ArrayList<>();

        try
        {
            setSecurityContextUser(1L);
            Deployment deployment = repositoryService.createDeployment()
                .name(DEPLOYMENT_NAME_PREFIX + "-revoke-claim-race")
                .addClasspathResource("processes/flowable-task-unsafe-it.bpmn20.xml")
                .deploy();
            deploymentId = deployment.getId();
            var raceDefinition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deploymentId)
                .processDefinitionKey("flowableRevokeClaimRaceIntegration")
                .singleResult();
            assertThat(raceDefinition).isNotNull();

            for (RaceWinner expectedWinner : RaceWinner.values())
            {
                String businessKey = BUSINESS_KEY_PREFIX + "revoke-claim-"
                    + expectedWinner.name().toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID();
                raceBusinessKeys.add(businessKey);
                ProcessInstance instance = startProcessAsUser(
                    runtimeService, raceDefinition.getId(), businessKey, Map.of());
                Task sourceTask = requireSingleTask(
                    taskService, instance.getId(), "revokeRaceSource");
                authenticationContext.runAs("1", () -> taskService.complete(sourceTask.getId()));
                Task candidateTask = taskService.createTaskQuery()
                    .processInstanceId(instance.getId())
                    .taskDefinitionKey("revokeRaceCandidate")
                    .active()
                    .singleResult();
                assertThat(candidateTask).isNotNull();
                assertThat(candidateTask.getAssignee()).isNull();

                RevokeClaimRaceResult raceResult = executeRevokeClaimRace(
                    instance.getId(), sourceTask.getId(), candidateTask.getId(), expectedWinner);
                assertThat(List.of(raceResult.revoke(), raceResult.claim()))
                    .filteredOn(RaceAttempt::successful).hasSize(1);
                RaceAttempt failedAttempt = raceResult.revoke().successful()
                    ? raceResult.claim() : raceResult.revoke();
                assertThat(failedAttempt.unexpectedFailure()).isNull();
                assertThat(failedAttempt.businessFailure()).isNotNull();
                assertThat(failedAttempt.businessFailure().getCode()).isEqualTo(HttpStatus.CONFLICT);

                if (expectedWinner == RaceWinner.CLAIM)
                {
                    assertThat(raceResult.claim().successful()).isTrue();
                    assertThat(raceResult.revoke().successful()).isFalse();
                    Task claimedTask = taskService.createTaskQuery()
                        .taskId(candidateTask.getId()).singleResult();
                    assertThat(claimedTask).isNotNull();
                    assertThat(claimedTask.getAssignee()).isEqualTo("1");
                    assertThat(claimedTask.getState()).isEqualTo(Task.CLAIMED);
                    assertAuditComment(taskService, instance.getId(), candidateTask.getId(),
                        "1", "CLAIM", "1", null, "用户认领任务");
                }
                else
                {
                    assertThat(raceResult.revoke().successful()).isTrue();
                    assertThat(raceResult.claim().successful()).isFalse();
                    Task restoredTask = requireSingleTask(
                        taskService, instance.getId(), "revokeRaceSource");
                    assertThat(restoredTask.getId()).isNotEqualTo(sourceTask.getId());
                    assertThat(taskService.createTaskQuery()
                        .taskId(candidateTask.getId()).count()).isZero();
                    assertLifecycleAuditComment(taskService, instance.getId(),
                        candidateTask.getId(), "7", "REVOKE", "竞态撤回",
                        "revokeRaceSource", sourceTask.getId(), null);
                }
            }
        }
        finally
        {
            SecurityContextHolder.clearContext();
            if (deploymentId != null)
            {
                deleteDeploymentIfPresent(repositoryService, deploymentId);
            }
        }

        for (String businessKey : raceBusinessKeys)
        {
            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).count()).isZero();
        }
    }

    /**
     * 使用真实 MySQL 行锁制造同一实例终态命令竞态，验证完成/取消和驳回/终止都只能提交一个结果。
     *
     * @return 无返回值；双成功、失败方非 409、终态变量、审计或业务副作用不一致时测试失败
     * @throws Exception 数据库行锁、并发线程或结果等待失败时传播给 JUnit
     */
    @Test
    @Order(54)
    void serializesCompetingTerminalCommandsWithSingleCommittedOutcome() throws Exception
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        TaskService taskService = processEngine.getTaskService();
        HistoryService historyService = processEngine.getHistoryService();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dynamicDataSource);
        List<String> businessKeys = new ArrayList<>();
        List<String> processInstanceIds = new ArrayList<>();
        String deploymentId = null;

        try
        {
            setSecurityContextUser(1L);
            Deployment deployment = repositoryService.createDeployment()
                .name(DEPLOYMENT_NAME_PREFIX + "-terminal-command-race")
                .addClasspathResource("processes/flowable-task-unsafe-it.bpmn20.xml")
                .deploy();
            deploymentId = deployment.getId();
            var processDefinition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deploymentId)
                .processDefinitionKey("flowableCallChildLifecycleIntegration")
                .singleResult();
            assertThat(processDefinition).as("终态竞态必须复用单任务真实流程定义").isNotNull();

            // 场景一同时释放完成和发起人取消，最终只能保留 completed 或 canceled 之一。
            String completeCancelBusinessKey = BUSINESS_KEY_PREFIX
                + "complete-cancel-race-" + UUID.randomUUID();
            businessKeys.add(completeCancelBusinessKey);
            ProcessInstance completeCancelInstance = startProcessAsUser(runtimeService,
                processDefinition.getId(), completeCancelBusinessKey, Map.of());
            processInstanceIds.add(completeCancelInstance.getId());
            Task completeCancelTask = requireSingleTask(taskService,
                completeCancelInstance.getId(), "callChildReview");
            long completeCancelCommentBaseline = countPersistedComments(
                jdbcTemplate, completeCancelInstance.getId());
            TerminalCommandRaceResult completeCancelResult = executeTerminalCommandRace(
                completeCancelInstance.getId(), completeCancelTask.getId(), Set.of(),
                () -> workflowTaskLifecycleService.completeTask(new WorkflowTaskCompleteRequest(
                    completeCancelTask.getId(), "竞态完成", Map.of())),
                () -> workflowTaskLifecycleService.cancelProcess(new WorkflowProcessCancelRequest(
                    completeCancelInstance.getId(), "竞态取消")));
            assertSingleTerminalCommandOutcome(completeCancelResult);

            var completeCancelHistory = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(completeCancelInstance.getId()).singleResult();
            assertThat(completeCancelHistory).isNotNull();
            assertThat(completeCancelHistory.getEndTime()).isNotNull();
            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(completeCancelInstance.getId()).count()).isZero();
            String completeCancelStatus = String.valueOf(historyService
                .createHistoricVariableInstanceQuery()
                .processInstanceId(completeCancelInstance.getId())
                .variableName(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE)
                .singleResult().getValue());
            if (completeCancelResult.first().successful())
            {
                assertThat(completeCancelStatus).isEqualTo("completed");
                assertThat(completeCancelHistory.getDeleteReason()).isNull();
                assertLifecycleAuditComment(taskService, completeCancelInstance.getId(),
                    completeCancelTask.getId(), "1", "COMPLETE", "竞态完成", null, null, null);
                assertPersistedCommentCount(jdbcTemplate, completeCancelInstance.getId(),
                    completeCancelCommentBaseline + 2L);
            }
            else
            {
                assertThat(completeCancelStatus).isEqualTo("canceled");
                assertThat(completeCancelHistory.getBusinessStatus()).isEqualTo("canceled");
                JsonNode cancelAudit = readAuditPayload(completeCancelHistory.getDeleteReason());
                assertThat(cancelAudit.path("action").asText()).isEqualTo("CANCEL");
                assertThat(cancelAudit.path("actorUserId").asText()).isEqualTo("1");
                assertThat(cancelAudit.path("opinion").asText()).isEqualTo("竞态取消");
                assertLifecycleAuditComment(taskService, completeCancelInstance.getId(),
                    completeCancelTask.getId(), "6", "CANCEL", "竞态取消", null, null, false);
                assertPersistedCommentCount(jdbcTemplate, completeCancelInstance.getId(),
                    completeCancelCommentBaseline + 1L);
            }
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_copy where instance_id = ?",
                Long.class, completeCancelInstance.getId())).isZero();

            // 场景二同时释放整实例驳回和管理员终止，失败方不能留下 comment、状态或抄送。
            String rejectTerminateBusinessKey = BUSINESS_KEY_PREFIX
                + "reject-terminate-race-" + UUID.randomUUID();
            businessKeys.add(rejectTerminateBusinessKey);
            ProcessInstance rejectTerminateInstance = startProcessAsUser(runtimeService,
                processDefinition.getId(), rejectTerminateBusinessKey, Map.of());
            processInstanceIds.add(rejectTerminateInstance.getId());
            Task rejectTerminateTask = requireSingleTask(taskService,
                rejectTerminateInstance.getId(), "callChildReview");
            long rejectTerminateCommentBaseline = countPersistedComments(
                jdbcTemplate, rejectTerminateInstance.getId());
            TerminalCommandRaceResult rejectTerminateResult = executeTerminalCommandRace(
                rejectTerminateInstance.getId(), rejectTerminateTask.getId(),
                Set.of("workflow:process:terminate"),
                () -> workflowTaskLifecycleService.rejectTask(new WorkflowTaskRejectRequest(
                    rejectTerminateTask.getId(), "竞态驳回")),
                () -> workflowProcessInstanceService.terminate(
                    new WorkflowInstanceTerminateRequest(
                        rejectTerminateInstance.getId(), "竞态管理员终止")));
            assertSingleTerminalCommandOutcome(rejectTerminateResult);

            if (rejectTerminateResult.first().successful())
            {
                var rejectedHistory = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(rejectTerminateInstance.getId()).singleResult();
                assertThat(rejectedHistory).isNotNull();
                assertThat(rejectedHistory.getBusinessStatus()).isEqualTo("rejected");
                assertThat(historyService.createHistoricVariableInstanceQuery()
                    .processInstanceId(rejectTerminateInstance.getId())
                    .variableName(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE)
                    .singleResult().getValue()).isEqualTo("rejected");
                JsonNode rejectAudit = readAuditPayload(rejectedHistory.getDeleteReason());
                assertThat(rejectAudit.path("action").asText()).isEqualTo("REJECT");
                assertThat(rejectAudit.path("actorUserId").asText()).isEqualTo("1");
                assertThat(rejectAudit.path("opinion").asText()).isEqualTo("竞态驳回");
                assertLifecycleAuditComment(taskService, rejectTerminateInstance.getId(),
                    rejectTerminateTask.getId(), "3", "REJECT", "竞态驳回",
                    "callChildReview", null, null);
                assertPersistedCommentCount(jdbcTemplate, rejectTerminateInstance.getId(),
                    rejectTerminateCommentBaseline + 1L);
            }
            else
            {
                assertProcessTerminationAudit(jdbcTemplate, rejectTerminateInstance.getId(),
                    "terminated", "1", "TERMINATE", "竞态管理员终止", false,
                    rejectTerminateCommentBaseline);
            }
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_copy where instance_id = ?",
                Long.class, rejectTerminateInstance.getId())).isZero();
        }
        finally
        {
            SecurityContextHolder.clearContext();
            if (deploymentId != null)
            {
                deleteDeploymentIfPresent(repositoryService, deploymentId);
            }
        }

        for (String businessKey : businessKeys)
        {
            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).count()).isZero();
            assertThat(historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).count()).isZero();
        }
        for (String processInstanceId : processInstanceIds)
        {
            assertPersistedCommentCount(jdbcTemplate, processInstanceId, 0L);
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_copy where instance_id = ?",
                Long.class, processInstanceId)).isZero();
        }
    }

    /**
     * 在真实 Spring 外层事务中强制取消、驳回和管理员终止失败，验证全部引擎写入原子回滚。
     *
     * @return 无返回值；任务、运行实例、状态变量、审计或抄送未恢复时测试失败
     */
    @Test
    @Order(55)
    void rollsBackCancelRejectAndTerminateAfterOuterTransactionFailure()
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        TaskService taskService = processEngine.getTaskService();
        HistoryService historyService = processEngine.getHistoryService();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dynamicDataSource);
        TransactionTemplate transactionTemplate = repeatableReadTransactionTemplate();
        List<String> businessKeys = new ArrayList<>();
        List<String> processInstanceIds = new ArrayList<>();
        String deploymentId = null;

        try
        {
            setSecurityContextUser(1L);
            Deployment deployment = repositoryService.createDeployment()
                .name(DEPLOYMENT_NAME_PREFIX + "-terminal-command-rollback")
                .addClasspathResource("processes/flowable-task-unsafe-it.bpmn20.xml")
                .deploy();
            deploymentId = deployment.getId();
            var processDefinition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deploymentId)
                .processDefinitionKey("flowableCallChildLifecycleIntegration")
                .singleResult();
            assertThat(processDefinition).as("终态回滚必须复用单任务真实流程定义").isNotNull();

            String cancelBusinessKey = BUSINESS_KEY_PREFIX
                + "cancel-rollback-" + UUID.randomUUID();
            businessKeys.add(cancelBusinessKey);
            ProcessInstance cancelInstance = startProcessAsUser(runtimeService,
                processDefinition.getId(), cancelBusinessKey, Map.of());
            processInstanceIds.add(cancelInstance.getId());
            Task cancelTask = requireSingleTask(taskService,
                cancelInstance.getId(), "callChildReview");
            long cancelRollbackCommentBaseline = countPersistedComments(
                jdbcTemplate, cancelInstance.getId());
            assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
            {
                workflowTaskLifecycleService.cancelProcess(new WorkflowProcessCancelRequest(
                    cancelInstance.getId(), "必须回滚的取消"));
                throw new IllegalStateException("force cancel rollback");
            })).isInstanceOf(IllegalStateException.class);
            assertTerminalCommandRollbackRestored(jdbcTemplate, cancelInstance.getId(),
                cancelTask.getId(), cancelRollbackCommentBaseline);

            String rejectBusinessKey = BUSINESS_KEY_PREFIX
                + "reject-rollback-" + UUID.randomUUID();
            businessKeys.add(rejectBusinessKey);
            ProcessInstance rejectInstance = startProcessAsUser(runtimeService,
                processDefinition.getId(), rejectBusinessKey, Map.of());
            processInstanceIds.add(rejectInstance.getId());
            Task rejectTask = requireSingleTask(taskService,
                rejectInstance.getId(), "callChildReview");
            long rejectRollbackCommentBaseline = countPersistedComments(
                jdbcTemplate, rejectInstance.getId());
            assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
            {
                workflowTaskLifecycleService.rejectTask(new WorkflowTaskRejectRequest(
                    rejectTask.getId(), "必须回滚的驳回"));
                throw new IllegalStateException("force reject rollback");
            })).isInstanceOf(IllegalStateException.class);
            assertTerminalCommandRollbackRestored(jdbcTemplate, rejectInstance.getId(),
                rejectTask.getId(), rejectRollbackCommentBaseline);

            String terminateBusinessKey = BUSINESS_KEY_PREFIX
                + "terminate-rollback-" + UUID.randomUUID();
            businessKeys.add(terminateBusinessKey);
            ProcessInstance terminateInstance = startProcessAsUser(runtimeService,
                processDefinition.getId(), terminateBusinessKey, Map.of());
            processInstanceIds.add(terminateInstance.getId());
            Task terminateTask = requireSingleTask(taskService,
                terminateInstance.getId(), "callChildReview");
            long terminateRollbackCommentBaseline = countPersistedComments(
                jdbcTemplate, terminateInstance.getId());
            setSecurityContextUser(1L, Set.of("workflow:process:terminate"));
            assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
            {
                workflowProcessInstanceService.terminate(new WorkflowInstanceTerminateRequest(
                    terminateInstance.getId(), "必须回滚的管理员终止"));
                throw new IllegalStateException("force terminate rollback");
            })).isInstanceOf(IllegalStateException.class);
            assertTerminalCommandRollbackRestored(jdbcTemplate, terminateInstance.getId(),
                terminateTask.getId(), terminateRollbackCommentBaseline);
        }
        finally
        {
            SecurityContextHolder.clearContext();
            if (deploymentId != null)
            {
                deleteDeploymentIfPresent(repositoryService, deploymentId);
            }
        }

        for (String businessKey : businessKeys)
        {
            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).count()).isZero();
            assertThat(historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).count()).isZero();
        }
        for (String processInstanceId : processInstanceIds)
        {
            assertPersistedCommentCount(jdbcTemplate, processInstanceId, 0L);
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_copy where instance_id = ?",
                Long.class, processInstanceId)).isZero();
        }
    }

    /**
     * 通过真实 MySQL 和 Flowable FULL 历史证明任务局部提交快照可持久保留且同名后序值不漂移。
     *
     * @return 无返回值；ACT_HI_DETAIL 关联、详情值或清理基线任一不符合时测试失败
     */
    @Test
    @Order(51)
    void persistsTaskBoundFormSubmissionsWithoutHistoricalValueDrift()
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        TaskService taskService = processEngine.getTaskService();
        HistoryService historyService = processEngine.getHistoryService();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dynamicDataSource);
        String deploymentId = null;
        List<Long> formIds = List.of();
        String businessKey = BUSINESS_KEY_PREFIX + "detail-history-" + UUID.randomUUID();
        String oversizedDetailId = "unsafe-detail-" + UUID.randomUUID();
        String oversizedByteArrayId = "unsafe-bytes-" + UUID.randomUUID();
        String cumulativeDetailIdOne = "cumulative-detail-1-" + UUID.randomUUID();
        String cumulativeDetailIdTwo = "cumulative-detail-2-" + UUID.randomUUID();
        String cumulativeByteArrayIdOne = "cumulative-bytes-1-" + UUID.randomUUID();
        String cumulativeByteArrayIdTwo = "cumulative-bytes-2-" + UUID.randomUUID();
        String firstSharedValue = "first-" + "值".repeat(2500);

        try
        {
            Deployment deployment = repositoryService.createDeployment()
                .name(DEPLOYMENT_NAME_PREFIX + "-detail-history")
                .addClasspathResource("processes/flowable-detail-history-it.bpmn20.xml")
                .deploy();
            deploymentId = deployment.getId();
            formIds = insertDetailHistoryFormSnapshots(jdbcTemplate, deploymentId);
            var definition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deploymentId).singleResult();
            assertThat(definition).as("历史详情测试必须只部署一个流程定义").isNotNull();

            setSecurityContextUser(1L);
            WorkflowProcessInstanceSnapshot started = workflowProcessStartService.start(
                new StartProcessRequest(definition.getId(), businessKey,
                    Map.of("requestTitle", "历史快照验证")));
            String processInstanceId = started.id();
            Task firstTask = requireSingleTask(taskService, processInstanceId, "firstReview");
            workflowTaskLifecycleService.completeTask(new WorkflowTaskCompleteRequest(
                firstTask.getId(), "初审提交", Map.of("sharedValue", firstSharedValue)));

            Task secondTask = requireSingleTask(taskService, processInstanceId, "secondReview");
            workflowTaskLifecycleService.completeTask(new WorkflowTaskCompleteRequest(
                secondTask.getId(), "复审提交", Map.of("sharedValue", "second-value")));
            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).count()).isZero();
            assertThat(historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId)
                .variableName("sharedValue").singleResult().getValue())
                .as("最终全局变量应为后序任务值，用于证明详情没有读取最终值")
                .isEqualTo("second-value");

            // 只查询物理元数据，避免真实 IT 通过 getValue 初始化字符串 Blob 并掩盖生产读取风险。
            List<Map<String, Object>> internalUpdates = jdbcTemplate.queryForList(
                "select d.TASK_ID_ as task_id, d.VAR_TYPE_ as variable_type, "
                    + "case when d.TEXT_ is null then 0 else 1 end as text_present, "
                    + "case when d.TEXT2_ is null then 0 else 1 end as text2_present, "
                    + "octet_length(d.TEXT_) as text_bytes, "
                    + "d.BYTEARRAY_ID_ as byte_array_id, "
                    + "octet_length(b.BYTES_) as stored_bytes "
                    + "from ACT_HI_DETAIL d "
                    + "left join ACT_GE_BYTEARRAY b on b.ID_ = d.BYTEARRAY_ID_ "
                    + "where d.PROC_INST_ID_ = ? and d.NAME_ = ? order by d.TIME_, d.ID_",
                processInstanceId, WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME);
            List<Map<String, Object>> firstTaskUpdates = internalUpdates.stream()
                .filter(update -> firstTask.getId().equals(update.get("task_id"))).toList();
            List<Map<String, Object>> secondTaskUpdates = internalUpdates.stream()
                .filter(update -> secondTask.getId().equals(update.get("task_id"))).toList();
            List<Map<String, Object>> startUpdates = internalUpdates.stream()
                .filter(update -> update.get("task_id") == null).toList();
            assertThat(startUpdates).as("开始提交快照必须只有一个流程级历史更新")
                .hasSize(1);
            assertThat(firstTaskUpdates).as("初审 task-local 快照必须真实保留").isNotEmpty();
            assertThat(secondTaskUpdates).as("复审 task-local 快照必须真实保留").isNotEmpty();
            assertThat(firstTaskUpdates)
                .as("Flowable 8 历史详情必须保留 string 类型并使用唯一合法正文位置")
                .allSatisfy(update ->
                {
                    assertThat(update.get("variable_type")).isEqualTo("string");
                    assertThat(((Number) update.get("text2_present")).intValue()).isZero();

                    int textPresent = ((Number) update.get("text_present")).intValue();
                    Object textBytes = update.get("text_bytes");
                    Object byteArrayId = update.get("byte_array_id");
                    Object storedBytes = update.get("stored_bytes");
                    // 行内文本与序列化 Blob 是互斥物理形态，必须恰好存在一种且满足生产容量门禁。
                    boolean inlineTextStorage = textPresent == 1
                        && textBytes instanceof Number textSize
                        && textSize.longValue() > 0
                        && textSize.longValue() <= HISTORY_SNAPSHOT_TEXT_LIMIT_BYTES
                        && byteArrayId == null && storedBytes == null;
                    boolean serializedBlobStorage = textPresent == 0 && textBytes == null
                        && byteArrayId instanceof String byteArrayKey
                        && !byteArrayKey.isBlank()
                        && storedBytes instanceof Number blobSize
                        && blobSize.longValue() > 0
                        && blobSize.longValue() <= HISTORY_SNAPSHOT_BLOB_LIMIT_BYTES;
                    assertThat(inlineTextStorage ^ serializedBlobStorage).isTrue();
                });
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from ACT_HI_DETAIL where PROC_INST_ID_ = ? "
                    + "and NAME_ = ? and TASK_ID_ in (?, ?)",
                Long.class, processInstanceId,
                WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME,
                firstTask.getId(), secondTask.getId()))
                .as("ACT_HI_DETAIL 必须持久化两个 taskId 的内部快照更新")
                .isGreaterThanOrEqualTo(2L);

            // 固定内部变量名和允许类型仍不足以信任正文，坏行必须被元数据阶段看见并整体拒绝。
            assertThat(jdbcTemplate.update(
                "insert into ACT_GE_BYTEARRAY "
                    + "(ID_, REV_, NAME_, DEPLOYMENT_ID_, BYTES_, GENERATED_) "
                    + "values (?, 1, ?, null, ?, 0)",
                oversizedByteArrayId, "oversized-internal-snapshot",
                new byte[OVERSIZED_HISTORY_SNAPSHOT_BYTES])).isEqualTo(1);
            assertThat(jdbcTemplate.update(
                "insert into ACT_HI_DETAIL "
                    + "(ID_, TYPE_, PROC_INST_ID_, EXECUTION_ID_, TASK_ID_, ACT_INST_ID_, "
                    + "NAME_, VAR_TYPE_, REV_, TIME_, BYTEARRAY_ID_) "
                    + "values (?, 'VariableUpdate', ?, null, null, null, ?, 'string', "
                    + "1, current_timestamp(3), ?)",
                oversizedDetailId, processInstanceId,
                WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME,
                oversizedByteArrayId)).isEqualTo(1);

            assertThatThrownBy(() -> workflowProcessDetailService.getDetail(
                new WorkflowProcessDetailQueryDto(processInstanceId, null)))
                .isInstanceOfSatisfying(ServiceException.class,
                    exception -> assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR));

            assertThat(jdbcTemplate.update(
                "delete from ACT_HI_DETAIL where ID_ = ?", oversizedDetailId)).isEqualTo(1);
            assertThat(jdbcTemplate.update(
                "delete from ACT_GE_BYTEARRAY where ID_ = ?", oversizedByteArrayId)).isEqualTo(1);

            // 两行各自低于单行限制，但累计正文超过 4 MiB 时也必须在正文 SQL 前整体失败。
            assertThat(jdbcTemplate.update(
                "insert into ACT_GE_BYTEARRAY "
                    + "(ID_, REV_, NAME_, DEPLOYMENT_ID_, BYTES_, GENERATED_) "
                    + "values (?, 1, ?, null, ?, 0), (?, 1, ?, null, ?, 0)",
                cumulativeByteArrayIdOne, "cumulative-internal-snapshot-1",
                new byte[CUMULATIVE_HISTORY_SNAPSHOT_ROW_BYTES],
                cumulativeByteArrayIdTwo, "cumulative-internal-snapshot-2",
                new byte[CUMULATIVE_HISTORY_SNAPSHOT_ROW_BYTES])).isEqualTo(2);
            assertThat(jdbcTemplate.update(
                "insert into ACT_HI_DETAIL "
                    + "(ID_, TYPE_, PROC_INST_ID_, EXECUTION_ID_, TASK_ID_, ACT_INST_ID_, "
                    + "NAME_, VAR_TYPE_, REV_, TIME_, BYTEARRAY_ID_) values "
                    + "(?, 'VariableUpdate', ?, null, null, null, ?, 'string', "
                    + "1, current_timestamp(3), ?), "
                    + "(?, 'VariableUpdate', ?, null, null, null, ?, 'string', "
                    + "1, current_timestamp(3), ?)",
                cumulativeDetailIdOne, processInstanceId,
                WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME,
                cumulativeByteArrayIdOne, cumulativeDetailIdTwo, processInstanceId,
                WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME,
                cumulativeByteArrayIdTwo)).isEqualTo(2);

            assertThatThrownBy(() -> workflowProcessDetailService.getDetail(
                new WorkflowProcessDetailQueryDto(processInstanceId, null)))
                .isInstanceOfSatisfying(ServiceException.class,
                    exception -> assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR));

            assertThat(jdbcTemplate.update(
                "delete from ACT_HI_DETAIL where ID_ in (?, ?)",
                cumulativeDetailIdOne, cumulativeDetailIdTwo)).isEqualTo(2);
            assertThat(jdbcTemplate.update(
                "delete from ACT_GE_BYTEARRAY where ID_ in (?, ?)",
                cumulativeByteArrayIdOne, cumulativeByteArrayIdTwo)).isEqualTo(2);

            var detail = workflowProcessDetailService.getDetail(
                new WorkflowProcessDetailQueryDto(processInstanceId, null));
            assertThat(detail.processFormList())
                .as("详情必须同时返回开始、初审和复审三份真实提交快照")
                .hasSize(3);
            WorkflowProcessFormSnapshotView startForm = detail.processFormList().stream()
                .filter(form -> form.taskId() == null).findFirst().orElseThrow();
            assertThat(startForm.values().get("requestTitle").textValue())
                .isEqualTo("历史快照验证");
            assertThat(startForm.snapshotTime()).isNotNull();
            Map<String, WorkflowProcessFormSnapshotView> submittedForms = detail.processFormList()
                .stream()
                .filter(form -> form.taskId() != null)
                .collect(java.util.stream.Collectors.toMap(
                    WorkflowProcessFormSnapshotView::taskId, form -> form));
            assertThat(submittedForms).containsOnlyKeys(firstTask.getId(), secondTask.getId());
            assertThat(submittedForms.get(firstTask.getId()).values()
                .get("sharedValue").textValue()).isEqualTo(firstSharedValue);
            assertThat(submittedForms.get(secondTask.getId()).values()
                .get("sharedValue").textValue()).isEqualTo("second-value");
            assertThat(submittedForms.values())
                .allSatisfy(form -> assertThat(form.snapshotTime()).isNotNull());
        }
        finally
        {
            SecurityContextHolder.clearContext();
            try
            {
                jdbcTemplate.update("delete from ACT_HI_DETAIL where ID_ in (?, ?, ?)",
                    oversizedDetailId, cumulativeDetailIdOne, cumulativeDetailIdTwo);
            }
            finally
            {
                try
                {
                    jdbcTemplate.update(
                        "delete from ACT_GE_BYTEARRAY where ID_ in (?, ?, ?)",
                        oversizedByteArrayId, cumulativeByteArrayIdOne,
                        cumulativeByteArrayIdTwo);
                }
                finally
                {
                    cleanupLifecycleDeployment(jdbcTemplate, repositoryService,
                        deploymentId, formIds);
                }
            }
        }

        assertThat(repositoryService.createDeploymentQuery()
            .deploymentNameLike(DEPLOYMENT_NAME_PREFIX + "-detail-history%").count()).isZero();
        assertThat(runtimeService.createProcessInstanceQuery()
            .processInstanceBusinessKey(businessKey).count()).isZero();
        assertThat(historyService.createHistoricProcessInstanceQuery()
            .processInstanceBusinessKey(businessKey).count()).isZero();
    }

    /**
     * 通过真实 MySQL 证明活动表单不会由 Flowable 初始化字符串 Blob 或 JSON Blob 正文。
     *
     * 场景依次注入带 readObject 探针的 Java 对象流、带尾随根节点的 json、超限 json，
     * 再创建三条各自合法但累计超过 2 MiB 的真实 json Blob；所有异常都必须由受控门禁拒绝。
     *
     * @return 无返回值；任一恶意正文被回显、解析或绕过容量门禁时测试失败
     */
    @Test
    @Order(52)
    void rejectsUnsafeAndCumulativeCurrentBodiesFromRealMysql()
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        TaskService taskService = processEngine.getTaskService();
        HistoryService historyService = processEngine.getHistoryService();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dynamicDataSource);
        String deploymentId = null;
        List<Long> formIds = List.of();
        String businessKey = BUSINESS_KEY_PREFIX + "detail-raw-value-" + UUID.randomUUID();

        try
        {
            Deployment deployment = repositoryService.createDeployment()
                .name(DEPLOYMENT_NAME_PREFIX + "-detail-raw-value")
                .addClasspathResource("processes/flowable-detail-history-it.bpmn20.xml")
                .deploy();
            deploymentId = deployment.getId();
            formIds = insertDetailHistoryFormSnapshots(jdbcTemplate, deploymentId,
                DETAIL_RAW_VALUE_TASK_FORM_CONTENT);
            var definition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deploymentId).singleResult();
            assertThat(definition).as("恶意变量测试必须只部署一个流程定义").isNotNull();

            setSecurityContextUser(1L);
            WorkflowProcessInstanceSnapshot started = workflowProcessStartService.start(
                new StartProcessRequest(definition.getId(), businessKey,
                    Map.of("requestTitle", "活动变量安全验证")));
            String processInstanceId = started.id();
            Task activeTask = requireSingleTask(taskService, processInstanceId, "firstReview");

            // 先让 Flowable 真实创建 longString 变量及其 Blob 关系，再只篡改隔离库正文。
            taskService.setVariable(activeTask.getId(), "sharedValue", "x".repeat(5000));
            Map<String, Object> storage = jdbcTemplate.queryForMap(
                "select ID_ as variable_id, BYTEARRAY_ID_ as byte_array_id, "
                    + "VAR_TYPE_ as variable_type from ACT_HI_VARINST "
                    + "where PROC_INST_ID_ = ? and NAME_ = ? and TASK_ID_ is null",
                processInstanceId, "sharedValue");
            String variableId = String.valueOf(storage.get("variable_id"));
            String byteArrayId = String.valueOf(storage.get("byte_array_id"));
            assertThat(storage.get("variable_type")).isEqualTo("longString");
            assertThat(variableId).isNotBlank();
            assertThat(byteArrayId).isNotBlank().isNotEqualTo("null");

            DESERIALIZATION_CANARY_TRIGGERED.set(false);
            byte[] maliciousObject = serializeJavaValue(new DeserializationCanary());
            assertThat(jdbcTemplate.update(
                "update ACT_GE_BYTEARRAY set BYTES_ = ? where ID_ = ?",
                maliciousObject, byteArrayId)).isEqualTo(1);
            assertThatThrownBy(() -> workflowProcessDetailService.getDetail(
                new WorkflowProcessDetailQueryDto(processInstanceId, activeTask.getId())))
                .isInstanceOfSatisfying(ServiceException.class,
                    exception -> assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR));
            assertThat(DESERIALIZATION_CANARY_TRIGGERED.get())
                .as("Flowable 禁止初始化查询不得执行任意 readObject")
                .isFalse();

            byte[] trailingJson = "{\"approved\":true}{\"trailing\":true}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(jdbcTemplate.update(
                "update ACT_HI_VARINST set VAR_TYPE_ = 'json' where ID_ = ?",
                variableId)).isEqualTo(1);
            assertThat(jdbcTemplate.update(
                "update ACT_GE_BYTEARRAY set BYTES_ = ? where ID_ = ?",
                trailingJson, byteArrayId)).isEqualTo(1);
            assertThatThrownBy(() -> workflowProcessDetailService.getDetail(
                new WorkflowProcessDetailQueryDto(processInstanceId, activeTask.getId())))
                .isInstanceOfSatisfying(ServiceException.class,
                    exception -> assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR));

            assertThat(jdbcTemplate.update(
                "update ACT_GE_BYTEARRAY set BYTES_ = ? where ID_ = ?",
                new byte[OVERSIZED_HISTORY_SNAPSHOT_BYTES], byteArrayId)).isEqualTo(1);
            assertThatThrownBy(() -> workflowProcessDetailService.getDetail(
                new WorkflowProcessDetailQueryDto(processInstanceId, activeTask.getId())))
                .isInstanceOfSatisfying(ServiceException.class,
                    exception -> assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR));

            // 恢复原字段为合法小 JSON，确保后续失败只能来自三条新增正文的累计容量门禁。
            byte[] restoredJson = "{\"restored\":true}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(jdbcTemplate.update(
                "update ACT_GE_BYTEARRAY set BYTES_ = ? where ID_ = ?",
                restoredJson, byteArrayId)).isEqualTo(1);

            List<String> cumulativeVariableNames = List.of(
                "cumulativePayloadOne", "cumulativePayloadTwo", "cumulativePayloadThree");
            String cumulativeText = "x".repeat(CUMULATIVE_CURRENT_JSON_TEXT_LENGTH);
            ObjectMapper cumulativeMapper = JsonMapper.shared();
            for (String variableName : cumulativeVariableNames)
            {
                ObjectNode payload = cumulativeMapper.createObjectNode();
                payload.put("payload", cumulativeText);
                taskService.setVariable(activeTask.getId(), variableName, payload);
            }

            List<Map<String, Object>> cumulativeStorage = jdbcTemplate.queryForList(
                "select v.NAME_ as variable_name, v.VAR_TYPE_ as variable_type, "
                    + "v.BYTEARRAY_ID_ as byte_array_id, "
                    + "octet_length(b.BYTES_) as stored_bytes "
                    + "from ACT_HI_VARINST v "
                    + "join ACT_GE_BYTEARRAY b on b.ID_ = v.BYTEARRAY_ID_ "
                    + "where v.PROC_INST_ID_ = ? and v.TASK_ID_ is null "
                    + "and v.NAME_ in (?, ?, ?) order by v.NAME_",
                processInstanceId, cumulativeVariableNames.get(0),
                cumulativeVariableNames.get(1), cumulativeVariableNames.get(2));
            assertThat(cumulativeStorage).hasSize(cumulativeVariableNames.size())
                .allSatisfy(row ->
                {
                    assertThat(row.get("variable_type")).isEqualTo("json");
                    assertThat(String.valueOf(row.get("byte_array_id")))
                        .isNotBlank().isNotEqualTo("null");
                    assertThat(((Number) row.get("stored_bytes")).longValue())
                        .isPositive().isLessThanOrEqualTo(CURRENT_VARIABLE_SINGLE_BODY_LIMIT_BYTES);
                });
            long cumulativeStoredBytes = cumulativeStorage.stream()
                .mapToLong(row -> ((Number) row.get("stored_bytes")).longValue()).sum();
            assertThat(cumulativeStoredBytes).isGreaterThan(CURRENT_VARIABLE_TOTAL_BODY_LIMIT_BYTES);

            assertThatThrownBy(() -> workflowProcessDetailService.getDetail(
                new WorkflowProcessDetailQueryDto(processInstanceId, activeTask.getId())))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
                    assertThat(exception.getMessage())
                        .isEqualTo("活动表单变量累计正文超过安全上限");
                });
        }
        finally
        {
            SecurityContextHolder.clearContext();
            DESERIALIZATION_CANARY_TRIGGERED.set(false);
            cleanupLifecycleDeployment(jdbcTemplate, repositoryService, deploymentId, formIds);
        }

        assertThat(repositoryService.createDeploymentQuery()
            .deploymentNameLike(DEPLOYMENT_NAME_PREFIX + "-detail-raw-value%").count()).isZero();
        assertThat(runtimeService.createProcessInstanceQuery()
            .processInstanceBusinessKey(businessKey).count()).isZero();
        assertThat(historyService.createHistoricProcessInstanceQuery()
            .processInstanceBusinessKey(businessKey).count()).isZero();
    }

    /**
     * 验证正式发起快照和任务提交快照只按部署 schema 投影，并按实例参与关系授权生成真实 PNG 流程图。
     *
     * @return 无返回值；隐藏变量泄露、PNG 无效或越权读取成功时测试失败
     */
    @Test
    @Order(52)
    void projectsSafeVariablesAndGeneratesAuthorizedProcessDiagram()
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        TaskService taskService = processEngine.getTaskService();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dynamicDataSource);
        String deploymentId = null;
        List<Long> formIds = List.of();
        boolean temporaryUserInserted = false;
        Long userAutoIncrementBaseline = readWorkflowIdentityAutoIncrementState(jdbcTemplate).get("sys_user");
        String businessKey = BUSINESS_KEY_PREFIX + "lifecycle-read-" + UUID.randomUUID();

        assertTemporaryWorkflowUserAbsent(jdbcTemplate);
        try
        {
            Deployment deployment = repositoryService.createDeployment()
                .name(DEPLOYMENT_NAME_PREFIX + "-lifecycle-read")
                .addClasspathResource("processes/flowable-task-lifecycle-it.bpmn20.xml")
                .deploy();
            deploymentId = deployment.getId();
            formIds = insertLifecycleFormSnapshots(jdbcTemplate, deploymentId);
            var processDefinition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deploymentId).singleResult();
            assertThat(processDefinition).isNotNull();

            int insertedRows = jdbcTemplate.update(
                "insert into sys_user (user_id, dept_id, user_name, nick_name, status, del_flag) "
                    + "values (?, null, ?, ?, '0', '0')",
                ADAPTER_NON_CANDIDATE_USER_ID,
                "flowable_read_it_90",
                "流程读取越权用户"
            );
            temporaryUserInserted = insertedRows == 1;
            assertThat(insertedRows).isEqualTo(1);

            setSecurityContextUser(1L);
            WorkflowProcessInstanceSnapshot processInstance = workflowProcessStartService.start(
                new StartProcessRequest(processDefinition.getId(), businessKey,
                    Map.of("requestReason", "变量白名单验证")));
            String processInstanceId = processInstance.id();
            // 模拟服务端监听器产生的非表单敏感变量，证明投影层不会把引擎内部变量泄露给页面。
            runtimeService.setVariable(processInstanceId, "hiddenSecret", "不可回显");
            assertThat(runtimeService.getVariable(processInstanceId, "hiddenSecret"))
                .isEqualTo("不可回显");
            Task firstTask = requireSingleTask(taskService, processInstanceId, "firstReview");
            Map<String, JsonNode> initialVariables = workflowTaskReadService.getProcessVariables(firstTask.getId());
            assertThat(initialVariables).containsOnlyKeys("requestReason");
            assertThat(initialVariables.get("requestReason").asText()).isEqualTo("变量白名单验证");
            assertThat(initialVariables).doesNotContainKeys(
                "hiddenSecret", WorkflowProcessStartService.INITIATOR_VARIABLE,
                WorkflowProcessStartService.PROCESS_STATUS_VARIABLE);

            workflowTaskLifecycleService.completeTask(new WorkflowTaskCompleteRequest(
                firstTask.getId(), "进入复审", Map.of("firstDecision", "初审通过")));
            Task finalTask = requireSingleTask(taskService, processInstanceId, "finalReview");
            Map<String, JsonNode> projectedVariables =
                workflowTaskReadService.getProcessVariables(finalTask.getId());
            assertThat(projectedVariables).containsOnlyKeys("requestReason", "firstDecision");
            assertThat(projectedVariables.get("requestReason").asText()).isEqualTo("变量白名单验证");
            assertThat(projectedVariables.get("firstDecision").asText()).isEqualTo("初审通过");
            assertThat(projectedVariables).doesNotContainKey("hiddenSecret");

            byte[] diagram = workflowTaskReadService.generateDiagram(processInstanceId);
            assertThat(diagram).as("真实流程图必须包含可渲染 PNG 正文").hasSizeGreaterThan(100);
            assertThat(diagram).startsWith(
                (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47,
                (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A);

            setSecurityContextUser(ADAPTER_NON_CANDIDATE_USER_ID);
            assertWorkflowBusinessError(
                () -> workflowTaskReadService.getProcessVariables(finalTask.getId()),
                HttpStatus.FORBIDDEN,
                "无权执行当前工作流操作"
            );
            assertWorkflowBusinessError(
                () -> workflowTaskReadService.generateDiagram(processInstanceId),
                HttpStatus.FORBIDDEN,
                "无权执行当前工作流操作"
            );
            assertThat(taskService.createTaskQuery().taskId(finalTask.getId()).count()).isEqualTo(1L);
        }
        finally
        {
            SecurityContextHolder.clearContext();
            try
            {
                cleanupLifecycleDeployment(jdbcTemplate, repositoryService, deploymentId, formIds);
            }
            finally
            {
                if (temporaryUserInserted)
                {
                    jdbcTemplate.update("delete from sys_user where user_id = ?",
                        ADAPTER_NON_CANDIDATE_USER_ID);
                }
            }
        }

        assertTemporaryWorkflowUserAbsent(jdbcTemplate);
        assertThat(readWorkflowIdentityAutoIncrementState(jdbcTemplate).get("sys_user"))
            .isEqualTo(userAutoIncrementBaseline);
        assertThat(runtimeService.createProcessInstanceQuery()
            .processInstanceBusinessKey(businessKey).count()).isZero();
    }

    /**
     * 通过真实 Flowable 实例验证 I01 状态切换、I02 双权限终止和 P15 历史删除闭环。
     *
     * @return 无返回值；权限、运行状态、历史审计、抄送删除或清理任一结果不符合契约时测试失败
     */
    @Test
    @Order(53)
    void managesInstanceStateTerminationAndHistoryDeletionThroughRealEngine()
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        HistoryService historyService = processEngine.getHistoryService();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dynamicDataSource);
        String canceledBusinessKey = BUSINESS_KEY_PREFIX + "instance-cancel-" + UUID.randomUUID();
        String terminatedBusinessKey = BUSINESS_KEY_PREFIX + "instance-terminate-" + UUID.randomUUID();
        String copyEventId = BUSINESS_KEY_PREFIX + "history-copy-" + UUID.randomUUID();
        String deploymentId = null;
        boolean temporaryUserInserted = false;
        Long userAutoIncrementBaseline = readWorkflowIdentityAutoIncrementState(jdbcTemplate).get("sys_user");

        assertThat(userAutoIncrementBaseline).isGreaterThan(ADAPTER_NON_CANDIDATE_USER_ID);
        assertTemporaryWorkflowUserAbsent(jdbcTemplate);
        try
        {
            Deployment deployment = repositoryService.createDeployment()
                .name(DEPLOYMENT_NAME_PREFIX + "-instance-management")
                .addClasspathResource("processes/flowable-process-start-it.bpmn20.xml")
                .deploy();
            deploymentId = deployment.getId();
            var processDefinition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deploymentId)
                .singleResult();
            assertThat(processDefinition).as("实例管理测试部署必须只产生一个流程定义").isNotNull();

            int insertedUserRows = jdbcTemplate.update(
                "insert into sys_user (user_id, dept_id, user_name, nick_name, status, del_flag) "
                    + "values (?, null, ?, ?, '0', '0')",
                ADAPTER_NON_CANDIDATE_USER_ID,
                "flowable_instance_it_90",
                "流程实例管理用户"
            );
            temporaryUserInserted = insertedUserRows == 1;
            assertThat(insertedUserRows).as("越权和管理员终止必须使用真实有效用户").isEqualTo(1);

            // 发起人的第一个实例用于验证挂起、幂等挂起、激活以及无权限拒绝不会改变状态。
            setSecurityContextUser(1L, Set.of("workflow:process:state"));
            ProcessInstance canceledInstance = startProcessAsUser(
                runtimeService, processDefinition.getId(), canceledBusinessKey, Map.of());
            var suspended = workflowProcessInstanceService.updateState(
                new WorkflowInstanceStateRequest(canceledInstance.getId(),
                    WorkflowInstanceState.SUSPENDED));
            assertThat(suspended.state()).isEqualTo(WorkflowInstanceState.SUSPENDED);
            assertThat(suspended.changed()).isTrue();
            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(canceledInstance.getId()).singleResult().isSuspended()).isTrue();

            var unchanged = workflowProcessInstanceService.updateState(
                new WorkflowInstanceStateRequest(canceledInstance.getId(),
                    WorkflowInstanceState.SUSPENDED));
            assertThat(unchanged.changed()).isFalse();

            var activated = workflowProcessInstanceService.updateState(
                new WorkflowInstanceStateRequest(canceledInstance.getId(),
                    WorkflowInstanceState.ACTIVE));
            assertThat(activated.state()).isEqualTo(WorkflowInstanceState.ACTIVE);
            assertThat(activated.changed()).isTrue();
            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(canceledInstance.getId()).singleResult().isSuspended()).isFalse();

            setSecurityContextUser(1L, Set.of());
            assertWorkflowBusinessError(
                () -> workflowProcessInstanceService.updateState(
                    new WorkflowInstanceStateRequest(canceledInstance.getId(),
                        WorkflowInstanceState.SUSPENDED)),
                HttpStatus.FORBIDDEN,
                "无权执行当前流程实例操作"
            );
            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(canceledInstance.getId()).singleResult().isSuspended()).isFalse();

            // 只有 cancel 权限的真实发起人取消本人实例，随后重复请求必须稳定返回状态冲突。
            setSecurityContextUser(1L, Set.of("workflow:process:cancel"));
            long canceledInstanceCommentBaseline = countPersistedComments(
                jdbcTemplate, canceledInstance.getId());
            var canceled = workflowProcessInstanceService.terminate(
                new WorkflowInstanceTerminateRequest(canceledInstance.getId(), "发起人主动取消"));
            assertThat(canceled.processStatus()).isEqualTo("canceled");
            assertThat(canceled.actorUserId()).isEqualTo("1");
            assertThat(canceled.wasSuspended()).isFalse();
            assertProcessTerminationAudit(jdbcTemplate, canceledInstance.getId(),
                "canceled", "1", "CANCEL", "发起人主动取消", false,
                canceledInstanceCommentBaseline);
            assertWorkflowBusinessError(
                () -> workflowProcessInstanceService.terminate(
                    new WorkflowInstanceTerminateRequest(canceledInstance.getId(), "重复取消")),
                HttpStatus.CONFLICT,
                "流程实例已结束，不能重复执行当前操作"
            );

            // 第二个实例先验证非发起人 cancel 越权，再由具备管理员权限的同一用户终止挂起实例。
            ProcessInstance terminatedInstance = startProcessAsUser(
                runtimeService, processDefinition.getId(), terminatedBusinessKey, Map.of());
            setSecurityContextUser(ADAPTER_NON_CANDIDATE_USER_ID,
                Set.of("workflow:process:cancel"));
            assertWorkflowBusinessError(
                () -> workflowProcessInstanceService.terminate(
                    new WorkflowInstanceTerminateRequest(terminatedInstance.getId(), "越权取消")),
                HttpStatus.FORBIDDEN,
                "无权结束当前流程实例"
            );
            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(terminatedInstance.getId()).count()).isEqualTo(1L);

            // 引擎集成场景复用真实超级管理员；普通角色的实时撤权由 RBAC HTTP IT 独立覆盖。
            setSecurityContextUser(1L, Set.of("workflow:process:state"));
            workflowProcessInstanceService.updateState(new WorkflowInstanceStateRequest(
                terminatedInstance.getId(), WorkflowInstanceState.SUSPENDED));
            setSecurityContextUser(1L,
                Set.of("workflow:process:cancel", "workflow:process:terminate"));
            long terminatedInstanceCommentBaseline = countPersistedComments(
                jdbcTemplate, terminatedInstance.getId());
            var terminated = workflowProcessInstanceService.terminate(
                new WorkflowInstanceTerminateRequest(terminatedInstance.getId(),
                    "管理员终止挂起实例"));
            assertThat(terminated.processStatus()).isEqualTo("terminated");
            assertThat(terminated.actorUserId()).isEqualTo("1");
            assertThat(terminated.wasSuspended()).isTrue();
            assertProcessTerminationAudit(jdbcTemplate, terminatedInstance.getId(),
                "terminated", "1",
                "TERMINATE", "管理员终止挂起实例", true,
                terminatedInstanceCommentBaseline);

            // 已结束历史绑定一条正式抄送记录，P15 必须在同一事务中逻辑删除抄送并物理删除历史。
            int insertedCopyRows = jdbcTemplate.update(
                "insert into wf_copy "
                    + "(copy_event_id, title, process_id, process_name, category_id, deployment_id, "
                    + "instance_id, task_id, user_id, originator_id, originator_name, create_by, del_flag) "
                    + "values (?, ?, ?, ?, '', ?, ?, null, ?, ?, ?, ?, '0')",
                copyEventId,
                "待删除历史抄送",
                processDefinition.getKey(),
                processDefinition.getName(),
                deploymentId,
                canceledInstance.getId(),
                1L,
                1L,
                "管理员",
                "flowable-it-instance"
            );
            assertThat(insertedCopyRows).as("历史删除必须使用真实 wf_copy 引用").isEqualTo(1);

            setSecurityContextUser(1L, Set.of("workflow:process:remove"));
            var deletion = workflowProcessInstanceService.deleteCompletedHistory(
                List.of(canceledInstance.getId()));
            assertThat(deletion.requestedCount()).isEqualTo(1);
            assertThat(deletion.deletedHistoryCount()).isEqualTo(1);
            assertThat(deletion.deletedCopyCount()).isEqualTo(1);
            assertThat(historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(canceledInstance.getId()).count()).isZero();
            assertThat(jdbcTemplate.queryForObject(
                "select del_flag from wf_copy where copy_event_id = ?",
                String.class, copyEventId)).isEqualTo("2");
            assertThat(jdbcTemplate.queryForObject(
                "select update_by from wf_copy where copy_event_id = ?",
                String.class, copyEventId)).isEqualTo("1");
        }
        finally
        {
            SecurityContextHolder.clearContext();
            try
            {
                jdbcTemplate.update("delete from wf_copy where copy_event_id = ?", copyEventId);
            }
            finally
            {
                try
                {
                    if (deploymentId != null)
                    {
                        deleteDeploymentIfPresent(repositoryService, deploymentId);
                    }
                }
                finally
                {
                    if (temporaryUserInserted)
                    {
                        jdbcTemplate.update("delete from sys_user where user_id = ?",
                            ADAPTER_NON_CANDIDATE_USER_ID);
                    }
                }
            }
        }

        assertTemporaryWorkflowUserAbsent(jdbcTemplate);
        assertThat(readWorkflowIdentityAutoIncrementState(jdbcTemplate).get("sys_user"))
            .isEqualTo(userAutoIncrementBaseline);
        assertThat(runtimeService.createProcessInstanceQuery()
            .processInstanceBusinessKey(canceledBusinessKey).count()).isZero();
        assertThat(runtimeService.createProcessInstanceQuery()
            .processInstanceBusinessKey(terminatedBusinessKey).count()).isZero();
        assertThat(historyService.createHistoricProcessInstanceQuery()
            .processInstanceBusinessKey(canceledBusinessKey).count()).isZero();
        assertThat(historyService.createHistoricProcessInstanceQuery()
            .processInstanceBusinessKey(terminatedBusinessKey).count()).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from wf_copy where copy_event_id = ?",
            Long.class, copyEventId)).isZero();
    }

    /**
     * 使用真实 MySQL 并发保存模型，验证 requestId 幂等锁和版本组锁保持数据一致。
     *
     * @return 无返回值；重复写入、版本冲突、幂等记录不一致或清理残留时测试失败
     * @throws Exception 并发线程等待、执行或关闭失败时传播给 JUnit
     */
    @Test
    @Order(56)
    void serializesConcurrentModelSavesWithPersistentIdempotency() throws Exception
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dynamicDataSource);
        // modelKey 和 categoryCode 都带随机 UUID，只允许本场景查询和清理自己的正式记录。
        String modelKey = "model_save_it_" + UUID.randomUUID().toString().replace("-", "");
        String categoryCode = "model_save_category_it_"
            + UUID.randomUUID().toString().replace("-", "");
        String sourceModelId = null;
        boolean categoryInserted = false;

        try
        {
            jdbcTemplate.update(
                "insert into wf_category (category_name, code, create_by, del_flag) values (?, ?, ?, '0')",
                "模型保存并发集成测试分类", categoryCode, "1");
            categoryInserted = true;
            Model source = repositoryService.newModel();
            source.setName("模型保存并发集成测试");
            source.setKey(modelKey);
            source.setCategory(categoryCode);
            source.setMetaInfo("{}");
            source.setTenantId("");
            source.setVersion(1);
            repositoryService.saveModel(source);
            sourceModelId = source.getId();

            // 同一保存意图的两个真实事务必须复用同一结果，只允许创建一个新版本。
            String replayRequestId = UUID.randomUUID().toString();
            WorkflowModelDto replayRequest = modelSaveRequest(sourceModelId, replayRequestId);
            List<String> replayResults = runConcurrentModelSaves(replayRequest, replayRequest);
            assertThat(replayResults).hasSize(2).containsOnly(replayResults.get(0));
            assertThat(repositoryService.createModelQuery().modelKey(modelKey).list())
                .extracting(Model::getVersion).containsExactlyInAnyOrder(1, 2);
            assertThat(jdbcTemplate.queryForObject(
                "select saved_model_id from wf_model_save_idempotency where request_id = ?",
                String.class, replayRequestId)).isEqualTo(replayResults.get(0));

            // 不同保存意图竞争同一版本组时必须串行分配后续版本，且每条幂等记录指向真实模型。
            String firstRequestId = UUID.randomUUID().toString();
            String secondRequestId = UUID.randomUUID().toString();
            List<String> versionResults = runConcurrentModelSaves(
                modelSaveRequest(sourceModelId, firstRequestId),
                modelSaveRequest(sourceModelId, secondRequestId));
            assertThat(versionResults).doesNotHaveDuplicates();
            List<Model> savedModels = repositoryService.createModelQuery().modelKey(modelKey).list();
            assertThat(savedModels).extracting(Model::getVersion)
                .containsExactlyInAnyOrder(1, 2, 3, 4);
            assertThat(savedModels).extracting(Model::getId).containsAll(versionResults);
            assertThat(jdbcTemplate.queryForList(
                "select saved_model_id from wf_model_save_idempotency where source_model_id = ?",
                String.class, sourceModelId))
                .containsExactlyInAnyOrder(replayResults.get(0), versionResults.get(0), versionResults.get(1));
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_model_save_idempotency "
                    + "where source_model_id = ? and complete_time is not null",
                Long.class, sourceModelId)).isEqualTo(3L);
            for (String savedModelId : new LinkedHashSet<>(List.of(
                    replayResults.get(0), versionResults.get(0), versionResults.get(1))))
            {
                assertThat(repositoryService.getModelEditorSource(savedModelId))
                    .as("每条完成的幂等记录必须指向已持久化的相同 BPMN 正文")
                    .isEqualTo(MODEL_SAVE_BPMN_XML.strip().getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        finally
        {
            SecurityContextHolder.clearContext();
            // 幂等表对模型使用审计软引用，先按唯一来源主键清理，再删除本场景的全部模型版本。
            jdbcTemplate.update(
                "delete from wf_model_save_idempotency where source_model_id = ?", sourceModelId);
            for (Model model : repositoryService.createModelQuery().modelKey(modelKey).list())
            {
                repositoryService.deleteModel(model.getId());
            }
            if (categoryInserted)
            {
                jdbcTemplate.update("delete from wf_category where code = ?", categoryCode);
            }
        }

        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from wf_model_save_idempotency where source_model_id = ?",
            Long.class, sourceModelId)).isZero();
        assertThat(repositoryService.createModelQuery().modelKey(modelKey).count()).isZero();
    }

    /**
     * 同时释放两个模型保存线程，并等待两个真实事务返回稳定模型主键。
     *
     * @param firstRequest WorkflowModelDto，第一个保存事务使用的完整请求
     * @param secondRequest WorkflowModelDto，第二个保存事务使用的完整请求
     * @return List&lt;String&gt;，两个事务各自返回的真实 Flowable 模型主键
     * @throws Exception 线程就绪、保存执行或线程池关闭失败时抛出
     */
    private List<String> runConcurrentModelSaves(WorkflowModelDto firstRequest,
            WorkflowModelDto secondRequest) throws Exception
    {
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try
        {
            Future<String> firstResult = executor.submit(
                () -> attemptConcurrentModelSave(firstRequest, workersReady, releaseWorkers));
            Future<String> secondResult = executor.submit(
                () -> attemptConcurrentModelSave(secondRequest, workersReady, releaseWorkers));
            assertThat(workersReady.await(10, TimeUnit.SECONDS))
                .as("两个模型保存线程必须在超时前同时就绪").isTrue();
            releaseWorkers.countDown();
            return List.of(firstResult.get(20, TimeUnit.SECONDS),
                secondResult.get(20, TimeUnit.SECONDS));
        }
        finally
        {
            releaseWorkers.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                .as("模型保存并发线程池必须完整退出").isTrue();
        }
    }

    /**
     * 在独立线程的真实 SecurityContext 中执行一次模型保存事务。
     *
     * @param request WorkflowModelDto，模型主键、BPMN 正文、新版本标志和幂等键
     * @param workersReady CountDownLatch，通知主线程当前保存线程已经就绪
     * @param releaseWorkers CountDownLatch，由主线程同时释放两个保存线程
     * @return String，真实保存成功的 Flowable 模型主键
     */
    private String attemptConcurrentModelSave(WorkflowModelDto request,
            CountDownLatch workersReady, CountDownLatch releaseWorkers)
    {
        try
        {
            setSecurityContextUser(1L);
            workersReady.countDown();
            if (!releaseWorkers.await(10, TimeUnit.SECONDS))
            {
                throw new IllegalStateException("等待模型并发保存开始信号超时");
            }
            return workflowModelService.saveModel(request);
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型并发保存线程被中断", exception);
        }
        finally
        {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 构造显式创建新版本的模型保存请求。
     *
     * @param sourceModelId String，所有并发事务共同指向的来源模型主键
     * @param requestId String，当前保存意图使用的唯一 UUID 幂等键
     * @return WorkflowModelDto，可直接提交真实模型保存服务的完整请求
     */
    private WorkflowModelDto modelSaveRequest(String sourceModelId, String requestId)
    {
        WorkflowModelDto request = new WorkflowModelDto();
        request.setModelId(sourceModelId);
        request.setSaveRequestId(requestId);
        request.setBpmnXml(MODEL_SAVE_BPMN_XML);
        request.setNewVersion(true);
        return request;
    }

    /**
     * 核对所有测试部署、实例、job、Redis 键和 Flowable 表行数均恢复到执行前基线。
     *
     * @return 无返回值；任何数据库或缓存残留都会使正式集成门禁失败
     */
    @Test
    @Order(99)
    void leavesNoDatabaseOrRedisResidue()
    {
        assertNoIntegrationTestResidue();
        assertThat(processEngine.getManagementService().getTableCount())
            .containsExactlyInAnyOrderEntriesOf(baselineTableCounts);
        assertThat(redisTemplate.hasKey(REDIS_MARKER_KEY)).isFalse();
        assertThat(redisDatabaseSize()).isEqualTo(baselineRedisKeyCount);
    }

    /**
     * 为生命周期测试部署写入三个真实可编辑表单及其不可变节点快照。
     *
     * @param jdbcTemplate JdbcTemplate，绑定专用集成测试 schema 的真实 MySQL 客户端
     * @param deploymentId String，串行生命周期测试的真实部署主键
     * @return List&lt;Long&gt;，本次创建并需在测试结束后清理的三个表单主键
     */
    private List<Long> insertLifecycleFormSnapshots(JdbcTemplate jdbcTemplate,
            String deploymentId)
    {
        List<LifecycleFormSpec> formSpecs = List.of(
            new LifecycleFormSpec(LIFECYCLE_START_FORM_KEY, "start",
                "生命周期发起表单", "发起申请", LIFECYCLE_START_FORM_CONTENT),
            new LifecycleFormSpec(LIFECYCLE_FIRST_FORM_KEY, "firstReview",
                "生命周期初审表单", "初审", LIFECYCLE_FIRST_FORM_CONTENT),
            new LifecycleFormSpec(LIFECYCLE_FINAL_FORM_KEY, "finalReview",
                "生命周期复审表单", "复审", LIFECYCLE_FINAL_FORM_CONTENT)
        );
        return insertFormSnapshots(jdbcTemplate, deploymentId, formSpecs,
            "flowable-it-lifecycle", "生命周期");
    }

    /**
     * 为历史详情集成场景写入开始、初审和复审三个真实部署表单快照。
     *
     * @param jdbcTemplate JdbcTemplate，绑定专用集成测试 schema 的真实 MySQL 客户端
     * @param deploymentId String，历史详情测试部署主键
     * @return List&lt;Long&gt;，本次创建并需随部署清理的三个表单主键
     */
    private List<Long> insertDetailHistoryFormSnapshots(JdbcTemplate jdbcTemplate,
            String deploymentId)
    {
        return insertDetailHistoryFormSnapshots(jdbcTemplate, deploymentId,
            DETAIL_HISTORY_TASK_FORM_CONTENT);
    }

    /**
     * 为历史详情场景写入可指定初审字段 schema 的三个真实部署表单快照。
     *
     * @param jdbcTemplate JdbcTemplate，绑定专用集成测试 schema 的真实 MySQL 客户端
     * @param deploymentId String，历史详情测试部署主键
     * @param firstTaskContent String，初审节点需要固化的完整表单 schema JSON
     * @return List&lt;Long&gt;，本次创建并需随部署清理的三个表单主键
     */
    private List<Long> insertDetailHistoryFormSnapshots(JdbcTemplate jdbcTemplate,
            String deploymentId, String firstTaskContent)
    {
        List<LifecycleFormSpec> formSpecs = List.of(
            new LifecycleFormSpec(DETAIL_HISTORY_START_FORM_KEY, "start",
                "历史详情发起表单", "发起", DETAIL_HISTORY_START_FORM_CONTENT),
            new LifecycleFormSpec(DETAIL_HISTORY_FIRST_FORM_KEY, "firstReview",
                "历史详情初审表单", "初审", firstTaskContent),
            new LifecycleFormSpec(DETAIL_HISTORY_SECOND_FORM_KEY, "secondReview",
                "历史详情复审表单", "复审", DETAIL_HISTORY_TASK_FORM_CONTENT)
        );
        return insertFormSnapshots(jdbcTemplate, deploymentId, formSpecs,
            "flowable-it-detail-history", "历史详情");
    }

    /**
     * 在单一真实事务中写入可编辑表单及对应部署快照。
     *
     * @param jdbcTemplate JdbcTemplate，绑定专用集成测试 schema 的真实 MySQL 客户端
     * @param deploymentId String，快照所属真实部署主键
     * @param formSpecs List&lt;LifecycleFormSpec&gt;，表单键、节点键、名称和 schema 规格
     * @param createdBy String，测试数据创建者标识
     * @param scenarioName String，断言提示中的场景名称
     * @return List&lt;Long&gt;，事务内生成的正式 wf_form 主键
     */
    private List<Long> insertFormSnapshots(JdbcTemplate jdbcTemplate, String deploymentId,
            List<LifecycleFormSpec> formSpecs, String createdBy, String scenarioName)
    {
        TransactionTemplate transactionTemplate = repeatableReadTransactionTemplate();
        List<Long> formIds = transactionTemplate.execute(status ->
        {
            List<Long> insertedFormIds = new ArrayList<>();
            for (LifecycleFormSpec formSpec : formSpecs)
            {
                GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
                int formRows = jdbcTemplate.update(connection ->
                {
                    PreparedStatement statement = connection.prepareStatement(
                        "insert into wf_form (form_name, content, create_by, del_flag) "
                            + "values (?, ?, ?, '0')",
                        Statement.RETURN_GENERATED_KEYS
                    );
                    statement.setString(1, formSpec.formName());
                    statement.setString(2, formSpec.content());
                    statement.setString(3, createdBy);
                    return statement;
                }, keyHolder);
                assertThat(formRows).as(scenarioName + "可编辑表单必须真实写入一行").isEqualTo(1);
                Number generatedKey = keyHolder.getKey();
                assertThat(generatedKey).as(scenarioName + "表单必须返回真实自增主键").isNotNull();
                long formId = generatedKey.longValue();
                insertedFormIds.add(formId);

                int snapshotRows = jdbcTemplate.update(
                    "insert into wf_deploy_form "
                        + "(deploy_id, form_id, form_key, node_key, form_name, node_name, "
                        + "content, create_by, del_flag) values (?, ?, ?, ?, ?, ?, ?, ?, '0')",
                    deploymentId,
                    formId,
                    formSpec.formKey(),
                    formSpec.nodeKey(),
                    formSpec.formName(),
                    formSpec.nodeName(),
                    formSpec.content(),
                    createdBy
                );
                assertThat(snapshotRows).as(scenarioName + "部署表单快照必须真实写入一行")
                    .isEqualTo(1);
            }
            return List.copyOf(insertedFormIds);
        });
        assertThat(formIds).as(scenarioName + "表单事务必须返回全部正式主键")
            .isNotNull().hasSize(formSpecs.size());
        return formIds;
    }

    /**
     * 以用户 1 的 Flowable 认证身份启动测试流程，并补齐服务端维护的状态变量。
     *
     * @param runtimeService RuntimeService，真实流程实例写入服务
     * @param processDefinitionId String，待启动的测试流程定义主键
     * @param businessKey String，测试专属业务主键
     * @param requestedVariables Map&lt;String, Object&gt;，场景需要的初始业务变量
     * @return ProcessInstance，已写入专用 MySQL schema 的活动流程实例
     */
    private ProcessInstance startProcessAsUser(RuntimeService runtimeService,
            String processDefinitionId, String businessKey,
            Map<String, Object> requestedVariables)
    {
        LinkedHashMap<String, Object> engineVariables = new LinkedHashMap<>();
        if (requestedVariables != null)
        {
            engineVariables.putAll(requestedVariables);
        }
        // 测试直接使用 RuntimeService 建立场景，仍保持正式服务维护的发起人和状态变量语义。
        engineVariables.put(WorkflowProcessStartService.INITIATOR_VARIABLE, "1");
        engineVariables.put(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE,
            WorkflowProcessStartService.RUNNING_STATUS);
        ProcessInstance processInstance = authenticationContext.runAs("1", () ->
            runtimeService.startProcessInstanceById(
                processDefinitionId, businessKey, Map.copyOf(engineVariables)));
        assertThat(processInstance).isNotNull();
        assertThat(processInstance.getProcessDefinitionId()).isEqualTo(processDefinitionId);
        assertThat(processInstance.getBusinessKey()).isEqualTo(businessKey);
        return processInstance;
    }

    /**
     * 使用 Flowable longString 相同的 Java 对象流格式生成真实 MySQL 攻击正文。
     *
     * @param value Object，待写入隔离库 Blob 的 String 或恶意非 String 测试对象
     * @return byte[]，ObjectOutputStream 生成的完整序列化正文
     */
    private byte[] serializeJavaValue(Object value)
    {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ObjectOutputStream objectOutput = new ObjectOutputStream(output))
        {
            objectOutput.writeObject(value);
            objectOutput.flush();
            return output.toByteArray();
        }
        catch (IOException exception)
        {
            throw new IllegalStateException("无法生成 longString 集成测试正文", exception);
        }
    }

    /**
     * 查询实例唯一活动任务并核对节点和静态办理人没有被服务层改写。
     *
     * @param taskService TaskService，真实活动任务查询服务
     * @param processInstanceId String，目标流程实例主键
     * @param taskDefinitionKey String，期望的 BPMN 用户任务节点 key
     * @return Task，唯一且由用户 1 办理的活动任务
     */
    private Task requireSingleTask(TaskService taskService, String processInstanceId,
            String taskDefinitionKey)
    {
        List<Task> tasks = taskService.createTaskQuery()
            .processInstanceId(processInstanceId)
            .active()
            .list();
        assertThat(tasks).as("串行测试流程必须只有一个活动任务").hasSize(1);
        Task task = tasks.get(0);
        assertThat(task.getTaskDefinitionKey()).isEqualTo(taskDefinitionKey);
        assertThat(task.getAssignee()).isEqualTo("1");
        return task;
    }

    /**
     * 清理生命周期测试的业务快照、可编辑表单及 Flowable 部署和全部实例历史。
     *
     * @param jdbcTemplate JdbcTemplate，绑定专用集成测试 schema 的真实 MySQL 客户端
     * @param repositoryService RepositoryService，执行部署级联清理的 Flowable 公共服务
     * @param deploymentId String，允许为空的测试部署主键
     * @param formIds List&lt;Long&gt;，允许为空的测试表单主键集合
     * @return 无返回值；任何残留都会在本方法或最终全表基线门禁中失败
     */
    private void cleanupLifecycleDeployment(JdbcTemplate jdbcTemplate,
            RepositoryService repositoryService, String deploymentId, List<Long> formIds)
    {
        try
        {
            if (deploymentId != null)
            {
                // 抄送表是项目正式业务表，不会被 Flowable 级联删除，必须先按部署快照关系清理。
                jdbcTemplate.update("delete from wf_copy where deployment_id = ?", deploymentId);
                jdbcTemplate.update("delete from wf_deploy_form where deploy_id = ?", deploymentId);
            }
            if (formIds != null)
            {
                for (Long formId : formIds)
                {
                    jdbcTemplate.update("delete from wf_form where form_id = ?", formId);
                }
            }
        }
        finally
        {
            if (deploymentId != null)
            {
                deleteDeploymentIfPresent(repositoryService, deploymentId);
            }
        }

        if (deploymentId != null)
        {
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_copy where deployment_id = ?",
                Long.class, deploymentId)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_deploy_form where deploy_id = ?",
                Long.class, deploymentId)).isZero();
        }
        if (formIds != null && !formIds.isEmpty())
        {
            for (Long formId : formIds)
            {
                assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_form where form_id = ?",
                    Long.class, formId)).isZero();
            }
        }
    }

    /**
     * 核对任务动作在正式 wf_copy 中只生成一条可追踪且未删除的抄送记录。
     *
     * @param jdbcTemplate JdbcTemplate，绑定专用集成测试 schema 的真实 MySQL 客户端
     * @param processInstanceId String，来源任务所属流程实例主键
     * @param taskId String，产生抄送事件的真实来源任务主键
     * @param recipientUserId long，正式有效抄送接收用户主键
     * @param action String，COMPLETE、RETURN、REJECT、DELEGATE 或 TRANSFER 动作编码
     * @return 无返回值；记录数量、事件键、接收人或审计字段不符合时测试失败
     */
    private void assertWorkflowCopy(JdbcTemplate jdbcTemplate, String processInstanceId,
            String taskId, long recipientUserId, String action)
    {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select copy_event_id, task_id, user_id, create_by, del_flag "
                + "from wf_copy where instance_id = ? and task_id = ? and user_id = ?",
            processInstanceId, taskId, recipientUserId);
        assertThat(rows).singleElement().satisfies(row ->
        {
            assertThat(String.valueOf(row.get("copy_event_id")))
                .startsWith(action + ":" + taskId + ":r");
            assertThat(String.valueOf(row.get("task_id"))).isEqualTo(taskId);
            assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(recipientUserId);
            assertThat(String.valueOf(row.get("create_by"))).isEqualTo("1");
            assertThat(String.valueOf(row.get("del_flag"))).isEqualTo("0");
        });
    }

    /**
     * 清理指定测试实例产生的正式抄送记录，避免 Flowable 部署级联后留下业务表残留。
     *
     * @param jdbcTemplate JdbcTemplate，绑定专用集成测试 schema 的真实 MySQL 客户端
     * @param processInstanceIds String[]，允许包含 null 的测试流程实例主键集合
     * @return 无返回值；每个非空实例的抄送记录均被物理清理
     */
    private void deleteWorkflowCopies(JdbcTemplate jdbcTemplate, String... processInstanceIds)
    {
        if (processInstanceIds == null)
        {
            return;
        }
        for (String processInstanceId : processInstanceIds)
        {
            if (processInstanceId != null)
            {
                jdbcTemplate.update("delete from wf_copy where instance_id = ?", processInstanceId);
            }
        }
    }

    /**
     * 在独立线程的真实 SecurityContext 中执行一次认领，并把稳定业务结果返回主测试线程。
     *
     * @param taskId String，两个线程竞争的同一 Flowable 活动任务主键
     * @param workersReady CountDownLatch，通知主线程当前认领线程已经就绪
     * @param releaseWorkers CountDownLatch，由主线程同时释放两个认领线程
     * @return ClaimAttemptResult，成功标记或已翻译的稳定业务异常
     */
    private ClaimAttemptResult attemptConcurrentClaim(String taskId, CountDownLatch workersReady,
            CountDownLatch releaseWorkers)
    {
        try
        {
            setSecurityContextUser(1L);
            workersReady.countDown();
            if (!releaseWorkers.await(10, TimeUnit.SECONDS))
            {
                throw new IllegalStateException("等待并发认领开始信号超时");
            }
            workflowTaskActionService.claim(new WorkflowTaskClaimRequest(taskId));
            return new ClaimAttemptResult(true, null);
        }
        catch (ServiceException exception)
        {
            return new ClaimAttemptResult(false, exception);
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发认领线程被中断", exception);
        }
        finally
        {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 先锁定真实 ACT_RU_TASK 行，再按指定顺序让撤回和认领都完成读预检并阻塞在写入点。
     *
     * @param processInstanceId String，竞态流程实例主键
     * @param sourceTaskId String，当前用户已完成且拟撤回的来源任务主键
     * @param candidateTaskId String，认领与撤回竞争的未处理直接后继任务主键
     * @param expectedWinner RaceWinner，通过 InnoDB 等待队列安排的先写动作
     * @return RevokeClaimRaceResult，按动作区分的成功或稳定业务失败结果
     * @throws Exception JDBC 锁、线程协调或结果等待失败时传播给 JUnit
     */
    private RevokeClaimRaceResult executeRevokeClaimRace(String processInstanceId,
            String sourceTaskId, String candidateTaskId, RaceWinner expectedWinner)
            throws Exception
    {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection lockConnection = dynamicDataSource.getConnection())
        {
            lockConnection.setAutoCommit(false);
            boolean lockReleased = false;
            try
            {
                try (PreparedStatement lockTask = lockConnection.prepareStatement(
                        "select ID_ from ACT_RU_TASK where ID_ = ? for update"))
                {
                    lockTask.setString(1, candidateTaskId);
                    try (var resultSet = lockTask.executeQuery())
                    {
                        assertThat(resultSet.next()).isTrue();
                        assertThat(resultSet.getString(1)).isEqualTo(candidateTaskId);
                        assertThat(resultSet.next()).isFalse();
                    }
                }

                CountDownLatch firstEntered = new CountDownLatch(1);
                CountDownLatch secondEntered = new CountDownLatch(1);
                Runnable revokeAction = () -> workflowTaskLifecycleService.revokeProcess(
                    new WorkflowProcessRevokeRequest(
                        processInstanceId, sourceTaskId, "竞态撤回"));
                Runnable claimAction = () -> workflowTaskActionService.claim(
                    new WorkflowTaskClaimRequest(candidateTaskId));
                Runnable firstAction = expectedWinner == RaceWinner.CLAIM
                    ? claimAction : revokeAction;
                Runnable secondAction = expectedWinner == RaceWinner.CLAIM
                    ? revokeAction : claimAction;

                Future<RaceAttempt> firstFuture = executor.submit(
                    () -> attemptLifecycleRace(firstEntered, firstAction));
                assertThat(firstEntered.await(10, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(250L);
                assertThat(firstFuture.isDone()).isFalse();

                Future<RaceAttempt> secondFuture = executor.submit(
                    () -> attemptLifecycleRace(secondEntered, secondAction));
                assertThat(secondEntered.await(10, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(250L);
                assertThat(secondFuture.isDone()).isFalse();

                // 释放测试持有的行锁后，InnoDB 按等待顺序只允许第一个业务事务提交。
                lockConnection.commit();
                lockReleased = true;
                RaceAttempt firstResult = firstFuture.get(20, TimeUnit.SECONDS);
                RaceAttempt secondResult = secondFuture.get(20, TimeUnit.SECONDS);
                return expectedWinner == RaceWinner.CLAIM
                    ? new RevokeClaimRaceResult(secondResult, firstResult)
                    : new RevokeClaimRaceResult(firstResult, secondResult);
            }
            finally
            {
                if (!lockReleased)
                {
                    lockConnection.rollback();
                }
            }
        }
        finally
        {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * 锁定同一实例的根 execution 与活动 task，再同时释放两个互斥终态命令形成真实数据库竞争。
     *
     * @param processInstanceId String，两个终态命令共同操作的运行实例主键
     * @param taskId String，两个终态命令共同影响的活动任务主键
     * @param permissions Set&lt;String&gt;，两个工作线程共同持有的受控工作流权限
     * @param firstAction Runnable，第一个终态命令，调用真实领域服务
     * @param secondAction Runnable，第二个终态命令，调用真实领域服务
     * @return TerminalCommandRaceResult，按入参顺序保存成功、409 或非预期失败
     * @throws Exception JDBC 行锁、线程协调或结果等待失败时传播给 JUnit
     */
    private TerminalCommandRaceResult executeTerminalCommandRace(String processInstanceId,
            String taskId, Set<String> permissions, Runnable firstAction,
            Runnable secondAction) throws Exception
    {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection lockConnection = dynamicDataSource.getConnection())
        {
            lockConnection.setAutoCommit(false);
            boolean lockReleased = false;
            try
            {
                // 同时冻结根 execution 和任务行，确保两个服务都已完成读预检后才进入真实写冲突。
                try (PreparedStatement lockExecution = lockConnection.prepareStatement(
                        "select ID_ from ACT_RU_EXECUTION "
                            + "where ID_ = ? and PROC_INST_ID_ = ? for update"))
                {
                    lockExecution.setString(1, processInstanceId);
                    lockExecution.setString(2, processInstanceId);
                    try (var resultSet = lockExecution.executeQuery())
                    {
                        assertThat(resultSet.next()).isTrue();
                        assertThat(resultSet.getString(1)).isEqualTo(processInstanceId);
                        assertThat(resultSet.next()).isFalse();
                    }
                }
                try (PreparedStatement lockTask = lockConnection.prepareStatement(
                        "select ID_ from ACT_RU_TASK where ID_ = ? "
                            + "and PROC_INST_ID_ = ? for update"))
                {
                    lockTask.setString(1, taskId);
                    lockTask.setString(2, processInstanceId);
                    try (var resultSet = lockTask.executeQuery())
                    {
                        assertThat(resultSet.next()).isTrue();
                        assertThat(resultSet.getString(1)).isEqualTo(taskId);
                        assertThat(resultSet.next()).isFalse();
                    }
                }

                CountDownLatch commandsEntered = new CountDownLatch(2);
                Future<RaceAttempt> firstFuture = executor.submit(() ->
                    attemptTerminalCommandRace(commandsEntered, permissions, firstAction));
                Future<RaceAttempt> secondFuture = executor.submit(() ->
                    attemptTerminalCommandRace(commandsEntered, permissions, secondAction));
                assertThat(commandsEntered.await(10, TimeUnit.SECONDS))
                    .as("两个终态命令线程必须同时进入真实服务").isTrue();
                Thread.sleep(300L);
                assertTerminalCommandBlocked(firstFuture,
                    "第一个终态命令必须阻塞在测试行锁后");
                assertTerminalCommandBlocked(secondFuture,
                    "第二个终态命令必须阻塞在测试行锁后");

                // 释放测试锁后由 InnoDB 和 Flowable 乐观锁共同裁决，业务上只能有一个事务提交。
                lockConnection.commit();
                lockReleased = true;
                return new TerminalCommandRaceResult(
                    firstFuture.get(30, TimeUnit.SECONDS),
                    secondFuture.get(30, TimeUnit.SECONDS));
            }
            finally
            {
                if (!lockReleased)
                {
                    lockConnection.rollback();
                }
            }
        }
        finally
        {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                .as("终态命令竞态线程池必须完整退出").isTrue();
        }
    }

    /**
     * 断言终态命令仍被真实数据库行锁阻塞，并在提前完成时输出完整业务结果。
     *
     * @param future Future&lt;RaceAttempt&gt;，正在执行的终态命令结果
     * @param message String，断言失败时对应的业务场景说明
     * @return 无返回值；命令提前成功或失败时抛出包含结果的断言异常
     * @throws Exception 提前完成结果无法读取时传播给 JUnit
     */
    private void assertTerminalCommandBlocked(Future<RaceAttempt> future,
            String message) throws Exception
    {
        if (!future.isDone())
        {
            return;
        }
        RaceAttempt earlyAttempt = future.get(1, TimeUnit.SECONDS);
        assertThat(future.isDone()).as(message + "，提前结果=" + earlyAttempt).isFalse();
    }

    /**
     * 在独立真实 SecurityContext 中执行一次终态命令并保存稳定业务结果。
     *
     * @param commandsEntered CountDownLatch，通知主线程当前命令已经进入执行路径
     * @param permissions Set&lt;String&gt;，当前线程需要持有的工作流权限集合
     * @param action Runnable，完成、取消、驳回或终止领域服务调用
     * @return RaceAttempt，成功、稳定业务异常或非预期异常三者之一
     */
    private RaceAttempt attemptTerminalCommandRace(CountDownLatch commandsEntered,
            Set<String> permissions, Runnable action)
    {
        try
        {
            setSecurityContextUser(1L, permissions);
            commandsEntered.countDown();
            action.run();
            return new RaceAttempt(true, null, null);
        }
        catch (ServiceException exception)
        {
            return new RaceAttempt(false, exception, null);
        }
        catch (Throwable failure)
        {
            return new RaceAttempt(false, null, failure);
        }
        finally
        {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 核对两个互斥终态命令只有一个提交，另一个稳定翻译为 HTTP 409 且没有基础设施异常。
     *
     * @param result TerminalCommandRaceResult，两个终态命令的完整执行结果
     * @return 无返回值；成功数量或失败语义不符合并发契约时测试失败
     */
    private void assertSingleTerminalCommandOutcome(TerminalCommandRaceResult result)
    {
        List<RaceAttempt> attempts = List.of(result.first(), result.second());
        assertThat(attempts).filteredOn(RaceAttempt::successful).hasSize(1);
        RaceAttempt failedAttempt = attempts.stream()
            .filter(attempt -> !attempt.successful())
            .findFirst()
            .orElseThrow();
        assertThat(failedAttempt.unexpectedFailure()).isNull();
        assertThat(failedAttempt.businessFailure()).isNotNull();
        assertThat(failedAttempt.businessFailure().getCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(failedAttempt.businessFailure().getMessage())
            .isEqualTo("工作流状态已发生变化，请刷新后重试");
    }

    /**
     * 核对终态命令被外层事务强制回滚后，运行实例、原任务、running 状态和零审计全部恢复。
     *
     * @param jdbcTemplate JdbcTemplate，连接专用真实 MySQL schema 的查询客户端
     * @param processInstanceId String，被强制回滚终态命令的流程实例主键
     * @param taskId String，回滚前后必须保持活动的同一任务主键
     * @param expectedCommentCount long，执行终态命令前已经提交的固定任务监听审计数量
     * @return 无返回值；任一运行、历史、变量、审计或抄送副作用残留时测试失败
     */
    private void assertTerminalCommandRollbackRestored(JdbcTemplate jdbcTemplate,
            String processInstanceId, String taskId, long expectedCommentCount)
    {
        RuntimeService runtimeService = processEngine.getRuntimeService();
        HistoryService historyService = processEngine.getHistoryService();
        TaskService taskService = processEngine.getTaskService();

        ProcessInstance restoredInstance = runtimeService.createProcessInstanceQuery()
            .processInstanceId(processInstanceId).singleResult();
        assertThat(restoredInstance).as("终态事务回滚后运行实例必须恢复").isNotNull();
        assertThat(restoredInstance.isSuspended()).isFalse();
        Task restoredTask = taskService.createTaskQuery().taskId(taskId).active().singleResult();
        assertThat(restoredTask).as("终态事务回滚后原任务必须保持活动").isNotNull();
        assertThat(restoredTask.getProcessInstanceId()).isEqualTo(processInstanceId);
        assertThat(restoredTask.getAssignee()).isEqualTo("1");

        var historicInstance = historyService.createHistoricProcessInstanceQuery()
            .processInstanceId(processInstanceId).singleResult();
        assertThat(historicInstance).isNotNull();
        assertThat(historicInstance.getEndTime()).isNull();
        assertThat(historicInstance.getDeleteReason()).isNull();
        assertThat(historicInstance.getBusinessStatus()).isNull();
        var historicTask = historyService.createHistoricTaskInstanceQuery()
            .taskId(taskId).singleResult();
        assertThat(historicTask).isNotNull();
        assertThat(historicTask.getEndTime()).isNull();
        assertThat(historicTask.getDeleteReason()).isNull();
        assertThat(runtimeService.getVariable(processInstanceId,
            WorkflowProcessStartService.PROCESS_STATUS_VARIABLE)).isEqualTo("running");
        assertThat(historyService.createHistoricVariableInstanceQuery()
            .processInstanceId(processInstanceId)
            .variableName(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE)
            .singleResult().getValue()).isEqualTo("running");

        // 固定 create/assignment 监听审计属于命令前基线；回滚后总数必须保持不变。
        assertThat(taskService.getProcessInstanceComments(processInstanceId))
            .hasSize(Math.toIntExact(expectedCommentCount));
        assertPersistedCommentCount(jdbcTemplate, processInstanceId, expectedCommentCount);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from wf_copy where instance_id = ?",
            Long.class, processInstanceId)).isZero();
    }

    /**
     * 在独立真实 SecurityContext 中执行一个撤回或认领动作并捕获稳定业务结果。
     *
     * @param entered CountDownLatch，通知主线程当前动作已经开始执行
     * @param action Runnable，撤回或认领领域服务调用
     * @return RaceAttempt，成功、业务异常或非预期异常三者之一
     */
    private RaceAttempt attemptLifecycleRace(CountDownLatch entered, Runnable action)
    {
        try
        {
            setSecurityContextUser(1L);
            entered.countDown();
            action.run();
            return new RaceAttempt(true, null, null);
        }
        catch (ServiceException exception)
        {
            return new RaceAttempt(false, exception, null);
        }
        catch (Throwable failure)
        {
            return new RaceAttempt(false, null, failure);
        }
        finally
        {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 从真实 Flowable comment API 定位并核对一条服务端生成的结构化任务审计记录。
     *
     * @param taskService TaskService，读取真实历史 comment 的 Flowable 公共服务
     * @param processInstanceId String，审计记录所属流程实例主键
     * @param taskId String，审计记录所属任务主键
     * @param commentType String，与旧系统兼容的 comment 类型
     * @param action String，服务端固定动作编码
     * @param actorUserId String，事务内重新核验的真实操作用户主键
     * @param targetUserId String，正式启用目标用户主键；认领和取消认领时为空
     * @param opinion String，期望持久化的业务意见
     * @return 无返回值；记录数量、引擎元数据或 JSON 字段不匹配时测试失败
     */
    private void assertAuditComment(TaskService taskService, String processInstanceId, String taskId,
            String commentType, String action, String actorUserId, String targetUserId, String opinion)
    {
        List<Comment> matchingComments = taskService.getProcessInstanceComments(processInstanceId).stream()
            .filter(comment -> action.equals(readAuditPayload(comment).path("action").asText()))
            .toList();
        assertThat(matchingComments).singleElement().satisfies(comment ->
        {
            JsonNode audit = readAuditPayload(comment);
            assertThat(comment.getProcessInstanceId()).isEqualTo(processInstanceId);
            assertThat(comment.getTaskId()).isEqualTo(taskId);
            assertThat(comment.getType()).isEqualTo(commentType);
            assertThat(comment.getUserId()).isEqualTo(actorUserId);
            assertThat(audit.path("action").asText()).isEqualTo(action);
            assertThat(audit.path("actorUserId").asText()).isEqualTo(actorUserId);
            assertThat(audit.path("opinion").asText()).isEqualTo(opinion);
            if (targetUserId == null)
            {
                assertThat(audit.has("targetUserId")).isFalse();
                assertThat(audit.size()).isEqualTo(3);
            }
            else
            {
                assertThat(audit.path("targetUserId").asText()).isEqualTo(targetUserId);
                assertThat(audit.size()).isEqualTo(4);
            }
        });
    }

    /**
     * 定位并核对生命周期服务生成的结构化状态迁移审计记录。
     *
     * @param taskService TaskService，读取真实历史 comment 的 Flowable 公共服务
     * @param processInstanceId String，审计记录所属流程实例主键
     * @param taskId String，审计记录所属活动或历史任务主键
     * @param commentType String，与旧系统兼容的 comment 类型
     * @param action String，COMPLETE、RETURN、REVOKE、REJECT 或 CANCEL
     * @param opinion String，期望持久化的业务意见
     * @param targetNodeKey String，可为空的目标 BPMN 节点 key
     * @param sourceTaskId String，可为空的撤回来源历史任务主键
     * @param expectedWasSuspended Boolean，可为空；非空时为取消前实例是否挂起，为空时要求审计不含该字段
     * @return void，无返回值；记录数量、引擎元数据或 JSON 字段不匹配时测试失败
     */
    private void assertLifecycleAuditComment(TaskService taskService,
            String processInstanceId, String taskId, String commentType, String action,
            String opinion, String targetNodeKey, String sourceTaskId, Boolean expectedWasSuspended)
    {
        // 同一动作在串行场景只能提交一次，先按 action 排除重复或遗漏的业务审计。
        List<Comment> matchingComments = taskService.getProcessInstanceComments(processInstanceId).stream()
            .filter(comment -> action.equals(readAuditPayload(comment).path("action").asText()))
            .toList();
        assertThat(matchingComments).hasSize(1);
        assertLifecycleAuditCommentForTask(taskService, processInstanceId, taskId,
            commentType, action, opinion, targetNodeKey, sourceTaskId, expectedWasSuspended);
    }

    /**
     * 按任务主键定位并核对生命周期服务生成的结构化状态迁移审计记录。
     *
     * @param taskService TaskService，读取真实历史 comment 的 Flowable 公共服务
     * @param processInstanceId String，审计记录所属流程实例主键
     * @param taskId String，审计记录所属活动或历史任务主键
     * @param commentType String，与旧系统兼容的 comment 类型
     * @param action String，COMPLETE、RETURN、REVOKE、REJECT 或 CANCEL
     * @param opinion String，期望持久化的业务意见
     * @param targetNodeKey String，可为空的目标 BPMN 节点 key
     * @param sourceTaskId String，可为空的撤回来源历史任务主键
     * @param expectedWasSuspended Boolean，可为空；非空时为取消前实例是否挂起，为空时要求审计不含该字段
     * @return void，无返回值；任务级记录数量、引擎元数据或 JSON 字段不匹配时测试失败
     */
    private void assertLifecycleAuditCommentForTask(TaskService taskService,
            String processInstanceId, String taskId, String commentType, String action,
            String opinion, String targetNodeKey, String sourceTaskId, Boolean expectedWasSuspended)
    {
        // 并行撤回会为每个被合并的后继任务保留独立审计，必须按 taskId 精确归属。
        List<Comment> matchingComments = taskService.getProcessInstanceComments(processInstanceId).stream()
            .filter(comment -> taskId.equals(comment.getTaskId()))
            .filter(comment -> action.equals(readAuditPayload(comment).path("action").asText()))
            .toList();
        assertThat(matchingComments).singleElement().satisfies(comment ->
        {
            JsonNode audit = readAuditPayload(comment);
            assertThat(comment.getProcessInstanceId()).isEqualTo(processInstanceId);
            assertThat(comment.getTaskId()).isEqualTo(taskId);
            assertThat(comment.getType()).isEqualTo(commentType);
            assertThat(comment.getUserId()).isEqualTo("1");
            assertThat(audit.path("action").asText()).isEqualTo(action);
            assertThat(audit.path("actorUserId").asText()).isEqualTo("1");
            assertThat(audit.path("opinion").asText()).isEqualTo(opinion);
            if (targetNodeKey == null)
            {
                assertThat(audit.has("targetNodeKey")).isFalse();
            }
            else
            {
                assertThat(audit.path("targetNodeKey").asText()).isEqualTo(targetNodeKey);
            }
            if (sourceTaskId == null)
            {
                assertThat(audit.has("sourceTaskId")).isFalse();
            }
            else
            {
                assertThat(audit.path("sourceTaskId").asText()).isEqualTo(sourceTaskId);
            }
            // wasSuspended 只属于 CANCEL 契约；非取消动作必须明确禁止该字段，避免审计结构漂移。
            if (expectedWasSuspended == null)
            {
                assertThat(audit.has("wasSuspended")).isFalse();
            }
            else
            {
                assertThat(audit.path("wasSuspended").isBoolean()).isTrue();
                assertThat(audit.path("wasSuspended").booleanValue())
                    .isEqualTo(expectedWasSuspended.booleanValue());
            }
            // 可选字段数量参与对象总字段数核对，确保未声明的结构化字段不会静默进入正式契约。
            int optionalFieldCount = (targetNodeKey == null ? 0 : 1)
                + (sourceTaskId == null ? 0 : 1)
                + (expectedWasSuspended == null ? 0 : 1);
            assertThat(audit.size()).isEqualTo(3 + optionalFieldCount);
        });
    }

    /**
     * 解析真实 Flowable comment 的完整正文，非法 JSON 直接作为审计持久化失败处理。
     *
     * @param comment Comment，从 Flowable 公共 API 读取的历史意见
     * @return JsonNode，服务端写入的结构化审计正文
     */
    private JsonNode readAuditPayload(Comment comment)
    {
        return readAuditPayload(comment.getFullMessage());
    }

    /**
     * 解析 comment 正文或流程删除原因中的完整结构化审计 JSON。
     *
     * @param payload String，服务端生成并持久化的审计 JSON 正文
     * @return JsonNode，结构化审计对象
     */
    private JsonNode readAuditPayload(String payload)
    {
        try
        {
            return JsonMapper.shared().readTree(payload);
        }
        catch (Exception exception)
        {
            throw new AssertionError("Flowable 生命周期审计正文必须是合法 JSON", exception);
        }
    }

    /**
     * 直接查询 Flowable 物理历史意见表中的实例 comment 总数。
     *
     * @param jdbcTemplate JdbcTemplate，连接专用集成测试 schema 的真实 MySQL 客户端
     * @param processInstanceId String，待核对的流程实例主键
     * @return long，ACT_HI_COMMENT 中已经真实提交的实例 comment 行数
     */
    private long countPersistedComments(JdbcTemplate jdbcTemplate, String processInstanceId)
    {
        Long persistedCount = jdbcTemplate.queryForObject(
            "select count(*) from act_hi_comment where proc_inst_id_ = ?",
            Long.class,
            processInstanceId
        );
        assertThat(persistedCount).as("任务审计 comment 物理计数不能为空").isNotNull();
        return persistedCount;
    }

    /**
     * 直接查询 Flowable 物理历史意见表，证明 API 读取结果已经真实提交而非会话内回显。
     *
     * @param jdbcTemplate JdbcTemplate，连接专用集成测试 schema 的真实 MySQL 客户端
     * @param processInstanceId String，待核对的流程实例主键
     * @param expectedCount long，包含固定任务监听审计在内的期望 comment 总行数
     * @return 无返回值；ACT_HI_COMMENT 行数不匹配时测试失败
     */
    private void assertPersistedCommentCount(JdbcTemplate jdbcTemplate, String processInstanceId,
            long expectedCount)
    {
        assertThat(countPersistedComments(jdbcTemplate, processInstanceId))
            .as("任务审计 comment 必须真实持久化到 ACT_HI_COMMENT")
            .isEqualTo(expectedCount);
    }

    /**
     * 核对终止实例的运行数据、删除原因、历史变量和类型 6 结构化审计均已真实持久化。
     *
     * @param jdbcTemplate JdbcTemplate，连接专用集成测试 schema 的真实 MySQL 客户端
     * @param processInstanceId String，已取消或终止的流程实例主键
     * @param expectedStatus String，期望持久化的 canceled 或 terminated 状态
     * @param expectedActorUserId String，期望写入审计的真实操作人用户主键
     * @param expectedAction String，期望写入审计的 CANCEL 或 TERMINATE 动作
     * @param expectedReason String，期望写入删除原因和审计正文的业务原因
     * @param expectedWasSuspended boolean，动作前实例是否应处于挂起状态
     * @param commentCountBeforeTermination long，终态命令前已经提交的固定任务监听审计数量
     * @return 无返回值；任一引擎 API 或物理表持久化结果不一致时测试失败
     */
    private void assertProcessTerminationAudit(JdbcTemplate jdbcTemplate,
            String processInstanceId, String expectedStatus, String expectedActorUserId,
            String expectedAction, String expectedReason, boolean expectedWasSuspended,
            long commentCountBeforeTermination)
    {
        RuntimeService runtimeService = processEngine.getRuntimeService();
        HistoryService historyService = processEngine.getHistoryService();
        TaskService taskService = processEngine.getTaskService();

        assertThat(runtimeService.createProcessInstanceQuery()
            .processInstanceId(processInstanceId).count()).isZero();
        var historic = historyService.createHistoricProcessInstanceQuery()
            .processInstanceId(processInstanceId).singleResult();
        assertThat(historic).as("终止后必须保留完整流程历史").isNotNull();
        assertThat(historic.getEndTime()).isNotNull();
        assertThat(historic.getBusinessStatus())
            .as("列表和详情必须读取到与历史变量一致的业务终态")
            .isEqualTo(expectedStatus);
        assertThat(historic.getDeleteReason())
            .isEqualTo(expectedStatus + ": " + expectedReason);

        var statusVariable = historyService.createHistoricVariableInstanceQuery()
            .processInstanceId(processInstanceId)
            .variableName(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE)
            .singleResult();
        assertThat(statusVariable).as("终止状态必须保留在历史变量中").isNotNull();
        assertThat(statusVariable.getValue()).isEqualTo(expectedStatus);
        Long persistedStatusRows = jdbcTemplate.queryForObject(
            "select count(*) from act_hi_varinst where proc_inst_id_ = ? and name_ = ? and text_ = ?",
            Long.class,
            processInstanceId,
            WorkflowProcessStartService.PROCESS_STATUS_VARIABLE,
            expectedStatus
        );
        assertThat(persistedStatusRows).as("终止状态必须真实写入 ACT_HI_VARINST").isEqualTo(1L);

        List<Comment> auditComments = taskService.getProcessInstanceComments(processInstanceId, "6");
        assertThat(auditComments).singleElement().satisfies(comment ->
        {
            assertThat(comment.getType()).isEqualTo("6");
            assertThat(comment.getUserId()).isEqualTo(expectedActorUserId);
            JsonNode audit = readAuditPayload(comment);
            assertThat(audit.path("action").asText()).isEqualTo(expectedAction);
            assertThat(audit.path("actorUserId").asText()).isEqualTo(expectedActorUserId);
            assertThat(audit.path("processStatus").asText()).isEqualTo(expectedStatus);
            assertThat(audit.path("reason").asText()).isEqualTo(expectedReason);
            assertThat(audit.path("wasSuspended").asBoolean()).isEqualTo(expectedWasSuspended);
        });
        assertPersistedCommentCount(jdbcTemplate, processInstanceId,
            commentCountBeforeTermination + 1L);
    }

    /**
     * 在正式业务表中写入同源可编辑表单与当前部署的不可变开始节点快照。
     *
     * @param jdbcTemplate JdbcTemplate，绑定专用集成测试 schema 的真实 MySQL 客户端
     * @param deploymentId String，P14 测试流程的真实 Flowable 部署主键
     * @return long，新建 wf_form 记录的数据库主键
     */
    private long insertProcessStartFormSnapshot(JdbcTemplate jdbcTemplate, String deploymentId)
    {
        String formName = "P14真实发起表单-" + deploymentId;
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        int formRows = jdbcTemplate.update(connection ->
        {
            PreparedStatement statement = connection.prepareStatement(
                "insert into wf_form (form_name, content, create_by, del_flag) values (?, ?, ?, '0')",
                Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, formName);
            statement.setString(2, PROCESS_START_FORM_CONTENT);
            statement.setString(3, "flowable-it-p14");
            return statement;
        }, keyHolder);
        assertThat(formRows).as("P14 可编辑表单必须真实写入一行").isEqualTo(1);
        Number generatedKey = keyHolder.getKey();
        assertThat(generatedKey).as("wf_form 必须返回真实自增主键").isNotNull();
        long formId = generatedKey.longValue();

        try
        {
            int snapshotRows = jdbcTemplate.update(
                "insert into wf_deploy_form "
                    + "(deploy_id, form_id, form_key, node_key, form_name, node_name, content, create_by, del_flag) "
                    + "values (?, ?, ?, ?, ?, ?, ?, ?, '0')",
                deploymentId,
                formId,
                PROCESS_START_FORM_KEY,
                "start",
                formName,
                "发起申请",
                PROCESS_START_FORM_CONTENT,
                "flowable-it-p14"
            );
            assertThat(snapshotRows).as("P14 部署表单快照必须真实写入一行").isEqualTo(1);
            return formId;
        }
        catch (RuntimeException | Error exception)
        {
            // 快照写入失败时立即补偿删除已提交表单，避免 helper 异常导致调用方拿不到清理主键。
            jdbcTemplate.update("delete from wf_form where form_id = ?", formId);
            throw exception;
        }
    }

    /**
     * 将指定若依用户写入当前线程的真实 Spring SecurityContext，供适配器解析正式登录身份。
     *
     * @param userId long，必须已存在于 sys_user 且处于有效状态的用户主键
     * @return 无返回值；方法结束后 SecurityUtils 可从 Authentication principal 读取该用户
     */
    private void setSecurityContextUser(long userId)
    {
        setSecurityContextUser(userId, Set.of());
    }

    /**
     * 将指定若依用户及受控权限集合写入当前线程的真实 Spring SecurityContext。
     *
     * @param userId long，必须已存在于 sys_user 且处于有效状态的用户主键
     * @param permissions Set&lt;String&gt;，当前场景允许命中的工作流按钮权限集合
     * @return 无返回值；方法结束后身份解析和领域权限门禁读取同一 LoginUser
     */
    private void setSecurityContextUser(long userId, Set<String> permissions)
    {
        SysUser user = new SysUser(userId);
        user.setUserName("flowable_it_security_" + userId);
        user.setNickName("Flowable 集成测试用户 " + userId);
        LoginUser loginUser = new LoginUser(userId, null, user, Set.copyOf(permissions));
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
            loginUser, null, loginUser.getAuthorities());
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(authentication);
        assertThat(authentication.isAuthenticated()).isTrue();
    }

    /**
     * 断言活动流程查询返回模块自有快照及完整业务定位字段。
     *
     * @param snapshot WorkflowProcessInstanceSnapshot，适配器返回的不可变流程实例快照
     * @param processInstanceId String，预期流程实例 ID
     * @param processDefinitionId String，预期流程定义 ID
     * @param businessKey String，预期业务主键
     * @return 无返回值；快照类型、字段或活动状态不符合契约时测试失败
     */
    private void assertActiveProcessSnapshot(WorkflowProcessInstanceSnapshot snapshot,
            String processInstanceId, String processDefinitionId, String businessKey)
    {
        assertThat(snapshot.getClass()).isEqualTo(WorkflowProcessInstanceSnapshot.class);
        assertThat(snapshot.id()).isEqualTo(processInstanceId);
        assertThat(snapshot.processDefinitionId()).isEqualTo(processDefinitionId);
        assertThat(snapshot.businessKey()).isEqualTo(businessKey);
        assertThat(snapshot.suspended()).isFalse();
    }

    /**
     * 断言活动任务查询返回模块自有快照，并核对办理人和委派状态没有被适配层改写。
     *
     * @param snapshot WorkflowTaskSnapshot，适配器返回的不可变任务快照
     * @param taskId String，预期任务 ID
     * @param processInstanceId String，预期所属流程实例 ID
     * @param assignee String，可为空的预期当前办理人 ID
     * @param delegationState String，可为空的预期 Flowable 委派状态名称
     * @return 无返回值；快照类型、定位字段、办理状态或活动状态不符合契约时测试失败
     */
    private void assertTaskSnapshot(WorkflowTaskSnapshot snapshot, String taskId,
            String processInstanceId, String assignee, String delegationState)
    {
        assertThat(snapshot.getClass()).isEqualTo(WorkflowTaskSnapshot.class);
        assertThat(snapshot.id()).isEqualTo(taskId);
        assertThat(snapshot.name()).isEqualTo("审核");
        assertThat(snapshot.processInstanceId()).isEqualTo(processInstanceId);
        assertThat(snapshot.taskDefinitionKey()).isEqualTo("review");
        assertThat(snapshot.assignee()).isEqualTo(assignee);
        assertThat(snapshot.delegationState()).isEqualTo(delegationState);
        assertThat(snapshot.suspended()).isFalse();
    }

    /**
     * 断言适配器拒绝命令时返回稳定的若依业务状态和对外提示。
     *
     * @param action ThrowingCallable，预期失败的真实适配器调用
     * @param expectedCode int，预期 HTTP 业务状态码
     * @param expectedMessage String，预期稳定且不泄露引擎细节的用户提示
     * @return 无返回值；异常类型、状态码或提示不匹配时测试失败
     */
    private void assertWorkflowBusinessError(ThrowingCallable action, int expectedCode, String expectedMessage)
    {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(expectedCode);
            assertThat(exception.getMessage()).isEqualTo(expectedMessage);
        });
    }

    /**
     * 核对适配器越权门禁使用的低位隔离用户及其角色关系没有占用或残留。
     *
     * @param jdbcTemplate JdbcTemplate，连接专用集成测试 schema 的真实 MySQL 客户端
     * @return 无返回值；隔离主键或角色关系已存在时测试立即失败，禁止覆盖正式身份数据
     */
    private void assertTemporaryWorkflowUserAbsent(JdbcTemplate jdbcTemplate)
    {
        Long userCount = jdbcTemplate.queryForObject(
            "select count(*) from sys_user where user_id = ?",
            Long.class,
            ADAPTER_NON_CANDIDATE_USER_ID
        );
        Long userRoleCount = jdbcTemplate.queryForObject(
            "select count(*) from sys_user_role where user_id = ?",
            Long.class,
            ADAPTER_NON_CANDIDATE_USER_ID
        );
        assertThat(userCount).as("适配器门禁隔离用户不得占用或残留").isZero();
        assertThat(userRoleCount).as("适配器门禁隔离用户角色关系不得占用或残留").isZero();
    }

    /**
     * 为隔离测试用户关联现有启用审批角色，确保任务监听器按正式权限主数据校验目标用户。
     *
     * @param jdbcTemplate JdbcTemplate，连接专用集成测试 schema 的真实 MySQL 客户端
     * @param userId long，需要取得审批资格的隔离测试用户主键
     * @param scenarioName String，断言失败时用于定位业务场景的名称
     * @return boolean，角色关系真实插入一行时返回 true，调用方必须在 finally 精确删除
     */
    private boolean linkTemporaryApprovalRole(JdbcTemplate jdbcTemplate, long userId,
            String scenarioName)
    {
        // 只复用当前正式菜单授权链上的启用角色，不创建临时角色或绕过审批资格解析器。
        Long approvalRoleId = jdbcTemplate.queryForObject(
            "select min(r.role_id) from sys_role r "
                + "inner join sys_role_menu rm on rm.role_id = r.role_id "
                + "inner join sys_menu m on m.menu_id = rm.menu_id "
                + "where r.status = '0' and r.del_flag = '0' and m.status = '0' "
                + "and m.perms = 'workflow:process:approval'",
            Long.class
        );
        assertThat(approvalRoleId).as(scenarioName + "必须复用真实审批角色").isNotNull();
        int insertedRoleLinks = jdbcTemplate.update(
            "insert into sys_user_role (user_id, role_id) values (?, ?)",
            userId, approvalRoleId);
        assertThat(insertedRoleLinks).as(scenarioName + "必须具备真实审批资格").isEqualTo(1);
        return true;
    }

    /**
     * 验证 Flowable 最终使用若依主路由数据源、统一事务管理器和 Jackson 3 mapper。
     *
     * @return 无返回值；基础设施不一致时测试失败
     */
    private void assertSharedInfrastructure() throws SQLException
    {
        // 测试会级联清理部署数据，因此必须先确认连接的是隔离库且没有使用 root 账号。
        assertThat(expectedSchema).isNotBlank().endsWith("_flowable_it");
        try (Connection connection = dynamicDataSource.getConnection())
        {
            assertThat(connection.getCatalog()).isEqualTo(expectedSchema);
            String databaseUser = connection.getMetaData().getUserName().toLowerCase(Locale.ROOT);
            assertThat(databaseUser).isNotEqualTo("root").doesNotStartWith("root@");
        }

        DataSource engineDataSource = engineConfiguration.getDataSource();
        if (engineDataSource instanceof TransactionAwareDataSourceProxy proxy)
        {
            assertThat(proxy.getTargetDataSource()).isSameAs(dynamicDataSource);
        }
        else
        {
            assertThat(engineDataSource).isSameAs(dynamicDataSource);
        }
        assertThat(engineConfiguration.getTransactionManager()).isSameAs(transactionManager);
        assertThat(engineConfiguration.getVariableJsonMapper())
                .isInstanceOf(Jackson3VariableJsonMapper.class);
    }

    /**
     * 验证 Redis 连接工厂实际选择且仅允许选择 database 15，保护其他 database 不被清理。
     *
     * @return 无返回值；期望值或连接工厂实际 database 不是 15 时立即失败
     */
    private void assertRedisIsolation()
    {
        assertThat(expectedRedisDatabase).as("Flowable IT 只允许使用 Redis DB 15").isEqualTo(15);
        assertThat(redisConnectionFactory).isInstanceOf(LettuceConnectionFactory.class);
        LettuceConnectionFactory lettuceConnectionFactory = (LettuceConnectionFactory) redisConnectionFactory;
        assertThat(lettuceConnectionFactory.getDatabase()).isEqualTo(expectedRedisDatabase);
    }

    /**
     * 读取当前 Redis 连接所选择 database 的键总数。
     *
     * @return long，当前专用 Redis database 的键数量
     */
    private long redisDatabaseSize()
    {
        try (RedisConnection connection = redisConnectionFactory.getConnection())
        {
            Long size = connection.serverCommands().dbSize();
            assertThat(size).isNotNull();
            return size == null ? -1L : size;
        }
    }

    /**
     * 首次执行时记录完整若依上下文自然加载的 Redis 键数量，后续只允许测试专属键产生增量。
     *
     * @return 无返回值；同一 Failsafe 运行中的新 Spring 上下文必须恢复到相同基线
     */
    private void captureRedisBaselineOnce()
    {
        if (baselineRedisKeyCount == null)
        {
            synchronized (FlowableEngineIT.class)
            {
                if (baselineRedisKeyCount == null)
                {
                    assertThat(redisTemplate.hasKey(REDIS_MARKER_KEY)).isFalse();
                    baselineRedisKeyCount = redisDatabaseSize();
                }
            }
        }
    }

    /**
     * 首次执行时确认没有旧测试残留，并保存本轮测试开始前的 Flowable 全表行数。
     *
     * @return 无返回值；后续场景复用同一份不可变基线
     */
    private void captureDatabaseBaselineOnce()
    {
        if (baselineTableCounts != null)
        {
            return;
        }
        synchronized (FlowableEngineIT.class)
        {
            if (baselineTableCounts == null)
            {
                assertNoIntegrationTestResidue();
                baselineTableCounts = Map.copyOf(processEngine.getManagementService().getTableCount());
            }
        }
    }

    /**
     * 验证测试命名空间内没有部署、实例、历史变量或六类 job 残留。
     *
     * @return 无返回值；专用 schema 中发现任何残留时测试失败
     */
    private void assertNoIntegrationTestResidue()
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        HistoryService historyService = processEngine.getHistoryService();
        ManagementService managementService = processEngine.getManagementService();

        assertThat(repositoryService.createDeploymentQuery()
            .deploymentNameLike(DEPLOYMENT_NAME_PREFIX + "%").count()).isZero();
        assertThat(runtimeService.createProcessInstanceQuery()
            .processInstanceBusinessKeyLike(BUSINESS_KEY_PREFIX + "%").count()).isZero();
        assertThat(historyService.createHistoricProcessInstanceQuery()
            .processInstanceBusinessKeyLike(BUSINESS_KEY_PREFIX + "%").count()).isZero();
        assertThat(historyService.createHistoricVariableInstanceQuery()
            .variableName(JSON_VARIABLE_NAME).count()).isZero();
        assertThat(managementService.createJobQuery().count()).isZero();
        assertThat(managementService.createTimerJobQuery().count()).isZero();
        assertThat(managementService.createSuspendedJobQuery().count()).isZero();
        assertThat(managementService.createDeadLetterJobQuery().count()).isZero();
        assertThat(managementService.createExternalWorkerJobQuery().count()).isZero();
        assertThat(managementService.createHistoryJobQuery().count()).isZero();
    }

    /**
     * 向当前 Spring 事务写入身份 Mapper 验收所需的用户、角色、部门及权限关系。
     *
     * @param jdbcTemplate JdbcTemplate，绑定若依动态数据源当前事务的真实 MySQL 客户端
     * @return 无返回值；测试数据只允许由调用方事务回滚，不执行独立提交
     */
    private void insertWorkflowIdentityTestData(JdbcTemplate jdbcTemplate)
    {
        jdbcTemplate.batchUpdate(
            "insert into sys_dept (dept_id, dept_name, status, del_flag) values (?, ?, ?, ?)",
            List.of(
                new Object[] { 191L, "Flowable IT 正常部门一", "0", "0" },
                new Object[] { 192L, "Flowable IT 正常部门二", "0", "0" },
                new Object[] { 193L, "Flowable IT 停用部门", "1", "0" },
                new Object[] { 194L, "Flowable IT 删除部门", "0", "2" }
            )
        );
        jdbcTemplate.batchUpdate(
            "insert into sys_role (role_id, role_name, role_key, role_sort, status, del_flag) "
                + "values (?, ?, ?, ?, ?, ?)",
            List.of(
                new Object[] { 91L, "Flowable IT 正常角色一", "flowable_it_active_one", 1, "0", "0" },
                new Object[] { 92L, "Flowable IT 正常角色二", "flowable_it_active_two", 2, "0", "0" },
                new Object[] { 93L, "Flowable IT 停用角色", "flowable_it_inactive", 3, "1", "0" },
                new Object[] { 94L, "Flowable IT 删除角色", "flowable_it_deleted", 4, "0", "2" }
            )
        );
        jdbcTemplate.batchUpdate(
            "insert into sys_user (user_id, dept_id, user_name, nick_name, status, del_flag) "
                + "values (?, ?, ?, ?, ?, ?)",
            List.of(
                new Object[] { 91L, 191L, "flowable_it_user_91", "正常用户一", "0", "0" },
                new Object[] { 92L, 192L, "flowable_it_user_92", "正常用户二", "0", "0" },
                new Object[] { 93L, 191L, "flowable_it_user_93", "停用用户", "1", "0" },
                new Object[] { 94L, 191L, "flowable_it_user_94", "删除用户", "0", "2" },
                new Object[] { 95L, 193L, "flowable_it_user_95", "停用部门用户", "0", "0" },
                new Object[] { 96L, 194L, "flowable_it_user_96", "删除部门用户", "0", "0" }
            )
        );
        jdbcTemplate.batchUpdate(
            "insert into sys_user_role (user_id, role_id) values (?, ?)",
            List.of(
                new Object[] { 91L, 91L },
                new Object[] { 91L, 92L },
                new Object[] { 91L, 93L },
                new Object[] { 91L, 94L },
                new Object[] { 92L, 92L },
                // 非 1 用户即使存在异常管理员角色关联，也必须与真实权限服务一样忽略 role_id=1。
                new Object[] { 92L, 1L },
                new Object[] { 93L, 91L },
                new Object[] { 94L, 91L },
                new Object[] { 95L, 93L },
                new Object[] { 96L, 94L }
            )
        );
        jdbcTemplate.batchUpdate(
            "insert into sys_role_menu (role_id, menu_id) "
                + "select ?, menu_id from sys_menu "
                + "where status = '0' and perms = ?",
            List.of(
                new Object[] { 91L, "workflow:process:todoList" },
                new Object[] { 91L, "workflow:process:query" },
                new Object[] { 91L, "workflow:process:approval" },
                new Object[] { 92L, "workflow:process:claimList" },
                new Object[] { 92L, "workflow:process:claim" },
                new Object[] { 93L, "workflow:process:approval" },
                new Object[] { 94L, "workflow:process:approval" }
            )
        );
    }

    /**
     * 在事务外核对身份 Mapper 的隔离主键范围没有遗留用户、角色、部门或关联数据。
     *
     * @param jdbcTemplate JdbcTemplate，查询专用集成测试 schema 的真实 MySQL 客户端
     * @return 无返回值；发现测试主键范围内存在任何记录时立即失败
     */
    private void assertNoWorkflowIdentityTestData(JdbcTemplate jdbcTemplate)
    {
        Long userCount = jdbcTemplate.queryForObject(
            "select count(*) from sys_user where user_id between ? and ?",
            Long.class,
            IDENTITY_TEST_USER_IDS.get(0),
            IDENTITY_TEST_USER_IDS.get(5)
        );
        Long roleCount = jdbcTemplate.queryForObject(
            "select count(*) from sys_role where role_id between ? and ?",
            Long.class,
            IDENTITY_TEST_ROLE_IDS.get(0),
            IDENTITY_TEST_ROLE_IDS.get(3)
        );
        Long deptCount = jdbcTemplate.queryForObject(
            "select count(*) from sys_dept where dept_id between ? and ?",
            Long.class,
            IDENTITY_TEST_DEPT_IDS.get(0),
            IDENTITY_TEST_DEPT_IDS.get(3)
        );
        Long userRoleCount = jdbcTemplate.queryForObject(
            "select count(*) from sys_user_role "
                + "where user_id between ? and ? or role_id between ? and ?",
            Long.class,
            IDENTITY_TEST_USER_IDS.get(0),
            IDENTITY_TEST_USER_IDS.get(5),
            IDENTITY_TEST_ROLE_IDS.get(0),
            IDENTITY_TEST_ROLE_IDS.get(3)
        );
        Long roleMenuCount = jdbcTemplate.queryForObject(
            "select count(*) from sys_role_menu where role_id between ? and ?",
            Long.class,
            IDENTITY_TEST_ROLE_IDS.get(0),
            IDENTITY_TEST_ROLE_IDS.get(3)
        );

        assertThat(userCount).as("身份 Mapper 测试用户不得残留").isZero();
        assertThat(roleCount).as("身份 Mapper 测试角色不得残留").isZero();
        assertThat(deptCount).as("身份 Mapper 测试部门不得残留").isZero();
        assertThat(userRoleCount).as("身份 Mapper 测试用户角色关系不得残留").isZero();
        assertThat(roleMenuCount).as("身份 Mapper 测试角色菜单关系不得残留").isZero();
    }

    /**
     * 读取三个身份主数据表当前的 MySQL 自增值，用于核对回滚测试没有推进正式表序列。
     *
     * @param jdbcTemplate JdbcTemplate，查询专用集成测试 schema 的真实 MySQL 客户端
     * @return Map&lt;String, Long&gt;，表名到当前 AUTO_INCREMENT 值的不可变映射
     */
    private Map<String, Long> readWorkflowIdentityAutoIncrementState(JdbcTemplate jdbcTemplate)
    {
        String sql = "select auto_increment from information_schema.tables "
            + "where table_schema = ? and table_name = ?";
        Long userAutoIncrement = jdbcTemplate.queryForObject(sql, Long.class, expectedSchema, "sys_user");
        Long roleAutoIncrement = jdbcTemplate.queryForObject(sql, Long.class, expectedSchema, "sys_role");
        Long deptAutoIncrement = jdbcTemplate.queryForObject(sql, Long.class, expectedSchema, "sys_dept");
        assertThat(userAutoIncrement).as("sys_user 必须保留 AUTO_INCREMENT").isNotNull();
        assertThat(roleAutoIncrement).as("sys_role 必须保留 AUTO_INCREMENT").isNotNull();
        assertThat(deptAutoIncrement).as("sys_dept 必须保留 AUTO_INCREMENT").isNotNull();
        return Map.of(
            "sys_user", userAutoIncrement,
            "sys_role", roleAutoIncrement,
            "sys_dept", deptAutoIncrement
        );
    }

    /**
     * 创建具备嵌套对象和数组的 Jackson 3 JSON 变量，覆盖常见审批数据结构。
     *
     * @return ObjectNode，待由 Flowable JSON variable type 持久化的审批数据
     */
    private ObjectNode createApprovalPayload()
    {
        ObjectMapper objectMapper = JsonMapper.shared();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("applicantId", 1L);
        payload.put("subject", "采购审批");
        payload.putObject("amount").put("currency", "CNY").put("value", 1280.50D);
        payload.putArray("approvers").add("1").add("2");
        return payload;
    }

    /**
     * 校验跨上下文读取的 JSON 变量仍为 Jackson 3 JsonNode 且业务字段完整。
     *
     * @param persistedValue Object，从 Flowable 运行或历史变量 API 读取的实际值
     * @return 无返回值；变量类型或字段值发生漂移时测试失败
     */
    private void assertApprovalPayload(Object persistedValue)
    {
        assertThat(persistedValue).isInstanceOf(JsonNode.class);
        JsonNode payload = (JsonNode) persistedValue;
        assertThat(payload.path("applicantId").longValue()).isEqualTo(1L);
        assertThat(payload.path("subject").textValue()).isEqualTo("采购审批");
        assertThat(payload.path("amount").path("currency").textValue()).isEqualTo("CNY");
        assertThat(payload.path("amount").path("value").doubleValue()).isEqualTo(1280.50D);
        assertThat(payload.path("approvers").isArray()).isTrue();
        assertThat(payload.path("approvers").size()).isEqualTo(2);
    }

    /**
     * 等待异步执行条件成立，超时或线程中断时提供明确失败原因。
     *
     * @param description String，超时时展示的业务断言说明
     * @param timeout Duration，允许异步执行器完成工作的最大时长
     * @param condition BooleanSupplier，每次轮询判断是否完成的条件
     * @return 无返回值；条件在超时前未成立时测试失败
     */
    private void awaitCondition(String description, Duration timeout, BooleanSupplier condition)
    {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline))
        {
            if (condition.getAsBoolean())
            {
                return;
            }
            try
            {
                Thread.sleep(100L);
            }
            catch (InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待 Flowable 异步执行时线程被中断", exception);
            }
        }
        assertThat(condition.getAsBoolean()).as(description).isTrue();
    }

    /**
     * 创建与生产工作流写边界一致的可重复读 Spring 事务模板。
     *
     * @return TransactionTemplate，绑定应用统一事务管理器且隔离级别固定为 REPEATABLE_READ
     */
    private TransactionTemplate repeatableReadTransactionTemplate()
    {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        // 外层事务必须显式采用生产门禁要求的隔离级别，避免测试绕过真实写入契约。
        transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transactionTemplate;
    }

    /**
     * 验证流程发起和历史写入在 Spring 事务异常时同步回滚。
     *
     * @param runtimeService RuntimeService，Flowable 运行时服务
     * @param historyService HistoryService，Flowable 历史查询服务
     * @return 无返回值；发现残留运行或历史数据时测试失败
     */
    private void assertEngineTransactionRollsBack(RuntimeService runtimeService, HistoryService historyService)
    {
        String rollbackBusinessKey = BUSINESS_KEY_PREFIX + "rollback-" + UUID.randomUUID();
        TransactionTemplate transactionTemplate = repeatableReadTransactionTemplate();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
            authenticationContext.runAs("1", () ->
            {
                runtimeService.startProcessInstanceByKey("flowableEngineIntegration", rollbackBusinessKey);
                throw new IllegalStateException("force workflow transaction rollback");
            })
        )).isInstanceOf(IllegalStateException.class);

        assertThat(runtimeService.createProcessInstanceQuery()
            .processInstanceBusinessKey(rollbackBusinessKey).count()).isZero();
        assertThat(historyService.createHistoricProcessInstanceQuery()
            .processInstanceBusinessKey(rollbackBusinessKey).count()).isZero();
    }

    /**
     * 在当前新上下文中按部署 ID 进行受控级联清理，兼容前一上下文已经关闭的场景。
     *
     * @param repositoryService RepositoryService，当前上下文的流程仓储服务
     * @param deploymentId String，需要清理的测试部署 ID
     * @return 无返回值；部署不存在时不执行删除
     */
    private void deleteDeploymentIfPresent(RepositoryService repositoryService, String deploymentId)
    {
        if (repositoryService.createDeploymentQuery().deploymentId(deploymentId).count() > 0L)
        {
            repositoryService.deleteDeployment(deploymentId, true);
        }
    }

    /**
     * 保存单个并发认领线程的稳定业务结果。
     *
     * @param successful boolean，当前线程是否成功认领任务
     * @param error ServiceException，失败线程收到的稳定业务异常；成功时为空
     */
    private record ClaimAttemptResult(boolean successful, ServiceException error)
    {
    }

    /** 撤回与认领竞态中由测试行锁安排的首个写动作。 */
    private enum RaceWinner
    {
        /** 认领先取得任务行锁并提交。 */
        CLAIM,

        /** 撤回先取得任务行锁并提交。 */
        REVOKE
    }

    /**
     * 单个竞态动作的互斥结果。
     *
     * @param successful boolean，动作是否完整提交
     * @param businessFailure ServiceException，失败时的稳定业务异常
     * @param unexpectedFailure Throwable，非预期基础设施或程序异常
     */
    private record RaceAttempt(boolean successful, ServiceException businessFailure,
            Throwable unexpectedFailure)
    {
    }

    /**
     * 同一实例上两个互斥终态命令的并发执行结果。
     *
     * @param first RaceAttempt，第一个终态命令的成功或失败结果
     * @param second RaceAttempt，第二个终态命令的成功或失败结果
     */
    private record TerminalCommandRaceResult(RaceAttempt first, RaceAttempt second)
    {
    }

    /**
     * 同一任务上撤回与认领的完整竞态结果。
     *
     * @param revoke RaceAttempt，撤回动作结果
     * @param claim RaceAttempt，认领动作结果
     */
    private record RevokeClaimRaceResult(RaceAttempt revoke, RaceAttempt claim)
    {
    }

    /**
     * 生命周期测试中一张可编辑表单及其部署节点快照的固定业务定义。
     *
     * @param formKey String，BPMN 表单键
     * @param nodeKey String，BPMN 节点 key
     * @param formName String，表单名称快照
     * @param nodeName String，节点名称快照
     * @param content String，不可变表单 schema JSON
     */
    private record LifecycleFormSpec(
        String formKey,
        String nodeKey,
        String formName,
        String nodeName,
        String content
    )
    {
    }

    /**
     * 仅用于真实 MySQL 安全测试的 Java 反序列化探针。
     */
    private static final class DeserializationCanary implements Serializable
    {
        private static final long serialVersionUID = 1L;

        /**
         * 当任意 ObjectInputStream 真正初始化该对象时记录副作用，用于证明 Flowable 未提前反序列化正文。
         *
         * @param input ObjectInputStream，正在读取测试攻击正文的对象输入流
         * @return 无返回值，调用即表示禁止初始化契约被破坏
         * @throws IOException 默认字段读取失败时抛出
         * @throws ClassNotFoundException 对象字段类型无法加载时抛出
         */
        private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException
        {
            input.defaultReadObject();
            DESERIALIZATION_CANARY_TRIGGERED.set(true);
        }
    }

    /**
     * 保存跨 Spring 上下文测试所需的最小定位信息。
     *
     * @param deploymentId String，重启前创建的 Flowable 部署 ID
     * @param processInstanceId String，保留待读取变量的流程实例 ID
     * @param businessKey String，流程实例业务主键
     * @param previousContext ConfigurableApplicationContext，必须在下一测试前关闭的旧上下文
     */
    private record RestartState(
        String deploymentId,
        String processInstanceId,
        String businessKey,
        ConfigurableApplicationContext previousContext
    )
    {
    }
}

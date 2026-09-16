package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.flowable.engine.IdentityService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftCreateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftSaveRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftSubmitRequest;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormView;
import com.ruoyi.flowable.engine.WorkflowProcessInstanceSnapshot;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfProcessDraftMapper;
import com.ruoyi.flowable.mapper.WorkflowProcessDefinitionLockMapper;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;
import com.ruoyi.flowable.testsupport.WorkflowMySqlITSupport;

/**
 * 使用显式隔离 MySQL 8 验证草稿创建等待部署行锁失败时的真实并发机器协议。
 *
 * 本类不创建或迁移表结构，只写入固定测试定义和部署主键并在每个场景前后精确清理。
 */
class WorkflowProcessDraftStartMySqlIT
{
    /** 当前 MySQL IT 独占的定义主键。 */
    private static final String DEFINITION_ID =
            "wf-draft-conflict-it-definition";

    /** 当前 MySQL IT 独占的部署主键。 */
    private static final String DEPLOYMENT_ID =
            "wf-draft-conflict-it-deployment";

    /** 当前 MySQL IT 使用的流程 key。 */
    private static final String PROCESS_KEY = "wfDraftConflictIt";

    /** 当前测试所有者，与真实草稿表数字用户约束一致。 */
    private static final long OWNER_ID = 100L;

    private static MysqlDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static WfProcessDraftMapper draftMapper;
    private static WorkflowProcessDefinitionLockMapper definitionLockMapper;
    private WorkflowAttachmentService attachmentService;
    private WorkflowProcessDraftService draftService;
    private WorkflowProcessStartService processStartService;

    /**
     * 连接显式隔离 MySQL，核验正式表并装配参与 Spring 事务的生产 Mapper。
     *
     * @return void，环境、表或 Mapper XML 不符合基线时立即失败
     * @throws Exception 数据库连接或 Mapper 装配失败时报告
     */
    @BeforeAll
    static void setUpMySql() throws Exception
    {
        dataSource = WorkflowMySqlITSupport.createDataSource();
        // 缩短真实行锁等待负例；仍由 InnoDB 产生 1205，而不是测试主动抛出异常。
        dataSource.setSessionVariables("innodb_lock_wait_timeout=2");
        WorkflowMySqlITSupport.verifyIsolatedBaseline(dataSource,
                "草稿创建并发协议 IT", null,
                List.of("act_re_deployment", "wf_process_draft"));
        jdbcTemplate = new JdbcTemplate(dataSource);
        draftMapper = WorkflowH2SchemaMapperSupport.createSpringMapper(dataSource,
                "draft-conflict-mysql", WfProcessDraftMapper.class,
                "mapper/flowable/WfProcessDraftMapper.xml");
        definitionLockMapper = WorkflowH2SchemaMapperSupport.createSpringMapper(dataSource,
                "draft-definition-lock-mysql", WorkflowProcessDefinitionLockMapper.class,
                "mapper/flowable/WorkflowProcessDefinitionLockMapper.xml");
    }

    /**
     * 清理固定测试记录、写入真实部署行并装配草稿创建服务。
     *
     * @return void，每个场景使用同一正式事务、Mapper 与异常翻译边界
     * @throws SQLException 测试部署行初始化失败时报告
     */
    @BeforeEach
    void setUp() throws SQLException
    {
        clearTestRows();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        insert into ACT_RE_DEPLOYMENT
                        (ID_,NAME_,CATEGORY_,KEY_,TENANT_ID_,DEPLOY_TIME_,ENGINE_VERSION_)
                        values (?,?,null,?,'',current_timestamp(3),null)
                        """))
        {
            statement.setString(1, DEPLOYMENT_ID);
            statement.setString(2, "Draft conflict IT");
            statement.setString(3, PROCESS_KEY);
            assertThat(statement.executeUpdate()).isOne();
        }

        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveCurrentIdentity()).thenReturn(
                new WorkflowCurrentIdentity(String.valueOf(OWNER_ID), Set.of()));
        IdentityService identityService = mock(IdentityService.class);
        WorkflowAuthenticationContext authenticationContext =
                new WorkflowAuthenticationContext(identityService,
                        new WorkflowIdentityCodec());
        WorkflowEngineOperations operationsTarget = new WorkflowEngineOperations(
                authenticationContext, new WorkflowExceptionTranslator(), identityResolver);
        WorkflowEngineOperations engineOperations =
                WorkflowMySqlITSupport.transactionalProxy(operationsTarget,
                        new DataSourceTransactionManager(dataSource));

        RepositoryService repositoryService = mock(RepositoryService.class);
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getId()).thenReturn(DEFINITION_ID);
        when(definition.getKey()).thenReturn(PROCESS_KEY);
        when(definition.getVersion()).thenReturn(1);
        when(definition.getDeploymentId()).thenReturn(DEPLOYMENT_ID);
        when(definition.getName()).thenReturn("Draft conflict IT");
        when(repositoryService.getProcessDefinition(DEFINITION_ID)).thenReturn(definition);

        WorkflowProcessDefinitionQueryService definitionQueryService =
                mock(WorkflowProcessDefinitionQueryService.class);
        processStartService = mock(WorkflowProcessStartService.class);
        WorkflowProcessFormView form = new WorkflowProcessFormView(DEFINITION_ID, DEPLOYMENT_ID,
                null, "TEMPLATE", 1L, "startForm", "start", "Draft form", "Start",
                "{\"fields\":[]}", Instant.now(), List.of());
        var loaded = new WorkflowProcessDefinitionQueryService.StartFormLoad(null, form);
        when(definitionQueryService.loadStartFormForPreviewInCurrentTransaction(any(), any())).thenReturn(loaded);
        when(definitionQueryService.loadDraftFormInCurrentTransaction(any(), any())).thenReturn(loaded);
        WorkflowStartVariableValidator variableValidator =
                new WorkflowStartVariableValidator(new WorkflowFormTemplateValidator());
        attachmentService = mock(WorkflowAttachmentService.class);
        draftService = new WorkflowProcessDraftService(engineOperations, identityResolver,
                repositoryService, definitionQueryService, processStartService,
                variableValidator, attachmentService, draftMapper, definitionLockMapper);
    }

    /**
     * 每个场景后精确清理固定定义和部署测试记录。
     *
     * @return void，不影响隔离库中的其他验收数据
     * @throws SQLException 清理失败时报告
     */
    @AfterEach
    void tearDown() throws SQLException
    {
        clearTestRows();
    }

    /** 真正的 InnoDB 草稿/部署锁串行化两个提交，启动边界为可计数夹具；引擎与附件由整链测试覆盖。 */
    @Test
    void concurrentSubmitWaitsForCommitAndReturnsOneStoredInstance() throws Exception
    {
        var draft = draftService.create(new WorkflowProcessDraftCreateRequest(DEFINITION_ID, null, Map.of(), Map.of()));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(processStartService.startDraft(any(), any(), any(), any(), any())).thenAnswer(invocation ->
        {
            started.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return new WorkflowProcessStartService.DraftStartResult(
                    new WorkflowProcessInstanceSnapshot("draft-concurrent-instance", DEFINITION_ID, null, false), Map.of());
        });
        var executor = Executors.newFixedThreadPool(2);
        try
        {
            var request = new WorkflowProcessDraftSubmitRequest(draft.revisionNo(), null, Map.of(), Map.of());
            var first = executor.submit(() -> draftService.submit(draft.draftId(), request));
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            CountDownLatch competing = new CountDownLatch(1);
            var second = executor.submit(() -> { competing.countDown(); return draftService.submit(draft.draftId(), request); });
            assertThat(competing.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
            verify(processStartService, times(1)).startDraft(any(), any(), any(), any(), any());
            var stored = draftMapper.selectOwnedById(draft.draftId(), OWNER_ID);
            assertThat(stored.revisionNo()).isEqualTo(2);
            assertThat(stored.submittedProcessInstanceId()).isEqualTo("draft-concurrent-instance");
        }
        finally
        {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void competingSavesKeepOneRevisionAndRejectTheStaleRequest() throws Exception
    {
        var draft = draftService.create(new WorkflowProcessDraftCreateRequest(DEFINITION_ID, null, Map.of(), Map.of()));
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try
        {
            java.util.concurrent.Callable<String> save = () ->
            {
                assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
                try
                {
                    return "saved-" + draftService.save(draft.draftId(), new WorkflowProcessDraftSaveRequest(
                            draft.revisionNo(), "race", Map.of(), Map.of())).revisionNo();
                }
                catch (ServiceException error)
                {
                    assertThat(error.getCode()).isEqualTo(HttpStatus.CONFLICT);
                    return error.getSubCode();
                }
            };
            var first = executor.submit(save);
            var second = executor.submit(save);
            go.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("saved-2", "DRAFT_VERSION_CONFLICT");
            assertThat(draftMapper.selectOwnedById(draft.draftId(), OWNER_ID).revisionNo()).isEqualTo(2);
        }
        finally
        {
            go.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * 由第二个真实 MySQL 事务持有部署行锁，验证创建等待超时只返回新建并发子码。
     *
     * @return void，若错误使用 DRAFT_VERSION_CONFLICT、写入草稿或进入附件阶段则失败
     * @throws SQLException 锁定连接或数据库断言失败时报告
     */
    @Test
    void returnsCreateConcurrencySubCodeForRealMySqlDeploymentLockTimeout()
            throws SQLException
    {
        try (Connection locker = dataSource.getConnection())
        {
            locker.setAutoCommit(false);
            try
            {
                try (PreparedStatement statement = locker.prepareStatement(
                        "select ID_ from ACT_RE_DEPLOYMENT where ID_=? for update"))
                {
                    statement.setString(1, DEPLOYMENT_ID);
                    try (ResultSet locked = statement.executeQuery())
                    {
                        assertThat(locked.next()).isTrue();
                        assertThat(locked.getString(1)).isEqualTo(DEPLOYMENT_ID);
                    }
                }

                assertThatThrownBy(() -> draftService.create(
                        new WorkflowProcessDraftCreateRequest(DEFINITION_ID, null,
                                Map.of(), Map.of())))
                        .isInstanceOfSatisfying(ServiceException.class, exception ->
                        {
                            assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(exception.getSubCode())
                                    .isEqualTo("DRAFT_CREATE_CONCURRENCY_CONFLICT");
                        });
            }
            finally
            {
                locker.rollback();
            }
        }

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from wf_process_draft
                where process_definition_id=? and owner_user_id=?
                """, Long.class, DEFINITION_ID, OWNER_ID)).isZero();
        verify(attachmentService, never()).reconcileDraftAttachments(
                any(), any(), any());
    }

    /**
     * 删除仅属于本 MySQL IT 固定定义和部署主键的数据。
     *
     * @return void，清理成功时不遗留草稿或部署测试记录
     * @throws SQLException 清理 SQL 失败时报告
     */
    private void clearTestRows() throws SQLException
    {
        try (Connection connection = dataSource.getConnection())
        {
            try (PreparedStatement drafts = connection.prepareStatement(
                    "delete from wf_process_draft where process_definition_id=?"))
            {
                drafts.setString(1, DEFINITION_ID);
                drafts.executeUpdate();
            }
            try (PreparedStatement deployment = connection.prepareStatement(
                    "delete from ACT_RE_DEPLOYMENT where ID_=?"))
            {
                deployment.setString(1, DEPLOYMENT_ID);
                deployment.executeUpdate();
            }
        }
    }
}

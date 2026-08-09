package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.task.Comment;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.StartProcessRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftCreateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftSubmitRequest;
import com.ruoyi.flowable.domain.WorkflowProcessDefinitionLockRow;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDraftView;
import com.ruoyi.flowable.engine.WorkflowProcessInstanceSnapshot;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.mapper.WorkflowProcessDefinitionLockMapper;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.model.WorkflowDeploymentService;
import com.ruoyi.flowable.service.process.WorkflowProcessDraftService;
import com.ruoyi.flowable.service.process.WorkflowProcessStartService;
import com.ruoyi.flowable.service.process.WorkflowStartVariableValidator;
import com.ruoyi.flowable.service.process.WorkflowValidatedStartVariables;
import com.ruoyi.flowable.service.task.WorkflowUserTaskAuditService;

@SpringBootTest(
    classes = { RuoYiApplication.class,
        WorkflowCompatibilityIT.CompatibilityTestConfiguration.class },
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        "spring.datasource.druid.master.url=${FLOWABLE_IT_JDBC_URL}",
        "spring.datasource.druid.master.username=${FLOWABLE_IT_USERNAME}",
        "spring.datasource.druid.master.password=${FLOWABLE_IT_PASSWORD}",
        "spring.data.redis.database=${FLOWABLE_IT_REDIS_DATABASE:15}",
        "flowable.it.expected-schema=${FLOWABLE_IT_EXPECTED_SCHEMA}",
        "flowable.it.toggle-user-id=${FLOWABLE_IT_TOGGLE_USER_ID:900000002}",
        // 固定值只用于装配未参与本 IT 的 TokenService，不是账号、随机密码或生产密钥。
        "token.secret=eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eA==",
        "flowable.database-schema-update=false",
        "flowable.async-executor-activate=false",
        "flowable.async-history-executor-activate=false",
        "flowable.notification.worker-enabled=false",
        "spring.quartz.auto-startup=false"
    }
)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkflowCompatibilityIT
{
    /** 测试部署统一前缀，用于执行前后检查隔离数据。 */
    private static final String DEPLOYMENT_NAME_PREFIX = "approvaplat-workflow-compatibility-it";

    /** 测试业务主键统一前缀，用于核对运行和历史实例零残留。 */
    private static final String BUSINESS_KEY_PREFIX = "workflow-compatibility-it-";

    /** 兼容 BPMN 的流程定义 key。 */
    private static final String PROCESS_KEY = "flowableCompatibilityIntegration";

    /** 兼容 BPMN 的开始节点部署表单 key。 */
    private static final String START_FORM_KEY = "key_compatibility_start";

    /** 真实主数据中用于发起和普通办理的管理员用户。 */
    private static final long ADMIN_USER_ID = 1L;

    /** key/ID 发起和监听器变量共同使用的不可变开始表单 schema。 */
    private static final String START_FORM_CONTENT = """
        {"fields":[
          {"__config__":{"layout":"colFormItem","tag":"el-input","required":true},
           "__vModel__":"assigneeId","maxlength":32},
          {"__config__":{"layout":"colFormItem","tag":"el-input","required":true},
           "__vModel__":"ownerId","maxlength":32}
        ]}
            """;

    /** 快速结束并发场景使用的无字段开始表单 schema。 */
    private static final String EMPTY_START_FORM_CONTENT = "{\"fields\":[]}";

    /** 快速结束流程使用的开始表单 key。 */
    private static final String FAST_START_FORM_KEY = "key_fast_complete_start";

    /** 显式业务终态必须高于自然完成和用户任务监听事件。 */
    private static final List<String> TERMINAL_STATUSES =
            List.of("rejected", "canceled", "terminated");

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorkflowProcessStartService processStartService;

    @Autowired
    private WorkflowProcessDraftService processDraftService;

    @Autowired
    private WorkflowDeploymentService deploymentService;

    @Autowired
    private WorkflowAuthenticationContext authenticationContext;

    @Autowired
    private CoordinatedStartVariableValidator coordinatedValidator;

    @Autowired
    private CoordinatedProcessDefinitionLockMapper coordinatedDefinitionLockMapper;

    @Value("${flowable.it.expected-schema}")
    private String expectedSchema;

    /** 隔离 schema 中通过真实账号入口创建、用于停用身份回滚场景的用户主键。 */
    @Value("${flowable.it.toggle-user-id}")
    private long toggleUserId;

    /** 当前测试创建的 Flowable 部署主键，结束时连同实例和历史级联清理。 */
    private final List<String> deploymentIds = new ArrayList<>();

    /** 当前测试创建的正式 wf_form 主键，结束时独立清理。 */
    private final List<Long> formIds = new ArrayList<>();

    /** 动态停用用户测试前的正式状态，结束时无条件恢复。 */
    private String originalToggleUserStatus;

    /** 动态停用用户本轮复用的正式审批角色主键。 */
    private Long toggleUserApprovalRoleId;

    /** 标记审批角色关系是否由当前测试实际插入，禁止误删环境既有授权。 */
    private boolean toggleUserApprovalRoleInserted;

    /**
     * 验证真实 MySQL schema、两个既有启用用户和测试数据隔离基线，
     * 并仅在缺失时给停用切换 fixture 关联现有有效审批角色。
     *
     * @return void，无返回值；环境不是批准 schema 或存在残留时测试失败
     */
    @BeforeEach
    void prepareEnvironment()
    {
        toggleUserApprovalRoleId = null;
        toggleUserApprovalRoleInserted = false;
        assertThat(jdbcTemplate.queryForObject("select database()", String.class))
                .isEqualTo(expectedSchema);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_user where user_id in (?, ?) "
                        + "and status = '0' and del_flag = '0'",
                Long.class, ADMIN_USER_ID, toggleUserId)).isEqualTo(2L);
        originalToggleUserStatus = jdbcTemplate.queryForObject(
                "select status from sys_user where user_id = ? and del_flag = '0'",
                String.class, toggleUserId);

        // 监听器实时核验 workflow:process:approval；测试只补当前环境缺失的正式角色关系。
        toggleUserApprovalRoleId = jdbcTemplate.queryForObject(
                "select min(r.role_id) from sys_user_role ur "
                        + "inner join sys_role r on r.role_id = ur.role_id "
                        + "inner join sys_role_menu rm on rm.role_id = r.role_id "
                        + "inner join sys_menu m on m.menu_id = rm.menu_id "
                        + "where ur.user_id = ? and r.status = '0' and r.del_flag = '0' "
                        + "and m.status = '0' and m.perms = 'workflow:process:approval'",
                Long.class, toggleUserId);
        if (toggleUserApprovalRoleId == null)
        {
            Long availableApprovalRoleId = jdbcTemplate.queryForObject(
                    "select min(r.role_id) from sys_role r "
                            + "inner join sys_role_menu rm on rm.role_id = r.role_id "
                            + "inner join sys_menu m on m.menu_id = rm.menu_id "
                            + "where r.status = '0' and r.del_flag = '0' and m.status = '0' "
                            + "and m.perms = 'workflow:process:approval'",
                    Long.class);
            assertThat(availableApprovalRoleId)
                    .as("兼容性停用用户场景必须复用真实有效审批角色")
                    .isNotNull();
            toggleUserApprovalRoleId = availableApprovalRoleId;
            int insertedRows = jdbcTemplate.update(
                    "insert into sys_user_role (user_id, role_id) values (?, ?)",
                    toggleUserId, toggleUserApprovalRoleId);
            toggleUserApprovalRoleInserted = insertedRows == 1;
            assertThat(insertedRows)
                    .as("兼容性停用用户场景必须建立唯一正式审批角色关系")
                    .isEqualTo(1);
        }
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_user u where u.user_id = ? "
                        + "and u.status = '0' and u.del_flag = '0' and exists ("
                        + "select 1 from sys_user_role ur "
                        + "inner join sys_role r on r.role_id = ur.role_id "
                        + "inner join sys_role_menu rm on rm.role_id = r.role_id "
                        + "inner join sys_menu m on m.menu_id = rm.menu_id "
                        + "where ur.user_id = u.user_id and r.status = '0' "
                        + "and r.del_flag = '0' and m.status = '0' "
                        + "and m.perms = 'workflow:process:approval')",
                Long.class, toggleUserId))
                .as("动态停用用户启用时必须具备真实流程审批资格")
                .isOne();
        assertThat(repositoryService().createDeploymentQuery()
                .deploymentNameLike(DEPLOYMENT_NAME_PREFIX + "%").count()).isZero();
        assertThat(runtimeService().createProcessInstanceQuery()
                .processInstanceBusinessKeyLike(BUSINESS_KEY_PREFIX + "%").count()).isZero();
        assertThat(historyService().createHistoricProcessInstanceQuery()
                .processInstanceBusinessKeyLike(BUSINESS_KEY_PREFIX + "%").count()).isZero();
    }

    /**
     * 恢复正式用户状态，精确删除本轮新增角色关系，并清理部署、实例、历史和表单。
     *
     * @return void，无返回值；任何正式测试数据残留都会使测试失败
     */
    @AfterEach
    void cleanEnvironment()
    {
        coordinatedValidator.release();
        coordinatedDefinitionLockMapper.release();
        coordinatedDefinitionLockMapper.releaseDeploymentLock();
        SecurityContextHolder.clearContext();
        if (originalToggleUserStatus != null)
        {
            jdbcTemplate.update("update sys_user set status = ? where user_id = ?",
                    originalToggleUserStatus, toggleUserId);
        }
        if (toggleUserApprovalRoleInserted && toggleUserApprovalRoleId != null)
        {
            assertThat(jdbcTemplate.update(
                    "delete from sys_user_role where user_id = ? and role_id = ?",
                    toggleUserId, toggleUserApprovalRoleId))
                    .as("只允许删除当前兼容性测试实际插入的审批角色关系")
                    .isEqualTo(1);
        }

        for (String deploymentId : deploymentIds)
        {
            // 草稿审计和附件均以 RESTRICT 外键引用草稿，必须按依赖顺序精确清理当前测试部署。
            jdbcTemplate.update("delete attachment from wf_attachment attachment "
                    + "inner join wf_process_draft draft on draft.draft_id = attachment.draft_id "
                    + "where draft.deployment_id = ?", deploymentId);
            jdbcTemplate.update("delete audit from wf_process_draft_audit audit "
                    + "inner join wf_process_draft draft on draft.draft_id = audit.draft_id "
                    + "where draft.deployment_id = ?", deploymentId);
            jdbcTemplate.update("delete from wf_process_draft where deployment_id = ?",
                    deploymentId);
            jdbcTemplate.update("delete from wf_deploy_form where deploy_id = ?", deploymentId);
            if (repositoryService().createDeploymentQuery().deploymentId(deploymentId).count() > 0L)
            {
                repositoryService().deleteDeployment(deploymentId, true);
            }
        }
        for (Long formId : formIds)
        {
            jdbcTemplate.update("delete from wf_form where form_id = ?", formId);
        }

        assertThat(repositoryService().createDeploymentQuery()
                .deploymentNameLike(DEPLOYMENT_NAME_PREFIX + "%").count()).isZero();
        assertThat(runtimeService().createProcessInstanceQuery()
                .processInstanceBusinessKeyLike(BUSINESS_KEY_PREFIX + "%").count()).isZero();
        assertThat(historyService().createHistoricProcessInstanceQuery()
                .processInstanceBusinessKeyLike(BUSINESS_KEY_PREFIX + "%").count()).isZero();
        deploymentIds.clear();
        formIds.clear();
        toggleUserApprovalRoleId = null;
        toggleUserApprovalRoleInserted = false;
    }

    /**
     * 通过真实 MySQL 和 Flowable 验证 key/ID 发起一致、三事件 comment 持久化及业务终态优先级。
     *
     * @return void，变量、身份、历史、comment 或终态存在任一漂移时测试失败
     * @throws Exception comment JSON 无法解析时测试失败
     */
    @Test
    void persistsApprovedListenerEventsAndKeepsKeyIdAndTerminalStateConsistent()
            throws Exception
    {
        ProcessDefinition definition = deployCompatibilityProcess("events-and-state");
        setSecurityContextUser(ADMIN_USER_ID);
        Map<String, Object> variables = listenerVariables(ADMIN_USER_ID, ADMIN_USER_ID);

        String idBusinessKey = businessKey("id");
        WorkflowProcessInstanceSnapshot idSnapshot = processStartService.start(
                new StartProcessRequest(definition.getId(), idBusinessKey, variables));
        String keyBusinessKey = businessKey("key");
        processStartService.startProcessByDefKey(PROCESS_KEY, keyBusinessKey, variables);
        ProcessInstance keyInstance = requireRuntimeInstance(keyBusinessKey);

        assertThat(keyInstance.getProcessDefinitionId()).isEqualTo(definition.getId());
        assertThat(runtimeService().getVariables(keyInstance.getId()))
                .containsExactlyInAnyOrderEntriesOf(runtimeService().getVariables(idSnapshot.id()));
        assertListenerActions(keyInstance.getId(), Set.of(
                "USER_TASK_CREATE", "USER_TASK_ASSIGNMENT"));

        Task keyTask = requireActiveTask(keyInstance.getId());
        authenticationContext.runAs(String.valueOf(ADMIN_USER_ID),
                () -> taskService().complete(keyTask.getId()));

        assertThat(runtimeService().createProcessInstanceQuery()
                .processInstanceId(keyInstance.getId()).count()).isZero();
        assertListenerActions(keyInstance.getId(), Set.of(
                "USER_TASK_CREATE", "USER_TASK_ASSIGNMENT", "USER_TASK_COMPLETE"));
        assertHistoricStatus(keyInstance.getId(), "completed");

        for (String terminalStatus : TERMINAL_STATUSES)
        {
            String terminalBusinessKey = businessKey(terminalStatus);
            processStartService.startProcessByDefKey(PROCESS_KEY,
                    terminalBusinessKey, variables);
            ProcessInstance terminalInstance = requireRuntimeInstance(terminalBusinessKey);
            runtimeService().setVariable(terminalInstance.getId(),
                    WorkflowProcessStartService.PROCESS_STATUS_VARIABLE, terminalStatus);
            Task terminalTask = requireActiveTask(terminalInstance.getId());
            authenticationContext.runAs(String.valueOf(ADMIN_USER_ID),
                    () -> taskService().complete(terminalTask.getId()));

            assertHistoricStatus(terminalInstance.getId(), terminalStatus);
            assertListenerActions(terminalInstance.getId(), Set.of(
                    "USER_TASK_CREATE", "USER_TASK_ASSIGNMENT", "USER_TASK_COMPLETE"));
        }
    }

    /**
     * 验证停用 assignee、owner 或 complete actor 都回滚真实引擎命令且 comment 零新增。
     *
     * @return void，无效用户产生实例、完成任务或审计副作用时测试失败
     */
    @Test
    void rollsBackInactiveAssigneeOwnerAndCompletionActor()
    {
        deployCompatibilityProcess("inactive-identities");
        setSecurityContextUser(ADMIN_USER_ID);
        setToggleUserStatus("1");

        assertRejectedStartForInactiveIdentity("inactive-assignee",
                toggleUserId, ADMIN_USER_ID);
        assertRejectedStartForInactiveIdentity("inactive-owner",
                ADMIN_USER_ID, toggleUserId);

        setToggleUserStatus("0");
        String actorBusinessKey = businessKey("inactive-actor");
        processStartService.startProcessByDefKey(PROCESS_KEY, actorBusinessKey,
                listenerVariables(toggleUserId, toggleUserId));
        ProcessInstance actorInstance = requireRuntimeInstance(actorBusinessKey);
        Task actorTask = requireActiveTask(actorInstance.getId());
        int commentsBefore = listenerComments(actorInstance.getId()).size();
        setToggleUserStatus("1");

        assertThatThrownBy(() -> authenticationContext.runAs(
                String.valueOf(toggleUserId), () -> taskService().complete(actorTask.getId())))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("用户任务办理身份无效");
                });

        assertThat(taskService().createTaskQuery().taskId(actorTask.getId()).count()).isOne();
        assertThat(runtimeService().createProcessInstanceQuery()
                .processInstanceId(actorInstance.getId()).count()).isOne();
        assertThat(listenerComments(actorInstance.getId())).hasSize(commentsBefore);
        assertThat(runtimeService().getVariable(actorInstance.getId(),
                WorkflowProcessStartService.PROCESS_STATUS_VARIABLE)).isEqualTo("running");
    }

    /**
     * 验证前导零办理人不会只在审计中被规范化，而是在任务创建事务内整体拒绝并回滚。
     *
     * @return void，非规范身份留下实例、任务、历史或 comment 时测试失败
     */
    @Test
    void rejectsNonCanonicalListenerIdentityWithoutEngineResidue()
    {
        deployCompatibilityProcess("non-canonical-identity");
        setSecurityContextUser(ADMIN_USER_ID);
        String rejectedBusinessKey = businessKey("non-canonical-assignee");
        long runtimeTaskCountBefore = taskService().createTaskQuery().count();
        long historicInstanceCountBefore = historyService()
                .createHistoricProcessInstanceQuery().count();
        long listenerCommentCountBefore = jdbcTemplate.queryForObject(
                "select count(*) from ACT_HI_COMMENT where TYPE_ = ?",
                Long.class, WorkflowUserTaskAuditService.COMMENT_TYPE);

        assertThatThrownBy(() -> processStartService.startProcessByDefKey(
                PROCESS_KEY, rejectedBusinessKey,
                Map.of("assigneeId", "0001", "ownerId", "1")))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("用户任务办理身份无效");
                });

        assertThat(runtimeService().createProcessInstanceQuery()
                .processInstanceBusinessKey(rejectedBusinessKey).count()).isZero();
        assertThat(historyService().createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(rejectedBusinessKey).count()).isZero();
        assertThat(taskService().createTaskQuery().count()).isEqualTo(runtimeTaskCountBefore);
        assertThat(historyService().createHistoricProcessInstanceQuery().count())
                .isEqualTo(historicInstanceCountBefore);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from ACT_HI_COMMENT where TYPE_ = ?",
                Long.class, WorkflowUserTaskAuditService.COMMENT_TYPE))
                .isEqualTo(listenerCommentCountBefore);
    }

    /**
     * 在变量校验与最终写入之间真实部署新版本，验证 key 入口以 409 回滚旧版本发起。
     *
     * @return void，并发部署期间旧 definitionId 产生实例或错误语义漂移时测试失败
     * @throws Exception 并发线程未按时进入屏障或返回结果时测试失败
     */
    @Test
    void rejectsLatestDefinitionChangeCommittedDuringKeyResolution() throws Exception
    {
        ProcessDefinition initialDefinition = deployCompatibilityProcess("concurrent-v1");
        String concurrentBusinessKey = businessKey("concurrent-deployment");
        coordinatedValidator.arm();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Throwable> startFailure = executor.submit(() ->
        {
            try
            {
                setSecurityContextUser(ADMIN_USER_ID);
                processStartService.startProcessByDefKey(PROCESS_KEY, concurrentBusinessKey,
                        listenerVariables(ADMIN_USER_ID, ADMIN_USER_ID));
                return null;
            }
            catch (Throwable failure)
            {
                return failure;
            }
            finally
            {
                SecurityContextHolder.clearContext();
            }
        });

        try
        {
            assertThat(coordinatedValidator.awaitValidation())
                    .as("key 发起线程必须在真实变量校验后进入并发部署屏障")
                    .isTrue();
            ProcessDefinition newerDefinition = deployCompatibilityProcess("concurrent-v2");
            assertThat(newerDefinition.getVersion()).isGreaterThan(initialDefinition.getVersion());
        }
        finally
        {
            coordinatedValidator.release();
            executor.shutdown();
        }

        Throwable failure = startFailure.get(20, TimeUnit.SECONDS);
        assertThat(failure).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(exception.getMessage()).isEqualTo("流程定义最新版已发生变化");
        });
        assertThat(runtimeService().createProcessInstanceQuery()
                .processInstanceBusinessKey(concurrentBusinessKey).count()).isZero();
        assertThat(historyService().createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(concurrentBusinessKey).count()).isZero();
    }

    /**
     * 在最终定义锁已取得后真实并发部署新版本，验证部署等待发起提交且不会改写已选定义。
     *
     * @return void，部署越过 key 范围锁或实例使用锁后才提交的新版本时测试失败
     * @throws Exception 并发线程、数据库锁等待或结果读取超时时测试失败
     */
    @Test
    void serializesDeploymentCommittedAfterLatestDefinitionLock() throws Exception
    {
        ProcessDefinition initialDefinition = deployCompatibilityProcess("after-lock-v1");
        String lockedBusinessKey = businessKey("after-lock-deployment");
        coordinatedDefinitionLockMapper.arm();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try
        {
            CountDownLatch deploymentAttemptStarted = new CountDownLatch(1);
            Future<Throwable> startFailure = executor.submit(() ->
            {
                try
                {
                    setSecurityContextUser(ADMIN_USER_ID);
                    processStartService.startProcessByDefKey(PROCESS_KEY, lockedBusinessKey,
                            listenerVariables(ADMIN_USER_ID, ADMIN_USER_ID));
                    return null;
                }
                catch (Throwable failure)
                {
                    return failure;
                }
                finally
                {
                    SecurityContextHolder.clearContext();
                }
            });

            Future<Deployment> laterDeployment = null;
            try
            {
                assertThat(coordinatedDefinitionLockMapper.awaitLock())
                        .as("key 发起线程必须先取得真实 ACT_RE_PROCDEF 当前读锁")
                        .isTrue();
                laterDeployment = executor.submit(() ->
                {
                    deploymentAttemptStarted.countDown();
                    return repositoryService().createDeployment()
                            .name(DEPLOYMENT_NAME_PREFIX + "-after-lock-v2-" + UUID.randomUUID())
                            .addClasspathResource(
                                    "processes/flowable-compatibility-it.bpmn20.xml")
                            .deploy();
                });
                assertThat(deploymentAttemptStarted.await(20, TimeUnit.SECONDS))
                        .as("并发部署线程必须开始执行真实 Flowable 部署命令")
                        .isTrue();
                Future<Deployment> blockedDeployment = laterDeployment;
                assertThatThrownBy(() -> blockedDeployment.get(500, TimeUnit.MILLISECONDS))
                        .as("锁后部署必须等待 key 发起事务结束")
                        .isInstanceOf(TimeoutException.class);
            }
            finally
            {
                coordinatedDefinitionLockMapper.release();
            }

            assertThat(startFailure.get(20, TimeUnit.SECONDS)).isNull();
            assertThat(laterDeployment).isNotNull();
            Deployment committedDeployment = laterDeployment.get(20, TimeUnit.SECONDS);
            deploymentIds.add(committedDeployment.getId());
            ProcessDefinition newerDefinition = repositoryService().createProcessDefinitionQuery()
                    .deploymentId(committedDeployment.getId()).singleResult();
            assertThat(newerDefinition).isNotNull();
            assertThat(newerDefinition.getVersion())
                    .isGreaterThan(initialDefinition.getVersion());

            ProcessInstance lockedInstance = requireRuntimeInstance(lockedBusinessKey);
            assertThat(lockedInstance.getProcessDefinitionId())
                    .isEqualTo(initialDefinition.getId());
        }
        finally
        {
            coordinatedDefinitionLockMapper.release();
            executor.shutdown();
            if (!executor.awaitTermination(20, TimeUnit.SECONDS))
            {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(20, TimeUnit.SECONDS))
                        .as("定义锁并发测试线程池必须在清理阶段终止")
                        .isTrue();
            }
        }
    }

    /**
     * 验证直接发起快速结束流程先持部署锁时，删除等待其提交并由历史当前读稳定拒绝。
     *
     * @return void，删除遗漏快速结束历史或发起失去可执行定义时测试失败
     * @throws Exception 并发屏障、锁等待或结果读取超时时测试失败
     */
    @Test
    void serializesDeletionAgainstDirectFastCompleteStart() throws Exception
    {
        ProcessDefinition definition = deployFastCompleteProcess("direct-fast-start");
        String startedBusinessKey = businessKey("direct-fast-start");
        coordinatedDefinitionLockMapper.armDeploymentLock(definition.getDeploymentId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try
        {
            Future<Throwable> startFailure = executor.submit(() -> captureAsAdmin(() ->
                    processStartService.start(new StartProcessRequest(
                            definition.getId(), startedBusinessKey, Map.of()))));
            assertThat(coordinatedDefinitionLockMapper.awaitDeploymentLock())
                    .as("直接发起必须先持有真实 ACT_RE_DEPLOYMENT 行锁")
                    .isTrue();

            CountDownLatch deletionAttempted = new CountDownLatch(1);
            Future<Throwable> deletionFailure = executor.submit(() ->
            {
                deletionAttempted.countDown();
                return captureAsAdmin(() -> deploymentService.deleteDeployments(
                        List.of(definition.getDeploymentId())));
            });
            assertThat(deletionAttempted.await(20, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> deletionFailure.get(500, TimeUnit.MILLISECONDS))
                    .as("删除必须等待快速结束发起事务释放部署锁")
                    .isInstanceOf(TimeoutException.class);

            coordinatedDefinitionLockMapper.releaseDeploymentLock();
            assertThat(startFailure.get(20, TimeUnit.SECONDS)).isNull();
            assertHistoricDeletionConflict(deletionFailure.get(20, TimeUnit.SECONDS));
            assertThat(historyService().createHistoricProcessInstanceQuery()
                    .processInstanceBusinessKey(startedBusinessKey).count()).isOne();
            assertThat(repositoryService().createDeploymentQuery()
                    .deploymentId(definition.getDeploymentId()).count()).isOne();
        }
        finally
        {
            coordinatedDefinitionLockMapper.releaseDeploymentLock();
            shutdownExecutor(executor, "直接发起与删除并发测试");
        }
    }

    /**
     * 验证草稿提交快速结束流程时按部署锁串行删除，并以历史当前读保护已提交审计。
     *
     * @return void，SUBMITTED 草稿或快速结束历史在删除竞争中失去定义时测试失败
     * @throws Exception 并发屏障、锁等待或结果读取超时时测试失败
     */
    @Test
    void serializesDeletionAgainstDraftFastCompleteSubmit() throws Exception
    {
        ProcessDefinition definition = deployFastCompleteProcess("draft-fast-submit");
        String draftBusinessKey = businessKey("draft-fast-submit");
        setSecurityContextUser(ADMIN_USER_ID);
        WorkflowProcessDraftView draft;
        try
        {
            draft = processDraftService.create(new WorkflowProcessDraftCreateRequest(
                    definition.getId(), draftBusinessKey, Map.of(), Map.of()));
        }
        finally
        {
            SecurityContextHolder.clearContext();
        }

        coordinatedDefinitionLockMapper.armDeploymentLock(definition.getDeploymentId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try
        {
            Future<Throwable> submitFailure = executor.submit(() -> captureAsAdmin(() ->
                    processDraftService.submit(draft.draftId(),
                            new WorkflowProcessDraftSubmitRequest(
                                    draft.revisionNo(), draftBusinessKey, Map.of(), Map.of()))));
            assertThat(coordinatedDefinitionLockMapper.awaitDeploymentLock())
                    .as("ACTIVE 草稿提交必须先持有真实 ACT_RE_DEPLOYMENT 行锁")
                    .isTrue();

            CountDownLatch deletionAttempted = new CountDownLatch(1);
            Future<Throwable> deletionFailure = executor.submit(() ->
            {
                deletionAttempted.countDown();
                return captureAsAdmin(() -> deploymentService.deleteDeployments(
                        List.of(definition.getDeploymentId())));
            });
            assertThat(deletionAttempted.await(20, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> deletionFailure.get(500, TimeUnit.MILLISECONDS))
                    .as("删除必须等待草稿提交事务释放部署锁")
                    .isInstanceOf(TimeoutException.class);

            coordinatedDefinitionLockMapper.releaseDeploymentLock();
            assertThat(submitFailure.get(20, TimeUnit.SECONDS)).isNull();
            assertHistoricDeletionConflict(deletionFailure.get(20, TimeUnit.SECONDS));
            Map<String, Object> persistedDraft = jdbcTemplate.queryForMap(
                    "select draft_status, submitted_process_instance_id "
                            + "from wf_process_draft where draft_id = ?", draft.draftId());
            assertThat(persistedDraft.get("draft_status")).isEqualTo("SUBMITTED");
            assertThat(persistedDraft.get("submitted_process_instance_id")).isNotNull();
            assertThat(historyService().createHistoricProcessInstanceQuery()
                    .processInstanceBusinessKey(draftBusinessKey).count()).isOne();
        }
        finally
        {
            coordinatedDefinitionLockMapper.releaseDeploymentLock();
            shutdownExecutor(executor, "草稿提交与删除并发测试");
        }
    }

    /**
     * 验证草稿创建先持部署锁时，删除等待其提交并由活动草稿当前读稳定拒绝。
     *
     * @return void，并发创建形成孤儿 ACTIVE 草稿或删除绕过正式草稿门禁时测试失败
     * @throws Exception 并发屏障、锁等待或结果读取超时时测试失败
     */
    @Test
    void serializesDeletionAgainstActiveDraftCreation() throws Exception
    {
        ProcessDefinition definition = deployFastCompleteProcess("draft-create");
        String draftBusinessKey = businessKey("draft-create");
        coordinatedDefinitionLockMapper.armDeploymentLock(definition.getDeploymentId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try
        {
            Future<WorkflowProcessDraftView> createdDraft = executor.submit(() ->
            {
                setSecurityContextUser(ADMIN_USER_ID);
                try
                {
                    return processDraftService.create(new WorkflowProcessDraftCreateRequest(
                            definition.getId(), draftBusinessKey, Map.of(), Map.of()));
                }
                finally
                {
                    SecurityContextHolder.clearContext();
                }
            });
            assertThat(coordinatedDefinitionLockMapper.awaitDeploymentLock())
                    .as("草稿创建必须先持有真实 ACT_RE_DEPLOYMENT 行锁")
                    .isTrue();

            CountDownLatch deletionAttempted = new CountDownLatch(1);
            Future<Throwable> deletionFailure = executor.submit(() ->
            {
                deletionAttempted.countDown();
                return captureAsAdmin(() -> deploymentService.deleteDeployments(
                        List.of(definition.getDeploymentId())));
            });
            assertThat(deletionAttempted.await(20, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> deletionFailure.get(500, TimeUnit.MILLISECONDS))
                    .as("删除必须等待草稿创建事务释放部署锁")
                    .isInstanceOf(TimeoutException.class);

            coordinatedDefinitionLockMapper.releaseDeploymentLock();
            WorkflowProcessDraftView draft = createdDraft.get(20, TimeUnit.SECONDS);
            assertThat(draft.status()).isEqualTo("ACTIVE");
            assertThat(deletionFailure.get(20, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(ServiceException.class, exception ->
                    {
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(exception.getMessage())
                                .isEqualTo("部署仍有未提交申请草稿，不能删除");
                    });
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_process_draft "
                            + "where draft_id = ? and draft_status = 'ACTIVE'",
                    Long.class, draft.draftId())).isOne();
            assertThat(repositoryService().createDeploymentQuery()
                    .deploymentId(definition.getDeploymentId()).count()).isOne();
        }
        finally
        {
            coordinatedDefinitionLockMapper.releaseDeploymentLock();
            shutdownExecutor(executor, "草稿创建与删除并发测试");
        }
    }

    /**
     * 验证两个反向批量删除请求都按部署主键升序取锁，真实 MySQL 不发生死锁或部分删除。
     *
     * @return void，反向请求死锁、两次均成功或留下任一部署时测试失败
     * @throws Exception 并发请求未按时完成或数据库返回非预期异常时测试失败
     */
    @Test
    void serializesReversedBatchDeletionWithoutDeadlock() throws Exception
    {
        ProcessDefinition first = deployFastCompleteProcess("batch-first");
        ProcessDefinition second = deployFastCompleteProcess("batch-second");
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try
        {
            Future<Throwable> forward = executor.submit(() -> deleteBatchConcurrently(
                    List.of(first.getDeploymentId(), second.getDeploymentId()),
                    workersReady, releaseWorkers));
            Future<Throwable> reverse = executor.submit(() -> deleteBatchConcurrently(
                    List.of(second.getDeploymentId(), first.getDeploymentId()),
                    workersReady, releaseWorkers));
            assertThat(workersReady.await(20, TimeUnit.SECONDS)).isTrue();
            releaseWorkers.countDown();

            List<Throwable> outcomes = new ArrayList<>();
            outcomes.add(forward.get(20, TimeUnit.SECONDS));
            outcomes.add(reverse.get(20, TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(java.util.Objects::isNull).hasSize(1);
            assertThat(outcomes).filteredOn(java.util.Objects::nonNull)
                    .singleElement()
                    .isInstanceOfSatisfying(ServiceException.class, exception ->
                            assertThat(exception.getCode()).isEqualTo(HttpStatus.NOT_FOUND));
            assertThat(repositoryService().createDeploymentQuery()
                    .deploymentId(first.getDeploymentId()).count()).isZero();
            assertThat(repositoryService().createDeploymentQuery()
                    .deploymentId(second.getDeploymentId()).count()).isZero();
        }
        finally
        {
            releaseWorkers.countDown();
            shutdownExecutor(executor, "反向批量删除并发测试");
        }
    }

    /**
     * 部署真实兼容 BPMN 并建立该 deployment 对应的开始表单正式快照。
     *
     * @param suffix String，测试场景部署名称后缀
     * @return ProcessDefinition，新部署产生的唯一流程定义
     */
    private ProcessDefinition deployCompatibilityProcess(String suffix)
    {
        Deployment deployment = repositoryService().createDeployment()
                .name(DEPLOYMENT_NAME_PREFIX + "-" + suffix + "-" + UUID.randomUUID())
                .addClasspathResource("processes/flowable-compatibility-it.bpmn20.xml")
                .deploy();
        deploymentIds.add(deployment.getId());
        ProcessDefinition definition = repositoryService().createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
        assertThat(definition).isNotNull();
        assertThat(definition.getKey()).isEqualTo(PROCESS_KEY);
        insertStartFormSnapshot(deployment.getId(), suffix);
        return definition;
    }

    /**
     * 部署 start -> end 的真实同步快速结束流程，并建立无字段开始表单正式快照。
     *
     * @param suffix String，测试场景部署名称后缀
     * @return ProcessDefinition，新部署产生且可通过 ID 正式发起的唯一流程定义
     */
    private ProcessDefinition deployFastCompleteProcess(String suffix)
    {
        String processKey = "fastComplete" + UUID.randomUUID().toString().replace("-", "");
        String resourceName = processKey + ".bpmn20.xml";
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="https://approvaplat.example/deployment-concurrency-it">
                  <process id="%s" name="快速结束并发测试" isExecutable="true">
                    <startEvent id="start" name="发起申请" flowable:formKey="%s"
                                flowable:initiator="initiator"/>
                    <sequenceFlow id="toEnd" sourceRef="start" targetRef="end"/>
                    <endEvent id="end" name="结束"/>
                  </process>
                </definitions>
                """.formatted(processKey, FAST_START_FORM_KEY);
        Deployment deployment = repositoryService().createDeployment()
                .name(DEPLOYMENT_NAME_PREFIX + "-" + suffix + "-" + UUID.randomUUID())
                .addString(resourceName, bpmnXml)
                .deploy();
        deploymentIds.add(deployment.getId());
        ProcessDefinition definition = repositoryService().createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
        assertThat(definition).isNotNull();
        assertThat(definition.getKey()).isEqualTo(processKey);
        insertStartFormSnapshot(deployment.getId(), suffix,
                FAST_START_FORM_KEY, EMPTY_START_FORM_CONTENT);
        return definition;
    }

    /**
     * 为部署写入真实 wf_form 与 wf_deploy_form 开始节点快照。
     *
     * @param deploymentId String，Flowable 部署主键
     * @param suffix String，测试场景名称后缀
     * @return void，无返回值；任一正式表写入失败时测试失败
     */
    private void insertStartFormSnapshot(String deploymentId, String suffix)
    {
        insertStartFormSnapshot(deploymentId, suffix, START_FORM_KEY, START_FORM_CONTENT);
    }

    /**
     * 为部署写入指定表单 key 和 schema 的真实 wf_form 与 wf_deploy_form 开始节点快照。
     *
     * @param deploymentId String，Flowable 部署主键
     * @param suffix String，测试场景名称后缀
     * @param formKey String，BPMN 开始事件声明的正式表单 key
     * @param formContent String，开始节点不可变表单 JSON schema
     * @return void，无返回值；任一正式表写入失败时测试失败
     */
    private void insertStartFormSnapshot(String deploymentId, String suffix,
            String formKey, String formContent)
    {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        int formRows = jdbcTemplate.update(connection ->
        {
            PreparedStatement statement = connection.prepareStatement(
                    "insert into wf_form (form_name, content, create_by, del_flag) "
                            + "values (?, ?, ?, '0')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "兼容契约表单-" + suffix);
            statement.setString(2, formContent);
            statement.setString(3, "workflow-compatibility-it");
            return statement;
        }, keyHolder);
        assertThat(formRows).isEqualTo(1);
        Number generatedKey = keyHolder.getKey();
        assertThat(generatedKey).isNotNull();
        long formId = generatedKey.longValue();
        formIds.add(formId);

        int snapshotRows = jdbcTemplate.update(
                "insert into wf_deploy_form "
                        + "(deploy_id, form_id, form_key, node_key, form_name, node_name, "
                        + "content, create_by, del_flag) values (?, ?, ?, ?, ?, ?, ?, ?, '0')",
                deploymentId, formId, formKey, "start",
                "兼容契约表单-" + suffix, "发起申请", formContent,
                "workflow-compatibility-it");
        assertThat(snapshotRows).isEqualTo(1);
    }

    /**
     * 以真实管理员 SecurityContext 执行业务动作并捕获结果，供两个并发线程独立认证。
     *
     * @param action Runnable，待执行的正式服务动作
     * @return Throwable，成功时为 null，失败时为原始业务或基础设施异常
     */
    private Throwable captureAsAdmin(Runnable action)
    {
        try
        {
            setSecurityContextUser(ADMIN_USER_ID);
            action.run();
            return null;
        }
        catch (Throwable failure)
        {
            return failure;
        }
        finally
        {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 断言部署删除因真实快速结束历史实例而稳定返回 409。
     *
     * @param failure Throwable，部署删除并发线程捕获的结果
     * @return void，异常类型、状态或消息不符合历史保护契约时测试失败
     */
    private void assertHistoricDeletionConflict(Throwable failure)
    {
        assertThat(failure).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(exception.getMessage()).isEqualTo("部署仍有流程历史记录");
        });
    }

    /**
     * 在统一屏障后以真实管理员身份执行一组部署删除，并将业务结果返回给主测试线程。
     *
     * @param deploymentIds List&lt;String&gt;，客户端原始批量部署主键顺序
     * @param workersReady CountDownLatch，两个删除线程均已就绪的屏障
     * @param releaseWorkers CountDownLatch，统一释放两个反向请求的开始信号
     * @return Throwable，成功时为 null，竞争失败时为稳定业务异常
     * @throws InterruptedException 等待统一开始信号被中断时抛出
     */
    private Throwable deleteBatchConcurrently(List<String> deploymentIds,
            CountDownLatch workersReady, CountDownLatch releaseWorkers)
            throws InterruptedException
    {
        workersReady.countDown();
        if (!releaseWorkers.await(20, TimeUnit.SECONDS))
        {
            throw new FlowableException("反向批量删除开始屏障等待超时");
        }
        return captureAsAdmin(() -> deploymentService.deleteDeployments(deploymentIds));
    }

    /**
     * 终止并发测试线程池并证明没有数据库死锁或遗留工作线程。
     *
     * @param executor ExecutorService，当前场景使用的固定线程池
     * @param scenario String，线程终止失败时用于定位的场景名称
     * @return void，线程池在两轮等待后仍未终止时测试失败
     * @throws InterruptedException 当前测试线程等待终止时被中断
     */
    private void shutdownExecutor(ExecutorService executor, String scenario)
            throws InterruptedException
    {
        executor.shutdown();
        if (!executor.awaitTermination(20, TimeUnit.SECONDS))
        {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS))
                    .as(scenario + "线程池必须在清理阶段终止")
                    .isTrue();
        }
    }

    /**
     * 断言停用 assignee 或 owner 的 key 发起返回 400 且运行、历史实例均为零。
     *
     * @param suffix String，业务主键场景后缀
     * @param assigneeId long，BPMN 表达式解析的办理用户主键
     * @param ownerId long，BPMN 表达式解析的 owner 用户主键
     * @return void，无返回值；拒绝语义或零副作用不符合契约时测试失败
     */
    private void assertRejectedStartForInactiveIdentity(String suffix,
            long assigneeId, long ownerId)
    {
        String rejectedBusinessKey = businessKey(suffix);
        assertThatThrownBy(() -> processStartService.startProcessByDefKey(
                PROCESS_KEY, rejectedBusinessKey, listenerVariables(assigneeId, ownerId)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("用户任务办理身份无效");
                });
        assertThat(runtimeService().createProcessInstanceQuery()
                .processInstanceBusinessKey(rejectedBusinessKey).count()).isZero();
        assertThat(historyService().createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(rejectedBusinessKey).count()).isZero();
    }

    /**
     * 读取并断言指定实例的监听 comment 动作集合、作者及安全 JSON 字段。
     *
     * @param processInstanceId String，流程实例主键
     * @param expectedActions Set&lt;String&gt;，预期唯一动作集合
     * @return void，无返回值；comment 缺失、重复或包含状态改写字段时测试失败
     * @throws Exception comment JSON 无法解析时测试失败
     */
    private void assertListenerActions(String processInstanceId,
            Set<String> expectedActions) throws Exception
    {
        List<Comment> comments = listenerComments(processInstanceId);
        assertThat(comments).hasSize(expectedActions.size());
        ObjectMapper objectMapper = JsonMapper.shared();
        LinkedHashSet<String> actualActions = new LinkedHashSet<>();
        for (Comment comment : comments)
        {
            assertThat(comment.getUserId()).isNotBlank();
            JsonNode audit = objectMapper.readTree(comment.getFullMessage());
            assertThat(audit.path("schemaVersion").intValue()).isEqualTo(1);
            assertThat(audit.path("processInstanceId").textValue())
                    .isEqualTo(processInstanceId);
            assertThat(audit.path("assigneeUserId").textValue()).isNotBlank();
            if (!"USER_TASK_ASSIGNMENT".equals(audit.path("action").textValue()))
            {
                // Flowable 可能在初始化 owner 前触发 assignment；create/complete 必须包含最终 owner。
                assertThat(audit.path("ownerUserId").textValue()).isNotBlank();
            }
            assertThat(audit.has("processStatus")).isFalse();
            actualActions.add(audit.path("action").textValue());
        }
        assertThat(actualActions).containsExactlyInAnyOrderElementsOf(expectedActions);
    }

    /**
     * 从真实历史变量表读取并断言最终 processStatus。
     *
     * @param processInstanceId String，已结束流程实例主键
     * @param expectedStatus String，预期自然或显式业务终态
     * @return void，无返回值；历史状态缺失或被监听器覆盖时测试失败
     */
    private void assertHistoricStatus(String processInstanceId, String expectedStatus)
    {
        var historicStatus = historyService().createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId)
                .variableName(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE)
                .singleResult();
        assertThat(historicStatus).isNotNull();
        assertThat(historicStatus.getValue()).isEqualTo(expectedStatus);
    }

    /**
     * 查询指定业务主键唯一的真实运行实例。
     *
     * @param businessKey String，测试业务主键
     * @return ProcessInstance，唯一活动实例
     */
    private ProcessInstance requireRuntimeInstance(String businessKey)
    {
        ProcessInstance instance = runtimeService().createProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).singleResult();
        assertThat(instance).isNotNull();
        return instance;
    }

    /**
     * 查询指定流程实例唯一的真实活动任务。
     *
     * @param processInstanceId String，活动流程实例主键
     * @return Task，唯一活动用户任务
     */
    private Task requireActiveTask(String processInstanceId)
    {
        Task task = taskService().createTaskQuery()
                .processInstanceId(processInstanceId).singleResult();
        assertThat(task).isNotNull();
        return task;
    }

    /**
     * 查询指定流程实例的固定类型用户任务监听 comment。
     *
     * @param processInstanceId String，流程实例主键
     * @return List&lt;Comment&gt;，Flowable 正式 comment 记录
     */
    private List<Comment> listenerComments(String processInstanceId)
    {
        return taskService().getProcessInstanceComments(
                processInstanceId, WorkflowUserTaskAuditService.COMMENT_TYPE);
    }

    /**
     * 构造兼容 BPMN 开始表单允许的 assignee/owner 变量。
     *
     * @param assigneeId long，办理用户主键
     * @param ownerId long，任务 owner 用户主键
     * @return Map&lt;String, Object&gt;，只包含部署 schema 白名单字段的不可变映射
     */
    private Map<String, Object> listenerVariables(long assigneeId, long ownerId)
    {
        return Map.of("assigneeId", String.valueOf(assigneeId),
                "ownerId", String.valueOf(ownerId));
    }

    /**
     * 生成带固定前缀的唯一测试业务主键。
     *
     * @param suffix String，场景定位后缀
     * @return String，长度受控的唯一业务主键
     */
    private String businessKey(String suffix)
    {
        return BUSINESS_KEY_PREFIX + suffix + "-" + UUID.randomUUID();
    }

    /**
     * 更新既有测试用户状态，用于真实停用身份回滚场景。
     *
     * @param status String，若依用户状态；0 为启用，1 为停用
     * @return void，无返回值；更新行数不是一行时测试失败
     */
    private void setToggleUserStatus(String status)
    {
        assertThat(jdbcTemplate.update(
                "update sys_user set status = ? where user_id = ? and del_flag = '0'",
                status, toggleUserId)).isEqualTo(1);
    }

    /**
     * 将指定正式用户写入当前测试线程的 Spring SecurityContext。
     *
     * @param userId long，当前线程使用的若依用户主键
     * @return void，无返回值；后续服务会再次从正式 sys_user 核验该身份
     */
    private void setSecurityContextUser(long userId)
    {
        SysUser user = new SysUser(userId);
        user.setUserName("workflow_compatibility_it_" + userId);
        user.setNickName("兼容契约集成用户" + userId);
        LoginUser loginUser = new LoginUser(userId, null, user, Set.of());
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        loginUser, null, loginUser.getAuthorities());
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);
    }

    /**
     * 获取真实 Flowable RepositoryService。
     *
     * @return RepositoryService，当前 Spring ProcessEngine 的定义与部署服务
     */
    private RepositoryService repositoryService()
    {
        return processEngine.getRepositoryService();
    }

    /**
     * 获取真实 Flowable RuntimeService。
     *
     * @return RuntimeService，当前 Spring ProcessEngine 的运行时服务
     */
    private RuntimeService runtimeService()
    {
        return processEngine.getRuntimeService();
    }

    /**
     * 获取真实 Flowable TaskService。
     *
     * @return TaskService，当前 Spring ProcessEngine 的任务与 comment 服务
     */
    private TaskService taskService()
    {
        return processEngine.getTaskService();
    }

    /**
     * 获取真实 Flowable HistoryService。
     *
     * @return HistoryService，当前 Spring ProcessEngine 的历史查询服务
     */
    private HistoryService historyService()
    {
        return processEngine.getHistoryService();
    }

    /**
     * 为并发部署 IT 提供真实变量校验后的确定性屏障，不替换或跳过任何生产校验逻辑。
     */
    static class CoordinatedStartVariableValidator extends WorkflowStartVariableValidator
    {
        /** 发起线程完成真实变量校验后通知测试线程的门闩。 */
        private volatile CountDownLatch validationReached;

        /** 测试线程完成新版本部署后释放发起线程的门闩。 */
        private volatile CountDownLatch continueValidation;

        /**
         * 创建调用生产实现的协调变量验证器。
         *
         * @param templateValidator WorkflowFormTemplateValidator，真实表单 schema 校验器
         * @return 无返回值，构造后仅在测试上下文中作为 Primary Bean
         */
        CoordinatedStartVariableValidator(WorkflowFormTemplateValidator templateValidator)
        {
            super(templateValidator);
        }

        /**
         * 为下一次变量校验建立单次并发部署屏障。
         *
         * @return void，无返回值
         */
        void arm()
        {
            validationReached = new CountDownLatch(1);
            continueValidation = new CountDownLatch(1);
        }

        /**
         * 等待发起线程完成真实变量校验。
         *
         * @return boolean，20 秒内到达屏障时为 true
         * @throws InterruptedException 当前测试线程被中断时传播给 JUnit
         */
        boolean awaitValidation() throws InterruptedException
        {
            CountDownLatch current = validationReached;
            return current != null && current.await(20, TimeUnit.SECONDS);
        }

        /**
         * 释放可能等待的发起线程；重复调用安全无副作用。
         *
         * @return void，无返回值
         */
        void release()
        {
            CountDownLatch current = continueValidation;
            if (current != null)
            {
                current.countDown();
            }
        }

        /**
         * 完整执行生产变量校验，再按测试屏障等待并发部署提交。
         *
         * @param snapshotContent String，真实 wf_deploy_form 开始表单 JSON
         * @param variables Map&lt;String, Object&gt;，客户端开始表单变量
         * @return WorkflowValidatedStartVariables，生产校验器生成的正式规范结果
         */
        @Override
        public WorkflowValidatedStartVariables validateForStart(String snapshotContent,
                Map<String, Object> variables)
        {
            WorkflowValidatedStartVariables validated =
                    super.validateForStart(snapshotContent, variables);
            CountDownLatch reached = validationReached;
            CountDownLatch proceed = continueValidation;
            if (reached != null && proceed != null && reached.getCount() > 0L)
            {
                reached.countDown();
                try
                {
                    if (!proceed.await(20, TimeUnit.SECONDS))
                    {
                        throw new FlowableException("并发部署测试屏障等待超时");
                    }
                }
                catch (InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                    throw new FlowableException("并发部署测试屏障被中断", exception);
                }
            }
            return validated;
        }
    }

    /**
     * 在生产 Mapper 已取得真实流程定义锁后提供确定性屏障，不替换任何锁 SQL 或查询结果。
     */
    static class CoordinatedProcessDefinitionLockMapper
            implements WorkflowProcessDefinitionLockMapper
    {
        /** 执行真实 MyBatis 锁 SQL 的生产 Mapper。 */
        private final WorkflowProcessDefinitionLockMapper delegate;

        /** 真实 FOR UPDATE 返回后通知测试线程的门闩。 */
        private volatile CountDownLatch lockReached;

        /** 测试线程完成锁后部署阻塞断言后释放发起线程的门闩。 */
        private volatile CountDownLatch continueAfterLock;

        /** 下一次需要暂停的部署生命周期主键。 */
        private volatile String coordinatedDeploymentId;

        /** 真实部署 FOR UPDATE 返回后通知测试线程的门闩。 */
        private volatile CountDownLatch deploymentLockReached;

        /** 测试线程完成并发删除阻塞断言后释放业务线程的门闩。 */
        private volatile CountDownLatch continueAfterDeploymentLock;

        /**
         * 创建只负责协调时序的 Mapper 装饰器。
         *
         * @param delegate WorkflowProcessDefinitionLockMapper，执行生产锁 SQL 的原始 MyBatis Mapper
         * @return 无返回值，构造后仅在当前 IT 上下文作为 Primary Bean
         */
        CoordinatedProcessDefinitionLockMapper(WorkflowProcessDefinitionLockMapper delegate)
        {
            this.delegate = delegate;
        }

        /**
         * 为下一次定义锁查询建立单次屏障。
         *
         * @return void，无返回值
         */
        void arm()
        {
            lockReached = new CountDownLatch(1);
            continueAfterLock = new CountDownLatch(1);
        }

        /**
         * 等待发起事务取得真实定义锁。
         *
         * @return boolean，20 秒内真实锁 SQL 返回时为 true
         * @throws InterruptedException 当前测试线程被中断时传播给 JUnit
         */
        boolean awaitLock() throws InterruptedException
        {
            CountDownLatch current = lockReached;
            return current != null && current.await(20, TimeUnit.SECONDS);
        }

        /**
         * 释放可能等待的发起线程；重复调用安全无副作用。
         *
         * @return void，无返回值
         */
        void release()
        {
            CountDownLatch current = continueAfterLock;
            if (current != null)
            {
                current.countDown();
            }
        }

        /**
         * 为指定部署的下一次生命周期锁查询建立单次屏障。
         *
         * @param deploymentId String，测试创建且需要协调的真实 Flowable 部署主键
         * @return void，无返回值
         */
        void armDeploymentLock(String deploymentId)
        {
            coordinatedDeploymentId = deploymentId;
            deploymentLockReached = new CountDownLatch(1);
            continueAfterDeploymentLock = new CountDownLatch(1);
        }

        /**
         * 等待业务事务取得指定部署的真实生命周期锁。
         *
         * @return boolean，20 秒内真实锁 SQL 返回时为 true
         * @throws InterruptedException 当前测试线程被中断时传播给 JUnit
         */
        boolean awaitDeploymentLock() throws InterruptedException
        {
            CountDownLatch current = deploymentLockReached;
            return current != null && current.await(20, TimeUnit.SECONDS);
        }

        /**
         * 释放可能等待的部署业务线程；重复调用安全且不会暂停后续删除线程。
         *
         * @return void，无返回值
         */
        void releaseDeploymentLock()
        {
            CountDownLatch current = continueAfterDeploymentLock;
            coordinatedDeploymentId = null;
            if (current != null)
            {
                current.countDown();
            }
        }

        /**
         * 完整执行生产锁 SQL，再在锁仍属于外层事务时按测试屏障等待。
         *
         * @param processKey String，已经过业务校验的流程定义 key
         * @return WorkflowProcessDefinitionLockRow，生产 Mapper 返回的真实锁投影
         */
        @Override
        public WorkflowProcessDefinitionLockRow selectLatestDefaultTenantDefinitionForUpdate(
                String processKey)
        {
            WorkflowProcessDefinitionLockRow locked = delegate
                    .selectLatestDefaultTenantDefinitionForUpdate(processKey);
            CountDownLatch reached = lockReached;
            CountDownLatch proceed = continueAfterLock;
            if (reached != null && proceed != null && reached.getCount() > 0L)
            {
                reached.countDown();
                try
                {
                    if (!proceed.await(20, TimeUnit.SECONDS))
                    {
                        throw new FlowableException("定义锁测试屏障等待超时");
                    }
                }
                catch (InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                    throw new FlowableException("定义锁测试屏障被中断", exception);
                }
            }
            return locked;
        }

        /**
         * 完整执行生产部署锁 SQL，并仅对显式指定部署的第一次调用建立测试屏障。
         *
         * @param deploymentId String，已经过业务校验的 Flowable 部署主键
         * @return String，生产 Mapper 返回的已锁定部署主键；部署不存在时为 null
         */
        @Override
        public String selectDeploymentIdForUpdate(String deploymentId)
        {
            String lockedDeploymentId = delegate.selectDeploymentIdForUpdate(deploymentId);
            String targetDeploymentId = coordinatedDeploymentId;
            CountDownLatch reached = deploymentLockReached;
            CountDownLatch proceed = continueAfterDeploymentLock;
            if (deploymentId.equals(targetDeploymentId)
                    && deploymentId.equals(lockedDeploymentId)
                    && reached != null && proceed != null && reached.getCount() > 0L)
            {
                reached.countDown();
                try
                {
                    if (!proceed.await(20, TimeUnit.SECONDS))
                    {
                        throw new FlowableException("部署锁测试屏障等待超时");
                    }
                }
                catch (InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                    throw new FlowableException("部署锁测试屏障被中断", exception);
                }
            }
            return lockedDeploymentId;
        }

        /**
         * 原样委托运行实例当前读，保持兼容并发 IT 只协调定义版本锁。
         *
         * @param deploymentId String，已经持有部署生命周期锁的部署主键
         * @return Integer，存在运行实例时为 1，否则为 null
         */
        @Override
        public Integer selectRuntimeInstanceReferenceForUpdate(String deploymentId)
        {
            return delegate.selectRuntimeInstanceReferenceForUpdate(deploymentId);
        }

        /**
         * 原样委托历史实例当前读，保持兼容并发 IT 使用生产删除门禁语义。
         *
         * @param deploymentId String，已经持有部署生命周期锁的部署主键
         * @return Integer，存在历史实例时为 1，否则为 null
         */
        @Override
        public Integer selectHistoricInstanceReferenceForUpdate(String deploymentId)
        {
            return delegate.selectHistoricInstanceReferenceForUpdate(deploymentId);
        }
    }

    /**
     * 仅在当前真实 IT 上下文中用协调验证器包装生产变量校验。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class CompatibilityTestConfiguration
    {
        /**
         * 提供调用真实生产校验后才暂停的 Primary 测试 Bean。
         *
         * @param templateValidator WorkflowFormTemplateValidator，正式表单校验器
         * @return CoordinatedStartVariableValidator，并发部署 IT 使用的确定性屏障
         */
        @Bean
        @Primary
        CoordinatedStartVariableValidator coordinatedStartVariableValidator(
                WorkflowFormTemplateValidator templateValidator)
        {
            return new CoordinatedStartVariableValidator(templateValidator);
        }

        /**
         * 提供委托真实 MyBatis Mapper 的 Primary 锁时序装饰器。
         *
         * @param delegate WorkflowProcessDefinitionLockMapper，MapperScan 创建的生产 Mapper
         * @return CoordinatedProcessDefinitionLockMapper，只增加测试屏障的装饰器
         */
        @Bean
        @Primary
        CoordinatedProcessDefinitionLockMapper coordinatedProcessDefinitionLockMapper(
                @Qualifier("workflowProcessDefinitionLockMapper")
                WorkflowProcessDefinitionLockMapper delegate)
        {
            return new CoordinatedProcessDefinitionLockMapper(delegate);
        }
    }
}

package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
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
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.task.Comment;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowInstanceTerminateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowMultiInstanceAdjustmentAction;
import com.ruoyi.flowable.domain.dto.WorkflowMultiInstanceAdjustmentRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessCancelRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskCompleteRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskRejectRequest;
import com.ruoyi.flowable.domain.vo.WorkflowMultiInstanceStateView;
import com.ruoyi.flowable.engine.WorkflowProcessEngineAdapter;
import com.ruoyi.flowable.service.process.WorkflowProcessInstanceService;
import com.ruoyi.flowable.service.process.WorkflowProcessStartService;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceService;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceVariables;
import com.ruoyi.flowable.service.task.WorkflowTaskLifecycleService;

@SpringBootTest(
    classes = RuoYiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        "spring.datasource.druid.master.url=${FLOWABLE_IT_JDBC_URL}",
        "spring.datasource.druid.master.username=${FLOWABLE_IT_USERNAME}",
        "spring.datasource.druid.master.password=${FLOWABLE_IT_PASSWORD}",
        "spring.data.redis.database=${FLOWABLE_IT_REDIS_DATABASE:15}",
        "flowable.it.expected-schema=${FLOWABLE_IT_EXPECTED_SCHEMA}",
        "flowable.it.accounts-registered=${FLOWABLE_IT_ACCOUNTS_REGISTERED:false}",
        // 固定公开材料只用于装配 TokenService，本 IT 不创建登录 Token，禁止复用于任何部署环境。
        "token.secret=eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eA==",
        "flowable.database-schema-update=false",
        "flowable.async-executor-activate=false",
        "flowable.async-history-executor-activate=false",
        "spring.quartz.auto-startup=false"
    }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.parallel.Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkflowMultiInstanceIT
{
    /** 独立集成部署名称，清理和残留检查只匹配此前缀。 */
    private static final String DEPLOYMENT_NAME =
            "approvaplat-flowable-multi-instance-it";

    /** 测试业务主键前缀，用于对账当前类产生的运行和历史实例。 */
    private static final String BUSINESS_KEY_PREFIX = "multi-instance-it-";

    /** 动态会签流程定义 key。 */
    private static final String ALL_PROCESS_KEY =
            "flowableMultiInstanceAllIntegration";

    /** 动态或签流程定义 key。 */
    private static final String ANY_PROCESS_KEY =
            "flowableMultiInstanceAnyIntegration";

    /** 指定角色会签流程定义 key。 */
    private static final String ROLE_ALL_PROCESS_KEY =
            "flowableMultiInstanceRoleAllIntegration";

    /** 指定部门或签流程定义 key。 */
    private static final String DEPT_ANY_PROCESS_KEY =
            "flowableMultiInstanceDeptAnyIntegration";

    /** 会签多实例活动 ID。 */
    private static final String ALL_ACTIVITY_ID = "allApprove";

    /** 或签多实例活动 ID。 */
    private static final String ANY_ACTIVITY_ID = "anyApprove";

    /** 指定角色会签多实例活动 ID。 */
    private static final String ROLE_ALL_ACTIVITY_ID = "roleAllApprove";

    /** 指定部门或签多实例活动 ID。 */
    private static final String DEPT_ANY_ACTIVITY_ID = "deptAnyApprove";

    /** 已由受控环境预置并登记的专用有效用户 ID。 */
    private static final List<Long> TEST_USER_IDS = List.of(81L, 82L, 83L, 84L);

    /** 指定角色 101 当前按正式审批资格展开的完整用户 ID。 */
    private static final List<Long> ROLE_ALL_USER_IDS =
            List.of(81L, 82L, 83L, 84L, 103L);

    /** 指定部门 100 当前按正式审批资格展开的完整用户 ID。 */
    private static final List<Long> DEPT_ANY_USER_IDS =
            List.of(1L, 81L, 82L, 83L, 84L, 100L, 103L);

    /** 单个并发动作允许占用数据库锁并返回的最长时间。 */
    private static final Duration CONCURRENT_TIMEOUT = Duration.ofSeconds(30);

    /** 直接领域服务所需的页面和 API 权限集合。 */
    private static final Set<String> APPROVAL_PERMISSIONS =
            Set.of("workflow:process:approval");

    /** 解析结构化 Flowable comment 的 JSON 映射器。 */
    private static final ObjectMapper AUDIT_MAPPER = JsonMapper.shared();

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private WorkflowProcessEngineAdapter processEngineAdapter;

    @Autowired
    private WorkflowMultiInstanceService multiInstanceService;

    @Autowired
    private WorkflowTaskLifecycleService taskLifecycleService;

    @Autowired
    private WorkflowProcessInstanceService processInstanceService;

    @Autowired
    @Qualifier("dynamicDataSource")
    private DataSource dataSource;

    @Value("${flowable.it.expected-schema}")
    private String expectedSchema;

    /** 操作员已在首次使用前把专用账号登记到忽略文件的显式证明。 */
    @Value("${flowable.it.accounts-registered}")
    private boolean accountsRegistered;

    /** 当前类使用的真实 MySQL 查询客户端。 */
    private JdbcTemplate jdbcTemplate;

    /** 当前类唯一部署主键，AfterAll 只对该主键执行级联清理。 */
    private String deploymentId;

    /**
     * 验证专用 schema 和已登记账号，再部署本类独占 BPMN 测试夹具。
     *
     * @return 无返回值；schema、账号登记或部署前置不满足时整类测试失败
     * @throws SQLException 无法读取真实 MySQL 连接元数据时测试失败
     */
    @BeforeAll
    void prepareIntegrationFixture() throws SQLException
    {
        jdbcTemplate = new JdbcTemplate(dataSource);
        assertDedicatedSchema();
        assertThat(accountsRegistered)
                .as("专用账号必须先写入 testcount/accounts.local.md，随后才可运行真实集成测试")
                .isTrue();
        // 测试只消费受控环境预置账号，不在业务库临时创建或删除任何登录主体。
        assertProvisionedTestUsers();

        RepositoryService repositoryService = processEngine.getRepositoryService();
        Deployment deployment = repositoryService.createDeployment()
                .name(DEPLOYMENT_NAME)
                .addClasspathResource("processes/flowable-multi-instance-it.bpmn20.xml")
                .deploy();
        deploymentId = deployment.getId();
    }

    /**
     * 在每条测试前清除线程身份，防止上一条用例的 assignee 身份泄漏。
     *
     * @return 无返回值；清理后当前线程不携带认证信息
     */
    @BeforeEach
    void clearIdentityBeforeTest()
    {
        SecurityContextHolder.clearContext();
    }

    /**
     * 在每条测试后释放当前线程身份；测试流程保留至类级部署清理统一级联删除。
     *
     * @return 无返回值；后续用例从空安全上下文开始
     */
    @AfterEach
    void clearIdentityAfterTest()
    {
        SecurityContextHolder.clearContext();
    }

    /**
     * 级联删除本类唯一部署及其 runtime/history/comment，并确认预置账号未被测试修改。
     *
     * @return 无返回值；发现部署、流程残留或预置账号漂移时清理门禁失败
     */
    @AfterAll
    void cleanupIntegrationFixture()
    {
        SecurityContextHolder.clearContext();
        RepositoryService repositoryService = processEngine.getRepositoryService();
        if (deploymentId != null && repositoryService.createDeploymentQuery()
                .deploymentId(deploymentId).count() > 0)
        {
            repositoryService.deleteDeployment(deploymentId, true);
        }

        assertThat(repositoryService.createDeploymentQuery()
                .deploymentName(DEPLOYMENT_NAME).count()).isZero();
        assertThat(processEngine.getRuntimeService().createProcessInstanceQuery()
                .processInstanceBusinessKeyLike(BUSINESS_KEY_PREFIX + "%").count()).isZero();
        assertThat(processEngine.getHistoryService().createHistoricProcessInstanceQuery()
                .processInstanceBusinessKeyLike(BUSINESS_KEY_PREFIX + "%").count()).isZero();
        assertProvisionedTestUsers();
    }

    /**
     * 验证 ALL 会签由 nextUserIds 创建三个真实任务，部分完成后保持活动，全部完成后自然结束。
     *
     * @return 无返回值；任务、execution、计数、历史或 completed 终态不一致时测试失败
     */
    @Test
    void allModeKeepsRemainingMembersActiveUntilEveryMemberCompletes()
    {
        String processInstanceId = startDynamicProcess(ALL_PROCESS_KEY, "allSource",
                ALL_ACTIVITY_ID, List.of(81L, 82L, 83L));

        assertExecutionTree(processInstanceId, ALL_ACTIVITY_ID, 3, 0);
        setSecurityContextUser(81L);
        WorkflowMultiInstanceStateView initial = multiInstanceService.getState(
                taskForAssignee(processInstanceId, ALL_ACTIVITY_ID, 81L).getId());
        assertThat(initial.mode()).isEqualTo("ALL");
        assertThat(initial.revision()).isZero();
        assertThat(initial.members()).extracting(member -> member.userId())
                .containsExactly(81L, 82L, 83L);

        completeMember(processInstanceId, ALL_ACTIVITY_ID, 81L);

        assertThat(processEngine.getRuntimeService().createProcessInstanceQuery()
                .processInstanceId(processInstanceId).count()).isOne();
        assertExecutionTree(processInstanceId, ALL_ACTIVITY_ID, 2, 1);
        setSecurityContextUser(82L);
        WorkflowMultiInstanceStateView partial = multiInstanceService.getState(
                taskForAssignee(processInstanceId, ALL_ACTIVITY_ID, 82L).getId());
        assertThat(partial.members()).filteredOn(member -> member.active()).hasSize(2);
        assertThat(partial.members()).filteredOn(member -> !member.active())
                .extracting(member -> member.userId()).containsExactly(81L);

        completeMember(processInstanceId, ALL_ACTIVITY_ID, 82L);
        completeMember(processInstanceId, ALL_ACTIVITY_ID, 83L);

        assertNaturallyCompleted(processInstanceId);
        assertThat(historicVariable(processInstanceId,
                WorkflowMultiInstanceVariables.revisionName(ALL_ACTIVITY_ID))).isEqualTo(3);
        assertCompletionAuditRevisions(processInstanceId, ALL_ACTIVITY_ID,
                List.of(1, 2, 3));
        List<HistoricTaskInstance> history = historicTasks(processInstanceId,
                ALL_ACTIVITY_ID);
        assertThat(history).hasSize(3).allSatisfy(task ->
        {
            assertThat(task.getEndTime()).isNotNull();
            assertThat(task.getDeleteReason()).isNull();
        });
    }

    /**
     * 验证 ANY 或签任一成员完成即结束节点，其余 sibling 留下可追踪的取消历史且无活动 execution。
     *
     * @return 无返回值；提前完成、历史成员或服务端变量快照不完整时测试失败
     */
    @Test
    void anyModeCompletesAfterFirstMemberAndPreservesSiblingHistory()
    {
        String processInstanceId = startDynamicProcess(ANY_PROCESS_KEY, "anySource",
                ANY_ACTIVITY_ID, List.of(81L, 82L, 83L));
        assertExecutionTree(processInstanceId, ANY_ACTIVITY_ID, 3, 0);

        completeMember(processInstanceId, ANY_ACTIVITY_ID, 81L);

        assertNaturallyCompleted(processInstanceId);
        List<HistoricTaskInstance> history = historicTasks(processInstanceId,
                ANY_ACTIVITY_ID);
        assertThat(history).hasSize(3).allSatisfy(task ->
                assertThat(task.getEndTime()).isNotNull());
        assertThat(history).filteredOn(task -> task.getDeleteReason() == null)
                .singleElement().extracting(HistoricTaskInstance::getAssignee)
                .isEqualTo("81");
        assertThat(history).filteredOn(task -> task.getDeleteReason() != null).hasSize(2);
        assertThat(historicVariable(processInstanceId,
                WorkflowMultiInstanceVariables.memberSnapshotName(ANY_ACTIVITY_ID)))
                .isEqualTo(List.of("81", "82", "83"));
        assertThat(historicVariable(processInstanceId,
                WorkflowMultiInstanceVariables.revisionName(ANY_ACTIVITY_ID))).isEqualTo(1);
        assertThat(historicVariable(processInstanceId,
                WorkflowMultiInstanceVariables.modeName(ANY_ACTIVITY_ID))).isEqualTo("ANY");
        assertCompletionAuditRevisions(processInstanceId, ANY_ACTIVITY_ID, List.of(1));
    }

    /**
     * 验证指定角色在节点进入时按实时 RBAC 展开完整 assignee 集合，会签等待全部成员完成。
     *
     * @return 无返回值；角色展开、候选链接、完成条件、历史或快照不一致时测试失败
     */
    @Test
    void configuredRoleAllExpandsRealAssigneesAndWaitsForEveryMember()
    {
        String processInstanceId = startConfiguredProcess(
                ROLE_ALL_PROCESS_KEY, ROLE_ALL_ACTIVITY_ID, "ALL", ROLE_ALL_USER_IDS);

        completeMember(processInstanceId, ROLE_ALL_ACTIVITY_ID, 81L);

        assertThat(processEngine.getRuntimeService().createProcessInstanceQuery()
                .processInstanceId(processInstanceId).count()).isOne();
        assertExecutionTree(processInstanceId, ROLE_ALL_ACTIVITY_ID, 4, 1);
        assertAssigneeTasksWithoutCandidates(processInstanceId, ROLE_ALL_ACTIVITY_ID,
                List.of("82", "83", "84", "103"));

        completeMember(processInstanceId, ROLE_ALL_ACTIVITY_ID, 82L);
        completeMember(processInstanceId, ROLE_ALL_ACTIVITY_ID, 83L);
        completeMember(processInstanceId, ROLE_ALL_ACTIVITY_ID, 84L);
        completeMember(processInstanceId, ROLE_ALL_ACTIVITY_ID, 103L);

        assertNaturallyCompleted(processInstanceId);
        assertThat(historicVariable(processInstanceId,
                WorkflowMultiInstanceVariables.memberSnapshotName(ROLE_ALL_ACTIVITY_ID)))
                .isEqualTo(List.of("81", "82", "83", "84", "103"));
        assertThat(historicVariable(processInstanceId,
                WorkflowMultiInstanceVariables.revisionName(ROLE_ALL_ACTIVITY_ID))).isEqualTo(5);
        assertThat(historicVariable(processInstanceId,
                WorkflowMultiInstanceVariables.modeName(ROLE_ALL_ACTIVITY_ID))).isEqualTo("ALL");
        assertCompletionAuditRevisions(processInstanceId, ROLE_ALL_ACTIVITY_ID,
                List.of(1, 2, 3, 4, 5));
        assertThat(historicTasks(processInstanceId, ROLE_ALL_ACTIVITY_ID))
                .hasSize(5)
                .extracting(HistoricTaskInstance::getDeleteReason)
                .containsOnlyNulls();
    }

    /**
     * 验证指定身份会签运行期加签后，重求值复用正式成员快照并允许全部成员自然完成。
     *
     * @return 无返回值；新增成员丢失、原成员完成报错或最终快照与 revision 不一致时测试失败
     */
    @Test
    void configuredRoleAllCompletesAfterRuntimeAddSign()
    {
        String processInstanceId = startConfiguredProcess(
                ROLE_ALL_PROCESS_KEY, ROLE_ALL_ACTIVITY_ID, "ALL", ROLE_ALL_USER_IDS);
        Task operatorTask = taskForAssignee(
                processInstanceId, ROLE_ALL_ACTIVITY_ID, 81L);
        setSecurityContextUser(81L);

        // adjustedUserIds 是加签后的正式成员顺序，用户 100 不属于 BPMN 原始角色展开结果。
        List<Long> adjustedUserIds = List.of(81L, 82L, 83L, 84L, 103L, 100L);
        WorkflowMultiInstanceStateView added = multiInstanceService.adjust(
                new WorkflowMultiInstanceAdjustmentRequest(operatorTask.getId(),
                        WorkflowMultiInstanceAdjustmentAction.ADD, 0L, "指定身份真实加签",
                        List.of(100L), null));

        assertThat(added.revision()).isEqualTo(1L);
        assertThat(added.members()).extracting(member -> member.userId())
                .containsExactlyElementsOf(adjustedUserIds);
        assertExecutionTree(processInstanceId, ROLE_ALL_ACTIVITY_ID, 6, 0);
        for (Long userId : adjustedUserIds)
        {
            completeMember(processInstanceId, ROLE_ALL_ACTIVITY_ID, userId);
        }

        assertNaturallyCompleted(processInstanceId);
        assertThat(historicVariable(processInstanceId,
                WorkflowMultiInstanceVariables.memberSnapshotName(ROLE_ALL_ACTIVITY_ID)))
                .isEqualTo(adjustedUserIds.stream().map(String::valueOf).toList());
        assertThat(historicVariable(processInstanceId,
                WorkflowMultiInstanceVariables.revisionName(ROLE_ALL_ACTIVITY_ID))).isEqualTo(7);
        assertCompletionAuditRevisions(processInstanceId, ROLE_ALL_ACTIVITY_ID,
                List.of(2, 3, 4, 5, 6, 7));
        assertThat(historicTasks(processInstanceId, ROLE_ALL_ACTIVITY_ID))
                .hasSize(6)
                .extracting(HistoricTaskInstance::getDeleteReason)
                .containsOnlyNulls();
    }

    /**
     * 验证指定部门在节点进入时按实时 RBAC 展开完整 assignee 集合，或签首人完成后取消其余实例。
     *
     * @return 无返回值；部门展开、候选链接、取消历史、模式或快照不一致时测试失败
     */
    @Test
    void configuredDeptAnyExpandsRealAssigneesAndCancelsRemainingMembers()
    {
        String processInstanceId = startConfiguredProcess(
                DEPT_ANY_PROCESS_KEY, DEPT_ANY_ACTIVITY_ID, "ANY", DEPT_ANY_USER_IDS);

        completeMember(processInstanceId, DEPT_ANY_ACTIVITY_ID, 81L);

        assertNaturallyCompleted(processInstanceId);
        List<HistoricTaskInstance> history = historicTasks(
                processInstanceId, DEPT_ANY_ACTIVITY_ID);
        assertThat(history).hasSize(7).allSatisfy(task ->
                assertThat(task.getEndTime()).isNotNull());
        assertThat(history).filteredOn(task -> task.getDeleteReason() == null)
                .singleElement().extracting(HistoricTaskInstance::getAssignee)
                .isEqualTo("81");
        assertThat(history).filteredOn(task -> task.getDeleteReason() != null).hasSize(6);
        assertThat(historicVariable(processInstanceId,
                WorkflowMultiInstanceVariables.memberSnapshotName(DEPT_ANY_ACTIVITY_ID)))
                .isEqualTo(List.of("1", "81", "82", "83", "84", "100", "103"));
        assertThat(historicVariable(processInstanceId,
                WorkflowMultiInstanceVariables.revisionName(DEPT_ANY_ACTIVITY_ID))).isEqualTo(1);
        assertThat(historicVariable(processInstanceId,
                WorkflowMultiInstanceVariables.modeName(DEPT_ANY_ACTIVITY_ID))).isEqualTo("ANY");
        assertCompletionAuditRevisions(processInstanceId, DEPT_ANY_ACTIVITY_ID, List.of(1));
    }

    /**
     * 验证动态多实例来源任务缺少 nextUserIds 时返回 400，完整完成事务回滚且零副作用。
     *
     * @return 无返回值；来源任务、变量、comment、目标任务或 execution 发生变化时测试失败
     */
    @Test
    void missingNextUsersRejectsSourceCompletionAndRollsBackEverySideEffect()
    {
        String processInstanceId = startSourceProcess(ALL_PROCESS_KEY, "allSource");
        TaskService taskService = processEngine.getTaskService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        Task sourceTask = taskService.createTaskQuery().processInstanceId(processInstanceId)
                .taskDefinitionKey("allSource").active().singleResult();
        assertThat(sourceTask).isNotNull();
        long executionCountBefore = runtimeService.createExecutionQuery()
                .processInstanceId(processInstanceId).count();
        setSecurityContextUser(81L);

        assertThatThrownBy(() -> taskLifecycleService.completeTask(
                new WorkflowTaskCompleteRequest(sourceTask.getId(),
                        "缺少动态成员", Map.of(), List.of(), List.of())))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("动态多实例下一办理人不能为空");
                });

        Task unchangedSource = taskService.createTaskQuery().taskId(sourceTask.getId())
                .active().singleResult();
        assertThat(unchangedSource).isNotNull();
        assertThat(unchangedSource.getAssignee()).isEqualTo("81");
        assertThat(taskService.createTaskQuery().processInstanceId(processInstanceId)
                .taskDefinitionKey(ALL_ACTIVITY_ID).count()).isZero();
        assertThat(runtimeService.createExecutionQuery().processInstanceId(processInstanceId)
                .activityId(ALL_ACTIVITY_ID).count()).isZero();
        assertThat(runtimeService.createExecutionQuery().processInstanceId(processInstanceId)
                .count()).isEqualTo(executionCountBefore);
        assertThat(runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.userCollectionName(ALL_ACTIVITY_ID))).isNull();
        assertThat(runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.memberSnapshotName(ALL_ACTIVITY_ID))).isNull();
        assertThat(runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.revisionName(ALL_ACTIVITY_ID))).isNull();
        assertThat(runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.modeName(ALL_ACTIVITY_ID))).isNull();
        assertThat(taskService.getProcessInstanceComments(processInstanceId)).isEmpty();
        HistoricTaskInstance sourceHistory = processEngine.getHistoryService()
                .createHistoricTaskInstanceQuery().taskId(sourceTask.getId()).singleResult();
        assertThat(sourceHistory).isNotNull();
        assertThat(sourceHistory.getEndTime()).isNull();
    }

    /**
     * 验证加签和减签真实改变 task/execution，revision、集合、历史和结构化 comment 同步收敛。
     *
     * @return 无返回值；任一增删 execution、正式快照、计数或审计不一致时测试失败
     */
    @Test
    void addAndRemovePersistRuntimeHistoryVariablesAndAudit()
    {
        String processInstanceId = startDynamicProcess(ALL_PROCESS_KEY, "allSource",
                ALL_ACTIVITY_ID, List.of(81L, 82L));
        Task currentTask = taskForAssignee(processInstanceId, ALL_ACTIVITY_ID, 81L);
        setSecurityContextUser(81L);

        WorkflowMultiInstanceStateView added = multiInstanceService.adjust(
                new WorkflowMultiInstanceAdjustmentRequest(currentTask.getId(),
                        WorkflowMultiInstanceAdjustmentAction.ADD, 0L, "真实加签",
                        List.of(83L), null));

        assertThat(added.revision()).isEqualTo(1L);
        assertThat(added.members()).extracting(member -> member.userId())
                .containsExactly(81L, 82L, 83L);
        assertExecutionTree(processInstanceId, ALL_ACTIVITY_ID, 3, 0);
        Task addedTask = taskForAssignee(processInstanceId, ALL_ACTIVITY_ID, 83L);
        assertAuditAction(processInstanceId, "MULTI_INSTANCE_ADD", 0, 1,
                List.of("83"), null, null);

        WorkflowMultiInstanceStateView removed = multiInstanceService.adjust(
                new WorkflowMultiInstanceAdjustmentRequest(currentTask.getId(),
                        WorkflowMultiInstanceAdjustmentAction.REMOVE, 1L, "真实减签",
                        List.of(), addedTask.getId()));

        assertThat(removed.revision()).isEqualTo(2L);
        assertThat(removed.members()).extracting(member -> member.userId())
                .containsExactly(81L, 82L);
        assertExecutionTree(processInstanceId, ALL_ACTIVITY_ID, 2, 0);
        assertThat(processEngine.getTaskService().createTaskQuery()
                .taskId(addedTask.getId()).count()).isZero();
        HistoricTaskInstance removedHistory = processEngine.getHistoryService()
                .createHistoricTaskInstanceQuery().taskId(addedTask.getId()).singleResult();
        assertThat(removedHistory).isNotNull();
        assertThat(removedHistory.getEndTime()).isNotNull();
        assertAuditAction(processInstanceId, "MULTI_INSTANCE_REMOVE", 1, 2,
                List.of(), addedTask.getId(), "83");
        assertThat(processEngine.getRuntimeService().getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.userCollectionName(ALL_ACTIVITY_ID)))
                .isEqualTo(List.of(81L, 82L));
    }

    /**
     * 验证低层适配器不能通过委派或转办改写动态多实例办理人，且两次拒绝均保持正式状态零变化。
     *
     * @return 无返回值；任一动作未返回 409，或 task、execution、revision、成员变量、comment 发生变化时测试失败
     */
    @Test
    void adapterRejectsDynamicDelegateAndTransferWithoutSideEffects()
    {
        String processInstanceId = startDynamicProcess(ALL_PROCESS_KEY, "allSource",
                ALL_ACTIVITY_ID, List.of(81L, 82L));
        Task currentTask = taskForAssignee(processInstanceId, ALL_ACTIVITY_ID, 81L);
        TaskService taskService = processEngine.getTaskService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        setSecurityContextUser(81L);

        // 基线覆盖当前任务、全部 sibling、完整 execution 树、正式多实例变量和已有审计，拒绝动作不得改变任一项。
        List<Task> activeTasksBefore = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).taskDefinitionKey(ALL_ACTIVITY_ID)
                .active().list();
        List<String> activeTaskIdsBefore = activeTasksBefore.stream()
                .map(Task::getId).toList();
        List<String> activeAssigneesBefore = activeTasksBefore.stream()
                .map(Task::getAssignee).toList();
        List<String> executionIdsBefore = runtimeService.createExecutionQuery()
                .processInstanceId(processInstanceId).list().stream()
                .map(Execution::getId).toList();
        List<Comment> commentsBefore = taskService.getProcessInstanceComments(
                processInstanceId);
        List<String> commentIdsBefore = commentsBefore.stream()
                .map(Comment::getId).toList();
        List<String> commentMessagesBefore = commentsBefore.stream()
                .map(Comment::getFullMessage).toList();
        Object revisionBefore = runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.revisionName(ALL_ACTIVITY_ID));
        Object memberSnapshotBefore = runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.memberSnapshotName(ALL_ACTIVITY_ID));
        Object userCollectionBefore = runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.userCollectionName(ALL_ACTIVITY_ID));

        // 每次 409 后都从真实引擎重新查询，避免只验证内存中的旧 Task 对象。
        Runnable assertUnchanged = () ->
        {
            Task unchangedTask = taskService.createTaskQuery().taskId(currentTask.getId())
                    .active().singleResult();
            assertThat(unchangedTask).isNotNull();
            assertThat(unchangedTask.getAssignee()).isEqualTo("81");
            assertThat(unchangedTask.getOwner()).isNull();
            assertThat(unchangedTask.getDelegationState()).isNull();
            List<Task> activeTasksAfter = taskService.createTaskQuery()
                    .processInstanceId(processInstanceId)
                    .taskDefinitionKey(ALL_ACTIVITY_ID).active().list();
            assertThat(activeTasksAfter).extracting(Task::getId)
                    .containsExactlyInAnyOrderElementsOf(activeTaskIdsBefore);
            assertThat(activeTasksAfter).extracting(Task::getAssignee)
                    .containsExactlyInAnyOrderElementsOf(activeAssigneesBefore);
            assertThat(runtimeService.createExecutionQuery()
                    .processInstanceId(processInstanceId).list())
                    .extracting(Execution::getId)
                    .containsExactlyInAnyOrderElementsOf(executionIdsBefore);
            assertExecutionTree(processInstanceId, ALL_ACTIVITY_ID, 2, 0);
            assertThat(runtimeService.getVariable(processInstanceId,
                    WorkflowMultiInstanceVariables.revisionName(ALL_ACTIVITY_ID)))
                    .isEqualTo(revisionBefore);
            assertThat(runtimeService.getVariable(processInstanceId,
                    WorkflowMultiInstanceVariables.memberSnapshotName(ALL_ACTIVITY_ID)))
                    .isEqualTo(memberSnapshotBefore);
            assertThat(runtimeService.getVariable(processInstanceId,
                    WorkflowMultiInstanceVariables.userCollectionName(ALL_ACTIVITY_ID)))
                    .isEqualTo(userCollectionBefore);
            List<Comment> commentsAfter = taskService.getProcessInstanceComments(
                    processInstanceId);
            assertThat(commentsAfter).extracting(Comment::getId)
                    .containsExactlyInAnyOrderElementsOf(commentIdsBefore);
            assertThat(commentsAfter).extracting(Comment::getFullMessage)
                    .containsExactlyInAnyOrderElementsOf(commentMessagesBefore);
        };

        assertThatThrownBy(() -> processEngineAdapter.delegateTaskForCurrentUser(
                currentTask.getId(), "84", "动态多实例禁止低层委派"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT));
        assertUnchanged.run();

        assertThatThrownBy(() -> processEngineAdapter.transferTaskForCurrentUser(
                currentTask.getId(), "84", "动态多实例禁止低层转办"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT));
        assertUnchanged.run();
    }

    /**
     * 验证同 revision 的 ADD、REMOVE 和 COMPLETE 三方竞态只有一个事务提交，其余稳定返回 409 且无 comment 半写。
     *
     * @return 无返回值；出现多成功、非冲突失败、快照漂移或重复审计时测试失败
     * @throws Exception 并发线程无法在门禁时间内返回时测试失败
     */
    @Test
    void sameRevisionAddRemoveAndCompleteRaceCommitsOnlyOneOutcome() throws Exception
    {
        String processInstanceId = startDynamicProcess(ALL_PROCESS_KEY, "allSource",
                ALL_ACTIVITY_ID, List.of(81L, 82L));
        Task currentTask = taskForAssignee(processInstanceId, ALL_ACTIVITY_ID, 81L);
        Task targetTask = taskForAssignee(processInstanceId, ALL_ACTIVITY_ID, 82L);
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<Future<ConcurrentAttempt>> futures = new ArrayList<>();
        try
        {
            futures.add(executor.submit(() -> runConcurrentAttempt("ADD", 81L, ready, start,
                    () -> multiInstanceService.adjust(
                            new WorkflowMultiInstanceAdjustmentRequest(currentTask.getId(),
                                    WorkflowMultiInstanceAdjustmentAction.ADD, 0L,
                                    "并发加签", List.of(83L), null)))));
            futures.add(executor.submit(() -> runConcurrentAttempt("REMOVE", 81L, ready, start,
                    () -> multiInstanceService.adjust(
                            new WorkflowMultiInstanceAdjustmentRequest(currentTask.getId(),
                                    WorkflowMultiInstanceAdjustmentAction.REMOVE, 0L,
                                    "并发减签", List.of(), targetTask.getId())))));
            futures.add(executor.submit(() -> runConcurrentAttempt("COMPLETE", 81L, ready, start,
                    () -> taskLifecycleService.completeTask(
                            new WorkflowTaskCompleteRequest(currentTask.getId(),
                                    "并发完成", Map.of(), List.of(), List.of(), 0L)))));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ConcurrentAttempt> attempts = new ArrayList<>();
            for (Future<ConcurrentAttempt> future : futures)
            {
                attempts.add(future.get(CONCURRENT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            }
            assertThat(attempts).filteredOn(ConcurrentAttempt::successful).hasSize(1);
            assertThat(attempts).filteredOn(attempt -> !attempt.successful())
                    .allSatisfy(attempt ->
                    {
                        assertThat(attempt.error()).isNull();
                        assertThat(attempt.statusCode()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(attempt.subCode()).isEqualTo(
                                WorkflowMultiInstanceService.REVISION_CONFLICT_SUB_CODE);
                    });

            ConcurrentAttempt winner = attempts.stream().filter(ConcurrentAttempt::successful)
                    .findFirst().orElseThrow();
            assertRaceWinnerState(processInstanceId, winner.action());
            List<Comment> currentTaskComments = processEngine.getTaskService()
                    .getProcessInstanceComments(processInstanceId).stream()
                    .filter(comment -> currentTask.getId().equals(comment.getTaskId()))
                    .toList();
            assertThat(currentTaskComments).as("失败竞态不得遗留 comment 半写").hasSize(1);
        }
        finally
        {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * 验证两个 sibling 使用同一 revision 并发完成时只有一个提交，失败方刷新后可继续完成。
     *
     * @return 无返回值；双完成、revision 漂移、重试失败或历史审计缺失时测试失败
     * @throws Exception 并发线程无法在门禁时间内返回时测试失败
     */
    @Test
    void sameRevisionSiblingCompletesCommitOnceAndRefreshAllowsRetry() throws Exception
    {
        String processInstanceId = startDynamicProcess(ALL_PROCESS_KEY, "allSource",
                ALL_ACTIVITY_ID, List.of(81L, 82L));
        Task firstTask = taskForAssignee(processInstanceId, ALL_ACTIVITY_ID, 81L);
        Task secondTask = taskForAssignee(processInstanceId, ALL_ACTIVITY_ID, 82L);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try
        {
            Future<ConcurrentAttempt> first = executor.submit(() -> runConcurrentAttempt(
                    "COMPLETE_81", 81L, ready, start,
                    () -> taskLifecycleService.completeTask(new WorkflowTaskCompleteRequest(
                            firstTask.getId(), "并发完成81", Map.of(), List.of(), List.of(), 0L))));
            Future<ConcurrentAttempt> second = executor.submit(() -> runConcurrentAttempt(
                    "COMPLETE_82", 82L, ready, start,
                    () -> taskLifecycleService.completeTask(new WorkflowTaskCompleteRequest(
                            secondTask.getId(), "并发完成82", Map.of(), List.of(), List.of(), 0L))));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ConcurrentAttempt> attempts = List.of(
                    first.get(CONCURRENT_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                    second.get(CONCURRENT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            assertThat(attempts).filteredOn(ConcurrentAttempt::successful).hasSize(1);
            assertThat(attempts).filteredOn(attempt -> !attempt.successful())
                    .singleElement().satisfies(attempt ->
                    {
                        assertThat(attempt.error()).isNull();
                        assertThat(attempt.statusCode()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(attempt.subCode()).isEqualTo(
                                WorkflowMultiInstanceService.REVISION_CONFLICT_SUB_CODE);
                    });
            assertExecutionTree(processInstanceId, ALL_ACTIVITY_ID, 1, 1);
            assertThat(processEngine.getRuntimeService().getVariable(processInstanceId,
                    WorkflowMultiInstanceVariables.revisionName(ALL_ACTIVITY_ID))).isEqualTo(1);

            Task remainingTask = processEngine.getTaskService().createTaskQuery()
                    .processInstanceId(processInstanceId).taskDefinitionKey(ALL_ACTIVITY_ID)
                    .active().singleResult();
            assertThat(remainingTask).isNotNull();
            long remainingUserId = Long.parseLong(remainingTask.getAssignee());
            setSecurityContextUser(remainingUserId);
            WorkflowMultiInstanceStateView refreshed = multiInstanceService.getState(
                    remainingTask.getId());
            assertThat(refreshed.revision()).isEqualTo(1);
            taskLifecycleService.completeTask(new WorkflowTaskCompleteRequest(
                    remainingTask.getId(), "刷新后完成", Map.of(), List.of(), List.of(),
                    (long) refreshed.revision()));

            assertNaturallyCompleted(processInstanceId);
            assertThat(historicVariable(processInstanceId,
                    WorkflowMultiInstanceVariables.revisionName(ALL_ACTIVITY_ID))).isEqualTo(2);
            assertCompletionAuditRevisions(processInstanceId, ALL_ACTIVITY_ID, List.of(1, 2));
        }
        finally
        {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * 验证动态 ADD/REMOVE 分别与取消、驳回和管理员终止竞争时只能提交一个正式业务结果。
     *
     * @return 无返回值；双提交、失败方非 409、终态或成员快照漂移、额外审计副作用出现时测试失败
     * @throws Exception 真实 MySQL 行锁、并发线程或结果等待失败时传播给 JUnit
     */
    @Test
    void adjustmentRacesWithCancelRejectAndTerminateCommitOneOutcome() throws Exception
    {
        assertAdjustmentAgainstTerminalRace(
                WorkflowMultiInstanceAdjustmentAction.ADD, TerminalRaceAction.CANCEL);
        assertAdjustmentAgainstTerminalRace(
                WorkflowMultiInstanceAdjustmentAction.REMOVE, TerminalRaceAction.REJECT);
        assertAdjustmentAgainstTerminalRace(
                WorkflowMultiInstanceAdjustmentAction.ADD, TerminalRaceAction.TERMINATE);
    }

    /**
     * 构造一组动态调整与整实例终态命令的真实数据库竞争，并对账唯一成功事务的全部持久化结果。
     *
     * @param adjustmentAction WorkflowMultiInstanceAdjustmentAction，参与竞争的 ADD 或 REMOVE
     * @param terminalAction TerminalRaceAction，参与竞争的取消、驳回或管理员终止
     * @return 无返回值；并发裁决、运行/历史状态、revision、成员或 comment 不一致时测试失败
     * @throws Exception 行锁、线程协调或结果等待失败时传播给 JUnit
     */
    private void assertAdjustmentAgainstTerminalRace(
            WorkflowMultiInstanceAdjustmentAction adjustmentAction,
            TerminalRaceAction terminalAction) throws Exception
    {
        String processInstanceId = startDynamicProcess(ALL_PROCESS_KEY, "allSource",
                ALL_ACTIVITY_ID, List.of(81L, 82L));
        Task currentTask = taskForAssignee(processInstanceId, ALL_ACTIVITY_ID, 81L);
        Task targetTask = taskForAssignee(processInstanceId, ALL_ACTIVITY_ID, 82L);
        int commentCountBefore = processEngine.getTaskService()
                .getProcessInstanceComments(processInstanceId).size();

        Runnable adjustmentCommand = adjustmentAction
                == WorkflowMultiInstanceAdjustmentAction.ADD
                ? () -> multiInstanceService.adjust(
                        new WorkflowMultiInstanceAdjustmentRequest(currentTask.getId(),
                                WorkflowMultiInstanceAdjustmentAction.ADD, 0L,
                                "终态竞态加签", List.of(83L), null))
                : () -> multiInstanceService.adjust(
                        new WorkflowMultiInstanceAdjustmentRequest(currentTask.getId(),
                                WorkflowMultiInstanceAdjustmentAction.REMOVE, 0L,
                                "终态竞态减签", List.of(), targetTask.getId()));

        long terminalActorUserId;
        Set<String> terminalPermissions;
        Runnable terminalCommand;
        if (terminalAction == TerminalRaceAction.CANCEL)
        {
            terminalActorUserId = 81L;
            terminalPermissions = APPROVAL_PERMISSIONS;
            terminalCommand = () -> taskLifecycleService.cancelProcess(
                    new WorkflowProcessCancelRequest(processInstanceId, "多实例竞态取消"));
        }
        else if (terminalAction == TerminalRaceAction.REJECT)
        {
            terminalActorUserId = 81L;
            terminalPermissions = APPROVAL_PERMISSIONS;
            terminalCommand = () -> taskLifecycleService.rejectTask(
                    new WorkflowTaskRejectRequest(currentTask.getId(), "多实例竞态驳回"));
        }
        else
        {
            // 终止动作使用真实超级管理员；81 号审批人的普通角色不应临时扩权。
            terminalActorUserId = 1L;
            terminalPermissions = Set.of("workflow:process:approval",
                    "workflow:process:terminate");
            terminalCommand = () -> processInstanceService.terminate(
                    new WorkflowInstanceTerminateRequest(processInstanceId,
                            "多实例竞态管理员终止"));
        }

        List<ConcurrentAttempt> attempts = executeAdjustmentTerminalRace(
                processInstanceId, currentTask.getId(), adjustmentAction.name(),
                adjustmentCommand, terminalAction.name(), terminalActorUserId,
                terminalPermissions, terminalCommand);
        assertThat(attempts).filteredOn(ConcurrentAttempt::successful).hasSize(1);
        assertThat(attempts).filteredOn(attempt -> !attempt.successful())
                .singleElement().satisfies(attempt ->
                {
                    assertThat(attempt.error()).isNull();
                    assertThat(attempt.statusCode()).isEqualTo(HttpStatus.CONFLICT);
                    if (attempt.action().equals(adjustmentAction.name()))
                    {
                        // 终态命令失败仍沿用自身 409；只有动态调整输掉 revision 竞争时要求专用子码。
                        assertThat(attempt.subCode()).isEqualTo(
                                WorkflowMultiInstanceService.REVISION_CONFLICT_SUB_CODE);
                    }
                });

        String winningAction = attempts.stream()
                .filter(ConcurrentAttempt::successful)
                .findFirst().orElseThrow().action();
        assertAdjustmentTerminalWinnerState(processInstanceId, adjustmentAction,
                terminalAction, winningAction, commentCountBefore,
                currentTask.getId(), targetTask.getId());
    }

    /**
     * 锁定根 execution、当前任务和多实例 revision 变量，再同时释放动态调整与终态命令。
     *
     * @param processInstanceId String，两个命令共同操作的流程实例主键
     * @param taskId String，动态调整和驳回共同引用的当前任务主键
     * @param adjustmentName String，ADD 或 REMOVE 结果标签
     * @param adjustmentCommand Runnable，调用真实动态多实例领域服务的命令
     * @param terminalName String，CANCEL、REJECT 或 TERMINATE 结果标签
     * @param terminalActorUserId long，执行终态命令的正式用户主键
     * @param terminalPermissions Set&lt;String&gt;，终态命令线程持有的正式权限
     * @param terminalCommand Runnable，调用真实终态领域服务的命令
     * @return List&lt;ConcurrentAttempt&gt;，按动态调整、终态命令顺序保存两个事务结果
     * @throws Exception JDBC 行锁、线程协调或结果等待失败时传播给 JUnit
     */
    private List<ConcurrentAttempt> executeAdjustmentTerminalRace(
            String processInstanceId, String taskId, String adjustmentName,
            Runnable adjustmentCommand, String terminalName,
            long terminalActorUserId, Set<String> terminalPermissions,
            Runnable terminalCommand) throws Exception
    {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (Connection lockConnection = dataSource.getConnection())
        {
            lockConnection.setAutoCommit(false);
            boolean lockReleased = false;
            try
            {
                // 三项锁覆盖终态删除和动态 revision-first 写路径，保证双方完成预检后再由 InnoDB 裁决。
                lockSingleRuntimeRow(lockConnection,
                        "select ID_ from ACT_RU_EXECUTION "
                                + "where ID_ = ? and PROC_INST_ID_ = ? for update",
                        List.of(processInstanceId, processInstanceId));
                lockSingleRuntimeRow(lockConnection,
                        "select ID_ from ACT_RU_TASK "
                                + "where ID_ = ? and PROC_INST_ID_ = ? for update",
                        List.of(taskId, processInstanceId));
                lockSingleRuntimeRow(lockConnection,
                        "select ID_ from ACT_RU_VARIABLE "
                                + "where PROC_INST_ID_ = ? and NAME_ = ? for update",
                        List.of(processInstanceId,
                                WorkflowMultiInstanceVariables.revisionName(
                                        ALL_ACTIVITY_ID)));

                Future<ConcurrentAttempt> adjustmentFuture = executor.submit(
                        () -> runConcurrentAttempt(adjustmentName, 81L,
                                APPROVAL_PERMISSIONS, ready, start,
                                adjustmentCommand));
                Future<ConcurrentAttempt> terminalFuture = executor.submit(
                        () -> runConcurrentAttempt(terminalName, terminalActorUserId,
                                terminalPermissions, ready, start, terminalCommand));
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                Thread.sleep(300L);
                assertConcurrentCommandBlocked(adjustmentFuture,
                        "动态调整必须阻塞在真实 MySQL 行锁后");
                assertConcurrentCommandBlocked(terminalFuture,
                        "整实例终态命令必须阻塞在真实 MySQL 行锁后");

                lockConnection.commit();
                lockReleased = true;
                return List.of(
                        adjustmentFuture.get(CONCURRENT_TIMEOUT.toSeconds(),
                                TimeUnit.SECONDS),
                        terminalFuture.get(CONCURRENT_TIMEOUT.toSeconds(),
                                TimeUnit.SECONDS));
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
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * 断言并发命令仍被真实数据库行锁阻塞，并在提前完成时输出稳定业务结果。
     *
     * @param future Future&lt;ConcurrentAttempt&gt;，正在执行的并发命令结果
     * @param message String，断言失败时对应的业务场景说明
     * @return 无返回值；命令提前成功或失败时抛出包含结果的断言异常
     * @throws Exception 提前完成结果无法读取时传播给 JUnit
     */
    private void assertConcurrentCommandBlocked(Future<ConcurrentAttempt> future,
            String message) throws Exception
    {
        if (!future.isDone())
        {
            return;
        }
        ConcurrentAttempt earlyAttempt = future.get(1, TimeUnit.SECONDS);
        assertThat(future.isDone()).as(message + "，提前结果=" + earlyAttempt).isFalse();
    }

    /**
     * 通过参数化预编译语句锁定唯一运行时行，避免测试 SQL 拼接业务主键。
     *
     * @param connection Connection，关闭自动提交的真实 MySQL 锁连接
     * @param sql String，只返回 ID_ 的单行 SELECT ... FOR UPDATE 语句
     * @param parameters List&lt;String&gt;，按占位符顺序绑定的流程、任务或变量标识
     * @return 无返回值；未命中唯一行或命中重复行时测试失败
     * @throws SQLException 预编译、绑定或查询失败时传播给 JUnit
     */
    private void lockSingleRuntimeRow(Connection connection, String sql,
            List<String> parameters) throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement(sql))
        {
            for (int index = 0; index < parameters.size(); index++)
            {
                statement.setString(index + 1, parameters.get(index));
            }
            try (var resultSet = statement.executeQuery())
            {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isNotBlank();
                assertThat(resultSet.next()).isFalse();
            }
        }
    }

    /**
     * 按唯一胜方核对运行/历史终态、revision、成员快照和新增 comment 严格一致。
     *
     * @param processInstanceId String，参与竞态的流程实例主键
     * @param adjustmentAction WorkflowMultiInstanceAdjustmentAction，ADD 或 REMOVE
     * @param terminalAction TerminalRaceAction，取消、驳回或管理员终止
     * @param winningAction String，唯一成功事务的动作标签
     * @param commentCountBefore int，竞争前已经存在的来源任务审计数量
     * @param currentTaskId String，当前办理人的活动多实例任务主键
     * @param siblingTaskId String，另一名成员的活动 sibling 任务主键
     * @return 无返回值；失败事务遗留变量、任务、终态或审计副作用时测试失败
     */
    private void assertAdjustmentTerminalWinnerState(String processInstanceId,
            WorkflowMultiInstanceAdjustmentAction adjustmentAction,
            TerminalRaceAction terminalAction, String winningAction,
            int commentCountBefore, String currentTaskId, String siblingTaskId)
    {
        RuntimeService runtimeService = processEngine.getRuntimeService();
        HistoryService historyService = processEngine.getHistoryService();
        List<Comment> comments = processEngine.getTaskService()
                .getProcessInstanceComments(processInstanceId);
        List<JsonNode> audits = comments.stream()
                .map(this::readAuditPayload).toList();

        if (winningAction.equals(terminalAction.name()))
        {
            int expectedTerminalComments = terminalAction == TerminalRaceAction.TERMINATE
                    ? 1 : 2;
            assertThat(comments).hasSize(commentCountBefore + expectedTerminalComments);
            String expectedStatus = terminalAction == TerminalRaceAction.CANCEL
                    ? "canceled" : terminalAction == TerminalRaceAction.REJECT
                            ? "rejected" : "terminated";
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceId(processInstanceId).count()).isZero();
            var historic = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(processInstanceId).singleResult();
            assertThat(historic).isNotNull();
            assertThat(historic.getEndTime()).isNotNull();
            assertThat(historic.getBusinessStatus()).isEqualTo(expectedStatus);
            assertThat(historicVariable(processInstanceId,
                    WorkflowProcessStartService.PROCESS_STATUS_VARIABLE))
                    .isEqualTo(expectedStatus);
            assertThat(historicVariable(processInstanceId,
                    WorkflowMultiInstanceVariables.revisionName(ALL_ACTIVITY_ID)))
                    .isEqualTo(0);
            assertThat(historicVariable(processInstanceId,
                    WorkflowMultiInstanceVariables.memberSnapshotName(
                            ALL_ACTIVITY_ID)))
                    .isEqualTo(List.of("81", "82"));
            List<Comment> terminalComments = comments.stream()
                    .filter(comment -> terminalAction.name().equals(
                            readAuditPayload(comment).path("action").asText()))
                    .toList();
            assertThat(terminalComments).hasSize(expectedTerminalComments);
            if (terminalAction == TerminalRaceAction.TERMINATE)
            {
                assertThat(terminalComments).extracting(Comment::getTaskId)
                        .containsExactly((String) null);
            }
            else
            {
                // 取消和驳回按活动任务逐条落审计，两个 sibling 必须各有且仅有一条。
                assertThat(terminalComments).extracting(Comment::getTaskId)
                        .containsExactlyInAnyOrder(currentTaskId, siblingTaskId);
            }
            assertThat(audits).noneMatch(audit -> audit.path("action").asText()
                    .startsWith("MULTI_INSTANCE_"));
            return;
        }

        assertThat(comments).hasSize(commentCountBefore + 1);
        assertThat(winningAction).isEqualTo(adjustmentAction.name());
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).count()).isOne();
        var historic = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        assertThat(historic).isNotNull();
        assertThat(historic.getEndTime()).isNull();
        assertThat(runtimeService.getVariable(processInstanceId,
                WorkflowProcessStartService.PROCESS_STATUS_VARIABLE))
                .isEqualTo("running");
        assertThat(runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.revisionName(ALL_ACTIVITY_ID)))
                .isEqualTo(1);
        List<String> expectedMembers = adjustmentAction
                == WorkflowMultiInstanceAdjustmentAction.ADD
                ? List.of("81", "82", "83") : List.of("81");
        assertThat(runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.memberSnapshotName(ALL_ACTIVITY_ID)))
                .isEqualTo(expectedMembers);
        assertThat(processEngine.getTaskService().createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(ALL_ACTIVITY_ID).active().list())
                .extracting(Task::getAssignee)
                .containsExactlyInAnyOrderElementsOf(expectedMembers);
        assertThat(audits).filteredOn(audit ->
                ("MULTI_INSTANCE_" + adjustmentAction.name()).equals(
                        audit.path("action").asText())).singleElement();
        assertThat(audits).noneMatch(audit -> terminalAction.name().equals(
                audit.path("action").asText()));
    }

    /**
     * 启动指定身份流程，确认后端实时展开结果已生成真实 assignee 且没有候选身份链接。
     *
     * @param processKey String，指定角色或部门流程定义 key
     * @param activityId String，指定身份多实例活动 ID
     * @param expectedMode String，期望持久化的 ALL 或 ANY 模式
     * @param expectedMemberIds List&lt;Long&gt;，实时 RBAC 应展开的完整办理人主键
     * @return String，已进入指定身份多实例节点的流程实例主键
     */
    private String startConfiguredProcess(String processKey, String activityId,
            String expectedMode, List<Long> expectedMemberIds)
    {
        String processInstanceId = startProcess(processKey);
        List<String> expectedAssignees = expectedMemberIds.stream()
                .map(String::valueOf).toList();
        assertExecutionTree(processInstanceId, activityId, expectedMemberIds.size(), 0);
        assertAssigneeTasksWithoutCandidates(processInstanceId, activityId,
                expectedAssignees);
        RuntimeService runtimeService = processEngine.getRuntimeService();
        assertThat(runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.memberSnapshotName(activityId)))
                .isEqualTo(expectedAssignees);
        assertThat(runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.revisionName(activityId))).isEqualTo(0);
        assertThat(runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.modeName(activityId))).isEqualTo(expectedMode);
        return processInstanceId;
    }

    /**
     * 启动指定模式流程，并从来源任务通过 nextUserIds 进入真实动态多实例节点。
     *
     * @param processKey String，ALL 或 ANY 流程定义 key
     * @param sourceActivityId String，来源普通用户任务活动 ID
     * @param targetActivityId String，动态多实例目标活动 ID
     * @param memberIds List&lt;Long&gt;，正式有效成员主键
     * @return String，已进入动态多实例节点的流程实例主键
     */
    private String startDynamicProcess(String processKey, String sourceActivityId,
            String targetActivityId, List<Long> memberIds)
    {
        String processInstanceId = startSourceProcess(processKey, sourceActivityId);
        Task sourceTask = processEngine.getTaskService().createTaskQuery()
                .processInstanceId(processInstanceId).taskDefinitionKey(sourceActivityId)
                .singleResult();
        assertThat(sourceTask).isNotNull();
        setSecurityContextUser(81L);
        taskLifecycleService.completeTask(new WorkflowTaskCompleteRequest(sourceTask.getId(),
                "进入动态多实例", Map.of(), List.of(), memberIds));

        List<Task> dynamicTasks = processEngine.getTaskService().createTaskQuery()
                .processInstanceId(processInstanceId).taskDefinitionKey(targetActivityId)
                .active().list();
        assertThat(dynamicTasks).hasSize(memberIds.size());
        assertThat(dynamicTasks).extracting(Task::getAssignee)
                .containsExactlyInAnyOrderElementsOf(
                        memberIds.stream().map(String::valueOf).toList());
        return processInstanceId;
    }

    /**
     * 启动流程并停留在动态多实例之前的唯一来源任务，供失败回滚场景复用。
     *
     * @param processKey String，ALL 或 ANY 流程定义 key
     * @param sourceActivityId String，来源普通用户任务活动 ID
     * @return String，仍停留在来源任务的运行流程实例主键
     */
    private String startSourceProcess(String processKey, String sourceActivityId)
    {
        String processInstanceId = startProcess(processKey);
        Task sourceTask = processEngine.getTaskService().createTaskQuery()
                .processInstanceId(processInstanceId).taskDefinitionKey(sourceActivityId)
                .active().singleResult();
        assertThat(sourceTask).isNotNull();
        assertThat(sourceTask.getAssignee()).isEqualTo("81");
        return processInstanceId;
    }

    /**
     * 以预置发起人和正式运行状态变量启动一个独立业务实例。
     *
     * @param processKey String，已部署的流程定义 key
     * @return String，真实 Flowable 流程实例主键
     */
    private String startProcess(String processKey)
    {
        String businessKey = BUSINESS_KEY_PREFIX + UUID.randomUUID();
        ProcessInstance instance;
        // 显式写入真实发起人，后续授权和历史必须复用与生产发起链一致的 startUserId 事实。
        processEngine.getIdentityService().setAuthenticatedUserId("81");
        try
        {
            instance = processEngine.getRuntimeService()
                    .startProcessInstanceByKey(processKey, businessKey,
                            Map.of(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE,
                                    "running"));
        }
        finally
        {
            processEngine.getIdentityService().setAuthenticatedUserId(null);
        }
        return instance.getId();
    }

    /**
     * 使用指定真实 assignee 通过正式生命周期服务完成一个活动多实例任务。
     *
     * @param processInstanceId String，目标流程实例主键
     * @param activityId String，动态多实例活动 ID
     * @param userId long，当前活动成员主键
     * @return 无返回值；任务不存在、越权或完成失败时测试失败
     */
    private void completeMember(String processInstanceId, String activityId, long userId)
    {
        Task task = taskForAssignee(processInstanceId, activityId, userId);
        setSecurityContextUser(userId);
        WorkflowMultiInstanceStateView state = multiInstanceService.getState(task.getId());
        taskLifecycleService.completeTask(new WorkflowTaskCompleteRequest(task.getId(),
                "完成动态多实例任务", Map.of(), List.of(), List.of(),
                (long) state.revision()));
    }

    /**
     * 查询指定流程、节点和办理人的唯一活动任务。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，用户任务活动 ID
     * @param userId long，任务办理人主键
     * @return Task，唯一活动任务
     */
    private Task taskForAssignee(String processInstanceId, String activityId, long userId)
    {
        Task task = processEngine.getTaskService().createTaskQuery()
                .processInstanceId(processInstanceId).taskDefinitionKey(activityId)
                .taskAssignee(String.valueOf(userId)).active().singleResult();
        assertThat(task).as("预期办理人的动态任务必须唯一存在").isNotNull();
        return task;
    }

    /**
     * 对账指定节点的活动任务均为真实 assignee，且不存在 candidateUser/candidateGroup 身份链接。
     *
     * @param processInstanceId String，目标流程实例主键
     * @param activityId String，指定身份多实例活动 ID
     * @param expectedAssignees List&lt;String&gt;，期望的活动办理人主键
     * @return 无返回值；任务数、办理人或候选身份链接不一致时测试失败
     */
    private void assertAssigneeTasksWithoutCandidates(String processInstanceId,
            String activityId, List<String> expectedAssignees)
    {
        TaskService taskService = processEngine.getTaskService();
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).taskDefinitionKey(activityId)
                .active().list();
        assertThat(tasks).hasSize(expectedAssignees.size());
        assertThat(tasks).extracting(Task::getAssignee)
                .containsExactlyInAnyOrderElementsOf(expectedAssignees);
        assertThat(tasks).allSatisfy(task ->
                assertThat(taskService.getIdentityLinksForTask(task.getId()))
                        .noneMatch(link -> "candidate".equals(link.getType())));
    }

    /**
     * 对账活动任务、直接子 execution 与多实例根三项本地计数。
     *
     * @param processInstanceId String，活动流程实例主键
     * @param activityId String，动态多实例活动 ID
     * @param expectedActive int，预期活动成员数
     * @param expectedCompleted int，预期已完成成员数
     * @return 无返回值；执行树或计数不一致时测试失败
     */
    private void assertExecutionTree(String processInstanceId, String activityId,
            int expectedActive, int expectedCompleted)
    {
        TaskService taskService = processEngine.getTaskService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstanceId)
                .taskDefinitionKey(activityId).active().list();
        assertThat(tasks).hasSize(expectedActive);
        LinkedHashSet<String> rootIds = new LinkedHashSet<>();
        LinkedHashSet<String> taskExecutionIds = new LinkedHashSet<>();
        for (Task task : tasks)
        {
            Execution execution = runtimeService.createExecutionQuery()
                    .executionId(task.getExecutionId()).singleResult();
            assertThat(execution).isNotNull();
            assertThat(execution.getActivityId()).isEqualTo(activityId);
            rootIds.add(execution.getParentId());
            taskExecutionIds.add(execution.getId());
        }
        assertThat(rootIds).singleElement().satisfies(rootId ->
                assertThat(rootId).isNotBlank());
        String rootId = rootIds.iterator().next();
        Execution root = runtimeService.createExecutionQuery().executionId(rootId)
                .singleResult();
        assertThat(root).isNotNull();
        assertThat(root.getProcessInstanceId()).isEqualTo(processInstanceId);
        assertThat(root.getActivityId()).isEqualTo(activityId);
        List<Execution> childExecutions = runtimeService.createExecutionQuery()
                .parentId(rootId).list();
        assertThat(childExecutions).hasSize(expectedActive + expectedCompleted);
        LinkedHashSet<String> activeChildIds = new LinkedHashSet<>();
        int inactiveChildCount = 0;
        for (Execution childExecution : childExecutions)
        {
            assertThat(childExecution.getParentId()).isEqualTo(rootId);
            assertThat(childExecution.getProcessInstanceId()).isEqualTo(processInstanceId);
            assertThat(childExecution.getActivityId()).isEqualTo(activityId);
            List<String> activeActivityIds = runtimeService.getActiveActivityIds(
                    childExecution.getId());
            if (activeActivityIds.isEmpty())
            {
                inactiveChildCount++;
            }
            else
            {
                assertThat(activeActivityIds).containsExactly(activityId);
                activeChildIds.add(childExecution.getId());
            }
        }
        assertThat(activeChildIds).containsExactlyInAnyOrderElementsOf(taskExecutionIds);
        assertThat(inactiveChildCount).isEqualTo(expectedCompleted);
        assertThat(runtimeService.getVariableLocal(rootId, "nrOfInstances"))
                .isEqualTo(expectedActive + expectedCompleted);
        assertThat(runtimeService.getVariableLocal(rootId, "nrOfActiveInstances"))
                .isEqualTo(expectedActive);
        assertThat(runtimeService.getVariableLocal(rootId, "nrOfCompletedInstances"))
                .isEqualTo(expectedCompleted);
    }

    /**
     * 断言流程已无 runtime 实例，历史结束时间和自然 completed 状态均真实持久化。
     *
     * @param processInstanceId String，预期自然完成的流程实例主键
     * @return 无返回值；运行残留、历史缺失或终态错误时测试失败
     */
    private void assertNaturallyCompleted(String processInstanceId)
    {
        assertThat(processEngine.getRuntimeService().createProcessInstanceQuery()
                .processInstanceId(processInstanceId).count()).isZero();
        var historic = processEngine.getHistoryService().createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        assertThat(historic).isNotNull();
        assertThat(historic.getEndTime()).isNotNull();
        assertThat(historicVariable(processInstanceId,
                WorkflowProcessStartService.PROCESS_STATUS_VARIABLE)).isEqualTo("completed");
    }

    /**
     * 查询指定动态节点的全部历史任务。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，动态多实例活动 ID
     * @return List&lt;HistoricTaskInstance&gt;，按开始时间升序排列的历史任务
     */
    private List<HistoricTaskInstance> historicTasks(String processInstanceId,
            String activityId)
    {
        return processEngine.getHistoryService().createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId).taskDefinitionKey(activityId)
                .orderByHistoricTaskInstanceStartTime().asc().list();
    }

    /**
     * 从真实 ACT_HI_VARINST 读取唯一流程变量值。
     *
     * @param processInstanceId String，历史流程实例主键
     * @param variableName String，需要读取的正式变量名
     * @return Object，Flowable 反序列化后的历史变量值
     */
    private Object historicVariable(String processInstanceId, String variableName)
    {
        var variable = processEngine.getHistoryService().createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId).variableName(variableName).singleResult();
        assertThat(variable).as("历史变量必须唯一存在: " + variableName).isNotNull();
        return variable.getValue();
    }

    /**
     * 定位唯一结构化调整 comment 并核对前后 revision 与目标字段。
     *
     * @param processInstanceId String，流程实例主键
     * @param action String，固定调整动作编码
     * @param beforeRevision int，动作前 revision
     * @param afterRevision int，动作后 revision
     * @param targetUserIds List&lt;String&gt;，ADD 目标用户集合
     * @param targetTaskId String，REMOVE 目标任务；ADD 时为 null
     * @param targetUserId String，REMOVE 目标办理人；ADD 时为 null
     * @return 无返回值；审计缺失、重复或字段不一致时测试失败
     */
    private void assertAuditAction(String processInstanceId, String action,
            int beforeRevision, int afterRevision, List<String> targetUserIds,
            String targetTaskId, String targetUserId)
    {
        List<JsonNode> matching = processEngine.getTaskService()
                .getProcessInstanceComments(processInstanceId).stream()
                .map(this::readAuditPayload)
                .filter(audit -> action.equals(audit.path("action").asText()))
                .toList();
        assertThat(matching).singleElement().satisfies(audit ->
        {
            assertThat(audit.path("actorUserId").asText()).isEqualTo("81");
            assertThat(audit.path("beforeRevision").asInt()).isEqualTo(beforeRevision);
            assertThat(audit.path("afterRevision").asInt()).isEqualTo(afterRevision);
            if (!targetUserIds.isEmpty())
            {
                assertThat(audit.path("targetUserIds")).extracting(JsonNode::asText)
                        .containsExactlyElementsOf(targetUserIds);
            }
            if (targetTaskId != null)
            {
                assertThat(audit.path("targetTaskId").asText()).isEqualTo(targetTaskId);
                assertThat(audit.path("targetUserId").asText()).isEqualTo(targetUserId);
            }
        });
    }

    /**
     * 核对动态完成审计严格记录活动 ID 和连续递增的前后 revision。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，动态多实例活动 ID
     * @param expectedAfterRevisions List&lt;Integer&gt;，按业务完成次数期望的 afterRevision 集合
     * @return 无返回值；审计缺失、重复或版本区间不连续时测试失败
     */
    private void assertCompletionAuditRevisions(String processInstanceId, String activityId,
            List<Integer> expectedAfterRevisions)
    {
        List<JsonNode> completionAudits = processEngine.getTaskService()
                .getProcessInstanceComments(processInstanceId).stream()
                .map(this::readAuditPayload)
                .filter(audit -> "COMPLETE".equals(audit.path("action").asText()))
                .filter(audit -> activityId.equals(
                        audit.path("multiInstanceActivityId").asText()))
                .toList();
        assertThat(completionAudits).hasSize(expectedAfterRevisions.size());
        assertThat(completionAudits).allSatisfy(audit ->
                assertThat(audit.path("afterRevision").asInt())
                        .isEqualTo(audit.path("beforeRevision").asInt() + 1));
        assertThat(completionAudits).extracting(audit ->
                audit.path("afterRevision").asInt())
                .containsExactlyInAnyOrderElementsOf(expectedAfterRevisions);
    }

    /**
     * 将 Flowable comment 正文解析为结构化审计 JSON。
     *
     * @param comment Comment，真实引擎 comment
     * @return JsonNode，解析后的审计对象
     */
    private JsonNode readAuditPayload(Comment comment)
    {
        try
        {
            return AUDIT_MAPPER.readTree(comment.getFullMessage());
        }
        catch (Exception exception)
        {
            throw new AssertionError("Flowable comment 不是合法结构化审计 JSON", exception);
        }
    }

    /**
     * 在线程内建立真实 SecurityContext，通过同步屏障同时执行一个并发动作并保存稳定结果。
     *
     * @param action String，ADD、REMOVE 或 COMPLETE
     * @param ready CountDownLatch，三个线程就绪屏障
     * @param start CountDownLatch，统一放行屏障
     * @param command Runnable，待执行领域命令
     * @return ConcurrentAttempt，成功、HTTP 冲突或非预期异常结果
     */
    private ConcurrentAttempt runConcurrentAttempt(String action, long userId,
            CountDownLatch ready, CountDownLatch start, Runnable command)
    {
        return runConcurrentAttempt(action, userId, APPROVAL_PERMISSIONS,
                ready, start, command);
    }

    /**
     * 在线程内建立带指定正式权限的 SecurityContext，通过同步屏障执行一个并发动作。
     *
     * @param action String，动态调整、完成或整实例终态动作标签
     * @param userId long，当前线程的正式用户主键
     * @param permissions Set&lt;String&gt;，当前线程持有的页面与 API 权限
     * @param ready CountDownLatch，工作线程就绪屏障
     * @param start CountDownLatch，主线程统一放行屏障
     * @param command Runnable，待执行的真实领域命令
     * @return ConcurrentAttempt，成功、稳定业务失败或非预期异常结果
     */
    private ConcurrentAttempt runConcurrentAttempt(String action, long userId,
            Set<String> permissions, CountDownLatch ready, CountDownLatch start,
            Runnable command)
    {
        setSecurityContextUser(userId, permissions);
        ready.countDown();
        try
        {
            if (!start.await(10, TimeUnit.SECONDS))
            {
                return new ConcurrentAttempt(action, false, null, null,
                        new AssertionError("并发动作未在门禁时间内统一启动"));
            }
            command.run();
            return new ConcurrentAttempt(action, true, null, null, null);
        }
        catch (ServiceException exception)
        {
            return new ConcurrentAttempt(action, false, exception.getCode(),
                    exception.getSubCode(), null);
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            return new ConcurrentAttempt(action, false, null, null, exception);
        }
        catch (RuntimeException exception)
        {
            return new ConcurrentAttempt(action, false, null, null, exception);
        }
        finally
        {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 根据唯一成功动作核对最终成员快照、revision、活动任务数量和办理人集合。
     *
     * @param processInstanceId String，竞态流程实例主键
     * @param winningAction String，唯一提交的 ADD、REMOVE 或 COMPLETE
     * @return 无返回值；最终服务端状态不能由成功动作唯一解释时测试失败
     */
    private void assertRaceWinnerState(String processInstanceId, String winningAction)
    {
        RuntimeService runtimeService = processEngine.getRuntimeService();
        Object revision = runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.revisionName(ALL_ACTIVITY_ID));
        Object members = runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.memberSnapshotName(ALL_ACTIVITY_ID));
        List<Task> activeTasks = processEngine.getTaskService().createTaskQuery()
                .processInstanceId(processInstanceId).taskDefinitionKey(ALL_ACTIVITY_ID)
                .active().list();
        if ("ADD".equals(winningAction))
        {
            assertThat(revision).isEqualTo(1);
            assertThat(members).isEqualTo(List.of("81", "82", "83"));
            assertThat(activeTasks).extracting(Task::getAssignee)
                    .containsExactlyInAnyOrder("81", "82", "83");
        }
        else if ("REMOVE".equals(winningAction))
        {
            assertThat(revision).isEqualTo(1);
            assertThat(members).isEqualTo(List.of("81"));
            assertThat(activeTasks).extracting(Task::getAssignee).containsExactly("81");
        }
        else
        {
            assertThat(winningAction).isEqualTo("COMPLETE");
            assertThat(revision).isEqualTo(1);
            assertThat(members).isEqualTo(List.of("81", "82"));
            assertThat(activeTasks).extracting(Task::getAssignee).containsExactly("82");
        }
    }

    /**
     * 为指定有效用户建立与正式身份解析器兼容的登录上下文。
     *
     * @param userId long，已由受控环境预置并登记的测试用户主键
     * @return 无返回值；领域服务随后从同一 LoginUser 读取用户和权限
     */
    private void setSecurityContextUser(long userId)
    {
        setSecurityContextUser(userId, APPROVAL_PERMISSIONS);
    }

    /**
     * 为指定有效用户建立与正式身份解析器兼容且权限显式受控的登录上下文。
     *
     * @param userId long，已由受控环境预置并登记的测试用户主键
     * @param permissions Set&lt;String&gt;，当前并发动作需要持有的正式权限
     * @return 无返回值；领域服务随后从同一 LoginUser 读取用户和权限
     */
    private void setSecurityContextUser(long userId, Set<String> permissions)
    {
        SysUser user = new SysUser(userId);
        user.setUserName("flowable_mi_it_" + userId);
        user.setNickName("多实例集成测试用户 " + userId);
        LoginUser loginUser = new LoginUser(userId, null, user,
                Set.copyOf(permissions));
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(loginUser, null,
                        loginUser.getAuthorities());
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);
    }

    /**
     * 验证当前 JDBC 连接只指向显式批准的集成测试 schema。
     *
     * @return 无返回值；期望 schema 为空或连接 catalog 不匹配时拒绝任何测试写入
     * @throws SQLException 无法读取连接 catalog 时测试失败
     */
    private void assertDedicatedSchema() throws SQLException
    {
        assertThat(expectedSchema).isNotBlank();
        try (Connection connection = dataSource.getConnection())
        {
            assertThat(connection.getCatalog()).isEqualToIgnoringCase(expectedSchema);
        }
    }

    /**
     * 核验四个专用账号已经由受控环境正式预置，且身份标记、启用状态和审批资格完全匹配。
     *
     * @return 无返回值；账号缺失、被替换、停用、逻辑删除或审批角色漂移时立即拒绝测试
     */
    private void assertProvisionedTestUsers()
    {
        Long matchingUsers = jdbcTemplate.queryForObject(
                "select count(*) from sys_user "
                        + "where user_id in (81,82,83,84) "
                        + "and user_name = concat('flowable_mi_it_', user_id) "
                        + "and status = '0' and del_flag = '0'",
                Long.class);
        assertThat(matchingUsers)
                .as("专用账号 81..84 必须已登记并以 flowable_mi_it_* 启用状态预置")
                .isEqualTo((long) TEST_USER_IDS.size());
        Long approvalEligibleUsers = jdbcTemplate.queryForObject(
                "select count(distinct u.user_id) from sys_user u "
                        + "inner join sys_user_role ur on ur.user_id = u.user_id "
                        + "inner join sys_role r on r.role_id = ur.role_id "
                        + "inner join sys_role_menu rm on rm.role_id = r.role_id "
                        + "inner join sys_menu m on m.menu_id = rm.menu_id "
                        + "where u.user_id in (81,82,83,84) "
                        + "and u.status = '0' and u.del_flag = '0' "
                        + "and r.status = '0' and r.del_flag = '0' "
                        + "and m.status = '0' and m.perms = 'workflow:process:approval'",
                Long.class);
        assertThat(approvalEligibleUsers)
                .as("专用账号 81..84 必须通过真实启用角色获得流程办理权限")
                .isEqualTo((long) TEST_USER_IDS.size());
        List<Long> configuredRoleMembers = jdbcTemplate.queryForList(
                "select distinct u.user_id from sys_user u "
                        + "inner join sys_user_role ur on ur.user_id = u.user_id "
                        + "inner join sys_role r on r.role_id = ur.role_id "
                        + "where ur.role_id = 101 "
                        + "and u.status = '0' and u.del_flag = '0' "
                        + "and r.status = '0' and r.del_flag = '0' "
                        + "and (u.user_id = 1 or 3 = ("
                        + "select count(distinct m.perms) from sys_user_role eur "
                        + "inner join sys_role er on er.role_id = eur.role_id "
                        + "inner join sys_role_menu erm on erm.role_id = er.role_id "
                        + "inner join sys_menu m on m.menu_id = erm.menu_id "
                        + "where eur.user_id = u.user_id and er.status = '0' "
                        + "and er.del_flag = '0' and er.role_id <> 1 "
                        + "and m.status = '0' and m.perms in ("
                        + "'workflow:process:todoList','workflow:process:query',"
                        + "'workflow:process:approval'))) order by u.user_id",
                Long.class);
        assertThat(configuredRoleMembers)
                .as("指定角色 101 必须按实时 RBAC 展开完整审批用户")
                .containsExactlyElementsOf(ROLE_ALL_USER_IDS);
        List<Long> configuredDeptMembers = jdbcTemplate.queryForList(
                "select distinct u.user_id from sys_user u "
                        + "inner join sys_dept d on d.dept_id = u.dept_id "
                        + "where u.dept_id = 100 "
                        + "and u.status = '0' and u.del_flag = '0' "
                        + "and d.status = '0' and d.del_flag = '0' "
                        + "and (u.user_id = 1 or 3 = ("
                        + "select count(distinct m.perms) from sys_user_role eur "
                        + "inner join sys_role er on er.role_id = eur.role_id "
                        + "inner join sys_role_menu erm on erm.role_id = er.role_id "
                        + "inner join sys_menu m on m.menu_id = erm.menu_id "
                        + "where eur.user_id = u.user_id and er.status = '0' "
                        + "and er.del_flag = '0' and er.role_id <> 1 "
                        + "and m.status = '0' and m.perms in ("
                        + "'workflow:process:todoList','workflow:process:query',"
                        + "'workflow:process:approval'))) order by u.user_id",
                Long.class);
        assertThat(configuredDeptMembers)
                .as("指定部门 100 必须按实时 RBAC 展开完整审批用户")
                .containsExactlyElementsOf(DEPT_ANY_USER_IDS);
    }

    /** 动态调整必须互斥的整实例终态动作。 */
    private enum TerminalRaceAction
    {
        CANCEL,
        REJECT,
        TERMINATE
    }

    /**
     * 保存一个并发动作的唯一业务结果。
     *
     * @param action String，ADD、REMOVE 或 COMPLETE
     * @param successful boolean，当前事务是否提交成功
     * @param statusCode Integer，失败的稳定 HTTP 业务码；非业务异常时为空
     * @param subCode String，失败的稳定业务子码；无专用分类时为空
     * @param error Throwable，非预期异常；正常成功或业务冲突时为空
     */
    private record ConcurrentAttempt(String action, boolean successful,
            Integer statusCode, String subCode, Throwable error)
    {
    }
}

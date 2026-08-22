package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.page.PageResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.domain.WfDeployParticipantRule;
import com.ruoyi.flowable.domain.dto.WorkflowStartableProcessQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowStartableDefinitionView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;
import com.ruoyi.flowable.runtime.WorkflowParticipantResolutionMetrics;
import com.ruoyi.flowable.service.identity.WorkflowParticipantRuleRuntimeService;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifacts;
import com.ruoyi.flowable.service.model.WorkflowDeploymentService;
import com.ruoyi.flowable.service.task.WorkflowTaskLifecycleService;
import com.ruoyi.system.service.ISysUserService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 使用真实 Flowable 8、H2、父子部署和业务资源快照验证可发起查询完整批量链路。
 */
class WorkflowStartableProcessQueryIntegrationTest
{
    /** 当前测试登录用户主键。 */
    private static final String CURRENT_USER_ID = "100";

    /** 当前测试身份，用正式 Flowable 候选组编码表达角色和部门身份。 */
    private static final WorkflowCurrentIdentity ACTOR = new WorkflowCurrentIdentity(
            CURRENT_USER_ID, Set.of("ROLE20", "DEPT30"));

    private ProcessEngine processEngine;
    private RepositoryService repositoryService;
    private WorkflowDeploymentArtifactRepository artifactRepository;
    private WorkflowIdentityMapper identityMapper;
    private WorkflowIdentityResolver identityResolver;
    private WorkflowParticipantRuleRuntimeService participantRuntimeService;
    private WorkflowProcessQueryService queryService;

    /** 流程 key 到真实定义。 */
    private final Map<String, ProcessDefinition> definitions = new LinkedHashMap<>();
    /** 流程 key 到真实父部署。 */
    private final Map<String, Deployment> deployments = new LinkedHashMap<>();

    /**
     * 创建真实内存引擎并部署新版与历史定义，只有登录身份和外部组织目录使用模拟。
     *
     * @return void，无返回值；每个测试获得独立 H2 引擎和真实业务资源子部署
     */
    @BeforeEach
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void setUp()
    {
        processEngine = ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration()
                .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
                .setHistory("full")
                .buildProcessEngine();
        repositoryService = spy(processEngine.getRepositoryService());
        RuntimeService runtimeService = processEngine.getRuntimeService();
        HistoryService historyService = processEngine.getHistoryService();
        TaskService taskService = processEngine.getTaskService();

        artifactRepository = new WorkflowDeploymentArtifactRepository(repositoryService);
        identityMapper = mock(WorkflowIdentityMapper.class);
        when(identityMapper.selectActiveScopeDeptIdsByUserId(100L))
                .thenReturn(List.of(30L, 31L));
        identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveCurrentIdentity()).thenReturn(ACTOR);
        participantRuntimeService = spy(new WorkflowParticipantRuleRuntimeService(
                repositoryService, runtimeService, artifactRepository, identityMapper,
                identityResolver,
                new WorkflowParticipantResolutionMetrics(new SimpleMeterRegistry())));

        // 新版定义均持久化真实业务资源子部署；允许和拒绝规则同时存在以验证 false 不回退。
        deployManaged("a_public", "公开流程", "finance", "PUBLIC", null);
        deployManaged("b_users_allow", "用户允许流程", "hr", "USERS", "100,101");
        deployManaged("c_users_deny", "用户拒绝流程", "finance", "USERS", "999");
        deployManaged("d_roles_allow", "角色允许流程", "finance", "ROLES", "20");
        deployManaged("e_roles_deny", "角色拒绝流程", "hr", "ROLES", "99");
        deployManaged("f_depts_allow", "部门允许流程", "finance", "DEPTS", "30");
        deployManaged("g_depts_deny", "部门拒绝流程", "hr", "DEPTS", "99");
        deployManaged("h_depts_allow_second", "第二部门允许流程", "finance", "DEPTS", "31");

        // 历史定义故意不创建业务资源子部署，只使用真实 Flowable starter identity link。
        deployHistorical("i_history_public", "历史公开流程", "archive", null, null);
        deployHistorical("j_history_user_allow", "历史用户允许流程", "finance", "100", null);
        deployHistorical("k_history_user_deny", "历史用户拒绝流程", "hr", "999", null);
        deployHistorical("l_history_group_allow", "历史组允许流程", "hr", null, "ROLE20");
        deployHistorical("m_history_group_deny", "历史组拒绝流程", "archive", null, "ROLE99");

        WorkflowEngineOperations engineOperations = mock(WorkflowEngineOperations.class);
        when(engineOperations.read(any(Supplier.class))).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        queryService = new WorkflowProcessQueryService(engineOperations, repositoryService,
                historyService, runtimeService, taskService, identityResolver,
                mock(WorkflowProcessAccessService.class), mock(WorkflowDeploymentService.class),
                artifactRepository, mock(WfCopyMapper.class), mock(ISysUserService.class),
                mock(WorkflowTaskLifecycleService.class), participantRuntimeService);

        // 夹具创建阶段的真实部署和 starter link 写入不属于待验证的查询次数。
        clearInvocations(repositoryService, identityMapper, identityResolver,
                participantRuntimeService);
    }

    /**
     * 关闭真实引擎并清理 H2，避免测试间共享部署和身份链接。
     *
     * @return void，无返回值
     */
    @AfterEach
    void tearDown()
    {
        if (processEngine != null)
        {
            processEngine.close();
        }
    }

    /**
     * 验证 PUBLIC、USERS、ROLES、DEPTS 的批量允许和拒绝，并确认部门范围整批只查一次。
     *
     * @return void，规则语义变化、受管定义缺失或部门目录发生 N+1 时测试失败
     */
    @Test
    void resolvesAllManagedRuleTypesAndLoadsDepartmentScopeOnce()
    {
        List<ProcessDefinition> managedDefinitions = definitions.entrySet().stream()
                .filter(entry -> entry.getKey().compareTo("i_history_public") < 0)
                .map(Map.Entry::getValue)
                .toList();

        Map<String, Boolean> decisions = participantRuntimeService
                .resolveManagedStartDecisions(ACTOR, managedDefinitions);

        assertThat(decisions).hasSize(8);
        assertDecision(decisions, "a_public", true);
        assertDecision(decisions, "b_users_allow", true);
        assertDecision(decisions, "c_users_deny", false);
        assertDecision(decisions, "d_roles_allow", true);
        assertDecision(decisions, "e_roles_deny", false);
        assertDecision(decisions, "f_depts_allow", true);
        assertDecision(decisions, "g_depts_deny", false);
        assertDecision(decisions, "h_depts_allow_second", true);
        verify(identityMapper, times(1)).selectActiveScopeDeptIdsByUserId(100L);

        Map<String, Map<String, WfDeployParticipantRule>> snapshots = artifactRepository
                .selectStartParticipantRulesByDeploymentIds(managedDefinitions.stream()
                        .map(ProcessDefinition::getDeploymentId).toList());
        assertThat(snapshots).hasSize(8);
        assertThat(snapshots.get(deployments.get("a_public").getId()))
                .containsOnlyKeys("a_public");
    }

    /**
     * 验证混合新版与历史定义的真实分页、总数、稳定顺序和部署元数据投影。
     *
     * @return void，任一新版定义触发历史兜底、分页边界漂移或投影变化时测试失败
     */
    @Test
    void listsMixedDefinitionsWithStablePagesAndHistoricalFallbackOnly()
    {
        PageResult<WorkflowStartableDefinitionView> firstPage =
                queryService.listStartable(null, 1, 3);

        // 首次无分类查询固定为一次业务资源子部署批量查询和一次当前页父部署投影查询，禁止退化为逐定义查询。
        verify(repositoryService, times(2)).createDeploymentQuery();
        assertThat(firstPage.total()).isEqualTo(8);
        assertThat(keys(firstPage.rows())).containsExactly(
                "a_public", "b_users_allow", "d_roles_allow");
        assertProjection(firstPage.rows().get(0), "a_public");
        assertProjection(firstPage.rows().get(1), "b_users_allow");
        assertProjection(firstPage.rows().get(2), "d_roles_allow");
        assertHistoricalIdentityLinkCalls(1);
        assertManagedIdentityLinksWereNeverRead();

        clearInvocations(repositoryService, participantRuntimeService, identityMapper);
        PageResult<WorkflowStartableDefinitionView> secondPage =
                queryService.listStartable(null, 2, 3);
        PageResult<WorkflowStartableDefinitionView> thirdPage =
                queryService.listStartable(null, 3, 3);

        assertThat(secondPage.total()).isEqualTo(8);
        assertThat(keys(secondPage.rows())).containsExactly(
                "f_depts_allow", "h_depts_allow_second", "i_history_public");
        assertThat(thirdPage.total()).isEqualTo(8);
        assertThat(keys(thirdPage.rows())).containsExactly(
                "j_history_user_allow", "l_history_group_allow");
        assertHistoricalIdentityLinkCalls(2);
        assertManagedIdentityLinksWereNeverRead();

        PageResult<WorkflowStartableDefinitionView> finance = queryService.listStartable(
                new WorkflowStartableProcessQueryDto(null, null, "finance"), 1, 20);
        assertThat(finance.total()).isEqualTo(5);
        assertThat(keys(finance.rows())).containsExactly(
                "a_public", "d_roles_allow", "f_depts_allow",
                "h_depts_allow_second", "j_history_user_allow");
        assertThat(finance.rows()).extracting(WorkflowStartableDefinitionView::category)
                .containsOnly("finance");
    }

    /**
     * 验证导出与分页列表全集一致，且导出只解析一次身份并执行一次整批快照授权扫描。
     *
     * @return void，导出重复扫描、权限差异或稳定顺序变化时测试失败
     */
    @Test
    void exportsSameVisibleSetWithOneAuthorizationScan()
    {
        List<WorkflowStartableDefinitionView> pagedRows = new ArrayList<>();
        for (int pageNum = 1; pageNum <= 3; pageNum++)
        {
            pagedRows.addAll(queryService.listStartable(null, pageNum, 3).rows());
        }
        clearInvocations(repositoryService, participantRuntimeService, identityMapper,
                identityResolver);

        List<WorkflowStartableDefinitionView> exported =
                queryService.listStartableForExport(null);

        assertThat(exported).containsExactlyElementsOf(pagedRows);
        assertThat(keys(exported)).containsExactly(
                "a_public", "b_users_allow", "d_roles_allow", "f_depts_allow",
                "h_depts_allow_second", "i_history_public",
                "j_history_user_allow", "l_history_group_allow");
        verify(identityResolver, times(1)).resolveCurrentIdentity();
        verify(participantRuntimeService, times(1))
                .resolveManagedStartDecisions(any(), any());
        verify(identityMapper, times(1)).selectActiveScopeDeptIdsByUserId(100L);
        assertHistoricalIdentityLinkCalls(1);
        assertManagedIdentityLinksWereNeverRead();
    }

    /**
     * 验证列表、单定义只读判定和真实发起前 assertCanStart 对同一新版定义保持一致。
     *
     * @return void，允许、拒绝或历史未托管语义在三个入口间不一致时测试失败
     */
    @Test
    void keepsListSingleDefinitionAndStartAssertionConsistent()
    {
        Set<String> visibleKeys = Set.copyOf(keys(
                queryService.listStartable(null, 1, 200).rows()));
        for (Map.Entry<String, ProcessDefinition> entry : definitions.entrySet())
        {
            String processKey = entry.getKey();
            ProcessDefinition definition = entry.getValue();
            Boolean managed = participantRuntimeService.canStartIfManaged(ACTOR, definition);
            if (processKey.compareTo("i_history_public") >= 0)
            {
                assertThat(managed).isNull();
                assertThat(participantRuntimeService.assertCanStart(ACTOR, definition)).isNull();
                continue;
            }

            boolean listed = visibleKeys.contains(processKey);
            assertThat(managed).isEqualTo(listed);
            if (listed)
            {
                assertThat(participantRuntimeService.assertCanStart(ACTOR, definition))
                        .extracting(WfDeployParticipantRule::getProcessKey)
                        .isEqualTo(processKey);
            }
            else
            {
                assertThatThrownBy(() -> participantRuntimeService
                        .assertCanStart(ACTOR, definition))
                        .isInstanceOfSatisfying(ServiceException.class, exception ->
                        {
                            assertThat(exception.getCode()).isEqualTo(HttpStatus.FORBIDDEN);
                            assertThat(exception.getSubCode())
                                    .isEqualTo("PROCESS_START_SCOPE_DENIED");
                        });
            }
        }
    }

    /**
     * 验证受管部署的不支持规则版本保持 409 失败，不会降级成拒绝、历史公开或空页。
     *
     * @return void，异常被吞掉或错误进入 starter identity link 兜底时测试失败
     */
    @Test
    void failsClosedForUnsupportedManagedSnapshotVersion()
    {
        Deployment deployment = deploy("z_unsupported_version", "版本异常流程", "finance");
        ProcessDefinition definition = requireDefinition(deployment);
        WfDeployParticipantRule unsupported = startRule(
                definition.getKey(), "PUBLIC", null);
        unsupported.setRuleVersion(2);
        artifactRepository.persist(deployment.getId(), artifacts(unsupported));
        clearInvocations(repositoryService);

        assertThatThrownBy(() -> queryService.listStartable(
                new WorkflowStartableProcessQueryDto("z_unsupported_version", null, null),
                1, 20))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getSubCode())
                            .isEqualTo("PROCESS_START_SCOPE_SNAPSHOT_MISSING");
                });
        verify(repositoryService, never())
                .getIdentityLinksForProcessDefinition(definition.getId());
    }

    /**
     * 部署一个真实新版流程及其唯一发起规则业务资源子部署。
     *
     * @param processKey String，保证字典序稳定的流程标识
     * @param processName String，流程显示名称
     * @param category String，父部署正式业务分类
     * @param ruleType String，PUBLIC、USERS、ROLES 或 DEPTS
     * @param targetIds String，可空逗号分隔目标主键
     * @return void，定义和父部署写入当前测试夹具映射
     */
    private void deployManaged(String processKey, String processName, String category,
            String ruleType, String targetIds)
    {
        Deployment deployment = deploy(processKey, processName, category);
        ProcessDefinition definition = requireDefinition(deployment);
        artifactRepository.persist(deployment.getId(), artifacts(
                startRule(processKey, ruleType, targetIds)));
        deployments.put(processKey, deployment);
        definitions.put(processKey, definition);
    }

    /**
     * 部署一个真实历史流程并按需写入 Flowable starter user 或 group identity link。
     *
     * @param processKey String，保证字典序稳定的流程标识
     * @param processName String，流程显示名称
     * @param category String，父部署正式业务分类
     * @param starterUserId String，可空历史候选发起用户
     * @param starterGroupId String，可空历史候选发起组
     * @return void，定义和父部署写入当前测试夹具映射
     */
    private void deployHistorical(String processKey, String processName, String category,
            String starterUserId, String starterGroupId)
    {
        Deployment deployment = deploy(processKey, processName, category);
        ProcessDefinition definition = requireDefinition(deployment);
        if (starterUserId != null)
        {
            repositoryService.addCandidateStarterUser(definition.getId(), starterUserId);
        }
        if (starterGroupId != null)
        {
            repositoryService.addCandidateStarterGroup(definition.getId(), starterGroupId);
        }
        deployments.put(processKey, deployment);
        definitions.put(processKey, definition);
    }

    /**
     * 创建只包含开始和结束节点的真实可执行 BPMN 父部署。
     *
     * @param processKey String，流程标识
     * @param processName String，流程显示名称
     * @param category String，发布冻结的业务分类
     * @return Deployment，真实 Flowable 父部署
     */
    private Deployment deploy(String processKey, String processName, String category)
    {
        return repositoryService.createDeployment()
                .category(category)
                .addString(processKey + ".bpmn20.xml", bpmn(processKey, processName))
                .deploy();
    }

    /**
     * 从真实父部署读取唯一流程定义。
     *
     * @param deployment Deployment，刚完成的真实 BPMN 部署
     * @return ProcessDefinition，唯一可执行流程定义
     */
    private ProcessDefinition requireDefinition(Deployment deployment)
    {
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
        assertThat(definition).isNotNull();
        return definition;
    }

    /**
     * 创建流程级不可变发起规则快照。
     *
     * @param processKey String，规则所属流程标识
     * @param ruleType String，受控发起规则类型
     * @param targetIds String，可空目标主键文本
     * @return WfDeployParticipantRule，版本为 1 且固定 FAIL 策略的规则
     */
    private WfDeployParticipantRule startRule(
            String processKey, String ruleType, String targetIds)
    {
        WfDeployParticipantRule rule = new WfDeployParticipantRule();
        rule.setProcessKey(processKey);
        rule.setRuleScope("START");
        rule.setAssignmentMode("START");
        rule.setRuleType(ruleType);
        rule.setTargetIds(targetIds);
        rule.setNoMatchPolicy("FAIL");
        rule.setRuleVersion(1);
        rule.setChecksum("checksum-" + processKey + "-" + ruleType);
        rule.setCreateBy("1");
        return rule;
    }

    /**
     * 将单条参与者规则放入完整业务资源集合，其他快照保持真实空资源。
     *
     * @param rule WfDeployParticipantRule，待持久化发起规则
     * @return WorkflowDeploymentArtifacts，可由正式仓库创建业务资源子部署的完整对象
     */
    private WorkflowDeploymentArtifacts artifacts(WfDeployParticipantRule rule)
    {
        return new WorkflowDeploymentArtifacts(List.of(), List.of(), List.of(), List.of(rule),
                List.of(), List.of(), List.of(), List.of());
    }

    /**
     * 断言批量决定中指定流程的定义主键对应期望结果。
     *
     * @param decisions Map&lt;String, Boolean&gt;，整批受管决定
     * @param processKey String，待断言流程标识
     * @param expected boolean，期望允许或拒绝
     * @return void，定义缺失或决定不一致时测试失败
     */
    private void assertDecision(Map<String, Boolean> decisions,
            String processKey, boolean expected)
    {
        assertThat(decisions).containsEntry(definitions.get(processKey).getId(), expected);
    }

    /**
     * 断言可发起视图完整保留真实定义和父部署投影。
     *
     * @param row WorkflowStartableDefinitionView，待核验视图
     * @param processKey String，期望流程标识
     * @return void，分类、时间、部署或定义字段变化时测试失败
     */
    private void assertProjection(WorkflowStartableDefinitionView row, String processKey)
    {
        ProcessDefinition definition = definitions.get(processKey);
        Deployment deployment = deployments.get(processKey);
        assertThat(row.definitionId()).isEqualTo(definition.getId());
        assertThat(row.processKey()).isEqualTo(processKey);
        assertThat(row.processName()).isEqualTo(definition.getName());
        assertThat(row.category()).isEqualTo(deployment.getCategory());
        assertThat(row.version()).isEqualTo(definition.getVersion());
        assertThat(row.deploymentId()).isEqualTo(deployment.getId());
        assertThat(row.deploymentTime()).isEqualTo(deployment.getDeploymentTime().toInstant());
    }

    /**
     * 断言一次扫描只为五个历史未托管定义读取 starter identity link。
     *
     * @param scanCount int，期望完整扫描次数
     * @return void，历史定义调用次数不一致时测试失败
     */
    private void assertHistoricalIdentityLinkCalls(int scanCount)
    {
        for (String processKey : List.of("i_history_public", "j_history_user_allow",
                "k_history_user_deny", "l_history_group_allow", "m_history_group_deny"))
        {
            verify(repositoryService, times(scanCount))
                    .getIdentityLinksForProcessDefinition(definitions.get(processKey).getId());
        }
    }

    /**
     * 断言所有新版受管定义都没有错误进入历史 starter identity link 兜底。
     *
     * @return void，任一新版允许或拒绝定义触发历史 API 时测试失败
     */
    private void assertManagedIdentityLinksWereNeverRead()
    {
        for (String processKey : List.of("a_public", "b_users_allow", "c_users_deny",
                "d_roles_allow", "e_roles_deny", "f_depts_allow", "g_depts_deny",
                "h_depts_allow_second"))
        {
            verify(repositoryService, never())
                    .getIdentityLinksForProcessDefinition(definitions.get(processKey).getId());
        }
    }

    /**
     * 提取视图流程 key，保持调用方返回顺序。
     *
     * @param rows List&lt;WorkflowStartableDefinitionView&gt;，可发起视图列表
     * @return List&lt;String&gt;，稳定流程 key 列表
     */
    private List<String> keys(List<WorkflowStartableDefinitionView> rows)
    {
        return rows.stream().map(WorkflowStartableDefinitionView::processKey).toList();
    }

    /**
     * 生成真实 Flowable 8 可部署的最小可执行 BPMN。
     *
     * @param processKey String，流程标识
     * @param processName String，流程名称
     * @return String，UTF-8 BPMN XML 正文
     */
    private String bpmn(String processKey, String processName)
    {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  targetNamespace="urn:startable-query:test">
                  <process id="%s" name="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <endEvent id="end"/>
                    <sequenceFlow id="flow" sourceRef="start" targetRef="end"/>
                  </process>
                </definitions>
                """.formatted(processKey, processName);
    }
}

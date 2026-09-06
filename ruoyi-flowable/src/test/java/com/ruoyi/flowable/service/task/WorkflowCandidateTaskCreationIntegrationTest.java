package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployParticipantRule;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.listener.WorkflowUserTaskListener;
import com.ruoyi.flowable.mapper.WfMultiInstanceRoundMapper;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;
import com.ruoyi.flowable.runtime.WorkflowParticipantResolutionMetrics;
import com.ruoyi.flowable.service.identity.WorkflowParticipantRuleRuntimeService;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifacts;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;

/**
 * 真实 Flowable 触发完整生产监听入口，验证未认领候选任务、普通轮次判断及身份失败回滚。
 */
class WorkflowCandidateTaskCreationIntegrationTest
{
    private WorkflowFlowableEngineTestSupport harness;
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private TaskService taskService;
    private WorkflowIdentityResolver identityResolver;
    private WorkflowDeploymentArtifactRepository artifactRepository;
    private WorkflowMultiInstanceRuntimeSnapshotReader snapshotReader;

    /** 复用现有引擎、延迟绑定和正式 Mapper 基础设施，仅隔离目录及无关外部协作者。 */
    @BeforeEach
    void setUp()
    {
        var binding = new WorkflowMultiInstanceEngineHarness.LateBindingTaskListener();
        harness = WorkflowFlowableEngineTestSupport.start("candidate-creation",
                Map.of("userTaskListener", binding));
        repositoryService = harness.processEngine().getRepositoryService();
        runtimeService = harness.processEngine().getRuntimeService();
        taskService = harness.processEngine().getTaskService();
        identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveClaimEligibleUserIds(anyCollection())).thenReturn(Set.of());
        when(identityResolver.resolveClaimEligibleCandidateGroups(anyCollection())).thenReturn(Set.of());
        artifactRepository = new WorkflowDeploymentArtifactRepository(repositoryService);
        harness.jdbcTemplate().execute("set mode MySQL");
        WorkflowH2SchemaMapperSupport.executeSchema(harness.dataSource(),
                WorkflowH2SchemaMapperSupport.MULTI_INSTANCE_ROUND_SCHEMA);
        WfMultiInstanceRoundMapper mapper = WorkflowH2SchemaMapperSupport.createSpringMapper(
                harness.dataSource(), "candidate-round", WfMultiInstanceRoundMapper.class,
                "mapper/flowable/WfMultiInstanceRoundMapper.xml");
        snapshotReader = new WorkflowMultiInstanceRuntimeSnapshotReader(
                repositoryService, runtimeService, taskService);
        var rounds = new WorkflowMultiInstanceRoundLifecycleService(
                new WorkflowMultiInstanceRoundRepository(mapper), snapshotReader,
                new WorkflowMultiInstanceTransitionCoordinator());
        var participants = new WorkflowParticipantRuleRuntimeService(repositoryService,
                runtimeService, artifactRepository, mock(WorkflowIdentityMapper.class),
                identityResolver, mock(WorkflowParticipantResolutionMetrics.class));
        binding.bind(new WorkflowUserTaskListener(new WorkflowUserTaskAuditService(
                taskService, identityResolver, new WorkflowIdentityCodec()),
                mock(WorkflowTaskSlaRuntimeService.class), participants,
                mock(WorkflowAutomaticCopyService.class),
                mock(WorkflowNotificationService.class), rounds));
    }

    /** 每例关闭独占 H2 和引擎，不在共享 Harness 中增加候选业务配置。 */
    @AfterEach
    void tearDown()
    {
        if (harness != null)
        {
            harness.close();
        }
    }

    /** 候选用户和候选组均保留未认领事实，跳过轮次后仍持久化正式创建审计。 */
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void createsUnclaimedCandidateAndContinuesAuditWithoutRound(boolean group)
    {
        deploy(group);
        allowCandidate(group);

        String instanceId = runtimeService.startProcessInstanceByKey("candidateTaskCreation",
                Map.of("initiator", "100", "requestTitle", "candidate regression")).getId();

        Task task = taskService.createTaskQuery().processInstanceId(instanceId).singleResult();
        assertThat(task).isNotNull();
        assertThat(task.getAssignee()).isNull();
        assertThat(task.getClaimedBy()).isNull();
        assertThat(task.getClaimTime()).isNull();
        assertThat(taskService.getIdentityLinksForTask(task.getId()).stream()
                .filter(link -> IdentityLinkType.CANDIDATE.equals(link.getType())).toList())
                .singleElement().satisfies(link ->
                {
                    assertThat(link.getUserId()).isEqualTo(group ? null : "201");
                    assertThat(link.getGroupId()).isEqualTo(group ? "ROLE2" : null);
                });
        assertThat(snapshotReader.readTask(task.getId())).isNull();
        assertThat(harness.jdbcTemplate().queryForObject(
                "select count(*) from wf_multi_instance_round", Long.class)).isZero();
        assertThat(taskService.getTaskComments(task.getId(), WorkflowUserTaskAuditService.COMMENT_TYPE))
                .singleElement().satisfies(comment -> assertThat(comment.getFullMessage())
                        .contains("\"action\":\"USER_TASK_CREATE\"", "\"taskId\":\"" + task.getId() + "\"")
                        .doesNotContain("assigneeUserId"));
    }

    /** 参与者解析没有合法候选身份时拒绝创建，运行、历史和业务轮次均不残留。 */
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void rollsBackWhenNoCandidateIsEligible(boolean group)
    {
        deploy(group);

        assertThatThrownBy(() -> runtimeService.startProcessInstanceByKey("candidateTaskCreation",
                Map.of("initiator", "100", "requestTitle", "must roll back")))
                .isInstanceOf(ServiceException.class)
                .hasMessage("候选规则没有解析出有效认领身份")
                .satisfies(error -> assertThat(((ServiceException) error).getCode()).isEqualTo(409));

        assertNoProcessWrites();
    }

    /** 解析后资格失效仍由真实创建审计拒绝，证明快照修复没有绕开后续身份门禁。 */
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void rollsBackWhenAuditRejectsResolvedCandidate(boolean group)
    {
        deploy(group);
        if (group)
        {
            when(identityResolver.resolveClaimEligibleCandidateGroups(anyCollection()))
                    .thenReturn(Set.of("ROLE2"), Set.of());
        }
        else
        {
            when(identityResolver.resolveClaimEligibleUserIds(anyCollection()))
                    .thenReturn(Set.of("201"), Set.of());
        }

        assertThatThrownBy(() -> runtimeService.startProcessInstanceByKey("candidateTaskCreation",
                Map.of("initiator", "100", "requestTitle", "must roll back")))
                .isInstanceOf(ServiceException.class).hasMessage("用户任务办理身份无效")
                .satisfies(error -> assertThat(((ServiceException) error).getCode()).isEqualTo(400));

        assertNoProcessWrites();
    }

    /** 创建真实 BPMN 和正式参与者部署制品，候选链接只能由生产参与者服务生成。 */
    private void deploy(boolean group)
    {
        String deploymentId = repositoryService.createDeployment().addClasspathResource(
                "bpmn/workflow-candidate-task-creation.bpmn20.xml").deploy().getId();
        WfDeployParticipantRule rule = new WfDeployParticipantRule();
        rule.setProcessKey("candidateTaskCreation");
        rule.setActivityId("review");
        rule.setActivityName("候选审批");
        rule.setRuleScope("TASK");
        rule.setAssignmentMode("CANDIDATE");
        rule.setRuleType(group ? "CANDIDATE_GROUPS" : "CANDIDATE_USERS");
        rule.setTargetIds(group ? "ROLE2" : "201");
        rule.setNoMatchPolicy("FAIL");
        rule.setRuleVersion(1);
        rule.setChecksum("candidate-creation-" + group);
        rule.setCreateBy("100");
        artifactRepository.persist(deploymentId, new WorkflowDeploymentArtifacts(
                List.of(), List.of(), List.of(), List.of(rule),
                List.of(), List.of(), List.of(), List.of()));
    }

    /** 只配置测试身份目录中的真实资格查询边界，不替代监听或轮次判断。 */
    private void allowCandidate(boolean group)
    {
        if (group)
        {
            when(identityResolver.resolveClaimEligibleCandidateGroups(anyCollection()))
                    .thenReturn(Set.of("ROLE2"));
        }
        else
        {
            when(identityResolver.resolveClaimEligibleUserIds(anyCollection()))
                    .thenReturn(Set.of("201"));
        }
    }

    /** 只读核对共享事务中的运行、历史、候选链接、变量、审计及轮次全部回滚。 */
    private void assertNoProcessWrites()
    {
        for (String table : List.of("ACT_RU_EXECUTION", "ACT_RU_TASK", "ACT_RU_VARIABLE",
                "ACT_RU_IDENTITYLINK", "ACT_HI_PROCINST", "ACT_HI_TASKINST", "ACT_HI_ACTINST",
                "ACT_HI_VARINST", "ACT_HI_IDENTITYLINK", "ACT_HI_COMMENT", "wf_multi_instance_round"))
        {
            assertThat(harness.jdbcTemplate().queryForObject("select count(*) from " + table, Long.class))
                    .as(table).isZero();
        }
    }
}

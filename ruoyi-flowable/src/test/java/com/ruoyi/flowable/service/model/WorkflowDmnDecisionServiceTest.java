package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.flowable.dmn.api.DmnDecision;
import org.flowable.dmn.api.DmnDecisionQuery;
import org.flowable.dmn.api.DmnDeployment;
import org.flowable.dmn.api.DmnDeploymentQuery;
import org.flowable.dmn.api.DmnRepositoryService;
import org.flowable.engine.IdentityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowDmnDeploymentRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;

/**
 * DMN XML 安全、来源目录过滤和删除保护领域测试。
 */
class WorkflowDmnDecisionServiceTest
{
    private DmnRepositoryService repositoryService;
    private WorkflowDeploymentArtifactRepository artifactRepository;
    private WorkflowDmnDecisionService service;

    /**
     * 建立真实工作流事务特征、可信用户边界和可控官方 DMN 仓储替身。
     * @return void，初始化后可验证正式读写事务路径
     */
    @BeforeEach
    void setUp()
    {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                Connection.TRANSACTION_REPEATABLE_READ);
        WorkflowIdentityResolver resolver = mock(WorkflowIdentityResolver.class);
        when(resolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity("7", Set.of()));
        WorkflowEngineOperations operations = new WorkflowEngineOperations(
                new WorkflowAuthenticationContext(mock(IdentityService.class),
                        new WorkflowIdentityCodec()), new WorkflowExceptionTranslator(), resolver);
        repositoryService = mock(DmnRepositoryService.class);
        artifactRepository = mock(WorkflowDeploymentArtifactRepository.class);
        service = new WorkflowDmnDecisionService(operations, repositoryService,
                artifactRepository);
    }

    /**
     * 清理当前线程事务特征。
     * @return void，避免事务状态污染其他测试
     */
    @AfterEach
    void tearDown()
    {
        TransactionSynchronizationManager.clear();
    }

    /**
     * 验证空请求、非法资源名、DTD、实体和超限 XML 均在官方部署前失败关闭。
     * @return void，任何非法正文到达 DmnRepositoryService 时测试失败
     */
    @Test
    void rejectsUnsafeDmnBeforeOfficialDeployment()
    {
        assertBadRequest(() -> service.deploy(null), "不能为空");
        assertBadRequest(() -> service.deploy(new WorkflowDmnDeploymentRequest(
                "risk.xml", "", "<definitions/>")), ".dmn");
        assertBadRequest(() -> service.deploy(new WorkflowDmnDeploymentRequest(
                "risk.dmn", "", "<!DOCTYPE x><definitions/>")), "DTD");
        assertBadRequest(() -> service.deploy(new WorkflowDmnDeploymentRequest(
                "risk.dmn", "", "<!ENTITY x SYSTEM 'file:///tmp/x'><definitions/>")),
                "实体");
        assertBadRequest(() -> service.deploy(new WorkflowDmnDeploymentRequest(
                "risk.dmn", "", "x".repeat(2 * 1024 * 1024 + 1))), "大小");

        verify(repositoryService, never()).createDeployment();
    }

    /**
     * 验证管理目录过滤流程冻结副本，latestOnly 对每个 key 仅保留最高来源版本。
     * @return void，冻结 decisionId 泄露或版本选择漂移时测试失败
     */
    @Test
    void listsOnlySourceDecisionsAndSelectsLatestVersion()
    {
        DmnDecision riskV2 = decision("risk-v2", "risk", 2, "source-v2");
        DmnDecision riskV1 = decision("risk-v1", "risk", 1, "source-v1");
        DmnDecision frozen = decision("risk-frozen", "risk", 3, "frozen");
        DmnDecision routeV1 = decision("route-v1", "route", 1, "source-route");
        configureDecisionQuery(List.of(riskV2, riskV1, frozen, routeV1));
        configureDeploymentLookup(Map.of(
                "source-v2", deployment("source-v2", "source-v2"),
                "source-v1", deployment("source-v1", null),
                "frozen", deployment("frozen", "process-deployment"),
                "source-route", deployment("source-route", "source-route")));

        assertThat(service.list(false)).extracting("decisionId")
                .containsExactly("risk-v2", "risk-v1", "route-v1");
        assertThat(service.list(true)).extracting("decisionId")
                .containsExactly("risk-v2", "route-v1");
    }

    /**
     * 验证内部冻结部署和已被流程快照引用的来源部署都无法被独立删除。
     * @return void，任一冲突路径调用官方 deleteDeployment 时测试失败
     */
    @Test
    void protectsFrozenAndReferencedDeploymentsFromDeletion()
    {
        DmnDeploymentQuery deploymentQuery = configureDeploymentLookup(Map.of(
                "frozen", deployment("frozen", "process-deployment"),
                "source", deployment("source", "source")));

        assertConflict(() -> service.delete("frozen"), "不允许独立删除");
        when(artifactRepository.countDmnSourceReferences("source")).thenReturn(1L);
        assertConflict(() -> service.delete("source"), "冻结引用");

        verify(repositoryService, never()).deleteDeployment(anyString());
        verify(deploymentQuery, org.mockito.Mockito.atLeastOnce()).deploymentId("frozen");
        verify(artifactRepository).countDmnSourceReferences("source");
    }

    /**
     * 创建字段完整的官方决策替身。
     * @param id String，精确 decisionId
     * @param key String，稳定决策 key
     * @param version int，官方版本号
     * @param deploymentId String，所属部署主键
     * @return DmnDecision，可用于目录映射的决策替身
     */
    private DmnDecision decision(String id, String key, int version, String deploymentId)
    {
        DmnDecision decision = mock(DmnDecision.class);
        when(decision.getId()).thenReturn(id);
        when(decision.getKey()).thenReturn(key);
        when(decision.getName()).thenReturn(key + " decision");
        when(decision.getVersion()).thenReturn(version);
        when(decision.getCategory()).thenReturn("test");
        when(decision.getDecisionType()).thenReturn("decision-table");
        when(decision.getDeploymentId()).thenReturn(deploymentId);
        when(decision.getResourceName()).thenReturn(key + ".dmn");
        return decision;
    }

    /**
     * 创建可区分来源根部署和流程冻结子部署的官方部署替身。
     * @param id String，部署主键
     * @param parentId String，父部署主键；根部署允许等于自身或为空
     * @return DmnDeployment，部署关系替身
     */
    private DmnDeployment deployment(String id, String parentId)
    {
        DmnDeployment deployment = mock(DmnDeployment.class);
        when(deployment.getId()).thenReturn(id);
        when(deployment.getParentDeploymentId()).thenReturn(parentId);
        return deployment;
    }

    /**
     * 配置官方决策查询的稳定排序链。
     * @param decisions List&lt;DmnDecision&gt;，已按 key 升序和版本降序排列的结果
     * @return void，后续 list 调用返回给定目录
     */
    private void configureDecisionQuery(List<DmnDecision> decisions)
    {
        DmnDecisionQuery query = mock(DmnDecisionQuery.class);
        when(repositoryService.createDecisionQuery()).thenReturn(query);
        when(query.orderByDecisionKey()).thenReturn(query);
        when(query.orderByDecisionVersion()).thenReturn(query);
        when(query.asc()).thenReturn(query);
        when(query.desc()).thenReturn(query);
        when(query.list()).thenReturn(decisions);
    }

    /**
     * 配置按 deploymentId 回读部署关系的官方查询。
     * @param deployments Map&lt;String,DmnDeployment&gt;，部署主键到部署对象映射
     * @return DmnDeploymentQuery，可用于验证查询调用
     */
    private DmnDeploymentQuery configureDeploymentLookup(
            Map<String, DmnDeployment> deployments)
    {
        DmnDeploymentQuery query = mock(DmnDeploymentQuery.class);
        AtomicReference<String> selectedId = new AtomicReference<>();
        when(repositoryService.createDeploymentQuery()).thenReturn(query);
        when(query.deploymentId(anyString())).thenAnswer(invocation ->
        {
            selectedId.set(invocation.getArgument(0));
            return query;
        });
        when(query.singleResult()).thenAnswer(invocation -> deployments.get(selectedId.get()));
        return query;
    }

    /**
     * 断言领域调用以 400 拒绝。
     * @param command Runnable，待执行命令
     * @param message String，预期消息片段
     * @return void，状态或消息不一致时断言失败
     */
    private void assertBadRequest(Runnable command, String message)
    {
        assertThatThrownBy(command::run)
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining(message);
    }

    /**
     * 断言领域调用以 409 拒绝。
     * @param command Runnable，待执行命令
     * @param message String，预期消息片段
     * @return void，状态或消息不一致时断言失败
     */
    private void assertConflict(Runnable command, String message)
    {
        assertThatThrownBy(command::run)
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining(message);
    }
}

package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import static org.mockito.Mockito.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.domain.dto.WorkflowDecisionTableRequest;
import com.ruoyi.flowable.domain.dto.WorkflowDecisionTableRequest.*;
import org.flowable.dmn.engine.DmnEngine;
import org.flowable.dmn.engine.DmnEngineConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WorkflowDecisionTableBusinessTest
{
    private static DmnEngine engine;
    private final WorkflowDecisionTableService compiler = new WorkflowDecisionTableService(null, null, null, null);

    @BeforeAll static void open()
    {
        engine = DmnEngineConfiguration.createStandaloneInMemDmnEngineConfiguration()
                .setJdbcUrl("jdbc:h2:mem:decision-table-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
                .setDatabaseSchemaUpdate("true").buildDmnEngine();
    }
    @AfterAll static void close() { engine.close(); }

    @Test void actualDmnEngineEvaluatesBothAmountBranches()
    {
        var request = table("UNIQUE", null);
        var repository = engine.getDmnRepositoryService();
        var first = repository.createDeployment().addDmnBytes("amount.dmn", compiler.compile(request, "amountDecision").getBytes(java.nio.charset.StandardCharsets.UTF_8)).deploy();
        try
        {
            assertThat(evaluate("amountDecision", 500)).containsExactly(Map.of("level", "department"));
            assertThat(evaluate("amountDecision", 2000)).containsExactly(Map.of("level", "manager"));
            assertThat(repository.createDecisionQuery().decisionKey("amountDecision").singleResult().getVersion()).isOne();
        }
        finally { repository.deleteDeployment(first.getId()); }
    }

    @Test void actualEngineAppliesDefaultAndCompilerRejectsAmbiguousUniqueRules()
    {
        var base = table("FIRST", List.of(JsonMapper.shared().getNodeFactory().textNode("fallback")));
        var request = new WorkflowDecisionTableRequest(base.key(), base.name(), "", "FIRST", base.inputs(), base.outputs(),
                List.of(base.rules().get(0)), base.defaultResult(), null);
        var repository = engine.getDmnRepositoryService();
        var deployment = repository.createDeployment().addDmnBytes("default.dmn", compiler.compile(request, "defaultDecision").getBytes(java.nio.charset.StandardCharsets.UTF_8)).deploy();
        try { assertThat(evaluate("defaultDecision", 5000)).containsExactly(Map.of("level", "fallback")); }
        finally { repository.deleteDeployment(deployment.getId()); }

        var unique = table("UNIQUE", null);
        var overlap = new WorkflowDecisionTableRequest(unique.key(), unique.name(), "", "UNIQUE", unique.inputs(), unique.outputs(),
                List.of(unique.rules().get(0), new Rule(unique.rules().get(0).conditions(), unique.rules().get(1).results())), null, null);
        assertThatThrownBy(() -> compiler.compile(overlap, "overlapDecision"))
                .isInstanceOf(ServiceException.class).hasMessageContaining("条件重叠");
    }

    @Test void rejectsExpressionsAndMismatchedColumnTypes()
    {
        var base = table("UNIQUE", null);
        var nodes = JsonMapper.shared().getNodeFactory();
        var unsafe = new Rule(base.rules().get(0).conditions(), List.of(nodes.textNode("${system.exit()}")));
        var request = new WorkflowDecisionTableRequest(base.key(), base.name(), "", "UNIQUE", base.inputs(), base.outputs(), List.of(unsafe), null, null);
        assertThatThrownBy(() -> compiler.compile(request, "bad")).isInstanceOf(ServiceException.class);
        var wrong = new Rule(List.of(new Condition("GT", nodes.textNode("500"))), base.rules().get(0).results());
        var wrongType = new WorkflowDecisionTableRequest(base.key(), base.name(), "", "UNIQUE", base.inputs(), base.outputs(), List.of(wrong), null, null);
        assertThatThrownBy(() -> compiler.compile(wrongType, "bad")).isInstanceOf(ServiceException.class);
    }

    @Test void transactionalTrialCleansResourcesAndFrozenVersionSurvivesSourceV2()
    {
        try (var support = WorkflowFlowableEngineTestSupport.startUsingStandardTableCase("decision-publish", Map.of()))
        {
            var config = new org.flowable.dmn.spring.SpringDmnEngineConfiguration();
            config.setDataSource(support.dataSource());
            config.setTransactionManager(support.transactionManager());
            config.setDatabaseSchemaUpdate("true");
            var dmn = config.buildDmnEngine();
            try
            {
                var identity = mock(WorkflowIdentityResolver.class);
                when(identity.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("100", Set.of()));
                var operations = support.transactionalProxy(new WorkflowEngineOperations(new WorkflowAuthenticationContext(
                        support.processEngine().getIdentityService(), new WorkflowIdentityCodec()), new WorkflowExceptionTranslator(), identity));
                var artifacts = new WorkflowDeploymentArtifactRepository(support.processEngine().getRepositoryService());
                var decisions = new WorkflowDmnDecisionService(operations, dmn.getDmnRepositoryService(), artifacts);
                var tables = new WorkflowDecisionTableService(decisions, dmn.getDmnDecisionService(), dmn.getDmnRepositoryService(), operations);
                var base = table("UNIQUE", null);
                var trial = new WorkflowDecisionTableRequest(base.key(), base.name(), "", base.hitPolicy(), base.inputs(), base.outputs(),
                        base.rules(), null, Map.of("amount", JsonMapper.shared().getNodeFactory().numberNode(500)));
                assertThat(tables.evaluate(trial)).containsExactly(Map.of("level", "department"));
                assertThat(dmn.getDmnRepositoryService().createDeploymentQuery().count()).isZero();
                assertThat(dmn.getDmnHistoryService().createHistoricDecisionExecutionQuery().count()).isZero();
                String source = tables.publish(base);
                var decision = dmn.getDmnRepositoryService().createDecisionQuery().deploymentId(source).singleResult();
                String bpmn = """
                    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="urn:freeze">
                      <process id="decisionProcess" isExecutable="true"><startEvent id="start"/><businessRuleTask id="decide" flowable:rules="%s"/><endEvent id="end"/>
                        <sequenceFlow id="a" sourceRef="start" targetRef="decide"/><sequenceFlow id="b" sourceRef="decide" targetRef="end"/>
                      </process>
                    </definitions>
                    """.formatted(decision.getId());
                var prepared = decisions.prepare(bpmn.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                // 正式流程仓库的父部署和正式资源仓库引用用于验证删除保护。
                var parent = support.processEngine().getRepositoryService().createDeployment()
                        .addString("metadata.txt", "decision freeze parent").deploy();
                var snapshots = support.transactionTemplate().execute(tx -> decisions.freezeSnapshots(parent.getId(), prepared, "100"));
                artifacts.persist(parent.getId(), new WorkflowDeploymentArtifacts(List.of(), List.of(), List.of(), List.of(),
                        List.of(), snapshots, List.of(), List.of()));
                var changed = new WorkflowDecisionTableRequest(base.key(), base.name(), "", base.hitPolicy(), base.inputs(), base.outputs(),
                        List.of(new Rule(base.rules().get(0).conditions(), List.of(JsonMapper.shared().getNodeFactory().textNode("new-level"))), base.rules().get(1)), null, null);
                String second = tables.publish(changed);
                assertThat(dmn.getDmnDecisionService().createExecuteDecisionBuilder().decisionKey(base.key()).parentDeploymentId(parent.getId())
                        .variable("amount", 500).disableHistory().executeDecision()).containsExactly(Map.of("level", "department"));
                assertThat(dmn.getDmnDecisionService().createExecuteDecisionBuilder().decisionKey(base.key())
                        .variable("amount", 500).disableHistory().executeDecision()).containsExactly(Map.of("level", "new-level"));
                assertThatThrownBy(() -> decisions.delete(source)).isInstanceOf(ServiceException.class).hasMessageContaining("冻结引用");
                decisions.delete(second);
                artifacts.delete(parent.getId());
                decisions.deleteFrozenDeployments(snapshots);
                support.processEngine().getRepositoryService().deleteDeployment(parent.getId(), true);
                decisions.delete(source);
                assertThat(dmn.getDmnRepositoryService().createDeploymentQuery().count()).isZero();
            }
            finally { dmn.close(); }
        }
    }

    private List<Map<String, Object>> evaluate(String key, int amount)
    {
        return engine.getDmnDecisionService().createExecuteDecisionBuilder().decisionKey(key).variable("amount", amount).disableHistory().executeDecision();
    }

    private WorkflowDecisionTableRequest table(String policy, List<tools.jackson.databind.JsonNode> defaults)
    {
        var nodes = JsonMapper.shared().getNodeFactory();
        return new WorkflowDecisionTableRequest("amountDecision", "金额审批", "", policy,
                List.of(new Column("amount", "金额", "number")), List.of(new Column("level", "级别", "string")),
                List.of(new Rule(List.of(new Condition("LE", nodes.numberNode(1000))), List.of(nodes.textNode("department"))),
                        new Rule(List.of(new Condition("GT", nodes.numberNode(1000))), List.of(nodes.textNode("manager")))), defaults, null);
    }
}

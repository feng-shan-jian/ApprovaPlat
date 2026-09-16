package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.math.BigDecimal;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

class WorkflowDecisionTableBusinessTest
{
    private static DmnEngine engine;
    private final WorkflowDecisionTableService compiler = new WorkflowDecisionTableService(null, null, null, null);

    @BeforeAll static void open()
    {
        var configuration = DmnEngineConfiguration.createStandaloneInMemDmnEngineConfiguration()
                .setJdbcUrl("jdbc:h2:mem:decision-table-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
                .setDatabaseSchemaUpdate("true");
        configuration.setRuleEngineExecutor(new WorkflowDecisionTableExecutor(configuration));
        engine = configuration.buildDmnEngine();
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
        var wrong = new Rule(List.of(new Condition("GT", nodes.booleanNode(true))), base.rules().get(0).results());
        var wrongType = new WorkflowDecisionTableRequest(base.key(), base.name(), "", "UNIQUE", base.inputs(), base.outputs(), List.of(wrong), null, null);
        assertThatThrownBy(() -> compiler.compile(wrongType, "bad")).isInstanceOf(ServiceException.class);
    }

    @Test void numericLiteralsRemainExactInRealEngine()
    {
        var nodes = JsonMapper.shared().getNodeFactory();
        var amount = new BigDecimal("9999999999.12345678");
        var output = new BigDecimal("9007199254740993");
        var request = new WorkflowDecisionTableRequest("preciseDecision", "精确数值", "", "FIRST",
                List.of(new Column("amount", "金额", "number")), List.of(new Column("result", "结果", "number")),
                List.of(new Rule(List.of(new Condition("EQ", nodes.numberNode(amount))), List.of(nodes.numberNode(output)))),
                List.of(nodes.numberNode(BigDecimal.ZERO)), null);
        var repository = engine.getDmnRepositoryService();
        var deployment = repository.createDeployment().addDmnBytes("precise.dmn",
                compiler.compile(request, request.key()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).deploy();
        try
        {
            var result = engine.getDmnDecisionService().createExecuteDecisionBuilder().decisionKey(request.key())
                    .variable("amount", amount).disableHistory().executeDecision();
            assertThat(result).hasSize(1);
            assertThat(new BigDecimal(result.get(0).get("result").toString())).isEqualByComparingTo(output);
        }
        finally { repository.deleteDeployment(deployment.getId()); }
    }

    @ParameterizedTest
    @CsvSource({"EQ,-1,false", "EQ,0,true", "EQ,1,false", "NE,-1,true", "NE,0,false", "NE,1,true",
            "LT,-1,true", "LT,0,false", "LT,1,false", "LE,-1,true", "LE,0,true", "LE,1,false",
            "GT,-1,false", "GT,0,false", "GT,1,true", "GE,-1,false", "GE,0,true", "GE,1,true"})
    void allNumericComparisonsPreserveAdjacentDecimals(String operator, int offset, boolean matched)
    {
        var base = decimalTable();
        var nodes = JsonMapper.shared().getNodeFactory();
        var request = new WorkflowDecisionTableRequest(base.key(), base.name(), "", "FIRST", base.inputs(), base.outputs(),
                List.of(new Rule(List.of(new Condition(operator, nodes.textNode("9999999999.12345678"))), base.rules().get(0).results())),
                base.defaultResult(), null);
        var amount = new BigDecimal("9999999999.12345678").add(new BigDecimal("0.00000001").multiply(BigDecimal.valueOf(offset)));
        var repository = engine.getDmnRepositoryService();
        var deployment = repository.createDeployment().addString("compare.dmn", compiler.compile(request, request.key())).deploy();
        try
        {
            var audit = engine.getDmnDecisionService().createExecuteDecisionBuilder().decisionKey(request.key())
                    .variable("amount", amount).disableHistory().executeDecisionWithAuditTrail();
            assertThat(audit.isFailed()).isFalse();
            assertThat(audit.getDecisionResult()).containsExactly(Map.of("result", new BigDecimal(matched ? "9007199254740993" : "-9999999999.12345678")));
            assertThat(audit.getDecisionResultTypes()).containsEntry("result", "number");
        }
        finally { repository.deleteDeployment(deployment.getId()); }
    }

    @Test void jsonDecimalTextsCompileExactlyAndUniqueChecksUseTheSamePrecision()
    {
        var base = decimalTable();
        assertThat(compiler.compile(base, base.key())).contains("9999999999.12345678", "9007199254740993", "-9999999999.12345678");
        var nodes = JsonMapper.shared().getNodeFactory();
        var adjacent = new Rule(List.of(new Condition("EQ", nodes.textNode("9999999999.12345679"))), base.rules().get(0).results());
        var unique = new WorkflowDecisionTableRequest(base.key(), base.name(), "", "UNIQUE", base.inputs(), base.outputs(),
                List.of(base.rules().get(0), adjacent), null, null);
        assertThat(compiler.compile(unique, "adjacent")).contains("9999999999.12345679");
        var overlap = new WorkflowDecisionTableRequest(base.key(), base.name(), "", "UNIQUE", base.inputs(), base.outputs(),
                List.of(base.rules().get(0), base.rules().get(0)), null, null);
        assertThatThrownBy(() -> compiler.compile(overlap, "overlap")).isInstanceOf(ServiceException.class).hasMessageContaining("条件重叠");
    }

    @Test void existingJsonNumberCallersStillCompileWithoutChangingDtoShape()
    {
        String json = JsonMapper.shared().writeValueAsString(decimalTable())
                .replace("\"9999999999.12345678\"", "1234.5").replace("\"9007199254740993\"", "9007199254740993");
        var request = JsonMapper.shared().readValue(json, WorkflowDecisionTableRequest.class);
        assertThat(request.testValues().get("amount").isNumber()).isTrue();
        assertThat(compiler.compile(request, "legacyNumber")).contains("1234.5</text>", "9007199254740993</text>");
    }

    @Test void malformedAndOutOfRangeNumbersAreRejectedInEveryInputPosition()
    {
        var base = decimalTable();
        var nodes = JsonMapper.shared().getNodeFactory();
        for (String invalid : List.of("", " ", "NaN", "Infinity", "0x10", "1+2", "${amount}", "1e9999999999999",
                "1234567890123456789", "0.000000001", "1e9"))
        {
            var value = nodes.textNode(invalid);
            var condition = new WorkflowDecisionTableRequest(base.key(), base.name(), "", "FIRST", base.inputs(), base.outputs(),
                    List.of(new Rule(List.of(new Condition("EQ", value)), base.rules().get(0).results())), base.defaultResult(), base.testValues());
            var output = new WorkflowDecisionTableRequest(base.key(), base.name(), "", "FIRST", base.inputs(), base.outputs(),
                    List.of(new Rule(base.rules().get(0).conditions(), List.of(value))), base.defaultResult(), base.testValues());
            var fallback = new WorkflowDecisionTableRequest(base.key(), base.name(), "", "FIRST", base.inputs(), base.outputs(),
                    base.rules(), List.of(value), base.testValues());
            var trial = new WorkflowDecisionTableRequest(base.key(), base.name(), "", "FIRST", base.inputs(), base.outputs(),
                    base.rules(), base.defaultResult(), Map.of("amount", value));
            for (var request : List.of(condition, output, fallback))
                assertThatThrownBy(() -> compiler.compile(request, "invalid")).as(invalid).isInstanceOf(ServiceException.class);
            assertThatThrownBy(() -> compiler.evaluate(trial)).as(invalid).isInstanceOf(ServiceException.class);
        }
    }

    @Test void maximumNegativeScaleRemainsSupportedAcrossCompileAndEngine()
    {
        var base = decimalTable();
        var value = JsonMapper.shared().getNodeFactory().textNode("123456789012345678e8");
        var request = new WorkflowDecisionTableRequest(base.key(), base.name(), "", "UNIQUE", base.inputs(), base.outputs(),
                List.of(new Rule(List.of(new Condition("EQ", value)), List.of(value))), null, null);
        String xml = compiler.compile(request, "maximum");
        var repository = engine.getDmnRepositoryService();
        var deployment = repository.createDeployment().addString("maximum.dmn", xml).deploy();
        try
        {
            var result = engine.getDmnDecisionService().createExecuteDecisionBuilder().decisionKey("maximum")
                    .variable("amount", new BigDecimal("123456789012345678e8")).disableHistory().executeDecision();
            assertThat((BigDecimal) result.get(0).get("result")).isEqualByComparingTo("12345678901234567800000000");
        }
        finally { repository.deleteDeployment(deployment.getId()); }
    }

    @Test void unmarkedLegacyDecimalsKeepNativeEngineBehaviorAndForgedV2ExpressionsFail()
    {
        String xml = compiler.compile(decimalTable(), "legacy");
        var repository = engine.getDmnRepositoryService();
        var legacy = repository.createDeployment().addString("legacy.dmn", xml.replace("exporterVersion=\"2\"", "exporterVersion=\"1\"")).deploy();
        try
        {
            var result = engine.getDmnDecisionService().createExecuteDecisionBuilder().decisionKey("legacy")
                    .variable("amount", new BigDecimal("9999999999.12345678")).disableHistory().executeDecision();
            assertThat(result.get(0).get("result")).isInstanceOf(Double.class);
            assertThat(result.get(0).get("result")).isEqualTo(Double.valueOf("-9999999999.12345678"));
        }
        finally { repository.deleteDeployment(legacy.getId()); }
        for (String forged : List.of(xml.replace("9007199254740993", "1 + 2"), xml.replace("9999999999.12345678</text>", "amount + 1</text>")))
        {
            var deployment = repository.createDeployment().addString("forged.dmn", forged).deploy();
            try
            {
                assertThatThrownBy(() -> engine.getDmnDecisionService().createExecuteDecisionBuilder().decisionKey("legacy")
                        .variable("amount", BigDecimal.ZERO).disableHistory().executeDecision()).isInstanceOf(ServiceException.class);
            }
            finally { repository.deleteDeployment(deployment.getId()); }
        }
    }

    @Test void transactionalTrialCleansResourcesAndFrozenVersionSurvivesSourceV2()
    {
        try (var support = WorkflowFlowableEngineTestSupport.startUsingStandardTableCase("decision-publish", Map.of()))
        {
            var config = new org.flowable.dmn.spring.SpringDmnEngineConfiguration();
            config.setDataSource(support.dataSource());
            config.setTransactionManager(support.transactionManager());
            config.setDatabaseSchemaUpdate("true");
            new com.ruoyi.flowable.config.WorkflowDecisionTableConfiguration().workflowDecisionTableConfigurer().configure(config);
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
                assertThat(tables.evaluate(decimalTable())).containsExactly(Map.of("result", new BigDecimal("9007199254740993")));
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

    static WorkflowDecisionTableRequest decimalTable()
    {
        return JsonMapper.shared().readValue("""
            {"key":"preciseDecision","name":"精确数值","category":"","hitPolicy":"FIRST",
             "inputs":[{"key":"amount","label":"金额","type":"number"}],
             "outputs":[{"key":"result","label":"结果","type":"number"}],
             "rules":[{"conditions":[{"operator":"EQ","value":"9999999999.12345678"}],"results":["9007199254740993"]}],
             "defaultResult":["-9999999999.12345678"],"testValues":{"amount":"9999999999.12345678"}}
            """, WorkflowDecisionTableRequest.class);
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

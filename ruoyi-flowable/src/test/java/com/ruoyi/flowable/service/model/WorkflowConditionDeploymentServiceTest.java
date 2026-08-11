package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.ExclusiveGateway;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.InclusiveGateway;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployConditionRule;
import com.ruoyi.flowable.service.model.WorkflowControlledLoopFormField.Kind;
import com.ruoyi.flowable.service.model.WorkflowControlledLoopFormField.NumericKind;

/**
 * 条件分支作者规则校验、固定表达式编译和不可变快照测试。
 */
class WorkflowConditionDeploymentServiceTest
{
    private WorkflowConditionDeploymentService service;

    /**
     * 为每个测试创建隔离的快照数据访问模拟。
     * @return void，测试服务不共享调用状态
     */
    @BeforeEach
    void setUp()
    {
        service = new WorkflowConditionDeploymentService();
    }

    /**
     * 验证金额、枚举、布尔、文本和 AND/OR 规则编译为固定令牌表达式，并剥离作者 JSON。
     * @return void，编译资源或快照与作者规则不一致时测试失败
     */
    @Test
    void compilesTypedGroupsAndPersistsImmutableSnapshots()
    {
        Fixture fixture = fixture(false, validComplexRule(), amountFallbackRule(), true);

        WorkflowPreparedConditionDeployment prepared = service.prepare(fixture.document(),
                fixture.xml(), List.of(schema()), "7");

        assertThat(prepared.snapshots()).hasSize(3);
        assertThat(prepared.snapshots()).filteredOn(snapshot -> !snapshot.getDefaultFlow())
                .allSatisfy(snapshot ->
                {
                    assertThat(snapshot.getRuleJson()).contains("fieldType", "combinator");
                    assertThat(snapshot.getCelConfigJson()).contains("conditionMatched", "rule1");
                    assertThat(snapshot.getSnapshotChecksum()).hasSize(64);
                });
        BpmnModel compiled = parse(prepared.compiledBpmn());
        FlowNode gateway = (FlowNode) compiled.getProcessById("expense").getFlowElement("gateway");
        assertThat(gateway.getOutgoingFlows()).filteredOn(flow -> !"flow_default".equals(flow.getId()))
                .allSatisfy(flow ->
                {
                    assertThat(flow.getConditionExpression()).matches(
                            "\\$\\{workflowConditionRouter\\.matches\\(execution,'[0-9a-f]{24}','[0-9a-f]{24}'\\)\\}");
                    assertThat(WorkflowConditionRuleBpmnContract.hasReservedProperty(flow)).isFalse();
                });
        assertThat(gateway.getOutgoingFlows()).filteredOn(flow -> "flow_default".equals(flow.getId()))
                .singleElement().satisfies(flow -> assertThat(flow.getConditionExpression()).isNull());

    }

    /**
     * 验证 MySQL JSON 重排对象键和空白后仍能复算同一快照摘要。
     * @return void，摘要依赖 JDBC JSON 原始文本时测试失败
     */
    @Test
    void keepsSnapshotChecksumStableAcrossMySqlJsonReordering()
    {
        Fixture fixture = fixture(false, validComplexRule(), amountFallbackRule(), true);
        WorkflowPreparedConditionDeployment prepared = service.prepare(fixture.document(),
                fixture.xml(), List.of(schema()), "7");
        WfDeployConditionRule defaultSnapshot = prepared.snapshots().stream()
                .filter(WfDeployConditionRule::getDefaultFlow)
                .findFirst().orElseThrow();
        String expected = defaultSnapshot.getSnapshotChecksum();

        defaultSnapshot.setRuleJson("{ \"version\" : 1, \"default\" : true }");

        assertThat(WorkflowConditionDeploymentService.snapshotChecksum(defaultSnapshot))
                .isEqualTo(expected);
    }

    /**
     * 验证默认遗漏、规则缺失、重复规则和普通用户手写表达式均在部署前失败关闭。
     * @return void，任一不完整网关被接受时测试失败
     */
    @Test
    void rejectsIncompleteAmbiguousAndAuthoredExpressionGateways()
    {
        Fixture missingDefault = fixture(false, amountRule("GT", 100), amountRule("LT", 0), false);
        assertRejected(missingDefault, "默认分支");

        Fixture missingRule = fixture(false, amountRule("GT", 100), null, true);
        assertRejected(missingRule, "规则不完整");

        String duplicate = amountRule("GT", 100);
        Fixture duplicateRule = fixture(false, duplicate, duplicate, true);
        assertRejected(duplicateRule, "完全相同");

        Fixture expression = fixture(false, amountRule("GT", 100), amountRule("LT", 0), true);
        expression.gateway().getOutgoingFlows().get(0).setConditionExpression("${amount > 100}");
        assertRejected(expression, "不能在网关出线输入任意表达式");
    }

    /**
     * 验证布尔拓扑相同但字段和值不同的两条单规则不是重复分支。
     * @return void，后端错误地只按 CEL rule1 拓扑去重时测试失败
     */
    @Test
    void acceptsDistinctAtomicRulesWithSameCelTopology()
    {
        Fixture fixture = fixture(true, amountRule("GTE", 5000),
                atomicRule("urgent", "EQ", true), true);

        service.validate(fixture.document(), List.of(schema()));
    }

    /**
     * 验证字段不存在、运算符错配、值类型错误和枚举越界均被正式表单契约拒绝。
     * @return void，作者配置可绕过字段或类型约束时测试失败
     */
    @Test
    void rejectsMissingFieldsAndInvalidTypedValues()
    {
        assertRejected(fixture(false, atomicRule("unknown", "EQ", "x"), amountFallbackRule(), true),
                "必须来自当前流程正式表单");
        assertRejected(fixture(false, atomicRule("urgent", "GT", true), amountFallbackRule(), true),
                "运算符与字段类型不匹配");
        assertRejected(fixture(false, atomicRule("amount", "GT", "100"), amountFallbackRule(), true),
                "数值条件值类型不合法");
        assertRejected(fixture(false, atomicRule("level", "EQ", "secret"), amountFallbackRule(), true),
                "不符合正式表单字段约束");
    }

    /**
     * 使用正式字段目录执行作者规则校验并断言稳定业务错误。
     * @param fixture Fixture，待校验作者模型
     * @param message String，期望错误摘要
     * @return void，模型被接受或提示不一致时测试失败
     */
    private void assertRejected(Fixture fixture, String message)
    {
        assertThatThrownBy(() -> service.validate(fixture.document(), List.of(schema())))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(message);
    }

    /**
     * 创建排他或包容网关及三条真实出线，默认分支仍保存固定 default 规则属性。
     * @param inclusive boolean，true 创建包容网关，false 创建排他网关
     * @param firstRule String，第一条非默认出线作者 JSON
     * @param secondRule String，第二条非默认出线作者 JSON，可为空
     * @param withDefault boolean，是否建立唯一默认引用
     * @return Fixture，同源作者模型、XML 和网关
     */
    private Fixture fixture(boolean inclusive, String firstRule, String secondRule,
            boolean withDefault)
    {
        BpmnModel model = new BpmnModel();
        Process process = new Process();
        process.setId("expense");
        process.setExecutable(true);
        FlowNode gateway = inclusive ? new InclusiveGateway() : new ExclusiveGateway();
        gateway.setId("gateway");
        process.addFlowElement(gateway);
        addBranch(process, gateway, "flow_high", "高额审批", firstRule);
        addBranch(process, gateway, "flow_other", "其他审批", secondRule);
        addBranch(process, gateway, "flow_default", "默认处理", "{\"version\":1,\"default\":true}");
        if (withDefault)
        {
            if (gateway instanceof ExclusiveGateway exclusive)
            {
                exclusive.setDefaultFlow("flow_default");
            }
            else
            {
                ((InclusiveGateway) gateway).setDefaultFlow("flow_default");
            }
        }
        model.addProcess(process);
        byte[] xml = new BpmnXMLConverter().convertToXML(model);
        return new Fixture(new WorkflowBpmnDocument(model,
                new String(xml, StandardCharsets.UTF_8), List.of()), xml, gateway);
    }

    /**
     * 向网关添加有完整正反向引用的顺序流和终点。
     * @param process Process，所属可执行流程
     * @param gateway FlowNode，出线源网关
     * @param flowId String，顺序流标识
     * @param name String，业务分支名称
     * @param config String，受控作者 JSON，可为空以构造缺失场景
     * @return void，新增元素直接写入测试模型
     */
    private void addBranch(Process process, FlowNode gateway, String flowId, String name,
            String config)
    {
        EndEvent target = new EndEvent();
        target.setId(flowId + "_end");
        SequenceFlow flow = new SequenceFlow(gateway.getId(), target.getId());
        flow.setId(flowId);
        flow.setName(name);
        flow.setSourceFlowElement(gateway);
        flow.setTargetFlowElement(target);
        if (config != null)
        {
            addConditionProperty(flow, config);
        }
        gateway.setOutgoingFlows(append(gateway.getOutgoingFlows(), flow));
        target.setIncomingFlows(new ArrayList<>(List.of(flow)));
        process.addFlowElement(target);
        process.addFlowElement(flow);
    }

    /**
     * 向顺序流写入唯一平台条件属性。
     * @param flow SequenceFlow，目标网关出线
     * @param config String，版本化规则 JSON
     * @return void，生成 Flowable properties 标准扩展结构
     */
    private void addConditionProperty(SequenceFlow flow, String config)
    {
        ExtensionElement property = new ExtensionElement();
        property.setName("property");
        property.setNamespace("http://flowable.org/bpmn");
        property.addAttribute(new ExtensionAttribute("name",
                WorkflowConditionRuleBpmnContract.CONFIG_PROPERTY));
        property.addAttribute(new ExtensionAttribute("value", config));
        ExtensionElement container = new ExtensionElement();
        container.setName("properties");
        container.setNamespace("http://flowable.org/bpmn");
        container.setChildElements(new HashMap<>(Map.of("property", List.of(property))));
        flow.addExtensionElement(container);
    }

    /**
     * 创建包含本次四类条件字段的正式表单 schema。
     * @return WorkflowControlledLoopFormSchema，expense/start 字段快照
     */
    private WorkflowControlledLoopFormSchema schema()
    {
        Map<String, WorkflowControlledLoopFormField> fields = Map.of(
                "amount", new WorkflowControlledLoopFormField("amount", Kind.NUMBER, 0, 128,
                        BigDecimal.ZERO, new BigDecimal("1000000"), NumericKind.DECIMAL, Set.of()),
                "level", new WorkflowControlledLoopFormField("level", Kind.SCALAR, 1, 16,
                        null, null, NumericKind.DECIMAL, Set.of("normal", "urgent")),
                "urgent", new WorkflowControlledLoopFormField("urgent", Kind.BOOLEAN, 0, 5,
                        null, null, NumericKind.DECIMAL, Set.of()),
                "description", new WorkflowControlledLoopFormField("description", Kind.TEXT, 1, 200,
                        null, null, NumericKind.DECIMAL, Set.of()));
        return new WorkflowControlledLoopFormSchema("expense", "start", fields);
    }

    /** @return String，覆盖四种字段及两级 AND/OR 的有效规则。 */
    private String validComplexRule()
    {
        return "{\"version\":1,\"default\":false,\"combinator\":\"OR\",\"groups\":["
                + "{\"combinator\":\"AND\",\"rules\":["
                + "{\"field\":\"amount\",\"operator\":\"GTE\",\"value\":5000},"
                + "{\"field\":\"level\",\"operator\":\"EQ\",\"value\":\"urgent\"}]},"
                + "{\"combinator\":\"AND\",\"rules\":["
                + "{\"field\":\"urgent\",\"operator\":\"EQ\",\"value\":true},"
                + "{\"field\":\"description\",\"operator\":\"CONTAINS\",\"value\":\"合同\"}]}]}";
    }

    /** @return String，合法的金额兜底非默认规则。 */
    private String amountFallbackRule()
    {
        return amountRule("LT", 5000);
    }

    /** @param operator String，数值运算符。 @param value Number，比较值。 @return String，金额规则 JSON。 */
    private String amountRule(String operator, Number value)
    {
        return atomicRule("amount", operator, value);
    }

    /**
     * 创建只含一个原子条件的作者 JSON。
     * @param field String，字段变量名
     * @param operator String，受控运算符
     * @param value Object，JSON 标量值
     * @return String，版本化条件规则 JSON
     */
    private String atomicRule(String field, String operator, Object value)
    {
        String encoded = value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
        return "{\"version\":1,\"default\":false,\"combinator\":\"AND\",\"groups\":["
                + "{\"combinator\":\"AND\",\"rules\":[{\"field\":\"" + field
                + "\",\"operator\":\"" + operator + "\",\"value\":" + encoded + "}]}]}";
    }

    /** @param current List&lt;SequenceFlow&gt;，当前出线。 @param flow SequenceFlow，新出线。 @return List&lt;SequenceFlow&gt;，新列表。 */
    private List<SequenceFlow> append(List<SequenceFlow> current, SequenceFlow flow)
    {
        List<SequenceFlow> result = new ArrayList<>(current == null ? List.of() : current);
        result.add(flow);
        return result;
    }

    /** @param xml byte[]，编译 BPMN。 @return BpmnModel，解析后的公共模型。 */
    private BpmnModel parse(byte[] xml)
    {
        return new BpmnXMLConverter().convertToBpmnModel(
                () -> new ByteArrayInputStream(xml), true, true);
    }

    /** @param document WorkflowBpmnDocument，作者文档。 @param xml byte[]，作者 XML。 @param gateway FlowNode，作者网关。 */
    private record Fixture(WorkflowBpmnDocument document, byte[] xml, FlowNode gateway) { }
}

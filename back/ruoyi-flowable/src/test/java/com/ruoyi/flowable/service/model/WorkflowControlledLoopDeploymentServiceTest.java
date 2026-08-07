package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
import org.flowable.bpmn.model.GraphicInfo;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.UserTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.mapper.WfDeployControlledLoopMapper;
import com.ruoyi.flowable.service.model.WorkflowControlledLoopFormField.Kind;
import com.ruoyi.flowable.service.model.WorkflowControlledLoopFormField.NumericKind;

/**
 * 受控循环部署语义、固定网关转换和 BPMN DI 单元测试。
 */
class WorkflowControlledLoopDeploymentServiceTest
{
    private WorkflowControlledLoopDeploymentService service;

    /**
     * 创建不访问数据库的部署编译服务。
     * @return 无返回值，后续测试只验证 prepare 阶段
     */
    @BeforeEach
    void setUp()
    {
        service = new WorkflowControlledLoopDeploymentService(
                mock(WfDeployControlledLoopMapper.class));
    }

    /**
     * 验证作者单出口任务被转换为固定默认退出网关并同步生成完整 DI。
     * @return 无返回值，执行拓扑、条件、属性剥离或 Viewer 图形不一致时测试失败
     */
    @Test
    void compilesFixedGatewayLoopAndDiagramInterchange()
    {
        Fixture fixture = fixture("decision", "redo", "approved", "3", true);
        WorkflowPreparedControlledLoopDeployment prepared = service.prepare(
                fixture.document(), fixture.xml(), List.of(schema(new WorkflowControlledLoopFormField(
                        "decision", Kind.SCALAR, 0, 128, null, null,
                        NumericKind.DECIMAL, Set.of("redo", "approved")))), "9");

        BpmnModel compiled = parse(prepared.compiledBpmn());
        Process process = compiled.getProcessById("process_1");
        UserTask task = (UserTask) process.getFlowElement("review");
        ExclusiveGateway gateway = (ExclusiveGateway) process.getFlowElements().stream()
                .filter(ExclusiveGateway.class::isInstance)
                .findFirst().orElseThrow();
        SequenceFlow repeat = gateway.getOutgoingFlows().stream()
                .filter(flow -> "review".equals(flow.getTargetRef()))
                .findFirst().orElseThrow();
        SequenceFlow exit = gateway.getOutgoingFlows().stream()
                .filter(flow -> "end".equals(flow.getTargetRef()))
                .findFirst().orElseThrow();

        assertThat(task.getOutgoingFlows()).hasSize(1);
        assertThat(task.getOutgoingFlows().get(0).getTargetRef()).isEqualTo(gateway.getId());
        assertThat(gateway.getDefaultFlow()).isEqualTo(exit.getId());
        assertThat(repeat.getConditionExpression()).contains("__approva_loop_route_");
        assertThat(WorkflowControlledLoopBpmnContract.hasReservedProperties(task)).isFalse();
        assertThat(compiled.getGraphicInfo(gateway.getId())).isNotNull();
        assertThat(compiled.getFlowLocationGraphicInfo(repeat.getId())).hasSize(4);
        assertThat(compiled.getFlowLocationGraphicInfo(exit.getId())).hasSizeGreaterThanOrEqualTo(2);
        assertThat(prepared.snapshots()).singleElement().satisfies(snapshot ->
        {
            assertThat(snapshot.getRepeatValue()).isEqualTo("redo");
            assertThat(snapshot.getExitValue()).isEqualTo("approved");
            assertThat(snapshot.getMaxIterations()).isEqualTo(3);
        });
    }

    /**
     * 验证数值条件按正式表单整数和范围约束规范化后持久化。
     * @return 无返回值，小数绕过整数约束或等价十进制未归一时测试失败
     */
    @Test
    void normalizesNumericConditionsUsingFormalFormContract()
    {
        Fixture fixture = fixture("decision", "1.0", "2.00", "4", false);
        WorkflowControlledLoopFormField field = new WorkflowControlledLoopFormField(
                "decision", Kind.NUMBER, 0, 128, BigDecimal.ONE,
                BigDecimal.TEN, NumericKind.INTEGER, Set.of());

        WorkflowPreparedControlledLoopDeployment prepared = service.prepare(
                fixture.document(), fixture.xml(), List.of(schema(field)), "9");

        assertThat(prepared.snapshots()).singleElement().satisfies(snapshot ->
        {
            assertThat(snapshot.getRepeatValue()).isEqualTo("1");
            assertThat(snapshot.getExitValue()).isEqualTo("2");
        });
    }

    /**
     * 验证非标量或不属于静态枚举的条件在 Flowable 部署前失败关闭。
     * @return 无返回值，运行时才暴露数据形态错误时测试失败
     */
    @Test
    void rejectsMissingNonScalarAndOutOfEnumDecisionFieldsBeforeDeployment()
    {
        Fixture fixture = fixture("decision", "redo", "approved", "3", false);
        WorkflowControlledLoopFormSchema missingScalar = new WorkflowControlledLoopFormSchema(
                "process_1", "review", Map.of());
        assertThatThrownBy(() -> service.prepare(fixture.document(), fixture.xml(),
                List.of(missingScalar), "9"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("可写标量字段");

        WorkflowControlledLoopFormField enumField = new WorkflowControlledLoopFormField(
                "decision", Kind.SCALAR, 0, 128, null, null,
                NumericKind.DECIMAL, Set.of("redo", "reject"));
        assertThatThrownBy(() -> service.prepare(fixture.document(), fixture.xml(),
                List.of(schema(enumField)), "9"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("可选范围");
    }

    /**
     * 创建带固定受控属性、单出口拓扑和可选 DI 的作者模型。
     * @param variable String，判断字段变量名
     * @param repeatValue String，再次进入条件值
     * @param exitValue String，退出条件值
     * @param maxIterations String，最大轮次文本
     * @param withDi boolean，是否添加作者 BPMN DI
     * @return Fixture，作者文档和同源 XML 字节
     */
    private Fixture fixture(String variable, String repeatValue, String exitValue,
            String maxIterations, boolean withDi)
    {
        BpmnModel model = new BpmnModel();
        Process process = new Process();
        process.setId("process_1");
        process.setExecutable(true);
        UserTask review = new UserTask();
        review.setId("review");
        review.setName("复核");
        addControlledLoopProperties(review, variable, repeatValue, exitValue, maxIterations);
        EndEvent end = new EndEvent();
        end.setId("end");
        SequenceFlow exit = new SequenceFlow("review", "end");
        exit.setId("review_to_end");
        exit.setSourceFlowElement(review);
        exit.setTargetFlowElement(end);
        review.setOutgoingFlows(new ArrayList<>(List.of(exit)));
        end.setIncomingFlows(new ArrayList<>(List.of(exit)));
        process.addFlowElement(review);
        process.addFlowElement(end);
        process.addFlowElement(exit);
        model.addProcess(process);
        if (withDi)
        {
            model.addGraphicInfo("review", new GraphicInfo(200, 150, 100, 80));
            model.addGraphicInfo("end", new GraphicInfo(500, 172, 36, 36));
            model.addFlowGraphicInfoList("review_to_end", List.of(
                    new GraphicInfo(300, 190), new GraphicInfo(500, 190)));
        }
        byte[] xml = new BpmnXMLConverter().convertToXML(model);
        return new Fixture(new WorkflowBpmnDocument(model,
                new String(xml, StandardCharsets.UTF_8), List.of()), xml);
    }

    /**
     * 向用户任务写入平台固定的五项作者循环属性。
     * @param task UserTask，作者用户任务
     * @param variable String，判断变量
     * @param repeatValue String，再次进入值
     * @param exitValue String，退出值
     * @param maxIterations String，最大轮次
     * @return void，属性以 Flowable properties 扩展结构写入
     */
    private void addControlledLoopProperties(UserTask task, String variable,
            String repeatValue, String exitValue, String maxIterations)
    {
        Map<String, String> values = Map.of(
                WorkflowControlledLoopBpmnContract.ENABLED, "true",
                WorkflowControlledLoopBpmnContract.DECISION_VARIABLE, variable,
                WorkflowControlledLoopBpmnContract.REPEAT_VALUE, repeatValue,
                WorkflowControlledLoopBpmnContract.EXIT_VALUE, exitValue,
                WorkflowControlledLoopBpmnContract.MAX_ITERATIONS, maxIterations);
        ExtensionElement container = new ExtensionElement();
        container.setName("properties");
        container.setNamespace("http://flowable.org/bpmn");
        Map<String, List<ExtensionElement>> children = new HashMap<>();
        List<ExtensionElement> properties = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet())
        {
            ExtensionElement property = new ExtensionElement();
            property.setName("property");
            property.setNamespace("http://flowable.org/bpmn");
            property.addAttribute(new ExtensionAttribute("name", entry.getKey()));
            property.addAttribute(new ExtensionAttribute("value", entry.getValue()));
            properties.add(property);
        }
        children.put("property", properties);
        container.setChildElements(children);
        task.addExtensionElement(container);
    }

    /**
     * 创建当前测试节点的正式标量字段 schema。
     * @param field WorkflowControlledLoopFormField，判断字段契约
     * @return WorkflowControlledLoopFormSchema，process_1/review 节点 schema
     */
    private WorkflowControlledLoopFormSchema schema(WorkflowControlledLoopFormField field)
    {
        return new WorkflowControlledLoopFormSchema("process_1", "review",
                Map.of(field.name(), field));
    }

    /**
     * 解析编译后的 BPMN XML。
     * @param xml byte[]，部署编译结果
     * @return BpmnModel，Flowable 公共模型
     */
    private BpmnModel parse(byte[] xml)
    {
        return new BpmnXMLConverter().convertToBpmnModel(
                () -> new ByteArrayInputStream(xml), true, true);
    }

    /**
     * @param document WorkflowBpmnDocument，作者模型
     * @param xml byte[]，作者 XML
     */
    private record Fixture(WorkflowBpmnDocument document, byte[] xml)
    {
    }
}

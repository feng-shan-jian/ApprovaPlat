package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployParticipantRule;
import com.ruoyi.flowable.mapper.WfDeployParticipantRuleMapper;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;

/**
 * 参与者规则部署编译、目录资格校验和不可变快照测试。
 */
class WorkflowParticipantRuleDeploymentServiceTest
{
    private WfDeployParticipantRuleMapper ruleMapper;
    private WorkflowIdentityMapper identityMapper;
    private WorkflowParticipantRuleDeploymentService service;

    /**
     * 为每个测试创建独立 Mapper 替身和部署服务。
     *
     * @return void，后续测试不共享调用状态
     */
    @BeforeEach
    void setUp()
    {
        ruleMapper = mock(WfDeployParticipantRuleMapper.class);
        identityMapper = mock(WorkflowIdentityMapper.class);
        service = new WorkflowParticipantRuleDeploymentService(ruleMapper, identityMapper);
    }

    /**
     * 验证真实 Flowable XML 输入冻结公开发起和发起人任务规则，并从执行资源剥离作者属性。
     *
     * @return void，快照数量、内容或执行 BPMN 隔离漂移时测试失败
     */
    @Test
    void freezesInitialRulesAndStripsAuthorPropertiesFromExecutableBpmn()
    {
        Fixture fixture = fixture("STARTER", "", "");

        WorkflowPreparedParticipantRuleDeployment prepared = service.prepare(
                fixture.document(), fixture.xml(), Map.of("process_1", Set.of()), "9");

        assertThat(prepared.snapshots()).hasSize(2);
        assertThat(prepared.snapshots()).anySatisfy(rule ->
        {
            assertThat(rule.getRuleScope()).isEqualTo("START");
            assertThat(rule.getRuleType()).isEqualTo("PUBLIC");
            assertThat(rule.getRuleVersion()).isEqualTo(1);
        }).anySatisfy(rule ->
        {
            assertThat(rule.getRuleScope()).isEqualTo("TASK");
            assertThat(rule.getRuleType()).isEqualTo("STARTER");
            assertThat(rule.getAssignmentMode()).isEqualTo("ASSIGNEE");
        });

        BpmnModel executable = parse(prepared.compiledBpmn());
        Process process = executable.getProcessById("process_1");
        UserTask task = (UserTask) process.getFlowElement("review");
        assertThat(WorkflowParticipantRuleBpmnContract.hasReservedProperties(process)).isFalse();
        assertThat(WorkflowParticipantRuleBpmnContract.hasReservedProperties(task)).isFalse();
    }

    /**
     * 验证固定用户必须来自实时审批资格目录，成功后部署主键写入每条快照且批量行数精确。
     *
     * @return void，失效目标可部署或快照写入不完整时测试失败
     */
    @Test
    void validatesApprovalTargetAndPersistsExactSnapshots()
    {
        Fixture fixture = fixture("FIXED_USER", "7", "");
        when(identityMapper.selectApprovalEligibleUserIdsByUserIds(List.of(7L)))
                .thenReturn(List.of(7L));
        WorkflowPreparedParticipantRuleDeployment prepared = service.prepare(
                fixture.document(), fixture.xml(), Map.of("process_1", Set.of()), "9");
        when(ruleMapper.insertBatch(anyList())).thenReturn(2);

        service.persist("deployment-1", prepared);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WfDeployParticipantRule>> captor = ArgumentCaptor.forClass(List.class);
        verify(ruleMapper).insertBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(2)
                .allMatch(rule -> "deployment-1".equals(rule.getDeployId()));

        when(identityMapper.selectApprovalEligibleUserIdsByUserIds(List.of(7L)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.prepare(
                fixture.document(), fixture.xml(), Map.of("process_1", Set.of()), "9"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("固定办理人");
    }

    /**
     * 验证 FORM_USER 只能引用当前流程正式部署表单变量，缺失字段在 Flowable 部署前失败。
     *
     * @return void，任意变量可进入任务解析时测试失败
     */
    @Test
    void validatesFormalFormUserFieldBeforeDeployment()
    {
        Fixture fixture = fixture("FORM_USER", "", "approverId");

        assertThatThrownBy(() -> service.prepare(
                fixture.document(), fixture.xml(), Map.of("process_1", Set.of()), "9"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("正式表单");

        WorkflowPreparedParticipantRuleDeployment prepared = service.prepare(
                fixture.document(), fixture.xml(), Map.of("process_1", Set.of("approverId")), "9");
        assertThat(prepared.snapshots()).anySatisfy(rule ->
        {
            if ("TASK".equals(rule.getRuleScope()))
            {
                assertThat(rule.getRuleType()).isEqualTo("FORM_USER");
                assertThat(rule.getFormField()).isEqualTo("approverId");
            }
        });
    }

    /**
     * 创建经 Flowable XML 序列化和重新解析的单实例流程。
     *
     * @param type String，任务规则类型
     * @param targetIds String，规范目标主键文本
     * @param formField String，FORM_USER 变量或空串
     * @return Fixture，作者文档与同源 UTF-8 XML
     */
    private Fixture fixture(String type, String targetIds, String formField)
    {
        BpmnModel model = new BpmnModel();
        Process process = new Process();
        process.setId("process_1");
        process.setName("参与者规则验收");
        process.setExecutable(true);
        StartEvent start = new StartEvent();
        start.setId("start");
        UserTask task = new UserTask();
        task.setId("review");
        task.setName("审批");
        EndEvent end = new EndEvent();
        end.setId("end");
        WorkflowParticipantRuleBpmnContract.addInitialAuthorRules(process, task);
        if (!"STARTER".equals(type))
        {
            WorkflowParticipantRuleBpmnContract.removeAuthorProperties(task);
            addTaskProperties(task, type, targetIds, formField);
        }
        SequenceFlow first = flow("flow_start_review", start, task);
        SequenceFlow second = flow("flow_review_end", task, end);
        start.setOutgoingFlows(new ArrayList<>(List.of(first)));
        task.setIncomingFlows(new ArrayList<>(List.of(first)));
        task.setOutgoingFlows(new ArrayList<>(List.of(second)));
        end.setIncomingFlows(new ArrayList<>(List.of(second)));
        process.addFlowElement(start);
        process.addFlowElement(first);
        process.addFlowElement(task);
        process.addFlowElement(second);
        process.addFlowElement(end);
        model.addProcess(process);

        byte[] xml = new BpmnXMLConverter().convertToXML(model);
        BpmnModel parsed = parse(xml);
        String source = new String(xml, StandardCharsets.UTF_8);
        return new Fixture(new WorkflowBpmnDocument(parsed, source, List.of()), xml);
    }

    /**
     * 写入字段完整的任务作者规则。
     *
     * @param task UserTask，目标单实例任务
     * @param type String，受控任务规则类型
     * @param targetIds String，规范目标文本
     * @param formField String，表单变量或空串
     * @return void，规则写入 Flowable properties
     */
    private void addTaskProperties(UserTask task, String type,
            String targetIds, String formField)
    {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put(WorkflowParticipantRuleBpmnContract.TASK_VERSION, "1");
        values.put(WorkflowParticipantRuleBpmnContract.TASK_TYPE, type);
        values.put(WorkflowParticipantRuleBpmnContract.TASK_TARGET_IDS, targetIds);
        values.put(WorkflowParticipantRuleBpmnContract.TASK_FORM_FIELD, formField);
        values.put(WorkflowParticipantRuleBpmnContract.TASK_NO_MATCH, "FAIL");
        ExtensionElement container = new ExtensionElement();
        container.setName("properties");
        container.setNamespace("http://flowable.org/bpmn");
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
        Map<String, List<ExtensionElement>> children = new HashMap<>();
        children.put("property", properties);
        container.setChildElements(children);
        task.addExtensionElement(container);
    }

    /**
     * 创建带双向关系的顺序流。
     *
     * @param id String，顺序流主键
     * @param source org.flowable.bpmn.model.FlowElement，来源节点
     * @param target org.flowable.bpmn.model.FlowElement，目标节点
     * @return SequenceFlow，可加入流程模型的顺序流
     */
    private SequenceFlow flow(String id, org.flowable.bpmn.model.FlowElement source,
            org.flowable.bpmn.model.FlowElement target)
    {
        SequenceFlow flow = new SequenceFlow(source.getId(), target.getId());
        flow.setId(id);
        flow.setSourceFlowElement(source);
        flow.setTargetFlowElement(target);
        return flow;
    }

    /**
     * 使用 Flowable 官方转换器解析 BPMN XML。
     *
     * @param xml byte[]，UTF-8 BPMN XML
     * @return BpmnModel，可查询流程和作者属性的公共模型
     */
    private BpmnModel parse(byte[] xml)
    {
        return new BpmnXMLConverter().convertToBpmnModel(
                () -> new ByteArrayInputStream(xml), true, true);
    }

    /**
     * @param document WorkflowBpmnDocument，真实 XML 解析后的作者文档
     * @param xml byte[]，同源作者 XML
     */
    private record Fixture(WorkflowBpmnDocument document, byte[] xml)
    {
    }
}

package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.UserTask;
import org.junit.jupiter.api.Test;

class WorkflowParticipantRuleBpmnContractTest
{
    /**
     * 验证正式 BPMN XML 可同时回读角色发起范围和角色、部门混合候选组。
     * @return 无返回值；类型或目标顺序丢失时测试失败
     */
    @Test
    void readsControlledStartAndMixedCandidateGroupRules()
    {
        BpmnModel model = parse(completeXml());
        Process process = model.getProcessById("expense");
        UserTask task = (UserTask) process.getFlowElement("review", true);

        WorkflowParticipantRuleBpmnContract.StartRule start =
                WorkflowParticipantRuleBpmnContract.readStartRule(process);
        WorkflowParticipantRuleBpmnContract.TaskRule assignment =
                WorkflowParticipantRuleBpmnContract.readTaskRule("expense", task).orElseThrow();

        assertThat(start.type()).isEqualTo("ROLES");
        assertThat(start.targetIds()).containsExactly(2L, 3L);
        assertThat(assignment.type()).isEqualTo("CANDIDATE_GROUPS");
        assertThat(assignment.assignmentMode()).isEqualTo("CANDIDATE");
        assertThat(assignment.targetIds()).containsExactly(7L, -8L);
        assertThat(assignment.ruleVersion()).isEqualTo(1);
        assertThat(assignment.noMatchPolicy()).isEqualTo("FAIL");
    }

    /**
     * 验证剥离作者规则时保留同容器中的普通扩展属性。
     * @return 无返回值；执行 BPMN 泄漏规则或误删普通属性时测试失败
     */
    @Test
    void removesAuthorPropertiesAndPreservesOrdinaryProperties()
    {
        BpmnModel model = parse(completeXml());
        Process process = model.getProcessById("expense");
        UserTask task = (UserTask) process.getFlowElement("review", true);

        WorkflowParticipantRuleBpmnContract.removeAuthorProperties(process);
        WorkflowParticipantRuleBpmnContract.removeAuthorProperties(task);
        String compiled = new String(new BpmnXMLConverter().convertToXML(model),
                StandardCharsets.UTF_8);

        assertThat(compiled).doesNotContain("approva.startScope", "approva.assignment");
        assertThat(compiled).contains("business.owner", "finance");
    }

    /**
     * 验证半配置和伪造候选组编码均失败关闭。
     * @return 无返回值；非法作者属性被接受时测试失败
     */
    @Test
    void rejectsIncompleteAndUncontrolledGroupRules()
    {
        BpmnModel incomplete = parse(completeXml().replace(
                "<flowable:property name=\"approva.startScope.noMatchPolicy\" value=\"FAIL\"/>", ""));
        assertThatThrownBy(() -> WorkflowParticipantRuleBpmnContract.readStartRule(
                incomplete.getProcessById("expense")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("流程发起范围配置不完整");

        BpmnModel forged = parse(completeXml().replace("ROLE7,DEPT8", "admins"));
        UserTask task = (UserTask) forged.getProcessById("expense")
                .getFlowElement("review", true);
        assertThatThrownBy(() -> WorkflowParticipantRuleBpmnContract
                .readTaskRule("expense", task))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("候选组必须来自正式角色或部门目录");
    }

    /**
     * 使用 Flowable 公共转换器解析测试 BPMN。
     * @param xml String，UTF-8 BPMN XML
     * @return BpmnModel，真实 Flowable 模型
     */
    private BpmnModel parse(String xml)
    {
        return new BpmnXMLConverter().convertToBpmnModel(
                () -> new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), true, true);
    }

    /**
     * 构造同时包含流程范围、任务规则和普通属性的作者 BPMN。
     * @return String，可由 Flowable 解析的完整最小 XML
     */
    private String completeXml()
    {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:flowable="http://flowable.org/bpmn" targetNamespace="urn:test">
                  <process id="expense" isExecutable="true">
                    <extensionElements><flowable:properties>
                      <flowable:property name="business.owner" value="finance"/>
                      <flowable:property name="approva.startScope.ruleVersion" value="1"/>
                      <flowable:property name="approva.startScope.type" value="ROLES"/>
                      <flowable:property name="approva.startScope.targetIds" value="2,3"/>
                      <flowable:property name="approva.startScope.noMatchPolicy" value="FAIL"/>
                    </flowable:properties></extensionElements>
                    <startEvent id="start"/>
                    <sequenceFlow id="toReview" sourceRef="start" targetRef="review"/>
                    <userTask id="review" name="审批">
                      <extensionElements><flowable:properties>
                        <flowable:property name="approva.assignment.ruleVersion" value="1"/>
                        <flowable:property name="approva.assignment.type" value="CANDIDATE_GROUPS"/>
                        <flowable:property name="approva.assignment.targetIds" value="ROLE7,DEPT8"/>
                        <flowable:property name="approva.assignment.formField" value=""/>
                        <flowable:property name="approva.assignment.noMatchPolicy" value="FAIL"/>
                      </flowable:properties></extensionElements>
                    </userTask>
                    <sequenceFlow id="toEnd" sourceRef="review" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """;
    }
}

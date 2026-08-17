package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BoundaryEvent;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.UserTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfBpmnEventCode;
import com.ruoyi.flowable.domain.WfBusinessCalendar;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;

/**
 * SLA 部署编译器结构与非法配置回归测试。
 */
class WorkflowTaskSlaDeploymentServiceTest
{
    private WorkflowBusinessCalendarService calendarService;
    private WorkflowBpmnEventCodeService eventCodeService;
    private WorkflowIdentityResolver identityResolver;
    private WorkflowTaskSlaDeploymentService service;

    /** @return void，为每个测试创建隔离服务替身和启用目录。 */
    @BeforeEach
    void setUp()
    {
        calendarService = mock(WorkflowBusinessCalendarService.class);
        eventCodeService = mock(WorkflowBpmnEventCodeService.class);
        identityResolver = mock(WorkflowIdentityResolver.class);
        service = new WorkflowTaskSlaDeploymentService(calendarService,
                eventCodeService, identityResolver);
        when(calendarService.requireEnabled("DEFAULT_CN")).thenReturn(calendar());
        when(eventCodeService.requireEnabled("ESCALATION", "SLA_ESC"))
                .thenReturn(escalationCode());
        when(identityResolver.resolveApprovalEligibleUserIds(java.util.Set.of("7")))
                .thenReturn(java.util.Set.of("7"));
    }

    /**
     * 验证提醒为非中断边界、升级为中断边界且作者属性被执行资源剥离。
     * @return void，任一 Flowable 模型结构或快照字段不符时测试失败
     */
    @Test
    void compilesRealBoundaryTimersAndHumanEscalationTask()
    {
        WorkflowPreparedSlaDeployment prepared = service.prepare(
                bpmn("2", "10", "7", ""), "1");

        var model = new BpmnXMLConverter().convertToBpmnModel(
                () -> new java.io.ByteArrayInputStream(prepared.compiledBpmn()), true, true);
        List<BoundaryEvent> boundaries = model.getMainProcess()
                .findFlowElementsOfType(BoundaryEvent.class, true);
        assertThat(boundaries).hasSize(3);
        assertThat(boundaries).filteredOn(BoundaryEvent::isCancelActivity).hasSize(1);
        assertThat(boundaries).filteredOn(boundary -> !boundary.isCancelActivity()).hasSize(2);
        assertThat(model.getMainProcess().findFlowElementsOfType(ServiceTask.class, true))
                .allSatisfy(task -> assertThat(task.getImplementation())
                        .contains("workflowSlaTimerDelegate.executeTimer"));
        assertThat(model.getMainProcess().findFlowElementsOfType(UserTask.class, true))
                .extracting(UserTask::getId)
                .contains("approve", "approva_sla_approve_escalation_user_task");
        UserTask escalationTask = (UserTask) model.getMainProcess()
                .getFlowElement("approva_sla_approve_escalation_user_task", true);
        assertThat(escalationTask.getExtensionElements().get("properties"))
                .singleElement().satisfies(properties -> assertThat(properties.getChildElements()
                        .get("property")).singleElement().satisfies(property ->
                        {
                            assertThat(property.getAttributeValue(null, "name")).isEqualTo(
                                    WorkflowTaskSlaDeploymentService.SOURCE_TASK_DEFINITION_KEY_PROPERTY);
                            assertThat(property.getAttributeValue(null, "value")).isEqualTo("approve");
                        }));
        String compiledXml = new String(prepared.compiledBpmn(), StandardCharsets.UTF_8);
        assertThat(compiledXml).doesNotContain("approva.sla.enabled", "approva.sla.calendarKey");
        assertThat(prepared.snapshots()).singleElement().satisfies(snapshot ->
        {
            assertThat(snapshot.getCalendarKey()).isEqualTo("DEFAULT_CN");
            assertThat(snapshot.getMaxReminders()).isEqualTo(2);
            assertThat(snapshot.getEscalationAssignee()).isEqualTo("7");
        });
    }

    /**
     * 验证事件型升级引用正式目录并输出真实 BPMN throwEvent。
     * @return void，事件目录未锁定或执行模型未生成升级事件时测试失败
     */
    @Test
    void compilesControlledEscalationEventRoute()
    {
        WorkflowPreparedSlaDeployment prepared = service.prepare(
                bpmn("1", "3", "", "SLA_ESC"), "1");
        String compiledXml = new String(prepared.compiledBpmn(), StandardCharsets.UTF_8);
        assertThat(compiledXml).contains("intermediateThrowEvent", "escalationCode=\"SLA_ESC\"");
        assertThat(prepared.snapshots()).singleElement().satisfies(snapshot ->
                assertThat(snapshot.getEscalationEventCode()).isEqualTo("SLA_ESC"));
    }

    /**
     * 验证升级时间不晚于最后提醒时部署前失败且不产生快照。
     * @return void，非法配置进入 Flowable 部署时测试失败
     */
    @Test
    void rejectsEscalationBeforeLastReminder()
    {
        assertThatThrownBy(() -> service.prepare(bpmn("2", "2", "7", ""), "1"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("晚于最后一次提醒");
    }

    /** @param reminders String，提醒次数；@param escalation String，升级分钟；@param userId String，升级用户；@param eventCode String，升级编码；@return byte[]，作者 BPMN。 */
    private byte[] bpmn(String reminders, String escalation, String userId, String eventCode)
    {
        return ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:flowable="http://flowable.org/bpmn" targetNamespace="Examples">
                  <process id="sla_test" isExecutable="true">
                    <startEvent id="start"/><sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="审批" flowable:assignee="7">
                      <extensionElements><flowable:properties>
                        <flowable:property name="approva.sla.enabled" value="true"/>
                        <flowable:property name="approva.sla.calendarKey" value="DEFAULT_CN"/>
                        <flowable:property name="approva.sla.reminderMinutes" value="1"/>
                        <flowable:property name="approva.sla.reminderRepeatMinutes" value="1"/>
                        <flowable:property name="approva.sla.maxReminders" value="%s"/>
                        <flowable:property name="approva.sla.escalationMinutes" value="%s"/>
                        <flowable:property name="approva.sla.escalationUserId" value="%s"/>
                        <flowable:property name="approva.sla.escalationEventCode" value="%s"/>
                      </flowable:properties></extensionElements>
                    </userTask>
                    <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/><endEvent id="end"/>
                  </process>
                </definitions>
                """).formatted(reminders, escalation, userId, eventCode)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** @return WfBusinessCalendar，完整启用工作日历。 */
    private WfBusinessCalendar calendar()
    {
        WfBusinessCalendar calendar = new WfBusinessCalendar();
        calendar.setCalendarKey("DEFAULT_CN");
        calendar.setCalendarName("默认日历");
        calendar.setTimezone("Asia/Shanghai");
        calendar.setWorkingDays("1,2,3,4,5");
        calendar.setWorkStart("09:00");
        calendar.setWorkEnd("18:00");
        calendar.setStatus("ENABLED");
        calendar.setDays(List.of());
        return calendar;
    }

    /** @return WfBpmnEventCode，启用受控升级目录。 */
    private WfBpmnEventCode escalationCode()
    {
        WfBpmnEventCode code = new WfBpmnEventCode();
        code.setEventType("ESCALATION");
        code.setEventCode("SLA_ESC");
        code.setEventName("SLA 升级");
        code.setStatus("ENABLED");
        return code;
    }
}

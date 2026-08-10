package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfBpmnEventCode;

/**
 * BPMN Error 与 Escalation 目录引用、附着和中断语义门禁测试。
 */
class WorkflowBpmnBusinessEventValidationTest
{
    private WorkflowBpmnEventCodeService codeService;
    private WorkflowBpmnService service;

    /** @return void，每个用例创建独立 Flowable 校验器和正式目录替身。 */
    @BeforeEach
    void setUp()
    {
        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.validateProcess(any(BpmnModel.class))).thenReturn(List.of());
        codeService = mock(WorkflowBpmnEventCodeService.class);
        WfBpmnEventCode enabled = new WfBpmnEventCode();
        enabled.setStatus(WorkflowBpmnEventCodeService.ENABLED);
        when(codeService.requireEnabled(any(String.class), any(String.class))).thenReturn(enabled);
        service = new WorkflowBpmnService(repositoryService, null, codeService);
    }

    /**
     * 验证同一活动的同编码边界只能出现一次。
     * @return void，重复匹配未被拒绝时测试失败
     */
    @Test
    void rejectsDuplicateBoundaryMatchOnSameActivity()
    {
        String xml = validErrorBoundaryBpmn().replace(
                "<sequenceFlow id=\"boundaryToHandle\" sourceRef=\"errorBoundary\" targetRef=\"handle\"/>",
                "<boundaryEvent id=\"errorBoundary2\" attachedToRef=\"raiseError\" cancelActivity=\"true\">"
                        + "<errorEventDefinition errorRef=\"businessError\"/>"
                        + "</boundaryEvent>"
                        + "<sequenceFlow id=\"boundaryToHandle\" sourceRef=\"errorBoundary\" targetRef=\"handle\"/>"
                        + "<sequenceFlow id=\"boundary2ToHandle\" sourceRef=\"errorBoundary2\" targetRef=\"handle\"/>");

        assertBadRequest(xml, "重复");
    }

    /**
     * 验证 BPMN Error 边界固定使用中断语义。
     * @return void，非中断 Error 被接受时测试失败
     */
    @Test
    void rejectsNonInterruptingErrorBoundary()
    {
        assertBadRequest(validErrorBoundaryBpmn().replace(
                "attachedToRef=\"raiseError\" cancelActivity=\"true\"",
                "attachedToRef=\"raiseError\" cancelActivity=\"false\""), "中断语义");
    }

    /**
     * 验证错误或升级引用必须存在于当前启用的正式编码目录。
     * @return void，停用或未知编码被接受时测试失败
     */
    @Test
    void rejectsDisabledOrUnknownCatalogCode()
    {
        when(codeService.requireEnabled("ERROR", "APPROVAL_BUSINESS_ERROR"))
                .thenThrow(new ServiceException("BPMN ERROR 编码未启用或不存在",
                        HttpStatus.CONFLICT));

        assertThatThrownBy(() -> service.validateForSave(
                validErrorBoundaryBpmn().getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("未启用或不存在");
    }

    /**
     * 验证错误或升级边界必须通过唯一出边进入人工或自动处理路径。
     * @return void，缺失或多条出边被接受时测试失败
     */
    @Test
    void rejectsBoundaryWithoutUniqueHandlingPath()
    {
        assertBadRequest(validErrorBoundaryBpmn().replace(
                "<sequenceFlow id=\"boundaryToHandle\" sourceRef=\"errorBoundary\" targetRef=\"handle\"/>",
                ""), "唯一出边");
    }

    /**
     * 断言模型被稳定映射为 HTTP 400。
     * @param xml String，待校验 BPMN XML
     * @param message String，预期提示片段
     * @return void，状态码或消息不一致时测试失败
     */
    private void assertBadRequest(String xml, String message)
    {
        assertThatThrownBy(() -> service.validateForSave(xml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    org.assertj.core.api.Assertions.assertThat(exception.getCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST);
                    org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                            .contains(message);
                });
    }

    /**
     * 构造带正式 Error 根定义、受控产生节点和人工处理路径的作者 BPMN。
     * @return String，合法 UTF-8 BPMN XML
     */
    private String validErrorBoundaryBpmn()
    {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:flowable="http://flowable.org/bpmn"
                  targetNamespace="urn:approvaplat:bpmn-event-validation">
                  <error id="businessError" name="审批业务校验失败" errorCode="APPROVAL_BUSINESS_ERROR"/>
                  <process id="businessEventValidation" isExecutable="true">
                    <startEvent id="start" flowable:formKey="key_1"/>
                    <sequenceFlow id="toRaise" sourceRef="start" targetRef="raiseError"/>
                    <serviceTask id="raiseError" flowable:delegateExpression="${workflowExtensionDelegate}">
                      <extensionElements>
                        <flowable:field name="approvaExtensionKey" stringValue="approva.raise-bpmn-event"/>
                        <flowable:field name="approvaExtensionConfig">
                          <flowable:string><![CDATA[{"eventType":"ERROR","eventCode":"APPROVAL_BUSINESS_ERROR","sourceType":"SERVICE_TASK","operator":"ALWAYS"}]]></flowable:string>
                        </flowable:field>
                      </extensionElements>
                    </serviceTask>
                    <boundaryEvent id="errorBoundary" attachedToRef="raiseError" cancelActivity="true">
                      <errorEventDefinition errorRef="businessError"/>
                    </boundaryEvent>
                    <sequenceFlow id="boundaryToHandle" sourceRef="errorBoundary" targetRef="handle"/>
                    <manualTask id="handle"/>
                    <sequenceFlow id="handleToEnd" sourceRef="handle" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """;
    }
}

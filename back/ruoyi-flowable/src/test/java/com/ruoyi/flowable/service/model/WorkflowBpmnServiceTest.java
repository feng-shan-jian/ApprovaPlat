package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.RepositoryService;
import org.flowable.validation.ValidationError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;

class WorkflowBpmnServiceTest
{
    private RepositoryService repositoryService;

    private WorkflowBpmnService service;

    /**
     * 为每个测试创建 Flowable 官方校验器替身和待测安全组件。
     *
     * @return 无返回值；初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        repositoryService = mock(RepositoryService.class);
        service = new WorkflowBpmnService(repositoryService);
        when(repositoryService.validateProcess(any(BpmnModel.class))).thenReturn(List.of());
    }

    /**
     * 验证合法 BPMN 会递归提取开始节点和用户任务的严格表单引用。
     *
     * @return 无返回值；解析或引用断言失败时测试失败
     */
    @Test
    void validatesBpmnAndExtractsFormReferences()
    {
        WorkflowBpmnDocument document = service.validate(validBpmn().getBytes(StandardCharsets.UTF_8));

        assertThat(document.bpmnModel().getProcessById("expense")).isNotNull();
        assertThat(document.formReferences()).containsExactly(
                new WorkflowBpmnFormReference(1L, "key_1", "start", "提交申请"),
                new WorkflowBpmnFormReference(2L, "key_2", "approve", "主管审批"));
        assertThatThrownBy(() -> document.formReferences().add(
                new WorkflowBpmnFormReference(3L, "key_3", "extra", "")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * 验证非法 UTF-8 字节不会被替换字符掩盖，并映射为稳定 400。
     *
     * @return 无返回值；异常类型或状态码不正确时测试失败
     */
    @Test
    void rejectsMalformedUtf8()
    {
        assertBadRequest(new byte[] {(byte) 0xC3, (byte) 0x28}, "UTF-8");
    }

    /**
     * 验证超过 2 MiB 的 BPMN 在创建解析器之前即被拒绝。
     *
     * @return 无返回值；大小门禁未生效时测试失败
     */
    @Test
    void rejectsOversizedBpmn()
    {
        assertBadRequest(new byte[WorkflowBpmnService.MAX_BPMN_BYTES + 1], "大小限制");
    }

    /**
     * 验证 DTD 和外部实体声明不会进入 Flowable 转换器。
     *
     * @return 无返回值；XXE 门禁未生效时测试失败
     */
    @Test
    void rejectsDoctypeAndExternalEntity()
    {
        String xml = "<!DOCTYPE definitions [<!ENTITY xxe SYSTEM \"file:///secret.txt\">]>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"/>";

        assertThatThrownBy(() -> service.validate(xml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(400);
                    assertThat(exception.getMessage()).doesNotContain("secret.txt");
                });
    }

    /**
     * 验证没有流程表单的开始节点会被拒绝。
     *
     * @return 无返回值；业务表单门禁未生效时测试失败
     */
    @Test
    void rejectsStartEventWithoutForm()
    {
        String xml = validBpmn().replace(" flowable:formKey=\"key_1\"", "");
        assertBadRequest(xml.getBytes(StandardCharsets.UTF_8), "开始节点");
    }

    /**
     * 验证设计草稿可以暂未选择开始表单，但已有节点表单仍会被严格解析。
     *
     * @return 无返回值；草稿被误拒绝或表单引用绕过校验时测试失败
     */
    @Test
    void allowsDraftWithoutStartForm()
    {
        String xml = validBpmn().replace(" flowable:formKey=\"key_1\"", "");

        WorkflowBpmnDocument document = service.validateDraft(
                xml.getBytes(StandardCharsets.UTF_8));

        assertThat(document.formReferences()).containsExactly(
                new WorkflowBpmnFormReference(2L, "key_2", "approve", "主管审批"));
    }

    /**
     * 验证表单键只接受 key_正Long，拒绝负数、零和非数字。
     *
     * @return 无返回值；表单键格式门禁未生效时测试失败
     */
    @Test
    void rejectsInvalidFormKey()
    {
        String xml = validBpmn().replace("key_2", "form_0");
        assertBadRequest(xml.getBytes(StandardCharsets.UTF_8), "key_正整数");
    }

    /**
     * 验证任意递归脚本任务都会被拒绝。
     *
     * @return 无返回值；脚本任务门禁未生效时测试失败
     */
    @Test
    void rejectsScriptTask()
    {
        String xml = validBpmn()
                .replace(ordinaryApprovalTaskXml(),
                        "<scriptTask id=\"approve\" name=\"脚本\" scriptFormat=\"groovy\"><script>println 1</script></scriptTask>");
        assertBadRequest(xml.getBytes(StandardCharsets.UTF_8), "脚本任务");
    }

    /**
     * 验证包含方法调用和运行时访问的表达式在 XML 转换前被拒绝。
     *
     * @return 无返回值；危险表达式门禁未生效时测试失败
     */
    @Test
    void rejectsDangerousExpression()
    {
        String xml = validBpmn().replace("${approver}", "${runtime.exec('calc')}");
        assertBadRequest(xml.getBytes(StandardCharsets.UTF_8), "表达式");
    }

    /**
     * 验证服务任务不能引用 JDK 或其他非白名单实现类。
     *
     * @return 无返回值；实现类白名单未生效时测试失败
     */
    @Test
    void rejectsUntrustedServiceTaskClass()
    {
        String xml = validBpmn()
                .replace(ordinaryApprovalTaskXml(),
                        "<serviceTask id=\"approve\" name=\"执行\" flowable:class=\"java.lang.Runtime\"/>");
        assertBadRequest(xml.getBytes(StandardCharsets.UTF_8), "白名单");
    }

    /**
     * 验证固定 handler、assignee、ALL/ANY 完成条件及三个批准监听事件可以共同通过保存门禁。
     *
     * @return 无返回值；受控动态多实例或兼容监听器被误拒绝时测试失败
     */
    @Test
    void allowsControlledDynamicMultiInstanceAndTaskListener()
    {
        String allBpmn = controlledMultiInstanceBpmn();
        String anyBpmn = allBpmn.replace(
                "${nrOfCompletedInstances == nrOfInstances}",
                "${nrOfCompletedInstances &gt; 0}");

        assertThat(service.validate(allBpmn.getBytes(StandardCharsets.UTF_8)).formReferences())
                .hasSize(2);
        assertThat(service.validate(anyBpmn.getBytes(StandardCharsets.UTF_8)).formReferences())
                .hasSize(2);
    }

    /**
     * 验证开始节点、网关和服务任务都不能直接初始化受控动态多实例。
     *
     * @return 无返回值；任一非普通用户任务前驱通过保存门禁时测试失败
     */
    @Test
    void rejectsDynamicMultiInstanceWithoutOrdinaryUserTaskInitializer()
    {
        String xml = controlledMultiInstanceBpmn();
        String initializerTask = ordinaryInitializerTaskXml();
        String directStart = xml
                .replace("<sequenceFlow id=\"flow1\" sourceRef=\"start\" targetRef=\"prepare\"/>",
                        "<sequenceFlow id=\"flow1\" sourceRef=\"start\" targetRef=\"approve\"/>")
                .replace(initializerTask, "")
                .replace("<sequenceFlow id=\"flow2\" sourceRef=\"prepare\" targetRef=\"approve\"/>", "");
        String gatewayInitializer = xml.replace(initializerTask,
                "<exclusiveGateway id=\"prepare\" name=\"选择会签人员\"/>");
        String serviceInitializer = xml.replace(initializerTask,
                "<serviceTask id=\"prepare\" name=\"准备会签\" "
                        + "flowable:delegateExpression=\"${workflowInitializer}\"/>");

        for (String invalidVariant : List.of(
                directStart, gatewayInitializer, serviceInitializer))
        {
            assertBadRequest(invalidVariant.getBytes(StandardCharsets.UTF_8), "初始化拓扑");
        }
    }

    /**
     * 验证动态多实例前驱必须非多实例、只有一条直连出边，且该顺序流没有条件或 skip 表达式。
     *
     * @return 无返回值；歧义、条件或非普通初始化前驱通过门禁时测试失败
     */
    @Test
    void rejectsAmbiguousOrConditionalDynamicMultiInstanceInitializer()
    {
        String xml = controlledMultiInstanceBpmn();
        String initializerIncomingFlow =
                "<sequenceFlow id=\"flow1\" sourceRef=\"start\" targetRef=\"prepare\"/>";
        String initializerFlow =
                "<sequenceFlow id=\"flow2\" sourceRef=\"prepare\" targetRef=\"approve\"/>";
        String missingInitializerIncoming = xml.replace(initializerIncomingFlow, "");
        String multipleInitializerIncoming = xml.replace(initializerIncomingFlow,
                initializerIncomingFlow
                        + "\n    <sequenceFlow id=\"flowExtraInitializerIncoming\" "
                        + "sourceRef=\"start\" targetRef=\"prepare\"/>");
        String multipleIncoming = xml.replace(initializerFlow,
                initializerFlow
                        + "\n    <sequenceFlow id=\"flowExtraIncoming\" "
                        + "sourceRef=\"start\" targetRef=\"approve\"/>");
        String multipleOutgoing = xml.replace(initializerFlow,
                initializerFlow
                        + "\n    <sequenceFlow id=\"flowExtraOutgoing\" "
                        + "sourceRef=\"prepare\" targetRef=\"end\"/>");
        String conditional = xml.replace(initializerFlow,
                "<sequenceFlow id=\"flow2\" sourceRef=\"prepare\" targetRef=\"approve\">"
                        + "<conditionExpression xsi:type=\"tFormalExpression\">${routeToApproval}"
                        + "</conditionExpression></sequenceFlow>");
        String skipped = xml.replace(initializerFlow,
                "<sequenceFlow id=\"flow2\" sourceRef=\"prepare\" targetRef=\"approve\" "
                        + "flowable:skipExpression=\"${skipApproval}\"/>");
        String multiInstanceInitializer = xml.replace(ordinaryInitializerTaskXml(),
                ordinaryInitializerTaskXml()
                        .replace("${approver}", "${preparer}")
                        .replace("</userTask>", """
                                  <multiInstanceLoopCharacteristics isSequential="false"
                                    flowable:collection="${preparers}" flowable:elementVariable="preparer"/>
                                </userTask>"""));
        String asyncInitializer = xml.replace(ordinaryInitializerTaskXml(),
                ordinaryInitializerTaskXml().replace("flowable:assignee=\"${approver}\"",
                        "flowable:assignee=\"${approver}\" flowable:asyncLeave=\"true\""));
        String skippableInitializer = xml.replace(ordinaryInitializerTaskXml(),
                ordinaryInitializerTaskXml().replace("flowable:assignee=\"${approver}\"",
                        "flowable:assignee=\"${approver}\" "
                                + "flowable:skipExpression=\"${skipPrepare}\""));
        String boundaryInitializer = xml.replace(ordinaryInitializerTaskXml(),
                ordinaryInitializerTaskXml()
                        + "\n    <boundaryEvent id=\"prepareTimer\" attachedToRef=\"prepare\">"
                        + "<timerEventDefinition><timeDuration>PT1M</timeDuration>"
                        + "</timerEventDefinition></boundaryEvent>");

        for (String invalidVariant : List.of(missingInitializerIncoming,
                multipleInitializerIncoming, multipleIncoming, multipleOutgoing,
                conditional, skipped, multiInstanceInitializer, asyncInitializer,
                skippableInitializer, boundaryInitializer))
        {
            assertBadRequest(invalidVariant.getBytes(StandardCharsets.UTF_8), "初始化拓扑");
        }
    }

    /**
     * 验证动态多实例后继路径不能回到初始化前驱并再次进入同一动态节点。
     *
     * @return 无返回值；活动 ID 级正式快照可能跨轮复用的回路通过门禁时测试失败
     */
    @Test
    void rejectsDynamicMultiInstanceReentryCycle()
    {
        String cyclic = controlledMultiInstanceBpmn()
                .replace("<sequenceFlow id=\"flow1\" sourceRef=\"start\" targetRef=\"prepare\"/>",
                        "<sequenceFlow id=\"flow1\" sourceRef=\"start\" "
                                + "targetRef=\"loopRouter\"/>\n"
                                + "    <exclusiveGateway id=\"loopRouter\"/>\n"
                                + "    <sequenceFlow id=\"flowLoopPrepare\" "
                                + "sourceRef=\"loopRouter\" targetRef=\"prepare\"/>")
                .replace("<sequenceFlow id=\"flow3\" sourceRef=\"approve\" targetRef=\"end\"/>",
                        "<sequenceFlow id=\"flow3\" sourceRef=\"approve\" "
                                + "targetRef=\"loopRouter\"/>");

        assertBadRequest(cyclic.getBytes(StandardCharsets.UTF_8), "重复进入");
    }

    /**
     * 验证固定 handler 方法表达式只能出现在多实例集合位置，不能复用于条件流等上下文。
     *
     * @return 无返回值；handler 方法表达式发生上下文逃逸时测试失败
     */
    @Test
    void rejectsControlledHandlerOutsideMultiInstanceCollection()
    {
        String conditionXml = validBpmn().replace(
                "<sequenceFlow id=\"flow2\" sourceRef=\"approve\" targetRef=\"end\"/>",
                "<sequenceFlow id=\"flow2\" sourceRef=\"approve\" targetRef=\"end\">"
                        + "<conditionExpression xsi:type=\"tFormalExpression\">"
                        + "${multiInstanceHandler.getUserIds(execution)}"
                        + "</conditionExpression></sequenceFlow>");
        String unmappedXml = validBpmn().replace(
                "<process id=\"expense\" name=\"报销审批\" isExecutable=\"true\">",
                "<process id=\"expense\" name=\"报销审批\" isExecutable=\"true\">"
                        + "<documentation>${multiInstanceHandler.getUserIds(execution)}"
                        + "</documentation>");

        assertBadRequest(conditionXml.getBytes(StandardCharsets.UTF_8), "表达式");
        assertBadRequest(unmappedXml.getBytes(StandardCharsets.UTF_8), "受控集合字段");
    }

    /**
     * 验证动态多实例只接受完整固定组合，串行、错误变量、错误办理人和自由完成条件均被拒绝。
     *
     * @return 无返回值；任一不完整动态多实例配置通过门禁时测试失败
     */
    @Test
    void rejectsUnsafeDynamicMultiInstanceVariants()
    {
        String xml = controlledMultiInstanceBpmn();
        List<String> invalidVariants = List.of(
                xml.replace("isSequential=\"false\"", "isSequential=\"true\""),
                xml.replace("id=\"approve\" name=\"主管审批\"",
                        "id=\"approve\" name=\"主管审批\" flowable:async=\"true\""),
                xml.replace("id=\"approve\" name=\"主管审批\"",
                        "id=\"approve\" name=\"主管审批\" "
                                + "flowable:skipExpression=\"${skipApproval}\""),
                xml.replace("flowable:elementVariable=\"assignee\"",
                        "flowable:elementVariable=\"reviewer\""),
                xml.replace("flowable:assignee=\"${assignee}\"",
                        "flowable:assignee=\"${reviewer}\""),
                xml.replace("${nrOfCompletedInstances == nrOfInstances}",
                        "${nrOfCompletedInstances &gt;= 2}"),
                xml.replace("<completionCondition xsi:type=\"tFormalExpression\">",
                        "<loopCardinality xsi:type=\"tFormalExpression\">3</loopCardinality>"
                                + "<completionCondition xsi:type=\"tFormalExpression\">"));

        for (String invalidVariant : invalidVariants)
        {
            assertBadRequest(invalidVariant.getBytes(StandardCharsets.UTF_8), "动态多实例");
        }
    }

    /**
     * 验证任务监听器拒绝未批准事件、任意 Bean、class 实现和字段注入。
     *
     * @return 无返回值；任一不受支持监听器配置通过门禁时测试失败
     */
    @Test
    void rejectsUnsupportedTaskListenerVariants()
    {
        String xml = controlledMultiInstanceBpmn();
        List<String> invalidVariants = List.of(
                xml.replace("event=\"create\"", "event=\"delete\""),
                xml.replace("delegateExpression=\"${userTaskListener}\"",
                        "delegateExpression=\"${workflowOtherListener}\""),
                xml.replace("delegateExpression=\"${userTaskListener}\"",
                        "class=\"com.ruoyi.flowable.listener.OtherListener\""),
                xml.replace("event=\"assignment\"", "event=\"create\""),
                xml.replace(
                        "<flowable:taskListener event=\"complete\" delegateExpression=\"${userTaskListener}\"/>",
                        ""),
                xml.replace(
                        "<flowable:taskListener event=\"create\" delegateExpression=\"${userTaskListener}\"/>",
                        "<flowable:taskListener event=\"create\" delegateExpression=\"${userTaskListener}\">"
                                + "<flowable:field name=\"payload\" stringValue=\"unsafe\"/>"
                                + "</flowable:taskListener>"));

        for (String invalidVariant : invalidVariants)
        {
            assertBadRequest(invalidVariant.getBytes(StandardCharsets.UTF_8), "任务监听器");
        }
    }

    /**
     * 验证 Flowable 官方校验器的非 warning 错误会阻止模型保存。
     *
     * @return 无返回值；官方错误未被拦截时测试失败
     */
    @Test
    void rejectsFlowableValidationErrors()
    {
        ValidationError error = new ValidationError();
        error.setWarning(false);
        error.setProblem("internal validation details");
        when(repositoryService.validateProcess(any(BpmnModel.class))).thenReturn(List.of(error));

        assertThatThrownBy(() -> service.validate(validBpmn().getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(400);
                    assertThat(exception.getMessage()).doesNotContain("internal validation details");
                });
    }

    /**
     * 验证 Flowable 官方校验器的 warning 不会误阻断合法流程。
     *
     * @return 无返回值；warning 被误判为错误时测试失败
     */
    @Test
    void allowsFlowableWarnings()
    {
        ValidationError warning = new ValidationError();
        warning.setWarning(true);
        when(repositoryService.validateProcess(any(BpmnModel.class))).thenReturn(List.of(warning));

        assertThat(service.validate(validBpmn().getBytes(StandardCharsets.UTF_8)).formReferences())
                .hasSize(2);
    }

    /**
     * 断言 BPMN 字节被映射为包含指定稳定提示的 HTTP 400 业务异常。
     *
     * @param bytes byte[]，待校验 BPMN 字节
     * @param messagePart String，预期稳定提示片段
     * @return 无返回值；异常断言失败时测试失败
     */
    private void assertBadRequest(byte[] bytes, String messagePart)
    {
        assertThatThrownBy(() -> service.validate(bytes))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(400);
                    assertThat(exception.getMessage()).contains(messagePart);
                });
    }

    /**
     * 构造包含开始表单、用户任务表单和安全变量表达式的最小可执行 BPMN。
     *
     * @return String，UTF-8 BPMN XML 测试数据
     */
    private String validBpmn()
    {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xmlns:flowable="http://flowable.org/bpmn"
                  targetNamespace="http://ruoyi.example/workflow">
                  <process id="expense" name="报销审批" isExecutable="true">
                    <startEvent id="start" name="提交申请" flowable:formKey="key_1"/>
                    <sequenceFlow id="flow1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="主管审批" flowable:formKey="key_2" flowable:assignee="${approver}">
                      <extensionElements>
                        <flowable:taskListener event="create" delegateExpression="${userTaskListener}"/>
                        <flowable:taskListener event="assignment" delegateExpression="${userTaskListener}"/>
                        <flowable:taskListener event="complete" delegateExpression="${userTaskListener}"/>
                      </extensionElements>
                    </userTask>
                    <sequenceFlow id="flow2" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end" name="结束"/>
                  </process>
                </definitions>
                """;
    }

    /**
     * 构造使用固定动态多实例 handler、会签条件及三个批准监听事件的可执行 BPMN。
     *
     * @return String，满足生产兼容契约的 UTF-8 BPMN XML 测试数据
     */
    private String controlledMultiInstanceBpmn()
    {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xmlns:flowable="http://flowable.org/bpmn"
                  targetNamespace="http://ruoyi.example/workflow">
                  <process id="expense" name="报销审批" isExecutable="true">
                    <startEvent id="start" name="提交申请" flowable:formKey="key_1"/>
                    <sequenceFlow id="flow1" sourceRef="start" targetRef="prepare"/>
                    <userTask id="prepare" name="选择会签人员" flowable:assignee="${approver}">
                      <extensionElements>
                        <flowable:taskListener event="create" delegateExpression="${userTaskListener}"/>
                        <flowable:taskListener event="assignment" delegateExpression="${userTaskListener}"/>
                        <flowable:taskListener event="complete" delegateExpression="${userTaskListener}"/>
                      </extensionElements>
                    </userTask>
                    <sequenceFlow id="flow2" sourceRef="prepare" targetRef="approve"/>
                <userTask id="approve" name="主管审批" flowable:formKey="key_2" flowable:assignee="${assignee}">
                  <extensionElements>
                    <flowable:taskListener event="create" delegateExpression="${userTaskListener}"/>
                    <flowable:taskListener event="assignment" delegateExpression="${userTaskListener}"/>
                    <flowable:taskListener event="complete" delegateExpression="${userTaskListener}"/>
                  </extensionElements>
                  <multiInstanceLoopCharacteristics isSequential="false"
                    flowable:collection="${multiInstanceHandler.getUserIds(execution)}"
                    flowable:elementVariable="assignee">
                    <completionCondition xsi:type="tFormalExpression">${nrOfCompletedInstances == nrOfInstances}</completionCondition>
                  </multiInstanceLoopCharacteristics>
                </userTask>
                    <sequenceFlow id="flow3" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end" name="结束"/>
                  </process>
                </definitions>
                """;
    }

    /**
     * 返回合法样本中负责提交 nextUserIds 的同步普通用户任务 XML。
     *
     * @return String，动态多实例唯一初始化前驱元素
     */
    private String ordinaryInitializerTaskXml()
    {
        String bpmn = controlledMultiInstanceBpmn();
        // 直接从合法样本提取初始化节点，避免 Java text block 缩进差异让非法变体未真正替换。
        int start = bpmn.indexOf("<userTask id=\"prepare\"");
        int end = bpmn.indexOf("</userTask>", start);
        if (start < 0 || end < 0)
        {
            throw new AssertionError("合法多实例 fixture 缺少初始化用户任务");
        }
        return bpmn.substring(start, end + "</userTask>".length());
    }

    /**
     * 返回合法样本中带完整身份审计监听器的普通审批任务 XML。
     *
     * @return String，满足生产监听契约的普通用户任务元素
     */
    private String ordinaryApprovalTaskXml()
    {
        String bpmn = validBpmn();
        // 直接从合法样本提取审批节点，确保脚本任务和服务任务拒绝用例实际替换原节点。
        int start = bpmn.indexOf("<userTask id=\"approve\"");
        int end = bpmn.indexOf("</userTask>", start);
        if (start < 0 || end < 0)
        {
            throw new AssertionError("合法 BPMN fixture 缺少审批用户任务");
        }
        return bpmn.substring(start, end + "</userTask>".length());
    }
}

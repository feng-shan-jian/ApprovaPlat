package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfBusinessCalendar;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;

/**
 * 作者 BPMN 一次性迁移后的严格协议门禁测试。
 */
class WorkflowBpmnLegacyRejectionTest
{
    private WorkflowBpmnService service;

    /**
     * 使用无事件、无自定义字段的最小依赖装配作者模型校验器。
     *
     * @return void，每个测试获得独立服务实例
     */
    @BeforeEach
    void setUp()
    {
        service = new WorkflowBpmnService(mock(RepositoryService.class),
                mock(WorkflowFormFieldExtensionService.class),
                mock(WorkflowBpmnEventCodeService.class));
    }

    /**
     * 验证完整发起范围和内嵌开始表单仍可进入正式作者协议。
     *
     * @return void，正式最小模型被拒绝时测试失败
     */
    @Test
    void acceptsFormalAuthorModel()
    {
        assertThatCode(() -> validate(process("", ""))).doesNotThrowAnyException();
    }

    @Test
    void savingAndDeployingRejectCorruptCopyRulesOnAnyTask()
    {
        String copyRule = "<flowable:property name=\"approva.autoCopyRules\" value=\"{&quot;version&quot;:99,&quot;rules&quot;:[]}\"/>";
        String author = process("", userTask("", taskRule())
                + userTask("", taskRule() + copyRule).replace("id=\"review\"", "id=\"other\""));
        assertThatThrownBy(() -> service.validateForSave(author.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ServiceException.class).hasMessageContaining("自动抄送规则版本不受支持");
        assertThatThrownBy(() -> service.validate(author.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ServiceException.class).hasMessageContaining("自动抄送规则版本不受支持");
    }

    @Test
    void embeddedAuthorFieldsRetainStaticContentAndPermissionGuards()
    {
        assertRejected(process("", "").replace("申请标题", "java script:alert(1)"), "危险协议");
        assertRejected(process("", "").replace("readable=\"true\"", "readable=\"false\""), "权限组合不合法");
        String enumField = "<flowable:formProperty id=\"requestTitle\" type=\"enum\" readable=\"true\" writable=\"true\">"
                + "<flowable:value id=\"safe\" name=\"data:text/html\"/></flowable:formProperty>";
        assertRejected(process("", "").replaceAll("(?s)<flowable:formProperty id=\"requestTitle\".*?/>", enumField), "危险协议");
    }

    /**
     * 验证作者 XML 不再暴露服务端内部 Spring Bean 监听器协议。
     *
     * @return void，浏览器或其他作者调用方仍可提交内部监听器时测试失败
     */
    @Test
    void rejectsInternalTaskListenerInAuthorModel()
    {
        String task = userTask("", taskRule()).replace("</extensionElements>", """
                <flowable:taskListener event="create" delegateExpression="${userTaskListener}"/>
                </extensionElements>
                """);
        assertRejected(process("", task), "内部任务监听器");
    }

    /**
     * 验证服务端最后部署编译阶段为全部 UserTask 注入并校验固定身份审计监听器。
     *
     * @return void，作者无内部监听器时不能生成可执行部署资源则测试失败
     */
    @Test
    void serverCompilerInjectsRuntimeTaskListeners()
    {
        WorkflowBusinessCalendarService calendarService =
                mock(WorkflowBusinessCalendarService.class);
        WfBusinessCalendar calendar = new WfBusinessCalendar();
        calendar.setCalendarKey("standard");
        calendar.setTimezone("Asia/Shanghai");
        calendar.setWorkingDays("1,2,3,4,5");
        calendar.setWorkStart("09:00");
        calendar.setWorkEnd("18:00");
        when(calendarService.requireEnabled("standard")).thenReturn(calendar);
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveApprovalEligibleUserIds(Set.of("200")))
                .thenReturn(Set.of("200"));
        WorkflowTaskSlaDeploymentService slaCompiler = new WorkflowTaskSlaDeploymentService(
                calendarService, mock(WorkflowBpmnEventCodeService.class), identityResolver);
        var executable = new org.flowable.bpmn.converter.BpmnXMLConverter().convertToBpmnModel(
                () -> new java.io.ByteArrayInputStream(preSlaCompiledProcess().getBytes(StandardCharsets.UTF_8)), true, true);
        slaCompiler.prepare(executable, "100");
        byte[] slaCompiled = WorkflowBpmnXmlPreserver.write(executable, preSlaCompiledProcess());
        String slaCompiledXml = new String(
                slaCompiled, StandardCharsets.UTF_8);
        assertThat(slaCompiledXml).doesNotContain("${userTaskListener}");
        assertThatThrownBy(() -> service.validateCompiledDeployment(slaCompiled))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("缺少固定身份审计任务监听器");

        new WorkflowRuntimeTaskListenerCompiler().compile(executable);
        byte[] compiled = WorkflowBpmnXmlPreserver.write(executable, preSlaCompiledProcess());
        // 原任务和 SLA 新生成的升级 UserTask 均由最后阶段获得三项固定事件。
        assertThat(occurrences(new String(compiled, StandardCharsets.UTF_8),
                "${userTaskListener}")).isEqualTo(6);
        assertThatCode(() -> service.validateCompiledDeployment(compiled))
                .doesNotThrowAnyException();
    }

    /**
     * 验证作者模型中的 ManualTask 被明确拒绝，不能保存为历史往返元素。
     *
     * @return void，ManualTask 未返回稳定 400 时测试失败
     */
    @Test
    void rejectsManualTask()
    {
        assertRejected(process("", "<manualTask id=\"manual\"/>"), "ManualTask");
    }

    /**
     * 验证每个可执行流程必须声明完整平台发起范围。
     *
     * @return void，缺失发起范围仍被当作 PUBLIC 时测试失败
     */
    @Test
    void rejectsMissingStartScope()
    {
        String xml = process("", "").replace(startScope(), "");
        assertRejected(xml, "发起范围");
    }

    /**
     * 验证普通 UserTask 缺少平台办理人规则时失败关闭。
     *
     * @return void，缺失规则仍从原生字段恢复时测试失败
     */
    @Test
    void rejectsMissingParticipantRule()
    {
        assertRejected(process("", userTask("", "")), "平台办理人规则");
    }

    /**
     * 验证作者模型不能把 Flowable assignee 当作平台办理规则来源。
     *
     * @return void，原生办理人字段未被明确拒绝时测试失败
     */
    @Test
    void rejectsNativeParticipantFields()
    {
        assertRejected(process("", userTask(" flowable:assignee=\"100\"", taskRule())),
                "原生办理人字段");
    }

    /**
     * 验证绑定正式模板但没有默认和逐字段权限的节点不能保存。
     *
     * @return void，缺失权限仍沿用模板语义时测试失败
     */
    @Test
    void rejectsTemplateWithoutNodePermissions()
    {
        assertRejected(process(" flowable:formKey=\"key_1\"", "", false),
                "完整字段权限");
    }

    /**
     * 验证纯数字表单键即使携带完整权限也不能替代 key_ 前缀正式协议。
     *
     * @return void，纯数字表单键仍被识别为模板引用时测试失败
     */
    @Test
    void rejectsNumericFormKey()
    {
        String permission = """
                <extensionElements>
                  <flowable:formProperty id="approva_permission_default" type="string"
                    readable="true" writable="true"/>
                </extensionElements>
                """;
        String xml = process("", "", false).replace(
                "<startEvent id=\"start\"></startEvent>",
                "<startEvent id=\"start\" flowable:formKey=\"1\">"
                        + permission + "</startEvent>");
        assertRejected(xml, "key_正整数");
    }

    /**
     * 验证固定人员旧表达式不能进入作者多实例协议。
     *
     * @return void，旧 getFixedUserIds 表达式仍被接受时测试失败
     */
    @Test
    void rejectsFixedMultiInstanceExpression()
    {
        String task = """
                <userTask id="review" name="审批">
                  <extensionElements>
                    <flowable:properties>
                      <flowable:property name="approva.multiInstance.identityType" value="USER"/>
                      <flowable:property name="approva.multiInstance.identityIds" value="201,202"/>
                    </flowable:properties>
                  </extensionElements>
                  <multiInstanceLoopCharacteristics isSequential="false"
                    flowable:collection="${multiInstanceHandler.getFixedUserIds(execution, '201,202')}"
                    flowable:elementVariable="assignee"/>
                </userTask>
                """;
        assertRejected(process("", task), "多实例");
    }

    /**
     * 执行作者保存级 BPMN 校验。
     *
     * @param xml String，完整 UTF-8 BPMN XML
     * @return void，通过时无返回；非法时抛出 ServiceException
     */
    private void validate(String xml)
    {
        service.validateForSave(xml.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 断言旧作者协议返回 400 且包含可定位的稳定业务提示。
     *
     * @param xml String，包含旧协议的 BPMN XML
     * @param messagePart String，预期错误提示片段
     * @return void，状态码或提示不符时测试失败
     */
    private void assertRejected(String xml, String messagePart)
    {
        assertThatThrownBy(() -> validate(xml))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    org.assertj.core.api.Assertions.assertThat(exception.getCode()).isEqualTo(400);
                    org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                            .contains(messagePart);
                });
    }

    /**
     * 生成只含开始和结束节点的最小正式作者流程。
     *
     * @param startAttributes String，追加到开始节点的受控属性
     * @param middleElements String，开始与结束之间的 BPMN 元素
     * @return String，完整 BPMN XML
     */
    private String process(String startAttributes, String middleElements)
    {
        return process(startAttributes, middleElements, true);
    }

    /**
     * 生成指定开始表单来源的最小作者流程。
     *
     * @param startAttributes String，追加到开始节点的受控属性
     * @param middleElements String，开始与结束之间的 BPMN 元素
     * @param embeddedStartForm boolean，true 时写入正式内嵌开始字段
     * @return String，完整 BPMN XML
     */
    private String process(String startAttributes, String middleElements,
            boolean embeddedStartForm)
    {
        String startForm = embeddedStartForm ? """
                <extensionElements>
                  <flowable:formProperty id="requestTitle" name="申请标题" type="string"
                    required="true" readable="true" writable="true"/>
                </extensionElements>
                """ : "";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:flowable="http://flowable.org/bpmn" targetNamespace="strict-author-test">
                  <process id="strictAuthor" name="严格作者模型" isExecutable="true">
                    %s
                    <startEvent id="start"%s>%s</startEvent>
                    %s
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(startScope(), startAttributes, startForm, middleElements);
    }

    /**
     * 返回可执行流程必须具备的完整发起范围属性。
     *
     * @return String，PUBLIC 发起范围的四个正式属性
     */
    private String startScope()
    {
        return """
                <extensionElements><flowable:properties>
                  <flowable:property name="approva.startScope.ruleVersion" value="1"/>
                  <flowable:property name="approva.startScope.type" value="PUBLIC"/>
                  <flowable:property name="approva.startScope.targetIds" value=""/>
                  <flowable:property name="approva.startScope.noMatchPolicy" value="FAIL"/>
                </flowable:properties></extensionElements>
                """;
    }

    /**
     * 生成普通任务，可选择原生属性并注入正式办理规则。
     *
     * @param attributes String，UserTask 原生属性
     * @param properties String，平台办理规则属性
     * @return String，只包含作者可配置业务属性的 UserTask
     */
    private String userTask(String attributes, String properties)
    {
        String propertyBlock = properties.isBlank() ? "" :
                "<flowable:properties>" + properties + "</flowable:properties>";
        return """
                <userTask id="review" name="审批"%s>
                  <extensionElements>%s</extensionElements>
                </userTask>
                """.formatted(attributes, propertyBlock);
    }

    /**
     * 返回普通任务办理规则的完整正式属性。
     *
     * @return String，STARTER 办理规则属性
     */
    private String taskRule()
    {
        return """
                <flowable:property name="approva.assignment.ruleVersion" value="1"/>
                <flowable:property name="approva.assignment.type" value="STARTER"/>
                <flowable:property name="approva.assignment.targetIds" value=""/>
                <flowable:property name="approva.assignment.formField" value=""/>
                <flowable:property name="approva.assignment.noMatchPolicy" value="FAIL"/>
                """;
    }

    /**
     * 生成进入 SLA 与最终监听器编译阶段前的最小执行 BPMN，作者属性已剥离且没有内部监听器。
     *
     * @return String，包含开始表单、已解析办理人和完整顺序流的执行资源
     */
    private String preSlaCompiledProcess()
    {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:flowable="http://flowable.org/bpmn" targetNamespace="runtime-compiler-test">
                  <process id="runtimeCompiler" name="运行编译" isExecutable="true">
                    <startEvent id="start">
                      <extensionElements>
                        <flowable:formProperty id="requestTitle" name="申请标题" type="string"
                          required="true" readable="true" writable="true"/>
                      </extensionElements>
                    </startEvent>
                    <userTask id="review" name="审批" flowable:assignee="100">
                      <extensionElements><flowable:properties>
                        <flowable:property name="approva.sla.enabled" value="true"/>
                        <flowable:property name="approva.sla.calendarKey" value="standard"/>
                        <flowable:property name="approva.sla.reminderMinutes" value="10"/>
                        <flowable:property name="approva.sla.reminderRepeatMinutes" value="5"/>
                        <flowable:property name="approva.sla.maxReminders" value="2"/>
                        <flowable:property name="approva.sla.escalationMinutes" value="30"/>
                        <flowable:property name="approva.sla.escalationUserId" value="200"/>
                        <flowable:property name="approva.sla.escalationEventCode" value=""/>
                      </flowable:properties></extensionElements>
                    </userTask>
                    <endEvent id="end"/>
                    <sequenceFlow id="flow1" sourceRef="start" targetRef="review"/>
                    <sequenceFlow id="flow2" sourceRef="review" targetRef="end"/>
                  </process>
                </definitions>
                """;
    }

    /**
     * 统计固定文本在完整执行 XML 中的不重叠出现次数。
     *
     * @param source String，最终编译 BPMN 文本
     * @param target String，待统计的内部监听器表达式
     * @return int，目标文本出现次数
     */
    private int occurrences(String source, String target)
    {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(target, offset)) >= 0)
        {
            count++;
            offset += target.length();
        }
        return count;
    }
}

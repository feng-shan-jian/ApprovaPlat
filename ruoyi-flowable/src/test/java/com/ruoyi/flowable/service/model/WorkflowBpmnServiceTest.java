package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.engine.RepositoryService;
import org.flowable.validation.ValidationError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceModelContract;

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
                new WorkflowBpmnFormReference(WorkflowFormSourceType.TEMPLATE, 1L,
                        "key_1", "start", "提交申请", null, "expense"),
                new WorkflowBpmnFormReference(WorkflowFormSourceType.TEMPLATE, 2L,
                        "key_2", "approve", "主管审批", null, "expense"));
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
                new WorkflowBpmnFormReference(WorkflowFormSourceType.TEMPLATE, 2L,
                        "key_2", "approve", "主管审批", null, "expense"));
    }

    /**
     * 验证开始节点 Flowable FormData 可作为正式表单来源，并转换为当前渲染协议。
     * @return void，内嵌表单未被解析、冻结或类型转换时测试失败
     */
    @Test
    void extractsEmbeddedStartFormData()
    {
        String embeddedStart = """
                <startEvent id="start" name="提交申请">
                  <extensionElements>
                    <flowable:formProperty id="reason" name="原因" type="string"
                      readable="true" writable="true" required="true"/>
                    <flowable:formProperty id="approved" name="确认" type="boolean"
                      readable="true" writable="true" required="false"/>
                  </extensionElements>
                </startEvent>
                """.strip();
        String xml = validBpmn().replace(
                "<startEvent id=\"start\" name=\"提交申请\" flowable:formKey=\"key_1\"/>",
                embeddedStart);

        WorkflowBpmnDocument document = service.validate(xml.getBytes(StandardCharsets.UTF_8));

        WorkflowBpmnFormReference start = document.formReferences().get(0);
        assertThat(start.sourceType()).isEqualTo(WorkflowFormSourceType.EMBEDDED);
        assertThat(start.formId()).isNull();
        assertThat(start.formKey()).isEqualTo(WorkflowFormSourceType.EMBEDDED_FORM_KEY);
        assertThat(start.embeddedContent()).contains("\"__vModel__\":\"reason\"",
                "\"tag\":\"el-switch\"");
    }

    /**
     * 验证同一节点不能同时引用 wf_form 和内嵌 FormData，避免部署快照来源歧义。
     * @return void，双来源节点被错误放行时测试失败
     */
    @Test
    void rejectsTemplateAndEmbeddedFormOnSameNode()
    {
        String xml = validBpmn().replace(
                "<startEvent id=\"start\" name=\"提交申请\" flowable:formKey=\"key_1\"/>",
                """
                <startEvent id="start" name="提交申请" flowable:formKey="key_1">
                  <extensionElements>
                    <flowable:formProperty id="reason" name="原因" type="string"
                      readable="true" writable="true"/>
                  </extensionElements>
                </startEvent>
                """.strip());

        assertBadRequest(xml.getBytes(StandardCharsets.UTF_8), "同时配置");
    }

    /**
     * 验证正式模板可以携带受控默认及逐字段权限，并在模型重开所需的 BPMN 引用中稳定回读。
     *
     * @return void，权限 FormProperty 被当作内嵌表单或四态丢失时测试失败
     */
    @Test
    void extractsControlledTemplateFieldPermissions()
    {
        String permissionStart = """
                <startEvent id="start" name="提交申请" flowable:formKey="key_1">
                  <extensionElements>
                    <flowable:formProperty id="approva_permission_default" name="批量默认字段权限"
                      type="string" readable="true" writable="true" required="false"/>
                    <flowable:formProperty id="approva_permission_field_1" name="金额"
                      type="string" variable="amount" readable="true" writable="true" required="true"/>
                  </extensionElements>
                </startEvent>
                """.strip();
        String xml = validBpmn().replace(
                "<startEvent id=\"start\" name=\"提交申请\" flowable:formKey=\"key_1\"/>",
                permissionStart);

        WorkflowBpmnFormReference reference = service.validate(
                xml.getBytes(StandardCharsets.UTF_8)).formReferences().get(0);

        assertThat(reference.sourceType()).isEqualTo(WorkflowFormSourceType.TEMPLATE);
        assertThat(reference.defaultPermission()).isEqualTo(
                WorkflowFormFieldPermissionMode.EDITABLE);
        assertThat(reference.fieldPermissions()).containsExactlyEntriesOf(
                java.util.Map.of("amount", WorkflowFormFieldPermissionMode.REQUIRED));
    }

    /**
     * 验证 ComplexGateway 可作为作者 XML 保存并返回精确诊断，但部署门禁明确拒绝。
     *
     * @return 无返回值；保存被误拒绝、元素定位缺失或部署被放行时测试失败
     */
    @Test
    void allowsComplexGatewayRoundTripButRejectsDeployment()
    {
        String xml = validBpmn().replace(ordinaryApprovalTaskXml(),
                "<complexGateway id=\"approve\" name=\"复杂汇聚\"/>");
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);

        WorkflowBpmnDocument document = service.validateForSave(bytes);

        assertThat(document.bpmnXml()).isEqualTo(xml);
        assertThat(service.deploymentCompatibilityIssues(document)).singleElement()
                .satisfies(issue ->
                {
                    assertThat(issue.code()).isEqualTo("BPMN_ELEMENT_NOT_EXECUTABLE");
                    assertThat(issue.severity()).isEqualTo("WARNING");
                    assertThat(issue.elementId()).isEqualTo("approve");
                    assertThat(issue.message()).contains("ComplexGateway", "Flowable 8");
                });
        assertThatThrownBy(() -> service.validate(bytes))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(400);
                    assertThat(exception.getSubCode())
                            .isEqualTo("BPMN_ELEMENT_NOT_EXECUTABLE");
                });
    }

    /**
     * 验证标准循环可保留作者 XML，但部署报告必须定位所属活动并拒绝执行。
     * @return void，标准循环被保存丢弃或部署放行时测试失败
     */
    @Test
    void allowsStandardLoopRoundTripButRejectsDeployment()
    {
        String xml = validBpmn().replace(ordinaryApprovalTaskXml(), """
                <manualTask id="approve" name="循环复核">
                  <standardLoopCharacteristics testBefore="false" loopMaximum="3">
                    <loopCondition xsi:type="tFormalExpression"><![CDATA[${continueLoop}]]></loopCondition>
                  </standardLoopCharacteristics>
                </manualTask>
                """.strip());

        WorkflowBpmnDocument document = service.validateForSave(
                xml.getBytes(StandardCharsets.UTF_8));

        assertThat(document.bpmnXml()).isEqualTo(xml);
        assertThat(service.deploymentCompatibilityIssues(document)).singleElement()
                .satisfies(issue ->
                {
                    assertThat(issue.code()).isEqualTo("BPMN_ELEMENT_NOT_EXECUTABLE");
                    assertThat(issue.elementId()).isEqualTo("approve");
                    assertThat(issue.message()).contains("标准循环", "Flowable 8");
                });
        assertThatThrownBy(() -> service.validate(xml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getSubCode())
                                .isEqualTo("BPMN_ELEMENT_NOT_EXECUTABLE"));
    }

    /**
     * 验证通用 Flowable 扩展属性可往返，重复名称和平台保留名称被服务端拒绝。
     * @return void，普通元数据丢失或有界校验失效时测试失败
     */
    @Test
    void validatesGenericFlowableExtensionProperties()
    {
        String validProperties = validBpmn().replace(ordinaryApprovalTaskXml(), """
                <manualTask id="approve" name="带扩展属性的复核">
                  <extensionElements>
                    <flowable:properties>
                      <flowable:property name="business.owner" value="finance" />
                      <flowable:property name="retentionDays" value="30" />
                    </flowable:properties>
                  </extensionElements>
                </manualTask>
                """.strip());
        assertThat(service.validateForSave(validProperties.getBytes(StandardCharsets.UTF_8))
                .bpmnXml()).isEqualTo(validProperties);

        String duplicate = validProperties.replace("retentionDays", "business.owner");
        assertThatThrownBy(() -> service.validateForSave(
                duplicate.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getMessage()).contains("不能重复"));

        String reserved = validProperties.replace("business.owner", "approva.internal");
        assertThatThrownBy(() -> service.validateForSave(
                reserved.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getMessage()).contains("保留前缀"));
    }

    /**
     * 验证其他引擎私有扩展可作为作者 XML 回显，但部署报告明确为不兼容。
     * @return void，私有扩展被误执行或没有结构化诊断时测试失败
     */
    @Test
    void reportsForeignPrivateExtensionAsDeploymentIncompatible()
    {
        String task = ordinaryApprovalTaskXml().replace("<extensionElements>",
                "<extensionElements><camunda:inputOutput />");
        String xml = validBpmn()
                .replace("xmlns:flowable=\"http://flowable.org/bpmn\"",
                        "xmlns:flowable=\"http://flowable.org/bpmn\" "
                                + "xmlns:camunda=\"http://camunda.org/schema/1.0/bpmn\"")
                .replace(ordinaryApprovalTaskXml(), task);

        WorkflowBpmnDocument document = service.validateForSave(
                xml.getBytes(StandardCharsets.UTF_8));

        assertThat(service.deploymentCompatibilityIssues(document)).singleElement()
                .satisfies(issue ->
                {
                    assertThat(issue.code())
                            .isEqualTo("BPMN_PRIVATE_EXTENSION_NOT_COMPATIBLE");
                    assertThat(issue.message()).contains("camunda", "禁止部署");
                });
        assertThatThrownBy(() -> service.validate(xml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getSubCode())
                                .isEqualTo("BPMN_PRIVATE_EXTENSION_NOT_COMPATIBLE"));
    }

    /**
     * 验证事件子流程和嵌入子流程的局部开始事件不会破坏主流程唯一开始节点约束。
     * @return 无返回值；递归开始事件被误计数或合法子流程无法保存时测试失败
     */
    @Test
    void allowsScopedStartEventsInsideEmbeddedAndEventSubProcesses()
    {
        String scopedStarts = validBpmn().replace(ordinaryApprovalTaskXml(), """
                <subProcess id="embedded" name="嵌入子流程">
                  <startEvent id="embeddedStart" />
                  <manualTask id="embeddedWork" />
                  <endEvent id="embeddedEnd" />
                  <sequenceFlow id="embeddedFlow1" sourceRef="embeddedStart" targetRef="embeddedWork" />
                  <sequenceFlow id="embeddedFlow2" sourceRef="embeddedWork" targetRef="embeddedEnd" />
                </subProcess>
                <subProcess id="signalHandler" triggeredByEvent="true">
                  <startEvent id="signalStart" isInterrupting="false">
                    <signalEventDefinition signalRef="signalEscalation" />
                  </startEvent>
                  <endEvent id="signalEnd" />
                  <sequenceFlow id="signalFlow" sourceRef="signalStart" targetRef="signalEnd" />
                </subProcess>
                """.strip()).replace(
                        "  <process id=\"expense\"",
                        "  <signal id=\"signalEscalation\" name=\"signalEscalation\"/>\n"
                                + "  <process id=\"expense\"");

        WorkflowBpmnDocument document = service.validateForSave(
                scopedStarts.getBytes(StandardCharsets.UTF_8));

        assertThat(document.bpmnModel().getMainProcess()
                .findFlowElementsOfType(StartEvent.class, true)).hasSize(3);
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
     * 验证服务任务不能引用任意 JDK 类、业务类或 Spring Bean。
     *
     * @return 无返回值；实现类白名单未生效时测试失败
     */
    @Test
    void rejectsUntrustedServiceTaskClass()
    {
        String xml = validBpmn()
                .replace(ordinaryApprovalTaskXml(),
                        "<serviceTask id=\"approve\" name=\"执行\" flowable:class=\"java.lang.Runtime\"/>");
        assertBadRequest(xml.getBytes(StandardCharsets.UTF_8), "受控扩展注册表");
    }

    /**
     * 验证作者 SendTask 只允许受控扩展字段，并拒绝标准 WebService 和操作引用直连。
     * @return 无返回值；发送任务绕过注册表或合法作者配置无法保存时测试失败
     */
    @Test
    void validatesControlledSendTaskAndRejectsDirectImplementation()
    {
        String controlled = validBpmn().replace(ordinaryApprovalTaskXml(), """
                <sendTask id="approve" name="发送通知">
                  <extensionElements>
                    <flowable:field name="approvaExtensionKey">
                      <flowable:string>approva.set-variable</flowable:string>
                    </flowable:field>
                    <flowable:field name="approvaExtensionConfig">
                      <flowable:string>{"targetVariable":"sent","value":true}</flowable:string>
                    </flowable:field>
                  </extensionElements>
                </sendTask>
                """.strip());

        WorkflowBpmnDocument author = service.validateForSave(
                controlled.getBytes(StandardCharsets.UTF_8));
        assertThat(author.bpmnModel().getMainProcess().getFlowElement("approve"))
                .isInstanceOf(org.flowable.bpmn.model.SendTask.class);

        String webService = controlled.replace("<sendTask id=\"approve\" name=\"发送通知\">",
                "<sendTask id=\"approve\" name=\"发送通知\" "
                        + "implementation=\"##WebService\" operationRef=\"tns:notify\">");
        assertBadRequest(webService.getBytes(StandardCharsets.UTF_8), "受控扩展注册表");
    }

    /**
     * 验证部署编译资源必须把作者 SendTask 收敛为固定委托 ServiceTask。
     * @return 无返回值；执行资源仍保留发送任务时测试失败
     */
    @Test
    void rejectsSendTaskInCompiledDeployment()
    {
        String compiledSendTask = validBpmn().replace(ordinaryApprovalTaskXml(), """
                <sendTask id="approve" name="发送通知">
                  <extensionElements>
                    <flowable:field name="approvaExtensionKey">
                      <flowable:string>approva.set-variable</flowable:string>
                    </flowable:field>
                  </extensionElements>
                </sendTask>
                """.strip());

        assertThatThrownBy(() -> service.validateCompiledDeployment(
                compiledSendTask.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("不允许保留发送任务");
    }

    /**
     * 验证部署编译资源只保留固定调度器且没有作者字段时可以重新安全校验。
     *
     * @return 无返回值；合法编译资源被误拒绝时测试失败
     */
    @Test
    void allowsCompiledDeploymentWithFixedDispatcherAndNoAuthorFields()
    {
        WorkflowBpmnDocument document = service.validateCompiledDeployment(
                compiledExtensionBpmn().getBytes(StandardCharsets.UTF_8));

        assertThat(document.bpmnModel().getProcessById("expense")).isNotNull();
        assertThat(document.formReferences()).hasSize(1);
    }

    /**
     * 验证编译资源重新出现作者扩展键或配置时立即拒绝，避免运行定义绕过冻结快照。
     *
     * @return 无返回值；作者字段进入执行 XML 时测试失败
     */
    @Test
    void rejectsAuthorFieldsInCompiledDeployment()
    {
        String xml = compiledExtensionBpmn().replace("</serviceTask>",
                "<extensionElements>"
                        + "<flowable:field name=\"approvaExtensionKey\" "
                        + "stringValue=\"approva.set-variable\"/>"
                        + "</extensionElements></serviceTask>");

        assertThatThrownBy(() -> service.validateCompiledDeployment(
                xml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(400);
                    assertThat(exception.getMessage()).contains("不允许保留作者扩展字段");
                });
    }

    /**
     * 验证编译资源仍禁止任意 Bean、类名或其他非固定调度实现。
     *
     * @return 无返回值；任意实现进入执行 XML 时测试失败
     */
    @Test
    void rejectsArbitraryImplementationInCompiledDeployment()
    {
        String xml = compiledExtensionBpmn().replace(
                "flowable:delegateExpression=\"${workflowExtensionDelegate}\"",
                "flowable:delegateExpression=\"${otherBean}\"");

        assertThatThrownBy(() -> service.validateCompiledDeployment(
                xml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(400);
                    assertThat(exception.getMessage()).contains("受控扩展注册表");
                });
    }

    /**
     * 验证平台 DMN 编译器生成的固定原生任务可以通过部署门禁，但作者不能直接声明同一引擎类型。
     *
     * @return 无返回值；合法冻结任务被拒绝或作者原生类型绕过时测试失败
     */
    @Test
    void allowsOnlyCompiledDmnServiceTask()
    {
        String compiled = compiledDmnBpmn();

        assertThat(service.validateCompiledDeployment(compiled.getBytes(StandardCharsets.UTF_8)))
                .isNotNull();
        assertBadRequest(compiled.getBytes(StandardCharsets.UTF_8), "安全白名单");
    }

    /**
     * 验证 DMN 编译结果必须只含固定决策键和 `sameDeployment=true`，拒绝表达式、额外字段及任意实现。
     *
     * @return 无返回值；任一可篡改 DMN 执行配置通过部署门禁时测试失败
     */
    @Test
    void rejectsTamperedCompiledDmnServiceTask()
    {
        String compiled = compiledDmnBpmn();
        List<String> invalidVariants = List.of(
                compiled.replace("stringValue=\"expenseDecision\"",
                        "expression=\"${decisionKey}\""),
                compiled.replace("stringValue=\"true\"", "stringValue=\"false\""),
                compiled.replace("</extensionElements>",
                        "<flowable:field name=\"unexpected\" stringValue=\"value\"/>"
                                + "</extensionElements>"),
                compiled.replace(" flowable:type=\"dmn\"",
                        " flowable:type=\"dmn\" flowable:class=\"java.lang.Runtime\""));

        for (String invalid : invalidVariants)
        {
            assertThatThrownBy(() -> service.validateCompiledDeployment(
                    invalid.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOfSatisfying(ServiceException.class,
                            error -> assertThat(error.getCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
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
     * 验证固定成员会签允许从开始节点直接进入，且能够识别设计器序列化后的 XML 单引号实体。
     *
     * @return 无返回值；固定成员被误要求动态前驱或原始 XML 白名单失配时测试失败
     */
    @Test
    void allowsFixedMultiInstanceWithoutDynamicInitializer()
    {
        String fixedBpmn = controlledMultiInstanceBpmn()
                .replace("${multiInstanceHandler.getUserIds(execution)}",
                        "${multiInstanceHandler.getFixedUserIds(execution, &#39;8,9&#39;)}")
                .replace("<sequenceFlow id=\"flow1\" sourceRef=\"start\" targetRef=\"prepare\"/>",
                        "<sequenceFlow id=\"flow1\" sourceRef=\"start\" targetRef=\"approve\"/>")
                .replace(ordinaryInitializerTaskXml(), "")
                .replace("<sequenceFlow id=\"flow2\" sourceRef=\"prepare\" targetRef=\"approve\"/>", "");

        assertThat(service.validate(fixedBpmn.getBytes(StandardCharsets.UTF_8)).formReferences())
                .hasSize(2);
    }

    /**
     * 验证发起时成员来源使用固定白名单表达式且不要求办理时选择前驱。
     *
     * @return 无返回值；设计器生成的发起来源模型被原始表达式门禁误拒绝时测试失败。
     */
    @Test
    void allowsStartMultiInstanceForSaveAndDeployment()
    {
        String startBpmn = controlledMultiInstanceBpmn()
                .replace("${multiInstanceHandler.getUserIds(execution)}",
                        "${multiInstanceHandler.getStartUserIds(execution)}")
                .replace("<sequenceFlow id=\"flow1\" sourceRef=\"start\" targetRef=\"prepare\"/>",
                        "<sequenceFlow id=\"flow1\" sourceRef=\"start\" targetRef=\"approve\"/>")
                .replace(ordinaryInitializerTaskXml(), "")
                .replace("<sequenceFlow id=\"flow2\" sourceRef=\"prepare\" targetRef=\"approve\"/>", "");

        assertThat(service.validateForSave(startBpmn.getBytes(StandardCharsets.UTF_8))).isNotNull();
        assertThat(service.validateCompiledDeployment(startBpmn.getBytes(StandardCharsets.UTF_8)))
                .isNotNull();
    }

    /**
     * 验证指定用户、角色和部门表达式及两项保留属性可通过保存和部署共同门禁。
     *
     * @return 无返回值；任一受控身份类型被原始表达式或属性门禁误拒绝时测试失败
     */
    @Test
    void allowsConfiguredMultiInstanceIdentitiesForSaveAndDeployment()
    {
        for (String type : List.of("USER", "ROLE", "DEPT"))
        {
            String xml = configuredMultiInstanceBpmn(type, "81,82");

            assertThat(service.validateForSave(xml.getBytes(StandardCharsets.UTF_8)))
                    .isNotNull();
            assertThat(service.validateCompiledDeployment(
                    xml.getBytes(StandardCharsets.UTF_8))).isNotNull();
        }
    }

    /**
     * 验证指定身份属性残缺、来源错配及候选身份链接都会在保存前失败。
     *
     * @return 无返回值；任一不完整或会生成候选链接的模型通过门禁时测试失败
     */
    @Test
    void rejectsIncompleteMismatchedAndCandidateConfiguredMultiInstanceModels()
    {
        String valid = configuredMultiInstanceBpmn("ROLE", "101");
        String incomplete = valid.replace(
                "<flowable:property name=\"approva.multiInstance.identityIds\" value=\"101\"/>",
                "");
        String mismatched = valid.replace(
                "${multiInstanceHandler.getConfiguredUserIds(execution)}",
                "${multiInstanceHandler.getStartUserIds(execution)}");
        String candidateGroup = valid.replace(
                "flowable:assignee=\"${assignee}\"",
                "flowable:assignee=\"${assignee}\" flowable:candidateGroups=\"ROLE101\"");

        assertBadRequest(incomplete.getBytes(StandardCharsets.UTF_8),
                "指定多实例身份配置不完整");
        assertBadRequest(mismatched.getBytes(StandardCharsets.UTF_8),
                "指定多实例身份属性与集合表达式不一致");
        assertBadRequest(candidateGroup.getBytes(StandardCharsets.UTF_8),
                "动态多实例");
    }

    /**
     * 验证发起成员 handler 仅允许契约完整等值，近似方法名不能借受控集合白名单执行。
     *
     * @return 无返回值；篡改后的发起成员方法表达式被错误放行时测试失败
     */
    @Test
    void rejectsTamperedStartMultiInstanceHandler()
    {
        String tamperedBpmn = controlledMultiInstanceBpmn().replace(
                "${multiInstanceHandler.getUserIds(execution)}",
                "${multiInstanceHandler.getStartUserIdsUnsafe(execution)}");

        assertBadRequest(tamperedBpmn.getBytes(StandardCharsets.UTF_8), "表达式");
    }

    /**
     * 验证固定业务监听 Bean 可携带唯一注册表键和 JSON 配置，同时系统审计监听器仍完整保留。
     * @return 无返回值；受控执行或任务监听器被误拒绝时测试失败
     */
    @Test
    void allowsControlledBusinessExecutionAndTaskListeners()
    {
        String businessListeners = """
                        <flowable:executionListener event="start" delegateExpression="${workflowBusinessListener}">
                          <flowable:field name="approvaExtensionKey" stringValue="approva.set-variable"/>
                          <flowable:field name="approvaExtensionConfig" stringValue="{&quot;targetVariable&quot;:&quot;entered&quot;,&quot;value&quot;:true}"/>
                        </flowable:executionListener>
                        <flowable:taskListener event="delete" delegateExpression="${workflowBusinessListener}">
                          <flowable:field name="approvaExtensionKey" stringValue="approva.set-variable"/>
                          <flowable:field name="approvaExtensionConfig" stringValue="{&quot;targetVariable&quot;:&quot;deleted&quot;,&quot;value&quot;:true}"/>
                        </flowable:taskListener>
                """;
        String source = validBpmn().replace(
                "<flowable:taskListener event=\"create\" delegateExpression=\"${userTaskListener}\"/>",
                businessListeners
                        + "<flowable:taskListener event=\"create\" delegateExpression=\"${userTaskListener}\"/>");

        assertThat(service.validateForSave(source.getBytes(StandardCharsets.UTF_8))).isNotNull();
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
                        + "flowable:delegateExpression=\"${workflowExtensionDelegate}\">"
                        + "<extensionElements>"
                        + "<flowable:field name=\"approvaExtensionKey\" "
                        + "stringValue=\"approva.set-variable\"/>"
                        + "<flowable:field name=\"approvaExtensionConfig\" stringValue=\"{}\"/>"
                        + "</extensionElements></serviceTask>");

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
     * 构造已剥离作者扩展键和配置、仅保留固定运行调度器的部署 BPMN。
     *
     * @return String，满足部署编译资源契约的 UTF-8 BPMN XML
     */
    private String compiledExtensionBpmn()
    {
        return validBpmn().replace(ordinaryApprovalTaskXml(),
                "<serviceTask id=\"approve\" name=\"执行扩展\" "
                        + "flowable:delegateExpression=\"${workflowExtensionDelegate}\">"
                        + "</serviceTask>");
    }

    /**
     * 构造 DMN 编译器完成精确来源解析后生成的固定 Flowable 原生服务任务。
     *
     * @return String，只包含决策键与同部署约束的 UTF-8 BPMN XML 测试数据
     */
    private String compiledDmnBpmn()
    {
        return validBpmn().replace(ordinaryApprovalTaskXml(),
                "<serviceTask id=\"approve\" name=\"执行决策\" flowable:type=\"dmn\">"
                        + "<extensionElements>"
                        + "<flowable:field name=\"decisionTableReferenceKey\" "
                        + "stringValue=\"expenseDecision\"/>"
                        + "<flowable:field name=\"sameDeployment\" stringValue=\"true\"/>"
                        + "</extensionElements></serviceTask>");
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
     * 构造从开始节点直接进入、带完整指定身份属性的受控会签 BPMN。
     *
     * @param type String，USER、ROLE 或 DEPT
     * @param ids String，逗号分隔的身份主键
     * @return String，可同时通过保存和部署结构门禁的 BPMN XML
     */
    private String configuredMultiInstanceBpmn(String type, String ids)
    {
        String xml = controlledMultiInstanceBpmn()
                .replace("${multiInstanceHandler.getUserIds(execution)}",
                        "${multiInstanceHandler.getConfiguredUserIds(execution)}")
                .replace("<sequenceFlow id=\"flow1\" sourceRef=\"start\" targetRef=\"prepare\"/>",
                        "<sequenceFlow id=\"flow1\" sourceRef=\"start\" targetRef=\"approve\"/>")
                .replace(ordinaryInitializerTaskXml(), "")
                .replace("<sequenceFlow id=\"flow2\" sourceRef=\"prepare\" targetRef=\"approve\"/>", "");
        String approvalExtension = """
                  <extensionElements>
                    <flowable:taskListener event="create" delegateExpression="${userTaskListener}"/>
                """.stripTrailing();
        String configuredExtension = """
                  <extensionElements>
                    <flowable:properties>
                      <flowable:property name="approva.multiInstance.identityType" value="%s"/>
                      <flowable:property name="approva.multiInstance.identityIds" value="%s"/>
                    </flowable:properties>
                    <flowable:taskListener event="create" delegateExpression="${userTaskListener}"/>
                """.formatted(type, ids).stripTrailing();
        if (!xml.contains(approvalExtension))
        {
            throw new AssertionError("合法多实例 fixture 缺少审批扩展元素");
        }
        return xml.replace(approvalExtension, configuredExtension);
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

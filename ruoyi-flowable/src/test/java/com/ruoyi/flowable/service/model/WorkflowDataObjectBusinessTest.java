package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WorkflowDataObjectBusinessTest
{
    private WorkflowFlowableEngineTestSupport support;
    private WorkflowBpmnService service;

    @BeforeEach void open()
    {
        support = WorkflowFlowableEngineTestSupport.start("data-object-contract", Map.of());
        service = new WorkflowBpmnService(support.processEngine().getRepositoryService(),
                new WorkflowFormFieldExtensionService(null), null);
    }
    @AfterEach void close() { support.close(); }

    @ParameterizedTest
    @CsvSource({ "string,hello,java.lang.String", "int,17,java.lang.Integer", "long,2147483648,java.lang.Long",
        "double,2.5,java.lang.Double", "boolean,true,java.lang.Boolean" })
    void typedDataObjectInitializesActualProcessVariable(String type, String value, String className)
    {
        String xml = model("<dataObject id=\"data\" name=\"payload\" itemSubjectRef=\"xsd:" + type
                + "\"><extensionElements><flowable:value>" + value + "</flowable:value></extensionElements></dataObject>");
        service.validate(xml.getBytes(StandardCharsets.UTF_8));
        var engine = support.processEngine();
        var deployment = engine.getRepositoryService().createDeployment().addString("data.bpmn20.xml", xml).deploy();
        try
        {
            var instance = engine.getRuntimeService().startProcessInstanceByKey("dataContract");
            Object actual = engine.getRuntimeService().getVariable(instance.getId(), "payload");
            assertThat(actual.getClass().getName()).isEqualTo(className);
            assertThat(actual.toString()).isEqualTo(value);
            engine.getRuntimeService().trigger(engine.getRuntimeService().createExecutionQuery()
                    .processInstanceId(instance.getId()).activityId("wait").singleResult().getId());
            assertThat(engine.getHistoryService().createHistoricProcessInstanceQuery()
                    .processInstanceId(instance.getId()).finished().count()).isOne();
        }
        finally { engine.getRepositoryService().deleteDeployment(deployment.getId(), true); }
    }

    @ParameterizedTest
    @CsvSource({ "initiator,string,hello,NAME", "payload,int,NaN,VALUE", "payload,double,Infinity,VALUE",
        "payload,boolean,yes,VALUE", "payload,unknown,hello,TYPE" })
    void rejectsReservedNamesAndInvalidTypesBeforeConverterCanDropData(String name, String type, String value, String code)
    {
        String xml = model("<dataObject id=\"data\" name=\"" + name + "\" itemSubjectRef=\"xsd:" + type
                + "\"><extensionElements><flowable:value>" + value + "</flowable:value></extensionElements></dataObject>"
                + "<dataObjectReference id=\"visibleData\" dataObjectRef=\"data\"/>");
        assertThatThrownBy(() -> service.validateForSave(xml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ServiceException.class, exception -> {
                    assertThat(exception.getSubCode()).isEqualTo("BPMN_DATA_OBJECT_" + code);
                    assertThat(((WorkflowBpmnValidationException) exception.getCause()).issues().get(0).elementId()).isEqualTo("visibleData");
                    assertThat(((WorkflowBpmnValidationException) exception.getCause()).issues().get(0).processId()).isEqualTo("dataContract");
                });
    }

    @Test void rejectsSameScopeDuplicateButPermitsSharedReferences()
    {
        String object = "<dataObject id=\"data\" name=\"payload\"/>";
        service.validate(model(object + "<dataObjectReference id=\"ref1\" dataObjectRef=\"data\"/>"
                + "<dataObjectReference id=\"ref2\" dataObjectRef=\"data\"/>").getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.validateForSave(model(object + "<dataObject id=\"other\" name=\"payload\"/>")
                .getBytes(StandardCharsets.UTF_8))).isInstanceOf(ServiceException.class);
    }

    @Test void messageThrowHasLocatedAuthorWarningAndDeploymentRemainsBlocked()
    {
        String xml = model("").replace("<receiveTask id=\"wait\"/>",
                "<intermediateThrowEvent id=\"wait\"><messageEventDefinition messageRef=\"notice\"/></intermediateThrowEvent>")
                .replace("<process id=", "<message id=\"notice\" name=\"notice\"/><process id=");
        var document = service.validateForSave(xml.getBytes(StandardCharsets.UTF_8));
        assertThat(service.authorValidationIssues(document)).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("BPMN_MESSAGE_THROW_NOT_EXECUTABLE");
            assertThat(issue.elementId()).isEqualTo("wait");
            assertThat(issue.processId()).isEqualTo("dataContract");
        });
        assertThatThrownBy(() -> service.validateDeployable(document)).isInstanceOf(ServiceException.class);
    }

    @Test void compilerPipelinePreservesReferencedDataObjectsAndTheirRuntimeValues()
    {
        String xml = model("<dataObject id=\"data\" name=\"payload\" itemSubjectRef=\"xsd:string\"><extensionElements><flowable:value>kept</flowable:value></extensionElements></dataObject>"
                + "<dataObjectReference id=\"visibleData\" dataObjectRef=\"data\"/>"
                + "<dataStoreReference id=\"archive\"/>"
                + "<textAnnotation id=\"note\"><text>业务说明</text></textAnnotation>"
                + "<association id=\"link\" sourceRef=\"note\" targetRef=\"visibleData\"/>")
                .replace("<receiveTask id=\"wait\"/>", "<receiveTask id=\"wait\"><property id=\"inputProperty\" name=\"input\"/>"
                    + "<dataInputAssociation><sourceRef>visibleData</sourceRef><targetRef>inputProperty</targetRef></dataInputAssociation></receiveTask>");
        xml = xml.replace("<process id=", "<collaboration id=\"collaboration\"><participant id=\"pool\" processRef=\"dataContract\"/>"
                + "<textAnnotation id=\"poolNote\"><text>池说明</text></textAnnotation></collaboration><process id=");
        var document = service.validateForSave(xml.getBytes(StandardCharsets.UTF_8));
        var extension = new WorkflowExtensionDeploymentService(null, null, null, null);
        var executable = service.executionModel(document);
        extension.prepare(document, executable, "1");
        new WorkflowParticipantRuleDeploymentService(null).prepare(document, executable,
                WorkflowAuthorFormFieldCatalog.builder().build(), "1");
        new WorkflowTaskSlaDeploymentService(null, null, null).prepare(executable, "1");
        new WorkflowRuntimeTaskListenerCompiler().compile(executable);
        byte[] compiled = WorkflowBpmnXmlPreserver.write(executable, document.bpmnXml());
        assertThat(new String(compiled, StandardCharsets.UTF_8)).contains("dataObjectReference", "visibleData", "archive", "业务说明");
        assertThat(document.bpmnXml()).isEqualTo(xml);
        var engine = support.processEngine();
        engine.getRepositoryService().createDeployment().addBytes("preserved.bpmn20.xml", compiled).deploy();
        var instance = engine.getRuntimeService().startProcessInstanceByKey("dataContract");
        assertThat(engine.getRuntimeService().getVariable(instance.getId(), "payload")).isEqualTo("kept");
        engine.getRuntimeService().trigger(engine.getRuntimeService().createExecutionQuery()
                .processInstanceId(instance.getId()).activityId("wait").singleResult().getId());
        assertThat(engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(instance.getId()).finished().count()).isOne();
    }

    @ParameterizedTest
    @CsvSource({ "true,true", "true,false", "false,true" })
    void callActivityDataAssociationsSurviveCompilationAndActualChildCompletion(boolean input, boolean output)
    {
        String associations = "<property id=\"callProperty\" name=\"__targetRef_placeholder\"/>"
                + (input ? "<dataInputAssociation id=\"dataIn\"><sourceRef>visibleData</sourceRef><targetRef>callProperty</targetRef></dataInputAssociation>" : "")
                + (output ? "<dataOutputAssociation id=\"dataOut\" name=\"归档结果\"><targetRef>archive</targetRef></dataOutputAssociation>" : "");
        String xml = model("<dataObject id=\"data\" name=\"amount\"/><dataObjectReference id=\"visibleData\" dataObjectRef=\"data\"/>"
                + "<dataStoreReference id=\"archive\"/>")
                .replace("<receiveTask id=\"wait\"/>", "<callActivity id=\"wait\" calledElement=\"childData\"><extensionElements>"
                        + "<flowable:in source=\"parentValue\" target=\"childValue\"/><flowable:out source=\"childValue\" target=\"parentValue\"/>"
                        + "</extensionElements>" + associations + "</callActivity>");
        var document = service.validateForSave(xml.getBytes(StandardCharsets.UTF_8));
        var executable = service.executionModel(document);
        new WorkflowExtensionDeploymentService(null, null, null, null).prepare(document, executable, "1");
        new WorkflowParticipantRuleDeploymentService(null).prepare(document, executable,
                WorkflowAuthorFormFieldCatalog.builder().build(), "1");
        new WorkflowTaskSlaDeploymentService(null, null, null).prepare(executable, "1");
        new WorkflowRuntimeTaskListenerCompiler().compile(executable);
        byte[] compiled = WorkflowBpmnXmlPreserver.write(executable, document.bpmnXml());
        var engine = support.processEngine();
        engine.getRepositoryService().createDeployment().addString("child.bpmn20.xml", """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="urn:test:data">
              <process id="childData" isExecutable="true"><startEvent id="childStart"/><receiveTask id="childWait"/>
                <endEvent id="childEnd"/><sequenceFlow id="c1" sourceRef="childStart" targetRef="childWait"/>
                <sequenceFlow id="c2" sourceRef="childWait" targetRef="childEnd"/></process></definitions>
            """).addBytes("parent.bpmn20.xml", compiled).deploy();
        var parent = engine.getRuntimeService().startProcessInstanceByKey("dataContract", Map.of("parentValue", 7));
        var child = engine.getRuntimeService().createProcessInstanceQuery().superProcessInstanceId(parent.getId()).singleResult();
        assertThat(engine.getRuntimeService().getVariable(child.getId(), "childValue")).isEqualTo(7);
        engine.getRuntimeService().setVariable(child.getId(), "childValue", 8);
        engine.getRuntimeService().trigger(engine.getRuntimeService().createExecutionQuery()
                .processInstanceId(child.getId()).activityId("childWait").singleResult().getId());
        assertThat(engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(parent.getId()).finished().count()).isOne();
        assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery().processInstanceId(parent.getId())
                .variableName("parentValue").singleResult().getValue()).isEqualTo(8);
        assertThat(document.bpmnXml()).isEqualTo(xml);
        if (input) assertThat(document.bpmnXml()).contains("dataIn");
        if (output) assertThat(document.bpmnXml()).contains("dataOut");
    }

    @ParameterizedTest
    @CsvSource({ "missing", "privateData", "start" })
    void invalidOrSiblingScopeReferenceIsRejectedBeforeDeployment(String target)
    {
        String xml = model("<subProcess id=\"otherScope\"><dataObject id=\"privateData\" name=\"privateValue\" itemSubjectRef=\"xsd:string\"/></subProcess>")
                .replace("<receiveTask id=\"wait\"/>", "<receiveTask id=\"wait\"><dataOutputAssociation id=\"badAssociation\">"
                    + "<targetRef>" + target + "</targetRef></dataOutputAssociation></receiveTask>");
        assertThatThrownBy(() -> service.validateForSave(xml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ServiceException.class, error -> {
                    assertThat(error.getSubCode()).isEqualTo("BPMN_DATA_REFERENCE_INVALID");
                    var issue = ((WorkflowBpmnValidationException) error.getCause()).issues().get(0);
                    assertThat(issue.elementId()).isEqualTo("badAssociation");
                    assertThat(issue.processId()).isEqualTo("dataContract");
                    assertThat(issue.fieldPath()).isEqualTo("targetRef");
                });
        assertThat(support.processEngine().getRepositoryService().createDeploymentQuery().count()).isZero();
    }

    private String model(String elements)
    {
        return """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn"
              xmlns:xsd="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:test:data">
              <process id="dataContract" isExecutable="true"><extensionElements><flowable:properties>
                <flowable:property name="approva.startScope.ruleVersion" value="1"/>
                <flowable:property name="approva.startScope.type" value="PUBLIC"/>
                <flowable:property name="approva.startScope.targetIds" value=""/>
                <flowable:property name="approva.startScope.noMatchPolicy" value="FAIL"/>
              </flowable:properties></extensionElements>
              <startEvent id="start"><extensionElements><flowable:formProperty id="reason" name="事由" type="string" readable="true" writable="true"/></extensionElements></startEvent>
              <receiveTask id="wait"/><endEvent id="end"/>
              <sequenceFlow id="f1" sourceRef="start" targetRef="wait"/><sequenceFlow id="f2" sourceRef="wait" targetRef="end"/>
              %s</process></definitions>
            """.formatted(elements);
    }
}

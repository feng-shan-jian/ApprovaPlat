package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.UserTask;
import org.junit.jupiter.api.Test;

class WorkflowBpmnPropertiesTest
{
    @Test
    void eachDomainRejectsDuplicatesAcrossContainersWithItsOriginalMessage()
    {
        SequenceFlow flow = new SequenceFlow();
        flow.setExtensionElements(duplicate(WorkflowConditionRuleBpmnContract.CONFIG_PROPERTY));
        assertThatThrownBy(() -> WorkflowConditionRuleBpmnContract.readConfig(flow))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("条件分支受控属性不能重复");
        UserTask task = new UserTask();
        task.setExtensionElements(duplicate(WorkflowControlledLoopBpmnContract.MAX_ITERATIONS));
        assertThatThrownBy(() -> WorkflowControlledLoopBpmnContract.readAuthorConfig("process", task))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("受控循环属性不能重复");
        task.setExtensionElements(duplicate(WorkflowParticipantRuleBpmnContract.TASK_VERSION));
        assertThatThrownBy(() -> WorkflowParticipantRuleBpmnContract.readTaskRule("process", task))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("参与者规则属性不能重复");
    }

    @Test
    void sequentialCompilationRemovesOnlyEachDomainsProperties()
    {
        ExtensionElement foreign = property("business.property", "keep");
        ExtensionElement listener = new ExtensionElement();
        ExtensionElement container = container(
                property(WorkflowConditionRuleBpmnContract.CONFIG_PROPERTY, "{}"),
                property(WorkflowParticipantRuleBpmnContract.TASK_VERSION, "1"),
                foreign,
                property(WorkflowControlledLoopBpmnContract.MAX_ITERATIONS, "3"));
        List<ExtensionElement> original = container.getChildElements().get("property");
        UserTask task = new UserTask();
        task.setExtensionElements(Map.of("properties", List.of(container), "taskListener", List.of(listener)));

        WorkflowControlledLoopBpmnContract.removeAuthorProperties(task);
        assertThat(container.getChildElements().get("property")).hasSize(3).contains(foreign);
        WorkflowParticipantRuleBpmnContract.removeAuthorProperties(task);
        assertThat(container.getChildElements().get("property")).hasSize(2).contains(foreign);
        SequenceFlow flow = new SequenceFlow();
        flow.setExtensionElements(task.getExtensionElements());
        assertThat(WorkflowConditionRuleBpmnContract.readConfig(flow)).contains("{}");
        WorkflowConditionRuleBpmnContract.removeAuthorConfig(flow);
        assertThat(container.getChildElements().get("property")).containsExactly(foreign);
        assertThat(flow.getExtensionElements().get("taskListener")).containsExactly(listener);
        assertThat(original).hasSize(4);
    }

    @Test
    void conditionPropertiesIgnoreUnnamedForeignEntriesAndKeepBlankValueErrors()
    {
        SequenceFlow flow = new SequenceFlow();
        flow.setExtensionElements(Map.of("properties", List.of(container(new ExtensionElement(),
                property(WorkflowConditionRuleBpmnContract.CONFIG_PROPERTY, " ")))));
        assertThatThrownBy(() -> WorkflowConditionRuleBpmnContract.readConfig(flow))
                .hasMessage("条件分支规则正文为空或超过长度限制");
        WorkflowConditionRuleBpmnContract.removeAuthorConfig(flow);
        assertThat(flow.getExtensionElements().get("properties").get(0)
                .getChildElements().get("property")).hasSize(1);
    }

    private Map<String, List<ExtensionElement>> duplicate(String name)
    {
        return Map.of("properties", List.of(container(property(name, "1")), container(property(name, "2"))));
    }

    private ExtensionElement container(ExtensionElement... properties)
    {
        ExtensionElement container = new ExtensionElement();
        container.setName("properties");
        container.setChildElements(Map.of("property", List.of(properties)));
        return container;
    }

    private ExtensionElement property(String name, String value)
    {
        ExtensionElement property = new ExtensionElement();
        property.setName("property");
        ExtensionAttribute nameAttribute = new ExtensionAttribute();
        nameAttribute.setName("name");
        nameAttribute.setValue(name);
        property.addAttribute(nameAttribute);
        ExtensionAttribute valueAttribute = new ExtensionAttribute();
        valueAttribute.setName("value");
        valueAttribute.setValue(value);
        property.addAttribute(valueAttribute);
        return property;
    }
}

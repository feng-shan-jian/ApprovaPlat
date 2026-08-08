package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.UserTask;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.service.model.WorkflowAutoCopyRuleContract.RecipientType;
import com.ruoyi.flowable.service.model.WorkflowAutoCopyRuleContract.Trigger;

/**
 * 自动抄送 BPMN 规则结构、触发放置和来源边界测试。
 */
class WorkflowAutoCopyRuleContractTest
{
    /**
     * 验证多来源节点规则能够稳定解析并保留设计器顺序。
     * @return void，任一来源或触发值不符合契约时测试失败
     */
    @Test
    void parsesControlledNodeRuleWithAllRecipientSources()
    {
        UserTask task = elementWithRules(new UserTask(), """
                {"version":1,"rules":[{"id":"arrival-review","trigger":"NODE_ARRIVED",
                "recipients":[{"type":"USER","values":["2","3"]},
                {"type":"GROUP","values":["ROLE4","DEPT5"]},
                {"type":"INITIATOR"},
                {"type":"FORM_USER_FIELD","values":["reviewerId"]}]}]}
                """);

        var rules = WorkflowAutoCopyRuleContract.readRules(task);

        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).trigger()).isEqualTo(Trigger.NODE_ARRIVED);
        assertThat(rules.get(0).recipients()).extracting(source -> source.type())
                .containsExactly(RecipientType.USER, RecipientType.GROUP,
                        RecipientType.INITIATOR, RecipientType.FORM_USER_FIELD);
    }

    /**
     * 验证流程和任务只能使用各自允许的生命周期触发时机。
     * @return void，错误放置未被拒绝时测试失败
     */
    @Test
    void rejectsTriggerPlacedOnWrongBpmnElement()
    {
        Process process = elementWithRules(new Process(), """
                {"version":1,"rules":[{"id":"wrong","trigger":"NODE_COMPLETED",
                "recipients":[{"type":"INITIATOR"}]}]}
                """);

        assertThatThrownBy(() -> WorkflowAutoCopyRuleContract.validatePlacement(process,
                Set.of(Trigger.PROCESS_COMPLETED)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("触发时机");
    }

    /**
     * 验证自由表达式、非法组编码和重复来源不能进入部署资源。
     * @return void，受控身份来源可被绕过时测试失败
     */
    @Test
    void rejectsExpressionsAndDuplicateRecipientSources()
    {
        UserTask expression = elementWithRules(new UserTask(), """
                {"version":1,"rules":[{"id":"bad","trigger":"NODE_COMPLETED",
                "recipients":[{"type":"FORM_USER_FIELD","values":["${user}"]}]}]}
                """);
        UserTask duplicate = elementWithRules(new UserTask(), """
                {"version":1,"rules":[{"id":"bad","trigger":"NODE_COMPLETED",
                "recipients":[{"type":"GROUP","values":["ROLE2"]},
                {"type":"GROUP","values":["ROLE2"]}]}]}
                """);

        assertThatThrownBy(() -> WorkflowAutoCopyRuleContract.readRules(expression))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> WorkflowAutoCopyRuleContract.readRules(duplicate))
                .isInstanceOf(ServiceException.class).hasMessageContaining("重复");
    }

    /**
     * 为测试元素写入标准 Flowable properties 容器。
     * @param element T，流程或用户任务
     * @param json String，自动抄送规则 JSON
     * @return T，带受控扩展属性的原元素
     */
    private <T extends org.flowable.bpmn.model.BaseElement> T elementWithRules(
            T element, String json)
    {
        ExtensionElement property = new ExtensionElement();
        property.setName("property");
        property.setNamespace("http://flowable.org/bpmn");
        property.addAttribute(new ExtensionAttribute("name",
                WorkflowAutoCopyRuleContract.PROPERTY_NAME));
        property.addAttribute(new ExtensionAttribute("value", json.strip()));

        ExtensionElement container = new ExtensionElement();
        container.setName("properties");
        container.setNamespace("http://flowable.org/bpmn");
        Map<String, List<ExtensionElement>> children = new HashMap<>();
        children.put("property", List.of(property));
        container.setChildElements(children);
        element.addExtensionElement(container);
        return element;
    }
}

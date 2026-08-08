package com.ruoyi.flowable.service.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.flowable.bpmn.model.BaseElement;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.SequenceFlow;

/**
 * 条件分支在作者 BPMN 中使用的单一受控属性契约。
 */
public final class WorkflowConditionRuleBpmnContract
{
    /** 受控规则 JSON 的 Flowable 扩展属性名。 */
    public static final String CONFIG_PROPERTY = "approva.conditionRule.config";

    /** 单条分支规则 JSON 的作者资源长度上限。 */
    public static final int MAX_CONFIG_LENGTH = 16384;

    private WorkflowConditionRuleBpmnContract()
    {
    }

    /**
     * 判断元素是否夹带了条件分支平台保留属性。
     * @param element BaseElement，待检查 BPMN 元素
     * @return boolean，存在受控规则属性时返回 true
     */
    public static boolean hasReservedProperty(BaseElement element)
    {
        return readPropertyValues(element).containsKey(CONFIG_PROPERTY);
    }

    /**
     * 从顺序流读取唯一受控规则 JSON，并拒绝重复属性。
     * @param sequenceFlow SequenceFlow，网关出线
     * @return Optional&lt;String&gt;，未配置时为空；配置时返回原始 JSON
     */
    public static Optional<String> readConfig(SequenceFlow sequenceFlow)
    {
        String config = readPropertyValues(sequenceFlow).get(CONFIG_PROPERTY);
        if (config == null)
        {
            return Optional.empty();
        }
        if (config.isBlank() || config.length() > MAX_CONFIG_LENGTH)
        {
            throw new IllegalArgumentException("条件分支规则正文为空或超过长度限制");
        }
        return Optional.of(config);
    }

    /**
     * 从编译执行资源移除作者规则，运行时只能读取数据库部署快照。
     * @param sequenceFlow SequenceFlow，正在编译的网关出线
     * @return void，无返回值；其他扩展属性保持不变
     */
    public static void removeAuthorConfig(SequenceFlow sequenceFlow)
    {
        if (sequenceFlow == null || sequenceFlow.getExtensionElements() == null)
        {
            return;
        }
        Map<String, List<ExtensionElement>> extensions = sequenceFlow.getExtensionElements();
        List<ExtensionElement> containers = extensions.get("properties");
        if (containers == null || containers.isEmpty())
        {
            return;
        }
        List<ExtensionElement> remainingContainers = new ArrayList<>();
        for (ExtensionElement container : containers)
        {
            Map<String, List<ExtensionElement>> children = container.getChildElements();
            if (children == null || children.get("property") == null)
            {
                remainingContainers.add(container);
                continue;
            }
            List<ExtensionElement> properties = children.get("property").stream()
                    .filter(property -> !CONFIG_PROPERTY.equals(
                            property.getAttributeValue(null, "name")))
                    .toList();
            if (!properties.isEmpty())
            {
                Map<String, List<ExtensionElement>> copiedChildren = new HashMap<>(children);
                copiedChildren.put("property", new ArrayList<>(properties));
                container.setChildElements(copiedChildren);
                remainingContainers.add(container);
            }
        }
        Map<String, List<ExtensionElement>> copiedExtensions = new HashMap<>(extensions);
        if (remainingContainers.isEmpty())
        {
            copiedExtensions.remove("properties");
        }
        else
        {
            copiedExtensions.put("properties", remainingContainers);
        }
        sequenceFlow.setExtensionElements(copiedExtensions);
    }

    /**
     * 读取 Flowable properties 容器并拒绝平台保留属性重复。
     * @param element BaseElement，待读取 BPMN 元素
     * @return Map&lt;String,String&gt;，平台属性名到原始值的不可变映射
     */
    private static Map<String, String> readPropertyValues(BaseElement element)
    {
        if (element == null || element.getExtensionElements() == null)
        {
            return Map.of();
        }
        Map<String, String> values = new HashMap<>();
        for (ExtensionElement container : element.getExtensionElements()
                .getOrDefault("properties", List.of()))
        {
            if (container == null || container.getChildElements() == null)
            {
                continue;
            }
            for (ExtensionElement property : container.getChildElements()
                    .getOrDefault("property", List.of()))
            {
                String name = property.getAttributeValue(null, "name");
                if (!CONFIG_PROPERTY.equals(name))
                {
                    continue;
                }
                if (values.put(name, property.getAttributeValue(null, "value")) != null)
                {
                    throw new IllegalArgumentException("条件分支受控属性不能重复");
                }
            }
        }
        return Map.copyOf(values);
    }
}

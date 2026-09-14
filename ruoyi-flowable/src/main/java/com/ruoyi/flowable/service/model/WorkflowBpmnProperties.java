package com.ruoyi.flowable.service.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.flowable.bpmn.model.BaseElement;
import org.flowable.bpmn.model.ExtensionElement;

/** Flowable 作者属性的容器读写；属性归属、内容校验和错误消息由各领域契约决定。 */
final class WorkflowBpmnProperties
{
    private WorkflowBpmnProperties()
    {
    }

    static Map<String, String> read(BaseElement element, Predicate<String> ownsProperty,
            String duplicateMessage)
    {
        if (element == null || element.getExtensionElements() == null) return Map.of();
        Map<String, String> values = new HashMap<>();
        for (ExtensionElement container : element.getExtensionElements()
                .getOrDefault("properties", List.of()))
        {
            if (container == null || container.getChildElements() == null) continue;
            for (ExtensionElement property : container.getChildElements()
                    .getOrDefault("property", List.of()))
            {
                String name = property.getAttributeValue(null, "name");
                if (!ownsProperty.test(name)) continue;
                if (values.put(name, property.getAttributeValue(null, "value")) != null)
                {
                    throw new IllegalArgumentException(duplicateMessage);
                }
            }
        }
        return Map.copyOf(values);
    }

    /** 只移除指定属性；保留其余扩展对象和顺序，不修改原容器的集合。 */
    static void remove(BaseElement element, Predicate<String> ownsProperty)
    {
        if (element == null || element.getExtensionElements() == null) return;
        Map<String, List<ExtensionElement>> extensions = element.getExtensionElements();
        List<ExtensionElement> containers = extensions.get("properties");
        if (containers == null) return;
        List<ExtensionElement> remainingContainers = new ArrayList<>();
        for (ExtensionElement container : containers)
        {
            Map<String, List<ExtensionElement>> children = container.getChildElements();
            List<ExtensionElement> properties = children == null ? null : children.get("property");
            if (properties == null)
            {
                remainingContainers.add(container);
                continue;
            }
            List<ExtensionElement> remaining = properties.stream()
                    .filter(property -> !ownsProperty.test(property.getAttributeValue(null, "name")))
                    .toList();
            if (!remaining.isEmpty())
            {
                Map<String, List<ExtensionElement>> copied = new HashMap<>(children);
                copied.put("property", new ArrayList<>(remaining));
                container.setChildElements(copied);
                remainingContainers.add(container);
            }
        }
        Map<String, List<ExtensionElement>> copied = new HashMap<>(extensions);
        if (remainingContainers.isEmpty()) copied.remove("properties");
        else copied.put("properties", remainingContainers);
        element.setExtensionElements(copied);
    }
}

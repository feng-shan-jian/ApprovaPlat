package com.ruoyi.flowable.service.model;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.flowable.bpmn.model.*;
import org.w3c.dom.Element;
import com.ruoyi.flowable.domain.vo.WorkflowBpmnValidationIssue;

/** 新作者协议默认不授予。Flowable 8 不读取自定义表单标志，在进入快照转换前显式解析。 */
final class WorkflowSystemOutputContract
{
    static final String ATTRIBUTE = "systemWritable";
    private WorkflowSystemOutputContract() { }

    static void apply(String xml, BpmnModel model)
    {
        try
        {
            var document = WorkflowBpmnXmlPreserver.parse(xml.getBytes(StandardCharsets.UTF_8));
            var nodes = document.getElementsByTagNameNS("http://flowable.org/bpmn", "formProperty");
            for (int i = 0; i < nodes.getLength(); i++)
            {
                Element node = (Element) nodes.item(i);
                if (!node.hasAttribute(ATTRIBUTE)) continue;
                Element owner = (Element) node.getParentNode().getParentNode();
                String id = owner.getAttribute("id");
                String value = node.getAttribute(ATTRIBUTE);
                var element = model.getFlowElement(id);
                List<FormProperty> fields = element instanceof StartEvent start ? start.getFormProperties()
                        : element instanceof UserTask task ? task.getFormProperties() : List.of();
                String formKey = element instanceof StartEvent start ? start.getFormKey()
                        : element instanceof UserTask task ? task.getFormKey() : null;
                var field = fields.stream().filter(item -> node.getAttribute("id").equals(item.getId())).findFirst().orElse(null);
                if ((!"true".equals(value) && !"false".equals(value)) || field == null
                        || (formKey != null && !formKey.isBlank())
                        || ("true".equals(value) && (!field.isReadable() || field.isWriteable() || field.isRequired())))
                    throw new WorkflowBpmnValidationException(List.of(new WorkflowBpmnValidationIssue(
                            "BPMN_SYSTEM_OUTPUT_PERMISSION", "ERROR", id, "系统结果字段必须可读、用户只读且非必填；模板字段请在正式模板声明"))).asServiceException();
                ExtensionAttribute attribute = new ExtensionAttribute();
                attribute.setName(ATTRIBUTE);
                attribute.setValue(value);
                field.addAttribute(attribute);
            }
        }
        catch (com.ruoyi.common.exception.ServiceException exception) { throw exception; }
        catch (Exception exception)
        {
            throw new com.ruoyi.common.exception.ServiceException("系统结果字段协议无法解析", 400);
        }
    }

    static boolean enabled(FormProperty property)
    {
        return property.getAttributes().getOrDefault(ATTRIBUTE, List.of()).stream()
                .anyMatch(attribute -> "true".equals(attribute.getValue()));
    }
}

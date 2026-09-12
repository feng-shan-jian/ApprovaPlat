package com.ruoyi.flowable.service.model;

import java.util.ArrayList;
import java.util.List;
import org.flowable.bpmn.model.BaseElement;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FieldExtension;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.ImplementationType;
import org.flowable.bpmn.model.MessageEventDefinition;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.ThrowEvent;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.vo.WorkflowBpmnValidationIssue;
import com.ruoyi.flowable.extension.WorkflowCollaborationOutboxHandler;
import com.ruoyi.flowable.extension.WorkflowExtensionBpmnContract;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 消息抛出的作者配置与执行编译契约；作者事件和执行任务共用原节点 ID。 */
public final class WorkflowMessageThrowContract
{
    public static final String CONFIG_PROPERTY = "approva.messageThrow.config";
    public static final int MAX_CONFIG_LENGTH = 16384;
    private WorkflowMessageThrowContract() { }

    /** 未配置的历史事件可继续编辑；配置只能存在于单一消息抛出事件上。 */
    public static String readConfig(BaseElement element)
    {
        String result = null;
        for (ExtensionElement container : element.getExtensionElements().getOrDefault("properties", List.of()))
        {
            for (ExtensionElement property : container.getChildElements().getOrDefault("property", List.of()))
            {
                if (!CONFIG_PROPERTY.equals(property.getAttributeValue(null, "name"))) continue;
                String value = property.getAttributeValue(null, "value");
                if (result != null || value == null || value.isBlank() || value.length() > MAX_CONFIG_LENGTH)
                    throw invalid(element, "可靠消息配置重复、为空或超过长度限制");
                result = value;
            }
        }
        if (result != null && (!(element instanceof ThrowEvent event)
                || event.getEventDefinitions().size() != 1
                || !(event.getEventDefinitions().get(0) instanceof MessageEventDefinition)))
            throw invalid(element, "可靠消息配置只能用于单一消息抛出事件");
        return result;
    }

    /** 校验消息定义与白名单配置；端点与处理器版本由正式部署事务锁定。 */
    public static JsonNode validate(BpmnModel model, ThrowEvent event)
    {
        String config = readConfig(event);
        if (config == null) return null;
        try
        {
            JsonNode parsed = JsonMapper.shared().readTree(config);
            WorkflowCollaborationOutboxHandler.validateAuthorConfig(parsed);
            var definition = (MessageEventDefinition) event.getEventDefinitions().get(0);
            var message = model.getMessage(definition.getMessageRef());
            if (message == null || !parsed.path("messageName").asText().equals(message.getName()))
                throw invalid(event, "可靠消息名称必须与事件引用的消息定义一致");
            return parsed;
        }
        catch (JacksonException exception) { throw invalid(event, "可靠消息配置不是合法 JSON"); }
        catch (ServiceException exception)
        {
            if (exception.getCause() instanceof WorkflowBpmnValidationException) throw exception;
            throw invalid(event, exception.getMessage());
        }
    }

    /** 只修改调用方提供的执行副本，保留拓扑、监听器、异步属性及 DI 使用的节点 ID。 */
    public static ServiceTask compile(BpmnModel model, org.flowable.bpmn.model.Process process,
            ThrowEvent event)
    {
        JsonNode config = validate(model, event);
        if (config == null) throw invalid(event, "消息抛出必须配置可靠消息发送参数");
        var container = process.findParent(event);
        if (container == null) throw invalid(event, "消息抛出缺少所属流程容器");
        ServiceTask task = new ServiceTask();
        task.setValues((FlowNode) event);
        for (ExtensionElement properties : task.getExtensionElements().getOrDefault("properties", List.of()))
        {
            var remaining = new ArrayList<>(properties.getChildElements().getOrDefault("property", List.of()));
            remaining.removeIf(property -> CONFIG_PROPERTY.equals(property.getAttributeValue(null, "name")));
            properties.getChildElements().put("property", remaining);
        }
        task.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
        task.setImplementation(WorkflowExtensionBpmnContract.DELEGATE_EXPRESSION);
        task.setFieldExtensions(new ArrayList<>(List.of(
                field(WorkflowExtensionBpmnContract.EXTENSION_KEY_FIELD, WorkflowCollaborationOutboxHandler.EXTENSION_KEY),
                field(WorkflowExtensionBpmnContract.EXTENSION_CONFIG_FIELD, config.toString()))));
        container.removeFlowElement(event.getId());
        container.addFlowElement(task);
        task.getIncomingFlows().forEach(flow -> flow.setTargetFlowElement(task));
        task.getOutgoingFlows().forEach(flow -> flow.setSourceFlowElement(task));
        return task;
    }

    private static FieldExtension field(String name, String value)
    {
        FieldExtension field = new FieldExtension();
        field.setFieldName(name);
        field.setStringValue(value);
        return field;
    }

    private static ServiceException invalid(BaseElement element, String message)
    {
        org.flowable.bpmn.model.FlowElementsContainer parent = element instanceof org.flowable.bpmn.model.FlowElement flow
                ? flow.getParentContainer() : null;
        while (parent instanceof org.flowable.bpmn.model.SubProcess nested) parent = nested.getParentContainer();
        String processId = parent instanceof org.flowable.bpmn.model.Process process ? process.getId() : null;
        return new WorkflowBpmnValidationException(List.of(new WorkflowBpmnValidationIssue(
                "BPMN_MESSAGE_THROW_CONFIG_INVALID", "ERROR", element.getId(), message, processId))).asServiceException();
    }
}

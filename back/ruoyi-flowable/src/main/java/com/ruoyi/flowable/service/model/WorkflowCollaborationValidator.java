package com.ruoyi.flowable.service.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.IntermediateCatchEvent;
import org.flowable.bpmn.model.ThrowEvent;
import org.flowable.bpmn.model.MessageFlow;
import org.flowable.bpmn.model.Pool;
import org.flowable.bpmn.model.ReceiveTask;
import org.flowable.bpmn.model.SendTask;
import org.flowable.bpmn.model.MessageEventDefinition;
import org.flowable.bpmn.model.Event;
import org.flowable.bpmn.model.FieldExtension;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.extension.WorkflowCollaborationOutboxHandler;
import com.ruoyi.flowable.extension.WorkflowExtensionBpmnContract;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Participant/MessageFlow 的部署运行契约校验器。
 * 该组件只把能够映射到 Flowable 实例和消息等待节点的协作图标记为可部署，
 * 不把 Lane、Annotation 或 XML 连线本身当作运行能力。
 */
public final class WorkflowCollaborationValidator
{
    private WorkflowCollaborationValidator() { }

    /**
     * 校验协作图的参与方、消息流和消息定义关系。
     * @param model BpmnModel，已经通过安全 XML 解析的 BPMN 模型
     * @return void，契约不满足时抛出 400 业务异常
     */
    public static void validate(BpmnModel model)
    {
        if (model == null || model.getProcesses() == null)
        {
            return;
        }
        Map<String, String> elementProcess = new HashMap<>();
        model.getProcesses().forEach(process ->
        {
            for (FlowElement element : process.findFlowElementsOfType(FlowElement.class, true))
            {
                elementProcess.put(element.getId(), process.getId());
            }
        });

        Set<String> participantIds = new HashSet<>();
        if (model.getPools() != null)
        {
            for (Pool pool : model.getPools())
            {
                requireText(pool.getId(), "Participant id 不能为空");
                requireText(pool.getProcessRef(), "Participant 必须绑定可执行流程定义");
                if (!participantIds.add(pool.getId()))
                {
                    throw invalid("Participant id 不能重复");
                }
                if (model.getProcessById(pool.getProcessRef()) == null)
                {
                    throw invalid("Participant 绑定的流程定义不存在: " + pool.getProcessRef());
                }
                if (!model.getProcessById(pool.getProcessRef()).isExecutable())
                {
                    throw invalid("Participant 只能绑定可执行流程定义: " + pool.getProcessRef());
                }
            }
        }

        if (model.getMessageFlows() == null)
        {
            return;
        }
        Set<String> names = new HashSet<>();
        for (MessageFlow flow : model.getMessageFlows().values())
        {
            requireText(flow.getId(), "MessageFlow id 不能为空");
            requireText(flow.getName(), "MessageFlow 必须配置消息名称");
            if (!names.add(flow.getName().trim()))
            {
                throw invalid("同一协作图中的 MessageFlow 消息名称必须唯一: " + flow.getName());
            }
            FlowElement source = model.getFlowElement(flow.getSourceRef());
            FlowElement target = model.getFlowElement(flow.getTargetRef());
            if (source == null || target == null)
            {
                throw invalid("MessageFlow 的 sourceRef/targetRef 必须指向真实节点");
            }
            String sourceProcess = elementProcess.get(source.getId());
            String targetProcess = elementProcess.get(target.getId());
            if (sourceProcess == null || targetProcess == null || sourceProcess.equals(targetProcess))
            {
                throw invalid("MessageFlow 必须连接不同 Participant 的流程节点");
            }
            if (!(source instanceof SendTask))
            {
                throw invalid("可执行 MessageFlow 起点必须是绑定可靠 outbox 的 SendTask");
            }
            if (!(target instanceof ReceiveTask || target instanceof IntermediateCatchEvent)
                    || target instanceof Event event && !isMessageEvent(event))
            {
                throw invalid("MessageFlow 终点必须是 ReceiveTask 或消息捕获事件");
            }
            if (flow.getMessageRef() != null && model.getMessage(flow.getMessageRef()) == null)
            {
                throw invalid("MessageFlow 引用的消息定义不存在: " + flow.getMessageRef());
            }
            validateOutboxBinding((SendTask) source, flow.getName().trim(), targetProcess);
        }
    }

    /**
     * 核验 MessageFlow 源 SendTask 使用固定 outbox 扩展，且配置消息名和目标流程与图一致。
     * @param task SendTask，MessageFlow 源活动
     * @param messageName String，MessageFlow 唯一消息名
     * @param targetProcess String，目标 Participant 流程 key
     * @return void，作者配置漂移时拒绝部署
     */
    private static void validateOutboxBinding(SendTask task, String messageName,
            String targetProcess)
    {
        String extensionKey = null;
        String configJson = null;
        if (task.getFieldExtensions() != null)
        {
            for (FieldExtension field : task.getFieldExtensions())
            {
                if (WorkflowExtensionBpmnContract.EXTENSION_KEY_FIELD.equals(field.getFieldName()))
                {
                    extensionKey = field.getStringValue();
                }
                else if (WorkflowExtensionBpmnContract.EXTENSION_CONFIG_FIELD.equals(field.getFieldName()))
                {
                    configJson = field.getStringValue();
                }
            }
        }
        if (!WorkflowCollaborationOutboxHandler.EXTENSION_KEY.equals(extensionKey)
                || configJson == null || configJson.isBlank())
        {
            throw invalid("MessageFlow 源 SendTask 必须选择跨参与方可靠消息处理器");
        }
        try
        {
            JsonNode config = JsonMapper.shared().readTree(configJson);
            if (config == null || !config.isObject()
                    || !messageName.equals(config.path("messageName").asText())
                    || !targetProcess.equals(config.path("targetProcessDefinitionKey").asText()))
            {
                throw invalid("MessageFlow 消息名和目标流程必须与 SendTask outbox 配置一致");
            }
        }
        catch (JacksonException exception)
        {
            throw invalid("MessageFlow SendTask outbox 配置不是合法 JSON");
        }
    }

    /**
     * 检查必填文本。
     * @param value String，待检查文本
     * @param message String，失败提示
     * @return void，空文本时抛出异常
     */
    private static void requireText(String value, String message)
    {
        if (value == null || value.isBlank())
        {
            throw invalid(message);
        }
    }

    /** 判断事件节点是否确实声明了 BPMN MessageEventDefinition。 */
    private static boolean isMessageEvent(Event event)
    {
        return event.getEventDefinitions() != null
                && event.getEventDefinitions().stream().anyMatch(MessageEventDefinition.class::isInstance);
    }

    /**
     * 构造稳定的部署契约异常。
     * @param message String，对设计器可见的业务错误
     * @return ServiceException，HTTP 400
     */
    private static ServiceException invalid(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST)
                .setSubCode("BPMN_COLLABORATION_CONTRACT_INVALID");
    }
}

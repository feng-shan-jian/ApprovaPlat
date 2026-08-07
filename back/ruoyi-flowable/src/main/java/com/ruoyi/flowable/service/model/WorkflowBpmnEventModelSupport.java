package com.ruoyi.flowable.service.model;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ErrorEventDefinition;
import org.flowable.bpmn.model.Escalation;
import org.flowable.bpmn.model.EscalationEventDefinition;
import org.flowable.bpmn.model.EventDefinition;

/**
 * BPMN 错误与升级定义的统一编码解析工具。
 */
public final class WorkflowBpmnEventModelSupport
{
    /** 禁止实例化纯函数工具类。 */
    private WorkflowBpmnEventModelSupport()
    {
    }

    /**
     * 将事件定义中的根元素引用解析为最终业务编码。
     * @param model BpmnModel，包含根 Error/Escalation 定义的流程模型
     * @param definition EventDefinition，错误或升级事件定义
     * @return ResolvedEvent，类型和最终业务编码；其他事件类型返回 null
     */
    public static ResolvedEvent resolve(BpmnModel model, EventDefinition definition)
    {
        if (definition instanceof ErrorEventDefinition errorDefinition)
        {
            String referenceOrCode = trimToNull(errorDefinition.getErrorCode());
            String code = referenceOrCode;
            if (referenceOrCode != null && model != null && model.containsErrorRef(referenceOrCode))
            {
                code = trimToNull(model.getErrors().get(referenceOrCode));
            }
            return new ResolvedEvent("ERROR", code);
        }
        if (definition instanceof EscalationEventDefinition escalationDefinition)
        {
            String referenceOrCode = trimToNull(escalationDefinition.getEscalationCode());
            String code = referenceOrCode;
            if (referenceOrCode != null && model != null && model.containsEscalationRef(referenceOrCode))
            {
                Escalation escalation = model.getEscalation(referenceOrCode);
                code = escalation == null ? null : trimToNull(escalation.getEscalationCode());
            }
            return new ResolvedEvent("ESCALATION", code);
        }
        return null;
    }

    /** @param value String，可空文本；@return String，空白转 null。 */
    private static String trimToNull(String value)
    {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 已解析的 BPMN 业务事件。
     * @param eventType String，ERROR 或 ESCALATION
     * @param eventCode String，最终业务编码
     */
    public record ResolvedEvent(String eventType, String eventCode)
    {
    }
}

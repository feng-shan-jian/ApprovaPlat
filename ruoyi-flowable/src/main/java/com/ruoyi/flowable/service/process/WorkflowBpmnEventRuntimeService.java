package com.ruoyi.flowable.service.process;

import java.util.List;
import org.flowable.bpmn.model.Activity;
import org.flowable.bpmn.model.BoundaryEvent;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.EventDefinition;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.impl.bpmn.helper.EscalationPropagation;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.extension.WorkflowExtensionChecksum;
import com.ruoyi.flowable.service.model.WorkflowBpmnEventModelSupport;

/**
 * 受控 BPMN Error 与 Escalation 运行产生器。
 *
 * 普通 Java 异常不会进入本服务；只有已冻结的显式事件配置才能产生 BPMN 业务事件。
 */
@Service
public class WorkflowBpmnEventRuntimeService
{
    private final RepositoryService repositoryService;
    private final WorkflowBpmnEventAuditService auditService;

    /**
     * 创建受控运行产生器。
     * @param repositoryService RepositoryService，流程定义和 BPMN 模型公共 API
     * @param auditService WorkflowBpmnEventAuditService，独立事务审计和通知服务
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowBpmnEventRuntimeService(RepositoryService repositoryService,
            WorkflowBpmnEventAuditService auditService)
    {
        this.repositoryService = repositoryService;
        this.auditService = auditService;
    }

    /**
     * 解析当前受控节点的精确边界匹配、先落审计和通知，再交给 Flowable 执行标准语义。
     * @param execution DelegateExecution，当前 ServiceTask 执行上下文
     * @param event FrozenEvent，部署时冻结的事件目录和来源配置
     * @return void，错误未匹配时由 BpmnError 使当前引擎命令失败；升级未匹配时按 BPMN 语义继续主路径
     */
    public void raise(DelegateExecution execution, FrozenEvent event)
    {
        if (execution == null || execution.getCurrentFlowElement() == null)
        {
            throw new ServiceException("BPMN 事件运行上下文不完整", HttpStatus.ERROR);
        }
        ProcessDefinition definition = repositoryService.getProcessDefinition(
                execution.getProcessDefinitionId());
        if (definition == null || definition.getDeploymentId() == null
                || definition.getKey() == null)
        {
            throw new ServiceException("BPMN 事件对应的流程定义不存在", HttpStatus.ERROR);
        }
        BpmnModel model = repositoryService.getBpmnModel(execution.getProcessDefinitionId());
        BoundaryMatch match = resolveDirectBoundary(model, execution, event);
        String matchStatus = match == null ? "UNMATCHED" : "CAPTURED";
        String idempotencyKey = WorkflowExtensionChecksum.sha256(
                definition.getDeploymentId(), execution.getProcessInstanceId(), execution.getId(),
                execution.getCurrentActivityId(), event.eventType(), event.eventCode());
        String initiator = scalar(execution.getVariable("initiator"));
        auditService.record(new WorkflowBpmnEventAuditService.RuntimeEvent(
                idempotencyKey, definition.getDeploymentId(), execution.getProcessInstanceId(),
                execution.getProcessDefinitionId(), definition.getKey(), execution.getId(),
                execution.getCurrentActivityId(),
                event.sourceType(), event.eventType(), event.eventCode(), event.eventName(),
                event.notificationPolicy(), matchStatus, match == null ? null : match.eventId(),
                match == null ? null : match.interrupting(), event.messageSummary(), initiator));

        // 结构化变量供人工处理表单、历史查询和后续分支回显，不写入任意异常堆栈。
        execution.setVariable("wfBpmnEventType", event.eventType());
        execution.setVariable("wfBpmnEventCode", event.eventCode());
        execution.setVariable("wfBpmnEventName", event.eventName());
        if (event.messageSummary() != null)
        {
            execution.setVariable("wfBpmnEventMessage", event.messageSummary());
        }
        if ("ERROR".equals(event.eventType()))
        {
            throw new BpmnError(event.eventCode(), event.messageSummary() == null
                    ? event.eventName() : event.messageSummary());
        }
        EscalationPropagation.propagateEscalation(event.eventCode(), event.eventName(), execution);
    }

    /**
     * 只解析附着在当前受控 ServiceTask 上的边界，避免跨作用域猜测捕获范围。
     * @param model BpmnModel，当前部署模型
     * @param execution DelegateExecution，当前执行上下文
     * @param event FrozenEvent，待匹配事件
     * @return BoundaryMatch，唯一精确匹配；未匹配返回 null
     */
    private BoundaryMatch resolveDirectBoundary(BpmnModel model, DelegateExecution execution,
            FrozenEvent event)
    {
        if (!(execution.getCurrentFlowElement() instanceof Activity activity))
        {
            return null;
        }
        List<BoundaryEvent> boundaries = activity.getBoundaryEvents();
        BoundaryMatch matched = null;
        if (boundaries == null)
        {
            return null;
        }
        for (BoundaryEvent boundary : boundaries)
        {
            for (EventDefinition definition : boundary.getEventDefinitions())
            {
                WorkflowBpmnEventModelSupport.ResolvedEvent resolved =
                        WorkflowBpmnEventModelSupport.resolve(model, definition);
                if (resolved != null && event.eventType().equals(resolved.eventType())
                        && event.eventCode().equals(resolved.eventCode()))
                {
                    if (matched != null)
                    {
                        throw new ServiceException("BPMN 事件运行时存在重复边界匹配", HttpStatus.ERROR);
                    }
                    // Flowable 8 的 ErrorEventDefinition 内部标记为 false，但标准 Error 边界始终中断；
                    // Escalation 才使用作者配置的 cancelActivity 区分中断与非中断。
                    boolean interrupting = "ERROR".equals(event.eventType())
                            || boundary.isCancelActivity();
                    matched = new BoundaryMatch(boundary.getId(), interrupting);
                }
            }
        }
        return matched;
    }

    /** @param value Object，流程变量；@return String，安全标量文本或 null。 */
    private String scalar(Object value)
    {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean)
        {
            return value == null ? null : value.toString();
        }
        return null;
    }

    /**
     * 部署快照中的受控事件配置。
     * @param eventType String，ERROR 或 ESCALATION
     * @param eventCode String，稳定编码
     * @param eventName String，冻结名称
     * @param notificationPolicy String，冻结通知策略
     * @param sourceType String，SERVICE_TASK、HTTP、SQL、DMN 或 MANUAL
     * @param messageSummary String，可空脱敏摘要
     */
    public record FrozenEvent(String eventType, String eventCode, String eventName,
            String notificationPolicy, String sourceType, String messageSummary)
    {
    }

    /** @param eventId String，边界标识；@param interrupting boolean，中断语义。 */
    private record BoundaryMatch(String eventId, boolean interrupting)
    {
    }
}

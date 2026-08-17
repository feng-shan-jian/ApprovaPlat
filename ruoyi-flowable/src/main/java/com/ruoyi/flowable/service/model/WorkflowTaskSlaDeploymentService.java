package com.ruoyi.flowable.service.model;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BoundaryEvent;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.Escalation;
import org.flowable.bpmn.model.EscalationEventDefinition;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.FlowableListener;
import org.flowable.bpmn.model.ImplementationType;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.ThrowEvent;
import org.flowable.bpmn.model.TimerEventDefinition;
import org.flowable.bpmn.model.UserTask;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.config.WorkflowSlaBusinessCalendar;
import com.ruoyi.flowable.domain.WfBpmnEventCode;
import com.ruoyi.flowable.domain.WfBusinessCalendar;
import com.ruoyi.flowable.domain.WfDeployTaskSla;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 将 UserTask 的受控 SLA 作者属性编译为真实 Flowable 边界定时器和不可变部署快照。
 */
@Service
public class WorkflowTaskSlaDeploymentService
{
    public static final String TIMER_ELEMENT_PREFIX = "approva_sla_";
    public static final String SOURCE_TASK_DEFINITION_KEY_PROPERTY =
            "approva.sla.sourceTaskDefinitionKey";
    private static final String USER_TASK_LISTENER = "${userTaskListener}";
    private static final Pattern USER_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final int MAX_MINUTES = 525600;
    private static final int MAX_REMINDERS = 100;

    private static final Map<String, String> PROPERTIES = Map.of(
            "enabled", "approva.sla.enabled",
            "calendarKey", "approva.sla.calendarKey",
            "reminderMinutes", "approva.sla.reminderMinutes",
            "reminderRepeatMinutes", "approva.sla.reminderRepeatMinutes",
            "maxReminders", "approva.sla.maxReminders",
            "escalationMinutes", "approva.sla.escalationMinutes",
            "escalationUserId", "approva.sla.escalationUserId",
            "escalationEventCode", "approva.sla.escalationEventCode");
    public static final Set<String> AUTHOR_PROPERTY_NAMES = Set.copyOf(PROPERTIES.values());

    private final WorkflowBusinessCalendarService calendarService;
    private final WorkflowBpmnEventCodeService eventCodeService;
    private final WorkflowIdentityResolver identityResolver;
    private final ObjectMapper objectMapper = JsonMapper.shared();

    /**
     * 创建 SLA 部署编译服务。
     * @param calendarService WorkflowBusinessCalendarService，启用日历锁定服务
     * @param eventCodeService WorkflowBpmnEventCodeService，受控升级编码锁定服务
     * @param identityResolver WorkflowIdentityResolver，正式审批资格身份解析器
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowTaskSlaDeploymentService(WorkflowBusinessCalendarService calendarService,
            WorkflowBpmnEventCodeService eventCodeService,
            WorkflowIdentityResolver identityResolver)
    {
        this.calendarService = calendarService;
        this.eventCodeService = eventCodeService;
        this.identityResolver = identityResolver;
    }

    /**
     * 复制上一阶段执行 BPMN，编译所有启用 SLA 的 UserTask。
     * @param sourceBpmn byte[]，已经完成受控扩展、DMN 和调用活动编译的 BPMN
     * @param actorUserId String，部署操作人正式用户主键
     * @return WorkflowPreparedSlaDeployment，真实定时器资源和待持久化快照
     */
    public WorkflowPreparedSlaDeployment prepare(byte[] sourceBpmn, String actorUserId)
    {
        if (sourceBpmn == null || sourceBpmn.length == 0)
        {
            throw new ServiceException("审批 SLA 编译源不能为空", HttpStatus.BAD_REQUEST);
        }
        BpmnXMLConverter converter = new BpmnXMLConverter();
        BpmnModel model = converter.convertToBpmnModel(
                () -> new ByteArrayInputStream(sourceBpmn), true, true);
        List<WfDeployTaskSla> snapshots = new ArrayList<>();
        List<Escalation> generatedEscalations = new ArrayList<>();
        for (Process process : model.getProcesses())
        {
            if (!process.isExecutable())
            {
                continue;
            }
            // 先复制集合，编译过程中会向同一流程增加边界、服务任务、升级任务和顺序流。
            for (UserTask task : List.copyOf(process.findFlowElementsOfType(UserTask.class, true)))
            {
                Map<String, String> values = readAndStripSlaProperties(task);
                if (!"true".equals(values.get("enabled")))
                {
                    continue;
                }
                SlaConfig config = validateConfig(values);
                WfBusinessCalendar calendar = calendarService.requireEnabled(config.calendarKey());
                WfBpmnEventCode escalationCode = config.escalationEventCode() == null ? null
                        : eventCodeService.requireEnabled("ESCALATION", config.escalationEventCode());
                WfDeployTaskSla snapshot = createSnapshot(process, task, config, calendar,
                        actorUserId);
                snapshots.add(snapshot);
                compileTask(model, process, task, config, escalationCode, generatedEscalations);
            }
        }
        byte[] compiled = converter.convertToXML(model);
        compiled = preserveGeneratedEscalations(compiled, generatedEscalations);
        if (compiled == null || compiled.length == 0)
        {
            throw new ServiceException("审批 SLA 执行资源编译失败", HttpStatus.ERROR);
        }
        return new WorkflowPreparedSlaDeployment(compiled, snapshots);
    }

    /**
     * 读取并从执行副本剥离 SLA 保留属性，防止运行时依赖可变作者配置。
     * @param task UserTask，待编译审批任务
     * @return Map&lt;String,String&gt;，按逻辑字段名索引的作者值
     */
    private Map<String, String> readAndStripSlaProperties(UserTask task)
    {
        Map<String, String> values = new HashMap<>();
        Map<String, List<ExtensionElement>> extensions = task.getExtensionElements();
        List<ExtensionElement> propertyContainers = extensions == null
                ? null : extensions.get("properties");
        if (propertyContainers == null)
        {
            return values;
        }
        for (ExtensionElement container : List.copyOf(propertyContainers))
        {
            List<ExtensionElement> properties = container.getChildElements() == null
                    ? null : container.getChildElements().get("property");
            if (properties == null)
            {
                continue;
            }
            List<ExtensionElement> retained = new ArrayList<>();
            for (ExtensionElement property : properties)
            {
                String propertyName = property.getAttributeValue(null, "name");
                String logicalName = PROPERTIES.entrySet().stream()
                        .filter(entry -> entry.getValue().equals(propertyName))
                        .map(Map.Entry::getKey).findFirst().orElse(null);
                if (logicalName == null)
                {
                    retained.add(property);
                }
                else if (values.put(logicalName,
                        trimToNull(property.getAttributeValue(null, "value"))) != null)
                {
                    throw invalid("审批 SLA 作者属性不能重复");
                }
            }
            if (retained.isEmpty())
            {
                container.getChildElements().remove("property");
            }
            else
            {
                container.getChildElements().put("property", retained);
            }
        }
        propertyContainers.removeIf(container -> container.getChildElements() == null
                || container.getChildElements().isEmpty());
        if (propertyContainers.isEmpty())
        {
            extensions.remove("properties");
        }
        return values;
    }

    /**
     * 交叉校验提醒顺序、升级目标和字段边界。
     * @param values Map&lt;String,String&gt;，作者 SLA 字段
     * @return SlaConfig，可直接编译的规范配置
     */
    private SlaConfig validateConfig(Map<String, String> values)
    {
        if (!values.keySet().containsAll(PROPERTIES.keySet()))
        {
            throw invalid("审批 SLA 作者配置字段不完整");
        }
        String calendarKey = required(values.get("calendarKey"), "审批 SLA 必须选择业务日历");
        int reminderMinutes = parseMinutes(values.get("reminderMinutes"));
        int repeatMinutes = parseMinutes(values.get("reminderRepeatMinutes"));
        int maxReminders = parseInteger(values.get("maxReminders"), 1, MAX_REMINDERS,
                "审批 SLA 最大提醒次数不合法");
        int escalationMinutes = parseMinutes(values.get("escalationMinutes"));
        long lastReminder = reminderMinutes + (long) repeatMinutes * (maxReminders - 1);
        if (escalationMinutes <= lastReminder)
        {
            throw invalid("审批 SLA 升级时间必须晚于最后一次提醒");
        }
        String escalationUserId = trimToNull(values.get("escalationUserId"));
        if (escalationUserId != null && !USER_ID.matcher(escalationUserId).matches())
        {
            throw invalid("审批 SLA 升级办理人不合法");
        }
        if (escalationUserId != null && !identityResolver
                .resolveApprovalEligibleUserIds(Set.of(escalationUserId))
                .equals(Set.of(escalationUserId)))
        {
            // 部署时按正式用户、启停状态和实时审批权限失败关闭，不能依赖可绕过的前端目录。
            throw invalid("审批 SLA 升级办理人不存在、已停用或无审批权限");
        }
        String escalationEventCode = trimToNull(values.get("escalationEventCode"));
        if (escalationUserId == null && escalationEventCode == null)
        {
            throw invalid("审批 SLA 必须配置升级办理人或受控升级事件");
        }
        return new SlaConfig(calendarKey, reminderMinutes, repeatMinutes, maxReminders,
                escalationMinutes, escalationUserId, escalationEventCode);
    }

    /**
     * 创建部署快照并冻结当时的完整日历规则。
     * @param process Process，所属可执行流程
     * @param task UserTask，原审批节点
     * @param config SlaConfig，规范 SLA 配置
     * @param calendar WfBusinessCalendar，锁定的启用日历
     * @param actorUserId String，部署操作人
     * @return WfDeployTaskSla，尚未绑定部署主键的快照
     */
    private WfDeployTaskSla createSnapshot(Process process, UserTask task,
            SlaConfig config, WfBusinessCalendar calendar, String actorUserId)
    {
        WfDeployTaskSla snapshot = new WfDeployTaskSla();
        snapshot.setProcessKey(process.getId());
        snapshot.setTaskDefinitionKey(task.getId());
        snapshot.setCalendarKey(calendar.getCalendarKey());
        snapshot.setCalendarTimezone(calendar.getTimezone());
        snapshot.setWorkingDays(calendar.getWorkingDays());
        snapshot.setWorkStart(calendar.getWorkStart());
        snapshot.setWorkEnd(calendar.getWorkEnd());
        try
        {
            snapshot.setCalendarDaysJson(objectMapper.writeValueAsString(calendar.getDays()));
        }
        catch (JacksonException exception)
        {
            throw new ServiceException("审批 SLA 日历快照序列化失败", HttpStatus.ERROR);
        }
        snapshot.setReminderMinutes(config.reminderMinutes());
        snapshot.setReminderRepeatMinutes(config.repeatMinutes());
        snapshot.setMaxReminders(config.maxReminders());
        snapshot.setEscalationMinutes(config.escalationMinutes());
        snapshot.setEscalationAssignee(config.escalationUserId());
        snapshot.setEscalationEventCode(config.escalationEventCode());
        snapshot.setCreateBy(actorUserId);
        return snapshot;
    }

    /**
     * 为一个审批任务生成多个一次性非中断提醒边界及一个中断升级边界。
     * @param model BpmnModel，执行模型
     * @param process Process，所属可执行流程
     * @param task UserTask，原审批任务
     * @param config SlaConfig，规范配置
     * @param escalationCode WfBpmnEventCode，可空受控升级目录
     * @param generatedEscalations List&lt;Escalation&gt;，需要保留到 XML 根的生成定义
     * @return void，生成元素直接加入执行模型
     */
    private void compileTask(BpmnModel model, Process process, UserTask task,
            SlaConfig config, WfBpmnEventCode escalationCode,
            List<Escalation> generatedEscalations)
    {
        for (int ordinal = 1; ordinal <= config.maxReminders(); ordinal++)
        {
            int dueMinutes = Math.toIntExact(config.reminderMinutes()
                    + (long) config.repeatMinutes() * (ordinal - 1));
            String baseId = generatedId(task.getId(), "reminder_" + ordinal);
            BoundaryEvent boundary = timerBoundary(baseId + "_boundary", task,
                    config.calendarKey(), dueMinutes, false);
            ServiceTask delegate = timerDelegate(baseId + "_delegate", task.getId(),
                    "REMINDER", ordinal, null);
            EndEvent end = new EndEvent();
            end.setId(baseId + "_end");
            addPath(process, boundary, delegate, end);
        }

        String baseId = generatedId(task.getId(), "escalation");
        BoundaryEvent boundary = timerBoundary(baseId + "_boundary", task,
                config.calendarKey(), config.escalationMinutes(), true);
        ServiceTask delegate = timerDelegate(baseId + "_delegate", task.getId(),
                "ESCALATE", 0, config.escalationUserId());
        process.addFlowElement(boundary);
        process.addFlowElement(delegate);
        connect(process, boundary, delegate, baseId + "_flow_1");
        if (config.escalationUserId() != null)
        {
            UserTask escalationTask = new UserTask();
            escalationTask.setId(baseId + "_user_task");
            escalationTask.setName((task.getName() == null ? "审批" : task.getName()) + "超时升级处理");
            escalationTask.setAssignee(config.escalationUserId());
            escalationTask.setTaskListeners(systemTaskListeners());
            appendGeneratedProperty(escalationTask, SOURCE_TASK_DEFINITION_KEY_PROPERTY,
                    task.getId());
            EndEvent end = new EndEvent();
            end.setId(baseId + "_end");
            process.addFlowElement(escalationTask);
            process.addFlowElement(end);
            connect(process, delegate, escalationTask, baseId + "_flow_2");
            connect(process, escalationTask, end, baseId + "_flow_3");
        }
        else
        {
            String escalationId = baseId + "_definition";
            Escalation escalation = new Escalation(escalationId,
                    escalationCode.getEventName(), escalationCode.getEventCode());
            model.addEscalation(escalation);
            generatedEscalations.add(escalation);
            EscalationEventDefinition definition = new EscalationEventDefinition();
            definition.setEscalationCode(escalationId);
            ThrowEvent throwEvent = new ThrowEvent();
            throwEvent.setId(baseId + "_throw");
            throwEvent.setName("超时受控升级");
            throwEvent.addEventDefinition(definition);
            process.addFlowElement(throwEvent);
            connect(process, delegate, throwEvent, baseId + "_flow_2");
        }
    }

    /**
     * 创建一次性 Flowable 边界定时器。
     * @param id String，生成边界标识
     * @param task UserTask，附着审批任务
     * @param calendarKey String，业务日历编码
     * @param minutes int，到期工作分钟
     * @param interrupting boolean，升级为 true，提醒为 false
     * @return BoundaryEvent，已附着真实 TimerEventDefinition 的边界
     */
    private BoundaryEvent timerBoundary(String id, UserTask task, String calendarKey,
            int minutes, boolean interrupting)
    {
        TimerEventDefinition timer = new TimerEventDefinition();
        timer.setTimeDuration(calendarKey + "|" + minutes);
        timer.setCalendarName(WorkflowSlaBusinessCalendar.NAME);
        BoundaryEvent boundary = new BoundaryEvent();
        boundary.setId(id);
        boundary.setAttachedToRef(task);
        boundary.setAttachedToRefId(task.getId());
        boundary.setCancelActivity(interrupting);
        boundary.addEventDefinition(timer);
        task.getBoundaryEvents().add(boundary);
        return boundary;
    }

    /**
     * 创建固定 SLA 定时 delegate，不允许作者提供类名或表达式。
     * @param id String，生成服务任务标识
     * @param taskDefinitionKey String，原审批节点标识
     * @param action String，REMINDER 或 ESCALATE
     * @param ordinal int，提醒序号，升级为零
     * @param escalationRecipient String，可空升级办理人
     * @return ServiceTask，字段已冻结的固定 Spring delegate
     */
    private ServiceTask timerDelegate(String id, String taskDefinitionKey,
            String action, int ordinal, String escalationRecipient)
    {
        ServiceTask delegate = new ServiceTask();
        delegate.setId(id);
        delegate.setName("REMINDER".equals(action) ? "审批超时自动催办" : "审批超时升级");
        delegate.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_EXPRESSION);
        String recipientArgument = escalationRecipient == null
                ? "null" : "'" + escalationRecipient + "'";
        delegate.setImplementation("${workflowSlaTimerDelegate.executeTimer(execution,'"
                + taskDefinitionKey + "','" + action + "'," + ordinal + ","
                + recipientArgument + ")}");
        return delegate;
    }

    /** @return List&lt;FlowableListener&gt;，固定 create、assignment、complete 三监听器。 */
    private List<FlowableListener> systemTaskListeners()
    {
        return List.of(taskListener("create"), taskListener("assignment"), taskListener("complete"));
    }

    /**
     * 为编译器生成节点写入只读运行属性，运行时据此关联原审批 SLA 执行。
     * @param task UserTask，编译器生成的升级任务
     * @param name String，平台保留属性名
     * @param value String，部署时冻结的原节点标识
     * @return void，属性以标准 flowable:properties 结构写入执行 BPMN
     */
    private void appendGeneratedProperty(UserTask task, String name, String value)
    {
        ExtensionElement property = new ExtensionElement();
        property.setName("property");
        property.setNamespace("http://flowable.org/bpmn");
        property.addAttribute(new ExtensionAttribute("name", name));
        property.addAttribute(new ExtensionAttribute("value", value));
        ExtensionElement container = new ExtensionElement();
        container.setName("properties");
        container.setNamespace("http://flowable.org/bpmn");
        container.setChildElements(Map.of("property", List.of(property)));
        task.addExtensionElement(container);
    }

    /** @param event String，固定任务事件；@return FlowableListener，固定 userTaskListener 委托表达式。 */
    private FlowableListener taskListener(String event)
    {
        FlowableListener listener = new FlowableListener();
        listener.setEvent(event);
        listener.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
        listener.setImplementation(USER_TASK_LISTENER);
        return listener;
    }

    /** @param process Process，目标流程；@param boundary BoundaryEvent，边界；@param delegate ServiceTask，动作；@param end EndEvent，结束；@return void。 */
    private void addPath(Process process, BoundaryEvent boundary, ServiceTask delegate, EndEvent end)
    {
        process.addFlowElement(boundary);
        process.addFlowElement(delegate);
        process.addFlowElement(end);
        String base = boundary.getId();
        connect(process, boundary, delegate, base + "_flow_1");
        connect(process, delegate, end, base + "_flow_2");
    }

    /**
     * 添加双向引用完整的顺序流，避免转换器输出孤立连接。
     * @param process Process，目标流程
     * @param source FlowNode，来源节点
     * @param target FlowNode，目标节点
     * @param id String，顺序流标识
     * @return void，顺序流加入流程和两端节点
     */
    private void connect(Process process, FlowNode source, FlowNode target, String id)
    {
        SequenceFlow flow = new SequenceFlow(source.getId(), target.getId());
        flow.setId(id);
        flow.setSourceFlowElement(source);
        flow.setTargetFlowElement(target);
        source.getOutgoingFlows().add(flow);
        target.getIncomingFlows().add(flow);
        process.addFlowElement(flow);
    }

    /** @param taskId String，原节点标识；@param suffix String，动作后缀；@return String，长度受控且稳定的生成标识。 */
    private String generatedId(String taskId, String suffix)
    {
        String safeTaskId = taskId == null ? "task" : taskId.replaceAll("[^A-Za-z0-9_]", "_");
        if (safeTaskId.length() > 80)
        {
            safeTaskId = safeTaskId.substring(0, 80);
        }
        return TIMER_ELEMENT_PREFIX + safeTaskId + "_" + suffix;
    }

    /**
     * 补回 Flowable 8 XML 转换器未稳定写出的 definitions 级生成升级定义。
     * @param converted byte[]，模型转换结果
     * @param escalations List&lt;Escalation&gt;，本次生成定义
     * @return byte[]，可部署且包含根升级定义的 XML
     */
    private byte[] preserveGeneratedEscalations(byte[] converted, List<Escalation> escalations)
    {
        if (escalations.isEmpty())
        {
            return converted;
        }
        String xml = new String(converted, StandardCharsets.UTF_8);
        StringBuilder definitions = new StringBuilder();
        for (Escalation escalation : escalations)
        {
            if (!xml.contains("id=\"" + escapeXml(escalation.getId()) + "\""))
            {
                definitions.append("<escalation id=\"").append(escapeXml(escalation.getId()))
                        .append("\" name=\"").append(escapeXml(escalation.getName()))
                        .append("\" escalationCode=\"")
                        .append(escapeXml(escalation.getEscalationCode()))
                        .append("\"></escalation>");
            }
        }
        if (definitions.isEmpty())
        {
            return converted;
        }
        int processIndex = xml.indexOf("<process ");
        if (processIndex < 0)
        {
            throw new ServiceException("审批 SLA 升级定义插入位置不存在", HttpStatus.ERROR);
        }
        return (xml.substring(0, processIndex) + definitions + xml.substring(processIndex))
                .getBytes(StandardCharsets.UTF_8);
    }

    /** @param value String，可空 XML 文本；@return String，属性安全文本。 */
    private String escapeXml(String value)
    {
        return value == null ? "" : value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    /** @param raw String，分钟文本；@return int，1 至 525600 的分钟。 */
    private int parseMinutes(String raw)
    {
        return parseInteger(raw, 1, MAX_MINUTES, "审批 SLA 提醒或升级分钟不合法");
    }

    /** @param raw String，整数文本；@param min int，最小值；@param max int，最大值；@param message String，错误提示；@return int，范围内整数。 */
    private int parseInteger(String raw, int min, int max, String message)
    {
        try
        {
            int value = Integer.parseInt(raw);
            if (value < min || value > max)
            {
                throw new NumberFormatException();
            }
            return value;
        }
        catch (RuntimeException exception)
        {
            throw invalid(message);
        }
    }

    /** @param value String，必填文本；@param message String，错误提示；@return String，非空文本。 */
    private String required(String value, String message)
    {
        String normalized = trimToNull(value);
        if (normalized == null)
        {
            throw invalid(message);
        }
        return normalized;
    }

    /** @param value String，可空文本；@return String，空白转 null。 */
    private String trimToNull(String value)
    {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** @param message String，稳定提示；@return ServiceException，HTTP 400 作者配置错误。 */
    private ServiceException invalid(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 已规范 SLA 作者配置。
     * @param calendarKey String，业务日历编码
     * @param reminderMinutes int，首次提醒工作分钟
     * @param repeatMinutes int，重复提醒间隔
     * @param maxReminders int，提醒次数
     * @param escalationMinutes int，升级工作分钟
     * @param escalationUserId String，可空升级办理人
     * @param escalationEventCode String，可空受控升级编码
     */
    private record SlaConfig(String calendarKey, int reminderMinutes, int repeatMinutes,
            int maxReminders, int escalationMinutes, String escalationUserId,
            String escalationEventCode) { }
}

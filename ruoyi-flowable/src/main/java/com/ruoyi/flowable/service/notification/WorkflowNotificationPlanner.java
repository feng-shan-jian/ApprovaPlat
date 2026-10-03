package com.ruoyi.flowable.service.notification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCopy;

/**
 * 通知规划器，消费冻结事实，集中解析策略、用户偏好和最终文案，不写通知收件箱或 Outbox。
 */
@Service
public class WorkflowNotificationPlanner
{
    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile(
            "\\{\\{([A-Za-z][A-Za-z0-9]*)}}");
    private static final List<String> CHANNEL_ORDER = List.of("INBOX", "EMAIL", "SMS");
    private static final int MAX_RECIPIENTS_PER_EVENT = 2_000;
    private static final int MAX_TITLE_LENGTH = 160;
    private static final int MAX_CONTENT_LENGTH = 700;

    private final JdbcTemplate jdbcTemplate;

    /** 创建只读取通知策略和用户偏好的规划器。 */
    public WorkflowNotificationPlanner(JdbcTemplate jdbcTemplate)
    {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 为单个审批业务事件生成不可变通知计划。
     * @param request NotificationRequest，已经冻结流程、任务和来源键的规划请求
     * @return NotificationPlan，完成接收人、偏好和文案解析的写入计划
     */
    public NotificationPlan plan(NotificationRequest request)
    {
        return plan(List.of(request));
    }

    /**
     * 为同一业务批次生成一个通知计划，并只执行一次用户状态和偏好查询。
     * @param requests Collection&lt;NotificationRequest&gt;，同一事务内的通知规划请求
     * @return NotificationPlan，按请求和接收人稳定排序的不可变计划
     */
    public NotificationPlan plan(Collection<NotificationRequest> requests)
    {
        return plan(selectPolicies(requests));
    }

    /** 校验整个批次并选择策略；相同 PolicyKey 只读取一次，包括未命中的策略。 */
    Map<NotificationRequest, Policy> selectPolicies(Collection<NotificationRequest> requests)
    {
        if (requests == null)
            throw new ServiceException("通知规划请求不能为空", HttpStatus.ERROR);
        LinkedHashMap<PolicyKey, Policy> policies = new LinkedHashMap<>();
        LinkedHashMap<NotificationRequest, Policy> selected = new LinkedHashMap<>();
        for (NotificationRequest original : requests)
        {
            NotificationRequest request = validateRequest(original);
            PolicyKey key = new PolicyKey(request.eventType, request.processDefinitionKey, request.taskDefinitionKey);
            if (!policies.containsKey(key)) policies.put(key, loadPolicy(key));
            Policy policy = policies.get(key);
            if (policy != null) selected.put(request, policy);
        }
        return Collections.unmodifiableMap(selected);
    }

    /** 消费本批已选策略及入口冻结的事实，期间不重新查询策略。 */
    NotificationPlan plan(Map<NotificationRequest, Policy> selected)
    {
        List<PlannedEvent> events = new ArrayList<>();
        LinkedHashSet<String> allRecipients = new LinkedHashSet<>();
        for (Map.Entry<NotificationRequest, Policy> entry : selected.entrySet())
        {
            NotificationRequest request = entry.getKey();
            Policy policy = entry.getValue();
            Set<String> recipients = resolveRecipients(policy, request);
            if (recipients.isEmpty()) continue;
            Map<String, String> variables = Map.of(
                    "processName", safe(request.processName),
                    "processDefinitionKey", request.processDefinitionKey,
                    "processInstanceId", request.processInstanceId,
                    "taskName", safe(request.taskName),
                    "taskDefinitionKey", safe(request.taskDefinitionKey),
                    "eventType", request.eventType);
            String title = truncate(render(policy.titleTemplate(), variables), MAX_TITLE_LENGTH);
            String content = render(policy.contentTemplate(), variables);
            if (StringUtils.hasText(request.contentSuffix))
            {
                // 催办原因先与完整模板结果拼接，随后统一执行一次最终字段截断。
                content += request.contentSuffix;
            }
            content = truncate(content, MAX_CONTENT_LENGTH);
            events.add(new PlannedEvent(request, policy, recipients, title, content));
            allRecipients.addAll(recipients);
        }
        if (events.isEmpty()) return NotificationPlan.empty();

        Map<String, Preference> preferences = loadPreferences(allRecipients);
        List<NotificationPlan.Notification> notifications = new ArrayList<>();
        for (PlannedEvent event : events)
        {
            NotificationRequest request = event.request();
            String actorUserId = optional(request.actorUserId, 64);
            for (String recipient : event.recipients())
            {
                Preference preference = preferences.get(recipient);
                if (preference == null) continue;
                Set<String> channels = effectiveChannels(event.policy(), preference);
                if (channels.isEmpty()) continue;
                notifications.add(new NotificationPlan.Notification("APPROVAL",
                        request.sourceId, request.eventType, recipient,
                        request.processDefinitionKey, request.processInstanceId,
                        request.taskId, request.taskDefinitionKey,
                        actorUserId, event.title(), event.content(),
                        event.policy().smsTemplateId(), request.routePath, channels,
                        event.policy().maxAttempts()));
            }
        }
        return new NotificationPlan(notifications);
    }

    /**
     * 直接消费调用方已冻结并写入的抄送事实，生成不依赖 copy_id 的自然幂等通知计划。
     * @param copies Collection&lt;WfCopy&gt;，当前事务写入或幂等命中的抄送事实
     * @param definitions Map&lt;String,ProcessDefinition&gt;，按 processId 一次查询得到的流程定义
     * @return NotificationPlan，按 COPY:{copyEventId}:{userId} 生成的批量计划
     */
    public NotificationPlan planCopies(Collection<WfCopy> copies,
            Map<String, ProcessDefinition> definitions)
    {
        if (copies == null || definitions == null)
        {
            throw new ServiceException("抄送通知事实不能为空", HttpStatus.ERROR);
        }
        LinkedHashMap<CopyIdentity, NotificationRequest> requests = new LinkedHashMap<>();
        for (WfCopy copy : copies)
        {
            NotificationRequest request = copyRequest(copy, definitions);
            CopyIdentity identity = new CopyIdentity(copy.getCopyEventId().trim(),
                    String.valueOf(copy.getUserId()));
            NotificationRequest previous = requests.putIfAbsent(identity, request);
            if (previous != null && !previous.hasSameContent(request))
            {
                throw new ServiceException("抄送通知事实身份冲突", HttpStatus.ERROR);
            }
        }
        return plan(requests.values());
    }

    /**
     * 校验抄送冻结字段并构造 COPY_CREATED 规划请求。
     * @param copy WfCopy，调用方当前事务内的抄送事实
     * @param definitions Map&lt;String,ProcessDefinition&gt;，已按定义主键去重查询的定义集合
     * @return NotificationRequest，可直接参与批量策略与偏好规划的请求
     */
    private NotificationRequest copyRequest(WfCopy copy,
            Map<String, ProcessDefinition> definitions)
    {
        if (copy == null || copy.getUserId() == null || copy.getUserId() <= 0)
        {
            throw new ServiceException("抄送通知事实身份不完整", HttpStatus.ERROR);
        }
        String copyEventId = normalized(copy.getCopyEventId(), 128, "抄送事件幂等键不合法");
        String processDefinitionId = normalized(copy.getProcessId(), 64,
                "抄送流程定义主键不合法");
        ProcessDefinition definition = definitions.get(processDefinitionId);
        if (definition == null)
        {
            throw new ServiceException("抄送流程定义不存在", HttpStatus.ERROR);
        }
        return NotificationRequest.copyCreated(copy, definition, copyEventId);
    }

    /**
     * 选择 NODE、PROCESS、DEFAULT 中优先级最高的启用策略。
     * @param key PolicyKey，事件、流程 key 和节点 key
     * @return Policy，最高优先级策略；没有启用策略时为 null
     */
    private Policy loadPolicy(PolicyKey key)
    {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select policy_id as policyId,scope_type as scopeType," +
                "recipient_rules as recipientRules,channels,sms_template_id as smsTemplateId," +
                "title_template as titleTemplate,content_template as contentTemplate," +
                "max_attempts as maxAttempts from wf_notification_policy " +
                "where event_type=? and status='ENABLED' and (" +
                "(scope_type='NODE' and process_definition_key=? and task_definition_key=?) or " +
                "(scope_type='PROCESS' and process_definition_key=?) or scope_type='DEFAULT')",
                key.eventType(), key.processDefinitionKey(), key.taskDefinitionKey(),
                key.processDefinitionKey());
        return rows.stream().map(this::policy).min(Comparator
                .comparingInt((Policy policy) -> scopeRank(policy.scopeType()))
                .thenComparingLong(Policy::policyId)).orElse(null);
    }

    /**
     * 将数据库策略投影转换为规划器内部不可变事实。
     * @param row Map&lt;String,Object&gt;，策略查询结果
     * @return Policy，包含固定规则、通道和模板的策略事实
     */
    private Policy policy(Map<String, Object> row)
    {
        Number policyId = (Number) row.get("policyId");
        Number maxAttempts = (Number) row.get("maxAttempts");
        if (policyId == null || maxAttempts == null)
        {
            throw new ServiceException("通知策略数据不完整", HttpStatus.ERROR);
        }
        return new Policy(policyId.longValue(), String.valueOf(row.get("scopeType")),
                csv(String.valueOf(row.get("recipientRules"))),
                csv(String.valueOf(row.get("channels"))),
                row.get("smsTemplateId") == null ? null : String.valueOf(row.get("smsTemplateId")),
                String.valueOf(row.get("titleTemplate")),
                String.valueOf(row.get("contentTemplate")), maxAttempts.intValue());
    }

    /**
     * 将策略作用域转换为稳定优先级。
     * @param scopeType String，NODE、PROCESS 或 DEFAULT
     * @return int，数值越小优先级越高
     */
    private int scopeRank(String scopeType)
    {
        return switch (scopeType)
        {
            case "NODE" -> 0;
            case "PROCESS" -> 1;
            case "DEFAULT" -> 2;
            default -> throw new ServiceException("通知策略作用域不合法", HttpStatus.ERROR);
        };
    }

    /**
     * 按策略规则合并任务接收人、发起人和操作者并执行稳定去重。
     * @param policy Policy，当前事件适用策略
     * @param request NotificationRequest，流程、任务和身份上下文
     * @return Set&lt;String&gt;，规范化且不超过单事件上限的候选用户主键
     */
    private Set<String> resolveRecipients(Policy policy, NotificationRequest request)
    {
        LinkedHashSet<String> recipients = new LinkedHashSet<>();
        for (String rule : policy.recipientRules())
        {
            if ("TASK_RECIPIENT".equals(rule))
            {
                recipients.addAll(canonicalUserIds(request.taskRecipientUserIds));
            }
            if ("INITIATOR".equals(rule))
            {
                String initiator = request.initiatorUserId;
                if (StringUtils.hasText(initiator)) recipients.add(initiator);
            }
            if ("ACTOR".equals(rule))
            {
                String actor = request.actorUserId;
                if (StringUtils.hasText(actor)) recipients.add(actor);
            }
        }
        Set<String> canonical = canonicalUserIds(recipients);
        if (canonical.size() > MAX_RECIPIENTS_PER_EVENT)
        {
            throw new ServiceException("通知接收人数超过单事件上限", HttpStatus.CONFLICT);
        }
        return canonical;
    }

    /**
     * 一次读取本批次全部用户的有效状态和三个通道偏好。
     * @param recipientUserIds Collection&lt;String&gt;，规范化接收用户主键
     * @return Map&lt;String,Preference&gt;，仅包含有效用户及其正式默认偏好
     */
    private Map<String, Preference> loadPreferences(Collection<String> recipientUserIds)
    {
        if (recipientUserIds.isEmpty()) return Map.of();
        String placeholders = String.join(",",
                recipientUserIds.stream().map(ignored -> "?").toList());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select cast(u.user_id as char) as userId," +
                "coalesce(p.inbox_enabled,1) as inboxEnabled," +
                "coalesce(p.email_enabled,1) as emailEnabled," +
                "coalesce(p.sms_enabled,0) as smsEnabled from sys_user u " +
                "left join wf_notification_preference p on p.user_id=u.user_id " +
                "where u.user_id in (" + placeholders + ") " +
                "and u.status='0' and u.del_flag='0'", recipientUserIds.toArray());
        LinkedHashMap<String, Preference> preferences = new LinkedHashMap<>();
        for (Map<String, Object> row : rows)
        {
            String userId = String.valueOf(row.get("userId"));
            preferences.put(userId, new Preference(enabled(row.get("inboxEnabled")),
                    enabled(row.get("emailEnabled")), enabled(row.get("smsEnabled"))));
        }
        return Map.copyOf(preferences);
    }

    /**
     * 按用户偏好过滤策略通道，保持 INBOX、EMAIL、SMS 固定顺序。
     * @param policy Policy，策略声明通道
     * @param preference Preference，用户正式偏好
     * @return Set&lt;String&gt;，该用户实际可登记通道
     */
    private Set<String> effectiveChannels(Policy policy, Preference preference)
    {
        LinkedHashSet<String> channels = new LinkedHashSet<>();
        for (String channel : CHANNEL_ORDER)
        {
            if (!policy.channels().contains(channel)) continue;
            if ("INBOX".equals(channel) && preference.inboxEnabled()) channels.add(channel);
            if ("EMAIL".equals(channel) && preference.emailEnabled()) channels.add(channel);
            if ("SMS".equals(channel) && preference.smsEnabled()) channels.add(channel);
        }
        return immutableLinkedSet(channels);
    }

    /**
     * 解析数据库布尔数值。
     * @param value Object，MySQL TINYINT 查询结果
     * @return boolean，数值为 1 时启用
     */
    private boolean enabled(Object value)
    {
        return value instanceof Number number && number.intValue() == 1;
    }

    /**
     * 校验并冻结单个规划请求的数据库边界字段。
     * @param request NotificationRequest，调用方构造的上下文
     * @return NotificationRequest，可安全参与策略查询和计划生成的请求
     */
    private NotificationRequest validateRequest(NotificationRequest request)
    {
        if (request == null)
        {
            throw new ServiceException("通知规划请求不能为空", HttpStatus.ERROR);
        }
        String eventType = upper(request.eventType);
        if (!WorkflowNotificationConstants.EVENT_TYPES.contains(eventType))
        {
            throw invalid("通知事件类型不受支持");
        }
        return new NotificationRequest(eventType, WorkflowNotificationSourceId.normalize(request.sourceId),
                normalized(request.processDefinitionKey, 255, "流程定义标识不合法"),
                optional(request.processName, 255),
                normalized(request.processInstanceId, 64, "流程实例主键不合法"),
                optional(request.taskId, 64), optional(request.taskDefinitionKey, 255),
                optional(request.taskName, 255),
                request.taskRecipientUserIds == null ? Set.of()
                        : immutableLinkedSet(request.taskRecipientUserIds),
                optional(request.actorUserId, 64), optional(request.initiatorUserId, 64),
                normalized(request.routePath, 500, "通知业务路由不合法"),
                request.contentSuffix);
    }

    /**
     * 将用户集合转换为规范正整数主键并稳定去重。
     * @param values Collection&lt;String&gt;，候选用户主键
     * @return Set&lt;String&gt;，保持输入顺序的规范用户主键
     */
    private Set<String> canonicalUserIds(Collection<String> values)
    {
        if (values == null) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values)
        {
            result.add(canonicalUserId(value));
        }
        return immutableLinkedSet(result);
    }

    /**
     * 校验单个用户主键为无前导零的正整数。
     * @param value String，候选用户主键
     * @return String，规范数字用户主键
     */
    private String canonicalUserId(String value)
    {
        String normalized = normalized(value, 19, "通知接收用户主键不合法");
        if (!normalized.matches("[1-9][0-9]{0,18}"))
        {
            throw invalid("通知接收用户主键不合法");
        }
        return normalized;
    }

    /**
     * 渲染白名单模板，截断由调用方在完成正文拼接后统一执行。
     * @param template String，已由策略维护服务校验的模板
     * @param variables Map&lt;String,String&gt;，固定模板变量值
     * @return String，尚未执行字段截断的最终渲染文本
     */
    private String render(String template, Map<String, String> variables)
    {
        Matcher matcher = TEMPLATE_VARIABLE.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find())
        {
            matcher.appendReplacement(rendered,
                    Matcher.quoteReplacement(variables.get(matcher.group(1))));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    /**
     * 按 Unicode code point 截断文本，避免拆断代理对。
     * @param value String，待截断文本
     * @param maxCodePoints int，最大字符数
     * @return String，不超过字段上限的文本
     */
    private String truncate(String value, int maxCodePoints)
    {
        int codePoints = value.codePointCount(0, value.length());
        return codePoints <= maxCodePoints ? value
                : value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    /**
     * 拆分逗号分隔的固定配置并去除空白。
     * @param value String，可空 CSV
     * @return Set&lt;String&gt;，保持配置顺序的非空条目
     */
    private Set<String> csv(String value)
    {
        if (!StringUtils.hasText(value)) return Set.of();
        return immutableLinkedSet(Arrays.stream(value.split(","))
                .map(String::trim).filter(item -> !item.isEmpty()).toList());
    }

    /**
     * 复制为保持插入顺序的不可变集合，保证批量规划和写入顺序可重复。
     * @param values Collection&lt;String&gt;，待冻结的有序值
     * @return Set&lt;String&gt;，保持首次出现顺序的不可变集合
     */
    private Set<String> immutableLinkedSet(Collection<String> values)
    {
        if (values == null || values.isEmpty()) return Set.of();
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    /**
     * 规范化必填文本并拒绝控制字符和超长值。
     * @param value String，待校验文本
     * @param max int，最大 Java 字符长度
     * @param message String，稳定错误提示
     * @return String，去除首尾空白的合法文本
     */
    private static String normalized(String value, int max, String message)
    {
        if (!StringUtils.hasText(value)) throw invalid(message);
        String normalized = value.trim();
        if (normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl))
            throw invalid(message);
        return normalized;
    }

    /**
     * 规范化可选文本。
     * @param value String，可空文本
     * @param max int，最大长度
     * @return String，规范值；无内容时为 null
     */
    private static String optional(String value, int max)
    {
        return StringUtils.hasText(value) ? normalized(value, max, "通知上下文字段不合法") : null;
    }

    /**
     * 使用固定 Locale 规范化枚举。
     * @param value String，可空枚举文本
     * @return String，去空白的大写文本
     */
    private String upper(String value)
    {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 将可空文本转换为模板可用空串。
     * @param value String，可空文本
     * @return String，原值或空串
     */
    private String safe(String value)
    {
        return value == null ? "" : value;
    }

    /**
     * 构造 HTTP 400 参数异常。
     * @param message String，稳定错误提示
     * @return ServiceException，参数异常
     */
    private static ServiceException invalid(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * Service 交给 Planner 的单一不可变规划请求。
     *
     * <p>构造器保持私有，四个有名工厂分别固化任务事件、流程结果、人工催办和
     * 抄送创建的合法字段组合，避免调用方依赖参数顺序或身份解析标志。</p>
     */
    public static final class NotificationRequest
    {
        /** 策略匹配使用的稳定通知事件类型。 */
        private final String eventType;
        /** Inbox/Outbox 幂等约束使用的稳定业务来源键。 */
        private final String sourceId;
        /** 策略作用域匹配使用的流程定义 key。 */
        private final String processDefinitionKey;
        /** 模板渲染使用的流程名称快照。 */
        private final String processName;
        /** 通知归属的流程实例主键。 */
        private final String processInstanceId;
        /** 可空任务主键。 */
        private final String taskId;
        /** 可空 BPMN 任务节点 key。 */
        private final String taskDefinitionKey;
        /** 可空任务名称快照。 */
        private final String taskName;
        /** 由业务入口冻结的任务接收人。 */
        private final Set<String> taskRecipientUserIds;
        /** 可空显式操作者；冻结身份事件不得回退到认证上下文。 */
        private final String actorUserId;
        /** 可空显式发起人；冻结身份事件不得回退到流程查询。 */
        private final String initiatorUserId;
        /** 用户打开业务详情时使用的站内相对路由。 */
        private final String routePath;
        /** 可空最终正文后缀，仅人工催办携带已校验原因。 */
        private final String contentSuffix;

        /**
         * 构造只携带值的不可变规划请求；仅允许有名工厂和本批事实冻结流程调用。
         * 构造结果仅在当前规划协议内部使用
         */
        private NotificationRequest(String eventType, String sourceId,
                String processDefinitionKey, String processName, String processInstanceId,
                String taskId, String taskDefinitionKey, String taskName,
                Set<String> taskRecipientUserIds, String actorUserId,
                String initiatorUserId, String routePath,
                String contentSuffix)
        {
            this.eventType = eventType;
            this.sourceId = sourceId;
            this.processDefinitionKey = processDefinitionKey;
            this.processName = processName;
            this.processInstanceId = processInstanceId;
            this.taskId = taskId;
            this.taskDefinitionKey = taskDefinitionKey;
            this.taskName = taskName;
            this.taskRecipientUserIds = taskRecipientUserIds == null ? null
                    : Collections.unmodifiableSet(new LinkedHashSet<>(taskRecipientUserIds));
            this.actorUserId = actorUserId;
            this.initiatorUserId = initiatorUserId;
            this.routePath = routePath;
            this.contentSuffix = contentSuffix;
        }

        /**
         * 构造任务到达、完成、受控退回/重提或稳定归属动作的规划请求。
         * @param eventType String，受支持的任务通知事件类型
         * @param sourceId String，任务事实及 revision 派生的稳定来源键
         * @param definition ProcessDefinition，已确认存在的流程定义事实
         * @param processInstanceId String，任务所属流程实例主键
         * @param taskId String，任务主键
         * @param taskDefinitionKey String，BPMN 节点 key
         * @param taskName String，任务名称快照
         * @param taskRecipientUserIds Set&lt;String&gt;，入口冻结的接收人
         * @param actorUserId String，冻结的审计操作者
         * @param initiatorUserId String，按策略需要冻结的发起人
         * @param routePath String，待办业务详情相对路由
         * @return NotificationRequest，仅携带调用方冻结值的请求
         */
        public static NotificationRequest taskEvent(String eventType, String sourceId,
                ProcessDefinition definition, String processInstanceId, String taskId,
                String taskDefinitionKey, String taskName,
                Set<String> taskRecipientUserIds, String actorUserId, String initiatorUserId, String routePath)
        {
            return new NotificationRequest(eventType, sourceId, definition.getKey(),
                    definition.getName(), processInstanceId, taskId, taskDefinitionKey,
                    taskName, taskRecipientUserIds, actorUserId, initiatorUserId, routePath, null);
        }

        /**
         * 构造流程自然完成、取消、拒绝或终止的结果通知请求。
         * @param eventType String，受支持的流程结果事件类型
         * @param sourceId String，流程实例和结果事件派生的稳定来源键
         * @param definition ProcessDefinition，已确认存在的流程定义事实
         * @param processInstanceId String，已经结束或即将结束的流程实例主键
         * @param actorUserId String，冻结的审计操作者
         * @param initiatorUserId String，按策略需要冻结的发起人
         * @param routePath String，已办业务详情相对路由
         * @return NotificationRequest，仅携带调用方冻结值的请求
         */
        public static NotificationRequest processResult(String eventType, String sourceId,
                ProcessDefinition definition, String processInstanceId, String actorUserId, String initiatorUserId, String routePath)
        {
            return new NotificationRequest(eventType, sourceId, definition.getKey(),
                    definition.getName(), processInstanceId, null, null, null,
                    Set.of(), actorUserId, initiatorUserId, routePath, null);
        }

        /**
         * 构造权限、任务状态和接收人均已由人工催办服务冻结的通知请求。
         * @param sourceId String，人工催办服务生成并校验的稳定来源键
         * @param definition ProcessDefinition，已确认存在的流程定义事实
         * @param registration WorkflowManualUrgeRegistration，已锁定的催办业务事实
         * @param routePath String，催办任务详情相对路由
         * @param contentSuffix String，包含已校验催办原因的正文后缀
         * @return NotificationRequest，只使用 registration 显式身份的请求
         */
        public static NotificationRequest manualUrge(String sourceId,
                ProcessDefinition definition, WorkflowManualUrgeRegistration registration,
                String routePath, String contentSuffix)
        {
            return new NotificationRequest("MANUAL_URGE", sourceId, definition.getKey(),
                    definition.getName(), registration.processInstanceId(),
                    registration.taskId(), registration.taskDefinitionKey(),
                    registration.taskName(), registration.recipientUserIds(),
                    registration.actorUserId(), registration.startUserId(),
                    routePath, contentSuffix);
        }

        /**
         * 构造直接消费已持久化 WfCopy 事实的抄送创建通知请求。
         * @param copy WfCopy，当前事务内已经冻结的有效抄送事实
         * @param definition ProcessDefinition，抄送事实引用的流程定义
         * @param copyEventId String，已校验的抄送业务事件幂等键
         * @return NotificationRequest，只使用抄送创建人且不解析流程发起人的请求
         */
        public static NotificationRequest copyCreated(WfCopy copy,
                ProcessDefinition definition, String copyEventId)
        {
            String processInstanceId = normalized(copy.getInstanceId(), 64,
                    "抄送流程实例主键不合法");
            String taskId = optional(copy.getTaskId(), 64);
            String routePath = "/workflow/process-detail/" + processInstanceId + "?source=copy"
                    + (taskId == null ? "" : "&taskId=" + taskId);
            return new NotificationRequest("COPY_CREATED",
                    WorkflowNotificationSourceId.normalize("COPY:" + copyEventId + ":" + copy.getUserId()),
                    normalized(definition.getKey(), 255, "抄送流程定义标识不合法"),
                    normalized(copy.getProcessName(), 255, "抄送流程名称不合法"),
                    processInstanceId, taskId, null,
                    normalized(copy.getTitle(), 255, "抄送标题不合法"),
                    Set.of(String.valueOf(copy.getUserId())), optional(copy.getCreateBy(), 64),
                    null, routePath, null);
        }

        String processInstanceId() { return processInstanceId; }
        Set<String> taskRecipientUserIds() { return taskRecipientUserIds; }

        /** 保留已校验事件字段，仅补齐选中策略需要的事实和独立审计操作者。 */
        NotificationRequest withFacts(Set<String> recipients, String actor, String initiator)
        {
            return new NotificationRequest(eventType, sourceId, processDefinitionKey, processName,
                    processInstanceId, taskId, taskDefinitionKey, taskName,
                    recipients, actor, initiator, routePath, contentSuffix);
        }

        /**
         * 比较同一抄送自然身份下的完整规划事实，拒绝幂等键对应不同内容。
         * @param other NotificationRequest，待比较的另一个规划请求
         * @return boolean，全部不可变字段语义一致时为 true
         */
        private boolean hasSameContent(NotificationRequest other)
        {
            return other != null && Objects.equals(eventType, other.eventType)
                    && Objects.equals(sourceId, other.sourceId)
                    && Objects.equals(processDefinitionKey, other.processDefinitionKey)
                    && Objects.equals(processName, other.processName)
                    && Objects.equals(processInstanceId, other.processInstanceId)
                    && Objects.equals(taskId, other.taskId)
                    && Objects.equals(taskDefinitionKey, other.taskDefinitionKey)
                    && Objects.equals(taskName, other.taskName)
                    && Objects.equals(taskRecipientUserIds, other.taskRecipientUserIds)
                    && Objects.equals(actorUserId, other.actorUserId)
                    && Objects.equals(initiatorUserId, other.initiatorUserId)
                    && Objects.equals(routePath, other.routePath)
                    && Objects.equals(contentSuffix, other.contentSuffix);
        }
    }

    private record PolicyKey(String eventType, String processDefinitionKey,
            String taskDefinitionKey) { }
    record Policy(long policyId, String scopeType, Set<String> recipientRules,
            Set<String> channels, String smsTemplateId, String titleTemplate,
            String contentTemplate, int maxAttempts) { }
    private record Preference(boolean inboxEnabled, boolean emailEnabled,
            boolean smsEnabled) { }
    private record PlannedEvent(NotificationRequest request, Policy policy,
            Set<String> recipients, String title, String content) { }
    private record CopyIdentity(String copyEventId, String recipientUserId) { }
}

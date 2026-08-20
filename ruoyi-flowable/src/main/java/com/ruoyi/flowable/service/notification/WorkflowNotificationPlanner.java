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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCopy;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;

/**
 * 通知规划器，集中解析策略、身份、用户偏好和最终文案，不写通知收件箱或 Outbox。
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
    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final TaskService taskService;
    private final WorkflowIdentityResolver identityResolver;

    /**
     * 创建通知规划器。
     * @param jdbcTemplate JdbcTemplate，通知策略、用户状态和偏好批量查询入口
     * @param runtimeService RuntimeService，运行实例发起人查询入口
     * @param historyService HistoryService，历史实例发起人查询入口
     * @param taskService TaskService，候选用户和候选组查询入口
     * @param identityResolver WorkflowIdentityResolver，正式身份目录解析入口
     * @return void，构造后由 Spring 管理
     */
    public WorkflowNotificationPlanner(JdbcTemplate jdbcTemplate,
            RuntimeService runtimeService, HistoryService historyService,
            TaskService taskService, WorkflowIdentityResolver identityResolver)
    {
        this.jdbcTemplate = jdbcTemplate;
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.taskService = taskService;
        this.identityResolver = identityResolver;
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
        if (requests == null)
        {
            throw new ServiceException("通知规划请求不能为空", HttpStatus.ERROR);
        }
        if (requests.isEmpty()) return NotificationPlan.empty();

        LinkedHashMap<PolicyKey, Policy> policies = new LinkedHashMap<>();
        List<PlannedEvent> events = new ArrayList<>();
        LinkedHashSet<String> allRecipients = new LinkedHashSet<>();
        for (NotificationRequest original : requests)
        {
            NotificationRequest request = validateRequest(original);
            PolicyKey policyKey = new PolicyKey(request.eventType(),
                    request.processDefinitionKey(), request.taskDefinitionKey());
            Policy policy = policies.computeIfAbsent(policyKey, this::loadPolicy);
            if (policy == null) continue;

            Set<String> recipients = resolveRecipients(policy, request);
            if (recipients.isEmpty()) continue;
            Map<String, String> variables = Map.of(
                    "processName", safe(request.processName()),
                    "processDefinitionKey", request.processDefinitionKey(),
                    "processInstanceId", request.processInstanceId(),
                    "taskName", safe(request.taskName()),
                    "taskDefinitionKey", safe(request.taskDefinitionKey()),
                    "eventType", request.eventType());
            String title = truncate(render(policy.titleTemplate(), variables), MAX_TITLE_LENGTH);
            String content = render(policy.contentTemplate(), variables);
            if (StringUtils.hasText(request.contentSuffix()))
            {
                // 催办原因先与完整模板结果拼接，随后统一执行一次最终字段截断。
                content += request.contentSuffix();
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
            String actorUserId = resolvedActor(request);
            for (String recipient : event.recipients())
            {
                Preference preference = preferences.get(recipient);
                if (preference == null) continue;
                Set<String> channels = effectiveChannels(event.policy(), preference);
                if (channels.isEmpty()) continue;
                notifications.add(new NotificationPlan.Notification("APPROVAL",
                        request.sourceId(), request.eventType(), recipient,
                        request.processDefinitionKey(), request.processInstanceId(),
                        request.taskId(), request.taskDefinitionKey(),
                        actorUserId, event.title(), event.content(),
                        event.policy().smsTemplateId(), request.routePath(), channels,
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
            if (previous != null && !previous.equals(request))
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
        String processInstanceId = normalized(copy.getInstanceId(), 64,
                "抄送流程实例主键不合法");
        String taskId = optional(copy.getTaskId(), 64);
        String route = "/workflow/process-detail/" + processInstanceId + "?source=copy"
                + (taskId == null ? "" : "&taskId=" + taskId);
        return new NotificationRequest("COPY_CREATED",
                normalizedSourceId("COPY:" + copyEventId + ":" + copy.getUserId()),
                normalized(definition.getKey(), 255, "抄送流程定义标识不合法"),
                normalized(copy.getProcessName(), 255, "抄送流程名称不合法"),
                processInstanceId, taskId, null,
                normalized(copy.getTitle(), 255, "抄送标题不合法"), null,
                Set.of(String.valueOf(copy.getUserId())), optional(copy.getCreateBy(), 64),
                false, null, false, route, null);
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
                Set<String> taskRecipients = request.task() == null
                        ? canonicalUserIds(request.taskRecipientUserIds())
                        : resolveTaskRecipients(request.task());
                recipients.addAll(taskRecipients);
            }
            if ("INITIATOR".equals(rule))
            {
                String initiator = StringUtils.hasText(request.initiatorUserId())
                        ? request.initiatorUserId()
                        : (request.resolveInitiatorFromProcess()
                                ? processInitiator(request.processInstanceId()) : null);
                if (StringUtils.hasText(initiator)) recipients.add(initiator);
            }
            if ("ACTOR".equals(rule))
            {
                String actor = StringUtils.hasText(request.actorUserId())
                        ? request.actorUserId()
                        : (request.resolveActorFromAuthentication()
                                ? Authentication.getAuthenticatedUserId() : null);
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
     * 解析任务办理人或候选用户、候选组，并保留审批认领权限约束。
     * @param task Task，当前真实 Flowable 任务
     * @return Set&lt;String&gt;，有效办理人或具备认领权限的候选用户
     */
    private Set<String> resolveTaskRecipients(Task task)
    {
        if (StringUtils.hasText(task.getAssignee()))
        {
            return identityResolver.resolveActiveUserIds(List.of(task.getAssignee()), List.of());
        }
        List<IdentityLink> links = taskService.getIdentityLinksForTask(task.getId());
        LinkedHashSet<String> users = new LinkedHashSet<>();
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        if (links != null)
        {
            for (IdentityLink link : links)
            {
                if (link == null || !IdentityLinkType.CANDIDATE.equals(link.getType())) continue;
                if (StringUtils.hasText(link.getUserId())) users.add(link.getUserId());
                if (StringUtils.hasText(link.getGroupId())) groups.add(link.getGroupId());
            }
        }
        Set<String> active = identityResolver.resolveActiveUserIds(users, groups);
        Set<String> eligible = identityResolver.resolveClaimEligibleUserIds(active);
        if (eligible.size() > MAX_RECIPIENTS_PER_EVENT)
        {
            throw new ServiceException("通知接收人数超过单事件上限", HttpStatus.CONFLICT);
        }
        return eligible;
    }

    /**
     * 从运行实例或历史实例解析流程发起人。
     * @param processInstanceId String，流程实例主键
     * @return String，发起用户主键；运行和历史均无事实时为 null
     */
    private String processInitiator(String processInstanceId)
    {
        ProcessInstance runtime = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (runtime != null && StringUtils.hasText(runtime.getStartUserId()))
        {
            return runtime.getStartUserId();
        }
        HistoricProcessInstance historic = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        return historic == null ? null : historic.getStartUserId();
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
     * 冻结最终操作者供外部 Outbox 审计使用；ACTOR 是否作为接收人仍由策略独立决定。
     * @param request NotificationRequest，原始身份上下文
     * @return String，规范化操作者标识；当前上下文没有操作者时为 null
     */
    private String resolvedActor(NotificationRequest request)
    {
        String actor = StringUtils.hasText(request.actorUserId()) ? request.actorUserId()
                : (request.resolveActorFromAuthentication()
                        ? Authentication.getAuthenticatedUserId() : null);
        return optional(actor, 64);
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
        String eventType = upper(request.eventType());
        if (!WorkflowNotificationConstants.EVENT_TYPES.contains(eventType))
        {
            throw invalid("通知事件类型不受支持");
        }
        return new NotificationRequest(eventType, normalizedSourceId(request.sourceId()),
                normalized(request.processDefinitionKey(), 255, "流程定义标识不合法"),
                optional(request.processName(), 255),
                normalized(request.processInstanceId(), 64, "流程实例主键不合法"),
                optional(request.taskId(), 64), optional(request.taskDefinitionKey(), 255),
                optional(request.taskName(), 255), request.task(),
                request.taskRecipientUserIds() == null ? Set.of()
                        : immutableLinkedSet(request.taskRecipientUserIds()),
                optional(request.actorUserId(), 64), request.resolveActorFromAuthentication(),
                optional(request.initiatorUserId(), 64), request.resolveInitiatorFromProcess(),
                normalized(request.routePath(), 500, "通知业务路由不合法"),
                request.contentSuffix());
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
    private String normalized(String value, int max, String message)
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
    private String optional(String value, int max)
    {
        return StringUtils.hasText(value) ? normalized(value, max, "通知上下文字段不合法") : null;
    }

    /**
     * 校验稳定来源键为可见 ASCII。
     * @param value String，业务来源键
     * @return String，可写入 ascii_bin 字段的来源键
     */
    private String normalizedSourceId(String value)
    {
        String sourceId = normalized(value, 191, "通知来源标识不合法");
        if (sourceId.chars().anyMatch(character -> character < 0x21 || character > 0x7e))
            throw invalid("通知来源标识不合法");
        return sourceId;
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
    private ServiceException invalid(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * Service 交给 Planner 的流程、任务和身份上下文。
     *
     * @param eventType String，通知事件类型
     * @param sourceId String，稳定业务来源键
     * @param processDefinitionKey String，流程定义 key
     * @param processName String，流程名称快照
     * @param processInstanceId String，流程实例主键
     * @param taskId String，可空任务主键
     * @param taskDefinitionKey String，可空任务节点 key
     * @param taskName String，可空任务名称
     * @param task Task，可空真实任务；非空时由 Planner 解析候选关系
     * @param taskRecipientUserIds Set&lt;String&gt;，无 Task 对象时的冻结任务接收人
     * @param actorUserId String，可空显式操作者
     * @param resolveActorFromAuthentication boolean，是否从当前 Flowable 认证解析操作者
     * @param initiatorUserId String，可空显式发起人
     * @param resolveInitiatorFromProcess boolean，是否从流程实例解析发起人
     * @param routePath String，业务详情相对路由
     * @param contentSuffix String，可空最终正文后缀，仅用于催办原因
     */
    public record NotificationRequest(String eventType, String sourceId,
            String processDefinitionKey, String processName, String processInstanceId,
            String taskId, String taskDefinitionKey, String taskName, Task task,
            Set<String> taskRecipientUserIds, String actorUserId,
            boolean resolveActorFromAuthentication, String initiatorUserId,
            boolean resolveInitiatorFromProcess, String routePath, String contentSuffix)
    {
    }

    private record PolicyKey(String eventType, String processDefinitionKey,
            String taskDefinitionKey) { }
    private record Policy(long policyId, String scopeType, Set<String> recipientRules,
            Set<String> channels, String smsTemplateId, String titleTemplate,
            String contentTemplate, int maxAttempts) { }
    private record Preference(boolean inboxEnabled, boolean emailEnabled,
            boolean smsEnabled) { }
    private record PlannedEvent(NotificationRequest request, Policy policy,
            Set<String> recipients, String title, String content) { }
    private record CopyIdentity(String copyEventId, String recipientUserId) { }
}

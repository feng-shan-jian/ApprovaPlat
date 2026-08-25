package com.ruoyi.flowable.service.notification;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowNotificationPolicyRequest;
import com.ruoyi.flowable.domain.dto.WorkflowNotificationPreferenceRequest;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;

/**
 * 通知策略与用户偏好服务，唯一负责配置校验、乐观锁和正式配置持久化。
 */
@Service
public class WorkflowNotificationPolicyService
{
    private static final Set<String> TEMPLATE_VARIABLES =
            WorkflowNotificationConstants.TEMPLATE_VARIABLES;
    private static final Set<String> EVENT_TYPES = WorkflowNotificationConstants.EVENT_TYPES;
    private static final List<String> RECIPIENT_RULE_ORDER = List.of(
            "TASK_RECIPIENT", "INITIATOR", "ACTOR");
    private static final List<String> CHANNEL_ORDER = List.of("INBOX", "EMAIL", "SMS");
    private static final Set<String> SCOPES = Set.of("DEFAULT", "PROCESS", "NODE");
    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");
    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile(
            "\\{\\{([A-Za-z][A-Za-z0-9]*)}}");
    private static final int MAX_TITLE_LENGTH = 160;
    private static final int MAX_CONTENT_LENGTH = 700;

    private final JdbcTemplate jdbcTemplate;
    private final WorkflowIdentityResolver identityResolver;
    private final WorkflowNotificationCatalogService notificationCatalogService;
    private final WorkflowMailConfigService mailConfigService;

    /**
     * 创建通知策略与偏好服务。
     * @param jdbcTemplate JdbcTemplate，策略和偏好正式数据访问入口
     * @param identityResolver WorkflowIdentityResolver，当前用户身份解析入口
     * @param notificationCatalogService WorkflowNotificationCatalogService，真实流程和节点复核入口
     * @param mailConfigService WorkflowMailConfigService，启用邮件策略前的 SMTP 可用性门禁
     * @return void，构造后由 Spring 管理
     */
    public WorkflowNotificationPolicyService(JdbcTemplate jdbcTemplate,
            WorkflowIdentityResolver identityResolver,
            WorkflowNotificationCatalogService notificationCatalogService,
            WorkflowMailConfigService mailConfigService)
    {
        this.jdbcTemplate = jdbcTemplate;
        this.identityResolver = identityResolver;
        this.notificationCatalogService = notificationCatalogService;
        this.mailConfigService = mailConfigService;
    }

    /**
     * 查询当前用户通知偏好，未保存时返回正式默认值。
     * @return Map&lt;String,Object&gt;，站内、邮件、短信开关和 revision
     */
    @Transactional(readOnly = true)
    public Map<String, Object> preference()
    {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select inbox_enabled as inboxEnabled,email_enabled as emailEnabled," +
                "sms_enabled as smsEnabled,revision " +
                "from wf_notification_preference where user_id=?", currentUserId());
        return rows.isEmpty() ? Map.of("inboxEnabled", true, "emailEnabled", true,
                "smsEnabled", false, "revision", 0) : rows.get(0);
    }

    /**
     * 以乐观锁保存当前用户通知偏好。
     * @param request WorkflowNotificationPreferenceRequest，三个通道开关和期望版本
     * @return Map&lt;String,Object&gt;，保存后的偏好
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> savePreference(WorkflowNotificationPreferenceRequest request)
    {
        if (request == null || request.expectedRevision() == null
                || request.expectedRevision() < 0)
        {
            throw invalid("通知偏好版本不合法");
        }
        long userId = currentUserId();
        int updated = jdbcTemplate.update("update wf_notification_preference set inbox_enabled=?," +
                "email_enabled=?,sms_enabled=?,revision=revision+1,update_time=current_timestamp(3) " +
                "where user_id=? and revision=?", request.inboxEnabled(), request.emailEnabled(),
                request.smsEnabled(), userId, request.expectedRevision());
        if (updated == 0 && request.expectedRevision() == 0)
        {
            try
            {
                updated = jdbcTemplate.update("insert into wf_notification_preference " +
                        "(user_id,inbox_enabled,email_enabled,sms_enabled,revision,update_time) " +
                        "values (?,?,?,?,1,current_timestamp(3))", userId,
                        request.inboxEnabled(), request.emailEnabled(), request.smsEnabled());
            }
            catch (DataAccessException exception)
            {
                throw new ServiceException("通知偏好已变化，请刷新后重试", HttpStatus.CONFLICT);
            }
        }
        if (updated != 1)
        {
            throw new ServiceException("通知偏好已变化，请刷新后重试", HttpStatus.CONFLICT);
        }
        return preference();
    }

    /**
     * 查询全部通知策略供管理员维护。
     * @return List&lt;Map&lt;String,Object&gt;&gt;，策略配置和 revision
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> policies()
    {
        return jdbcTemplate.queryForList("select policy_id as policyId,scope_type as scopeType," +
                "process_definition_key as processDefinitionKey," +
                "task_definition_key as taskDefinitionKey,event_type as eventType," +
                "recipient_rules as recipientRules,channels,sms_template_id as smsTemplateId," +
                "title_template as titleTemplate,content_template as contentTemplate," +
                "max_attempts as maxAttempts,status,revision,update_time as updateTime " +
                "from wf_notification_policy " +
                "order by event_type,field(scope_type,'DEFAULT','PROCESS','NODE'),policy_id");
    }

    /**
     * 新增或乐观锁更新流程、节点通知策略。
     * @param request WorkflowNotificationPolicyRequest，完整策略请求
     * @return Map&lt;String,Object&gt;，保存后的策略
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> savePolicy(WorkflowNotificationPolicyRequest request)
    {
        ValidatedPolicy policy = validatePolicy(request);
        String actor = String.valueOf(currentUserId());
        long policyId;
        if (request.policyId() == null)
        {
            policyId = insertPolicy(policy, actor);
        }
        else
        {
            if (request.policyId() <= 0 || request.expectedRevision() == null
                    || request.expectedRevision() < 0)
            {
                throw invalid("通知策略版本不合法");
            }
            int updated;
            try
            {
                updated = jdbcTemplate.update("update wf_notification_policy set scope_type=?," +
                        "process_definition_key=?,task_definition_key=?,event_type=?," +
                        "recipient_rules=?,channels=?,sms_template_id=?,title_template=?," +
                        "content_template=?,max_attempts=?,status=?,revision=revision+1," +
                        "update_by=?,update_time=current_timestamp(3) " +
                        "where policy_id=? and revision=?", policy.scopeType(),
                        policy.processDefinitionKey(), policy.taskDefinitionKey(), policy.eventType(),
                        policy.recipientRules(), policy.channels(), policy.smsTemplateId(),
                        policy.titleTemplate(), policy.contentTemplate(), policy.maxAttempts(),
                        policy.status(), actor, request.policyId(), request.expectedRevision());
            }
            catch (DuplicateKeyException exception)
            {
                throw new ServiceException("相同作用域和事件的通知策略已存在",
                        HttpStatus.CONFLICT).setSubCode("NOTIFICATION_POLICY_DUPLICATE");
            }
            catch (DataAccessException exception)
            {
                throw new ServiceException("通知策略保存失败", HttpStatus.ERROR);
            }
            if (updated != 1)
            {
                throw new ServiceException("通知策略已变化，请刷新后重试", HttpStatus.CONFLICT)
                        .setSubCode("NOTIFICATION_POLICY_REVISION_CONFLICT");
            }
            policyId = request.policyId();
        }
        return jdbcTemplate.queryForMap("select policy_id as policyId,scope_type as scopeType," +
                "process_definition_key as processDefinitionKey," +
                "task_definition_key as taskDefinitionKey,event_type as eventType," +
                "recipient_rules as recipientRules,channels,sms_template_id as smsTemplateId," +
                "title_template as titleTemplate,content_template as contentTemplate," +
                "max_attempts as maxAttempts,status,revision " +
                "from wf_notification_policy where policy_id=?", policyId);
    }

    /**
     * 插入一条通过领域校验的策略并返回生成主键。
     * @param policy ValidatedPolicy，规范化策略字段
     * @param actor String，执行维护的管理员用户主键
     * @return long，新策略正式主键
     */
    private long insertPolicy(ValidatedPolicy policy, String actor)
    {
        KeyHolder holder = new GeneratedKeyHolder();
        try
        {
            jdbcTemplate.update(connection ->
            {
                PreparedStatement statement = connection.prepareStatement(
                        "insert into wf_notification_policy " +
                        "(scope_type,process_definition_key,task_definition_key,event_type," +
                        "recipient_rules,channels,sms_template_id,title_template,content_template," +
                        "max_attempts,status,revision,create_by,create_time) " +
                        "values (?,?,?,?,?,?,?,?,?,?,?,0,?,current_timestamp(3))",
                        Statement.RETURN_GENERATED_KEYS);
                bindPolicy(statement, policy, actor);
                return statement;
            }, holder);
        }
        catch (DuplicateKeyException exception)
        {
            throw new ServiceException("相同作用域和事件的通知策略已存在", HttpStatus.CONFLICT)
                    .setSubCode("NOTIFICATION_POLICY_DUPLICATE");
        }
        catch (DataAccessException exception)
        {
            throw new ServiceException("通知策略保存失败", HttpStatus.ERROR);
        }
        if (holder.getKey() == null)
        {
            throw new ServiceException("通知策略保存失败", HttpStatus.ERROR);
        }
        return holder.getKey().longValue();
    }

    /**
     * 校验通知策略作用域、枚举、模板、通道和有界重试配置。
     * @param request WorkflowNotificationPolicyRequest，管理员提交的完整策略请求
     * @return ValidatedPolicy，可按正式字段顺序写入数据库的规范策略
     */
    private ValidatedPolicy validatePolicy(WorkflowNotificationPolicyRequest request)
    {
        if (request == null) throw invalid("通知策略不能为空");
        String scope = upper(request.scopeType());
        String event = upper(request.eventType());
        String status = upper(request.status());
        String processKey = optional(request.processDefinitionKey(), 255);
        String taskKey = optional(request.taskDefinitionKey(), 255);
        if (!SCOPES.contains(scope) || !EVENT_TYPES.contains(event) || !STATUSES.contains(status))
            throw invalid("通知策略枚举值不合法");
        if (("DEFAULT".equals(scope) && (processKey != null || taskKey != null))
                || ("PROCESS".equals(scope) && (processKey == null || taskKey != null))
                || ("NODE".equals(scope) && (processKey == null || taskKey == null)))
            throw invalid("通知策略作用域字段不一致");
        // 客户端目录值不是权威事实；每次保存都重新读取最新激活部署及真实 UserTask。
        notificationCatalogService.validateScope(scope, processKey, taskKey);
        String recipients = normalizedCsv(request.recipientRules(), RECIPIENT_RULE_ORDER);
        String channels = normalizedCsv(request.channels(), CHANNEL_ORDER);
        String smsTemplateId = optional(request.smsTemplateId(), 64);
        if (channels.contains("SMS") != StringUtils.hasText(smsTemplateId))
            throw invalid("短信通道与供应商模板 ID 必须同时配置");
        String titleTemplate = validateTemplate(request.titleTemplate(), MAX_TITLE_LENGTH,
                "通知标题模板不合法", false);
        String contentTemplate = validateTemplate(request.contentTemplate(), MAX_CONTENT_LENGTH,
                "通知正文模板不合法", true);
        if (request.maxAttempts() == null || request.maxAttempts() < 1
                || request.maxAttempts() > 20)
            throw invalid("通知最大投递次数必须为 1 至 20");
        if ("ENABLED".equals(status) && csv(channels).contains("EMAIL"))
        {
            // 启用邮件策略必须绑定可解密正式 SMTP；停用策略允许预先编辑但不会进入 outbox。
            mailConfigService.requireMailChannelAvailable();
        }
        return new ValidatedPolicy(scope, processKey, taskKey, event, recipients, channels,
                smsTemplateId, titleTemplate, contentTemplate, request.maxAttempts(), status);
    }

    /**
     * 按新增 SQL 字段顺序绑定策略和操作者。
     * @param statement PreparedStatement，新增策略语句
     * @param policy ValidatedPolicy，规范化策略
     * @param actor String，管理员用户主键
     * @return void，全部字段绑定完成
     * @throws java.sql.SQLException JDBC 参数绑定失败
     */
    private void bindPolicy(PreparedStatement statement, ValidatedPolicy policy,
            String actor) throws java.sql.SQLException
    {
        statement.setString(1, policy.scopeType());
        statement.setString(2, policy.processDefinitionKey());
        statement.setString(3, policy.taskDefinitionKey());
        statement.setString(4, policy.eventType());
        statement.setString(5, policy.recipientRules());
        statement.setString(6, policy.channels());
        statement.setString(7, policy.smsTemplateId());
        statement.setString(8, policy.titleTemplate());
        statement.setString(9, policy.contentTemplate());
        statement.setInt(10, policy.maxAttempts());
        statement.setString(11, policy.status());
        statement.setString(12, actor);
    }

    /**
     * 校验模板长度和变量白名单。
     * @param template String，原始模板
     * @param maxCodePoints int，字段字符上限
     * @param message String，长度错误提示
     * @param allowBodyWhitespace boolean，正文是否允许换行和制表符
     * @return String，规范化模板
     */
    private String validateTemplate(String template, int maxCodePoints, String message,
            boolean allowBodyWhitespace)
    {
        if (!StringUtils.hasText(template)) throw invalid("通知模板不能为空");
        String normalized = template.trim();
        if (normalized.codePointCount(0, normalized.length()) > maxCodePoints)
            throw invalid(message);
        // SMTP Subject 禁止任何控制字符；正文只允许业务排版需要的 CR、LF 和 TAB。
        boolean hasForbiddenControl = normalized.chars().anyMatch(character ->
                Character.isISOControl(character)
                        && (!allowBodyWhitespace
                                || (character != '\r' && character != '\n' && character != '\t')));
        if (hasForbiddenControl)
            throw invalid(allowBodyWhitespace ? "通知正文模板包含非法控制字符"
                    : "通知标题模板不能包含换行或控制字符");
        Matcher matcher = TEMPLATE_VARIABLE.matcher(normalized);
        while (matcher.find())
        {
            if (!TEMPLATE_VARIABLES.contains(matcher.group(1)))
                throw invalid("通知模板包含非白名单变量: " + matcher.group(1));
        }
        String residue = TEMPLATE_VARIABLE.matcher(normalized).replaceAll("");
        if (residue.contains("{{") || residue.contains("}}"))
            throw invalid("通知模板变量格式不合法");
        return normalized;
    }

    /**
     * 校验 CSV 枚举并按服务端固定顺序输出。
     * @param value String，客户端 CSV
     * @param canonicalOrder List&lt;String&gt;，唯一合法值和保存顺序
     * @return String，去重且顺序稳定的 CSV
     */
    private String normalizedCsv(String value, List<String> canonicalOrder)
    {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String item : csv(value))
        {
            String normalized = upper(item);
            if (!canonicalOrder.contains(normalized))
                throw invalid("通知策略包含不支持的配置: " + item);
            values.add(normalized);
        }
        if (values.isEmpty()) throw invalid("通知策略列表不能为空");
        return canonicalOrder.stream().filter(values::contains)
                .collect(java.util.stream.Collectors.joining(","));
    }

    /** @param value String，可空 CSV；@return List&lt;String&gt;，非空去空白条目。 */
    private List<String> csv(String value)
    {
        if (!StringUtils.hasText(value)) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim)
                .filter(item -> !item.isEmpty()).toList();
    }

    /** @param value String，可空标识；@param max int，最大长度；@return String，规范值或 null。 */
    private String optional(String value, int max)
    {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.trim();
        if (normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl))
            throw invalid("通知策略标识不合法");
        return normalized;
    }

    /** @param value String，可空枚举；@return String，固定 Locale 大写值。 */
    private String upper(String value)
    {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    /** @return long，当前正式用户主键。 */
    private long currentUserId()
    {
        try
        {
            return Long.parseLong(identityResolver.resolveCurrentIdentity().userId());
        }
        catch (RuntimeException exception)
        {
            throw new ServiceException("当前用户身份无效", HttpStatus.UNAUTHORIZED);
        }
    }

    /** @param message String，稳定错误提示；@return ServiceException，HTTP 400 参数异常。 */
    private ServiceException invalid(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    private record ValidatedPolicy(String scopeType, String processDefinitionKey,
            String taskDefinitionKey, String eventType, String recipientRules, String channels,
            String smsTemplateId, String titleTemplate, String contentTemplate,
            int maxAttempts, String status) { }
}

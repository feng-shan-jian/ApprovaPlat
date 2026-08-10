package com.ruoyi.flowable.service.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.flowable.bpmn.model.BaseElement;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.UserTask;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 自动抄送 BPMN 扩展属性契约，负责受控 JSON 的结构、触发时机和接收人来源校验。
 */
public final class WorkflowAutoCopyRuleContract
{
    /** 设计器写入、部署资源冻结和运行时读取共用的保留属性名。 */
    public static final String PROPERTY_NAME = "approva.autoCopyRules";

    /** 自动抄送规则允许超过普通展示属性，但仍受 BPMN 总体积和此处边界约束。 */
    public static final int MAX_PROPERTY_LENGTH = 8 * 1024;

    /** 单个节点最多配置的规则数。 */
    private static final int MAX_RULES = 10;

    /** 单条规则最多配置的接收人来源数。 */
    private static final int MAX_SOURCES = 20;

    /** 单个来源最多包含的固定身份或字段数。 */
    private static final int MAX_SOURCE_VALUES = 100;

    private static final Pattern RULE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}");
    private static final Pattern USER_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern GROUP_ID = Pattern.compile("(?:ROLE|DEPT)[1-9][0-9]{0,18}");
    private static final Pattern VARIABLE = Pattern.compile("[A-Za-z][A-Za-z0-9_.]{0,63}");

    private WorkflowAutoCopyRuleContract()
    {
    }

    /**
     * 判断扩展属性名是否属于平台自动抄送契约。
     *
     * @param propertyName String，待判断的 flowable:property 名称
     * @return boolean，命中唯一保留属性名时返回 true
     */
    public static boolean isReservedProperty(String propertyName)
    {
        return PROPERTY_NAME.equals(propertyName);
    }

    /**
     * 从 BPMN 元素读取并严格解析自动抄送规则。
     *
     * @param element BaseElement，流程或用户任务元素
     * @return List&lt;Rule&gt;，顺序稳定且不可修改的规则集合
     */
    public static List<Rule> readRules(BaseElement element)
    {
        if (element == null)
        {
            return List.of();
        }
        String json = readPropertyValue(element);
        if (json == null)
        {
            return List.of();
        }
        if (json.isBlank() || json.length() > MAX_PROPERTY_LENGTH)
        {
            throw invalid("自动抄送规则内容为空或超过长度限制", null);
        }
        try
        {
            JsonNode root = JsonMapper.shared().readTree(json);
            if (root == null || !root.isObject() || root.path("version").asInt(-1) != 1)
            {
                throw invalid("自动抄送规则版本不受支持", null);
            }
            JsonNode rulesNode = root.get("rules");
            if (rulesNode == null || !rulesNode.isArray()
                    || rulesNode.isEmpty() || rulesNode.size() > MAX_RULES)
            {
                throw invalid("自动抄送规则数量必须为 1 至 " + MAX_RULES, null);
            }
            List<Rule> rules = new ArrayList<>();
            Set<String> ruleIds = new LinkedHashSet<>();
            for (JsonNode ruleNode : rulesNode)
            {
                rules.add(parseRule(ruleNode, ruleIds));
            }
            return List.copyOf(rules);
        }
        catch (ServiceException exception)
        {
            throw exception;
        }
        catch (Exception exception)
        {
            throw invalid("自动抄送规则 JSON 不合法", exception);
        }
    }

    /**
     * 校验规则只能出现在与触发时机匹配的 BPMN 元素上。
     *
     * @param element BaseElement，流程或用户任务元素
     * @param allowedTriggers Set&lt;Trigger&gt;，该元素允许的触发时机
     * @return void，配置不匹配时抛出稳定 400
     */
    public static void validatePlacement(BaseElement element, Set<Trigger> allowedTriggers)
    {
        for (Rule rule : readRules(element))
        {
            if (allowedTriggers == null || !allowedTriggers.contains(rule.trigger()))
            {
                throw invalid("自动抄送触发时机与 BPMN 元素类型不匹配", null);
            }
        }
    }

    /**
     * 使用保存、显式校验和部署共用的正式表单目录校验全部表单用户来源。
     *
     * 任务级规则只能读取该任务权限化快照中的字段；流程完成规则可以读取同一流程任一
     * 正式节点字段。目录仅包含可见、可读、单值且由规则明确赋予用户主键语义的字段。
     *
     * @param model BpmnModel，已通过作者 BPMN 安全校验的公共模型
     * @param catalog WorkflowAuthorFormFieldCatalog，本次事务冻结的正式表单字段目录
     * @return void，字段不存在、隐藏、无读权限或为复合值时抛出稳定 400
     */
    public static void validateFormUserFields(BpmnModel model,
            WorkflowAuthorFormFieldCatalog catalog)
    {
        if (model == null || catalog == null)
        {
            throw new ServiceException("自动抄送表单字段校验输入不完整", HttpStatus.ERROR);
        }
        for (Process process : model.getProcesses())
        {
            if (!process.isExecutable()) continue;
            validateFormUserSources(process, fieldName ->
                    catalog.containsProcessField(process.getId(), fieldName));
            for (UserTask task : process.findFlowElementsOfType(UserTask.class, true))
            {
                validateFormUserSources(task, fieldName -> catalog.containsTaskField(
                        process.getId(), task.getId(), fieldName));
            }
        }
    }

    /**
     * 校验单个流程或任务元素上的 FORM_USER_FIELD 来源全部命中正式字段目录。
     * @param element BaseElement，当前流程或用户任务元素
     * @param fieldExists java.util.function.Predicate&lt;String&gt;，元素作用域的正式字段查询器
     * @return void，任一字段不合格时立即失败
     */
    private static void validateFormUserSources(BaseElement element,
            java.util.function.Predicate<String> fieldExists)
    {
        for (Rule rule : readRules(element))
        {
            for (RecipientSource source : rule.recipients())
            {
                if (source.type() != RecipientType.FORM_USER_FIELD) continue;
                for (String fieldName : source.values())
                {
                    if (!fieldExists.test(fieldName))
                    {
                        throw new ServiceException(
                                "自动抄送表单用户字段必须来自当前作用域可见、可读的正式单值字段",
                                HttpStatus.BAD_REQUEST)
                                .setSubCode("BPMN_AUTO_COPY_FORM_FIELD_INVALID");
                    }
                }
            }
        }
    }

    /**
     * 解析单条规则并校验唯一标识、触发时机和接收来源。
     *
     * @param node JsonNode，单条规则 JSON
     * @param ruleIds Set&lt;String&gt;，当前元素已出现的规则主键
     * @return Rule，结构化规则
     */
    private static Rule parseRule(JsonNode node, Set<String> ruleIds)
    {
        if (node == null || !node.isObject())
        {
            throw invalid("自动抄送规则必须为对象", null);
        }
        String id = text(node.get("id"));
        if (!RULE_ID.matcher(id).matches() || !ruleIds.add(id))
        {
            throw invalid("自动抄送规则主键不合法或重复", null);
        }
        Trigger trigger;
        try
        {
            trigger = Trigger.valueOf(text(node.get("trigger")));
        }
        catch (IllegalArgumentException exception)
        {
            throw invalid("自动抄送触发时机不受支持", exception);
        }
        JsonNode recipientsNode = node.get("recipients");
        if (recipientsNode == null || !recipientsNode.isArray()
                || recipientsNode.isEmpty() || recipientsNode.size() > MAX_SOURCES)
        {
            throw invalid("自动抄送接收人来源数量必须为 1 至 " + MAX_SOURCES, null);
        }
        List<RecipientSource> sources = new ArrayList<>();
        Set<String> sourceKeys = new LinkedHashSet<>();
        for (JsonNode sourceNode : recipientsNode)
        {
            RecipientSource source = parseSource(sourceNode);
            String sourceKey = source.type().name() + ":" + String.join(",", source.values());
            if (!sourceKeys.add(sourceKey))
            {
                throw invalid("同一自动抄送规则不能重复配置接收人来源", null);
            }
            sources.add(source);
        }
        return new Rule(id, trigger, List.copyOf(sources));
    }

    /**
     * 解析单个固定身份、发起人或表单用户字段来源。
     *
     * @param node JsonNode，接收人来源 JSON
     * @return RecipientSource，结构化来源
     */
    private static RecipientSource parseSource(JsonNode node)
    {
        if (node == null || !node.isObject())
        {
            throw invalid("自动抄送接收人来源必须为对象", null);
        }
        RecipientType type;
        try
        {
            type = RecipientType.valueOf(text(node.get("type")));
        }
        catch (IllegalArgumentException exception)
        {
            throw invalid("自动抄送接收人来源不受支持", exception);
        }
        List<String> values = new ArrayList<>();
        JsonNode valuesNode = node.get("values");
        if (valuesNode != null && !valuesNode.isNull())
        {
            if (!valuesNode.isArray() || valuesNode.size() > MAX_SOURCE_VALUES)
            {
                throw invalid("自动抄送来源值必须为有界数组", null);
            }
            for (JsonNode valueNode : valuesNode)
            {
                String value = text(valueNode);
                Pattern pattern = switch (type)
                {
                    case USER -> USER_ID;
                    case GROUP -> GROUP_ID;
                    case FORM_USER_FIELD -> VARIABLE;
                    case INITIATOR -> null;
                };
                if (pattern == null || !pattern.matcher(value).matches() || values.contains(value))
                {
                    throw invalid("自动抄送来源值格式不合法或重复", null);
                }
                values.add(value);
            }
        }
        if (type == RecipientType.INITIATOR)
        {
            if (!values.isEmpty())
            {
                throw invalid("发起人来源不允许携带额外值", null);
            }
        }
        else if (values.isEmpty())
        {
            throw invalid("自动抄送接收人来源不能为空", null);
        }
        return new RecipientSource(type, List.copyOf(values));
    }

    /**
     * 从 Flowable 扩展元素树读取唯一的自动抄送属性值。
     *
     * @param element BaseElement，待读取元素
     * @return String，属性值；未配置时返回 null
     */
    private static String readPropertyValue(BaseElement element)
    {
        String matched = null;
        List<ExtensionElement> containers = element.getExtensionElements().get("properties");
        if (containers == null)
        {
            return null;
        }
        for (ExtensionElement container : containers)
        {
            Map<String, List<ExtensionElement>> children = container.getChildElements();
            for (ExtensionElement property : children.getOrDefault("property", List.of()))
            {
                if (!PROPERTY_NAME.equals(property.getAttributeValue(null, "name")))
                {
                    continue;
                }
                if (matched != null)
                {
                    throw invalid("同一元素不能重复配置自动抄送规则", null);
                }
                matched = property.getAttributeValue(null, "value");
            }
        }
        return matched;
    }

    /**
     * 读取必填 JSON 文本节点。
     *
     * @param node JsonNode，待读取节点
     * @return String，原始文本值；非文本返回空串并由调用方拒绝
     */
    private static String text(JsonNode node)
    {
        return node != null && node.isTextual() ? node.asText() : "";
    }

    /**
     * 创建稳定的 BPMN 规则参数异常。
     *
     * @param message String，对外错误说明
     * @param cause Throwable，可为空的解析原因
     * @return ServiceException，HTTP 400 异常
     */
    private static ServiceException invalid(String message, Throwable cause)
    {
        ServiceException exception = new ServiceException(message, HttpStatus.BAD_REQUEST);
        if (cause != null)
        {
            exception.initCause(cause);
        }
        return exception;
    }

    /** 自动抄送生命周期触发时机。 */
    public enum Trigger
    {
        NODE_ARRIVED,
        NODE_COMPLETED,
        PROCESS_COMPLETED
    }

    /** 自动抄送接收人来源类型。 */
    public enum RecipientType
    {
        USER,
        GROUP,
        INITIATOR,
        FORM_USER_FIELD
    }

    /**
     * 单条自动抄送规则。
     *
     * @param id String，设计器生成的稳定规则主键
     * @param trigger Trigger，生命周期触发时机
     * @param recipients List&lt;RecipientSource&gt;，接收人来源集合
     */
    public record Rule(String id, Trigger trigger, List<RecipientSource> recipients)
    {
    }

    /**
     * 单个自动抄送接收人来源。
     *
     * @param type RecipientType，固定用户、角色部门、发起人或表单用户字段
     * @param values List&lt;String&gt;，来源对应的规范身份或变量名
     */
    public record RecipientSource(RecipientType type, List<String> values)
    {
    }
}

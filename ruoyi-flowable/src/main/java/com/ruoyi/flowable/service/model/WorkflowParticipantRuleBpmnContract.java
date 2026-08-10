package com.ruoyi.flowable.service.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.flowable.bpmn.model.BaseElement;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.UserTask;

/**
 * 流程发起范围和单实例 UserTask 参与者规则的作者 BPMN 固定协议。
 */
public final class WorkflowParticipantRuleBpmnContract
{
    /** 当前规则解析协议版本；部署快照必须冻结该值。 */
    public static final int RULE_VERSION = 1;
    /** 无匹配时固定失败，禁止产生无人任务或偷偷降级为公开发起。 */
    public static final String NO_MATCH_FAIL = "FAIL";

    public static final String START_PREFIX = "approva.startScope.";
    public static final String START_VERSION = START_PREFIX + "ruleVersion";
    public static final String START_TYPE = START_PREFIX + "type";
    public static final String START_TARGET_IDS = START_PREFIX + "targetIds";
    public static final String START_NO_MATCH = START_PREFIX + "noMatchPolicy";

    public static final String TASK_PREFIX = "approva.assignment.";
    public static final String TASK_VERSION = TASK_PREFIX + "ruleVersion";
    public static final String TASK_TYPE = TASK_PREFIX + "type";
    public static final String TASK_TARGET_IDS = TASK_PREFIX + "targetIds";
    public static final String TASK_FORM_FIELD = TASK_PREFIX + "formField";
    public static final String TASK_NO_MATCH = TASK_PREFIX + "noMatchPolicy";

    /** 发起范围类型。 */
    public static final Set<String> START_TYPES = Set.of("PUBLIC", "USERS", "ROLES", "DEPTS");
    /** 直接办理规则，解析结果必须且只能有一个有效审批人。 */
    public static final Set<String> ASSIGNEE_TYPES = Set.of(
            "FIXED_USER", "STARTER", "STARTER_MANAGER", "DEPT_MANAGER", "FORM_USER");
    /** 候选规则，解析结果可包含多个去重身份。 */
    public static final Set<String> CANDIDATE_TYPES = Set.of(
            "CANDIDATE_USERS", "CANDIDATE_GROUPS", "STARTER_DEPT_ROLE");

    public static final Set<String> RESERVED_PROPERTIES = Set.of(
            START_VERSION, START_TYPE, START_TARGET_IDS, START_NO_MATCH,
            TASK_VERSION, TASK_TYPE, TASK_TARGET_IDS, TASK_FORM_FIELD, TASK_NO_MATCH);

    private static final Pattern POSITIVE_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    private WorkflowParticipantRuleBpmnContract()
    {
    }

    /**
     * 判断属性名是否属于平台参与者规则保留集合。
     * @param propertyName String，flowable:property 名称
     * @return boolean，命中保留集合时返回 true
     */
    public static boolean isReservedProperty(String propertyName)
    {
        return RESERVED_PROPERTIES.contains(propertyName);
    }

    /**
     * 判断元素是否携带任何参与者规则属性。
     * @param element BaseElement，BPMN 流程或节点
     * @return boolean，存在至少一个保留属性时返回 true
     */
    public static boolean hasReservedProperties(BaseElement element)
    {
        return readPropertyValues(element).keySet().stream().anyMatch(RESERVED_PROPERTIES::contains);
    }

    /**
     * 判断元素是否声明流程级发起范围属性。
     * @param element BaseElement，BPMN 元素
     * @return boolean，至少存在一个 startScope 属性时返回 true
     */
    public static boolean hasStartProperties(BaseElement element)
    {
        return readPropertyValues(element).keySet().stream().anyMatch(name -> name.startsWith(START_PREFIX));
    }

    /**
     * 判断元素是否声明用户任务办理人属性。
     * @param element BaseElement，BPMN 元素
     * @return boolean，至少存在一个 assignment 属性时返回 true
     */
    public static boolean hasTaskProperties(BaseElement element)
    {
        return readPropertyValues(element).keySet().stream().anyMatch(name -> name.startsWith(TASK_PREFIX));
    }

    /**
     * 为后端新建模型写入公开发起和发起人本人办理的完整 v1 作者规则。
     *
     * @param process Process，新建模型的可执行流程
     * @param task UserTask，新建模型的默认单实例审批任务
     * @return void，输入已带参与者属性时拒绝覆盖
     */
    public static void addInitialAuthorRules(Process process, UserTask task)
    {
        if (process == null || task == null || hasReservedProperties(process)
                || hasReservedProperties(task))
        {
            throw new IllegalArgumentException("初始参与者规则写入目标不合法");
        }
        LinkedHashMap<String, String> startValues = new LinkedHashMap<>();
        startValues.put(START_VERSION, Integer.toString(RULE_VERSION));
        startValues.put(START_TYPE, "PUBLIC");
        startValues.put(START_TARGET_IDS, "");
        startValues.put(START_NO_MATCH, NO_MATCH_FAIL);
        appendProperties(process, startValues);

        LinkedHashMap<String, String> taskValues = new LinkedHashMap<>();
        taskValues.put(TASK_VERSION, Integer.toString(RULE_VERSION));
        taskValues.put(TASK_TYPE, "STARTER");
        taskValues.put(TASK_TARGET_IDS, "");
        taskValues.put(TASK_FORM_FIELD, "");
        taskValues.put(TASK_NO_MATCH, NO_MATCH_FAIL);
        appendProperties(task, taskValues);
    }

    /**
     * 以 Flowable properties 扩展结构追加一组顺序稳定的作者属性。
     *
     * @param element BaseElement，流程或用户任务
     * @param values Map&lt;String,String&gt;，属性名和值的稳定有序映射
     * @return void，属性写入元素 extensionElements
     */
    private static void appendProperties(BaseElement element, Map<String, String> values)
    {
        ExtensionElement container = new ExtensionElement();
        container.setName("properties");
        container.setNamespace("http://flowable.org/bpmn");
        List<ExtensionElement> properties = new ArrayList<>(values.size());
        for (Map.Entry<String, String> entry : values.entrySet())
        {
            ExtensionElement property = new ExtensionElement();
            property.setName("property");
            property.setNamespace("http://flowable.org/bpmn");
            property.addAttribute(new ExtensionAttribute("name", entry.getKey()));
            property.addAttribute(new ExtensionAttribute("value", entry.getValue()));
            properties.add(property);
        }
        container.setChildElements(Map.of("property", properties));
        element.addExtensionElement(container);
    }

    /**
     * 读取流程级发起范围；旧模型缺失配置时按公开 v1 形成正式部署快照。
     * @param process Process，可执行流程
     * @return StartRule，字段完整的规范发起规则
     */
    public static StartRule readStartRule(Process process)
    {
        String processKey = requireText(process == null ? null : process.getId(), "发起范围所属流程标识不能为空");
        Map<String, String> values = readPropertyValues(process);
        Set<String> present = present(values, Set.of(START_VERSION, START_TYPE,
                START_TARGET_IDS, START_NO_MATCH));
        if (present.isEmpty())
        {
            return new StartRule(processKey, "PUBLIC", List.of(), RULE_VERSION,
                    NO_MATCH_FAIL, checksum("START", processKey, "PUBLIC", ""));
        }
        requireComplete(present, Set.of(START_VERSION, START_TYPE,
                START_TARGET_IDS, START_NO_MATCH), "流程发起范围配置不完整");
        requireVersion(values.get(START_VERSION));
        requireFailPolicy(values.get(START_NO_MATCH));
        String type = requireText(values.get(START_TYPE), "流程发起范围类型不能为空");
        if (!START_TYPES.contains(type))
        {
            throw new IllegalArgumentException("流程发起范围类型不受支持");
        }
        List<Long> ids = "PUBLIC".equals(type) ? requireNoTargets(values.get(START_TARGET_IDS))
                : parseIds(values.get(START_TARGET_IDS), "流程发起范围必须选择正式身份");
        return new StartRule(processKey, type, ids, RULE_VERSION, NO_MATCH_FAIL,
                checksum("START", processKey, type, join(ids)));
    }

    /**
     * 读取用户任务规则；受控动态规则来自扩展属性，原有静态配置转换为同版本快照。
     * @param processKey String，任务所属流程标识
     * @param task UserTask，单实例用户任务
     * @return Optional&lt;TaskRule&gt;，动态或静态规则存在时返回；兼容表达式和多实例任务为空
     */
    public static Optional<TaskRule> readTaskRule(String processKey, UserTask task)
    {
        if (task == null || task.getLoopCharacteristics() != null)
        {
            return Optional.empty();
        }
        Map<String, String> values = readPropertyValues(task);
        Set<String> required = Set.of(TASK_VERSION, TASK_TYPE, TASK_TARGET_IDS,
                TASK_FORM_FIELD, TASK_NO_MATCH);
        Set<String> present = present(values, required);
        if (!present.isEmpty())
        {
            requireComplete(present, required, "用户任务办理规则配置不完整");
            requireVersion(values.get(TASK_VERSION));
            requireFailPolicy(values.get(TASK_NO_MATCH));
            String type = requireText(values.get(TASK_TYPE), "用户任务办理规则类型不能为空");
            if (!ASSIGNEE_TYPES.contains(type) && !CANDIDATE_TYPES.contains(type))
            {
                throw new IllegalArgumentException("用户任务办理规则类型不受支持");
            }
            List<Long> targets = switch (type)
            {
                case "FIXED_USER", "DEPT_MANAGER" -> requireOneId(values.get(TASK_TARGET_IDS));
                case "CANDIDATE_USERS" ->
                        parseIds(values.get(TASK_TARGET_IDS), "用户任务办理规则必须选择正式身份");
                case "CANDIDATE_GROUPS" -> parseGroupTargets(values.get(TASK_TARGET_IDS));
                case "STARTER_DEPT_ROLE" -> requireOneId(values.get(TASK_TARGET_IDS));
                default -> requireNoTargets(values.get(TASK_TARGET_IDS));
            };
            String formField = "FORM_USER".equals(type)
                    ? requireVariable(values.get(TASK_FORM_FIELD))
                    : requireEmpty(values.get(TASK_FORM_FIELD), "非表单规则不能携带表单字段");
            return Optional.of(taskRule(processKey, task, type, targets, formField));
        }
        return readLegacyStaticRule(processKey, task);
    }

    /**
     * 将原有静态 assignee、candidateUsers 或 candidateGroups 纳入 v1 部署快照。
     * @param processKey String，任务所属流程标识
     * @param task UserTask，原有单实例任务
     * @return Optional&lt;TaskRule&gt;，静态配置可规范化时返回，表达式配置继续走兼容链
     */
    private static Optional<TaskRule> readLegacyStaticRule(String processKey, UserTask task)
    {
        String assignee = trim(task.getAssignee());
        if (!assignee.isEmpty() && !isExpression(assignee))
        {
            return Optional.of(taskRule(processKey, task, "FIXED_USER",
                    List.of(parseId(assignee, "固定办理人主键不合法")), ""));
        }
        List<String> users = nonBlank(task.getCandidateUsers());
        if (!users.isEmpty() && users.stream().noneMatch(WorkflowParticipantRuleBpmnContract::isExpression))
        {
            return Optional.of(taskRule(processKey, task, "CANDIDATE_USERS",
                    users.stream().map(value -> parseId(value, "候选用户主键不合法")).toList(), ""));
        }
        List<String> groups = nonBlank(task.getCandidateGroups());
        if (!groups.isEmpty() && groups.stream().noneMatch(WorkflowParticipantRuleBpmnContract::isExpression))
        {
            List<Long> encoded = groups.stream().map(value ->
            {
                if (value.startsWith("ROLE"))
                {
                    return parseId(value.substring(4), "候选角色主键不合法");
                }
                if (value.startsWith("DEPT"))
                {
                    return -parseId(value.substring(4), "候选部门主键不合法");
                }
                throw new IllegalArgumentException("候选组必须来自正式角色或部门目录");
            }).toList();
            return Optional.of(taskRule(processKey, task, "CANDIDATE_GROUPS", encoded, ""));
        }
        return Optional.empty();
    }

    /**
     * 构造字段完整且带内容摘要的任务规则。
     * @param processKey String，流程标识
     * @param task UserTask，任务节点
     * @param type String，规则类型
     * @param targets List&lt;Long&gt;，目标身份；负数仅在内部表示部门候选组
     * @param formField String，表单用户字段或空串
     * @return TaskRule，规范不可变任务规则
     */
    private static TaskRule taskRule(String processKey, UserTask task, String type,
            List<Long> targets, String formField)
    {
        String activityId = requireText(task.getId(), "用户任务标识不能为空");
        String mode = ASSIGNEE_TYPES.contains(type) ? "ASSIGNEE" : "CANDIDATE";
        List<Long> normalizedTargets = List.copyOf(new LinkedHashSet<>(targets));
        String canonical = String.join("\u0000", "TASK", processKey, activityId, mode,
                type, join(normalizedTargets), formField);
        return new TaskRule(processKey, activityId, trim(task.getName()), mode, type,
                normalizedTargets, formField, RULE_VERSION, NO_MATCH_FAIL, checksum(canonical));
    }

    /**
     * 从编译执行资源中移除作者可编辑规则属性，运行时只读取部署快照。
     * @param element BaseElement，流程或用户任务
     * @return void，无返回值
     */
    public static void removeAuthorProperties(BaseElement element)
    {
        if (element == null || element.getExtensionElements() == null)
        {
            return;
        }
        Map<String, List<ExtensionElement>> extensions = element.getExtensionElements();
        List<ExtensionElement> containers = extensions.get("properties");
        if (containers == null)
        {
            return;
        }
        List<ExtensionElement> remainingContainers = new ArrayList<>();
        for (ExtensionElement container : containers)
        {
            Map<String, List<ExtensionElement>> children = container.getChildElements();
            List<ExtensionElement> properties = children == null ? null : children.get("property");
            if (properties == null)
            {
                remainingContainers.add(container);
                continue;
            }
            List<ExtensionElement> remaining = properties.stream()
                    .filter(property -> !isReservedProperty(
                            property.getAttributeValue(null, "name"))).toList();
            if (!remaining.isEmpty())
            {
                Map<String, List<ExtensionElement>> copied = new HashMap<>(children);
                copied.put("property", new ArrayList<>(remaining));
                container.setChildElements(copied);
                remainingContainers.add(container);
            }
        }
        Map<String, List<ExtensionElement>> copied = new HashMap<>(extensions);
        if (remainingContainers.isEmpty()) copied.remove("properties");
        else copied.put("properties", remainingContainers);
        element.setExtensionElements(copied);
    }

    /**
     * 读取 Flowable properties 并拒绝同名规则属性重复。
     * @param element BaseElement，流程或节点
     * @return Map&lt;String,String&gt;，规则属性名值映射
     */
    private static Map<String, String> readPropertyValues(BaseElement element)
    {
        if (element == null || element.getExtensionElements() == null) return Map.of();
        Map<String, String> values = new HashMap<>();
        for (ExtensionElement container : element.getExtensionElements()
                .getOrDefault("properties", List.of()))
        {
            if (container == null || container.getChildElements() == null) continue;
            for (ExtensionElement property : container.getChildElements()
                    .getOrDefault("property", List.of()))
            {
                String name = property.getAttributeValue(null, "name");
                if (!isReservedProperty(name)) continue;
                if (values.put(name, property.getAttributeValue(null, "value")) != null)
                {
                    throw new IllegalArgumentException("参与者规则属性不能重复");
                }
            }
        }
        return Map.copyOf(values);
    }

    private static Set<String> present(Map<String, String> values, Set<String> names)
    {
        Set<String> result = new HashSet<>(values.keySet());
        result.retainAll(names);
        return result;
    }

    private static void requireComplete(Set<String> present, Set<String> required, String message)
    {
        if (!present.equals(required)) throw new IllegalArgumentException(message);
    }

    private static void requireVersion(String value)
    {
        if (!Integer.toString(RULE_VERSION).equals(trim(value)))
            throw new IllegalArgumentException("参与者规则版本不受支持");
    }

    private static void requireFailPolicy(String value)
    {
        if (!NO_MATCH_FAIL.equals(trim(value)))
            throw new IllegalArgumentException("无匹配策略必须为阻止流转");
    }

    private static List<Long> parseIds(String value, String message)
    {
        String normalized = trim(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(message);
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (String part : normalized.split(",", -1)) ids.add(parseId(part, message));
        if (ids.isEmpty() || ids.size() > 200) throw new IllegalArgumentException(message);
        return List.copyOf(ids);
    }

    /**
     * 解析角色和部门混合候选目标，内部用正负号保留对象类型。
     * @param value String，逗号分隔的 ROLE&lt;id&gt; 或 DEPT&lt;id&gt; 受控编码
     * @return List&lt;Long&gt;，角色为正数、部门为负数且保持作者选择顺序
     */
    private static List<Long> parseGroupTargets(String value)
    {
        String normalized = trim(value);
        if (normalized.isEmpty())
        {
            throw new IllegalArgumentException("用户任务办理规则必须选择正式身份");
        }
        LinkedHashSet<Long> targets = new LinkedHashSet<>();
        for (String part : normalized.split(",", -1))
        {
            String target = trim(part);
            if (target.startsWith("ROLE"))
            {
                targets.add(parseId(target.substring(4), "候选角色主键不合法"));
            }
            else if (target.startsWith("DEPT"))
            {
                targets.add(-parseId(target.substring(4), "候选部门主键不合法"));
            }
            else
            {
                throw new IllegalArgumentException("候选组必须来自正式角色或部门目录");
            }
        }
        if (targets.isEmpty() || targets.size() > 200)
        {
            throw new IllegalArgumentException("用户任务办理规则必须选择正式身份");
        }
        return List.copyOf(targets);
    }

    private static List<Long> requireOneId(String value)
    {
        List<Long> ids = parseIds(value, "直接办理规则必须选择一个正式身份");
        if (ids.size() != 1) throw new IllegalArgumentException("直接办理规则只能选择一个正式身份");
        return ids;
    }

    private static long parseId(String value, String message)
    {
        String normalized = trim(value);
        if (!POSITIVE_ID.matcher(normalized).matches()) throw new IllegalArgumentException(message);
        try { return Long.parseLong(normalized); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(message, exception); }
    }

    private static List<Long> requireNoTargets(String value)
    {
        requireEmpty(value, "当前规则不能携带目标身份");
        return List.of();
    }

    private static String requireVariable(String value)
    {
        String normalized = requireText(value, "表单用户字段不能为空");
        if (!VARIABLE.matcher(normalized).matches()) throw new IllegalArgumentException("表单用户字段不合法");
        return normalized;
    }

    private static String requireEmpty(String value, String message)
    {
        if (!trim(value).isEmpty()) throw new IllegalArgumentException(message);
        return "";
    }

    private static String requireText(String value, String message)
    {
        String normalized = trim(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(message);
        return normalized;
    }

    private static List<String> nonBlank(List<String> values)
    {
        return values == null ? List.of() : values.stream().map(WorkflowParticipantRuleBpmnContract::trim)
                .filter(value -> !value.isEmpty()).toList();
    }

    private static boolean isExpression(String value)
    {
        String normalized = trim(value);
        return normalized.startsWith("${") || normalized.startsWith("#{");
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }

    /**
     * 将目标主键转换为稳定逗号文本。
     * @param ids List&lt;Long&gt;，规范有序主键
     * @return String，逗号分隔文本
     */
    public static String join(List<Long> ids)
    {
        return ids == null ? "" : ids.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String checksum(String... parts)
    {
        return checksum(String.join("\u0000", parts));
    }

    private static String checksum(String canonical)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest)
            {
                result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                result.append(Character.forDigit(value & 0x0f, 16));
            }
            return result.toString();
        }
        catch (NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    /**
     * 流程级发起范围作者规则。
     * @param processKey String，流程标识
     * @param type String，PUBLIC、USERS、ROLES 或 DEPTS
     * @param targetIds List&lt;Long&gt;，规范目标身份
     * @param ruleVersion int，规则协议版本
     * @param noMatchPolicy String，无匹配策略
     * @param checksum String，规范内容摘要
     */
    public record StartRule(String processKey, String type, List<Long> targetIds,
            int ruleVersion, String noMatchPolicy, String checksum)
    {
        public StartRule { targetIds = List.copyOf(targetIds); }
    }

    /**
     * 单实例用户任务办理人作者规则。
     * @param processKey String，流程标识
     * @param activityId String，任务节点标识
     * @param activityName String，任务名称
     * @param assignmentMode String，ASSIGNEE 或 CANDIDATE
     * @param type String，受控规则类型
     * @param targetIds List&lt;Long&gt;，目标身份
     * @param formField String，表单用户字段或空串
     * @param ruleVersion int，规则版本
     * @param noMatchPolicy String，无匹配策略
     * @param checksum String，规范内容摘要
     */
    public record TaskRule(String processKey, String activityId, String activityName,
            String assignmentMode, String type, List<Long> targetIds, String formField,
            int ruleVersion, String noMatchPolicy, String checksum)
    {
        public TaskRule { targetIds = List.copyOf(targetIds); }
    }
}

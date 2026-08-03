package com.ruoyi.flowable.service.process;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.BigIntegerNode;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.DoubleNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.LongNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 工作流表单提交快照的服务端专用编解码器。
 *
 * 快照以普通字符串变量写入 Flowable，避免反序列化任意对象；所有关联字段和值均在写入和读取时执行双向门禁。
 */
public final class WorkflowFormSubmissionSnapshotCodec
{
    /** 服务端内部变量统一前缀，客户端变量和表单字段不得使用。 */
    public static final String RESERVED_VARIABLE_PREFIX = "__ruoyi_workflow_";

    /** 表单提交快照变量名；开始表单重新提交会产生同实例的新版本，任务表单仍只写入一次。 */
    public static final String VARIABLE_NAME = RESERVED_VARIABLE_PREFIX + "form_submission_v1";

    /** 当前快照结构版本。 */
    private static final int SNAPSHOT_VERSION = 1;

    /** 单份快照编码后的最大 UTF-8 字节数。 */
    private static final int MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024;

    /** 快照值允许的最大递归深度。 */
    private static final int MAX_VALUE_DEPTH = 20;

    /** 单份快照允许的最大 JSON 节点数。 */
    private static final int MAX_VALUE_NODES = 20_000;

    /** 单个 JSON 容器允许的最大成员数。 */
    private static final int MAX_CONTAINER_SIZE = 1_000;

    /** 单个文本值允许的最大 UTF-8 字节数。 */
    private static final int MAX_TEXT_BYTES = 256 * 1024;

    /** 引擎和业务关联主键允许的最大字符数。 */
    private static final int MAX_ID_LENGTH = 255;

    /** 快照根对象允许出现的固定字段。 */
    private static final Set<String> ROOT_FIELDS = Set.of(
            "version", "kind", "deploymentId", "formId", "formKey", "nodeKey",
            "taskId", "taskLocal", "values");

    /** 表单字段名使用与变量校验器一致的稳定 ASCII 标识。 */
    private static final Pattern FIELD_NAME_PATTERN = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]{0,127}");

    /** 拒绝可改变前端对象原型语义的 JSON 键。 */
    private static final Set<String> FORBIDDEN_JSON_KEYS = Set.of(
            "__proto__", "prototype", "constructor");

    /** 严格拒绝重复 JSON 字段和合法根节点后尾随内容的专用解析器。 */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /**
     * 禁止实例化纯静态编解码器。
     *
     * @return 无返回值
     */
    private WorkflowFormSubmissionSnapshotCodec()
    {
    }

    /**
     * 编码开始表单的不可变提交快照。
     *
     * @param deploymentId String，流程定义所属部署主键
     * @param formId Long，部署表单快照来源主键
     * @param formKey String，开始节点 BPMN 表单键
     * @param nodeKey String，开始节点 BPMN 主键
     * @param values Map&lt;String, Object&gt;，附件投影完成后的服务端安全字段值
     * @return String，可作为 Flowable 普通字符串变量持久化的受限 JSON
     */
    public static String encodeStart(String deploymentId, Long formId, String formKey,
            String nodeKey, Map<String, Object> values)
    {
        return encode(SnapshotKind.START, deploymentId, formId, formKey, nodeKey,
                null, false, values);
    }

    /**
     * 编码任务表单的不可变提交快照。
     *
     * @param deploymentId String，任务定义所属部署主键
     * @param formId Long，部署表单快照来源主键
     * @param formKey String，任务节点 BPMN 表单键
     * @param nodeKey String，任务节点 BPMN 主键
     * @param taskId String，本次真实活动任务主键
     * @param taskLocal boolean，业务字段是否按 BPMN localScope 写入任务局部作用域
     * @param values Map&lt;String, Object&gt;，附件投影完成后的服务端安全字段值
     * @return String，可作为任务局部字符串变量持久化的受限 JSON
     */
    public static String encodeTask(String deploymentId, Long formId, String formKey,
            String nodeKey, String taskId, boolean taskLocal, Map<String, Object> values)
    {
        return encode(SnapshotKind.TASK, deploymentId, formId, formKey, nodeKey,
                taskId, taskLocal, values);
    }

    /**
     * 解码并严格校验 Flowable 历史变量中的提交快照。
     *
     * @param encoded String，历史变量保存的快照 JSON
     * @return SubmissionSnapshot，关联字段和值均已校验并深复制的不可变快照
     */
    public static SubmissionSnapshot decode(String encoded)
    {
        requireSnapshotSize(encoded);
        final JsonNode parsed;
        try
        {
            parsed = MAPPER.readTree(encoded);
        }
        catch (JacksonException exception)
        {
            throw dataError("工作流表单提交快照正文损坏", exception);
        }
        if (!(parsed instanceof ObjectNode root) || root.size() != ROOT_FIELDS.size())
        {
            throw dataError("工作流表单提交快照结构异常");
        }
        Iterator<String> fieldNames = root.propertyNames().iterator();
        while (fieldNames.hasNext())
        {
            if (!ROOT_FIELDS.contains(fieldNames.next()))
            {
                throw dataError("工作流表单提交快照包含未知字段");
            }
        }
        if (!root.path("version").isIntegralNumber()
                || root.path("version").intValue() != SNAPSHOT_VERSION)
        {
            throw dataError("工作流表单提交快照版本不受支持");
        }
        SnapshotKind kind = parseKind(root.get("kind"));
        String deploymentId = requiredText(root.get("deploymentId"), "部署主键");
        Long formId = requiredPositiveLong(root.get("formId"), "表单主键");
        String formKey = requiredText(root.get("formKey"), "表单键");
        String nodeKey = requiredText(root.get("nodeKey"), "节点主键");
        String taskId = optionalText(root.get("taskId"), "任务主键");
        JsonNode taskLocalNode = root.get("taskLocal");
        if (taskLocalNode == null || !taskLocalNode.isBoolean())
        {
            throw dataError("工作流表单提交快照作用域异常");
        }
        boolean taskLocal = taskLocalNode.booleanValue();
        if ((kind == SnapshotKind.START && (taskId != null || taskLocal))
                || (kind == SnapshotKind.TASK && taskId == null))
        {
            throw dataError("工作流表单提交快照任务关联异常");
        }
        Map<String, JsonNode> values = decodeValues(root.get("values"));
        return new SubmissionSnapshot(kind, deploymentId, formId, formKey, nodeKey,
                taskId, taskLocal, values);
    }

    /**
     * 判断变量名是否属于服务端内部保留命名空间。
     *
     * @param variableName String，客户端或表单 schema 声明的变量名
     * @return boolean，命中工作流内部前缀时返回 true
     */
    public static boolean isReservedVariableName(String variableName)
    {
        return variableName != null && variableName.startsWith(RESERVED_VARIABLE_PREFIX);
    }

    /**
     * 按固定结构编码一份不可变提交快照。
     *
     * @param kind SnapshotKind，开始或任务提交类型
     * @param deploymentId String，部署主键
     * @param formId Long，部署表单来源主键
     * @param formKey String，BPMN 表单键
     * @param nodeKey String，BPMN 节点主键
     * @param taskId String，任务提交时的真实任务主键；开始提交为空
     * @param taskLocal boolean，业务字段是否使用任务局部作用域
     * @param values Map&lt;String, Object&gt;，已经过业务 schema 验证的字段值
     * @return String，字段固定且资源受限的快照 JSON
     */
    private static String encode(SnapshotKind kind, String deploymentId, Long formId,
            String formKey, String nodeKey, String taskId, boolean taskLocal,
            Map<String, Object> values)
    {
        Objects.requireNonNull(kind, "提交快照类型不能为空");
        String safeDeploymentId = requiredText(deploymentId, "部署主键");
        Long safeFormId = requiredPositiveLong(formId, "表单主键");
        String safeFormKey = requiredText(formKey, "表单键");
        String safeNodeKey = requiredText(nodeKey, "节点主键");
        String safeTaskId = taskId == null ? null : requiredText(taskId, "任务主键");
        if ((kind == SnapshotKind.START && (safeTaskId != null || taskLocal))
                || (kind == SnapshotKind.TASK && safeTaskId == null))
        {
            throw dataError("工作流表单提交快照任务关联异常");
        }

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("version", SNAPSHOT_VERSION);
        root.put("kind", kind.name());
        root.put("deploymentId", safeDeploymentId);
        root.put("formId", safeFormId);
        root.put("formKey", safeFormKey);
        root.put("nodeKey", safeNodeKey);
        if (safeTaskId == null)
        {
            root.putNull("taskId");
        }
        else
        {
            root.put("taskId", safeTaskId);
        }
        root.put("taskLocal", taskLocal);
        root.set("values", encodeValues(values));
        try
        {
            String encoded = MAPPER.writeValueAsString(root);
            requireSnapshotSize(encoded);
            return encoded;
        }
        catch (JacksonException exception)
        {
            throw dataError("工作流表单提交快照编码失败", exception);
        }
    }

    /**
     * 把服务端安全变量映射转换为受限 JSON 对象。
     *
     * @param values Map&lt;String, Object&gt;，待固化的字段值；允许为空映射
     * @return ObjectNode，字段名和值均通过安全门禁的 JSON 对象
     */
    private static ObjectNode encodeValues(Map<String, Object> values)
    {
        Map<String, Object> source = values == null ? Map.of() : values;
        if (source.size() > MAX_CONTAINER_SIZE)
        {
            throw dataError("工作流表单提交快照字段过多");
        }
        ObjectNode encoded = JsonNodeFactory.instance.objectNode();
        NodeCounter counter = new NodeCounter();
        for (Map.Entry<String, Object> entry : source.entrySet())
        {
            String fieldName = requireFieldName(entry.getKey());
            encoded.set(fieldName, toSafeNode(entry.getValue(), 1, counter));
        }
        return encoded;
    }

    /**
     * 从快照根对象读取并深复制安全字段值。
     *
     * @param valuesNode JsonNode，快照 values 字段
     * @return Map&lt;String, JsonNode&gt;，按写入顺序保存的不可变字段值
     */
    private static Map<String, JsonNode> decodeValues(JsonNode valuesNode)
    {
        if (!(valuesNode instanceof ObjectNode values) || values.size() > MAX_CONTAINER_SIZE)
        {
            throw dataError("工作流表单提交快照字段结构异常");
        }
        LinkedHashMap<String, JsonNode> decoded = new LinkedHashMap<>();
        NodeCounter counter = new NodeCounter();
        Iterator<Map.Entry<String, JsonNode>> fields = values.properties().iterator();
        while (fields.hasNext())
        {
            Map.Entry<String, JsonNode> field = fields.next();
            String fieldName = requireFieldName(field.getKey());
            decoded.put(fieldName, sanitizeNode(field.getValue(), 1, counter));
        }
        return Collections.unmodifiableMap(decoded);
    }

    /**
     * 将经过业务校验的 Java 值转换为受限 JSON 节点。
     *
     * @param value Object，标量、时间、JsonNode、Map 或 Collection
     * @param depth int，当前递归深度
     * @param counter NodeCounter，单份快照共享的节点计数器
     * @return JsonNode，不包含任意对象类型信息的安全 JSON 节点
     */
    private static JsonNode toSafeNode(Object value, int depth, NodeCounter counter)
    {
        enterNode(depth, counter);
        if (value == null)
        {
            return NullNode.getInstance();
        }
        if (value instanceof JsonNode node)
        {
            return sanitizeNodeAfterEnter(node, depth, counter);
        }
        if (value instanceof CharSequence text)
        {
            requireTextSize(text.toString());
            return StringNode.valueOf(text.toString());
        }
        if (value instanceof Boolean bool)
        {
            return BooleanNode.valueOf(bool);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long)
        {
            return LongNode.valueOf(((Number) value).longValue());
        }
        if (value instanceof BigInteger integer)
        {
            return BigIntegerNode.valueOf(integer);
        }
        if (value instanceof BigDecimal decimal)
        {
            return DecimalNode.valueOf(decimal);
        }
        if (value instanceof Float || value instanceof Double)
        {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number))
            {
                throw dataError("工作流表单提交快照包含非有限数值");
            }
            return DoubleNode.valueOf(number);
        }
        if (value instanceof Date date)
        {
            return StringNode.valueOf(date.toInstant().toString());
        }
        if (value instanceof Instant || value instanceof LocalDate
                || value instanceof LocalDateTime || value instanceof OffsetDateTime
                || value instanceof ZonedDateTime || value instanceof UUID)
        {
            return StringNode.valueOf(value.toString());
        }
        if (value instanceof Map<?, ?> map)
        {
            if (map.size() > MAX_CONTAINER_SIZE)
            {
                throw dataError("工作流表单提交快照对象成员过多");
            }
            ObjectNode object = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<?, ?> entry : map.entrySet())
            {
                if (!(entry.getKey() instanceof String key))
                {
                    throw dataError("工作流表单提交快照对象字段名异常");
                }
                requireJsonKey(key);
                object.set(key, toSafeNode(entry.getValue(), depth + 1, counter));
            }
            return object;
        }
        if (value instanceof Collection<?> collection)
        {
            if (collection.size() > MAX_CONTAINER_SIZE)
            {
                throw dataError("工作流表单提交快照数组成员过多");
            }
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            for (Object item : collection)
            {
                array.add(toSafeNode(item, depth + 1, counter));
            }
            return array;
        }
        throw dataError("工作流表单提交快照包含不支持的值类型");
    }

    /**
     * 深复制并校验解析后的 JSON 节点。
     *
     * @param node JsonNode，待校验节点
     * @param depth int，当前递归深度
     * @param counter NodeCounter，单份快照共享的节点计数器
     * @return JsonNode，资源受限且与解析树解耦的节点
     */
    private static JsonNode sanitizeNode(JsonNode node, int depth, NodeCounter counter)
    {
        enterNode(depth, counter);
        return sanitizeNodeAfterEnter(node, depth, counter);
    }

    /**
     * 在当前节点已经计数后执行类型与子节点校验。
     *
     * @param node JsonNode，已经进入计数流程的节点
     * @param depth int，当前递归深度
     * @param counter NodeCounter，单份快照共享的节点计数器
     * @return JsonNode，安全深复制节点
     */
    private static JsonNode sanitizeNodeAfterEnter(JsonNode node, int depth, NodeCounter counter)
    {
        if (node == null || node.isNull())
        {
            return NullNode.getInstance();
        }
        if (node.isTextual())
        {
            requireTextSize(node.textValue());
            return StringNode.valueOf(node.textValue());
        }
        if (node.isBoolean())
        {
            return BooleanNode.valueOf(node.booleanValue());
        }
        if (node.isIntegralNumber())
        {
            return node.bigIntegerValue().bitLength() <= 63
                    ? LongNode.valueOf(node.longValue())
                    : BigIntegerNode.valueOf(node.bigIntegerValue());
        }
        if (node.isFloatingPointNumber())
        {
            double number = node.doubleValue();
            if (!Double.isFinite(number))
            {
                throw dataError("工作流表单提交快照包含非有限数值");
            }
            return DecimalNode.valueOf(node.decimalValue());
        }
        if (node.isObject())
        {
            if (node.size() > MAX_CONTAINER_SIZE)
            {
                throw dataError("工作流表单提交快照对象成员过多");
            }
            ObjectNode object = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            while (fields.hasNext())
            {
                Map.Entry<String, JsonNode> field = fields.next();
                requireJsonKey(field.getKey());
                object.set(field.getKey(), sanitizeNode(field.getValue(), depth + 1, counter));
            }
            return object;
        }
        if (node.isArray())
        {
            if (node.size() > MAX_CONTAINER_SIZE)
            {
                throw dataError("工作流表单提交快照数组成员过多");
            }
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : node)
            {
                array.add(sanitizeNode(item, depth + 1, counter));
            }
            return array;
        }
        throw dataError("工作流表单提交快照包含不支持的 JSON 节点");
    }

    /**
     * 校验并累计一个 JSON 节点的深度和总量。
     *
     * @param depth int，当前递归深度
     * @param counter NodeCounter，单份快照共享的节点计数器
     * @return 无返回值，超过上限时抛出数据异常
     */
    private static void enterNode(int depth, NodeCounter counter)
    {
        if (depth > MAX_VALUE_DEPTH)
        {
            throw dataError("工作流表单提交快照值层级过深");
        }
        counter.increment();
        if (counter.value() > MAX_VALUE_NODES)
        {
            throw dataError("工作流表单提交快照值节点过多");
        }
    }

    /**
     * 解析快照类型字段。
     *
     * @param node JsonNode，kind 字段节点
     * @return SnapshotKind，受支持的开始或任务提交类型
     */
    private static SnapshotKind parseKind(JsonNode node)
    {
        if (node == null || !node.isTextual())
        {
            throw dataError("工作流表单提交快照类型异常");
        }
        try
        {
            return SnapshotKind.valueOf(node.textValue());
        }
        catch (IllegalArgumentException exception)
        {
            throw dataError("工作流表单提交快照类型不受支持", exception);
        }
    }

    /**
     * 从 JSON 字段读取非空且长度受控的关联文本。
     *
     * @param node JsonNode，待读取字段节点
     * @param fieldName String，异常提示中的业务字段名称
     * @return String，保持原值且通过长度门禁的文本
     */
    private static String requiredText(JsonNode node, String fieldName)
    {
        if (node == null || !node.isTextual())
        {
            throw dataError("工作流表单提交快照" + fieldName + "异常");
        }
        return requiredText(node.textValue(), fieldName);
    }

    /**
     * 校验非空且长度受控的关联文本。
     *
     * @param value String，待校验文本
     * @param fieldName String，异常提示中的业务字段名称
     * @return String，保持原值且通过门禁的文本
     */
    private static String requiredText(String value, String fieldName)
    {
        if (value == null || value.isBlank() || !value.equals(value.trim())
                || value.length() > MAX_ID_LENGTH)
        {
            throw dataError("工作流表单提交快照" + fieldName + "异常");
        }
        return value;
    }

    /**
     * 从 JSON 字段读取可空关联文本。
     *
     * @param node JsonNode，待读取字段节点
     * @param fieldName String，异常提示中的业务字段名称
     * @return String，字段为 JSON null 时返回 null，否则返回受控文本
     */
    private static String optionalText(JsonNode node, String fieldName)
    {
        if (node == null || node.isNull())
        {
            return null;
        }
        return requiredText(node, fieldName);
    }

    /**
     * 从 JSON 字段读取正数 Long 主键。
     *
     * @param node JsonNode，待读取字段节点
     * @param fieldName String，异常提示中的业务字段名称
     * @return Long，大于零且未溢出的主键
     */
    private static Long requiredPositiveLong(JsonNode node, String fieldName)
    {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()
                || node.longValue() <= 0)
        {
            throw dataError("工作流表单提交快照" + fieldName + "异常");
        }
        return node.longValue();
    }

    /**
     * 校验正数 Long 主键。
     *
     * @param value Long，待校验主键
     * @param fieldName String，异常提示中的业务字段名称
     * @return Long，大于零的原主键
     */
    private static Long requiredPositiveLong(Long value, String fieldName)
    {
        if (value == null || value <= 0)
        {
            throw dataError("工作流表单提交快照" + fieldName + "异常");
        }
        return value;
    }

    /**
     * 校验顶层表单字段名并拒绝服务端保留命名空间。
     *
     * @param fieldName String，表单变量字段名
     * @return String，通过格式和保留前缀门禁的原字段名
     */
    private static String requireFieldName(String fieldName)
    {
        if (fieldName == null || !FIELD_NAME_PATTERN.matcher(fieldName).matches()
                || FORBIDDEN_JSON_KEYS.contains(fieldName.toLowerCase(java.util.Locale.ROOT))
                || isReservedVariableName(fieldName))
        {
            throw dataError("工作流表单提交快照字段名异常");
        }
        return fieldName;
    }

    /**
     * 校验嵌套 JSON 对象键，防止原型污染和资源滥用。
     *
     * @param key String，嵌套对象字段名
     * @return 无返回值，键非法时抛出数据异常
     */
    private static void requireJsonKey(String key)
    {
        if (key == null || key.isBlank() || key.length() > 128
                || FORBIDDEN_JSON_KEYS.contains(key.toLowerCase(java.util.Locale.ROOT)))
        {
            throw dataError("工作流表单提交快照对象字段名异常");
        }
    }

    /**
     * 校验单个文本值的 UTF-8 字节数。
     *
     * @param value String，待写入或回显的文本
     * @return 无返回值，超过上限时抛出数据异常
     */
    private static void requireTextSize(String value)
    {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES)
        {
            throw dataError("工作流表单提交快照文本过长");
        }
    }

    /**
     * 校验完整快照正文的 UTF-8 大小。
     *
     * @param encoded String，完整快照 JSON
     * @return 无返回值，正文为空或超过上限时抛出数据异常
     */
    private static void requireSnapshotSize(String encoded)
    {
        if (encoded == null || encoded.isBlank()
                || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES)
        {
            throw dataError("工作流表单提交快照正文大小异常");
        }
    }

    /**
     * 创建稳定的提交快照数据异常。
     *
     * @param message String，面向服务端日志和调用方的稳定中文提示
     * @return ServiceException，HTTP 500 数据一致性异常
     */
    private static ServiceException dataError(String message)
    {
        return new ServiceException(message, HttpStatus.ERROR);
    }

    /**
     * 创建带原始原因的提交快照数据异常。
     *
     * @param message String，稳定中文提示
     * @param cause Throwable，底层解析或编码异常
     * @return ServiceException，保留原因链的 HTTP 500 数据一致性异常
     */
    private static ServiceException dataError(String message, Throwable cause)
    {
        ServiceException exception = dataError(message);
        exception.initCause(cause);
        return exception;
    }

    /** 提交快照业务类型。 */
    public enum SnapshotKind
    {
        /** 流程开始表单提交。 */
        START,

        /** 用户任务表单提交。 */
        TASK
    }

    /**
     * 已通过结构、关联和资源门禁的不可变提交快照。
     *
     * @param kind SnapshotKind，开始或任务提交类型
     * @param deploymentId String，流程定义部署主键
     * @param formId Long，部署表单来源主键
     * @param formKey String，BPMN 表单键
     * @param nodeKey String，BPMN 节点主键
     * @param taskId String，任务提交主键；开始提交为空
     * @param taskLocal boolean，业务字段是否使用任务局部作用域
     * @param values Map&lt;String, JsonNode&gt;，提交当时的安全字段值
     */
    public record SubmissionSnapshot(SnapshotKind kind, String deploymentId, Long formId,
            String formKey, String nodeKey, String taskId, boolean taskLocal,
            Map<String, JsonNode> values)
    {
        /**
         * 深复制提交值，阻止调用方修改历史快照。
         *
         * @param kind SnapshotKind，提交类型
         * @param deploymentId String，部署主键
         * @param formId Long，表单主键
         * @param formKey String，表单键
         * @param nodeKey String，节点主键
         * @param taskId String，任务主键；开始提交为空
         * @param taskLocal boolean，业务字段是否使用任务局部作用域
         * @param values Map&lt;String, JsonNode&gt;，已校验字段值
         * @return 无返回值，构造完成后 values 不可修改
         */
        public SubmissionSnapshot
        {
            Objects.requireNonNull(kind, "提交快照类型不能为空");
            Objects.requireNonNull(values, "提交快照字段值不能为空");
            LinkedHashMap<String, JsonNode> copied = new LinkedHashMap<>();
            values.forEach((key, value) -> copied.put(key,
                    value == null ? NullNode.getInstance() : value.deepCopy()));
            values = Collections.unmodifiableMap(copied);
        }

        /**
         * 返回字段值的防御性深复制，避免调用方通过可变 JsonNode 修改已解码的历史快照。
         *
         * @return Map&lt;String, JsonNode&gt;，键集合和所有节点均与内部状态解耦的不可变映射
         */
        @Override
        public Map<String, JsonNode> values()
        {
            LinkedHashMap<String, JsonNode> copied = new LinkedHashMap<>();
            values.forEach((key, value) -> copied.put(key,
                    value == null ? NullNode.getInstance() : value.deepCopy()));
            return Collections.unmodifiableMap(copied);
        }
    }

    /** 单份提交快照的 JSON 节点计数器。 */
    private static final class NodeCounter
    {
        /** 已进入的节点数量。 */
        private int value;

        /**
         * 累加一个已进入节点。
         *
         * @return 无返回值
         */
        private void increment()
        {
            value++;
        }

        /**
         * 读取当前节点数量。
         *
         * @return int，已进入节点总数
         */
        private int value()
        {
            return value;
        }
    }
}

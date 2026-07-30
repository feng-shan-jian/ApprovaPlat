package com.ruoyi.flowable.service.process;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceVariables;

/**
 * 根据不可变开始表单快照校验并规范化客户端流程变量。
 */
@Component
public class WorkflowStartVariableValidator
{
    /** 客户端流程变量序列化后的最大字节数。 */
    static final int MAX_VARIABLE_BYTES = 1024 * 1024;

    /** 单次发起允许提交的最大表单字段数。 */
    static final int MAX_VARIABLE_FIELDS = 500;

    /** 任意集合允许的最大元素数，表单配置只能进一步收紧。 */
    static final int MAX_COLLECTION_SIZE = 100;

    /** 任意嵌套对象允许的最大字段数。 */
    static final int MAX_OBJECT_FIELDS = 50;

    /** 客户端变量树允许的最大节点数。 */
    static final int MAX_VALUE_NODES = 10_000;

    /** 客户端变量树允许的最大嵌套深度。 */
    static final int MAX_VALUE_DEPTH = 20;

    /** 单个普通文本允许的绝对最大字符数。 */
    static final int MAX_STRING_LENGTH = 65_535;

    private static final int DEFAULT_TEXT_LENGTH = 4_096;
    private static final int DEFAULT_TEMPORAL_LENGTH = 128;
    private static final int MAX_FIELD_NAME_LENGTH = 128;

    private static final String CONFIG_FIELD = "__config__";
    private static final String CHILDREN_FIELD = "children";
    private static final String MODEL_FIELD = "__vModel__";
    private static final String TAG_FIELD = "tag";
    private static final String REQUIRED_FIELD = "required";

    /** 流程引擎和服务端状态维护使用的变量，客户端及表单 schema 均不得声明。 */
    private static final Set<String> RESERVED_VARIABLES = Set.of(
            "initiator", "processStatus", "processInstanceId", "processDefinitionId",
            "deploymentId", "startUserId", "authenticatedUserId", "businessKey",
            "assignee", "nrOfInstances", "nrOfActiveInstances",
            "nrOfCompletedInstances", "loopCounter", "_FLOWABLE_SKIP_EXPRESSION_ENABLED");

    /** 顶层变量和嵌套 JSON 对象都拒绝可能影响前端对象原型的键。 */
    private static final Set<String> PROTOTYPE_POLLUTION_KEYS = Set.of(
            "__proto__", "prototype", "constructor");

    /** Flowable 变量名使用稳定 ASCII 标识，避免表达式路径和日志字段产生歧义。 */
    private static final Pattern FIELD_NAME_PATTERN = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]{0," + (MAX_FIELD_NAME_LENGTH - 1) + "}");

    /** 上传字段只接受服务端生成的规范 UUID，路径、URL 和文件对象均不能进入白名单。 */
    private static final Pattern ATTACHMENT_ID_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    private final WorkflowFormTemplateValidator templateValidator;
    private final ObjectMapper objectMapper;

    /**
     * 创建开始表单变量验证器。
     *
     * @param templateValidator WorkflowFormTemplateValidator，部署表单快照结构与安全验证器
     * @return 无返回值，构造后由 Spring 管理该组件
     */
    public WorkflowStartVariableValidator(WorkflowFormTemplateValidator templateValidator)
    {
        this.templateValidator = templateValidator;
        this.objectMapper = JsonMapper.shared();
    }

    /**
     * 按部署时固化的开始表单 schema 校验字段、必填、类型和资源边界。
     *
     * @param snapshotContent String，来自 wf_deploy_form.content 的不可变 JSON 快照
     * @param variables Map&lt;String, Object&gt;，客户端提交的开始表单变量，允许为空
     * @return Map&lt;String, Object&gt;，深度复制且不可修改的受控变量映射
     */
    public Map<String, Object> validateAndNormalize(String snapshotContent,
            Map<String, Object> variables)
    {
        return validateForStart(snapshotContent, variables).variables();
    }

    /**
     * 按部署表单 schema 校验变量，并单独提取 el-upload 中的临时附件 UUID 白名单。
     *
     * @param snapshotContent String，来自 wf_deploy_form.content 的不可变 JSON 快照
     * @param variables Map&lt;String, Object&gt;，客户端提交的开始表单变量，允许为空
     * @return WorkflowValidatedStartVariables，规范变量及按字段分组的附件 UUID
     */
    public WorkflowValidatedStartVariables validateForStart(String snapshotContent,
            Map<String, Object> variables)
    {
        JsonNode snapshotRoot = parseTrustedSnapshot(snapshotContent);
        LinkedHashMap<String, FieldSpec> fieldSpecs = new LinkedHashMap<>();
        collectFieldSpecs(snapshotRoot.path("fields"), fieldSpecs);

        Map<String, Object> source = variables == null ? Map.of() : variables;
        if (source.size() > MAX_VARIABLE_FIELDS)
        {
            throw invalidVariable("流程变量字段数量不能超过" + MAX_VARIABLE_FIELDS);
        }

        ValueBudget budget = new ValueBudget();
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        LinkedHashMap<String, List<String>> attachmentIdsByField = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet())
        {
            String fieldName = entry.getKey();
            if (fieldName == null || !FIELD_NAME_PATTERN.matcher(fieldName).matches())
            {
                throw invalidVariable("流程变量字段名不合法");
            }
            if (PROTOTYPE_POLLUTION_KEYS.contains(fieldName.toLowerCase(Locale.ROOT)))
            {
                throw invalidVariable("流程变量字段名不合法");
            }
            if (isReservedVariable(fieldName))
            {
                throw invalidVariable("客户端不能覆盖服务端保留流程变量");
            }
            FieldSpec fieldSpec = fieldSpecs.get(fieldName);
            if (fieldSpec == null)
            {
                throw invalidVariable("流程变量字段不在开始表单中: " + fieldName);
            }

            Object normalizedValue = normalizeJsonValue(entry.getValue(), 1, budget);
            if (fieldSpec.type() == FieldType.UPLOAD_LIST && normalizedValue != null)
            {
                List<String> attachmentIds = requireAttachmentIdList(fieldSpec, normalizedValue);
                normalizedValue = attachmentIds;
                attachmentIdsByField.put(fieldName, attachmentIds);
            }
            else
            {
                validateFieldValue(fieldSpec, normalizedValue);
            }
            normalized.put(fieldName, normalizedValue);
        }

        // 必填校验必须基于 containsKey，避免把未提交字段和显式 null 混为同一数据流。
        for (FieldSpec fieldSpec : fieldSpecs.values())
        {
            if (fieldSpec.required()
                    && (!normalized.containsKey(fieldSpec.name())
                    || isEmptyValue(normalized.get(fieldSpec.name()))))
            {
                throw invalidVariable("开始表单必填字段不能为空: " + fieldSpec.name());
            }
        }

        assertSerializedSize(normalized);
        return new WorkflowValidatedStartVariables(normalized, attachmentIdsByField);
    }

    /**
     * 重新校验并解析数据库中的部署表单快照，将持久化损坏归为服务端数据异常。
     *
     * @param snapshotContent String，数据库读取的部署表单 JSON
     * @return JsonNode，已经通过模板门禁的表单根对象
     */
    private JsonNode parseTrustedSnapshot(String snapshotContent)
    {
        try
        {
            templateValidator.validate(snapshotContent);
            return objectMapper.readTree(snapshotContent);
        }
        catch (ServiceException | JacksonException exception)
        {
            throw invalidSnapshot(exception);
        }
    }

    /**
     * 递归提取有 __vModel__ 的受控输入组件，并拒绝重复、保留或异常字段定义。
     *
     * @param components JsonNode，当前层 fields 或 children 数组
     * @param fieldSpecs Map&lt;String, FieldSpec&gt;，按表单顺序累积的字段 schema
     * @return void，快照字段 schema 异常时抛出服务端数据异常
     */
    private void collectFieldSpecs(JsonNode components, Map<String, FieldSpec> fieldSpecs)
    {
        for (JsonNode component : components)
        {
            JsonNode config = component.path(CONFIG_FIELD);
            JsonNode modelNode = component.get(MODEL_FIELD);
            if (modelNode != null && !modelNode.isNull())
            {
                if (!modelNode.isTextual())
                {
                    throw invalidSnapshot(null);
                }
                String fieldName = modelNode.textValue();
                if (!FIELD_NAME_PATTERN.matcher(fieldName).matches()
                        || PROTOTYPE_POLLUTION_KEYS.contains(fieldName.toLowerCase(Locale.ROOT))
                        || isReservedVariable(fieldName))
                {
                    throw invalidSnapshot(null);
                }
                FieldSpec fieldSpec = createFieldSpec(fieldName, component, config);
                if (fieldSpecs.putIfAbsent(fieldName, fieldSpec) != null)
                {
                    throw invalidSnapshot(null);
                }
                if (fieldSpecs.size() > MAX_VARIABLE_FIELDS)
                {
                    throw invalidSnapshot(null);
                }
            }

            JsonNode children = config.get(CHILDREN_FIELD);
            if (children != null && children.isArray() && !children.isEmpty())
            {
                collectFieldSpecs(children, fieldSpecs);
            }
        }
    }

    /**
     * 判断字段名是否属于引擎固定变量或工作流内部快照命名空间。
     *
     * @param fieldName String，客户端变量或部署表单 schema 字段名
     * @return boolean，客户端不得声明或覆盖时返回 true
     */
    private boolean isReservedVariable(String fieldName)
    {
        return RESERVED_VARIABLES.contains(fieldName)
                || WorkflowMultiInstanceVariables.isReservedVariableName(fieldName)
                || WorkflowFormSubmissionSnapshotCodec.isReservedVariableName(fieldName);
    }

    /**
     * 将表单组件属性转换为服务端可执行字段 schema，并应用绝对资源上限。
     *
     * @param fieldName String，已校验的变量字段名
     * @param component JsonNode，完整表单组件节点
     * @param config JsonNode，组件 __config__ 对象
     * @return FieldSpec，字段类型、必填和长度/数量约束
     */
    private FieldSpec createFieldSpec(String fieldName, JsonNode component, JsonNode config)
    {
        JsonNode tagNode = config.get(TAG_FIELD);
        if (tagNode == null || !tagNode.isTextual())
        {
            throw invalidSnapshot(null);
        }
        String tag = tagNode.textValue();
        boolean required = optionalBoolean(config, REQUIRED_FIELD, false);
        FieldType fieldType;
        int defaultMaxLength = DEFAULT_TEXT_LENGTH;
        int minItems = 0;
        int maxItems = MAX_COLLECTION_SIZE;
        BigDecimal minimum = null;
        BigDecimal maximum = null;

        switch (tag)
        {
            case "el-input" -> fieldType = FieldType.TEXT;
            case "tinymce" ->
            {
                fieldType = FieldType.TEXT;
                defaultMaxLength = MAX_STRING_LENGTH;
            }
            case "el-color-picker" ->
            {
                fieldType = FieldType.TEXT;
                defaultMaxLength = DEFAULT_TEMPORAL_LENGTH;
            }
            case "el-input-number", "el-rate" ->
            {
                fieldType = FieldType.NUMBER;
                minimum = optionalDecimal(component, "min");
                maximum = optionalDecimal(component, "max");
            }
            case "el-slider" ->
            {
                fieldType = optionalBoolean(component, "range", false)
                        ? FieldType.NUMBER_RANGE : FieldType.NUMBER;
                minimum = optionalDecimal(component, "min");
                maximum = optionalDecimal(component, "max");
                if (fieldType == FieldType.NUMBER_RANGE)
                {
                    minItems = 2;
                    maxItems = 2;
                }
            }
            case "el-switch" -> fieldType = FieldType.BOOLEAN;
            case "el-radio-group" -> fieldType = FieldType.SCALAR;
            case "el-select" ->
            {
                if (optionalBoolean(component, "multiple", false))
                {
                    fieldType = FieldType.SCALAR_LIST;
                    maxItems = configuredCollectionLimit(component, "multiple-limit");
                }
                else
                {
                    fieldType = FieldType.SCALAR;
                }
            }
            case "el-checkbox-group" ->
            {
                fieldType = FieldType.SCALAR_LIST;
                minItems = configuredMinimum(component, "min");
                maxItems = configuredCollectionLimit(component, "max");
            }
            case "el-cascader" -> fieldType = FieldType.CASCADER;
            case "el-time-picker", "el-date-picker" ->
            {
                if (isTemporalRange(component))
                {
                    fieldType = FieldType.TEMPORAL_RANGE;
                    minItems = 2;
                    maxItems = 2;
                }
                else
                {
                    fieldType = FieldType.TEXT;
                }
                defaultMaxLength = DEFAULT_TEMPORAL_LENGTH;
            }
            case "el-upload" ->
            {
                fieldType = FieldType.UPLOAD_LIST;
                maxItems = configuredCollectionLimit(component, "limit");
            }
            case "el-table" -> fieldType = FieldType.OBJECT_LIST;
            default -> throw invalidSnapshot(null);
        }

        int minLength = configuredStringLength(component, "minlength", 0);
        int maxLength = configuredStringLength(component, "maxlength", defaultMaxLength);
        if (minLength > maxLength || minItems > maxItems
                || (minimum != null && maximum != null && minimum.compareTo(maximum) > 0))
        {
            throw invalidSnapshot(null);
        }
        return new FieldSpec(fieldName, fieldType, required, minLength, maxLength,
                minItems, maxItems, minimum, maximum);
    }

    /**
     * 深度复制一个仅由 JSON 标量、集合和字符串键对象组成的变量值。
     *
     * @param value Object，客户端变量值
     * @param depth int，当前变量树深度
     * @param budget ValueBudget，整个请求共享的节点计数器
     * @return Object，不可变集合/对象或原生受控标量
     */
    private Object normalizeJsonValue(Object value, int depth, ValueBudget budget)
    {
        if (depth > MAX_VALUE_DEPTH)
        {
            throw invalidVariable("流程变量嵌套深度不能超过" + MAX_VALUE_DEPTH);
        }
        budget.increment();
        if (budget.value() > MAX_VALUE_NODES)
        {
            throw invalidVariable("流程变量节点数量不能超过" + MAX_VALUE_NODES);
        }
        if (value == null || value instanceof Boolean)
        {
            return value;
        }
        if (value instanceof String text)
        {
            if (text.length() > MAX_STRING_LENGTH)
            {
                throw invalidVariable("流程变量文本长度超过限制");
            }
            return text;
        }
        if (isSupportedNumber(value))
        {
            assertFiniteNumber((Number) value);
            return value;
        }
        if (value instanceof Collection<?> collection)
        {
            if (collection.size() > MAX_COLLECTION_SIZE)
            {
                throw invalidVariable("流程变量集合元素不能超过" + MAX_COLLECTION_SIZE);
            }
            List<Object> normalizedItems = new ArrayList<>(collection.size());
            for (Object item : collection)
            {
                normalizedItems.add(normalizeJsonValue(item, depth + 1, budget));
            }
            return Collections.unmodifiableList(normalizedItems);
        }
        if (value instanceof Map<?, ?> map)
        {
            if (map.size() > MAX_OBJECT_FIELDS)
            {
                throw invalidVariable("流程变量对象字段不能超过" + MAX_OBJECT_FIELDS);
            }
            LinkedHashMap<String, Object> normalizedObject = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet())
            {
                if (!(entry.getKey() instanceof String key)
                        || key.isBlank() || key.length() > MAX_FIELD_NAME_LENGTH
                        || PROTOTYPE_POLLUTION_KEYS.contains(key.toLowerCase(Locale.ROOT)))
                {
                    throw invalidVariable("流程变量对象字段名不合法");
                }
                normalizedObject.put(key,
                        normalizeJsonValue(entry.getValue(), depth + 1, budget));
            }
            return Collections.unmodifiableMap(normalizedObject);
        }
        throw invalidVariable("流程变量包含不支持的数据类型");
    }

    /**
     * 按字段 schema 校验顶层变量类型、长度、集合规模和数值范围。
     *
     * @param fieldSpec FieldSpec，部署快照提取出的字段规则
     * @param value Object，已经深度规范化的客户端变量值
     * @return void，字段值不满足 schema 时抛出 400 业务异常
     */
    private void validateFieldValue(FieldSpec fieldSpec, Object value)
    {
        if (value == null)
        {
            return;
        }
        switch (fieldSpec.type())
        {
            case TEXT -> requireTextValue(fieldSpec, value);
            case NUMBER -> requireNumberValue(fieldSpec, value);
            case BOOLEAN ->
            {
                if (!(value instanceof Boolean))
                {
                    throw invalidType(fieldSpec);
                }
            }
            case SCALAR -> requireScalarValue(fieldSpec, value);
            case SCALAR_LIST -> requireScalarList(fieldSpec, value, false);
            case CASCADER -> requireScalarList(fieldSpec, value, true);
            case NUMBER_RANGE -> requireNumberRange(fieldSpec, value);
            case TEMPORAL_RANGE -> requireTemporalRange(fieldSpec, value);
            case UPLOAD_LIST -> requireAttachmentIdList(fieldSpec, value);
            case OBJECT_LIST -> requireObjectList(fieldSpec, value);
        }
    }

    /**
     * 校验文本字段及 schema 长度范围。
     *
     * @param fieldSpec FieldSpec，文本字段规则
     * @param value Object，待校验值
     * @return void，类型或长度不合法时抛出 400 业务异常
     */
    private void requireTextValue(FieldSpec fieldSpec, Object value)
    {
        if (!(value instanceof String text))
        {
            throw invalidType(fieldSpec);
        }
        if (text.length() < fieldSpec.minLength() || text.length() > fieldSpec.maxLength())
        {
            throw invalidVariable("流程变量文本长度不合法: " + fieldSpec.name());
        }
    }

    /**
     * 校验数值字段及 schema 数值范围。
     *
     * @param fieldSpec FieldSpec，数值字段规则
     * @param value Object，待校验值
     * @return void，类型或数值范围不合法时抛出 400 业务异常
     */
    private void requireNumberValue(FieldSpec fieldSpec, Object value)
    {
        if (!(value instanceof Number number) || !isSupportedNumber(number))
        {
            throw invalidType(fieldSpec);
        }
        BigDecimal decimal = decimalValue(number);
        if ((fieldSpec.minimum() != null && decimal.compareTo(fieldSpec.minimum()) < 0)
                || (fieldSpec.maximum() != null && decimal.compareTo(fieldSpec.maximum()) > 0))
        {
            throw invalidVariable("流程变量数值范围不合法: " + fieldSpec.name());
        }
    }

    /**
     * 校验单选类字段为字符串、数值或布尔值。
     *
     * @param fieldSpec FieldSpec，单值字段规则
     * @param value Object，待校验值
     * @return void，类型或文本长度不合法时抛出 400 业务异常
     */
    private void requireScalarValue(FieldSpec fieldSpec, Object value)
    {
        if (value instanceof String)
        {
            requireTextValue(fieldSpec, value);
            return;
        }
        if (value instanceof Number number && isSupportedNumber(number))
        {
            assertFiniteNumber(number);
            return;
        }
        if (!(value instanceof Boolean))
        {
            throw invalidType(fieldSpec);
        }
    }

    /**
     * 校验多选或级联字段集合，级联字段可包含受限的嵌套标量数组。
     *
     * @param fieldSpec FieldSpec，集合字段规则
     * @param value Object，待校验值
     * @param allowNested boolean，是否允许嵌套标量数组
     * @return void，类型、集合规模或元素类型不合法时抛出 400 业务异常
     */
    private void requireScalarList(FieldSpec fieldSpec, Object value, boolean allowNested)
    {
        List<?> values = requireList(fieldSpec, value);
        requireCollectionSize(fieldSpec, values);
        for (Object item : values)
        {
            requireScalarListItem(fieldSpec, item, allowNested, 1);
        }
    }

    /**
     * 递归校验多选集合元素，级联路径最多允许三层数组。
     *
     * @param fieldSpec FieldSpec，集合字段规则
     * @param item Object，当前集合元素
     * @param allowNested boolean，是否允许嵌套数组
     * @param depth int，当前集合嵌套深度
     * @return void，元素类型或嵌套深度不合法时抛出 400 业务异常
     */
    private void requireScalarListItem(FieldSpec fieldSpec, Object item,
            boolean allowNested, int depth)
    {
        if (item instanceof String || item instanceof Boolean
                || (item instanceof Number && isSupportedNumber(item)))
        {
            if (item instanceof String text && text.length() > fieldSpec.maxLength())
            {
                throw invalidVariable("流程变量文本长度不合法: " + fieldSpec.name());
            }
            if (item instanceof Number number)
            {
                assertFiniteNumber(number);
            }
            return;
        }
        if (allowNested && item instanceof List<?> nested && depth < 3)
        {
            for (Object nestedItem : nested)
            {
                requireScalarListItem(fieldSpec, nestedItem, true, depth + 1);
            }
            return;
        }
        throw invalidType(fieldSpec);
    }

    /**
     * 校验滑块范围为两个满足字段数值边界的数字。
     *
     * @param fieldSpec FieldSpec，滑块范围规则
     * @param value Object，待校验值
     * @return void，类型、元素数量或数值范围不合法时抛出 400 业务异常
     */
    private void requireNumberRange(FieldSpec fieldSpec, Object value)
    {
        List<?> values = requireList(fieldSpec, value);
        requireCollectionSize(fieldSpec, values);
        for (Object item : values)
        {
            requireNumberValue(fieldSpec, item);
        }
    }

    /**
     * 校验日期或时间范围为两个长度受控的字符串。
     *
     * @param fieldSpec FieldSpec，日期时间范围规则
     * @param value Object，待校验值
     * @return void，类型、元素数量或文本长度不合法时抛出 400 业务异常
     */
    private void requireTemporalRange(FieldSpec fieldSpec, Object value)
    {
        List<?> values = requireList(fieldSpec, value);
        requireCollectionSize(fieldSpec, values);
        for (Object item : values)
        {
            requireTextValue(fieldSpec, item);
        }
    }

    /**
     * 将上传字段规范化为唯一附件 UUID 列表，拒绝 URL、路径、文件对象和重复引用。
     *
     * @param fieldSpec FieldSpec，上传字段数量规则
     * @param value Object，深度复制后的客户端上传字段值
     * @return List&lt;String&gt;，规范小写且不可修改的附件 UUID 列表
     */
    private List<String> requireAttachmentIdList(FieldSpec fieldSpec, Object value)
    {
        List<?> values = requireList(fieldSpec, value);
        requireCollectionSize(fieldSpec, values);
        LinkedHashSet<String> attachmentIds = new LinkedHashSet<>();
        for (Object item : values)
        {
            if (!(item instanceof String rawAttachmentId))
            {
                throw invalidVariable("上传字段只能提交附件标识: " + fieldSpec.name());
            }
            String attachmentId = rawAttachmentId.trim().toLowerCase(Locale.ROOT);
            if (!ATTACHMENT_ID_PATTERN.matcher(attachmentId).matches())
            {
                throw invalidVariable("上传字段包含非法附件标识: " + fieldSpec.name());
            }
            if (!attachmentIds.add(attachmentId))
            {
                throw invalidVariable("上传字段不能重复引用同一附件: " + fieldSpec.name());
            }
        }
        return List.copyOf(attachmentIds);
    }

    /**
     * 校验表格字段为对象数组，禁止客户端使用标量冒充结构化数据。
     *
     * @param fieldSpec FieldSpec，对象数组字段规则
     * @param value Object，待校验值
     * @return void，类型、集合规模或元素类型不合法时抛出 400 业务异常
     */
    private void requireObjectList(FieldSpec fieldSpec, Object value)
    {
        List<?> values = requireList(fieldSpec, value);
        requireCollectionSize(fieldSpec, values);
        for (Object item : values)
        {
            if (!(item instanceof Map<?, ?>))
            {
                throw invalidType(fieldSpec);
            }
        }
    }

    /**
     * 将字段值断言为规范化后的不可变 List。
     *
     * @param fieldSpec FieldSpec，集合字段规则
     * @param value Object，待断言值
     * @return List&lt;?&gt;，规范化后的集合
     */
    private List<?> requireList(FieldSpec fieldSpec, Object value)
    {
        if (!(value instanceof List<?> values))
        {
            throw invalidType(fieldSpec);
        }
        return values;
    }

    /**
     * 校验字段集合大小同时满足 schema 和服务端硬上限。
     *
     * @param fieldSpec FieldSpec，集合字段规则
     * @param values List&lt;?&gt;，规范化后的字段集合
     * @return void，集合大小不合法时抛出 400 业务异常
     */
    private void requireCollectionSize(FieldSpec fieldSpec, List<?> values)
    {
        if (values.size() < fieldSpec.minItems() || values.size() > fieldSpec.maxItems())
        {
            throw invalidVariable("流程变量集合大小不合法: " + fieldSpec.name());
        }
    }

    /**
     * 使用 JSON 序列化结果校验整个客户端变量负载大小。
     *
     * @param normalized Map&lt;String, Object&gt;，已经过类型和资源校验的变量
     * @return void，序列化失败按服务端错误处理，超限按 400 处理
     */
    private void assertSerializedSize(Map<String, Object> normalized)
    {
        try
        {
            if (objectMapper.writeValueAsBytes(normalized).length > MAX_VARIABLE_BYTES)
            {
                throw invalidVariable("流程变量总大小不能超过1 MiB");
            }
        }
        catch (JacksonException exception)
        {
            ServiceException failure = new ServiceException("流程变量序列化失败", HttpStatus.ERROR);
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 判断必填字段值是否为空。
     *
     * @param value Object，待判断的规范化变量值
     * @return boolean，null、空白文本、空集合或空对象返回 true
     */
    private boolean isEmptyValue(Object value)
    {
        return value == null
                || (value instanceof String text && text.isBlank())
                || (value instanceof Collection<?> collection && collection.isEmpty())
                || (value instanceof Map<?, ?> map && map.isEmpty());
    }

    /**
     * 判断日期/时间组件是否配置为范围值。
     *
     * @param component JsonNode，日期或时间组件
     * @return boolean，type 包含 range 或 is-range 为 true 时返回 true
     */
    private boolean isTemporalRange(JsonNode component)
    {
        if (optionalBoolean(component, "is-range", false))
        {
            return true;
        }
        JsonNode typeNode = component.get("type");
        return typeNode != null && typeNode.isTextual()
                && typeNode.textValue().toLowerCase(Locale.ROOT).contains("range");
    }

    /**
     * 读取可选布尔 schema 字段，存在但类型错误时拒绝持久化快照。
     *
     * @param node JsonNode，字段所属对象
     * @param fieldName String，字段名
     * @param defaultValue boolean，字段缺失时默认值
     * @return boolean，schema 中的布尔值或默认值
     */
    private boolean optionalBoolean(JsonNode node, String fieldName, boolean defaultValue)
    {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull())
        {
            return defaultValue;
        }
        if (!value.isBoolean())
        {
            throw invalidSnapshot(null);
        }
        return value.booleanValue();
    }

    /**
     * 读取受服务端硬上限约束的字符串长度配置。
     *
     * @param component JsonNode，表单组件
     * @param fieldName String，minlength 或 maxlength
     * @param defaultValue int，字段缺失时默认值
     * @return int，非负且不超过服务端绝对上限的长度
     */
    private int configuredStringLength(JsonNode component, String fieldName, int defaultValue)
    {
        Integer configured = optionalNonNegativeInt(component, fieldName);
        return configured == null ? defaultValue : Math.min(configured, MAX_STRING_LENGTH);
    }

    /**
     * 读取集合最小数量配置。
     *
     * @param component JsonNode，表单组件
     * @param fieldName String，最小数量字段名
     * @return int，非负且不超过服务端集合上限的最小数量
     */
    private int configuredMinimum(JsonNode component, String fieldName)
    {
        Integer configured = optionalNonNegativeInt(component, fieldName);
        return configured == null ? 0 : Math.min(configured, MAX_COLLECTION_SIZE);
    }

    /**
     * 读取集合最大数量配置，零表示组件未设置额外上限。
     *
     * @param component JsonNode，表单组件
     * @param fieldName String，最大数量字段名
     * @return int，不超过服务端绝对上限的集合数量
     */
    private int configuredCollectionLimit(JsonNode component, String fieldName)
    {
        Integer configured = optionalNonNegativeInt(component, fieldName);
        if (configured == null || configured == 0)
        {
            return MAX_COLLECTION_SIZE;
        }
        return Math.min(configured, MAX_COLLECTION_SIZE);
    }

    /**
     * 读取可选非负整数 schema 字段，拒绝小数、负数和 int 溢出。
     *
     * @param node JsonNode，字段所属对象
     * @param fieldName String，字段名
     * @return Integer，可为空的非负整数
     */
    private Integer optionalNonNegativeInt(JsonNode node, String fieldName)
    {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull())
        {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0)
        {
            throw invalidSnapshot(null);
        }
        return value.intValue();
    }

    /**
     * 读取可选十进制 schema 边界。
     *
     * @param node JsonNode，字段所属对象
     * @param fieldName String，min 或 max
     * @return BigDecimal，可为空的数值边界
     */
    private BigDecimal optionalDecimal(JsonNode node, String fieldName)
    {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull())
        {
            return null;
        }
        if (!value.isNumber())
        {
            throw invalidSnapshot(null);
        }
        return value.decimalValue();
    }

    /**
     * 判断值是否为 JSON 可稳定表达的标准 Java 数值类型。
     *
     * @param value Object，待判断值
     * @return boolean，标准整数、浮点数、BigInteger 或 BigDecimal 返回 true
     */
    private boolean isSupportedNumber(Object value)
    {
        return value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double
                || value instanceof BigInteger || value instanceof BigDecimal;
    }

    /**
     * 拒绝 NaN 和无穷值，避免进入 JSON 或 Flowable 变量序列化。
     *
     * @param number Number，已经过标准类型筛选的数值
     * @return void，非有限浮点数时抛出 400 业务异常
     */
    private void assertFiniteNumber(Number number)
    {
        if ((number instanceof Double doubleValue && !Double.isFinite(doubleValue))
                || (number instanceof Float floatValue && !Float.isFinite(floatValue)))
        {
            throw invalidVariable("流程变量数值必须为有限值");
        }
    }

    /**
     * 将标准 Java 数值转换为可比较的 BigDecimal。
     *
     * @param number Number，有限标准数值
     * @return BigDecimal，保持十进制文本语义的数值
     */
    private BigDecimal decimalValue(Number number)
    {
        assertFiniteNumber(number);
        return number instanceof BigDecimal decimal
                ? decimal : new BigDecimal(number.toString());
    }

    /**
     * 创建包含可信字段名但不回显客户端值的类型错误。
     *
     * @param fieldSpec FieldSpec，发生类型错误的字段规则
     * @return ServiceException，HTTP 400 业务异常
     */
    private ServiceException invalidType(FieldSpec fieldSpec)
    {
        return invalidVariable("流程变量类型不合法: " + fieldSpec.name());
    }

    /**
     * 创建客户端流程变量校验异常。
     *
     * @param message String，不包含客户端变量值的稳定错误提示
     * @return ServiceException，HTTP 400 业务异常
     */
    private ServiceException invalidVariable(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 创建部署表单快照数据异常，并仅在 cause 链保留内部原因。
     *
     * @param cause Throwable，可为空的内部校验或解析异常
     * @return ServiceException，HTTP 500 业务异常
     */
    private ServiceException invalidSnapshot(Throwable cause)
    {
        ServiceException failure = new ServiceException("流程部署表单快照结构异常", HttpStatus.ERROR);
        if (cause != null)
        {
            failure.initCause(cause);
        }
        return failure;
    }

    /**
     * 已从不可变表单快照提取的字段校验规则。
     *
     * @param name String，Flowable 变量名
     * @param type FieldType，服务端允许的字段类型
     * @param required boolean，是否必填
     * @param minLength int，文本最小字符数
     * @param maxLength int，文本最大字符数
     * @param minItems int，集合最小元素数
     * @param maxItems int，集合最大元素数
     * @param minimum BigDecimal，可为空的最小数值
     * @param maximum BigDecimal，可为空的最大数值
     */
    private record FieldSpec(String name, FieldType type, boolean required,
            int minLength, int maxLength, int minItems, int maxItems,
            BigDecimal minimum, BigDecimal maximum)
    {
    }

    /** 开始表单字段在服务端允许的顶层数据形态。 */
    private enum FieldType
    {
        /** 普通文本、富文本、颜色或单值日期时间。 */
        TEXT,
        /** 单个有限数值。 */
        NUMBER,
        /** 布尔开关。 */
        BOOLEAN,
        /** 字符串、数值或布尔单选值。 */
        SCALAR,
        /** 多选标量数组。 */
        SCALAR_LIST,
        /** 可包含受限嵌套路径的级联数组。 */
        CASCADER,
        /** 两个有限数值组成的滑块范围。 */
        NUMBER_RANGE,
        /** 两个字符串组成的日期时间范围。 */
        TEMPORAL_RANGE,
        /** 上传字段；只允许服务端生成的临时附件 UUID 数组。 */
        UPLOAD_LIST,
        /** 表格行对象数组。 */
        OBJECT_LIST
    }

    /** 单次请求共享的可变节点计数器。 */
    private static final class ValueBudget
    {
        /** 已遍历的变量值节点数量。 */
        private int value;

        /**
         * 记录一个变量值节点。
         *
         * @return void，无返回值
         */
        private void increment()
        {
            value++;
        }

        /**
         * 获取当前变量值节点数量。
         *
         * @return int，已遍历节点数量
         */
        private int value()
        {
            return value;
        }
    }
}

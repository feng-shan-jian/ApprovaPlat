package com.ruoyi.flowable.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 工作流正式平面表单 Schema 的服务端结构和安全白名单验证器。
 */
@Component
public class WorkflowFormTemplateValidator
{
    /** 表单 JSON 的 UTF-8 最大字节数。 */
    public static final int MAX_CONTENT_BYTES = 1024 * 1024;

    /** 单个表单允许的最大组件节点数。 */
    public static final int MAX_COMPONENT_NODES = 500;

    /** 通过 children 形成的最大组件嵌套深度。 */
    public static final int MAX_COMPONENT_DEPTH = 20;

    private static final int MAX_JSON_NODES = 10000;
    private static final int MAX_JSON_DEPTH = 100;

    private static final String FIELDS_FIELD = "fields";
    private static final String CHILDREN_FIELD = "children";
    private static final String TAG_FIELD = "tag";
    private static final String LAYOUT_FIELD = "layout";
    private static final String VARIABLE_FIELD = "vModel";

    /** 单个表单字段名允许的最大字符数。 */
    private static final int MAX_VARIABLE_NAME_LENGTH = 128;

    private static final Set<String> ALLOWED_TAGS = Set.of(
            "el-input", "el-input-number", "el-select", "el-cascader",
            "el-radio-group", "el-checkbox-group", "el-switch", "el-slider",
            "el-time-picker", "el-date-picker", "el-rate", "el-color-picker",
            "el-upload", "tinymce", "el-table", "el-table-column", "el-button");

    private static final Set<String> ALLOWED_LAYOUTS = Set.of(
            "colFormItem", "rowFormItem", "raw");

    private static final Set<String> PROTOTYPE_POLLUTION_KEYS = Set.of(
            "__proto__", "prototype", "constructor");

    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

    /**
     * 校验表单 JSON 的全部安全约束并返回根节点，供本次调用只读提取字段。
     * @param content String，正式平面表单 Schema JSON 文本
     * @return JsonNode，通过全部门禁的根节点；任何门禁失败时抛出 400 业务异常
     */
    public JsonNode validate(String content)
    {
        JsonNode root = decode(content);
        // 作者输入仍需完整安全与组件校验；持久化读取只复用解码边界。
        validateStaticContent(root);
        ComponentCounter counter = new ComponentCounter();
        validateComponents(root.get(FIELDS_FIELD), 1, counter);
        return root;
    }

    /** 单次解码表单正文，保留字节、深度、重复键和单一根约束，不重新审计部署模板。 */
    public static JsonNode decode(String content)
    {
        if (content == null || content.isBlank())
        {
            throw invalid("表单内容不能为空");
        }
        int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (contentBytes > MAX_CONTENT_BYTES)
        {
            throw invalid("表单内容不能超过1 MiB");
        }

        JsonNode root = parse(content);
        if (!root.isObject())
        {
            throw invalid("表单模板根节点必须是对象");
        }
        JsonNode fields = root.get(FIELDS_FIELD);
        if (fields == null || !fields.isArray())
        {
            throw invalid("表单模板必须包含 fields 数组");
        }

        return root;
    }

    /**
     * 只读遍历已通过完整模板校验的根节点，提取所有组件声明的变量字段名。
     *
     * @param root JsonNode，本次 validate 返回且未经修改的表单根节点
     * @return Set&lt;String&gt;，按组件顺序去重后的变量字段名不可变集合
     */
    public Set<String> extractVariableNames(JsonNode root)
    {
        LinkedHashSet<String> variableNames = new LinkedHashSet<>();
        visitFields(root, (field, permission) -> variableNames.add(variableName(field)));
        return Collections.unmodifiableSet(variableNames);
    }

    /**
     * 只读遍历已通过完整模板校验的根节点，提取允许向用户回显的变量字段名。
     *
     * 部署快照必须显式声明 workflowReadable，防止缺失节点权限时形成隐式可读回退。
     *
     * @param root JsonNode，本次 validate 返回且未经修改的表单根节点
     * @return Set&lt;String&gt;，按组件顺序保存的可读变量名不可变集合
     */
    public Set<String> extractReadableVariableNames(JsonNode root)
    {
        LinkedHashSet<String> variableNames = new LinkedHashSet<>();
        visitFields(root, (field, permission) ->
        {
            if (permission.readable()) variableNames.add(variableName(field));
        });
        return Collections.unmodifiableSet(variableNames);
    }

    /** 用户字段权限只能被祖先收紧；系统回填资格仍由字段自身的显式声明决定。 */
    public record FieldPermissions(boolean readable, boolean writable)
    {
    }

    /** 正式表单字段的顶层值形态；调用映射只允许前四类标量。 */
    public enum FieldType
    {
        TEXT, NUMBER, BOOLEAN, SCALAR, SCALAR_LIST, CASCADER,
        NUMBER_RANGE, TEMPORAL_RANGE, UPLOAD_LIST, OBJECT_LIST;

        public String scalarType()
        {
            return switch (this)
            {
                case TEXT, NUMBER, BOOLEAN, SCALAR -> name();
                default -> null;
            };
        }
    }

    /** 统一解释组件值形态；布局和非输入组件返回 null，不涉及节点权限或字段值约束。 */
    public FieldType resolveFieldType(JsonNode component)
    {
        return switch (component.path(TAG_FIELD).asText(""))
        {
            case "el-input", "tinymce", "el-color-picker" -> FieldType.TEXT;
            case "el-input-number", "el-rate" -> FieldType.NUMBER;
            case "el-slider" -> shapeFlag(component, "range") ? FieldType.NUMBER_RANGE : FieldType.NUMBER;
            case "el-switch" -> FieldType.BOOLEAN;
            case "el-radio-group" -> FieldType.SCALAR;
            case "el-select" -> shapeFlag(component, "multiple") ? FieldType.SCALAR_LIST : FieldType.SCALAR;
            case "el-checkbox-group" -> FieldType.SCALAR_LIST;
            case "el-cascader" -> FieldType.CASCADER;
            case "el-time-picker", "el-date-picker" -> shapeFlag(component, "is-range")
                    || component.path("type").asText("").toLowerCase(Locale.ROOT).contains("range")
                            ? FieldType.TEMPORAL_RANGE : FieldType.TEXT;
            case "el-upload" -> FieldType.UPLOAD_LIST;
            case "el-table" -> FieldType.OBJECT_LIST;
            default -> null;
        };
    }

    private boolean shapeFlag(JsonNode component, String name)
    {
        JsonNode value = component.get(name);
        if (value == null || value.isNull()) return false;
        if (!value.isBoolean()) throw invalid("表单字段 " + name + " 必须为布尔值");
        return value.booleanValue();
    }

    /** 只读遍历已验证的完整树，按原顺序访问业务字段，不改写模板或部署快照。 */
    public void visitFields(JsonNode root, BiConsumer<JsonNode, FieldPermissions> visitor)
    {
        visitFields(root.get(FIELDS_FIELD), new FieldPermissions(true, true), visitor);
    }

    private void visitFields(JsonNode components, FieldPermissions parent,
            BiConsumer<JsonNode, FieldPermissions> visitor)
    {
        for (JsonNode component : components)
        {
            if (!component.isObject()) throw invalid("fields 和 children 中的组件必须是对象");
            // 字段消费必须读取明确权限，不能把损坏的权限值强制转换为默认布尔值。
            validatePermissionMetadata(component);
            boolean readable = parent.readable() && !component.path("workflowHidden").booleanValue()
                    && component.path("workflowReadable").booleanValue();
            FieldPermissions permission = new FieldPermissions(readable,
                    parent.writable() && readable && component.path("workflowWritable").booleanValue());
            if (component.hasNonNull(VARIABLE_FIELD)) visitor.accept(component, permission);
            JsonNode children = component.get(CHILDREN_FIELD);
            if (children != null && !children.isNull() && !children.isArray())
                throw invalid("children 必须是数组");
            if (children != null && children.isArray()) visitFields(children, permission, visitor);
        }
    }

    /**
     * 只读遍历已通过完整模板校验的根节点，提取附件字段名。
     *
     * @param root JsonNode，本次 validate 返回且未经修改的表单根节点
     * @return Set&lt;String&gt;，按组件顺序保存的 el-upload 变量名不可变集合
     */
    public Set<String> extractAttachmentVariableNames(JsonNode root)
    {
        LinkedHashSet<String> variableNames = new LinkedHashSet<>();
        visitFields(root, (field, permission) ->
        {
            JsonNode variable = field.get(VARIABLE_FIELD);
            if ("el-upload".equals(field.path(TAG_FIELD).asText()) && variable.isTextual()
                    && !variable.textValue().isBlank()) variableNames.add(variable.textValue().trim());
        });
        return Collections.unmodifiableSet(variableNames);
    }

    /**
     * 只读遍历已通过完整模板校验的根节点，提取正式用户主键字段及其稳定值形态签名。
     *
     * 同名字段只有在全部声明均可见、可读、单值且组件类型完全一致时才会保留；
     * 该签名供流程级目录识别跨节点异构声明，不能用于渲染或执行任意组件。
     *
     * @param root JsonNode，本次 validate 返回且未经修改的权限化表单根节点
     * @return Map&lt;String,String&gt;，字段名到受控组件类型签名的有序不可变映射
     */
    public Map<String, String> extractUserIdSourceFieldSignatures(JsonNode root)
    {
        // signatureByName 保存首次合格声明的值形态，disqualifiedNames 负责单向失败关闭。
        LinkedHashMap<String, String> signatureByName = new LinkedHashMap<>();
        LinkedHashSet<String> disqualifiedNames = new LinkedHashSet<>();
        collectUserIdSourceVariables(root, signatureByName,
                disqualifiedNames);
        disqualifiedNames.forEach(signatureByName::remove);
        return Collections.unmodifiableMap(signatureByName);
    }

    private String variableName(JsonNode component)
    {
        JsonNode variable = component.get(VARIABLE_FIELD);
        if (!variable.isTextual() || variable.textValue().isBlank()
                || variable.textValue().length() > MAX_VARIABLE_NAME_LENGTH)
            throw invalid("表单组件变量名不合法");
        return variable.textValue().trim();
    }

    /**
     * 递归收集可见、可读且可承载单一用户主键的字段，并拒绝同名异构声明被并集放宽。
     * @param root JsonNode，已经验证的完整表单
     * @param signatureByName Map&lt;String,String&gt;，字段名到首次合格值形态签名的有序映射
     * @param disqualifiedNames Set&lt;String&gt;，包含任一不合格或异构声明的字段名
     * @return void，直接更新调用方目录
     */
    private void collectUserIdSourceVariables(JsonNode root,
            Map<String, String> signatureByName, Set<String> disqualifiedNames)
    {
        visitFields(root, (component, permission) ->
        {
            String variableName = variableName(component);
            String signature = permission.readable() ? singleUserIdValueSignature(component) : null;
            if (signature == null)
            {
                disqualifiedNames.add(variableName);
            }
            else
            {
                String existing = signatureByName.putIfAbsent(variableName, signature);
                if (existing != null && !existing.equals(signature))
                {
                    // 同名文本、数值或选择字段可能在运行时形成不同值类型，必须失败关闭。
                    disqualifiedNames.add(variableName);
                }
            }
        });
    }

    /**
     * 判断组件值是否能无歧义承载一个若依用户主键。
     * @param component JsonNode，包含 tag、multiple 等值形态配置的平面表单组件
     * @return String，单值文本、数值、下拉或单选组件的稳定类型签名；不合格时返回 null
     */
    private String singleUserIdValueSignature(JsonNode component)
    {
        String tag = component.path(TAG_FIELD).asText("");
        return switch (tag)
        {
            case "el-input", "el-input-number", "el-radio-group" -> tag;
            case "el-select" -> !component.path("multiple").asBoolean(false) ? tag : null;
            default -> null;
        };
    }

    /**
     * 使用严格 Jackson 配置解析单一 JSON 根节点。
     * @param content String，待解析 JSON 文本
     * @return JsonNode，解析后的根节点
     */
    private static JsonNode parse(String content)
    {
        try
        {
            JsonNode root = OBJECT_MAPPER.readTree(content);
            if (root == null || root.isNull())
            {
                throw invalid("表单内容必须是合法 JSON 对象");
            }
            return root;
        }
        catch (JacksonException exception)
        {
            throw invalid("表单内容必须是合法且无重复键的 JSON 对象");
        }
    }

    /**
     * 检查不可信静态内容的危险协议、键和规模；不重复判断转换器固定生成的组件结构。
     * @param root JsonNode，模板或内嵌作者字段生成的静态内容
     * @return void，发现危险内容时抛出业务异常
     */
    public void validateStaticContent(JsonNode root)
    {
        Deque<JsonNode> pending = new ArrayDeque<>();
        pending.push(root);
        int visitedNodes = 0;
        while (!pending.isEmpty())
        {
            JsonNode node = pending.pop();
            visitedNodes++;
            if (visitedNodes > MAX_JSON_NODES)
            {
                throw invalid("表单模板 JSON 节点数量过多");
            }
            if (node.isTextual() && containsDangerousProtocol(node.textValue()))
            {
                throw invalid("表单模板包含 javascript: 或 data: 危险协议");
            }
            if (node.isArray())
            {
                for (JsonNode child : node)
                {
                    pending.push(child);
                }
            }
            else if (node.isObject())
            {
                Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
                while (fields.hasNext())
                {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (field.getKey().startsWith("__"))
                    {
                        throw invalid("表单模板包含已废止的内部生成器字段");
                    }
                    if (PROTOTYPE_POLLUTION_KEYS.contains(field.getKey().toLowerCase(Locale.ROOT)))
                    {
                        throw invalid("表单模板包含原型污染键");
                    }
                    pending.push(field.getValue());
                }
            }
        }
    }

    /**
     * 递归验证平面组件节点、布局、标签及 children。
     * @param components JsonNode，当前层组件数组
     * @param depth int，当前组件嵌套深度，从 1 开始
     * @param counter ComponentCounter，全表单共享组件计数器
     * @return void，组件结构不合法时抛出业务异常
     */
    private void validateComponents(JsonNode components, int depth, ComponentCounter counter)
    {
        if (depth > MAX_COMPONENT_DEPTH)
        {
            throw invalid("表单模板组件嵌套深度不能超过" + MAX_COMPONENT_DEPTH);
        }
        for (JsonNode component : components)
        {
            counter.increment();
            if (counter.value() > MAX_COMPONENT_NODES)
            {
                throw invalid("表单模板组件数量不能超过" + MAX_COMPONENT_NODES);
            }
            if (!component.isObject())
            {
                throw invalid("fields 和 children 中的组件必须是对象");
            }
            String layout = requiredText(component, LAYOUT_FIELD, "组件 layout 不能为空");
            if (!ALLOWED_LAYOUTS.contains(layout))
            {
                throw invalid("不支持的表单组件布局: " + layout);
            }
            JsonNode tagNode = component.get(TAG_FIELD);
            if (!"rowFormItem".equals(layout) || (tagNode != null && !tagNode.isNull()))
            {
                String tag = requiredText(component, TAG_FIELD, "非行容器组件 tag 不能为空");
                if (!ALLOWED_TAGS.contains(tag))
                {
                    throw invalid("不支持的表单组件: " + tag);
                }
            }

            JsonNode children = component.get(CHILDREN_FIELD);
            validatePermissionMetadata(component);
            if (children != null && !children.isNull())
            {
                if (!children.isArray())
                {
                    throw invalid("children 必须是数组");
                }
                if (!children.isEmpty())
                {
                    validateComponents(children, depth + 1, counter);
                }
            }
        }
    }

    /**
     * 校验部署快照中的节点字段权限元数据，防止数据库脏数据形成前后端语义分裂。
     *
     * @param component JsonNode，当前平面字段配置
     * @return void，标志类型或四态组合非法时抛出稳定 400
     */
    private void validatePermissionMetadata(JsonNode component)
    {
        JsonNode hiddenNode = component.get("workflowHidden");
        JsonNode readableNode = component.get("workflowReadable");
        JsonNode writableNode = component.get("workflowWritable");
        for (String flagName : new String[] { "workflowHidden", "workflowReadable", "workflowWritable" })
        {
            JsonNode flag = component.get(flagName);
            if (flag == null || !flag.isBoolean())
            {
                throw invalid("表单组件必须声明完整节点权限标志: "
                        + component.path("vModel").asText("布局组件") + "." + flagName);
            }
        }
        boolean hidden = hiddenNode.booleanValue();
        boolean readable = readableNode.booleanValue();
        boolean writable = writableNode.booleanValue();
        JsonNode systemWritable = component.get("workflowSystemWritable");
        if (systemWritable != null && (!systemWritable.isBoolean()
                || (systemWritable.booleanValue() && (hidden || !readable || writable))))
            throw invalid("系统结果字段必须可读且用户只读: " + component.path("vModel").asText(""));
        JsonNode requiredNode = component.get("required");
        if (requiredNode != null && !requiredNode.isNull() && !requiredNode.isBoolean())
        {
            throw invalid("表单组件必填标志不合法");
        }
        boolean required = requiredNode != null && requiredNode.isBoolean()
                && requiredNode.booleanValue();
        if ((hidden && (readable || writable || required))
                || (required && (!readable || !writable)))
        {
            throw invalid("表单组件节点权限组合不合法");
        }
    }

    /**
     * 读取必填文本字段。
     * @param object JsonNode，字段所属对象
     * @param fieldName String，字段名
     * @param message String，缺失或类型错误时的稳定提示
     * @return String，去除首尾空白后的字段值
     */
    private String requiredText(JsonNode object, String fieldName, String message)
    {
        JsonNode value = object.get(fieldName);
        if (value == null || !value.isTextual() || value.textValue().isBlank())
        {
            throw invalid(message);
        }
        return value.textValue().trim();
    }

    /**
     * 判断文本中是否包含 javascript: 或 data: 协议，忽略大小写及控制空白混淆。
     * @param value String，JSON 文本值
     * @return boolean，发现危险协议时返回 true
     */
    private boolean containsDangerousProtocol(String value)
    {
        StringBuilder normalized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++)
        {
            char character = value.charAt(index);
            if (!Character.isWhitespace(character) && !Character.isISOControl(character))
            {
                normalized.append(Character.toLowerCase(character));
            }
        }
        String compact = normalized.toString();
        return compact.contains("javascript:") || compact.contains("data:");
    }

    /**
     * 创建启用重复键检测、尾随 token 检测和解析深度限制的 Jackson mapper。
     * @return ObjectMapper，表单模板专用严格 JSON mapper
     */
    private static ObjectMapper createObjectMapper()
    {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_JSON_DEPTH)
                .maxStringLength(MAX_CONTENT_BYTES)
                .build();
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        return JsonMapper.builder(factory)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    /**
     * 创建稳定的 400 表单模板校验异常。
     * @param message String，对外可读且不包含模板原文的错误提示
     * @return ServiceException，HTTP 语义为 400 的业务异常
     */
    private static ServiceException invalid(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 单次校验使用的可变组件计数器，避免把状态存入单例验证器。
     */
    private static final class ComponentCounter
    {
        /** 已遍历组件节点数量。 */
        private int value;

        /**
         * 将已遍历组件节点数量加一。
         */
        private void increment()
        {
            value++;
        }

        /**
         * 获取已遍历组件节点数量。
         * @return int，当前组件节点数量
         */
        private int value()
        {
            return value;
        }
    }
}

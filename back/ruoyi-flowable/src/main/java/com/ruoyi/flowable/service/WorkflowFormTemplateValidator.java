package com.ruoyi.flowable.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * 旧表单生成器 JSON 的服务端结构和安全白名单验证器。
 */
@Component
public class WorkflowFormTemplateValidator
{
    /** 表单 JSON 的 UTF-8 最大字节数。 */
    public static final int MAX_CONTENT_BYTES = 1024 * 1024;

    /** 单个表单允许的最大组件节点数。 */
    public static final int MAX_COMPONENT_NODES = 500;

    /** 通过 __config__.children 形成的最大组件嵌套深度。 */
    public static final int MAX_COMPONENT_DEPTH = 20;

    private static final int MAX_JSON_NODES = 10000;
    private static final int MAX_JSON_DEPTH = 100;

    private static final String CONFIG_FIELD = "__config__";
    private static final String FIELDS_FIELD = "fields";
    private static final String CHILDREN_FIELD = "children";
    private static final String TAG_FIELD = "tag";
    private static final String LAYOUT_FIELD = "layout";
    private static final String VARIABLE_FIELD = "__vModel__";

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

    private final ObjectMapper objectMapper;

    /**
     * 创建表单模板验证器并启用重复键、尾随内容和解析深度门禁。
     * @return 构造函数，无返回值
     */
    public WorkflowFormTemplateValidator()
    {
        this.objectMapper = createObjectMapper();
    }

    /**
     * 校验表单 JSON 的大小、语法、根结构、组件白名单及危险内容。
     * @param content String，旧表单生成器产生的完整 JSON 文本
     * @return void，任何门禁失败时抛出 400 业务异常
     */
    public void validate(String content)
    {
        validateAndParse(content);
    }

    /**
     * 校验表单快照并提取所有组件声明的变量字段名。
     *
     * @param content String，部署时固化的完整表单 JSON
     * @return Set&lt;String&gt;，按组件顺序去重后的变量字段名不可变集合
     */
    public Set<String> extractVariableNames(String content)
    {
        JsonNode root = validateAndParse(content);
        LinkedHashSet<String> variableNames = new LinkedHashSet<>();
        collectVariableNames(root.get(FIELDS_FIELD), variableNames);
        return Collections.unmodifiableSet(variableNames);
    }

    /**
     * 校验表单 JSON 的全部安全约束并返回已解析根节点，供只读字段提取复用。
     *
     * @param content String，旧表单生成器产生的完整 JSON 文本
     * @return JsonNode，通过大小、语法、结构和组件白名单校验的根节点
     */
    private JsonNode validateAndParse(String content)
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

        // 先扫描整棵 JSON，避免危险键或协议藏在插槽、选项、样式等非组件字段中。
        validateEntireJsonTree(root);
        ComponentCounter counter = new ComponentCounter();
        validateComponents(fields, 1, counter);
        return root;
    }

    /**
     * 按表单组件顺序递归提取 __vModel__，不把布局容器或配置键当作业务变量。
     *
     * @param components JsonNode，当前层 fields 或 __config__.children 数组
     * @param variableNames Set&lt;String&gt;，全表单共享的有序字段名集合
     * @return 无返回值，字段名异常时抛出稳定 400
     */
    private void collectVariableNames(JsonNode components, Set<String> variableNames)
    {
        for (JsonNode component : components)
        {
            JsonNode variableNode = component.get(VARIABLE_FIELD);
            if (variableNode != null && !variableNode.isNull())
            {
                if (!variableNode.isTextual() || variableNode.textValue().isBlank()
                        || variableNode.textValue().length() > MAX_VARIABLE_NAME_LENGTH)
                {
                    throw invalid("表单组件变量名不合法");
                }
                variableNames.add(variableNode.textValue().trim());
            }
            JsonNode children = component.path(CONFIG_FIELD).get(CHILDREN_FIELD);
            if (children != null && children.isArray() && !children.isEmpty())
            {
                collectVariableNames(children, variableNames);
            }
        }
    }

    /**
     * 使用严格 Jackson 配置解析单一 JSON 根节点。
     * @param content String，待解析 JSON 文本
     * @return JsonNode，解析后的根节点
     */
    private JsonNode parse(String content)
    {
        try
        {
            JsonNode root = objectMapper.readTree(content);
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
     * 迭代扫描全部 JSON 节点，拒绝原型污染键、危险协议和异常节点规模。
     * @param root JsonNode，表单 JSON 根节点
     * @return void，发现危险内容时抛出业务异常
     */
    private void validateEntireJsonTree(JsonNode root)
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
     * 递归验证组件节点、布局、标签及 __config__.children。
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
            JsonNode config = component.get(CONFIG_FIELD);
            if (config == null || !config.isObject())
            {
                throw invalid("组件必须包含 __config__ 对象");
            }

            String layout = requiredText(config, LAYOUT_FIELD, "组件 layout 不能为空");
            if (!ALLOWED_LAYOUTS.contains(layout))
            {
                throw invalid("不支持的表单组件布局: " + layout);
            }
            JsonNode tagNode = config.get(TAG_FIELD);
            if (!"rowFormItem".equals(layout) || (tagNode != null && !tagNode.isNull()))
            {
                String tag = requiredText(config, TAG_FIELD, "非行容器组件 tag 不能为空");
                if (!ALLOWED_TAGS.contains(tag))
                {
                    throw invalid("不支持的表单组件: " + tag);
                }
            }

            JsonNode children = config.get(CHILDREN_FIELD);
            if (children != null && !children.isNull())
            {
                if (!children.isArray())
                {
                    throw invalid("__config__.children 必须是数组");
                }
                if (!children.isEmpty())
                {
                    validateComponents(children, depth + 1, counter);
                }
            }
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
    private ObjectMapper createObjectMapper()
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
    private ServiceException invalid(String message)
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
         * @return void，无返回值
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

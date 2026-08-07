package com.ruoyi.flowable.extension;

import java.util.Comparator;
import java.util.Map;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 扩展版本和部署快照共用的 JSON 规范化器。
 *
 * MySQL JSON 会按自身规则重排对象键，因此摘要必须基于结构化后再稳定排序的文本，
 * 不能依赖 JDBC 写入前或读取后的原始键顺序。
 */
public final class WorkflowExtensionJsonCanonicalizer
{
    /** 只执行 JsonNode 结构化读写，不启用任意类型反序列化。 */
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.shared();

    /** 禁止实例化纯函数工具类。 */
    private WorkflowExtensionJsonCanonicalizer()
    {
    }

    /**
     * 将 JSON 对象键递归按字典序排列并输出无空白稳定文本，数组元素顺序保持不变。
     * @param json String，数据库或代码注册表提供的合法 JSON 文本
     * @return String，可跨 MySQL JSON 键重排稳定复算的规范文本
     */
    public static String canonicalize(String json)
    {
        if (json == null || json.isBlank())
        {
            throw new ServiceException("扩展 JSON 数据不能为空", HttpStatus.ERROR);
        }
        try
        {
            JsonNode parsed = OBJECT_MAPPER.readTree(json);
            if (parsed == null)
            {
                throw new ServiceException("扩展 JSON 数据不能为空", HttpStatus.ERROR);
            }
            return OBJECT_MAPPER.writeValueAsString(sortRecursively(parsed));
        }
        catch (JacksonException exception)
        {
            throw new ServiceException("扩展 JSON 数据不合法", HttpStatus.ERROR);
        }
    }

    /**
     * 递归复制 JSON 节点，对象按键排序，数组保持业务顺序，标量保持原类型和值。
     * @param node JsonNode，已由 Jackson 安全解析的任意 JSON 节点
     * @return JsonNode，不共享可变容器且键顺序稳定的新节点
     */
    private static JsonNode sortRecursively(JsonNode node)
    {
        if (node.isObject())
        {
            ObjectNode sorted = OBJECT_MAPPER.createObjectNode();
            node.properties().stream()
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(entry -> sorted.set(entry.getKey(),
                            sortRecursively(entry.getValue())));
            return sorted;
        }
        if (node.isArray())
        {
            ArrayNode sorted = OBJECT_MAPPER.createArrayNode();
            node.values().forEach(value -> sorted.add(sortRecursively(value)));
            return sorted;
        }
        return node.deepCopy();
    }
}

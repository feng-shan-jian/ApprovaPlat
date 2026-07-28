package com.ruoyi.flowable.config;

import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * 将 Flowable 使用的 Jackson 2 {@link JsonNode} 安全写入 Jackson 3 响应流。
 *
 * <p>序列化器只接受标准 JSON 节点，禁止通过 raw JSON、二进制节点或 POJO 节点
 * 绕过 Spring Boot 4 的 JSON 编码和响应安全边界。</p>
 */
public final class WorkflowJackson2JsonNodeSerializer extends StdSerializer<JsonNode>
{
    private static final long serialVersionUID = 1L;

    /**
     * 创建 Jackson 2 JsonNode 到 Jackson 3 的值序列化器。
     *
     * @return 无返回值，新实例绑定 Jackson 2 JsonNode 类型
     */
    public WorkflowJackson2JsonNodeSerializer()
    {
        super(JsonNode.class);
    }

    /**
     * 先校验完整节点树，再使用 Jackson 3 生成器逐类型写出原生 JSON。
     *
     * @param value JsonNode，Flowable 兼容层返回的 Jackson 2 安全 JSON 树
     * @param generator JsonGenerator，Spring Boot 4 当前 HTTP 响应生成器
     * @param context SerializationContext，Jackson 3 当前序列化上下文
     * @return 无返回值，节点树不受支持时抛出 JacksonException
     * @throws JacksonException 节点类型不安全或底层响应写入失败时抛出
     */
    @Override
    public void serialize(JsonNode value, JsonGenerator generator,
            SerializationContext context) throws JacksonException
    {
        if (value == null)
        {
            generator.writeNull();
            return;
        }

        // 必须在写响应正文前校验整棵树，避免深层非法节点导致部分 JSON 已经输出。
        validateNode(value, generator);
        writeNode(value, generator);
    }

    /**
     * 递归校验节点树只包含有限、可无损映射到原生 JSON 的 Jackson 2 节点。
     *
     * @param node JsonNode，当前待校验节点
     * @param generator JsonGenerator，用于创建带响应上下文的序列化异常
     * @return 无返回值，任一节点不满足安全契约时立即抛出异常
     * @throws JacksonException 节点为空、浮点值非有限或节点类型不受支持时抛出
     */
    private void validateNode(JsonNode node, JsonGenerator generator) throws JacksonException
    {
        if (node == null)
        {
            throw unsupportedNode(generator, null);
        }
        if (node.isFloat() && !Float.isFinite(node.floatValue())
                || node.isDouble() && !Double.isFinite(node.doubleValue()))
        {
            throw DatabindException.from(generator, "工作流 JSON 响应不允许非有限浮点数");
        }
        if (node.isNull() || node.isTextual() || node.isBoolean()
                || node.isShort() || node.isInt() || node.isLong()
                || node.isBigInteger() || node.isFloat() || node.isDouble()
                || node.isBigDecimal())
        {
            return;
        }
        if (node.isArray())
        {
            for (JsonNode child : node)
            {
                validateNode(child, generator);
            }
            return;
        }
        if (node.isObject())
        {
            for (Map.Entry<String, JsonNode> property : node.properties())
            {
                validateNode(property.getValue(), generator);
            }
            return;
        }

        // Binary、POJO、Missing 及未知扩展节点都不能进入业务 HTTP 响应。
        throw unsupportedNode(generator, node);
    }

    /**
     * 按已经通过完整校验的节点类型递归写入 Jackson 3 响应生成器。
     *
     * @param node JsonNode，已经过 validateNode 校验的当前节点
     * @param generator JsonGenerator，Spring Boot 4 当前 HTTP 响应生成器
     * @return 无返回值，节点内容按原生 JSON 类型写入
     * @throws JacksonException 底层写入失败或校验后类型发生异常时抛出
     */
    private void writeNode(JsonNode node, JsonGenerator generator) throws JacksonException
    {
        if (node.isNull())
        {
            generator.writeNull();
        }
        else if (node.isTextual())
        {
            generator.writeString(node.textValue());
        }
        else if (node.isBoolean())
        {
            generator.writeBoolean(node.booleanValue());
        }
        else if (node.isShort())
        {
            generator.writeNumber(node.shortValue());
        }
        else if (node.isInt())
        {
            generator.writeNumber(node.intValue());
        }
        else if (node.isLong())
        {
            generator.writeNumber(node.longValue());
        }
        else if (node.isBigInteger())
        {
            generator.writeNumber(node.bigIntegerValue());
        }
        else if (node.isFloat())
        {
            generator.writeNumber(node.floatValue());
        }
        else if (node.isDouble())
        {
            generator.writeNumber(node.doubleValue());
        }
        else if (node.isBigDecimal())
        {
            generator.writeNumber(node.decimalValue());
        }
        else if (node.isArray())
        {
            generator.writeStartArray();
            for (JsonNode child : node)
            {
                writeNode(child, generator);
            }
            generator.writeEndArray();
        }
        else if (node.isObject())
        {
            generator.writeStartObject();
            for (Map.Entry<String, JsonNode> property : node.properties())
            {
                generator.writeName(property.getKey());
                writeNode(property.getValue(), generator);
            }
            generator.writeEndObject();
        }
        else
        {
            // 双重门禁防止未来节点实现具有不稳定的类型判断结果。
            throw unsupportedNode(generator, node);
        }
    }

    /**
     * 创建不受支持节点的稳定 Jackson 3 数据绑定异常。
     *
     * @param generator JsonGenerator，当前响应生成器
     * @param node JsonNode，被拒绝的 Jackson 2 节点，允许为空
     * @return DatabindException，包含被拒绝节点的运行时类型
     */
    private DatabindException unsupportedNode(JsonGenerator generator, JsonNode node)
    {
        String nodeType = node == null ? "null" : node.getClass().getName();
        return DatabindException.from(generator,
                "工作流 JSON 响应包含不支持的 Jackson 2 节点类型: " + nodeType);
    }
}

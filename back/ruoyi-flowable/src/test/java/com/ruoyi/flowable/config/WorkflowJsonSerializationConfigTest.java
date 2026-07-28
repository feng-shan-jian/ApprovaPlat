package com.ruoyi.flowable.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.BinaryNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.FloatNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.POJONode;
import com.fasterxml.jackson.databind.node.ShortNode;
import com.fasterxml.jackson.databind.node.TextNode;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

class WorkflowJsonSerializationConfigTest
{
    private final WorkflowJsonSerializationConfig configuration =
            new WorkflowJsonSerializationConfig();

    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(configuration.workflowJackson2JsonNodeModule())
            .build();

    /**
     * 验证 Spring 配置真实暴露一个可由 Boot 自动收集的 JacksonModule Bean。
     *
     * @return 无返回值，Bean 类型、名称或模块实现不符合约定时测试失败
     */
    @Test
    void exposesJacksonModuleBean()
    {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(WorkflowJsonSerializationConfig.class))
        {
            Map<String, JacksonModule> modules = context.getBeansOfType(JacksonModule.class);

            assertThat(modules).containsOnlyKeys("workflowJackson2JsonNodeModule");
            assertThat(modules.get("workflowJackson2JsonNodeModule"))
                    .isInstanceOf(SimpleModule.class);
        }
    }

    /**
     * 验证全部允许节点通过 Jackson 3 生成器输出为原生 JSON，并保持嵌套与转义语义。
     *
     * @return 无返回值，任一标量、数组或对象被序列化为 Jackson 2 Bean 属性时测试失败
     * @throws JacksonException Jackson 3 序列化或解析失败时抛出
     */
    @Test
    void serializesSupportedJackson2NodesAsNativeJson() throws JacksonException
    {
        ObjectNode source = JsonNodeFactory.instance.objectNode();
        source.set("text", TextNode.valueOf("引号\"与换行\n仍是文本"));
        source.set("boolean", BooleanNode.TRUE);
        source.set("nullValue", NullNode.getInstance());
        source.set("short", ShortNode.valueOf((short) 7));
        source.set("int", IntNode.valueOf(42));
        source.set("long", LongNode.valueOf(5_000_000_000L));
        source.set("bigInteger", BigIntegerNode.valueOf(
                new BigInteger("123456789012345678901234567890")));
        source.set("float", FloatNode.valueOf(1.25F));
        source.set("double", DoubleNode.valueOf(2.5D));
        source.set("bigDecimal", DecimalNode.valueOf(new BigDecimal("12345.678900")));

        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        array.add("first");
        array.add(2);
        ObjectNode nested = JsonNodeFactory.instance.objectNode();
        nested.put("quoted\"key", "nested-value");
        array.add(nested);
        source.set("array", array);
        source.set("object", nested.deepCopy());

        String serialized = mapper.writeValueAsString(source);
        tools.jackson.databind.JsonNode result = mapper.readTree(serialized);

        assertThat(result.path("text").textValue()).isEqualTo("引号\"与换行\n仍是文本");
        assertThat(result.path("boolean").booleanValue()).isTrue();
        assertThat(result.path("nullValue").isNull()).isTrue();
        assertThat(result.path("short").intValue()).isEqualTo(7);
        assertThat(result.path("int").intValue()).isEqualTo(42);
        assertThat(result.path("long").longValue()).isEqualTo(5_000_000_000L);
        assertThat(result.path("bigInteger").bigIntegerValue())
                .isEqualTo(new BigInteger("123456789012345678901234567890"));
        assertThat(result.path("float").doubleValue()).isEqualTo(1.25D);
        assertThat(result.path("double").doubleValue()).isEqualTo(2.5D);
        assertThat(result.path("bigDecimal").decimalValue())
                .isEqualByComparingTo(new BigDecimal("12345.678900"));
        assertThat(result.path("array").isArray()).isTrue();
        assertThat(result.path("array").path(2).path("quoted\"key").textValue())
                .isEqualTo("nested-value");
        assertThat(result.path("object").path("quoted\"key").textValue())
                .isEqualTo("nested-value");
        assertThat(serialized).doesNotContain("nodeType", "textual", "containerNode");
    }

    /**
     * 验证 Float 和 Double 的 NaN、正无穷及负无穷全部在写出前被拒绝。
     *
     * @param description String，当前非法浮点场景名称
     * @param node JsonNode，包含非有限值的 Jackson 2 数值节点
     * @return 无返回值，非有限值能够进入 JSON 响应时测试失败
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nonFiniteFloatingNodes")
    void rejectsNonFiniteFloatingPointNodes(String description, JsonNode node)
    {
        assertRejectedInsideObject(node, "非有限浮点数");
    }

    /**
     * 提供 Jackson 2 支持创建但 JSON 协议不允许写出的全部非有限浮点节点。
     *
     * @return Stream&lt;Arguments&gt;，Float 和 Double 的 NaN、正无穷及负无穷
     */
    private static Stream<Arguments> nonFiniteFloatingNodes()
    {
        return Stream.of(
                arguments("Float NaN", FloatNode.valueOf(Float.NaN)),
                arguments("Float positive infinity", FloatNode.valueOf(Float.POSITIVE_INFINITY)),
                arguments("Float negative infinity", FloatNode.valueOf(Float.NEGATIVE_INFINITY)),
                arguments("Double NaN", DoubleNode.valueOf(Double.NaN)),
                arguments("Double positive infinity", DoubleNode.valueOf(Double.POSITIVE_INFINITY)),
                arguments("Double negative infinity", DoubleNode.valueOf(Double.NEGATIVE_INFINITY)));
    }

    /**
     * 验证 Binary、POJO、Missing 及未知 Jackson 2 节点在嵌套位置同样 fail-closed。
     *
     * @param description String，当前不安全节点场景名称
     * @param node JsonNode，被放入嵌套对象的 Jackson 2 节点
     * @return 无返回值，不安全节点能够进入 JSON 响应时测试失败
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedNodes")
    void rejectsUnsupportedNodes(String description, JsonNode node)
    {
        assertRejectedInsideObject(node, "不支持的 Jackson 2 节点类型");
    }

    /**
     * 提供必须拒绝的 Jackson 2 非标准 JSON 节点及未知扩展节点。
     *
     * @return Stream&lt;Arguments&gt;，Binary、POJO、Missing 和未知节点
     */
    private static Stream<Arguments> unsupportedNodes()
    {
        return Stream.of(
                arguments("binary", BinaryNode.valueOf(new byte[] { 1, 2, 3 })),
                arguments("pojo", new POJONode(Map.of("unsafe", true))),
                arguments("missing", MissingNode.getInstance()),
                arguments("unknown", mock(JsonNode.class)));
    }

    /**
     * 将非法节点放入对象后断言递归预检在响应写出前返回稳定数据绑定异常。
     *
     * @param node JsonNode，待验证的非法 Jackson 2 节点
     * @param expectedMessage String，异常中必须包含的稳定原因
     * @return 无返回值，序列化未失败或错误原因不稳定时测试失败
     */
    private void assertRejectedInsideObject(JsonNode node, String expectedMessage)
    {
        ObjectNode wrapper = JsonNodeFactory.instance.objectNode();
        wrapper.set("safe", TextNode.valueOf("尚未写出"));
        wrapper.set("unsafe", node);

        assertThatThrownBy(() -> mapper.writeValueAsString(wrapper))
                .isInstanceOf(JacksonException.class)
                .hasMessageContaining(expectedMessage);
    }
}

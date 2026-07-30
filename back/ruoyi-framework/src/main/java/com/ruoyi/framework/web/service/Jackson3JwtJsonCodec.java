package com.ruoyi.framework.web.service;

import java.io.OutputStream;
import java.io.Reader;
import java.util.Map;
import io.jsonwebtoken.io.AbstractDeserializer;
import io.jsonwebtoken.io.AbstractSerializer;
import io.jsonwebtoken.io.DeserializationException;
import io.jsonwebtoken.io.Deserializer;
import io.jsonwebtoken.io.SerializationException;
import io.jsonwebtoken.io.Serializer;
import io.jsonwebtoken.lang.Supplier;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * 使用 Jackson 3 为 JJWT 提供受控 JSON 编解码，避免引入基于 Jackson 2 的 jjwt-jackson。
 */
final class Jackson3JwtJsonCodec
{
    /** JWT JSON 允许的最大嵌套深度，远高于当前两项登录声明但拒绝异常深树。 */
    private static final int MAX_JSON_DEPTH = 32;

    /** 单个 JWT 头或声明 JSON 允许的最大文档字符数，限制解析前后的总体内存占用。 */
    private static final long MAX_DOCUMENT_LENGTH = 128L * 1024L;

    /** 单个 JWT JSON 允许的最大词法 Token 数，拒绝由大量微小字段或数组元素构成的输入。 */
    private static final long MAX_TOKEN_COUNT = 2_048L;

    /** 单个 JWT JSON 字段名允许的最大字符数，登录声明无需承载超长动态键名。 */
    private static final int MAX_NAME_LENGTH = 256;

    /** 单个 JWT JSON 数字允许的最大字符数，覆盖时间戳和业务标识同时限制高精度数值分配。 */
    private static final int MAX_NUMBER_LENGTH = 128;

    /** 单个 JWT JSON 字符串允许的最大字符数，避免异常声明触发无界分配。 */
    private static final int MAX_STRING_LENGTH = 64 * 1024;

    /** 固定重复键检测与读取上限的 JWT 专用 Jackson 3 mapper。 */
    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

    /** 复用不可变写入配置，且不关闭 JJWT 管理生命周期的目标流。 */
    private static final ObjectWriter OBJECT_WRITER = OBJECT_MAPPER.writer()
            .without(StreamWriteFeature.AUTO_CLOSE_TARGET);

    /** 无状态流式编码器，供所有令牌签发请求共享。 */
    static final Serializer<Map<String, ?>> SERIALIZER = new JwtSerializer();

    /** 无状态流式解码器，供所有令牌解析请求共享。 */
    static final Deserializer<Map<String, ?>> DESERIALIZER = new JwtDeserializer();

    /**
     * 禁止实例化仅承载 JJWT Jackson 3 适配器的工具类。
     *
     * @return 无返回值
     */
    private Jackson3JwtJsonCodec()
    {
    }

    /**
     * 创建只服务于 JWT 的严格 Jackson 3 mapper。
     *
     * @return ObjectMapper，启用重复键检测、读取上限和 JJWT Supplier 解包
     */
    private static ObjectMapper createObjectMapper()
    {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_JSON_DEPTH)
                .maxDocumentLength(MAX_DOCUMENT_LENGTH)
                .maxTokenCount(MAX_TOKEN_COUNT)
                .maxNameLength(MAX_NAME_LENGTH)
                .maxNumberLength(MAX_NUMBER_LENGTH)
                .maxStringLength(MAX_STRING_LENGTH)
                .build();
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        SimpleModule module = new SimpleModule("jjwt-jackson3");
        module.addSerializer(Supplier.class, new JwtSupplierSerializer());
        return JsonMapper.builder(factory)
                .addModule(module)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * 校验 Jackson 解析结果确实是非空 JSON 对象。
     *
     * @param value Map，Jackson 3 按 Map 类型解析的根对象
     * @return Map&lt;String, ?&gt;，通过根对象校验的 JWT 数据
     */
    @SuppressWarnings("unchecked")
    private static Map<String, ?> readObject(Map<?, ?> value)
    {
        if (value == null)
        {
            throw new DeserializationException("JWT JSON 根对象不能为空");
        }
        for (Object key : value.keySet())
        {
            if (!(key instanceof String))
            {
                throw new DeserializationException("JWT JSON 字段名必须是字符串");
            }
        }
        return (Map<String, ?>) value;
    }

    /**
     * 通过 JJWT 当前流式扩展点写入 Jackson 3 JSON，避免使用已弃用的字节数组便捷方法。
     */
    private static final class JwtSerializer
            extends AbstractSerializer<Map<String, ?>>
    {
        /**
         * 将 JWT 头或声明映射写入 JJWT 提供的输出流，且不关闭调用方流。
         *
         * @param value Map&lt;String, ?&gt;，JJWT 传入的头或声明映射
         * @param output OutputStream，JJWT 管理生命周期的目标流
         * @return void，输入为空或 Jackson 3 写入失败时抛出 SerializationException
         */
        @Override
        protected void doSerialize(Map<String, ?> value, OutputStream output)
        {
            if (value == null || output == null)
            {
                throw new SerializationException("JWT JSON 序列化参数不能为空");
            }
            try
            {
                OBJECT_WRITER.writeValue(output, value);
            }
            catch (JacksonException exception)
            {
                throw new SerializationException("JWT JSON 序列化失败", exception);
            }
        }
    }

    /**
     * 通过 JJWT 当前流式扩展点读取 Jackson 3 JSON，并执行严格对象结构校验。
     */
    private static final class JwtDeserializer
            extends AbstractDeserializer<Map<String, ?>>
    {
        /**
         * 从字符流解析 JWT 头或声明映射。
         *
         * @param reader Reader，JJWT 管理生命周期的 JSON 字符流
         * @return Map&lt;String, ?&gt;，保留 JSON 标量、数组和对象结构的有序映射
         */
        @Override
        protected Map<String, ?> doDeserialize(Reader reader)
        {
            if (reader == null)
            {
                throw new DeserializationException("JWT JSON 读取器不能为空");
            }
            try
            {
                return readObject(OBJECT_MAPPER.readValue(reader, Map.class));
            }
            catch (JacksonException exception)
            {
                throw new DeserializationException("JWT JSON 解析失败", exception);
            }
        }
    }

    /**
     * 将 JJWT 延迟值解包后交回 Jackson 3 的正式类型序列化器。
     */
    private static final class JwtSupplierSerializer extends StdSerializer<Supplier>
    {
        private static final long serialVersionUID = 1L;

        /**
         * 创建 JJWT Supplier 序列化器。
         *
         * @return 无返回值，新实例绑定 JJWT Supplier 接口
         */
        private JwtSupplierSerializer()
        {
            super(Supplier.class);
        }

        /**
         * 解包 JJWT 延迟值并按实际类型写入 JSON。
         *
         * @param supplier Supplier，JJWT 内部延迟提供的头或声明值
         * @param generator JsonGenerator，Jackson 3 当前 JSON 生成器
         * @param context SerializationContext，Jackson 3 当前序列化上下文
         * @return void，实际值为空时写入 null，否则按运行时类型序列化
         * @throws JacksonException 实际值无法安全序列化时抛出
         */
        @Override
        public void serialize(Supplier supplier, JsonGenerator generator,
                SerializationContext context) throws JacksonException
        {
            Object value = supplier.get();
            if (value == null)
            {
                generator.writeNull();
                return;
            }
            context.writeValue(generator, value);
        }
    }
}

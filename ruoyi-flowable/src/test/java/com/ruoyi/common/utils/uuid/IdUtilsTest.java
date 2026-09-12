package com.ruoyi.common.utils.uuid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 登录、验证码和文件路径共用的 UUID 字符串契约。 */
class IdUtilsTest
{
    @ParameterizedTest(name = "{0}")
    @MethodSource("generators")
    void generatesDistinctLowercaseVersion4Ids(String name, Supplier<String> generator,
            boolean compact)
    {
        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 128; i++)
        {
            String id = generator.get();
            assertTrue(id.matches(compact ? "[0-9a-f]{32}"
                    : "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
            String standard = compact ? id.substring(0, 8) + "-" + id.substring(8, 12)
                    + "-" + id.substring(12, 16) + "-" + id.substring(16, 20)
                    + "-" + id.substring(20) : id;
            UUID parsed = UUID.fromString(standard);
            assertEquals(4, parsed.version());
            assertEquals(2, parsed.variant());
            assertTrue(generated.add(id), "连续生成的标识不能复用固定值");
        }
    }

    static Stream<Arguments> generators()
    {
        return Stream.of(
                Arguments.of("randomUUID", (Supplier<String>) IdUtils::randomUUID, false),
                Arguments.of("simpleUUID", (Supplier<String>) IdUtils::simpleUUID, true),
                Arguments.of("fastUUID", (Supplier<String>) IdUtils::fastUUID, false),
                Arguments.of("fastSimpleUUID", (Supplier<String>) IdUtils::fastSimpleUUID, true));
    }
}

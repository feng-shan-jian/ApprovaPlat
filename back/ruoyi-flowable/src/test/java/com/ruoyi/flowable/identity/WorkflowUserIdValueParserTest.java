package com.ruoyi.flowable.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;

/**
 * 表单用户主键共享解析契约测试。
 */
class WorkflowUserIdValueParserTest
{
    /**
     * 验证不同整数运行时类型得到同一规范用户主键。
     * @return void，无返回值；任一整数类型解析结果漂移时测试失败
     */
    @Test
    void acceptsOnlyExactPositiveIntegerNumbers()
    {
        assertThat(WorkflowUserIdValueParser.requirePositiveUserId(7, "非法"))
                .isEqualTo("7");
        assertThat(WorkflowUserIdValueParser.requirePositiveUserId(7L, "非法"))
                .isEqualTo("7");
        assertThat(WorkflowUserIdValueParser.requirePositiveUserId(
                new BigDecimal("7.0"), "非法")).isEqualTo("7");
        assertThat(WorkflowUserIdValueParser.requirePositiveUserId(
                new BigInteger("7"), "非法")).isEqualTo("7");
    }

    /**
     * 验证小数、非有限数、越界、前导零及空值都以稳定业务异常失败关闭。
     * @return void，无返回值；任一歧义值被截断、规范化或放行时测试失败
     */
    @Test
    void rejectsAmbiguousOrOutOfRangeValues()
    {
        for (Object value : new Object[] { 1.5D, Double.NaN, Double.POSITIVE_INFINITY,
                new BigInteger("9223372036854775808"), "01", " 1 ", 0, -1, null })
        {
            assertThatThrownBy(() -> WorkflowUserIdValueParser
                    .requirePositiveUserId(value, "表单用户字段值不合法"))
                    .isInstanceOf(ServiceException.class)
                    .hasMessage("表单用户字段值不合法");
        }
    }
}

package com.ruoyi.flowable.identity;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Pattern;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 表单变量、流程变量和部署规则共用的若依用户主键值解析器。
 */
public final class WorkflowUserIdValueParser
{
    /** 字符串用户主键必须是无前导零的十进制正整数。 */
    private static final Pattern CANONICAL_TEXT = Pattern.compile("[1-9][0-9]{0,18}");

    /** Long 正数上界，避免不同 Number 子类型发生截断或溢出。 */
    private static final BigInteger MAX_USER_ID = BigInteger.valueOf(Long.MAX_VALUE);

    /**
     * 工具类不允许实例化。
     * @return 无返回值
     */
    private WorkflowUserIdValueParser()
    {
    }

    /**
     * 将字符串或任意精确整数 Number 解析为规范用户主键。
     * @param value Object，表单、流程变量或规则中的用户主键值
     * @param message String，解析失败时对外返回的稳定业务提示
     * @return String，无前导零且位于 Long 正数范围内的十进制用户主键
     */
    public static String requirePositiveUserId(Object value, String message)
    {
        try
        {
            BigInteger userId = exactInteger(value);
            if (userId.signum() <= 0 || userId.compareTo(MAX_USER_ID) > 0)
            {
                throw new NumberFormatException("用户主键超出 Long 正数范围");
            }
            return userId.toString();
        }
        catch (ArithmeticException | NumberFormatException exception)
        {
            String stableMessage = message == null || message.isBlank()
                    ? "用户主键值不合法" : message;
            ServiceException failure = new ServiceException(stableMessage, HttpStatus.CONFLICT);
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 按运行时值类型提取数学意义上的精确整数，禁止小数、非有限数和文本歧义。
     * @param value Object，待解析的原始用户主键值
     * @return BigInteger，尚未执行正数与 Long 上界校验的精确整数
     */
    private static BigInteger exactInteger(Object value)
    {
        if (value instanceof BigInteger integer)
        {
            return integer;
        }
        if (value instanceof BigDecimal decimal)
        {
            return decimal.toBigIntegerExact();
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long)
        {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        if (value instanceof Float number)
        {
            if (!Float.isFinite(number)) throw new NumberFormatException("用户主键不是有限数");
            return BigDecimal.valueOf(number.doubleValue()).toBigIntegerExact();
        }
        if (value instanceof Double number)
        {
            if (!Double.isFinite(number)) throw new NumberFormatException("用户主键不是有限数");
            return BigDecimal.valueOf(number).toBigIntegerExact();
        }
        if (value instanceof Number number)
        {
            return new BigDecimal(number.toString()).toBigIntegerExact();
        }
        String text = value instanceof CharSequence sequence ? sequence.toString() : "";
        if (!CANONICAL_TEXT.matcher(text).matches())
        {
            throw new NumberFormatException("用户主键文本不是规范正整数");
        }
        return new BigInteger(text);
    }
}

package com.ruoyi.flowable.service.model;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 受控循环判断字段从正式表单验证器投影出的可执行标量契约。
 *
 * @param name String，Flowable 业务变量名
 * @param kind Kind，服务端标量数据形态
 * @param minLength int，文本最小长度
 * @param maxLength int，文本最大长度
 * @param minimum BigDecimal，可为空的数值下界
 * @param maximum BigDecimal，可为空的数值上界
 * @param numericKind NumericKind，数值字段的整数或小数约束
 * @param enumValues Set&lt;String&gt;，静态单选允许值；空集合表示没有静态枚举约束
 */
public record WorkflowControlledLoopFormField(String name, Kind kind,
        int minLength, int maxLength, BigDecimal minimum, BigDecimal maximum,
        NumericKind numericKind, Set<String> enumValues)
{
    /**
     * 创建不可变的循环判断字段契约。
     * @return 无返回值，静态枚举集合会被复制为不可修改集合
     */
    public WorkflowControlledLoopFormField
    {
        enumValues = enumValues == null ? Set.of() : Set.copyOf(enumValues);
    }

    /** 受控循环允许参与判断的顶层标量数据形态。 */
    public enum Kind
    {
        /** 普通文本或单值日期时间。 */
        TEXT,
        /** 有限数值。 */
        NUMBER,
        /** 布尔开关。 */
        BOOLEAN,
        /** 字符串、数值或布尔单选值。 */
        SCALAR
    }

    /** 数值字段与正式表单校验器一致的执行边界。 */
    public enum NumericKind
    {
        /** 允许有限小数。 */
        DECIMAL,
        /** 有符号 32 位整数。 */
        INTEGER,
        /** 有符号 64 位整数。 */
        LONG
    }
}

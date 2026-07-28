package com.ruoyi.flowable.identity;

import java.util.Locale;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 工作流身份目录允许查询的主数据类型。
 */
public enum WorkflowIdentityOptionType
{
    USER("user"),
    ROLE("role"),
    DEPT("dept");

    private final String value;

    /**
     * 创建身份选项类型。
     *
     * @param value String，对外稳定的小写类型值
     * @return 无返回值，枚举构造完成后保留规范值
     */
    WorkflowIdentityOptionType(String value)
    {
        this.value = value;
    }

    /**
     * 返回接口和 Mapper 共用的规范类型值。
     *
     * @return String，user、role 或 dept
     */
    public String value()
    {
        return value;
    }

    /**
     * 把接口类型值转换为受控枚举，拒绝未知主数据表查询。
     *
     * @param value String，接口传入的身份类型
     * @return WorkflowIdentityOptionType，对应受控身份类型
     */
    public static WorkflowIdentityOptionType fromValue(String value)
    {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (WorkflowIdentityOptionType type : values())
        {
            if (type.value.equals(normalized))
            {
                return type;
            }
        }
        throw new ServiceException("工作流身份类型必须为 user、role 或 dept", HttpStatus.BAD_REQUEST);
    }
}

package com.ruoyi.flowable.service.model;

import java.util.Locale;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 流程定义可执行的目标状态。
 */
public enum WorkflowDefinitionState
{
    /** 激活状态。 */
    ACTIVE("active"),

    /** 挂起状态。 */
    SUSPENDED("suspended");

    /** 对外兼容的状态编码。 */
    private final String code;

    /**
     * 创建流程定义状态枚举。
     *
     * @param code String，对外兼容的状态编码
     * @return 无返回值，构造后得到枚举常量
     */
    WorkflowDefinitionState(String code)
    {
        this.code = code;
    }

    /**
     * 获取对外状态编码。
     *
     * @return String，active 或 suspended
     */
    public String getCode()
    {
        return code;
    }

    /**
     * 将请求状态编码解析为受控枚举，拒绝空值和未知状态。
     *
     * @param code String，请求中的状态编码
     * @return WorkflowDefinitionState，匹配的目标状态
     */
    public static WorkflowDefinitionState fromCode(String code)
    {
        if (code == null || code.isBlank())
        {
            throw new ServiceException("流程定义状态不能为空", HttpStatus.BAD_REQUEST);
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        for (WorkflowDefinitionState state : values())
        {
            if (state.code.equals(normalized))
            {
                return state;
            }
        }
        throw new ServiceException("流程定义状态不合法", HttpStatus.BAD_REQUEST);
    }
}

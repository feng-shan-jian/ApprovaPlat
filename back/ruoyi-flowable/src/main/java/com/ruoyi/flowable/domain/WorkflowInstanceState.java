package com.ruoyi.flowable.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 流程实例可由管理接口切换的两种运行状态。
 */
public enum WorkflowInstanceState
{
    ACTIVE("active", false),
    SUSPENDED("suspended", true);

    private final String value;

    private final boolean suspended;

    /**
     * 创建流程实例状态枚举。
     *
     * @param value String，对外 JSON 协议中的小写状态值
     * @param suspended boolean，该状态是否表示实例已挂起
     * @return 无返回值，构造后的枚举同时保存协议值和引擎状态语义
     */
    WorkflowInstanceState(String value, boolean suspended)
    {
        this.value = value;
        this.suspended = suspended;
    }

    /**
     * 将受控 JSON 状态值转换为枚举，仅接受 active 和 suspended。
     *
     * @param value String，客户端提交的状态值
     * @return WorkflowInstanceState，匹配的实例状态；非法值抛出 IllegalArgumentException
     */
    @JsonCreator
    public static WorkflowInstanceState fromValue(String value)
    {
        if (value == null)
        {
            return null;
        }
        for (WorkflowInstanceState state : values())
        {
            if (state.value.equals(value))
            {
                return state;
            }
        }
        throw new IllegalArgumentException("流程实例状态仅支持active或suspended");
    }

    /**
     * 返回稳定的小写 JSON 状态值。
     *
     * @return String，active 或 suspended
     */
    @JsonValue
    public String value()
    {
        return value;
    }

    /**
     * 判断当前枚举对应的 Flowable 挂起标志。
     *
     * @return boolean，挂起状态返回 true，激活状态返回 false
     */
    public boolean suspended()
    {
        return suspended;
    }
}

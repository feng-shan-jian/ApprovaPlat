package com.ruoyi.flowable.domain;

/**
 * 多实例轮次的可持久化生命周期状态。
 */
public enum WorkflowMultiInstanceRoundStatus
{
    /** 引擎正在执行本轮多实例任务。 */
    ACTIVE,

    /** 本轮已整组退回且正在等待申请人重提。 */
    RETURNED,

    /** 申请人重提后本轮已关闭，后续必须建立新轮次。 */
    REOPENED,

    /** 本轮按 ALL/ANY 规则正常结束。 */
    COMPLETED,

    /** 流程显式终止或 Flowable 原生中断执行树时，本轮异常关闭。 */
    TERMINATED;

    /**
     * 从数据库或边界文本中解析精确状态，禁止大小写或空白兼容。
     *
     * @param value String，待解析的持久化状态值
     * @return WorkflowMultiInstanceRoundStatus，与文本完全一致的状态
     */
    public static WorkflowMultiInstanceRoundStatus require(String value)
    {
        if (value == null)
        {
            throw new IllegalArgumentException("多实例轮次状态不能为空");
        }
        try
        {
            return valueOf(value);
        }
        catch (IllegalArgumentException exception)
        {
            throw new IllegalArgumentException("多实例轮次状态不受支持: " + value,
                    exception);
        }
    }

    /**
     * 判断当前状态是否属于尚未关闭的轮次。
     *
     * @return boolean，ACTIVE 或 RETURNED 时返回 true
     */
    public boolean isOpen()
    {
        return this == ACTIVE || this == RETURNED;
    }

}

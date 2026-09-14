package com.ruoyi.flowable.extension;

/**
 * 条件路由器可安全跨 Flowable 异常包装层传递的受控业务失败。
 */
public class WorkflowConditionRoutingException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    /** 统一异常边界允许对外返回的业务码。 */
    private final int code;

    /**
     * 创建只含稳定业务提示和 HTTP 业务码的条件路由失败。
     * 异常实例由调用方抛出
     */
    public WorkflowConditionRoutingException(String message, int code)
    {
        super(message);
        this.code = code;
    }

    /**
     * 返回受控业务码。
     * @return int，条件路由失败对应的 HTTP 业务码
     */
    public int getCode()
    {
        return code;
    }
}

package com.ruoyi.common.exception;

/**
 * 业务异常
 * 
 * @author ruoyi
 */
public final class ServiceException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    /**
     * 错误码
     */
    private Integer code;

    /**
     * 可选稳定业务子码，用于同一 HTTP 状态下需要客户端精确分流的领域冲突。
     */
    private String subCode;

    /**
     * 错误提示
     */
    private String message;

    /**
     * 错误明细，内部调试错误
     *
     * 和 {@link CommonResult#getDetailMessage()} 一致的设计
     */
    private String detailMessage;

    /**
     * 空构造方法，避免反序列化问题
     */
    public ServiceException()
    {
    }

    public ServiceException(String message)
    {
        this.message = message;
    }

    public ServiceException(String message, Integer code)
    {
        this.message = message;
        this.code = code;
    }

    public String getDetailMessage()
    {
        return detailMessage;
    }

    @Override
    public String getMessage()
    {
        return message;
    }

    public Integer getCode()
    {
        return code;
    }

    /**
     * 读取可选稳定业务子码。
     *
     * @return String，未设置时为 null
     */
    public String getSubCode()
    {
        return subCode;
    }

    /**
     * 设置可选稳定业务子码并保持链式调用。
     *
     * @param subCode String，供客户端精确分流的稳定业务子码
     * @return ServiceException，当前异常对象
     */
    public ServiceException setSubCode(String subCode)
    {
        this.subCode = subCode;
        return this;
    }

    public ServiceException setMessage(String message)
    {
        this.message = message;
        return this;
    }

    public ServiceException setDetailMessage(String detailMessage)
    {
        this.detailMessage = detailMessage;
        return this;
    }
}

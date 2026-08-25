package com.ruoyi.flowable.service.task;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 任务生命周期请求 ID、意见和对象一致性的统一输入门禁。
 */
@Component
public class WorkflowTaskRequestValidator
{
    /** 流程、任务和节点主键的服务端安全长度上限。 */
    private static final int MAX_ID_LENGTH = 255;

    /** 客户端业务意见写入引擎前允许的最大字符数。 */
    private static final int MAX_OPINION_LENGTH = 500;

    /**
     * 校验并规范化流程或任务主键。
     *
     * @param value String，客户端提交的流程、任务或节点主键
     * @return String，去除首尾空白后的合法主键
     */
    public String requireId(String value)
    {
        if (!StringUtils.hasText(value))
        {
            throw invalidArgument();
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_ID_LENGTH)
        {
            throw invalidArgument();
        }
        return normalized;
    }

    /**
     * 校验并规范化取消、撤回或审批意见。
     *
     * @param value String，客户端提交的业务意见
     * @return String，去除首尾空白后的合法意见
     */
    public String requireOpinion(String value)
    {
        if (!StringUtils.hasText(value))
        {
            throw invalidArgument();
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_OPINION_LENGTH)
        {
            throw invalidArgument();
        }
        return normalized;
    }

    /**
     * 校验客户端对象主键与服务端真实对象完全一致。
     *
     * @param expected String，客户端已规范化主键
     * @param actual String，服务端持久化对象中的真实主键
     * @return 无返回值，不一致时抛出既有 HTTP 400
     */
    public void requireSame(String expected, String actual)
    {
        if (!expected.equals(actual))
        {
            throw invalidArgument();
        }
    }

    /**
     * 创建稳定请求参数错误。
     *
     * @return ServiceException，既有 HTTP 400 错误
     */
    public ServiceException invalidArgument()
    {
        return new ServiceException("工作流请求参数不合法", HttpStatus.BAD_REQUEST);
    }
}

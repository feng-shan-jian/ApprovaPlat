package com.ruoyi.flowable.service.notification;

import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/** 通知幂等来源键的规范化，保持数据库 ascii_bin 键和哈希输入不变。 */
final class WorkflowNotificationSourceId
{
    private WorkflowNotificationSourceId()
    {
    }

    static String normalize(String value)
    {
        if (!StringUtils.hasText(value))
        {
            throw invalid();
        }
        String normalized = value.trim();
        if (normalized.length() > 191
                || normalized.chars().anyMatch(character -> character < 0x21 || character > 0x7e))
        {
            throw invalid();
        }
        return normalized;
    }

    private static ServiceException invalid()
    {
        return new ServiceException("通知来源标识不合法", HttpStatus.BAD_REQUEST);
    }
}

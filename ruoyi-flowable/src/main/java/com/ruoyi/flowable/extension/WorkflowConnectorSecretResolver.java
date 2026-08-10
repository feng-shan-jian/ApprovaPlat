package com.ruoyi.flowable.extension;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * HTTP 连接器外部密钥引用解析器。
 *
 * 当前实现只允许从固定环境变量命名空间读取，数据库、BPMN、日志和调用台账均不保存密钥正文。
 */
@Component
public class WorkflowConnectorSecretResolver
{
    /** 连接器密钥环境变量命名空间。 */
    private static final Pattern SECRET_REF = Pattern.compile("WORKFLOW_CONNECTOR_SECRET_[A-Z0-9_]{1,96}");

    /**
     * 解析必须存在的连接器密钥。
     * @param secretRef String，数据库冻结的环境变量引用
     * @return String，当前进程注入的密钥正文；调用方不得记录
     */
    public String requireSecret(String secretRef)
    {
        if (secretRef == null || !SECRET_REF.matcher(secretRef).matches())
        {
            throw new ServiceException("连接器密钥引用不合法", HttpStatus.ERROR);
        }
        String secret = System.getenv(secretRef);
        if (secret == null || secret.isBlank() || secret.length() > 8192)
        {
            throw new ServiceException("连接器外部密钥未注入或长度不合法", HttpStatus.ERROR);
        }
        return secret;
    }
}

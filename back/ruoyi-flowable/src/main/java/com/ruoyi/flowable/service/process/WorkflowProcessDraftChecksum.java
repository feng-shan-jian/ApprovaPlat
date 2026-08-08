package com.ruoyi.flowable.service.process;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormView;

/**
 * 草稿绑定的部署表单快照正文摘要工具。
 */
public final class WorkflowProcessDraftChecksum
{
    /** 禁止实例化静态摘要工具。 */
    private WorkflowProcessDraftChecksum()
    {
    }

    /**
     * 对部署表单原始正文计算稳定 SHA-256。
     *
     * @param snapshot WorkflowProcessFormView，已由服务端核验的部署表单快照
     * @return String，64 位小写十六进制 SHA-256
     */
    public static String sha256(WorkflowProcessFormView snapshot)
    {
        if (snapshot == null)
        {
            throw new ServiceException("流程部署表单快照不能为空", HttpStatus.ERROR);
        }
        return sha256(snapshot.content());
    }

    /**
     * 对草稿持久化的部署表单正文计算稳定摘要。
     *
     * @param content String，部署表单 JSON
     * @return String，64 位小写十六进制 SHA-256
     */
    public static String sha256(String content)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (content == null)
            {
                throw new ServiceException("流程部署表单快照正文异常", HttpStatus.ERROR);
            }
            digest.update(content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }
}

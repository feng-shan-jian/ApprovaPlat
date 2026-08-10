package com.ruoyi.flowable.extension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * BPMN 扩展版本和部署快照的稳定 SHA-256 计算器。
 */
public final class WorkflowExtensionChecksum
{
    /** 禁止实例化纯函数工具类。 */
    private WorkflowExtensionChecksum()
    {
    }

    /**
     * 使用长度前缀拼接字段后计算摘要，避免普通分隔符产生歧义碰撞。
     * @param values String[]，按业务协议固定顺序提供的非空或可空字段
     * @return String，64 位小写 SHA-256 十六进制
     */
    public static String sha256(String... values)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values)
            {
                byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }
}

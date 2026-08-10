package com.ruoyi.system.domain.integration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * S3 兼容对象存储配置新增或修改请求。
 *
 * @param configId Long，修改时的正式配置主键，新增时为空
 * @param configName String，配置显示名称
 * @param endpoint String，S3 兼容 HTTPS 服务根地址
 * @param region String，签名地域
 * @param bucketName String，已经由运维创建的存储桶
 * @param accessKey String，访问密钥 ID
 * @param secretKey String，访问密钥；修改时留空表示保留原密钥
 * @param domain String，可空的公开访问域名
 * @param prefix String，可空的对象键业务前缀
 * @param pathStyle String，Y 表示路径风格，N 表示虚拟主机风格
 * @param accessPolicy String，PRIVATE 或 PUBLIC
 * @param remark String，管理员备注
 */
public record OssConfigRequest(
        Long configId,
        @NotBlank @Size(max = 64) String configName,
        @NotBlank @Size(max = 255) String endpoint,
        @NotBlank @Size(max = 64) String region,
        @NotBlank @Size(max = 128) String bucketName,
        @NotBlank @Size(max = 128) String accessKey,
        @Size(max = 256) String secretKey,
        @Size(max = 255) String domain,
        @Size(max = 128) String prefix,
        @NotBlank @Pattern(regexp = "Y|N") String pathStyle,
        @NotBlank @Pattern(regexp = "PRIVATE|PUBLIC") String accessPolicy,
        @Size(max = 500) String remark)
{
}

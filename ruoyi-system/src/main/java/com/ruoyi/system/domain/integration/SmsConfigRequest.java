package com.ruoyi.system.domain.integration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 短信供应商配置新增或修改请求。
 *
 * @param configId Long，修改时的正式配置主键，新增时为空
 * @param configName String，管理员可辨识的配置名称
 * @param provider String，ALIYUN 或 TENCENT
 * @param accessKeyId String，供应商访问密钥 ID
 * @param accessKeySecret String，供应商访问密钥；修改时留空表示保留原密钥
 * @param signName String，已在供应商审核通过的短信签名
 * @param sdkAppId String，腾讯云短信应用 ID；阿里云配置可空
 * @param region String，腾讯云地域；阿里云配置可空
 * @param remark String，管理员备注
 */
public record SmsConfigRequest(
        Long configId,
        @NotBlank @Size(max = 64) String configName,
        @NotBlank @Pattern(regexp = "ALIYUN|TENCENT") String provider,
        @NotBlank @Size(max = 128) String accessKeyId,
        @Size(max = 256) String accessKeySecret,
        @NotBlank @Size(max = 64) String signName,
        @Size(max = 64) String sdkAppId,
        @Size(max = 64) String region,
        @Size(max = 500) String remark)
{
}

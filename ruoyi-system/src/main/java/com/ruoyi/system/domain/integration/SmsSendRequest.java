package com.ruoyi.system.domain.integration;

import java.util.Map;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 短信测试或业务投递请求。
 *
 * @param phones String，逗号分隔的国际或中国大陆手机号，单次最多 20 个
 * @param templateId String，供应商审核通过的模板 ID
 * @param parameters Map&lt;String,String&gt;，阿里云使用变量名，腾讯云按数字键顺序取值
 */
public record SmsSendRequest(
        @NotBlank @Size(max = 400) String phones,
        @NotBlank @Size(max = 64) String templateId,
        @NotNull Map<String, String> parameters)
{
}

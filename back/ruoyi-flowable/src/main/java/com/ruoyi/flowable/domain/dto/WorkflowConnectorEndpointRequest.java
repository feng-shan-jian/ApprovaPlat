package com.ruoyi.flowable.domain.dto;

import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * HTTP 连接器端点新增或修订请求。
 *
 * @param endpointKey String，新增时使用的稳定键；修订时必须与原键一致
 * @param endpointName String，用户可见名称
 * @param baseUrl String，基础 URL
 * @param allowedMethods List&lt;String&gt;，允许的 HTTP 方法
 * @param pathPrefix String，允许路径前缀
 * @param authType String，NONE、BEARER 或 API_KEY
 * @param secretRef String，外部环境密钥引用
 * @param apiKeyHeader String，API_KEY 模式请求头名称
 * @param connectTimeoutMs Integer，连接超时毫秒
 * @param requestTimeoutMs Integer，请求超时毫秒
 * @param networkScope String，PUBLIC 或 PRIVATE
 */
public record WorkflowConnectorEndpointRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_.-]{0,127}$") String endpointKey,
        @NotBlank @Size(max = 128) String endpointName,
        @NotBlank @Size(max = 1024) String baseUrl,
        @NotEmpty @Size(max = 5) List<@Pattern(regexp = "^(GET|POST|PUT|PATCH|DELETE)$") String> allowedMethods,
        @NotBlank @Size(max = 512) String pathPrefix,
        @NotBlank @Pattern(regexp = "^(NONE|BEARER|API_KEY)$") String authType,
        @Size(max = 128) String secretRef,
        @Size(max = 128) String apiKeyHeader,
        @NotNull @Min(100) @Max(10000) Integer connectTimeoutMs,
        @NotNull @Min(500) @Max(120000) Integer requestTimeoutMs,
        @NotBlank @Pattern(regexp = "^(PUBLIC|PRIVATE)$") String networkScope)
{
}

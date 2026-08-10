package com.ruoyi.flowable.domain.vo;

/**
 * 服务端已安装 Java 处理器最小视图。
 *
 * @param implementationKey String，处理器稳定键
 * @param name String，用户可见名称
 * @param configSchema String，处理器配置 JSON Schema
 */
public record WorkflowInstalledJavaHandlerView(String implementationKey, String name,
        String configSchema)
{
}

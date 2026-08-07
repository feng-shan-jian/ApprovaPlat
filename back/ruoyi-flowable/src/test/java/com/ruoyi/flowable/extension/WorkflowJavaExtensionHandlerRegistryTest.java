package com.ruoyi.flowable.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 服务端 Java 处理器注册表的启动期唯一性和运行期白名单测试。
 */
class WorkflowJavaExtensionHandlerRegistryTest
{
    /**
     * 验证注册表按稳定键排序展示并返回唯一安装实例。
     * @return 无返回值；排序或解析行为漂移时测试失败
     */
    @Test
    void listsAndResolvesInstalledHandlers()
    {
        WorkflowJavaExtensionHandler second = handler("Z_HANDLER", "后置处理");
        WorkflowJavaExtensionHandler first = handler("A_HANDLER", "前置处理");
        WorkflowJavaExtensionHandlerRegistry registry =
                new WorkflowJavaExtensionHandlerRegistry(List.of(second, first));

        assertThat(registry.list()).extracting("implementationKey")
                .containsExactly("A_HANDLER", "Z_HANDLER");
        assertThat(registry.require("Z_HANDLER")).isSameAs(second);
    }

    /**
     * 验证重复稳定键会阻止应用构建注册表，避免处理器选择取决于 Bean 顺序。
     * @return 无返回值；重复键未被拒绝时测试失败
     */
    @Test
    void rejectsDuplicateImplementationKeys()
    {
        assertThatThrownBy(() -> new WorkflowJavaExtensionHandlerRegistry(
                List.of(handler("DUPLICATE", "处理器一"), handler("DUPLICATE", "处理器二"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Java 扩展处理器标识重复");
    }

    /**
     * 验证数据库引用未安装处理器时返回 409，禁止退化为任意 Bean 或类名执行。
     * @return 无返回值；异常边界不一致时测试失败
     */
    @Test
    void rejectsMissingHandlerWithConflict()
    {
        WorkflowJavaExtensionHandlerRegistry registry =
                new WorkflowJavaExtensionHandlerRegistry(List.of());

        assertThatThrownBy(() -> registry.require("MISSING"))
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("未安装或已移除");
    }

    /**
     * 创建具备固定元数据的处理器替身。
     * @param key String，处理器稳定键
     * @param name String，用户可见名称
     * @return WorkflowJavaExtensionHandler，测试处理器替身
     */
    private WorkflowJavaExtensionHandler handler(String key, String name)
    {
        WorkflowJavaExtensionHandler handler = mock(WorkflowJavaExtensionHandler.class);
        when(handler.implementationKey()).thenReturn(key);
        when(handler.displayName()).thenReturn(name);
        when(handler.configSchema()).thenReturn("{\"type\":\"object\"}");
        return handler;
    }
}

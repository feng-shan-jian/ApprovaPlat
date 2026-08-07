package com.ruoyi.flowable.extension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.vo.WorkflowInstalledJavaHandlerView;

/**
 * 服务端已安装 Java 处理器注册表。
 */
@Component
public class WorkflowJavaExtensionHandlerRegistry
{
    /** 按稳定键冻结的处理器映射，应用启动后不可变更。 */
    private final Map<String, WorkflowJavaExtensionHandler> handlers;

    /**
     * 收集 Spring 中显式实现处理器契约的组件，并在重复键时阻止应用启动。
     * @param installedHandlers List&lt;WorkflowJavaExtensionHandler&gt;，服务端代码安装的处理器集合
     * @return 无返回值，构造后形成不可变注册表
     */
    public WorkflowJavaExtensionHandlerRegistry(List<WorkflowJavaExtensionHandler> installedHandlers)
    {
        Map<String, WorkflowJavaExtensionHandler> registered = new LinkedHashMap<>();
        for (WorkflowJavaExtensionHandler handler : installedHandlers)
        {
            Objects.requireNonNull(handler, "Java 扩展处理器不能为空");
            String key = Objects.requireNonNull(handler.implementationKey(), "Java 扩展处理器标识不能为空");
            if (registered.putIfAbsent(key, handler) != null)
            {
                throw new IllegalStateException("Java 扩展处理器标识重复: " + key);
            }
        }
        handlers = Map.copyOf(registered);
    }

    /**
     * 查询已安装处理器的最小管理视图。
     * @return List&lt;WorkflowInstalledJavaHandlerView&gt;，按稳定键排序的不可变列表
     */
    public List<WorkflowInstalledJavaHandlerView> list()
    {
        return handlers.values().stream()
                .sorted(java.util.Comparator.comparing(WorkflowJavaExtensionHandler::implementationKey))
                .map(handler -> new WorkflowInstalledJavaHandlerView(handler.implementationKey(),
                        handler.displayName(), handler.configSchema()))
                .toList();
    }

    /**
     * 按稳定键取得已安装处理器，数据库不得把任意类名或 Bean 变成执行入口。
     * @param implementationKey String，扩展版本冻结的处理器稳定键
     * @return WorkflowJavaExtensionHandler，服务端安装的唯一实现
     */
    public WorkflowJavaExtensionHandler require(String implementationKey)
    {
        WorkflowJavaExtensionHandler handler = handlers.get(implementationKey);
        if (handler == null)
        {
            throw new ServiceException("Java 扩展处理器未安装或已移除", HttpStatus.CONFLICT);
        }
        return handler;
    }
}

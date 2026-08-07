package com.ruoyi.flowable.extension;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.task.service.delegate.DelegateTask;
import tools.jackson.databind.JsonNode;

/**
 * 服务端预安装 Java 扩展处理器契约。
 *
 * 处理器只能通过稳定键进入注册表，BPMN 不得保存实现类名或 Spring Bean 名称。
 */
public interface WorkflowJavaExtensionHandler
{
    /**
     * 返回服务端安装处理器的稳定键。
     * @return String，仅含大写字母、数字和下划线的稳定键
     */
    String implementationKey();

    /**
     * 返回处理器的用户可见名称。
     * @return String，设计和管理页面展示名称
     */
    String displayName();

    /**
     * 返回处理器配置 JSON Schema。
     * @return String，服务端固定且可解析的 JSON Schema
     */
    String configSchema();

    /**
     * 校验并规范化单节点配置；部署和运行时必须复用同一实现。
     * @param config JsonNode，客户端提供或快照回读的配置对象
     * @return String，字段顺序确定的规范 JSON
     */
    String validateAndNormalizeConfig(JsonNode config);

    /**
     * 使用已校验快照配置执行真实 Flowable 业务动作。
     * @param execution DelegateExecution，当前 Flowable 活动执行上下文
     * @param config JsonNode，运行前重新校验通过的不可变配置
     * @return void，无返回值
     */
    void execute(DelegateExecution execution, JsonNode config);

    /**
     * 声明当前安装处理器是否允许作为受控业务监听器执行。
     * @return boolean，默认 false，未显式实现的服务任务处理器不能进入监听器入口
     */
    default boolean supportsBusinessListener()
    {
        return false;
    }

    /**
     * 使用已校验快照配置执行真实 Flowable 任务监听动作。
     * @param task DelegateTask，当前用户任务监听上下文
     * @param config JsonNode，运行前重新校验通过的不可变配置
     * @return void，无返回值；默认拒绝未声明任务监听能力的处理器
     */
    default void executeTask(DelegateTask task, JsonNode config)
    {
        throw new UnsupportedOperationException("当前 Java 扩展不支持任务监听器");
    }
}

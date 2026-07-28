package com.ruoyi.flowable.engine;

import org.flowable.task.api.Task;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;

/**
 * 在引擎任务写命令中准备业务侧事务计划，并在引擎状态变更成功后执行同事务写入。
 */
@FunctionalInterface
public interface WorkflowTaskWriteHook
{
    /**
     * 使用事务内可信身份和动作前任务快照准备业务计划，返回的动作仅在引擎命令成功后执行。
     *
     * @param actor WorkflowCurrentIdentity，事务内重新核验的当前操作人
     * @param task Task，完成对象权限及状态校验的动作前活动任务
     * @return Runnable，引擎动作成功后在同一事务执行的业务写入，不允许返回 null
     */
    Runnable prepare(WorkflowCurrentIdentity actor, Task task);

    /**
     * 创建不附加业务写入的事务钩子，供兼容原有适配器调用方式使用。
     *
     * @return WorkflowTaskWriteHook，准备及成功阶段均不产生副作用的钩子
     */
    static WorkflowTaskWriteHook none()
    {
        return (actor, task) -> () ->
        {
        };
    }
}

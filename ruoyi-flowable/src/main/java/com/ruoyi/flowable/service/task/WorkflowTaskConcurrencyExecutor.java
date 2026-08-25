package com.ruoyi.flowable.service.task;

import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 生命周期预检后 Flowable 对象并发消失的稳定冲突翻译器。
 */
@Component
public class WorkflowTaskConcurrencyExecutor
{
    /**
     * 执行单事务内的并发敏感写链。
     *
     * @param action Runnable，已完成只读预检的原子写动作
     * @return 无返回值，对象并发消失时抛出既有 HTTP 409
     */
    public void execute(Runnable action)
    {
        try
        {
            action.run();
        }
        catch (FlowableObjectNotFoundException exception)
        {
            ServiceException failure = new ServiceException(
                    "工作流状态已发生变化，请刷新后重试", HttpStatus.CONFLICT);
            failure.initCause(exception);
            throw failure;
        }
    }
}

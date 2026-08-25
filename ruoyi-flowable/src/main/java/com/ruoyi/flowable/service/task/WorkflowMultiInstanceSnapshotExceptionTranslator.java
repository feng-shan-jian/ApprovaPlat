package com.ruoyi.flowable.service.task;

import java.util.function.Supplier;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 受控多实例运行时快照漂移到既有外部错误契约的唯一翻译边界。
 */
final class WorkflowMultiInstanceSnapshotExceptionTranslator
{
    /** 禁止实例化纯异常翻译组件。 */
    private WorkflowMultiInstanceSnapshotExceptionTranslator()
    {
    }

    /**
     * 按轮次服务错误契约执行只读快照操作。
     *
     * @param <T> 返回事实类型
     * @param action Supplier&lt;T&gt;，只读快照动作
     * @return T，读取成功的不可变事实
     */
    static <T> T asRoundDataError(Supplier<T> action)
    {
        return translate(action, "工作流多实例轮次状态不一致", HttpStatus.ERROR);
    }

    /**
     * 按轮次服务错误契约执行无返回值快照核验。
     *
     * @param action Runnable，只读快照核验动作
     * @return 无返回值，漂移时抛出稳定 HTTP 500
     */
    static void asRoundDataError(Runnable action)
    {
        asRoundDataError(() ->
        {
            action.run();
            return null;
        });
    }

    /**
     * 按动态多实例服务错误契约执行只读快照操作。
     *
     * @param <T> 返回事实类型
     * @param action Supplier&lt;T&gt;，只读快照动作
     * @return T，读取成功的不可变事实
     */
    static <T> T asMultiInstanceDataError(Supplier<T> action)
    {
        return translate(action, "工作流多实例状态不一致", HttpStatus.ERROR);
    }

    /**
     * 按动态多实例服务错误契约执行无返回值快照核验。
     *
     * @param action Runnable，只读快照核验动作
     * @return 无返回值，漂移时抛出稳定 HTTP 500
     */
    static void asMultiInstanceDataError(Runnable action)
    {
        asMultiInstanceDataError(() ->
        {
            action.run();
            return null;
        });
    }

    /**
     * 按任务迁移写后验证契约执行只读快照操作。
     *
     * @param <T> 返回事实类型
     * @param action Supplier&lt;T&gt;，写后只读快照动作
     * @return T，读取成功的不可变事实
     */
    static <T> T asTransitionConflict(Supplier<T> action)
    {
        return translate(action, "工作流状态已发生变化，请刷新后重试",
                HttpStatus.CONFLICT);
    }

    /**
     * 只捕获读取器内部漂移异常并保留原因为稳定领域错误。
     *
     * @param <T> 返回事实类型
     * @param action Supplier&lt;T&gt;，只读快照动作
     * @param message String，既有外部错误消息
     * @param status int，既有 HTTP 状态码
     * @return T，读取成功的事实
     */
    private static <T> T translate(Supplier<T> action, String message, int status)
    {
        try
        {
            return action.get();
        }
        catch (WorkflowMultiInstanceSnapshotDriftException exception)
        {
            ServiceException failure = new ServiceException(message, status);
            failure.initCause(exception);
            throw failure;
        }
    }
}

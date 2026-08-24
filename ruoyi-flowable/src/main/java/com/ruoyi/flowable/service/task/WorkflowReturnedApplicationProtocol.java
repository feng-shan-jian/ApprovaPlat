package com.ruoyi.flowable.service.task;

/**
 * 退回申请、申请人修改和受控迁移共享的稳定内部协议。
 *
 * <p>这些名称已经写入 Flowable 变量或被监听器读取，属于跨服务持久化协议，不能由某个
 * 生命周期门面私有持有，也不能在通知、轮次等服务中重复定义。</p>
 */
public final class WorkflowReturnedApplicationProtocol
{
    /** 申请退回发起人修改期间的稳定流程和业务状态。 */
    public static final String RETURNED_STATUS = "returned";

    /** 退回任务局部保存原办理配置的稳定变量名。 */
    public static final String RETURN_ASSIGNMENT_VARIABLE =
            "__ruoyi_workflow_return_assignment";

    /** 退回任务局部保存原申请人主键的稳定变量名。 */
    public static final String RETURN_APPLICANT_VARIABLE =
            "__ruoyi_workflow_return_applicant";

    /** 受控状态迁移期间抑制中间通知的稳定流程变量名。 */
    public static final String CONTROLLED_TRANSITION_VARIABLE =
            "__ruoyi_workflow_notification_transition";

    /** 退回命令写入受控迁移变量的稳定标记。 */
    public static final String RETURN_TRANSITION_MARKER = "RETURN";

    /** 重提命令写入受控迁移变量的稳定标记。 */
    public static final String RESUBMIT_TRANSITION_MARKER = "RESUBMIT";

    /**
     * 禁止实例化纯协议类。
     *
     * @return 无返回值，该构造函数始终不可访问
     */
    private WorkflowReturnedApplicationProtocol()
    {
    }
}

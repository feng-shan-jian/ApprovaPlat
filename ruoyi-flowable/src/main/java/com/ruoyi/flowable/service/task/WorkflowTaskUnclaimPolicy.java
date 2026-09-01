package com.ruoyi.flowable.service.task;

import org.springframework.stereotype.Component;

/**
 * 根据服务端冻结的流程、任务和身份事实计算取消认领能力。
 */
@Component
public class WorkflowTaskUnclaimPolicy
{
    /**
     * 判断当前身份能否取消任务认领。
     *
     * @param processActive boolean，流程实例是否仍处于活动态且未挂起
     * @param taskActive boolean，任务是否仍为运行时活动任务
     * @param taskSuspended boolean，任务是否已经挂起
     * @param currentUserId String，服务端解析的当前用户主键
     * @param assignee String，任务当前办理人主键
     * @param owner String，任务 owner 主键，允许为空
     * @param delegationState String，任务委派状态，允许为空
     * @param claimedBy String，Flowable 记录的真实认领用户主键，允许为空
     * @param claimTimePresent boolean，Flowable 是否记录了真实认领时间
     * @return boolean，全部服务端事实同时满足取消认领合同才返回 true
     */
    public boolean isUnclaimAllowed(boolean processActive, boolean taskActive,
            boolean taskSuspended, String currentUserId, String assignee,
            String owner, String delegationState, String claimedBy,
            boolean claimTimePresent)
    {
        // 取消认领只属于仍可写的运行时任务；任一生命周期事实漂移都必须失败关闭。
        if (!processActive || !taskActive || taskSuspended || currentUserId == null)
        {
            return false;
        }
        // owner 和 delegationState 只接受真实 null；空字符串同样是非法引擎状态，不能开放能力。
        return currentUserId.equals(assignee)
                && owner == null
                && delegationState == null
                && currentUserId.equals(claimedBy)
                && claimTimePresent;
    }
}

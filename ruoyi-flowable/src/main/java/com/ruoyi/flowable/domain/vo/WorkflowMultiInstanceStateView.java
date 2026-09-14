package com.ruoyi.flowable.domain.vo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;

/**
 * 当前活动并行多实例的服务端状态和并发前置版本。
 *
 * @param mode String，固定 ALL 或 ANY
 * @param activityId String，受控 BPMN 用户任务活动 ID
 * @param revision long，下一次调整必须携带的服务端 revision
 * @param adjustmentAllowed boolean，当前用户是否至少可执行一次加签或减签
 * @param addAllowed boolean，当前用户是否可进入正式加签写链
 * @param remainingCapacity int，距离正式成员上限的剩余容量
 * @param members List&lt;WorkflowMultiInstanceMemberView&gt;，按正式成员快照顺序返回的状态
 */
public record WorkflowMultiInstanceStateView(String mode, String activityId,
        long revision, boolean adjustmentAllowed, boolean addAllowed,
        int remainingCapacity, List<WorkflowMultiInstanceMemberView> members)
{
    /**
     * 创建多实例状态并复制成员集合，避免 Controller 序列化期间状态被修改。
     *
     * 构造后 members 为不可修改集合
     */
    public WorkflowMultiInstanceStateView
    {
        if (members == null || members.isEmpty())
        {
            throw new IllegalArgumentException("多实例成员状态不能为空");
        }
        members = Collections.unmodifiableList(new ArrayList<>(members));
        if (members.size() > WorkflowUserSelectionValidator.MAX_SELECTED_USERS)
        {
            throw new IllegalArgumentException("多实例成员数量超过上限");
        }
        // 剩余容量是正式成员快照的派生事实，必须在 HTTP 投影边界再次拒绝不一致状态。
        int expectedRemainingCapacity = WorkflowUserSelectionValidator.MAX_SELECTED_USERS
                - members.size();
        if (remainingCapacity != expectedRemainingCapacity)
        {
            throw new IllegalArgumentException("多实例剩余容量与成员数量矛盾");
        }
        boolean hasRemovableMember = members.stream()
                .anyMatch(WorkflowMultiInstanceMemberView::removable);
        if (addAllowed && remainingCapacity == 0)
        {
            throw new IllegalArgumentException("多实例加签能力与剩余容量矛盾");
        }
        if (adjustmentAllowed != (addAllowed || hasRemovableMember))
        {
            throw new IllegalArgumentException("多实例调整能力与成员状态矛盾");
        }
    }
}

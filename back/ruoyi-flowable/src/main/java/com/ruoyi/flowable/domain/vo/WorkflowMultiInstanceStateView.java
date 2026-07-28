package com.ruoyi.flowable.domain.vo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 当前活动并行多实例的服务端状态和并发前置版本。
 *
 * @param mode String，固定 ALL 或 ANY
 * @param activityId String，受控 BPMN 用户任务活动 ID
 * @param revision long，下一次调整必须携带的服务端 revision
 * @param members List&lt;WorkflowMultiInstanceMemberView&gt;，按正式成员快照顺序返回的状态
 */
public record WorkflowMultiInstanceStateView(String mode, String activityId,
        long revision, List<WorkflowMultiInstanceMemberView> members)
{
    /**
     * 创建多实例状态并复制成员集合，避免 Controller 序列化期间状态被修改。
     *
     * @param mode String，ALL 或 ANY
     * @param activityId String，BPMN 活动 ID
     * @param revision long，当前调整版本
     * @param members List&lt;WorkflowMultiInstanceMemberView&gt;，有序成员状态
     * @return 无返回值，构造后 members 为不可修改集合
     */
    public WorkflowMultiInstanceStateView
    {
        if (members == null)
        {
            throw new IllegalArgumentException("多实例成员状态不能为空");
        }
        members = Collections.unmodifiableList(new ArrayList<>(members));
    }
}

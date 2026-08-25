package com.ruoyi.flowable.service.task;

import java.util.List;
import java.util.Objects;

/**
 * 普通退回时冻结的首审批任务办理配置。
 *
 * @param assignee String，原直接办理人，候选任务时为空
 * @param owner String，原任务所有者，允许为空
 * @param candidateUserIds List&lt;String&gt;，原候选用户主键
 * @param candidateGroupIds List&lt;String&gt;，原候选组编码
 */
public record ReturnedAssignmentSnapshot(String assignee, String owner,
        List<String> candidateUserIds, List<String> candidateGroupIds)
{
    /**
     * 校验并冻结候选身份集合。
     *
     * @param assignee String，原直接办理人
     * @param owner String，原任务所有者
     * @param candidateUserIds List&lt;String&gt;，原候选用户
     * @param candidateGroupIds List&lt;String&gt;，原候选组
     * @return 无返回值，构造后集合不可修改
     */
    public ReturnedAssignmentSnapshot
    {
        candidateUserIds = List.copyOf(Objects.requireNonNull(candidateUserIds));
        candidateGroupIds = List.copyOf(Objects.requireNonNull(candidateGroupIds));
    }
}

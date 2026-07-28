package com.ruoyi.flowable.identity;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 当前登录用户在工作流中的规范身份。
 *
 * @param userId String，规范化后的若依用户 ID
 * @param candidateGroups Set&lt;String&gt;，当前用户有效的角色和部门候选组
 */
public record WorkflowCurrentIdentity(String userId, Set<String> candidateGroups)
{
    /**
     * 创建不可变的当前工作流身份，防止调用方修改候选组后绕过权限判断。
     *
     * @param userId String，规范化后的若依用户 ID
     * @param candidateGroups Set&lt;String&gt;，当前用户有效的候选组
     * @return 无返回值，构造后 record 保存不可变候选组副本
     */
    public WorkflowCurrentIdentity
    {
        Objects.requireNonNull(userId, "工作流用户标识不能为空");
        Objects.requireNonNull(candidateGroups, "工作流候选组不能为空");
        candidateGroups = Collections.unmodifiableSet(new LinkedHashSet<>(candidateGroups));
    }
}

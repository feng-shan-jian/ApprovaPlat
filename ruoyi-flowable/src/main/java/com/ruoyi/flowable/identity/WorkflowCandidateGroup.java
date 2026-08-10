package com.ruoyi.flowable.identity;

/**
 * 已通过格式和数值范围校验的 Flowable 候选组。
 *
 * @param type WorkflowCandidateGroupType，候选组类型
 * @param id long，若依角色或部门主键
 */
public record WorkflowCandidateGroup(WorkflowCandidateGroupType type, long id)
{
}

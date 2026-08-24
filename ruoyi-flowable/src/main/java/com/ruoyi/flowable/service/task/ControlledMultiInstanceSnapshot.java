package com.ruoyi.flowable.service.task;

import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.util.StringUtils;

/**
 * 一次命令读取的受控多实例定义、执行树、成员和任务完整不可变快照。
 *
 * @param deployId String，部署主键
 * @param processDefinitionId String，流程定义主键
 * @param processInstanceId String，流程实例主键
 * @param activityId String，受控用户任务节点
 * @param rootExecutionId String，多实例根 execution 主键
 * @param sourceTaskId String，触发读取的活动任务主键
 * @param sourceExecutionId String，来源任务 child execution 主键
 * @param mode WorkflowMultiInstanceMode，部署与流程变量共同确认的 ALL/ANY 模式
 * @param members List&lt;String&gt;，有序正式成员快照
 * @param revision int，实时 revision
 * @param counts MultiInstanceEngineCounts，根局部计数
 * @param activeTasks List&lt;MultiInstanceActiveTaskSnapshot&gt;，按稳定顺序排列的活动成员任务
 * @param childExecutionIds List&lt;String&gt;，根下全部活动及已完成 child execution 主键
 */
public record ControlledMultiInstanceSnapshot(String deployId,
        String processDefinitionId, String processInstanceId, String activityId,
        String rootExecutionId, String sourceTaskId, String sourceExecutionId,
        WorkflowMultiInstanceMode mode, List<String> members, int revision,
        MultiInstanceEngineCounts counts,
        List<MultiInstanceActiveTaskSnapshot> activeTasks,
        List<String> childExecutionIds)
{
    /**
     * 复制所有集合并校验身份、成员、计数、任务和 execution 之间严格闭合。
     *
     * @return 无返回值，漂移或不完整快照拒绝构造
     */
    public ControlledMultiInstanceSnapshot
    {
        if (!StringUtils.hasText(deployId)
                || !StringUtils.hasText(processDefinitionId)
                || !StringUtils.hasText(processInstanceId)
                || !StringUtils.hasText(activityId)
                || !StringUtils.hasText(rootExecutionId)
                || !StringUtils.hasText(sourceTaskId)
                || !StringUtils.hasText(sourceExecutionId)
                || mode == null || revision < 0 || counts == null
                || members == null || activeTasks == null
                || childExecutionIds == null)
        {
            throw new IllegalArgumentException("受控多实例运行时快照不完整");
        }
        List<String> immutableMembers = List.copyOf(members);
        List<MultiInstanceActiveTaskSnapshot> immutableActiveTasks = List.copyOf(
                activeTasks);
        List<String> immutableChildExecutionIds = List.copyOf(childExecutionIds);
        if (immutableMembers.isEmpty()
                || new LinkedHashSet<>(immutableMembers).size() != immutableMembers.size()
                || counts.instances() != immutableMembers.size()
                || counts.active() != immutableActiveTasks.size()
                || counts.instances() != immutableChildExecutionIds.size()
                || immutableActiveTasks.stream().noneMatch(
                        task -> sourceTaskId.equals(task.taskId()))
                || immutableActiveTasks.stream().anyMatch(
                        task -> !immutableMembers.contains(task.assignee()))
                || new LinkedHashSet<>(immutableChildExecutionIds).size()
                        != immutableChildExecutionIds.size()
                || immutableActiveTasks.stream().anyMatch(
                        task -> !immutableChildExecutionIds.contains(task.executionId())))
        {
            throw new IllegalArgumentException("受控多实例运行时快照不一致");
        }
        members = immutableMembers;
        activeTasks = immutableActiveTasks;
        childExecutionIds = immutableChildExecutionIds;
    }

    /**
     * 按主键返回当前来源活动任务事实。
     *
     * @return MultiInstanceActiveTaskSnapshot，构造时已经证明唯一存在的来源任务
     */
    public MultiInstanceActiveTaskSnapshot sourceTask()
    {
        return activeTasks.stream().filter(task -> sourceTaskId.equals(task.taskId()))
                .findFirst().orElseThrow();
    }
}

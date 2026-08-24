package com.ruoyi.flowable.service.task;

import java.util.List;
import org.springframework.util.StringUtils;

/**
 * 根取消时仍可读取的受控多实例定义和流程变量不可变快照。
 *
 * @param deployId String，部署主键
 * @param processDefinitionId String，流程定义主键
 * @param processInstanceId String，流程实例主键
 * @param activityId String，受控节点主键
 * @param rootExecutionId String，多实例根 execution 主键
 * @param mode WorkflowMultiInstanceMode，部署与流程变量共同确认的模式
 * @param members List&lt;String&gt;，流程作用域有序成员快照
 * @param revision int，流程作用域 revision
 */
public record ControlledMultiInstanceRootSnapshot(String deployId,
        String processDefinitionId, String processInstanceId, String activityId,
        String rootExecutionId, WorkflowMultiInstanceMode mode,
        List<String> members, int revision)
{
    /**
     * 校验根身份、模式、成员和 revision 并复制集合。
     *
     * @return 无返回值，非法根快照拒绝构造
     */
    public ControlledMultiInstanceRootSnapshot
    {
        if (!StringUtils.hasText(deployId)
                || !StringUtils.hasText(processDefinitionId)
                || !StringUtils.hasText(processInstanceId)
                || !StringUtils.hasText(activityId)
                || !StringUtils.hasText(rootExecutionId) || mode == null
                || members == null || members.isEmpty() || revision < 0)
        {
            throw new IllegalArgumentException("受控多实例根快照不完整");
        }
        members = List.copyOf(members);
    }
}

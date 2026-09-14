package com.ruoyi.flowable.domain.vo;

import java.util.List;
import java.util.Objects;
import org.springframework.util.StringUtils;

/**
 * 申请表单、请求节点表单和正式历史任务表单的不可变语义分组。
 *
 * @param applicationForm WorkflowProcessFormSnapshotView，正式开始提交快照；退回申请态绑定当前活动任务
 * @param nodeTaskForm WorkflowProcessFormSnapshotView，普通状态下请求任务的节点表单
 * @param historyForms List&lt;WorkflowProcessFormSnapshotView&gt;，不含开始表单和请求任务的正式历史任务表单
 * @param returnedApplication boolean，是否为 returned 状态下的活动申请修改任务投影
 */
public record WorkflowProcessFormDetailView(
        WorkflowProcessFormSnapshotView applicationForm,
        WorkflowProcessFormSnapshotView nodeTaskForm,
        List<WorkflowProcessFormSnapshotView> historyForms,
        boolean returnedApplication)
{
    /**
     * 创建语义明确的表单详情并拒绝普通态、退回态与正式快照事实之间的矛盾组合。
     *
     * 构造后 historyForms 为不可修改集合
     */
    public WorkflowProcessFormDetailView
    {
        historyForms = List.copyOf(Objects.requireNonNull(
                historyForms, "历史表单集合不能为空"));
        Objects.requireNonNull(applicationForm, "申请表单不能为空");
        if (returnedApplication)
        {
            if (!StringUtils.hasText(applicationForm.taskId()))
            {
                throw new IllegalArgumentException("退回申请表单必须绑定活动任务主键");
            }
            if (nodeTaskForm != null)
            {
                throw new IllegalArgumentException("退回申请态不能存在节点任务表单");
            }
        }
        else if (applicationForm.taskId() != null)
        {
            throw new IllegalArgumentException("普通申请表单不能绑定任务主键");
        }
        if (historyForms.stream().anyMatch(form -> !StringUtils.hasText(form.taskId())))
        {
            throw new IllegalArgumentException("历史表单必须绑定正式任务主键");
        }
    }
}

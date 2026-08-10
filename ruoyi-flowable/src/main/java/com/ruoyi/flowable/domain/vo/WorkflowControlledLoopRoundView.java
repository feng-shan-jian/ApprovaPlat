package com.ruoyi.flowable.domain.vo;

import java.time.Instant;

/**
 * 受控重复审批循环单轮只读审计视图。
 *
 * @param taskId String，本轮真实 Flowable 任务主键
 * @param iteration int，从 1 开始的完成轮次
 * @param actorUserId String，本轮真实完成人主键
 * @param decisionValue String，经表单 schema 校验的判断字段值
 * @param outcome String，REPEAT 再次进入或 EXIT 退出
 * @param completedAt Instant，本轮完成时间
 */
public record WorkflowControlledLoopRoundView(String taskId, int iteration,
        String actorUserId, String decisionValue, String outcome, Instant completedAt)
{
}

package com.ruoyi.flowable.domain.vo;

import java.time.Instant;

/**
 * 经过类型和正文大小门禁的流程审批意见视图。
 *
 * @param commentId String，Flowable 意见主键
 * @param taskId String，意见所属任务主键
 * @param type String，1 至 7 的业务意见类型或 comment
 * @param typeName String，稳定中文业务类型名称
 * @param message String，受控原始意见或结构化审计正文
 * @param opinion String，授权后可直接展示的纯文本业务意见，系统事件允许为空
 * @param userId String，意见记录人主键，允许为空
 * @param time Instant，意见记录时间，允许为空
 */
public record WorkflowProcessCommentView(
        String commentId,
        String taskId,
        String type,
        String typeName,
        String message,
        String opinion,
        String userId,
        Instant time)
{
}

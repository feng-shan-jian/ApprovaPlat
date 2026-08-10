package com.ruoyi.flowable.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfBpmnEventCode;
import com.ruoyi.flowable.domain.vo.WorkflowBpmnEventAuditView;
import com.ruoyi.flowable.domain.vo.WorkflowBpmnEventNotificationView;

/**
 * BPMN 错误、升级目录及运行审计数据访问层。
 */
public interface WfBpmnEventMapper
{
    /** @return List&lt;WfBpmnEventCode&gt;，全部编码目录。 */
    List<WfBpmnEventCode> selectCodeList();

    /**
     * 查询设计器可选的已启用目录。
     * @param eventType String，ERROR 或 ESCALATION
     * @return List&lt;WfBpmnEventCode&gt;，按编码排序的真实目录
     */
    List<WfBpmnEventCode> selectEnabledCodes(@Param("eventType") String eventType);

    /**
     * 按类型和编码查询目录。
     * @param eventType String，事件类型
     * @param eventCode String，稳定编码
     * @return WfBpmnEventCode，目录记录；不存在时为空
     */
    WfBpmnEventCode selectCode(@Param("eventType") String eventType,
            @Param("eventCode") String eventCode);

    /** @param eventCodeId Long，主键；@return WfBpmnEventCode，锁定记录。 */
    WfBpmnEventCode selectCodeForUpdate(@Param("eventCodeId") Long eventCodeId);

    /** @param code WfBpmnEventCode，新增目录；@return int，写入行数。 */
    int insertCode(WfBpmnEventCode code);

    /** @param code WfBpmnEventCode，名称、通知与说明修改；@return int，更新行数。 */
    int updateCode(WfBpmnEventCode code);

    /**
     * 修改目录状态。
     * @param eventCodeId Long，目录主键
     * @param status String，ENABLED 或 DISABLED
     * @param updateBy String，操作人用户主键
     * @return int，更新行数
     */
    int updateCodeStatus(@Param("eventCodeId") Long eventCodeId,
            @Param("status") String status, @Param("updateBy") String updateBy);

    /**
     * 首次写入幂等运行审计。
     * @param idempotencyKey String，稳定幂等摘要
     * @param deploymentId String，部署主键
     * @param processInstanceId String，实例主键
     * @param processDefinitionId String，定义主键
     * @param executionId String，执行主键
     * @param sourceElementId String，产生节点
     * @param sourceType String，业务来源类型
     * @param eventType String，事件类型
     * @param eventCode String，事件编码
     * @param eventName String，冻结名称
     * @param matchStatus String，CAPTURED 或 UNMATCHED
     * @param boundaryEventId String，匹配边界标识
     * @param interrupting Boolean，中断语义
     * @param messageSummary String，脱敏摘要
     * @param initiatorUserId String，发起人用户主键
     * @return int，首次写入 1，重复触发 0
     */
    int insertAudit(@Param("idempotencyKey") String idempotencyKey,
            @Param("deploymentId") String deploymentId,
            @Param("processInstanceId") String processInstanceId,
            @Param("processDefinitionId") String processDefinitionId,
            @Param("executionId") String executionId,
            @Param("sourceElementId") String sourceElementId,
            @Param("sourceType") String sourceType,
            @Param("eventType") String eventType, @Param("eventCode") String eventCode,
            @Param("eventName") String eventName, @Param("matchStatus") String matchStatus,
            @Param("boundaryEventId") String boundaryEventId,
            @Param("interrupting") Boolean interrupting,
            @Param("messageSummary") String messageSummary,
            @Param("initiatorUserId") String initiatorUserId);

    /** @param idempotencyKey String，幂等摘要；@return Long，审计主键。 */
    Long selectAuditId(@Param("idempotencyKey") String idempotencyKey);

    /**
     * 为有效发起人创建一条真实站内工作流通知。
     * @param auditId Long，审计主键
     * @param recipientUserId String，接收人用户主键
     * @param title String，标题
     * @param content String，正文
     * @return int，首次创建 1，无有效用户或重复时 0
     */
    int insertNotification(@Param("auditId") Long auditId,
            @Param("recipientUserId") String recipientUserId,
            @Param("title") String title, @Param("content") String content);

    /** @return List&lt;WorkflowBpmnEventAuditView&gt;，最近 500 条运行审计。 */
    List<WorkflowBpmnEventAuditView> selectAuditList();

    /** @param userId String，当前用户主键；@return List&lt;WorkflowBpmnEventNotificationView&gt;，最近 200 条通知。 */
    List<WorkflowBpmnEventNotificationView> selectNotifications(@Param("userId") String userId);

    /**
     * 标记当前用户通知已读。
     * @param notificationId Long，通知主键
     * @param userId String，当前用户主键
     * @return int，首次标记 1，不存在或越权 0
     */
    int markNotificationRead(@Param("notificationId") Long notificationId,
            @Param("userId") String userId);
}

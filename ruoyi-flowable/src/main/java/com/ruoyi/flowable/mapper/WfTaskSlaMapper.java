package com.ruoyi.flowable.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfBusinessCalendar;
import com.ruoyi.flowable.domain.WfTaskSlaExecution;
import com.ruoyi.flowable.domain.dto.WorkflowOperationsQuery;
import com.ruoyi.flowable.domain.vo.WorkflowTaskSlaAuditView;
import com.ruoyi.flowable.domain.vo.WorkflowTaskSlaNotificationView;
import com.ruoyi.flowable.domain.vo.WorkflowTaskSlaExecutionView;

/**
 * 业务日历、SLA 运行状态、审计及统一通知查询数据访问层。
 */
public interface WfTaskSlaMapper
{
    /** @return List&lt;WfBusinessCalendar&gt;，全部正式业务日历。 */
    List<WfBusinessCalendar> selectCalendars();

    /** @return List&lt;WfBusinessCalendar&gt;，设计器可选的启用日历。 */
    List<WfBusinessCalendar> selectEnabledCalendars();

    /** @param calendarId Long，日历主键；@return WfBusinessCalendar，锁定记录或 null。 */
    WfBusinessCalendar selectCalendarForUpdate(@Param("calendarId") Long calendarId);

    /** @param calendarKey String，稳定编码；@return WfBusinessCalendar，日历及日期覆盖或 null。 */
    WfBusinessCalendar selectCalendarByKey(@Param("calendarKey") String calendarKey);

    /** @param calendar WfBusinessCalendar，新增日历；@return int，写入行数。 */
    int insertCalendar(WfBusinessCalendar calendar);

    /** @param calendar WfBusinessCalendar，修改日历元数据；@return int，更新行数。 */
    int updateCalendar(WfBusinessCalendar calendar);

    /**
     * 更新日历状态。
     * @param calendarId Long，日历主键
     * @param status String，ENABLED 或 DISABLED
     * @param updateBy String，操作人用户主键
     * @return int，更新行数
     */
    int updateCalendarStatus(@Param("calendarId") Long calendarId,
            @Param("status") String status, @Param("updateBy") String updateBy);

    /** @param calendarId Long，日历主键；@return int，删除日期覆盖行数。 */
    int deleteCalendarDays(@Param("calendarId") Long calendarId);

    /**
     * 批量新增日历日期覆盖。
     * @param calendarId Long，日历主键
     * @param days List&lt;WfBusinessCalendar.CalendarDay&gt;，覆盖规则
     * @return int，写入行数
     */
    int insertCalendarDays(@Param("calendarId") Long calendarId,
            @Param("days") List<WfBusinessCalendar.CalendarDay> days);

    /** @param execution WfTaskSlaExecution，任务 SLA 初始状态；@return int，首次写入 1，重复创建 0。 */
    int insertExecution(WfTaskSlaExecution execution);

    /** @param taskId String，Flowable 任务主键；@return WfTaskSlaExecution，锁定执行或 null。 */
    WfTaskSlaExecution selectExecutionByTaskForUpdate(@Param("taskId") String taskId);

    /**
     * 按实例和原审批节点锁定唯一活动执行。
     * @param processInstanceId String，实例主键
     * @param taskDefinitionKey String，原审批节点标识
     * @return WfTaskSlaExecution，活动或升级执行；不存在时为 null
     */
    WfTaskSlaExecution selectActiveExecutionForUpdate(
            @Param("processInstanceId") String processInstanceId,
            @Param("taskDefinitionKey") String taskDefinitionKey);

    /** @param processInstanceId String，流程实例主键；@return List&lt;WfTaskSlaExecution&gt;，锁定的活动执行。 */
    List<WfTaskSlaExecution> selectActiveExecutionsForInstanceForUpdate(
            @Param("processInstanceId") String processInstanceId);

    /**
     * 同步当前办理人。
     * @param executionId Long，SLA 执行主键
     * @param assigneeUserId String，可空办理人
     * @param revision Integer，预期版本
     * @return int，乐观锁更新行数
     */
    int updateAssignee(@Param("executionId") Long executionId,
            @Param("assigneeUserId") String assigneeUserId,
            @Param("revision") Integer revision);

    /**
     * 将任务标记为完成。
     * @param executionId Long，SLA 执行主键
     * @param revision Integer，预期版本
     * @return int，乐观锁更新行数
     */
    int completeExecution(@Param("executionId") Long executionId,
            @Param("revision") Integer revision);

    /**
     * 原子递增提醒次数。
     * @param executionId Long，SLA 执行主键
     * @param expectedOrdinal Integer，预期本次提醒序号
     * @param revision Integer，预期版本
     * @return int，首次有效触发行数
     */
    int markReminder(@Param("executionId") Long executionId,
            @Param("expectedOrdinal") Integer expectedOrdinal,
            @Param("revision") Integer revision);

    /**
     * 原子标记升级。
     * @param executionId Long，SLA 执行主键
     * @param revision Integer，预期版本
     * @return int，首次有效触发行数
     */
    int markEscalated(@Param("executionId") Long executionId,
            @Param("revision") Integer revision);

    /**
     * 挂起实例内全部活动 SLA 时钟。
     * @param processInstanceId String，流程实例主键
     * @param pausedAt LocalDateTime，统一暂停时刻
     * @return int，真实暂停执行数量
     */
    int pauseInstance(@Param("processInstanceId") String processInstanceId,
            @Param("pausedAt") LocalDateTime pausedAt);

    /**
     * 恢复实例内全部 SLA 时钟并平移到期时间。
     * @param processInstanceId String，流程实例主键
     * @param resumedAt LocalDateTime，统一恢复时刻
     * @return int，真实恢复执行数量
     */
    int resumeInstance(@Param("processInstanceId") String processInstanceId,
            @Param("resumedAt") LocalDateTime resumedAt);

    /**
     * 写入幂等审计。
     * @param executionId Long，SLA 执行主键
     * @param actionType String，动作类型
     * @param actionOrdinal Integer，动作序号
     * @param actorUserId String，可空操作人
     * @param detail String，脱敏摘要
     * @return int，首次写入 1，重复触发 0
     */
    int insertAudit(@Param("executionId") Long executionId,
            @Param("actionType") String actionType,
            @Param("actionOrdinal") Integer actionOrdinal,
            @Param("actorUserId") String actorUserId,
            @Param("detail") String detail);

    /** @param executionId Long，执行主键；@param actionType String，动作；@param actionOrdinal Integer，序号；@return Long，审计主键或 null。 */
    Long selectAuditId(@Param("executionId") Long executionId,
            @Param("actionType") String actionType,
            @Param("actionOrdinal") Integer actionOrdinal);

    /** @param query SlaAudit，审计筛选条件；@return long，符合条件的审计总数。 */
    long countAudits(@Param("query") WorkflowOperationsQuery.SlaAudit query);

    /** @param query SlaAudit，审计筛选条件；@param offset int，起始偏移；@param pageSize int，本页大小；@return List，当前页审计。 */
    List<WorkflowTaskSlaAuditView> selectAudits(
            @Param("query") WorkflowOperationsQuery.SlaAudit query,
            @Param("offset") int offset, @Param("pageSize") int pageSize);

    /** @param userId String，当前用户主键；@return List&lt;WorkflowTaskSlaNotificationView&gt;，最近 200 条通知。 */
    List<WorkflowTaskSlaNotificationView> selectNotifications(@Param("userId") String userId);

    /** @param notificationId Long，通知主键；@param userId String，当前用户主键；@return int，首次标记行数。 */
    int markNotificationRead(@Param("notificationId") Long notificationId,
            @Param("userId") String userId);

    /** @param query SlaExecution，执行筛选条件；@return long，符合条件的执行总数。 */
    long countExecutions(@Param("query") WorkflowOperationsQuery.SlaExecution query);

    /** @param query SlaExecution，执行筛选条件；@param offset int，起始偏移；@param pageSize int，本页大小；@return List，当前页执行。 */
    List<WorkflowTaskSlaExecutionView> selectExecutions(
            @Param("query") WorkflowOperationsQuery.SlaExecution query,
            @Param("offset") int offset, @Param("pageSize") int pageSize);
}

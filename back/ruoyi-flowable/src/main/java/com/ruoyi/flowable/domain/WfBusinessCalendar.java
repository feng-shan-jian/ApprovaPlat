package com.ruoyi.flowable.domain;

import java.time.LocalDate;
import java.util.List;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 审批 SLA 业务日历正式主数据，对应 {@code wf_business_calendar}。
 */
public class WfBusinessCalendar extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 业务日历主键。 */
    private Long calendarId;
    /** 部署配置引用的稳定日历编码。 */
    private String calendarKey;
    /** 用户可见日历名称。 */
    private String calendarName;
    /** IANA 时区标识。 */
    private String timezone;
    /** ISO 周序号集合，例如 1,2,3,4,5。 */
    private String workingDays;
    /** 每个工作日开始时间，格式 HH:mm。 */
    private String workStart;
    /** 每个工作日结束时间，格式 HH:mm。 */
    private String workEnd;
    /** ENABLED 或 DISABLED。 */
    private String status;
    /** 节假日和补班日覆盖规则。 */
    private List<CalendarDay> days = List.of();

    /** @return Long，业务日历主键。 */
    public Long getCalendarId() { return calendarId; }
    /** @param calendarId Long，业务日历主键；@return void，无返回值。 */
    public void setCalendarId(Long calendarId) { this.calendarId = calendarId; }
    /** @return String，稳定日历编码。 */
    public String getCalendarKey() { return calendarKey; }
    /** @param calendarKey String，稳定日历编码；@return void，无返回值。 */
    public void setCalendarKey(String calendarKey) { this.calendarKey = calendarKey; }
    /** @return String，用户可见名称。 */
    public String getCalendarName() { return calendarName; }
    /** @param calendarName String，用户可见名称；@return void，无返回值。 */
    public void setCalendarName(String calendarName) { this.calendarName = calendarName; }
    /** @return String，IANA 时区。 */
    public String getTimezone() { return timezone; }
    /** @param timezone String，IANA 时区；@return void，无返回值。 */
    public void setTimezone(String timezone) { this.timezone = timezone; }
    /** @return String，ISO 工作周序号。 */
    public String getWorkingDays() { return workingDays; }
    /** @param workingDays String，ISO 工作周序号；@return void，无返回值。 */
    public void setWorkingDays(String workingDays) { this.workingDays = workingDays; }
    /** @return String，工作开始时间。 */
    public String getWorkStart() { return workStart; }
    /** @param workStart String，工作开始时间；@return void，无返回值。 */
    public void setWorkStart(String workStart) { this.workStart = workStart; }
    /** @return String，工作结束时间。 */
    public String getWorkEnd() { return workEnd; }
    /** @param workEnd String，工作结束时间；@return void，无返回值。 */
    public void setWorkEnd(String workEnd) { this.workEnd = workEnd; }
    /** @return String，ENABLED 或 DISABLED。 */
    public String getStatus() { return status; }
    /** @param status String，目标状态；@return void，无返回值。 */
    public void setStatus(String status) { this.status = status; }
    /** @return List&lt;CalendarDay&gt;，不可变日期覆盖规则。 */
    public List<CalendarDay> getDays() { return days; }
    /** @param days List&lt;CalendarDay&gt;，日期覆盖规则；@return void，无返回值。 */
    public void setDays(List<CalendarDay> days) { this.days = days == null ? List.of() : List.copyOf(days); }

    /**
     * 单日工作状态覆盖。
     *
     * @param calendarDate LocalDate，日历所在时区的自然日
     * @param workingDay boolean，true 为补班，false 为节假日
     * @param dayName String，可空说明
     */
    public record CalendarDay(LocalDate calendarDate, boolean workingDay, String dayName) { }
}

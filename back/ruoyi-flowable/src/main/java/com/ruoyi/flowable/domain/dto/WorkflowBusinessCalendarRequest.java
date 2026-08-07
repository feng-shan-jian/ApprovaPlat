package com.ruoyi.flowable.domain.dto;

import java.time.LocalDate;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 业务日历新增或修改请求。
 *
 * @param calendarKey String，发布后不可修改的稳定编码
 * @param calendarName String，用户可见名称
 * @param timezone String，IANA 时区
 * @param workingDays List&lt;Integer&gt;，ISO 工作周序号 1 至 7
 * @param workStart String，每日工作开始时间 HH:mm
 * @param workEnd String，每日工作结束时间 HH:mm
 * @param description String，可空说明
 * @param days List&lt;CalendarDayRequest&gt;，节假日和补班日覆盖
 */
public record WorkflowBusinessCalendarRequest(
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_.-]{1,63}$") String calendarKey,
        @NotBlank @Size(max = 128) String calendarName,
        @NotBlank @Size(max = 64) String timezone,
        @NotEmpty @Size(max = 7) List<@NotNull Integer> workingDays,
        @NotBlank @Pattern(regexp = "^(?:[01]\\d|2[0-3]):[0-5]\\d$") String workStart,
        @NotBlank @Pattern(regexp = "^(?:[01]\\d|2[0-3]):[0-5]\\d$") String workEnd,
        @Size(max = 500) String description,
        @NotNull @Size(max = 1000) List<@Valid CalendarDayRequest> days)
{
    /**
     * 单日工作状态覆盖请求。
     *
     * @param calendarDate LocalDate，日历时区自然日
     * @param workingDay Boolean，true 为补班，false 为节假日
     * @param dayName String，可空说明
     */
    public record CalendarDayRequest(@NotNull LocalDate calendarDate,
            @NotNull Boolean workingDay, @Size(max = 128) String dayName) { }
}

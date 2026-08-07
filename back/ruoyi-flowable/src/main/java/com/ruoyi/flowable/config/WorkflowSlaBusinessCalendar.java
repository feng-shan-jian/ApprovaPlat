package com.ruoyi.flowable.config;

import java.time.Instant;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flowable.common.engine.impl.calendar.BusinessCalendar;
import org.flowable.common.engine.impl.runtime.ClockReader;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.service.model.WorkflowBusinessCalendarService;

/**
 * Flowable 审批 SLA 自定义业务日历，表达式格式固定为 {@code CALENDAR_KEY|工作分钟}。
 */
public class WorkflowSlaBusinessCalendar implements BusinessCalendar
{
    public static final String NAME = "approvaSla";
    private static final Pattern EXPRESSION = Pattern.compile(
            "^([A-Z][A-Z0-9_.-]{1,63})\\|([1-9][0-9]{0,6})$");

    private final ClockReader clock;
    private final WorkflowBusinessCalendarService calendarService;

    /**
     * 创建 Flowable 业务日历实现。
     * @param clock ClockReader，Flowable 可测试引擎时钟
     * @param calendarService WorkflowBusinessCalendarService，正式业务日历计算服务
     * @return 无返回值，由引擎配置器注册
     */
    public WorkflowSlaBusinessCalendar(ClockReader clock,
            WorkflowBusinessCalendarService calendarService)
    {
        this.clock = clock;
        this.calendarService = calendarService;
    }

    /** @param dueDate String，日历编码和工作分钟；@return Date，绝对到期时间。 */
    @Override
    public Date resolveDuedate(String dueDate)
    {
        return resolveDuedate(dueDate, 0);
    }

    /**
     * 解析一次性 SLA 定时器到期时间。
     * @param dueDate String，日历编码和工作分钟
     * @param maxIterations int，Flowable 循环保护参数；SLA 定时器不使用循环语义
     * @return Date，按引擎时钟和正式业务日历计算的绝对时间
     */
    @Override
    public Date resolveDuedate(String dueDate, int maxIterations)
    {
        Matcher matcher = EXPRESSION.matcher(dueDate == null ? "" : dueDate.trim());
        if (!matcher.matches())
        {
            throw new ServiceException("审批 SLA 定时表达式不合法", HttpStatus.BAD_REQUEST);
        }
        int minutes;
        try
        {
            minutes = Integer.parseInt(matcher.group(2));
        }
        catch (NumberFormatException exception)
        {
            throw new ServiceException("审批 SLA 定时表达式不合法", HttpStatus.BAD_REQUEST);
        }
        Instant start = clock.getCurrentTime().toInstant();
        return Date.from(calendarService.resolveDueAt(matcher.group(1), start, minutes));
    }

    /**
     * 校验循环定时器候选到期时间；SLA 仅使用一次性边界定时器。
     * @param timeCycle String，日历表达式
     * @param maxIterations int，最大循环次数
     * @param endDate Date，可空结束时间
     * @param newTimer Date，候选到期时间
     * @return Boolean，候选时间不晚于结束时间时为 true
     */
    @Override
    public Boolean validateDuedate(String timeCycle, int maxIterations,
            Date endDate, Date newTimer)
    {
        return newTimer != null && (endDate == null || !newTimer.after(endDate));
    }

    /** @param dueDate String，日历表达式；@return Date，与一次性到期解析相同的结束时间。 */
    @Override
    public Date resolveEndDate(String dueDate)
    {
        return resolveDuedate(dueDate);
    }
}

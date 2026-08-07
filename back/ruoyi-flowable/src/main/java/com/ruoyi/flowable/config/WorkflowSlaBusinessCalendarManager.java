package com.ruoyi.flowable.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.flowable.common.engine.impl.calendar.BusinessCalendar;
import org.flowable.common.engine.impl.calendar.BusinessCalendarManager;
import org.flowable.common.engine.impl.calendar.CycleBusinessCalendar;
import org.flowable.common.engine.impl.calendar.DueDateBusinessCalendar;
import org.flowable.common.engine.impl.calendar.DurationBusinessCalendar;
import org.flowable.common.engine.impl.runtime.ClockReader;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.service.model.WorkflowBusinessCalendarService;

/**
 * 延迟创建 Flowable 内置日历和审批 SLA 日历，避免配置回调早于引擎时钟初始化。
 */
public class WorkflowSlaBusinessCalendarManager implements BusinessCalendarManager
{
    private final Supplier<ClockReader> clockSupplier;
    private final WorkflowBusinessCalendarService calendarService;
    private final Map<String, BusinessCalendar> calendars = new ConcurrentHashMap<>();

    /**
     * 创建延迟日历管理器。
     * @param clockSupplier Supplier&lt;ClockReader&gt;，运行时读取已初始化引擎时钟
     * @param calendarService WorkflowBusinessCalendarService，正式业务日历服务
     * @return 无返回值，由引擎配置器注册
     */
    public WorkflowSlaBusinessCalendarManager(Supplier<ClockReader> clockSupplier,
            WorkflowBusinessCalendarService calendarService)
    {
        this.clockSupplier = clockSupplier;
        this.calendarService = calendarService;
    }

    /**
     * 按名称取得日历，首次运行访问时才绑定非空引擎时钟。
     * @param businessCalendarName String，Flowable 日历名称
     * @return BusinessCalendar，内置或审批 SLA 日历
     */
    @Override
    public BusinessCalendar getBusinessCalendar(String businessCalendarName)
    {
        if (businessCalendarName == null || businessCalendarName.isBlank())
        {
            return null;
        }
        return calendars.computeIfAbsent(businessCalendarName, this::createCalendar);
    }

    /** @param name String，日历名称；@return BusinessCalendar，使用已初始化时钟创建的固定实现。 */
    private BusinessCalendar createCalendar(String name)
    {
        ClockReader clock = clockSupplier.get();
        if (clock == null)
        {
            throw new ServiceException("Flowable 引擎时钟尚未初始化", HttpStatus.ERROR);
        }
        if (DurationBusinessCalendar.NAME.equals(name))
        {
            return new DurationBusinessCalendar(clock);
        }
        if (DueDateBusinessCalendar.NAME.equals(name))
        {
            return new DueDateBusinessCalendar(clock);
        }
        if (CycleBusinessCalendar.NAME.equals(name))
        {
            return new CycleBusinessCalendar(clock);
        }
        if (WorkflowSlaBusinessCalendar.NAME.equals(name))
        {
            return new WorkflowSlaBusinessCalendar(clock, calendarService);
        }
        return null;
    }
}

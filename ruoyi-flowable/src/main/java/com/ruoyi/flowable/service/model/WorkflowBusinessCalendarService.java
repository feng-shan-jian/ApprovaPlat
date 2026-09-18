package com.ruoyi.flowable.service.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.zone.ZoneRulesException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfBusinessCalendar;
import com.ruoyi.flowable.domain.dto.WorkflowBusinessCalendarRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.mapper.WfTaskSlaMapper;

/**
 * 审批 SLA 业务日历管理与工作分钟计算服务。
 */
@Service
public class WorkflowBusinessCalendarService
{
    public static final String ENABLED = "ENABLED";
    public static final String DISABLED = "DISABLED";
    /** 单个 SLA 最多允许五个自然年的工作分钟，防止配置导致无界日期扫描。 */
    private static final int MAX_WORKING_MINUTES = 5 * 366 * 24 * 60;
    /** 日期扫描最多十个自然年，覆盖低工作频率日历且保持确定上界。 */
    private static final int MAX_SCAN_DAYS = 3660;

    private final WorkflowEngineOperations engineOperations;
    private final WfTaskSlaMapper slaMapper;

    /**
     * 创建业务日历服务。
     * 构造后由 Spring 管理
     */
    public WorkflowBusinessCalendarService(WorkflowEngineOperations engineOperations,
            WfTaskSlaMapper slaMapper)
    {
        this.engineOperations = engineOperations;
        this.slaMapper = slaMapper;
    }

    /** @return List&lt;WfBusinessCalendar&gt;，全部正式业务日历。 */
    public List<WfBusinessCalendar> listManagement()
    {
        return engineOperations.read(() -> List.copyOf(slaMapper.selectCalendars()));
    }

    /** @return List&lt;WfBusinessCalendar&gt;，设计器可引用的已启用日历。 */
    public List<WfBusinessCalendar> listEnabled()
    {
        return engineOperations.read(() -> List.copyOf(slaMapper.selectEnabledCalendars()));
    }

    /**
     * 新建稳定业务日历。
     * @param request WorkflowBusinessCalendarRequest，完整日历与日期覆盖配置
     * @return Long，数据库生成主键
     */
    public Long create(WorkflowBusinessCalendarRequest request)
    {
        WfBusinessCalendar calendar = normalize(request);
        return engineOperations.writeAsCurrentUser(identity ->
        {
            if (slaMapper.selectCalendarByKey(calendar.getCalendarKey()) != null)
            {
                throw new ServiceException("业务日历编码已存在", HttpStatus.CONFLICT);
            }
            calendar.setStatus(ENABLED);
            calendar.setCreateBy(identity.userId());
            if (slaMapper.insertCalendar(calendar) != 1 || calendar.getCalendarId() == null)
            {
                throw dataError("业务日历保存不完整");
            }
            insertDays(calendar);
            return calendar.getCalendarId();
        });
    }

    /**
     * 修改日历规则；稳定编码保持不变，已创建任务的实际到期时间不会被回写。
     * @param calendarId Long，业务日历主键
     * @param request WorkflowBusinessCalendarRequest，完整替换配置
     * @return void，任一步不完整时整个事务回滚
     */
    public void update(Long calendarId, WorkflowBusinessCalendarRequest request)
    {
        requirePositiveId(calendarId);
        WfBusinessCalendar normalized = normalize(request);
        engineOperations.writeAsCurrentUser(identity ->
        {
            WfBusinessCalendar existing = slaMapper.selectCalendarForUpdate(calendarId);
            if (existing == null)
            {
                throw new ServiceException("业务日历不存在", HttpStatus.NOT_FOUND);
            }
            if (!existing.getCalendarKey().equals(normalized.getCalendarKey()))
            {
                throw new ServiceException("业务日历编码发布后不可修改", HttpStatus.CONFLICT);
            }
            normalized.setCalendarId(calendarId);
            normalized.setUpdateBy(identity.userId());
            if (slaMapper.updateCalendar(normalized) != 1)
            {
                throw new ServiceException("业务日历更新失败", HttpStatus.CONFLICT);
            }
            slaMapper.deleteCalendarDays(calendarId);
            insertDays(normalized);
            return null;
        });
    }

    /**
     * 启用或停用日历；停用只阻止新部署，历史任务继续使用已生成的真实定时作业。
     * @param calendarId Long，业务日历主键
     * @param enabled boolean，目标启用状态
     * @return void，日历不存在时返回 404
     */
    public void changeStatus(Long calendarId, boolean enabled)
    {
        requirePositiveId(calendarId);
        engineOperations.writeAsCurrentUser(identity ->
        {
            if (slaMapper.selectCalendarForUpdate(calendarId) == null)
            {
                throw new ServiceException("业务日历不存在", HttpStatus.NOT_FOUND);
            }
            if (slaMapper.updateCalendarStatus(calendarId, enabled ? ENABLED : DISABLED,
                    identity.userId()) != 1)
            {
                throw new ServiceException("业务日历状态更新失败", HttpStatus.CONFLICT);
            }
            return null;
        });
    }

    /**
     * 取得作者保存或部署引用的已启用日历；配置关系由主数据保存入口建立。
     * @param calendarKey String，作者配置中的稳定日历编码
     * @return WfBusinessCalendar，当前启用且字段完整的正式日历
     */
    public WfBusinessCalendar requireEnabled(String calendarKey)
    {
        String normalizedKey = calendarKey == null ? "" : calendarKey.trim();
        WfBusinessCalendar calendar = slaMapper.selectCalendarByKey(normalizedKey);
        if (calendar == null || !ENABLED.equals(calendar.getStatus()))
        {
            throw new ServiceException("审批 SLA 业务日历未启用或不存在", HttpStatus.CONFLICT);
        }
        return calendar;
    }

    /**
     * 按稳定日历编码从指定绝对时刻累加工作分钟，用于 Flowable 自定义业务日历。
     * @param calendarKey String，日历稳定编码
     * @param start Instant，Flowable 引擎当前绝对时刻
     * @param workingMinutes int，需要累加的正工作分钟
     * @return Instant，考虑时区、周规则、节假日和补班后的绝对到期时间
     */
    public Instant resolveDueAt(String calendarKey, Instant start, int workingMinutes)
    {
        requireWorkingMinutes(start, workingMinutes);
        return calculate(readCalendar(calendarKey), start, workingMinutes);
    }

    /** 一次 SLA 建立操作的两个绝对到期点，不保存日历或跨请求状态。 */
    public record SlaDueDates(Instant reminderDueAt, Instant escalationDueAt) { }

    /**
     * 一次读取和解析当前日历，以同一起点计算提醒及升级时间。
     * @param calendarKey String，日历稳定编码
     * @param start Instant，任务创建时的引擎绝对时刻
     * @param reminderMinutes int，提醒工作分钟
     * @param escalationMinutes int，升级工作分钟
     * @return SlaDueDates，本次操作实际消费的两个到期点
     */
    public SlaDueDates resolveSlaDueDates(String calendarKey, Instant start,
            int reminderMinutes, int escalationMinutes)
    {
        requireWorkingMinutes(start, reminderMinutes);
        requireWorkingMinutes(start, escalationMinutes);
        CalendarSchedule calendar = readCalendar(calendarKey);
        return new SlaDueDates(calculate(calendar, start, reminderMinutes),
                calculate(calendar, start, escalationMinutes));
    }

    private void requireWorkingMinutes(Instant start, int workingMinutes)
    {
        if (start == null || workingMinutes <= 0 || workingMinutes > MAX_WORKING_MINUTES)
        {
            throw new ServiceException("审批 SLA 工作分钟不合法", HttpStatus.BAD_REQUEST);
        }
    }

    private CalendarSchedule readCalendar(String calendarKey)
    {
        WfBusinessCalendar calendar = slaMapper.selectCalendarByKey(calendarKey);
        if (calendar == null)
        {
            throw dataError("审批 SLA 运行日历不存在");
        }
        return parseCalendar(calendar);
    }

    /** 仅在当前调用内使用的工作窗口及日期索引，两个到期点共用这份解析结果。 */
    private record CalendarSchedule(ZoneId zone, LocalTime workStart, LocalTime workEnd,
            Set<Integer> weekdays, Map<LocalDate, Boolean> overrides) { }

    /**
     * 使用单日工作窗口和日期覆盖计算确定到期时间。
     * @param calendar CalendarSchedule，本次读取并解析的日历
     * @param start Instant，开始绝对时刻
     * @param workingMinutes int，正工作分钟
     * @return Instant，确定的绝对到期时间
     */
    private Instant calculate(CalendarSchedule calendar, Instant start, int workingMinutes)
    {
        ZonedDateTime cursor = start.atZone(calendar.zone());
        long remainingSeconds = Math.multiplyExact((long) workingMinutes, 60L);
        for (int scannedDays = 0; scannedDays <= MAX_SCAN_DAYS; scannedDays++)
        {
            LocalDate date = cursor.toLocalDate();
            boolean workingDay = calendar.overrides().getOrDefault(date,
                    calendar.weekdays().contains(date.getDayOfWeek().getValue()));
            ZonedDateTime dayStart = date.atTime(calendar.workStart()).atZone(calendar.zone());
            ZonedDateTime dayEnd = date.atTime(calendar.workEnd()).atZone(calendar.zone());
            if (workingDay && cursor.isBefore(dayEnd))
            {
                ZonedDateTime effectiveStart = cursor.isAfter(dayStart) ? cursor : dayStart;
                long availableSeconds = java.time.Duration.between(effectiveStart, dayEnd).getSeconds();
                if (remainingSeconds <= availableSeconds)
                {
                    return effectiveStart.plusSeconds(remainingSeconds).toInstant();
                }
                remainingSeconds -= availableSeconds;
            }
            // 每次只跨到下一自然日零点，避免夏令时日使用固定 24 小时造成偏移。
            cursor = date.plusDays(1).atStartOfDay(calendar.zone());
        }
        throw dataError("审批 SLA 业务日历无法解析到期时间");
    }

    /**
     * 规范并校验客户端日历配置。
     * @param request WorkflowBusinessCalendarRequest，客户端完整配置
     * @return WfBusinessCalendar，可直接持久化的规范对象
     */
    private WfBusinessCalendar normalize(WorkflowBusinessCalendarRequest request)
    {
        if (request == null)
        {
            throw new ServiceException("业务日历参数不能为空", HttpStatus.BAD_REQUEST);
        }
        try
        {
            ZoneId.of(request.timezone().trim());
        }
        catch (ZoneRulesException exception)
        {
            throw new ServiceException("业务日历时区不合法", HttpStatus.BAD_REQUEST);
        }
        LocalTime start = LocalTime.parse(request.workStart());
        LocalTime end = LocalTime.parse(request.workEnd());
        if (!start.isBefore(end))
        {
            throw new ServiceException("业务日历工作结束时间必须晚于开始时间", HttpStatus.BAD_REQUEST);
        }
        TreeSet<Integer> workingDays = new TreeSet<>(request.workingDays());
        if (workingDays.size() != request.workingDays().size()
                || workingDays.stream().anyMatch(day -> day < 1 || day > 7))
        {
            throw new ServiceException("业务日历工作周配置不合法", HttpStatus.BAD_REQUEST);
        }
        Set<LocalDate> uniqueDates = new LinkedHashSet<>();
        List<WfBusinessCalendar.CalendarDay> days = request.days().stream().map(day ->
        {
            if (!uniqueDates.add(day.calendarDate()))
            {
                throw new ServiceException("业务日历日期覆盖不能重复", HttpStatus.BAD_REQUEST);
            }
            String name = day.dayName() == null || day.dayName().isBlank()
                    ? null : day.dayName().trim();
            return new WfBusinessCalendar.CalendarDay(day.calendarDate(),
                    day.workingDay(), name);
        }).sorted(java.util.Comparator.comparing(WfBusinessCalendar.CalendarDay::calendarDate))
                .toList();
        WfBusinessCalendar calendar = new WfBusinessCalendar();
        calendar.setCalendarKey(request.calendarKey().trim());
        calendar.setCalendarName(request.calendarName().trim());
        calendar.setTimezone(request.timezone().trim());
        calendar.setWorkingDays(workingDays.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(",")));
        calendar.setWorkStart(request.workStart());
        calendar.setWorkEnd(request.workEnd());
        calendar.setRemark(request.description() == null || request.description().isBlank()
                ? null : request.description().trim());
        calendar.setDays(days);
        return calendar;
    }

    /**
     * 将实际计算需要的时区、工作窗口、周规则与日期覆盖解析为索引。
     * @param calendar WfBusinessCalendar，数据库读取日历
     * @return CalendarSchedule，当前日历工作窗口及日期索引；字段损坏时抛出稳定 500
     */
    private CalendarSchedule parseCalendar(WfBusinessCalendar calendar)
    {
        try
        {
            ZoneId zone = ZoneId.of(calendar.getTimezone());
            LocalTime start = LocalTime.parse(calendar.getWorkStart());
            LocalTime end = LocalTime.parse(calendar.getWorkEnd());
            Set<Integer> weekdays = java.util.Arrays.stream(calendar.getWorkingDays().split(","))
                    .map(Integer::valueOf).collect(java.util.stream.Collectors.toSet());
            Map<LocalDate, Boolean> overrides = new HashMap<>();
            for (WfBusinessCalendar.CalendarDay day : calendar.getDays())
            {
                overrides.put(day.calendarDate(), day.workingDay());
            }
            return new CalendarSchedule(zone, start, end, weekdays, overrides);
        }
        catch (RuntimeException exception)
        {
            throw dataError("业务日历正式数据异常");
        }
    }

    /** @param calendar WfBusinessCalendar，包含主键和日期覆盖；@return void，批量数量不一致时回滚。 */
    private void insertDays(WfBusinessCalendar calendar)
    {
        if (!calendar.getDays().isEmpty()
                && slaMapper.insertCalendarDays(calendar.getCalendarId(), calendar.getDays())
                        != calendar.getDays().size())
        {
            throw dataError("业务日历日期覆盖保存不完整");
        }
    }

    /** @param value Long，待校验主键；@return void，非正数抛出 400。 */
    private void requirePositiveId(Long value)
    {
        if (value == null || value <= 0)
        {
            throw new ServiceException("业务日历主键不合法", HttpStatus.BAD_REQUEST);
        }
    }

    /** @param message String，稳定错误提示；@return ServiceException，HTTP 500 数据异常。 */
    private ServiceException dataError(String message)
    {
        return new ServiceException(message, HttpStatus.ERROR);
    }
}

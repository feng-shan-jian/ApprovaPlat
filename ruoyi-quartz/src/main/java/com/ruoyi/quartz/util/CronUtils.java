package com.ruoyi.quartz.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import org.quartz.CronExpression;

/**
 * cron表达式工具类
 * 
 * @author ruoyi
 */
public class CronUtils
{
    /** 时间字符串包含实际调度时区的 UTC 偏移，避免 Jackson 或浏览器二次换算。 */
    public record CronPreview(String timeZone, List<String> executionTimes) {}

    /**
     * 使用与 ScheduleUtils 相同的 Quartz 默认时区，最多查询未来五次计划时间。
     * after 只由服务端指定；此方法不创建或执行任何任务。
     */
    public static CronPreview preview(String cronExpression, Date after)
    {
        if (cronExpression == null || cronExpression.isBlank())
        {
            throw new IllegalArgumentException("Cron表达式不能为空");
        }
        if (cronExpression.length() > 255)
        {
            throw new IllegalArgumentException("Cron执行表达式不能超过255个字符");
        }
        try
        {
            CronExpression cron = new CronExpression(cronExpression);
            TimeZone timeZone = cron.getTimeZone();
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX");
            format.setTimeZone(timeZone);
            List<String> times = new ArrayList<>(5);
            Date next = after;
            for (int i = 0; i < 5; i++)
            {
                next = cron.getNextValidTimeAfter(next);
                if (next == null)
                {
                    break;
                }
                times.add(format.format(next));
            }
            return new CronPreview(timeZone.getID(), List.copyOf(times));
        }
        catch (ParseException e)
        {
            throw new IllegalArgumentException("Cron表达式不正确");
        }
    }

    /**
     * 返回一个布尔值代表一个给定的Cron表达式的有效性
     *
     * @param cronExpression Cron表达式
     * @return boolean 表达式是否有效
     */
    public static boolean isValid(String cronExpression)
    {
        return CronExpression.isValidExpression(cronExpression);
    }

    /**
     * 返回下一个执行时间根据给定的Cron表达式
     *
     * @param cronExpression Cron表达式
     * @return Date 下次Cron表达式执行时间
     */
    public static Date getNextExecution(String cronExpression)
    {
        try
        {
            CronExpression cron = new CronExpression(cronExpression);
            return cron.getNextValidTimeAfter(new Date(System.currentTimeMillis()));
        }
        catch (ParseException e)
        {
            throw new IllegalArgumentException(e.getMessage());
        }
    }
}

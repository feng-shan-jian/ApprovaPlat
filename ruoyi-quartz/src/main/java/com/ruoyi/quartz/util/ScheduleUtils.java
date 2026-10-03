package com.ruoyi.quartz.util;

import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.Trigger;
import com.ruoyi.common.constant.ScheduleConstants;
import com.ruoyi.common.exception.job.TaskException;
import com.ruoyi.common.exception.job.TaskException.Code;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.quartz.domain.SysJob;

/**
 * 定时任务工具类
 * 
 * @author ruoyi
 */
public class ScheduleUtils
{
    /**
     * 得到quartz任务类
     *
     * @param sysJob 执行计划
     * @return 具体执行任务类
     */
    private static Class<? extends Job> getQuartzJobClass(SysJob sysJob)
    {
        boolean isConcurrent = "0".equals(sysJob.getConcurrent());
        return isConcurrent ? QuartzJobExecution.class : QuartzDisallowConcurrentExecution.class;
    }

    /**
     * 构建任务触发对象
     */
    public static TriggerKey getTriggerKey(Long jobId, String jobGroup)
    {
        return TriggerKey.triggerKey(ScheduleConstants.TASK_CLASS_NAME + jobId, jobGroup);
    }

    /**
     * 构建任务键对象
     */
    public static JobKey getJobKey(Long jobId, String jobGroup)
    {
        return JobKey.jobKey(ScheduleConstants.TASK_CLASS_NAME + jobId, jobGroup);
    }

    /**
     * 创建定时任务
     */
    public static void createScheduleJob(Scheduler scheduler, SysJob job) throws SchedulerException, TaskException
    {
        replaceScheduleJob(scheduler, job, job.getJobGroup());
    }

    /** 完整构造和校验后替换定义；旧分组事实由数据库服务提供。 */
    public static void replaceScheduleJob(Scheduler scheduler, SysJob job, String previousGroup)
            throws SchedulerException, TaskException
    {
        Class<? extends Job> jobClass = getQuartzJobClass(job);
        // 构建job信息
        Long jobId = job.getJobId();
        String jobGroup = job.getJobGroup();
        JobDetail jobDetail = JobBuilder.newJob(jobClass).withIdentity(getJobKey(jobId, jobGroup)).build();

        // 表达式调度构建器
        CronScheduleBuilder cronScheduleBuilder = CronScheduleBuilder.cronSchedule(job.getCronExpression());
        cronScheduleBuilder = handleCronScheduleMisfirePolicy(job, cronScheduleBuilder);

        // 按新的cronExpression表达式构建一个新的trigger
        CronTrigger trigger = TriggerBuilder.newTrigger().withIdentity(getTriggerKey(jobId, jobGroup))
                .withSchedule(cronScheduleBuilder).build();

        // 放入参数，运行时的方法可以获取
        jobDetail.getJobDataMap().put(ScheduleConstants.TASK_PROPERTIES, job);

        boolean hasNextExecution = StringUtils.isNotNull(CronUtils.getNextExecution(job.getCronExpression()));
        boolean paused = job.getStatus().equals(ScheduleConstants.Status.PAUSE.getValue());
        JobKey currentKey = getJobKey(jobId, jobGroup);
        JobKey previousKey = getJobKey(jobId, previousGroup);

        ScheduledDefinition currentBefore = readDefinition(scheduler, currentKey);
        ScheduledDefinition previousBefore = previousKey.equals(currentKey) ? currentBefore
                : readDefinition(scheduler, previousKey);
        try
        {
            if (hasNextExecution)
            {
                // 由 JobStore 替换同键定义，不在调度操作成功前删除原任务。
                scheduler.scheduleJob(jobDetail, Set.of(trigger), true);
                if (paused) scheduler.pauseJob(currentKey);
            }
            else
            {
                scheduler.deleteJob(currentKey);
            }
            if (!previousKey.equals(currentKey)) scheduler.deleteJob(previousKey);
        }
        catch (SchedulerException | RuntimeException failure)
        {
            // Quartz 不参与数据库事务；明确恢复替换前的定义，恢复失败作为 suppressed 保留。
            restoreDefinition(scheduler, currentKey, currentBefore, failure);
            if (!previousKey.equals(currentKey)) restoreDefinition(scheduler, previousKey, previousBefore, failure);
            throw failure;
        }
    }

    private static ScheduledDefinition readDefinition(Scheduler scheduler, JobKey key) throws SchedulerException
    {
        JobDetail detail = scheduler.getJobDetail(key);
        if (detail == null) return null;
        Set<? extends Trigger> triggers = Set.copyOf(scheduler.getTriggersOfJob(key));
        Map<TriggerKey, Trigger.TriggerState> states = new LinkedHashMap<>();
        for (Trigger trigger : triggers) states.put(trigger.getKey(), scheduler.getTriggerState(trigger.getKey()));
        return new ScheduledDefinition(detail, triggers, states);
    }

    private static void restoreDefinition(Scheduler scheduler, JobKey key, ScheduledDefinition before,
            Exception failure)
    {
        try
        {
            if (before == null) scheduler.deleteJob(key);
            else
            {
                scheduler.scheduleJob(before.detail(), before.triggers(), true);
                for (var state : before.states().entrySet())
                    if (state.getValue() == Trigger.TriggerState.PAUSED) scheduler.pauseTrigger(state.getKey());
            }
        }
        catch (SchedulerException | RuntimeException recoveryFailure)
        {
            if (recoveryFailure != failure) failure.addSuppressed(recoveryFailure);
        }
    }

    private record ScheduledDefinition(JobDetail detail, Set<? extends Trigger> triggers,
            Map<TriggerKey, Trigger.TriggerState> states) { }

    /**
     * 设置定时任务策略
     */
    public static CronScheduleBuilder handleCronScheduleMisfirePolicy(SysJob job, CronScheduleBuilder cb)
            throws TaskException
    {
        switch (job.getMisfirePolicy())
        {
            case ScheduleConstants.MISFIRE_DEFAULT:
                return cb;
            case ScheduleConstants.MISFIRE_IGNORE_MISFIRES:
                return cb.withMisfireHandlingInstructionIgnoreMisfires();
            case ScheduleConstants.MISFIRE_FIRE_AND_PROCEED:
                return cb.withMisfireHandlingInstructionFireAndProceed();
            case ScheduleConstants.MISFIRE_DO_NOTHING:
                return cb.withMisfireHandlingInstructionDoNothing();
            default:
                throw new TaskException("The task misfire policy '" + job.getMisfirePolicy()
                        + "' cannot be used in cron schedule tasks", Code.CONFIG_ERROR);
        }
    }

}

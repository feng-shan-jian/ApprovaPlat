package com.ruoyi.quartz.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.job.TaskException;
import com.ruoyi.quartz.domain.SysJob;
import com.ruoyi.quartz.service.impl.SysJobServiceImpl;
import com.ruoyi.quartz.mapper.SysJobMapper;

class ScheduleReplacementTest
{
    private Scheduler scheduler;
    private SysJobServiceImpl service;

    @BeforeEach
    void setup() throws Exception
    {
        var properties = new Properties();
        properties.setProperty("org.quartz.scheduler.instanceName", "replacement-" + System.nanoTime());
        properties.setProperty("org.quartz.threadPool.threadCount", "1");
        properties.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        scheduler = new StdSchedulerFactory(properties).getScheduler();
        service = new SysJobServiceImpl();
        ReflectionTestUtils.setField(service, "scheduler", scheduler);
        ScheduleUtils.createScheduleJob(scheduler, job("original", "0", "1"));
    }

    @AfterEach
    void close() throws Exception { scheduler.shutdown(true); }

    @Test
    void invalidDefinitionsLeaveOriginalJobUntouched() throws Exception
    {
        var invalidPolicy = job("new-group", "invalid", "0");
        assertThrows(TaskException.class, () -> service.updateSchedulerJob(invalidPolicy, "original"));
        assertTrue(scheduler.checkExists(ScheduleUtils.getJobKey(10L, "original")));
        var invalidCron = job("original", "0", "0");
        invalidCron.setCronExpression("invalid");
        assertThrows(RuntimeException.class, () -> service.updateSchedulerJob(invalidCron, "original"));
        assertTrue(scheduler.checkExists(ScheduleUtils.getJobKey(10L, "original")));
        assertEquals(Trigger.TriggerState.PAUSED, scheduler.getTriggerState(ScheduleUtils.getTriggerKey(10L, "original")));
    }

    @Test
    void replacesSameAndDifferentGroupsAndPreservesPauseAndConcurrency() throws Exception
    {
        var replacement = job("original", "2", "0");
        replacement.setConcurrent("1");
        service.updateSchedulerJob(replacement, "original");
        assertEquals(QuartzDisallowConcurrentExecution.class,
                scheduler.getJobDetail(ScheduleUtils.getJobKey(10L, "original")).getJobClass());
        assertEquals(Trigger.TriggerState.NORMAL, scheduler.getTriggerState(ScheduleUtils.getTriggerKey(10L, "original")));
        service.updateSchedulerJob(job("new-group", "3", "1"), "original");
        assertFalse(scheduler.checkExists(ScheduleUtils.getJobKey(10L, "original")));
        assertEquals(Trigger.TriggerState.PAUSED, scheduler.getTriggerState(ScheduleUtils.getTriggerKey(10L, "new-group")));
        var expired = job("last-group", "0", "0");
        expired.setCronExpression("0 0 0 1 1 ? 2000");
        service.updateSchedulerJob(expired, "new-group");
        assertFalse(scheduler.checkExists(ScheduleUtils.getJobKey(10L, "new-group")));
        assertFalse(scheduler.checkExists(ScheduleUtils.getJobKey(10L, "last-group")));
    }

    @Test
    void schedulingFailureIsVisibleAndDoesNotPreDeleteOriginal() throws Exception
    {
        var failing = spy(scheduler);
        doThrow(new SchedulerException("injected store failure")).when(failing)
                .scheduleJob(any(JobDetail.class), anySet(), eq(true));
        ReflectionTestUtils.setField(service, "scheduler", failing);
        assertThrows(SchedulerException.class, () -> service.updateSchedulerJob(job("original", "0", "0"), "original"));
        assertTrue(scheduler.checkExists(ScheduleUtils.getJobKey(10L, "original")));
    }

    @Test
    void failureDeletingPreviousGroupRemovesNewDefinitionAndRestoresPreviousPause() throws Exception
    {
        var failing = spy(scheduler);
        doThrow(new SchedulerException("injected old-group delete failure")).when(failing)
                .deleteJob(ScheduleUtils.getJobKey(10L, "original"));
        ReflectionTestUtils.setField(service, "scheduler", failing);
        assertThrows(SchedulerException.class,
                () -> service.updateSchedulerJob(job("new-group", "0", "0"), "original"));
        assertFalse(scheduler.checkExists(ScheduleUtils.getJobKey(10L, "new-group")));
        assertEquals(Trigger.TriggerState.PAUSED,
                scheduler.getTriggerState(ScheduleUtils.getTriggerKey(10L, "original")));
    }

    @Test
    void pauseFailureRestoresPreviousDefinitionAndRecoveryFailureIsSuppressed() throws Exception
    {
        var failing = spy(scheduler);
        doThrow(new SchedulerException("injected pause failure")).when(failing)
                .pauseJob(ScheduleUtils.getJobKey(10L, "original"));
        ReflectionTestUtils.setField(service, "scheduler", failing);
        var replacement = job("original", "3", "1"); replacement.setConcurrent("1");
        assertThrows(SchedulerException.class, () -> service.updateSchedulerJob(replacement, "original"));
        assertEquals(QuartzJobExecution.class,
                scheduler.getJobDetail(ScheduleUtils.getJobKey(10L, "original")).getJobClass());
        assertEquals(Trigger.TriggerState.PAUSED,
                scheduler.getTriggerState(ScheduleUtils.getTriggerKey(10L, "original")));
        doThrow(new SchedulerException("injected recovery failure")).when(failing)
                .pauseTrigger(ScheduleUtils.getTriggerKey(10L, "original"));
        var failure = assertThrows(SchedulerException.class,
                () -> service.updateSchedulerJob(replacement, "original"));
        assertEquals("injected pause failure", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
    }

    @Test
    void immediateRunAndDeleteUseTheReplacedGroup() throws Exception
    {
        var replacement = job("new-group", "0", "0");
        service.updateSchedulerJob(replacement, "original");
        var mapper = mock(SysJobMapper.class);
        ReflectionTestUtils.setField(service, "jobMapper", mapper);
        when(mapper.selectJobById(10L)).thenReturn(replacement);
        when(mapper.deleteJobById(10L)).thenReturn(1);
        assertTrue(service.run(replacement));
        assertEquals(1, service.deleteJob(replacement));
        assertFalse(service.run(replacement));
        assertFalse(scheduler.checkExists(ScheduleUtils.getJobKey(10L, "new-group")));
    }

    private static SysJob job(String group, String misfire, String status)
    {
        var job = new SysJob();
        job.setJobId(10L);
        job.setJobGroup(group);
        job.setCronExpression("0 0 0 1 1 ? 2099");
        job.setMisfirePolicy(misfire);
        job.setStatus(status);
        job.setConcurrent("0");
        return job;
    }
}

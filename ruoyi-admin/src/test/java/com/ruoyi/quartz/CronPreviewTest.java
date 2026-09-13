package com.ruoyi.quartz;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.quartz.controller.SysJobController;
import com.ruoyi.quartz.domain.SysJob;
import com.ruoyi.quartz.service.ISysJobService;
import com.ruoyi.quartz.util.CronUtils;
import com.ruoyi.quartz.util.ScheduleUtils;
import com.ruoyi.system.service.ISysMenuService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.ArgumentCaptor;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 用真实 Quartz 解析与 ScheduleUtils 构建的 Trigger 校验预览，HTTP 层启用正式方法权限。 */
@ResourceLock("java.util.TimeZone.default")
class CronPreviewTest
{
    private static final Date AFTER = Date.from(Instant.parse("2030-01-01T00:00:00Z"));
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX");
    private TimeZone previousTimeZone;

    @BeforeEach
    void useScheduleTimeZone()
    {
        previousTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    }

    @AfterEach
    void restoreRuntimeState()
    {
        TimeZone.setDefault(previousTimeZone);
        SecurityContextHolder.clearContext();
    }

    @Test
    void previewMatchesRealScheduleTriggerForNamesRangesListsNthAndLastWeek() throws Exception
    {
        for (String zone : List.of("Asia/Shanghai", "America/New_York", "UTC"))
        {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            for (String expression : List.of("0 0 9 ? * MON 2030", "0 0 9 ? * 2#5 2030",
                    "0 0 9 ? * MON#5 2030", "0 0 9 ? * SUN-SAT 2030", "0 0 9 ? * FRI-MON 2030",
                    "0 0 9 ? * MON,WED,FRI 2030", "0 0 9 ? * 6L 2030", "0 0 9 ? * FRIL 2030",
                    "0 0 9 ? * L 2030", "0 0 9 ? * 2/2 2030", "0 0 9 ? * 2-4,6 2030",
                    "5 10 8 ? JAN,JUN MON 2030", "0 0 9 LW * ? 2030"))
            {
                assertTrue(CronUtils.isValid(expression), expression);
                Scheduler scheduler = mock(Scheduler.class);
                SysJob job = new SysJob();
                job.setJobId(1L);
                job.setJobGroup("DEFAULT");
                job.setCronExpression(expression);
                job.setConcurrent("1");
                job.setMisfirePolicy("3");
                job.setStatus("0");
                ScheduleUtils.createScheduleJob(scheduler, job);
                ArgumentCaptor<Trigger> captured = ArgumentCaptor.forClass(Trigger.class);
                verify(scheduler).scheduleJob(any(JobDetail.class), captured.capture());
                CronTrigger trigger = (CronTrigger) captured.getValue();
                CronUtils.CronPreview preview = CronUtils.preview(expression, AFTER);
                assertEquals(trigger.getTimeZone().getID(), preview.timeZone());
                Date expected = AFTER;
                for (String time : preview.executionTimes())
                {
                    expected = trigger.getFireTimeAfter(expected);
                    assertEquals(expected.toInstant(), OffsetDateTime.parse(time, FORMAT).toInstant(), expression);
                }
                assertEquals(Math.min(5, countRemaining(trigger, AFTER)), preview.executionTimes().size());
            }
        }
    }

    private int countRemaining(CronTrigger trigger, Date after)
    {
        int count = 0;
        while (count < 5 && (after = trigger.getFireTimeAfter(after)) != null)
        {
            count++;
        }
        return count;
    }

    @Test
    void fifthMondaySkipsMonthsWithoutAnOccurrenceAndPreservesServerOffset()
    {
        assertEquals(List.of("2030-04-29 09:00:00 +08:00", "2030-07-29 09:00:00 +08:00",
                "2030-09-30 09:00:00 +08:00", "2030-12-30 09:00:00 +08:00"),
                CronUtils.preview("0 0 9 ? * 2#5 2030", AFTER).executionTimes());
        assertEquals("2030-01-07 09:00:00 +08:00",
                CronUtils.preview("0 0 9 ? * MON 2030", AFTER).executionTimes().get(0));
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        assertEquals("2030-03-11 09:00:00 -04:00", CronUtils.preview("0 0 9 ? * MON 2030",
                Date.from(Instant.parse("2030-03-10T00:00:00Z"))).executionTimes().get(0));
    }

    @Test
    void invalidExpiredAndExhaustedExpressionsHaveBoundedExplicitResults()
    {
        for (String expression : List.of("", " ", "0", "0 0 9 ? * NaN", "0 0 9 ? * 2#6", "x".repeat(256)))
        {
            assertThrows(IllegalArgumentException.class, () -> CronUtils.preview(expression, AFTER), expression);
        }
        assertThrows(IllegalArgumentException.class, () -> CronUtils.preview(null, AFTER));
        assertEquals(List.of(), CronUtils.preview("0 0 9 ? * MON 2000", AFTER).executionTimes());
        assertEquals(List.of(), CronUtils.preview("0 0 9 31 2 ?", AFTER).executionTimes());
        assertEquals(List.of("2030-01-01 09:00:00 +08:00"),
                CronUtils.preview("0 0 9 1 1 ? 2030", AFTER).executionTimes());
        assertEquals(5, CronUtils.preview("* * * * * ?", AFTER).executionTimes().size());
    }

    @Test
    void httpPreviewAllowsAddOrEditOnlyAndDoesNotTouchTaskServices() throws Exception
    {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(PreviewConfiguration.class))
        {
            MockMvc mvc = MockMvcBuilders.standaloneSetup(context.getBean(SysJobController.class))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            for (String permission : List.of("monitor:job:add", "monitor:job:edit"))
            {
                authenticate(permission);
                mvc.perform(get("/monitor/job/cron/preview").param("cronExpression", "* * * * * ?"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                        .andExpect(jsonPath("$.data.timeZone").value("Asia/Shanghai"))
                        .andExpect(jsonPath("$.data.executionTimes.length()").value(5))
                        .andExpect(jsonPath("$.data.executionTimes[0]").isString());
            }
            authenticate("monitor:job:query");
            mvc.perform(get("/monitor/job/cron/preview").param("cronExpression", "* * * * * ?"))
                    .andExpect(jsonPath("$.code").value(403));
            authenticate("monitor:job:add");
            mvc.perform(get("/monitor/job/cron/preview"))
                    .andExpect(jsonPath("$.code").value(500)).andExpect(jsonPath("$.msg").value("Cron表达式不能为空"));
            mvc.perform(get("/monitor/job/cron/preview").param("cronExpression", "0"))
                    .andExpect(jsonPath("$.code").value(500)).andExpect(jsonPath("$.msg").value("Cron表达式不正确"));
            mvc.perform(get("/monitor/job/cron/preview").param("cronExpression", "x".repeat(256)))
                    .andExpect(jsonPath("$.code").value(500)).andExpect(jsonPath("$.msg").value("Cron执行表达式不能超过255个字符"));
            mvc.perform(get("/monitor/job/cron/preview").param("cronExpression", "0 0 9 ? * MON 2000"))
                    .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.executionTimes").isEmpty());
            verifyNoInteractions(context.getBean(ISysJobService.class), context.getBean(ISysMenuService.class));
        }
    }

    private void authenticate(String permission)
    {
        LoginUser loginUser = new LoginUser();
        loginUser.setPermissions(Set.of(permission));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class PreviewConfiguration
    {
        @Bean
        SysJobController controller() { return new SysJobController(); }

        @Bean
        ISysJobService jobService() { return mock(ISysJobService.class); }

        @Bean
        ISysMenuService menuService() { return mock(ISysMenuService.class); }

        @Bean("ss")
        PermissionService permissionService(ISysMenuService menuService) { return new PermissionService(menuService); }
    }
}

package com.ruoyi.quartz;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.quartz.controller.SysJobController;
import com.ruoyi.quartz.domain.SysJob;
import com.ruoyi.quartz.domain.SysJobLog;
import com.ruoyi.quartz.mapper.SysJobMapper;
import com.ruoyi.quartz.mapper.SysJobLogMapper;
import com.ruoyi.quartz.service.ISysJobService;
import com.ruoyi.quartz.service.ISysJobLogService;
import com.ruoyi.quartz.service.impl.SysJobServiceImpl;
import com.ruoyi.quartz.service.impl.SysJobLogServiceImpl;
import com.ruoyi.quartz.task.RecordingTask;
import com.ruoyi.quartz.task.nested.InheritedTask;
import com.ruoyi.quartz.taskextra.LookalikeTask;
import com.ruoyi.quartz.util.JobInvokeUtil;
import com.ruoyi.quartz.util.ScheduleUtils;
import com.ruoyi.system.service.ISysMenuService;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Production controllers, method security, transactional services, XML Mappers and Quartz jobs.
 * H2 persists business rows/logs; Quartz uses a dedicated RAMJobStore. The task only records inputs.
 * Menu lookup is the only mock. No complete application, HTTP server, JWT or audit AOP is started.
 */
@ResourceLock("SpringUtils")
public class JobInvocationTest
{
    private static final IllegalStateException TASK_DEPENDENCY_FAILURE =
            new IllegalStateException("task dependency unavailable");
    private AnnotationConfigApplicationContext context;
    private Scheduler scheduler;
    private SysJobController controller;
    private ISysJobService jobs;
    private JdbcTemplate jdbc;
    private Object previousBeanFactory;
    private Object previousApplicationContext;

    /** H2 compatibility for the production Mapper's MySQL sysdate() call. */
    public static java.sql.Timestamp sysdate()
    {
        return new java.sql.Timestamp(System.currentTimeMillis());
    }

    @BeforeEach
    void start() throws Exception
    {
        previousBeanFactory = ReflectionTestUtils.getField(SpringUtils.class, "beanFactory");
        previousApplicationContext = ReflectionTestUtils.getField(SpringUtils.class, "applicationContext");
        RecordingTask.CALLS.clear();
        context = new AnnotationConfigApplicationContext(JobConfiguration.class);
        scheduler = context.getBean(Scheduler.class);
        controller = context.getBean(SysJobController.class);
        jobs = context.getBean(ISysJobService.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        authenticate("monitor:job:add", "monitor:job:edit", "monitor:job:changeStatus");
        scheduler.start();
    }

    @AfterEach
    void stop() throws Exception
    {
        try
        {
            if (scheduler != null) scheduler.shutdown(true);
            if (context != null) context.close();
        }
        finally
        {
            ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", previousBeanFactory);
            ReflectionTestUtils.setField(SpringUtils.class, "applicationContext", previousApplicationContext);
            RecordingTask.CALLS.clear();
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void urlLiteralSurvivesAddEditPersistenceAndQuartzExecution() throws Exception
    {
        SysJob job = job("ryTask.ryParams('https://example.test')");
        assertThat(controller.add(job).get("code")).isEqualTo(200);
        assertThat(jobs.selectJobById(job.getJobId()).getInvokeTarget()).isEqualTo(job.getInvokeTarget());
        assertThat(jobs.selectJobById(job.getJobId()).getCreateBy()).isEqualTo("quartz-test");
        assertThat(scheduler.getTriggerState(ScheduleUtils.getTriggerKey(job.getJobId(), job.getJobGroup())))
                .isEqualTo(Trigger.TriggerState.PAUSED);
        assertThat(controller.run(job).get("code")).isEqualTo(200);
        assertThat(RecordingTask.CALLS.poll(5, TimeUnit.SECONDS)).containsExactly("https://example.test");
        awaitLog(job, "0");

        job.setInvokeTarget("ryTask.ryParams('https://example.test/updated')");
        assertThat(controller.edit(job).get("code")).isEqualTo(200);
        assertThat(jobs.selectJobById(job.getJobId()).getInvokeTarget()).isEqualTo(job.getInvokeTarget());
        assertThat(jobs.selectJobById(job.getJobId()).getUpdateBy()).isEqualTo("quartz-test");
        assertThat(controller.run(job).get("code")).isEqualTo(200);
        assertThat(RecordingTask.CALLS.poll(5, TimeUnit.SECONDS)).containsExactly("https://example.test/updated");
        awaitLog(job, "0");
    }

    @Test
    void httpAddEditPreservePermissionsCronAndResponseShape() throws Exception
    {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        SysJob job = job("ryTask.ryParams('https://example.test')");
        authenticate("monitor:job:query");
        mvc.perform(post("/monitor/job").contentType(MediaType.APPLICATION_JSON).content(jobBody(job)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403));
        authenticate("monitor:job:add");
        job.setCronExpression("invalid");
        mvc.perform(post("/monitor/job").contentType(MediaType.APPLICATION_JSON).content(jobBody(job)))
                .andExpect(jsonPath("$.code").value(500));
        assertThat(jdbc.queryForObject("select count(*) from sys_job", Integer.class)).isZero();
        job.setCronExpression("0 0 0 1 1 ? 2099");
        mvc.perform(post("/monitor/job").contentType(MediaType.APPLICATION_JSON).content(jobBody(job)))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.msg").value("操作成功"));
        job = jobs.selectJobList(new SysJob()).get(0);
        job.setInvokeTarget("ryTask.ryParams('https://example.test/edited')");
        mvc.perform(put("/monitor/job").contentType(MediaType.APPLICATION_JSON).content(jobBody(job)))
                .andExpect(jsonPath("$.code").value(403));
        authenticate("monitor:job:edit");
        job.setCronExpression("invalid");
        mvc.perform(put("/monitor/job").contentType(MediaType.APPLICATION_JSON).content(jobBody(job)))
                .andExpect(jsonPath("$.code").value(500));
        job.setCronExpression("0 0 0 1 1 ? 2099");
        mvc.perform(put("/monitor/job").contentType(MediaType.APPLICATION_JSON).content(jobBody(job)))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.msg").value("操作成功"));
        assertThat(jobs.selectJobById(job.getJobId()).getInvokeTarget()).isEqualTo(job.getInvokeTarget());
    }

    @Test
    void beanAndClassCallsRetainNoArgAndAllFiveParameterTypes() throws Exception
    {
        for (String target : List.of("ryTask", RecordingTask.class.getName(), "nestedTask", InheritedTask.class.getName()))
        {
            execute(job(target + ".ryNoParams()"), List.of());
            execute(job(target + ".ryMultipleParams('text, (literal)', TRUE, -12L, 1.25D, +7)"),
                    List.of("text, (literal)", true, -12L, 1.25D, 7));
        }
        // Production methods themselves remain resolvable in both supported target formats.
        for (String target : List.of("ryTask", "com.ruoyi.quartz.task.RyTask"))
        {
            assertThat(JobInvokeUtil.isValidInvokeTarget(target + ".ryParams('https://example.test')")).isTrue();
            assertThat(JobInvokeUtil.isValidInvokeTarget(target + ".ryNoParams()")).isTrue();
        }
    }

    @Test
    void quotedDataNeverParticipatesInAuthorizationOrExpressionEvaluation() throws Exception
    {
        for (String literal : List.of("https://example.test", "rmi: ldap: ldaps: http:// java.net.URL",
                "com.ruoyi.quartz.task org.springframework org.apache javax.naming.InitialContext",
                "org.yaml.snakeyaml com.ruoyi.common.utils.file com.ruoyi.common.config com.ruoyi.generator",
                "a,b (c) \"double quotes\" C:\\data", "", "${someBean.call()}"))
        {
            execute(job("ryTask.ryParams('" + literal + "')"), List.of(literal));
        }
        execute(job("ryTask.ryParams(\"it's literal, (data)\")"), List.of("it's literal, (data)"));
    }

    @Test
    void legacyBeanNamesAndAliasesRoundTrip() throws Exception
    {
        for (String name : List.of("ry-task", "task-alias", "ry/task", "ry task"))
        {
            legacyRoundTrip(name + ".ryParams('plain')", "plain");
        }
    }

    @Test
    void legacyApostrophesRoundTrip() throws Exception
    {
        for (String literal : List.of("O'Brien", "He said 'hi', then left"))
        {
            legacyRoundTrip("ryTask.ryParams('" + literal + "')", literal);
        }
    }

    @Test
    void legacyMixedQuotesRoundTrip() throws Exception
    {
        legacyRoundTrip("ryTask.ryParams('He said \"it's ok\"')", "He said \"it's ok\"");
    }

    @Test
    void callLookingStringsRemainDataAndInvokeOnlyOneMethod() throws Exception
    {
        legacyRoundTrip("ryTask.ryParams('count('x')')", "count('x')");
        legacyRoundTrip("ryTask.ryParams('a');ryTask.ryParams('b')", "a');ryTask.ryParams('b");
        legacyRoundTrip("ryTask.ryParams('a').ryParams('b')", "a').ryParams('b");
    }

    private void legacyRoundTrip(String target, String expected) throws Exception
    {
        SysJob job = job(target);
        execute(job, List.of(expected));
        assertThat(jobs.selectJobById(job.getJobId()).getInvokeTarget()).isEqualTo(target);
        job.setJobName(job.getJobName() + "-edit");
        assertThat(controller.edit(job).get("code")).isEqualTo(200);
        assertThat(jobs.selectJobById(job.getJobId()).getInvokeTarget()).isEqualTo(target);
        assertThat(controller.run(job).get("code")).isEqualTo(200);
        assertThat(RecordingTask.CALLS.poll(5, TimeUnit.SECONDS)).containsExactly(expected);
        awaitLog(job, "0");
        assertThat(RecordingTask.CALLS).isEmpty();
    }

    @Test
    void lazyTaskCreationFailurePropagatesOnSaveAndRuntime() throws Exception
    {
        assertThat(context.getBeanFactory().containsSingleton("failingTask")).isFalse();
        SysJob job = job("failingTask.ryNoParams()");
        assertThatThrownBy(() -> controller.add(job)).isInstanceOf(BeanCreationException.class)
                .satisfies(failure -> assertThat(NestedExceptionUtils.getMostSpecificCause(failure))
                        .isSameAs(TASK_DEPENDENCY_FAILURE));
        assertThat(jdbc.queryForObject("select count(*) from sys_job", Integer.class)).isZero();
        assertThat(jobs.insertJob(job)).isEqualTo(1);
        assertThatThrownBy(() -> controller.edit(job)).isInstanceOf(BeanCreationException.class)
                .satisfies(failure -> assertThat(NestedExceptionUtils.getMostSpecificCause(failure))
                        .isSameAs(TASK_DEPENDENCY_FAILURE));
        assertThat(jobs.run(job)).isTrue();
        assertThat(awaitLog(job, "1").getExceptionInfo())
                .contains("BeanCreationException", "task dependency unavailable");
        assertThat(RecordingTask.CALLS).isEmpty();
    }

    @Test
    void missingDependencyOfRegisteredTaskIsNotAnUnknownTarget() throws Exception
    {
        assertThat(controller.add(job("missingTask.ryNoParams()")).get("code")).isEqualTo(500);
        assertThatThrownBy(() -> controller.add(job("dependencyTask.ryNoParams()")))
                .isInstanceOf(BeanCreationException.class).hasMessageContaining("dependencyTask");
        assertThat(jdbc.queryForObject("select count(*) from sys_job", Integer.class)).isZero();
        assertThat(RecordingTask.CALLS).isEmpty();
    }

    @Test
    void springJdkAndClassProxiesAuthorizeTargetAndStillRunAdvice() throws Exception
    {
        for (boolean classProxy : List.of(false, true))
        {
            AtomicInteger adviceCalls = new AtomicInteger();
            ProxyFactory factory = new ProxyFactory(new RecordingTask());
            factory.setProxyTargetClass(classProxy);
            factory.addAdvice((MethodInterceptor) invocation -> {
                adviceCalls.incrementAndGet();
                return invocation.proceed();
            });
            String name = classProxy ? "classTask" : "jdkTask";
            context.getBeanFactory().registerSingleton(name, factory.getProxy());
            SysJob job = job(name + ".ryParams('https://example.test')");
            assertThat(controller.add(job).get("code")).isEqualTo(200);
            assertThat(adviceCalls.get()).isZero();
            assertThat(controller.run(job).get("code")).isEqualTo(200);
            assertThat(RecordingTask.CALLS.poll(5, TimeUnit.SECONDS)).containsExactly("https://example.test");
            awaitLog(job, "0");
            assertThat(adviceCalls.get()).isEqualTo(1);
            assertThat(JobInvokeUtil.isValidInvokeTarget(name + ".toString()")).isFalse();
            if (!classProxy)
            {
                // The implementation has this method, but this JDK proxy does not expose it.
                assertThat(JobInvokeUtil.isValidInvokeTarget(name + ".ryNoParams()")).isFalse();
                assertThat(controller.add(job(name + ".ryNoParams()")).get("code")).isEqualTo(500);
                assertThatThrownBy(() -> JobInvokeUtil.invokeMethod(job(name + ".ryNoParams()")))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(adviceCalls.get()).isEqualTo(1);
            }
        }
    }

    @Test
    void invalidTargetsAndArgumentsCannotSaveEditOrInvoke() throws Exception
    {
        SysJob saved = job("ryTask.ryNoParams()");
        assertThat(controller.add(saved).get("code")).isEqualTo(200);
        for (String target : invalidTargets())
        {
            assertThat(controller.add(job(target)).get("code")).as(target).isEqualTo(500);
            saved.setInvokeTarget(target);
            assertThat(controller.edit(saved).get("code")).as(target).isEqualTo(500);
            assertThat(jobs.selectJobById(saved.getJobId()).getInvokeTarget()).isEqualTo("ryTask.ryNoParams()");
            assertThatThrownBy(() -> JobInvokeUtil.invokeMethod(job(target))).as(target).isInstanceOf(Exception.class);
        }
        assertThat(jdbc.queryForObject("select count(*) from sys_job", Integer.class)).isEqualTo(1);
        assertThat(RecordingTask.CALLS).isEmpty();
    }

    @Test
    void persistedAndDirectlyScheduledInvalidJobsFailAtRuntimeAndWriteFailureLogs() throws Exception
    {
        for (String target : invalidTargets())
        {
            SysJob job = job(target);
            // Deliberately bypass HTTP validation: existing rows and direct service callers are untrusted at execution.
            assertThat(jobs.insertJob(job)).isEqualTo(1);
            assertThat(jobs.run(job)).isTrue();
            assertThat(awaitLog(job, "1").getExceptionInfo()).isNotBlank();
        }
        SysJob direct = job("lookalike.ryParams('com.ruoyi.quartz.task')");
        direct.setJobId(99999L);
        direct.setConcurrent("0");
        ScheduleUtils.createScheduleJob(scheduler, direct);
        scheduler.triggerJob(ScheduleUtils.getJobKey(direct.getJobId(), direct.getJobGroup()));
        assertThat(awaitLog(direct, "1").getExceptionInfo()).isNotBlank();
        assertThat(RecordingTask.CALLS).isEmpty();
    }

    @Test
    void automaticCronTriggerAndPauseResumeUseTheSameAuthorizedInvocation() throws Exception
    {
        SysJob job = job("ryTask.ryParams('https://example.test/cron')");
        job.setCronExpression("* * * * * ?");
        job.setConcurrent("0");
        assertThat(controller.add(job).get("code")).isEqualTo(200);
        assertThat(RecordingTask.CALLS).isEmpty();
        job.setStatus("0");
        assertThat(controller.changeStatus(job).get("code")).isEqualTo(200);
        assertThat(RecordingTask.CALLS.poll(5, TimeUnit.SECONDS)).containsExactly("https://example.test/cron");
        awaitLog(job, "0");
        job.setStatus("1");
        assertThat(controller.changeStatus(job).get("code")).isEqualTo(200);
        assertThat(jobs.selectJobById(job.getJobId()).getStatus()).isEqualTo("1");
        assertThat(scheduler.getTriggerState(ScheduleUtils.getTriggerKey(job.getJobId(), job.getJobGroup())))
                .isEqualTo(Trigger.TriggerState.PAUSED);
    }

    @Test
    void schedulerConfigurationFailureStillRollsBackBusinessInsert() throws Exception
    {
        SysJob job = job("ryTask.ryParams('https://example.test')");
        job.setMisfirePolicy("unsupported");
        assertThatThrownBy(() -> controller.add(job)).isInstanceOf(com.ruoyi.common.exception.job.TaskException.class);
        assertThat(jdbc.queryForObject("select count(*) from sys_job", Integer.class)).isZero();
        assertThat(scheduler.checkExists(ScheduleUtils.getJobKey(job.getJobId(), job.getJobGroup()))).isFalse();
        assertThat(RecordingTask.CALLS).isEmpty();
    }

    private void execute(SysJob job, List<Object> expected) throws Exception
    {
        assertThat(controller.add(job).get("code")).as(job.getInvokeTarget()).isEqualTo(200);
        assertThat(controller.run(job).get("code")).isEqualTo(200);
        assertThat(RecordingTask.CALLS.poll(5, TimeUnit.SECONDS)).isEqualTo(expected);
        awaitLog(job, "0");
        assertThat(RecordingTask.CALLS).isEmpty();
    }

    private static List<String> invalidTargets()
    {
        return List.of("lookalike.ryParams('com.ruoyi.quartz.task')",
                "lookalike.ryParams('https://example.test java.net.URL')",
                "lookalike.ryParams('count('x')')", "lookalike.ryParams('a');ryTask.ryParams('b')",
                "com.ruoyi.quartz.taskextra.LookalikeTask.ryNoParams()",
                "controller.list('com.ruoyi.quartz.task')", "java.lang.String.toString()",
                "missingBean.ryNoParams()", "com.ruoyi.quartz.task.MissingTask.ryNoParams()",
                "ryTask.toString()", "ryTask.getClass()", "ryTask.hashCode()", "ryTask.notify()", "ryTask.wait()",
                "com.ruoyi.quartz.task.RecordingTask.toString()", "ryTask.missing()",
                "ryTask.ryParams()", "ryTask.ryParams(1)", "ryTask.ryParams('a','b')",
                "ryTask.ryParams('a',)", "ryTask.ryParams('unterminated)", "ryTask.ryParams('a'junk)",
                "ryTask.ryParams('a') trailing", "ryTask.ryParams('a');ryTask.ryNoParams()",
                "ryTask.ryParams(ryTask.getClass())", "ryTask.ryNoParams", "ryTask.ryNoParams( )",
                "ryTask.ryMultipleParams('a', true, 1, 1D, 1)",
                "ryTask.ryMultipleParams('a', true, 1L, 1.2, 1)",
                "ryTask.ryMultipleParams('a', true, 1L, 1D, 2147483648)");
    }

    private SysJobLog awaitLog(SysJob job, String status) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do
        {
            SysJobLog query = new SysJobLog();
            query.setJobName(job.getJobName());
            query.setJobGroup(job.getJobGroup());
            List<SysJobLog> logs = context.getBean(ISysJobLogService.class).selectJobLogList(query).stream()
                    .filter(log -> job.getInvokeTarget().equals(log.getInvokeTarget())).toList();
            if (!logs.isEmpty())
            {
                SysJobLog log = logs.get(0);
                assertThat(log.getStatus()).isEqualTo(status);
                assertThat(log.getStartTime()).isNotNull();
                assertThat(log.getEndTime()).isNotNull();
                return log;
            }
            Thread.sleep(20);
        }
        while (System.nanoTime() < deadline);
        throw new AssertionError("Quartz did not persist a log for " + job.getInvokeTarget());
    }

    private static SysJob job(String target)
    {
        SysJob job = new SysJob();
        job.setJobName("literal-" + UUID.randomUUID());
        job.setJobGroup("L02");
        job.setInvokeTarget(target);
        job.setCronExpression("0 0 0 1 1 ? 2099");
        job.setMisfirePolicy("3");
        job.setConcurrent("1");
        job.setStatus("1");
        return job;
    }

    private static String jobBody(SysJob job)
    {
        return JSON.toJSONString(Map.of("jobId", job.getJobId() == null ? 0L : job.getJobId(),
                "jobName", job.getJobName(), "jobGroup", job.getJobGroup(), "invokeTarget", job.getInvokeTarget(),
                "cronExpression", job.getCronExpression(), "misfirePolicy", job.getMisfirePolicy(),
                "concurrent", job.getConcurrent(), "status", job.getStatus()));
    }

    private static void authenticate(String... permissions)
    {
        SysUser user = new SysUser();
        user.setUserId(2L);
        user.setUserName("quartz-test");
        LoginUser login = new LoginUser(2L, 1L, user, Set.of(permissions));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(login, null, List.of()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    @EnableTransactionManagement
    static class JobConfiguration
    {
        @Bean(destroyMethod = "")
        Scheduler scheduler() throws Exception
        {
            Properties properties = new Properties();
            properties.setProperty("org.quartz.scheduler.instanceName", "L02-" + UUID.randomUUID());
            properties.setProperty("org.quartz.threadPool.threadCount", "1");
            properties.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
            return new StdSchedulerFactory(properties).getScheduler();
        }

        @Bean
        static SpringUtils springUtils() { return new SpringUtils(); }

        @Bean("ryTask")
        RecordingTask task() { return new RecordingTask(); }

        @Bean({"ry-task", "task-alias", "ry/task", "ry task"})
        RecordingTask namedTask() { return new RecordingTask(); }

        @Bean("failingTask")
        @Lazy
        RecordingTask failingTask() { throw TASK_DEPENDENCY_FAILURE; }

        @Bean("dependencyTask")
        @Lazy
        RecordingTask dependencyTask(@Qualifier("missingTaskDependency") RecordingTask dependency) { return dependency; }

        @Bean("nestedTask")
        InheritedTask nestedTask() { return new InheritedTask(); }

        @Bean("lookalike")
        LookalikeTask lookalike() { return new LookalikeTask(); }

        @Bean
        SysJobController controller() { return new SysJobController(); }

        @Bean
        ISysJobService jobService() { return new SysJobServiceImpl(); }

        @Bean
        ISysJobLogService jobLogService() { return new SysJobLogServiceImpl(); }

        @Bean("ss")
        PermissionService permissions() { return new PermissionService(mock(ISysMenuService.class)); }

        @Bean(destroyMethod = "shutdown")
        EmbeddedDatabase dataSource()
        {
            return new EmbeddedDatabaseBuilder()
                    .generateUniqueName(true)
                    .setType(EmbeddedDatabaseType.H2)
                    .addScript("classpath:quartz/job-invocation-schema.sql").build();
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource source)
        {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        SqlSessionTemplate sqlSession(DataSource source) throws Exception
        {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setTypeAliasesPackage("com.ruoyi.quartz.domain");
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/quartz/*.xml"));
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        SysJobMapper jobMapper(SqlSessionTemplate session) { return session.getMapper(SysJobMapper.class); }

        @Bean
        SysJobLogMapper jobLogMapper(SqlSessionTemplate session) { return session.getMapper(SysJobLogMapper.class); }
    }
}

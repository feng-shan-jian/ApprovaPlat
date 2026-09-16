package com.ruoyi.system.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.domain.entity.*;
import com.ruoyi.common.core.domain.model.RegisterBody;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.flowable.testsupport.WorkflowMySqlITSupport;
import com.ruoyi.framework.manager.AsyncManager;
import com.ruoyi.framework.web.service.SysRegisterService;
import com.ruoyi.system.domain.*;
import com.ruoyi.system.exception.*;
import com.ruoyi.system.mapper.*;

/** 真实 MySQL、生产 Mapper/服务/Spring 事务；只替代 Redis 和异步审计调度边界。 */
class SystemUniqueWriteMySqlIT
{
    private static MysqlDataSource source;
    private final String owner = "c12-" + UUID.randomUUID().toString().substring(0, 12);
    private final RecordingCache cache = new RecordingCache();
    private final SqlBoundary boundary = new SqlBoundary();
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private SysUserServiceImpl users;
    private SysConfigServiceImpl configs;
    private SysUserMapper userMapper;
    private ValidatorFactory validators;

    @BeforeAll
    static void verifyDatabase() throws Exception
    {
        source = WorkflowMySqlITSupport.createDataSource();
        WorkflowMySqlITSupport.verifyIsolatedBaseline(source, "系统唯一写入 IT", "approvaplat_it",
                List.of("sys_user", "sys_config", "sys_user_role", "sys_user_post", "sys_dept"));
        var jdbc = new JdbcTemplate(source);
        assertEquals(2, jdbc.queryForObject("select count(*) from information_schema.statistics "
                + "where table_schema=database() and non_unique=0 "
                + "and index_name in ('uk_sys_user_active_name','uk_sys_config_key')", Integer.class),
                "测试库必须先安装 C12 正式迁移，测试不得自行修复共享基线");
    }

    @BeforeEach
    void setUp() throws Exception
    {
        jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(manager);
        transactions.setTimeout(15);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliases(SysUser.class, SysRole.class, SysDept.class, SysConfig.class, SysUserRole.class, SysUserPost.class);
        factory.setMapperLocations(List.of("SysUser", "SysUserRole", "SysUserPost", "SysConfig", "SysDept")
                .stream().map(name -> new ClassPathResource("mapper/system/" + name + "Mapper.xml"))
                .toArray(ClassPathResource[]::new));
        factory.setPlugins(boundary);
        var session = new SqlSessionTemplate(factory.getObject());
        userMapper = session.getMapper(SysUserMapper.class);
        configs = new SysConfigServiceImpl();
        ReflectionTestUtils.setField(configs, "configMapper", session.getMapper(SysConfigMapper.class));
        ReflectionTestUtils.setField(configs, "redisCache", cache);
        configs = WorkflowMySqlITSupport.transactionalProxy(configs, manager);
        var departments = new SysDeptServiceImpl();
        ReflectionTestUtils.setField(departments, "deptMapper", session.getMapper(SysDeptMapper.class));
        users = new SysUserServiceImpl();
        ReflectionTestUtils.setField(users, "userMapper", userMapper);
        ReflectionTestUtils.setField(users, "userRoleMapper", session.getMapper(SysUserRoleMapper.class));
        ReflectionTestUtils.setField(users, "userPostMapper", session.getMapper(SysUserPostMapper.class));
        ReflectionTestUtils.setField(users, "configService", configs);
        ReflectionTestUtils.setField(users, "deptService", departments);
        validators = Validation.buildDefaultValidatorFactory();
        ReflectionTestUtils.setField(users, "validator", validators.getValidator());
        users = WorkflowMySqlITSupport.transactionalProxy(users, manager);
    }

    @AfterEach
    void cleanOwnedRows()
    {
        if (validators != null) validators.close();
        if (jdbc == null) return;
        jdbc.update("delete ur from sys_user_role ur join sys_user u on u.user_id=ur.user_id where u.user_name like ?", owner + "%");
        jdbc.update("delete up from sys_user_post up join sys_user u on u.user_id=up.user_id where u.user_name like ?", owner + "%");
        jdbc.update("delete from sys_user where user_name like ?", owner + "%");
        jdbc.update("delete from sys_config where create_by=?", owner);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void concurrentSameValueUsesTwoConnectionsAndOnlyOneCommits(boolean config) throws Exception
    {
        SysUser first = user("same"), second = user("same");
        first.setRoleIds(new Long[] { 2L }); second.setRoleIds(new Long[] { 3L });
        first.setPostIds(new Long[] { 1L }); second.setPostIds(new Long[] { 2L });
        SysConfig one = config("same"), two = config("same");
        two.setConfigValue("loser");
        Throwable error = competing(config ? () -> configs.insertConfig(one) : () -> users.insertUser(first),
                config ? () -> configs.insertConfig(two) : () -> users.insertUser(second), true);
        if (config) assertInstanceOf(ConfigKeyConflictException.class, error);
        else assertInstanceOf(UserNameConflictException.class, error);
        assertEquals(1, owned(config ? "sys_config" : "sys_user"));
        if (config)
            assertEquals("value", cache.values.get(CacheConstants.SYS_CONFIG_KEY + one.getConfigKey()));
        else
        {
            assertEquals(1, jdbc.queryForObject("select count(*) from sys_user_role where user_id=?", Integer.class, first.getUserId()));
            assertEquals(1, jdbc.queryForObject("select count(*) from sys_user_post where user_id=?", Integer.class, first.getUserId()));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void differentValuesCommitIndependently(boolean config) throws Exception
    {
        assertNull(competing(config ? () -> configs.insertConfig(config("one")) : () -> users.insertUser(user("one")),
                config ? () -> configs.insertConfig(config("two")) : () -> users.insertUser(user("two")), false));
        assertEquals(2, owned(config ? "sys_config" : "sys_user"));
    }

    @Test
    void disabledAccountStillOwnsNameAndSoftDeletionCanBeRepeated() throws Exception
    {
        SysUser disabled = user("reuse"); disabled.setStatus("1");
        users.insertUser(disabled);
        assertThrows(UserNameConflictException.class, () -> users.registerUser(user("REUSE")));
        for (int i = 0; i < 3; i++)
        {
            assertEquals(1, userMapper.deleteUserByIds(new Long[] { disabled.getUserId() }));
            disabled = user("reuse");
            assertTrue(users.registerUser(disabled));
        }
        assertEquals(4, owned("sys_user"));
        assertEquals(3, jdbc.queryForObject("select count(*) from sys_user where create_by=? and active_user_name is null", Integer.class, owner));
        assertThrows(DuplicateKeyException.class, () -> jdbc.update("update sys_user set del_flag='0' where create_by=?", owner));
        assertEquals(1, jdbc.queryForObject("select count(*) from sys_user where create_by=? and del_flag='0'", Integer.class, owner));
    }

    @Test
    void configCollationMatchesLookupAndDuplicateRenamePreservesBothCaches()
    {
        SysConfig first = config("first"), second = config("second");
        configs.insertConfig(first); configs.insertConfig(second);
        assertEquals("value", configs.selectConfigByKey(first.getConfigKey().toUpperCase(Locale.ROOT)));
        assertThrows(ConfigKeyConflictException.class, () -> configs.insertConfig(config("FIRST")));
        first.setConfigId(configId(first)); first.setConfigKey(second.getConfigKey()); first.setConfigValue("collision");
        Map<String, Object> before = Map.copyOf(cache.values);
        assertThrows(ConfigKeyConflictException.class, () -> configs.updateConfig(first));
        assertEquals(before, cache.values);
        assertEquals(2, owned("sys_config"));
        assertEquals("value", jdbc.queryForObject("select config_value from sys_config where config_id=?", String.class, first.getConfigId()));
    }

    @Test
    void configCacheWaitsForCommitAndOuterRollbackLeavesNoPublication()
    {
        SysConfig original = config("original"); configs.insertConfig(original);
        original.setConfigId(configId(original));
        Map<String, Object> before = Map.copyOf(cache.values);
        transactions.executeWithoutResult(tx -> {
            original.setConfigKey(owner + "-renamed"); original.setConfigValue("changed");
            configs.updateConfig(original);
            configs.insertConfig(config("new"));
            assertEquals(before, cache.values);
            tx.setRollbackOnly();
        });
        assertEquals(before, cache.values);
        assertEquals(1, owned("sys_config"));
        assertEquals(owner + "-original", jdbc.queryForObject("select config_key from sys_config where config_id=?", String.class, original.getConfigId()));
        configs.updateConfig(original);
        assertFalse(cache.values.containsKey(CacheConstants.SYS_CONFIG_KEY + owner + "-original"));
        assertEquals("changed", cache.values.get(CacheConstants.SYS_CONFIG_KEY + owner + "-renamed"));
    }

    @Test
    void concurrentConfigRenamesKeepTheLosersOriginalValueAndCache() throws Exception
    {
        SysConfig first = config("first"), second = config("second");
        configs.insertConfig(first); configs.insertConfig(second);
        first.setConfigId(configId(first)); second.setConfigId(configId(second));
        first.setConfigKey(owner + "-target"); second.setConfigKey(owner + "-target");
        first.setConfigValue("winner"); second.setConfigValue("loser");
        assertInstanceOf(ConfigKeyConflictException.class,
                competing(() -> configs.updateConfig(first), () -> configs.updateConfig(second), true));
        assertEquals(owner + "-second", jdbc.queryForObject("select config_key from sys_config where config_id=?", String.class, second.getConfigId()));
        assertEquals("value", cache.values.get(CacheConstants.SYS_CONFIG_KEY + owner + "-second"));
        assertEquals("winner", cache.values.get(CacheConstants.SYS_CONFIG_KEY + owner + "-target"));
        assertFalse(cache.values.containsKey(CacheConstants.SYS_CONFIG_KEY + owner + "-first"));
    }

    @Test
    void profileUpdateNeverRenamesTheAccount()
    {
        SysUser existing = user("one"), other = user("two");
        users.insertUser(existing); users.insertUser(other);
        existing.setUserName(other.getUserName()); existing.setNickName("edited");
        assertEquals(1, users.updateUser(existing));
        assertEquals(owner + "-one", jdbc.queryForObject("select user_name from sys_user where user_id=?", String.class, existing.getUserId()));
        assertEquals("edited", jdbc.queryForObject("select nick_name from sys_user where user_id=?", String.class, existing.getUserId()));
    }

    @Test
    void associationFailureRollsBackUserAndEarlierAssociationWithoutMisclassification()
    {
        SysUser row = user("role-fail");
        row.setPostIds(new Long[] { 1L }); row.setRoleIds(new Long[] { 2L, 2L });
        DuplicateKeyException error = assertThrows(DuplicateKeyException.class, () -> users.insertUser(row));
        assertFalse(error instanceof UserNameConflictException);
        assertEquals(0, owned("sys_user"));
        assertEquals(0, jdbc.queryForObject("select count(*) from sys_user_post where user_id=?", Integer.class, row.getUserId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from sys_user_role where user_id=?", Integer.class, row.getUserId()));
    }

    @Test
    void primaryAndSqlFailuresAreNotReportedAsNameConflict()
    {
        SysUser existing = user("existing"); users.insertUser(existing);
        SysUser other = user("other"); other.setUserId(existing.getUserId());
        DuplicateKeyException error = assertThrows(DuplicateKeyException.class, () -> users.registerUser(other));
        assertFalse(error instanceof UserNameConflictException);
        SysUser invalid = user("invalid"); invalid.setNickName(null);
        assertFalse(assertThrows(DataAccessException.class, () -> users.registerUser(invalid)) instanceof UserNameConflictException);
        var unknown = new DuplicateKeyException("unknown", new SQLException(
                "Duplicate entry 'value' for key 'sys_user.unrelated_key'", "23000", 1062));
        assertSame(unknown, UserNameConflictException.translate(unknown));
        assertSame(unknown, ConfigKeyConflictException.translate(unknown));
    }

    @Test
    void registerKeepsMessageAndSchedulesSuccessAuditOnlyForCommittedInsert()
    {
        SysUser existing = user("r"); users.insertUser(existing);
        SysRegisterService register = new SysRegisterService();
        ReflectionTestUtils.setField(register, "userService", users);
        ReflectionTestUtils.setField(register, "configService", configs);
        cache.values.put(CacheConstants.SYS_CONFIG_KEY + "sys.account.captchaEnabled", "false");
        RegisterBody body = new RegisterBody(); body.setUsername(existing.getUserName()); body.setPassword("pass123");
        var audit = mock(AsyncManager.class);
        Object prior = ReflectionTestUtils.getField(SpringUtils.class, "beanFactory");
        var beans = new DefaultListableBeanFactory();
        var messages = new StaticMessageSource();
        messages.addMessage("user.register.success", Locale.getDefault(), "registered");
        beans.registerSingleton("messageSource", messages);
        new SpringUtils().postProcessBeanFactory(beans);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try (var manager = mockStatic(AsyncManager.class))
        {
            manager.when(AsyncManager::me).thenReturn(audit);
            assertEquals("保存用户'" + existing.getUserName() + "'失败，注册账号已存在", register.register(body));
            verifyNoInteractions(audit);
            body.setUsername(owner + "-ok");
            assertEquals("", register.register(body));
            verify(audit).execute(any(TimerTask.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from sys_user where user_name=?", Integer.class, body.getUsername()));
        }
        finally
        {
            RequestContextHolder.resetRequestAttributes();
            ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", prior);
        }
    }

    @Test
    void racingImportsReportExistingAccountWithoutRetryOrPartialRow() throws Exception
    {
        var workers = Executors.newFixedThreadPool(2);
        var read = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        boundary.afterIdentityRead = () -> { read.countDown(); await(release); };
        try
        {
            var first = workers.submit(() -> importResult(user("import")));
            var second = workers.submit(() -> importResult(user("import")));
            assertTrue(read.await(10, TimeUnit.SECONDS)); release.countDown();
            List<String> results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(s -> s.startsWith("恭喜您")).count());
            assertEquals(1, results.stream().filter(s -> s.contains("<br/>1、账号 " + owner + "-import 已存在")).count());
            assertEquals(1, owned("sys_user"));
        }
        finally { release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    private String importResult(SysUser user)
    {
        try { return users.importUser(List.of(user), false, owner); }
        catch (ServiceException error) { return error.getMessage(); }
    }

    private Throwable competing(Runnable firstWrite, Runnable secondWrite, boolean same) throws Exception
    {
        var workers = Executors.newFixedThreadPool(2);
        var written = new CountDownLatch(1); var attempted = new CountDownLatch(1); var release = new CountDownLatch(1);
        Set<Long> connections = ConcurrentHashMap.newKeySet();
        try
        {
            var first = workers.submit(() -> transactions.executeWithoutResult(tx -> {
                connections.add(jdbc.queryForObject("select connection_id()", Long.class));
                firstWrite.run(); written.countDown(); await(release);
            }));
            assertTrue(written.await(10, TimeUnit.SECONDS));
            boundary.beforeWrite = statement -> { if (statement.endsWith(".insertUser") || statement.endsWith(".insertConfig") || statement.endsWith(".updateConfig")) attempted.countDown(); };
            var second = workers.submit(() -> transactions.executeWithoutResult(tx -> {
                connections.add(jdbc.queryForObject("select connection_id()", Long.class)); secondWrite.run();
            }));
            assertTrue(attempted.await(10, TimeUnit.SECONDS));
            if (same) assertThrows(TimeoutException.class, () -> second.get(250, TimeUnit.MILLISECONDS));
            else second.get(10, TimeUnit.SECONDS);
            release.countDown(); first.get(10, TimeUnit.SECONDS);
            assertEquals(2, connections.size());
            if (same) return assertThrows(ExecutionException.class, () -> second.get(10, TimeUnit.SECONDS)).getCause();
            return null;
        }
        finally { release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    private SysUser user(String suffix)
    {
        SysUser value = new SysUser(); value.setUserName(owner + "-" + suffix); value.setNickName(suffix);
        value.setCreateBy(owner); value.setStatus("0"); return value;
    }

    private SysConfig config(String suffix)
    {
        SysConfig value = new SysConfig(); value.setConfigName(suffix); value.setConfigKey(owner + "-" + suffix);
        value.setConfigValue("value"); value.setConfigType("N"); value.setCreateBy(owner); return value;
    }

    private long configId(SysConfig value)
    {
        return jdbc.queryForObject("select config_id from sys_config where config_key=?", Long.class, value.getConfigKey());
    }

    private int owned(String table)
    {
        return jdbc.queryForObject("select count(*) from " + table + " where create_by=?", Integer.class, owner);
    }

    private static void await(CountDownLatch latch)
    {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    static class RecordingCache extends RedisCache
    {
        final Map<String, Object> values = new ConcurrentHashMap<>();
        @Override public <T> void setCacheObject(String key, T value) { values.put(key, value); }
        @Override @SuppressWarnings("unchecked") public <T> T getCacheObject(String key) { return (T) values.get(key); }
        @Override public boolean deleteObject(String key) { return values.remove(key) != null; }
    }

    @Intercepts({ @Signature(type = Executor.class, method = "update", args = { MappedStatement.class, Object.class }),
            @Signature(type = Executor.class, method = "query", args = { MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class }) })
    static class SqlBoundary implements Interceptor
    {
        volatile Consumer<String> beforeWrite = statement -> {};
        volatile Runnable afterIdentityRead = () -> {};
        @Override public Object intercept(Invocation invocation) throws Throwable
        {
            String id = ((MappedStatement) invocation.getArgs()[0]).getId();
            if (invocation.getMethod().getName().equals("update")) beforeWrite.accept(id);
            Object result = invocation.proceed();
            if (id.endsWith(".selectActiveUserIdentityByName")) afterIdentityRead.run();
            return result;
        }
    }
}

package com.ruoyi.system.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.domain.entity.SysDictData;
import com.ruoyi.common.core.domain.entity.SysDictType;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.flowable.testsupport.WorkflowMySqlITSupport;
import com.ruoyi.system.domain.SysConfig;
import com.ruoyi.system.mapper.SysConfigMapper;
import com.ruoyi.system.mapper.SysDictDataMapper;
import com.ruoyi.system.mapper.SysDictTypeMapper;

/**
 * 真实 MySQL + 生产 XML Mapper + Spring 事务代理验证集合删除和引用竞争。
 * 仅 Redis 边界使用内存替身；SQL 拦截器只记录、协调时序或执行真实失败 SQL，不伪造数据库返回值。
 */
class SysConfigDictDeletionMySqlIT
{
    private static MysqlDataSource dataSource;
    private final String owner = "c13-it-" + UUID.randomUUID();
    private final RecordingCache cache = new RecordingCache();
    private final SqlBoundary boundary = new SqlBoundary();
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private SysConfigServiceImpl configs;
    private SysDictTypeServiceImpl types;
    private SysDictDataServiceImpl data;
    private Object previousBeanFactory;

    @BeforeAll
    static void verifyDatabase() throws Exception
    {
        dataSource = WorkflowMySqlITSupport.createDataSource();
        WorkflowMySqlITSupport.verifyIsolatedBaseline(dataSource, "参数字典原子删除 IT",
                "approvaplat_it", List.of("sys_config", "sys_dict_type", "sys_dict_data"));
    }

    @BeforeEach
    void setUp() throws Exception
    {
        jdbc = new JdbcTemplate(dataSource);
        assertEquals(3, jdbc.queryForObject("select count(*) from information_schema.tables "
                + "where table_schema=database() and table_name in "
                + "('sys_config','sys_dict_type','sys_dict_data') and engine='InnoDB'", Integer.class));
        var manager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(manager);
        transactions.setTimeout(15);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTypeAliases(SysConfig.class, SysDictType.class, SysDictData.class);
        factory.setMapperLocations(new ClassPathResource("mapper/system/SysConfigMapper.xml"),
                new ClassPathResource("mapper/system/SysDictTypeMapper.xml"),
                new ClassPathResource("mapper/system/SysDictDataMapper.xml"));
        factory.setPlugins(boundary);
        var session = new SqlSessionTemplate(factory.getObject());
        var configMapper = session.getMapper(SysConfigMapper.class);
        var typeMapper = session.getMapper(SysDictTypeMapper.class);
        var dataMapper = session.getMapper(SysDictDataMapper.class);
        configs = new SysConfigServiceImpl();
        ReflectionTestUtils.setField(configs, "configMapper", configMapper);
        ReflectionTestUtils.setField(configs, "redisCache", cache);
        configs = WorkflowMySqlITSupport.transactionalProxy(configs, manager);
        types = new SysDictTypeServiceImpl();
        ReflectionTestUtils.setField(types, "dictTypeMapper", typeMapper);
        ReflectionTestUtils.setField(types, "dictDataMapper", dataMapper);
        types = WorkflowMySqlITSupport.transactionalProxy(types, manager);
        data = new SysDictDataServiceImpl();
        ReflectionTestUtils.setField(data, "dictTypeMapper", typeMapper);
        ReflectionTestUtils.setField(data, "dictDataMapper", dataMapper);
        data = WorkflowMySqlITSupport.transactionalProxy(data, manager);
        previousBeanFactory = ReflectionTestUtils.getField(SpringUtils.class, "beanFactory");
        var beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("redisCache", cache);
        new SpringUtils().postProcessBeanFactory(beanFactory);
    }

    @AfterEach
    void cleanOnlyOwnedRows()
    {
        ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", previousBeanFactory);
        jdbc.update("delete from sys_dict_data where create_by=?", owner);
        jdbc.update("delete from sys_dict_type where create_by=?", owner);
        jdbc.update("delete from sys_config where create_by=?", owner);
    }

    @Test
    void protectedLaterConfigRejectsWholeRequestInRequestOrder()
    {
        SysConfig first = config("first", "N");
        SysConfig second = config("second", "Y");
        SysConfig third = config("third", "Y");
        ServiceException error = assertThrows(ServiceException.class, () -> configs.deleteConfigByIds(
                new Long[] { first.getConfigId(), third.getConfigId(), second.getConfigId() }));
        assertEquals("内置参数【" + third.getConfigKey() + "】不能删除 ", error.getMessage());
        assertEquals(3, ownedCount("sys_config"));
        assertTrue(cache.deleted.isEmpty());
        assertEquals(0, boundary.count("deleteConfigByIds"));
    }

    @Test
    void referencedLaterTypeRejectsWholeRequestIncludingDisabledAndCaseVariantData()
    {
        SysDictType first = type("first");
        SysDictType second = type("second");
        SysDictData reference = datum(second.getDictType().toUpperCase());
        reference.setStatus("1");
        data.insertDictData(reference);
        cache.deleted.clear();
        ServiceException error = assertThrows(ServiceException.class, () -> types.deleteDictTypeByIds(
                new Long[] { first.getDictId(), second.getDictId() }));
        assertEquals(second.getDictName() + "已分配,不能删除", error.getMessage());
        assertEquals(2, ownedCount("sys_dict_type"));
        assertEquals(1, ownedCount("sys_dict_data"));
        assertTrue(cache.deleted.isEmpty());
        assertEquals(0, boundary.count("deleteDictTypeByIds"));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void successfulSetDeletionWaitsForOuterCommitBeforeInvalidating(boolean dictionary)
    {
        Long[] ids = targets(dictionary);
        List<String> keys = new ArrayList<>(cache.values.keySet());
        transactions.executeWithoutResult(tx -> {
            delete(dictionary, ids);
            assertEquals(0, ownedCount(table(dictionary)));
            assertTrue(cache.deleted.isEmpty(), "加入外层事务时不得提前失效缓存");
            assertTrue(cache.values.keySet().containsAll(keys));
        });
        assertEquals(0, ownedCount(table(dictionary)));
        assertEquals(keys.size(), cache.deleted.size());
        assertFalse(cache.values.keySet().stream().anyMatch(keys::contains));
        assertEquals(1, boundary.count(dictionary ? "deleteDictTypeByIds" : "deleteConfigByIds"));
        assertEquals(1, boundary.count(dictionary ? "selectDictTypesByIdsForUpdate" : "selectConfigsByIdsForUpdate"));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void realDatabaseWriteFailureAfterBulkDeleteRollsBackAllTargets(boolean dictionary)
    {
        Long[] ids = targets(dictionary);
        String deletion = dictionary ? "deleteDictTypeByIds" : "deleteConfigByIds";
        boundary.after = statement -> {
            if (statement.endsWith("." + deletion))
            {
                assertEquals(0, ownedCount(table(dictionary)), "真实批量删除必须先在同一事务中生效");
                // 由 MySQL strict mode 拒绝的真实写入；不需要测试账号拥有 DDL 或触发器权限。
                jdbc.update("insert into sys_config(config_id,create_by) values('c13-invalid-integer',?)", owner);
            }
        };
        DataAccessException error = assertThrows(DataAccessException.class, () -> delete(dictionary, ids));
        assertTrue(error.getMostSpecificCause().getMessage().contains("Incorrect integer value"));
        assertEquals(2, ownedCount(table(dictionary)));
        assertEquals(2, cache.values.size());
        assertTrue(cache.deleted.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void outerRollbackRetainsRowsAndCaches(boolean dictionary)
    {
        Long[] ids = targets(dictionary);
        transactions.executeWithoutResult(tx -> {
            delete(dictionary, ids);
            tx.setRollbackOnly();
        });
        assertEquals(2, ownedCount(table(dictionary)));
        assertEquals(2, cache.values.size());
        assertTrue(cache.deleted.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void cacheFailureKeepsOriginalCauseAndCommittedDeletionThenExistingRefreshRecovers(boolean dictionary)
    {
        Long[] ids = targets(dictionary);
        RuntimeException failure = new IllegalStateException("c13 redis connection unavailable");
        cache.failure = failure;
        assertSame(failure, assertThrows(RuntimeException.class, () -> delete(dictionary, ids)));
        assertEquals(0, ownedCount(table(dictionary)), "缓存故障发生时数据库已提交");
        assertEquals(2, cache.values.size());
        cache.failure = null;
        if (dictionary)
        {
            types.resetDictCache();
        }
        else
        {
            configs.resetConfigCache();
        }
        assertFalse(cache.values.keySet().stream().anyMatch(key -> key.contains(owner)));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void referenceWriterWinsAndWaitingDeleteRejects(boolean movingExistingData) throws Exception
    {
        SysDictType target = type("target");
        SysDictData reference = referenceFor(target, movingExistingData);
        withUncommittedWinner(() -> write(reference, movingExistingData),
                () -> types.deleteDictTypeByIds(new Long[] { target.getDictId() }),
                "selectDictTypesByIdsForUpdate", target.getDictName() + "已分配,不能删除");
        assertEquals(1, referenceCount(target.getDictType()));
        assertNotNull(types.selectDictTypeById(target.getDictId()));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void deleteWinsAndWaitingInsertOrMoveRejectsMissingParent(boolean movingExistingData) throws Exception
    {
        SysDictType target = type("target");
        SysDictData reference = referenceFor(target, movingExistingData);
        withUncommittedWinner(() -> types.deleteDictTypeByIds(new Long[] { target.getDictId() }),
                () -> write(reference, movingExistingData),
                "selectDictTypeForUpdate", "字典类型不存在或已删除");
        assertEquals(0, referenceCount(target.getDictType()));
        assertNull(types.selectDictTypeById(target.getDictId()));
        assertEquals(movingExistingData ? 1 : 0, ownedCount("sys_dict_data"));
        if (movingExistingData)
        {
            assertNotEquals(target.getDictType(), data.selectDictDataById(reference.getDictCode()).getDictType());
        }
    }

    @Test
    void olderRepeatableReadSnapshotDoesNotHideCommittedReference() throws Exception
    {
        SysDictType target = type("target");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try
        {
            transactions.executeWithoutResult(tx -> {
                assertEquals("REPEATABLE-READ", jdbc.queryForObject("select @@transaction_isolation", String.class));
                assertEquals(0, referenceCount(target.getDictType()));
                awaitResult(worker.submit(() -> data.insertDictData(datum(target.getDictType()))));
                assertEquals(0, referenceCount(target.getDictType()), "先证明外层事务仍持有旧 RR 快照");
                ServiceException error = assertThrows(ServiceException.class,
                        () -> types.deleteDictTypeByIds(new Long[] { target.getDictId() }));
                assertEquals(target.getDictName() + "已分配,不能删除", error.getMessage());
                tx.setRollbackOnly();
            });
        }
        finally
        {
            shutdown(worker);
        }
        assertEquals(1, referenceCount(target.getDictType()));
        assertNotNull(types.selectDictTypeById(target.getDictId()));
    }

    @Test
    void concurrentPromotionToBuiltInWinsBeforeDeletion() throws Exception
    {
        SysConfig target = config("target", "N");
        target.setConfigType("Y");
        target.setConfigValue("promoted");
        withUncommittedWinner(() -> configs.updateConfig(target),
                () -> configs.deleteConfigByIds(new Long[] { target.getConfigId() }),
                "selectConfigsByIdsForUpdate", "内置参数【" + target.getConfigKey() + "】不能删除 ");
        assertEquals("Y", configs.selectConfigById(target.getConfigId()).getConfigType());
    }

    @Test
    void renameWinsAndWaitingWriterCannotRecreateOldReference() throws Exception
    {
        SysDictType target = type("target");
        String oldType = target.getDictType();
        data.insertDictData(datum(oldType));
        target.setDictType(owner + "-renamed");
        withUncommittedWinner(() -> types.updateDictType(target),
                () -> data.insertDictData(datum(oldType)),
                "selectDictTypeForUpdate", "字典类型不存在或已删除");
        assertEquals(0, referenceCount(oldType));
        assertEquals(1, referenceCount(target.getDictType()));
        assertFalse(cache.values.containsKey(CacheConstants.SYS_DICT_KEY + oldType));
    }

    @Test
    void writerWinsAndRenameIncludesNewlyCommittedReference() throws Exception
    {
        SysDictType target = type("target");
        String oldType = target.getDictType();
        target.setDictType(owner + "-renamed");
        withUncommittedWinner(() -> data.insertDictData(datum(oldType)),
                () -> types.updateDictType(target), "selectDictTypesByIdsForUpdate", null);
        assertEquals(0, referenceCount(oldType));
        assertEquals(1, referenceCount(target.getDictType()));
    }

    @Test
    void deletionWinsAndConcurrentRenameRejectsMissingType() throws Exception
    {
        SysDictType target = type("target");
        target.setDictType(owner + "-renamed");
        withUncommittedWinner(() -> types.deleteDictTypeByIds(new Long[] { target.getDictId() }),
                () -> types.updateDictType(target), "selectDictTypesByIdsForUpdate", "字典类型不存在或已删除");
        assertNull(types.selectDictTypeById(target.getDictId()));
    }

    @Test
    void dataMoveCommitsBothCacheInvalidationsTogether()
    {
        SysDictType target = type("target");
        SysDictData reference = referenceFor(target, true);
        cache.deleted.clear();
        transactions.executeWithoutResult(tx -> {
            data.updateDictData(reference);
            assertTrue(cache.deleted.isEmpty());
        });
        assertTrue(cache.deleted.contains(CacheConstants.SYS_DICT_KEY + owner + "-source"));
        assertTrue(cache.deleted.contains(CacheConstants.SYS_DICT_KEY + target.getDictType()));
        assertEquals(1, referenceCount(target.getDictType()));
    }

    private SysConfig config(String suffix, String protection)
    {
        SysConfig result = new SysConfig();
        result.setConfigKey(owner + "-" + suffix);
        jdbc.update("insert into sys_config(config_name,config_key,config_value,config_type,create_by) values(?,?,'value',?,?)",
                suffix, result.getConfigKey(), protection, owner);
        result.setConfigId(jdbc.queryForObject("select config_id from sys_config where config_key=?", Long.class, result.getConfigKey()));
        cache.values.put(CacheConstants.SYS_CONFIG_KEY + result.getConfigKey(), "value");
        return result;
    }

    private SysDictType type(String suffix)
    {
        SysDictType result = new SysDictType();
        result.setDictType(owner + "-" + suffix);
        result.setDictName(suffix);
        jdbc.update("insert into sys_dict_type(dict_name,dict_type,create_by) values(?,?,?)", suffix, result.getDictType(), owner);
        result.setDictId(jdbc.queryForObject("select dict_id from sys_dict_type where dict_type=?", Long.class, result.getDictType()));
        cache.values.put(CacheConstants.SYS_DICT_KEY + result.getDictType(), List.of());
        return result;
    }

    private SysDictData datum(String dictType)
    {
        SysDictData result = new SysDictData();
        result.setDictType(dictType);
        result.setDictSort(1L);
        result.setDictLabel("reference");
        result.setDictValue("value");
        result.setStatus("0");
        result.setCreateBy(owner);
        return result;
    }

    private SysDictData referenceFor(SysDictType target, boolean moving)
    {
        SysDictData reference = datum(target.getDictType());
        if (moving)
        {
            SysDictType source = type("source");
            reference.setDictType(source.getDictType());
            data.insertDictData(reference);
            reference.setDictCode(jdbc.queryForObject("select dict_code from sys_dict_data where create_by=?", Long.class, owner));
            reference.setDictType(target.getDictType());
            cache.values.put(CacheConstants.SYS_DICT_KEY + source.getDictType(), List.of(reference));
        }
        return reference;
    }

    private void write(SysDictData reference, boolean moving)
    {
        assertEquals(1, moving ? data.updateDictData(reference) : data.insertDictData(reference));
    }

    private Long[] targets(boolean dictionary)
    {
        return dictionary ? new Long[] { type("first").getDictId(), type("second").getDictId() }
                : new Long[] { config("first", "N").getConfigId(), config("second", "N").getConfigId() };
    }

    private void delete(boolean dictionary, Long[] ids)
    {
        if (dictionary) types.deleteDictTypeByIds(ids);
        else configs.deleteConfigByIds(ids);
    }

    private String table(boolean dictionary)
    {
        return dictionary ? "sys_dict_type" : "sys_config";
    }

    private int ownedCount(String table)
    {
        return jdbc.queryForObject("select count(*) from " + table + " where create_by=?", Integer.class, owner);
    }

    private int referenceCount(String dictType)
    {
        return jdbc.queryForObject("select count(*) from sys_dict_data where dict_type=?", Integer.class, dictType);
    }

    private void withUncommittedWinner(Runnable winner, Runnable contender, String lockingStatement,
            String expectedRejection) throws Exception
    {
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attemptingLock = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try
        {
            Future<?> first = workers.submit(() -> transactions.executeWithoutResult(tx -> {
                winner.run();
                written.countDown();
                await(release);
            }));
            assertTrue(written.await(10, TimeUnit.SECONDS), "先手没有进入未提交窗口");
            boundary.before = statement -> {
                if (statement.endsWith("." + lockingStatement)) attemptingLock.countDown();
            };
            Future<?> second = workers.submit(contender);
            assertTrue(attemptingLock.await(10, TimeUnit.SECONDS), "后手没有到达正式锁查询");
            assertThrows(TimeoutException.class, () -> second.get(250, TimeUnit.MILLISECONDS), "后手应等待先手持有的数据库锁");
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            if (expectedRejection == null)
            {
                second.get(10, TimeUnit.SECONDS);
            }
            else
            {
                ExecutionException error = assertThrows(ExecutionException.class, () -> second.get(10, TimeUnit.SECONDS));
                assertInstanceOf(ServiceException.class, error.getCause());
                assertEquals(expectedRejection, error.getCause().getMessage());
            }
        }
        finally
        {
            release.countDown();
            shutdown(workers);
        }
    }

    private static void await(CountDownLatch latch)
    {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    private static void awaitResult(Future<?> future)
    {
        try { future.get(10, TimeUnit.SECONDS); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException
    {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }

    @Intercepts({
        @Signature(type = Executor.class, method = "query", args = { MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class }),
        @Signature(type = Executor.class, method = "update", args = { MappedStatement.class, Object.class })
    })
    static class SqlBoundary implements Interceptor
    {
        final List<String> statements = new CopyOnWriteArrayList<>();
        volatile Consumer<String> before = statement -> { };
        volatile Consumer<String> after = statement -> { };

        @Override
        public Object intercept(Invocation invocation) throws Throwable
        {
            String statement = ((MappedStatement) invocation.getArgs()[0]).getId();
            statements.add(statement);
            before.accept(statement);
            Object result = invocation.proceed();
            after.accept(statement);
            return result;
        }

        long count(String method)
        {
            return statements.stream().filter(statement -> statement.endsWith("." + method)).count();
        }
    }

    static class RecordingCache extends RedisCache
    {
        final Map<String, Object> values = new ConcurrentHashMap<>();
        final List<String> deleted = new CopyOnWriteArrayList<>();
        volatile RuntimeException failure;

        @Override
        public <T> void setCacheObject(String key, T value) { values.put(key, value); }

        @Override
        public boolean deleteObject(Collection keys)
        {
            if (failure != null) throw failure;
            for (Object key : keys)
            {
                values.remove(key);
                deleted.add((String) key);
            }
            return !keys.isEmpty();
        }

        @Override
        public Collection<String> keys(String pattern)
        {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return values.keySet().stream().filter(key -> key.startsWith(prefix)).toList();
        }
    }
}

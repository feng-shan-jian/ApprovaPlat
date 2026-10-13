package com.ruoyi.system.service.impl;

import com.ruoyi.common.testsupport.WorkflowMysqlTargetGuard;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.utils.DictUtils;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.system.mapper.SysDictDataMapper;
import com.ruoyi.system.mapper.SysNoticeMapper;
import com.ruoyi.system.mapper.SysNoticeReadMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

/** Real Spring transaction proxies and production MyBatis SQL, including the configured MySQL test database. */
class SystemDeletionTransactionTest
{
    @ParameterizedTest
    @ValueSource(strings = {"h2", "mysql"})
    void dictionaryDeletionInvalidatesEachTypeOnlyAfterCommit(String database) throws Exception
    {
        try (Fixture f = new Fixture(database); var spring = mockStatic(SpringUtils.class))
        {
            RedisCache cache = mock(RedisCache.class);
            spring.when(() -> SpringUtils.getBean(RedisCache.class)).thenReturn(cache);
            f.transaction.executeWithoutResult(status -> {
                f.dictionary.deleteDictDataByIds(f.dictIds());
                assertEquals(0, f.dictCount());
                verifyNoInteractions(cache);
            });
            verify(cache).deleteObject(List.of(DictUtils.getCacheKey(f.typeA), DictUtils.getCacheKey(f.typeB)));
            verifyNoMoreInteractions(cache);
            assertEquals(0, f.dictCount());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "mysql"})
    void dictionaryFailureAfterBatchWriteRollsBackEverySelectedRow(String database) throws Exception
    {
        try (Fixture f = new Fixture(database); var spring = mockStatic(SpringUtils.class))
        {
            RedisCache cache = mock(RedisCache.class);
            spring.when(() -> SpringUtils.getBean(RedisCache.class)).thenReturn(cache);
            doAnswer(invocation -> {
                f.dictMapper.deleteDictDataByIds(invocation.getArgument(0));
                assertEquals(0, f.dictCount());
                throw new IllegalStateException("injected write failure");
            }).when(f.dictDelegate).deleteDictDataByIds(any());
            assertThrows(IllegalStateException.class, () -> f.dictionary.deleteDictDataByIds(f.dictIds()));
            assertEquals(3, f.dictCount());
            verifyNoInteractions(cache);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "mysql"})
    void outerRollbackDoesNotPublishDeletedDictionaryState(String database) throws Exception
    {
        try (Fixture f = new Fixture(database); var spring = mockStatic(SpringUtils.class))
        {
            RedisCache cache = mock(RedisCache.class);
            spring.when(() -> SpringUtils.getBean(RedisCache.class)).thenReturn(cache);
            f.transaction.executeWithoutResult(status -> {
                f.dictionary.deleteDictDataByIds(f.dictIds());
                status.setRollbackOnly();
            });
            assertEquals(3, f.dictCount());
            verifyNoInteractions(cache);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "mysql"})
    void cacheFailureIsVisibleAfterDatabaseCommit(String database) throws Exception
    {
        try (Fixture f = new Fixture(database); var spring = mockStatic(SpringUtils.class))
        {
            RedisCache cache = mock(RedisCache.class);
            spring.when(() -> SpringUtils.getBean(RedisCache.class)).thenReturn(cache);
            when(cache.deleteObject(any(Collection.class))).thenThrow(new IllegalStateException("cache unavailable"));
            assertThrows(IllegalStateException.class, () -> f.dictionary.deleteDictDataByIds(f.dictIds()));
            assertEquals(0, f.dictCount());
            verify(cache).deleteObject(List.of(DictUtils.getCacheKey(f.typeA), DictUtils.getCacheKey(f.typeB)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "mysql"})
    void noticeAndReadsDeleteTogetherAndRepeatedDeleteIsEmpty(String database) throws Exception
    {
        try (Fixture f = new Fixture(database))
        {
            assertTrue(f.readMapper.selectNoticeListWithReadStatus(f.id, Integer.MAX_VALUE).stream()
                    .anyMatch(notice -> notice.getNoticeId().equals(f.id) && notice.getIsRead()));
            assertEquals(1, f.readMapper.selectReadUsersByNoticeId(f.id, f.username).size());
            assertEquals(1, f.notice.deleteNoticeByIds(new Long[] {f.id}));
            assertEquals(0, f.noticeCount());
            assertEquals(0, f.readCount());
            assertEquals(0, f.notice.deleteNoticeByIds(new Long[] {f.id}));
            assertTrue(f.readMapper.selectReadUsersByNoticeId(f.id, null).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "mysql"})
    void secondNoticeWriteFailureRestoresBothNoticeAndReads(String database) throws Exception
    {
        try (Fixture f = new Fixture(database))
        {
            doAnswer(invocation -> {
                assertEquals(0, f.readCount());
                f.noticeMapper.deleteNoticeByIds(invocation.getArgument(0));
                throw new IllegalStateException("injected second write failure");
            }).when(f.noticeDelegate).deleteNoticeByIds(any());
            assertThrows(IllegalStateException.class, () -> f.notice.deleteNoticeByIds(new Long[] {f.id}));
            assertEquals(1, f.noticeCount());
            assertEquals(1, f.readCount());
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        private final long id = ThreadLocalRandom.current().nextLong(1_000_000_000L, 1_900_000_000L);
        private final String tag = UUID.randomUUID().toString().replace("-", "");
        private final String typeA = "cleanup_a_" + tag;
        private final String typeB = "cleanup_b_" + tag;
        private final String username = "cleanup_" + tag.substring(0, 20);
        private final JdbcTemplate jdbc;
        private final boolean mysql;
        private final TransactionTemplate transaction;
        private final SysDictDataMapper dictMapper;
        private final SysDictDataMapper dictDelegate;
        private final SysNoticeMapper noticeMapper;
        private final SysNoticeMapper noticeDelegate;
        private final SysNoticeReadMapper readMapper;
        private final SysDictDataServiceImpl dictionary;
        private final SysNoticeServiceImpl notice;

        Fixture(String database) throws Exception
        {
            mysql = database.equals("mysql");
            String url = System.getenv("WORKFLOW_MYSQL_TEST_URL");
            if (mysql)
            {
                assumeTrue(url != null && !url.isBlank(), "MySQL test environment is not configured");
                WorkflowMysqlTargetGuard.verify(url);
            }
            DriverManagerDataSource source = mysql
                    ? new DriverManagerDataSource(url, System.getenv("WORKFLOW_MYSQL_TEST_USERNAME"),
                            System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD"))
                    : new DriverManagerDataSource("jdbc:h2:mem:" + tag + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
            jdbc = new JdbcTemplate(source);
            DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
            transaction = new TransactionTemplate(manager);
            if (!mysql)
            {
                jdbc.execute("create table sys_dict_data (dict_code bigint primary key, dict_sort int, dict_label varchar(100), dict_value varchar(100), dict_type varchar(100), css_class varchar(100), list_class varchar(100), is_default char(1), status char(1), create_by varchar(100), create_time timestamp, remark varchar(500))");
                jdbc.execute("create table sys_notice (notice_id bigint primary key, notice_title varchar(100), notice_type char(1), notice_content varchar(1000), status char(1), create_by varchar(100), create_time timestamp, update_by varchar(100), update_time timestamp, remark varchar(500))");
                jdbc.execute("create table sys_notice_read (read_id bigint auto_increment primary key, notice_id bigint, user_id bigint, read_time timestamp)");
                jdbc.execute("create table sys_user (user_id bigint primary key, user_name varchar(64), nick_name varchar(64), dept_id bigint, phonenumber varchar(20), del_flag char(1))");
                jdbc.execute("create table sys_dept (dept_id bigint primary key, dept_name varchar(100))");
            }
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setTypeAliasesPackage("com.ruoyi.common.core.domain.entity,com.ruoyi.system.domain");
            factory.setMapperLocations(new ClassPathResource("mapper/system/SysDictDataMapper.xml"),
                    new ClassPathResource("mapper/system/SysNoticeMapper.xml"),
                    new ClassPathResource("mapper/system/SysNoticeReadMapper.xml"));
            SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
            dictMapper = session.getMapper(SysDictDataMapper.class);
            noticeMapper = session.getMapper(SysNoticeMapper.class);
            readMapper = session.getMapper(SysNoticeReadMapper.class);
            dictDelegate = mock(SysDictDataMapper.class, delegatesTo(dictMapper));
            noticeDelegate = mock(SysNoticeMapper.class, delegatesTo(noticeMapper));
            SysDictDataServiceImpl dictTarget = new SysDictDataServiceImpl();
            ReflectionTestUtils.setField(dictTarget, "dictDataMapper", dictDelegate);
            dictionary = proxy(dictTarget, manager);
            SysNoticeServiceImpl noticeTarget = new SysNoticeServiceImpl();
            ReflectionTestUtils.setField(noticeTarget, "noticeMapper", noticeDelegate);
            ReflectionTestUtils.setField(noticeTarget, "noticeReadMapper", readMapper);
            notice = proxy(noticeTarget, manager);
            try
            {
                transaction.executeWithoutResult(status -> {
                    for (int i = 0; i < 3; i++)
                    {
                        jdbc.update("insert into sys_dict_data (dict_code,dict_sort,dict_label,dict_value,dict_type,status) values (?,?,?,?,?,'0')",
                                id + i, i, tag, "value" + i, i < 2 ? typeA : typeB);
                    }
                    jdbc.update("insert into sys_notice (notice_id,notice_title,notice_type,status,create_by) values (?,?,'1','0',?)", id, tag, username);
                    jdbc.update("insert into sys_notice_read (notice_id,user_id,read_time) values (?,?,current_timestamp)", id, id);
                    jdbc.update("insert into sys_user (user_id,user_name,nick_name,del_flag) values (?,?,?,'0')", id, username, username);
                });
            }
            catch (RuntimeException exception)
            {
                if (!mysql) jdbc.execute("shutdown");
                throw exception;
            }
        }

        Long[] dictIds() { return new Long[] {id, id + 1, id + 2}; }
        int dictCount() { return jdbc.queryForObject("select count(*) from sys_dict_data where dict_code in (?,?,?)", Integer.class, (Object[]) dictIds()); }
        int noticeCount() { return jdbc.queryForObject("select count(*) from sys_notice where notice_id=?", Integer.class, id); }
        int readCount() { return jdbc.queryForObject("select count(*) from sys_notice_read where notice_id=?", Integer.class, id); }

        @Override
        public void close()
        {
            try
            {
                transaction.executeWithoutResult(status -> {
                    jdbc.update("delete from sys_notice_read where notice_id=? and user_id=?", id, id);
                    jdbc.update("delete from sys_notice where notice_id=? and notice_title=?", id, tag);
                    jdbc.update("delete from sys_user where user_id=? and user_name=?", id, username);
                    jdbc.update("delete from sys_dict_data where dict_code in (?,?,?) and dict_type in (?,?)", id, id + 1, id + 2, typeA, typeB);
                });
            }
            finally
            {
                if (!mysql) jdbc.execute("shutdown");
            }
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(T target, DataSourceTransactionManager manager)
        {
            ProxyFactory factory = new ProxyFactory(target);
            factory.setProxyTargetClass(true);
            factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
            return (T) factory.getProxy();
        }
    }
}

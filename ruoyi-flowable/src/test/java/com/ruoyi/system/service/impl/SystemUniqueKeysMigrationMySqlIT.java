package com.ruoyi.system.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;
import com.ruoyi.flowable.testsupport.WorkflowMySqlITSupport;
import com.ruoyi.system.exception.ConfigKeyConflictException;
import com.ruoyi.system.exception.UserNameConflictException;

/** DDL 只在显式授权的专用实例内创建随机 schema；每例结束删除自己创建的 schema。 */
class SystemUniqueKeysMigrationMySqlIT
{
    private static MysqlDataSource source;
    private Connection connection;
    private String schema;

    @BeforeAll
    static void verifyIsolation() throws Exception
    {
        assertEquals("true", System.getenv("WORKFLOW_MYSQL_TEST_ALLOW_SCHEMA_DDL"),
                "DDL 演练必须显式设置 WORKFLOW_MYSQL_TEST_ALLOW_SCHEMA_DDL=true 并指向专用实例");
        source = WorkflowMySqlITSupport.createDataSource();
        WorkflowMySqlITSupport.verifyIsolatedBaseline(source, "系统唯一键迁移 IT", "approvaplat_it",
                List.of("sys_user", "sys_config"));
    }

    @BeforeEach
    void createOwnedSchema() throws Exception
    {
        connection = source.getConnection();
        schema = "c12_migration_" + UUID.randomUUID().toString().replace("-", "");
        sql("create database " + schema + " character set utf8mb4 collate utf8mb4_unicode_ci");
        connection.setCatalog(schema);
    }

    @AfterEach
    void cleanOwnedSchema() throws Exception
    {
        if (connection != null)
        {
            try { sql("drop database " + schema); }
            finally { connection.close(); }
        }
    }

    @Test
    void fullEmptyInstallSeedsAndRepeatedUpgradeRollback() throws Exception
    {
        script("ry_20260417.sql");
        assertEquals(1, count("select count(*) from sys_user"));
        assertEquals(9, count("select count(*) from sys_config"));
        assertEquals(2, indexes());
        assertTrue(script("system/20260916__unique_keys_upgrade.sql").contains("C12 ALREADY_INSTALLED_VERIFIED"));
        assertTrue(script("system/20260916__unique_keys_rollback.sql").contains("C12 ROLLED_BACK"));
        assertEquals(0, indexes());
        assertTrue(script("system/20260916__unique_keys_rollback.sql").contains("C12 ALREADY_ROLLED_BACK_VERIFIED"));
        assertTrue(script("system/20260916__unique_keys_upgrade.sql").contains("C12 INSTALLED"));
        assertTrue(script("system/20260916__unique_keys_upgrade.sql").contains("C12 ALREADY_INSTALLED_VERIFIED"));
        assertEquals(1, count("select count(*) from sys_user where user_id=1 and user_name='admin'"));
        assertEquals(9, count("select count(*) from sys_config"));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void historicalConflictsAbortBeforeEitherTableIsChanged(boolean config) throws Exception
    {
        legacy();
        if (config)
            sql("insert into sys_config(config_key) values('SYS.INDEX.SKINNAME')");
        else
            sql("insert into sys_user(user_name,nick_name,status) values('ADMIN','disabled','1')");
        int users = count("select count(*) from sys_user");
        int configs = count("select count(*) from sys_config");
        SQLException error = assertThrows(SQLException.class,
                () -> script("system/20260916__unique_keys_upgrade.sql"));
        assertEquals("45000", error.getSQLState());
        assertTrue(error.getMessage().contains("duplicate rows"));
        assertEquals(0, indexes());
        assertEquals(0, count("select count(*) from information_schema.columns where table_schema=database() "
                + "and table_name='sys_user' and column_name='active_user_name'"));
        assertEquals(users, count("select count(*) from sys_user"));
        assertEquals(configs, count("select count(*) from sys_config"));
        assertFalse(script("system/20260916__unique_keys_audit.sql").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = { "utf8mb4", "latin1" })
    void migrationPreservesEachColumnsCollationInsteadOfTableDefault(String charset) throws Exception
    {
        legacy();
        sql("alter table sys_user modify user_name varchar(30) character set " + charset + " collate " + charset + "_bin not null");
        sql("alter table sys_config modify config_key varchar(100) character set " + charset + " collate " + charset + "_bin default ''");
        script("system/20260916__unique_keys_upgrade.sql");
        assertEquals(3, count("select count(*) from information_schema.columns where table_schema=database() "
                + "and ((table_name='sys_user' and column_name in ('user_name','active_user_name')) "
                + "or (table_name='sys_config' and column_name='config_key')) and collation_name='" + charset + "_bin'"));
        sql("insert into sys_user(user_name,nick_name) values('ADMIN','different case')");
        sql("insert into sys_config(config_key) values('SYS.INDEX.SKINNAME')");
        assertThrows(SQLException.class, () -> sql("insert into sys_user(user_name,nick_name) values('ADMIN','same case')"));
        assertThrows(SQLException.class, () -> sql("insert into sys_config(config_key) values('SYS.INDEX.SKINNAME')"));
        assertTrue(script("system/20260916__unique_keys_upgrade.sql").contains("C12 ALREADY_INSTALLED_VERIFIED"));
    }

    @Test
    void existingWrongIndexOrProjectionIsNeverAcceptedOrRemoved() throws Exception
    {
        legacy();
        sql("alter table sys_config add index uk_sys_config_key(config_key)");
        assertThrows(SQLException.class, () -> script("system/20260916__unique_keys_upgrade.sql"));
        assertThrows(SQLException.class, () -> script("system/20260916__unique_keys_rollback.sql"));
        assertEquals(1, indexes());
        sql("alter table sys_config drop index uk_sys_config_key");
        sql("alter table sys_user add active_user_name varchar(30)");
        assertThrows(SQLException.class, () -> script("system/20260916__unique_keys_upgrade.sql"));
        assertThrows(SQLException.class, () -> script("system/20260916__unique_keys_rollback.sql"));
        assertEquals(0, indexes());
    }

    @Test
    void conflictingLegacyIndexAndWrongGenerationAreBlocked() throws Exception
    {
        legacy();
        sql("alter table sys_user add unique key old_name_del(user_name,del_flag)");
        assertThrows(SQLException.class, () -> script("system/20260916__unique_keys_upgrade.sql"));
        sql("alter table sys_user drop index old_name_del");
        sql("alter table sys_user add active_user_name varchar(30) as (case when del_flag='2' then user_name else null end) virtual");
        assertThrows(SQLException.class, () -> script("system/20260916__unique_keys_upgrade.sql"));
        assertEquals(0, indexes());
    }

    @Test
    void resumesPartialDdlAndRollsBackWithoutLosingDeletionHistory() throws Exception
    {
        legacy();
        sql("alter table sys_user add active_user_name varchar(30) as (case when del_flag='0' then user_name else null end) virtual");
        script("system/20260916__unique_keys_upgrade.sql");
        sql("insert into sys_user(user_name,nick_name,del_flag) values('admin','history 1','2'),('admin','history 2','2')");
        sql("alter table sys_config drop index uk_sys_config_key");
        assertTrue(script("system/20260916__unique_keys_upgrade.sql").contains("C12 INSTALLED"));
        script("system/20260916__unique_keys_rollback.sql");
        assertEquals(3, count("select count(*) from sys_user where user_name='admin'"));
        assertEquals(9, count("select count(*) from sys_config"));
        script("system/20260916__unique_keys_upgrade.sql");
        assertEquals(2, indexes());
    }

    @Test
    void realUnknownUniqueKeysAndPrimaryKeysAreNeverMisclassified() throws Exception
    {
        script("ry_20260417.sql");
        sql("alter table sys_user add unique key unrelated_nickname(nick_name)");
        sql("alter table sys_config add unique key unrelated_name(config_name)");
        for (String statement : List.of(
                "insert into sys_user(user_id,user_name,nick_name) values(1,'different','different')",
                "insert into sys_user(user_name,nick_name) select 'different',nick_name from sys_user where user_id=1",
                "insert into sys_config(config_id,config_key) values(1,'different')",
                "insert into sys_config(config_key,config_name) select 'different',config_name from sys_config where config_id=1"))
        {
            SQLException cause = assertThrows(SQLException.class, () -> sql(statement));
            assertEquals(1062, cause.getErrorCode());
            var error = new DuplicateKeyException("real MySQL write failure", cause);
            assertSame(error, UserNameConflictException.translate(error));
            assertSame(error, ConfigKeyConflictException.translate(error));
        }
    }

    @Test
    void failedSecondDdlNeverReportsCompleteAndCanBeRolledBackThenReapplied() throws Exception
    {
        legacy();
        sql("alter table sys_config modify config_key varchar(1000) default ''");
        SQLException error = assertThrows(SQLException.class, () -> script("system/20260916__unique_keys_upgrade.sql"));
        assertEquals(1071, error.getErrorCode(), "真实 MySQL 索引长度错误发生在第二张表的 DDL");
        assertEquals(1, indexes(), "第一张表 DDL 已提交，不能把失败解释成整批回滚");
        assertThrows(SQLException.class, () -> script("system/20260916__unique_keys_upgrade.sql"));
        assertTrue(script("system/20260916__unique_keys_rollback.sql").contains("C12 ROLLED_BACK"));
        assertEquals(0, indexes());
        sql("alter table sys_config modify config_key varchar(100) default ''");
        assertTrue(script("system/20260916__unique_keys_upgrade.sql").contains("C12 INSTALLED"));
        assertEquals(1, count("select count(*) from sys_user"));
        assertEquals(9, count("select count(*) from sys_config"));
    }

    @Test
    void rollbackRefusesToRemoveAnotherIndexDependingOnProjection() throws Exception
    {
        script("ry_20260417.sql");
        sql("alter table sys_user add index later_feature(active_user_name,status)");
        SQLException error = assertThrows(SQLException.class, () -> script("system/20260916__unique_keys_rollback.sql"));
        assertTrue(error.getMessage().contains("another index depends"));
        assertEquals(2, indexes(), "阻断必须发生在删除配置索引之前");
    }

    private void legacy() throws Exception
    {
        script("ry_20260417.sql");
        script("system/20260916__unique_keys_rollback.sql");
    }

    private int indexes() throws SQLException
    {
        return count("select count(*) from information_schema.statistics where table_schema=database() "
                + "and index_name in ('uk_sys_user_active_name','uk_sys_config_key')");
    }

    private int count(String query) throws SQLException
    {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(query))
        {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private List<String> sql(String query) throws SQLException
    {
        List<String> values = new ArrayList<>();
        try (var statement = connection.createStatement())
        {
            boolean rows = statement.execute(query);
            while (rows || statement.getUpdateCount() != -1)
            {
                if (rows)
                {
                    try (var result = statement.getResultSet())
                    {
                        while (result.next()) values.add(result.getString(1));
                    }
                }
                rows = statement.getMoreResults();
            }
        }
        return values;
    }

    /** 正式 SQL 按行结束符执行，支持迁移脚本 DELIMITER；任何 SQL 错误立即停止。 */
    private List<String> script(String relativePath) throws Exception
    {
        List<String> values = new ArrayList<>();
        String delimiter = ";";
        StringBuilder query = new StringBuilder();
        for (String line : Files.readAllLines(Path.of("../sql", relativePath), StandardCharsets.UTF_8))
        {
            String trimmed = line.strip();
            if (trimmed.startsWith("--") || trimmed.isEmpty()) continue;
            if (trimmed.toLowerCase().startsWith("delimiter "))
            {
                assertTrue(query.isEmpty());
                delimiter = trimmed.substring(10);
                continue;
            }
            query.append(line).append('\n');
            if (trimmed.endsWith(delimiter))
            {
                String complete = query.toString().strip();
                values.addAll(sql(complete.substring(0, complete.length() - delimiter.length())));
                query.setLength(0);
            }
        }
        assertTrue(query.isEmpty(), "SQL 脚本最后一条语句缺少结束符");
        return values;
    }
}

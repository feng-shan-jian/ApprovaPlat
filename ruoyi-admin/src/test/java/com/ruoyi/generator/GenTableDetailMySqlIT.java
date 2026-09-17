package com.ruoyi.generator;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.ruoyi.generator.domain.GenTable;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 显式 MySQL profile：复用全部详情/编辑/模板断言，并验证 information_schema 目录消费者。 */
class GenTableDetailMySqlIT extends GenTableDetailTest
{
    @Override
    protected DataSource dataSource() throws Exception
    {
        var source = new MysqlDataSource();
        source.setURL(required("WORKFLOW_MYSQL_TEST_URL"));
        source.setUser(required("WORKFLOW_MYSQL_TEST_USERNAME"));
        source.setPassword(required("WORKFLOW_MYSQL_TEST_PASSWORD"));
        var check = new JdbcTemplate(source);
        assertThat(check.queryForObject("select database()", String.class)).isEqualTo("approvaplat_it");
        assertThat(check.queryForObject("select version()", String.class)).startsWith("8.");
        assertThat(check.queryForObject("select count(*) from information_schema.tables where table_schema=database() "
                + "and table_name in ('gen_table','gen_table_column') and engine='InnoDB'", Integer.class)).isEqualTo(2);
        return source;
    }

    @Override
    protected void closeDatabase()
    {
        // 父类逐 ID 删除并核实本用例拥有的夹具；不关闭显式提供的服务器。
    }

    @Test
    void databaseDirectoryAndImportLookupKeepTheirMetadataWithoutAJoin() throws Exception
    {
        String name = owner + "_unimported";
        jdbc.execute("create table " + name + " (record_id bigint primary key) engine=InnoDB comment='Import candidate'");
        try
        {
            var filter = new GenTable();
            filter.setTableName(name);
            var directory = service.selectDbTableList(filter);
            assertThat(directory).hasSize(1);
            assertThat(directory.get(0).getTableName()).isEqualTo(name);
            assertThat(directory.get(0).getTableComment()).isEqualTo("Import candidate");
            assertThat(directory.get(0).getTableId()).isNull();
            assertThat(directory.get(0).getCreateTime()).isNotNull();
            var imported = service.selectDbTableListByNames(new String[] {name});
            assertThat(imported).hasSize(1);
            assertThat(imported.get(0).getTableName()).isEqualTo(name);
            assertThat(imported.get(0).getTableComment()).isEqualTo("Import candidate");
            assertThat(imported.get(0).getCreateTime()).isEqualTo(directory.get(0).getCreateTime());
            assertThat(imported.get(0).getUpdateTime()).isEqualTo(directory.get(0).getUpdateTime());
        }
        finally
        {
            jdbc.execute("drop table " + name);
            assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() "
                    + "and table_name=?", Integer.class, name)).isZero();
        }
    }

    private static String required(String name)
    {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }
}

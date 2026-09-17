package com.ruoyi.flowable.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** 独占随机 schema：按 docker/mysql/init.sh 的正式顺序初始化，关闭时仅删除本对象创建的库。 */
public final class WorkflowMySqlTestDatabase implements AutoCloseable
{
    private final MysqlDataSource admin;
    private final String name;
    private final MysqlDataSource dataSource;

    public WorkflowMySqlTestDatabase(String purpose) throws Exception
    {
        if (!"true".equals(System.getenv("WORKFLOW_MYSQL_TEST_ALLOW_SCHEMA_DDL")))
            throw new IllegalStateException("C05 requires WORKFLOW_MYSQL_TEST_ALLOW_SCHEMA_DDL=true and a dedicated MySQL instance");
        admin = WorkflowMySqlITSupport.createDataSource();
        WorkflowMySqlITSupport.verifyIsolatedBaseline(admin, purpose, null, List.of("act_ge_property"));
        name = "c05_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(admin).execute("CREATE DATABASE " + name + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        dataSource = WorkflowMySqlITSupport.createDataSource();
        dataSource.setURL(dataSource.getURL().replaceFirst("(jdbc:mysql://[^/]+/)[^?]+", "$1" + name));
        try
        {
            // URL may omit the explicit port. JDBC catalog selection is checked before any DDL.
            try (Connection connection = dataSource.getConnection())
            {
                if (!name.equals(connection.getCatalog())) throw new IllegalStateException("Private MySQL URL did not select owned schema");
            }
            var files = Files.readAllLines(Path.of("../docker/mysql/init.sh")).stream()
                    .map(String::strip).filter(line -> line.matches("\".+\\.sql\""))
                    .map(line -> line.substring(1, line.length() - 1)).toList();
            if (files.size() != 14) throw new IllegalStateException("Expected 14 formal baseline SQL files");
            for (String file : files) script(Path.of("../sql", file));
        }
        catch (Exception failure)
        {
            close();
            throw failure;
        }
    }

    public MysqlDataSource dataSource() { return dataSource; }

    /** 在单个连接执行正式 SQL，包括 DELIMITER 过程；错误直接抛出，不跳过后续门禁。 */
    public void script(Path file) throws Exception
    {
        try (Connection connection = dataSource.getConnection())
        {
            String delimiter = ";";
            StringBuilder query = new StringBuilder();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
            {
                String trimmed = line.strip();
                if (trimmed.startsWith("--") || trimmed.isEmpty()) continue;
                if (trimmed.toLowerCase().startsWith("delimiter "))
                {
                    if (!query.isEmpty()) throw new IllegalArgumentException("Unterminated SQL before DELIMITER");
                    delimiter = trimmed.substring(10);
                    continue;
                }
                query.append(line).append('\n');
                if (trimmed.endsWith(delimiter))
                {
                    String sql = query.toString().strip();
                    try (var statement = connection.createStatement())
                    {
                        statement.execute(sql.substring(0, sql.length() - delimiter.length()));
                        while (statement.getMoreResults() || statement.getUpdateCount() != -1) { }
                    }
                    query.setLength(0);
                }
            }
            if (!query.isEmpty()) throw new IllegalArgumentException("Unterminated SQL");
        }
    }

    @Override public void close()
    {
        new JdbcTemplate(admin).execute("DROP DATABASE IF EXISTS " + name);
    }
}

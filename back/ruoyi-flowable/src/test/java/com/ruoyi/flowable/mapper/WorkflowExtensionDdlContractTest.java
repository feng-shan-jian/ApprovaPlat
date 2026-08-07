package com.ruoyi.flowable.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.extension.WorkflowCelSandbox;
import com.ruoyi.flowable.extension.WorkflowExtensionChecksum;
import com.ruoyi.flowable.extension.WorkflowHttpConnector;
import com.ruoyi.flowable.extension.WorkflowSqlConnector;

/**
 * BPMN 扩展目录、不可变版本和部署快照的 MySQL DDL 契约测试。
 */
class WorkflowExtensionDdlContractTest
{
    /**
     * 验证首个正式业务基线幂等、非破坏且具备 JSON、摘要、外键和唯一性约束。
     * @return 无返回值；基线可能破坏数据或允许非法快照时测试失败
     * @throws Exception 正式 SQL 无法读取时测试失败
     */
    @Test
    void definesConstrainedNonDestructiveExtensionRegistry() throws Exception
    {
        String ddl = Files.readString(findProjectSql(
                "sql/flowable/business/8.0.0__workflow_business.sql"),
                StandardCharsets.UTF_8).toLowerCase();
        Pattern destructive = Pattern.compile(
                "(?im)^\\s*(drop|delete|update|alter|truncate|replace|call|set)\\b");

        assertThat(ddl).contains(
                "create table if not exists `wf_bpmn_extension`",
                "create table if not exists `wf_bpmn_extension_version`",
                "create table if not exists `wf_deploy_extension_snapshot`",
                "unique key `uk_wf_bpmn_extension_key` (`extension_key`)",
                "unique key `uk_wf_bpmn_extension_version` (`extension_id`, `version_no`)",
                "unique key `uk_wf_deploy_extension_element` (`deploy_id`, `process_key`, `element_id`)",
                "`config_schema`      json",
                "`config_json`         json",
                "references `wf_bpmn_extension` (`extension_id`)",
                "references `wf_bpmn_extension_version` (`version_id`)",
                "on update restrict on delete restrict",
                "`checksum` regexp '^[0-9a-f]{64}$'",
                "`snapshot_checksum` regexp '^[0-9a-f]{64}$'",
                "42bca2710135b3faac369facee8c103683edf52b63f95c2ec2fb18f14fd3b3f0",
                "where not exists");
        assertThat(ddl).doesNotContain("+create table");
        assertThat(destructive.matcher(ddl).find()).isFalse();
    }

    /**
     * 验证首个正式业务基线包含三元快照唯一键和版本外键。
     * @return 无返回值；基线结构漂移时测试失败
     * @throws Exception 正式 SQL 无法读取时测试失败
     */
    @Test
    void definesExtensionSnapshotKeysInFormalBaseline() throws Exception
    {
        String ddl = Files.readString(findProjectSql(
                "sql/flowable/business/8.0.0__workflow_business.sql"),
                StandardCharsets.UTF_8).toLowerCase();

        assertThat(ddl).contains(
                "unique key `uk_wf_deploy_extension_element` (`deploy_id`, `process_key`, `element_id`)",
                "constraint `fk_wf_deploy_extension_version` foreign key (`extension_version_id`)",
                "references `wf_bpmn_extension_version` (`version_id`)");
    }

    /**
     * 验证 CEL 内置目录基线与当前沙箱 Schema 和版本摘要完全一致。
     * @return 无返回值；基线允许实现漂移或摘要不匹配时测试失败
     * @throws Exception 正式 SQL 无法读取时测试失败
     */
    @Test
    void seedsCelRegistryWithCurrentSandboxChecksum() throws Exception
    {
        String baseline = Files.readString(findProjectSql(
                "sql/flowable/business/8.0.0__workflow_business.sql"),
                StandardCharsets.UTF_8);
        String schema = new WorkflowCelSandbox().configSchema();
        String checksum = WorkflowExtensionChecksum.sha256(
                "approva.cel-expression", "CEL", "1", "CEL_EXPRESSION_V1", schema);
        Pattern destructive = Pattern.compile(
                "(?im)^\\s*(drop|delete|update|alter|truncate|replace|call|set)\\b");

        assertThat(baseline).contains(
                "'approva.cel-expression'",
                "'CEL_EXPRESSION_V1'",
                schema.replace("'", "''"),
                checksum,
                "WHERE NOT EXISTS");
        assertThat(destructive.matcher(baseline).find()).isFalse();
    }

    /**
     * 验证 HTTP 连接器基线包含受约束端点、幂等调用台账和当前固定实现版本。
     * @return void，结构、Schema 或摘要与运行时代码漂移时测试失败
     * @throws Exception 正式 SQL 无法读取时测试失败
     */
    @Test
    void definesHttpConnectorTablesAndCurrentFixedVersion() throws Exception
    {
        String baseline = Files.readString(findProjectSql(
                "sql/flowable/business/8.0.0__workflow_business.sql"),
                StandardCharsets.UTF_8);
        String normalized = baseline.toLowerCase();
        String schema = new WorkflowHttpConnector(null, null, null).configSchema();
        String checksum = WorkflowExtensionChecksum.sha256(
                "approva.http-connector", "HTTP", "1", "HTTP_CONNECTOR_V1", schema);
        Pattern destructive = Pattern.compile(
                "(?im)^\\s*(drop|delete|update|alter|truncate|replace|call|set)\\b");

        assertThat(normalized).contains(
                "create table if not exists `wf_connector_endpoint`",
                "create table if not exists `wf_connector_invocation`",
                "unique key `uk_wf_connector_endpoint_key` (`endpoint_key`)",
                "unique key `uk_wf_connector_invocation_idempotency` (`idempotency_key`)",
                "`secret_ref`",
                "`claim_token`",
                "`lease_expires_at`",
                "'approva.http-connector'",
                "'http_connector_v1'");
        assertThat(baseline).contains(schema.replace("'", "''"), checksum, "WHERE NOT EXISTS");
        assertThat(destructive.matcher(baseline).find()).isFalse();
    }

    /**
     * 验证首个正式业务基线包含 HTTP 连接器表和固定版本。
     * @return void，清洁建库脚本缺少 HTTP 正式结构时测试失败
     * @throws Exception 正式 SQL 无法读取时测试失败
     */
    @Test
    void definesHttpConnectorSeedInFormalBaseline() throws Exception
    {
        String ddl = Files.readString(findProjectSql(
                "sql/flowable/business/8.0.0__workflow_business.sql"),
                StandardCharsets.UTF_8).toLowerCase();

        assertThat(ddl).contains(
                "create table if not exists `wf_connector_endpoint`",
                "create table if not exists `wf_connector_invocation`",
                "unique key `uk_wf_connector_endpoint_key` (`endpoint_key`)",
                "unique key `uk_wf_connector_invocation_idempotency` (`idempotency_key`)",
                "'approva.http-connector'",
                "'http_connector_v1'",
                "1e01f5bb398c3ef1755cfc53d0dffb8899464969289b7ecf10b5e6e5a9fdc2a9");
    }

    /**
     * 验证 SQL 数据源目录、固定实现版本和通用调用台账基线与运行时代码保持一致。
     * @return void，SQL Schema、摘要或通用台账字段漂移时测试失败
     * @throws Exception 正式 SQL 无法读取时测试失败
     */
    @Test
    void definesSqlConnectorAndGenericInvocationLedger() throws Exception
    {
        String baseline = Files.readString(findProjectSql(
                "sql/flowable/business/8.0.0__workflow_business.sql"),
                StandardCharsets.UTF_8);
        String base = baseline.toLowerCase();
        String schema = new WorkflowSqlConnector(null, null, null, null, null).configSchema();
        String checksum = WorkflowExtensionChecksum.sha256(
                "approva.sql-connector", "SQL", "1", "SQL_CONNECTOR_V1", schema);

        assertThat(baseline).contains(schema.replace("'", "''"), checksum,
                "'approva.sql-connector'", "'SQL_CONNECTOR_V1'", "WHERE NOT EXISTS");
        assertThat(base).contains(
                "create table if not exists `wf_sql_datasource`",
                "unique key `uk_wf_sql_datasource_key` (`datasource_key`)",
                "`connector_type`", "`target_key`", "`target_revision`",
                "`operation`", "`target_summary`", "`result_code`",
                "'approva.sql-connector'", "'sql_connector_v1'", checksum);
    }

    /**
     * 从模块或后端聚合目录向上定位正式 SQL。
     * @param relativePath String，以 back 为基准的 SQL 相对路径
     * @return Path，存在的正式 SQL 绝对路径
     */
    private Path findProjectSql(String relativePath)
    {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null)
        {
            Path direct = current.resolve(relativePath);
            if (Files.isRegularFile(direct))
            {
                return direct;
            }
            Path nested = current.resolve("back").resolve(relativePath);
            if (Files.isRegularFile(nested))
            {
                return nested;
            }
            current = current.getParent();
        }
        throw new AssertionError("未找到 BPMN 扩展 SQL");
    }
}

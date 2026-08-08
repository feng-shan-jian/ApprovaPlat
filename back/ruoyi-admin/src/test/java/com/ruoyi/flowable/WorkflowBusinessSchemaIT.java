package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * 工作流十七张正式业务表的真实 MySQL 结构与约束集成测试。
 */
@Execution(ExecutionMode.SAME_THREAD)
class WorkflowBusinessSchemaIT
{
    /** 集成测试只允许操作的 schema 名称后缀。 */
    private static final String SAFE_SCHEMA_SUFFIX = "_flowable_it";
    /** MySQL 唯一键冲突错误码。 */
    private static final int MYSQL_DUPLICATE_KEY_ERROR = 1062;
    /** MySQL CHECK 约束冲突错误码。 */
    private static final int MYSQL_CHECK_CONSTRAINT_ERROR = 3819;

    /** 当前测试方法独占的真实 MySQL 连接，所有业务写入均在该事务内回滚。 */
    private Connection connection;
    /** 经环境变量和当前连接双重核对后的隔离 schema 名称。 */
    private String expectedSchema;
    /** 并发连接复用的隔离库 JDBC 地址，不写入任何测试输出。 */
    private String jdbcUrl;
    /** 并发连接复用的隔离库账号，不写入任何测试输出。 */
    private String databaseUsername;
    /** 并发连接复用的隔离库口令，不写入任何测试输出。 */
    private String databasePassword;

    /**
     * 连接专用 MySQL schema，并在任何业务写入前完成目标库安全门禁。
     * @return void，连接或安全门禁失败时测试失败
     * @throws SQLException JDBC 连接或只读元数据查询失败
     */
    @BeforeEach
    void openIsolatedDatabase() throws SQLException
    {
        expectedSchema = requiredEnvironment("FLOWABLE_IT_EXPECTED_SCHEMA");
        if (!expectedSchema.toLowerCase(Locale.ROOT).endsWith(SAFE_SCHEMA_SUFFIX))
        {
            throw new IllegalStateException("业务表集成测试只允许使用 *_flowable_it 隔离 schema");
        }

        jdbcUrl = requiredEnvironment("FLOWABLE_IT_JDBC_URL");
        databaseUsername = requiredEnvironment("FLOWABLE_IT_USERNAME");
        databasePassword = requiredSecretEnvironment("FLOWABLE_IT_PASSWORD");
        connection = DriverManager.getConnection(jdbcUrl, databaseUsername, databasePassword);

        String actualSchema = queryString("select database()");
        assertThat(actualSchema).as("当前 JDBC schema 必须与显式安全门禁一致")
                .isEqualTo(expectedSchema);
        assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        assertThat(connection.getMetaData().getDatabaseMajorVersion()).isGreaterThanOrEqualTo(8);
        connection.setAutoCommit(false);
    }

    /**
     * 回滚当前测试事务并关闭连接，保证集成测试不保留业务记录。
     * @return void，无返回值
     * @throws SQLException 回滚或关闭连接失败
     */
    @AfterEach
    void rollbackAndClose() throws SQLException
    {
        if (connection == null)
        {
            return;
        }
        try
        {
            connection.rollback();
        }
        finally
        {
            connection.close();
        }
    }

    /**
     * 验证分类有效编码唯一，逻辑删除后同一业务编码可以重新创建。
     * @return void，约束行为与逻辑删除契约不一致时测试失败
     * @throws SQLException 正常 JDBC 操作失败
     */
    @Test
    void enforcesActiveCategoryCodeAndAllowsReuseAfterLogicalDelete() throws SQLException
    {
        long firstCategoryId = negativeId();
        long replacementCategoryId = firstCategoryId - 1L;
        String categoryCode = "it-category-" + token();

        assertThat(executeUpdate(
                "insert into wf_category (category_id, category_name, code, del_flag) values (?, ?, ?, '0')",
                firstCategoryId, "集成测试分类", categoryCode)).isEqualTo(1);
        assertMysqlError(MYSQL_DUPLICATE_KEY_ERROR, () -> executeUpdate(
                "insert into wf_category (category_id, category_name, code, del_flag) values (?, ?, ?, '0')",
                replacementCategoryId, "重复编码分类", categoryCode));

        assertThat(executeUpdate(
                "update wf_category set del_flag = '2' where category_id = ? and del_flag = '0'",
                firstCategoryId)).isEqualTo(1);
        assertThat(executeUpdate(
                "insert into wf_category (category_id, category_name, code, del_flag) values (?, ?, ?, '0')",
                replacementCategoryId, "复用编码分类", categoryCode)).isEqualTo(1);
        assertThat(queryLong(
                "select count(*) from wf_category where code = ? and del_flag = '0'", categoryCode))
                .isEqualTo(1L);
    }

    /**
     * 验证可编辑表单和部署快照均由 MySQL JSON CHECK 约束兜底。
     * @return void，任一非法 JSON 能写入时测试失败
     */
    @Test
    void rejectsInvalidJsonForEditableFormAndDeploymentSnapshot()
    {
        long formId = negativeId();
        String deploymentId = "it-deploy-" + token();

        assertMysqlError(MYSQL_CHECK_CONSTRAINT_ERROR, () -> executeUpdate(
                "insert into wf_form (form_id, form_name, content, del_flag) values (?, ?, ?, '0')",
                formId, "非法 JSON 表单", "{broken"));
        assertMysqlError(MYSQL_CHECK_CONSTRAINT_ERROR, () -> executeUpdate(
                "insert into wf_deploy_form "
                        + "(deploy_id, form_id, form_key, node_key, form_name, node_name, content, del_flag) "
                        + "values (?, ?, ?, ?, ?, ?, ?, '0')",
                deploymentId, formId, "key_" + Math.abs(formId), "start", "非法快照",
                "开始节点", "{broken"));
    }

    /**
     * 验证同一抄送业务事件不会为同一用户重复生成记录。
     * @return void，幂等唯一键未生效时测试失败
     * @throws SQLException 首次合法写入或计数查询失败
     */
    @Test
    void rejectsDuplicateCopyRecipientForSameBusinessEvent() throws SQLException
    {
        long firstCopyId = negativeId();
        long duplicateCopyId = firstCopyId - 1L;
        long userId = Math.abs(firstCopyId);
        String copyEventId = "it-copy-" + token();
        String insertSql = "insert into wf_copy "
                + "(copy_id, copy_event_id, title, process_id, process_name, category_id, "
                + "deployment_id, instance_id, task_id, user_id, originator_id, del_flag) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '0')";

        assertThat(executeUpdate(insertSql, firstCopyId, copyEventId, "集成测试抄送",
                "process-definition", "测试流程", "test", "deployment", "instance",
                "task", userId, userId + 1L)).isEqualTo(1);
        assertMysqlError(MYSQL_DUPLICATE_KEY_ERROR, () -> executeUpdate(insertSql,
                duplicateCopyId, copyEventId, "重复抄送", "process-definition", "测试流程",
                "test", "deployment", "instance", "task-2", userId, userId + 1L));
        assertThat(queryLong(
                "select count(*) from wf_copy where copy_event_id = ? and user_id = ?",
                copyEventId, userId)).isEqualTo(1L);
    }

    /**
     * 验证 MySQL 8.4 接受生产 Mapper 使用的行别名 upsert，并合并手工与自动来源。
     * @return void，幂等 SQL 不兼容或来源合并错误时测试失败
     * @throws SQLException 正常 JDBC 写入或查询失败
     */
    @Test
    void mergesManualAndAutomaticCopyWithProductionUpsertSyntax() throws SQLException
    {
        long copyId = negativeId();
        long userId = Math.abs(copyId);
        String copyEventId = "TASK_COMPLETED:it-" + token();
        String insertSql = "insert into wf_copy "
                + "(copy_id, copy_event_id, title, process_id, process_name, category_id, "
                + "deployment_id, instance_id, task_id, user_id, originator_id, source_type, "
                + "trigger_type, create_by, del_flag) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '0') as incoming "
                + "on duplicate key update source_type = case "
                + "when wf_copy.source_type = incoming.source_type then wf_copy.source_type "
                + "else 'MANUAL_AUTO' end, update_time = current_timestamp(3)";

        assertThat(executeUpdate(insertSql, copyId, copyEventId, "手工抄送", "definition",
                "测试流程", "test", "deployment", "instance", "task", userId,
                userId + 1L, "MANUAL", "MANUAL_COMPLETE", "91")).isEqualTo(1);
        // 同一完成事件与接收人再次由自动规则写入时只能合并来源，不能新增第二条事实记录。
        assertThat(executeUpdate(insertSql, copyId - 1L, copyEventId, "自动抄送",
                "definition", "测试流程", "test", "deployment", "instance", "task",
                userId, userId + 1L, "AUTO", "NODE_COMPLETED", "SYSTEM"))
                .isGreaterThanOrEqualTo(1);
        assertThat(queryLong("select count(*) from wf_copy where copy_event_id = ? and user_id = ?",
                copyEventId, userId)).isEqualTo(1L);
        assertThat(queryText(
                "select source_type from wf_copy where copy_event_id = ? and user_id = ?",
                copyEventId, userId)).isEqualTo("MANUAL_AUTO");
    }

    /**
     * 验证越权请求零更新，两个并发首次阅读请求只写入一次且后续请求保留首次时间。
     * @return void，接收人边界、条件更新或首次时间契约被破坏时测试失败
     * @throws Exception JDBC 或并发任务执行失败
     */
    @Test
    void marksFirstCopyReadAtomicallyForOwnerOnly() throws Exception
    {
        long copyId = negativeId();
        long ownerUserId = Math.abs(copyId);
        String copyEventId = "it-read-" + token();
        boolean inserted = false;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try
        {
            assertThat(executeUpdate("insert into wf_copy "
                    + "(copy_id, copy_event_id, title, process_id, process_name, category_id, "
                    + "deployment_id, instance_id, task_id, user_id, originator_id, del_flag) "
                    + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '0')",
                    copyId, copyEventId, "并发已读", "definition", "测试流程", "test",
                    "deployment", "instance", "task", ownerUserId, ownerUserId + 1L))
                    .isEqualTo(1);
            connection.commit();
            inserted = true;

            assertThat(executeUpdate("update wf_copy set read_status = '1', "
                    + "read_time = current_timestamp(3) where copy_id = ? and user_id = ? "
                    + "and del_flag = '0' and read_status = '0' and read_time is null",
                    copyId, ownerUserId + 99L)).isZero();
            connection.commit();

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> first = executor.submit(() -> markReadConcurrently(
                    copyId, ownerUserId, ready, start));
            Future<Integer> second = executor.submit(() -> markReadConcurrently(
                    copyId, ownerUserId, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS))
                    .isEqualTo(1);

            Timestamp firstReadTime = queryTimestamp(
                    "select read_time from wf_copy where copy_id = ? and user_id = ?",
                    copyId, ownerUserId);
            assertThat(firstReadTime).isNotNull();
            assertThat(executeUpdate("update wf_copy set read_status = '1', "
                    + "read_time = current_timestamp(3) where copy_id = ? and user_id = ? "
                    + "and del_flag = '0' and read_status = '0' and read_time is null",
                    copyId, ownerUserId)).isZero();
            assertThat(queryTimestamp(
                    "select read_time from wf_copy where copy_id = ? and user_id = ?",
                    copyId, ownerUserId)).isEqualTo(firstReadTime);
        }
        finally
        {
            executor.shutdownNow();
            if (inserted)
            {
                executeUpdate("delete from wf_copy where copy_id = ?", copyId);
                connection.commit();
            }
        }
    }

    /**
     * 验证业务查询与配额互斥依赖的关键索引，以及分类有效编码生成列。
     * @return void，列或索引结构漂移时测试失败
     * @throws SQLException information_schema 查询失败
     */
    @Test
    void exposesRequiredGeneratedColumnAndIndexes() throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement(
                "select extra, generation_expression from information_schema.columns "
                        + "where table_schema = ? and table_name = 'wf_category' "
                        + "and column_name = 'active_code'"))
        {
            statement.setString(1, expectedSchema);
            try (ResultSet resultSet = statement.executeQuery())
            {
                assertThat(resultSet.next()).as("wf_category.active_code 必须存在").isTrue();
                assertThat(resultSet.getString("extra")).containsIgnoringCase("STORED GENERATED");
                String expression = resultSet.getString("generation_expression");
                assertThat(expression).containsIgnoringCase("del_flag");
                assertThat(expression).containsIgnoringCase("code");
                assertThat(resultSet.next()).isFalse();
            }
        }

        assertIndex("wf_category", "uk_wf_category_active_code", false, "active_code");
        assertIndex("wf_form", "idx_wf_form_name", true, "form_name");
        assertIndex("wf_deploy_form", "PRIMARY", false, "deploy_id,form_key,node_key");
        assertIndex("wf_deploy_form", "idx_wf_deploy_form_form_id", true, "form_id");
        assertIndex("wf_copy", "uk_wf_copy_event_user", false, "copy_event_id,user_id");
        assertIndex("wf_copy", "idx_wf_copy_user_status_time", true,
                "user_id,del_flag,read_status,create_time");
        assertIndex("wf_copy", "idx_wf_copy_instance", true, "instance_id,del_flag");
        assertIndex("wf_attachment_quota_guard", "PRIMARY", false, "owner_user_id");
        assertIndex("wf_attachment", "idx_wf_attachment_owner_status_expire", true,
                "owner_user_id,attachment_status,expire_time");
        assertIndex("ACT_RE_MODEL", "ACT_UNIQ_MODEL", false, "KEY_,VERSION_,TENANT_ID_");
    }

    /**
     * 验证数据库拒绝同一模型 key、版本和租户的重复记录，作为多实例部署下的最终并发门禁。
     * @return void，重复模型版本能够持久化时测试失败
     * @throws SQLException 首次合法模型写入或清理失败
     */
    @Test
    void rejectsDuplicateFlowableModelVersion() throws SQLException
    {
        String modelKey = "it-model-" + token();
        String firstModelId = "it-model-id-" + token();
        String duplicateModelId = "it-model-id-" + token();
        String insertSql = "insert into ACT_RE_MODEL (ID_, REV_, NAME_, KEY_, VERSION_, TENANT_ID_) "
                + "values (?, 1, ?, ?, 1, '')";

        assertThat(executeUpdate(insertSql, firstModelId, "集成测试模型", modelKey)).isEqualTo(1);
        assertMysqlError(MYSQL_DUPLICATE_KEY_ERROR, () -> executeUpdate(
                insertSql, duplicateModelId, "重复版本模型", modelKey));
        assertThat(queryLong(
                "select count(*) from ACT_RE_MODEL where KEY_ = ? and VERSION_ = 1 and TENANT_ID_ = ''",
                modelKey)).isEqualTo(1L);
    }

    /**
     * 核对指定索引的唯一性和列顺序。
     * @param tableName String，目标业务表名
     * @param indexName String，目标索引名
     * @param nonUnique boolean，期望的非唯一标志
     * @param expectedColumns String，按索引顺序拼接的列名
     * @return void，索引不存在或契约漂移时测试失败
     * @throws SQLException information_schema 查询失败
     */
    private void assertIndex(String tableName, String indexName, boolean nonUnique,
            String expectedColumns) throws SQLException
    {
        String sql = "select non_unique, "
                + "group_concat(column_name order by seq_in_index separator ',') as columns_in_order "
                + "from information_schema.statistics "
                + "where table_schema = ? and table_name = ? and index_name = ? "
                + "group by non_unique";
        try (PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setString(1, expectedSchema);
            statement.setString(2, tableName);
            statement.setString(3, indexName);
            try (ResultSet resultSet = statement.executeQuery())
            {
                assertThat(resultSet.next()).as(tableName + "." + indexName + " 必须存在")
                        .isTrue();
                assertThat(resultSet.getBoolean("non_unique")).isEqualTo(nonUnique);
                assertThat(resultSet.getString("columns_in_order")).isEqualTo(expectedColumns);
                assertThat(resultSet.next()).isFalse();
            }
        }
    }

    /**
     * 执行参数化更新，避免集成测试把动态值拼接进 SQL。
     * @param sql String，带占位符的 SQL
     * @param parameters Object[]，按占位符顺序绑定的参数
     * @return int，受影响行数
     * @throws SQLException SQL 执行失败
     */
    private int executeUpdate(String sql, Object... parameters) throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement(sql))
        {
            bindParameters(statement, parameters);
            return statement.executeUpdate();
        }
    }

    /**
     * 执行只返回单个长整数的参数化查询。
     * @param sql String，带占位符的查询 SQL
     * @param parameters Object[]，按占位符顺序绑定的参数
     * @return long，查询返回的首列数值
     * @throws SQLException SQL 执行失败或未返回记录
     */
    private long queryLong(String sql, Object... parameters) throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement(sql))
        {
            bindParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery())
            {
                if (!resultSet.next())
                {
                    throw new SQLException("计数查询未返回记录");
                }
                return resultSet.getLong(1);
            }
        }
    }

    /**
     * 查询单条首次阅读时间。
     * @param sql String，带占位符的单列时间查询
     * @param parameters Object[]，按占位符顺序绑定的参数
     * @return Timestamp，数据库中的首次阅读时间；列为空时返回 null
     * @throws SQLException JDBC 查询失败或未返回记录
     */
    private Timestamp queryTimestamp(String sql, Object... parameters) throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement(sql))
        {
            bindParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery())
            {
                if (!resultSet.next())
                {
                    throw new SQLException("首次阅读时间查询未返回记录");
                }
                return resultSet.getTimestamp(1);
            }
        }
    }

    /**
     * 查询单条参数化文本结果。
     * @param sql String，带占位符的单列文本查询
     * @param parameters Object[]，按占位符顺序绑定的参数
     * @return String，数据库返回的首列文本
     * @throws SQLException JDBC 查询失败或未返回记录
     */
    private String queryText(String sql, Object... parameters) throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement(sql))
        {
            bindParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery())
            {
                if (!resultSet.next())
                {
                    throw new SQLException("文本查询未返回记录");
                }
                return resultSet.getString(1);
            }
        }
    }

    /**
     * 使用独立真实 MySQL 连接执行接收人限定的首次阅读条件更新。
     * @param copyId long，抄送记录主键
     * @param ownerUserId long，正式接收用户主键
     * @param ready CountDownLatch，两个并发连接均已就绪的屏障
     * @param start CountDownLatch，统一释放更新请求的开始信号
     * @return int，当前请求实际更新的记录数，只允许 0 或 1
     * @throws Exception JDBC、等待或线程中断失败
     */
    private int markReadConcurrently(long copyId, long ownerUserId,
            CountDownLatch ready, CountDownLatch start) throws Exception
    {
        try (Connection concurrentConnection = DriverManager.getConnection(
                jdbcUrl, databaseUsername, databasePassword);
                PreparedStatement statement = concurrentConnection.prepareStatement(
                        "update wf_copy set read_status = '1', read_time = current_timestamp(3) "
                                + "where copy_id = ? and user_id = ? and del_flag = '0' "
                                + "and read_status = '0' and read_time is null"))
        {
            statement.setLong(1, copyId);
            statement.setLong(2, ownerUserId);
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS))
            {
                throw new IllegalStateException("并发首次阅读启动超时");
            }
            return statement.executeUpdate();
        }
    }

    /**
     * 执行不带参数且只返回一个字符串的安全门禁查询。
     * @param sql String，只读标量查询 SQL
     * @return String，查询返回的首列字符串
     * @throws SQLException SQL 执行失败或未返回记录
     */
    private String queryString(String sql) throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery())
        {
            if (!resultSet.next())
            {
                throw new SQLException("标量查询未返回记录");
            }
            return resultSet.getString(1);
        }
    }

    /**
     * 按顺序绑定 PreparedStatement 参数。
     * @param statement PreparedStatement，待绑定参数的语句
     * @param parameters Object[]，按占位符顺序排列的参数
     * @return void，无返回值
     * @throws SQLException 参数绑定失败
     */
    private void bindParameters(PreparedStatement statement, Object... parameters)
            throws SQLException
    {
        for (int index = 0; index < parameters.length; index++)
        {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    /**
     * 断言 SQL 失败来自预期的 MySQL 约束错误码。
     * @param errorCode int，期望的 MySQL 服务端错误码
     * @param operation ThrowingCallable，预计被数据库约束拒绝的写入操作
     * @return void，写入成功或错误类型不符时测试失败
     */
    private void assertMysqlError(int errorCode, ThrowingCallable operation)
    {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(SQLException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }

    /**
     * 读取必填环境变量，不记录变量值，避免连接凭据进入测试输出。
     * @param name String，环境变量名
     * @return String，去除首尾空白后的环境变量值
     */
    private String requiredEnvironment(String name)
    {
        String value = System.getenv(name);
        if (value == null || value.isBlank())
        {
            throw new IllegalStateException("缺少必填集成测试环境变量：" + name);
        }
        return value.trim();
    }

    /**
     * 读取必填敏感环境变量并保留原值，避免改变合法的首尾空格。
     * @param name String，敏感环境变量名
     * @return String，未经规范化的环境变量原值
     */
    private String requiredSecretEnvironment(String name)
    {
        String value = System.getenv(name);
        if (value == null || value.isBlank())
        {
            throw new IllegalStateException("缺少必填集成测试环境变量：" + name);
        }
        return value;
    }

    /**
     * 生成不会推进表 AUTO_INCREMENT 的负数测试主键。
     * @return long，隔离事务内使用的随机负数主键
     */
    private long negativeId()
    {
        return -ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE / 4L);
    }

    /**
     * 生成长度受控的随机业务键片段。
     * @return String，不含连字符的小写 UUID 文本
     */
    private String token()
    {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

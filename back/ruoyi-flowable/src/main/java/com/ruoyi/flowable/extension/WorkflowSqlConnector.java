package com.ruoyi.flowable.extension;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;
import com.ruoyi.flowable.domain.WfSqlDataSource;
import com.ruoyi.flowable.domain.vo.WorkflowConnectorInvocationClaim;
import com.ruoyi.flowable.service.model.WorkflowSqlDataSourceService;
import com.ruoyi.flowable.service.process.WorkflowConnectorInvocationService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 受控 SQL 连接器：部署时冻结数据源修订，运行时只执行 AST 校验过的命名参数模板。
 */
@Component
public class WorkflowSqlConnector
{
    public static final String IMPLEMENTATION_KEY = "SQL_CONNECTOR_V1";
    private static final Set<String> AUTHOR_FIELDS = Set.of(
            "dataSourceKey", "sql", "parameters", "resultVariable", "maxRows");
    private static final Set<String> FROZEN_FIELDS = Set.of(
            "dataSourceKey", "sql", "parameters", "resultVariable", "maxRows",
            "operation", "tables", "dataSourceSnapshot");
    private static final String CONFIG_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["dataSourceKey","sql","parameters"],
             "properties":{"dataSourceKey":{"type":"string","pattern":"^[A-Za-z][A-Za-z0-9_.-]{0,127}$"},
             "sql":{"type":"string","minLength":1,"maxLength":8192},
             "parameters":{"type":"object","additionalProperties":{"type":"string","pattern":"^[A-Za-z_][A-Za-z0-9_]{0,127}$"}},
             "resultVariable":{"type":"string","pattern":"^[A-Za-z_][A-Za-z0-9_]{0,127}$"},
             "maxRows":{"type":"integer","minimum":1,"maximum":1000}}}
            """;

    private final DataSource primaryDataSource;
    private final WorkflowSqlDataSourceService dataSourceService;
    private final WorkflowSqlTemplateValidator templateValidator;
    private final WorkflowSqlSecretResolver secretResolver;
    private final WorkflowConnectorInvocationService invocationService;
    private final ObjectMapper objectMapper = JsonMapper.shared();

    /**
     * 创建 SQL 连接器。
     * @param primaryDataSource DataSource，Flowable 与业务表共用的主库连接池
     * @param dataSourceService WorkflowSqlDataSourceService，受控数据源目录
     * @param templateValidator WorkflowSqlTemplateValidator，SQL AST 门禁
     * @param secretResolver WorkflowSqlSecretResolver，外库环境引用解析器
     * @param invocationService WorkflowConnectorInvocationService，外部副作用幂等台账
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowSqlConnector(DataSource primaryDataSource,
            WorkflowSqlDataSourceService dataSourceService,
            WorkflowSqlTemplateValidator templateValidator,
            WorkflowSqlSecretResolver secretResolver,
            WorkflowConnectorInvocationService invocationService)
    {
        this.primaryDataSource = primaryDataSource;
        this.dataSourceService = dataSourceService;
        this.templateValidator = templateValidator;
        this.secretResolver = secretResolver;
        this.invocationService = invocationService;
    }

    /**
     * 返回固定作者配置 Schema。
     * @return String，规范 JSON Schema
     */
    public String configSchema()
    {
        return WorkflowExtensionJsonCanonicalizer.canonicalize(CONFIG_SCHEMA);
    }

    /**
     * 校验作者配置、锁定数据源并生成不含凭据正文的部署快照。
     * @param config JsonNode，作者节点配置
     * @param asynchronous boolean，节点是否启用进入前异步
     * @return String，规范冻结配置
     */
    public String freezeConfig(JsonNode config, boolean asynchronous)
    {
        AuthorConfig author = parseAuthor(config, AUTHOR_FIELDS);
        WfSqlDataSource dataSource = dataSourceService.lockEnabledForDeployment(
                author.dataSourceKey());
        WorkflowSqlTemplate template = templateValidator.validate(author.sql(),
                Set.of(dataSource.getAllowedTables().split(",")));
        requireParameterMapping(author, template);
        boolean externalWrite = "EXTERNAL".equals(dataSource.getConnectionType())
                && !"SELECT".equals(template.operation());
        if (externalWrite && (!asynchronous || !template.parameterNames().contains("idempotencyKey")))
        {
            throw new ServiceException("SQL 外库写入必须异步并消费命名参数 idempotencyKey",
                    HttpStatus.BAD_REQUEST);
        }
        if (externalWrite && author.resultVariable() != null)
        {
            throw new ServiceException("SQL 外库写入不允许依赖不可重放的影响行数",
                    HttpStatus.BAD_REQUEST);
        }
        try
        {
            ObjectNode frozen = objectMapper.createObjectNode();
            frozen.put("dataSourceKey", author.dataSourceKey());
            frozen.put("sql", template.sql());
            frozen.set("parameters", author.parameters().deepCopy());
            putOptional(frozen, "resultVariable", author.resultVariable());
            frozen.put("maxRows", author.maxRows());
            frozen.put("operation", template.operation());
            frozen.set("tables", objectMapper.valueToTree(template.tables()));
            ObjectNode source = frozen.putObject("dataSourceSnapshot");
            source.put("dataSourceId", dataSource.getDataSourceId());
            source.put("dataSourceName", dataSource.getDataSourceName());
            source.put("connectionType", dataSource.getConnectionType());
            putOptional(source, "jdbcUrlRef", dataSource.getJdbcUrlRef());
            putOptional(source, "usernameRef", dataSource.getUsernameRef());
            putOptional(source, "passwordRef", dataSource.getPasswordRef());
            source.put("allowedTables", dataSource.getAllowedTables());
            source.put("connectTimeoutMs", dataSource.getConnectTimeoutMs());
            source.put("queryTimeoutSeconds", dataSource.getQueryTimeoutSeconds());
            source.put("revisionNo", dataSource.getRevisionNo());
            source.put("checksum", dataSource.getChecksum());
            return WorkflowExtensionJsonCanonicalizer.canonicalize(
                    objectMapper.writeValueAsString(frozen));
        }
        catch (JacksonException exception)
        {
            throw new ServiceException("SQL 连接器冻结配置无法序列化", HttpStatus.ERROR);
        }
    }

    /**
     * 复核并执行冻结 SQL；主库复用 Flowable 事务，外库通过稳定幂等台账承载至少一次重试。
     * @param execution DelegateExecution，当前 Flowable 执行上下文
     * @param snapshot WfDeployExtensionSnapshot，扩展部署快照
     * @param config JsonNode，冻结 SQL 配置
     * @return void，结果按受控 resultVariable 写回流程变量
     */
    public void execute(DelegateExecution execution, WfDeployExtensionSnapshot snapshot,
            JsonNode config)
    {
        FrozenConfig frozen = parseFrozen(config);
        Map<String, Object> parameters = resolveParameters(execution, frozen);
        if ("PRIMARY".equals(frozen.dataSource().connectionType()))
        {
            Object result = executeSql(primaryDataSource, frozen, parameters);
            setResult(execution, frozen.author().resultVariable(), result);
            return;
        }

        String idempotencyKey = WorkflowExtensionChecksum.sha256(snapshot.getDeployId(),
                execution.getProcessInstanceId(), execution.getId(), execution.getCurrentActivityId());
        parameters.put("idempotencyKey", idempotencyKey);
        WorkflowConnectorInvocationClaim claim = invocationService.begin(snapshot.getDeployId(),
                execution.getProcessInstanceId(), execution.getId(), execution.getCurrentActivityId(),
                "SQL", frozen.author().dataSourceKey(), frozen.dataSource().revisionNo(), idempotencyKey,
                frozen.operation(), String.join(",", frozen.tables()));
        if ("SUCCESS".equals(claim.status()))
        {
            return;
        }
        long started = System.nanoTime();
        try
        {
            Object result = executeSql(externalDataSource(frozen.dataSource()), frozen, parameters);
            invocationService.success(claim, elapsedMillis(started), 200, summarize(result));
            setResult(execution, frozen.author().resultVariable(), result);
        }
        catch (RuntimeException exception)
        {
            invocationService.failure(claim, elapsedMillis(started), null,
                    "SQL_EXECUTION_ERROR", "sql-execution-failed");
            throw exception;
        }
    }

    /**
     * 重新规范化冻结配置，供运行调度器执行版本和配置漂移校验。
     * @param config JsonNode，冻结配置
     * @return String，规范冻结配置
     */
    public String validateFrozenConfig(JsonNode config)
    {
        FrozenConfig frozen = parseFrozen(config);
        return WorkflowExtensionJsonCanonicalizer.canonicalize(config.toString());
    }

    /**
     * 在指定数据源执行单条模板并限制查询结果规模。
     * @param dataSource DataSource，主库或本次外库连接配置
     * @param frozen FrozenConfig，已复核配置
     * @param parameters Map&lt;String,Object&gt;，由白名单变量解析的参数
     * @return Object，SELECT 返回有界行列表，写操作返回影响行数
     */
    private Object executeSql(DataSource dataSource, FrozenConfig frozen,
            Map<String, Object> parameters)
    {
        try
        {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            jdbcTemplate.setQueryTimeout(frozen.dataSource().queryTimeoutSeconds());
            jdbcTemplate.setMaxRows(frozen.author().maxRows());
            NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbcTemplate);
            if ("SELECT".equals(frozen.operation()))
            {
                return named.queryForList(frozen.author().sql(), parameters);
            }
            return named.update(frozen.author().sql(), parameters);
        }
        catch (DataAccessException exception)
        {
            throw new ServiceException("SQL 连接器执行失败", HttpStatus.ERROR);
        }
    }

    /**
     * 从冻结环境引用创建本次外库 DataSource，不缓存或记录任何凭据正文。
     * @param source DataSourceSnapshot，冻结引用和超时
     * @return DataSource，仅供当前执行使用的外库数据源
     */
    private DataSource externalDataSource(DataSourceSnapshot source)
    {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(secretResolver.requireJdbcUrl(source.jdbcUrlRef()));
        dataSource.setUsername(secretResolver.requireUsername(source.usernameRef()));
        dataSource.setPassword(secretResolver.requirePassword(source.passwordRef()));
        java.util.Properties properties = new java.util.Properties();
        properties.setProperty("connectTimeout", String.valueOf(source.connectTimeoutMs()));
        properties.setProperty("socketTimeout",
                String.valueOf(source.queryTimeoutSeconds() * 1000));
        dataSource.setConnectionProperties(properties);
        return dataSource;
    }

    /**
     * 解析作者字段并拒绝附加配置、空模板和非法变量名。
     * @param config JsonNode，作者配置
     * @param allowed Set&lt;String&gt;，允许字段集合
     * @return AuthorConfig，规范作者配置
     */
    private AuthorConfig parseAuthor(JsonNode config, Set<String> allowed)
    {
        if (config == null || !config.isObject())
        {
            throw new ServiceException("SQL 连接器配置必须是对象", HttpStatus.BAD_REQUEST);
        }
        config.propertyStream().forEach(entry ->
        {
            if (!allowed.contains(entry.getKey()))
            {
                throw new ServiceException("SQL 连接器包含未授权配置", HttpStatus.BAD_REQUEST);
            }
        });
        String key = requiredText(config, "dataSourceKey", 128);
        String sql = requiredText(config, "sql", 8192);
        JsonNode mappings = config.get("parameters");
        if (mappings == null || !mappings.isObject() || mappings.size() > 128)
        {
            throw new ServiceException("SQL 参数映射必须是有界对象", HttpStatus.BAD_REQUEST);
        }
        mappings.propertyStream().forEach(entry ->
        {
            if (!entry.getValue().isTextual()
                    || !entry.getValue().asText().matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
            {
                throw new ServiceException("SQL 参数只能映射到受控流程变量", HttpStatus.BAD_REQUEST);
            }
        });
        String result = optionalVariable(config, "resultVariable");
        int maxRows = config.has("maxRows") ? config.get("maxRows").asInt(-1) : 100;
        if (maxRows < 1 || maxRows > 1000)
        {
            throw new ServiceException("SQL 查询行数上限不合法", HttpStatus.BAD_REQUEST);
        }
        return new AuthorConfig(key, sql, (ObjectNode) mappings, result, maxRows);
    }

    /**
     * 解析并复核冻结数据源摘要、SQL AST、参数、操作和访问表。
     * @param config JsonNode，部署快照配置
     * @return FrozenConfig，运行时结构化配置
     */
    private FrozenConfig parseFrozen(JsonNode config)
    {
        AuthorConfig author = parseAuthor(config, FROZEN_FIELDS);
        JsonNode source = config.get("dataSourceSnapshot");
        if (source == null || !source.isObject())
        {
            throw new ServiceException("SQL 数据源部署快照缺失", HttpStatus.ERROR);
        }
        DataSourceSnapshot dataSource = new DataSourceSnapshot(source.path("dataSourceId").asLong(),
                requiredText(source, "dataSourceName", 128),
                requiredText(source, "connectionType", 16), optionalText(source, "jdbcUrlRef"),
                optionalText(source, "usernameRef"), optionalText(source, "passwordRef"),
                requiredText(source, "allowedTables", 8192), source.path("connectTimeoutMs").asInt(),
                source.path("queryTimeoutSeconds").asInt(), source.path("revisionNo").asInt(),
                requiredText(source, "checksum", 64));
        WfSqlDataSource checksumSource = dataSource.toDomain(author.dataSourceKey());
        if (!WorkflowSqlDataSourceService.dataSourceChecksum(checksumSource)
                .equals(dataSource.checksum()))
        {
            throw new ServiceException("SQL 数据源部署快照校验和不一致", HttpStatus.ERROR);
        }
        WorkflowSqlTemplate template = templateValidator.validate(author.sql(),
                Set.of(dataSource.allowedTables().split(",")));
        requireParameterMapping(author, template);
        String operation = requiredText(config, "operation", 16);
        if (!operation.equals(template.operation()) || !config.path("tables").isArray()
                || !config.path("tables").equals(objectMapper.valueToTree(template.tables())))
        {
            throw new ServiceException("SQL 模板部署快照已漂移", HttpStatus.ERROR);
        }
        return new FrozenConfig(author, operation, template.tables(), dataSource);
    }

    /**
     * 要求模板每个参数都有唯一变量映射；系统幂等键由运行时注入。
     * @param author AuthorConfig，作者参数映射
     * @param template WorkflowSqlTemplate，AST 参数清单
     * @return void，缺失或多余映射时拒绝
     */
    private void requireParameterMapping(AuthorConfig author, WorkflowSqlTemplate template)
    {
        Set<String> expected = new java.util.TreeSet<>(template.parameterNames());
        expected.remove("idempotencyKey");
        Set<String> actual = new java.util.TreeSet<>();
        author.parameters().propertyStream().forEach(entry -> actual.add(entry.getKey()));
        if (!expected.equals(actual))
        {
            throw new ServiceException("SQL 参数映射与模板命名参数不一致", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 从当前流程实例读取白名单映射变量，不允许表达式或动态属性访问。
     * @param execution DelegateExecution，当前执行上下文
     * @param frozen FrozenConfig，冻结映射
     * @return Map&lt;String,Object&gt;，SQL 命名参数值
     */
    private Map<String, Object> resolveParameters(DelegateExecution execution, FrozenConfig frozen)
    {
        Map<String, Object> values = new HashMap<>();
        frozen.author().parameters().propertyStream().forEach(entry ->
                values.put(entry.getKey(), execution.getVariable(entry.getValue().asText())));
        return values;
    }

    /**
     * 把执行结果写入显式允许的流程变量。
     * @param execution DelegateExecution，当前执行上下文
     * @param variable String，可空结果变量名
     * @param result Object，影响行数或有界结果行
     * @return void，无结果变量时不写入
     */
    private void setResult(DelegateExecution execution, String variable, Object result)
    {
        if (variable == null)
        {
            return;
        }
        try
        {
            execution.setVariable(variable, result instanceof List<?>
                    ? objectMapper.writeValueAsString(result) : result);
        }
        catch (JacksonException exception)
        {
            throw new ServiceException("SQL 查询结果无法安全序列化", HttpStatus.ERROR);
        }
    }

    /**
     * 生成不包含行值和 SQL 正文的审计摘要。
     * @param result Object，连接器执行结果
     * @return String，结果类型和计数摘要
     */
    private String summarize(Object result)
    {
        if (result instanceof List<?> rows)
        {
            return "rows=" + rows.size();
        }
        return "affectedRows=" + result;
    }

    /**
     * 计算单次执行耗时。
     * @param started long，System.nanoTime 起始值
     * @return long，非负毫秒
     */
    private long elapsedMillis(long started)
    {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    /**
     * 读取必填短文本。
     * @param node JsonNode，来源对象
     * @param field String，字段名
     * @param max int，最大长度
     * @return String，去空白文本
     */
    private String requiredText(JsonNode node, String field, int max)
    {
        JsonNode value = node.get(field);
        String text = value != null && value.isTextual() ? value.asText().trim() : "";
        if (text.isEmpty() || text.length() > max)
        {
            throw new ServiceException("SQL 连接器字段 " + field + " 不合法", HttpStatus.BAD_REQUEST);
        }
        return text;
    }

    /**
     * 读取可选流程变量名。
     * @param node JsonNode，来源对象
     * @param field String，字段名
     * @return String，合法变量名或 null
     */
    private String optionalVariable(JsonNode node, String field)
    {
        String value = optionalText(node, field);
        if (value != null && !value.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
        {
            throw new ServiceException("SQL 结果变量名不合法", HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    /**
     * 读取可选文本。
     * @param node JsonNode，来源对象
     * @param field String，字段名
     * @return String，空值转为 null
     */
    private String optionalText(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank()
                ? value.asText().trim() : null;
    }

    /**
     * 向 JSON 写入可选文本。
     * @param node ObjectNode，目标对象
     * @param field String，字段名
     * @param value String，可空值
     * @return void，空值不写入
     */
    private void putOptional(ObjectNode node, String field, String value)
    {
        if (value != null)
        {
            node.put(field, value);
        }
    }

    private record AuthorConfig(String dataSourceKey, String sql, ObjectNode parameters,
            String resultVariable, int maxRows) { }
    private record FrozenConfig(AuthorConfig author, String operation, List<String> tables,
            DataSourceSnapshot dataSource) { }
    private record DataSourceSnapshot(long dataSourceId, String dataSourceName,
            String connectionType, String jdbcUrlRef, String usernameRef, String passwordRef,
            String allowedTables, int connectTimeoutMs, int queryTimeoutSeconds,
            int revisionNo, String checksum)
    {
        /**
         * 重建校验和所需目录实体。
         * @param dataSourceKey String，作者稳定键
         * @return WfSqlDataSource，未含凭据正文的摘要输入
         */
        private WfSqlDataSource toDomain(String dataSourceKey)
        {
            WfSqlDataSource source = new WfSqlDataSource();
            source.setDataSourceKey(dataSourceKey);
            source.setDataSourceName(dataSourceName);
            source.setConnectionType(connectionType);
            source.setJdbcUrlRef(jdbcUrlRef);
            source.setUsernameRef(usernameRef);
            source.setPasswordRef(passwordRef);
            source.setAllowedTables(allowedTables);
            source.setConnectTimeoutMs(connectTimeoutMs);
            source.setQueryTimeoutSeconds(queryTimeoutSeconds);
            source.setRevisionNo(revisionNo);
            return source;
        }
    }
}

package com.ruoyi.flowable.extension;

import com.ruoyi.flowable.service.integration.WorkflowSqlSecretResolver;

import static com.ruoyi.flowable.extension.WorkflowSqlTemplateValidator.*;
import com.ruoyi.flowable.extension.WorkflowSqlTemplateValidator.AuthorConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.flowable.engine.delegate.DelegateExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfSqlDataSource;
import com.ruoyi.flowable.runtime.WorkflowConnectorMetrics;
import com.ruoyi.flowable.service.integration.WorkflowSqlDataSourceService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 受控 SQL 连接器：部署时冻结数据源修订，运行时只执行 AST 校验过的命名参数模板。
 */
@Component
public class WorkflowSqlConnector
{
    private static final Logger log = LoggerFactory.getLogger(WorkflowSqlConnector.class);
    public static final String IMPLEMENTATION_KEY = "SQL_CONNECTOR_V1";
    private final DataSource primaryDataSource;
    private final WorkflowSqlTemplateValidator templateValidator;
    private final WorkflowSqlSecretResolver secretResolver;
    private final WorkflowConnectorMetrics connectorMetrics;
    private final ObjectMapper objectMapper = JsonMapper.shared();

    /**
     * 创建 SQL 连接器。
     * 构造后由 Spring 管理
     */
    public WorkflowSqlConnector(DataSource primaryDataSource,
            WorkflowSqlTemplateValidator templateValidator,
            WorkflowSqlSecretResolver secretResolver,
            WorkflowConnectorMetrics connectorMetrics)
    {
        this.primaryDataSource = primaryDataSource;
        this.templateValidator = templateValidator;
        this.secretResolver = secretResolver;
        this.connectorMetrics = connectorMetrics;
    }

    /**
     * 复核并执行冻结 SQL；失败交回 Flowable Job 重试，外库写由业务唯一键契约消除重复副作用。
     * @param execution DelegateExecution，当前 Flowable 执行上下文
     * @param config JsonNode，冻结 SQL 配置
     * @return void，结果按受控 resultVariable 写回流程变量
     */
    public void execute(DelegateExecution execution, JsonNode config)
    {
        FrozenConfig frozen = parseFrozen(config);
        Map<String, Object> parameters = resolveParameters(execution, frozen);
        boolean external = "EXTERNAL".equals(frozen.dataSource().connectionType());
        boolean write = !"SELECT".equals(frozen.operation());
        if (external && write)
        {
            String idempotencyKey = WorkflowExtensionChecksum.sha256(
                    execution.getProcessInstanceId(), execution.getId(),
                    execution.getCurrentActivityId());
            parameters.put("idempotencyKey", idempotencyKey);
        }
        long started = System.nanoTime();
        try
        {
            DataSource dataSource = external
                    ? externalDataSource(frozen.dataSource()) : primaryDataSource;
            Object result = executeSql(dataSource, frozen, parameters);
            setResult(execution, frozen.author().resultVariable(), result);
            recordAttempt(execution, started, true, "SUCCESS");
        }
        catch (RuntimeException exception)
        {
            recordAttempt(execution, started, false, "SQL_EXECUTION_ERROR");
            throw exception;
        }
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
                Set.of(dataSource.allowedTables().split(",")),
                "EXTERNAL".equals(dataSource.connectionType()), author.idempotencyColumn());
        requireParameterMapping(author, template);
        String operation = requiredText(config, "operation", 16);
        if (!operation.equals(template.operation()) || !config.path("tables").isArray()
                || !config.path("tables").equals(objectMapper.valueToTree(template.tables())))
        {
            throw new ServiceException("SQL 模板部署快照已漂移", HttpStatus.ERROR);
        }
        return new FrozenConfig(author, operation, dataSource);
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
     * 记录单次 Flowable Job 尝试的结构化日志和指标，不输出 SQL、参数、结果行或凭据。
     * @param execution DelegateExecution，当前 Flowable 执行上下文
     * @param started long，System.nanoTime 起点
     * @param success boolean，本次尝试是否成功
     * @param resultCode String，稳定低敏结果码
     * @return void，日志与指标只承担诊断，不参与业务恢复
     */
    private void recordAttempt(DelegateExecution execution, long started,
            boolean success, String resultCode)
    {
        long durationMs = elapsedMillis(started);
        connectorMetrics.record("SQL", success, durationMs);
        log.info("operation=workflowConnector type=SQL traceId={} processInstanceId={} "
                        + "executionId={} elementId={} resultCode={} durationMs={}",
                safeTraceId(), execution.getProcessInstanceId(), execution.getId(),
                execution.getCurrentActivityId(), resultCode, durationMs);
    }

    /**
     * 读取当前链路标识，未配置 MDC 时使用空字符串。
     * @return String，当前 traceId 或空字符串
     */
    private String safeTraceId()
    {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
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

    private record FrozenConfig(AuthorConfig author, String operation,
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

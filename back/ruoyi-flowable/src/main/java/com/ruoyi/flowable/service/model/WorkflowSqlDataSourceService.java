package com.ruoyi.flowable.service.model;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfSqlDataSource;
import com.ruoyi.flowable.domain.dto.WorkflowSqlDataSourceRequest;
import com.ruoyi.flowable.domain.vo.WorkflowSqlDataSourceView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.extension.WorkflowExtensionChecksum;
import com.ruoyi.flowable.mapper.WfSqlDataSourceMapper;

/**
 * SQL 连接器数据源逻辑目录、表白名单和不可回退修订服务。
 */
@Service
public class WorkflowSqlDataSourceService
{
    /** 数据源稳定键格式。 */
    private static final Pattern DATA_SOURCE_KEY =
            Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,127}");
    /** 授权表支持普通表名或显式 schema.table。 */
    private static final Pattern TABLE_NAME =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,127}(\\.[A-Za-z_][A-Za-z0-9_$]{0,127})?");
    /** 外库引用统一限定在 SQL 数据源环境变量命名空间。 */
    private static final Pattern JDBC_URL_REF =
            Pattern.compile("WORKFLOW_SQL_JDBC_URL_[A-Z0-9_]{1,80}");
    private static final Pattern USERNAME_REF =
            Pattern.compile("WORKFLOW_SQL_USERNAME_[A-Z0-9_]{1,80}");
    private static final Pattern PASSWORD_REF =
            Pattern.compile("WORKFLOW_SQL_PASSWORD_[A-Z0-9_]{1,80}");
    private static final String ENABLED = "ENABLED";
    private static final String DISABLED = "DISABLED";

    private final WorkflowEngineOperations engineOperations;
    private final WfSqlDataSourceMapper dataSourceMapper;

    /**
     * 创建 SQL 数据源目录服务。
     * @param engineOperations WorkflowEngineOperations，统一身份和事务边界
     * @param dataSourceMapper WfSqlDataSourceMapper，正式目录 Mapper
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowSqlDataSourceService(WorkflowEngineOperations engineOperations,
            WfSqlDataSourceMapper dataSourceMapper)
    {
        this.engineOperations = engineOperations;
        this.dataSourceMapper = dataSourceMapper;
    }

    /**
     * 查询全部 SQL 数据源管理视图。
     * @return List&lt;WorkflowSqlDataSourceView&gt;，不含凭据正文的真实数据库清单
     */
    public List<WorkflowSqlDataSourceView> list()
    {
        return engineOperations.read(() -> dataSourceMapper.selectList().stream()
                .map(this::toView).toList());
    }

    /**
     * 查询设计器可选的已启用 SQL 数据源。
     * @return List&lt;WorkflowSqlDataSourceView&gt;，已启用清单
     */
    public List<WorkflowSqlDataSourceView> listOptions()
    {
        return engineOperations.read(() -> dataSourceMapper.selectEnabledOptions().stream()
                .map(this::toView).toList());
    }

    /**
     * 创建数据源目录修订 1。
     * @param request WorkflowSqlDataSourceRequest，逻辑连接配置和表白名单
     * @return Long，数据库生成主键
     */
    public Long create(WorkflowSqlDataSourceRequest request)
    {
        WfSqlDataSource normalized = normalize(request, 1);
        return engineOperations.writeAsCurrentUser(identity ->
        {
            normalized.setStatus(ENABLED);
            normalized.setCreateBy(identity.userId());
            if (dataSourceMapper.insert(normalized) != 1 || normalized.getDataSourceId() == null)
            {
                throw new ServiceException("SQL 数据源保存结果不完整", HttpStatus.CONFLICT);
            }
            return normalized.getDataSourceId();
        });
    }

    /**
     * 在行锁内发布下一数据源修订，稳定逻辑键不可修改。
     * @param dataSourceId Long，目录主键
     * @param request WorkflowSqlDataSourceRequest，新修订配置
     * @return Integer，新修订号
     */
    public Integer update(Long dataSourceId, WorkflowSqlDataSourceRequest request)
    {
        requireId(dataSourceId);
        return engineOperations.writeAsCurrentUser(identity ->
        {
            WfSqlDataSource current = requireLocked(dataSourceId);
            WfSqlDataSource normalized = normalize(request,
                    Math.addExact(current.getRevisionNo(), 1));
            if (!current.getDataSourceKey().equals(normalized.getDataSourceKey()))
            {
                throw new ServiceException("SQL 数据源稳定键不允许修改", HttpStatus.CONFLICT);
            }
            normalized.setDataSourceId(dataSourceId);
            normalized.setUpdateBy(identity.userId());
            if (dataSourceMapper.updateRevision(normalized, current.getRevisionNo()) != 1)
            {
                throw new ServiceException("SQL 数据源修订已发生变化", HttpStatus.CONFLICT);
            }
            return normalized.getRevisionNo();
        });
    }

    /**
     * 启用或停用 SQL 数据源，只影响后续设计与部署。
     * @param dataSourceId Long，目录主键
     * @param enabled boolean，目标启用状态
     * @return void，无返回值
     */
    public void changeStatus(Long dataSourceId, boolean enabled)
    {
        requireId(dataSourceId);
        engineOperations.writeAsCurrentUser(identity ->
        {
            WfSqlDataSource current = requireLocked(dataSourceId);
            String target = enabled ? ENABLED : DISABLED;
            if (target.equals(current.getStatus()))
            {
                throw new ServiceException("SQL 数据源已经是目标状态", HttpStatus.CONFLICT);
            }
            if (dataSourceMapper.updateStatus(dataSourceId, target, identity.userId()) != 1)
            {
                throw new ServiceException("SQL 数据源状态已发生变化", HttpStatus.CONFLICT);
            }
            return null;
        });
    }

    /**
     * 部署事务内锁定并复核已启用数据源修订。
     * @param dataSourceKey String，作者配置引用的稳定逻辑键
     * @return WfSqlDataSource，字段完整且摘要一致的当前修订
     */
    public WfSqlDataSource lockEnabledForDeployment(String dataSourceKey)
    {
        String key = dataSourceKey == null ? "" : dataSourceKey.trim();
        if (!DATA_SOURCE_KEY.matcher(key).matches())
        {
            throw new ServiceException("SQL 数据源键不合法", HttpStatus.BAD_REQUEST);
        }
        WfSqlDataSource dataSource = dataSourceMapper.selectEnabledByKeyForUpdate(key);
        if (dataSource == null)
        {
            throw new ServiceException("SQL 数据源不存在或已停用", HttpStatus.CONFLICT);
        }
        if (!dataSourceChecksum(dataSource).equals(dataSource.getChecksum()))
        {
            throw new ServiceException("SQL 数据源校验和不一致", HttpStatus.CONFLICT);
        }
        return dataSource;
    }

    /**
     * 计算数据源当前修订稳定摘要，不包含任何凭据正文。
     * @param dataSource WfSqlDataSource，字段完整的规范数据源
     * @return String，64 位小写 SHA-256
     */
    public static String dataSourceChecksum(WfSqlDataSource dataSource)
    {
        return WorkflowExtensionChecksum.sha256(dataSource.getDataSourceKey(),
                dataSource.getDataSourceName(), dataSource.getConnectionType(),
                dataSource.getJdbcUrlRef(), dataSource.getUsernameRef(),
                dataSource.getPasswordRef(), dataSource.getAllowedTables(),
                String.valueOf(dataSource.getConnectTimeoutMs()),
                String.valueOf(dataSource.getQueryTimeoutSeconds()),
                String.valueOf(dataSource.getRevisionNo()));
    }

    /**
     * 校验并规范化目录请求，阻断明文凭据和未授权表表达式。
     * @param request WorkflowSqlDataSourceRequest，外部请求
     * @param revisionNo int，本次不可回退修订号
     * @return WfSqlDataSource，可直接持久化的规范实体
     */
    private WfSqlDataSource normalize(WorkflowSqlDataSourceRequest request, int revisionNo)
    {
        if (request == null)
        {
            throw new ServiceException("SQL 数据源请求不能为空", HttpStatus.BAD_REQUEST);
        }
        String key = trimToEmpty(request.dataSourceKey());
        String name = trimToEmpty(request.dataSourceName());
        String connectionType = trimToEmpty(request.connectionType()).toUpperCase(Locale.ROOT);
        String jdbcUrlRef = trimToNull(request.jdbcUrlRef());
        String usernameRef = trimToNull(request.usernameRef());
        String passwordRef = trimToNull(request.passwordRef());
        if (!DATA_SOURCE_KEY.matcher(key).matches() || name.isEmpty() || name.length() > 128)
        {
            throw new ServiceException("SQL 数据源基础信息不合法", HttpStatus.BAD_REQUEST);
        }
        validateConnectionReferences(connectionType, jdbcUrlRef, usernameRef, passwordRef);
        String allowedTables = normalizeTables(request.allowedTables());
        if (request.connectTimeoutMs() == null || request.connectTimeoutMs() < 100
                || request.connectTimeoutMs() > 10000 || request.queryTimeoutSeconds() == null
                || request.queryTimeoutSeconds() < 1 || request.queryTimeoutSeconds() > 300)
        {
            throw new ServiceException("SQL 数据源超时配置不合法", HttpStatus.BAD_REQUEST);
        }
        WfSqlDataSource dataSource = new WfSqlDataSource();
        dataSource.setDataSourceKey(key);
        dataSource.setDataSourceName(name);
        dataSource.setConnectionType(connectionType);
        dataSource.setJdbcUrlRef(jdbcUrlRef);
        dataSource.setUsernameRef(usernameRef);
        dataSource.setPasswordRef(passwordRef);
        dataSource.setAllowedTables(allowedTables);
        dataSource.setConnectTimeoutMs(request.connectTimeoutMs());
        dataSource.setQueryTimeoutSeconds(request.queryTimeoutSeconds());
        dataSource.setRevisionNo(revisionNo);
        dataSource.setChecksum(dataSourceChecksum(dataSource));
        return dataSource;
    }

    /**
     * 校验主库或外库连接引用组合，引用值本身不能包含凭据正文。
     * @param connectionType String，PRIMARY 或 EXTERNAL
     * @param jdbcUrlRef String，可空 JDBC URL 引用
     * @param usernameRef String，可空用户名引用
     * @param passwordRef String，可空密码引用
     * @return void，组合非法时抛出业务异常
     */
    private void validateConnectionReferences(String connectionType, String jdbcUrlRef,
            String usernameRef, String passwordRef)
    {
        if ("PRIMARY".equals(connectionType))
        {
            if (jdbcUrlRef != null || usernameRef != null || passwordRef != null)
            {
                throw new ServiceException("主库 SQL 数据源不能配置外部连接引用", HttpStatus.BAD_REQUEST);
            }
            return;
        }
        if (!"EXTERNAL".equals(connectionType) || jdbcUrlRef == null || usernameRef == null
                || passwordRef == null || !JDBC_URL_REF.matcher(jdbcUrlRef).matches()
                || !USERNAME_REF.matcher(usernameRef).matches()
                || !PASSWORD_REF.matcher(passwordRef).matches())
        {
            throw new ServiceException("外库 SQL 数据源环境引用不合法", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 规范授权表清单并阻止重复、空值和超量配置。
     * @param tables List&lt;String&gt;，外部表白名单
     * @return String，逗号分隔且按字典序冻结的小写表名
     */
    private String normalizeTables(List<String> tables)
    {
        TreeSet<String> normalized = new TreeSet<>();
        for (String table : tables == null ? List.<String>of() : tables)
        {
            String value = table == null ? "" : table.trim().toLowerCase(Locale.ROOT);
            if (!TABLE_NAME.matcher(value).matches() || !normalized.add(value))
            {
                throw new ServiceException("SQL 数据源表白名单不合法或重复", HttpStatus.BAD_REQUEST);
            }
        }
        if (normalized.isEmpty() || normalized.size() > 64)
        {
            throw new ServiceException("SQL 数据源表白名单为空或超过数量限制", HttpStatus.BAD_REQUEST);
        }
        return String.join(",", normalized);
    }

    /**
     * 把目录实体转换为不含凭据正文的管理视图。
     * @param dataSource WfSqlDataSource，数据库目录实体
     * @return WorkflowSqlDataSourceView，引用和表清单结构化视图
     */
    private WorkflowSqlDataSourceView toView(WfSqlDataSource dataSource)
    {
        return new WorkflowSqlDataSourceView(dataSource.getDataSourceId(),
                dataSource.getDataSourceKey(), dataSource.getDataSourceName(),
                dataSource.getConnectionType(), dataSource.getJdbcUrlRef(),
                dataSource.getUsernameRef(), dataSource.getPasswordRef(),
                List.of(dataSource.getAllowedTables().split(",")),
                dataSource.getConnectTimeoutMs(), dataSource.getQueryTimeoutSeconds(),
                dataSource.getRevisionNo(), dataSource.getStatus(), dataSource.getChecksum(),
                dataSource.getCreateTime(), dataSource.getUpdateTime());
    }

    /**
     * 查询并锁定必须存在的数据源目录。
     * @param dataSourceId Long，目录主键
     * @return WfSqlDataSource，锁定后的实体
     */
    private WfSqlDataSource requireLocked(Long dataSourceId)
    {
        WfSqlDataSource dataSource = dataSourceMapper.selectByIdForUpdate(dataSourceId);
        if (dataSource == null)
        {
            throw new ServiceException("SQL 数据源不存在", HttpStatus.NOT_FOUND);
        }
        return dataSource;
    }

    /**
     * 校验目录主键。
     * @param dataSourceId Long，目录主键
     * @return void，非法时抛出业务异常
     */
    private void requireId(Long dataSourceId)
    {
        if (dataSourceId == null || dataSourceId <= 0)
        {
            throw new ServiceException("SQL 数据源主键不合法", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 去除可选文本首尾空白并把空串转为 null。
     * @param value String，可空文本
     * @return String，规范文本或 null
     */
    private String trimToNull(String value)
    {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 去除必填文本首尾空白并把空值转为空串。
     * @param value String，可空文本
     * @return String，非空规范文本
     */
    private String trimToEmpty(String value)
    {
        return value == null ? "" : value.trim();
    }
}

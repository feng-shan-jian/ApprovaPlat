package com.ruoyi.flowable.extension;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * SQL 连接器的单语句、命名参数和表白名单 AST 校验器。
 */
@Component
public class WorkflowSqlTemplateValidator
{
    /** 单个 SQL 模板 UTF-8 字符上限，避免解析和日志资源失控。 */
    private static final int MAX_SQL_LENGTH = 8192;
    /** 流程变量和 SQL 命名参数共同使用的稳定标识格式。 */
    private static final Pattern PARAMETER_NAME =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    /** 表白名单支持普通标识以及显式 schema.table，不接受动态或引号混淆标识。 */
    private static final Pattern TABLE_NAME =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,127}(\\.[A-Za-z_][A-Za-z0-9_$]{0,127})?");

    /**
     * 解析并校验一条受控 SQL 模板。
     * @param sql String，作者配置中的命名参数 SQL
     * @param allowedTables Set&lt;String&gt;，当前数据源允许访问的表名，大小写不敏感
     * @return WorkflowSqlTemplate，规范 SQL、类型、参数和访问表
     */
    public WorkflowSqlTemplate validate(String sql, Set<String> allowedTables)
    {
        String source = sql == null ? "" : sql.trim();
        if (source.isEmpty() || source.length() > MAX_SQL_LENGTH)
        {
            throw invalid("SQL 模板为空或超过长度限制");
        }
        if (source.contains("--") || source.contains("/*") || source.contains("*/"))
        {
            throw invalid("SQL 模板不允许注释");
        }
        try
        {
            Statements statements = CCJSqlParserUtil.parseStatements(source);
            if (statements.size() != 1)
            {
                throw invalid("SQL 连接器只允许单条语句");
            }
            Statement statement = statements.get(0);
            String operation = requireAllowedOperation(statement);
            ParameterAndTableVisitor visitor = new ParameterAndTableVisitor();
            Set<String> tables = visitor.getTables(statement);
            if (visitor.positionalParameterSeen())
            {
                throw invalid("SQL 模板只允许命名参数，不允许位置参数");
            }
            List<String> parameters = normalizeParameters(visitor.parameterNames());
            if (parameters.isEmpty())
            {
                throw invalid("SQL 模板至少需要一个命名参数");
            }
            List<String> normalizedTables = normalizeTables(tables, allowedTables);
            return new WorkflowSqlTemplate(statement.toString(), operation,
                    List.copyOf(parameters), List.copyOf(normalizedTables));
        }
        catch (JSQLParserException exception)
        {
            throw invalid("SQL 模板无法解析");
        }
    }

    /**
     * 校验外库写模板具有可重放的业务唯一键契约。
     * @param template WorkflowSqlTemplate，已经通过单语句与表白名单校验的模板
     * @param idempotencyColumn String，目标表由唯一约束保护的幂等键列
     * @return void，仅接受写入系统 idempotencyKey 且重复键分支为空操作的 INSERT
     */
    public void requireIdempotentExternalWrite(WorkflowSqlTemplate template,
            String idempotencyColumn)
    {
        String columnName = idempotencyColumn == null ? "" : idempotencyColumn.trim();
        if (!TABLE_NAME.matcher(columnName).matches() || columnName.contains("."))
        {
            throw invalid("SQL 外库幂等唯一列格式不合法");
        }
        if (template == null || !"INSERT".equals(template.operation())
                || !template.parameterNames().contains("idempotencyKey"))
        {
            throw invalid("SQL 外库写入只允许使用 idempotencyKey 的幂等 INSERT 模板");
        }
        try
        {
            Statement statement = CCJSqlParserUtil.parse(template.sql());
            if (!(statement instanceof Insert insert))
            {
                throw invalid("SQL 外库写入只允许幂等 INSERT 模板");
            }
            requireIdempotencyValue(insert, columnName);
            requireNoOpDuplicateUpdate(insert, columnName);
        }
        catch (JSQLParserException exception)
        {
            throw invalid("SQL 外库幂等模板无法解析");
        }
    }

    /**
     * 校验幂等唯一列在 INSERT values 中由系统参数 idempotencyKey 直接赋值。
     * @param insert Insert，外库写入 AST
     * @param idempotencyColumn String，业务唯一列
     * @return void，列缺失、值非命名参数或参数名错误时拒绝
     */
    private void requireIdempotencyValue(Insert insert, String idempotencyColumn)
    {
        if (insert.getColumns() == null || insert.getValues() == null
                || insert.getValues().getExpressions() == null
                || insert.getColumns().size() != insert.getValues().getExpressions().size())
        {
            throw invalid("SQL 外库幂等 INSERT 必须显式列出单行 values");
        }
        for (int index = 0; index < insert.getColumns().size(); index++)
        {
            Column column = insert.getColumns().get(index);
            if (column.getColumnName().equalsIgnoreCase(idempotencyColumn))
            {
                Object value = insert.getValues().getExpressions().get(index);
                if (value instanceof JdbcNamedParameter parameter
                        && "idempotencyKey".equals(parameter.getName()))
                {
                    return;
                }
                throw invalid("SQL 外库幂等唯一列必须直接绑定 :idempotencyKey");
            }
        }
        throw invalid("SQL 外库幂等 INSERT 缺少业务唯一列");
    }

    /**
     * 校验重复键分支只把幂等列赋回自身，禁止计数累加、函数和其他业务字段二次变更。
     * @param insert Insert，外库写入 AST
     * @param idempotencyColumn String，业务唯一列
     * @return void，不是唯一 no-op 更新时拒绝
     */
    private void requireNoOpDuplicateUpdate(Insert insert, String idempotencyColumn)
    {
        List<UpdateSet> updates = insert.getDuplicateUpdateSets();
        if (updates == null || updates.size() != 1)
        {
            throw invalid("SQL 外库幂等 INSERT 必须包含唯一 no-op 重复键分支");
        }
        UpdateSet update = updates.get(0);
        if (update.getColumns() == null || update.getColumns().size() != 1
                || update.getValues() == null || update.getValues().size() != 1)
        {
            throw invalid("SQL 外库重复键分支只能更新幂等唯一列");
        }
        Column target = update.getColumn(0);
        Object value = update.getValue(0);
        if (!(value instanceof Column source)
                || !target.getColumnName().equalsIgnoreCase(idempotencyColumn)
                || !source.getColumnName().equalsIgnoreCase(idempotencyColumn))
        {
            throw invalid("SQL 外库重复键分支必须将幂等唯一列赋回自身");
        }
    }

    /**
     * 限制可执行语句类型，并阻止无 WHERE 的批量修改或删除。
     * @param statement Statement，JSqlParser 单条 AST
     * @return String，稳定操作类型
     */
    private String requireAllowedOperation(Statement statement)
    {
        if (statement instanceof Select)
        {
            return "SELECT";
        }
        if (statement instanceof Insert)
        {
            return "INSERT";
        }
        if (statement instanceof Update update)
        {
            if (update.getWhere() == null)
            {
                throw invalid("UPDATE 必须包含 WHERE 条件");
            }
            return "UPDATE";
        }
        if (statement instanceof Delete delete)
        {
            if (delete.getWhere() == null)
            {
                throw invalid("DELETE 必须包含 WHERE 条件");
            }
            return "DELETE";
        }
        throw invalid("SQL 语句类型未列入白名单");
    }

    /**
     * 规范并校验 AST 中发现的命名参数。
     * @param parameterNames Set&lt;String&gt;，访问器收集的参数名
     * @return List&lt;String&gt;，按字典序冻结的唯一参数名
     */
    private List<String> normalizeParameters(Set<String> parameterNames)
    {
        TreeSet<String> normalized = new TreeSet<>();
        for (String parameterName : parameterNames)
        {
            if (parameterName == null || !PARAMETER_NAME.matcher(parameterName).matches())
            {
                throw invalid("SQL 命名参数格式不合法");
            }
            normalized.add(parameterName);
        }
        return List.copyOf(normalized);
    }

    /**
     * 规范访问表并与数据源白名单逐项比对。
     * @param tables Set&lt;String&gt;，AST 解析得到的访问表
     * @param allowedTables Set&lt;String&gt;，数据源允许表
     * @return List&lt;String&gt;，按字典序冻结的小写表名
     */
    private List<String> normalizeTables(Set<String> tables, Set<String> allowedTables)
    {
        Set<String> allowed = new TreeSet<>();
        for (String table : allowedTables == null ? Set.<String>of() : allowedTables)
        {
            if (table == null || !TABLE_NAME.matcher(table.trim()).matches())
            {
                throw invalid("SQL 数据源表白名单配置不合法");
            }
            allowed.add(table.trim().toLowerCase(Locale.ROOT));
        }
        if (allowed.isEmpty())
        {
            throw invalid("SQL 数据源没有配置允许访问的表");
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String table : tables)
        {
            String normalizedTable = table == null ? "" : table.trim().toLowerCase(Locale.ROOT);
            if (!TABLE_NAME.matcher(normalizedTable).matches() || !allowed.contains(normalizedTable))
            {
                throw invalid("SQL 模板访问了未授权表");
            }
            normalized.add(normalizedTable);
        }
        if (normalized.isEmpty())
        {
            throw invalid("SQL 模板没有可识别的访问表");
        }
        return List.copyOf(normalized);
    }

    /**
     * 创建统一的 SQL 安全校验异常。
     * @param message String，稳定且不回显 SQL 正文的错误消息
     * @return ServiceException，HTTP 400 业务异常
     */
    private ServiceException invalid(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 在 JSqlParser 完整 AST 遍历中同时收集命名参数并识别位置参数。
     */
    private static final class ParameterAndTableVisitor extends TablesNamesFinder<Void>
    {
        /** 已发现的唯一命名参数，保持首次出现顺序供诊断。 */
        private final Set<String> parameterNames = new LinkedHashSet<>();
        /** 是否发现任何问号位置参数。 */
        private boolean positionalParameterSeen;

        /**
         * 收集命名参数并继续执行父类访问逻辑。
         * @param parameter JdbcNamedParameter，当前 AST 参数节点
         * @param context S，JSqlParser 访问上下文
         * @return Void，无返回值
         */
        @Override
        public <S> Void visit(JdbcNamedParameter parameter, S context)
        {
            parameterNames.add(parameter.getName());
            return super.visit(parameter, context);
        }

        /**
         * 标记位置参数并继续执行父类访问逻辑。
         * @param parameter JdbcParameter，当前 AST 问号参数节点
         * @param context S，JSqlParser 访问上下文
         * @return Void，无返回值
         */
        @Override
        public <S> Void visit(JdbcParameter parameter, S context)
        {
            positionalParameterSeen = true;
            return super.visit(parameter, context);
        }

        /**
         * 返回本次 AST 遍历发现的命名参数。
         * @return Set&lt;String&gt;，唯一参数名
         */
        private Set<String> parameterNames()
        {
            return Set.copyOf(parameterNames);
        }

        /**
         * 返回本次 AST 遍历是否发现位置参数。
         * @return boolean，发现问号参数时为 true
         */
        private boolean positionalParameterSeen()
        {
            return positionalParameterSeen;
        }
    }
}

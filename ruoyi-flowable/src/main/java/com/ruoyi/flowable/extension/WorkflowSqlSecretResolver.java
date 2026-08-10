package com.ruoyi.flowable.extension;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * SQL 外部数据源环境引用解析器，数据库和部署快照只保存引用名。
 */
@Component
public class WorkflowSqlSecretResolver
{
    private static final Pattern JDBC_URL_REF =
            Pattern.compile("WORKFLOW_SQL_JDBC_URL_[A-Z0-9_]{1,80}");
    private static final Pattern USERNAME_REF =
            Pattern.compile("WORKFLOW_SQL_USERNAME_[A-Z0-9_]{1,80}");
    private static final Pattern PASSWORD_REF =
            Pattern.compile("WORKFLOW_SQL_PASSWORD_[A-Z0-9_]{1,80}");

    /**
     * 解析外部 JDBC URL。
     * @param reference String，受控环境变量名
     * @return String，运行环境注入的 JDBC URL
     */
    public String requireJdbcUrl(String reference)
    {
        return require(reference, JDBC_URL_REF, 2048, "SQL 外库 JDBC URL");
    }

    /**
     * 解析外部数据库用户名。
     * @param reference String，受控环境变量名
     * @return String，运行环境注入的用户名
     */
    public String requireUsername(String reference)
    {
        return require(reference, USERNAME_REF, 256, "SQL 外库用户名");
    }

    /**
     * 解析外部数据库密码。
     * @param reference String，受控环境变量名
     * @return String，运行环境注入的密码
     */
    public String requirePassword(String reference)
    {
        return require(reference, PASSWORD_REF, 4096, "SQL 外库密码");
    }

    /**
     * 校验引用命名空间并读取非空环境变量，异常信息不包含凭据正文。
     * @param reference String，环境变量名
     * @param pattern Pattern，当前字段允许的固定命名空间
     * @param maxLength int，凭据正文最大长度
     * @param label String，安全错误标签
     * @return String，环境变量正文
     */
    private String require(String reference, Pattern pattern, int maxLength, String label)
    {
        if (reference == null || !pattern.matcher(reference).matches())
        {
            throw new ServiceException(label + "引用不合法", HttpStatus.ERROR);
        }
        String value = System.getenv(reference);
        if (value == null || value.isBlank() || value.length() > maxLength)
        {
            throw new ServiceException(label + "未注入或长度不合法", HttpStatus.ERROR);
        }
        return value;
    }
}

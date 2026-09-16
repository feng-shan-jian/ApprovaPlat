package com.ruoyi.system.exception;

import java.sql.SQLException;
import org.springframework.dao.DuplicateKeyException;

/** 参数键唯一索引的实际写入冲突；其他约束和系统故障仍按原异常传播。 */
public final class ConfigKeyConflictException extends DuplicateKeyException
{
    private ConfigKeyConflictException(DuplicateKeyException cause)
    {
        super("参数键名已存在", cause);
    }

    public static DuplicateKeyException translate(DuplicateKeyException error)
    {
        if (error.getMostSpecificCause() instanceof SQLException sql
                && sql.getErrorCode() == 1062 && "23000".equals(sql.getSQLState())
                && (sql.getMessage().endsWith(" for key 'sys_config.uk_sys_config_key'")
                    || sql.getMessage().endsWith(" for key 'uk_sys_config_key'")))
        {
            return new ConfigKeyConflictException(error);
        }
        return error;
    }
}

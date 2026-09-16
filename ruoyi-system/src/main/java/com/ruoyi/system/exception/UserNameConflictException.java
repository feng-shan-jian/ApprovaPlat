package com.ruoyi.system.exception;

import java.sql.SQLException;
import org.springframework.dao.DuplicateKeyException;

/** 有效账号唯一索引的实际写入冲突，供管理、注册、导入各自保留原有提示。 */
public final class UserNameConflictException extends DuplicateKeyException
{
    private UserNameConflictException(DuplicateKeyException cause)
    {
        super("登录账号已存在", cause);
    }

    public static DuplicateKeyException translate(DuplicateKeyException error)
    {
        if (error.getMostSpecificCause() instanceof SQLException sql
                && sql.getErrorCode() == 1062 && "23000".equals(sql.getSQLState())
                && (sql.getMessage().endsWith(" for key 'sys_user.uk_sys_user_active_name'")
                    || sql.getMessage().endsWith(" for key 'uk_sys_user_active_name'")))
        {
            return new UserNameConflictException(error);
        }
        return error;
    }
}

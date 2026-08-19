package com.ruoyi.common.core.page;

import java.util.List;
import java.util.Objects;

/**
 * 不依赖 Web 层的不可变分页结果。
 *
 * @param rows List&lt;T&gt;，当前页业务数据
 * @param total long，符合查询条件的总记录数
 * @param <T> 当前页业务数据类型
 */
public record PageResult<T>(List<T> rows, long total)
{
    /**
     * 创建分页结果并复制当前页集合，避免调用方修改服务层返回数据。
     *
     * @param rows List&lt;T&gt;，当前页业务数据
     * @param total long，符合查询条件的总记录数
     * @return 无返回值，构造后得到不可变分页结果
     */
    public PageResult
    {
        rows = List.copyOf(Objects.requireNonNull(rows, "分页数据不能为空"));
        if (total < 0)
        {
            throw new IllegalArgumentException("分页总数不能小于零");
        }
    }
}

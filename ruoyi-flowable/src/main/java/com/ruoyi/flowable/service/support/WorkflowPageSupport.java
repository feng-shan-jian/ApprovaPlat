package com.ruoyi.flowable.service.support;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.page.PageResult;
import com.ruoyi.common.exception.ServiceException;

/**
 * 工作流运维查询的统一页边界和总数装配支持。
 */
public final class WorkflowPageSupport
{
    /** 运维页面单页最大记录数，避免绕过前端发起无界读取。 */
    public static final int MAX_PAGE_SIZE = 100;

    private WorkflowPageSupport()
    {
    }

    /**
     * 执行一次先计数、后按偏移读取的物理分页查询。
     *
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数，最大 100
     * @param countSupplier LongSupplier，按相同筛选条件统计总数
     * @param rowLoader BiFunction&lt;Integer, Integer, List&lt;T&gt;&gt;，按 offset、pageSize 读取当前页
     * @param <T> 当前页业务视图类型
     * @return PageResult&lt;T&gt;，包含当前页 rows 和筛选后 total
     */
    public static <T> PageResult<T> query(int pageNum, int pageSize,
            LongSupplier countSupplier, BiFunction<Integer, Integer, List<T>> rowLoader)
    {
        PageWindow page = requirePage(pageNum, pageSize);
        long total = countSupplier.getAsLong();
        if (total == 0 || page.offset() >= total)
        {
            return new PageResult<>(List.of(), total);
        }
        return new PageResult<>(rowLoader.apply(page.offset(), page.pageSize()), total);
    }

    /**
     * 校验时间范围，防止开始时间晚于结束时间导致筛选语义反转。
     *
     * @param beginTime LocalDateTime，允许为空的开始时间
     * @param endTime LocalDateTime，允许为空的结束时间
     * @return void，合法范围不返回业务数据
     */
    public static void requireTimeRange(LocalDateTime beginTime, LocalDateTime endTime)
    {
        if (beginTime != null && endTime != null && beginTime.isAfter(endTime))
        {
            throw new ServiceException("开始时间不能晚于结束时间", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 校验并计算数据库物理分页边界。
     *
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return PageWindow，数据库 offset 和 pageSize
     */
    private static PageWindow requirePage(int pageNum, int pageSize)
    {
        if (pageNum < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE)
        {
            throw new ServiceException("分页参数不合法，pageNum 必须大于 0 且 pageSize 必须处于 1 至 100",
                    HttpStatus.BAD_REQUEST);
        }
        long offset = (long) (pageNum - 1) * pageSize;
        if (offset > Integer.MAX_VALUE)
        {
            throw new ServiceException("页码超出允许范围", HttpStatus.BAD_REQUEST);
        }
        return new PageWindow((int) offset, pageSize);
    }

    /** 数据库物理分页窗口。 */
    private record PageWindow(int offset, int pageSize) { }
}

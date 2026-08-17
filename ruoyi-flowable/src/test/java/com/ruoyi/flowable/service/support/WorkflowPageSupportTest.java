package com.ruoyi.flowable.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;

/**
 * 运维服务端分页边界测试。
 */
class WorkflowPageSupportTest
{
    /**
     * 验证总数、offset 和当前页由同一分页支持装配。
     * @return void，分页计算或 total 传递漂移时失败
     */
    @Test
    void returnsRequestedPageAndTotal()
    {
        AtomicInteger capturedOffset = new AtomicInteger(-1);
        var page = WorkflowPageSupport.query(3, 20, () -> 105L, (offset, pageSize) ->
        {
            capturedOffset.set(offset);
            return List.of("row-41", "row-42");
        });

        assertThat(capturedOffset.get()).isEqualTo(40);
        assertThat(page.total()).isEqualTo(105);
        assertThat(page.rows()).containsExactly("row-41", "row-42");
    }

    /**
     * 验证越过最后一页时不再执行无意义的数据查询。
     * @return void，空页仍访问数据库时失败
     */
    @Test
    void skipsRowQueryWhenOffsetExceedsTotal()
    {
        AtomicInteger rowQueries = new AtomicInteger();
        var page = WorkflowPageSupport.query(7, 20, () -> 105L, (offset, pageSize) ->
        {
            rowQueries.incrementAndGet();
            return List.of("unexpected");
        });

        assertThat(rowQueries).hasValue(0);
        assertThat(page.rows()).isEmpty();
        assertThat(page.total()).isEqualTo(105);
    }

    /**
     * 验证页大小上限和时间范围错误稳定失败关闭。
     * @return void，非法分页或反向时间范围被接受时失败
     */
    @Test
    void rejectsInvalidPageAndTimeRange()
    {
        assertThatThrownBy(() -> WorkflowPageSupport.query(1, 101, () -> 0L,
                (offset, pageSize) -> List.of()))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> WorkflowPageSupport.requireTimeRange(
                LocalDateTime.of(2026, 8, 16, 12, 0),
                LocalDateTime.of(2026, 8, 16, 11, 0)))
                .isInstanceOf(ServiceException.class);
    }
}

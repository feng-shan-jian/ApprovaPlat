package com.ruoyi.flowable.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.domain.dto.WorkflowCopyQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowManualUrgeView;

/**
 * 验证页面使用的工作流 HTTP DTO 与 VO 不再暴露无调用方字段。
 */
class WorkflowHttpProtocolContractTest
{
    /**
     * 验证抄送查询只接收页面真实发送的五个条件。
     *
     * @return void，记录组件新增或缺失时测试失败
     */
    @Test
    void copyQueryContainsOnlyPageFilters()
    {
        assertThat(componentNames(WorkflowCopyQueryDto.class)).containsExactlyInAnyOrder(
                "title", "processName", "originatorName", "categoryId", "readStatus");
    }

    /**
     * 验证人工催办 HTTP 响应只暴露页面读取的真实收件人数。
     *
     * @return void，响应记录重新暴露内部事件键或通道数时测试失败
     */
    @Test
    void manualUrgeResponseContainsOnlyRecipientCount()
    {
        assertThat(componentNames(WorkflowManualUrgeView.class))
                .containsExactlyInAnyOrder("recipientCount");
    }

    /**
     * 读取 Java record 的字段名，用于校验默认 HTTP 绑定与序列化的字段集合。
     *
     * @param recordType Class&lt;?&gt;，待检查的 HTTP record 类型
     * @return String[]，record 组件名称数组
     */
    private String[] componentNames(Class<?> recordType)
    {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toArray(String[]::new);
    }
}

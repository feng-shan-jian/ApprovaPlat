package com.ruoyi.flowable.service.process;

import java.util.Map;

import com.ruoyi.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 表单提交快照唯一正式协议测试，禁止历史版本重新进入运行时读取链。
 */
class WorkflowFormSubmissionSnapshotCodecTest
{
    /**
     * 验证编码结果能够按唯一正式版本无损解码。
     *
     * @return void，正式版本无法无损往返时测试失败
     */
    @Test
    void shouldRoundTripFormalSnapshot()
    {
        assertEquals("__ruoyi_workflow_form_submission_v2",
                WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME);
        String encoded = WorkflowFormSubmissionSnapshotCodec.encodeStart(
                "deployment-1", "TEMPLATE", 7L, "key_7", "start", Map.of("amount", 100));

        WorkflowFormSubmissionSnapshotCodec.SubmissionSnapshot snapshot =
                WorkflowFormSubmissionSnapshotCodec.decode(encoded);

        assertEquals("TEMPLATE", snapshot.sourceType());
        assertEquals(7L, snapshot.formId());
        assertEquals(100, snapshot.values().get("amount").intValue());
    }

    /**
     * 验证缺少 sourceType 的旧版本快照被明确拒绝，不再推断为模板来源。
     *
     * @return void，旧版本或缺失来源仍被运行时接受时测试失败
     */
    @Test
    void shouldRejectLegacyVersionOneSnapshot()
    {
        String legacy = """
                {"version":1,"kind":"START","deploymentId":"deployment-1",\
                "formId":7,"formKey":"key_7","nodeKey":"start","taskId":null,\
                "taskLocal":false,"values":{"amount":100}}
                """;

        ServiceException exception = assertThrows(ServiceException.class,
                () -> WorkflowFormSubmissionSnapshotCodec.decode(legacy));

        assertEquals("工作流表单提交快照版本不受支持", exception.getMessage());
    }
}

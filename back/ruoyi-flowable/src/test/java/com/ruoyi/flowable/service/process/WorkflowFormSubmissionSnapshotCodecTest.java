package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

class WorkflowFormSubmissionSnapshotCodecTest
{
    /**
     * 验证快照编码和解码都会拒绝顶层表单字段中的原型污染键。
     *
     * @return 无返回值，危险字段能进入持久化快照或历史回显时测试失败
     */
    @Test
    void rejectsPrototypePollutionKeysInTopLevelValues()
    {
        for (String forbiddenKey : java.util.List.of("__proto__", "prototype", "constructor"))
        {
            assertDataError(() -> WorkflowFormSubmissionSnapshotCodec.encodeStart(
                    "deployment-1", 1L, "key_1", "start", Map.of(forbiddenKey, "x")));
        }

        String forged = """
                {"version":1,"kind":"START","deploymentId":"deployment-1",\
                "formId":1,"formKey":"key_1","nodeKey":"start","taskId":null,\
                "taskLocal":false,"values":{"__proto__":{"polluted":true}}}
                """;
        assertDataError(() -> WorkflowFormSubmissionSnapshotCodec.decode(forged));
    }

    /**
     * 验证合法快照根节点后追加第二个 JSON token 会按持久化数据损坏处理。
     *
     * @return 无返回值，尾随 JSON 被静默忽略时测试失败
     */
    @Test
    void rejectsTrailingJsonTokens()
    {
        String encoded = WorkflowFormSubmissionSnapshotCodec.encodeStart(
                "deployment-1", 1L, "key_1", "start", Map.of("reason", "采购"));

        assertDataError(() -> WorkflowFormSubmissionSnapshotCodec.decode(encoded + " {}"));
    }

    /**
     * 验证 values 访问器每次返回深复制节点，调用方修改不会污染快照内部状态。
     *
     * @return 无返回值，外部 JsonNode 修改可影响后续读取时测试失败
     */
    @Test
    void returnsDefensiveJsonNodeCopies()
    {
        String encoded = WorkflowFormSubmissionSnapshotCodec.encodeStart(
                "deployment-1", 1L, "key_1", "start",
                Map.of("payload", Map.of("status", "original")));
        WorkflowFormSubmissionSnapshotCodec.SubmissionSnapshot snapshot =
                WorkflowFormSubmissionSnapshotCodec.decode(encoded);

        ObjectNode exposed = (ObjectNode) snapshot.values().get("payload");
        exposed.put("status", "changed");
        exposed.put("injected", true);

        JsonNode reread = snapshot.values().get("payload");
        assertThat(reread.path("status").textValue()).isEqualTo("original");
        assertThat(reread.has("injected")).isFalse();
        assertThatThrownBy(() -> snapshot.values().put("extra", reread))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * 断言非法快照始终映射为稳定的服务端数据一致性错误。
     *
     * @param action ThrowingCallable，预计抛出快照数据异常的操作
     * @return 无返回值，状态码或异常类型不符合契约时测试失败
     */
    private void assertDataError(ThrowingCallable action)
    {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
            assertThat(exception.getMessage()).contains("工作流表单提交快照");
        });
    }
}

package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.service.model.WorkflowFormSourceType;

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
     * 验证版本 2 可编码内嵌表单空 formId，同时仍可读取升级前版本 1 模板快照。
     * @return 无返回值，双来源组合或历史兼容解码错误时测试失败
     */
    @Test
    void supportsEmbeddedSourceAndDecodesLegacyTemplateSnapshot()
    {
        String embedded = WorkflowFormSubmissionSnapshotCodec.encodeStart(
                "deployment-2", WorkflowFormSourceType.EMBEDDED.name(), null,
                WorkflowFormSourceType.EMBEDDED_FORM_KEY, "start", Map.of("reason", "采购"));
        WorkflowFormSubmissionSnapshotCodec.SubmissionSnapshot embeddedSnapshot =
                WorkflowFormSubmissionSnapshotCodec.decode(embedded);
        assertThat(embeddedSnapshot.sourceType()).isEqualTo("EMBEDDED");
        assertThat(embeddedSnapshot.formId()).isNull();

        String legacy = """
                {"version":1,"kind":"START","deploymentId":"deployment-1",\
                "formId":1,"formKey":"key_1","nodeKey":"start","taskId":null,\
                "taskLocal":false,"values":{"reason":"采购"}}
                """;
        WorkflowFormSubmissionSnapshotCodec.SubmissionSnapshot legacySnapshot =
                WorkflowFormSubmissionSnapshotCodec.decode(legacy);
        assertThat(legacySnapshot.sourceType()).isEqualTo("TEMPLATE");
        assertThat(legacySnapshot.formId()).isEqualTo(1L);

        assertDataError(() -> WorkflowFormSubmissionSnapshotCodec.encodeStart(
                "deployment-2", WorkflowFormSourceType.EMBEDDED.name(), 1L,
                WorkflowFormSourceType.EMBEDDED_FORM_KEY, "start", Map.of()));
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

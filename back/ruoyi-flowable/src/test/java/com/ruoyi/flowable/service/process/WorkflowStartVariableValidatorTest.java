package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;

class WorkflowStartVariableValidatorTest
{
    private static final String START_FORM = """
            {
              "fields": [
                {
                  "__config__": {"layout":"colFormItem","tag":"el-input","required":true},
                  "__vModel__":"reason","maxlength":10
                },
                {
                  "__config__": {"layout":"colFormItem","tag":"el-input-number","required":true},
                  "__vModel__":"amount","min":0,"max":10000
                },
                {
                  "__config__": {"layout":"colFormItem","tag":"el-checkbox-group","required":false},
                  "__vModel__":"tags","min":0,"max":2
                },
                {
                  "__config__": {"layout":"colFormItem","tag":"el-upload","required":false},
                  "__vModel__":"files","limit":2
                },
                {
                  "__config__": {"layout":"colFormItem","tag":"el-table","required":false},
                  "__vModel__":"items"
                }
              ]
            }
            """;

    private static final String PERMISSION_FORM = """
            {
              "fields": [
                {"__vModel__":"hiddenField","__config__":{"layout":"colFormItem",
                 "tag":"el-input","workflowHidden":true,"workflowReadable":false,
                 "workflowWritable":false,"required":false}},
                {"__vModel__":"readonlyField","__config__":{"layout":"colFormItem",
                 "tag":"el-input","workflowHidden":false,"workflowReadable":true,
                 "workflowWritable":false,"required":false}},
                {"__vModel__":"editableField","__config__":{"layout":"colFormItem",
                 "tag":"el-input-number","workflowHidden":false,"workflowReadable":true,
                 "workflowWritable":true,"required":false}},
                {"__vModel__":"requiredField","__config__":{"layout":"colFormItem",
                 "tag":"el-input","workflowHidden":false,"workflowReadable":true,
                 "workflowWritable":true,"required":true}}
              ]
            }
            """;

    private WorkflowStartVariableValidator validator;

    /**
     * 为每个测试创建无共享状态的开始变量验证器。
     *
     * @return void，无返回值
     */
    @BeforeEach
    void setUp()
    {
        validator = new WorkflowStartVariableValidator(new WorkflowFormTemplateValidator());
    }

    /**
     * 验证草稿可以缺少正式必填字段，但已提供字段仍执行与正式提交一致的类型门禁。
     *
     * @return void，草稿必填放宽或类型安全边界漂移时测试失败
     */
    @Test
    void draftSkipsRequiredFieldsButKeepsTypeValidation()
    {
        WorkflowValidatedStartVariables partial = validator.validateForDraft(
                START_FORM, Map.of("reason", "采购"));

        assertThat(partial.variables()).containsExactlyEntriesOf(Map.of("reason", "采购"));
        assertBadRequest(() -> validator.validateForDraft(START_FORM,
                Map.of("amount", "不是数字")), "流程变量类型不合法: amount");
        assertBadRequest(() -> validator.validateForStart(START_FORM,
                Map.of("reason", "采购")), "开始表单必填字段不能为空: amount");
    }

    /**
     * 验证草稿和正式提交复用节点字段权限，隐藏及只读字段均不能被客户端写入。
     *
     * @return void，草稿若绕过 workflowWritable 白名单则测试失败
     */
    @Test
    void draftKeepsHiddenAndReadonlyFieldPermissions()
    {
        String permissionSnapshot = """
                {"fields":[
                  {"__config__":{"layout":"colFormItem","tag":"el-input",
                    "workflowHidden":true,"workflowReadable":false,"workflowWritable":false},
                   "__vModel__":"hiddenValue"},
                  {"__config__":{"layout":"colFormItem","tag":"el-input",
                    "workflowHidden":false,"workflowReadable":true,"workflowWritable":false},
                   "__vModel__":"readonlyValue"},
                  {"__config__":{"layout":"colFormItem","tag":"el-input",
                    "workflowWritable":true},"__vModel__":"editableValue"}
                ]}
                """;

        assertThat(validator.validateForDraft(permissionSnapshot,
                Map.of("editableValue", "draft")).variables())
                .containsExactlyEntriesOf(Map.of("editableValue", "draft"));
        assertBadRequest(() -> validator.validateForDraft(permissionSnapshot,
                Map.of("hiddenValue", "forged")), "流程变量字段为只读字段: hiddenValue");
        assertBadRequest(() -> validator.validateForDraft(permissionSnapshot,
                Map.of("readonlyValue", "forged")), "流程变量字段为只读字段: readonlyValue");
    }

    /**
     * 验证合法字段、类型和集合可通过，并返回不受原请求后续修改影响的深度副本。
     *
     * @return void，校验或不可变性不符合契约时测试失败
     */
    @Test
    void acceptsSchemaBoundVariablesAndReturnsDeepImmutableCopy()
    {
        List<String> tags = new ArrayList<>(List.of("finance"));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("materialCode", "M-91");
        item.put("quantity", 2);
        List<Map<String, Object>> items = new ArrayList<>(List.of(item));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("reason", "采购");
        source.put("amount", 1280);
        source.put("tags", tags);
        source.put("files", List.of());
        source.put("items", items);

        Map<String, Object> result = validator.validateAndNormalize(START_FORM, source);

        tags.add("changed");
        item.put("quantity", 999);
        assertThat(result.get("tags")).isEqualTo(List.of("finance"));
        assertThat(result.get("files")).isEqualTo(List.of());
        assertThat(result.get("items")).isEqualTo(List.of(
                Map.of("materialCode", "M-91", "quantity", 2)));
        assertThatThrownBy(() -> result.put("extra", true))
                .isInstanceOf(UnsupportedOperationException.class);
        @SuppressWarnings("unchecked")
        List<Object> immutableTags = (List<Object>) result.get("tags");
        assertThatThrownBy(() -> immutableTags.add("changed"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * 验证上传组件拒绝未绑定正式附件归属的 fileList/url，同时普通表格对象列表仍可提交。
     *
     * @return void，任意非空上传数据可进入流程变量或表格字段被误拒绝时测试失败
     */
    @Test
    void rejectsUnboundUploadValuesWithoutBlockingTableRows()
    {
        Map<String, Object> requiredValues = Map.of("reason", "采购", "amount", 100);
        Map<String, Object> fileListVariables = new LinkedHashMap<>(requiredValues);
        fileListVariables.put("files", List.of(Map.of(
                "name", "invoice.pdf", "url", "/profile/upload/invoice.pdf")));
        assertBadRequest(() -> validator.validateAndNormalize(START_FORM, fileListVariables),
                "上传字段只能提交附件标识: files");

        Map<String, Object> urlVariables = new LinkedHashMap<>(requiredValues);
        urlVariables.put("files", "/profile/upload/invoice.pdf");
        assertBadRequest(() -> validator.validateAndNormalize(START_FORM, urlVariables),
                "流程变量类型不合法: files");

        Map<String, Object> tableVariables = new LinkedHashMap<>(requiredValues);
        tableVariables.put("files", List.of());
        tableVariables.put("items", List.of(Map.of("materialCode", "M-91", "quantity", 2)));
        assertThat(validator.validateAndNormalize(START_FORM, tableVariables).get("items"))
                .isEqualTo(List.of(Map.of("materialCode", "M-91", "quantity", 2)));
    }

    /**
     * 验证上传字段只接受规范附件 UUID，并按表单字段提取不可变绑定白名单。
     * @return void，UUID 规范化、分组或不可变契约不符合时测试失败
     */
    @Test
    void extractsOnlyCanonicalAttachmentIdsForStartBinding()
    {
        String attachmentId = "d9428888-122b-4c6f-8f0c-9c3e1dbd3210";
        WorkflowValidatedStartVariables result = validator.validateForStart(START_FORM,
                Map.of("reason", "采购", "amount", 100,
                        "files", List.of(attachmentId.toUpperCase())));

        assertThat(result.variables().get("files")).isEqualTo(List.of(attachmentId));
        assertThat(result.attachmentIdsByField())
                .containsExactlyEntriesOf(Map.of("files", List.of(attachmentId)));
        assertThatThrownBy(() -> result.attachmentIdsByField().put("other", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertBadRequest(() -> validator.validateAndNormalize(START_FORM,
                Map.of("reason", "采购", "amount", 100,
                        "files", List.of(attachmentId, attachmentId))),
                "上传字段不能重复引用同一附件: files");
        assertBadRequest(() -> validator.validateAndNormalize(START_FORM,
                Map.of("reason", "采购", "amount", 100,
                        "files", List.of("/profile/workflow-attachments/a.pdf"))),
                "上传字段包含非法附件标识: files");
    }

    /**
     * 验证表单白名单外字段和服务端保留变量均被明确拒绝。
     *
     * @return void，任一字段能绕过白名单时测试失败
     */
    @Test
    void rejectsUnknownAndReservedVariables()
    {
        assertBadRequest(() -> validator.validateAndNormalize(START_FORM,
                Map.of("reason", "采购", "amount", 100, "unknown", true)),
                "流程变量字段不在开始表单中: unknown");
        assertBadRequest(() -> validator.validateAndNormalize(START_FORM,
                Map.of("reason", "采购", "amount", 100, "initiator", "999")),
                "客户端不能覆盖服务端保留流程变量");
        assertBadRequest(() -> validator.validateAndNormalize(START_FORM,
                Map.of("reason", "采购", "amount", 100, "processStatus", "completed")),
                "客户端不能覆盖服务端保留流程变量");
        assertBadRequest(() -> validator.validateAndNormalize(START_FORM,
                Map.of("reason", "采购", "amount", 100,
                        WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME, "forged")),
                "客户端不能覆盖服务端保留流程变量");
        assertBadRequest(() -> validator.validateAndNormalize(START_FORM,
                Map.of("reason", "采购", "amount", 100,
                        "wfMiUsers_approveTask", List.of(1L, 2L))),
                "客户端不能覆盖服务端保留流程变量");
        assertBadRequest(() -> validator.validateAndNormalize(START_FORM,
                Map.of("reason", "采购", "amount", 100,
                        "_wfMiRevision_approveTask", 99)),
                "客户端不能覆盖服务端保留流程变量");
        assertBadRequest(() -> validator.validateAndNormalize(START_FORM,
                Map.of("reason", "采购", "amount", 100,
                        "nrOfActiveInstances", 99)),
                "客户端不能覆盖服务端保留流程变量");
        assertBadRequest(() -> validator.validateAndNormalize(START_FORM,
                Map.of("reason", "采购", "amount", 100, "loopCounter", 99)),
                "客户端不能覆盖服务端保留流程变量");
    }

    /**
     * 验证顶层变量和表格嵌套对象均拒绝可能触发前端原型污染的字段名。
     *
     * @return void，任一危险键进入规范变量映射时测试失败
     */
    @Test
    void rejectsPrototypePollutionKeysAtEveryLevel()
    {
        for (String forbiddenKey : List.of("__proto__", "prototype", "constructor"))
        {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("reason", "采购");
            variables.put("amount", 100);
            variables.put(forbiddenKey, "polluted");
            assertBadRequest(() -> validator.validateAndNormalize(START_FORM, variables),
                    "流程变量字段名不合法");
        }

        assertBadRequest(() -> validator.validateAndNormalize(START_FORM,
                Map.of("reason", "采购", "amount", 100,
                        "items", List.of(Map.of("__proto__", "polluted")))),
                "流程变量对象字段名不合法");
    }

    /**
     * 验证缺失必填字段、字段类型错误和 schema 文本长度均返回稳定 400。
     *
     * @return void，任一非法字段能通过时测试失败
     */
    @Test
    void rejectsMissingWrongTypeAndOversizedText()
    {
        assertBadRequest(() -> validator.validateAndNormalize(START_FORM,
                Map.of("reason", "采购")), "开始表单必填字段不能为空: amount");
        assertBadRequest(() -> validator.validateAndNormalize(START_FORM,
                Map.of("reason", "采购", "amount", "100")),
                "流程变量类型不合法: amount");
        assertBadRequest(() -> validator.validateAndNormalize(START_FORM,
                Map.of("reason", "x".repeat(11), "amount", 100)),
                "流程变量文本长度不合法: reason");
        assertBadRequest(() -> validator.validateAndNormalize(START_FORM,
                Map.of("reason", "采购", "amount", 10001)),
                "流程变量数值范围不合法: amount");
    }

    /**
     * 验证客户端抓包写入隐藏或只读字段会被服务端拒绝，可写字段仍执行类型和必填规则。
     *
     * @return void，节点写权限或当前 schema 规则可被绕过时测试失败
     */
    @Test
    void enforcesHiddenReadonlyWritableAndRequiredPermissions()
    {
        assertBadRequest(() -> validator.validateForStart(PERMISSION_FORM,
                Map.of("hiddenField", "伪造", "requiredField", "已填")),
                "流程变量字段为只读字段: hiddenField");
        assertBadRequest(() -> validator.validateForStart(PERMISSION_FORM,
                Map.of("readonlyField", "篡改", "requiredField", "已填")),
                "流程变量字段为只读字段: readonlyField");
        assertBadRequest(() -> validator.validateForStart(PERMISSION_FORM,
                Map.of("editableField", "不是数字", "requiredField", "已填")),
                "流程变量类型不合法: editableField");
        assertBadRequest(() -> validator.validateForStart(PERMISSION_FORM,
                Map.of("editableField", 12)),
                "开始表单必填字段不能为空: requiredField");

        assertThat(validator.validateForStart(PERMISSION_FORM,
                Map.of("editableField", 12, "requiredField", "已填")).variables())
                .containsExactlyEntriesOf(Map.of("editableField", 12, "requiredField", "已填"));
    }

    /**
     * 验证草稿与正式动作复用同一字段策略，仅草稿允许必填字段暂时缺失或为空。
     *
     * @return void，草稿绕过字段权限/类型或正式提交未执行必填规则时测试失败
     */
    @Test
    void validatesDraftWithSharedPolicyButWithoutRequiredCompleteness()
    {
        assertThat(validator.validateForDraft(PERMISSION_FORM,
                Map.of("editableField", 12)).variables())
                .containsExactlyEntriesOf(Map.of("editableField", 12));
        Map<String, Object> emptyRequiredDraft = new LinkedHashMap<>();
        emptyRequiredDraft.put("requiredField", null);
        assertThat(validator.validateForDraft(PERMISSION_FORM,
                emptyRequiredDraft).variables()).containsEntry("requiredField", null);

        assertBadRequest(() -> validator.validateForDraft(PERMISSION_FORM,
                Map.of("hiddenField", "伪造")),
                "流程变量字段为只读字段: hiddenField");
        assertBadRequest(() -> validator.validateForDraft(PERMISSION_FORM,
                Map.of("readonlyField", "篡改")),
                "流程变量字段为只读字段: readonlyField");
        assertBadRequest(() -> validator.validateForDraft(PERMISSION_FORM,
                Map.of("editableField", "不是数字")),
                "流程变量类型不合法: editableField");
        assertBadRequest(() -> validator.validateForDraft(PERMISSION_FORM,
                Map.of("unknownField", "越权")),
                "流程变量字段不在开始表单中: unknownField");
        assertBadRequest(() -> validator.validateForStart(PERMISSION_FORM,
                Map.of("editableField", 12)),
                "开始表单必填字段不能为空: requiredField");
    }

    /**
     * 验证退回重提补丁只校验明确提交字段，省略必填字段沿用正式旧值但显式空值仍被拒绝。
     *
     * @return void，省略字段被清空或显式空值绕过必填时测试失败
     */
    @Test
    void validatesPatchWithoutOverwritingOmittedValues()
    {
        Map<String, JsonNode> previous = Map.of(
                "requiredField", JsonMapper.shared().getNodeFactory().textNode("原值"),
                "readonlyField", JsonMapper.shared().getNodeFactory().textNode("只读值"));

        WorkflowValidatedStartVariables patch = validator.validatePatch(
                PERMISSION_FORM, Map.of("editableField", 18), previous);

        assertThat(patch.variables()).containsExactlyEntriesOf(Map.of("editableField", 18));
        assertBadRequest(() -> validator.validatePatch(PERMISSION_FORM,
                java.util.Collections.singletonMap("requiredField", null), previous),
                "开始表单必填字段不能为空: requiredField");
    }

    /**
     * 验证组件集合限制和全请求 1 MiB 上限同时生效。
     *
     * @return void，集合或总量超限未被拒绝时测试失败
     */
    @Test
    void rejectsCollectionAndOverallPayloadLimits()
    {
        assertBadRequest(() -> validator.validateAndNormalize(START_FORM,
                Map.of("reason", "采购", "amount", 100,
                        "tags", List.of("a", "b", "c"))),
                "流程变量集合大小不合法: tags");

        String largeTemplate = richTextTemplate(17);
        Map<String, Object> largeVariables = new LinkedHashMap<>();
        for (int index = 0; index < 17; index++)
        {
            largeVariables.put("note" + index,
                    "x".repeat(WorkflowStartVariableValidator.MAX_STRING_LENGTH));
        }
        assertBadRequest(() -> validator.validateAndNormalize(largeTemplate, largeVariables),
                "流程变量总大小不能超过1 MiB");
    }

    /**
     * 验证损坏、重复字段或声明保留变量的数据库快照按服务端数据异常处理。
     *
     * @return void，损坏快照被误报为客户端参数错误时测试失败
     */
    @Test
    void rejectsInvalidPersistedSnapshotAsServerError()
    {
        assertServerSnapshotError(() -> validator.validateAndNormalize("not-json", Map.of()));
        assertServerSnapshotError(() -> validator.validateAndNormalize("""
                {"fields":[
                  {"__config__":{"layout":"colFormItem","tag":"el-input"},"__vModel__":"same"},
                  {"__config__":{"layout":"colFormItem","tag":"el-input"},"__vModel__":"same"}
                ]}
                """, Map.of()));
        assertServerSnapshotError(() -> validator.validateAndNormalize("""
                {"fields":[
                  {"__config__":{"layout":"colFormItem","tag":"el-input"},"__vModel__":"initiator"}
                ]}
                """, Map.of()));
        assertServerSnapshotError(() -> validator.validateAndNormalize("""
                {"fields":[
                  {"__config__":{"layout":"colFormItem","tag":"el-input"},
                   "__vModel__":"__ruoyi_workflow_forged"}
                ]}
                """, Map.of()));
        assertServerSnapshotError(() -> validator.validateAndNormalize("""
                {"fields":[
                  {"__config__":{"layout":"colFormItem","tag":"el-input"},
                   "__vModel__":"constructor"}
                ]}
                """, Map.of()));
    }

    /**
     * 生成包含多个富文本字段的合法表单快照，用于总负载边界测试。
     *
     * @param fieldCount int，待生成字段数量
     * @return String，字段名依次为 note0..noteN 的表单 JSON
     */
    private String richTextTemplate(int fieldCount)
    {
        StringBuilder json = new StringBuilder("{\"fields\":[");
        for (int index = 0; index < fieldCount; index++)
        {
            if (index > 0)
            {
                json.append(',');
            }
            json.append("{\"__config__\":{\"layout\":\"colFormItem\",\"tag\":\"tinymce\"},")
                    .append("\"__vModel__\":\"note").append(index).append("\"}");
        }
        return json.append("]}").toString();
    }

    /**
     * 断言客户端变量错误的状态码和消息。
     *
     * @param action ThrowingCallable，预计失败的校验操作
     * @param expectedMessage String，预期稳定错误提示
     * @return void，异常契约不符时测试失败
     */
    private void assertBadRequest(ThrowingCallable action, String expectedMessage)
    {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getMessage()).isEqualTo(expectedMessage);
        });
    }

    /**
     * 断言持久化快照异常不会被归因于客户端变量。
     *
     * @param action ThrowingCallable，预计失败的快照校验操作
     * @return void，异常状态或提示不符时测试失败
     */
    private void assertServerSnapshotError(ThrowingCallable action)
    {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
            assertThat(exception.getMessage()).isEqualTo("流程部署表单快照结构异常");
        });
    }
}

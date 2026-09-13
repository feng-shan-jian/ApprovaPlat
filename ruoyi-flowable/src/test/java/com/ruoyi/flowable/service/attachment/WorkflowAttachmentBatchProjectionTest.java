package com.ruoyi.flowable.service.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.domain.WfAttachment;
import com.ruoyi.flowable.domain.WorkflowAttachmentStatus;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;

/**
 * 详情附件批量授权与安全元数据投影契约测试。
 */
class WorkflowAttachmentBatchProjectionTest
{
    /**
     * 验证多个字段附件只执行一次批量查询，并且响应不暴露私有对象键。
     *
     * @return void，出现逐附件查询、对象关联漂移或内部字段泄露时测试失败
     */
    @Test
    void projectsAllAttachmentsWithOneBatchQuery()
    {
        String firstId = "11111111-1111-4111-8111-111111111111";
        String secondId = "22222222-2222-4222-8222-222222222222";
        String processInstanceId = "process-1";
        WfAttachmentMapper mapper = mock(WfAttachmentMapper.class);
        WorkflowProcessAccessService accessService = mock(WorkflowProcessAccessService.class);
        WorkflowAttachmentService service = new WorkflowAttachmentService(mapper,
                mock(WorkflowAttachmentStorage.class), new WorkflowAttachmentProperties(),
                mock(WorkflowIdentityResolver.class), accessService,
                mock(WorkflowAttachmentUploadJournal.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        when(mapper.selectByIds(anyCollection())).thenReturn(List.of(
                boundAttachment(firstId, "contracts", processInstanceId),
                boundAttachment(secondId, "evidence", processInstanceId)));
        Map<String, String> expectedFields = new LinkedHashMap<>();
        expectedFields.put(firstId, "contracts");
        expectedFields.put(secondId, "evidence");

        Map<String, JsonNode> result = service.projectReadableBoundMetadata(
                processInstanceId, expectedFields);

        assertThat(result).containsOnlyKeys(firstId, secondId);
        assertThat(result.get(firstId).path("fieldName").asText()).isEqualTo("contracts");
        assertThat(result.get(secondId).path("fieldName").asText()).isEqualTo("evidence");
        assertThat(result.values()).allSatisfy(node ->
                assertThat(node.has("storageKey")).isFalse());
        verify(accessService, times(1)).requireReadableInstance(processInstanceId);
        verify(mapper, times(1)).selectByIds(anyCollection());
    }

    /**
     * 验证多个合法提交累计超过 100 个附件时按查询批次投影，而不是套用单次提交上限。
     *
     * @return void，历史累计数量被误拒绝或查询未按 100 条分批时测试失败
     */
    @Test
    void projectsMoreThanOneSubmissionLimitAcrossHistory()
    {
        String processInstanceId = "process-many";
        WfAttachmentMapper mapper = mock(WfAttachmentMapper.class);
        WorkflowProcessAccessService accessService = mock(WorkflowProcessAccessService.class);
        WorkflowAttachmentService service = new WorkflowAttachmentService(mapper,
                mock(WorkflowAttachmentStorage.class), new WorkflowAttachmentProperties(),
                mock(WorkflowIdentityResolver.class), accessService,
                mock(WorkflowAttachmentUploadJournal.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        Map<String, String> expectedFields = new LinkedHashMap<>();
        List<WfAttachment> firstBatch = new ArrayList<>();
        List<WfAttachment> secondBatch = new ArrayList<>();
        for (int index = 1; index <= 101; index++)
        {
            String attachmentId = String.format("00000000-0000-4000-8000-%012d", index);
            expectedFields.put(attachmentId, "evidence");
            (index <= 100 ? firstBatch : secondBatch).add(
                    boundAttachment(attachmentId, "evidence", processInstanceId));
        }
        when(mapper.selectByIds(anyCollection())).thenReturn(firstBatch, secondBatch);

        Map<String, JsonNode> result = service.projectReadableBoundMetadata(
                processInstanceId, expectedFields);

        assertThat(result).hasSize(101);
        verify(accessService, times(1)).requireReadableInstance(processInstanceId);
        verify(mapper, times(2)).selectByIds(anyCollection());
    }

    /**
     * 生成已经绑定到指定流程和字段的完整附件元数据。
     *
     * @param attachmentId String，规范附件 UUID
     * @param fieldName String，正式表单字段名
     * @param processInstanceId String，真实流程实例主键
     * @return WfAttachment，可参与详情安全投影的 BOUND 元数据
     */
    private WfAttachment boundAttachment(String attachmentId, String fieldName,
            String processInstanceId)
    {
        LocalDateTime now = LocalDateTime.now();
        return new WfAttachment(attachmentId, 100L, fieldName, fieldName + ".pdf",
                "private/" + attachmentId, "application/pdf", 128L, "a".repeat(64),
                WorkflowAttachmentStatus.BOUND, null, null, processInstanceId,
                "task-1", "review", now, null, 0, null, null, null, null,
                now.minusMinutes(1), now);
    }
}

package com.ruoyi.flowable.service.attachment;

import java.util.Collection;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Test-only boundary for a shared database's upload recovery journal. */
final class WorkflowAttachmentMysqlTestGuard
{
    private WorkflowAttachmentMysqlTestGuard() { }

    static void assertEmptyUploadJournal(JdbcTemplate jdbc)
    {
        if (!Long.valueOf(0L).equals(jdbc.queryForObject("select count(*) from wf_attachment_upload", Long.class)))
        {
            throw new IllegalStateException("Shared database has existing wf_attachment_upload records; existing data is preserved");
        }
    }

    static WfAttachmentMapper protectUploadCleanup(WfAttachmentMapper delegate, Collection<String> ownedKeys)
    {
        var guarded = mock(WfAttachmentMapper.class, delegatesTo(delegate));
        doAnswer(call -> {
            // Keep the real locking SELECT; validate the whole batch before the first claim.
            var candidates = delegate.selectUploadsForUpdate(call.getArgument(0));
            if (candidates.stream().anyMatch(upload -> !ownedKeys.contains(upload.storageKey())))
            {
                throw new IllegalStateException("Upload cleanup contains records from another run; existing data is preserved");
            }
            return candidates;
        }).when(guarded).selectUploadsForUpdate(anyInt());
        return guarded;
    }
}

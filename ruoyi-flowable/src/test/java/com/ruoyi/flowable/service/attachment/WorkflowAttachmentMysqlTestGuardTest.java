package com.ruoyi.flowable.service.attachment;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.domain.WfAttachmentUpload;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowAttachmentMysqlTestGuardTest
{
    private static final String COUNT = "select count(*) from wf_attachment_upload";

    @Test
    void refusesExistingJournalBeforeAnyWrite()
    {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(COUNT, Long.class)).thenReturn(1L);

        assertThatThrownBy(() -> WorkflowAttachmentMysqlTestGuard.assertEmptyUploadJournal(jdbc))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("wf_attachment_upload");

        verify(jdbc).queryForObject(COUNT, Long.class);
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void allowsAnEmptyJournalUsingOnlyARead()
    {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(COUNT, Long.class)).thenReturn(0L);

        WorkflowAttachmentMysqlTestGuard.assertEmptyUploadJournal(jdbc);

        verify(jdbc).queryForObject(COUNT, Long.class);
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void refusesAnUnknownCount()
    {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertThatThrownBy(() -> WorkflowAttachmentMysqlTestGuard.assertEmptyUploadJournal(jdbc))
                .isInstanceOf(IllegalStateException.class);
        verify(jdbc).queryForObject(COUNT, Long.class);
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void foreignReservationArrivingAfterPreflightRemainsUntouched()
    {
        var existing = new WfAttachmentUpload("existing-business-reservation", 2);
        var owned = new WfAttachmentUpload("this-test-reservation", 0);
        for (List<WfAttachmentUpload> candidates : List.of(List.of(existing), List.of(owned, existing)))
        {
            WfAttachmentMapper delegate = mock(WfAttachmentMapper.class);
            when(delegate.selectUploadsForUpdate(anyInt())).thenReturn(candidates);
            when(delegate.claimUpload(anyString(), anyString(), anyLong())).thenReturn(1);
            when(delegate.completeUploadCleanup(anyString(), anyString())).thenReturn(1);
            WorkflowAttachmentStorage storage = mock(WorkflowAttachmentStorage.class);
            var guarded = WorkflowAttachmentMysqlTestGuard.protectUploadCleanup(delegate, List.of(owned.storageKey()));

            assertThatThrownBy(() -> journal(guarded, storage).cleanupBatch())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("another run");

            verify(delegate).selectUploadsForUpdate(anyInt());
            verifyNoMoreInteractions(delegate);
            verifyNoInteractions(storage);
            assertThat(existing).isEqualTo(new WfAttachmentUpload("existing-business-reservation", 2));
        }
    }

    @Test
    void cleansOnlyAReservationOwnedByThisTest()
    {
        String key = "this-test-reservation";
        WfAttachmentMapper delegate = mock(WfAttachmentMapper.class);
        when(delegate.selectUploadsForUpdate(anyInt())).thenReturn(List.of(new WfAttachmentUpload(key, 0)));
        when(delegate.claimUpload(eq(key), anyString(), anyLong())).thenReturn(1);
        when(delegate.completeUploadCleanup(eq(key), anyString())).thenReturn(1);
        WorkflowAttachmentStorage storage = mock(WorkflowAttachmentStorage.class);
        var ownedKeys = new ArrayList<String>();
        var guarded = WorkflowAttachmentMysqlTestGuard.protectUploadCleanup(delegate, ownedKeys);
        // Successful reservations are tracked after the fixture creates its mapper.
        ownedKeys.add(key);

        assertThat(journal(guarded, storage).cleanupBatch().cleaned()).isOne();

        verify(delegate).selectUploadsForUpdate(anyInt());
        verify(delegate).claimUpload(eq(key), anyString(), anyLong());
        verify(delegate).existsByStorageKey(key);
        verify(delegate).completeUploadCleanup(eq(key), anyString());
        verify(storage).deleteUncommittedUpload(key);
        verifyNoMoreInteractions(delegate, storage);
    }

    private WorkflowAttachmentUploadJournal journal(WfAttachmentMapper mapper, WorkflowAttachmentStorage storage)
    {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        return new WorkflowAttachmentUploadJournal(mapper, storage, new WorkflowAttachmentProperties(), manager);
    }
}

package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceUserRow;
import com.ruoyi.flowable.mapper.WorkflowMultiInstanceUserMapper;

class WorkflowProcessQuerySupportTest
{
    @Test
    void projectsOnlyRequiredNumericIdsAndPreservesRawIdentityAndNickNameSemantics()
    {
        var users = mock(WorkflowMultiInstanceUserMapper.class);
        when(users.selectRawNickNamesByIds(anyList())).thenReturn(List.of(
                new WorkflowMultiInstanceUserRow(1L, " 申请人 "),
                new WorkflowMultiInstanceUserRow(2L, " \t"),
                new WorkflowMultiInstanceUserRow(-1L, "负号"),
                new WorkflowMultiInstanceUserRow(0L, "零")));
        var names = WorkflowProcessQuerySupport.loadUserNames(users, new LinkedHashSet<>(Arrays.asList(
                null, " ", "legacy-user", "1", "01", "+1", "2", "02", "3", "-1", "-0",
                " 1", "1 ", "9223372036854775808")));
        assertThat(names.get(null)).isNull();
        assertThat(names.get(" ")).isNull();
        for (String id : List.of("1", "01", "+1")) assertThat(names.get(id)).isEqualTo(" 申请人 ");
        for (String id : List.of("legacy-user", "2", "02", "3", " 1", "1 ", "9223372036854775808"))
            assertThat(names.get(id)).isEqualTo(id);
        assertThat(names.get("-1")).isEqualTo("负号");
        assertThat(names.get("-0")).isEqualTo("零");
        verify(users).selectRawNickNamesByIds(List.of(1L, 2L, 3L, -1L, 0L));
        verifyNoMoreInteractions(users);
    }

    @Test
    void refreshesNamesEachRequestAndPropagatesDatabaseFailures()
    {
        var users = mock(WorkflowMultiInstanceUserMapper.class);
        when(users.selectRawNickNamesByIds(List.of(1L)))
                .thenReturn(List.of(new WorkflowMultiInstanceUserRow(1L, "原昵称")))
                .thenReturn(List.of(new WorkflowMultiInstanceUserRow(1L, "新昵称")));
        assertThat(WorkflowProcessQuerySupport.loadUserNames(users, Set.of("1")).get("1")).isEqualTo("原昵称");
        assertThat(WorkflowProcessQuerySupport.loadUserNames(users, Set.of("1")).get("1")).isEqualTo("新昵称");
        var failure = new IllegalStateException("database unavailable");
        when(users.selectRawNickNamesByIds(List.of(1L))).thenThrow(failure);
        assertThatThrownBy(() -> WorkflowProcessQuerySupport.loadUserNames(users, Set.of("1"))).isSameAs(failure);
    }

    @Test
    void emptyAndNonNumericPagesDoNotReadUsers()
    {
        var users = mock(WorkflowMultiInstanceUserMapper.class);
        assertThat(WorkflowProcessQuerySupport.loadUserNames(users, Set.of())).isEmpty();
        assertThat(WorkflowProcessQuerySupport.loadUserNames(users, Set.of("old-user")))
                .containsEntry("old-user", "old-user");
        verifyNoInteractions(users);
    }
}

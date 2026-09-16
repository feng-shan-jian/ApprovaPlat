package com.ruoyi.flowable.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;

/** 身份目录故障不能伪装成用户选择错误或丢失原始原因。 */
class WorkflowUserSelectionFailureTest
{
    @ParameterizedTest
    @ValueSource(strings = { "active", "approval", "claim", "role", "dept", "role-members", "dept-members" })
    void preservesDatabaseFailure(String source)
    {
        var mapper = mock(WorkflowIdentityMapper.class);
        var failure = new DataAccessResourceFailureException("identity database unavailable");
        when(mapper.selectActiveUserIdsByUserIds(anyList())).thenThrow(failure);
        when(mapper.selectApprovalEligibleUserIdsByUserIds(anyList())).thenThrow(failure);
        when(mapper.selectClaimEligibleUserIdsByUserIds(anyList())).thenThrow(failure);
        when(mapper.selectApprovalEligibleRoleIdsByRoleIds(anyList())).thenThrow(failure);
        when(mapper.selectApprovalEligibleDeptIdsByDeptIds(anyList())).thenThrow(failure);
        if (source.equals("role-members"))
        {
            org.mockito.Mockito.doReturn(List.of(2L)).when(mapper).selectApprovalEligibleRoleIdsByRoleIds(anyList());
            when(mapper.selectApprovalEligibleUserIdsByRoleIds(anyList())).thenThrow(failure);
        }
        if (source.equals("dept-members"))
        {
            org.mockito.Mockito.doReturn(List.of(2L)).when(mapper).selectApprovalEligibleDeptIdsByDeptIds(anyList());
            when(mapper.selectApprovalEligibleUserIdsByDeptIds(anyList())).thenThrow(failure);
        }
        var validator = new WorkflowUserSelectionValidator(new WorkflowIdentityResolver(mapper,
                new WorkflowIdentityCodec()));
        assertThatThrownBy(() -> select(validator, source)).isSameAs(failure);
    }

    private List<String> select(WorkflowUserSelectionValidator validator, String source)
    {
        return switch (source)
        {
            case "active" -> validator.requireActiveUserIds(List.of(201L));
            case "approval" -> validator.requireApprovalEligibleUserIds(List.of(201L));
            case "claim" -> validator.requireClaimEligibleUserIds(List.of(201L));
            case "role", "role-members" -> validator.requireApprovalEligibleUserIdsByRoleIds(List.of(2L));
            case "dept", "dept-members" -> validator.requireApprovalEligibleUserIdsByDeptIds(List.of(2L));
            default -> throw new AssertionError(source);
        };
    }
}

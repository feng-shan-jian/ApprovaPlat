package com.ruoyi.flowable.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

class WorkflowUserSelectionValidatorTest
{
    private WorkflowIdentityResolver identityResolver;

    private WorkflowUserSelectionValidator validator;

    /**
     * 为每个测试创建独立身份解析器替身和用户选择校验器。
     *
     * @return 无返回值；初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        identityResolver = mock(WorkflowIdentityResolver.class);
        validator = new WorkflowUserSelectionValidator(identityResolver);
    }

    /**
     * 验证有效用户按请求顺序转换为不可变的规范字符串主键。
     *
     * @return 无返回值；顺序、规范格式或不可变约束不符合时测试失败
     */
    @Test
    void returnsOrderedImmutableCanonicalIdsForActiveUsers()
    {
        when(identityResolver.resolveActiveUserIds(List.of("3", "2"), List.of()))
                .thenReturn(new LinkedHashSet<>(List.of("3", "2")));

        List<String> result = validator.requireActiveUserIds(List.of(3L, 2L));

        assertThat(result).containsExactly("3", "2");
        assertThatThrownBy(() -> result.add("9"))
                .isInstanceOf(UnsupportedOperationException.class);
        verify(identityResolver).resolveActiveUserIds(List.of("3", "2"), List.of());
    }

    /**
     * 验证审批资格用户按请求顺序返回且必须全部通过实时权限解析。
     *
     * @return 无返回值；顺序、不可变或审批资格解析入口漂移时测试失败
     */
    @Test
    void returnsOrderedImmutableCanonicalIdsForApprovalEligibleUsers()
    {
        when(identityResolver.resolveApprovalEligibleUserIds(List.of("3", "2")))
                .thenReturn(new LinkedHashSet<>(List.of("3", "2")));

        List<String> result = validator.requireApprovalEligibleUserIds(
                List.of(3L, 2L));

        assertThat(result).containsExactly("3", "2");
        assertThatThrownBy(() -> result.add("9"))
                .isInstanceOf(UnsupportedOperationException.class);
        verify(identityResolver).resolveApprovalEligibleUserIds(List.of("3", "2"));
    }

    /**
     * 验证指定角色和部门展开结果保持正式查询顺序并返回不可修改集合。
     *
     * @return 无返回值；组织身份分支、顺序或不可变约束错误时测试失败
     */
    @Test
    void returnsBoundedUsersExpandedFromConfiguredRolesAndDepartments()
    {
        when(identityResolver.resolveApprovalEligibleUserIdsByRoleIds(
                new LinkedHashSet<>(List.of(101L))))
                .thenReturn(new LinkedHashSet<>(List.of("81", "82")));
        when(identityResolver.resolveApprovalEligibleUserIdsByDeptIds(
                new LinkedHashSet<>(List.of(100L))))
                .thenReturn(new LinkedHashSet<>(List.of("81", "83")));

        List<String> roleUsers = validator.requireApprovalEligibleUserIdsByRoleIds(
                List.of(101L));
        List<String> deptUsers = validator.requireApprovalEligibleUserIdsByDeptIds(
                List.of(100L));

        assertThat(roleUsers).containsExactly("81", "82");
        assertThat(deptUsers).containsExactly("81", "83");
        assertThatThrownBy(() -> roleUsers.add("84"))
                .isInstanceOf(UnsupportedOperationException.class);
        verify(identityResolver).resolveApprovalEligibleUserIdsByRoleIds(
                new LinkedHashSet<>(List.of(101L)));
        verify(identityResolver).resolveApprovalEligibleUserIdsByDeptIds(
                new LinkedHashSet<>(List.of(100L)));
    }

    /**
     * 验证组织成员为空、查询失败或展开超过 100 人时整批拒绝。
     *
     * @return 无返回值；空组、异常组或超量多实例成员被接受时测试失败
     */
    @Test
    void rejectsEmptyFailedAndOversizedConfiguredGroupExpansions()
    {
        when(identityResolver.resolveApprovalEligibleUserIdsByRoleIds(
                new LinkedHashSet<>(List.of(101L)))).thenReturn(Set.of());
        when(identityResolver.resolveApprovalEligibleUserIdsByDeptIds(
                new LinkedHashSet<>(List.of(100L))))
                .thenReturn(LongStream.rangeClosed(1, 101)
                        .mapToObj(String::valueOf)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        when(identityResolver.resolveApprovalEligibleUserIdsByRoleIds(
                new LinkedHashSet<>(List.of(102L)))).thenThrow(new ServiceException(
                        "身份主数据异常", HttpStatus.ERROR));

        assertGroupApprovalIneligible(() ->
                validator.requireApprovalEligibleUserIdsByRoleIds(List.of(101L)));
        assertGroupApprovalIneligible(() ->
                validator.requireApprovalEligibleUserIdsByDeptIds(List.of(100L)));
        assertGroupApprovalIneligible(() ->
                validator.requireApprovalEligibleUserIdsByRoleIds(List.of(102L)));
    }

    /**
     * 验证候选用户按请求顺序返回且必须全部通过完整认领资格解析。
     *
     * @return 无返回值；顺序、不可变或认领资格解析入口漂移时测试失败
     */
    @Test
    void returnsOrderedImmutableCanonicalIdsForClaimEligibleUsers()
    {
        when(identityResolver.resolveClaimEligibleUserIds(List.of("3", "2")))
                .thenReturn(new LinkedHashSet<>(List.of("3", "2")));

        List<String> result = validator.requireClaimEligibleUserIds(
                List.of(3L, 2L));

        assertThat(result).containsExactly("3", "2");
        assertThatThrownBy(() -> result.add("9"))
                .isInstanceOf(UnsupportedOperationException.class);
        verify(identityResolver).resolveClaimEligibleUserIds(List.of("3", "2"));
    }

    /**
     * 验证 null 和空集合均表示未选择用户，且不会访问正式身份主数据。
     *
     * @return 无返回值；空选择触发身份查询或返回非空结果时测试失败
     */
    @Test
    void returnsEmptySelectionWithoutIdentityLookup()
    {
        assertThat(validator.requireActiveUserIds(null)).isEmpty();
        assertThat(validator.requireActiveUserIds(List.of())).isEmpty();
        assertThat(validator.requireApprovalEligibleUserIds(null)).isEmpty();
        assertThat(validator.requireApprovalEligibleUserIds(List.of())).isEmpty();
        assertThat(validator.requireClaimEligibleUserIds(null)).isEmpty();
        assertThat(validator.requireClaimEligibleUserIds(List.of())).isEmpty();

        verifyNoInteractions(identityResolver);
    }

    /**
     * 验证空主键、非正主键、重复主键和超量选择在身份查询前整体拒绝。
     *
     * @return 无返回值；任一非法集合进入身份解析或未返回 HTTP 400 时测试失败
     */
    @Test
    void rejectsMalformedDuplicateAndOversizedSelectionsBeforeLookup()
    {
        List<List<Long>> invalidSelections = List.of(
                Arrays.asList((Long) null),
                List.of(0L),
                List.of(-1L),
                List.of(8L, 8L),
                LongStream.rangeClosed(1, WorkflowUserSelectionValidator.MAX_SELECTED_USERS + 1L)
                        .boxed().toList());

        for (List<Long> invalidSelection : invalidSelections)
        {
            assertInvalidSelection(() -> validator.requireActiveUserIds(invalidSelection));
        }
        verifyNoInteractions(identityResolver);
    }

    /**
     * 验证停用、删除或不存在用户导致解析结果不完整时整次选择失败。
     *
     * @return 无返回值；部分有效用户被静默接受或错误契约变化时测试失败
     */
    @Test
    void rejectsInactiveOrMissingUsersAsOneAtomicSelection()
    {
        when(identityResolver.resolveActiveUserIds(List.of("3", "8"), List.of()))
                .thenReturn(new LinkedHashSet<>(List.of("3")));

        assertInvalidSelection(() -> validator.requireActiveUserIds(List.of(3L, 8L)));

        verify(identityResolver).resolveActiveUserIds(List.of("3", "8"), List.of());
    }

    /**
     * 验证任一用户缺少流程办理资格时整批返回稳定 400，不接受部分有效结果。
     *
     * @return 无返回值；部分审批人被静默接受或错误语义漂移时测试失败
     */
    @Test
    void rejectsApprovalIneligibleUsersAsOneAtomicSelection()
    {
        when(identityResolver.resolveApprovalEligibleUserIds(List.of("3", "8")))
                .thenReturn(new LinkedHashSet<>(List.of("3")));

        assertApprovalIneligible(() -> validator.requireApprovalEligibleUserIds(
                List.of(3L, 8L)));

        verify(identityResolver).resolveApprovalEligibleUserIds(List.of("3", "8"));
    }

    /**
     * 验证任一候选用户缺少完整认领资格时整批返回稳定 400。
     *
     * @return 无返回值；部分候选用户被写入任务或错误语义漂移时测试失败
     */
    @Test
    void rejectsClaimIneligibleUsersAsOneAtomicSelection()
    {
        when(identityResolver.resolveClaimEligibleUserIds(List.of("3", "8")))
                .thenReturn(new LinkedHashSet<>(List.of("3")));

        assertClaimIneligible(() -> validator.requireClaimEligibleUserIds(
                List.of(3L, 8L)));

        verify(identityResolver).resolveClaimEligibleUserIds(List.of("3", "8"));
    }

    /**
     * 验证审批权限主数据异常不会泄漏底层细节，并按整批不可选返回稳定 400。
     *
     * @return 无返回值；底层异常泄漏或错误码变化时测试失败
     */
    @Test
    void normalizesApprovalEligibilityLookupFailuresToBadRequest()
    {
        when(identityResolver.resolveApprovalEligibleUserIds(List.of("3")))
                .thenThrow(new ServiceException("权限主数据异常", HttpStatus.ERROR));

        assertApprovalIneligible(() -> validator.requireApprovalEligibleUserIds(
                List.of(3L)));
    }

    /**
     * 验证底层身份解析异常统一收敛为稳定参数错误，不向客户端暴露主数据细节。
     *
     * @return 无返回值；底层异常泄漏或 HTTP 状态不稳定时测试失败
     */
    @Test
    void normalizesIdentityLookupFailuresToBadRequest()
    {
        when(identityResolver.resolveActiveUserIds(List.of("3"), List.of()))
                .thenThrow(new ServiceException("工作流身份主数据异常", HttpStatus.ERROR));

        assertInvalidSelection(() -> validator.requireActiveUserIds(List.of(3L)));
    }

    /**
     * 断言用户选择失败时返回稳定的 HTTP 400 业务异常。
     *
     * @param action ThrowingCallable，预期被用户选择校验器拒绝的调用
     * @return 无返回值；异常类型、状态码或提示不匹配时测试失败
     */
    private void assertInvalidSelection(ThrowingCallable action)
    {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getMessage()).isEqualTo("工作流用户选择不合法");
        });
    }

    /**
     * 断言审批资格失败时返回稳定的 HTTP 400 业务异常。
     *
     * @param action ThrowingCallable，预期被审批资格校验器拒绝的调用
     * @return 无返回值；异常类型、状态码或提示不匹配时测试失败
     */
    private void assertApprovalIneligible(ThrowingCallable action)
    {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getMessage())
                    .isEqualTo("所选用户不存在、已停用或无流程办理权限");
        });
    }

    /**
     * 断言候选认领资格失败时返回稳定的 HTTP 400 业务异常。
     *
     * @param action ThrowingCallable，预期被候选认领资格校验器拒绝的调用
     * @return 无返回值；异常类型、状态码或提示不匹配时测试失败
     */
    private void assertClaimIneligible(ThrowingCallable action)
    {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getMessage())
                    .isEqualTo("所选候选用户不存在、已停用或无完整认领权限");
        });
    }

    /**
     * 断言指定角色或部门无法安全展开时返回稳定的 HTTP 400 业务异常。
     *
     * @param action ThrowingCallable，预期被组织成员校验拒绝的调用
     * @return 无返回值；异常类型、状态码或提示不匹配时测试失败
     */
    private void assertGroupApprovalIneligible(ThrowingCallable action)
    {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getMessage()).isEqualTo(
                    "指定角色或部门不存在、已停用、无合格办理成员或展开后超过100人");
        });
    }
}

package com.ruoyi.flowable.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;

class WorkflowIdentityResolverTest
{
    private WorkflowIdentityMapper identityMapper;

    private WorkflowIdentityResolver identityResolver;

    /**
     * 为每个测试创建独立 Mapper 替身和身份解析器。
     *
     * @return 无返回值；初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        identityMapper = mock(WorkflowIdentityMapper.class);
        identityResolver = new WorkflowIdentityResolver(identityMapper, new WorkflowIdentityCodec());
    }

    /**
     * 清理 Spring Security 线程上下文，避免测试之间身份串线。
     *
     * @return 无返回值
     */
    @AfterEach
    void clearSecurityContext()
    {
        SecurityContextHolder.clearContext();
    }

    /**
     * 验证当前身份完全依据正式主数据返回有效用户、角色和部门。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void resolvesCurrentIdentityFromActiveMasterData()
    {
        authenticate(10L);
        when(identityMapper.selectActiveUserIdsByUserIds(List.of(10L))).thenReturn(List.of(10L));
        when(identityMapper.selectActiveRoleIdsByUserId(10L)).thenReturn(List.of(3L, 5L));
        when(identityMapper.selectActiveDeptIdsByUserId(10L)).thenReturn(List.of(20L));

        WorkflowCurrentIdentity identity = identityResolver.resolveCurrentIdentity();

        assertThat(identity.userId()).isEqualTo("10");
        assertThat(identity.candidateGroups()).containsExactly("ROLE3", "ROLE5", "DEPT20");
        assertThatThrownBy(() -> identity.candidateGroups().add("ROLE99"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * 验证正式用户已删除或停用时，即使旧登录会话仍存在也会拒绝工作流身份。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsCurrentUserMissingFromActiveMasterData()
    {
        authenticate(10L);
        when(identityMapper.selectActiveUserIdsByUserIds(List.of(10L))).thenReturn(List.of());

        assertThatThrownBy(identityResolver::resolveCurrentIdentity)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.getMessage()).isEqualTo("当前用户不可参与工作流");
                });
        verify(identityMapper, never()).selectActiveRoleIdsByUserId(10L);
        verify(identityMapper, never()).selectActiveDeptIdsByUserId(10L);
    }

    /**
     * 验证直接用户、角色组和部门组会去重展开，且 Mapper 未返回的停用或删除用户被过滤。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void resolvesOnlyActiveUsersAcrossDirectRoleAndDepartmentCandidates()
    {
        when(identityMapper.selectActiveUserIdsByUserIds(List.of(3L, 2L, 7L))).thenReturn(List.of(2L, 3L));
        when(identityMapper.selectActiveUserIdsByRoleIds(List.of(9L))).thenReturn(List.of(2L, 4L));
        when(identityMapper.selectActiveUserIdsByDeptIds(List.of(8L))).thenReturn(List.of(4L, 5L));

        Set<String> resolved = identityResolver.resolveActiveUserIds(
                List.of("3", "2", "7", "3"), List.of("ROLE9", "DEPT8", "ROLE9"));

        assertThat(resolved).containsExactly("3", "2", "4", "5");
        assertThatThrownBy(() -> resolved.add("6")).isInstanceOf(UnsupportedOperationException.class);
        verify(identityMapper).selectActiveUserIdsByUserIds(List.of(3L, 2L, 7L));
        verify(identityMapper).selectActiveUserIdsByRoleIds(List.of(9L));
        verify(identityMapper).selectActiveUserIdsByDeptIds(List.of(8L));
    }

    /**
     * 验证审批资格解析使用正式 RBAC 查询，并按请求顺序过滤无权限用户。
     *
     * @return 无返回值；Mapper 入口、过滤规则或结果顺序漂移时测试失败
     */
    @Test
    void resolvesOnlyApprovalEligibleUsersInRequestOrder()
    {
        when(identityMapper.selectApprovalEligibleUserIdsByUserIds(
                List.of(3L, 2L, 7L))).thenReturn(List.of(2L, 3L));

        Set<String> resolved = identityResolver.resolveApprovalEligibleUserIds(
                List.of("3", "2", "7", "3"));

        assertThat(resolved).containsExactly("3", "2");
        assertThatThrownBy(() -> resolved.add("8"))
                .isInstanceOf(UnsupportedOperationException.class);
        verify(identityMapper).selectApprovalEligibleUserIdsByUserIds(
                List.of(3L, 2L, 7L));
    }

    /**
     * 验证完整认领资格解析使用独立 RBAC 查询，并按请求顺序过滤缺少认领链权限的用户。
     *
     * @return 无返回值；认领资格误用审批查询或结果顺序漂移时测试失败
     */
    @Test
    void resolvesOnlyClaimEligibleUsersInRequestOrder()
    {
        when(identityMapper.selectClaimEligibleUserIdsByUserIds(
                List.of(3L, 2L, 7L))).thenReturn(List.of(3L));

        Set<String> resolved = identityResolver.resolveClaimEligibleUserIds(
                List.of("3", "2", "7", "3"));

        assertThat(resolved).containsExactly("3");
        assertThatThrownBy(() -> resolved.add("8"))
                .isInstanceOf(UnsupportedOperationException.class);
        verify(identityMapper).selectClaimEligibleUserIdsByUserIds(
                List.of(3L, 2L, 7L));
    }

    /**
     * 验证候选角色和部门分别按成员完整认领资格过滤，并保持原候选组顺序。
     *
     * @return 无返回值；任一不合格组被其他组掩盖或结果顺序漂移时测试失败
     */
    @Test
    void resolvesEachClaimEligibleCandidateGroupInRequestOrder()
    {
        when(identityMapper.selectClaimEligibleRoleIdsByRoleIds(
                List.of(9L, 10L))).thenReturn(List.of(10L));
        when(identityMapper.selectClaimEligibleDeptIdsByDeptIds(
                List.of(8L))).thenReturn(List.of(8L));

        Set<String> resolved = identityResolver.resolveClaimEligibleCandidateGroups(
                List.of("ROLE9", "DEPT8", "ROLE10", "ROLE9"));

        assertThat(resolved).containsExactly("DEPT8", "ROLE10");
        assertThatThrownBy(() -> resolved.add("ROLE11"))
                .isInstanceOf(UnsupportedOperationException.class);
        verify(identityMapper).selectClaimEligibleRoleIdsByRoleIds(List.of(9L, 10L));
        verify(identityMapper).selectClaimEligibleDeptIdsByDeptIds(List.of(8L));
    }

    /**
     * 验证候选身份中的非法值会在数据库访问前整体拒绝，避免部分授权结果生效。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsMalformedCandidateBeforeDatabaseAccess()
    {
        assertThatThrownBy(() -> identityResolver.resolveActiveUserIds(List.of("1"), List.of("ROLE-2")))
                .isInstanceOf(ServiceException.class)
                .hasMessage("工作流候选组标识无效");
        assertThatThrownBy(() -> identityResolver.resolveClaimEligibleCandidateGroups(
                List.of("ROLE007")))
                .isInstanceOf(ServiceException.class)
                .hasMessage("工作流候选组标识无效");
        verifyNoInteractions(identityMapper);
    }

    /**
     * 验证候选集合本身不能为 null，但允许两个显式空集合表示没有候选人。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void validatesCandidateCollections()
    {
        assertThatThrownBy(() -> identityResolver.resolveActiveUserIds(null, List.of()))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(identityResolver.resolveActiveUserIds(List.of(), List.of())).isEmpty();
        verifyNoInteractions(identityMapper);
    }

    /**
     * 验证 Mapper 返回非法主键时按主数据错误终止，不把损坏身份交给 Flowable。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsInvalidIdsReturnedByMasterDataMapper()
    {
        when(identityMapper.selectActiveUserIdsByUserIds(List.of(1L))).thenReturn(List.of(0L));

        assertThatThrownBy(() -> identityResolver.resolveActiveUserIds(List.of("1"), List.of()))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
                    assertThat(exception.getMessage()).isEqualTo("工作流身份主数据异常");
                });
    }

    /**
     * 在 Spring Security 上下文中放入指定若依用户 ID。
     *
     * @param userId Long，测试登录用户主键
     * @return 无返回值
     */
    private void authenticate(Long userId)
    {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(userId);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}

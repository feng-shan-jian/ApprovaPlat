package com.ruoyi.flowable.service.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.vo.WorkflowIdentityOptionView;
import com.ruoyi.flowable.domain.vo.WorkflowIdentitySelectionView;
import com.ruoyi.flowable.domain.vo.WorkflowPageResult;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;

/**
 * WorkflowIdentityDirectoryService 的类型、分页和主数据异常门禁测试。
 */
class WorkflowIdentityDirectoryServiceTest
{
    private WorkflowIdentityMapper identityMapper;

    private WorkflowIdentityDirectoryService service;

    /**
     * 为每个测试创建独立 Mapper 替身和目录服务。
     *
     * @return 无返回值，初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        identityMapper = mock(WorkflowIdentityMapper.class);
        service = new WorkflowIdentityDirectoryService(identityMapper);
    }

    /**
     * 验证用户查询会规范检索词并使用 long 偏移量返回不可变分页结果。
     *
     * @return 无返回值，类型、偏移量或响应协议漂移时测试失败
     */
    @Test
    void listsNormalizedUserOptionsWithStablePagination()
    {
        WorkflowIdentityOptionView option = new WorkflowIdentityOptionView(
                "21", "张三 (zhangsan)", "user");
        when(identityMapper.countActiveIdentityOptions("user", "张三")).thenReturn(21L);
        when(identityMapper.selectActiveIdentityOptions("user", "张三", 20L, 20))
                .thenReturn(List.of(option));

        WorkflowPageResult<WorkflowIdentityOptionView> page = service.listOptions(
                " USER ", "  张三  ", 2, 20);

        assertThat(page.total()).isEqualTo(21L);
        assertThat(page.rows()).containsExactly(option);
        assertThatThrownBy(() -> page.rows().add(option))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * 验证 approval 能力使用服务端审批资格目录，并保持既有最小响应结构。
     *
     * @return 无返回值；审批资格 Mapper、分页或响应协议漂移时测试失败
     */
    @Test
    void listsOnlyApprovalEligibleUserOptions()
    {
        WorkflowIdentityOptionView option = new WorkflowIdentityOptionView(
                "21", "张三 (zhangsan)", "user");
        when(identityMapper.countApprovalEligibleUserOptions("张三")).thenReturn(1L);
        when(identityMapper.selectApprovalEligibleUserOptions("张三", 0L, 20))
                .thenReturn(List.of(option));

        WorkflowPageResult<WorkflowIdentityOptionView> page = service.listOptions(
                "user", " 张三 ", 1, 20,
                WorkflowIdentityDirectoryService.APPROVAL_CAPABILITY);

        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.rows()).containsExactly(option);
        verify(identityMapper).countApprovalEligibleUserOptions("张三");
        verify(identityMapper).selectApprovalEligibleUserOptions("张三", 0L, 20);
    }

    /**
     * 验证 claim 能力把角色目录路由到至少包含一名完整认领成员的资格查询。
     *
     * @return 无返回值；候选角色退回通用有效目录时测试失败
     */
    @Test
    void listsOnlyClaimEligibleRoleOptions()
    {
        WorkflowIdentityOptionView option = new WorkflowIdentityOptionView(
                "ROLE7", "角色: 财务审批", "role");
        when(identityMapper.countClaimEligibleIdentityOptions("role", "财务"))
                .thenReturn(1L);
        when(identityMapper.selectClaimEligibleIdentityOptions(
                "role", "财务", 0L, 20)).thenReturn(List.of(option));

        WorkflowPageResult<WorkflowIdentityOptionView> page = service.listOptions(
                "role", " 财务 ", 1, 20,
                WorkflowIdentityDirectoryService.CLAIM_CAPABILITY);

        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.rows()).containsExactly(option);
        verify(identityMapper).countClaimEligibleIdentityOptions("role", "财务");
        verify(identityMapper).selectClaimEligibleIdentityOptions(
                "role", "财务", 0L, 20);
    }

    /**
     * 验证空结果不会执行无意义分页查询。
     *
     * @return 无返回值，零结果仍访问明细查询时测试失败
     */
    @Test
    void skipsPageQueryWhenNoActiveIdentityMatches()
    {
        when(identityMapper.countActiveIdentityOptions("role", null)).thenReturn(0L);

        WorkflowPageResult<WorkflowIdentityOptionView> page = service.listOptions(
                "role", "  ", 1, 50);

        assertThat(page.total()).isZero();
        assertThat(page.rows()).isEmpty();
        verify(identityMapper).countActiveIdentityOptions("role", null);
    }

    /**
     * 验证未知身份类型在访问 Mapper 前即被拒绝。
     *
     * @return 无返回值，非法类型触达数据库时测试失败
     */
    @Test
    void rejectsUnknownIdentityTypeBeforeDatabaseAccess()
    {
        assertThatThrownBy(() -> service.listOptions("post", null, 1, 20))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).contains("user、role 或 dept");
                });
        verifyNoInteractions(identityMapper);
    }

    /**
     * 验证 approval 只允许用户目录，未知能力也会在访问数据库前返回 400。
     *
     * @return 无返回值；非法能力触达 Mapper 或被角色目录接受时测试失败
     */
    @Test
    void rejectsInvalidCapabilityCombinationsBeforeDatabaseAccess()
    {
        assertBadRequest(() -> service.listOptions(
                "role", null, 1, 20,
                WorkflowIdentityDirectoryService.APPROVAL_CAPABILITY));
        assertBadRequest(() -> service.listOptions(
                "user", null, 1, 20, "delegate"));

        verifyNoInteractions(identityMapper);
    }

    /**
     * 验证服务层独立执行分页和检索词资源门禁。
     *
     * @return 无返回值，绕过 Controller 后可提交超限参数时测试失败
     */
    @Test
    void rejectsOutOfRangePaginationAndKeyword()
    {
        assertBadRequest(() -> service.listOptions("user", null, 0, 20));
        assertBadRequest(() -> service.listOptions("user", null, 1,
                WorkflowIdentityDirectoryService.MAX_PAGE_SIZE + 1));
        assertBadRequest(() -> service.listOptions("user", "x".repeat(65), 1, 20));
        verifyNoInteractions(identityMapper);
    }

    /**
     * 验证 Mapper 返回空集合引用或超量集合时转换为稳定服务异常。
     *
     * @return 无返回值，异常主数据结果被当作成功响应时测试失败
     */
    @Test
    void rejectsInvalidMapperPageResult()
    {
        when(identityMapper.countActiveIdentityOptions("dept", null)).thenReturn(1L);
        when(identityMapper.selectActiveIdentityOptions("dept", null, 0L, 1))
                .thenReturn(null);

        assertThatThrownBy(() -> service.listOptions("dept", null, 1, 1))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR));
    }

    /**
     * 验证已选对象按作者顺序回显，失去审批资格和物理删除对象不会显示裸主键。
     *
     * @return 无返回值，名称、资格状态或删除占位契约漂移时测试失败
     */
    @Test
    void resolvesSavedSelectionsWithoutExposingMissingIdentifiers()
    {
        WorkflowIdentitySelectionView existing = new WorkflowIdentitySelectionView(
                "77", "历史审批人 (reviewer)", "user", true);
        when(identityMapper.selectIdentitySelectionsByIds("user", List.of(88L, 77L)))
                .thenReturn(List.of(existing));
        when(identityMapper.selectApprovalEligibleUserIdsByUserIds(List.of(88L, 77L)))
                .thenReturn(List.of());

        List<WorkflowIdentitySelectionView> rows = service.resolveSelections(
                "user", WorkflowIdentityDirectoryService.APPROVAL_CAPABILITY,
                List.of("88", "77"));

        assertThat(rows).extracting(WorkflowIdentitySelectionView::value)
                .containsExactly("88", "77");
        assertThat(rows).allMatch(row -> !row.available());
        assertThat(rows.get(0).label()).isEqualTo("已删除用户（不可用）")
                .doesNotContain("88");
        assertThat(rows.get(1).label()).contains("历史审批人", "已停用或无当前资格");
    }

    /**
     * 验证角色回显必须使用 ROLE 受控值并按 claim 资格查询，非法裸数字在数据库前失败。
     *
     * @return 无返回值，目录编码或能力隔离被绕过时测试失败
     */
    @Test
    void validatesRoleSelectionEncodingAndClaimCapability()
    {
        WorkflowIdentitySelectionView role = new WorkflowIdentitySelectionView(
                "ROLE7", "角色: 财务审批", "role", true);
        when(identityMapper.selectIdentitySelectionsByIds("role", List.of(7L)))
                .thenReturn(List.of(role));
        when(identityMapper.selectClaimEligibleRoleIdsByRoleIds(List.of(7L)))
                .thenReturn(List.of(7L));

        assertThat(service.resolveSelections("role", "claim", List.of("ROLE7")))
                .containsExactly(role);
        assertBadRequest(() -> service.resolveSelections(
                "role", "claim", List.of("7")));
    }

    /**
     * 断言指定目录调用被服务层作为 400 参数错误拒绝。
     *
     * @param action Runnable，预期失败的目录服务调用
     * @return 无返回值，未抛出稳定 400 异常时测试失败
     */
    private void assertBadRequest(Runnable action)
    {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}

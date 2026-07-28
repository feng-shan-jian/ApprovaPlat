package com.ruoyi.flowable.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.vo.WorkflowIdentityOptionView;

/**
 * 工作流身份解析专用数据访问层。
 */
public interface WorkflowIdentityMapper
{
    /**
     * 统计指定类型的有效身份选项。
     *
     * @param type String，user、role 或 dept 规范值
     * @param keyword String，可为空的名称、账号或编码检索词
     * @return long，符合条件的有效身份数量
     */
    long countActiveIdentityOptions(@Param("type") String type,
            @Param("keyword") String keyword);

    /**
     * 分页查询指定类型的有效最小身份选项。
     *
     * @param type String，user、role 或 dept 规范值
     * @param keyword String，可为空的名称、账号或编码检索词
     * @param offset long，从零开始的查询偏移量
     * @param pageSize int，单页记录数
     * @return List&lt;WorkflowIdentityOptionView&gt;，稳定排序的身份选项
     */
    List<WorkflowIdentityOptionView> selectActiveIdentityOptions(
            @Param("type") String type,
            @Param("keyword") String keyword,
            @Param("offset") long offset,
            @Param("pageSize") int pageSize);

    /**
     * 统计具备正式流程办理资格的有效用户选项。
     *
     * @param keyword String，可为空的名称或账号检索词
     * @return long，同时满足用户启用及待办、详情、审批权限的用户数量
     */
    long countApprovalEligibleUserOptions(@Param("keyword") String keyword);

    /**
     * 分页查询具备正式流程办理资格的有效用户选项。
     *
     * @param keyword String，可为空的名称或账号检索词
     * @param offset long，从零开始的查询偏移量
     * @param pageSize int，单页记录数
     * @return List&lt;WorkflowIdentityOptionView&gt;，稳定排序的完整办理资格用户选项
     */
    List<WorkflowIdentityOptionView> selectApprovalEligibleUserOptions(
            @Param("keyword") String keyword,
            @Param("offset") long offset,
            @Param("pageSize") int pageSize);

    /**
     * 统计具备完整认领资格的有效用户、角色或部门选项。
     *
     * @param type String，user、role 或 dept 规范值
     * @param keyword String，可为空的名称、账号或编码检索词
     * @return long，直接用户合格或至少包含一名合格成员的身份数量
     */
    long countClaimEligibleIdentityOptions(@Param("type") String type,
            @Param("keyword") String keyword);

    /**
     * 分页查询具备完整认领资格的有效用户、角色或部门选项。
     *
     * @param type String，user、role 或 dept 规范值
     * @param keyword String，可为空的名称、账号或编码检索词
     * @param offset long，从零开始的查询偏移量
     * @param pageSize int，单页记录数
     * @return List&lt;WorkflowIdentityOptionView&gt;，稳定排序的候选身份选项
     */
    List<WorkflowIdentityOptionView> selectClaimEligibleIdentityOptions(
            @Param("type") String type,
            @Param("keyword") String keyword,
            @Param("offset") long offset,
            @Param("pageSize") int pageSize);

    /**
     * 从指定用户中查询未删除且未停用的用户 ID。
     *
     * @param userIds List&lt;Long&gt;，待过滤的用户主键
     * @return List&lt;Long&gt;，有效用户主键，按主键升序返回
     */
    List<Long> selectActiveUserIdsByUserIds(@Param("userIds") List<Long> userIds);

    /**
     * 从指定用户中查询当前具备流程办理资格的有效用户 ID。
     *
     * @param userIds List&lt;Long&gt;，待校验的用户主键
     * @return List&lt;Long&gt;，有效且具备待办、详情和审批权限的用户主键
     */
    List<Long> selectApprovalEligibleUserIdsByUserIds(
            @Param("userIds") List<Long> userIds);

    /**
     * 从指定用户中查询可走通待签、认领和后续办理主路径的有效用户 ID。
     *
     * @param userIds List&lt;Long&gt;，待校验候选用户主键
     * @return List&lt;Long&gt;，具备完整认领资格的有效用户主键
     */
    List<Long> selectClaimEligibleUserIdsByUserIds(
            @Param("userIds") List<Long> userIds);

    /**
     * 从指定角色中查询至少包含一名完整认领资格成员的有效角色 ID。
     *
     * @param roleIds List&lt;Long&gt;，待校验候选角色主键
     * @return List&lt;Long&gt;，可产生真实可认领任务的角色主键
     */
    List<Long> selectClaimEligibleRoleIdsByRoleIds(
            @Param("roleIds") List<Long> roleIds);

    /**
     * 从指定部门中查询至少包含一名完整认领资格成员的有效部门 ID。
     *
     * @param deptIds List&lt;Long&gt;，待校验候选部门主键
     * @return List&lt;Long&gt;，可产生真实可认领任务的部门主键
     */
    List<Long> selectClaimEligibleDeptIdsByDeptIds(
            @Param("deptIds") List<Long> deptIds);

    /**
     * 从指定角色中查询未删除且未停用的角色 ID。
     *
     * @param roleIds List&lt;Long&gt;，待过滤的角色主键
     * @return List&lt;Long&gt;，有效角色主键，按主键升序返回
     */
    List<Long> selectActiveRoleIdsByRoleIds(@Param("roleIds") List<Long> roleIds);

    /**
     * 从指定部门中查询未删除且未停用的部门 ID。
     *
     * @param deptIds List&lt;Long&gt;，待过滤的部门主键
     * @return List&lt;Long&gt;，有效部门主键，按主键升序返回
     */
    List<Long> selectActiveDeptIdsByDeptIds(@Param("deptIds") List<Long> deptIds);

    /**
     * 查询指定有效角色下未删除且未停用的用户 ID。
     *
     * @param roleIds List&lt;Long&gt;，角色主键
     * @return List&lt;Long&gt;，有效用户主键，去重并按主键升序返回
     */
    List<Long> selectActiveUserIdsByRoleIds(@Param("roleIds") List<Long> roleIds);

    /**
     * 查询指定有效部门下未删除且未停用的用户 ID。
     *
     * @param deptIds List&lt;Long&gt;，部门主键
     * @return List&lt;Long&gt;，有效用户主键，去重并按主键升序返回
     */
    List<Long> selectActiveUserIdsByDeptIds(@Param("deptIds") List<Long> deptIds);

    /**
     * 查询有效用户当前仍有效的角色 ID。
     *
     * @param userId Long，若依用户主键
     * @return List&lt;Long&gt;，有效角色主键，按主键升序返回
     */
    List<Long> selectActiveRoleIdsByUserId(@Param("userId") Long userId);

    /**
     * 查询有效用户当前仍有效的部门 ID。
     *
     * @param userId Long，若依用户主键
     * @return List&lt;Long&gt;，有效部门主键；用户无有效部门时为空
     */
    List<Long> selectActiveDeptIdsByUserId(@Param("userId") Long userId);
}

package com.ruoyi.flowable.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.vo.WorkflowIdentityOptionView;
import com.ruoyi.flowable.domain.vo.WorkflowIdentitySelectionView;

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
     * 按主键批量读取正式身份目录名称和基础启用状态，包含已停用但尚未物理删除的对象。
     *
     * @param type String，user、role 或 dept
     * @param ids List&lt;Long&gt;，已规范化且去重的正式主键
     * @return List&lt;WorkflowIdentitySelectionView&gt;，存在于正式目录中的已选对象
     */
    List<WorkflowIdentitySelectionView> selectIdentitySelectionsByIds(
            @Param("type") String type, @Param("ids") List<Long> ids);

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

    /**
     * 查询有效用户直属部门及其当前有效祖先部门，供流程发起范围按组织层级命中。
     * @param userId Long，当前发起用户主键
     * @return List&lt;Long&gt;，直属部门到祖先部门的有效主键集合
     */
    List<Long> selectActiveScopeDeptIdsByUserId(@Param("userId") Long userId);

    /**
     * 查询指定部门当前唯一且具备直接办理资格的负责人用户。
     * @param deptIds List&lt;Long&gt;，正式部门主键
     * @return List&lt;Long&gt;，负责人账号与有效用户精确匹配后的合格用户主键
     */
    List<Long> selectApprovalEligibleDeptLeaderUserIds(
            @Param("deptIds") List<Long> deptIds);

    /**
     * 查询用户直属上级；本人是本部门负责人时向上取父部门负责人。
     * @param userId Long，流程发起人用户主键
     * @return List&lt;Long&gt;，零或一个具备直接办理资格的上级用户主键
     */
    List<Long> selectApprovalEligibleManagerUserIdsByUserId(
            @Param("userId") Long userId);

    /**
     * 查询发起人所在有效部门内同时拥有指定角色和完整认领资格的用户。
     * @param userId Long，流程发起人用户主键
     * @param roleId Long，设计时选择的正式角色主键
     * @return List&lt;Long&gt;，去重且按用户主键稳定排序的候选用户
     */
    List<Long> selectClaimEligibleUserIdsByStarterDeptAndRole(
            @Param("userId") Long userId, @Param("roleId") Long roleId);
}

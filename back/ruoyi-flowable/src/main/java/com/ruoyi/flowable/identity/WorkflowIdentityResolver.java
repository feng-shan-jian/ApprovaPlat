package com.ruoyi.flowable.identity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;

/**
 * 将若依用户、角色和部门主数据解析为 Flowable 可使用的身份集合。
 */
@Component
public class WorkflowIdentityResolver
{
    /** 候选身份集合参数非法时的稳定提示。 */
    private static final String INVALID_CANDIDATES_MESSAGE = "工作流候选身份不能为空";

    /** 当前登录用户已删除或停用时的稳定提示。 */
    private static final String CURRENT_USER_UNAVAILABLE_MESSAGE = "当前用户不可参与工作流";

    /** 主数据返回非法身份主键时的稳定提示。 */
    private static final String INVALID_MASTER_DATA_MESSAGE = "工作流身份主数据异常";

    private final WorkflowIdentityMapper identityMapper;

    private final WorkflowIdentityCodec identityCodec;

    /**
     * 创建工作流身份解析器。
     *
     * @param identityMapper WorkflowIdentityMapper，工作流专用身份查询 Mapper
     * @param identityCodec WorkflowIdentityCodec，身份标识格式解析器
     * @return 无返回值，构造后由 Spring 管理该组件
     */
    public WorkflowIdentityResolver(WorkflowIdentityMapper identityMapper, WorkflowIdentityCodec identityCodec)
    {
        this.identityMapper = identityMapper;
        this.identityCodec = identityCodec;
    }

    /**
     * 从正式主数据解析当前登录用户及其有效候选组。
     *
     * @return WorkflowCurrentIdentity，规范用户 ID 与当前有效角色、部门候选组
     */
    public WorkflowCurrentIdentity resolveCurrentIdentity()
    {
        String normalizedUserId = identityCodec.normalizeUserId(String.valueOf(SecurityUtils.getUserId()));
        long userId = Long.parseLong(normalizedUserId);

        // 每次执行工作流写操作前核对正式用户表，阻止已删除或已停用的旧会话继续流转。
        Set<Long> activeUserIds = checkedIdSet(identityMapper.selectActiveUserIdsByUserIds(List.of(userId)));
        if (!activeUserIds.contains(userId))
        {
            throw new ServiceException(CURRENT_USER_UNAVAILABLE_MESSAGE, HttpStatus.FORBIDDEN);
        }

        LinkedHashSet<String> candidateGroups = new LinkedHashSet<>();
        for (Long roleId : checkedIds(identityMapper.selectActiveRoleIdsByUserId(userId)))
        {
            candidateGroups.add(identityCodec.roleGroup(roleId));
        }
        for (Long deptId : checkedIds(identityMapper.selectActiveDeptIdsByUserId(userId)))
        {
            candidateGroups.add(identityCodec.deptGroup(deptId));
        }
        return new WorkflowCurrentIdentity(normalizedUserId, candidateGroups);
    }

    /**
     * 展开候选用户和候选组，并仅返回正式主数据中的有效用户。
     *
     * @param candidateUserIds Collection&lt;String&gt;，数字格式的 Flowable 候选用户 ID 集合
     * @param candidateGroups Collection&lt;String&gt;，ROLE&lt;id&gt; 或 DEPT&lt;id&gt; 候选组集合
     * @return Set&lt;String&gt;，去重后的有效数字用户 ID，顺序稳定且不可修改
     */
    public Set<String> resolveActiveUserIds(Collection<String> candidateUserIds,
            Collection<String> candidateGroups)
    {
        if (candidateUserIds == null || candidateGroups == null)
        {
            throw new ServiceException(INVALID_CANDIDATES_MESSAGE, HttpStatus.BAD_REQUEST);
        }

        LinkedHashSet<Long> requestedUserIds = new LinkedHashSet<>();
        for (String candidateUserId : candidateUserIds)
        {
            requestedUserIds.add(Long.valueOf(identityCodec.normalizeUserId(candidateUserId)));
        }

        LinkedHashSet<Long> roleIds = new LinkedHashSet<>();
        LinkedHashSet<Long> deptIds = new LinkedHashSet<>();
        for (String candidateGroup : candidateGroups)
        {
            WorkflowCandidateGroup parsedGroup = identityCodec.parseCandidateGroup(candidateGroup);
            if (parsedGroup.type() == WorkflowCandidateGroupType.ROLE)
            {
                roleIds.add(parsedGroup.id());
            }
            else
            {
                deptIds.add(parsedGroup.id());
            }
        }

        LinkedHashSet<String> resolvedUserIds = new LinkedHashSet<>();
        if (!requestedUserIds.isEmpty())
        {
            Set<Long> activeDirectUsers = checkedIdSet(
                    identityMapper.selectActiveUserIdsByUserIds(new ArrayList<>(requestedUserIds)));
            // 直接候选用户保持 BPMN 中声明的顺序，停用或删除用户不会进入结果。
            requestedUserIds.stream().filter(activeDirectUsers::contains)
                    .map(String::valueOf).forEach(resolvedUserIds::add);
        }
        appendActiveUsersByRoles(roleIds, resolvedUserIds);
        appendActiveUsersByDepts(deptIds, resolvedUserIds);
        return Collections.unmodifiableSet(resolvedUserIds);
    }

    /**
     * 展开自动抄送身份，并只返回具备抄送工作台与流程详情权限的有效用户。
     *
     * @param candidateUserIds Collection&lt;String&gt;，固定、发起人或表单解析用户主键
     * @param candidateGroups Collection&lt;String&gt;，ROLE&lt;id&gt; 或 DEPT&lt;id&gt; 集合
     * @return Set&lt;String&gt;，去重且具备对象可见性的用户主键
     */
    public Set<String> resolveCopyEligibleUserIds(Collection<String> candidateUserIds,
            Collection<String> candidateGroups)
    {
        if (candidateUserIds == null || candidateGroups == null)
        {
            throw new ServiceException(INVALID_CANDIDATES_MESSAGE, HttpStatus.BAD_REQUEST);
        }
        LinkedHashSet<Long> userIds = new LinkedHashSet<>();
        for (String userId : candidateUserIds)
        {
            userIds.add(Long.valueOf(identityCodec.normalizeUserId(userId)));
        }
        LinkedHashSet<Long> roleIds = new LinkedHashSet<>();
        LinkedHashSet<Long> deptIds = new LinkedHashSet<>();
        for (String group : candidateGroups)
        {
            WorkflowCandidateGroup parsed = identityCodec.parseCandidateGroup(group);
            (parsed.type() == WorkflowCandidateGroupType.ROLE ? roleIds : deptIds)
                    .add(parsed.id());
        }
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        if (!userIds.isEmpty())
        {
            Set<Long> eligible = checkedIdSet(identityMapper
                    .selectCopyEligibleUserIdsByUserIds(new ArrayList<>(userIds)));
            userIds.stream().filter(eligible::contains).map(String::valueOf)
                    .forEach(resolved::add);
        }
        if (!roleIds.isEmpty())
        {
            checkedIds(identityMapper.selectCopyEligibleUserIdsByRoleIds(
                    new ArrayList<>(roleIds))).forEach(id -> resolved.add(String.valueOf(id)));
        }
        if (!deptIds.isEmpty())
        {
            checkedIds(identityMapper.selectCopyEligibleUserIdsByDeptIds(
                    new ArrayList<>(deptIds))).forEach(id -> resolved.add(String.valueOf(id)));
        }
        return Collections.unmodifiableSet(resolved);
    }

    /**
     * 从正式用户、角色和菜单授权数据中解析具备流程办理资格的有效用户。
     *
     * @param candidateUserIds Collection&lt;String&gt;，数字格式的待校验用户主键
     * @return Set&lt;String&gt;，保持请求顺序且不可修改的审批资格用户主键集合
     */
    public Set<String> resolveApprovalEligibleUserIds(
            Collection<String> candidateUserIds)
    {
        if (candidateUserIds == null)
        {
            throw new ServiceException(INVALID_CANDIDATES_MESSAGE, HttpStatus.BAD_REQUEST);
        }

        LinkedHashSet<Long> requestedUserIds = new LinkedHashSet<>();
        for (String candidateUserId : candidateUserIds)
        {
            requestedUserIds.add(Long.valueOf(
                    identityCodec.normalizeUserId(candidateUserId)));
        }
        if (requestedUserIds.isEmpty())
        {
            return Set.of();
        }

        // Mapper 同时核对用户启停、超级管理员语义和实时角色菜单权限，防止旧会话或过期目录绕过授权。
        Set<Long> eligibleUserIds = checkedIdSet(
                identityMapper.selectApprovalEligibleUserIdsByUserIds(
                        new ArrayList<>(requestedUserIds)));
        LinkedHashSet<String> resolvedUserIds = new LinkedHashSet<>();
        requestedUserIds.stream().filter(eligibleUserIds::contains)
                .map(String::valueOf).forEach(resolvedUserIds::add);
        return Collections.unmodifiableSet(resolvedUserIds);
    }

    /**
     * 从正式用户、角色和菜单授权数据中解析可走通认领及后续办理主路径的有效用户。
     *
     * @param candidateUserIds Collection&lt;String&gt;，数字格式的待校验候选用户主键
     * @return Set&lt;String&gt;，保持请求顺序且不可修改的完整认领资格用户主键集合
     */
    public Set<String> resolveClaimEligibleUserIds(
            Collection<String> candidateUserIds)
    {
        if (candidateUserIds == null)
        {
            throw new ServiceException(INVALID_CANDIDATES_MESSAGE, HttpStatus.BAD_REQUEST);
        }

        LinkedHashSet<Long> requestedUserIds = new LinkedHashSet<>();
        for (String candidateUserId : candidateUserIds)
        {
            requestedUserIds.add(Long.valueOf(
                    identityCodec.normalizeUserId(candidateUserId)));
        }
        if (requestedUserIds.isEmpty())
        {
            return Set.of();
        }

        // 候选任务必须同时可查看待签、认领、进入待办详情并完成审批，缺少任一权限都不能落为 assignee。
        Set<Long> eligibleUserIds = checkedIdSet(
                identityMapper.selectClaimEligibleUserIdsByUserIds(
                        new ArrayList<>(requestedUserIds)));
        LinkedHashSet<String> resolvedUserIds = new LinkedHashSet<>();
        requestedUserIds.stream().filter(eligibleUserIds::contains)
                .map(String::valueOf).forEach(resolvedUserIds::add);
        return Collections.unmodifiableSet(resolvedUserIds);
    }

    /**
     * 从正式角色、部门、成员和菜单授权中解析每个可产生真实认领人的规范候选组。
     *
     * @param candidateGroups Collection&lt;String&gt;，ROLE&lt;id&gt; 或 DEPT&lt;id&gt; 规范候选组集合
     * @return Set&lt;String&gt;，保持请求顺序且不可修改的完整认领资格候选组集合
     */
    public Set<String> resolveClaimEligibleCandidateGroups(
            Collection<String> candidateGroups)
    {
        if (candidateGroups == null)
        {
            throw new ServiceException(INVALID_CANDIDATES_MESSAGE, HttpStatus.BAD_REQUEST);
        }

        // requestedGroups 保留 Flowable 原始候选组顺序，roleIds/deptIds 用于分类型执行有界 RBAC 查询。
        LinkedHashSet<String> requestedGroups = new LinkedHashSet<>();
        LinkedHashSet<Long> roleIds = new LinkedHashSet<>();
        LinkedHashSet<Long> deptIds = new LinkedHashSet<>();
        for (String candidateGroup : candidateGroups)
        {
            // Codec 同时拒绝 ROLE007 等无法与运行时当前用户组精确匹配的非规范编码。
            WorkflowCandidateGroup parsedGroup = identityCodec.parseCandidateGroup(candidateGroup);
            requestedGroups.add(candidateGroup);
            if (parsedGroup.type() == WorkflowCandidateGroupType.ROLE)
            {
                roleIds.add(parsedGroup.id());
            }
            else
            {
                deptIds.add(parsedGroup.id());
            }
        }
        if (requestedGroups.isEmpty())
        {
            return Set.of();
        }

        // 合格集合按组类型独立计算，最终逐个映射回原编码，避免一个有效组掩盖同批失效组。
        Set<Long> eligibleRoleIds = roleIds.isEmpty() ? Set.of()
                : checkedIdSet(identityMapper.selectClaimEligibleRoleIdsByRoleIds(
                        new ArrayList<>(roleIds)));
        Set<Long> eligibleDeptIds = deptIds.isEmpty() ? Set.of()
                : checkedIdSet(identityMapper.selectClaimEligibleDeptIdsByDeptIds(
                        new ArrayList<>(deptIds)));
        LinkedHashSet<String> resolvedGroups = new LinkedHashSet<>();
        for (String requestedGroup : requestedGroups)
        {
            WorkflowCandidateGroup parsedGroup = identityCodec.parseCandidateGroup(requestedGroup);
            boolean eligible = parsedGroup.type() == WorkflowCandidateGroupType.ROLE
                    ? eligibleRoleIds.contains(parsedGroup.id())
                    : eligibleDeptIds.contains(parsedGroup.id());
            if (eligible)
            {
                resolvedGroups.add(requestedGroup);
            }
        }
        return Collections.unmodifiableSet(resolvedGroups);
    }

    /**
     * 查询角色候选组对应的有效用户并追加到去重结果。
     *
     * @param roleIds Set&lt;Long&gt;，已校验的角色主键
     * @param resolvedUserIds Set&lt;String&gt;，待追加的用户结果集合
     * @return 无返回值，查询结果直接写入 resolvedUserIds
     */
    private void appendActiveUsersByRoles(Set<Long> roleIds, Set<String> resolvedUserIds)
    {
        if (!roleIds.isEmpty())
        {
            checkedIds(identityMapper.selectActiveUserIdsByRoleIds(new ArrayList<>(roleIds)))
                    .forEach(userId -> resolvedUserIds.add(String.valueOf(userId)));
        }
    }

    /**
     * 查询部门候选组对应的有效用户并追加到去重结果。
     *
     * @param deptIds Set&lt;Long&gt;，已校验的部门主键
     * @param resolvedUserIds Set&lt;String&gt;，待追加的用户结果集合
     * @return 无返回值，查询结果直接写入 resolvedUserIds
     */
    private void appendActiveUsersByDepts(Set<Long> deptIds, Set<String> resolvedUserIds)
    {
        if (!deptIds.isEmpty())
        {
            checkedIds(identityMapper.selectActiveUserIdsByDeptIds(new ArrayList<>(deptIds)))
                    .forEach(userId -> resolvedUserIds.add(String.valueOf(userId)));
        }
    }

    /**
     * 校验 Mapper 返回的主键集合，主数据异常时停止身份扩散。
     *
     * @param ids List&lt;Long&gt;，Mapper 返回的用户、角色或部门主键
     * @return List&lt;Long&gt;，非空且全部为正整数的原顺序集合
     */
    private List<Long> checkedIds(List<Long> ids)
    {
        if (ids == null)
        {
            throw new ServiceException(INVALID_MASTER_DATA_MESSAGE, HttpStatus.ERROR);
        }
        for (Long id : ids)
        {
            if (id == null || id <= 0)
            {
                throw new ServiceException(INVALID_MASTER_DATA_MESSAGE, HttpStatus.ERROR);
            }
        }
        return ids;
    }

    /**
     * 将已校验的 Mapper 主键列表转换为便于成员判断的集合。
     *
     * @param ids List&lt;Long&gt;，Mapper 返回的主键
     * @return Set&lt;Long&gt;，去重后的有效主键集合
     */
    private Set<Long> checkedIdSet(List<Long> ids)
    {
        return new HashSet<>(checkedIds(ids));
    }
}

package com.ruoyi.flowable.identity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 校验客户端选择的工作流用户集合，只允许正式主数据中的有效且不重复用户进入写命令。
 */
@Component
public class WorkflowUserSelectionValidator
{
    /** 单次工作流动作允许选择的最大用户数量。 */
    public static final int MAX_SELECTED_USERS = 100;

    /** 用户选择非法时返回的稳定业务提示。 */
    private static final String INVALID_SELECTION_MESSAGE = "工作流用户选择不合法";

    /** 目标用户不具备流程办理资格时返回的稳定业务提示。 */
    private static final String APPROVAL_INELIGIBLE_MESSAGE =
            "所选用户不存在、已停用或无流程办理权限";

    /** 候选用户缺少完整认领路径权限时返回的稳定业务提示。 */
    private static final String CLAIM_INELIGIBLE_MESSAGE =
            "所选候选用户不存在、已停用或无完整认领权限";

    private final WorkflowIdentityResolver identityResolver;

    /**
     * 创建工作流用户选择校验器。
     *
     * @param identityResolver WorkflowIdentityResolver，正式身份主数据解析器
     * @return 无返回值，构造后由 Spring 管理该组件
     */
    public WorkflowUserSelectionValidator(WorkflowIdentityResolver identityResolver)
    {
        this.identityResolver = identityResolver;
    }

    /**
     * 严格校验可选用户集合，重复、停用、删除、不存在及越界用户都会使整次请求失败。
     *
     * @param requestedUserIds List&lt;Long&gt;，客户端提交的用户主键；null 等同空集合
     * @return List&lt;String&gt;，保持请求顺序的规范数字用户主键，不可修改
     */
    public List<String> requireActiveUserIds(List<Long> requestedUserIds)
    {
        LinkedHashSet<Long> requestedIds = normalizeRequestedIds(requestedUserIds);
        List<String> canonicalIds = requestedIds.stream().map(String::valueOf).toList();
        if (canonicalIds.isEmpty())
        {
            return List.of();
        }
        Set<String> activeIds;
        try
        {
            activeIds = identityResolver.resolveActiveUserIds(canonicalIds, List.of());
        }
        catch (RuntimeException exception)
        {
            // 身份解析细节不向客户端暴露，所有非法选择统一映射为参数错误。
            throw invalidSelection();
        }
        if (activeIds.size() != canonicalIds.size()
                || !activeIds.equals(new LinkedHashSet<>(canonicalIds)))
        {
            throw invalidSelection();
        }
        return List.copyOf(new ArrayList<>(canonicalIds));
    }

    /**
     * 严格校验动态加签或下一节点指定的用户均具备实时流程办理资格。
     *
     * @param requestedUserIds List&lt;Long&gt;，客户端提交的目标用户主键；null 等同空集合
     * @return List&lt;String&gt;，保持请求顺序的规范审批用户主键，不可修改
     */
    public List<String> requireApprovalEligibleUserIds(List<Long> requestedUserIds)
    {
        LinkedHashSet<Long> requestedIds = normalizeRequestedIds(requestedUserIds);
        List<String> canonicalIds = requestedIds.stream().map(String::valueOf).toList();
        if (canonicalIds.isEmpty())
        {
            return List.of();
        }

        Set<String> eligibleIds;
        try
        {
            eligibleIds = identityResolver.resolveApprovalEligibleUserIds(canonicalIds);
        }
        catch (RuntimeException exception)
        {
            // 权限主数据查询失败按整批不可选处理，不得让部分用户进入后续 Flowable 写命令。
            throw approvalIneligible();
        }
        if (eligibleIds.size() != canonicalIds.size()
                || !eligibleIds.equals(new LinkedHashSet<>(canonicalIds)))
        {
            throw approvalIneligible();
        }
        return List.copyOf(new ArrayList<>(canonicalIds));
    }

    /**
     * 严格校验写入 candidateUser 的用户均具备待签、认领和后续办理完整资格。
     *
     * @param requestedUserIds List&lt;Long&gt;，客户端提交的候选用户主键；null 等同空集合
     * @return List&lt;String&gt;，保持请求顺序的规范候选用户主键，不可修改
     */
    public List<String> requireClaimEligibleUserIds(List<Long> requestedUserIds)
    {
        LinkedHashSet<Long> requestedIds = normalizeRequestedIds(requestedUserIds);
        List<String> canonicalIds = requestedIds.stream().map(String::valueOf).toList();
        if (canonicalIds.isEmpty())
        {
            return List.of();
        }

        Set<String> eligibleIds;
        try
        {
            eligibleIds = identityResolver.resolveClaimEligibleUserIds(canonicalIds);
        }
        catch (RuntimeException exception)
        {
            // 认领权限主数据异常必须整批失败，不能把部分 candidateUser 写入正式任务。
            throw claimIneligible();
        }
        if (eligibleIds.size() != canonicalIds.size()
                || !eligibleIds.equals(new LinkedHashSet<>(canonicalIds)))
        {
            throw claimIneligible();
        }
        return List.copyOf(new ArrayList<>(canonicalIds));
    }

    /**
     * 规范用户选择并在数据库访问前拒绝空主键、非正数、重复和超量请求。
     *
     * @param requestedUserIds List&lt;Long&gt;，客户端提交的原始用户主键集合
     * @return LinkedHashSet&lt;Long&gt;，保持请求顺序且已完成基础校验的用户主键
     */
    private LinkedHashSet<Long> normalizeRequestedIds(List<Long> requestedUserIds)
    {
        if (requestedUserIds == null || requestedUserIds.isEmpty())
        {
            return new LinkedHashSet<>();
        }
        if (requestedUserIds.size() > MAX_SELECTED_USERS)
        {
            throw invalidSelection();
        }

        // requestedIds 同时承担顺序保持和重复检测，避免服务端静默合并用户选择。
        LinkedHashSet<Long> requestedIds = new LinkedHashSet<>();
        for (Long requestedUserId : requestedUserIds)
        {
            if (requestedUserId == null || requestedUserId <= 0
                    || !requestedIds.add(requestedUserId))
            {
                throw invalidSelection();
            }
        }
        return requestedIds;
    }

    /**
     * 创建稳定的用户选择参数异常。
     *
     * @return ServiceException，HTTP 400 参数异常
     */
    private ServiceException invalidSelection()
    {
        return new ServiceException(INVALID_SELECTION_MESSAGE, HttpStatus.BAD_REQUEST);
    }

    /**
     * 创建稳定的审批资格异常，避免暴露底层用户、角色或菜单数据细节。
     *
     * @return ServiceException，HTTP 400 审批目标用户不可选异常
     */
    private ServiceException approvalIneligible()
    {
        return new ServiceException(APPROVAL_INELIGIBLE_MESSAGE, HttpStatus.BAD_REQUEST);
    }

    /**
     * 创建稳定的候选认领资格异常，避免暴露底层权限组合和角色关系。
     *
     * @return ServiceException，HTTP 400 候选用户不可选异常
     */
    private ServiceException claimIneligible()
    {
        return new ServiceException(CLAIM_INELIGIBLE_MESSAGE, HttpStatus.BAD_REQUEST);
    }
}

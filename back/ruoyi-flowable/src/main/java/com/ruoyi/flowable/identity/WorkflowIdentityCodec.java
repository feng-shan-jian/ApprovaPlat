package com.ruoyi.flowable.identity;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 统一解析和生成工作流用户、角色组及部门组标识。
 */
@Component
public class WorkflowIdentityCodec
{
    /** 角色候选组前缀，存量流程和新流程统一使用该格式。 */
    public static final String ROLE_GROUP_PREFIX = "ROLE";

    /** 部门候选组前缀，存量流程和新流程统一使用该格式。 */
    public static final String DEPT_GROUP_PREFIX = "DEPT";

    /** 仅接受不带符号、小数点和空白的十进制数字。 */
    private static final Pattern DECIMAL_ID = Pattern.compile("[0-9]+");

    /** 用户标识非法时返回给调用方的稳定提示。 */
    private static final String INVALID_USER_ID_MESSAGE = "工作流用户标识无效";

    /** 候选组标识非法时返回给调用方的稳定提示。 */
    private static final String INVALID_GROUP_ID_MESSAGE = "工作流候选组标识无效";

    /**
     * 校验并规范化若依用户 ID。
     *
     * @param userId String，Flowable 中保存的用户标识
     * @return String，去除无意义前导零后的正整数用户 ID
     */
    public String normalizeUserId(String userId)
    {
        return Long.toString(parsePositiveId(userId, INVALID_USER_ID_MESSAGE));
    }

    /**
     * 解析可与 Flowable 当前用户组精确匹配的规范角色或部门候选组。
     *
     * @param candidateGroup String，格式必须为无前导零的 ROLE&lt;id&gt; 或 DEPT&lt;id&gt;
     * @return WorkflowCandidateGroup，候选组类型及正整数主键
     */
    public WorkflowCandidateGroup parseCandidateGroup(String candidateGroup)
    {
        if (candidateGroup == null)
        {
            throw invalidIdentity(INVALID_GROUP_ID_MESSAGE);
        }

        if (candidateGroup.startsWith(ROLE_GROUP_PREFIX))
        {
            long roleId = parsePositiveId(candidateGroup.substring(ROLE_GROUP_PREFIX.length()),
                    INVALID_GROUP_ID_MESSAGE);
            // Flowable 按字符串精确匹配候选组，前导零等非规范编码即使能解析也永远无法与当前用户组命中。
            if (!candidateGroup.equals(ROLE_GROUP_PREFIX + roleId))
            {
                throw invalidIdentity(INVALID_GROUP_ID_MESSAGE);
            }
            return new WorkflowCandidateGroup(WorkflowCandidateGroupType.ROLE, roleId);
        }
        if (candidateGroup.startsWith(DEPT_GROUP_PREFIX))
        {
            long deptId = parsePositiveId(candidateGroup.substring(DEPT_GROUP_PREFIX.length()),
                    INVALID_GROUP_ID_MESSAGE);
            // 部门组同样按规范字符串参与精确候选匹配，不能只验证其数字部分可解析。
            if (!candidateGroup.equals(DEPT_GROUP_PREFIX + deptId))
            {
                throw invalidIdentity(INVALID_GROUP_ID_MESSAGE);
            }
            return new WorkflowCandidateGroup(WorkflowCandidateGroupType.DEPT, deptId);
        }
        throw invalidIdentity(INVALID_GROUP_ID_MESSAGE);
    }

    /**
     * 生成角色候选组标识。
     *
     * @param roleId long，若依角色主键
     * @return String，ROLE&lt;roleId&gt; 格式的候选组
     */
    public String roleGroup(long roleId)
    {
        return ROLE_GROUP_PREFIX + parsePositiveId(Long.toString(roleId), INVALID_GROUP_ID_MESSAGE);
    }

    /**
     * 生成部门候选组标识。
     *
     * @param deptId long，若依部门主键
     * @return String，DEPT&lt;deptId&gt; 格式的候选组
     */
    public String deptGroup(long deptId)
    {
        return DEPT_GROUP_PREFIX + parsePositiveId(Long.toString(deptId), INVALID_GROUP_ID_MESSAGE);
    }

    /**
     * 解析正整数数据库主键，统一拒绝空值、符号、小数、零、负数和 long 溢出。
     *
     * @param rawId String，待解析的十进制主键
     * @param errorMessage String，校验失败时的稳定业务提示
     * @return long，已校验的正整数主键
     */
    private long parsePositiveId(String rawId, String errorMessage)
    {
        if (rawId == null || !DECIMAL_ID.matcher(rawId).matches())
        {
            throw invalidIdentity(errorMessage);
        }
        try
        {
            long id = Long.parseLong(rawId);
            if (id <= 0)
            {
                throw invalidIdentity(errorMessage);
            }
            return id;
        }
        catch (NumberFormatException exception)
        {
            // 溢出值不能进入 Flowable 或数据库查询，且响应中不回显原始输入。
            throw invalidIdentity(errorMessage);
        }
    }

    /**
     * 创建身份格式错误业务异常。
     *
     * @param message String，对外稳定错误提示
     * @return ServiceException，HTTP 400 语义的业务异常
     */
    private ServiceException invalidIdentity(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }
}

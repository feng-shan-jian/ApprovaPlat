package com.ruoyi.flowable.service.task;

import java.util.regex.Pattern;

/**
 * 动态多实例变量命名契约，集中管理客户端集合变量和服务端保留变量。
 */
public final class WorkflowMultiInstanceVariables
{
    /** 客户端不可直接提交、仅由受控领域服务写入的多实例用户集合前缀。 */
    public static final String USER_COLLECTION_PREFIX = "wfMiUsers_";

    /** 服务端当前正式成员快照变量前缀。 */
    public static final String MEMBER_SNAPSHOT_PREFIX = "_wfMiMembers_";

    /** 服务端并发修订号变量前缀。 */
    public static final String REVISION_PREFIX = "_wfMiRevision_";

    /** 服务端完成模式变量前缀。 */
    public static final String MODE_PREFIX = "_wfMiMode_";

    /** 受控 BPMN 活动 ID 的最大字符数。 */
    public static final int MAX_ACTIVITY_ID_LENGTH = 64;

    /** BPMN 活动 ID 只允许字母开头以及字母、数字、下划线和连字符。 */
    private static final Pattern ACTIVITY_ID_PATTERN = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_-]{0," + (MAX_ACTIVITY_ID_LENGTH - 1) + "}");

    /**
     * 禁止实例化纯变量命名工具类。
     *
     * @return 无返回值，调用时始终抛出 AssertionError
     */
    private WorkflowMultiInstanceVariables()
    {
        throw new AssertionError("动态多实例变量工具类不能实例化");
    }

    /**
     * 生成指定活动的受控用户集合变量名。
     *
     * @param activityId String，符合受控语法的 BPMN 活动 ID
     * @return String，形如 wfMiUsers_approveTask 的变量名
     */
    public static String userCollectionName(String activityId)
    {
        return USER_COLLECTION_PREFIX + requireActivityId(activityId);
    }

    /**
     * 生成指定活动的服务端正式成员快照变量名。
     *
     * @param activityId String，符合受控语法的 BPMN 活动 ID
     * @return String，形如 _wfMiMembers_approveTask 的变量名
     */
    public static String memberSnapshotName(String activityId)
    {
        return MEMBER_SNAPSHOT_PREFIX + requireActivityId(activityId);
    }

    /**
     * 生成指定活动的服务端修订号变量名。
     *
     * @param activityId String，符合受控语法的 BPMN 活动 ID
     * @return String，形如 _wfMiRevision_approveTask 的变量名
     */
    public static String revisionName(String activityId)
    {
        return REVISION_PREFIX + requireActivityId(activityId);
    }

    /**
     * 生成指定活动的服务端完成模式变量名。
     *
     * @param activityId String，符合受控语法的 BPMN 活动 ID
     * @return String，形如 _wfMiMode_approveTask 的变量名
     */
    public static String modeName(String activityId)
    {
        return MODE_PREFIX + requireActivityId(activityId);
    }

    /**
     * 判断变量名是否属于动态多实例协议，供表单和普通变量接口阻断客户端覆盖。
     *
     * @param variableName String，待检查的变量名，可为 null
     * @return boolean，命中集合变量或任一服务端保留变量前缀时返回 true
     */
    public static boolean isReservedVariableName(String variableName)
    {
        return variableName != null && (variableName.startsWith(USER_COLLECTION_PREFIX)
                || variableName.startsWith(MEMBER_SNAPSHOT_PREFIX)
                || variableName.startsWith(REVISION_PREFIX)
                || variableName.startsWith(MODE_PREFIX));
    }

    /**
     * 严格校验活动 ID，防止变量命名碰撞和任意变量访问。
     *
     * @param activityId String，客户端不可控的 BPMN 活动 ID
     * @return String，校验通过的原始活动 ID
     */
    public static String requireActivityId(String activityId)
    {
        if (activityId == null || !ACTIVITY_ID_PATTERN.matcher(activityId).matches())
        {
            throw new IllegalArgumentException("工作流多实例活动标识不合法");
        }
        return activityId;
    }
}

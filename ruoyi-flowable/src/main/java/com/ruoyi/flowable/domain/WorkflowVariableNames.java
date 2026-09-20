package com.ruoyi.flowable.domain;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 纯变量名协议；不依赖写入变量的流程、任务或快照服务。 */
public final class WorkflowVariableNames
{
    public static final String MI_USERS_PREFIX = "wfMiUsers_";
    public static final String MI_MEMBERS_PREFIX = "_wfMiMembers_";
    public static final String MI_REVISION_PREFIX = "_wfMiRevision_";
    public static final String MI_MODE_PREFIX = "_wfMiMode_";
    public static final String MI_COMPLETION_REVISION = "_wfMiCompletionRevision";
    public static final String LOOP_ROUTE_PREFIX = "__approva_loop_route_";
    public static final String LOOP_ITERATION_PREFIX = "__approva_loop_iteration_";
    public static final String FORM_SNAPSHOT_PREFIX = "__ruoyi_workflow_";

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Set<String> ENGINE_VARIABLES = Set.of(
            "initiator", "processStatus", "processInstanceId", "processDefinitionId",
            "deploymentId", "startUserId", "authenticatedUserId", "businessKey",
            "assignee", "nrOfInstances", "nrOfActiveInstances", "nrOfCompletedInstances",
            "loopCounter", "_FLOWABLE_SKIP_EXPRESSION_ENABLED", MI_COMPLETION_REVISION);
    private static final Set<String> INTERNAL_PREFIXES = Set.of(MI_USERS_PREFIX, MI_MEMBERS_PREFIX,
            MI_REVISION_PREFIX, MI_MODE_PREFIX, LOOP_ROUTE_PREFIX, LOOP_ITERATION_PREFIX, FORM_SNAPSHOT_PREFIX);
    private static final Set<String> UNSAFE_OBJECT_KEYS = Set.of("__proto__", "prototype", "constructor");
    private static final Set<String> JAVA_NAMESPACES = Set.of("ACT_", "FLOWABLE_", "__", "WF_");

    private WorkflowVariableNames() { }

    /** 连接器只读输入保留已有标识符契约；读取系统变量不等于允许覆盖。 */
    public static boolean isIdentifier(String name)
    {
        return name != null && IDENTIFIER.matcher(name).matches();
    }

    /** 引擎变量区分大小写；不把 wfSignalName 等业务回显归为内部变量。 */
    public static boolean isReserved(String name)
    {
        return name != null && (ENGINE_VARIABLES.contains(name)
                || INTERNAL_PREFIXES.stream().anyMatch(name::startsWith));
    }

    /** JSON 顶层和嵌套键沿用大小写不敏感的原型保护。 */
    public static boolean isUnsafeObjectKey(String name)
    {
        return name != null && UNSAFE_OBJECT_KEYS.contains(name.toLowerCase(Locale.ROOT));
    }

    /** 表单、DMN、数据对象及普通扩展输出只能拥有业务变量。 */
    public static boolean isBusinessVariable(String name)
    {
        return isIdentifier(name) && !isReserved(name) && !isUnsafeObjectKey(name);
    }

    /** Java 处理器保留原有更严格的安装包命名空间限制。 */
    public static boolean isJavaOutputVariable(String name)
    {
        return isBusinessVariable(name)
                && JAVA_NAMESPACES.stream().noneMatch(name.toUpperCase(Locale.ROOT)::startsWith);
    }
}

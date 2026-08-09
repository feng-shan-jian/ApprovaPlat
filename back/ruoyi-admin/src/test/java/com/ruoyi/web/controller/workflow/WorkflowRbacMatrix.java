package com.ruoyi.web.controller.workflow;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * 五角色工作流 URL 权限矩阵的测试侧唯一解析与源码盘点工具。
 */
final class WorkflowRbacMatrix
{
    /** 机器可读权限矩阵在测试 classpath 中的固定资源名。 */
    static final String RESOURCE_NAME = "workflow-rbac-matrix.csv";

    /** 五个受管工作流角色，顺序同时冻结 CSV 列顺序和验收报告顺序。 */
    static final List<String> ROLE_KEYS = List.of(
            "workflow_admin",
            "workflow_designer",
            "workflow_starter",
            "workflow_approver",
            "workflow_auditor");

    /** 正式工作流 HTTP Controller 白名单。 */
    static final List<Class<?>> CONTROLLERS = List.of(
            WfAttachmentController.class,
            WfCategoryController.class,
            WfDeployController.class,
            WfDesignerController.class,
            WfConnectorController.class,
            WfDmnController.class,
            WfExtensionController.class,
            WfBpmnEventController.class,
            WfCallActivityController.class,
            WfTaskSlaController.class,
            WfSqlDataSourceController.class,
            WfIntegrationCredentialController.class,
            WfRuntimeEventAuditController.class,
            WfCollaborationController.class,
            WfNotificationController.class,
            WfFormController.class,
            WfIdentityController.class,
            WfInstanceController.class,
            WfModelController.class,
            WfProcessController.class,
            WfProcessDraftController.class,
            WfTaskController.class);

    /** CSV 固定列定义，禁止静默接受少列、多列或角色顺序漂移。 */
    private static final List<String> CSV_HEADER = List.of(
            "controller", "handler", "httpMethod", "path", "permissionMode",
            "permissions", "workflow_admin", "workflow_designer", "workflow_starter",
            "workflow_approver", "workflow_auditor");

    /** 方法权限表达式只允许单权限或任一权限两种受控格式。 */
    private static final Pattern PERMISSION_EXPRESSION = Pattern.compile(
            "^@ss\\.(hasPermi|hasAnyPermi)\\('([^']+)'\\)$");

    /** SQL 角色片段中工作流权限字符串的提取规则。 */
    private static final Pattern WORKFLOW_PERMISSION = Pattern.compile(
            "'((?:workflow):[^']+)'", Pattern.CASE_INSENSITIVE);

    /** 工具类不允许实例化。 */
    private WorkflowRbacMatrix()
    {
    }

    /**
     * 从 UTF-8 classpath 资源读取并严格解析五角色权限矩阵。
     *
     * @return List&lt;Endpoint&gt;，保持 CSV 顺序且每行包含五角色显式预期
     */
    static List<Endpoint> load()
    {
        InputStream input = WorkflowRbacMatrix.class.getClassLoader()
                .getResourceAsStream(RESOURCE_NAME);
        if (input == null)
        {
            throw new IllegalStateException("未找到工作流 RBAC 矩阵资源: " + RESOURCE_NAME);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8)))
        {
            String header = reader.readLine();
            if (header == null || !Arrays.asList(header.split(",", -1)).equals(CSV_HEADER))
            {
                throw new IllegalStateException("工作流 RBAC 矩阵表头不符合冻结契约");
            }
            List<Endpoint> endpoints = new ArrayList<>();
            Set<String> keys = new LinkedHashSet<>();
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null)
            {
                lineNumber++;
                if (line.isBlank())
                {
                    continue;
                }
                Endpoint endpoint = parseEndpoint(line, lineNumber);
                if (!keys.add(endpoint.key()))
                {
                    throw new IllegalStateException("工作流 RBAC 矩阵入口重复: "
                            + endpoint.key());
                }
                endpoints.add(endpoint);
            }
            return List.copyOf(endpoints);
        }
        catch (IOException exception)
        {
            throw new IllegalStateException("无法读取工作流 RBAC 矩阵", exception);
        }
    }

    /**
     * 反射盘点白名单 Controller 的全部显式方法级 mapping 和权限规则。
     *
     * @return Map&lt;String, InventoryEndpoint&gt;，键为 Controller#handler 的真实源码清单
     */
    static Map<String, InventoryEndpoint> reflectInventory()
    {
        Map<String, InventoryEndpoint> inventory = new LinkedHashMap<>();
        for (Class<?> controller : CONTROLLERS)
        {
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(
                    controller, RequestMapping.class);
            if (classMapping == null)
            {
                throw new IllegalStateException(controller.getSimpleName()
                        + " 缺少类级 RequestMapping");
            }
            String basePath = singlePath(classMapping, controller.getSimpleName());
            for (Method method : controller.getDeclaredMethods())
            {
                RequestMapping methodMapping = AnnotatedElementUtils.findMergedAnnotation(
                        method, RequestMapping.class);
                if (methodMapping == null)
                {
                    continue;
                }
                RequestMethod httpMethod = singleHttpMethod(methodMapping,
                        controller.getSimpleName() + "#" + method.getName());
                String methodPath = singlePath(methodMapping,
                        controller.getSimpleName() + "#" + method.getName());
                PreAuthorize preAuthorize = AnnotatedElementUtils.findMergedAnnotation(
                        method, PreAuthorize.class);
                if (preAuthorize == null)
                {
                    throw new IllegalStateException(controller.getSimpleName() + "#"
                            + method.getName() + " 缺少 PreAuthorize");
                }
                PermissionRule rule = parsePermissionRule(preAuthorize.value());
                InventoryEndpoint endpoint = new InventoryEndpoint(
                        controller.getSimpleName(), method.getName(), httpMethod.name(),
                        joinPath(basePath, methodPath), rule.mode(), rule.permissions(),
                        preAuthorize.value());
                if (inventory.put(endpoint.key(), endpoint) != null)
                {
                    throw new IllegalStateException("Controller mapping 重复: "
                            + endpoint.key());
                }
            }
        }
        return Map.copyOf(inventory);
    }

    /**
     * 从正式菜单 SQL 解析五角色实际应获得的全部 workflow 权限。
     *
     * @return Map&lt;String, Set&lt;String&gt;&gt;，角色键到正式权限集合的映射
     */
    static Map<String, Set<String>> loadRolePermissions()
    {
        Path sqlPath = findProjectFile("sql/flowable/menu/8.0.0__workflow_menu.sql");
        try
        {
            String sql = Files.readString(sqlPath, StandardCharsets.UTF_8);
            Map<String, Set<String>> permissions = new LinkedHashMap<>();
            permissions.put("workflow_admin", extractPermissions(extractSection(sql,
                    "INSERT INTO tmp_workflow_menu_seed",
                    "-- 目录按 path、页面和按钮按 perms 写入")));
            permissions.put("workflow_designer", extractPermissions(extractSection(sql,
                    "-- 流程设计者仅管理分类",
                    "-- 发起人只发起")));
            permissions.put("workflow_starter", extractPermissions(extractSection(sql,
                    "-- 发起人只发起",
                    "-- 审批人可认领")));
            permissions.put("workflow_approver", extractPermissions(extractSection(sql,
                    "-- 审批人可认领",
                    "-- 审计角色只有列表")));
            permissions.put("workflow_auditor", extractPermissions(extractSection(sql,
                    "-- 审计角色只有列表",
                    "-- 仅重建五个受管角色")));
            return Map.copyOf(permissions);
        }
        catch (IOException exception)
        {
            throw new IllegalStateException("无法读取正式工作流菜单 SQL", exception);
        }
    }

    /**
     * 解析一行固定无引号 CSV；权限列表内部使用竖线，避免逗号歧义。
     *
     * @param line String，待解析的非空 CSV 行
     * @param lineNumber int，错误提示使用的一基行号
     * @return Endpoint，完成枚举校验和五角色列校验的矩阵入口
     */
    private static Endpoint parseEndpoint(String line, int lineNumber)
    {
        String[] columns = line.split(",", -1);
        if (columns.length != CSV_HEADER.size())
        {
            throw new IllegalStateException("工作流 RBAC 矩阵第 " + lineNumber
                    + " 行列数错误");
        }
        PermissionMode permissionMode = parseEnum(PermissionMode.class,
                columns[4], lineNumber, "permissionMode");
        List<String> permissions = columns[5].isBlank()
                ? List.of()
                : List.copyOf(Arrays.asList(columns[5].split("\\|", -1)));
        if ((permissionMode == PermissionMode.AUTHENTICATED) != permissions.isEmpty())
        {
            throw new IllegalStateException("工作流 RBAC 矩阵第 " + lineNumber
                    + " 行权限模式与权限列表不一致");
        }

        Map<String, Access> roleAccess = new LinkedHashMap<>();
        for (int index = 0; index < ROLE_KEYS.size(); index++)
        {
            roleAccess.put(ROLE_KEYS.get(index), parseEnum(Access.class,
                    columns[index + 6], lineNumber, ROLE_KEYS.get(index)));
        }
        return new Endpoint(columns[0], columns[1], columns[2], columns[3],
                permissionMode, permissions, Map.copyOf(roleAccess));
    }

    /**
     * 把 CSV 枚举值转换为指定枚举，并把非法值定位到稳定行列。
     *
     * @param enumType Class&lt;E&gt;，目标枚举类型
     * @param value String，CSV 原始枚举值
     * @param lineNumber int，CSV 一基行号
     * @param columnName String，CSV 列名
     * @param <E> 枚举类型
     * @return E，严格匹配的枚举值
     */
    private static <E extends Enum<E>> E parseEnum(Class<E> enumType, String value,
            int lineNumber, String columnName)
    {
        try
        {
            return Enum.valueOf(enumType, value);
        }
        catch (IllegalArgumentException exception)
        {
            throw new IllegalStateException("工作流 RBAC 矩阵第 " + lineNumber
                    + " 行 " + columnName + " 非法", exception);
        }
    }

    /**
     * 将源码 PreAuthorize 表达式归一为矩阵使用的权限规则。
     *
     * @param expression String，方法上的完整 PreAuthorize 表达式
     * @return PermissionRule，认证模式或受控权限集合
     */
    private static PermissionRule parsePermissionRule(String expression)
    {
        if ("isAuthenticated()".equals(expression))
        {
            return new PermissionRule(PermissionMode.AUTHENTICATED, List.of());
        }
        Matcher matcher = PERMISSION_EXPRESSION.matcher(expression);
        if (!matcher.matches())
        {
            throw new IllegalStateException("工作流权限表达式不在冻结白名单内: " + expression);
        }
        PermissionMode mode = "hasPermi".equals(matcher.group(1))
                ? PermissionMode.SINGLE : PermissionMode.ANY;
        List<String> permissions = List.copyOf(
                Arrays.asList(matcher.group(2).split(",", -1)));
        if (mode == PermissionMode.SINGLE && permissions.size() != 1)
        {
            throw new IllegalStateException("hasPermi 只能声明一个权限: " + expression);
        }
        return new PermissionRule(mode, permissions);
    }

    /**
     * 从 RequestMapping 中读取唯一显式路径，空路径表示类根路径本身。
     *
     * @param mapping RequestMapping，已合并的类级或方法级映射
     * @param owner String，错误提示使用的映射所有者
     * @return String，唯一映射路径或空字符串
     */
    private static String singlePath(RequestMapping mapping, String owner)
    {
        String[] paths = mapping.path().length == 0 ? mapping.value() : mapping.path();
        if (paths.length == 0)
        {
            return "";
        }
        if (paths.length != 1)
        {
            throw new IllegalStateException(owner + " 必须只有一个显式 mapping 路径");
        }
        return paths[0];
    }

    /**
     * 从方法级 RequestMapping 中读取唯一 HTTP 动词。
     *
     * @param mapping RequestMapping，已合并的方法级映射
     * @param owner String，错误提示使用的 Controller#handler
     * @return RequestMethod，唯一 HTTP 动词
     */
    private static RequestMethod singleHttpMethod(RequestMapping mapping, String owner)
    {
        if (mapping.method().length != 1)
        {
            throw new IllegalStateException(owner + " 必须只有一个显式 HTTP 动词");
        }
        return mapping.method()[0];
    }

    /**
     * 合并类级和方法级路径并保持根路径格式稳定。
     *
     * @param basePath String，Controller 类级路径
     * @param methodPath String，方法级路径，允许为空
     * @return String，以斜线开头且不含重复斜线的完整路径
     */
    private static String joinPath(String basePath, String methodPath)
    {
        if (methodPath.isEmpty())
        {
            return basePath;
        }
        if (basePath.endsWith("/") && methodPath.startsWith("/"))
        {
            return basePath + methodPath.substring(1);
        }
        if (!basePath.endsWith("/") && !methodPath.startsWith("/"))
        {
            return basePath + "/" + methodPath;
        }
        return basePath + methodPath;
    }

    /**
     * 截取正式 SQL 中两个稳定注释标记之间的角色授权片段。
     *
     * @param content String，完整正式 SQL
     * @param beginMarker String，片段开始标记
     * @param endMarker String，片段结束标记
     * @return String，只包含目标角色授权片段
     */
    private static String extractSection(String content, String beginMarker,
            String endMarker)
    {
        int begin = content.indexOf(beginMarker);
        int end = content.indexOf(endMarker, begin + beginMarker.length());
        if (begin < 0 || end <= begin)
        {
            throw new IllegalStateException("正式工作流菜单 SQL 标记缺失: "
                    + beginMarker + " -> " + endMarker);
        }
        return content.substring(begin, end);
    }

    /**
     * 提取 SQL 片段内不重复的 workflow 权限字符串。
     *
     * @param section String，菜单种子或单角色授权 SQL 片段
     * @return Set&lt;String&gt;，保持首次出现顺序的正式权限集合
     */
    private static Set<String> extractPermissions(String section)
    {
        Set<String> permissions = new LinkedHashSet<>();
        Matcher matcher = WORKFLOW_PERMISSION.matcher(section);
        while (matcher.find())
        {
            permissions.add(matcher.group(1));
        }
        if (permissions.isEmpty())
        {
            throw new IllegalStateException("正式角色授权 SQL 未提取到 workflow 权限");
        }
        return Set.copyOf(permissions);
    }

    /**
     * 从 Maven 模块或聚合工程工作目录向上定位正式项目文件。
     *
     * @param relativePath String，以 back 目录为根的相对路径
     * @return Path，存在且可读的正式文件绝对路径
     */
    private static Path findProjectFile(String relativePath)
    {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null)
        {
            Path direct = current.resolve(relativePath);
            if (Files.isRegularFile(direct))
            {
                return direct;
            }
            Path underBack = current.resolve("back").resolve(relativePath);
            if (Files.isRegularFile(underBack))
            {
                return underBack;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未找到正式项目文件: " + relativePath);
    }

    /** URL 层权限预期。 */
    enum Access
    {
        /** 角色具备方法级权限；需要独立业务 fixture 验证对象授权和业务状态。 */
        ALLOW,

        /** 角色必须在方法业务逻辑执行前被拒绝。 */
        DENY
    }

    /** PreAuthorize 权限表达式模式。 */
    enum PermissionMode
    {
        /** 仅要求存在真实认证身份。 */
        AUTHENTICATED,

        /** 要求唯一权限。 */
        SINGLE,

        /** 多个权限中满足任意一个。 */
        ANY
    }

    /**
     * CSV 中一条正式 HTTP 入口及五角色预期。
     *
     * @param controller String，Controller 简单类名
     * @param handler String，映射方法名
     * @param httpMethod String，大写 HTTP 动词
     * @param path String，完整 Spring mapping 路径模板
     * @param permissionMode PermissionMode，权限表达式模式
     * @param permissions List&lt;String&gt;，方法要求的权限集合
     * @param roleAccess Map&lt;String, Access&gt;，五角色 URL 层预期
     */
    record Endpoint(String controller, String handler, String httpMethod, String path,
            PermissionMode permissionMode, List<String> permissions,
            Map<String, Access> roleAccess)
    {
        /**
         * 返回用于矩阵、反射清单和报告关联的稳定入口键。
         *
         * @return String，Controller#handler
         */
        String key()
        {
            return controller + "#" + handler;
        }
    }

    /**
     * 从真实 Controller 反射得到的一条入口契约。
     *
     * @param controller String，Controller 简单类名
     * @param handler String，映射方法名
     * @param httpMethod String，大写 HTTP 动词
     * @param path String，完整 Spring mapping 路径模板
     * @param permissionMode PermissionMode，权限表达式模式
     * @param permissions List&lt;String&gt;，方法要求的权限集合
     * @param expression String，源码完整 PreAuthorize 表达式
     */
    record InventoryEndpoint(String controller, String handler, String httpMethod,
            String path, PermissionMode permissionMode, List<String> permissions,
            String expression)
    {
        /**
         * 返回用于与 CSV 逐项比对的稳定入口键。
         *
         * @return String，Controller#handler
         */
        String key()
        {
            return controller + "#" + handler;
        }
    }

    /**
     * 归一化后的方法级权限规则。
     *
     * @param mode PermissionMode，认证、单权限或任一权限模式
     * @param permissions List&lt;String&gt;，受控权限集合
     */
    private record PermissionRule(PermissionMode mode, List<String> permissions)
    {
    }
}

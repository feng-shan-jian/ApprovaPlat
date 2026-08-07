package com.ruoyi.flowable.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 工作流菜单、角色和只读验收 SQL 的静态契约测试。
 */
class WorkflowMenuSqlContractTest
{
    /** 菜单种子每行首个字符串字段的提取规则。 */
    private static final Pattern SEED_KEY_PATTERN = Pattern.compile(
            "(?m)^\\s*\\('([^']+)'\\s*,");

    /** SQL 验收文件中禁止出现的写操作语句。 */
    private static final Pattern MUTATION_PATTERN = Pattern.compile(
            "(?im)^\\s*(insert|update|delete|create|drop|alter|truncate|replace|call|set)\\b");

    /**
     * 验证目标菜单种子数量、关键权限修正、显式列写入和职责分离角色完整。
     *
     * @return void，菜单或角色契约漂移时测试失败
     * @throws Exception 正式菜单 SQL 无法按 UTF-8 读取时测试失败
     */
    @Test
    void definesIdempotentMenusAndSeparatedWorkflowRoles() throws Exception
    {
        String sql = Files.readString(findProjectSql(
                "sql/flowable/menu/8.0.0__workflow_menu.sql"), StandardCharsets.UTF_8);
        String normalized = sql.toLowerCase();

        String menuSeed = extractSection(sql,
                "INSERT INTO tmp_workflow_menu_seed",
                "-- 目录按 path、页面和按钮按 perms 写入");
        Set<String> seedKeys = extractSeedKeys(menuSeed);

        assertThat(seedKeys).hasSize(72).contains(
                "workflow", "office",
                "workflow:category:list", "workflow:category:export",
                "workflow:model:designer", "workflow:model:save",
                "workflow:extension:list", "workflow:extension:add",
                "workflow:extension:edit", "workflow:extension:version:add",
                "workflow:extension:remove",
                "workflow:connector:list", "workflow:connector:add",
                "workflow:connector:edit",
                "workflow:sqlDatasource:list", "workflow:sqlDatasource:add",
                "workflow:sqlDatasource:edit",
                "workflow:dmn:list", "workflow:dmn:add", "workflow:dmn:remove",
                "workflow:integrationCredential:list",
                "workflow:integrationCredential:add",
                "workflow:integrationCredential:rotate",
                "workflow:integrationCredential:revoke",
                "workflow:runtimeEvent:list",
                "workflow:deploy:state",
                "workflow:process:manageList", "workflow:process:manageExport",
                "workflow:process:start", "workflow:process:query",
                "workflow:process:approval", "workflow:process:claim",
                "workflow:process:state", "workflow:process:terminate",
                "workflow:attachment:upload", "workflow:attachment:query",
                "workflow:attachment:remove");
        assertThat(normalized)
                .contains("insert into sys_menu\n    (menu_name, parent_id, order_num, path, component, `query`, route_name",
                        "where not exists",
                        "workflow:model:import",
                        "workflow:deploy:status",
                        "'workflow_admin', '流程管理员'",
                        "'workflow_designer', '流程设计者'",
                        "'workflow_starter', '流程发起人'",
                        "'workflow_approver', '流程审批人'",
                        "'workflow_auditor', '流程审计查看者'",
                        "仅重建五个受管角色的工作流菜单关联")
                .doesNotContain("insert into sys_menu values");

        // 管理员专属实例运维权限不能出现在其他四类角色的显式授权清单中。
        String restrictedRoleMappings = extractSection(sql,
                "-- 流程设计者仅管理分类",
                "-- 仅重建五个受管角色的工作流菜单关联");
        assertThat(restrictedRoleMappings)
                .doesNotContain("'workflow:process:manageList'",
                        "'workflow:process:manageExport'",
                        "'workflow:process:remove'",
                        "'workflow:process:state'", "'workflow:process:terminate'");
        String adminRoleMapping = extractSection(sql,
                "-- 流程管理员拥有全部工作流入口和按钮",
                "-- 流程设计者仅管理分类");
        assertThat(adminRoleMapping).contains(
                "SELECT 'workflow_admin', seed_key FROM tmp_workflow_menu_seed");
    }

    /**
     * 验证菜单验收 SQL 保持只读并覆盖数量、树结构、退役权限和角色边界。
     *
     * @return void，验收脚本包含写操作或缺少关键检查时测试失败
     * @throws Exception 正式验收 SQL 无法按 UTF-8 读取时测试失败
     */
    @Test
    void keepsWorkflowMenuVerificationReadOnlyAndComplete() throws Exception
    {
        String verification = Files.readString(findProjectSql(
                "sql/flowable/verify/8.0.0__verify_workflow_menu.sql"),
                StandardCharsets.UTF_8);
        String normalized = verification.toLowerCase();

        assertThat(MUTATION_PATTERN.matcher(verification).find()).isFalse();
        assertThat(normalized).contains(
                "workflow_menu_count",
                "workflow_menu_tree",
                "workflow_retired_permissions",
                "workflow_roles",
                "workflow_admin_menu_scope",
                "workflow_admin_only_instance_management",
                "when count(*) = 72",
                "workflow_page) = 17",
                "workflow_button) = 53",
                "'workflow:process:managelist'",
                "'workflow:process:manageexport'",
                "admin_management_permissions",
                "workflow_auditor_read_only");
    }

    /**
     * 验证已安装环境可通过独立增量脚本获得运行集成菜单和职责分离授权。
     * @return void，增量脚本缺少幂等保护、页面或角色授权时失败
     * @throws Exception 增量菜单 SQL 无法按 UTF-8 读取时测试失败
     */
    @Test
    void definesIdempotentRuntimeIntegrationMenuMigration() throws Exception
    {
        String migration = Files.readString(findProjectSql(
                "sql/flowable/menu/8.0.0.3__workflow_runtime_integration_menu.sql"),
                StandardCharsets.UTF_8);
        String normalized = migration.toLowerCase();

        assertThat(normalized).contains(
                "not exists",
                "insert ignore into sys_role_menu",
                "workflow/integrationcredential/index",
                "workflow/runtimeevent/index",
                "'workflow:integrationcredential:add'",
                "'workflow:integrationcredential:rotate'",
                "'workflow:integrationcredential:revoke'",
                "role_info.role_key = 'workflow_admin'",
                "role_info.role_key = 'workflow_auditor'");
        assertThat(normalized).doesNotContain("insert into sys_menu values");
    }

    /**
     * 提取两个稳定标记之间的 SQL 片段，避免正则跨越其他种子表。
     *
     * @param content String，完整 SQL 文本
     * @param beginMarker String，片段开始标记
     * @param endMarker String，片段结束标记
     * @return String，只包含目标标记之间的 SQL 文本
     */
    private String extractSection(String content, String beginMarker, String endMarker)
    {
        int begin = content.indexOf(beginMarker);
        int end = content.indexOf(endMarker, begin + beginMarker.length());
        assertThat(begin).as("SQL 开始标记必须存在: " + beginMarker).isGreaterThanOrEqualTo(0);
        assertThat(end).as("SQL 结束标记必须存在: " + endMarker).isGreaterThan(begin);
        return content.substring(begin, end);
    }

    /**
     * 按插入顺序提取菜单自然键，并借助 Set 同时检查重复种子。
     *
     * @param menuSeed String，tmp_workflow_menu_seed 的 VALUES 片段
     * @return Set&lt;String&gt;，不重复的菜单自然键
     */
    private Set<String> extractSeedKeys(String menuSeed)
    {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        Matcher matcher = SEED_KEY_PATTERN.matcher(menuSeed);
        while (matcher.find())
        {
            assertThat(keys.add(matcher.group(1)))
                    .as("菜单种子自然键不得重复: " + matcher.group(1)).isTrue();
        }
        return Set.copyOf(keys);
    }

    /**
     * 从 Maven 模块或聚合工程工作目录向上定位正式 SQL 文件。
     *
     * @param moduleRelativePath String，以 back 或当前后端模块为基准的 SQL 相对路径
     * @return Path，正式 SQL 的绝对路径
     */
    private Path findProjectSql(String moduleRelativePath)
    {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null)
        {
            Path candidate = current.resolve(moduleRelativePath);
            if (Files.isRegularFile(candidate))
            {
                return candidate;
            }
            candidate = current.resolve("back").resolve(moduleRelativePath);
            if (Files.isRegularFile(candidate))
            {
                return candidate;
            }
            current = current.getParent();
        }
        throw new AssertionError("未找到正式工作流 SQL: " + moduleRelativePath);
    }
}

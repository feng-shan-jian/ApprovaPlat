package com.ruoyi.web.controller.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import com.ruoyi.web.controller.workflow.WorkflowRbacMatrix.Access;
import com.ruoyi.web.controller.workflow.WorkflowRbacMatrix.Endpoint;
import com.ruoyi.web.controller.workflow.WorkflowRbacMatrix.InventoryEndpoint;
import com.ruoyi.web.controller.workflow.WorkflowRbacMatrix.PermissionMode;

/**
 * 工作流 17 个 Controller、111 个 mapping 和 5x111 URL 权限矩阵的静态契约测试。
 */
class WorkflowRbacMatrixContractTest
{
    /** 每个正式工作流 Controller 的方法级 mapping 冻结数量。 */
    private static final Map<String, Long> EXPECTED_CONTROLLER_COUNTS = Map.ofEntries(
            Map.entry("WfAttachmentController", 4L),
            Map.entry("WfCategoryController", 7L),
            Map.entry("WfDeployController", 5L),
            Map.entry("WfDesignerController", 2L),
            Map.entry("WfConnectorController", 5L),
            Map.entry("WfDmnController", 4L),
            Map.entry("WfBpmnEventController", 8L),
            Map.entry("WfExtensionController", 11L),
            Map.entry("WfSqlDataSourceController", 5L),
            Map.entry("WfIntegrationCredentialController", 4L),
            Map.entry("WfRuntimeEventAuditController", 1L),
            Map.entry("WfFormController", 6L),
            Map.entry("WfIdentityController", 1L),
            Map.entry("WfInstanceController", 2L),
            Map.entry("WfModelController", 12L),
            Map.entry("WfProcessController", 19L),
            Map.entry("WfTaskController", 15L));

    /** 每个角色按正式职责分离 SQL 应得到的 URL 层允许入口数量。 */
    private static final Map<String, Long> EXPECTED_ALLOW_COUNTS = Map.of(
            "workflow_admin", 111L,
            "workflow_designer", 59L,
            "workflow_starter", 22L,
            "workflow_approver", 31L,
            "workflow_auditor", 23L);

    /**
     * 逐项冻结 Controller、handler、HTTP 动词、完整路径及 PreAuthorize 规则。
     *
     * @return void，无返回值；源码与 103 行正式矩阵任一漂移时测试失败
     */
    @Test
    void freezesSeventeenControllersAndOneHundredElevenMappings()
    {
        List<Endpoint> matrix = WorkflowRbacMatrix.load();
        Map<String, InventoryEndpoint> inventory = WorkflowRbacMatrix.reflectInventory();

        assertThat(WorkflowRbacMatrix.CONTROLLERS).hasSize(17);
        assertThat(matrix).hasSize(111);
        assertThat(inventory).hasSize(111);
        assertThat(matrix.stream().collect(Collectors.groupingBy(
                Endpoint::controller, Collectors.counting())))
                .containsExactlyInAnyOrderEntriesOf(EXPECTED_CONTROLLER_COUNTS);
        assertThat(inventory.values().stream().collect(Collectors.groupingBy(
                InventoryEndpoint::controller, Collectors.counting())))
                .containsExactlyInAnyOrderEntriesOf(EXPECTED_CONTROLLER_COUNTS);

        Map<String, Endpoint> expectedByKey = matrix.stream().collect(Collectors.toMap(
                Endpoint::key, Function.identity(), (left, right) -> left,
                LinkedHashMap::new));
        assertThat(inventory).containsOnlyKeys(expectedByKey.keySet());
        for (Map.Entry<String, Endpoint> entry : expectedByKey.entrySet())
        {
            InventoryEndpoint actual = inventory.get(entry.getKey());
            Endpoint expected = entry.getValue();
            assertThat(actual.controller()).as(entry.getKey()).isEqualTo(expected.controller());
            assertThat(actual.handler()).as(entry.getKey()).isEqualTo(expected.handler());
            assertThat(actual.httpMethod()).as(entry.getKey()).isEqualTo(expected.httpMethod());
            assertThat(actual.path()).as(entry.getKey()).isEqualTo(expected.path());
            assertThat(actual.permissionMode()).as(entry.getKey())
                    .isEqualTo(expected.permissionMode());
            assertThat(actual.permissions()).as(entry.getKey())
                    .containsExactlyElementsOf(expected.permissions());
            assertThat(actual.expression()).as(entry.getKey())
                    .isEqualTo(expectedExpression(expected));
        }
    }

    /**
     * 用正式菜单角色 SQL 反向核对 555 个矩阵单元并冻结允许、拒绝总量。
     *
     * @return void，无返回值；矩阵与正式职责分离角色授权不一致时测试失败
     */
    @Test
    void derivesAllFiveHundredFifteenCellsFromProductionRoleSql()
    {
        List<Endpoint> matrix = WorkflowRbacMatrix.load();
        Map<String, Set<String>> rolePermissions = WorkflowRbacMatrix.loadRolePermissions();
        Map<String, Long> allowCounts = new LinkedHashMap<>();
        long cellCount = 0L;
        long denyCount = 0L;

        assertThat(rolePermissions).containsOnlyKeys(WorkflowRbacMatrix.ROLE_KEYS);
        for (String roleKey : WorkflowRbacMatrix.ROLE_KEYS)
        {
            long roleAllows = 0L;
            for (Endpoint endpoint : matrix)
            {
                Access expected = isAllowed(endpoint, rolePermissions.get(roleKey))
                        ? Access.ALLOW : Access.DENY;
                assertThat(endpoint.roleAccess())
                        .as(endpoint.key() + " 必须包含全部五角色")
                        .containsOnlyKeys(WorkflowRbacMatrix.ROLE_KEYS);
                assertThat(endpoint.roleAccess().get(roleKey))
                        .as(roleKey + " -> " + endpoint.key())
                        .isEqualTo(expected);
                cellCount++;
                if (expected == Access.ALLOW)
                {
                    roleAllows++;
                }
                else
                {
                    denyCount++;
                }
            }
            allowCounts.put(roleKey, roleAllows);
        }

        assertThat(cellCount).isEqualTo(555L);
        assertThat(allowCounts).containsExactlyInAnyOrderEntriesOf(EXPECTED_ALLOW_COUNTS);
        assertThat(denyCount).isEqualTo(309L);
    }

    /**
     * 按方法权限模式判断一个正式角色是否应通过 URL 层门禁。
     *
     * @param endpoint Endpoint，矩阵中的 HTTP 入口
     * @param rolePermissions Set&lt;String&gt;，正式菜单 SQL 赋予角色的权限
     * @return boolean，认证入口或至少命中一个所需权限时返回 true
     */
    private boolean isAllowed(Endpoint endpoint, Set<String> rolePermissions)
    {
        return endpoint.permissionMode() == PermissionMode.AUTHENTICATED
                || endpoint.permissions().stream().anyMatch(rolePermissions::contains);
    }

    /**
     * 从矩阵的结构化权限规则重建源码必须声明的完整 PreAuthorize 表达式。
     *
     * @param endpoint Endpoint，矩阵中的 HTTP 入口
     * @return String，与 Controller 注解逐字符一致的权限表达式
     */
    private String expectedExpression(Endpoint endpoint)
    {
        if (endpoint.permissionMode() == PermissionMode.AUTHENTICATED)
        {
            return "isAuthenticated()";
        }
        String method = endpoint.permissionMode() == PermissionMode.SINGLE
                ? "hasPermi" : "hasAnyPermi";
        return "@ss." + method + "('" + String.join(",", endpoint.permissions()) + "')";
    }
}

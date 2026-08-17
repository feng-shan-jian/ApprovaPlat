package com.ruoyi.web.controller.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.domain.dto.WorkflowBpmnEventCodeRequest;
import com.ruoyi.flowable.domain.dto.WorkflowBpmnEventCodeStatusRequest;
import com.ruoyi.flowable.domain.dto.WorkflowBusinessCalendarRequest;
import com.ruoyi.flowable.domain.dto.WorkflowEnabledStatusRequest;
import com.ruoyi.flowable.domain.dto.WorkflowManualUrgeRequest;
import com.ruoyi.flowable.domain.dto.WorkflowNotificationPolicyRequest;
import com.ruoyi.flowable.domain.dto.WorkflowNotificationPreferenceRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftCreateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftSaveRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftSubmitRequest;
import com.ruoyi.web.controller.workflow.WorkflowRbacMatrix.Access;
import com.ruoyi.web.controller.workflow.WorkflowRbacMatrix.Endpoint;
import com.ruoyi.web.controller.workflow.WorkflowRbacMatrix.InventoryEndpoint;
import com.ruoyi.web.controller.workflow.WorkflowRbacMatrix.PermissionMode;
import tools.jackson.databind.ObjectMapper;

/**
 * 工作流 21 个 Controller、143 个 mapping 和 5x143 URL 权限矩阵的静态契约测试。
 */
class WorkflowRbacMatrixContractTest
{
    /** 新增写入口与真实 Request DTO 的拒绝探针反序列化合同。 */
    private static final Map<String, Class<?>> DENIED_PROBE_REQUEST_TYPES = Map.ofEntries(
            Map.entry("WfProcessDraftController#create", WorkflowProcessDraftCreateRequest.class),
            Map.entry("WfProcessDraftController#save", WorkflowProcessDraftSaveRequest.class),
            Map.entry("WfProcessDraftController#submit", WorkflowProcessDraftSubmitRequest.class),
            Map.entry("WfBpmnEventController#createCode", WorkflowBpmnEventCodeRequest.class),
            Map.entry("WfBpmnEventController#updateCode", WorkflowBpmnEventCodeRequest.class),
            Map.entry("WfBpmnEventController#changeCodeStatus",
                    WorkflowBpmnEventCodeStatusRequest.class),
            Map.entry("WfTaskSlaController#createCalendar", WorkflowBusinessCalendarRequest.class),
            Map.entry("WfTaskSlaController#updateCalendar", WorkflowBusinessCalendarRequest.class),
            Map.entry("WfTaskSlaController#changeCalendarStatus",
                    WorkflowEnabledStatusRequest.class),
            Map.entry("WfNotificationController#savePreference",
                    WorkflowNotificationPreferenceRequest.class),
            Map.entry("WfNotificationController#urge", WorkflowManualUrgeRequest.class),
            Map.entry("WfNotificationController#savePolicy",
                    WorkflowNotificationPolicyRequest.class));

    /** 每个正式工作流 Controller 的方法级 mapping 冻结数量。 */
    private static final Map<String, Long> EXPECTED_CONTROLLER_COUNTS = Map.ofEntries(
            Map.entry("WfAttachmentController", 4L),
            Map.entry("WfCategoryController", 7L),
            Map.entry("WfDeployController", 5L),
            Map.entry("WfConnectorController", 5L),
            Map.entry("WfDmnController", 4L),
            Map.entry("WfBpmnEventController", 8L),
            Map.entry("WfCallActivityController", 1L),
            Map.entry("WfTaskSlaController", 9L),
            Map.entry("WfExtensionController", 11L),
            Map.entry("WfSqlDataSourceController", 5L),
            Map.entry("WfIntegrationCredentialController", 4L),
            Map.entry("WfRuntimeEventAuditController", 1L),
            Map.entry("WfCollaborationController", 6L),
            Map.entry("WfNotificationController", 10L),
            Map.entry("WfFormController", 6L),
            Map.entry("WfIdentityController", 2L),
            Map.entry("WfInstanceController", 2L),
            Map.entry("WfModelController", 12L),
            Map.entry("WfProcessController", 20L),
            Map.entry("WfProcessDraftController", 6L),
            Map.entry("WfTaskController", 15L));

    /** 每个角色按正式职责分离 SQL 应得到的 URL 层允许入口数量。 */
    private static final Map<String, Long> EXPECTED_ALLOW_COUNTS = Map.of(
            "workflow_admin", 143L,
            "workflow_designer", 71L,
            "workflow_starter", 38L,
            "workflow_approver", 41L,
            "workflow_auditor", 38L);

    /**
     * 逐项冻结 Controller、handler、HTTP 动词、完整路径及 PreAuthorize 规则。
     *
     * @return void，无返回值；源码与 143 行正式矩阵任一漂移时测试失败
     */
    @Test
    void freezesTwentyOneControllersAndOneHundredFortyThreeMappings()
    {
        List<Endpoint> matrix = WorkflowRbacMatrix.load();
        Map<String, InventoryEndpoint> inventory = WorkflowRbacMatrix.reflectInventory();

        assertThat(WorkflowRbacMatrix.CONTROLLERS).hasSize(21);
        assertThat(matrix).hasSize(143);
        assertThat(inventory).hasSize(143);
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
     * 用正式菜单角色 SQL 反向核对 715 个矩阵单元并冻结允许、拒绝总量。
     *
     * @return void，无返回值；矩阵与正式职责分离角色授权不一致时测试失败
     */
    @Test
    void derivesAllSevenHundredFifteenCellsFromProductionRoleSql()
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

        assertThat(cellCount).isEqualTo(715L);
        assertThat(allowCounts).containsExactlyInAnyOrderEntriesOf(EXPECTED_ALLOW_COUNTS);
        assertThat(denyCount).isEqualTo(384L);
    }

    /**
     * 遍历全部冻结入口，保证拒绝探针在发送 HTTP 前解析每一种路径变量。
     *
     * @return void，无返回值；任一路径变量缺少类型合法哨兵或仍含花括号时测试失败
     */
    @Test
    void resolvesDeniedProbeVariablesForAllOneHundredFortyThreeMappings()
    {
        List<Endpoint> matrix = WorkflowRbacMatrix.load();
        assertThat(matrix).hasSize(143);
        for (Endpoint endpoint : matrix)
        {
            assertThat(WorkflowRbacHttpIT.deniedProbePath(endpoint))
                    .as(endpoint.key())
                    .doesNotContain("{", "}");
        }

        assertThat(WorkflowRbacHttpIT.resolveDeniedProbePath(
                "/probe/{messageId}/{draftId}/{copyId}/{eventType}"))
                .isEqualTo("/probe/rbac-denied-probe/"
                        + "00000000-0000-4000-8000-000000000000/"
                        + Long.MAX_VALUE + "/ERROR");
    }

    /**
     * 验证矩阵新增未知路径变量时解析器 fail-closed，而不是把模板传给 URI.create。
     *
     * @return void，无返回值；未知变量被静默保留或替换时测试失败
     */
    @Test
    void rejectsUnknownDeniedProbePathVariable()
    {
        assertThatThrownBy(() -> WorkflowRbacHttpIT.resolveDeniedProbePath(
                "/workflow/unknown/{unknownId}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknownId");
    }

    /**
     * 将新增写入口的拒绝探针反序列化为真实请求 DTO，并执行正式 Bean Validation。
     *
     * @return void，无返回值；JSON 缺失、类型漂移或任一字段约束不满足时测试失败
     * @throws Exception JSON 无法按真实 Request DTO 反序列化时测试失败
     */
    @Test
    void deniedProbeBodiesSatisfyRealRequestValidationContracts() throws Exception
    {
        ObjectMapper objectMapper = new ObjectMapper();
        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory())
        {
            Validator validator = validatorFactory.getValidator();
            for (Map.Entry<String, Class<?>> entry : DENIED_PROBE_REQUEST_TYPES.entrySet())
            {
                String json = WorkflowRbacHttpIT.deniedProbeJsonBody(entry.getKey());
                assertThat(json).as(entry.getKey()).isNotBlank();
                Object request = objectMapper.readValue(json, entry.getValue());
                assertThat(validator.validate(request)).as(entry.getKey()).isEmpty();
            }
        }

        Endpoint deleteDraft = WorkflowRbacMatrix.load().stream()
                .filter(endpoint -> "WfProcessDraftController#delete".equals(endpoint.key()))
                .findFirst()
                .orElseThrow();
        assertThat(WorkflowRbacHttpIT.deniedProbePath(deleteDraft))
                .endsWith("?expectedVersion=1");
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

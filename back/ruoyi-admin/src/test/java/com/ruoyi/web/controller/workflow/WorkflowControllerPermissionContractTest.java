package com.ruoyi.web.controller.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import com.ruoyi.flowable.domain.WfForm;

/**
 * 工作流设计与编辑入口依赖权限的静态契约测试。
 */
class WorkflowControllerPermissionContractTest
{
    /** 工作流公开 HTTP Controller 固定白名单。 */
    private static final List<Class<?>> WORKFLOW_CONTROLLERS = List.of(
            WfAttachmentController.class,
            WfCategoryController.class,
            WfDeployController.class,
            WfDesignerController.class,
            WfConnectorController.class,
            WfDmnController.class,
            WfBpmnEventController.class,
            WfExtensionController.class,
            WfSqlDataSourceController.class,
            WfFormController.class,
            WfIdentityController.class,
            WfInstanceController.class,
            WfModelController.class,
            WfProcessController.class,
            WfTaskController.class);

    /** 当前生产工作流显式方法级 mapping 数量。 */
    private static final int EXPECTED_MAPPING_COUNT = 106;

    /**
     * 冻结全部工作流 HTTP 入口数量，并保证每个入口均经过方法级 URL 权限门禁。
     *
     * @return 无返回值；Controller、mapping 数量或 PreAuthorize 门禁漂移时测试失败
     */
    @Test
    void requiresPreAuthorizeOnEveryWorkflowMapping()
    {
        int mappingCount = 0;
        for (Class<?> controllerType : WORKFLOW_CONTROLLERS)
        {
            for (Method method : controllerType.getDeclaredMethods())
            {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(
                        method, RequestMapping.class);
                if (mapping == null)
                {
                    continue;
                }
                mappingCount++;
                PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
                assertThat(preAuthorize)
                        .as("%s.%s 必须声明 PreAuthorize",
                                controllerType.getSimpleName(), method.getName())
                        .isNotNull();
                assertThat(preAuthorize.value())
                        .as("%s.%s 的 PreAuthorize 不能为空",
                                controllerType.getSimpleName(), method.getName())
                        .isNotBlank();
            }
        }
        assertThat(mappingCount).isEqualTo(EXPECTED_MAPPING_COUNT);
    }

    /**
     * 验证模型设计和表单编辑入口具备读取上下文的必要权限，同时不放宽模型保存权限。
     *
     * @return 无返回值；任一方法权限表达式漂移时测试失败
     * @throws NoSuchMethodException Controller 方法签名发生非兼容变化时抛出
     */
    @Test
    void alignsDesignerAndEditorPermissionsWithRequiredReadApis()
            throws NoSuchMethodException
    {
        assertPreAuthorize(WfModelController.class, "getInfo",
                "@ss.hasAnyPermi('workflow:model:query,workflow:model:edit,workflow:model:designer')",
                String.class);
        assertPreAuthorize(WfModelController.class, "getBpmnXml",
                "@ss.hasAnyPermi('workflow:model:query,workflow:model:designer')",
                String.class);
        assertPreAuthorize(WfModelController.class, "save",
                "@ss.hasPermi('workflow:model:save')",
                com.ruoyi.flowable.domain.dto.WorkflowModelSaveRequest.class);
        assertPreAuthorize(WfModelController.class, "validate",
                "@ss.hasPermi('workflow:model:designer')",
                com.ruoyi.flowable.domain.dto.WorkflowBpmnValidationRequest.class);
        assertPreAuthorize(WfDesignerController.class, "getPreference",
                "@ss.hasPermi('workflow:model:designer')");
        assertPreAuthorize(WfDesignerController.class, "savePreference",
                "@ss.hasPermi('workflow:model:designer')",
                com.ruoyi.flowable.domain.dto.WorkflowDesignerPreferenceRequest.class);
        assertPreAuthorize(WfExtensionController.class, "javaOptions",
                "@ss.hasAnyPermi('workflow:extension:list,workflow:model:designer')");
        assertPreAuthorize(WfExtensionController.class, "celOptions",
                "@ss.hasAnyPermi('workflow:extension:list,workflow:model:designer')");
        assertPreAuthorize(WfExtensionController.class, "httpOptions",
                "@ss.hasAnyPermi('workflow:extension:list,workflow:model:designer')");
        assertPreAuthorize(WfExtensionController.class, "sqlOptions",
                "@ss.hasAnyPermi('workflow:extension:list,workflow:model:designer')");
        assertPreAuthorize(WfExtensionController.class, "formFieldOptions",
                "@ss.hasAnyPermi('workflow:extension:list,workflow:model:designer')");
        assertPreAuthorize(WfExtensionController.class, "installedJavaHandlers",
                "@ss.hasPermi('workflow:extension:list')");
        assertPreAuthorize(WfExtensionController.class, "list",
                "@ss.hasPermi('workflow:extension:list')");
        assertPreAuthorize(WfExtensionController.class, "remove",
                "@ss.hasPermi('workflow:extension:remove')", Long.class);
        assertPreAuthorize(WfConnectorController.class, "options",
                "@ss.hasAnyPermi('workflow:connector:list,workflow:model:designer')");
        assertPreAuthorize(WfConnectorController.class, "list",
                "@ss.hasPermi('workflow:connector:list')");
        assertPreAuthorize(WfDmnController.class, "options",
                "@ss.hasAnyPermi('workflow:dmn:list,workflow:model:designer')");
        assertPreAuthorize(WfDmnController.class, "list",
                "@ss.hasPermi('workflow:dmn:list')");
        assertPreAuthorize(WfDmnController.class, "deploy",
                "@ss.hasPermi('workflow:dmn:add')",
                com.ruoyi.flowable.domain.dto.WorkflowDmnDeploymentRequest.class);
        assertPreAuthorize(WfDmnController.class, "delete",
                "@ss.hasPermi('workflow:dmn:remove')", String.class);
        assertPreAuthorize(WfBpmnEventController.class, "listCodes",
                "@ss.hasPermi('workflow:bpmnEvent:list')");
        assertPreAuthorize(WfBpmnEventController.class, "codeOptions",
                "@ss.hasAnyPermi('workflow:bpmnEvent:list,workflow:model:designer')",
                String.class);
        assertPreAuthorize(WfBpmnEventController.class, "createCode",
                "@ss.hasPermi('workflow:bpmnEvent:add')",
                com.ruoyi.flowable.domain.dto.WorkflowBpmnEventCodeRequest.class);
        assertPreAuthorize(WfBpmnEventController.class, "updateCode",
                "@ss.hasPermi('workflow:bpmnEvent:edit')", Long.class,
                com.ruoyi.flowable.domain.dto.WorkflowBpmnEventCodeRequest.class);
        assertPreAuthorize(WfBpmnEventController.class, "changeCodeStatus",
                "@ss.hasPermi('workflow:bpmnEvent:edit')", Long.class,
                com.ruoyi.flowable.domain.dto.WorkflowBpmnEventCodeStatusRequest.class);
        assertPreAuthorize(WfBpmnEventController.class, "audit",
                "@ss.hasPermi('workflow:bpmnEvent:audit')");
        assertPreAuthorize(WfBpmnEventController.class, "myNotifications",
                "@ss.hasAnyPermi('workflow:process:approval,workflow:process:start,workflow:bpmnEvent:list')");
        assertPreAuthorize(WfBpmnEventController.class, "markRead",
                "@ss.hasAnyPermi('workflow:process:approval,workflow:process:start,workflow:bpmnEvent:list')",
                Long.class);
        assertPreAuthorize(WfSqlDataSourceController.class, "options",
                "@ss.hasAnyPermi('workflow:sqlDatasource:list,workflow:model:designer')");
        assertPreAuthorize(WfSqlDataSourceController.class, "list",
                "@ss.hasPermi('workflow:sqlDatasource:list')");
        assertPreAuthorize(WfFormController.class, "list",
                "@ss.hasAnyPermi('workflow:form:list,workflow:model:list,workflow:model:designer')",
                WfForm.class, int.class, int.class);
        assertPreAuthorize(WfFormController.class, "getInfo",
                "@ss.hasAnyPermi('workflow:form:query,workflow:form:edit')",
                Long.class);
    }

    /**
     * 读取指定 Controller 方法的权限注解并比对完整 Spring Security 表达式。
     *
     * @param controllerType Class，待检查的 Controller 类型
     * @param methodName String，待检查的方法名
     * @param expectedExpression String，期望的 PreAuthorize 表达式
     * @param parameterTypes Class<?>[]，用于唯一定位方法的入参类型
     * @return 无返回值；注解缺失或表达式不一致时断言失败
     * @throws NoSuchMethodException 方法签名不存在时抛出
     */
    private void assertPreAuthorize(Class<?> controllerType, String methodName,
            String expectedExpression, Class<?>... parameterTypes)
            throws NoSuchMethodException
    {
        Method method = controllerType.getDeclaredMethod(methodName, parameterTypes);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);

        assertThat(annotation)
                .as("%s.%s 必须声明 PreAuthorize", controllerType.getSimpleName(), methodName)
                .isNotNull();
        assertThat(annotation.value()).isEqualTo(expectedExpression);
    }
}

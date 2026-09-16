package com.ruoyi.web.controller.workflow;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.flowable.domain.vo.WorkflowAuthorFieldCatalogView;
import com.ruoyi.flowable.service.model.WorkflowModelService;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.system.service.ISysMenuService;

class WfAuthorFieldCatalogControllerTest
{
    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test
    void designerPermissionIsEnforcedAndBlankXmlNeverReachesTheService() throws Exception
    {
        try (var context = new AnnotationConfigApplicationContext(Config.class))
        {
            var service = context.getBean(WorkflowModelService.class);
            var mvc = MockMvcBuilders.standaloneSetup(context.getBean(WfModelController.class))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            authenticate("workflow:model:list");
            mvc.perform(post("/workflow/model/author-field-catalog").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"bpmnXml\":\"<unfinished/>\"}")).andExpect(jsonPath("$.code").value(403));
            verifyNoInteractions(service);
            authenticate("workflow:model:designer");
            // 全局 Bean Validation 异常沿用若依 AjaxResult.error 的 500 业务码。
            mvc.perform(post("/workflow/model/author-field-catalog").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"bpmnXml\":\" \"}")).andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.msg").value("BPMN XML 不能为空"));
            verifyNoInteractions(service);
            when(service.authorFieldCatalog("<unfinished/>")).thenReturn(new WorkflowAuthorFieldCatalogView(List.of(), List.of(), List.of()));
            mvc.perform(post("/workflow/model/author-field-catalog").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"bpmnXml\":\"<unfinished/>\"}")).andExpect(jsonPath("$.code").value(200));
            verify(service).authorFieldCatalog("<unfinished/>");
        }
    }

    @Test
    void readOnlyCatalogAcceptsSameXmlEnvelopeAndReturnsScopedFieldsAndRecoverableIssues() throws Exception
    {
        var service = mock(WorkflowModelService.class);
        when(service.authorFieldCatalog("<unfinished/>")).thenReturn(new WorkflowAuthorFieldCatalogView(
                List.of(new WorkflowAuthorFieldCatalogView.Node("main", "task",
                        List.of(new WorkflowAuthorFieldCatalogView.Option("owner", "负责人")), List.of())), List.of(),
                List.of(new WorkflowAuthorFieldCatalogView.Issue("main", "broken", "配置字段权限"))));
        var mvc = MockMvcBuilders.standaloneSetup(new WfModelController(service, null, null))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/workflow/model/author-field-catalog").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bpmnXml\":\"<unfinished/>\"}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.nodes[0].processKey").value("main"))
                .andExpect(jsonPath("$.data.nodes[0].userIdFields[0].value").value("owner"))
                .andExpect(jsonPath("$.data.issues[0].nodeKey").value("broken"));
        verify(service).authorFieldCatalog("<unfinished/>");
        verifyNoMoreInteractions(service);
    }

    private void authenticate(String permission)
    {
        var user = new LoginUser(); user.setUserId(100L); user.setPermissions(Set.of(permission));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Config
    {
        @Bean WorkflowModelService service() { return mock(WorkflowModelService.class); }
        @Bean WfModelController controller(WorkflowModelService service) { return new WfModelController(service, null, null); }
        @Bean ISysMenuService menuService()
        {
            var service = mock(ISysMenuService.class);
            when(service.selectMenuPermsByUserId(100L)).thenReturn(Set.of("workflow:model:designer"));
            return service;
        }
        @Bean("ss") PermissionService permissionService(ISysMenuService menuService) { return new PermissionService(menuService); }
    }
}

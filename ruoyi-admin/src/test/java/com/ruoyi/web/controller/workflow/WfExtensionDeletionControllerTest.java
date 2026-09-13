package com.ruoyi.web.controller.workflow;

import java.util.List;
import java.util.Set;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.service.model.WorkflowExtensionRegistryService;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.system.service.ISysMenuService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class WfExtensionDeletionControllerTest
{
    @AfterEach
    void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test
    void listPermissionCannotCheckOrDelete() throws Exception
    {
        try (var context = new AnnotationConfigApplicationContext(Config.class))
        {
            authenticate("workflow:extension:list");
            var mvc = MockMvcBuilders.standaloneSetup(context.getBean(WfExtensionController.class))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            mvc.perform(get("/workflow/extension/1/deletion-check")).andExpect(jsonPath("$.code").value(403));
            mvc.perform(delete("/workflow/extension/1")).andExpect(jsonPath("$.code").value(403));
            verifyNoInteractions(context.getBean(WorkflowExtensionRegistryService.class));
        }
    }

    @Test
    void successfulCheckHasExplicitBooleanAndDeleteStillCallsService() throws Exception
    {
        try (var context = new AnnotationConfigApplicationContext(Config.class))
        {
            authenticate("workflow:extension:remove");
            var mvc = MockMvcBuilders.standaloneSetup(context.getBean(WfExtensionController.class))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            mvc.perform(get("/workflow/extension/9/deletion-check"))
                    .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.deleteAllowed").value(true));
            mvc.perform(delete("/workflow/extension/9")).andExpect(jsonPath("$.code").value(200));
            verify(context.getBean(WorkflowExtensionRegistryService.class)).checkDeletion(9L);
            verify(context.getBean(WorkflowExtensionRegistryService.class)).removeExtension(9L);
        }
    }

    @Test
    void corruptionAndLateReferenceNeverProduceDeleteCapability() throws Exception
    {
        try (var context = new AnnotationConfigApplicationContext(Config.class))
        {
            authenticate("workflow:extension:remove");
            var service = context.getBean(WorkflowExtensionRegistryService.class);
            doThrow(new ServiceException("流程部署业务资源清单无法解析", 500)).when(service).checkDeletion(9L);
            doThrow(new ServiceException("扩展目录已被部署快照引用，不能删除", 409)).when(service).removeExtension(9L);
            var mvc = MockMvcBuilders.standaloneSetup(context.getBean(WfExtensionController.class))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            mvc.perform(get("/workflow/extension/9/deletion-check"))
                    .andExpect(jsonPath("$.code").value(500)).andExpect(jsonPath("$.data.deleteAllowed").doesNotExist());
            mvc.perform(delete("/workflow/extension/9")).andExpect(jsonPath("$.code").value(409));
        }
    }

    private void authenticate(String permission)
    {
        LoginUser user = new LoginUser();
        user.setUserId(100L);
        user.setPermissions(Set.of(permission));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Config
    {
        @Bean WorkflowExtensionRegistryService service() { return mock(WorkflowExtensionRegistryService.class); }
        @Bean WfExtensionController controller(WorkflowExtensionRegistryService service) { return new WfExtensionController(service); }
        @Bean ISysMenuService menuService()
        {
            var service = mock(ISysMenuService.class);
            when(service.selectMenuPermsByUserId(100L)).thenReturn(Set.of("workflow:extension:remove"));
            return service;
        }
        @Bean("ss") PermissionService permissionService(ISysMenuService menuService) { return new PermissionService(menuService); }
    }
}

package com.ruoyi.web.controller;

import java.util.List;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.flowable.domain.dto.WorkflowIntegrationCredentialCreateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowIntegrationCredentialRotateRequest;
import com.ruoyi.flowable.domain.vo.WorkflowIntegrationCredentialSecretView;
import com.ruoyi.flowable.service.process.WorkflowIntegrationCredentialService;
import com.ruoyi.framework.aspectj.LogAspect;
import com.ruoyi.framework.manager.AsyncManager;
import com.ruoyi.framework.manager.factory.AsyncFactory;
import com.ruoyi.system.domain.SysOperLog;
import com.ruoyi.system.domain.integration.OssConfigRequest;
import com.ruoyi.system.domain.integration.SmsConfigRequest;
import com.ruoyi.system.service.integration.SysOssService;
import com.ruoyi.system.service.integration.SysSmsService;
import com.ruoyi.web.controller.system.SysOssController;
import com.ruoyi.web.controller.system.SysSmsController;
import com.ruoyi.web.controller.workflow.WfIntegrationCredentialController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CredentialAuditLogTest
{
    private static final String SECRET = "synthetic-audit-secret-5821";

    @BeforeEach
    void authenticate()
    {
        SysUser user = new SysUser();
        user.setUserName("audit-tester");
        LoginUser login = new LoginUser(7L, 1L, user, Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(login, null, List.of()));
    }

    @AfterEach
    void clearContext()
    {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void integrationTokenStaysInBusinessResponseOnly(boolean rotate)
    {
        WorkflowIntegrationCredentialService service = mock(WorkflowIntegrationCredentialService.class);
        var secret = new WorkflowIntegrationCredentialSecretView(1L, 1, SECRET, null);
        when(service.create(any())).thenReturn(secret);
        when(service.rotate(any(), any())).thenReturn(secret);
        WfIntegrationCredentialController controller = proxy(new WfIntegrationCredentialController(service));
        SysOperLog log = audit(rotate ? "PUT" : "POST", () -> rotate
                ? controller.rotate(1L, new WorkflowIntegrationCredentialRotateRequest(null))
                : controller.create(new WorkflowIntegrationCredentialCreateRequest(
                        "test", List.of("MESSAGE"), List.of(), 10, null)), true, rotate);
        assertNull(log.getJsonResult());
        assertNotNull(log.getOperParam());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void smsSecretIsNotPersistedFromRequest(boolean update)
    {
        SysSmsService service = mock(SysSmsService.class);
        SysSmsController controller = proxy(new SysSmsController(service));
        SmsConfigRequest request = new SmsConfigRequest(1L, "test", "ALIYUN", "synthetic-id",
                SECRET, "sign", null, null, null);
        SysOperLog log = audit(update ? "PUT" : "POST", () -> update
                ? controller.editConfig(request) : controller.addConfig(request), false, update);
        assertNull(log.getOperParam());
        assertNotNull(log.getJsonResult());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ossSecretIsNotPersistedFromRequest(boolean update)
    {
        SysOssService service = mock(SysOssService.class);
        SysOssController controller = proxy(new SysOssController(service));
        OssConfigRequest request = new OssConfigRequest(1L, "test", "https://example.invalid",
                "region", "bucket", "synthetic-id", SECRET, null, null, "Y", "PRIVATE", null);
        SysOperLog log = audit(update ? "PUT" : "POST", () -> update
                ? controller.editConfig(request) : controller.addConfig(request), false, update);
        assertNull(log.getOperParam());
        assertNotNull(log.getJsonResult());
    }

    private SysOperLog audit(String method, Supplier<AjaxResult> call, boolean returnsSecret, boolean update)
    {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/synthetic-credential-test");
        request.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        AtomicReference<SysOperLog> captured = new AtomicReference<>();
        AsyncManager manager = mock(AsyncManager.class);
        TimerTask task = mock(TimerTask.class);
        try (MockedStatic<AsyncManager> managers = mockStatic(AsyncManager.class);
                MockedStatic<AsyncFactory> factory = mockStatic(AsyncFactory.class))
        {
            managers.when(AsyncManager::me).thenReturn(manager);
            factory.when(() -> AsyncFactory.recordOper(any())).thenAnswer(invocation -> {
                captured.set(invocation.getArgument(0));
                return task;
            });
            AjaxResult response = call.get();
            assertEquals(200, response.get("code"));
            assertEquals(returnsSecret, JSON.toJSONString(response).contains(SECRET));
            verify(manager).execute(task);
        }
        SysOperLog log = captured.get();
        assertNotNull(log);
        assertFalse(JSON.toJSONString(log).contains(SECRET));
        assertEquals("audit-tester", log.getOperName());
        assertEquals("127.0.0.1", log.getOperIp());
        assertEquals("/synthetic-credential-test", log.getOperUrl());
        assertEquals(method, log.getRequestMethod());
        assertEquals(0, log.getStatus());
        assertEquals((update ? BusinessType.UPDATE : BusinessType.INSERT).ordinal(), log.getBusinessType());
        assertFalse(log.getTitle().isBlank());
        assertNotNull(log.getMethod());
        assertNotNull(log.getCostTime());
        return log;
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T controller)
    {
        AspectJProxyFactory factory = new AspectJProxyFactory(controller);
        factory.addAspect(new LogAspect());
        return (T) factory.getProxy();
    }
}

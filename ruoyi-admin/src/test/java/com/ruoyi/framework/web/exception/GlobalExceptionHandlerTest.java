package com.ruoyi.framework.web.exception;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 全局异常边界测试，验证已删除的旧 URL 不会被包装成内部系统错误。
 */
class GlobalExceptionHandlerTest
{
    /**
     * 验证未命中任何控制器的请求明确返回业务 404，且不泄露内部资源路径。
     *
     * @return void，已删除路由仍映射为 500 或泄露资源路径时测试失败
     */
    @Test
    void shouldMapMissingResourceToNotFound()
    {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "DELETE", "/workflow/process/instance/legacy-one,legacy-two");
        NoResourceFoundException exception = new NoResourceFoundException(
                HttpMethod.DELETE, "workflow/process/instance/legacy-one,legacy-two", "No static resource");

        AjaxResult result = handler.handleNoResourceFoundException(exception, request);

        assertEquals(HttpStatus.NOT_FOUND, result.get("code"));
        assertEquals("请求地址不存在", result.get("msg"));
    }
}

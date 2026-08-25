package com.ruoyi.web.controller.workflow;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审批通知专用异常处理器测试，验证 HTTP 状态与 AjaxResult 业务码始终一致。
 */
class WorkflowNotificationExceptionHandlerTest
{
    private final WorkflowNotificationExceptionHandler handler =
            new WorkflowNotificationExceptionHandler();

    /**
     * 验证并发冲突保留 HTTP 409 与稳定业务子码。
     * 无入参。
     * 无返回值。
     */
    @Test
    void shouldMapServiceConflictToHttp409WithSubCode()
    {
        ServiceException exception = new ServiceException("配置已被其他管理员修改", HttpStatus.CONFLICT)
                .setSubCode("MAIL_CONFIG_REVISION_CONFLICT");

        ResponseEntity<AjaxResult> response = handler.handleServiceException(exception);

        assertResponse(response, HttpStatus.CONFLICT, "配置已被其他管理员修改");
        assertEquals("MAIL_CONFIG_REVISION_CONFLICT", response.getBody().get("subCode"));
    }

    /**
     * 验证权限拒绝转换为真实 HTTP 403，避免只在响应体返回权限错误。
     * 无入参。
     * 无返回值。
     */
    @Test
    void shouldMapAccessDeniedToHttp403()
    {
        ResponseEntity<AjaxResult> response = handler.handleAccessDeniedException(
                new AccessDeniedException("内部权限细节"));

        assertResponse(response, HttpStatus.FORBIDDEN, "没有权限，请联系管理员授权");
        assertFalse(Objects.requireNonNull(response.getBody()).get("msg").toString()
                .contains("内部权限细节"));
    }

    /**
     * 验证方法参数约束异常转换为 HTTP 400，并返回首条安全校验提示。
     * 无入参。
     * 无返回值。
     */
    @Test
    void shouldMapConstraintViolationToHttp400()
    {
        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        when(violation.getMessage()).thenReturn("页码必须大于零");
        Set<ConstraintViolation<?>> violations = Collections.singleton(violation);

        ResponseEntity<AjaxResult> response = handler.handleConstraintViolationException(
                new ConstraintViolationException(violations));

        assertResponse(response, HttpStatus.BAD_REQUEST, "页码必须大于零");
    }

    /**
     * 验证 JSON DTO 字段校验异常转换为 HTTP 400，并使用绑定结果中的业务提示。
     * 无入参。
     * 无返回值。
     */
    @Test
    void shouldMapMethodArgumentNotValidToHttp400()
    {
        BeanPropertyBindingResult bindingResult = rejectedBindingResult("SMTP端口不能为空");
        MethodArgumentNotValidException exception =
                new MethodArgumentNotValidException(null, bindingResult);

        ResponseEntity<AjaxResult> response =
                handler.handleMethodArgumentNotValidException(exception);

        assertResponse(response, HttpStatus.BAD_REQUEST, "SMTP端口不能为空");
    }

    /**
     * 验证查询参数绑定异常转换为 HTTP 400，并使用绑定结果中的业务提示。
     * 无入参。
     * 无返回值。
     */
    @Test
    void shouldMapBindExceptionToHttp400()
    {
        BindException exception = new BindException(rejectedBindingResult("查询条件不合法"));

        ResponseEntity<AjaxResult> response = handler.handleBindException(exception);

        assertResponse(response, HttpStatus.BAD_REQUEST, "查询条件不合法");
    }

    /**
     * 验证参数类型不匹配转换为 HTTP 400，且不回显攻击者提供的原始值。
     * 无入参。
     * 无返回值。
     */
    @Test
    void shouldMapTypeMismatchToHttp400WithoutRawValue()
    {
        MethodArgumentTypeMismatchException exception = new MethodArgumentTypeMismatchException(
                "credential-secret", Long.class, "id", null, null);

        ResponseEntity<AjaxResult> response =
                handler.handleMethodArgumentTypeMismatchException(exception);

        assertResponse(response, HttpStatus.BAD_REQUEST, "请求参数类型不匹配: id");
        assertFalse(Objects.requireNonNull(response.getBody()).get("msg").toString()
                .contains("credential-secret"));
    }

    /**
     * 验证缺少必填查询参数转换为 HTTP 400，并明确返回缺失字段名。
     * 无入参。
     * 无返回值。
     */
    @Test
    void shouldMapMissingParameterToHttp400()
    {
        MissingServletRequestParameterException exception =
                new MissingServletRequestParameterException("expectedRevision", "long");

        ResponseEntity<AjaxResult> response =
                handler.handleMissingServletRequestParameterException(exception);

        assertResponse(response, HttpStatus.BAD_REQUEST, "缺少请求参数: expectedRevision");
    }

    /**
     * 验证畸形 JSON 转换为固定提示的 HTTP 400，不把解析异常中的敏感原文带入响应。
     * 无入参。
     * 无返回值。
     */
    @Test
    void shouldMapMalformedJsonToHttp400WithoutRawContent()
    {
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                "无法解析 credential-secret", mock(HttpInputMessage.class));

        ResponseEntity<AjaxResult> response =
                handler.handleHttpMessageNotReadableException(exception);

        assertResponse(response, HttpStatus.BAD_REQUEST, "请求体格式不合法");
        assertFalse(Objects.requireNonNull(response.getBody()).get("msg").toString()
                .contains("credential-secret"));
    }

    /**
     * 创建带一条稳定提示的绑定结果，供两类 Spring 参数绑定异常复用。
     *
     * @param message String，期望返回给用户的安全校验提示
     * @return BeanPropertyBindingResult，包含一条全局校验错误
     */
    private BeanPropertyBindingResult rejectedBindingResult(String message)
    {
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(Map.of(), "request");
        bindingResult.reject("invalid", message);
        return bindingResult;
    }

    /**
     * 断言传输层状态、响应体 code 与用户提示同步，避免出现 HTTP 200 包装业务错误。
     *
     * @param response ResponseEntity&lt;AjaxResult&gt;，待验证的异常响应
     * @param expectedCode int，期望的 HTTP 状态和响应体业务码
     * @param expectedMessage String，期望的安全用户提示
     * @return void，无返回值
     */
    private void assertResponse(ResponseEntity<AjaxResult> response, int expectedCode,
            String expectedMessage)
    {
        assertEquals(expectedCode, response.getStatusCode().value());
        AjaxResult body = response.getBody();
        assertNotNull(body);
        assertEquals(expectedCode, body.get("code"));
        assertEquals(expectedMessage, body.get("msg"));
    }
}

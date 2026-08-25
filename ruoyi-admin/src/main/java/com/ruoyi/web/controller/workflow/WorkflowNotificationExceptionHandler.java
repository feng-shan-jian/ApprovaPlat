package com.ruoyi.web.controller.workflow;

import jakarta.validation.ConstraintViolationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;

/**
 * 审批通知管理接口的 HTTP 状态适配器，只在该 Controller 范围内把业务码同步为传输状态。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = WfNotificationController.class)
public class WorkflowNotificationExceptionHandler
{
    /**
     * 将通知领域异常转换为同码 HTTP 响应，保留若依 AjaxResult 和稳定业务子码。
     *
     * @param exception ServiceException，通知配置、并发、状态或参数异常
     * @return ResponseEntity&lt;AjaxResult&gt;，HTTP 状态与响应体 code 保持一致
     */
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<AjaxResult> handleServiceException(ServiceException exception)
    {
        Integer businessCode = exception.getCode();
        int responseCode = businessCode != null && businessCode >= 400 && businessCode <= 599
                ? businessCode : HttpStatus.ERROR;
        AjaxResult result = AjaxResult.error(responseCode, exception.getMessage());
        if (StringUtils.isNotEmpty(exception.getSubCode()))
        {
            result.put("subCode", exception.getSubCode());
        }
        return ResponseEntity.status(responseCode).body(result);
    }

    /**
     * 将方法权限拒绝转换为真实 HTTP 403，防止绕过前端时仅收到 HTTP 200 业务错误。
     *
     * @param exception AccessDeniedException，Spring Method Security 权限拒绝
     * @return ResponseEntity&lt;AjaxResult&gt;，HTTP 403 与固定无权提示
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<AjaxResult> handleAccessDeniedException(
            AccessDeniedException exception)
    {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(AjaxResult.error(HttpStatus.FORBIDDEN, "没有权限，请联系管理员授权"));
    }

    /**
     * 将控制器方法参数约束失败转换为真实 HTTP 400，避免分页、枚举和正数约束落入全局 200 包装。
     *
     * @param exception ConstraintViolationException，方法参数上的 Jakarta Validation 约束异常
     * @return ResponseEntity&lt;AjaxResult&gt;，HTTP 400 与首条稳定校验提示
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<AjaxResult> handleConstraintViolationException(
            ConstraintViolationException exception)
    {
        String message = exception.getConstraintViolations().stream()
                .findFirst().map(violation -> violation.getMessage())
                .orElse("请求参数校验失败");
        return badRequest(message);
    }

    /**
     * 处理 Spring MVC 原生方法参数校验失败，兼容无需 AOP 代理的控制器校验路径。
     *
     * @param exception HandlerMethodValidationException，Spring MVC 方法参数校验异常
     * @return ResponseEntity&lt;AjaxResult&gt;，不回显原始输入的 HTTP 400
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<AjaxResult> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception)
    {
        String message = exception.getAllErrors().stream().findFirst()
                .map(error -> error.getDefaultMessage())
                .filter(StringUtils::isNotEmpty).orElse("请求参数校验失败");
        return badRequest(message);
    }

    /**
     * 处理 JSON DTO 字段校验失败，只返回首条业务提示并保持真实 HTTP 400。
     *
     * @param exception MethodArgumentNotValidException，RequestBody 字段约束异常
     * @return ResponseEntity&lt;AjaxResult&gt;，HTTP 400 与首条字段校验提示
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AjaxResult> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException exception)
    {
        String message = exception.getBindingResult().getAllErrors().stream().findFirst()
                .map(error -> error.getDefaultMessage())
                .filter(StringUtils::isNotEmpty).orElse("请求参数校验失败");
        return badRequest(message);
    }

    /**
     * 处理查询参数绑定失败，不回显可能包含敏感值的原始请求内容。
     *
     * @param exception BindException，Spring 数据绑定异常
     * @return ResponseEntity&lt;AjaxResult&gt;，HTTP 400 与首条绑定提示
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<AjaxResult> handleBindException(BindException exception)
    {
        String message = exception.getAllErrors().stream().findFirst()
                .map(error -> error.getDefaultMessage())
                .filter(StringUtils::isNotEmpty).orElse("请求参数校验失败");
        return badRequest(message);
    }

    /**
     * 处理参数类型不匹配，提示字段名但不回显攻击者提供的原始值。
     *
     * @param exception MethodArgumentTypeMismatchException，查询或路径参数类型异常
     * @return ResponseEntity&lt;AjaxResult&gt;，HTTP 400 与安全字段提示
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<AjaxResult> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException exception)
    {
        return badRequest("请求参数类型不匹配: " + exception.getName());
    }

    /**
     * 处理缺失的必填查询参数，不暴露请求体或运行时实现信息。
     *
     * @param exception MissingServletRequestParameterException，必填查询参数缺失异常
     * @return ResponseEntity&lt;AjaxResult&gt;，HTTP 400 与缺失字段提示
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<AjaxResult> handleMissingServletRequestParameterException(
            MissingServletRequestParameterException exception)
    {
        return badRequest("缺少请求参数: " + exception.getParameterName());
    }

    /**
     * 处理无法反序列化的 JSON，请求中的授权码等原始内容不得进入响应。
     *
     * @param exception HttpMessageNotReadableException，畸形或类型不兼容的 JSON 异常
     * @return ResponseEntity&lt;AjaxResult&gt;，固定安全提示的 HTTP 400
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AjaxResult> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException exception)
    {
        return badRequest("请求体格式不合法");
    }

    /**
     * 构造通知管理接口统一的真实 HTTP 400 响应。
     *
     * @param message String，可安全返回用户的参数错误提示
     * @return ResponseEntity&lt;AjaxResult&gt;，HTTP 状态和响应体 code 同为 400
     */
    private ResponseEntity<AjaxResult> badRequest(String message)
    {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(AjaxResult.error(HttpStatus.BAD_REQUEST, message));
    }
}

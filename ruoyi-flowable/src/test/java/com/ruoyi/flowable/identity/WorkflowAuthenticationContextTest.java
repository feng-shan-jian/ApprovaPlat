package com.ruoyi.flowable.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import org.flowable.engine.IdentityService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class WorkflowAuthenticationContextTest
{
    /**
     * 验证正常调用会返回业务结果，并在最外层结束后清空 Flowable 身份。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void returnsResultAndClearsAuthentication()
    {
        IdentityService identityService = mock(IdentityService.class);
        WorkflowAuthenticationContext context = new WorkflowAuthenticationContext(identityService,
                new WorkflowIdentityCodec());

        String result = context.runAs("100", () -> "done");

        assertThat(result).isEqualTo("done");
        InOrder calls = inOrder(identityService);
        calls.verify(identityService).setAuthenticatedUserId("100");
        calls.verify(identityService).setAuthenticatedUserId(null);
    }

    /**
     * 验证业务操作抛出异常时仍会清理 Flowable 身份并原样传播异常。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void clearsAuthenticationWhenActionFails()
    {
        IdentityService identityService = mock(IdentityService.class);
        WorkflowAuthenticationContext context = new WorkflowAuthenticationContext(identityService,
                new WorkflowIdentityCodec());

        assertThatThrownBy(() -> context.runAs("100", () ->
        {
            throw new IllegalStateException("expected");
        })).isInstanceOf(IllegalStateException.class).hasMessage("expected");

        InOrder calls = inOrder(identityService);
        calls.verify(identityService).setAuthenticatedUserId("100");
        calls.verify(identityService).setAuthenticatedUserId(null);
    }

    /**
     * 验证嵌套调用结束后恢复外层用户，最外层结束后再清空线程身份。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void restoresOuterAuthenticationAfterNestedCall()
    {
        IdentityService identityService = mock(IdentityService.class);
        WorkflowAuthenticationContext context = new WorkflowAuthenticationContext(identityService,
                new WorkflowIdentityCodec());

        context.runAs("100", () -> context.runAs("200", () -> "nested"));

        InOrder calls = inOrder(identityService);
        calls.verify(identityService).setAuthenticatedUserId("100");
        calls.verify(identityService).setAuthenticatedUserId("200");
        calls.verify(identityService).setAuthenticatedUserId("100");
        calls.verify(identityService).setAuthenticatedUserId(null);
    }

    /**
     * 验证显式操作人会规范为数字用户 ID，非法或溢出标识不会写入 Flowable 身份上下文。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void normalizesAndValidatesExplicitActor()
    {
        IdentityService identityService = mock(IdentityService.class);
        WorkflowAuthenticationContext context = new WorkflowAuthenticationContext(identityService,
                new WorkflowIdentityCodec());

        context.runAs("0007", () -> "done");

        InOrder calls = inOrder(identityService);
        calls.verify(identityService).setAuthenticatedUserId("7");
        calls.verify(identityService).setAuthenticatedUserId(null);
        assertThatThrownBy(() -> context.runAs("9223372036854775808", () -> "never"))
                .isInstanceOf(com.ruoyi.common.exception.ServiceException.class)
                .hasMessage("工作流用户标识无效");
    }
}

package com.ruoyi.flowable.identity;

import java.util.Objects;
import java.util.function.Supplier;
import org.flowable.engine.IdentityService;
import org.springframework.stereotype.Component;
import com.ruoyi.common.utils.SecurityUtils;

/**
 * 统一管理 Flowable 命令执行期间的认证用户。
 *
 * 所有需要记录发起人、操作人或审计人的引擎写操作都应通过本组件执行，
 * 以保证线程复用、异常和嵌套调用时认证信息不会泄漏到后续请求。
 */
@Component
public class WorkflowAuthenticationContext
{
    /** 当前组件维护的认证用户，用于嵌套调用结束后恢复外层身份。 */
    private final ThreadLocal<String> authenticatedActor = new ThreadLocal<>();

    private final IdentityService identityService;

    private final WorkflowIdentityCodec identityCodec;

    /**
     * 创建工作流认证上下文。
     *
     * @param identityService IdentityService，Flowable 对外提供的身份上下文服务
     * @param identityCodec WorkflowIdentityCodec，数字用户 ID 校验和规范化组件
     * @return 无返回值，构造完成后由 Spring 管理该组件
     */
    public WorkflowAuthenticationContext(IdentityService identityService, WorkflowIdentityCodec identityCodec)
    {
        this.identityService = identityService;
        this.identityCodec = identityCodec;
    }

    /**
     * 使用当前登录用户执行有返回值的引擎操作。
     *
     * @param action Supplier&lt;T&gt;，需要在当前用户身份下执行的操作
     * @return T，引擎操作返回的业务结果
     */
    public <T> T runAsCurrentUser(Supplier<T> action)
    {
        return runAs(String.valueOf(SecurityUtils.getUserId()), action);
    }

    /**
     * 使用当前登录用户执行无返回值的引擎操作。
     *
     * @param action Runnable，需要在当前用户身份下执行的操作
     * @return 无返回值
     */
    public void runAsCurrentUser(Runnable action)
    {
        runAs(String.valueOf(SecurityUtils.getUserId()), action);
    }

    /**
     * 使用指定用户执行有返回值的引擎操作，并在结束后恢复外层身份。
     *
     * @param actorUserId String，若依用户主键的字符串形式
     * @param action Supplier&lt;T&gt;，需要在指定用户身份下执行的操作
     * @return T，引擎操作返回的业务结果
     */
    public <T> T runAs(String actorUserId, Supplier<T> action)
    {
        String normalizedActorUserId = validateArguments(actorUserId, action);
        String previousActor = authenticatedActor.get();

        identityService.setAuthenticatedUserId(normalizedActorUserId);
        authenticatedActor.set(normalizedActorUserId);
        try
        {
            return action.get();
        }
        finally
        {
            // 嵌套调用恢复外层身份；最外层调用必须清空 ThreadLocal 和 Flowable 身份。
            if (previousActor == null)
            {
                identityService.setAuthenticatedUserId(null);
                authenticatedActor.remove();
            }
            else
            {
                identityService.setAuthenticatedUserId(previousActor);
                authenticatedActor.set(previousActor);
            }
        }
    }

    /**
     * 使用指定用户执行无返回值的引擎操作，并在结束后恢复外层身份。
     *
     * @param actorUserId String，若依用户主键的字符串形式
     * @param action Runnable，需要在指定用户身份下执行的操作
     * @return 无返回值
     */
    public void runAs(String actorUserId, Runnable action)
    {
        Objects.requireNonNull(action, "工作流操作不能为空");
        runAs(actorUserId, () ->
        {
            action.run();
            return null;
        });
    }

    /**
     * 校验显式执行身份和有返回值操作，阻止匿名审计记录或空操作进入引擎。
     *
     * @param actorUserId String，若依用户主键的字符串形式
     * @param action Supplier&lt;T&gt;，待执行的引擎操作
     * @return String，规范化后的正整数用户 ID；参数非法时抛出 ServiceException 或 NullPointerException
     */
    private <T> String validateArguments(String actorUserId, Supplier<T> action)
    {
        Objects.requireNonNull(action, "工作流操作不能为空");
        return identityCodec.normalizeUserId(actorUserId);
    }
}

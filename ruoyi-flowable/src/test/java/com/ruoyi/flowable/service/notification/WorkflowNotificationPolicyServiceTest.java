package com.ruoyi.flowable.service.notification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowNotificationPolicyRequest;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;

/**
 * 验证通知策略在进入持久化前执行模板头注入与控制字符校验。
 */
class WorkflowNotificationPolicyServiceTest
{
    private JdbcTemplate jdbcTemplate;
    private WorkflowIdentityResolver identityResolver;
    private WorkflowMailConfigService mailConfigService;
    private WorkflowNotificationPolicyService service;

    /**
     * 装配只执行生产参数校验的策略服务，数据库和身份依赖保持为可验证 mock。
     *
     * @return void，无返回值
     */
    @BeforeEach
    void setUp()
    {
        jdbcTemplate = mock(JdbcTemplate.class);
        identityResolver = mock(WorkflowIdentityResolver.class);
        mailConfigService = mock(WorkflowMailConfigService.class);
        service = new WorkflowNotificationPolicyService(jdbcTemplate, identityResolver,
                mock(WorkflowNotificationCatalogService.class), mailConfigService);
    }

    /**
     * 验证 SMTP Subject 使用的标题模板拒绝 CR/LF，防止注入额外邮件头。
     *
     * @return void，标题换行未在数据库写入前返回 HTTP 400 时测试失败
     */
    @Test
    void rejectsLineBreaksInTitleTemplateBeforePersistence()
    {
        WorkflowNotificationPolicyRequest request = request(
                "审批提醒\r\nBcc: hidden@example.com", "正文允许\n换行");

        assertInvalid(request, "通知标题模板不能包含换行或控制字符");
    }

    /**
     * 验证正文只允许 CR、LF、TAB 排版字符，其他 ISO 控制字符必须拒绝。
     *
     * @return void，非法正文控制字符未在数据库写入前返回 HTTP 400 时测试失败
     */
    @Test
    void rejectsNonLayoutControlCharactersInContentTemplate()
    {
        WorkflowNotificationPolicyRequest request = request(
                "审批提醒", "正文包含" + (char) 0x01 + "非法字符");

        assertInvalid(request, "通知正文模板包含非法控制字符");
    }

    /**
     * 构造停用的全局站内信策略，使测试只聚焦模板校验而不依赖 SMTP 或数据库。
     *
     * @param title String，待校验标题模板
     * @param content String，待校验正文模板
     * @return WorkflowNotificationPolicyRequest，其他字段均为合法值的新增请求
     */
    private WorkflowNotificationPolicyRequest request(String title, String content)
    {
        return new WorkflowNotificationPolicyRequest(null, "DEFAULT", null, null,
                "TASK_ARRIVED", "TASK_RECIPIENT", "INBOX", null, title, content,
                3, "DISABLED", null);
    }

    /**
     * 断言非法模板返回稳定 HTTP 400，且不会读取身份、SMTP 或执行任何数据库访问。
     *
     * @param request WorkflowNotificationPolicyRequest，包含非法模板的请求
     * @param message String，期望的稳定业务提示
     * @return void，异常合同或副作用边界漂移时测试失败
     */
    private void assertInvalid(WorkflowNotificationPolicyRequest request, String message)
    {
        assertThatThrownBy(() -> service.savePolicy(request))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    org.assertj.core.api.Assertions.assertThat(exception.getCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST);
                    org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                            .isEqualTo(message);
                });
        verifyNoInteractions(jdbcTemplate, identityResolver, mailConfigService);
    }
}

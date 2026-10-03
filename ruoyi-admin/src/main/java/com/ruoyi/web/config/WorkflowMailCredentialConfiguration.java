package com.ruoyi.web.config;

import java.security.SecureRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.ruoyi.flowable.service.notification.WorkflowMailCredentialCipher;
import com.ruoyi.framework.web.service.TokenService;

/** 应用装配层从既有 Token 根密钥派生邮件专用子密钥。 */
@Configuration(proxyBeanMethods = false)
public class WorkflowMailCredentialConfiguration
{
    @Bean
    public WorkflowMailCredentialCipher workflowMailCredentialCipher(TokenService tokenService)
    {
        return new WorkflowMailCredentialCipher(
                tokenService.deriveAes256Key("approvaplat-mail-credential"), new SecureRandom());
    }
}

package com.ruoyi.web.core.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.ruoyi.common.config.RuoYiConfig;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * 开发环境 OpenAPI 接口文档配置。
 * 
 * @author ruoyi
 */
@Configuration
public class SwaggerConfig
{
    /** 系统基础配置 */
    @Autowired
    private RuoYiConfig ruoyiConfig;
    
    /**
     * 自定义的 OpenAPI 对象
     */
    @Bean
    public OpenAPI customOpenApi()
    {
        return new OpenAPI().components(new Components()
            // 设置认证的请求头
            .addSecuritySchemes("apikey", securityScheme()))
            .addSecurityItem(new SecurityRequirement().addList("apikey"))
            .info(getApiInfo());
    }

    /**
     * 构建与实际 Spring Security JWT 认证链一致的 HTTP Bearer 方案。
     *
     * @return SecurityScheme，使用 Authorization: Bearer &lt;JWT&gt; 的 OpenAPI 认证定义
     */
    @Bean
    public SecurityScheme securityScheme()
    {
        return new SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT");
    }
    
    /**
     * 构建 ApprovaPlat 的 OpenAPI 摘要信息。
     *
     * @return 包含平台名称、用途和版本号的 {@link Info} 元数据。
     */
    public Info getApiInfo()
    {
        return new Info()
            // 设置标题
            .title("ApprovaPlat API")
            // 描述
            .description("ApprovaPlat 审批管理平台后端接口文档")
            // 作者信息
            .contact(new Contact().name(ruoyiConfig.getName()))
            // 版本
            .version("版本号:" + ruoyiConfig.getVersion());
    }
}

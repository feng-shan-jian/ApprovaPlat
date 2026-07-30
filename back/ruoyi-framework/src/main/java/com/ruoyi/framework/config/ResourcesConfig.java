package com.ruoyi.framework.config;

import java.io.IOException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.utils.file.FileUtils;
import com.ruoyi.framework.interceptor.RepeatSubmitInterceptor;

/**
 * 通用配置
 * 
 * @author ruoyi
 */
@Configuration
public class ResourcesConfig implements WebMvcConfigurer
{
    @Autowired
    private RepeatSubmitInterceptor repeatSubmitInterceptor;

    /**
     * 注册公开 profile 资源，并从公开资源解析链排除工作流私有附件目录。
     *
     * @param registry ResourceHandlerRegistry，Spring MVC 静态资源注册器
     * @return void，无返回值
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry)
    {
        /** 本地文件上传路径 */
        registry.addResourceHandler(Constants.RESOURCE_PREFIX + "/**")
                .addResourceLocations("file:" + RuoYiConfig.getProfile() + "/")
                .resourceChain(true)
                .addResolver(new ProtectedProfilePathResourceResolver());
    }

    /**
     * 自定义拦截规则
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry)
    {
        registry.addInterceptor(repeatSubmitInterceptor).addPathPatterns("/**");
    }

    /**
     * 跨域配置
     */
    @Bean
    public CorsFilter corsFilter()
    {
        CorsConfiguration config = new CorsConfiguration();
        // 设置访问源地址
        config.addAllowedOriginPattern("*");
        // 设置访问源请求头
        config.addAllowedHeader("*");
        // 设置访问源请求方法
        config.addAllowedMethod("*");
        // 有效期 1800秒
        config.setMaxAge(1800L);
        // 添加映射路径，拦截一切请求
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        // 返回新的CorsFilter
        return new CorsFilter(source);
    }

    /**
     * profile 静态资源解析器，拒绝任何可解析到工作流私有附件目录的请求路径。
     */
    static class ProtectedProfilePathResourceResolver extends PathResourceResolver
    {
        /**
         * 在默认文件解析前拒绝私有附件目录，其他 profile 资源保持原有行为。
         *
         * @param resourcePath String，Spring 从 /profile/** 提取的相对资源路径
         * @param location Resource，若依 profile 文件资源根
         * @return Resource，允许公开的资源；私有目录或非法编码返回 null
         * @throws IOException 默认路径资源解析失败
         */
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException
        {
            if (FileUtils.isProtectedProfilePath(resourcePath))
            {
                return null;
            }
            Resource resolved = super.getResource(resourcePath, location);
            if (resolved == null)
            {
                return null;
            }
            try
            {
                Path profileRoot = location.getFile().toPath();
                Path safeRealFile = FileUtils.resolvePublicProfileFile(
                        profileRoot, profileRoot, resolved.getFile().toPath());
                return new FileSystemResource(safeRealFile);
            }
            catch (IOException | RuntimeException unsafePath)
            {
                // 静态资源链无法证明真实文件仍处于公开根时必须按不存在处理。
                return null;
            }
        }
    }
}

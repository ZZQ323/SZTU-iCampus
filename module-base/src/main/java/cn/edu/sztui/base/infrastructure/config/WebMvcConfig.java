package cn.edu.sztui.base.infrastructure.config;

import cn.edu.sztui.base.application.service.AccessTouchInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册 AccessTouchInterceptor
 * <p>
 * 如果已有 WebMvcConfigurer，把 addInterceptors 的内容合并进去即可。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private AccessTouchInterceptor accessTouchInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accessTouchInterceptor)
            .addPathPatterns("/**")
            .excludePathPatterns("/wx-auth/v1/get-token", "/wx-auth/v1/refresh-token");
    }
}
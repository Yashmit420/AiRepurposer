package com.ai.repurposer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthTokenInterceptor authTokenInterceptor;
    private final String[] allowedOrigins;
    private final String[] allowedMethods;
    private final String[] allowedHeaders;

    public WebConfig(
        AuthTokenInterceptor authTokenInterceptor,
        @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:5173,http://127.0.0.1:5500,http://localhost:5500,https://*.vercel.app,https://*.netlify.app,https://ai-repurposer.netlify.app,https://attractive-youth-production-c125.up.railway.app}") String origins,
        @Value("${app.cors.allowed-methods:GET,POST,PUT,PATCH,DELETE,OPTIONS}") String methods,
        @Value("${app.cors.allowed-headers:*}") String headers
    ) {
        this.authTokenInterceptor = authTokenInterceptor;
        this.allowedOrigins = splitAndTrim(origins);
        this.allowedMethods = splitAndTrim(methods);
        this.allowedHeaders = splitAndTrim(headers);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authTokenInterceptor)
            .addPathPatterns("/**")
            .excludePathPatterns(
                "/",
                "/health",
                "/error",
                "/login",
                "/signup",
                "/signup/request-otp",
                "/user/exists",
                "/password/**",
                "/admin/**"
            );
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
            .allowedOriginPatterns(allowedOrigins)
            .allowedMethods(allowedMethods)
            .allowedHeaders(allowedHeaders)
            .exposedHeaders("Content-Type", "X-Auth-Token")
            .allowCredentials(false)
            .maxAge(3600);
    }

    private static String[] splitAndTrim(String input) {
        return Arrays.stream(input.split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .toArray(String[]::new);
    }
}



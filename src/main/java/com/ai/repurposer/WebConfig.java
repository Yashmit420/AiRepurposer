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
        @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:5173,airepurposer-production.up.railway.app}") String origins,
        @Value("${app.cors.allowed-methods:GET,POST,OPTIONS}") String methods,
        @Value("${app.cors.allowed-headers:Content-Type,Authorization,X-Auth-Token}") String headers
    ) {
        this.authTokenInterceptor = authTokenInterceptor;
        this.allowedOrigins = splitAndTrim(origins);
        this.allowedMethods = splitAndTrim(methods);
        this.allowedHeaders = splitAndTrim(headers);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authTokenInterceptor).addPathPatterns("/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
            .allowedOrigins(allowedOrigins)
            .allowedMethods(allowedMethods)
            .allowedHeaders(allowedHeaders)
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


package com.toysystem.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * spring.cloud.gateway.globalcors 只对"被网关代理的路由"生效，
 * 对网关自己暴露的本地接口（比如 /auth/login）和 /actuator/** 完全不起作用——
 * 这两类请求走的是普通 WebFlux DispatcherHandler，不经过网关路由过滤器链。
 * 所以这里用一个作用于整个应用（"/**"）的 CorsWebFilter，统一处理所有请求。
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig {

    @Bean
    public CorsWebFilter corsWebFilter(CorsProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.copyOf(properties.getAllowedOrigins()));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}

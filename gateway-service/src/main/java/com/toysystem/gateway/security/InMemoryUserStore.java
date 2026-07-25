package com.toysystem.gateway.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * P2阶段没有独立的用户/账号服务，登录校验先用硬编码演示账号代替。
 * 后续如果要练用户体系，可以在此基础上换成真正的 user-service + DB。
 */
@Component
public class InMemoryUserStore {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final Map<String, String> usernameToPasswordHash = Map.of(
            "admin", encoder.encode("admin123")
    );

    public boolean authenticate(String username, String rawPassword) {
        String hash = usernameToPasswordHash.get(username);
        return hash != null && encoder.matches(rawPassword, hash);
    }
}

package dev.recon.service.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param jwtSecret     HMAC-SHA256 key (>= 32 bytes), from JWT_SECRET
 * @param tokenTtlMinutes lifetime of issued tokens
 * @param demoUsers     passwords for the three seeded demo users (from .env); blank = not seeded
 */
@ConfigurationProperties(prefix = "recon.security")
public record SecurityProperties(String jwtSecret, @DefaultValue("480") long tokenTtlMinutes, DemoUsers demoUsers) {

    public record DemoUsers(String analystPassword, String supervisorPassword, String adminPassword) {
    }
}

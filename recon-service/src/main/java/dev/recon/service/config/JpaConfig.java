package dev.recon.service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(basePackages = "dev.recon.service.domain", considerNestedRepositories = true)
public class JpaConfig {
}

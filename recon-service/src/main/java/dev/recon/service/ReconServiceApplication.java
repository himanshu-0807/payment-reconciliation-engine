package dev.recon.service;

import dev.recon.common.store.StorageConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ConfigurationPropertiesScan
@Import(StorageConfig.class)
public class ReconServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReconServiceApplication.class, args);
    }
}

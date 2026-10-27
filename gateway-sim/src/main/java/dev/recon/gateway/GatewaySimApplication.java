package dev.recon.gateway;

import dev.recon.common.store.StorageConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(StorageConfig.class)
public class GatewaySimApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewaySimApplication.class, args);
    }
}

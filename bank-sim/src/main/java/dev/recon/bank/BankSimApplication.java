package dev.recon.bank;

import dev.recon.common.store.StorageConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(StorageConfig.class)
public class BankSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankSimApplication.class, args);
    }
}

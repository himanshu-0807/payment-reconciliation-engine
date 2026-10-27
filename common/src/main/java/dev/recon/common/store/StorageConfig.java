package dev.recon.common.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Selects the object store: {@code storage.type=s3} (AWS SDK v2 against a configurable endpoint,
 * LocalStack by default) or {@code storage.type=local} (filesystem fallback).
 */
@Configuration
@EnableConfigurationProperties(StorageConfig.StorageProperties.class)
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @ConfigurationProperties(prefix = "storage")
    public record StorageProperties(String type, S3 s3, Local local) {
        public record S3(String endpoint, String region, String bucket, String accessKey, String secretKey) {
        }

        public record Local(String root) {
        }
    }

    @Bean
    public ObjectStore objectStore(StorageProperties props) {
        ObjectStore store;
        if ("local".equalsIgnoreCase(props.type())) {
            store = new LocalFsObjectStore(Path.of(props.local() == null || props.local().root() == null
                    ? "./data/object-store" : props.local().root()));
        } else {
            StorageProperties.S3 s3 = props.s3();
            S3ObjectStore s3Store = new S3ObjectStore(s3.endpoint(), s3.region(), s3.bucket(),
                    s3.accessKey(), s3.secretKey());
            s3Store.ensureBucket();
            store = s3Store;
        }
        log.info("Object store: {}", store.describe());
        return store;
    }
}

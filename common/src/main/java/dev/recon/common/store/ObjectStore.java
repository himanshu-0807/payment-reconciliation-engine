package dev.recon.common.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Minimal object storage used for settlement files, bank statements and ground truth. Implemented
 * by {@link S3ObjectStore} (AWS SDK v2; LocalStack locally) and {@link LocalFsObjectStore}.
 */
public interface ObjectStore {

    record ObjectInfo(String key, long size, String etag) {
    }

    void put(String key, Path file) throws IOException;

    void put(String key, byte[] content) throws IOException;

    InputStream get(String key) throws IOException;

    boolean exists(String key) throws IOException;

    /** Lists objects under a prefix, sorted by key. */
    List<ObjectInfo> list(String prefix) throws IOException;

    void deletePrefix(String prefix) throws IOException;

    String describe();
}

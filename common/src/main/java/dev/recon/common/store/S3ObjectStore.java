package dev.recon.common.store;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * S3 store built on AWS SDK v2. The endpoint is configurable so it points at LocalStack locally;
 * leave it blank to use the real AWS endpoint resolution (never done in this project).
 */
public class S3ObjectStore implements ObjectStore {

    private final S3Client s3;
    private final String bucket;
    private final String endpoint;

    public S3ObjectStore(String endpoint, String region, String bucket, String accessKey, String secretKey) {
        S3ClientBuilder b = S3Client.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .forcePathStyle(true);
        if (endpoint != null && !endpoint.isBlank()) {
            b.endpointOverride(URI.create(endpoint));
        }
        if (accessKey != null && !accessKey.isBlank()) {
            b.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        } else {
            b.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        this.s3 = b.build();
        this.bucket = bucket;
        this.endpoint = endpoint;
    }

    /** Creates the bucket if it does not exist (idempotent). */
    public void ensureBucket() {
        try {
            s3.headBucket(r -> r.bucket(bucket));
        } catch (S3Exception e) {
            try {
                s3.createBucket(r -> r.bucket(bucket));
            } catch (BucketAlreadyOwnedByYouException ignored) {
                // created concurrently by another service
            } catch (S3Exception e2) {
                if (!"BucketAlreadyOwnedByYou".equals(e2.awsErrorDetails().errorCode())) {
                    throw e2;
                }
            }
        }
    }

    @Override
    public void put(String key, Path file) {
        s3.putObject(r -> r.bucket(bucket).key(key).contentType("text/csv"), RequestBody.fromFile(file));
    }

    @Override
    public void put(String key, byte[] content) {
        s3.putObject(r -> r.bucket(bucket).key(key).contentType("text/csv"), RequestBody.fromBytes(content));
    }

    @Override
    public InputStream get(String key) {
        return s3.getObject(r -> r.bucket(bucket).key(key));
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(r -> r.bucket(bucket).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public List<ObjectInfo> list(String prefix) {
        List<ObjectInfo> out = new ArrayList<>();
        for (S3Object o : s3.listObjectsV2Paginator(r -> r.bucket(bucket).prefix(prefix)).contents()) {
            out.add(new ObjectInfo(o.key(), o.size(), o.eTag() == null ? "" : o.eTag().replace("\"", "")));
        }
        out.sort(Comparator.comparing(ObjectInfo::key));
        return out;
    }

    @Override
    public void deletePrefix(String prefix) {
        List<ObjectInfo> all = list(prefix);
        for (int i = 0; i < all.size(); i += 1000) {
            List<ObjectIdentifier> ids = all.subList(i, Math.min(all.size(), i + 1000)).stream()
                    .map(o -> ObjectIdentifier.builder().key(o.key()).build()).toList();
            s3.deleteObjects(r -> r.bucket(bucket).delete(Delete.builder().objects(ids).build()));
        }
    }

    @Override
    public String describe() {
        return "s3://" + bucket + (endpoint == null || endpoint.isBlank() ? "" : " @ " + endpoint);
    }
}

package dev.recon.service.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One error shape for every endpoint: RFC 7807 problem+json with a stable machine-readable
 * {@code code}, a timestamp, the request path and, for validation failures, per-field errors.
 */
public final class ApiErrors {

    private ApiErrors() {
    }

    public static ProblemDetail problem(HttpStatus status, String code, String detail, String path) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(status.getReasonPhrase());
        p.setType(URI.create("https://github.com/himanshu-0807/payment-reconciliation-engine/blob/main/docs/API.md#" + code.toLowerCase()));
        p.setProperty("code", code);
        p.setProperty("timestamp", Instant.now().toString());
        if (path != null) {
            p.setInstance(URI.create(path));
        }
        return p;
    }

    public static ProblemDetail validation(String path, List<Map<String, String>> errors) {
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "request validation failed", path);
        p.setProperty("errors", errors);
        return p;
    }
}

package dev.recon.service.api;

/** A dependency (gateway-sim, bank SOAP endpoint) failed or is unreachable (502). */
public class UpstreamException extends RuntimeException {
    public UpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}

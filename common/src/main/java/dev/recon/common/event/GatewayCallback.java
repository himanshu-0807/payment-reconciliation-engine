package dev.recon.common.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Webhook-style callback the gateway publishes to Kafka topic {@code gateway.callbacks}.
 * {@code eventId} is stable across redeliveries and duplicates; consumers dedupe on it.
 */
public record GatewayCallback(
        UUID eventId,
        String paymentId,
        String gatewayTxnId,
        String merchantId,
        LocalDate businessDate,
        String status,
        long amountMinor,
        String currency,
        Instant eventTime) {

    public static final String TOPIC = "gateway.callbacks";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_PENDING = "PENDING";
}

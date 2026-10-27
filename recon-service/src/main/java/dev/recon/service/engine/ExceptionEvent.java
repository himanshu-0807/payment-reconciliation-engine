package dev.recon.service.engine;

import java.time.LocalDate;

/** Published to Kafka topic recon.exceptions when an exception is created or changes status. */
public record ExceptionEvent(long exceptionId, String action, String entityType, String entityId,
                             LocalDate businessDate, String merchantId, String exceptionClass, String reason,
                             String status, long amountAtRiskMinor, String currency, long runId) {
}

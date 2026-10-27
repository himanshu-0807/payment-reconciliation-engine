package dev.recon.service.engine;

import java.time.LocalDate;
import java.util.List;

/** Classification of one entity: a payment, or a bank credit that matched no payment. */
public record Outcome(
        String entityType,
        String entityId,
        LocalDate businessDate,
        String merchantId,
        ResultClass resultClass,
        String reason,
        String confidence,
        List<Finding> findings,
        Long ledgerAmountMinor,
        Long gatewayAmountMinor,
        Long bankAmountMinor,
        long atRiskMinor,
        String currency,
        String paymentId,
        String gatewayTxnId,
        String utr,
        List<String> bankRefs) {

    public static final String PAYMENT = "PAYMENT";
    public static final String BANK_CREDIT = "BANK_CREDIT";
    public static final String HIGH = "HIGH";
    public static final String LOW = "LOW";

    /** The primary finding (the one that decided resultClass/reason). */
    public Finding primary() {
        return findings.stream().filter(f -> f.resultClass() == resultClass && f.reason().equals(reason))
                .findFirst().orElse(new Finding(resultClass, reason, atRiskMinor));
    }

    public String findingsText() {
        StringBuilder sb = new StringBuilder();
        for (Finding f : findings) {
            if (!sb.isEmpty()) {
                sb.append(';');
            }
            sb.append(f.resultClass()).append(':').append(f.reason());
        }
        return sb.length() > 300 ? sb.substring(0, 300) : sb.toString();
    }

    /** Canonical line used for the run digest (parallelism-invariance proof). */
    public String digestLine() {
        return entityType + '|' + entityId + '|' + businessDate + '|' + merchantId + '|' + resultClass + '|' + reason
                + '|' + confidence + '|' + atRiskMinor + '|' + findingsText();
    }
}

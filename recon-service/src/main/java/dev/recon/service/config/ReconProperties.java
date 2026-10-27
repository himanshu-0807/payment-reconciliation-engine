package dev.recon.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunables of the reconciliation engine and auto-fix rules. Amounts are paise.
 *
 * @param parallelism             concurrent (business date, merchant) partitions per run
 * @param toleranceMinor          |difference| at or below this is a fee/rounding mismatch
 * @param approvalThresholdMinor  auto-adjustments with |amount| above this need OPS_SUPERVISOR approval
 * @param settlementDays          bank credit expected by business date + this many days (T+2)
 * @param graceDays               missing credits stay PENDING_EXPECTED until business date + graceDays
 * @param fallbackWindowDays      date window for amount+merchant fallback matching
 */
@ConfigurationProperties(prefix = "recon")
public record ReconProperties(
        @DefaultValue("4") int parallelism,
        @DefaultValue("100") long toleranceMinor,
        @DefaultValue("50") long approvalThresholdMinor,
        @DefaultValue("2") int settlementDays,
        @DefaultValue("5") int graceDays,
        @DefaultValue("7") int fallbackWindowDays,
        @DefaultValue AutoFix autoFix,
        @DefaultValue("http://localhost:8081") String gatewaySimUrl,
        @DefaultValue("http://localhost:8082/ws") String bankSoapUrl) {

    /** Each auto-fix rule can be switched off, sending those cases to the ops queue instead. */
    public record AutoFix(
            @DefaultValue("true") boolean dedupeCallbacks,
            @DefaultValue("true") boolean pendingExpected,
            @DefaultValue("true") boolean feeAdjustments,
            @DefaultValue("true") boolean lateSettlementWithinGrace) {
    }
}

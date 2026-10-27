package dev.recon.common.sim;

/**
 * Faults the simulator can inject. Each fault has exactly one outcome the reconciliation engine is
 * expected to report for the affected entity (see {@link #expectedClass()} / {@link #expectedReason()}).
 * The last two faults are additions beyond the original brief so that every exception class and
 * the low-confidence fallback path are exercised by the ground-truth test.
 */
public enum FaultType {
    NONE("MATCHED", "CLEAN"),
    DUPLICATE_CALLBACK("DUPLICATE", "DUPLICATE_CALLBACK"),
    MISSING_CALLBACK("MISSING_IN_LEDGER", "NO_CALLBACK"),
    TIMEOUT_THEN_SUCCESS("MATCHED", "OUT_OF_ORDER_CALLBACK"),
    DELAYED_SETTLEMENT("LATE_SETTLEMENT", "SETTLED_WITHIN_GRACE"),
    AMOUNT_MISMATCH_FEE("AMOUNT_MISMATCH", "WITHIN_TOLERANCE"),
    AMOUNT_MISMATCH_REAL("AMOUNT_MISMATCH", "BEYOND_TOLERANCE"),
    MISSING_BANK_RECORD("MISSING_IN_BANK", "NOT_CREDITED"),
    ORPHAN_BANK_CREDIT("ORPHAN_BANK_CREDIT", "UNKNOWN_UTR"),
    DUPLICATE_BANK_CREDIT("DUPLICATE", "DUPLICATE_BANK_CREDIT"),
    MISSING_GATEWAY_RECORD("MISSING_IN_GATEWAY", "NOT_IN_SETTLEMENT_FILE"),
    BANK_UTR_MISSING("MATCHED", "LOW_CONFIDENCE_FALLBACK");

    private final String expectedClass;
    private final String expectedReason;

    FaultType(String expectedClass, String expectedReason) {
        this.expectedClass = expectedClass;
        this.expectedReason = expectedReason;
    }

    public String expectedClass() {
        return expectedClass;
    }

    public String expectedReason() {
        return expectedReason;
    }

    /** Maps an engine outcome (class + reason) back to the fault that should have produced it. */
    public static FaultType fromOutcome(String exceptionClass, String reason) {
        for (FaultType f : values()) {
            if (f.expectedClass.equals(exceptionClass) && f.expectedReason.equals(reason)) {
                return f;
            }
        }
        return null;
    }
}

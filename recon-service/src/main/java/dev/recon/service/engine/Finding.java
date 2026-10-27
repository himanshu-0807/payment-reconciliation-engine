package dev.recon.service.engine;

/**
 * One problem detected on an entity. Several findings may apply; precedence picks the primary.
 * {@code signedDiffMinor} is actual minus expected for amount findings (0 otherwise); it becomes the
 * adjustment journal amount when a fee/rounding mismatch is auto-fixed.
 */
public record Finding(ResultClass resultClass, String reason, long atRiskMinor, long signedDiffMinor) {

    public Finding(ResultClass resultClass, String reason, long atRiskMinor) {
        this(resultClass, reason, atRiskMinor, 0);
    }

    public static final String CLEAN = "CLEAN";
    public static final String OUT_OF_ORDER_CALLBACK = "OUT_OF_ORDER_CALLBACK";
    public static final String LOW_CONFIDENCE_FALLBACK = "LOW_CONFIDENCE_FALLBACK";
    public static final String NO_CALLBACK = "NO_CALLBACK";
    public static final String LEDGER_NOT_SUCCESS = "LEDGER_NOT_SUCCESS";
    public static final String NOT_IN_SETTLEMENT_FILE = "NOT_IN_SETTLEMENT_FILE";
    public static final String DUPLICATE_BANK_CREDIT = "DUPLICATE_BANK_CREDIT";
    public static final String DUPLICATE_CALLBACK = "DUPLICATE_CALLBACK";
    public static final String BEYOND_TOLERANCE = "BEYOND_TOLERANCE";
    public static final String WITHIN_TOLERANCE = "WITHIN_TOLERANCE";
    public static final String CURRENCY_MISMATCH = "CURRENCY_MISMATCH";
    public static final String NOT_CREDITED = "NOT_CREDITED";
    public static final String AWAITING_CREDIT = "AWAITING_CREDIT";
    public static final String SETTLED_WITHIN_GRACE = "SETTLED_WITHIN_GRACE";
    public static final String SETTLED_AFTER_GRACE = "SETTLED_AFTER_GRACE";
    public static final String UNKNOWN_UTR = "UNKNOWN_UTR";
    public static final String NO_REFERENCE = "NO_REFERENCE";
    public static final String DUPLICATE_SETTLEMENT_ROW = "DUPLICATE_SETTLEMENT_ROW";

    /**
     * Precedence, highest first. Money-moving and unverifiable problems outrank timing problems,
     * which outrank problems already absorbed by auto-fix. Documented in docs/DESIGN.md.
     */
    public int rank() {
        return switch (resultClass) {
            case MISSING_IN_LEDGER -> 1;
            case MISSING_IN_GATEWAY -> 2;
            case DUPLICATE -> DUPLICATE_BANK_CREDIT.equals(reason) ? 3 : 8;
            case AMOUNT_MISMATCH -> WITHIN_TOLERANCE.equals(reason) ? 7 : 4;
            case MISSING_IN_BANK -> NOT_CREDITED.equals(reason) ? 5 : 6;
            case LATE_SETTLEMENT -> SETTLED_AFTER_GRACE.equals(reason) ? 6 : 7;
            case ORPHAN_BANK_CREDIT -> 1;
            case MATCHED -> 9;
        };
    }
}

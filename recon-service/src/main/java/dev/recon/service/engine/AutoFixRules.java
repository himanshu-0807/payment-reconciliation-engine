package dev.recon.service.engine;

import dev.recon.service.config.ReconProperties;

/**
 * Decides what happens to a newly detected exception. Pure function; every rule can be disabled,
 * in which case the case goes to the ops queue as OPEN.
 */
public final class AutoFixRules {

    public static final String DEDUPED = "DEDUPED_NO_MONEY_EFFECT";
    public static final String WITHIN_GRACE = "SETTLED_WITHIN_GRACE";
    public static final String ADJUSTMENT_POSTED = "ADJUSTMENT_POSTED";

    /**
     * @param status            initial queue status
     * @param resolutionReason  set when the rule closes the case automatically
     * @param adjustmentMinor   signed adjustment amount to journal, or null
     * @param adjustmentStatus  POSTED or PENDING_APPROVAL when an adjustment is created
     */
    public record Decision(String status, String resolutionReason, Long adjustmentMinor, String adjustmentStatus) {
        static Decision open() {
            return new Decision("OPEN", null, null, null);
        }
    }

    private AutoFixRules() {
    }

    public static Decision decide(Outcome o, ReconProperties.AutoFix fix, long approvalThresholdMinor) {
        String key = o.resultClass() + "/" + o.reason();
        return switch (key) {
            case "DUPLICATE/DUPLICATE_CALLBACK" ->
                    fix.dedupeCallbacks() ? new Decision("AUTO_RESOLVED", DEDUPED, null, null) : Decision.open();
            case "MISSING_IN_BANK/AWAITING_CREDIT" ->
                    fix.pendingExpected() ? new Decision("PENDING_EXPECTED", null, null, null) : Decision.open();
            case "LATE_SETTLEMENT/SETTLED_WITHIN_GRACE" ->
                    fix.lateSettlementWithinGrace() ? new Decision("AUTO_RESOLVED", WITHIN_GRACE, null, null) : Decision.open();
            case "AMOUNT_MISMATCH/WITHIN_TOLERANCE" -> {
                if (!fix.feeAdjustments()) {
                    yield Decision.open();
                }
                long adj = o.primary().signedDiffMinor();
                yield Math.abs(adj) <= approvalThresholdMinor
                        ? new Decision("AUTO_RESOLVED", ADJUSTMENT_POSTED, adj, "POSTED")
                        : new Decision("OPEN", null, adj, "PENDING_APPROVAL");
            }
            default -> Decision.open();
        };
    }
}

package dev.recon.service.engine;

import dev.recon.service.config.ReconProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AutoFixRulesTest {

    private static final ReconProperties.AutoFix ALL_ON = new ReconProperties.AutoFix(true, true, true, true);
    private static final ReconProperties.AutoFix ALL_OFF = new ReconProperties.AutoFix(false, false, false, false);

    private static Outcome outcome(ResultClass cls, String reason, long signedDiff) {
        Finding f = new Finding(cls, reason, Math.abs(signedDiff), signedDiff);
        return new Outcome("PAYMENT", "P1", LocalDate.of(2026, 1, 1), "M001", cls, reason, "HIGH", List.of(f),
                null, null, null, Math.abs(signedDiff), "INR", "P1", null, null, List.of());
    }

    @ParameterizedTest(name = "{0}/{1} diff={2} -> {3}")
    @CsvSource(nullValues = "-", value = {
            "DUPLICATE,DUPLICATE_CALLBACK,0,AUTO_RESOLVED,DEDUPED_NO_MONEY_EFFECT,-,-",
            "MISSING_IN_BANK,AWAITING_CREDIT,0,PENDING_EXPECTED,-,-,-",
            "LATE_SETTLEMENT,SETTLED_WITHIN_GRACE,0,AUTO_RESOLVED,SETTLED_WITHIN_GRACE,-,-",
            "AMOUNT_MISMATCH,WITHIN_TOLERANCE,-50,AUTO_RESOLVED,ADJUSTMENT_POSTED,-50,POSTED",
            "AMOUNT_MISMATCH,WITHIN_TOLERANCE,50,AUTO_RESOLVED,ADJUSTMENT_POSTED,50,POSTED",
            "AMOUNT_MISMATCH,WITHIN_TOLERANCE,-51,OPEN,-,-51,PENDING_APPROVAL",
            "AMOUNT_MISMATCH,BEYOND_TOLERANCE,-5000,OPEN,-,-,-",
            "MISSING_IN_BANK,NOT_CREDITED,0,OPEN,-,-,-",
            "LATE_SETTLEMENT,SETTLED_AFTER_GRACE,0,OPEN,-,-,-",
            "DUPLICATE,DUPLICATE_BANK_CREDIT,0,OPEN,-,-,-",
            "MISSING_IN_LEDGER,NO_CALLBACK,0,OPEN,-,-,-",
            "MISSING_IN_GATEWAY,NOT_IN_SETTLEMENT_FILE,0,OPEN,-,-,-",
            "ORPHAN_BANK_CREDIT,UNKNOWN_UTR,0,OPEN,-,-,-"})
    void appliesConfiguredRules(ResultClass cls, String reason, long diff, String status, String resolution,
                                Long adjustment, String adjustmentStatus) {
        AutoFixRules.Decision d = AutoFixRules.decide(outcome(cls, reason, diff), ALL_ON, 50);
        assertThat(d.status()).isEqualTo(status);
        assertThat(d.resolutionReason()).isEqualTo(resolution);
        assertThat(d.adjustmentMinor()).isEqualTo(adjustment);
        assertThat(d.adjustmentStatus()).isEqualTo(adjustmentStatus);
    }

    @ParameterizedTest
    @CsvSource({"DUPLICATE,DUPLICATE_CALLBACK", "MISSING_IN_BANK,AWAITING_CREDIT",
            "LATE_SETTLEMENT,SETTLED_WITHIN_GRACE", "AMOUNT_MISMATCH,WITHIN_TOLERANCE"})
    void disabledRulesSendTheCaseToTheQueue(ResultClass cls, String reason) {
        AutoFixRules.Decision d = AutoFixRules.decide(outcome(cls, reason, -10), ALL_OFF, 50);
        assertThat(d.status()).isEqualTo("OPEN");
        assertThat(d.adjustmentMinor()).isNull();
    }
}

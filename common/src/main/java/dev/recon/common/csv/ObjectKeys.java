package dev.recon.common.csv;

import java.time.LocalDate;

/** Object-store layout shared by the simulators and the recon service. */
public final class ObjectKeys {
    public static final String GATEWAY_PREFIX = "gateway/settlements/";
    public static final String BANK_PREFIX = "bank/statements/";
    public static final String GROUND_TRUTH_PREFIX = "ground-truth/";

    private ObjectKeys() {
    }

    public static String gatewayFile(LocalDate d) {
        return GATEWAY_PREFIX + d + ".csv";
    }

    public static String bankFile(LocalDate d) {
        return BANK_PREFIX + d + ".csv";
    }

    public static String groundTruth(String simRunId) {
        return GROUND_TRUTH_PREFIX + simRunId + ".csv";
    }
}

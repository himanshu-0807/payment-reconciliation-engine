package dev.recon.common.sim;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

/**
 * Parameters of one deterministic simulation. The same config always produces byte-identical
 * callbacks, settlement files, bank statements and ground truth.
 *
 * @param seed             RNG seed
 * @param startDate        first business (payment) date
 * @param days             number of business dates
 * @param paymentsPerDay   payments generated per business date
 * @param merchants        number of synthetic merchants (M001..)
 * @param feeBps           gateway fee in basis points
 * @param faultRates       probability per payment of each fault (ORPHAN_BANK_CREDIT is a rate of
 *                         extra bank credits relative to paymentsPerDay)
 * @param trailingBankDays bank statement files written after the last business date, so late
 *                         credits land and the settlement grace window can expire
 */
public record SimulationConfig(
        long seed,
        LocalDate startDate,
        int days,
        int paymentsPerDay,
        int merchants,
        int feeBps,
        Map<FaultType, Double> faultRates,
        int trailingBankDays) {

    public static final int NORMAL_MAX_SETTLEMENT_DAYS = 2;
    public static final int DELAY_MIN_DAYS = 3;
    public static final int DELAY_MAX_DAYS = 5;
    public static final long FEE_MISMATCH_MAX_MINOR = 100;
    public static final long REAL_MISMATCH_MIN_MINOR = 1_000;

    public SimulationConfig {
        if (days < 1 || paymentsPerDay < 1 || merchants < 1) {
            throw new IllegalArgumentException("days, paymentsPerDay and merchants must be >= 1");
        }
        if (paymentsPerDay > 999_999) {
            throw new IllegalArgumentException("paymentsPerDay must be < 1,000,000");
        }
        faultRates = new EnumMap<>(faultRates.isEmpty() ? Map.of(FaultType.NONE, 0.0) : faultRates);
        faultRates.remove(FaultType.NONE);
        double sum = faultRates.entrySet().stream()
                .filter(e -> e.getKey() != FaultType.ORPHAN_BANK_CREDIT)
                .mapToDouble(Map.Entry::getValue).sum();
        for (double r : faultRates.values()) {
            if (r < 0 || r > 1) {
                throw new IllegalArgumentException("fault rates must be within [0,1]");
            }
        }
        if (sum > 1.0) {
            throw new IllegalArgumentException("per-payment fault rates sum to more than 1: " + sum);
        }
    }

    public double rate(FaultType f) {
        return faultRates.getOrDefault(f, 0.0);
    }

    public LocalDate lastBusinessDate() {
        return startDate.plusDays(days - 1L);
    }

    public LocalDate lastBankDate() {
        return lastBusinessDate().plusDays(trailingBankDays);
    }

    public static Map<FaultType, Double> defaultRates() {
        Map<FaultType, Double> m = new EnumMap<>(FaultType.class);
        m.put(FaultType.DUPLICATE_CALLBACK, 0.010);
        m.put(FaultType.MISSING_CALLBACK, 0.005);
        m.put(FaultType.TIMEOUT_THEN_SUCCESS, 0.005);
        m.put(FaultType.DELAYED_SETTLEMENT, 0.010);
        m.put(FaultType.AMOUNT_MISMATCH_FEE, 0.010);
        m.put(FaultType.AMOUNT_MISMATCH_REAL, 0.003);
        m.put(FaultType.MISSING_BANK_RECORD, 0.003);
        m.put(FaultType.ORPHAN_BANK_CREDIT, 0.002);
        m.put(FaultType.DUPLICATE_BANK_CREDIT, 0.003);
        m.put(FaultType.MISSING_GATEWAY_RECORD, 0.002);
        m.put(FaultType.BANK_UTR_MISSING, 0.003);
        return m;
    }

    public static SimulationConfig defaults(long seed, LocalDate start, int days, int perDay) {
        return new SimulationConfig(seed, start, days, perDay, 5, 200, defaultRates(), 6);
    }
}

package dev.recon.common.sim;

import java.time.LocalDate;
import java.util.Map;

/**
 * JSON body accepted by the simulator control endpoints; null fields fall back to defaults.
 * {@code bankCutoffDate}, when set, stops bank statements at that date (credits due later have
 * simply not arrived yet), which is how a live system looks on any given day.
 */
public record SimRunRequest(
        Long seed,
        LocalDate startDate,
        Integer days,
        Integer paymentsPerDay,
        Integer merchants,
        Map<FaultType, Double> faultRates,
        String simRunId,
        LocalDate bankCutoffDate) {

    public SimulationConfig toConfig(LocalDate defaultStart) {
        SimulationConfig d = SimulationConfig.defaults(seed == null ? 42L : seed,
                startDate == null ? defaultStart : startDate, days == null ? 3 : days,
                paymentsPerDay == null ? 1_000 : paymentsPerDay);
        return new SimulationConfig(d.seed(), d.startDate(), d.days(), d.paymentsPerDay(),
                merchants == null ? d.merchants() : merchants, d.feeBps(),
                faultRates == null ? d.faultRates() : faultRates, d.trailingBankDays());
    }
}

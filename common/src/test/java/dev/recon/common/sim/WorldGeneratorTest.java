package dev.recon.common.sim;

import dev.recon.common.csv.BankStatementRow;
import dev.recon.common.csv.GatewaySettlementRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class WorldGeneratorTest {

    private static final LocalDate DAY = LocalDate.of(2026, 1, 5);

    @Test
    void sameSeedProducesIdenticalWorld() {
        var a = new WorldGenerator(SimulationConfig.defaults(7, DAY, 1, 5_000)).generateDay(DAY);
        var b = new WorldGenerator(SimulationConfig.defaults(7, DAY, 1, 5_000)).generateDay(DAY);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void differentSeedProducesDifferentWorld() {
        var a = new WorldGenerator(SimulationConfig.defaults(7, DAY, 1, 1_000)).generateDay(DAY);
        var b = new WorldGenerator(SimulationConfig.defaults(8, DAY, 1, 1_000)).generateDay(DAY);
        assertThat(a.gatewayRows()).isNotEqualTo(b.gatewayRows());
    }

    @Test
    void faultRatesDoNotChangeAmountsOfPayments() {
        var clean = new WorldGenerator(new SimulationConfig(7, DAY, 1, 2_000, 5, 200, Map.of(), 6)).generateDay(DAY);
        var faulty = new WorldGenerator(SimulationConfig.defaults(7, DAY, 1, 2_000)).generateDay(DAY);
        Map<String, Long> cleanAmounts = clean.callbacks().stream()
                .collect(Collectors.toMap(c -> c.paymentId(), c -> c.amountMinor(), (x, y) -> x));
        faulty.callbacks().forEach(c -> assertThat(c.amountMinor()).isEqualTo(cleanAmounts.get(c.paymentId())));
        assertThat(clean.groundTruth()).isEmpty();
    }

    @Test
    void injectedFaultsAreReflectedInTheData() {
        Map<FaultType, Double> rates = new EnumMap<>(FaultType.class);
        rates.put(FaultType.MISSING_CALLBACK, 0.1);
        rates.put(FaultType.DUPLICATE_BANK_CREDIT, 0.1);
        rates.put(FaultType.MISSING_GATEWAY_RECORD, 0.1);
        rates.put(FaultType.ORPHAN_BANK_CREDIT, 0.01);
        var day = new WorldGenerator(new SimulationConfig(1, DAY, 1, 10_000, 5, 200, rates, 6)).generateDay(DAY);

        Map<FaultType, Long> counts = day.groundTruth().stream()
                .collect(Collectors.groupingBy(GroundTruthRecord::fault, Collectors.counting()));
        assertThat(counts.get(FaultType.ORPHAN_BANK_CREDIT)).isEqualTo(100);
        assertThat(counts.get(FaultType.MISSING_CALLBACK)).isBetween(850L, 1150L);

        long payments = 10_000;
        long noCallback = counts.get(FaultType.MISSING_CALLBACK);
        assertThat(day.callbacks().stream().map(c -> c.paymentId()).distinct().count())
                .isEqualTo(payments - noCallback);
        assertThat(day.gatewayRows()).hasSize((int) (payments - counts.get(FaultType.MISSING_GATEWAY_RECORD)));
        long distinctUtrs = day.bankRows().stream().map(BankStatementRow::utr).distinct().count();
        assertThat(day.bankRows().size() - distinctUtrs).isEqualTo(counts.get(FaultType.DUPLICATE_BANK_CREDIT));
        assertThat(day.gatewayRows()).allSatisfy((GatewaySettlementRow g) ->
                assertThat(g.netMinor()).isEqualTo(g.grossMinor() - g.feeMinor()));
    }
}

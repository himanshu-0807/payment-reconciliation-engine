package dev.recon.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.recon.common.csv.BankStatementRow;
import dev.recon.common.csv.GatewaySettlementRow;
import dev.recon.common.csv.ObjectKeys;
import dev.recon.common.event.GatewayCallback;
import dev.recon.common.sim.GroundTruthRecord;
import dev.recon.common.sim.SimulationConfig;
import dev.recon.common.sim.WorldGenerator;
import dev.recon.common.store.ObjectStore;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Test double for gateway-sim + bank-sim: publishes the generated callbacks to Kafka and writes the
 * same CSV files and ground truth the simulators write (BankSimulatorTest checks bank-sim against
 * the same generator).
 */
public final class SimFeeder {

    private SimFeeder() {
    }

    public static String feed(SimulationConfig c, String simRunId, KafkaTemplate<String, String> kafka,
                              ObjectMapper mapper, ObjectStore store) throws Exception {
        WorldGenerator gen = new WorldGenerator(c);
        Map<LocalDate, List<BankStatementRow>> bank = new TreeMap<>();
        StringBuilder truth = new StringBuilder(GroundTruthRecord.HEADER).append('\n');
        for (int d = 0; d < c.days(); d++) {
            LocalDate date = c.startDate().plusDays(d);
            WorldGenerator.DaySlice day = gen.generateDay(date);
            for (GatewayCallback cb : day.callbacks()) {
                kafka.send(GatewayCallback.TOPIC, cb.paymentId(), mapper.writeValueAsString(cb));
            }
            StringBuilder gw = new StringBuilder(GatewaySettlementRow.HEADER).append('\n');
            day.gatewayRows().forEach(r -> gw.append(r.toCsv()).append('\n'));
            store.put(ObjectKeys.gatewayFile(date), gw.toString().getBytes(StandardCharsets.UTF_8));
            day.bankRows().forEach(r -> bank.computeIfAbsent(r.valueDate(), k -> new ArrayList<>()).add(r));
            day.groundTruth().forEach(g -> truth.append(g.toCsv()).append('\n'));
        }
        for (LocalDate v = c.startDate().plusDays(1); !v.isAfter(c.lastBankDate()); v = v.plusDays(1)) {
            List<BankStatementRow> rows = new ArrayList<>(bank.getOrDefault(v, List.of()));
            rows.sort(Comparator.comparing(BankStatementRow::bankRef));
            StringBuilder b = new StringBuilder(BankStatementRow.HEADER).append('\n');
            rows.forEach(r -> b.append(r.toCsv()).append('\n'));
            store.put(ObjectKeys.bankFile(v), b.toString().getBytes(StandardCharsets.UTF_8));
        }
        store.put(ObjectKeys.groundTruth(simRunId), truth.toString().getBytes(StandardCharsets.UTF_8));
        kafka.flush();
        return simRunId;
    }
}

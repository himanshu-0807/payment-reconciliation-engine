package dev.recon.service.groundtruth;

import dev.recon.common.csv.ObjectKeys;
import dev.recon.common.sim.FaultType;
import dev.recon.common.sim.GroundTruthRecord;
import dev.recon.common.store.ObjectStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Compares the engine's stored outcomes with the simulator's ground truth. Each fault type maps to
 * exactly one expected (class, reason); an entity with no injected fault is expected to be
 * MATCHED/CLEAN. Precision and recall are computed per fault type and micro-averaged.
 */
@Service
public class GroundTruthEvaluator {

    public record FaultScore(long injected, long detected, long truePositives, double precision, double recall) {
    }

    public record Mismatch(String entityType, String entityId, String expected, String actual) {
    }

    /**
     * @param unmappedOutcomes outcomes that correspond to no fault type (e.g. MISSING_IN_BANK/AWAITING_CREDIT);
     *                         they count against overall precision
     */
    public record Report(String simRunId, long entitiesEvaluated, long injectedFaults, long cleanEntities,
                         long cleanCorrect, long unmappedOutcomes, double precision, double recall,
                         Map<String, FaultScore> byFault,
                         List<Mismatch> sampleMismatches) {
    }

    private final ObjectStore store;
    private final JdbcTemplate jdbc;

    public GroundTruthEvaluator(ObjectStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    public String latestSimRunId() throws IOException {
        String key = ObjectKeys.GROUND_TRUTH_PREFIX + "latest.txt";
        if (!store.exists(key)) {
            return null;
        }
        try (InputStream in = store.get(key)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    public Report evaluate(String simRunId) throws IOException {
        Map<String, FaultType> expected = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(store.get(ObjectKeys.groundTruth(simRunId)),
                StandardCharsets.UTF_8))) {
            r.readLine();
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
                    GroundTruthRecord g = GroundTruthRecord.parse(line);
                    expected.put(g.entityType() + "|" + g.entityId(), g.fault());
                }
            }
        }
        return score(simRunId, expected);
    }

    Report score(String simRunId, Map<String, FaultType> expected) {
        Map<FaultType, long[]> counts = new EnumMap<>(FaultType.class); // injected, detected, tp
        for (FaultType f : FaultType.values()) {
            counts.put(f, new long[3]);
        }
        expected.values().forEach(f -> counts.get(f)[0]++);
        Set<String> seen = new HashSet<>();
        List<Mismatch> mismatches = new ArrayList<>();
        long[] totals = new long[4]; // evaluated, clean, cleanCorrect, unmapped
        jdbc.query("SELECT entity_type, entity_id, result_class, reason FROM recon_result", rs -> {
            String key = rs.getString(1) + "|" + rs.getString(2);
            seen.add(key);
            totals[0]++;
            FaultType exp = expected.getOrDefault(key, FaultType.NONE);
            FaultType got = FaultType.fromOutcome(rs.getString(3), rs.getString(4));
            if (exp == FaultType.NONE) {
                totals[1]++;
            }
            if (got != null) {
                counts.get(got)[1]++;
            } else {
                totals[3]++;
            }
            if (got == exp) {
                counts.get(exp)[2]++;
                if (exp == FaultType.NONE) {
                    totals[2]++;
                }
            } else if (mismatches.size() < 50) {
                mismatches.add(new Mismatch(rs.getString(1), rs.getString(2), exp.name(),
                        rs.getString(3) + "/" + rs.getString(4)));
            }
        });
        for (Map.Entry<String, FaultType> e : expected.entrySet()) {
            if (!seen.contains(e.getKey()) && mismatches.size() < 50) {
                String[] k = e.getKey().split("\\|", 2);
                mismatches.add(new Mismatch(k[0], k[1], e.getValue().name(), "NOT_REPORTED"));
            }
        }

        Map<String, FaultScore> byFault = new TreeMap<>();
        long tp = 0, injected = 0, detected = 0;
        for (FaultType f : FaultType.values()) {
            if (f == FaultType.NONE) {
                continue;
            }
            long[] c = counts.get(f);
            byFault.put(f.name(), new FaultScore(c[0], c[1], c[2], ratio(c[2], c[1]), ratio(c[2], c[0])));
            tp += c[2];
            injected += c[0];
            detected += c[1];
        }
        return new Report(simRunId, totals[0], injected, totals[1], totals[2], totals[3],
                ratio(tp, detected + totals[3]), ratio(tp, injected),
                byFault, mismatches);
    }

    private static double ratio(long a, long b) {
        return b == 0 ? 1.0 : (double) a / b;
    }
}

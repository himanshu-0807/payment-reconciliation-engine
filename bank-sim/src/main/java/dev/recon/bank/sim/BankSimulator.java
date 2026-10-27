package dev.recon.bank.sim;

import dev.recon.common.csv.BankStatementRow;
import dev.recon.common.csv.ObjectKeys;
import dev.recon.common.sim.SimRunRequest;
import dev.recon.common.sim.SimulationConfig;
import dev.recon.common.sim.WorldGenerator;
import dev.recon.common.store.ObjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Regenerates the simulated world from the same seed as gateway-sim and writes one bank statement
 * per value date. Credits of a business date land on later value dates, so rows are buffered per
 * value date and a file is flushed once no later business date can add to it. Keeps a UTR index in
 * memory for the SOAP settlement-status lookup.
 */
@Service
public class BankSimulator {

    private static final Logger log = LoggerFactory.getLogger(BankSimulator.class);

    /** What the bank knows about one UTR. */
    public record UtrInfo(int credits, long amountMinor, String currency, LocalDate valueDate, String bankRef) {
    }

    public record Result(String simRunId, int files, long rowsWritten, long durationMs) {
    }

    private volatile Map<String, UtrInfo> utrIndex = new HashMap<>();
    private final ObjectStore store;

    public BankSimulator(ObjectStore store) {
        this.store = store;
    }

    public synchronized Result generate(SimRunRequest request) throws IOException {
        long t0 = System.nanoTime();
        SimulationConfig config = request.toConfig(LocalDate.now(WorldGenerator.ZONE).minusDays(20));
        WorldGenerator gen = new WorldGenerator(config);
        store.deletePrefix(ObjectKeys.BANK_PREFIX);
        TreeMap<LocalDate, List<BankStatementRow>> pending = new TreeMap<>();
        Map<String, UtrInfo> index = new HashMap<>();
        LocalDate firstFile = config.startDate().plusDays(1);
        LocalDate lastFile = request.bankCutoffDate() != null && request.bankCutoffDate().isBefore(config.lastBankDate())
                ? request.bankCutoffDate() : config.lastBankDate();
        int files = 0;
        long rows = 0;
        for (int d = 0; d < config.days(); d++) {
            LocalDate date = config.startDate().plusDays(d);
            for (BankStatementRow r : gen.generateDay(date).bankRows()) {
                pending.computeIfAbsent(r.valueDate(), k -> new ArrayList<>()).add(r);
            }
            // Every credit of a later business date has value date >= that date + 1.
            LocalDate complete = date.plusDays(1);
            while (!pending.isEmpty() && !pending.firstKey().isAfter(complete)) {
                var e = pending.pollFirstEntry();
                if (!e.getKey().isAfter(lastFile)) {
                    rows += writeStatement(e.getKey(), e.getValue(), index);
                    files++;
                }
            }
        }
        for (LocalDate v = firstFile; !v.isAfter(lastFile); v = v.plusDays(1)) {
            if (!store.exists(ObjectKeys.bankFile(v))) {
                List<BankStatementRow> list = pending.remove(v);
                rows += writeStatement(v, list == null ? new ArrayList<>() : list, index);
                files++;
            }
        }
        utrIndex = index;
        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("bank statements for {} written: files={} rows={} in {} ms", request.simRunId(), files, rows, ms);
        return new Result(request.simRunId(), files, rows, ms);
    }

    private long writeStatement(LocalDate valueDate, List<BankStatementRow> rows, Map<String, UtrInfo> index)
            throws IOException {
        rows.sort(Comparator.comparing(BankStatementRow::bankRef));
        Path tmp = Files.createTempFile("bank-", ".csv");
        try {
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write(BankStatementRow.HEADER);
                w.newLine();
                for (BankStatementRow r : rows) {
                    w.write(r.toCsv());
                    w.newLine();
                    indexRow(index, r);
                }
            }
            store.put(ObjectKeys.bankFile(valueDate), tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return rows.size();
    }

    private static void indexRow(Map<String, UtrInfo> index, BankStatementRow r) {
        if (r.utr() == null) {
            return;
        }
        index.merge(r.utr(), new UtrInfo(1, r.amountMinor(), r.currency(), r.valueDate(), r.bankRef()),
                (a, b) -> new UtrInfo(a.credits() + 1, a.amountMinor(), a.currency(), a.valueDate(), a.bankRef()));
    }

    public UtrInfo lookup(String utr) {
        return utrIndex.get(utr);
    }

    /** After a restart, rebuild the UTR index from statements already in the object store. */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildIndex() {
        try {
            Map<String, UtrInfo> index = new HashMap<>();
            for (ObjectStore.ObjectInfo o : store.list(ObjectKeys.BANK_PREFIX)) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(store.get(o.key()), StandardCharsets.UTF_8))) {
                    String line = r.readLine();
                    while ((line = r.readLine()) != null) {
                        if (!line.isBlank()) {
                            indexRow(index, BankStatementRow.parse(line));
                        }
                    }
                }
            }
            if (!index.isEmpty()) {
                utrIndex = index;
                log.info("rebuilt UTR index from object store: {} UTRs", index.size());
            }
        } catch (Exception e) {
            log.warn("could not rebuild UTR index: {}", e.getMessage());
        }
    }
}

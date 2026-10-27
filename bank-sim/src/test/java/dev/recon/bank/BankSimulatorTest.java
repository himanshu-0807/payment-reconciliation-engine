package dev.recon.bank;

import dev.recon.bank.sim.BankSimulator;
import dev.recon.common.csv.BankStatementRow;
import dev.recon.common.csv.ObjectKeys;
import dev.recon.common.sim.FaultType;
import dev.recon.common.sim.SimRunRequest;
import dev.recon.common.sim.SimulationConfig;
import dev.recon.common.sim.WorldGenerator;
import dev.recon.common.store.ObjectStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.xml.transform.StringSource;
import org.springframework.ws.test.server.MockWebServiceClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.ws.test.server.RequestCreators.withPayload;
import static org.springframework.ws.test.server.ResponseMatchers.noFault;
import static org.springframework.ws.test.server.ResponseMatchers.xpath;

@SpringBootTest(properties = {"storage.type=local", "storage.local.root=${java.io.tmpdir}/bank-sim-test-store"})
class BankSimulatorTest {

    private static final LocalDate START = LocalDate.of(2026, 3, 1);
    private static final Map<String, String> NS = Map.of("b", "http://recon.dev/bank/settlement");

    @Autowired BankSimulator bank;
    @Autowired ObjectStore store;
    @Autowired ApplicationContext ctx;

    @Test
    void writesOneStatementPerValueDateWithEveryGeneratedCreditAndAnswersSoapLookups() throws Exception {
        SimRunRequest req = new SimRunRequest(99L, START, 3, 2_000, 5, Map.of(
                FaultType.DUPLICATE_BANK_CREDIT, 0.05, FaultType.DELAYED_SETTLEMENT, 0.05), "test-run", null);
        BankSimulator.Result result = bank.generate(req);

        SimulationConfig config = req.toConfig(START);
        WorldGenerator gen = new WorldGenerator(config);
        List<BankStatementRow> expected = new ArrayList<>();
        for (int d = 0; d < 3; d++) {
            expected.addAll(gen.generateDay(START.plusDays(d)).bankRows());
        }
        // value dates run from start+1 to last business date + trailing days
        assertThat(result.files()).isEqualTo(3 + config.trailingBankDays() - 1);
        assertThat(result.rowsWritten()).isEqualTo(expected.size());

        List<BankStatementRow> written = new ArrayList<>();
        for (ObjectStore.ObjectInfo o : store.list(ObjectKeys.BANK_PREFIX)) {
            try (var r = new BufferedReader(new InputStreamReader(store.get(o.key()), StandardCharsets.UTF_8))) {
                r.lines().skip(1).map(BankStatementRow::parse).forEach(row -> {
                    assertThat(o.key()).isEqualTo(ObjectKeys.bankFile(row.valueDate()));
                    written.add(row);
                });
            }
        }
        assertThat(written).containsExactlyInAnyOrderElementsOf(expected);

        BankStatementRow single = expected.stream()
                .filter(r -> expected.stream().filter(x -> r.utr() != null && r.utr().equals(x.utr())).count() == 1)
                .findFirst().orElseThrow();
        BankStatementRow dup = expected.stream()
                .filter(r -> r.utr() != null && expected.stream().filter(x -> r.utr().equals(x.utr())).count() == 2)
                .findFirst().orElseThrow();

        MockWebServiceClient client = MockWebServiceClient.createClient(ctx);
        client.sendRequest(withPayload(request(single.utr()))).andExpect(noFault())
                .andExpect(xpath("//b:status", NS).evaluatesTo("CREDITED"))
                .andExpect(xpath("//b:amountMinor", NS).evaluatesTo(single.amountMinor()))
                .andExpect(xpath("//b:valueDate", NS).evaluatesTo(single.valueDate().toString()));
        client.sendRequest(withPayload(request(dup.utr()))).andExpect(noFault())
                .andExpect(xpath("//b:status", NS).evaluatesTo("DUPLICATE_CREDIT"))
                .andExpect(xpath("//b:creditCount", NS).evaluatesTo(2));
        client.sendRequest(withPayload(request("UTRDOESNOTEXIST"))).andExpect(noFault())
                .andExpect(xpath("//b:status", NS).evaluatesTo("NOT_FOUND"));
    }

    @Test
    void cutoffStopsStatementsAtTheGivenDate() throws Exception {
        SimRunRequest req = new SimRunRequest(5L, START, 3, 500, 5, Map.of(), "cutoff-run", START.plusDays(3));
        BankSimulator.Result result = bank.generate(req);
        assertThat(result.files()).isEqualTo(3); // value dates start+1 .. start+3
        assertThat(store.list(ObjectKeys.BANK_PREFIX)).extracting(ObjectStore.ObjectInfo::key)
                .containsExactly(ObjectKeys.bankFile(START.plusDays(1)), ObjectKeys.bankFile(START.plusDays(2)),
                        ObjectKeys.bankFile(START.plusDays(3)));
    }

    private static StringSource request(String utr) {
        return new StringSource("<getSettlementStatusRequest xmlns=\"http://recon.dev/bank/settlement\"><utr>"
                + utr + "</utr></getSettlementStatusRequest>");
    }
}

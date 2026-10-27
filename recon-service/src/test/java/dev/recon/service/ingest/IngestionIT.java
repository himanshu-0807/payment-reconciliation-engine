package dev.recon.service.ingest;

import dev.recon.common.csv.BankStatementRow;
import dev.recon.common.csv.ObjectKeys;
import dev.recon.common.store.ObjectStore;
import dev.recon.service.admin.DataReset;
import dev.recon.service.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionIT extends IntegrationTestBase {

    @Autowired IngestionService ingestion;
    @Autowired ObjectStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataReset reset;

    private static final LocalDate DAY = LocalDate.of(2026, 4, 2);

    @BeforeEach
    void clean() throws Exception {
        reset.resetAll("test");
        store.deletePrefix(ObjectKeys.GATEWAY_PREFIX);
        store.deletePrefix(ObjectKeys.BANK_PREFIX);
    }

    private void putBank(String... rows) throws Exception {
        StringBuilder sb = new StringBuilder(BankStatementRow.HEADER).append('\n');
        for (String r : rows) {
            sb.append(r).append('\n');
        }
        store.put(ObjectKeys.bankFile(DAY), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void reIngestingIsANoOpAndRewrittenFilesOnlyAddNewRows() throws Exception {
        String a = "BNKA,SIMACC0000M001,2026-04-02,9800,INR,UTR1,NEFT CR UTR1";
        String b = "BNKB,SIMACC0000M001,2026-04-02,9800,INR,UTR1,NEFT CR UTR1"; // same UTR, new bank_ref: real duplicate
        String bad = "BNKC,SIMACC0000M001,2026-04-02,-5,INR,UTR2,negative credit";
        putBank(a, b, bad);

        IngestionService.Report first = ingestion.ingestAll(2);
        assertThat(first.filesIngested()).isEqualTo(1);
        assertThat(first.rowsInserted()).isEqualTo(2);
        assertThat(first.rowsRejected()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM bank_txn WHERE utr = 'UTR1'")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT statement_date FROM ingested_file", LocalDate.class)).isEqualTo(DAY);

        IngestionService.Report again = ingestion.ingestAll(2);
        assertThat(again.filesIngested()).isZero();
        assertThat(count("SELECT count(*) FROM bank_txn")).isEqualTo(2);

        // The bank re-sends the statement with one extra line: only that line is new.
        putBank(a, b, "BNKD,SIMACC0000M002,2026-04-02,5000,INR,UTR3,NEFT CR UTR3");
        IngestionService.Report third = ingestion.ingestAll(2);
        assertThat(third.filesIngested()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM bank_txn")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM ingested_file")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT merchant_id FROM bank_txn WHERE bank_ref = 'BNKD'", String.class))
                .isEqualTo("M002");
    }

    @Test
    void rejectsFilesWithAnUnexpectedHeader() throws Exception {
        store.put(ObjectKeys.bankFile(DAY), "wrong,header\n1,2\n".getBytes(StandardCharsets.UTF_8));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ingestion.ingestAll(1))
                .hasMessageContaining("unexpected header");
        assertThat(count("SELECT count(*) FROM ingested_file")).isZero();
    }
}

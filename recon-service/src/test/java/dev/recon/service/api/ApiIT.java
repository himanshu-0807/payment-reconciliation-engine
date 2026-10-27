package dev.recon.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.recon.common.csv.ObjectKeys;
import dev.recon.common.sim.SimulationConfig;
import dev.recon.common.store.ObjectStore;
import dev.recon.service.admin.DataReset;
import dev.recon.service.engine.ReconEngine;
import dev.recon.service.support.IntegrationTestBase;
import dev.recon.service.support.SimFeeder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiIT extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired DataReset reset;
    @Autowired ReconEngine engine;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectStore store;
    @Autowired JdbcTemplate jdbc;

    String analyst, supervisor, admin;

    @BeforeAll
    void world() throws Exception {
        reset.resetAll("test");
        store.deletePrefix(ObjectKeys.GATEWAY_PREFIX);
        store.deletePrefix(ObjectKeys.BANK_PREFIX);
        SimulationConfig c = SimulationConfig.defaults(3, LocalDate.of(2026, 5, 1), 2, 3_000);
        SimFeeder.feed(c, "api-it", kafka, mapper, store);
        engine.run(new ReconEngine.RunRequest(null, null, null, 4, "test", false, Duration.ofSeconds(60)));
        analyst = login("analyst", "analyst-test");
        supervisor = login("supervisor", "supervisor-test");
        admin = login("admin", "admin-test");
    }

    private String login(String u, String p) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(body).get("token").asText();
    }

    private ResultActions getAs(String token, String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", token));
    }

    private ResultActions postAs(String token, String url, String json) throws Exception {
        return mvc.perform(post(url).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private JsonNode json(ResultActions r) throws Exception {
        return mapper.readTree(r.andReturn().getResponse().getContentAsString());
    }

    @Test
    void loginIssuesARoleBearingTokenAndRejectsBadPasswords() throws Exception {
        getAs(analyst, "/api/auth/me").andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("OPS_ANALYST"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"analyst\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @Test
    void everyErrorUsesTheSameProblemFormat() throws Exception {
        mvc.perform(get("/api/exceptions")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED")).andExpect(jsonPath("$.status").value(401));
        getAs("Bearer not-a-jwt", "/api/exceptions").andExpect(status().isUnauthorized());
        postAs(analyst, "/api/recon/runs", "{}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        postAs(analyst, "/api/exceptions/1/resolve", "{\"resolutionReason\":\"NOPE\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("resolutionReason"));
        getAs(analyst, "/api/exceptions/999999999").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        getAs(analyst, "/api/exceptions?sort=password,asc").andExpect(status().isBadRequest());
        getAs(analyst, "/api/exceptions?size=1000").andExpect(status().isBadRequest());
    }

    @Test
    void rolesAreEnforced() throws Exception {
        getAs(analyst, "/api/audit").andExpect(status().isForbidden());
        getAs(supervisor, "/api/audit?action=RUN_COMPLETED").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("RUN_COMPLETED"));
        postAs(supervisor, "/api/admin/reset", "{}").andExpect(status().isForbidden());
        postAs(supervisor, "/api/simulator/runs", "{}").andExpect(status().isForbidden());
        getAs(analyst, "/api/ground-truth/evaluation").andExpect(status().isForbidden());
        getAs(supervisor, "/api/ground-truth/evaluation?simRunId=api-it").andExpect(status().isOk())
                .andExpect(jsonPath("$.precision").value(1.0)).andExpect(jsonPath("$.recall").value(1.0));
    }

    @Test
    void listsFiltersAndPaginatesExceptions() throws Exception {
        JsonNode page = json(getAs(analyst, "/api/exceptions?status=OPEN,IN_REVIEW&size=5&page=0").andExpect(status().isOk()));
        long open = jdbc.queryForObject("SELECT count(*) FROM recon_exception WHERE status IN ('OPEN','IN_REVIEW')", Long.class);
        assertThat(page.get("totalElements").asLong()).isEqualTo(open);
        assertThat(page.get("content")).hasSize(5);
        // default sort: largest amount at risk first
        assertThat(page.get("content").get(0).get("amountAtRiskMinor").asLong())
                .isGreaterThanOrEqualTo(page.get("content").get(1).get("amountAtRiskMinor").asLong());

        JsonNode orphans = json(getAs(analyst, "/api/exceptions?exceptionClass=ORPHAN_BANK_CREDIT&merchantId=M002&size=200"));
        assertThat(orphans.get("content")).allSatisfy(e -> {
            assertThat(e.get("exceptionClass").asText()).isEqualTo("ORPHAN_BANK_CREDIT");
            assertThat(e.get("merchantId").asText()).isEqualTo("M002");
        });
        JsonNode big = json(getAs(analyst, "/api/exceptions?minAmountMinor=500000&from=2026-05-01&to=2026-05-01&size=200"));
        assertThat(big.get("content")).allSatisfy(e -> {
            assertThat(e.get("amountAtRiskMinor").asLong()).isGreaterThanOrEqualTo(500_000);
            assertThat(e.get("businessDate").asText()).isEqualTo("2026-05-01");
        });
    }

    @Test
    void detailShowsLedgerGatewayAndBankSideBySide() throws Exception {
        long id = jdbc.queryForObject("SELECT id FROM recon_exception WHERE exception_class = 'AMOUNT_MISMATCH' "
                + "AND reason = 'BEYOND_TOLERANCE' ORDER BY id LIMIT 1", Long.class);
        JsonNode d = json(getAs(analyst, "/api/exceptions/" + id).andExpect(status().isOk()));
        assertThat(d.get("ledger")).hasSize(1);
        assertThat(d.get("gateway")).hasSize(1);
        assertThat(d.get("bank")).hasSize(1);
        assertThat(d.get("ledger").get(0).get("amount_minor").asLong())
                .isNotEqualTo(d.get("gateway").get(0).get("gross_minor").asLong());
        assertThat(d.get("history").get(0).get("action").asText()).isEqualTo("CREATED");
    }

    @Test
    void analystWorksACaseFromAssignmentToResolution() throws Exception {
        long id = jdbc.queryForObject("SELECT id FROM recon_exception WHERE exception_class = 'MISSING_IN_LEDGER' "
                + "AND status = 'OPEN' ORDER BY id LIMIT 1", Long.class);
        postAs(analyst, "/api/exceptions/" + id + "/assign", "{\"assignee\":\"analyst\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_REVIEW"));
        postAs(analyst, "/api/exceptions/" + id + "/comments", "{\"body\":\"callback lost; gateway confirms capture\"}")
                .andExpect(status().isCreated());
        postAs(analyst, "/api/exceptions/" + id + "/resolve",
                "{\"resolutionReason\":\"LEDGER_CORRECTED\",\"note\":\"posted manually\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"));
        postAs(analyst, "/api/exceptions/" + id + "/resolve", "{\"resolutionReason\":\"OTHER\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_RESOLVED"));
        JsonNode d = json(getAs(analyst, "/api/exceptions/" + id));
        assertThat(d.get("comments")).hasSize(1);
        assertThat(d.get("history").findValuesAsText("action")).contains("CREATED", "ASSIGNED", "COMMENTED", "RESOLVED");

        // A re-run must not reopen or overwrite the human decision.
        engine.run(new ReconEngine.RunRequest(null, null, null, 4, "test", false, Duration.ofSeconds(10)));
        assertThat(jdbc.queryForObject("SELECT status FROM recon_exception WHERE id = ?", String.class, id)).isEqualTo("RESOLVED");
    }

    @Test
    void largeFeeAdjustmentsNeedSupervisorApproval() throws Exception {
        JsonNode pending = json(getAs(analyst, "/api/adjustments?status=PENDING_APPROVAL&size=1"));
        assertThat(pending.get("totalElements").asLong()).isPositive();
        long adjId = pending.get("content").get(0).get("id").asLong();
        long excId = pending.get("content").get(0).get("exceptionId").asLong();
        assertThat(Math.abs(pending.get("content").get(0).get("amountMinor").asLong())).isGreaterThan(50).isLessThanOrEqualTo(100);

        postAs(analyst, "/api/exceptions/" + excId + "/resolve", "{\"resolutionReason\":\"OTHER\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADJUSTMENT_PENDING_APPROVAL"));
        postAs(analyst, "/api/adjustments/" + adjId + "/approve", "").andExpect(status().isForbidden());
        postAs(supervisor, "/api/adjustments/" + adjId + "/approve", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED")).andExpect(jsonPath("$.approvedBy").value("supervisor"));
        postAs(supervisor, "/api/adjustments/" + adjId + "/approve", "").andExpect(status().isConflict());
        getAs(analyst, "/api/exceptions/" + excId).andExpect(jsonPath("$.exception.status").value("RESOLVED"));

        long autoPosted = jdbc.queryForObject("SELECT count(*) FROM adjustment_journal WHERE status = 'POSTED' "
                + "AND approved_by IS NULL AND abs(amount_minor) <= 50", Long.class);
        long badAuto = jdbc.queryForObject("SELECT count(*) FROM adjustment_journal WHERE approved_by IS NULL "
                + "AND status = 'POSTED' AND abs(amount_minor) > 50", Long.class);
        assertThat(autoPosted).isPositive();
        assertThat(badAuto).isZero();
    }

    @Test
    void dashboardSumsUnresolvedExceptions() throws Exception {
        JsonNode s = json(getAs(analyst, "/api/dashboard/summary?asOf=2026-05-10").andExpect(status().isOk()));
        long expected = jdbc.queryForObject("SELECT COALESCE(sum(amount_at_risk_minor),0) FROM recon_exception "
                + "WHERE status IN ('OPEN','IN_REVIEW')", Long.class);
        assertThat(s.get("moneyAtRiskMinor").asLong()).isEqualTo(expected);
        long byClass = 0, aging = 0;
        for (JsonNode b : s.get("byClass")) byClass += b.get("amountMinor").asLong();
        for (JsonNode b : s.get("aging")) aging += b.get("amountMinor").asLong();
        assertThat(byClass).isEqualTo(expected);
        assertThat(aging).isEqualTo(expected);
        assertThat(s.get("aging").findValuesAsText("key")).containsExactly("0-1d", "2-3d", "4-7d", "8d+");

        JsonNode trend = json(getAs(analyst, "/api/dashboard/trend?asOf=2026-05-10&days=14"));
        assertThat(trend).hasSize(14);
        long trendSum = 0;
        for (JsonNode p : trend) trendSum += p.get("moneyAtRiskMinor").asLong();
        assertThat(trendSum).isEqualTo(expected);
    }

    @Test
    void openApiDocumentIsPublished() throws Exception {
        JsonNode doc = json(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()));
        assertThat(doc.get("paths").has("/api/exceptions/{id}/resolve")).isTrue();
        assertThat(doc.get("components").get("securitySchemes").has("bearer")).isTrue();
    }
}

package dev.recon.service.api;

import dev.recon.common.sim.WorldGenerator;
import dev.recon.service.admin.DataReset;
import dev.recon.service.groundtruth.GroundTruthEvaluator;
import dev.recon.service.ops.SimulatorClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Simulator", description = "Failure simulator control (ADMIN) and ground-truth evaluation")
public class SimulatorController {

    private final SimulatorClient simulator;
    private final DataReset reset;
    private final GroundTruthEvaluator evaluator;

    public SimulatorController(SimulatorClient simulator, DataReset reset, GroundTruthEvaluator evaluator) {
        this.simulator = simulator;
        this.reset = reset;
        this.evaluator = evaluator;
    }

    @GetMapping("/simulator/defaults")
    public Map<String, Object> defaults() {
        return simulator.defaults();
    }

    @PostMapping("/simulator/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Start a simulation (seed, days, paymentsPerDay, merchants, faultRates, startDate, bankCutoffDate)",
            description = "By default clears previous data first (resetData=true), ends the business dates yesterday "
                    + "and cuts bank statements off at today, so the newest days are still inside the grace window.")
    public Map<String, Object> start(@RequestBody Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> req = new HashMap<>(body);
        boolean resetData = !Boolean.FALSE.equals(req.remove("resetData"));
        int days = req.get("days") instanceof Number n ? n.intValue() : 3;
        if (days < 1 || days > 60) {
            throw new IllegalArgumentException("days must be between 1 and 60");
        }
        if (req.get("paymentsPerDay") instanceof Number n && (n.intValue() < 1 || n.intValue() > 200_000)) {
            throw new IllegalArgumentException("paymentsPerDay must be between 1 and 200000");
        }
        LocalDate today = LocalDate.now(WorldGenerator.ZONE);
        req.putIfAbsent("startDate", today.minusDays(days).toString());
        req.putIfAbsent("bankCutoffDate", today.toString());
        if (resetData) {
            reset.resetAll(jwt.getSubject());
        }
        return simulator.start(req);
    }

    @GetMapping("/simulator/runs")
    public List<Map<String, Object>> runs() {
        return simulator.runs();
    }

    @GetMapping("/simulator/runs/{id}")
    public Map<String, Object> run(@PathVariable String id) {
        return simulator.run(id);
    }

    @GetMapping("/ground-truth/evaluation")
    @PreAuthorize("hasAnyRole('OPS_SUPERVISOR','ADMIN')")
    @Operation(summary = "Precision/recall of the stored results against the simulator's ground truth")
    public GroundTruthEvaluator.Report evaluate(@RequestParam(required = false) String simRunId) throws IOException {
        String id = simRunId != null ? simRunId : evaluator.latestSimRunId();
        if (id == null) {
            throw new NotFoundException("ground truth (no simulation has completed yet)");
        }
        return evaluator.evaluate(id);
    }

    @PostMapping("/admin/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Clear ledger, sources, results and queue (scope=all), or only results and queue "
            + "(scope=results). The audit log is always kept.")
    public void reset(@RequestParam(defaultValue = "all") String scope, @AuthenticationPrincipal Jwt jwt) {
        switch (scope) {
            case "all" -> reset.resetAll(jwt.getSubject());
            case "results" -> reset.resetResults(jwt.getSubject());
            default -> throw new IllegalArgumentException("scope must be 'all' or 'results'");
        }
    }
}

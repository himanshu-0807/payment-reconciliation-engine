package dev.recon.gateway.sim;

import dev.recon.common.sim.SimRunRequest;
import dev.recon.common.sim.SimulationConfig;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Simulator control API (internal; the recon-service proxies it behind ADMIN auth). */
@RestController
@RequestMapping("/sim")
public class SimulatorController {

    private final GatewaySimulator simulator;

    public SimulatorController(GatewaySimulator simulator) {
        this.simulator = simulator;
    }

    @PostMapping("/runs")
    public ResponseEntity<SimRunStatus> start(@RequestBody SimRunRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(simulator.start(request));
    }

    @GetMapping("/runs")
    public List<SimRunStatus> runs() {
        return simulator.all();
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<SimRunStatus> run(@PathVariable String id) {
        SimRunStatus s = simulator.status(id);
        return s == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(s);
    }

    @GetMapping("/defaults")
    public Map<String, Object> defaults() {
        return Map.of("seed", 42, "days", 3, "paymentsPerDay", 1000, "merchants", 5,
                "faultRates", SimulationConfig.defaultRates());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail busy(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}

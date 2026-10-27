package dev.recon.gateway.sim;

import dev.recon.common.sim.FaultType;
import dev.recon.common.sim.SimulationConfig;

import java.time.Instant;
import java.util.Map;

/** Progress and outcome of one simulator run, as returned by the control API. */
public record SimRunStatus(
        String simRunId,
        String state,
        SimulationConfig config,
        Instant startedAt,
        Instant finishedAt,
        int daysCompleted,
        long paymentsGenerated,
        long callbacksPublished,
        long gatewayRowsWritten,
        long bankRowsWritten,
        Map<FaultType, Long> injectedFaults,
        String groundTruthKey,
        String error) {

    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
}

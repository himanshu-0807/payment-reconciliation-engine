package dev.recon.service.api;

import dev.recon.service.domain.ReconRun;
import dev.recon.service.domain.Repositories.ReconRunRepository;
import dev.recon.service.engine.ReconEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/recon/runs")
@Tag(name = "Reconciliation runs")
public class ReconRunController {

    private final ReconEngine engine;
    private final ReconRunRepository runs;

    public ReconRunController(ReconEngine engine, ReconRunRepository runs) {
        this.engine = engine;
        this.runs = runs;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OPS_SUPERVISOR','ADMIN')")
    @Operation(summary = "Ingest new files and reconcile a date range (synchronous)",
            description = "fromDate/toDate default to the full data range; asOfDate defaults to the latest bank "
                    + "statement received. Waits up to waitForLedgerSeconds (default 60) for the callback consumer.")
    public ReconEngine.RunSummary run(@Valid @RequestBody(required = false) Dtos.RunReconRequest req,
                                      @AuthenticationPrincipal Jwt jwt) {
        Dtos.RunReconRequest r = req == null ? new Dtos.RunReconRequest(null, null, null, null, null) : req;
        int wait = r.waitForLedgerSeconds() == null ? 60 : r.waitForLedgerSeconds();
        return engine.run(new ReconEngine.RunRequest(r.fromDate(), r.toDate(), r.asOfDate(), r.parallelism(),
                jwt.getSubject(), false, Duration.ofSeconds(wait)));
    }

    @GetMapping
    @Operation(summary = "Run history, newest first")
    public Dtos.PageResponse<ReconRun> list(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        Page<ReconRun> p = runs.findAllByOrderByIdDesc(PageRequest.of(page, Math.min(Math.max(size, 1), 100)));
        return new Dtos.PageResponse<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    @GetMapping("/{id}")
    public ReconRun get(@PathVariable long id) {
        return runs.findById(id).orElseThrow(() -> new NotFoundException("run " + id));
    }
}

package dev.recon.service.api;

import dev.recon.common.sim.WorldGenerator;
import dev.recon.service.ops.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
@Tag(name = "Dashboard", description = "Money at risk")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/summary")
    @Operation(summary = "Money at risk now, by exception class, by age, and auto-fix counts")
    public DashboardService.Summary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return dashboard.summary(asOf == null ? LocalDate.now(WorldGenerator.ZONE) : asOf);
    }

    @GetMapping("/trend")
    @Operation(summary = "Money at risk per business date for the last N days (default 14)")
    public List<DashboardService.TrendPoint> trend(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(defaultValue = "14") int days) {
        return dashboard.trend(asOf == null ? LocalDate.now(WorldGenerator.ZONE) : asOf, days);
    }
}

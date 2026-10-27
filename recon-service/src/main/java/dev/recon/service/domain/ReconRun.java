package dev.recon.service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "recon_run")
public class ReconRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;
    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;
    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;
    @Column(nullable = false)
    private int parallelism;
    @Column(nullable = false)
    private String status;
    @Column(name = "triggered_by", nullable = false)
    private String triggeredBy;
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;
    @Column(name = "finished_at")
    private Instant finishedAt;
    @Column(name = "duration_ms")
    private Long durationMs;
    private Integer partitions;
    private Integer entities;
    @Column(name = "exceptions_open")
    private Integer exceptionsOpen;
    @Column(name = "auto_resolved")
    private Integer autoResolved;
    @Column(name = "result_digest")
    private String resultDigest;
    private String error;

    protected ReconRun() {
    }

    public ReconRun(LocalDate fromDate, LocalDate toDate, LocalDate asOfDate, int parallelism, String triggeredBy) {
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.asOfDate = asOfDate;
        this.parallelism = parallelism;
        this.triggeredBy = triggeredBy;
        this.status = "RUNNING";
        this.startedAt = Instant.now();
    }

    public void complete(int partitions, int entities, int open, int autoResolved, String digest) {
        this.status = "COMPLETED";
        this.partitions = partitions;
        this.entities = entities;
        this.exceptionsOpen = open;
        this.autoResolved = autoResolved;
        this.resultDigest = digest;
        finish();
    }

    public void fail(String error) {
        this.status = "FAILED";
        this.error = error == null ? null : error.substring(0, Math.min(error.length(), 2000));
        finish();
    }

    private void finish() {
        this.finishedAt = Instant.now();
        this.durationMs = finishedAt.toEpochMilli() - startedAt.toEpochMilli();
    }

    public Long getId() { return id; }
    public LocalDate getFromDate() { return fromDate; }
    public LocalDate getToDate() { return toDate; }
    public LocalDate getAsOfDate() { return asOfDate; }
    public int getParallelism() { return parallelism; }
    public String getStatus() { return status; }
    public String getTriggeredBy() { return triggeredBy; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Long getDurationMs() { return durationMs; }
    public Integer getPartitions() { return partitions; }
    public Integer getEntities() { return entities; }
    public Integer getExceptionsOpen() { return exceptionsOpen; }
    public Integer getAutoResolved() { return autoResolved; }
    public String getResultDigest() { return resultDigest; }
    public String getError() { return error; }
}

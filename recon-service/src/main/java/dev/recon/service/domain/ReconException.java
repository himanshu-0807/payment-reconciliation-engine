package dev.recon.service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;

/** An item in the ops queue. Created and updated by the engine; worked by ops users. */
@Entity
@Table(name = "recon_exception")
public class ReconException {

    public enum Status { OPEN, IN_REVIEW, RESOLVED, PENDING_EXPECTED, AUTO_RESOLVED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "entity_type", nullable = false)
    private String entityType;
    @Column(name = "entity_id", nullable = false)
    private String entityId;
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;
    @Column(name = "merchant_id", nullable = false)
    private String merchantId;
    @Column(name = "exception_class", nullable = false)
    private String exceptionClass;
    @Column(nullable = false)
    private String reason;
    @Column(name = "amount_at_risk_minor", nullable = false)
    private long amountAtRiskMinor;
    @Column(nullable = false, length = 3)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    private String currency;
    @Column(nullable = false)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Status status;
    private String assignee;
    @Column(name = "resolution_reason")
    private String resolutionReason;
    @Column(name = "resolution_note")
    private String resolutionNote;
    @Column(name = "resolved_by")
    private String resolvedBy;
    @Column(name = "resolved_at")
    private Instant resolvedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "first_run_id", nullable = false)
    private long firstRunId;
    @Column(name = "last_run_id", nullable = false)
    private long lastRunId;
    @Version
    private long version;

    protected ReconException() {
    }

    public void assign(String user) {
        this.assignee = user;
        if (status == Status.OPEN || status == Status.PENDING_EXPECTED) {
            status = Status.IN_REVIEW;
        }
        touch();
    }

    public void resolve(String user, String reason, String note) {
        this.status = Status.RESOLVED;
        this.resolutionReason = reason;
        this.resolutionNote = note;
        this.resolvedBy = user;
        this.resolvedAt = Instant.now();
        touch();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public boolean isUnresolved() {
        return status == Status.OPEN || status == Status.IN_REVIEW;
    }

    public Long getId() { return id; }
    public String getEntityType() { return entityType; }
    public String getEntityId() { return entityId; }
    public LocalDate getBusinessDate() { return businessDate; }
    public String getMerchantId() { return merchantId; }
    public String getExceptionClass() { return exceptionClass; }
    public String getReason() { return reason; }
    public long getAmountAtRiskMinor() { return amountAtRiskMinor; }
    public String getCurrency() { return currency; }
    public Status getStatus() { return status; }
    public String getAssignee() { return assignee; }
    public String getResolutionReason() { return resolutionReason; }
    public String getResolutionNote() { return resolutionNote; }
    public String getResolvedBy() { return resolvedBy; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getFirstRunId() { return firstRunId; }
    public long getLastRunId() { return lastRunId; }
    public long getVersion() { return version; }
}

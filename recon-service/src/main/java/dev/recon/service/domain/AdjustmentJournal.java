package dev.recon.service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Signed adjustment entry (paise) that books a fee/rounding difference. */
@Entity
@Table(name = "adjustment_journal")
public class AdjustmentJournal {

    public enum Status { POSTED, PENDING_APPROVAL, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "exception_id", nullable = false)
    private long exceptionId;
    @Column(name = "entity_id", nullable = false)
    private String entityId;
    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;
    @Column(nullable = false, length = 3)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    private String currency;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    @Column(name = "created_by", nullable = false)
    private String createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "approved_by")
    private String approvedBy;
    @Column(name = "approved_at")
    private Instant approvedAt;

    protected AdjustmentJournal() {
    }

    public void approve(String user) {
        if (status != Status.PENDING_APPROVAL) {
            throw new IllegalStateException("adjustment " + id + " is " + status + ", not PENDING_APPROVAL");
        }
        this.status = Status.POSTED;
        this.approvedBy = user;
        this.approvedAt = Instant.now();
    }

    public Long getId() { return id; }
    public long getExceptionId() { return exceptionId; }
    public String getEntityId() { return entityId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public Status getStatus() { return status; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public String getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
}

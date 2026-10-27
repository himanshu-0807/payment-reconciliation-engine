package dev.recon.service.api;

import dev.recon.service.domain.AdjustmentJournal;
import dev.recon.service.domain.AuditEntry;
import dev.recon.service.domain.ExceptionComment;
import dev.recon.service.domain.ReconException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Request and response bodies of the REST API. Amounts are integer paise ("...Minor"). */
public final class Dtos {

    private Dtos() {
    }

    public record LoginRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 128) String password) {
    }

    public record LoginResponse(String token, String tokenType, Instant expiresAt, String username, String role) {
    }

    public record Me(String username, List<String> roles) {
    }

    public record RunReconRequest(LocalDate fromDate, LocalDate toDate, LocalDate asOfDate,
                                  @Min(1) @Max(64) Integer parallelism,
                                  @Min(0) @Max(600) Integer waitForLedgerSeconds) {
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }

    public record ExceptionView(long id, String entityType, String entityId, LocalDate businessDate, String merchantId,
                                String exceptionClass, String reason, long amountAtRiskMinor, String currency,
                                String status, String assignee, String resolutionReason, Instant createdAt,
                                Instant updatedAt, long version) {
        public static ExceptionView of(ReconException e) {
            return new ExceptionView(e.getId(), e.getEntityType(), e.getEntityId(), e.getBusinessDate(),
                    e.getMerchantId(), e.getExceptionClass(), e.getReason(), e.getAmountAtRiskMinor(), e.getCurrency(),
                    e.getStatus().name(), e.getAssignee(), e.getResolutionReason(), e.getCreatedAt(), e.getUpdatedAt(),
                    e.getVersion());
        }
    }

    public record CommentView(long id, String author, String body, Instant createdAt) {
        public static CommentView of(ExceptionComment c) {
            return new CommentView(c.getId(), c.getAuthor(), c.getBody(), c.getCreatedAt());
        }
    }

    public record AdjustmentView(long id, long exceptionId, String entityId, long amountMinor, String currency,
                                 String status, String createdBy, Instant createdAt, String approvedBy,
                                 Instant approvedAt) {
        public static AdjustmentView of(AdjustmentJournal a) {
            return new AdjustmentView(a.getId(), a.getExceptionId(), a.getEntityId(), a.getAmountMinor(), a.getCurrency(),
                    a.getStatus().name(), a.getCreatedBy(), a.getCreatedAt(), a.getApprovedBy(), a.getApprovedAt());
        }
    }

    public record AuditView(long id, Instant occurredAt, String actor, String action, String entityType,
                            String entityId, String details) {
        public static AuditView of(AuditEntry a) {
            return new AuditView(a.getId(), a.getOccurredAt(), a.getActor(), a.getAction(), a.getEntityType(),
                    a.getEntityId(), a.getDetails());
        }
    }

    /** Ledger, gateway and bank records side by side, plus the engine's findings and the case history. */
    public record ExceptionDetail(ExceptionView exception, Map<String, Object> reconResult,
                                  List<Map<String, Object>> ledger, List<Map<String, Object>> gateway,
                                  List<Map<String, Object>> bank, AdjustmentView adjustment,
                                  List<CommentView> comments, List<AuditView> history) {
    }

    public record AssignRequest(@NotBlank @Size(max = 64) String assignee) {
    }

    public record CommentRequest(@NotBlank @Size(max = 2000) String body) {
    }

    public record ResolveRequest(
            @NotBlank @Pattern(regexp = "WRITTEN_OFF|RECOVERED_FROM_GATEWAY|BANK_CONFIRMED|LEDGER_CORRECTED|FALSE_POSITIVE|DUPLICATE_REFUNDED|OTHER",
                    message = "must be one of WRITTEN_OFF, RECOVERED_FROM_GATEWAY, BANK_CONFIRMED, LEDGER_CORRECTED, FALSE_POSITIVE, DUPLICATE_REFUNDED, OTHER")
            String resolutionReason,
            @Size(max = 1000) String note) {
    }

    public record BankStatus(String utr, String status, int creditCount, long amountMinor, String currency,
                             String valueDate, String bankRef, String source) {
    }
}

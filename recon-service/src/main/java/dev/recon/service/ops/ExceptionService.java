package dev.recon.service.ops;

import dev.recon.common.Money;
import dev.recon.service.api.ConflictException;
import dev.recon.service.api.Dtos;
import dev.recon.service.api.NotFoundException;
import dev.recon.service.audit.AuditLog;
import dev.recon.service.domain.AdjustmentJournal;
import dev.recon.service.domain.ExceptionComment;
import dev.recon.service.domain.ReconException;
import dev.recon.service.domain.Repositories.AdjustmentRepository;
import dev.recon.service.domain.Repositories.AuditRepository;
import dev.recon.service.domain.Repositories.CommentRepository;
import dev.recon.service.domain.Repositories.ReconExceptionRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Ops-queue queries and the actions analysts and supervisors take on exceptions. */
@Service
public class ExceptionService {

    public record Filter(String exceptionClass, String status, String merchantId, LocalDate from, LocalDate to,
                         String assignee, Long minAmountMinor, String entityId) {
    }

    private static final Set<String> SORTABLE = Set.of("id", "businessDate", "amountAtRiskMinor", "createdAt",
            "updatedAt", "status", "exceptionClass", "merchantId");

    private final ReconExceptionRepository exceptions;
    private final CommentRepository comments;
    private final AdjustmentRepository adjustments;
    private final AuditRepository auditRepo;
    private final AuditLog audit;
    private final JdbcTemplate jdbc;

    public ExceptionService(ReconExceptionRepository exceptions, CommentRepository comments,
                            AdjustmentRepository adjustments, AuditRepository auditRepo, AuditLog audit,
                            JdbcTemplate jdbc) {
        this.exceptions = exceptions;
        this.comments = comments;
        this.adjustments = adjustments;
        this.auditRepo = auditRepo;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Dtos.PageResponse<Dtos.ExceptionView> search(Filter f, int page, int size, String sort) {
        if (size < 1 || size > 200) {
            throw new IllegalArgumentException("size must be between 1 and 200");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        Page<ReconException> p = exceptions.findAll(spec(f), PageRequest.of(page, size, parseSort(sort)));
        return new Dtos.PageResponse<>(p.getContent().stream().map(Dtos.ExceptionView::of).toList(), p.getNumber(),
                p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    static Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Order.desc("amountAtRiskMinor"), Sort.Order.asc("id"));
        }
        String[] parts = sort.split(",");
        if (!SORTABLE.contains(parts[0])) {
            throw new IllegalArgumentException("cannot sort by '" + parts[0] + "'; allowed: " + SORTABLE);
        }
        Sort.Direction dir = parts.length > 1 && parts[1].equalsIgnoreCase("asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(new Sort.Order(dir, parts[0]), Sort.Order.asc("id"));
    }

    private static Specification<ReconException> spec(Filter f) {
        return (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (f.exceptionClass() != null && !f.exceptionClass().isBlank()) {
                ps.add(root.get("exceptionClass").in(Arrays.asList(f.exceptionClass().split(","))));
            }
            if (f.status() != null && !f.status().isBlank()) {
                List<ReconException.Status> statuses = new ArrayList<>();
                for (String s : f.status().split(",")) {
                    statuses.add(ReconException.Status.valueOf(s.trim()));
                }
                ps.add(root.get("status").in(statuses));
            }
            if (f.merchantId() != null && !f.merchantId().isBlank()) {
                ps.add(cb.equal(root.get("merchantId"), f.merchantId()));
            }
            if (f.from() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("businessDate"), f.from()));
            }
            if (f.to() != null) {
                ps.add(cb.lessThanOrEqualTo(root.get("businessDate"), f.to()));
            }
            if (f.assignee() != null && !f.assignee().isBlank()) {
                ps.add(cb.equal(root.get("assignee"), f.assignee()));
            }
            if (f.minAmountMinor() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("amountAtRiskMinor"), f.minAmountMinor()));
            }
            if (f.entityId() != null && !f.entityId().isBlank()) {
                ps.add(cb.like(root.get("entityId"), f.entityId().trim() + "%"));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
    }

    @Transactional(readOnly = true)
    public Dtos.ExceptionDetail detail(long id) {
        ReconException e = get(id);
        Map<String, Object> result = first(jdbc.queryForList(
                "SELECT * FROM recon_result WHERE entity_type = ? AND entity_id = ?", e.getEntityType(), e.getEntityId()));
        List<Map<String, Object>> ledger = List.of(), gateway = List.of(), bank = List.of();
        if ("PAYMENT".equals(e.getEntityType())) {
            String paymentId = result != null && result.get("payment_id") != null ? (String) result.get("payment_id") : e.getEntityId();
            String txnId = result == null ? null : (String) result.get("gateway_txn_id");
            String utr = result == null ? null : (String) result.get("utr");
            ledger = jdbc.queryForList("SELECT payment_id, gateway_txn_id, merchant_id, business_date, amount_minor, "
                    + "currency, status, first_event_at, last_event_at, duplicate_deliveries, out_of_order_events "
                    + "FROM ledger_entry WHERE payment_id = ?", paymentId);
            gateway = jdbc.queryForList("SELECT gateway_txn_id, merchant_order_id, merchant_id, txn_date, settlement_date, "
                    + "gross_minor, fee_minor, net_minor, currency, settlement_ref, source_file FROM gateway_txn "
                    + "WHERE merchant_order_id = ? OR gateway_txn_id = ?", paymentId, txnId == null ? paymentId : txnId);
            List<String> refs = refs(result);
            bank = jdbc.queryForList("SELECT bank_ref, account_no, value_date, amount_minor, currency, utr, narration, "
                    + "source_file FROM bank_txn WHERE (utr IS NOT NULL AND utr = ?) OR bank_ref = ANY (?) ORDER BY value_date, bank_ref",
                    utr, refs.toArray(String[]::new));
        } else {
            bank = jdbc.queryForList("SELECT bank_ref, account_no, value_date, amount_minor, currency, utr, narration, "
                    + "source_file FROM bank_txn WHERE bank_ref = ?", e.getEntityId());
        }
        Dtos.AdjustmentView adj = adjustments.findByExceptionId(id).map(Dtos.AdjustmentView::of).orElse(null);
        return new Dtos.ExceptionDetail(Dtos.ExceptionView.of(e), result, ledger, gateway, bank, adj,
                comments.findByExceptionIdOrderByCreatedAtAsc(id).stream().map(Dtos.CommentView::of).toList(),
                auditRepo.findByEntityTypeAndEntityIdOrderByIdAsc("EXCEPTION", Long.toString(id)).stream()
                        .map(Dtos.AuditView::of).toList());
    }

    private static List<String> refs(Map<String, Object> result) {
        if (result == null || result.get("bank_refs") == null || ((String) result.get("bank_refs")).isBlank()) {
            return List.of();
        }
        return Arrays.asList(((String) result.get("bank_refs")).split(" "));
    }

    @Transactional
    public Dtos.ExceptionView assign(long id, String assignee, String actor) {
        ReconException e = get(id);
        requireOpen(e);
        String before = e.getStatus() + "/" + e.getAssignee();
        e.assign(assignee);
        exceptions.saveAndFlush(e);
        audit.record(actor, "ASSIGNED", "EXCEPTION", Long.toString(id), before + " -> " + e.getStatus() + "/" + assignee);
        return Dtos.ExceptionView.of(e);
    }

    @Transactional
    public Dtos.CommentView comment(long id, String body, String actor) {
        get(id);
        ExceptionComment c = comments.save(new ExceptionComment(id, actor, body));
        audit.record(actor, "COMMENTED", "EXCEPTION", Long.toString(id), body.length() > 200 ? body.substring(0, 200) : body);
        return Dtos.CommentView.of(c);
    }

    @Transactional
    public Dtos.ExceptionView resolve(long id, String reason, String note, String actor) {
        ReconException e = get(id);
        requireOpen(e);
        adjustments.findByExceptionId(id).filter(a -> a.getStatus() == AdjustmentJournal.Status.PENDING_APPROVAL)
                .ifPresent(a -> {
                    throw new ConflictException("ADJUSTMENT_PENDING_APPROVAL",
                            "adjustment " + a.getId() + " must be approved by an OPS_SUPERVISOR first");
                });
        String before = e.getStatus().name();
        e.resolve(actor, reason, note);
        exceptions.saveAndFlush(e);
        audit.record(actor, "RESOLVED", "EXCEPTION", Long.toString(id), before + " -> RESOLVED (" + reason + ")"
                + (note == null || note.isBlank() ? "" : ": " + note));
        return Dtos.ExceptionView.of(e);
    }

    /** Approves a pending adjustment journal entry and closes its exception. */
    @Transactional
    public Dtos.AdjustmentView approve(long adjustmentId, String actor) {
        AdjustmentJournal a = adjustments.findById(adjustmentId)
                .orElseThrow(() -> new NotFoundException("adjustment " + adjustmentId));
        try {
            a.approve(actor);
        } catch (IllegalStateException ex) {
            throw new ConflictException("ADJUSTMENT_NOT_PENDING", ex.getMessage());
        }
        adjustments.saveAndFlush(a);
        ReconException e = get(a.getExceptionId());
        if (e.getStatus() != ReconException.Status.RESOLVED) {
            e.resolve(actor, "ADJUSTMENT_APPROVED", "adjustment " + Money.format(a.getAmountMinor()) + " " + a.getCurrency()
                    + " approved");
            exceptions.saveAndFlush(e);
        }
        audit.record(actor, "ADJUSTMENT_APPROVED", "EXCEPTION", Long.toString(e.getId()),
                "adjustment " + a.getId() + " " + Money.format(a.getAmountMinor()) + " " + a.getCurrency() + " posted");
        return Dtos.AdjustmentView.of(a);
    }

    @Transactional(readOnly = true)
    public Dtos.PageResponse<Dtos.AdjustmentView> adjustments(String status, int page, int size) {
        AdjustmentJournal.Status s = AdjustmentJournal.Status.valueOf(status);
        Page<AdjustmentJournal> p = adjustments.findByStatus(s, PageRequest.of(page, Math.min(size, 200),
                Sort.by(Sort.Order.asc("id"))));
        return new Dtos.PageResponse<>(p.getContent().stream().map(Dtos.AdjustmentView::of).toList(), p.getNumber(),
                p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    public String utrFor(long id) {
        ReconException e = get(id);
        Map<String, Object> r = first(jdbc.queryForList("SELECT utr FROM recon_result WHERE entity_type = ? AND entity_id = ?",
                e.getEntityType(), e.getEntityId()));
        return r == null ? null : (String) r.get("utr");
    }

    private ReconException get(long id) {
        return exceptions.findById(id).orElseThrow(() -> new NotFoundException("exception " + id));
    }

    private static void requireOpen(ReconException e) {
        if (e.getStatus() == ReconException.Status.RESOLVED || e.getStatus() == ReconException.Status.AUTO_RESOLVED) {
            throw new ConflictException("ALREADY_RESOLVED", "exception " + e.getId() + " is already " + e.getStatus());
        }
    }

    private static Map<String, Object> first(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? null : rows.getFirst();
    }
}

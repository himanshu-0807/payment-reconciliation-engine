package dev.recon.service.api;

import dev.recon.service.domain.AuditEntry;
import dev.recon.service.domain.Repositories.AuditRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/audit")
@Tag(name = "Audit log", description = "Append-only record of every engine and user action")
public class AuditController {

    private final AuditRepository audit;

    public AuditController(AuditRepository audit) {
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OPS_SUPERVISOR','ADMIN')")
    @Operation(summary = "Search the audit log, newest first")
    public Dtos.PageResponse<Dtos.AuditView> search(@RequestParam(required = false) String entityType,
                                                    @RequestParam(required = false) String entityId,
                                                    @RequestParam(required = false) String action,
                                                    @RequestParam(required = false) String actor,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "50") int size) {
        Specification<AuditEntry> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (entityType != null) ps.add(cb.equal(root.get("entityType"), entityType));
            if (entityId != null) ps.add(cb.equal(root.get("entityId"), entityId));
            if (action != null) ps.add(cb.equal(root.get("action"), action));
            if (actor != null) ps.add(cb.equal(root.get("actor"), actor));
            return cb.and(ps.toArray(Predicate[]::new));
        };
        Page<AuditEntry> p = audit.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200),
                Sort.by(Sort.Order.desc("id"))));
        return new Dtos.PageResponse<>(p.getContent().stream().map(Dtos.AuditView::of).toList(), p.getNumber(),
                p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}

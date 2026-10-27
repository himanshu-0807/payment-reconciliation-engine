package dev.recon.service.api;

import dev.recon.service.ops.ExceptionService;
import dev.recon.service.soap.BankSettlementClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api")
@Tag(name = "Ops queue", description = "Exceptions, actions and adjustment approval")
public class ExceptionController {

    private final ExceptionService service;
    private final BankSettlementClient bank;

    public ExceptionController(ExceptionService service, BankSettlementClient bank) {
        this.service = service;
        this.bank = bank;
    }

    @GetMapping("/exceptions")
    @Operation(summary = "List and filter exceptions",
            description = "Comma-separated values allowed for exceptionClass and status. sort=field,asc|desc")
    public Dtos.PageResponse<Dtos.ExceptionView> list(
            @RequestParam(required = false) String exceptionClass,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) Long minAmountMinor,
            @RequestParam(required = false) String entityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort) {
        return service.search(new ExceptionService.Filter(exceptionClass, status, merchantId, from, to, assignee,
                minAmountMinor, entityId), page, size, sort);
    }

    @GetMapping("/exceptions/{id}")
    @Operation(summary = "Exception detail: ledger, gateway and bank records side by side, findings, comments, history")
    public Dtos.ExceptionDetail detail(@PathVariable long id) {
        return service.detail(id);
    }

    @GetMapping("/exceptions/{id}/bank-status")
    @Operation(summary = "Ask the bank (SOAP getSettlementStatus) about this exception's UTR")
    public Dtos.BankStatus bankStatus(@PathVariable long id) {
        String utr = service.utrFor(id);
        if (utr == null) {
            throw new ConflictException("NO_UTR", "exception " + id + " has no settlement reference to look up");
        }
        return bank.status(utr);
    }

    @PostMapping("/exceptions/{id}/assign")
    @PreAuthorize("hasAnyRole('OPS_ANALYST','OPS_SUPERVISOR','ADMIN')")
    public Dtos.ExceptionView assign(@PathVariable long id, @Valid @RequestBody Dtos.AssignRequest req,
                                     @AuthenticationPrincipal Jwt jwt) {
        return service.assign(id, req.assignee(), jwt.getSubject());
    }

    @PostMapping("/exceptions/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OPS_ANALYST','OPS_SUPERVISOR','ADMIN')")
    public Dtos.CommentView comment(@PathVariable long id, @Valid @RequestBody Dtos.CommentRequest req,
                                    @AuthenticationPrincipal Jwt jwt) {
        return service.comment(id, req.body(), jwt.getSubject());
    }

    @PostMapping("/exceptions/{id}/resolve")
    @PreAuthorize("hasAnyRole('OPS_ANALYST','OPS_SUPERVISOR','ADMIN')")
    public Dtos.ExceptionView resolve(@PathVariable long id, @Valid @RequestBody Dtos.ResolveRequest req,
                                      @AuthenticationPrincipal Jwt jwt) {
        return service.resolve(id, req.resolutionReason(), req.note(), jwt.getSubject());
    }

    @GetMapping("/adjustments")
    @Operation(summary = "Adjustment journal entries by status (default PENDING_APPROVAL)")
    public Dtos.PageResponse<Dtos.AdjustmentView> adjustments(@RequestParam(defaultValue = "PENDING_APPROVAL") String status,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "25") int size) {
        return service.adjustments(status, page, size);
    }

    @PostMapping("/adjustments/{id}/approve")
    @PreAuthorize("hasAnyRole('OPS_SUPERVISOR','ADMIN')")
    @Operation(summary = "Approve an adjustment above the auto-post threshold (OPS_SUPERVISOR)")
    public Dtos.AdjustmentView approve(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return service.approve(id, jwt.getSubject());
    }
}

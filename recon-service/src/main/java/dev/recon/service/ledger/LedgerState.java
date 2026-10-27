package dev.recon.service.ledger;

import dev.recon.common.event.GatewayCallback;

import java.time.Instant;
import java.time.LocalDate;

/**
 * State of one ledger entry and the pure rules for applying a gateway callback to it. Status only
 * moves forward (PENDING -> SUCCESS); an event older than the newest one already applied is counted
 * as out-of-order and never regresses the status.
 */
public record LedgerState(
        String paymentId,
        String gatewayTxnId,
        String merchantId,
        LocalDate businessDate,
        long amountMinor,
        String currency,
        String status,
        Instant firstEventAt,
        Instant lastEventAt,
        int duplicateDeliveries,
        int outOfOrderEvents) {

    static int rank(String status) {
        return switch (status) {
            case GatewayCallback.STATUS_SUCCESS, "FAILED" -> 2;
            case GatewayCallback.STATUS_PENDING -> 1;
            default -> 0;
        };
    }

    public static LedgerState apply(LedgerState s, GatewayCallback e) {
        if (s == null) {
            return new LedgerState(e.paymentId(), e.gatewayTxnId(), e.merchantId(), e.businessDate(),
                    e.amountMinor(), e.currency(), e.status(), e.eventTime(), e.eventTime(), 0, 0);
        }
        boolean stale = e.eventTime().isBefore(s.lastEventAt());
        boolean advances = rank(e.status()) > rank(s.status());
        String status = advances ? e.status() : s.status();
        long amount = advances ? e.amountMinor() : s.amountMinor();
        String txn = advances && e.gatewayTxnId() != null ? e.gatewayTxnId() : s.gatewayTxnId();
        Instant first = e.eventTime().isBefore(s.firstEventAt()) ? e.eventTime() : s.firstEventAt();
        Instant last = stale ? s.lastEventAt() : e.eventTime();
        return new LedgerState(s.paymentId(), txn, s.merchantId(), s.businessDate(), amount, s.currency(), status,
                first, last, s.duplicateDeliveries(), s.outOfOrderEvents() + (stale ? 1 : 0));
    }

    public LedgerState withDuplicates(int extra) {
        return new LedgerState(paymentId, gatewayTxnId, merchantId, businessDate, amountMinor, currency, status,
                firstEventAt, lastEventAt, duplicateDeliveries + extra, outOfOrderEvents);
    }
}

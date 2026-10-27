package dev.recon.service.engine;

import java.time.LocalDate;

/** Row snapshots of the three sources as the engine sees them. Amounts are paise. */
public final class Records {

    private Records() {
    }

    public record LedgerRec(String paymentId, String gatewayTxnId, String merchantId, LocalDate businessDate,
                            long amountMinor, String currency, String status, int duplicateDeliveries,
                            int outOfOrderEvents) {
        public boolean succeeded() {
            return "SUCCESS".equals(status);
        }
    }

    public record GatewayRec(String gatewayTxnId, String merchantOrderId, String merchantId, LocalDate txnDate,
                             LocalDate settlementDate, long grossMinor, long feeMinor, long netMinor, String currency,
                             String settlementRef) {
    }

    public record BankRec(String bankRef, String accountNo, String merchantId, LocalDate valueDate, long amountMinor,
                          String currency, String utr) {
    }
}

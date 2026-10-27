package dev.recon.common.csv;

import java.time.LocalDate;

/** One row of the gateway's daily settlement CSV. Amounts are paise. */
public record GatewaySettlementRow(
        String gatewayTxnId,
        String merchantOrderId,
        String merchantId,
        LocalDate txnDate,
        LocalDate settlementDate,
        long grossMinor,
        long feeMinor,
        long netMinor,
        String currency,
        String settlementRef) {

    public static final String HEADER =
            "gateway_txn_id,merchant_order_id,merchant_id,txn_date,settlement_date,gross_amount_minor,fee_minor,net_amount_minor,currency,settlement_ref";

    public String toCsv() {
        return String.join(",", gatewayTxnId, merchantOrderId, merchantId, txnDate.toString(),
                settlementDate.toString(), Long.toString(grossMinor), Long.toString(feeMinor),
                Long.toString(netMinor), currency, settlementRef == null ? "" : settlementRef);
    }

    public static GatewaySettlementRow parse(String line) {
        String[] p = line.split(",", -1);
        if (p.length != 10) {
            throw new IllegalArgumentException("expected 10 columns, got " + p.length);
        }
        return new GatewaySettlementRow(p[0], blankToNull(p[1]), p[2], LocalDate.parse(p[3]), LocalDate.parse(p[4]),
                Long.parseLong(p[5]), Long.parseLong(p[6]), Long.parseLong(p[7]), p[8], blankToNull(p[9]));
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}

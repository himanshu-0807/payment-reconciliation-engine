package dev.recon.common.csv;

import java.time.LocalDate;

/** One credit line of the bank's daily statement CSV. utr may be empty (narration truncated). */
public record BankStatementRow(
        String bankRef,
        String accountNo,
        LocalDate valueDate,
        long amountMinor,
        String currency,
        String utr,
        String narration) {

    public static final String HEADER = "bank_ref,account_no,value_date,amount_minor,currency,utr,narration";

    public String toCsv() {
        return String.join(",", bankRef, accountNo, valueDate.toString(), Long.toString(amountMinor), currency,
                utr == null ? "" : utr, narration);
    }

    public static BankStatementRow parse(String line) {
        String[] p = line.split(",", -1);
        if (p.length != 7) {
            throw new IllegalArgumentException("expected 7 columns, got " + p.length);
        }
        return new BankStatementRow(p[0], p[1], LocalDate.parse(p[2]), Long.parseLong(p[3]), p[4],
                GatewaySettlementRow.blankToNull(p[5]), p[6]);
    }

    /** Synthetic account number for a merchant id, e.g. M001 -> SIMACC0000M001. */
    public static String accountFor(String merchantId) {
        return "SIMACC0000" + merchantId;
    }

    public static String merchantForAccount(String accountNo) {
        return accountNo.startsWith("SIMACC0000") ? accountNo.substring(10) : null;
    }
}

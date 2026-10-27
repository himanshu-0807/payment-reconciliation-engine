package dev.recon.service.engine;

/** Reconciliation outcome classes (the exception classes plus MATCHED). */
public enum ResultClass {
    MATCHED,
    MISSING_IN_LEDGER,
    MISSING_IN_GATEWAY,
    MISSING_IN_BANK,
    DUPLICATE,
    AMOUNT_MISMATCH,
    LATE_SETTLEMENT,
    ORPHAN_BANK_CREDIT
}

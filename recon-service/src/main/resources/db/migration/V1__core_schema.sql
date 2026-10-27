-- All money columns are BIGINT minor units (paise) with a CHAR(3) currency. No floating point.

-- ---------------------------------------------------------------- ledger (from gateway callbacks)
CREATE TABLE ledger_entry (
    payment_id           VARCHAR(40) PRIMARY KEY,
    gateway_txn_id       VARCHAR(40),
    merchant_id          VARCHAR(16)  NOT NULL,
    business_date        DATE         NOT NULL,
    amount_minor         BIGINT       NOT NULL CHECK (amount_minor >= 0),
    currency             CHAR(3)      NOT NULL,
    status               VARCHAR(16)  NOT NULL,
    first_event_at       TIMESTAMPTZ  NOT NULL,
    last_event_at        TIMESTAMPTZ  NOT NULL,
    duplicate_deliveries INT          NOT NULL DEFAULT 0,
    out_of_order_events  INT          NOT NULL DEFAULT 0,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_ledger_date_merchant ON ledger_entry (business_date, merchant_id);

-- Dedupe table: one row per gateway event id, remembering the Kafka position of the delivery that
-- was applied. A redelivery of that same position is ignored; any other position is a duplicate.
CREATE TABLE processed_event (
    event_id        UUID PRIMARY KEY,
    payment_id      VARCHAR(40) NOT NULL,
    event_status    VARCHAR(16) NOT NULL,
    kafka_partition INT         NOT NULL,
    kafka_offset    BIGINT      NOT NULL,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Extra deliveries of an already-processed event, keyed by Kafka position so that a redelivered
-- duplicate is counted once.
CREATE TABLE duplicate_delivery (
    kafka_partition INT         NOT NULL,
    kafka_offset    BIGINT      NOT NULL,
    event_id        UUID        NOT NULL,
    payment_id      VARCHAR(40) NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (kafka_partition, kafka_offset)
);
CREATE INDEX ix_dup_payment ON duplicate_delivery (payment_id);

-- ---------------------------------------------------------------- ingested external sources
CREATE TABLE gateway_txn (
    gateway_txn_id    VARCHAR(40) PRIMARY KEY,
    merchant_order_id VARCHAR(40),
    merchant_id       VARCHAR(16)  NOT NULL,
    txn_date          DATE         NOT NULL,
    settlement_date   DATE         NOT NULL,
    gross_minor       BIGINT       NOT NULL,
    fee_minor         BIGINT       NOT NULL,
    net_minor         BIGINT       NOT NULL,
    currency          CHAR(3)      NOT NULL,
    settlement_ref    VARCHAR(40),
    source_file       VARCHAR(200) NOT NULL
);
CREATE INDEX ix_gw_date_merchant ON gateway_txn (txn_date, merchant_id);
CREATE INDEX ix_gw_order ON gateway_txn (merchant_order_id);
CREATE INDEX ix_gw_ref ON gateway_txn (settlement_ref);

CREATE TABLE bank_txn (
    bank_ref     VARCHAR(40) PRIMARY KEY,
    account_no   VARCHAR(32)  NOT NULL,
    merchant_id  VARCHAR(16),
    value_date   DATE         NOT NULL,
    amount_minor BIGINT       NOT NULL,
    currency     CHAR(3)      NOT NULL,
    utr          VARCHAR(40),
    narration    VARCHAR(200),
    source_file  VARCHAR(200) NOT NULL
);
CREATE INDEX ix_bank_utr ON bank_txn (utr);
CREATE INDEX ix_bank_merchant_date ON bank_txn (merchant_id, value_date);

CREATE TABLE ingested_file (
    id             BIGSERIAL PRIMARY KEY,
    source         VARCHAR(16)  NOT NULL,
    object_key     VARCHAR(300) NOT NULL,
    checksum       VARCHAR(64)  NOT NULL,
    row_count      INT          NOT NULL,
    inserted_count INT          NOT NULL,
    ingested_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (source, object_key, checksum)
);

-- ---------------------------------------------------------------- reconciliation
CREATE TABLE recon_run (
    id                BIGSERIAL PRIMARY KEY,
    from_date         DATE        NOT NULL,
    to_date           DATE        NOT NULL,
    as_of_date        DATE        NOT NULL,
    parallelism       INT         NOT NULL,
    status            VARCHAR(16) NOT NULL,
    triggered_by      VARCHAR(64) NOT NULL,
    started_at        TIMESTAMPTZ NOT NULL,
    finished_at       TIMESTAMPTZ,
    duration_ms       BIGINT,
    partitions        INT,
    entities          INT,
    exceptions_open   INT,
    auto_resolved     INT,
    result_digest     VARCHAR(64),
    error             TEXT
);

-- Latest outcome per entity (a payment, or a bank credit that matched nothing).
CREATE TABLE recon_result (
    entity_type          VARCHAR(16) NOT NULL,
    entity_id            VARCHAR(40) NOT NULL,
    business_date        DATE        NOT NULL,
    merchant_id          VARCHAR(16) NOT NULL,
    result_class         VARCHAR(24) NOT NULL,
    reason               VARCHAR(32) NOT NULL,
    confidence           VARCHAR(8)  NOT NULL,
    findings             VARCHAR(300) NOT NULL,
    ledger_amount_minor  BIGINT,
    gateway_amount_minor BIGINT,
    bank_amount_minor    BIGINT,
    amount_at_risk_minor BIGINT      NOT NULL,
    currency             CHAR(3)     NOT NULL,
    payment_id           VARCHAR(40),
    gateway_txn_id       VARCHAR(40),
    utr                  VARCHAR(40),
    bank_refs            VARCHAR(200),
    run_id               BIGINT      NOT NULL,
    PRIMARY KEY (entity_type, entity_id)
);
CREATE INDEX ix_result_date_merchant ON recon_result (business_date, merchant_id);
CREATE INDEX ix_result_class ON recon_result (result_class, reason);

-- ---------------------------------------------------------------- ops queue
CREATE TABLE recon_exception (
    id                   BIGSERIAL PRIMARY KEY,
    entity_type          VARCHAR(16)  NOT NULL,
    entity_id            VARCHAR(40)  NOT NULL,
    business_date        DATE         NOT NULL,
    merchant_id          VARCHAR(16)  NOT NULL,
    exception_class      VARCHAR(24)  NOT NULL,
    reason               VARCHAR(32)  NOT NULL,
    amount_at_risk_minor BIGINT       NOT NULL,
    currency             CHAR(3)      NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    assignee             VARCHAR(64),
    resolution_reason    VARCHAR(40),
    resolution_note      VARCHAR(1000),
    resolved_by          VARCHAR(64),
    resolved_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    first_run_id         BIGINT       NOT NULL,
    last_run_id          BIGINT       NOT NULL,
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_exception_entity_class UNIQUE (entity_type, entity_id, exception_class),
    CONSTRAINT ck_exception_status CHECK (status IN
        ('OPEN', 'IN_REVIEW', 'RESOLVED', 'PENDING_EXPECTED', 'AUTO_RESOLVED'))
);
CREATE INDEX ix_exc_status_date ON recon_exception (status, business_date);
CREATE INDEX ix_exc_date_merchant ON recon_exception (business_date, merchant_id);

CREATE TABLE exception_comment (
    id           BIGSERIAL PRIMARY KEY,
    exception_id BIGINT        NOT NULL REFERENCES recon_exception (id) ON DELETE CASCADE,
    author       VARCHAR(64)   NOT NULL,
    body         VARCHAR(2000) NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_comment_exception ON exception_comment (exception_id);

-- Adjustment journal: one signed entry per fee/rounding mismatch. Above the approval threshold an
-- entry stays PENDING_APPROVAL until an OPS_SUPERVISOR approves it.
CREATE TABLE adjustment_journal (
    id           BIGSERIAL PRIMARY KEY,
    exception_id BIGINT      NOT NULL UNIQUE REFERENCES recon_exception (id) ON DELETE CASCADE,
    entity_id    VARCHAR(40) NOT NULL,
    amount_minor BIGINT      NOT NULL,
    currency     CHAR(3)     NOT NULL,
    status       VARCHAR(20) NOT NULL CHECK (status IN ('POSTED', 'PENDING_APPROVAL', 'REJECTED')),
    created_by   VARCHAR(64) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by  VARCHAR(64),
    approved_at  TIMESTAMPTZ
);
CREATE INDEX ix_adjustment_status ON adjustment_journal (status);

-- ---------------------------------------------------------------- audit (append-only)
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor       VARCHAR(64)  NOT NULL,
    action      VARCHAR(40)  NOT NULL,
    entity_type VARCHAR(24)  NOT NULL,
    entity_id   VARCHAR(64)  NOT NULL,
    details     VARCHAR(2000)
);
CREATE INDEX ix_audit_entity ON audit_log (entity_type, entity_id);

CREATE FUNCTION audit_log_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_append_only();
CREATE TRIGGER trg_audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_append_only();

-- ---------------------------------------------------------------- users
CREATE TABLE app_user (
    username      VARCHAR(64) PRIMARY KEY,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('OPS_ANALYST', 'OPS_SUPERVISOR', 'ADMIN')),
    display_name  VARCHAR(100) NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

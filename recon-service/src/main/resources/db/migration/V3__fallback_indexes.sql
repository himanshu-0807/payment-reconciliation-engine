-- Fallback (amount + merchant + date window) lookups. Without these, each lookup scanned a merchant's
-- whole week of rows and filtered on amount.
CREATE INDEX ix_bank_no_utr ON bank_txn (merchant_id, amount_minor, value_date) WHERE utr IS NULL;
CREATE INDEX ix_gw_merchant_net_date ON gateway_txn (merchant_id, net_minor, txn_date);

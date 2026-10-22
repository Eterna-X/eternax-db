-- DEF-10: a composite rule may group candidates by merchant instead of (or as well as) terminal.
CREATE INDEX idx_txn_composite_merchant ON canonical_transaction (tenant_id, rail, merchant_id, amount_minor, txn_time)
    WHERE recon_status <> 'MATCHED';

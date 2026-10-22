-- DM-01: rail-specific, counterparty and accounting data that arrives as attr.<name> mapping fields.
ALTER TABLE canonical_transaction ADD COLUMN attributes_json TEXT;

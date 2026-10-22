-- CLS-08: remember how severe each carried-forward exception was when it was carried.
ALTER TABLE carry_forward ADD COLUMN severity VARCHAR(10) NOT NULL DEFAULT 'LOW';

-- Initialize database schema for Pix Wallet Service

-- Create indexes for performance
CREATE INDEX IF NOT EXISTS idx_wallets_user_id ON wallets(user_id);
CREATE INDEX IF NOT EXISTS idx_pix_keys_key_value ON pix_keys(key_value);
CREATE INDEX IF NOT EXISTS idx_pix_keys_wallet_id ON pix_keys(wallet_id);
CREATE INDEX IF NOT EXISTS idx_ledger_entries_wallet_created ON ledger_entries(wallet_id, created_at);
CREATE INDEX IF NOT EXISTS idx_pix_transfers_end_to_end_id ON pix_transfers(end_to_end_id);
CREATE INDEX IF NOT EXISTS idx_pix_transfers_idempotency_key ON pix_transfers(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_idempotency_scope_key ON idempotency_records(scope, idempotency_key);

-- Insert sample data for testing (optional)
-- This would be removed in production
INSERT INTO wallets (id, user_id, balance, version, created_at, updated_at)
VALUES
    ('550e8400-e29b-41d4-a716-446655440001', 'demo-user-1', 1000.00, 0, NOW(), NOW()),
    ('550e8400-e29b-41d4-a716-446655440002', 'demo-user-2', 500.00, 0, NOW(), NOW())
ON CONFLICT DO NOTHING;

INSERT INTO pix_keys (id, key_value, key_type, wallet_id, created_at, is_active)
VALUES
    ('550e8400-e29b-41d4-a716-446655440011', 'demo1@example.com', 'EMAIL', '550e8400-e29b-41d4-a716-446655440001', NOW(), true),
    ('550e8400-e29b-41d4-a716-446655440012', 'demo2@example.com', 'EMAIL', '550e8400-e29b-41d4-a716-446655440002', NOW(), true)
ON CONFLICT DO NOTHING;
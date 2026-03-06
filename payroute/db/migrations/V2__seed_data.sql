-- ============================================================
-- Seed Data for PayRoute Development
-- ============================================================

-- Customer accounts
INSERT INTO accounts (id, owner_name, owner_email, account_type) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000001', 'Dangote Industries Ltd', 'finance@dangote.ng', 'customer'),
    ('aaaaaaaa-0000-0000-0000-000000000002', 'Flutterwave Nigeria', 'treasury@flutterwave.ng', 'customer'),
    ('aaaaaaaa-0000-0000-0000-000000000003', 'Paystack Merchants', 'accounts@paystack.ng', 'customer');

-- Balances in NGN (source currency)
INSERT INTO account_balances (account_id, currency, balance) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000001', 'NGN', 5000000.00),
    ('aaaaaaaa-0000-0000-0000-000000000002', 'NGN', 2500000.00),
    ('aaaaaaaa-0000-0000-0000-000000000003', 'NGN', 1000000.00);

-- USD balances for recipients (simulated)
INSERT INTO account_balances (account_id, currency, balance) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000001', 'USD', 0.00),
    ('aaaaaaaa-0000-0000-0000-000000000002', 'USD', 0.00),
    ('aaaaaaaa-0000-0000-0000-000000000003', 'GBP', 0.00);

-- System account balances
INSERT INTO account_balances (account_id, currency, balance) VALUES
    ('00000000-0000-0000-0000-000000000001', 'NGN', 0.00),
    ('00000000-0000-0000-0000-000000000001', 'USD', 0.00),
    ('00000000-0000-0000-0000-000000000002', 'NGN', 0.00);

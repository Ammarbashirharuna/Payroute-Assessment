-- ============================================================
-- PayRoute Database Schema
-- Double-entry bookkeeping, idempotency, full lifecycle support
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ============================================================
-- CURRENCIES
-- ============================================================
CREATE TABLE currencies (
    code        VARCHAR(3) PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    symbol      VARCHAR(10),
    decimal_places INT NOT NULL DEFAULT 2,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO currencies (code, name, symbol) VALUES
    ('NGN', 'Nigerian Naira', '₦'),
    ('USD', 'US Dollar', '$'),
    ('EUR', 'Euro', '€'),
    ('GBP', 'British Pound', '£'),
    ('KES', 'Kenyan Shilling', 'KSh'),
    ('GHS', 'Ghanaian Cedi', 'GH₵');

-- ============================================================
-- ACCOUNTS
-- ============================================================
CREATE TABLE accounts (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    owner_name      VARCHAR(255) NOT NULL,
    owner_email     VARCHAR(255) UNIQUE NOT NULL,
    account_type    VARCHAR(20) NOT NULL CHECK (account_type IN ('customer', 'system', 'suspense')),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Separate balance per currency — no mixed-currency accounts
CREATE TABLE account_balances (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    account_id  UUID NOT NULL REFERENCES accounts(id),
    currency    VARCHAR(3) NOT NULL REFERENCES currencies(code),
    balance     NUMERIC(20, 6) NOT NULL DEFAULT 0,
    UNIQUE (account_id, currency),
    CONSTRAINT positive_balance CHECK (balance >= 0)
);

-- System accounts for double-entry
INSERT INTO accounts (id, owner_name, owner_email, account_type) VALUES
    ('00000000-0000-0000-0000-000000000001', 'PayRoute Transit', 'transit@system.payroute', 'system'),
    ('00000000-0000-0000-0000-000000000002', 'PayRoute Suspense', 'suspense@system.payroute', 'suspense');

-- ============================================================
-- FX QUOTES
-- ============================================================
CREATE TABLE fx_quotes (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    source_currency VARCHAR(3) NOT NULL REFERENCES currencies(code),
    dest_currency   VARCHAR(3) NOT NULL REFERENCES currencies(code),
    rate            NUMERIC(20, 8) NOT NULL,
    fee_rate        NUMERIC(8, 6) NOT NULL DEFAULT 0.015, -- 1.5% fee
    expires_at      TIMESTAMPTZ NOT NULL,
    used            BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT different_currencies CHECK (source_currency <> dest_currency)
);

CREATE INDEX idx_fx_quotes_expires ON fx_quotes(expires_at) WHERE NOT used;

-- ============================================================
-- IDEMPOTENCY KEYS
-- ============================================================
CREATE TABLE idempotency_keys (
    key             VARCHAR(255) PRIMARY KEY,
    endpoint        VARCHAR(100) NOT NULL,
    request_hash    VARCHAR(64),
    response_status INT,
    response_body   JSONB,
    transaction_id  UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '24 hours'
);

CREATE INDEX idx_idempotency_expires ON idempotency_keys(expires_at);

-- ============================================================
-- TRANSACTIONS
-- ============================================================
CREATE TABLE transactions (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    reference               VARCHAR(50) UNIQUE NOT NULL,
    idempotency_key         VARCHAR(255) REFERENCES idempotency_keys(key),

    sender_account_id       UUID NOT NULL REFERENCES accounts(id),
    recipient_account_id    UUID NOT NULL REFERENCES accounts(id),
    recipient_name          VARCHAR(255) NOT NULL,
    recipient_country       VARCHAR(2) NOT NULL,

    source_currency         VARCHAR(3) NOT NULL REFERENCES currencies(code),
    dest_currency           VARCHAR(3) NOT NULL REFERENCES currencies(code),
    source_amount           NUMERIC(20, 6) NOT NULL,
    dest_amount             NUMERIC(20, 6) NOT NULL,
    fee_amount              NUMERIC(20, 6) NOT NULL DEFAULT 0,

    fx_quote_id             UUID REFERENCES fx_quotes(id),
    fx_rate                 NUMERIC(20, 8) NOT NULL,

    status                  VARCHAR(20) NOT NULL DEFAULT 'initiated'
                            CHECK (status IN ('initiated','processing','completed','failed','reversed')),

    provider_reference      VARCHAR(255),
    provider_submitted_at   TIMESTAMPTZ,

    completed_at            TIMESTAMPTZ,
    failed_at               TIMESTAMPTZ,
    reversed_at             TIMESTAMPTZ,
    failure_reason          TEXT,

    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT source_amount_positive CHECK (source_amount > 0),
    CONSTRAINT dest_amount_positive CHECK (dest_amount > 0)
);

CREATE INDEX idx_transactions_status ON transactions(status);
CREATE INDEX idx_transactions_sender ON transactions(sender_account_id);
CREATE INDEX idx_transactions_created ON transactions(created_at DESC);
CREATE INDEX idx_transactions_reference ON transactions(reference);
CREATE INDEX idx_transactions_provider_ref ON transactions(provider_reference);

-- ============================================================
-- TRANSACTION STATUS HISTORY (audit trail)
-- ============================================================
CREATE TABLE transaction_status_history (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    transaction_id  UUID NOT NULL REFERENCES transactions(id),
    from_status     VARCHAR(20),
    to_status       VARCHAR(20) NOT NULL,
    reason          TEXT,
    created_by      VARCHAR(100) NOT NULL DEFAULT 'system',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_status_history_txn ON transaction_status_history(transaction_id);

-- ============================================================
-- LEDGER ENTRIES (double-entry bookkeeping)
-- Every financial event = 2+ entries that sum to zero
-- ============================================================
CREATE TABLE ledger_entries (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    transaction_id  UUID NOT NULL REFERENCES transactions(id),
    account_id      UUID NOT NULL REFERENCES accounts(id),
    currency        VARCHAR(3) NOT NULL REFERENCES currencies(code),
    -- DEBIT = money leaving account (negative), CREDIT = money entering (positive)
    entry_type      VARCHAR(6) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount          NUMERIC(20, 6) NOT NULL CHECK (amount > 0),
    description     VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ledger_transaction ON ledger_entries(transaction_id);
CREATE INDEX idx_ledger_account ON ledger_entries(account_id);

-- View: ensure every transaction's ledger entries balance (per currency)
CREATE VIEW ledger_integrity_check AS
SELECT
    transaction_id,
    currency,
    SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS net_balance
FROM ledger_entries
GROUP BY transaction_id, currency
HAVING ABS(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END)) > 0.000001;

-- ============================================================
-- WEBHOOK EVENTS (raw log — never lose an event)
-- ============================================================
CREATE TABLE webhook_events (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    provider            VARCHAR(50) NOT NULL DEFAULT 'default',
    event_id            VARCHAR(255),       -- provider's own event ID for idempotency
    headers             JSONB,
    raw_body            TEXT NOT NULL,
    signature           VARCHAR(255),
    signature_valid     BOOLEAN,
    transaction_id      UUID REFERENCES transactions(id),
    processing_status   VARCHAR(20) NOT NULL DEFAULT 'received'
                        CHECK (processing_status IN ('received','processed','failed','ignored')),
    processing_error    TEXT,
    processed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_webhook_event_id ON webhook_events(provider, event_id) WHERE event_id IS NOT NULL;
CREATE INDEX idx_webhook_transaction ON webhook_events(transaction_id);
CREATE INDEX idx_webhook_created ON webhook_events(created_at DESC);

-- ============================================================
-- TRIGGER: auto-update updated_at
-- ============================================================
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_accounts_updated_at
    BEFORE UPDATE ON accounts
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER trg_transactions_updated_at
    BEFORE UPDATE ON transactions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

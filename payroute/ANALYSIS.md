# PayRoute — Written Analysis

## Schema Design Document

### 1. Why this structure over alternatives considered

**Separate `account_balances` table (not a `balance` column on `accounts`):**  
Each account can hold multiple currencies. A single `balance` column forces either a denormalised JSON blob or a redesign when you add a new currency. The junction table `(account_id, currency)` with a UNIQUE constraint keeps the model clean and lets you add NGN, USD, EUR support without schema changes.

**Double-entry `ledger_entries` instead of raw balance mutations:**  
Every payment that touches a balance can be reconstructed by summing ledger entries. If a bug corrupts a balance row, you can replay the ledger to restore it — you cannot do this if you only mutate balances in-place. The `ledger_integrity_check` view queries for any transaction whose entries don't net to zero, making silent corruption detectable.

**Separate `transaction_status_history` table instead of a single `status` column:**  
The current status is on `transactions` for fast reads. The history table gives a complete audit trail — essential for dispute resolution and regulatory requirements. A single status column loses history forever.

**`webhook_events` as a raw log table:**  
Webhook payloads are written to `webhook_events` before any processing logic runs. This means even if the processing code crashes, panics, or the DB transaction rolls back, we still have the raw event. This is the "append-first" pattern: log it, then process it.

**`idempotency_keys` with a TTL:**  
Storing the full serialised response against the idempotency key means repeat requests get the exact same bytes back without re-running business logic. The 24-hour TTL matches typical client retry windows.

**Alternatives rejected:**  
- Single `transactions` table with embedded balance — breaks double-entry.  
- Event sourcing (rebuild state from events only) — too complex for a prototype, adds operational burden.  
- Balance stored as integer cents — considered, but `NUMERIC(20,6)` keeps precision without floating-point risk and handles sub-cent currencies cleanly.

---

### 2. How we ensure no money is created or destroyed

Three layers of defence:

**Layer 1 — Double-entry constraint:**  
Every balance change creates two ledger entries that net to zero: a DEBIT on the source account and a CREDIT on the transit account (or vice versa on settlement). The `ledger_integrity_check` view surfaces any violation. Summing all ledger entries across all accounts at any point in time must equal zero.

**Layer 2 — Database-level positive balance constraint:**  
`account_balances` has `CHECK (balance >= 0)`. Any code path that would take a balance negative fails at the database layer, not just the application layer. This is a last-resort guard against bugs that bypass service-layer checks.

**Layer 3 — Pessimistic row locking on balance updates:**  
`findByAccountIdAndCurrencyForUpdate` uses `SELECT ... FOR UPDATE`, acquiring an exclusive lock on the balance row before reading it. This prevents two concurrent transactions from both reading the same balance (say, 1000 NGN), both deciding they can proceed, and both debiting — resulting in a -1000 balance. The lock serialises concurrent payment initiation for the same account.

---

### 3. How to handle adding a new currency pair in production

1. **Insert into `currencies`** — zero-downtime DDL, no application restart needed.
2. **Deploy updated FX rate configuration** — in this prototype, rates are hardcoded in `PaymentService.getSimulatedRate()`. In production this would be a `currency_pairs` table or external FX service config, updated independently.
3. **Create system account balances** for the new currency — the system transit account needs a `(transit_account_id, 'NEW')` row in `account_balances`.
4. **No migration needed for customer accounts** — customers get a new balance row on first use (the `findByAccountIdAndCurrency` → `createBalance` flow handles this).
5. **Canary test** — run a test payment with a small amount to verify the FX rate, ledger entries, and webhook handling all work correctly before opening to production traffic.

---

### 4. One thing I would do differently with more time

Replace the simulated in-memory idempotency key lookup with a **Redis-based distributed lock** acquired before the balance check. The current approach uses a database row as the lock, which works but has a window between "check if idempotency key exists" and "write the key" where two concurrent identical requests can both proceed. In production with multiple backend replicas, this window is real. A Redis SETNX with a 30-second TTL would close that window atomically, and then the database unique constraint acts as a final safety net.

---

## Part 4A: Code Review — Junior Webhook Handler

```javascript
app.post('/webhooks/payment-provider', async (req, res) => {
  const payload = req.body;
  const signature = req.headers['x-webhook-signature'];
  if (!signature) {
    return res.status(401).send('Missing signature');
  }
  // ...
```

### Issue 1: Signature is checked for presence but never verified

**Problem:** The code checks that `signature` exists but never computes HMAC-SHA256 over the raw body and compares it to the header value.

**Why it matters in payments:** Any attacker who knows your webhook endpoint URL can POST arbitrary status updates — marking failed payments as completed, crediting accounts they don't own, or triggering fraudulent reversals. Signature verification is the only authentication between you and the provider.

**Fix:**
```javascript
const crypto = require('crypto');
const expectedSig = crypto
  .createHmac('sha256', process.env.WEBHOOK_SECRET)
  .update(rawBody) // rawBody must be the raw Buffer, not parsed JSON
  .digest('hex');
if (!crypto.timingSafeEqual(Buffer.from(signature), Buffer.from(expectedSig))) {
  return res.status(200).send('OK'); // Return 200 but log the invalid attempt
}
```

Note: use `timingSafeEqual` — standard string equality leaks timing information about how many characters matched.

---

### Issue 2: Raw body is not preserved for HMAC verification

**Problem:** `req.body` is parsed JSON (an object). HMAC must be computed over the exact raw bytes received, not re-serialised JSON. Even a different key ordering in serialisation will produce a different HMAC and make all signatures fail.

**Why it matters:** You will reject every legitimate webhook because the signature will never match.

**Fix:** Use raw body middleware:
```javascript
app.use('/webhooks', express.raw({ type: 'application/json' }));
```
Store `req.body.toString()` as `rawBody` before parsing.

---

### Issue 3: Non-200 response for unknown transaction references

**Problem:** Returning `404` when the transaction isn't found.

**Why it matters in payments:** Payment providers retry on any non-2xx response. An unknown reference (e.g., from a test environment, a misconfigured provider account, or a race where the webhook arrives before the transaction is committed) will cause the provider to retry forever, filling your logs and potentially triggering rate-limiting or account suspension.

**Fix:** Return 200, log the unknown reference, and store it in `webhook_events` for investigation. This is the industry-standard behaviour.

---

### Issue 4: No idempotency — double-credit on retry

**Problem:** If the provider sends the same "completed" webhook twice (common — providers retry on network failures), the code will execute the `UPDATE accounts SET balance = balance + $1` twice, crediting the recipient twice.

**Why it matters:** This creates money from nothing. The recipient gets double the funds, the provider only paid once, and your books are wrong.

**Fix:** Use a `webhook_events` table with a unique constraint on `(provider, event_id)`. Check before processing; skip if already processed.

---

### Issue 5: No database transaction — partial writes

**Problem:** The `UPDATE transactions` and `UPDATE accounts` are two separate queries with no wrapping transaction. If the process crashes, network drops, or the second query fails, you get a transaction marked "completed" but the account balance not updated (or vice versa).

**Why it matters:** You now have a transaction that says "completed" but the recipient was never credited. This is a silent financial discrepancy that is very hard to detect and reconcile.

**Fix:**
```javascript
const client = await db.pool.connect();
try {
  await client.query('BEGIN');
  await client.query('UPDATE transactions ...');
  await client.query('UPDATE accounts ...');
  await client.query('COMMIT');
} catch (e) {
  await client.query('ROLLBACK');
  throw e;
} finally {
  client.release();
}
```

---

### Issue 6: No state transition validation

**Problem:** The code blindly updates the transaction to "completed" or "failed" regardless of the current status. A "completed" webhook arriving after a "failed" webhook would re-complete an already-failed transaction.

**Why it matters:** You could reverse funds (on failed) and then credit the recipient again (on a delayed completed webhook), effectively paying out twice for one payment.

**Fix:** Load the current status and validate the transition before updating:
```javascript
if (transaction.rows[0].status !== 'processing') {
  return res.status(200).send('OK'); // Already processed, skip
}
```

---

### Issue 7: `payload.amount` used for credit instead of `dest_amount`

**Problem:** `balance + $1 WHERE ... [payload.amount]` — the amount being credited comes from the webhook payload, not from your own stored `dest_amount`.

**Why it matters:** A compromised or misconfigured provider could send `amount: 999999` and your code would credit that amount. The correct amount to credit is the `dest_amount` you computed and stored when you created the transaction.

**Fix:** Use `transaction.rows[0].dest_amount` for the credit, never trust the amount from an external webhook.

---

## Part 4B: Failure Scenarios

### 1. Double-spend: Two concurrent payments, one balance

**How the system handles it:**

The service uses `SELECT ... FOR UPDATE` (pessimistic write lock) on the `account_balances` row combined with `Isolation.REPEATABLE_READ`. When two concurrent requests arrive:

1. Request A acquires the lock on the balance row.
2. Request B blocks, waiting for the lock.
3. Request A reads balance (e.g., 1000 NGN), validates (1000 ≥ 600 ✓), debits, commits, releases lock.
4. Request B acquires the lock, reads the updated balance (400 NGN), validates (400 ≥ 600 ✗), throws `InsufficientFundsException`.

The database-level `CHECK (balance >= 0)` is an additional backstop — even if the application logic had a bug, the constraint would reject the write.

---

### 2. Webhook arrives before transaction is written

**How the system handles it:**

`findByProviderReference` returns empty. The webhook handler logs the event to `webhook_events` with `processing_status = 'ignored'` and returns 200. The provider will retry (per their retry policy). By the time the retry arrives, the transaction will have been committed.

**What I would add:** A short-circuit reconciliation job that re-processes `ignored` webhook events older than 30 seconds against the current transaction state. This handles providers that retry only once or have very short retry windows.

---

### 3. FX rate stale — user waits 10 minutes before confirming

**How the system handles it:**

`FxQuote` has an `expiresAt` field set to `NOW() + fxQuoteTtlMinutes` (default 5 minutes). The `isExpired()` method checks against current time. In `initiatePayment`, if the quote is expired, we call `obtainFxQuote` to get a fresh rate.

**In the frontend:** The quote preview shows a "valid for 5 minutes" notice. If the user waits and then submits, the backend obtains a fresh quote at the actual market rate. The new rate is shown in the response, and the user can see the final FX rate applied.

**The 3% move scenario:** The system should apply the fresh rate and charge the new amount. The user should be shown the rate on confirmation (frontend preview) and the actual rate applied (in the response). For large moves, a production system might add a "rate tolerance" parameter — reject if the rate moved more than X% from the quoted rate and ask the user to reconfirm.

---

### 4. Partial settlement — recipient bank rejects credit 2 days later

**How to model this:**

This is a new event type: `partially_reversed`. The schema supports it via a new status and additional ledger entries. The modelling would be:

1. Provider sends a new webhook: `{ status: "credit_rejected", reference: "PRV-xxx", amount: <dest_amount> }`
2. A new `transaction_status_history` entry records the reversal.
3. Compensating ledger entries:
   - DEBIT recipient's dest-currency account by `dest_amount`
   - CREDIT a suspense account (you now hold funds that need to be returned)
4. A separate reconciliation process handles returning funds to the sender — either as a new NGN credit or a manual refund.

The key principle: the original ledger entries are never modified. All reversals are new entries. The audit trail is always additive.

---

### 5. Provider timeout — did they receive it?

**Current implementation:** `submitToProvider` is simulated and always succeeds. In production:

**What I would do:**

1. **Before the HTTP call:** Set `status = processing` and write the transaction to DB with `provider_submitted_at = NOW()`.
2. **On timeout (30s):** The transaction stays in `processing`. We do NOT know if the provider received it.
3. **Do NOT retry immediately** — if they did receive it and we retry, we may submit a duplicate payment. Most providers have idempotency keys for submissions — include the transaction's `reference` as the provider's idempotency key.
4. **Background reconciliation job:** Every 5 minutes, query the provider's status endpoint for all transactions in `processing` state older than 2 minutes. Update status based on their response.
5. **Alert:** If a transaction stays in `processing` for more than 30 minutes without a webhook, trigger an ops alert for manual review.

The worst outcome is the provider received the payment but we never find out — the background job prevents this from being a permanent silent failure.

---

## Part 4C: Production Readiness — 5 Critical Items

### 1. Distributed idempotency locking (Redis SETNX)

**Why it matters:** With multiple backend replicas, two requests with the same idempotency key can both reach the `findByKeyAndEndpoint` check before either writes the key, both see "not found", and both create transactions. This is a real race condition that the current DB-only approach doesn't fully close.

**Implementation:** On payment initiation, acquire `SETNX payroute:idempotency:{key}` with a 30-second TTL before any DB work. If the lock is not acquired, wait and poll for the cached response. The DB unique constraint remains a backstop.

**Failure mode prevented:** Duplicate payments created under concurrent requests with the same idempotency key.

---

### 2. Outbox pattern for provider submission

**Why it matters:** After debiting the sender and saving the transaction, we call the provider. If this call fails (timeout, crash), the sender has been debited but no payment was submitted. The funds are stuck in transit. The transaction shows `processing` forever.

**Implementation:** Write a row to a `payment_outbox` table in the same DB transaction as the debit. A background worker (Debezium CDC or a polling job) reads the outbox and makes the provider call, retrying with exponential backoff. Only delete the outbox row on confirmed receipt from the provider.

**Failure mode prevented:** Lost payments where funds are debited but never submitted.

---

### 3. Comprehensive audit logging and alerting

**Why it matters:** Financial regulators require audit trails. Ops teams need to detect anomalies (sudden spike in failures, large unexpected payments, balance discrepancies) in real time.

**Implementation:**
- Structured JSON logs for every state transition, every ledger entry, every webhook.
- Metrics: `payment_initiated_total`, `payment_completed_total`, `payment_failed_total`, `ledger_net_balance` (should always be 0), `balance_check_failed_total`.
- Alerts: balance goes negative (CRITICAL), failure rate > 10% in 5 minutes (WARNING), `processing` transactions older than 30 minutes (WARNING).
- Daily reconciliation report: sum of all ledger entries by currency must equal zero.

**Failure mode prevented:** Silent financial discrepancies, undetected provider outages, regulatory non-compliance.

---

### 4. Secrets management and API key rotation

**Why it matters:** The webhook secret, database password, and provider API keys are currently in environment variables. In production, these need rotation capability without restarts, audit trails, and access controls.

**Implementation:**
- Store secrets in AWS Secrets Manager or HashiCorp Vault.
- Spring Boot loads secrets at startup via the Vault/Secrets Manager SDK, not env vars.
- Webhook secret: support a `previousSecret` alongside `currentSecret` for a rotation window — accept webhooks signed with either. Rotate the old one out after 24 hours.
- Database credentials: use short-lived credentials via IAM auth (RDS IAM Authentication) — no static passwords.

**Failure mode prevented:** Credential exposure via logs, env var leakage, inability to rotate after a breach.

---

### 5. Circuit breaker and graceful degradation for provider calls

**Why it matters:** If the downstream provider API goes down, every payment initiation will block for 30 seconds (the timeout), exhausting thread pools and making the entire service unresponsive.

**Implementation:**
- Wrap provider HTTP calls in a circuit breaker (Resilience4j `@CircuitBreaker`).
- After 5 consecutive timeouts, the circuit opens: new payment submissions immediately return a queued state without hitting the provider.
- Queued payments are held in the `payment_outbox` and submitted when the circuit closes.
- Surface circuit state in a health endpoint so the operations dashboard can show "Provider degraded — payments queued".

**Failure mode prevented:** Provider outage cascading into full service outage; thread pool exhaustion; customer-facing timeouts on payment initiation.

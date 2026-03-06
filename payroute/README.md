# PayRoute — Cross-Border Payment Processing

A simplified cross-border payment processing service for Nigerian businesses to send payments to international suppliers.

## Tech Stack

- **Backend:** Java 17 + Spring Boot 3.2
- **Database:** PostgreSQL 15 with Flyway migrations
- **Frontend:** React 18
- **Infrastructure:** Docker + docker-compose

## Quick Start

```bash
# 1. Clone the repository
git clone https://github.com/YOUR_USERNAME/payroute.git
cd payroute

# 2. Copy environment variables
cp .env.example .env

# 3. Start everything
docker-compose up --build

# Services:
# - Frontend:  http://localhost:3000
# - Backend:   http://localhost:8080
# - Database:  localhost:5432
```

The database is automatically migrated and seeded on first start via Flyway.

## Architecture

```
Client Request → PaymentController
                     ↓
              PaymentService
              ├── Idempotency check (IdempotencyKeyRepository)
              ├── Balance lock (PESSIMISTIC_WRITE on account_balances)
              ├── FX Quote (simulated FxQuoteService)
              ├── Debit sender + double-entry ledger
              ├── Submit to provider (simulated)
              └── Record status history

Webhook →  WebhookController
                ↓
           WebhookService
           ├── Log raw event (always first)
           ├── Verify HMAC-SHA256 signature
           ├── Idempotency check (webhook_events.event_id UNIQUE)
           └── PaymentService.processWebhookEvent
               ├── completed → credit recipient via ledger
               └── failed → reverse sender debit via ledger
```

## API Reference

### POST /payments
Initiate a new payment.

**Headers:**
- `Idempotency-Key: <unique-string>` (required)
- `Content-Type: application/json`

**Request:**
```json
{
  "senderAccountId": "aaaaaaaa-0000-0000-0000-000000000001",
  "recipientName": "Acme Suppliers Ltd",
  "recipientCountry": "US",
  "destCurrency": "USD",
  "sourceAmount": 500000,
  "sourceCurrency": "NGN"
}
```

**Response:**
```json
{
  "id": "uuid",
  "reference": "PAY-1234567890-ABC123",
  "status": "processing",
  "sourceAmount": 500000,
  "destAmount": 323.50,
  "fxRate": 0.00064700,
  "feeAmount": 7500,
  "providerReference": "PRV-ABC12345",
  "ledgerEntries": [...],
  "statusHistory": [...]
}
```

### GET /payments/:id
Get full transaction details including ledger entries and status timeline.

### GET /payments
List transactions with optional filters.

**Query params:**
- `status` — initiated | processing | completed | failed | reversed | all
- `from` — ISO 8601 datetime
- `to` — ISO 8601 datetime
- `page` — 0-indexed (default: 0)
- `size` — max 100 (default: 20)

### POST /webhooks/provider
Receive provider status updates.

**Headers:**
- `X-Webhook-Signature: <hmac-sha256-hex>`
- `Content-Type: application/json`

**Payload:**
```json
{
  "eventId": "evt_unique_id",
  "reference": "PRV-ABC12345",
  "status": "completed",
  "amount": 323.50,
  "currency": "USD"
}
```

Always returns `200 OK`. See ANALYSIS.md for reasoning.

## Simulating a Webhook (testing)

```bash
# Compute the HMAC signature
SECRET="dev-webhook-secret-change-in-production"
BODY='{"eventId":"evt001","reference":"PRV-YOURREF","status":"completed","amount":323.50,"currency":"USD"}'
SIG=$(echo -n "$BODY" | openssl dgst -sha256 -hmac "$SECRET" | awk '{print $2}')

# Send the webhook
curl -X POST http://localhost:8080/webhooks/provider \
  -H "Content-Type: application/json" \
  -H "X-Webhook-Signature: $SIG" \
  -d "$BODY"
```

## Seed Accounts (development)

| Account | Owner | NGN Balance |
|---------|-------|-------------|
| `aaaaaaaa-0000-0000-0000-000000000001` | Dangote Industries Ltd | 5,000,000 |
| `aaaaaaaa-0000-0000-0000-000000000002` | Flutterwave Nigeria | 2,500,000 |
| `aaaaaaaa-0000-0000-0000-000000000003` | Paystack Merchants | 1,000,000 |

## Supported Currency Pairs (NGN source)

| Pair | Simulated Rate |
|------|---------------|
| NGN → USD | 0.000647 |
| NGN → GBP | 0.000511 |
| NGN → EUR | 0.000598 |
| NGN → KES | 0.083600 |
| NGN → GHS | 0.009830 |

## Key Design Decisions

### Double-Entry Bookkeeping
Every payment creates two ledger entries that net to zero. A `ledger_integrity_check` DB view surfaces any violation. The `account_balances.CHECK (balance >= 0)` constraint prevents negative balances at the database level.

### Idempotency
Payment initiation checks `idempotency_keys` first. If the key was seen before, the cached response is returned without re-running business logic. Webhook processing uses a `UNIQUE (provider, event_id)` index — duplicate events are rejected by the database constraint.

### Concurrency Safety
Balance debits use `SELECT ... FOR UPDATE` (pessimistic locking) wrapped in `REPEATABLE_READ` isolation to prevent overdraft under concurrent requests.

### Webhook Design
Raw webhook body is logged to `webhook_events` before any processing. Even if the processing code fails, the event is preserved. The handler always returns `200 OK` to prevent provider retries from endless loops on expected error cases.

## What's Not Production-Ready (see ANALYSIS.md for details)

1. FX rates are hardcoded — production needs a real FX API
2. Provider submission is simulated — no actual HTTP call
3. No Redis distributed lock for idempotency (race window exists with multiple replicas)
4. No outbox pattern for provider submission (payment can be debited but not submitted on crash)
5. No circuit breaker on provider calls

## Running Tests

```bash
cd backend
mvn test
```

## Project Structure

```
payroute/
├── backend/
│   ├── src/main/java/com/payroute/
│   │   ├── controller/     # REST endpoints
│   │   ├── service/        # Business logic
│   │   ├── model/          # JPA entities
│   │   ├── repository/     # Spring Data JPA
│   │   ├── dto/            # Request/response objects
│   │   └── exception/      # Error handling
│   └── src/main/resources/
│       └── db/migration/   # Flyway SQL migrations
├── frontend/
│   └── src/
│       ├── pages/          # TransactionList, TransactionDetail, PaymentForm
│       └── services/       # API client
├── db/migrations/          # Source of truth SQL files
├── docker-compose.yml
├── .env.example
├── README.md
└── ANALYSIS.md
```

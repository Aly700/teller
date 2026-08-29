# Teller — design (extracted from the shared spec)

> **Scope.** Teller is a simulated payments core for synthetic data only. It moves no real money and connects to no external financial system.

## Project 2 — Teller (new repo, built after Gate)

A simulated payments core with a policy gate. Same stack. Reuses Gate's rules engine
as its policy module, extended with money-aware matchers.

### Domain

- Account: id, currency, ledger balance, available balance, status.
- Transfer: idempotency key, from, to, amount, currency, state machine
  `PENDING -> AUTHORIZED | HELD -> POSTED | REVERSED`, reason codes.
- Entry: double-entry rows; every posted transfer produces balanced
  debit/credit entries; balances are derived and cached with optimistic
  locking.
- Policy: rules with matchers for amount limits, velocity (n per window),
  counterparty allow/deny lists, four-eyes threshold, currency; effects
  ALLOW / DENY / REQUIRE_APPROVAL; first match wins; default deny.
- Approval: as in Gate, over SQS; expiry reverses the hold.
- Audit and nightly export: as in Gate, plus a reconciliation job that
  proves the S3 export sums to the ledger.

### Proof layer

DST as in Gate with money invariants: conservation (sum of all entries is
zero per currency), no negative available balance without an overdraft rule,
every HELD transfer ends POSTED or REVERSED, idempotent replays are no-ops
under any interleaving of crashes and duplicate deliveries. Property tests on
ledger arithmetic and on the policy module. k6 and fault evidence come only
from local or isolated demo environments.

### Approvals console

Small React + TypeScript (Vite) app: queue of held transfers, approve/deny
with reason, audit view, served from S3 + CloudFront only if it stays under
the cost ceiling, else from the API's static resources. This gives reviewers
a small interface for synthetic held transfers and audit records.

### Out of scope

Connections to external financial systems, KYC, FX, multi-currency
conversion, Kafka, OAuth, multi-region.

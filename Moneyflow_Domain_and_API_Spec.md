# Moneyflow — Domain Model & API Contract Specification
**Version:** 1.5.0  
**Derived from:** Figma screens (39 pages), Excel cashflow template (FY24-25), User journey map  
**Purpose:** Complete build specification before writing any Java code  
**Architecture:** Modular monolith · Spring Boot 3 · SQLite · Financial year April–March

### Changelog
| Version | Change |
|---|---|
| 1.0.0 | Initial specification |
| 1.1.0 | Removed income profiling wizard. Income tracked naturally via transactions. Simplified UserProfile to preferences only. Removed OtherIncome entity. |
| 1.2.0 | Backend MVP complete. Analytics finalised (mode/anchor query model). PlannedAmount module deferred to Phase 2. Onboarding step 2 (planned amounts) hardcoded false. Transaction backdating warning (BR-13) added. All implemented modules documented accurately. |
| 1.3.0 | Transactions API hardened (pagination, sort, flowType filter, PUT scope, smart-skip month navigation, available-periods picker); TRANSFER/goal reversal made fully symmetric; three new business rules (BR-15/16/17); Accounts gained `goalLinked`; Categories gained `isInternal`; Dashboard `balancePercentage`/`savedThisMonth` corrected. Full breakdown below. |
| 1.4.0 | Onboarding is now invite-only (BR-18). `POST /auth/signup` removed; `ADMIN` invites a `MEMBER` by email, who verifies via a 6-digit OTP before their `User` is created. |
| 1.5.0 | Goal allocation ledger added, covering both trigger points of the original regression: a withdrawal TRANSFER drawing from a goal-linked account beyond its free balance (BR-19), and a downward `PUT /accounts/{id}` balance correction that drops below the account's earmarked total (BR-20) — both now record *which* goal(s) absorb the change, via a new `transaction_goal_allocations` table, instead of assuming one goal per account. Full breakdown below. |

<details>
<summary><strong>1.3.0 detailed changes</strong> (click to expand)</summary>

**`GET /transactions`**
- Paginated (`page`/`size`, default `size=100`); `sort` uses Spring Data's `property,direction` convention, repeatable for multi-field sort — not a separate `direction` param.
- Month-navigation hints `previousPeriod`/`nextPeriod` added for calendar- and FY-filtered requests — resolve to the *nearest period with data*, not just the adjacent one, so a backdated entry several months back stays reachable even through empty months in between (originally shipped as booleans `hasPreviousMonthData`/`hasNextMonthData`, later replaced once backdating exposed the adjacent-only gap).
- New `flowType=INCOME|EXPENSE` filter, composes with either period filter.
- New `GET /transactions/available-periods`: years + months-per-year with any data, calendar lens only, powering a year-based month picker as a companion to (not a replacement for) the smart-skip arrows above.

**`PUT /transactions/{id}`**
- Scope clarified: corrects `amount`, `category`, `date`, `notes` (non-SETTLEMENT only) — never `type`, `accountId`, `toAccountId`, `toGoalId`.

**TRANSFER / goal correctness**
- BR-03/BR-05: TRANSFER reversal made symmetric across source *and* destination account, on both PUT and DELETE (previously only source was reversed).
- BR-07: goal-progress reversal wired into PUT amount corrections; progress now also decreases on withdrawal (TRANSFER whose source account is goal-linked), not just increases on arrival.
- `to_account_id` now always stored for a TRANSFER regardless of direct-account vs. goal destination; `transactions` `CHECK` constraint relaxed to match.
- **New BR-15:** `POST /transactions` rejects a TRANSFER whose destination resolves to the same account as the source.
- **New BR-16:** `POST`/`PUT /transactions` reject a future-dated `date` (`400`, hard block — unlike BR-13's backdating warning), since balance effects apply immediately and a future date would understate `currentBalance` today. Forecasting noted as a separate, deferred §11 concept built on `PlannedAmount`, not future-dated `Transaction` rows.

**Accounts**
- `GET /accounts` and `GET /accounts/{id}` gained a response-only `goalLinked` flag (batch-resolved to avoid N+1), driving the piggy-bank icon.

**Categories**
- `isInternal` added (new seed row `cat-35 Opening Balance`); excluded from `GET /categories`, rejected identically to a nonexistent category by `POST`/`PUT /transactions` (no distinguishing error, per BR-14's enumeration-safety precedent) — **new BR-17**.
- BR-01's opening-balance `SETTLEMENT` now uses this category instead of sharing `Adjustment` (`cat-02`) with BR-02 — distinguishable by category, not by parsing `notes` text.
- BR-02's adjustment `notes` amounts normalized (`stripTrailingZeros().toPlainString()`) so old/new never show mismatched decimal precision.

**Dashboard**
- `balancePercentage` documented as a deliberate solvency/safety-margin indicator (not a bounded monthly-depletion gauge); display bands (>30% healthy, 20–30% caution, ≤20% low) traced to the Excel template's conditional formatting.
- Formula extended to add this month's `Opening Balance`-categorized `SETTLEMENT` sum to the denominator (fixes a false `0%`/"critically low" reading on onboarding day); now returns `null`, not `0`, when the denominator is zero.
- `savedThisMonth` fixed to sum only `TRANSFER` rows with `toGoalId` set — a plain account-to-account transfer no longer counts as savings.

</details>

<details>
<summary><strong>1.5.0 detailed changes</strong> (click to expand)</summary>

**Why this was needed:** a goal-linked account is protected from direct expenses (BR-12), so the only way to spend down a goal-linked balance is a `TRANSFER` withdrawal. Before this change, a withdrawal's effect on `Goal.currentProgress` was inferred implicitly — and once an account could back *more than one* active goal, there was no way to know which goal(s) a withdrawal should draw down, and a later balance/amount correction had no memory of what a previous edit had decided. `GoalRepository.findByAccountIdAndActiveTrueAndStatusNot` returning a singular `Optional<Goal>` for the withdrawal path would have thrown `IncorrectResultSizeDataAccessException` the moment two active goals shared an account.

**New concept — free balance:** `account.currentBalance − Σ(currentProgress of that account's active, non-completed goals)`. This is the portion of a goal-linked account's balance that is *not* currently earmarked for any goal — a withdrawal transfer can always draw from free balance without needing to touch any goal, and only needs an explicit goal allocation when the amount exceeds it.

**New table — `transaction_goal_allocations`:** a proper one-to-many child ledger, `{transactionId, goalId, amount}` per goal a withdrawal transfer drew from. Deliberately separate from the existing singular `to_goal_id` column, which remains exactly what it always was — the *arrival*-side tag for a TRANSFER crediting a single goal (BR-07's "arriving" case). `to_goal_id` and this new table are never both populated on the same transaction: one is for crediting a goal, the other for debiting one or more.

**`POST /transactions` (TRANSFER, withdrawal from a goal-linked account):**
- New optional `goalAllocations: [{ goalId, amount }]` field. If omitted or the amount fits entirely within free balance, no allocation is recorded. If the amount exceeds free balance and no allocation is supplied, `400` with a structured payload (`freeBalance`, `shortfall`, `availableGoals`) instead of a bare message — see below.
- Guard: the sum of `goalAllocations` amounts cannot exceed the transaction amount; the remaining, unallocated portion must fit within free balance.

**`PUT /transactions/{id}` (same account/transaction, amount changed):**
Reconciles the existing allocation against the new amount in four tiers, tried in order:
1. An explicit `goalAllocations` in the request always wins, fully replacing whatever was recorded before (never merged with it).
2. Otherwise, if the prior allocation still validates against the new amount (its total doesn't exceed the new amount, and the unallocated remainder still fits free balance), it is kept unchanged — even if the new amount is larger, with the extra funded from free balance.
3. Otherwise, if the new amount on its own fits entirely within free balance, the prior allocation is dropped to empty and a `warning` is returned explaining that the goal money was released — since the new amount needs no goal help at all, keeping a stale allocation would misrepresent the transaction.
4. Otherwise (the prior allocation no longer fits, and free balance alone still isn't enough), the request is rejected with the same structured shortfall payload as create.

**Deliberate bookkeeping principle:** the system never silently redistributes or reinterprets a user's earmarked goal money without either an explicit instruction (tier 1) or the allocation becoming provably unnecessary (tier 3). It never guesses which goal(s) to draw from when genuine ambiguity exists (tier 4 always asks, never assumes).

**Structured insufficient-balance error:** `ApiException`/`ApiResponse.ApiError` gained a generic `details: Object` field (kept generic so `shared.exception` never depends on a domain module, preserving the modular-monolith dependency direction). For a withdrawal that can't be covered by free balance alone with no allocation supplied, `details` carries:
```json
{
  "freeBalance": 93000,
  "shortfall": 107000,
  "availableGoals": [
    { "goalId": "uuid", "goalName": "Emergency Fund", "currentProgress": 20000 },
    { "goalId": "uuid", "goalName": "Vacation", "currentProgress": 35000 }
  ]
}
```
This lets the client render "choose which goal(s) to draw the rest from" directly from the error, without a second round trip. When a goal-level (not account-level) guard fires instead — an individual allocation exceeding what a specific goal can supply or absorb — the error stays a plain message, since that failure isn't the "let the user choose" case.

**Per-goal bounds, both directions:** decreasing a goal's earmarked amount (withdrawal) can't exceed its `currentProgress`; increasing it can't push `currentProgress` past `targetAmount`. The increase-direction cap has no live call path yet (every current caller passes `DECREASE` only) — it exists ahead of a future feature (e.g. crediting a goal from `INCOME`) that will need it.

**Allocation-item validation (hardened post-ship, same v1.5.0):** two gaps surfaced testing BR-20 that applied equally to BR-19, since both call the same shared method. First, `GoalAllocationItem.amount` had no positivity constraint at the application layer — nothing stopped a negative amount from reaching `applyAllocations`, where a `DECREASE` call would compute `currentProgress.subtract(negative)`, i.e. an *increase*. In practice this never silently corrupted a goal: `transaction_goal_allocations` has carried `CHECK (amount > 0)` and `UNIQUE (transaction_id, goal_id)` since its very first migration (V7), and because `applyAllocations` runs inside `@Transactional`, hitting either constraint at flush/commit rolled back the *whole* transaction atomically — including the goal's `currentProgress` mutation from earlier in the same loop. The real symptom was an unhandled `DataIntegrityViolationException` surfacing as a generic 500 "An unexpected error occurred," not a corrupted goal. Second, none of the three request DTOs (`CreateTransactionRequest`, `UpdateTransactionRequest`, `UpdateAccountRequest`) cascaded validation into the `goalAllocations` list at all — Bean Validation needs `@Valid` on the container field to walk into list elements, the same way a NestJS DTO needs `@ValidateNested()` + `@Type()` on an array field before `class-validator` will look inside it. Fixed by adding a `@DecimalMin(inclusive = false)` constraint to `GoalAllocationItem.amount` and `@Valid` to all three DTOs together — neither half does anything without the other — plus the same duplicate-`goalId` check restated at the application layer in `GoalAllocationService.applyAllocations`, ahead of the DB constraint. The value of both fixes is failing at the API boundary with a clean `400` and a specific message, instead of leaking an internal database exception as an opaque `500` — not preventing data corruption, which the schema's own constraints already guaranteed.

**`TransactionResponse`** gained a `goalAllocations: [{ goalId, goalName, amount }]` breakdown, present (possibly empty) on every transaction, list and detail alike — surfacing what previously required a failed edit attempt to discover.

**Delete and update** both fully reverse a transaction's allocation rows (and the corresponding goal progress) before any new state is applied — verified end-to-end, not just by reading the `@Transactional` annotation.

**`PUT /accounts/{id}` (balance correction, BR-20):** the second trigger point the original regression came from — editing an account's balance directly, which previously never touched a linked goal's progress at all. Reuses the same structured payload and per-goal guard as above, but needs no tiered reconciliation: unlike a transaction edit, a balance correction is always a brand-new `SETTLEMENT/Adjustment` event (BR-02), never an edit to a prior one, so there's nothing to reconcile against — only a single validate-or-commit check.

- Trigger: `earmarked = Σ(currentProgress of the account's active goals)`. If the corrected `currentBalance` is still `≥ earmarked`, nothing goal-related happens — the correction only reduces free balance, same as before this feature existed.
- If the new balance drops below `earmarked`, `shortfall = earmarked − newBalance`. No `goalAllocations` supplied → `400` with the same `InsufficientFreeBalanceDetails` shape as the transaction case (`freeBalance` here computed against the *pre-edit* balance, for context).
- `goalAllocations` supplied → guard is `sum(allocations) == shortfall`, exactly. **Corrected post-ship** (same v1.5.0): the guard originally allowed `sum(allocations) ≥ shortfall`, on the reasoning that covering more than strictly required should be fine. Testing showed this let a client silently deflate goal progress well beyond what the balance change actually required — e.g. a ₹1,000 shortfall "covered" by ₹1,000 from *each* of two goals, quietly turning a ₹1,000 correction into a ₹2,000 loss of earmarked money with no transaction explaining the extra ₹1,000. The guard now rejects both under- and over-allocation. Each individual amount is still bounded by that goal's own `currentProgress` via the same per-goal DECREASE guard `applyAllocations` already enforces — this fires independently of the aggregate check, e.g. a goal with only ₹5,000 earmarked can't supply ₹8,000 toward the shortfall even if the total across goals would otherwise cover it.
- If `goalAllocations` is supplied but turns out not to be needed — the corrected balance still covers `earmarked` after all, or the balance didn't change at all — the correction still succeeds, now returning a `warning` (same envelope field used elsewhere) instead of silently discarding the unused selection.
- The allocation rows attach to the `SETTLEMENT/Adjustment` transaction this correction creates, exactly like a withdrawal TRANSFER's rows attach to itself — visible the same way in that transaction's `goalAllocations` breakdown.

This closes the second half of the originally reported regression — a manual balance correction on a goal-linked account no longer leaves its goals' progress silently stale.

**Query model simplified: `mode`/`anchor` dropped, plain `from`/`to` only (same day, v1.5.0):** `GET /transactions` and `GET /analytics/cashflow-summary` both ended up on a single, explicit `from`/`to` query contract, replacing a more complex `mode`/`anchor` design that never reached the frontend. See "Period filtering — `GET /transactions` and `GET /analytics/cashflow-summary`" under §6 Transactions for the full rationale and the resulting contract.

</details>

---

## Table of Contents
1. [Application Overview](#1-application-overview)
2. [User Flows & App Modes](#2-user-flows--app-modes)
3. [Complete Entity Catalogue](#3-complete-entity-catalogue)
4. [Database Schema (SQLite)](#4-database-schema-sqlite)
5. [Java Module Structure](#5-java-module-structure)
6. [Complete API Contract](#6-complete-api-contract)
7. [Computed Metrics Reference](#7-computed-metrics-reference)
8. [Seed Data](#8-seed-data)
9. [Key Business Rules](#9-key-business-rules)
10. [Screens-to-API Mapping](#10-screens-to-api-mapping)
11. [Deferred to Post-MVP](#11-deferred-to-post-mvp)

---

## 1. Application Overview

**Moneyflow** is an offline-first personal cashflow management app for Indian users.  
Currency: INR (₹). Financial year: April 1 → March 31.  
Data is stored locally on-device in SQLite. No cloud database.  
LLM integration provides analytics and insights on demand.

**Core proposition:**
- Track cashflows in a simple manner — log what comes in and what goes out
- Add planned amounts — known future recurring payments or income
- Define realistic savings goals with timelines

**Design principle on income:** Income source diversity is not a setup question. A user with salary, freelance, and rental income simply logs three Income transactions. The data emerges from usage. The LLM analyses the pattern. No wizard required.

**Bottom navigation tabs:** Home · Cash Flow · Goals · More

---

## 2. User Flows & App Modes

### Mode 1 — First Launch (Setup)
Sequential 3-step wizard shown on dashboard after Sign Up, until all steps are complete:

1. **Setup Accounts** → Add bank/savings accounts with opening balance
2. **Add Planned Amounts** → Known recurring income/expenses (**Phase 2 — `onboardingChecklist.plannedAmountsAdded` is hardcoded `false` in MVP**)
3. **Define Savings Goals** → Named goals with target, account, timeline

The dashboard tracks completion and updates its message after each step is done.

### Mode 2 — Ongoing Daily Use
- Add Cash Flow entries (Expense / Income / Transfer)
- Review Dashboard (balance, targets, last entries)
- Monitor Goals progress
- Review and manage Planned Amounts

---

## 3. Complete Entity Catalogue

### 3.1 User
Derived from: Sign Up screen (page 4)

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key |
| `firstName` | String | Required |
| `lastName` | String | Required |
| `email` | String | Unique, used for login |
| `passwordHash` | String | BCrypt hashed, never stored plain |
| `onboardingStep` | Integer | 0=not started, 1=accounts done, 2=planned done, 3=complete |
| `createdAt` | LocalDateTime | |
| `updatedAt` | LocalDateTime | |

---

### 3.2 UserProfile
Preferences only. No income profiling. 1:1 with User.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key |
| `userId` | UUID | FK to User, unique |
| `defaultCurrency` | String | Default `INR` |
| `financialYearStartMonth` | Integer | Default 4 (April) |
| `motivationalQuotesEnabled` | Boolean | Subheader quote on dashboard |
| `createdAt` | LocalDateTime | |

---

### 3.3 Account
Derived from: Add Account screen (page 38), Accounts list (page 39), Excel Accounts sheet

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key |
| `userId` | UUID | FK to User |
| `name` | String | e.g. "MAXIS Bank", "India Bank" |
| `type` | Enum | `CASH`, `BANK`, `WALLET` |
| `currentBalance` | BigDecimal | Source of truth — updated with every transaction |
| `currency` | String | Default `INR` |
| `colorLabel` | String | Hex colour code for UI display |
| `displayOrder` | Integer | For UI ordering |
| `isActive` | Boolean | Soft delete flag |
| `createdAt` | LocalDateTime | |
| `goalLinked` | Boolean | Response-only, not a stored column — `true` when the account backs at least one active, non-completed goal (same rule as BR-12's protected-account check). Drives the piggy-bank icon on the Accounts screen. |

**Key design decision:** `currentBalance` is kept in sync in real-time as transactions are posted. It is never recomputed from history. This makes balance reads instant regardless of how many transactions exist.

**Type revision (post v1.1.0):** Original draft listed `CASH, SAVINGS, CURRENT, WALLET` — four values mixing two unrelated classification axes (where money sits, vs. bank account sub-type). Revised to three values, each answering a question a user actually recognises:
- `CASH` — physical cash, no bank involved
- `BANK` — any bank account, savings or current. The Savings/Current distinction is informational only (the app's logic never behaves differently between them — both just hold a balance), so it lives in the account `name` (e.g. "MAXIS Bank Savings") rather than as a separate enforced type.
- `WALLET` — digital prepaid wallet balance (Paytm, PhonePe, Amazon Pay). Distinct from UPI, which is a payment rail moving money between bank accounts, not a balance-holding account type — a UPI payment still debits a `BANK` account.

`CREDIT` was considered and deliberately excluded from this enum — credit cards are structurally different (billing cycle, credit limit, carry-forward balance) and belong to their own entity. See Deferred to Post-MVP below.

---

### 3.4 TransactionType (Enum)
Derived from: Excel Types sheet, narrowed to 8 active values for MVP — see §11 for deferred `CC_CREDIT`

```
FIXED_EXPENSE      → Regular, predictable (rent, EMI, insurance)
VARIABLE_EXPENSE   → Day-to-day spending (groceries, fuel, food)
INCOME             → Any money received — salary, freelance, rental, gifts
LENDING            → Money lent to someone else
BORROWING          → Money borrowed from someone
REPAYMENT          → Paying back a debt or credit card bill
SETTLEMENT         → System-generated balance correction (see below)
TRANSFER           → Moving money between own accounts or to a goal
```

**Note on INCOME:** This single type covers all income sources. Whether salary, freelance, business, or rental — it is all `INCOME`. The `notes` field and `category` provide context. No classification wizard needed.

**Note on SETTLEMENT — design rationale:** `SETTLEMENT` is never created directly by a user filling out the Add Entry form. It is always system-generated, in exactly two scenarios, both covered in §9 (BR-01, BR-02). This mirrors the **Adjustment Method** used by real banks for correcting reconciliation errors: the original record is never edited; a new entry is appended that bridges the gap to the correct balance, with the discrepancy and resolution captured in that new entry's description — never silently merged into prior history. This is why `Transaction.notes` is immutable once created for `SETTLEMENT` rows specifically (see §9, BR-11): system-generated financial records are append-only, not mutated, matching standard banking reconciliation practice.

---

### 3.5 Category
Derived from: Excel Categories sheet (34 items)

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key |
| `name` | String | e.g. "Groceries", "Rent", "Food & Beverages" |
| `icon` | String | Emoji for UI display |
| `isSystem` | Boolean | True = seeded, cannot be deleted by user |
| `isActive` | Boolean | User can hide categories |
| `isInternal` | Boolean | True = reserved for system-generated transactions only, never returned by `GET /categories`, never accepted by `POST`/`PUT /transactions` — see BR-17. Currently one row: `Opening Balance` (`cat-35`, used by BR-01). Distinct from `isSystem`/`isActive`: those describe a category a user can see and pick, just not delete or hide-toggle; `isInternal` means the user never sees or picks it at all. |
| `displayOrder` | Integer | |

---

### 3.6 Transaction
Derived from: Cash Flow screens (pages 30–36), Excel monthly sheets

**The central entity. Every money movement is a transaction.**

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key |
| `userId` | UUID | FK to User |
| `date` | LocalDate | The effective/transaction date — when the money actually moved. User-selected, defaults to today, editable to any past date at entry time. |
| `type` | TransactionType | Enum |
| `categoryId` | UUID | FK to Category |
| `accountId` | UUID | FK to Account — the source account |
| `toAccountId` | UUID | FK to Account — nullable, used for TRANSFER between accounts |
| `toGoalId` | UUID | FK to Goal — nullable, used for TRANSFER *arriving* at a goal. Never set alongside a `transaction_goal_allocations` row — see §3.8a. |
| `amount` | BigDecimal | Always stored as positive. Sign derived from type at read time. |
| `notes` | String | Description, set at creation. Editable via `PUT` for all types except `SETTLEMENT` — see BR-11. |
| `financialYear` | String | e.g. `FY24-25` — derived server-side from `date`, not user input. See "Two calendars" below. |
| `month` | Integer | 1–12, where 1=April, 12=March — derived server-side. See "Two calendars" below. |
| `calendarMonth` | Integer | Standard calendar month 1–12, derived server-side from `date` |
| `calendarYear` | Integer | Standard calendar year, derived server-side from `date` |
| `isPlanned` | Boolean | True if spawned from a PlannedAmount |
| `plannedAmountId` | UUID | FK to PlannedAmount — nullable |
| `createdAt` | LocalDateTime | System-stamped the instant the row is saved. See "Transaction date vs. entry date" below. |
| `updatedAt` | LocalDateTime | |

**Amount sign convention:** Store absolute positive value. The display sign (+/-) is derived from `type` at query time by the service layer, never stored.

**Transfer rules:** Exactly one of `toAccountId` or `toGoalId` must be set when `type = TRANSFER`. Both null = validation error. Both set = validation error.

**Immutability:** `notes` is fixed at creation for `SETTLEMENT` transactions — system-generated notes must never be altered, matching real bank reconciliation practice. For every other type, `PUT /transactions/{id}` may also correct `notes`, alongside `amount`, `category`, and `date`. `type`, `accountId`, `toAccountId`, and `toGoalId` are never editable via `PUT` regardless of transaction type — changing what a transaction fundamentally is or where it moves money means deleting it and creating a new one, not rewriting it in place. See BR-11.

**Two calendars — why `financialYear`/`month` AND `calendarMonth`/`calendarYear` both exist:**

Every transaction date can be read through two different lenses, both valid, answering different questions:

- *Regular calendar lens* (`calendarMonth`, `calendarYear`) — January is month 1, December is month 12. This is what the Cash Flow screen's "This Month" / back-forward navigation uses (Figma p30) — a user expects it to behave like every calendar app they've ever used.
- *Financial year lens* (`financialYear`, `month`) — April is month 1, March is month 12, matching how Indian household/government/corporate budgeting actually works, and how the original Excel sheet (one sheet per FY month) was structured.

Example — **14 January 2026**: regular calendar says `calendarYear=2026, calendarMonth=1`. Financial year says this date falls in FY25-26 (April 2025 → March 2026), and counting April as month 1 means January is the 10th month of that year → `financialYear="FY25-26", month=10`.

Example — **14 June 2025**: regular calendar says `calendarMonth=6`. Financial year says `financialYear="FY25-26", month=3` (April=1, May=2, June=3). Same actual month, two different numbers — this disagreement is exactly why both must be stored, not just one.

**Why stored, not computed on demand:** Both lenses are used in frequent, indexed `WHERE`-clause queries (`idx_transactions_user_fy_month` — §4) — the Cash Flow screen filters by regular month constantly, `MonthSummary` filters by financial year constantly. A database can only build a fast index over a value that physically exists on the row; it cannot index a calculation that would otherwise need to be redone per-row, per-query. Storing both once at creation, server-side only (never client-supplied — see BR-04), trades a small amount of redundant data for indexed, instant filtering on both calendars. This is the same category of deliberate denormalization as `account.currentBalance` (BR-05) — safe specifically because there is exactly one code path that ever writes these fields.

**Transaction date vs. entry date — handling late-logged transactions:**

A real, common scenario: a transaction happens Wednesday, the user forgets, and logs it Thursday evening, correctly backdating `date` to Wednesday in the Add Entry form. This is normal, expected use of the existing `date` field — no special handling needed at entry time.

`date` captures *when the money moved* (user-selected, user-owned). `createdAt` captures *when the row was physically saved* (system-stamped, immutable). These two fields together fully represent the distinction — no additional stored or computed field is needed for the MVP. A late-logged entry is simply one where `date` and `createdAt`'s calendar day differ; this is observable from the data already present on the row, and can be surfaced in a future UI feature if a genuine screen requirement for it emerges. Not built speculatively now.

---

### 3.6a TransactionGoalAllocation *(added v1.5.0)*
Derived from: the multi-goal-per-account gap discovered testing BR-07's withdrawal path.

A one-to-many child ledger recording exactly which goal(s) a withdrawal `TRANSFER` drew from, and how much from each. Exists so an account can back more than one active goal without ambiguity about which goal a given withdrawal affects — the previous approach (implicit, via a singular goal-per-account lookup) could not represent this at all.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key |
| `transactionId` | UUID | FK to Transaction, `ON DELETE CASCADE`, not updatable |
| `goalId` | UUID | FK to Goal, not updatable |
| `amount` | BigDecimal | The portion of the transaction drawn from this goal. Not updatable — a change in allocation deletes and recreates the row, never edits it in place, mirroring `SETTLEMENT`'s append-only philosophy (§3.4). |
| `createdAt` | LocalDateTime | No `updatedAt` — rows are never edited, only deleted and recreated. |

**Unique constraint:** `(transactionId, goalId)` — a transaction can allocate to a given goal at most once.

**Relationship to `to_goal_id`:** mutually exclusive on the same transaction. `to_goal_id` (§3.6) is the *arrival* tag — a TRANSFER crediting one goal directly. This table is the *withdrawal* ledger — a TRANSFER debiting one or more goals. A transaction is either arriving at a goal or withdrawing from one or more goals, never both (enforced by BR-15's "destination ≠ source" rule making the two paths structurally disjoint).

**Free balance (computed, not stored):** `account.currentBalance − Σ(currentProgress of that account's active, non-completed goals)`. See BR-19.

**Repository sums for net-savings reporting (added v1.5.0):** `TransactionGoalAllocationRepository.sumByUserIdAndDateRange`/`sumByUserIdAndCalendarMonth` total this table's `amount` column, joined back to `Transaction` for the user/period filter (the allocation row itself has no date or user column). Wrapped by `GoalAllocationService.sumWithdrawalsByDateRange`/`sumWithdrawalsByCalendarMonth`, which both Dashboard and Analytics now subtract from their gross TRANSFER-to-goal figures — see the Dashboard and Analytics sections below.

---

### 3.7 PlannedAmount
Derived from: Planned Amounts screens (pages 21–25)

Known future recurring payments or income. Examples: House Rent (monthly), Netflix, Bike Insurance, Salary.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key |
| `userId` | UUID | FK to User |
| `name` | String | e.g. "House Rent", "Netflix", "Salary" |
| `direction` | Enum | `EXPENSE` or `INCOME` |
| `amount` | BigDecimal | |
| `categoryId` | UUID | FK to Category |
| `frequency` | Enum | `DAILY`, `WEEKLY`, `FORTNIGHTLY`, `MONTHLY`, `QUARTERLY`, `YEARLY`, `ONE_TIME` |
| `startDate` | LocalDate | |
| `endDate` | LocalDate | Nullable — null means "Never" |
| `nextDueDate` | LocalDate | Computed and stored — drives "in X days" display |
| `isActive` | Boolean | |
| `icon` | String | Emoji for display |
| `createdAt` | LocalDateTime | |

**Key insight:** The "in X days" countdown in the list is `nextDueDate - today`. When > 30 days away, show the actual date (e.g. "5 August"). After each occurrence passes, `nextDueDate` advances by the `frequency` interval.

---

### 3.8 Goal
Derived from: Goals screens (pages 26–29), Excel Goals sheet

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key |
| `userId` | UUID | FK to User |
| `name` | String | e.g. "Vacation Fund", "Emergency Fund", "Downpayment" |
| `targetAmount` | BigDecimal | Total amount to reach. Caps how far `currentProgress` may rise — see BR-19. |
| `accountId` | UUID | FK to Account where goal savings are held. Any account type allowed — see BR-12 for protection behaviour. |
| `startDate` | LocalDate | |
| `endDate` | LocalDate | Drives monthly savings calculation |
| `currentProgress` | BigDecimal | Moves in both directions — increases on arrival (BR-07) or an explicit allocation increase, decreases on withdrawal (BR-07/BR-19). Bounded between 0 and `targetAmount`. |
| `monthlySavingsRequired` | BigDecimal | Computed: (targetAmount - currentProgress) / monthsRemaining. Nullable — division by zero avoided when endDate passes. |
| `displayOrder` | Integer | User can drag to reorder |
| `status` | Enum | `IN_PROGRESS`, `UPCOMING`, `COMPLETED`, `PAUSED` |
| `isActive` | Boolean | Soft delete |
| `createdAt` | LocalDateTime | |
| `updatedAt` | LocalDateTime | Added: goal fields (targetAmount, endDate, status) are editable — audit timestamp warranted. |

**Computed at read time (not stored):**
- `progressPercentage` = (currentProgress / targetAmount) × 100
- `monthsRemaining` = months between today and endDate
- `savedThisMonth` = sum of TRANSFER transactions to this goal in current calendar month

**An account may back more than one active goal (v1.5.0):** the original singular per-account goal lookup used for withdrawal crediting has been replaced by the allocation ledger (§3.6a) precisely to support this — an account like "Axis Bank" can simultaneously back an Emergency Fund and a Vacation goal, each tracked independently.

---

### 3.9 FinancialYear
Derived from: Excel FY structure (April–March)

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key |
| `userId` | UUID | FK to User |
| `label` | String | e.g. `FY24-25` |
| `startDate` | LocalDate | Always April 1 |
| `endDate` | LocalDate | Always March 31 of next year |
| `isActive` | Boolean | Current FY flag |

Created automatically when user's first transaction falls into a new financial year.

---

### 3.10 MonthSummary
Derived from: Excel monthly sheet headers, Cash Flow summary cards

The 10 key metrics per month, computed from transactions and cached for performance.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key |
| `userId` | UUID | FK to User |
| `financialYear` | String | e.g. `FY24-25` |
| `month` | Integer | 1–12, April-based |
| `calendarMonth` | Integer | |
| `calendarYear` | Integer | |
| `totalIncome` | BigDecimal | Sum of INCOME transactions |
| `totalExpense` | BigDecimal | Sum of expense-type transactions (absolute) |
| `balance` | BigDecimal | totalIncome − totalExpense |
| `balancePercentage` | BigDecimal | balance / totalIncome × 100 — drives the ring on dashboard |
| `totalDebt` | BigDecimal | Outstanding borrowings + CC balances |
| `totalSavings` | BigDecimal | Sum of TRANSFER transactions to savings/goals |
| `savingsRate` | BigDecimal | totalSavings / totalIncome × 100 |
| `debtIncomeRatio` | BigDecimal | totalDebt / totalIncome × 100 |
| `dailyExpenseLimit` | BigDecimal | balance / remaining days in month |
| `totalCreditBill` | BigDecimal | **Deferred** — depends on `CC_CREDIT` type, see §11. Field reserved, value stays 0 in MVP. |
| `totalOutstanding` | BigDecimal | Carried forward from previous month |
| `isDirty` | Boolean | True = needs recomputation after a transaction change |
| `lastComputedAt` | LocalDateTime | |

**Unique constraint:** (userId, financialYear, month)

---

### 3.11 Invite

Derived from: invite-only onboarding decision (v1.4.0) — replaces open self-registration.

An `Invite` is a short-lived state machine, not a permanent record like the entities above: it exists only to carry a new person from "an admin typed their email" to "a verified `User` row exists," then keeps its shell around (with secrets cleared) as a completed audit trail.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key |
| `email` | String | The address the admin invited |
| `token` | String | Unique, unguessable (32 random bytes, URL-safe base64) — the invite link's identity, not a JWT |
| `status` | Enum | `PENDING` → `AWAITING_OTP` → `COMPLETED`, see below |
| `invitedBy` | UUID | FK to User (the admin) |
| `expiresAt` | LocalDateTime | 7 days from creation |
| `pendingFirstName` | String | Staged, not on a real `User` until OTP verifies |
| `pendingLastName` | String | Staged, not on a real `User` until OTP verifies |
| `pendingPasswordHash` | String | Staged, BCrypt hashed; cleared once the `User` is created |
| `otpCodeHash` | String | BCrypt hashed 6-digit code; cleared once the `User` is created |
| `otpExpiresAt` | LocalDateTime | 10 minutes from issue |
| `otpAttempts` | Integer | Resets to 0 on each new code; capped at 5 before a resend is required |
| `createdAt` | LocalDateTime | |
| `updatedAt` | LocalDateTime | Doubles as the resend-cooldown anchor — see BR-18 |

**Status transitions:**
- `PENDING` — invite created and emailed, nobody has clicked through yet.
- `AWAITING_OTP` — the invited person submitted their name/password; a code has been emailed and is awaiting verification.
- `COMPLETED` — OTP verified, `User` created, staged secrets cleared. Terminal.

See BR-18 for the full onboarding/OTP business rules.

---

## 4. Database Schema (SQLite)

### SQLite type notes:
- No native UUID → store as `TEXT`
- No native Boolean → store as `INTEGER` (0=false, 1=true)
- No native Enum → store as `TEXT` with `CHECK` constraints
- No native BigDecimal → store as `REAL` for amounts
- Dates → `TEXT` in `YYYY-MM-DD` format
- Timestamps → `TEXT` in `YYYY-MM-DDTHH:MM:SS` format

```sql
-- ============================================================
-- USERS
-- ============================================================
CREATE TABLE users (
    id TEXT PRIMARY KEY,
    first_name TEXT NOT NULL,
    last_name TEXT NOT NULL,
    email TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'MEMBER',
    onboarding_step INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- ============================================================
-- INVITES (invite-only onboarding — see spec §3.11, BR-18)
-- ============================================================
CREATE TABLE invites (
    id                     TEXT      NOT NULL PRIMARY KEY,
    email                  TEXT      NOT NULL,
    token                  TEXT      NOT NULL UNIQUE,
    status                 TEXT      NOT NULL DEFAULT 'PENDING'
                               CHECK(status IN ('PENDING','AWAITING_OTP','COMPLETED')),
    invited_by             TEXT      NOT NULL REFERENCES users(id),
    expires_at             TIMESTAMP NOT NULL,
    pending_first_name     TEXT,
    pending_last_name      TEXT,
    pending_password_hash  TEXT,
    otp_code_hash          TEXT,
    otp_expires_at         TIMESTAMP,
    otp_attempts           INTEGER   NOT NULL DEFAULT 0,
    created_at             TIMESTAMP NOT NULL,
    updated_at             TIMESTAMP NOT NULL
);

-- ============================================================
-- USER PROFILES (preferences only)
-- ============================================================
CREATE TABLE user_profiles (
    id TEXT PRIMARY KEY,
    user_id TEXT UNIQUE NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    default_currency TEXT NOT NULL DEFAULT 'INR',
    financial_year_start_month INTEGER NOT NULL DEFAULT 4,
    motivational_quotes_enabled INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL
);

-- ============================================================
-- ACCOUNTS
-- ============================================================
CREATE TABLE accounts (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    type TEXT NOT NULL CHECK(type IN ('CASH','BANK','WALLET')),
    current_balance REAL NOT NULL DEFAULT 0,
    currency TEXT NOT NULL DEFAULT 'INR',
    color_label TEXT,
    display_order INTEGER NOT NULL DEFAULT 0,
    is_active INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE(user_id, name)
);

CREATE INDEX idx_accounts_user ON accounts(user_id);

-- ============================================================
-- CATEGORIES (seeded, user cannot delete system ones)
-- ============================================================
CREATE TABLE categories (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    icon TEXT,
    is_system INTEGER NOT NULL DEFAULT 0,
    is_active INTEGER NOT NULL DEFAULT 1,
    is_internal INTEGER NOT NULL DEFAULT 0,
    display_order INTEGER NOT NULL DEFAULT 0
);

-- ============================================================
-- PLANNED AMOUNTS
-- ============================================================
CREATE TABLE planned_amounts (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    direction TEXT NOT NULL CHECK(direction IN ('EXPENSE','INCOME')),
    amount REAL NOT NULL CHECK(amount > 0),
    category_id TEXT NOT NULL REFERENCES categories(id),
    frequency TEXT NOT NULL CHECK(frequency IN (
        'DAILY','WEEKLY','FORTNIGHTLY','MONTHLY',
        'QUARTERLY','YEARLY','ONE_TIME'
    )),
    start_date TEXT NOT NULL,
    end_date TEXT,
    next_due_date TEXT NOT NULL,
    is_active INTEGER NOT NULL DEFAULT 1,
    icon TEXT,
    created_at TEXT NOT NULL
);

-- ============================================================
-- GOALS
-- ============================================================
CREATE TABLE goals (
    id                       TEXT      NOT NULL PRIMARY KEY,
    user_id                  TEXT      NOT NULL REFERENCES users(id),
    name                     TEXT      NOT NULL,
    target_amount            REAL      NOT NULL CHECK(target_amount > 0),
    account_id               TEXT      NOT NULL REFERENCES accounts(id),
    start_date               TEXT      NOT NULL,
    end_date                 TEXT      NOT NULL,
    current_progress         REAL      NOT NULL DEFAULT 0,
    monthly_savings_required REAL,
    display_order            INTEGER   NOT NULL DEFAULT 0,
    status                   TEXT      NOT NULL DEFAULT 'IN_PROGRESS' CHECK(status IN (
                                 'IN_PROGRESS','UPCOMING','COMPLETED','PAUSED'
                             )),
    is_active                INTEGER   NOT NULL DEFAULT 1,
    created_at               TIMESTAMP NOT NULL,
    updated_at               TIMESTAMP NOT NULL,
    UNIQUE(user_id, name)
);

CREATE INDEX idx_goals_user_status ON goals(user_id, status) WHERE is_active = 1;

-- ============================================================
-- TRANSACTIONS (the central table)
-- ============================================================
CREATE TABLE transactions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    date TEXT NOT NULL,
    type TEXT NOT NULL CHECK(type IN (
        'FIXED_EXPENSE','VARIABLE_EXPENSE','INCOME',
        'LENDING','BORROWING','REPAYMENT','SETTLEMENT','TRANSFER'
    )),
    category_id TEXT NOT NULL REFERENCES categories(id),
    account_id TEXT NOT NULL REFERENCES accounts(id),
    to_account_id TEXT REFERENCES accounts(id),
    to_goal_id TEXT REFERENCES goals(id),
    amount REAL NOT NULL CHECK(amount > 0),
    notes TEXT,
    financial_year TEXT NOT NULL,
    month INTEGER NOT NULL CHECK(month BETWEEN 1 AND 12),
    calendar_month INTEGER NOT NULL,
    calendar_year INTEGER NOT NULL,
    is_planned INTEGER NOT NULL DEFAULT 0,
    planned_amount_id TEXT REFERENCES planned_amounts(id),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    -- Transfer must always record a destination account. to_goal_id is optional
    -- extra metadata riding alongside it (set when that account was reached via
    -- a goal), not an alternative to it — see BR-05 and BR-07.
    CHECK(type != 'TRANSFER' OR to_account_id IS NOT NULL)
);

-- ============================================================
-- TRANSACTION GOAL ALLOCATIONS (withdrawal ledger — v1.5.0, BR-19)
-- ============================================================
CREATE TABLE transaction_goal_allocations
(
    id             TEXT      NOT NULL PRIMARY KEY,
    transaction_id TEXT      NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    goal_id        TEXT      NOT NULL REFERENCES goals (id),
    amount         REAL      NOT NULL CHECK (amount > 0),
    created_at     TIMESTAMP NOT NULL,
    UNIQUE (transaction_id, goal_id)
);

CREATE INDEX idx_tga_transaction ON transaction_goal_allocations (transaction_id);
CREATE INDEX idx_tga_goal ON transaction_goal_allocations (goal_id);

-- ============================================================
-- FINANCIAL YEARS
-- ============================================================
CREATE TABLE financial_years (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    label TEXT NOT NULL,
    start_date TEXT NOT NULL,
    end_date TEXT NOT NULL,
    is_active INTEGER NOT NULL DEFAULT 1,
    UNIQUE(user_id, label)
);

-- ============================================================
-- MONTH SUMMARIES (computed cache)
-- ============================================================
CREATE TABLE month_summaries (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    financial_year TEXT NOT NULL,
    month INTEGER NOT NULL,
    calendar_month INTEGER NOT NULL,
    calendar_year INTEGER NOT NULL,
    total_income REAL NOT NULL DEFAULT 0,
    total_expense REAL NOT NULL DEFAULT 0,
    balance REAL NOT NULL DEFAULT 0,
    balance_percentage REAL NOT NULL DEFAULT 0,
    total_debt REAL NOT NULL DEFAULT 0,
    total_savings REAL NOT NULL DEFAULT 0,
    savings_rate REAL NOT NULL DEFAULT 0,
    debt_income_ratio REAL NOT NULL DEFAULT 0,
    daily_expense_limit REAL NOT NULL DEFAULT 0,
    total_credit_bill REAL NOT NULL DEFAULT 0,
    total_outstanding REAL NOT NULL DEFAULT 0,
    is_dirty INTEGER NOT NULL DEFAULT 1,
    last_computed_at TEXT,
    UNIQUE(user_id, financial_year, month)
);

-- ============================================================
-- PERFORMANCE INDEXES
-- ============================================================
CREATE INDEX idx_transactions_user_date
    ON transactions(user_id, date DESC);

CREATE INDEX idx_transactions_user_calendar
    ON transactions(user_id, calendar_year, calendar_month);

CREATE INDEX idx_transactions_user_fy_month
    ON transactions(user_id, financial_year, month);

CREATE INDEX idx_transactions_account
    ON transactions(account_id);

CREATE INDEX idx_transactions_to_goal
    ON transactions(to_goal_id)
    WHERE to_goal_id IS NOT NULL;

CREATE INDEX idx_planned_next_due
    ON planned_amounts(user_id, next_due_date)
    WHERE is_active = 1;

CREATE INDEX idx_goals_user_status
    ON goals(user_id, status)
    WHERE is_active = 1;

CREATE INDEX idx_month_summaries_lookup
    ON month_summaries(user_id, financial_year, month);
```

---

## 5. Java Module Structure

```
com.moneyflow
├── auth/
│   ├── AuthController.java           signin only — see invite/ for onboarding
│   ├── AuthService.java
│   ├── AdminBootstrapRunner.java     Seeds the first ADMIN from env vars at startup
│   ├── SignInRequest.java
│   ├── AuthResponse.java
│   ├── UserRepository.java
│   └── User.java                     @Entity
│
├── invite/                           Invite-only onboarding (replaces open signup, v1.4.0)
│   ├── AdminInviteController.java    POST /api/admin/invites — JWT + ADMIN only
│   ├── InviteSignupController.java   POST /auth/invites/{token}/** — public
│   ├── InviteService.java            Token generation, OTP issue/verify/resend
│   ├── InviteRepository.java
│   ├── CreateInviteRequest.java
│   ├── InviteSignupRequest.java
│   ├── VerifyOtpRequest.java
│   ├── InviteResponse.java
│   ├── InviteResult.java
│   ├── InviteStatus.java             Enum: PENDING, AWAITING_OTP, COMPLETED
│   └── Invite.java                   @Entity
│
├── account/
│   ├── AccountController.java
│   ├── AccountService.java
│   ├── AccountRepository.java
│   ├── dto/
│   │   ├── CreateAccountRequest.java
│   │   └── AccountResponse.java
│   └── Account.java                  @Entity
│
├── transaction/
│   ├── TransactionController.java
│   ├── TransactionService.java              Balance updates, FY derivation, allocation resolution (v1.5.0)
│   ├── TransactionRepository.java
│   ├── GoalAllocationService.java           Withdrawal ledger read/write, free-balance math (v1.5.0)
│   ├── GoalAllocationDirection.java         Enum: INCREASE, DECREASE (v1.5.0)
│   ├── GoalAllocationItem.java               Record: goalId, amount — shared by create/update requests (v1.5.0)
│   ├── TransactionGoalAllocationRepository.java (v1.5.0)
│   ├── InsufficientFreeBalanceDetails.java  Structured 400 payload (v1.5.0)
│   ├── dto/
│   │   ├── CreateTransactionRequest.java    +goalAllocations (v1.5.0)
│   │   └── TransactionResponse.java         +goalAllocations breakdown (v1.5.0)
│   ├── Transaction.java              @Entity
│   └── TransactionGoalAllocation.java @Entity (v1.5.0)
│
├── planned/                          ← PHASE 2 — NOT built in MVP
│   ├── PlannedAmountController.java
│   ├── PlannedAmountService.java     Due date calculations
│   ├── PlannedAmountRepository.java
│   ├── dto/
│   │   ├── CreatePlannedAmountRequest.java
│   │   └── PlannedAmountResponse.java
│   └── PlannedAmount.java            @Entity
│
├── goal/
│   ├── GoalController.java
│   ├── GoalService.java              Progress tracking, reorder
│   ├── GoalRepository.java           +findByAccountIdAndActiveTrueAndStatusNotOrderByDisplayOrderAsc (v1.5.0, plural — see §3.8)
│   ├── dto/
│   │   ├── CreateGoalRequest.java
│   │   └── GoalResponse.java
│   └── Goal.java                     @Entity
│
├── analytics/
│   ├── AnalyticsController.java
│   ├── AnalyticsService.java         Aggregations, dirty-flag recompute
│   ├── MonthSummaryRepository.java
│   └── MonthSummary.java             @Entity
│
├── dashboard/
│   ├── DashboardController.java
│   ├── DashboardService.java         Assembles home screen payload
│   └── dto/
│       └── DashboardResponse.java
│
├── category/
│   ├── CategoryController.java
│   ├── CategoryRepository.java
│   └── Category.java                 @Entity
│
├── profile/
│   ├── ProfileController.java
│   ├── ProfileService.java
│   ├── dto/
│   │   └── UpdateProfileRequest.java
│   └── UserProfile.java              @Entity
│
├── llm/
│   ├── LLMController.java
│   ├── LLMService.java               Outbound HTTP to LLM API
│   └── dto/
│       ├── InsightRequest.java
│       └── InsightResponse.java
│
└── shared/
    ├── security/
    │   ├── ProdSecurityConfig.java / DevSecurityConfig.java
    │   ├── JwtUtil.java
    │   ├── JwtAuthFilter.java
    │   ├── JwtAuthEntryPoint.java
    │   ├── CorsConfig.java
    │   └── BaseController.java
    ├── email/
    │   └── EmailService.java         Invite + OTP emails (Brevo SMTP)
    ├── exception/
    │   ├── GlobalExceptionHandler.java
    │   └── ApiException.java         +details field (v1.5.0)
    ├── dto/
    │   └── ApiResponse.java          Standard envelope for all responses; ApiError +details (v1.5.0)
    └── util/
        └── FinancialYearUtil.java    Date → FY label, month number
```

---

## 6. Complete API Contract

### Standard Response Envelope

Every API response uses this wrapper — success or failure.

**Success:**
```json
{
  "success": true,
  "data": { },
  "message": "Operation successful",
  "timestamp": "2024-06-14T09:41:00"
}
```

**Error:**
```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Amount must be greater than zero",
    "field": "amount"
  },
  "timestamp": "2024-06-14T09:41:00"
}
```

**Error, with structured details (v1.5.0 — see BR-19):**
```json
{
  "success": false,
  "error": {
    "code": "BAD_REQUEST",
    "message": "This amount exceeds the account's free balance. Choose which goal(s) to draw the rest from.",
    "details": {
      "freeBalance": 93000,
      "shortfall": 107000,
      "availableGoals": [
        { "goalId": "uuid", "goalName": "Emergency Fund", "currentProgress": 20000 },
        { "goalId": "uuid", "goalName": "Vacation", "currentProgress": 35000 }
      ]
    }
  },
  "timestamp": "2024-06-14T09:41:00"
}
```
`details` is omitted (`@JsonInclude NON_NULL`, on both the outer error and the nested payload) on every other error — it exists only for this specific insufficient-free-balance case, so the client can render the allocation picker directly from the error rather than a second round trip.

All endpoints require `Authorization: Bearer {jwt}` except `/auth/**`.

---

### Auth

Self-registration is invite-only — there is no public "create an account" endpoint. A new `User` only ever comes into existence via the **Invites** flow below (or, for the very first account, `AdminBootstrapRunner` at startup). `/auth/**` stays fully `permitAll` even though it now covers the invite-acceptance endpoints too, since every step of that flow happens before the person has a JWT to present.

#### POST /auth/signin
```json
{ "email": "nishant@example.com", "password": "SecurePass123!" }
```
**Response 200:** same shape as the invite `verify-otp` response below
**401:** `INVALID_CREDENTIALS` — returned identically whether the email doesn't exist or the password is wrong. This is deliberate: a login endpoint that distinguishes "no such user" from "wrong password" via status code or message lets an anonymous caller enumerate registered emails. Both failure modes must be indistinguishable to the caller.

---

### Invites

How a new `User` actually gets created. Three steps: an admin vouches for an email, the invited person proves their name/password intent, then proves they own the inbox via a 6-digit code. A read-only lookup sits alongside these so the frontend can confirm the invited email before any of that starts. See BR-18 for the full rule set.

#### POST /api/admin/invites — requires JWT + `ADMIN` role
```json
{ "email": "newmember@example.com" }
```
**Response 201:** the created invite (token included so it can be resent manually if the email bounces)
```json
{
  "data": {
    "id": "uuid",
    "email": "newmember@example.com",
    "token": "opaque-url-safe-token",
    "status": "PENDING",
    "expiresAt": "2026-09-12T10:00:00"
  }
}
```
An invite-email is sent immediately. If the workspace is already at or past `app.max-users`, the invite is still created (response carries a `warning` field) — the hard limit is enforced later, at OTP verification, not here (BR-18).

---

#### GET /auth/invites/{token} — public
Lets the frontend show "you're registering as `{email}`" before the person types anything, and lets it distinguish an already-used or expired invite from a fresh one without guessing from a generic error.
**Response 200:**
```json
{
  "data": {
    "id": "uuid",
    "email": "newmember@example.com",
    "token": "opaque-url-safe-token",
    "status": "PENDING",
    "expiresAt": "2026-09-12T10:00:00"
  }
}
```
Same response shape as invite creation above — deliberately reuses `InviteResponse` rather than a second DTO. Safe to leave unauthenticated for the same reason `signup`/`verify-otp`/`resend-otp` are: the token itself (32 random bytes) is the access control, and whoever has it already received it via an email addressed to them — this endpoint reveals nothing they don't already implicitly know.
**400:** invite expired (same as the other invite endpoints — see BR-18)
**404:** token doesn't exist

---

#### POST /auth/invites/{token}/signup — public
The invited person's first step after opening the emailed link — stages their details and triggers the OTP email. Does **not** create a `User` yet.
```json
{
  "firstName": "Nishant",
  "lastName": "Kumar",
  "password": "SecurePass123!",
  "confirmPassword": "SecurePass123!"
}
```
**Response 200:** `{ "message": "Verification code sent to your email" }` — no token, no user object, because neither exists yet.

---

#### POST /auth/invites/{token}/verify-otp — public
```json
{ "code": "482913" }
```
**Response 200:** the `User` is created on success — same shape as `/auth/signin`
```json
{
  "data": {
    "token": "eyJhbGci...",
    "user": { "id": "uuid", "firstName": "Nishant", "lastName": "Kumar", "email": "newmember@example.com", "onboardingStep": 0 }
  }
}
```
**401:** incorrect code (attempt counted toward the 5-try cap)
**400:** code expired, too many incorrect attempts, or invite expired
**409:** the workspace's `app.max-users` cap has been reached since the invite was created

---

#### POST /auth/invites/{token}/resend-otp — public
No body. Issues a fresh code and resets the attempt counter. Rate-limited to one call per 60 seconds per invite (BR-18).
**Response 200:** `{ "message": "A new code has been sent to your email" }`
**400:** invite not awaiting a code, invite expired, or still inside the cooldown window

---

### Accounts

#### POST /accounts
```json
{
  "name": "MAXIS Bank",
  "type": "CASH",
  "currentBalance": 35000.00,
  "currency": "INR",
  "colorLabel": "#E8A04D"
}
```
**Response 201:** Full account object  
**Side effect:** If `currentBalance > 0`, auto-creates a `SETTLEMENT/Adjustment` transaction. This produces the "Current Balance / Adjustment" entries visible in the Figma Cash Flow list.  
**Side effect:** Increments `user.onboardingStep` to 1 if currently 0.

---

#### GET /accounts
**Query params:** `?type=BANK` · `?type=CASH` · `?type=WALLET` (optional — returns all types when absent)

Useful for filtering account dropdowns in various UI contexts (e.g. show only BANK accounts in a particular form).

```json
{
  "data": {
    "accounts": [
      { "id": "uuid", "name": "MAXIS Bank", "type": "CASH", "currentBalance": 35000.00, "colorLabel": "#E8A04D", "goalLinked": false },
      { "id": "uuid", "name": "India Bank", "type": "BANK", "currentBalance": 3457.55, "colorLabel": "#4A90D9", "goalLinked": true }
    ],
    "totalBalance": 38457.55
  }
}
```

`goalLinked` is resolved per request, not stored — for the list endpoint, every active goal for the user is fetched once and checked by account ID in memory, rather than one existence query per account, to avoid an N+1 as the account list grows.

#### GET /accounts/{id} — same shape as a single entry above, including `goalLinked`.

#### PUT /accounts/{id} — name, type, colorLabel, currentBalance (triggers BR-02's auto `SETTLEMENT/Adjustment`).

**Downward correction on a goal-linked account (v1.5.0 — see BR-20):**
```json
{
  "currentBalance": 20000,
  "goalAllocations": [
    { "goalId": "uuid-emergency-fund", "amount": 10000 },
    { "goalId": "uuid-vacation", "amount": 15000 }
  ]
}
```
`goalAllocations` is optional and only meaningful when the new `currentBalance` would drop below the account's total earmarked goal progress — omitted otherwise. If it's needed and omitted, `400` with the same structured `details` payload shown in §6's response envelope. Amounts must sum to *exactly* the shortfall (not just cover it), each must be strictly positive, and a `goalId` can't repeat within the list — all three are rejected with `400`. Supplying it when it isn't actually needed doesn't error; the update still succeeds, with a `warning` in the response explaining that the selection wasn't used. See BR-20 for the full guard.

#### DELETE /accounts/{id} — soft delete. Reject if account has transactions.

---

### Transactions

**Period filtering — `GET /transactions` and `GET /analytics/cashflow-summary` (v1.5.0):** both endpoints share the same period-filtering contract, documented once here rather than twice below.

`GET /analytics/cashflow-summary` originally planned a three-mode query surface — `MONTHLY`/`WEEKLY` resolved server-side from a single `anchor` date, plus `CUSTOM` for an explicit `from`/`to`. A separate gap then surfaced: `GET /transactions` had no way to filter by week at all, only `calendarYear`/`calendarMonth` or `financialYear`/`financialMonth`. Designing the same mode/anchor model for transactions is what exposed the problem — `MONTHLY`/`WEEKLY` mode-resolution wasn't earning its place. Unlike `calendarYear`/`calendarMonth` or `financialYear`/`financialMonth` (real FY-math and skip-to-nearest-period navigation live behind those), a week or month boundary is a one-line client-side computation with no business rule attached. Keeping it server-side meant maintaining "what defines a period" in two places (`AnalyticsService` and a near-identical planned copy for transactions) that would have to agree forever, and — worse — under the mode/anchor model neither endpoint could offer smart prev/next navigation for `WEEKLY` the way `calendarYear`/`calendarMonth` already does, so the client had to drive period-shifting itself regardless. Once the client's already doing that arithmetic, having the backend also resolve `MONTHLY`/`WEEKLY` from an anchor added a second, subtly different definition of "period" with no real benefit.

Collapsed to a single, explicit `from`/`to` on both endpoints — both required, validated `from <= to`, no `mode` or `anchor` concept anywhere. Period shape (this week, this month, a custom range) is now entirely frontend-owned; the backend only ever answers "give me this exact date range." `GET /transactions` keeps `calendarYear`/`calendarMonth` and `financialYear`/`financialMonth` exactly as they were — those still carry real backend logic a client shouldn't reimplement — and a request combining `from`/`to` with either legacy pair on `GET /transactions` is rejected with `400` rather than silently picking one, since the schemes are ambiguous together, not additive (`GET /analytics/cashflow-summary` never had the legacy pair, so no such conflict is possible there). `TransactionSpecifications` gained `inDateRange(from, to)`, an inclusive-both-ends date filter, alongside the existing calendar/FY specifications. The idea of also embedding these KPIs directly inside `GET /transactions`'s response (so a single call returns both the list and its aggregate) was raised and deliberately shelved during this same investigation — parked for if/when a dedicated performance-metrics feature needs both the rows and their aggregate in one round trip.

---

#### POST /transactions
Three request shapes depending on the tab selected in the Add Entry form.

**Expense:**
```json
{
  "type": "VARIABLE_EXPENSE",
  "date": "2024-06-14",
  "amount": 919.19,
  "categoryId": "cat-12",
  "accountId": "uuid-maxis",
  "notes": "Vijetha"
}
```

**Income:**
```json
{
  "type": "INCOME",
  "date": "2024-06-14",
  "amount": 50000.00,
  "categoryId": "cat-20",
  "accountId": "uuid-maxis",
  "notes": "Salary"
}
```

**Transfer to account:**
```json
{
  "type": "TRANSFER",
  "date": "2024-06-14",
  "amount": 5000.00,
  "categoryId": "cat-01",
  "accountId": "uuid-maxis",
  "toAccountId": "uuid-india-bank",
  "notes": "Monthly transfer"
}
```

**Transfer to goal:**
```json
{
  "type": "TRANSFER",
  "date": "2024-06-14",
  "amount": 10000.00,
  "categoryId": "cat-27",
  "accountId": "uuid-maxis",
  "toGoalId": "uuid-emergency-fund",
  "notes": "Emergency Fund"
}
```

**Transfer withdrawing from a goal-linked account, exceeding free balance (v1.5.0 — see BR-19):**
```json
{
  "type": "TRANSFER",
  "date": "2026-09-14",
  "amount": 15000.00,
  "categoryId": "cat-01",
  "accountId": "uuid-axis-bank",
  "toAccountId": "uuid-backup",
  "goalAllocations": [
    { "goalId": "uuid-emergency-fund", "amount": 10000.00 },
    { "goalId": "uuid-vacation", "amount": 5000.00 }
  ]
}
```
`goalAllocations` is optional and only meaningful for a TRANSFER whose source account is goal-linked and whose destination isn't a goal (`toGoalId` null) — i.e. the withdrawal path. Omitted or `[]` means "fund entirely from free balance"; if the amount exceeds free balance in that case, `400` with the structured `details` payload shown above rather than accepting the request. Every `amount` in the list must be strictly positive and every `goalId` unique within it — both rejected with `400` — the same two item-level guards `PUT /accounts/{id}`'s `goalAllocations` enforces, since both endpoints validate through the same shared type and method.

**Response 201:** Full transaction object with derived fields. May include optional `warning` field (see BR-13):
```json
{
  "data": {
    "id": "uuid",
    "date": "2024-06-14",
    "type": "VARIABLE_EXPENSE",
    "category": { "id": "cat-12", "name": "Groceries", "icon": "🛒" },
    "account": { "id": "uuid", "name": "MAXIS Bank", "colorLabel": "#E8A04D" },
    "amount": 919.19,
    "displayAmount": "-₹919.19",
    "notes": "Vijetha",
    "financialYear": "FY24-25",
    "month": 3,
    "planned": false,
    "plannedAmountId": null,
    "goalAllocations": []
  },
  "warning": "This transaction is dated before your account was set up (2026-08-08). Your opening balance reflects your balance as of setup date — consider updating it if needed."
}
```

`warning` is absent (`@JsonInclude NON_NULL`) on normal transactions. Only appears when BR-13 condition is met. Frontend shows it as a toast notification — once per account per session, not on every backdated transaction.

`goalAllocations` (v1.5.0) is always present, `[]` when nothing was drawn from a goal — see BR-19.

**Side effects on every POST:**
1. Update `account.currentBalance` (debit source, credit destination for transfers)
2. Update `goal.currentProgress` if `toGoalId` set (BR-07), or via the allocation ledger for a withdrawal (BR-19)
3. Set `month_summaries.is_dirty = true` for the affected month (BR-06)
4. Auto-advance `planned_amounts.next_due_date` if `is_planned = true` (Phase 2)

---

#### GET /transactions
Powers the Cash Flow list with date grouping. Pageable — defaults to `size=100`, sorted `date,desc`, when the caller supplies neither.

**Query params:**
```
calendarYear=2026&calendarMonth=8       filter by calendar month/year
financialYear=FY26-27&financialMonth=5  filter by financial year/month (1=April..12=March)
from=2026-07-27&to=2026-08-02           filter by an explicit inclusive date range (added v1.5.0)
flowType=INCOME|EXPENSE                 filter by cash-flow direction, independent of the above
page=0&size=100                         0-indexed page, size defaults to 100
sort=date,desc                          optional override of the default sort
```

`sort` follows Spring Data's standard `property,direction` convention — direction is embedded in the same param, not a separate `direction` param (a bare `direction=DESC` alongside `sort=date` is silently ignored; Spring falls back to ascending). Repeat `sort` to order by more than one field, each with its own direction, e.g. `sort=type,asc&sort=date,desc`. Defaults to `date,desc` when omitted.

Exactly one filtering scheme may be used per request — `calendarYear`/`calendarMonth`, `financialYear`/`financialMonth`, or `from`/`to` — never two at once (`400` if you do). None supplied means an unfiltered listing across all of the user's transactions — still paginated the same way. `flowType` composes with any one period filter (or with none) since it's an orthogonal axis, not a fourth alternative alongside the period schemes.

**`from`/`to` (added v1.5.0):** a general-purpose, inclusive-both-ends date-range filter, added alongside — not in place of — the two period pairs above. `calendarYear`/`calendarMonth` and `financialYear`/`financialMonth` stay because they carry real backend logic a client shouldn't have to reimplement (FY derivation, the smart-skip `previousPeriod`/`nextPeriod` navigation below); `from`/`to` exists for everything else — a week, a custom range, whatever the UI needs next — where the backend has no business rule to contribute beyond filtering by the exact dates it's given. Both `from` and `to` must be supplied together (`400` if only one is present), and `from` must not be after `to` (`400` otherwise). See "Period filtering — `GET /transactions` and `GET /analytics/cashflow-summary`" just above (start of this Transactions section) for why this replaced an earlier, more complex `mode`/`anchor` design that would have applied to this endpoint too.

`flowType` is a grouping over `TransactionType`, not a raw type value — `INCOME` maps to the single `INCOME` type; `EXPENSE` maps to `FIXED_EXPENSE`, `VARIABLE_EXPENSE`, `LENDING`, `BORROWING`, `REPAYMENT`. `TRANSFER` and `SETTLEMENT` are deliberately excluded from both — they're money movement and balance corrections, not real income or spending, matching how Dashboard already buckets `TRANSFER` separately as "savings." A transaction of either excluded type simply won't appear when `flowType` is set, and only shows up in the unfiltered listing.

`previousPeriod`/`nextPeriod` respect whichever `flowType` is active — the resolved target is always a period that has at least one transaction matching the current `flowType`, so the frontend's arrow never lands on an empty filtered screen. Neither field is ever populated for a `from`/`to` request (see below) — there's no adjacent-period concept for an arbitrary range; the client already knows how to compute its own next range for navigation.

**Response 200:**
```json
{
  "data": {
    "transactions": {
      "content": [ ... ],
      "page": 0,
      "size": 100,
      "totalElements": 47,
      "totalPages": 1,
      "hasNext": false
    },
    "previousPeriod": { "calendarYear": 2026, "calendarMonth": 6 },
    "nextPeriod": null
  }
}
```

`previousPeriod`/`nextPeriod` are present only when `calendarYear`/`calendarMonth` or `financialYear`/`financialMonth` are supplied — absent entirely (not `null`) on the unfiltered listing and on a `from`/`to` request alike, since neither has an "adjacent period" concept. Each shape matches whichever lens is active: `{ calendarYear, calendarMonth }` for a calendar-filtered request, `{ financialYear, financialMonth }` for an FY-filtered one — never both on the same object.

**Smart-skip, not merely adjacent:** these were originally booleans (`hasPreviousMonthData`/`hasNextMonthData`) checking only the literally-adjacent month. That broke the moment BR-13's backdating let a user log a transaction several months back while the months in between stayed empty — the arrow would disable at the first empty month and the earlier data would become unreachable through the UI, even though it was still sitting in the database. `previousPeriod`/`nextPeriod` instead resolve to the *nearest period that actually has data*, skipping empty gaps automatically — `null` means genuinely nothing further in that direction, not "the adjacent period happens to be empty."

**Calendar lens** resolves this in one indexed query — `calendarYear`/`calendarMonth` are plain integers, so the boundary (`date < firstDayOfCurrentMonth` / `date > lastDayOfCurrentMonth`) is unambiguous, and the nearest transaction on the correct side of that boundary already carries the target `calendarYear`/`calendarMonth` as denormalized fields.

**FY lens** resolves this differently, deliberately: converting a `financialYear` label like `FY26-27` back into an absolute calendar boundary means guessing which century its two-digit year belongs to — real but avoidable complexity for a personal app. Instead, it steps financial-month by financial-month (reusing `FinancialYearUtil.previous`/`next`) checking for data at each step, capped at 24 steps (2 years either direction) as an explicit, generous bound rather than an unbounded walk.

---

#### GET /transactions/available-periods
Powers a year-based month picker on the Cash Flow screen — calendar lens only (the FY lens keeps arrow-only navigation via `previousPeriod`/`nextPeriod` above; no picker for it). Returns every year and month that has at least one transaction, unfiltered by `flowType` — this endpoint only answers "does this month have any data at all," not "does it have data matching the currently-applied type filter."

**Response 200:**
```json
{
  "data": {
    "years": [2026, 2025],
    "monthsByYear": {
      "2026": [8, 6],
      "2025": [12, 11, 6]
    },
    "earliestTransactionDate": "2025-06-03"
  }
}
```

`years` and each year's month list are both already `DESC` (most recent first) — no client-side sorting needed. The endpoint deliberately returns the full years+months structure in one response rather than accepting a `?year=` param and fetching per year: for a single-user personal app the total data volume is small, so one payload avoids a round trip every time the user switches year tabs. "Default to current year" is a frontend concern — the client already knows what year "today" is and doesn't need the backend to say so; if the current year genuinely has no transactions yet, it's simply absent from `years`, and the picker's default view is correctly an empty month grid.

**`earliestTransactionDate` (added v1.5.0) — the shared back-navigation boundary for `from`/`to` requests:** a single `MIN(date)` across the user's transactions (`TransactionRepository.findEarliestDate`), `null` for a brand-new user with none yet. This exists specifically for the `from`/`to` query model on both `GET /transactions` and `GET /analytics/cashflow-summary` (see "Period filtering" above), which deliberately has no smart-skip navigation of its own — the frontend needs *some* signal for "how far back can the user go" before it starts requesting empty weeks. One shared fact serves both endpoints without duplication: `GET /analytics/cashflow-summary` has no data independent of the `transactions` table, so there's no separate "earliest date analytics has" to compute — it's definitionally the same value. The frontend's period-navigator fetches this once (cached, not refetched per navigation step) and uses it two ways: gating the back arrow directly (disable once the next `from`/`to` window would end before this date), and, for a week-list picker built per available month, truncating the week list for the earliest available month at this exact date rather than at the month's calendar start — `monthsByYear` only proves a month has *some* data, not that every week within it does.

#### GET /transactions/{id}
Response includes `goalAllocations` (v1.5.0), same shape as the list endpoint.

#### PUT /transactions/{id} — corrects `amount`, `category`, and `date` for any transaction type. Also corrects `notes` — for every type *except* `SETTLEMENT`, whose notes are append-only and locked from creation (see BR-11). Never changes `type`, `accountId`, `toAccountId`, or `toGoalId` — see §3.6 Immutability. For a TRANSFER, reverses the old effect on both the source and destination account (and goal progress — arriving via `toGoalId` or leaving a goal-linked source account, see BR-07) before reapplying with the new values — see BR-03, BR-05, BR-07.

**Optional `goalAllocations` on PUT (v1.5.0):** for a withdrawal TRANSFER whose amount is changing, resolved via BR-19's four-tier reconciliation. Response may include a `warning` (same field used by BR-13) when tier 3 releases a no-longer-needed allocation back to free balance.

#### DELETE /transactions/{id} — reverses all balance and goal side effects, both source and destination account for TRANSFER — see BR-03. For a withdrawal TRANSFER, also reverses its `transaction_goal_allocations` rows and the corresponding goals' `currentProgress` (v1.5.0, BR-19).

`POST /transactions` additionally rejects a TRANSFER whose destination resolves to the same account as the source (`400`, whether reached via `toAccountId` or `toGoalId`) — see BR-15.

Both `POST /transactions` and `PUT /transactions/{id}` reject any `date` later than today (`400`) — see BR-16.

---

### Planned Amounts

#### POST /planned-amounts
```json
{
  "name": "House Rent",
  "direction": "EXPENSE",
  "amount": 10000.00,
  "categoryId": "cat-25",
  "frequency": "MONTHLY",
  "startDate": "2024-07-05",
  "endDate": null,
  "icon": "🏠"
}
```
**Response 201:** Includes computed `nextDueDate` and `daysUntilDue`  
**Side effect:** Increments `user.onboardingStep` to 2 if currently 1.

---

#### GET /planned-amounts
**Query params:** `direction=EXPENSE` · `direction=INCOME` · `upcoming=true`

```json
{
  "data": {
    "items": [
      {
        "id": "uuid",
        "name": "House Rent",
        "direction": "EXPENSE",
        "amount": 10000.00,
        "category": { "name": "Rent", "icon": "🏠" },
        "frequency": "MONTHLY",
        "nextDueDate": "2024-08-05",
        "daysUntilDue": 15,
        "displayDue": "5 August"
      },
      {
        "id": "uuid",
        "name": "Netflix",
        "direction": "EXPENSE",
        "amount": 199.00,
        "category": { "name": "Subscriptions", "icon": "🔔" },
        "frequency": "MONTHLY",
        "nextDueDate": "2024-07-22",
        "daysUntilDue": 7,
        "displayDue": "in 7 days"
      }
    ]
  }
}
```

`displayDue` logic: if `daysUntilDue <= 30` → "in X days", else → actual date string

#### PUT /planned-amounts/{id}
#### DELETE /planned-amounts/{id}

---

### Goals

#### POST /goals
```json
{
  "name": "Emergency Fund",
  "targetAmount": 100000.00,
  "accountId": "uuid-india-bank",
  "startDate": "2024-05-01",
  "endDate": "2025-04-01"
}
```

**Response 201:**
```json
{
  "data": {
    "id": "uuid",
    "name": "Emergency Fund",
    "targetAmount": 100000.00,
    "account": { "id": "uuid", "name": "India Bank" },
    "startDate": "2024-05-01",
    "endDate": "2025-04-01",
    "currentProgress": 0.00,
    "progressPercentage": 0.0,
    "monthlySavingsRequired": 9090.91,
    "monthsRemaining": 11,
    "savedThisMonth": 0.00,
    "status": "IN_PROGRESS"
  }
}
```

`monthlySavingsRequired` = targetAmount / totalMonths (at creation). Recalculated on every read as progress increases.  
**Side effect:** Increments `user.onboardingStep` to 3 if currently 2.

---

#### GET /goals
```json
{
  "data": {
    "overview": {
      "totalGoalSavings": 22000.00,
      "monthlyTarget": 22500.00,
      "savedThisMonth": 20000.00,
      "overallProgressPercentage": 10
    },
    "goals": [ ... ]
  }
}
```

**Query params:** `status=IN_PROGRESS` · `status=UPCOMING`

#### GET /goals/{id}
#### PUT /goals/{id}
#### DELETE /goals/{id} — soft delete
#### PUT /goals/reorder — `[{ "id": "uuid", "displayOrder": 1 }, ...]`

---

### Dashboard

#### GET /dashboard
Single endpoint — assembles everything the Home screen needs in one call.

```json
{
  "data": {
    "user": {
      "firstName": "Nishant",
      "onboardingStep": 3,
      "motivationalQuote": "You can make money two ways, make more or spend less."
    },
    "balance": {
      "totalAvailableBalance": 35000.00,
      "balancePercentage": 94,
      "monthlyTarget": 22500.00,
      "savedThisMonth": 0.00,
      "savingsMessage": "You can save ₹22,500 today!"
    },
    "accounts": [
      { "id": "uuid", "name": "MAXIS Bank", "currentBalance": 35000.00, "type": "CASH", "colorLabel": "#E8A04D" },
      { "id": "uuid", "name": "India Bank", "currentBalance": 3457.55, "type": "BANK", "colorLabel": "#4A90D9" }
    ],
    "lastEntries": [
      {
        "id": "uuid",
        "notes": "Vijetha",
        "category": { "name": "Groceries", "icon": "🛒" },
        "account": { "name": "MAXIS Bank", "colorLabel": "#E8A04D" },
        "displayAmount": "-₹919.19",
        "date": "2024-06-14"
      }
    ],
    "onboardingChecklist": {
      "accountsAdded": true,
      "plannedAmountsAdded": true,
      "goalsAdded": false
    }
  }
}
```

**`balancePercentage` — what it actually represents:** `totalAvailableBalance ÷ (totalIncomeThisMonth + openingBalanceThisMonth) × 100` (§7), or `null` when that denominator is zero (no data yet to compute a ratio against — not the same as "critically low"). This is deliberately a **solvency / safety-margin indicator** — "how large is your total balance relative to what came into the system this month" — not a bounded, monthly-depleting gauge. It routinely reads above 100%, because the numerator (`totalAvailableBalance`) is the user's full accumulated balance across every account and every past month, while the denominator is only this month's inflow. It is a direct port of the original Excel template's `TOTAL BALANCE` header cell (`=D3/B3`), inherited unchanged because the underlying question — "am I running dangerously thin relative to what I've brought in this month" — is the same one the original spreadsheet was built to answer, and the thresholds below were already tuned by hand against real spending before this app existed.

**`openingBalanceThisMonth` — the onboarding-day fix:** `totalIncomeThisMonth` alone is `0` on the day a user first adds accounts, since an opening balance is a `SETTLEMENT`, not `INCOME` — so a user who just funded ₹60,000 across two accounts would otherwise see `0%` (reading as critically low, under the bands below) despite having real money and no expenses yet. The fix sums this month's `SETTLEMENT` transactions filtered specifically to the `Opening Balance` category (`cat-35`, BR-01/BR-17) — never `Adjustment` (`cat-02`, BR-02) — and adds that to `totalIncomeThisMonth` as the denominator. Because BR-01 only ever fires once per account, this term is naturally `0` in every month after the account's creation month, so the formula quietly reduces back to plain `totalBalance ÷ totalIncomeThisMonth` once onboarding is behind the user — no ongoing special-casing needed.

`balancePercentage` display bands (frontend-owned, Angular), carried over from the Excel template's conditional formatting on that same cell: **> 30% healthy (green)** · **20–30% caution (amber)** · **≤ 20% low (red)**. A `null` value (denominator still zero) is a distinct fourth state — "not enough data yet," not a color band at all. Deliberately *not* modelled as a phone-style "low power mode" that drains from 100% to 0% over the month — that shape belongs to a different metric (`dailyExpenseLimit`, §7 — a live, shrinking safe-to-spend figure), not to this one. Considered and rejected: reworking `balancePercentage` itself into a bounded per-month depletion gauge — rejected because it would replace a validated, already-battle-tested solvency signal with an unrelated metric wearing the same name.

**`savedThisMonth` — goal contributions, net of the same month's withdrawals (corrected, v1.5.0):** starts from the same gross figure as before — `TRANSFER` transactions with `toGoalId` set, in the current calendar month; a plain account-to-account transfer still doesn't count, since it was never a savings action — but that gross figure is no longer the final answer. It is now reduced by `GoalAllocationService.sumWithdrawalsByCalendarMonth`, the same allocation-ledger sum BR-19/BR-20 write to. **This was a known gap the original v1.4.0 note explicitly flagged and deferred** ("deliberately not netted against any BR-07 goal withdrawals in the same month; that would be a different, distinct metric if ever needed later") — the goal allocation ledger shipping in v1.5.0 is exactly that "if ever needed later" moment: a BR-19 withdrawal transfer or a BR-20 balance correction in the same month now visibly reduces `savedThisMonth`, instead of the metric silently reporting money that was deposited and then pulled back out again as if it were still saved. `Goal.savedThisMonth` (§3.8) is unaffected by this change and remains the gross, single-goal figure — this correction is specific to the Dashboard's account-wide aggregate, which is the one place gross-vs-net actually misleads a user browsing multiple goals at once.

**`savingsMessage` logic (rounding corrected, v1.5.0):**
- If `monthlyTarget − savedThisMonth < ₹1` → `"🎯 You are all set! Just hold on to it 💰"` — a sub-rupee remainder is rounding dust once real paise-level transactions are being netted in, not a meaningful amount to ask someone to go save; the old exact `savedThisMonth >= monthlyTarget` check missed this and could report "You can save ₹0 today!", which reads as nonsense rather than "you're basically done."
- Else, if `totalAvailableBalance < remaining` → `"Save what you can — every ₹{totalAvailableBalance, floored} counts towards your goal! 💪"` (floored, since rounding *up* here would overstate money the user doesn't actually have).
- Else → `"You can save ₹{remaining, ceiling} today!"` — ceiling, not floor: flooring a fractional remainder (e.g. ₹5000.60) would tell the user ₹5000 is enough when it's actually ₹0.60 short of the real target.

---

### Analytics

#### GET /analytics/cashflow-summary
Powers the 4 summary cards on the Cash Flow screen.

**Query params:**
```
from=2026-07-01&to=2026-07-31   inclusive date range — both required
```

Both `from` and `to` are required (`400` if either is missing), and `from` must not be after `to` (`400` otherwise). There is no `mode` or `anchor` param — period shape (this week, this month, a custom range) is entirely the frontend's concern. The Angular period-navigator computes the boundaries for whatever it's displaying — today's month on first load, a shifted range on prev/next navigation, an arbitrary picked range — and always sends them explicitly; the backend never infers a period from a single date. See "Period filtering — `GET /transactions` and `GET /analytics/cashflow-summary`" at the start of §6 Transactions for why this replaced an earlier three-mode (`MONTHLY`/`WEEKLY`/`CUSTOM`) design that never reached the frontend.

**The response includes the resolved period** so Angular can display "July 2026" or "27 Jul – 2 Aug" without computing it client-side:

**The four cards and what drives them:**

| Card | Formula | Color coding |
|---|---|---|
| Income | SUM of `INCOME` transactions in period | neutral |
| Expense | SUM of `FIXED_EXPENSE` + `VARIABLE_EXPENSE` in period | neutral |
| Savings | SUM of `TRANSFER` to goal in period, net of the same period's allocation-ledger withdrawals, + savings rate % | > 20% green · 10-20% yellow · < 10% red |
| Debt Ratio | SUM of `REPAYMENT` / SUM of `INCOME` × 100 | < 30% green · 30-50% yellow · > 50% red |

**Savings rate** displayed inside the Savings card — not a separate card.

**Debt Ratio** covers all repayments — EMIs to banks and repayments to individuals alike.

**Savings — corrected to net out withdrawals (v1.5.0):** originally a pure gross figure (`SUM of TRANSFER to goal in period`), carrying the exact same known gap as Dashboard's `savedThisMonth` above — a BR-19 withdrawal or BR-20 balance correction in the same period was invisible to it, so pulling money back out of a goal still showed as full savings. Fixed the same way: `AnalyticsService.sumTransferToGoal` now subtracts `GoalAllocationService.sumWithdrawalsByDateRange` from the gross deposit sum before returning it, using the request's `from`/`to` directly. `savingsRate` (`savings ÷ income × 100`) is computed from this corrected, net `savings` figure, so a rate that previously could read well above 100% purely from an uncorrected gross number now reflects genuine savings performance.

**Special cases:**
- `INCOME = 0` → savings rate and debt ratio show `null` (not 0%)
- `REPAYMENT = 0` → debt ratio shows `0.0` (green)

```json
{
  "data": {
    "period": {
      "from": "2026-07-01",
      "to": "2026-07-31"
    },
    "income": 50000.00,
    "expense": 15919.19,
    "savings": 10000.00,
    "savingsRate": 20.0,
    "debtRatio": 0.0
  }
}
```

---

#### GET /analytics/monthly/{financialYear}/{month}
Full monthly breakdown. **Phase 2** — deferred until `MonthSummary` computation is built.

#### GET /analytics/yearly/{financialYear}
Full FY rollup. **Phase 2** — deferred. Year view in the Cash Flow screen is also Phase 2.

### Categories

#### GET /categories
All active, user-selectable categories. Called once on app load, cached on the Angular side. Excludes `isInternal = true` rows entirely — the `Opening Balance` category (`cat-35`) never appears here, since it's system-reserved (BR-17) and would otherwise show up as a pickable option in the Add Entry category dropdown.

```json
{
  "data": [
    { "id": "cat-12", "name": "Groceries", "icon": "🛒", "isSystem": true },
    { "id": "cat-20", "name": "Paycheck", "icon": "💵", "isSystem": true }
  ]
}
```

---

### Profile

#### GET /profile
#### PUT /profile
```json
{
  "defaultCurrency": "INR",
  "financialYearStartMonth": 4,
  "motivationalQuotesEnabled": true
}
```

---

### LLM Insights

#### POST /insights/generate
```json
{
  "period": "MONTHLY",
  "financialYear": "FY24-25",
  "month": 3,
  "metrics": ["SAVINGS_RATE", "TOP_EXPENSE_CATEGORIES", "DEBT_INCOME_RATIO", "DAILY_SPEND_TREND"]
}
```

**Response 200:**
```json
{
  "data": {
    "insightId": "uuid",
    "generatedAt": "2024-06-14T09:41:00",
    "period": "MONTHLY",
    "summary": "Your savings rate this month is 20%, which is strong...",
    "insights": [
      {
        "metric": "SAVINGS_RATE",
        "value": 20.0,
        "assessment": "GOOD",
        "message": "You are saving 20% of your income, above the recommended 15–20% target.",
        "suggestion": "Consider increasing your Emergency Fund contribution by ₹2,000."
      }
    ]
  }
}
```

The service collects the `MonthSummary` data + goal progress + category breakdown, builds a structured prompt, calls the LLM API, and returns parsed insights. Raw prompt and response stored for history/debugging.

---

## 7. Computed Metrics Reference

| Metric | Formula | Used in |
|---|---|---|
| Balance percentage | `totalBalance / (totalIncomeThisMonth + openingBalanceThisMonth) × 100`, `null` if denominator is 0 | Dashboard ring |
| Saved this month | `SUM(amount)` for TRANSFER where `toGoalId IS NOT NULL`, current calendar month | Dashboard savings message |
| Daily expense limit | `balance / daysRemainingInMonth` | Monthly summary |
| Debt-income ratio | `totalDebt / totalIncome × 100` | Monthly summary |
| Savings rate | `totalSavings / totalIncome × 100` | Monthly summary |
| Goal monthly required | `(targetAmount - currentProgress) / monthsToEnd` | Goal card |
| Goal progress % | `currentProgress / targetAmount × 100` | Goal progress bar |
| Free balance *(v1.5.0)* | `account.currentBalance - Σ(currentProgress of that account's active goals)` | Withdrawal allocation guard (BR-19) |
| Days until due | `nextDueDate - today` | Planned amounts list |
| Display due string | `≤ 30 days → "in X days"`, `> 30 days → "5 August"` | Planned amounts list |
| Financial year label | See BR-04 below | Transaction, summary |
| FY month (1-based April) | `date.month >= 4 ? date.month - 3 : date.month + 9` | Transaction, summary |

---

## 8. Seed Data

Applied via Flyway migration `V1__seed_categories.sql` on first launch.

```sql
INSERT INTO categories (id, name, icon, is_system, is_active, is_internal, display_order) VALUES
('cat-01','Account','📊',1,1,0,1),
('cat-02','Adjustment','🔄',1,1,0,2),
('cat-03','Allowances','💰',1,1,0,3),
('cat-04','Cashback','💸',1,1,0,4),
('cat-05','CC Payment','💳',1,1,0,5),
('cat-06','Clothing','👔',1,1,0,6),
('cat-07','Donation','🤝',1,1,0,7),
('cat-08','Family & Friends','👨‍👩‍👧',1,1,0,8),
('cat-09','Food & Beverages','🍔',1,1,0,9),
('cat-10','Fuel','⛽',1,1,0,10),
('cat-11','Gifts','🎁',1,1,0,11),
('cat-12','Groceries','🛒',1,1,0,12),
('cat-13','Grooming','💈',1,1,0,13),
('cat-14','Healthcare','🏥',1,1,0,14),
('cat-15','Household','🏠',1,1,0,15),
('cat-16','Insurance','🛡',1,1,0,16),
('cat-17','Investments','📈',1,1,0,17),
('cat-18','Loan','🏦',1,1,0,18),
('cat-19','Miscellaneous','📦',1,1,0,19),
('cat-20','Paycheck','💵',1,1,0,20),
('cat-21','Pets','🐾',1,1,0,21),
('cat-22','Phone','📱',1,1,0,22),
('cat-23','Refreshment','😋',1,1,0,23),
('cat-24','Refund','↩️',1,1,0,24),
('cat-25','Rent','🏠',1,1,0,25),
('cat-26','Restaurant','🍽',1,1,0,26),
('cat-27','Savings','🐷',1,1,0,27),
('cat-28','Subscriptions','🔔',1,1,0,28),
('cat-29','Telephone','☎️',1,1,0,29),
('cat-30','Tips','🤌',1,1,0,30),
('cat-31','Transport','🚌',1,1,0,31),
('cat-32','Utilities','💡',1,1,0,32),
('cat-33','Vehicle','🚗',1,1,0,33),
('cat-34','Wellness','🧘',1,1,0,34),
('cat-35','Opening Balance','🏛️',1,1,1,35);
```

`cat-35` is `isInternal = true` — set only by BR-01's auto-generated opening-balance `SETTLEMENT`, never user-selectable (BR-17). Every other row is `isInternal = false`, unchanged from the original 34-category set.

---

## 9. Key Business Rules

### BR-01: Opening Balance Auto-Transaction
When an account is **created** (`POST /accounts`) with `currentBalance > 0`, the service automatically creates a `SETTLEMENT` transaction, category `Opening Balance` (`cat-35`, `isInternal = true` — see BR-17), dated today, `notes = "Opening balance"`. This is what appears as "Current Balance / Opening Balance MAXIS Bank" in the Figma Cash Flow list. The user never creates this manually. Amount is always positive (enforced by `@DecimalMin(0)` on the request).

`Opening Balance` is a deliberately separate category from `Adjustment` (used by BR-02) even though both are system-generated `SETTLEMENT` rows — Dashboard's `balancePercentage` (§6, §7) sums this month's opening-balance transactions specifically by category to make the metric sensible on onboarding day, and a later BR-02 correction must never be counted the same way (see §6 Dashboard note).

### BR-02: Balance Correction Auto-Transaction
When an existing account's `currentBalance` is **edited** (`PUT /accounts/{id}` with a `currentBalance` differing from the stored value), the service automatically creates a second `SETTLEMENT` transaction for the delta, category `Adjustment` (`cat-02`, unchanged from BR-01's category), dated today, `notes = "Balance adjustment: ₹{old} → ₹{new}"` (both amounts normalized via `stripTrailingZeros().toPlainString()` so one side never shows a spurious decimal the other doesn't — e.g. `₹30500 → ₹32000`, not `₹30500.0 → ₹32000`). Unlike BR-01, this delta may be negative (a downward correction). Triggered by deliberate user action — editing the account, not the general-purpose Add Entry form — but the transaction itself is system-generated, not user-typed.

**Interaction with the goal allocation ledger (v1.5.0):** a downward correction on a goal-linked account is now checked against that account's total earmarked goal progress before it's accepted — see BR-20.

**Design rationale (BR-01 & BR-02):** Both mirror the **Adjustment Method** real banks use for reconciliation corrections — the original record is never edited; a new entry bridges the gap, with the discrepancy described in that new entry rather than silently merged into history. See §3.4.

### BR-03: Transfer Atomicity
A TRANSFER updates two balances simultaneously — source account debited, destination account/goal credited. This two-sided effect must succeed or fail together in all three operations:

- **POST /transactions (TRANSFER):** wrap creation and both balance updates in a single `@Transactional` block. If the destination credit fails, roll back the source debit entirely.
- **PUT /transactions/{id} (TRANSFER amount or account change):** load the old row first, reverse both the old debit and old credit, then apply both sides of the new amounts — all within a single `@Transactional` block. Partially reversing one side without the other silently creates or destroys money; this is the most serious possible bug in a finance app.
- **DELETE /transactions/{id} (TRANSFER):** reverse both sides atomically — restore the source balance and reverse the destination credit/goal contribution in one transaction.

Amounts involved in a TRANSFER are always net-zero for the user's total wealth (`totalIncome`/`totalExpense` analytics exclude TRANSFER-typed transactions for exactly this reason).

### BR-04: Financial Year Derivation
Server-side only. Never trust the client to set this. Computed once at creation from `date`, and **recomputed whenever `date` changes** — including a later `PUT /transactions/{id}` that corrects the date on an existing row. Without this recompute step, `financialYear`/`month`/`calendarMonth`/`calendarYear` could silently drift out of sync with `date`, the one failure mode that makes storing derived fields unsafe (see §3.6, "Two calendars").
```
If date.month >= 4:
  financialYear = "FY" + (year % 100) + "-" + ((year + 1) % 100)
  month         = date.month - 3            // April=1, May=2 ... Dec=9
Else:
  financialYear = "FY" + ((year - 1) % 100) + "-" + (year % 100)
  month         = date.month + 9            // Jan=10, Feb=11, Mar=12
```

### BR-05: Balance is the Source of Truth
`account.currentBalance` is updated with every transaction POST, PUT, and DELETE. It is never recalculated from the full transaction history.

**On PUT (amount correction):** load the old stored amount *before* overwriting the row — reverse the old amount's effect on `currentBalance`, then apply the new amount's effect. The old value must be captured before it's overwritten; this is an implementation-order constraint, not just a design one. For a TRANSFER, this reversal applies to both the source and destination account, not just the source — see BR-03.

**On DELETE:** reverse the stored amount's exact effect. Never re-derive the amount from anywhere — use what was actually stored on that row. For a TRANSFER, this again means both the source and destination account, not just the source.

**Destination account is always a stored snapshot, never re-derived.** `to_account_id` is populated for *every* TRANSFER, whether the user picked a destination account directly or picked a goal — in which case `to_account_id` is the account backing that goal at creation time, resolved once and stored. Same reasoning as the amount above: a goal's linked account could theoretically be reassigned later, and re-deriving the destination live via `to_goal_id → goal.account` at update/delete time would silently reverse the wrong account's balance if that ever happened. `to_goal_id` remains purely a progress-tracking tag (BR-07) — it never substitutes for `to_account_id` as the source of truth for *which balance moved*.

### BR-06: Month Summary Invalidation
Any create, update, or delete on a transaction sets `month_summaries.is_dirty = true` for the affected month(s). Summaries are recomputed lazily on next `GET /analytics/**` call for that month, or eagerly via a background recalculation.

**Cross-month edge case:** if a `PUT /transactions/{id}` changes `date` such that the transaction moves from one month/FY to another — e.g. correcting a June entry to May — **both** months must be invalidated: the old month (a transaction left it, its totals decreased) and the new month (a transaction arrived, its totals increased). Setting `is_dirty = true` on only one of the two months silently leaves the other month's cached summary permanently wrong.

### BR-07: Goal Progress Source
`goal.currentProgress` moves in both directions, symmetric with how `currentBalance` moves for a TRANSFER (BR-05):

- **Arriving:** a TRANSFER with `toGoalId` set increases the target goal's `currentProgress` by the amount.
- **Leaving:** a TRANSFER whose *source* account (`accountId`) is itself goal-linked (BR-12) decreases that goal's `currentProgress` by the amount — this is the withdrawal path, moving money back out of a goal account into a regular one. As of v1.5.0, *which* goal(s) and how much each contributes is explicit, via the allocation ledger — see BR-19 — rather than assumed to be the account's one and only goal.

No direct PUT endpoint for `currentProgress`. Deleting a transaction reverses whichever of the two effects above applied. Editing the amount reverses the old contribution and applies the new one (same reversal pattern as BR-05, applied to `currentProgress` instead of `currentBalance`) — implemented as part of `PUT /transactions/{id}`, not a separate goal-specific endpoint. A transaction can only trigger one side of this — never both — since `to_account_id` can't equal `account_id` (see BR-15).

### BR-08: Planned Amount Advancement
When a PlannedAmount's `nextDueDate` passes, or when a transaction is logged with `plannedAmountId` set, `nextDueDate` advances by the frequency interval. For `ONE_TIME` frequency, mark `isActive = false` after it triggers.

### BR-09: Onboarding Step Progression
- Step 0 → 1: when first account is created
- Step 1 → 2: when first planned amount is created (**Phase 2 — `plannedAmountsAdded` is hardcoded `false` in MVP**)
- Step 2 → 3: when first goal is created
- Steps only advance, never go backward
- Dashboard `onboardingChecklist` always reflects real data counts, not just the step number

### BR-10: Soft Delete
Accounts, goals, and planned amounts use soft delete (`is_active = false`). Transactions are never soft-deleted — deletion reverses side effects and removes the record. Categories marked `is_system = true` cannot be deleted at all.

### BR-11: Transaction Notes Immutability
`Transaction.notes` is updatable via `PUT /transactions/{id}` for every transaction type **except** `SETTLEMENT`. A `SETTLEMENT` row's notes are fixed at creation and never updatable thereafter — system-generated notes must never be altered, matching real bank reconciliation practice (see §3.4). This restriction applies only to `notes`; `amount`, `category`, and `date` remain editable on a `SETTLEMENT` row like any other type. `type`, `accountId`, `toAccountId`, and `toGoalId` remain non-editable via `PUT` regardless of transaction type — see §3.6 Immutability.

### BR-12: Goal-Linked Accounts Are Protected From Direct Expense Transactions
An account linked to at least one active, non-completed goal (`isActive = true`, `status != 'COMPLETED'`) is a **protected account**. The following transaction types are blocked when `accountId` resolves to a protected account:

```
BLOCKED:  FIXED_EXPENSE, VARIABLE_EXPENSE, LENDING, BORROWING, REPAYMENT
ALLOWED:  INCOME, TRANSFER, SETTLEMENT
```

**Why blocked types are blocked:** These represent money leaving the account in ways that bypass the goal's intended purpose. Blocking them forces the user to make a conscious two-step decision — TRANSFER to a spending account first, then log the expense — keeping the withdrawal tracked and deliberate.

**Why allowed types are allowed:**
- `INCOME` — money arriving into the account is always fine. No salary designation concept exists in Moneyflow; a user may receive income directly into their savings account.
- `TRANSFER` — this is the correct mechanism for moving money between accounts, including withdrawing from a goal account back to a spending account. Always tracked, always visible in history. As of v1.5.0, this is exactly the path that now records *which* goal(s) a withdrawal draws from — see BR-19.
- `SETTLEMENT` — system-generated (BR-01/BR-02), never user-initiated via Add Entry form.

**Implementation:** `TransactionService.createTransaction` checks whether the source `account` is goal-linked before accepting the transaction. Uses `GoalRepository.existsByAccountIdAndActiveTrueAndStatusNot(accountId, "COMPLETED")`.

**No account type constraint:** Any account type (`CASH`, `BANK`, `WALLET`) may back a goal. The protection comes from the goal linkage, not the account type. FDs and RDs are out of scope for MVP — see §11.

### BR-13: Transaction Backdating Warning
When a transaction's `date` is more than 7 days before the `account.createdAt` date (the account setup date), the API returns an optional `warning` field alongside the normal success response. The transaction is NOT blocked — the user may legitimately be reconstructing history from a bank statement. The warning informs them that their opening balance may need updating to reflect the historical transaction.

**Why 7 days:** Minor date differences (timezone, bank processing delay) should not trigger warnings. Significant backdating (more than a week before setup) is the meaningful case.

**Frontend responsibility:** Show the warning as a toast notification once per account per session. Suppress subsequent warnings for the same account to avoid repetitive friction during bulk historical entry.

### BR-14: Authentication Requires an Existing Principal
A cryptographically valid JWT can still reference a `userId` that no longer exists (e.g. after a database reset, or an account deletion feature added later). `JwtAuthFilter` verifies the referenced user still exists (`UserRepository.existsById`) before marking a request as authenticated — a token for a deleted user is treated identically to an invalid one, and the request falls through unauthenticated to the standard `401 UNAUTHORIZED` response via `JwtAuthEntryPoint`. This must be uniform across every endpoint; no controller or service should independently decide what a stale-but-valid token means.

### BR-15: Transfer Destination Cannot Equal Source
`POST /transactions` rejects a TRANSFER whose resolved destination account is the same account as the source — `400 Bad Request`. This check runs against the *resolved* account, so it catches both ways a user could trigger it: picking the same account directly as `toAccountId`, or picking a goal (`toGoalId`) whose backing account happens to be the same account they're transferring from.

**Why this matters beyond being a meaningless entry:** a self-transfer's balance effect nets to zero on `currentBalance` regardless (the same account gets debited and credited by the same amount), but if the destination was reached via a goal, `currentProgress` still increases by the full amount under BR-07's "arriving" rule — with no real money having moved anywhere. That's phantom savings: the goal shows progress a bank statement would never confirm. Rejecting the self-transfer at creation is simpler and safer than trying to special-case the goal-progress math around it.

### BR-16: Transaction Date Cannot Be in the Future
Both `POST /transactions` and `PUT /transactions/{id}` reject a `date` later than today — `400 Bad Request`. Unlike BR-13's backdating warning, this is a hard block, not a soft warning, because the two directions aren't symmetric: backdating has a legitimate real-world justification (reconstructing history from a bank statement), so it's allowed with a nudge; a future-dated transaction has no equivalent justification, because `date` means "when the money actually moved" (§3.6) and money that hasn't moved yet cannot have a transaction date.

**Why this matters beyond being conceptually odd:** every transaction's balance effect applies immediately and unconditionally on `POST` (BR-05 — `currentBalance` is updated in real time). A future-dated expense would debit `currentBalance` *today*, silently understating the money the user actually has available right now — corrupting the single most load-bearing number in the app, along with everything computed from it (`balancePercentage`, BR-12's goal-protection check, the Dashboard total). This isn't a UI inconvenience to work around with better month-navigation; it's a data-integrity gap that had to be closed at the source.

**Where genuinely-future information belongs instead:** a known upcoming payment or expected income has a real home already — `PlannedAmount` (Phase 2, deferred, see §11) — not `Transaction`. The distinction is deliberate: a `Transaction` is a ledger fact (money definitely moved), while a `PlannedAmount` (and any forecast built on top of it) is a prediction (money is expected to move). Letting a future date into `Transaction` would blur a boundary real accounting always keeps separate — a general ledger never contains forecast rows.

### BR-17: Internal Categories Are System-Reserved
A category with `isInternal = true` (currently just `Opening Balance`, `cat-35`) exists purely for the app's own bookkeeping and must never be user-visible or user-selectable:

- **`GET /categories`** excludes every `isInternal = true` row from its response entirely — it never reaches the Angular category dropdown in the first place.
- **`POST /transactions` and `PUT /transactions/{id}`** reject a request naming an internal category's ID — but *identically* to how they reject a nonexistent one (`404 Category not found`), not with a distinct `400` explaining why. This is deliberate, not an oversight: a distinguishable error would let a caller enumerate which category IDs are reserved just by probing responses, the same enumeration risk `/auth/signin`'s intentionally-generic `401` (§6 Auth) is designed to prevent. Implementation is a single `.filter(c -> !c.isInternal())` folded into the existing `findById(...).orElseThrow(...)` category lookup — no separate branch to leak through.

**Why this exists:** without it, a client could tag an ordinary expense with `Opening Balance`'s category ID, which would silently count that expense as opening-balance inflow in Dashboard's `balancePercentage` calculation (§6) — corrupting a metric that's supposed to reflect real onboarding funding, not an arbitrary transaction a user (or a bug) happened to mislabel.

### BR-18: Invite-Based Onboarding & OTP Security
Replaces open self-registration entirely (`POST /auth/signup` removed, v1.4.0). A new `User` is created in exactly two ways: `AdminBootstrapRunner` seeds the first `ADMIN` from env vars at startup (solving the chicken-and-egg problem of nobody existing yet to send the first invite), and every subsequent `User` is `MEMBER`, created only through this flow.

**The three-step state machine:** `PENDING` (admin created the invite, email sent) → `AWAITING_OTP` (invited person submitted name/password, code sent) → `COMPLETED` (code verified, `User` created). The `User` row does not exist until the final step — `pendingFirstName`/`pendingLastName`/`pendingPasswordHash` hold the staged details in the interim, cleared the instant they're promoted to a real `User`.

**OTP is treated exactly like a password, not like a lesser secret:** the 6-digit code is BCrypt-hashed with the same `PasswordEncoder` bean before storage (`otpCodeHash`) — never stored or logged in plaintext. Expires after 10 minutes. Capped at 5 incorrect attempts, after which the invite requires a fresh code via resend rather than being permanently killed — the invite itself stays valid for its full 7-day window regardless of how many codes have been burned.

**Resend cooldown without a schema change:** rather than adding a dedicated "last sent" column, the 60-second cooldown is derived from the `Invite` entity's existing `updatedAt` (auto-maintained by `@LastModifiedDate`) — one fewer migration, one fewer thing that can drift out of sync with reality.

**The user cap is enforced at verification, not at invite creation:** `POST /api/admin/invites` only warns if `app.max-users` is already reached — it doesn't block, since an admin may legitimately want to queue invites while deciding who to remove. The real gate is in `verifyOtp`: `userRepository.countByRole("MEMBER") >= maxUsers` is checked immediately before creating the `User`, deliberately ahead of the OTP-match check itself, so a workspace that's full rejects cleanly regardless of whether the code is correct — and so a correct code is never wasted against `MAX_OTP_ATTEMPTS` for a reason unrelated to the code being wrong. First-to-verify wins over first-to-click-the-link if two invited people race past the cap.

**Why `/auth/invites/{token}/**` sits under `/auth/**` rather than its own permitted path:** every step of this flow happens before the invited person has a JWT, so it must ride the same `permitAll` rule `/auth/signin` already uses — no separate Spring Security exemption to maintain in parallel.

**Why `GET /auth/invites/{token}` is safe to leave unauthenticated and un-rate-limited, unlike `verify-otp`:** a 6-digit OTP has only a million possible values, which is exactly why it's capped at 5 attempts (above) — but the invite token itself is 32 random bytes, effectively unguessable within any practical timeframe. Knowing the token is already equivalent to having received the invite email; the lookup adds no new way to find or confirm a token, it only reveals the email tied to one you already hold.

### BR-19: Withdrawal Goal Allocation Ledger *(added v1.5.0)*
A TRANSFER withdrawing from a goal-linked account (source account is goal-linked, destination isn't a goal) records *which* goal(s) it drew from and how much from each, via `transaction_goal_allocations` (§3.6a) — rather than assuming a single goal per account, which cannot represent an account backing more than one active goal.

**Free balance** is the amount of a goal-linked account's balance not currently earmarked for any goal: `currentBalance − Σ(currentProgress of that account's active, non-completed goals)`. A withdrawal within free balance needs no goal allocation at all.

**On create:** an optional `goalAllocations: [{goalId, amount}]` is supplied when the withdrawal amount exceeds free balance. Two guards apply at the account level: the sum of allocations cannot exceed the transaction amount, and the unallocated remainder must fit within free balance. If no allocation is supplied and free balance alone is insufficient, the request is rejected (`400`) with a structured payload (`freeBalance`, `shortfall`, `availableGoals` — see §6) rather than a bare message, so the client can build a picker directly from the error.

**On update, when the amount changes**, the existing allocation is reconciled against the new amount in four tiers, tried in order:
1. **Explicit wins.** A `goalAllocations` in the request always replaces whatever was recorded before, in full — never merged.
2. **Reuse if still valid.** If the prior allocation still satisfies both account-level guards against the new amount, it's kept as-is — even where the new amount is larger, with the extra funded from free balance.
3. **Drop if no longer needed.** If the new amount, on its own, fits entirely within free balance, the prior allocation is released to empty and a `warning` explains that the goal money is no longer needed for this transaction.
4. **Ask, never guess.** If neither of the above holds — the prior split doesn't fit, and free balance alone doesn't cover it either — the request is rejected with the same structured payload as create, asking for an explicit new split.

**The dividing line between tiers 3 and 4 is deliberate, not incidental:** tier 3 only fires when the transaction needs *zero* goal help at the new amount — there is nothing left to decide, so continuing to show an allocation would misrepresent the transaction. Tier 4 fires whenever *any* goal help is still needed but the system cannot determine which goal(s) should provide it — that ambiguity is always resolved by asking, never by a default guess. The system never redistributes or reinterprets a user's earmarked goal money without either an explicit instruction or the allocation becoming provably unnecessary.

**Per-goal bounds, independent of the account-level guards above:** a DECREASE (withdrawal) cannot exceed a goal's own `currentProgress`; an INCREASE cannot push `currentProgress` past `targetAmount`. These fire even when the account-level totals would otherwise allow the request — e.g. asking to draw ₹45,000 from a specific goal that only has ₹40,000 earmarked fails here, independently of whether the overall transaction amount and free balance would have permitted it.

**On delete, or on any update that changes the amount,** the transaction's existing allocation rows (and the corresponding goals' `currentProgress`) are fully reversed before anything new is applied — the same "reverse fully, then reapply" pattern BR-05/BR-07 already use for balance and goal-progress corrections, extended to the allocation ledger.

**Allocation-item guards (apply to every `goalAllocations` payload, this rule and BR-20 alike):** each `amount` must be strictly positive, cascaded via `@Valid` on every request DTO into `GoalAllocationItem`'s own constraint — a gap that, until hardened, let a negative amount on a DECREASE reach `applyAllocations` and compute as an increase, though `transaction_goal_allocations`'s own `CHECK (amount > 0)` (present since its first migration) and the `@Transactional` rollback it triggers already prevented that from actually persisting; the fix turns an opaque 500 into a clean 400 rather than closing a real corruption path. A single list also can't name the same `goalId` twice, checked at the application layer ahead of the table's own `UNIQUE (transaction_id, goal_id)` constraint, for the same reason — that check lives once in `GoalAllocationService.applyAllocations`, the shared method behind both this rule and BR-20.

**Deliberately not addressed by this rule (see §11):** crediting a goal via `INCOME` directly, reallocating between two goals without a real transfer, and editing an existing transaction's goal split without also changing its amount. The downward-balance-correction trigger this rule originally left open is now covered separately — see BR-20.

### BR-20: Balance-Correction Goal Allocation *(added v1.5.0)*
The second trigger point of the same regression BR-19 addresses: a downward `PUT /accounts/{id}` correction (BR-02) on a goal-linked account is checked against that account's total earmarked goal progress before it's accepted, reusing BR-19's structured shortfall payload and per-goal guard.

**Trigger:** `earmarked = Σ(currentProgress of the account's active goals)`. If the corrected `currentBalance` is still `≥ earmarked`, the correction only reduces free balance — nothing goal-related happens, same as before this rule existed. Only a balance dropping *below* `earmarked` needs a goal to give something up, since the account no longer physically holds enough to back everyone's earmarked amount.

**Single-shot, not tiered — deliberately simpler than BR-19:** a balance correction is always a brand-new `SETTLEMENT/Adjustment` event (BR-02) each time, never an edit to a prior correction, so there is no existing allocation to reconcile against. Only a validate-or-commit check is needed, not BR-19's four-tier reconciliation.

- No `goalAllocations` supplied, and the new balance is short → `400` with the same `InsufficientFreeBalanceDetails` shape BR-19 uses (`freeBalance`, `shortfall`, `availableGoals`) — `shortfall` here is `earmarked − newBalance`, not the transfer-amount-minus-free-balance formula BR-19 uses, but the same response shape lets a single frontend picker component handle both triggers.
- `goalAllocations` supplied → guard is `sum(allocations) == shortfall`, exactly — unlike BR-19's withdrawal guard, which only caps allocations at the transaction amount (`≤`), this rule requires an exact match, both ways. **Corrected post-ship:** the guard originally allowed `≥ shortfall`, reasoning that covering more than strictly required was a harmless deliberate choice. Testing surfaced that this let a client silently deflate goal progress beyond what the balance change actually required — e.g. a ₹1,000 shortfall "covered" by ₹1,000 from *each* of two goals, turning a ₹1,000 correction into an unexplained ₹2,000 loss of earmarked money. Both under- and over-allocation are now rejected. Each individual amount is still bounded by that goal's own `currentProgress`, via the same per-goal DECREASE guard `GoalAllocationService.applyAllocations` already enforces — this fires independently of the aggregate check, so a goal with only ₹5,000 earmarked can't supply ₹8,000 toward the shortfall even when the combined total across goals would otherwise cover it.
- If `goalAllocations` is supplied but isn't actually needed — the corrected balance still covers `earmarked`, or the balance didn't change at all — the correction still succeeds and returns a `warning` instead of silently discarding the unused selection.
- The resulting allocation rows attach to the `SETTLEMENT/Adjustment` transaction this correction creates — visible in that transaction's `goalAllocations` breakdown exactly like a withdrawal TRANSFER's own allocation rows.

This closes the second half of the originally reported regression: a manual balance correction on a goal-linked account no longer leaves its linked goals' progress silently stale.

---

## 10. Screens-to-API Mapping

| Screen (Figma page) | API calls made |
|---|---|
| Splash / Onboarding (p2–3) | None |
| Sign Up (p4) | `GET /auth/invites/{token}` → `POST /auth/invites/{token}/signup` → `POST /auth/invites/{token}/verify-otp` (invite-only, see §6 Invites) |
| Sign In (p5) | `POST /auth/signin` |
| Get Started — step 1 (p6) | `GET /accounts` |
| Get Started — step 2 (p7) | `GET /planned-amounts` |
| Get Started — step 3 (p8) | `GET /goals` |
| Dashboard — all states (p9–11) | `GET /dashboard` |
| Planned Amounts — empty (p21) | `GET /planned-amounts` ← **Phase 2** |
| Add Planned Amount (p22–23) | `GET /categories`, `POST /planned-amounts` ← **Phase 2** |
| Planned Amounts — list (p24–25) | `GET /planned-amounts` ← **Phase 2** |
| Goals — empty (p26) | `GET /goals` |
| Add Goal (p27) | `GET /accounts`, `POST /goals` |
| Goals — in progress (p28–29) | `GET /goals` |
| Cash Flow — empty (p30) | `GET /api/analytics/cashflow-summary?from=&to=`, `GET /transactions` |
| Add Entry — expense (p31) | `GET /categories`, `GET /accounts`, `POST /transactions` |
| Add Entry — income (p32) | `GET /categories`, `GET /accounts`, `POST /transactions` |
| Add Entry — transfer (p33) | `GET /accounts`, `GET /goals`, `POST /transactions` (with `goalAllocations` on a goal-linked withdrawal — v1.5.0, BR-19) |
| Cash Flow — with data (p34–36) | `GET /api/analytics/cashflow-summary?from=&to=`, `GET /transactions?from=&to=` |
| Accounts — empty (p37) | `GET /accounts` |
| Add Account (p38) | `POST /accounts` |
| Accounts — list (p39) | `GET /accounts` |

---

## 11. Deferred to Post-MVP

These items are consciously not part of the current MVP build. Listed here so research already done isn't silently lost.

**MVP is complete when:** sign up → add accounts → log transactions → track goals → see dashboard → see cashflow analytics works end to end on the frontend.

| Deferred item | Source | Why deferred | Revisit when |
|---|---|---|---|
| `PlannedAmount` module — entire entity, migration, API | Figma p21-25, spec §3.7 | Automation feature — enhances UX but not on the critical path for core cashflow tracking. `onboardingChecklist.plannedAmountsAdded` hardcoded `false` in Dashboard. | Phase 2, after MVP frontend is shipped. |
| Analytics — monthly breakdown `GET /analytics/monthly/{fy}/{month}` | §6 Analytics | Requires `MonthSummary` computation and caching layer to be meaningful. | Phase 2, together with MonthSummary. |
| Analytics — yearly view `GET /analytics/yearly/{fy}` | Cash Flow screen navigation | Year view is a Phase 2 navigation mode. Weekly and Monthly are MVP. | Phase 2, after monthly is working in frontend. |
| `MonthSummary` computation and caching | §3.10 entity exists in spec | `month_summaries` table DDL exists but no computation logic built. `is_dirty` flag mechanism designed but not implemented. | Phase 2, when historical analytics beyond the current period are needed. |
| LLM Insights `POST /insights/generate` | §6 LLM Insights | Requires local Ollama in Docker. Infrastructure not yet set up. Entire `llm/` module not built. | Phase 2, after Docker + Ollama are configured. |
| `CreditCard` entity — bank, card name, billing day, credit limit, carried/bill/carry-forward balance | Excel Accounts/Credit sheet | Structurally different from a simple balance-holding `Account` — has a billing cycle and rolling state across months. | After `Account` + `Transaction` MVP are working. |
| `CC_CREDIT` transaction type + `totalCreditBill` metric | TransactionType enum | No `CreditCard` entity to attach to. Credit card spend logged as normal expense; `REPAYMENT` covers paying off a card. | Together with `CreditCard` entity. |
| `Account.type` Savings/Current sub-classification | Original Excel data | App logic never behaves differently for Savings vs. Current — expressed informally via account `name`. | If a feature ever needs to behave differently per sub-type. |
| Multi-currency support beyond INR | Domain overview | `Account.currency` field exists, defaults to `INR`, no conversion logic. | If/when non-INR user base becomes real. |
| Goal `status` automatic transitions | Goal entity | Status field exists; transitions not automated — set manually. | Phase 2 Goal enhancements. |
| FD/RD (Fixed Deposits / Recurring Deposits) as account types | User discussion | Different financial instrument — lock-in, maturity, interest. Doesn't fit `Account` model. | Separate `Investment` module, post-MVP. |
| `BalanceAfter` snapshot per transaction | Analytics discussion | Considered and deliberately rejected in favour of `currentBalance - sumNetAfterDate` approach for period-end balance queries. No new column needed. | If performance profiling shows the aggregation approach is insufficient at scale. |
| Projected/forecast balance (`GET /planned-amounts/forecast?until=...` or similar) | BR-16 discussion — Excel template's month-to-month carry-forward | Genuinely valuable ("at this rate, you'll have ₹X by date Y"), but deliberately *not* built as future-dated `Transaction` rows (blocked by BR-16) — a forecast is a prediction, not a ledger fact, and the two must never share a table. Belongs on top of `PlannedAmount`: `currentBalance` + sum of active `PlannedAmount` occurrences due before the target date. Month-to-month balance carry-forward itself needs no new work — `account.currentBalance` (BR-05) already accumulates continuously with no monthly reset, unlike the Excel template's per-month-sheet structure. | Phase 2, after `PlannedAmount` is built. |
| `INCOME` crediting a goal directly | Goal allocation design discussion (v1.5.0) | Today only a TRANSFER can move money into or out of a goal (BR-07). Letting `INCOME` credit a goal directly would remove a "log income, then transfer to goal" two-step, but changes BR-07's arrival semantics and needs its own design pass. | Next enhancement iteration. |
| Goal-to-goal reallocation (including same-account, zero-cash-movement reallocation) | Goal allocation design discussion (v1.5.0) | Moving earmarked money between two goals — including two goals sharing one account, where no real balance actually moves — doesn't fit the existing TRANSFER model (BR-15 requires a real destination). Needs its own request/response shape, not an extension of `goalAllocations`. | After INCOME-to-goal crediting (above). |
| Editing a transaction's goal split without changing its amount | Discovered testing v1.5.0's tier-1 override | A dedicated "edit allocation" action, independent of the amount field, for correcting how an already-recorded transaction's fixed amount was split across goals (e.g. it should have been 100% Vacation, not half Emergency Fund) — distinct from BR-19's tiers, which only reconcile allocation against a *changing* amount. Purely additive: needs a new UI affordance and validation (sum of new split = existing amount, each goal's own bound), but doesn't change any existing tier's behavior. | Enhancement iteration, no urgency — doesn't block or alter anything shipped in v1.5.0. |
| Goal deleted while still referenced by a `transaction_goal_allocations` row | Raised during v1.5.0 test planning | `Goal` currently only supports soft delete (`isActive=false`, BR-10) with no hard-delete path, so this can't happen yet as a dangling foreign key — but worth confirming a soft-deleted goal's historical allocations still resolve sensibly (e.g. in `TransactionResponse.goalAllocations`) once a goal-archival UI exists. | If/when a hard-delete or archive UI for goals is built. |

---

*Moneyflow Domain Model & API Contract — v1.5.0*
*Backend MVP complete: auth (invite-only), accounts, transactions (with withdrawal-side goal allocation ledger), goals, dashboard, analytics.*
*PlannedAmount module, LLM insights, MonthSummary computation, yearly analytics — all Phase 2.*
*Analytics query model: plain `from`/`to` date range only, both required (an earlier planned `mode`/`anchor` MONTHLY/WEEKLY design was dropped before frontend integration — see v1.5.0 note below). Analytics module finalised: 4 cards (Income, Expense, Savings+rate, Debt Ratio). Balance card removed — account balance belongs on Dashboard, not in period-filtered cashflow view. Monthly breakdown deferred to Phase 2.*
*Transaction date vs. entry date distinction documented (§3.6): date = when money moved, createdAt = when logged. No speculative computed fields added (loggedLate removed — no screen consumer, not built speculatively).*
*BR-03 extended to cover TRANSFER edit/delete atomicity, not just creation, and fixed to be symmetric across source and destination account on both PUT and DELETE — previously only the source account was reversed, silently duplicating balance on the destination side on delete, and never correcting it at all on update.*
*BR-05 extended with implementation-order constraint for PUT: load old value before overwriting.*
*BR-06 extended with cross-month double-invalidation edge case on date edits.*
*BR-07 goal-progress reversal wired into PUT /transactions/{id} amount corrections — previously only applied on POST and DELETE, so editing the amount on a goal-linked TRANSFER left the goal's currentProgress stale.*
*BR-11 corrected to reflect actual intended behaviour: notes are editable via PUT for all transaction types except SETTLEMENT, not universally immutable as an earlier revision of this doc stated.*
*BR-13 added: transaction backdating warning (7-day threshold, non-blocking, frontend surfaces once per session).*
*BR-14 added: JwtAuthFilter now verifies the JWT's principal still exists before authenticating a request, closing a gap where a token for a deleted user was inconsistently handled per-endpoint (404 on some, silent empty success on others).*
*GET /transactions made pageable (default size 100), with hasPreviousMonthData/hasNextMonthData added for calendar and financial-year month-navigation UI.*
*GET /transactions gained a flowType=INCOME|EXPENSE filter for the Cash Flow screen, composing with the calendar/FY period filters and respected by the month-navigation hints.*
*to_account_id now always stored for a TRANSFER, not just when the destination was picked as a direct account — fixes a bug where deleting a goal-linked TRANSFER never reversed the goal's account balance, since the column was left NULL for that path. Schema CHECK constraint relaxed to match: a TRANSFER just needs to_account_id set, with to_goal_id as optional metadata rather than a mutually-exclusive alternative.*
*BR-07 extended to cover goal-progress decreasing when a TRANSFER's source account is itself goal-linked (withdrawal), not just increasing on arrival — previously only createTransaction's arrival path was wired up, so withdrawing from a goal account never corrected its currentProgress.*
*BR-15 added: POST /transactions rejects a TRANSFER whose destination resolves to the same account as the source, whether reached via toAccountId or toGoalId — closes a gap where transferring into one's own goal-linked account inflated currentProgress with no real balance movement.*
*GET /accounts and GET /accounts/{id} gained a response-only goalLinked flag, resolved via the same active/non-completed goal rule as BR-12, batch-computed for the list endpoint to avoid an N+1 query per account.*
*GET /transactions sort param clarified as Spring Data's property,direction convention — a separate direction param was silently ignored (Spring defaults to ascending when sort has no embedded direction), which was the root cause of a list appearing unsorted despite an explicit direction being sent.*
*Dashboard balancePercentage design note added (§6): confirmed as an intentional solvency/safety-margin indicator (totalBalance ÷ this-month's-income, can exceed 100%), directly inherited from the original Excel template's TOTAL BALANCE cell formula and its hand-tuned conditional-formatting thresholds — not a bug, and not to be reworked into a bounded battery-style gauge. Display bands (>30 green, 20–30 amber, ≤20 red) are frontend-only, no backend change.*
*BR-16 added: transaction dates cannot be in the future, hard-blocked on both POST and PUT — closes a gap where a future-dated entry silently understated currentBalance today, since balance effects apply immediately on creation (BR-05). Deliberately asymmetric with BR-13 (backdating is warned, not blocked; postdating has no equivalent real-world justification). Future balance projection/forecasting noted as a legitimate but separate, deferred idea (§11) — belongs on PlannedAmount as a computed forecast, never as a future-dated Transaction row; month-to-month balance carry-forward itself needs no new work since currentBalance already accumulates continuously with no monthly reset.*
*BR-17 added: categories can now be isInternal (new seed row cat-35 "Opening Balance"), hidden from GET /categories and rejected identically to a nonexistent category (not a distinguishable error, matching /auth/signin's enumeration-safety precedent) if a client tries to submit one via POST/PUT /transactions. BR-01 switched from sharing Adjustment (cat-02) with BR-02 to this new dedicated category, making the two distinguishable by a stable foreign key instead of by matching notes text — closes the loop that made the balancePercentage fix below possible.*
*Dashboard balancePercentage extended to add this month's Opening-Balance-categorized SETTLEMENT total to the denominator (previously totalIncomeThisMonth alone, which read as a false 0%/critical on onboarding day before any INCOME transaction existed) and now returns null instead of 0 when the denominator is zero — null meaning "not enough data," distinct from the low/red display band. savedThisMonth corrected to sum only TRANSFER rows with toGoalId set — a plain account-to-account transfer no longer inflates it. BR-02's balance-adjustment notes now format both amounts through stripTrailingZeros().toPlainString() so they never show mismatched decimal precision on one side.*
*GET /transactions month-navigation hints replaced: hasPreviousMonthData/hasNextMonthData (booleans, adjacent-month-only) → previousPeriod/nextPeriod (resolved period objects, or null). Root cause: BR-13 backdating can leave empty months between "now" and an older backdated entry, and the boolean check only ever looked at the literally-adjacent month — the arrow would disable at the first empty month, permanently hiding real data beyond it. The new fields resolve to the nearest period that actually has data, skipping gaps automatically; calendar lens does this via a single indexed date-range query, FY lens via a bounded (24-step) walk through FinancialYearUtil.previous/next to avoid guessing which century a two-digit FY label belongs to. The now-unused PeriodFilter record and resolvePeriod method were removed as part of this change; the previously-dead hasData helper is now used by the FY-lens walk. cat-35's icon changed 🏁 → 🏛️. Verified integrated on the frontend.*
*New GET /transactions/available-periods: returns every year and month (calendar lens only) that has at least one transaction, powering a year-based month picker as a companion to the smart-skip arrows — for genuinely distant backdating where even a smart-skip arrow means several clicks. Deliberately returns the full years+months structure in one response rather than a per-year `?year=` param, since total data volume is small for a single-user app; "default to current year" is left to the frontend, which already knows what year "today" is.*
*v1.4.0: Onboarding is invite-only now — see BR-18.*
*v1.5.0: Goal allocation ledger added, covering both trigger points of the originally reported regression. BR-19 (transaction side): a TRANSFER drawing from a goal-linked account beyond its free balance records which goal(s) it drew from via a new transaction_goal_allocations table, replacing the previous singular per-account goal assumption. Four-tier reconciliation on amount edits (explicit > reuse-if-valid > drop-if-unneeded > ask). Structured 400 payload (freeBalance/shortfall/availableGoals) added for the insufficient-balance case. Goal allocation increases now capped at targetAmount, mirroring the existing cap on decreases at currentProgress. BR-20 (balance-correction side): a downward PUT /accounts/{id} correction below an account's earmarked goal total now requires (and applies) an explicit goal reduction, reusing BR-19's structured payload and per-goal guard, with a single validate-or-commit check rather than BR-19's tiers, since each correction is a standalone SETTLEMENT/Adjustment event, never an edit to a prior one. Both halves verified end-to-end (create/update/delete, all four tiers, both account- and goal-level guards, plus BR-20's upward/no-op/insufficient/over-reduction/per-goal-guard cases) against real data before shipping. Hardened the same day, still v1.5.0: BR-20's guard tightened from "at least the shortfall" to exactly the shortfall, after testing showed over-allocation could silently deflate goal progress beyond what a correction actually required; GoalAllocationItem.amount gained a positivity constraint with @Valid cascading into it from all three request DTOs — a gap that would have surfaced as an opaque 500 rather than actual corruption, since transaction_goal_allocations' own CHECK(amount > 0) and the @Transactional rollback it triggers already prevented persistence; a goalAllocations list can no longer repeat the same goalId, same reasoning against the table's UNIQUE(transaction_id, goal_id); PUT /accounts/{id} now returns a warning instead of silently discarding an unnecessary goalAllocations selection; and GlobalExceptionHandler's validation-error messages dropped their technical field-path prefix, since every constraint message across the API already reads as a complete sentence on its own. Frontend integration surfaced one more real gap the same day: Dashboard's savedThisMonth and Analytics' cashflow-summary savings were both still the original gross TRANSFER-to-goal figure, exactly the "deliberately not netted... if ever needed later" case the v1.4.0 note had flagged and deferred — now that the allocation ledger exists, both were corrected to subtract GoalAllocationService.sumWithdrawalsByDateRange/sumWithdrawalsByCalendarMonth from the gross deposit sum, so a same-period BR-19/BR-20 withdrawal now visibly reduces reported savings instead of vanishing from the metric. Dashboard's savingsMessage was corrected alongside it: a sub-rupee remaining amount (now realistic once real paise are being netted) previously floored to a nonsensical "You can save ₹0 today!" — fixed with a sub-₹1 "already met" threshold — and the remaining-amount branch switched from FLOOR to CEILING so it never understates what's actually needed to hit the exact target.*
*v1.5.0 (same day): GET /transactions gained from/to date-range filtering (inclusive both ends, via a new TransactionSpecifications.inDateRange) to close a gap where only calendarYear/calendarMonth and financialYear/financialMonth existed and there was no way to filter by week. calendarYear/calendarMonth and financialYear/financialMonth are retained unchanged, since they carry real FY-math and smart-skip-navigation logic a client shouldn't reimplement; combining from/to with either legacy pair now returns 400 rather than silently picking one, and from/to must be supplied together with from <= to. GET /analytics/cashflow-summary's originally-planned mode/anchor (MONTHLY/WEEKLY) + CUSTOM query design was dropped before any frontend integration, in favor of the same plain from/to contract, once designing the transactions-side fix showed the mode-resolution logic (Monday-Sunday, 1st-to-last-day) was a one-line client computation with no business rule behind it — not worth maintaining server-side, especially since WEEKLY mode would have had no smart prev/next navigation anyway, unlike calendarYear/calendarMonth. Both endpoints now require from/to together and reject from > to with a consistent ApiException-shaped 400, not a framework-default error body. Embedding these KPIs directly into GET /transactions's response (considered, then shelved, during this same investigation) was deliberately deferred rather than built — parked for if/when a dedicated performance-metrics feature needs both the transaction rows and their aggregate in one call.*
*v1.5.0 (same day): GET /transactions/available-periods gained earliestTransactionDate (a single MIN(date) across the user's transactions, null if none exist), giving the frontend the back-navigation boundary that the new from/to query model has no smart-skip logic of its own to provide. One shared value serves both GET /transactions and GET /analytics/cashflow-summary, since the latter has no data independent of the transactions table — there's no separate "earliest date analytics has" to compute. Used two ways on the frontend: gating the back arrow directly, and truncating a per-month week-list picker's earliest month at this exact date rather than at that month's calendar start, since monthsByYear only proves a month has some data, not that every week within it does.*
*Next: Analytics module frontend integration → Ionic frontend migration (Strapi → Moneyflow Spring Boot API).*
</content>

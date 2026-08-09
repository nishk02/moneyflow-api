# Moneyflow — Domain Model & API Contract Specification
**Version:** 1.1.0  
**Derived from:** Figma screens (39 pages), Excel cashflow template (FY24-25), User journey map  
**Purpose:** Complete build specification before writing any Java code  
**Architecture:** Modular monolith · Spring Boot 3 · SQLite · Financial year April–March

### Changelog
| Version | Change |
|---|---|
| 1.0.0 | Initial specification |
| 1.1.0 | Removed income profiling wizard. Income tracked naturally via transactions. Simplified UserProfile to preferences only. Removed OtherIncome entity. |
| 1.2.0 | Backend MVP complete. Analytics finalised (mode/anchor query model). PlannedAmount module deferred to Phase 2. Onboarding step 2 (planned amounts) hardcoded false. Transaction backdating warning (BR-13) added. All implemented modules documented accurately. |

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

**Note on SETTLEMENT — design rationale:** `SETTLEMENT` is never created directly by a user filling out the Add Entry form. It is always system-generated, in exactly two scenarios, both covered in §9 (BR-01, BR-02). This mirrors the **Adjustment Method** used by real banks for correcting reconciliation errors: the original record is never edited; a new entry is appended that bridges the gap to the correct balance, with the discrepancy and resolution captured in that new entry's description — never silently merged into prior history. This is why `Transaction.notes` is immutable once created (see §9, BR-11): financial records are append-only, not mutated, matching standard banking reconciliation practice.

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
| `toGoalId` | UUID | FK to Goal — nullable, used for TRANSFER to a goal |
| `amount` | BigDecimal | Always stored as positive. Sign derived from type at read time. |
| `notes` | String | Description, set once at creation. Never editable — see BR-11. |
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

**Immutability:** `notes` is set once at creation and never updated thereafter, including via `PUT /transactions/{id}` (that endpoint may correct `amount`, `category`, `date` — never `notes`). This is deliberate, matching real bank reconciliation practice: corrections append a new record rather than rewriting history. See BR-11.

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
| `targetAmount` | BigDecimal | Total amount to reach |
| `accountId` | UUID | FK to Account where goal savings are held. Any account type allowed — see BR-12 for protection behaviour. |
| `startDate` | LocalDate | |
| `endDate` | LocalDate | Drives monthly savings calculation |
| `currentProgress` | BigDecimal | Updated when TRANSFER transactions post to this goal |
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
    onboarding_step INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
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
    -- Transfer must go somewhere but not both
    CHECK(
        type != 'TRANSFER' OR (
            (to_account_id IS NOT NULL AND to_goal_id IS NULL) OR
            (to_account_id IS NULL AND to_goal_id IS NOT NULL)
        )
    )
);

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
│   ├── AuthController.java
│   ├── AuthService.java
│   ├── JwtUtil.java
│   ├── dto/
│   │   ├── SignUpRequest.java
│   │   ├── SignInRequest.java
│   │   └── AuthResponse.java
│   └── User.java                     @Entity
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
│   ├── TransactionService.java       Balance updates, FY derivation
│   ├── TransactionRepository.java
│   ├── dto/
│   │   ├── CreateTransactionRequest.java
│   │   └── TransactionResponse.java
│   └── Transaction.java              @Entity
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
│   ├── GoalRepository.java
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
    │   ├── SecurityConfig.java
    │   ├── JwtAuthFilter.java
    │   └── UserDetailsServiceImpl.java
    ├── exception/
    │   ├── GlobalExceptionHandler.java
    │   ├── ApiException.java
    │   └── ErrorCode.java
    ├── dto/
    │   └── ApiResponse.java          Standard envelope for all responses
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

All endpoints require `Authorization: Bearer {jwt}` except `/auth/**`.

---

### Auth

#### POST /auth/signup
```json
{
  "firstName": "Nishant",
  "lastName": "Sharma",
  "email": "nishant@example.com",
  "password": "SecurePass123!",
  "confirmPassword": "SecurePass123!"
}
```
**Response 201:**
```json
{
  "data": {
    "token": "eyJhbGci...",
    "user": { "id": "uuid", "firstName": "Nishant", "email": "nishant@example.com", "onboardingStep": 0 }
  }
}
```
Validations: email unique · password min 8 chars + mixed case + digit · passwords match  
Side effect: creates `UserProfile` record with defaults

---

#### POST /auth/signin
```json
{ "email": "nishant@example.com", "password": "SecurePass123!" }
```
**Response 200:** same shape as signup  
**401:** `INVALID_CREDENTIALS`

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
      { "id": "uuid", "name": "MAXIS Bank", "type": "CASH", "currentBalance": 35000.00, "colorLabel": "#E8A04D" },
      { "id": "uuid", "name": "India Bank", "type": "BANK", "currentBalance": 3457.55, "colorLabel": "#4A90D9" }
    ],
    "totalBalance": 38457.55
  }
}
```

#### GET /accounts/{id}
#### PUT /accounts/{id} — name, type, colorLabel. Balance not directly editable.
#### DELETE /accounts/{id} — soft delete. Reject if account has transactions.

---

### Transactions

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
    "plannedAmountId": null
  },
  "warning": "This transaction is dated before your account was set up (2026-08-08). Your opening balance reflects your balance as of setup date — consider updating it if needed."
}
```

`warning` is absent (`@JsonInclude NON_NULL`) on normal transactions. Only appears when BR-13 condition is met. Frontend shows it as a toast notification — once per account per session, not on every backdated transaction.

**Side effects on every POST:**
1. Update `account.currentBalance` (debit source, credit destination for transfers)
2. Update `goal.currentProgress` if `toGoalId` set (BR-07)
3. Set `month_summaries.is_dirty = true` for the affected month (BR-06)
4. Auto-advance `planned_amounts.next_due_date` if `is_planned = true` (Phase 2)

---

#### GET /transactions
Powers the Cash Flow list with date grouping.

**Query params:**
```
month=6&year=2024            filter by calendar month/year
financialYear=FY24-25        filter by financial year
type=INCOME                  filter by single type
direction=EXPENSE            INCOME or EXPENSE (groups related types)
accountId=uuid               filter by account
page=0&size=20
sort=date,desc
```

**Response 200:**
```json
{
  "data": {
    "transactions": [ ... ],
    "pagination": { "page": 0, "size": 20, "total": 47 }
  }
}
```

#### GET /transactions/{id}
#### PUT /transactions/{id} — recalculates balances if amount or account changed
#### DELETE /transactions/{id} — reverses all balance and goal side effects

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

`savingsMessage` logic:
- If `savedThisMonth >= monthlyTarget` → `"🎯 You are all set! Just hold on to it 💰"`
- Else → `"You can save ₹{monthlyTarget - savedThisMonth} today!"`

---

### Analytics

#### GET /analytics/cashflow-summary
Powers the 4 summary cards on the Cash Flow screen.

**Query params — three modes:**

**Mode 1 — Monthly navigation (default):**
```
?mode=MONTHLY&anchor=2026-07-15
```
Resolves to the 1st–last day of the month containing `anchor`. Default when no params: current calendar month.

**Mode 2 — Weekly navigation:**
```
?mode=WEEKLY&anchor=2026-07-28
```
Resolves to Monday–Sunday of the week containing `anchor`.

**Mode 3 — Custom date range:**
```
?mode=CUSTOM&from=2026-06-01&to=2026-08-31
```
Uses dates directly. Maximum range: 1 year (enforced on frontend).

**Why anchor not preset strings:** The frontend owns navigation state (current anchor date). It increments/decrements the anchor by 7 days (weekly) or 1 month (monthly) when user taps arrows. The backend is stateless — it just computes the range from the anchor. No magic strings like `THIS_MONTH` / `LAST_MONTH`.

**The response includes the resolved period** so Angular can display "July 2026" or "27 Jul – 2 Aug" without computing it client-side:

**The four cards and what drives them:**

| Card | Formula | Color coding |
|---|---|---|
| Income | SUM of `INCOME` transactions in period | neutral |
| Expense | SUM of `FIXED_EXPENSE` + `VARIABLE_EXPENSE` in period | neutral |
| Savings | SUM of `TRANSFER` to goal in period + savings rate % | > 20% green · 10-20% yellow · < 10% red |
| Debt Ratio | SUM of `REPAYMENT` / SUM of `INCOME` × 100 | < 30% green · 30-50% yellow · > 50% red |

**Savings rate** displayed inside the Savings card — not a separate card.

**Debt Ratio** covers all repayments — EMIs to banks and repayments to individuals alike.

**Special cases:**
- `INCOME = 0` → savings rate and debt ratio show `null` (not 0%)
- `REPAYMENT = 0` → debt ratio shows `0.0` (green)

```json
{
  "data": {
    "period": {
      "from": "2026-07-01",
      "to": "2026-07-31",
      "mode": "MONTHLY"
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
All active categories. Called once on app load, cached on the Angular side.

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
| Balance percentage | `balance / totalIncome × 100` | Dashboard ring, Cash Flow header |
| Daily expense limit | `balance / daysRemainingInMonth` | Monthly summary |
| Debt-income ratio | `totalDebt / totalIncome × 100` | Monthly summary |
| Savings rate | `totalSavings / totalIncome × 100` | Monthly summary |
| Goal monthly required | `(targetAmount - currentProgress) / monthsToEnd` | Goal card |
| Goal progress % | `currentProgress / targetAmount × 100` | Goal progress bar |
| Days until due | `nextDueDate - today` | Planned amounts list |
| Display due string | `≤ 30 days → "in X days"`, `> 30 days → "5 August"` | Planned amounts list |
| Financial year label | See BR-04 below | Transaction, summary |
| FY month (1-based April) | `date.month >= 4 ? date.month - 3 : date.month + 9` | Transaction, summary |

---

## 8. Seed Data

Applied via Flyway migration `V1__seed_categories.sql` on first launch.

```sql
INSERT INTO categories (id, name, icon, is_system, is_active, display_order) VALUES
('cat-01','Account','📊',1,1,1),
('cat-02','Adjustment','🔄',1,1,2),
('cat-03','Allowances','💰',1,1,3),
('cat-04','Cashback','💸',1,1,4),
('cat-05','CC Payment','💳',1,1,5),
('cat-06','Clothing','👔',1,1,6),
('cat-07','Donation','🤝',1,1,7),
('cat-08','Family & Friends','👨‍👩‍👧',1,1,8),
('cat-09','Food & Beverages','🍔',1,1,9),
('cat-10','Fuel','⛽',1,1,10),
('cat-11','Gifts','🎁',1,1,11),
('cat-12','Groceries','🛒',1,1,12),
('cat-13','Grooming','💈',1,1,13),
('cat-14','Healthcare','🏥',1,1,14),
('cat-15','Household','🏠',1,1,15),
('cat-16','Insurance','🛡',1,1,16),
('cat-17','Investments','📈',1,1,17),
('cat-18','Loan','🏦',1,1,18),
('cat-19','Miscellaneous','📦',1,1,19),
('cat-20','Paycheck','💵',1,1,20),
('cat-21','Pets','🐾',1,1,21),
('cat-22','Phone','📱',1,1,22),
('cat-23','Refreshment','😋',1,1,23),
('cat-24','Refund','↩️',1,1,24),
('cat-25','Rent','🏠',1,1,25),
('cat-26','Restaurant','🍽',1,1,26),
('cat-27','Savings','🐷',1,1,27),
('cat-28','Subscriptions','🔔',1,1,28),
('cat-29','Telephone','☎️',1,1,29),
('cat-30','Tips','🤌',1,1,30),
('cat-31','Transport','🚌',1,1,31),
('cat-32','Utilities','💡',1,1,32),
('cat-33','Vehicle','🚗',1,1,33),
('cat-34','Wellness','🧘',1,1,34);
```

---

## 9. Key Business Rules

### BR-01: Opening Balance Auto-Transaction
When an account is **created** (`POST /accounts`) with `currentBalance > 0`, the service automatically creates a `SETTLEMENT` transaction, category `Adjustment`, dated today, `notes = "Opening balance"`. This is what appears as "Current Balance / Adjustment MAXIS Bank" in the Figma Cash Flow list. The user never creates this manually. Amount is always positive (enforced by `@DecimalMin(0)` on the request).

### BR-02: Balance Correction Auto-Transaction
When an existing account's `currentBalance` is **edited** (`PUT /accounts/{id}` with a `currentBalance` differing from the stored value), the service automatically creates a second `SETTLEMENT` transaction for the delta, category `Adjustment`, dated today, `notes = "Balance adjustment: ₹{old} → ₹{new}"`. Unlike BR-01, this delta may be negative (a downward correction). Triggered by deliberate user action — editing the account, not the general-purpose Add Entry form — but the transaction itself is system-generated, not user-typed.

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

**On PUT (amount correction):** load the old stored amount *before* overwriting the row — reverse the old amount's effect on `currentBalance`, then apply the new amount's effect. The old value must be captured before it's overwritten; this is an implementation-order constraint, not just a design one.

**On DELETE:** reverse the stored amount's exact effect. Never re-derive the amount from anywhere — use what was actually stored on that row.

### BR-06: Month Summary Invalidation
Any create, update, or delete on a transaction sets `month_summaries.is_dirty = true` for the affected month(s). Summaries are recomputed lazily on next `GET /analytics/**` call for that month, or eagerly via a background recalculation.

**Cross-month edge case:** if a `PUT /transactions/{id}` changes `date` such that the transaction moves from one month/FY to another — e.g. correcting a June entry to May — **both** months must be invalidated: the old month (a transaction left it, its totals decreased) and the new month (a transaction arrived, its totals increased). Setting `is_dirty = true` on only one of the two months silently leaves the other month's cached summary permanently wrong.

### BR-07: Goal Progress Source
`goal.currentProgress` only increases via TRANSFER transactions with `toGoalId` set. No direct PUT endpoint for `currentProgress`. Deleting such a transaction decrements the goal progress. Editing the amount on such a transaction reverses the old contribution and applies the new one (same reversal pattern as BR-05, applied to `currentProgress` instead of `currentBalance`).

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
`Transaction.notes` is set once at creation and is never updatable thereafter — not via `PUT /transactions/{id}`, not via any other endpoint. Applies to SETTLEMENT auto-transactions specifically (system-generated notes must never be altered). User-created transactions allow notes updates for non-SETTLEMENT types via the service layer check. Matches standard bank reconciliation practice.

### BR-12: Goal-Linked Accounts Are Protected From Direct Expense Transactions
An account linked to at least one active, non-completed goal (`isActive = true`, `status != 'COMPLETED'`) is a **protected account**. The following transaction types are blocked when `accountId` resolves to a protected account:

```
BLOCKED:  FIXED_EXPENSE, VARIABLE_EXPENSE, LENDING, BORROWING, REPAYMENT
ALLOWED:  INCOME, TRANSFER, SETTLEMENT
```

**Implementation:** `TransactionService.createTransaction` checks via `GoalRepository.existsByAccountIdAndActiveTrueAndStatusNot(accountId, "COMPLETED")`.

### BR-13: Transaction Backdating Warning
When a transaction's `date` is more than 7 days before the `account.createdAt` date (the account setup date), the API returns an optional `warning` field alongside the normal success response. The transaction is NOT blocked — the user may legitimately be reconstructing history from a bank statement. The warning informs them that their opening balance may need updating to reflect the historical transaction.

**Why 7 days:** Minor date differences (timezone, bank processing delay) should not trigger warnings. Significant backdating (more than a week before setup) is the meaningful case.

**Frontend responsibility:** Show the warning as a toast notification once per account per session. Suppress subsequent warnings for the same account to avoid repetitive friction during bulk historical entry.

### BR-12: Goal-Linked Accounts Are Protected From Direct Expense Transactions
An account linked to at least one active, non-completed goal (`isActive = true`, `status != 'COMPLETED'`) is a **protected account**. The following transaction types are blocked when `accountId` resolves to a protected account:

```
BLOCKED:  FIXED_EXPENSE, VARIABLE_EXPENSE, LENDING, BORROWING, REPAYMENT
ALLOWED:  INCOME, TRANSFER, SETTLEMENT
```

**Why blocked types are blocked:** These represent money leaving the account in ways that bypass the goal's intended purpose. Blocking them forces the user to make a conscious two-step decision — TRANSFER to a spending account first, then log the expense — keeping the withdrawal tracked and deliberate.

**Why allowed types are allowed:**
- `INCOME` — money arriving into the account is always fine. No salary designation concept exists in Moneyflow; a user may receive income directly into their savings account.
- `TRANSFER` — this is the correct mechanism for moving money between accounts, including withdrawing from a goal account back to a spending account. Always tracked, always visible in history.
- `SETTLEMENT` — system-generated (BR-01/BR-02), never user-initiated via Add Entry form.

**Implementation:** `TransactionService.createTransaction` checks whether the source `account` is goal-linked before accepting the transaction. Uses `GoalRepository.existsByAccountIdAndActiveTrueAndStatusNot(accountId, "COMPLETED")`.

**No account type constraint:** Any account type (`CASH`, `BANK`, `WALLET`) may back a goal. The protection comes from the goal linkage, not the account type. FDs and RDs are out of scope for MVP — see §11.

---

## 10. Screens-to-API Mapping

| Screen (Figma page) | API calls made |
|---|---|
| Splash / Onboarding (p2–3) | None |
| Sign Up (p4) | `POST /auth/signup` |
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
| Cash Flow — empty (p30) | `GET /api/analytics/cashflow-summary?mode=MONTHLY`, `GET /transactions` |
| Add Entry — expense (p31) | `GET /categories`, `GET /accounts`, `POST /transactions` |
| Add Entry — income (p32) | `GET /categories`, `GET /accounts`, `POST /transactions` |
| Add Entry — transfer (p33) | `GET /accounts`, `GET /goals`, `POST /transactions` |
| Cash Flow — with data (p34–36) | `GET /api/analytics/cashflow-summary?mode=MONTHLY&anchor=`, `GET /transactions?mode=MONTHLY&anchor=` |
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

---

*Moneyflow Domain Model & API Contract — v1.2.0*
*Backend MVP complete: auth, accounts, transactions, goals, dashboard, analytics.*
*PlannedAmount module, LLM insights, MonthSummary computation, yearly analytics — all Phase 2.*
*Analytics query model: mode/anchor (MONTHLY/WEEKLY) + CUSTOM from/to date range.*
*BR-13 added: transaction backdating warning (7-day threshold, non-blocking, frontend surfaces once per session).*
*Next: Ionic frontend migration — Strapi → MoneyFlow Spring Boot API.*
*Transaction date vs. entry date distinction documented (§3.6): date = when money moved, createdAt = when logged. No speculative computed fields added (loggedLate removed — no screen consumer, not built speculatively).*
*BR-03 extended to cover TRANSFER edit/delete atomicity, not just creation.*
*BR-05 extended with implementation-order constraint for PUT: load old value before overwriting.*
*BR-06 extended with cross-month double-invalidation edge case on date edits.*
*BR-07 extended to cover goal progress reversal on TRANSFER amount correction.*
*Analytics module finalised: 4 cards (Income, Expense, Savings+rate, Debt Ratio). Balance card removed — account balance belongs on Dashboard, not in period-filtered cashflow view. Date range query model: from/to params + preset period shortcuts. Monthly breakdown deferred to Phase 2.*
*Next: Analytics module implementation → frontend integration*

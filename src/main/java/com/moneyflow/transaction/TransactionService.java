package com.moneyflow.transaction;

import com.moneyflow.account.Account;
import com.moneyflow.account.AccountRepository;
import com.moneyflow.auth.User;
import com.moneyflow.auth.UserRepository;
import com.moneyflow.category.Category;
import com.moneyflow.category.CategoryRepository;
import com.moneyflow.goal.Goal;
import com.moneyflow.goal.GoalRepository;
import com.moneyflow.shared.dto.PageResponse;
import com.moneyflow.shared.exception.ApiException;
import com.moneyflow.shared.util.FinancialYearUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class TransactionService {
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final GoalRepository goalRepository;

    private static final Set<String> SORTABLE_PROPERTIES = Set.of("date", "amount", "createdAt");

    private void validateSort(Sort sort) {
        sort.forEach(order -> {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw ApiException.badRequest("Cannot sort by '" + order.getProperty() + "'");
            }
        });
    }

    @Transactional(readOnly = true)
    public TransactionListResponse getTransactions(
            String userId, Integer calendarYear, Integer calendarMonth,
            String financialYear, String financialMonth, FlowType flowType, Pageable pageable) {

        PeriodFilter period = resolvePeriod(calendarYear, calendarMonth, financialYear, financialMonth);

        validateSort(pageable.getSort());

        Specification<Transaction> spec = combine(userId, period.current(), flowType);

        Page<TransactionResponse> page = transactionRepository.findAll(spec, pageable)
                .map(TransactionResponse::from);

        if (period.previous() == null) {
            return TransactionListResponse.of(PageResponse.from(page));
        }

        boolean hasPrevious = transactionRepository.exists(combine(userId, period.previous(), flowType));
        boolean hasNext = transactionRepository.exists(combine(userId, period.next(), flowType));
        return TransactionListResponse.of(PageResponse.from(page), hasPrevious, hasNext);
    }

    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(String userId, String id) {
        return transactionRepository.findByIdAndUserId(id, userId)
                .map(TransactionResponse::from)
                .orElseThrow(() -> ApiException.notFound("Transaction not found"));
    }

    @Transactional
    public TransactionResult createTransaction(String userId, CreateTransactionRequest request) {
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("User not found"));

        Account account = accountRepository.findByIdAndUserId(request.accountId(), userId)
                .orElseThrow(() -> ApiException.notFound("Account not found"));

        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> ApiException.notFound("Category not found"));

        // BR-12: block direct expense transactions against goal-linked accounts
        if (isGoalProtectedType(request.type())
                && goalRepository.existsByAccountIdAndActiveTrueAndStatusNot(account.getId(), "COMPLETED")) {
            throw ApiException.badRequest(
                    "This account is linked to an active savings goals. " +
                            " Use TRANSFER to move money out, or choose a different account.");
        }

        validateTransferDestination(request);

        Account toAccount = resolveToAccount(request, userId);
        if (toAccount != null && toAccount.getId().equals(account.getId())) {
            throw ApiException.badRequest("Transfer destination cannot be the same account you're transferring from");
        }

        Transaction transaction = buildTransaction(user, account, category, toAccount, request);

        applyBalanceEffect(transaction, account, toAccount, request.amount());

        accountRepository.save(account);
        if (toAccount != null) accountRepository.save(toAccount);

        // BR-07: update goal progress when TRANSFER targets a goal
        applyGoalProgress(transaction, userId);

        Transaction saved = transactionRepository.save(transaction);
        TransactionResponse response = TransactionResponse.from(saved);

        // Only warn if backdated more than 7 days before account setup
        LocalDate setupDate = account.getCreatedAt().toLocalDate();
        String warning = request.date().isBefore(setupDate.minusDays(7))
                ? "This transaction is dated before your account was " +
                "set up (" + account.getCreatedAt().toLocalDate() + "). " +
                "Your opening balance reflects your balance as of setup " +
                "date — consider updating it if needed."
                : null;

        return new TransactionResult(response, warning);
    }

    @Transactional
    public TransactionResponse updateTransaction(String userId, String id, UpdateTransactionRequest request) {
        Transaction transaction = transactionRepository
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("Transaction not found"));

        Account account = transaction.getAccount();
        Account toAccount = transaction.getToAccount();

        reverseBalanceEffect(transaction, account, toAccount);
        reverseGoalProgress(transaction, userId);

        if (request.amount() != null) transaction.setAmount(request.amount());
        if (request.date() != null) {
            transaction.setDate(request.date());
            FinancialYearUtil.applyDerivedDateFields(transaction, request.date());
        }
        if (request.categoryId() != null) {
            Category category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> ApiException.notFound("Category not found"));
            transaction.setCategory(category);
        }
        if (request.notes() != null
                && transaction.getType() != TransactionType.SETTLEMENT) {
            transaction.setNotes(request.notes());
        }

        applyBalanceEffect(transaction, account, toAccount, transaction.getAmount());
        applyGoalProgress(transaction, userId);

        accountRepository.save(account);
        if (toAccount != null) accountRepository.save(toAccount);

        Transaction saved = transactionRepository.save(transaction);
        return TransactionResponse.from(saved);
    }

    @Transactional
    public void deleteTransaction(String userId, String id) {
        Transaction transaction = transactionRepository
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("Transaction not found"));

        Account account = transaction.getAccount();
        Account toAccount = transaction.getToAccount();

        reverseBalanceEffect(transaction, account, toAccount);
        // BR-07: reverse goal progress on delete
        reverseGoalProgress(transaction, userId);

        accountRepository.save(account);
        if (toAccount != null) accountRepository.save(toAccount);

        transactionRepository.delete(transaction);
    }

    // BR-01/BR-02: System-generated SETTLEMENT transactions

    @Transactional
    public void createOpeningBalanceSettlement(Account account, User user) {
        Category adjustmentCategory = categoryRepository
                .findById("cat-02")
                .orElseThrow(() -> ApiException.notFound("Adjustment category not found"));

        Transaction t = new Transaction();
        t.setUser(user);
        t.setAccount(account);
        t.setCategory(adjustmentCategory);
        t.setType(TransactionType.SETTLEMENT);
        t.setAmount(account.getCurrentBalance());
        t.setNotes("Opening balance");
        t.setDate(LocalDate.now());
        t.setPlanned(false);
        FinancialYearUtil.applyDerivedDateFields(t, LocalDate.now());

        transactionRepository.save(t);
    }

    @Transactional
    public void createBalanceCorrectionSettlement(
            Account account, User user, BigDecimal oldBalance, BigDecimal newBalance) {
        Category adjustmentCategory = categoryRepository
                .findById("cat-02")
                .orElseThrow(() -> ApiException.notFound("Adjustment category not found"));

        BigDecimal delta = newBalance.subtract(oldBalance).abs();

        Transaction t = new Transaction();
        t.setUser(user);
        t.setAccount(account);
        t.setCategory(adjustmentCategory);
        t.setType(TransactionType.SETTLEMENT);
        t.setAmount(delta);
        t.setNotes(String.format("Balance adjustment: ₹%s → ₹%s", oldBalance, newBalance));
        t.setDate(LocalDate.now());
        t.setPlanned(false);
        FinancialYearUtil.applyDerivedDateFields(t, LocalDate.now());

        transactionRepository.save(t);
    }

    // Internal helpers

    private void validateTransferDestination(CreateTransactionRequest request) {
        if (request.type() == TransactionType.TRANSFER) {
            boolean hasAccount = request.toAccountId() != null;
            boolean hasGoal = request.toGoalId() != null;
            if (!hasAccount && !hasGoal) {
                throw ApiException.badRequest("Transfer requires a destination account or goal");
            }
            if (hasAccount && hasGoal) {
                throw ApiException.badRequest("Transfer cannot have both a destination account and a goal");
            }
        }
    }

    private Account resolveToAccount(CreateTransactionRequest request, String userId) {
        if (request.toAccountId() != null) {
            Account toAccount = accountRepository.findByIdAndUserId(request.toAccountId(), userId)
                    .orElseThrow(() -> ApiException.notFound("Destination account not found"));
            if (!toAccount.isActive()) {
                throw ApiException.badRequest("Destination account is inactive");
            }
            return toAccount;
        }

        if (request.toGoalId() != null) {
            Goal goal = goalRepository.findByIdAndUserId(request.toGoalId(), userId)
                    .orElseThrow(() -> ApiException.notFound("Goal not found"));
            if (!goal.isActive()) {
                throw ApiException.badRequest("Goal is inactive");
            }
            if ("COMPLETED".equals(goal.getStatus())) {
                throw ApiException.badRequest("Cannot transfer to a completed goal");
            }
            Account goalAccount = goal.getAccount();
            if (!goalAccount.isActive()) {
                throw ApiException.badRequest("The account backing this goal is inactive");
            }
            return goalAccount;
        }

        return null;
    }

    private Transaction buildTransaction(
            User user, Account account, Category category,
            Account toAccount, CreateTransactionRequest request) {
        Transaction t = new Transaction();
        t.setUser(user);
        t.setAccount(account);
        t.setCategory(category);
        t.setType(request.type());
        t.setAmount(request.amount());
        t.setNotes(request.notes());
        t.setDate(request.date());
        t.setPlanned(false);
        t.setToGoalId(request.toGoalId());

        if (toAccount != null) {
            t.setToAccount(toAccount);
        }

        FinancialYearUtil.applyDerivedDateFields(t, request.date());

        return t;
    }

    private void applyBalanceEffect(
            Transaction transaction, Account account, Account toAccount, BigDecimal amount) {
        switch (transaction.getType()) {
            case INCOME, SETTLEMENT -> account.setCurrentBalance(
                    account.getCurrentBalance().add(amount));
            case FIXED_EXPENSE, VARIABLE_EXPENSE,
                 LENDING, BORROWING, REPAYMENT -> account.setCurrentBalance(
                    account.getCurrentBalance().subtract(amount));
            case TRANSFER -> {
                account.setCurrentBalance(
                        account.getCurrentBalance().subtract(amount));
                if (toAccount != null) {
                    toAccount.setCurrentBalance(
                            toAccount.getCurrentBalance().add(amount));
                }
            }
        }
    }

    private void reverseBalanceEffect(Transaction transaction, Account account, Account toAccount) {
        switch (transaction.getType()) {
            case INCOME, SETTLEMENT -> account.setCurrentBalance(
                    account.getCurrentBalance().subtract(transaction.getAmount()));
            case FIXED_EXPENSE, VARIABLE_EXPENSE, LENDING,
                 BORROWING, REPAYMENT -> account.setCurrentBalance(
                    account.getCurrentBalance().add(transaction.getAmount()));
            case TRANSFER -> {
                account.setCurrentBalance(account.getCurrentBalance().add(transaction.getAmount()));
                if (toAccount != null) {
                    toAccount.setCurrentBalance(toAccount.getCurrentBalance().subtract(transaction.getAmount()));
                }
            }
        }
    }

    private void reverseGoalProgress(Transaction transaction, String userId) {
        if (transaction.getType() != TransactionType.TRANSFER) return;

        if (transaction.getToGoalId() != null) {
            goalRepository.findByIdAndUserId(transaction.getToGoalId(), userId)
                    .ifPresent(goal -> {
                        goal.setCurrentProgress(goal.getCurrentProgress().subtract(transaction.getAmount()));
                        goalRepository.save(goal);
                    });
            return;
        }

        goalRepository.findByAccountIdAndActiveTrueAndStatusNot(transaction.getAccount().getId(), "COMPLETED")
                .ifPresent(goal -> {
                    goal.setCurrentProgress(goal.getCurrentProgress().add(transaction.getAmount()));
                    goalRepository.save(goal);
                });
    }

    private void applyGoalProgress(Transaction transaction, String userId) {
        if (transaction.getType() != TransactionType.TRANSFER) return;

        if (transaction.getToGoalId() != null) {
            goalRepository.findByIdAndUserId(transaction.getToGoalId(), userId)
                    .ifPresent(goal -> {
                        goal.setCurrentProgress(goal.getCurrentProgress().add(transaction.getAmount()));
                        goalRepository.save(goal);
                    });
            return;
        }

        goalRepository.findByAccountIdAndActiveTrueAndStatusNot(transaction.getAccount().getId(), "COMPLETED")
                .ifPresent(goal -> {
                    goal.setCurrentProgress(goal.getCurrentProgress().subtract(transaction.getAmount()));
                    goalRepository.save(goal);
                });
    }

    private boolean isGoalProtectedType(TransactionType type) {
        return switch (type) {
            case FIXED_EXPENSE, VARIABLE_EXPENSE, LENDING, BORROWING, REPAYMENT -> true;
            default -> false;
        };
    }

    private boolean hasData(String userId, Specification<Transaction> periodSpec, FlowType flowType) {
        Specification<Transaction> spec = TransactionSpecifications.belongsToUser(userId)
                .and(periodSpec);
        if (flowType != null) {
            spec = spec.and(TransactionSpecifications.hasFlowType(flowType));
        }
        return transactionRepository.exists(spec);
    }

    private record PeriodFilter(
            Specification<Transaction> current,
            Specification<Transaction> previous,
            Specification<Transaction> next
    ) {
        static PeriodFilter none() {
            return new PeriodFilter(null, null, null);
        }
    }

    private PeriodFilter resolvePeriod(
            Integer calendarYear, Integer calendarMonth, String financialYear, String financialMonth) {

        if (calendarYear != null && calendarMonth != null) {
            YearMonth current = YearMonth.of(calendarYear, calendarMonth);
            YearMonth previous = current.minusMonths(1);
            YearMonth next = current.plusMonths(1);
            return new PeriodFilter(
                    TransactionSpecifications.inCalendarMonth(calendarYear, calendarMonth),
                    TransactionSpecifications.inCalendarMonth(previous.getYear(), previous.getMonthValue()),
                    TransactionSpecifications.inCalendarMonth(next.getYear(), next.getMonthValue()));
        }

        if (financialYear != null && financialMonth != null) {
            int month = Integer.parseInt(financialMonth);
            FinancialYearUtil.FinancialMonth previous = FinancialYearUtil.previous(financialYear, month);
            FinancialYearUtil.FinancialMonth next = FinancialYearUtil.next(financialYear, month);
            return new PeriodFilter(
                    TransactionSpecifications.inFinancialMonth(financialYear, month),
                    TransactionSpecifications.inFinancialMonth(previous.financialYear(), previous.month()),
                    TransactionSpecifications.inFinancialMonth(next.financialYear(), next.month()));
        }

        return PeriodFilter.none();
    }

    private Specification<Transaction> combine(String userId, Specification<Transaction> periodSpec, FlowType flowType) {
        List<Specification<Transaction>> filters = Stream.of(
                TransactionSpecifications.belongsToUser(userId),
                periodSpec,
                flowType != null ? TransactionSpecifications.hasFlowType(flowType) : null
        ).filter(Objects::nonNull).toList();

        return Specification.allOf(filters);
    }
}

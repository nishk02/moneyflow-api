package com.moneyflow.transaction;

import com.moneyflow.account.Account;
import com.moneyflow.account.AccountRepository;
import com.moneyflow.auth.User;
import com.moneyflow.auth.UserRepository;
import com.moneyflow.category.Category;
import com.moneyflow.category.CategoryRepository;
import com.moneyflow.shared.exception.ApiException;
import com.moneyflow.shared.util.FinancialYearUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TransactionService {
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public List<TransactionResponse> getTransactions(
            String userId, Integer calendarYear, Integer calendarMonth,
            String financialYear, String financialMonth) {
        List<Transaction> transactions;

        if (calendarYear != null && calendarMonth != null) {
            transactions = transactionRepository
                    .findByUserIdAndCalendarYearAndCalendarMonthOrderByDateDescCreatedAtDesc(
                            userId, calendarYear, calendarMonth);
        } else if (financialYear != null && financialMonth != null) {
            transactions = transactionRepository.findByUserIdAndFinancialYearAndMonthOrderByDateDescCreatedAtDesc(
                    userId, financialYear, Integer.parseInt(financialMonth));
        } else {
            transactions = transactionRepository.findByUserIdOrderByDateDescCreatedAtDesc(userId);
        }

        return transactions.stream().map(TransactionResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(String userId, String id) {
        return transactionRepository.findByIdAndUserId(id, userId)
                .map(TransactionResponse::from)
                .orElseThrow(() -> ApiException.notFound("Transaction not found"));
    }

    @Transactional
    public TransactionResponse createTransaction(String userId, CreateTransactionRequest request) {
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("User not found"));

        Account account = accountRepository.findByIdAndUserId(request.accountId(), userId)
                .orElseThrow(() -> ApiException.notFound("Account not found"));

        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> ApiException.notFound("Category not found"));

        validateTransferDestination(request);

        Account toAccount = resolveToAccount(request, userId);

        Transaction transaction = buildTransaction(user, account, category, toAccount, request);

        applyBalanceEffect(transaction, account, toAccount, request.amount());

        accountRepository.save(account);
        if (toAccount != null) accountRepository.save(toAccount);

        Transaction saved = transactionRepository.save(transaction);
        return TransactionResponse.from(saved);
    }

    @Transactional
    public TransactionResponse updateTransaction(String userId, String id, UpdateTransactionRequest request) {
        Transaction transaction = transactionRepository
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("Transaction not found"));

        Account account = transaction.getAccount();

        reverseBalanceEffect(transaction, account);
        accountRepository.save(account);

        if (request.amount() != null) transaction.setAmount(request.amount());
        if (request.date() != null) {
            transaction.setDate(request.date());
            FinancialYearUtil.applyDerivedDateFields(transaction,
                    request.date());
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

        applyBalanceEffect(transaction, account, null, transaction.getAmount());
        accountRepository.save(account);

        Transaction saved = transactionRepository.save(transaction);
        return TransactionResponse.from(saved);
    }

    @Transactional
    public void deleteTransaction(String userId, String id) {
        Transaction transaction = transactionRepository
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("Transaction not found"));

        Account account = transaction.getAccount();
        reverseBalanceEffect(transaction, account);
        accountRepository.save(account);

        transactionRepository.delete(transaction);
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
        if (request.toAccountId() == null) return null;

        return accountRepository.findByIdAndUserId(request.toAccountId(), userId)
                .orElseThrow(() -> ApiException.notFound("Destination account not found"));
    }

    private Transaction buildTransaction(
            User user, Account account, Category category,
            Account toAccount, CreateTransactionRequest request) {
        Transaction t = new Transaction();
        t.setUser(user);
        t.setAccount(account);
        t.setCategory(category);
        t.setToAccount(toAccount);
        t.setToGoalId(request.toGoalId());
        t.setType(request.type());
        t.setAmount(request.amount());
        t.setNotes(request.notes());
        t.setDate(request.date());
        t.setPlanned(false);

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

    private void reverseBalanceEffect(Transaction transaction, Account account) {
        switch(transaction.getType()) {
            case INCOME, SETTLEMENT -> account.setCurrentBalance(
                    account.getCurrentBalance().subtract(transaction.getAmount()));
            case FIXED_EXPENSE, VARIABLE_EXPENSE, LENDING,
                 BORROWING, REPAYMENT -> account.setCurrentBalance(
                         account.getCurrentBalance().add(transaction.getAmount()));
            case TRANSFER -> account.setCurrentBalance(
                    account.getCurrentBalance().add(transaction.getAmount()));
        }
    }
}

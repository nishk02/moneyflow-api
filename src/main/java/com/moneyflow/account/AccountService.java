package com.moneyflow.account;

import com.moneyflow.auth.User;
import com.moneyflow.auth.UserRepository;
import com.moneyflow.goal.Goal;
import com.moneyflow.goal.GoalRepository;
import com.moneyflow.shared.exception.ApiException;
import com.moneyflow.transaction.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AccountService {
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final TransactionService transactionService;
    private final GoalRepository goalRepository;
    private final GoalAllocationService goalAllocationService;

    public List<AccountResponse> getAccounts(String userId) {
        return mapAccounts(accountRepository.findByUserIdAndActiveTrue(userId), userId);
    }

    public AccountSummaryResponse getAccountsSummary(String userId, String type) {
        List<AccountResponse> accounts = type != null
                ? mapAccounts(accountRepository.findByUserIdAndTypeAndActiveTrue(userId, type), userId)
                : getAccounts(userId);
        BigDecimal totalBalance = accounts.stream()
                .map(AccountResponse::currentBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AccountSummaryResponse(totalBalance, accounts);
    }

    @Transactional
    public AccountResponse createAccount(String userId, CreateAccountRequest request) {
        User user = getUser(userId);

        if (accountRepository.existsByUserIdAndNameIgnoreCase(userId, request.name())) {
            throw ApiException.conflict("An account name '" + request.name() + "' already exists");
        }

        Account account = new Account();

        account.setUser(user);
        account.setName(request.name());
        account.setType(request.type());
        account.setCurrentBalance(request.currentBalance() != null ? request.currentBalance() : BigDecimal.ZERO);
        account.setCurrency("INR");
        account.setColorLabel(request.colorLabel());

        Account savedAccount = accountRepository.save(account);

        // BR-01: auto-create SETTLEMENT transaction for opening balance
        if (savedAccount.getCurrentBalance().compareTo(BigDecimal.ZERO) > 0) {
            transactionService.createOpeningBalanceSettlement(savedAccount, user);
        }

        return AccountResponse.from(savedAccount, false);
    }

    public AccountResponse getAccount(String userId, String accountId) {
        Account account = accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> ApiException.notFound("Account not found"));
        boolean goalLinked = goalRepository.existsByAccountIdAndActiveTrueAndStatusNot(accountId, "COMPLETED");
        return AccountResponse.from(account, goalLinked);
    }

    @Transactional
    public AccountResponse updateAccount(String userId, String accountId, UpdateAccountRequest request) {
        Account account = accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> ApiException.notFound("Account not found"));

        if (request.name() != null
                && !request.name().equalsIgnoreCase(account.getName())
                && accountRepository.existsByUserIdAndNameIgnoreCase(userId, request.name())
        ) {
            throw ApiException.conflict("An account named '" + request.name() + "' already exists");
        }

        BigDecimal oldBalance = account.getCurrentBalance();

        if (request.name() != null) {
            account.setName(request.name());
        }

        if (request.type() != null) {
            account.setType(request.type());
        }

        if (request.colorLabel() != null) {
            account.setColorLabel(request.colorLabel());
        }

        boolean balanceChanged = request.currentBalance() != null
                && request.currentBalance().compareTo(oldBalance) != 0;

        List<GoalAllocationItem> reduction = List.of();

        if (balanceChanged) {
            BigDecimal newBalance = request.currentBalance();
            List<Goal> activeGoals = goalAllocationService.activeGoalsOn(account);
            BigDecimal earmarked = activeGoals.stream()
                    .map(Goal::getCurrentProgress)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (newBalance.compareTo(earmarked) < 0) {
                BigDecimal shortfall = earmarked.subtract(newBalance);

                if (request.goalAllocations() == null || request.goalAllocations().isEmpty()) {
                    throw ApiException.badRequest(
                            "This balance is below what's currently earmarked across linked goals. " +
                                    "Choose which goal(s) should absorb the reduction.",
                            goalAllocationService.buildBalanceCorrectionDetails(account, newBalance));
                }

                BigDecimal allocatedTotal = request.goalAllocations().stream()
                        .map(GoalAllocationItem::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                if (allocatedTotal.compareTo(shortfall) < 0) {
                    throw ApiException.badRequest(
                            "Selected goal reductions (₹" + formatAmount(allocatedTotal) + ") don't cover the full shortfall (₹" + formatAmount(shortfall) + ").");
                }

                reduction = request.goalAllocations();
            }

            account.setCurrentBalance(newBalance);
        }

        Account savedAccount = accountRepository.save(account);

        if (balanceChanged) {
            // BR-02: auto-create SETTLEMENT transaction for balance correction
            Transaction settlement = transactionService.createBalanceCorrectionSettlement(
                    savedAccount, getUser(userId), oldBalance, request.currentBalance());

            if (!reduction.isEmpty()) {
                goalAllocationService.applyAllocations(settlement, reduction, GoalAllocationDirection.DECREASE);
            }
        }

        return AccountResponse.from(savedAccount, false);
    }

    @Transactional
    public void deleteAccount(String userId, String accountId) {
        Account account = accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> ApiException.notFound("Account not found"));

        account.setActive(false);
        accountRepository.save(account);
    }

    private List<AccountResponse> mapAccounts(List<Account> accounts, String userId) {
        Set<String> goalLinkedAccountIds = goalRepository
                .findByUserIdAndActiveTrueOrderByDisplayOrderAsc(userId)
                .stream()
                .filter(g -> !"COMPLETED".equals(g.getStatus()))
                .map(g -> g.getAccount().getId())
                .collect(Collectors.toSet());

        return accounts.stream()
                .map(a -> AccountResponse.from(a, goalLinkedAccountIds.contains(a.getId())))
                .toList();
    }

    private User getUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));
    }

    private String formatAmount(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
}

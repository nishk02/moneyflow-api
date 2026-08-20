package com.moneyflow.account;

import com.moneyflow.auth.User;
import com.moneyflow.auth.UserRepository;
import com.moneyflow.goal.GoalRepository;
import com.moneyflow.shared.exception.ApiException;
import com.moneyflow.transaction.TransactionService;
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

        if (request.currentBalance() != null) {
            account.setCurrentBalance(request.currentBalance());
        }

        Account savedAccount = accountRepository.save(account);

        // BR-02: auto-create SETTLEMENT transaction for balance correction
        if (request.currentBalance() != null && request.currentBalance().compareTo(oldBalance) != 0) {
            transactionService.createBalanceCorrectionSettlement(
                    savedAccount, getUser(userId), oldBalance, request.currentBalance());
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
}

package com.moneyflow.account;

import com.moneyflow.auth.User;
import com.moneyflow.auth.UserRepository;
import com.moneyflow.shared.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AccountService {
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    public List<AccountResponse> getAccounts(String userId) {
        return accountRepository.findByUserIdAndActiveTrue(userId).stream().map(AccountResponse::from).toList();
    }

    public AccountSummaryResponse getAccountsSummary(String userId) {
        List<AccountResponse> accounts = getAccounts(userId);
        BigDecimal totalBalance = accounts.stream()
                .map(AccountResponse::currentBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AccountSummaryResponse(totalBalance, accounts);
    }

    @Transactional
    public AccountResponse createAccount(String userId, CreateAccountRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));

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

        // TODO: BR-01: auto-create SETTLEMENT transaction for opening balance
        // Wired here once TransactionService is built
        // if (savedAccount.getCurrentBalance().compareTo(BigDecimal.ZERO) > 0) {
        //     transactionService.createOpeningBalanceSettlement(savedAccount);
        // }

        return AccountResponse.from(savedAccount);
    }

    public AccountResponse getAccount(String userId, String accountId) {
        return accountRepository.findByIdAndUserId(accountId, userId)
                .map(AccountResponse::from)
                .orElseThrow(() -> ApiException.notFound("Account not found"));
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

        // TODO: BR-02: auto-create SETTLEMENT transaction for balance correction
        // Wired here once TransactionService is built
        // if (request.currentBalance() != null
        //     && request.currentBalance().compareTo(oldBalance) != 0) {
        //     transactionService.createBalanceCorrectionSettlement(
        //         savedAccount, oldBalance, request.currentBalance());
        // }

        return AccountResponse.from(savedAccount);
    }

    @Transactional
    public void deleteAccount(String userId, String accountId) {
        Account account = accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> ApiException.notFound("Account not found"));

        account.setActive(false);
        accountRepository.save(account);
    }
}

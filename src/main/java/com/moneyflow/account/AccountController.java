package com.moneyflow.account;

import com.moneyflow.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {
    private final AccountService accountService;

    @GetMapping
    public ResponseEntity<ApiResponse<AccountSummaryResponse>> getAccounts() {
        String userId = getCurrentUserId();
        return ResponseEntity.ok(
                ApiResponse.success(accountService.getAccountsSummary(userId))
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AccountResponse>> getAccount(@PathVariable String id) {
        String userId = getCurrentUserId();
        return ResponseEntity.ok(
                ApiResponse.success(accountService.getAccount(userId, id))
        );
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AccountResponse>> createAccount(
            @Valid @RequestBody CreateAccountRequest request
    ) {
        String userId = getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(accountService.createAccount(userId, request), "Account created successfully"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<AccountResponse>> updateAccount(
            @PathVariable String id,
            @Valid @RequestBody UpdateAccountRequest request
    ) {
        String userId = getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.success(accountService.updateAccount(userId, id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteAccount(@PathVariable String id) {
        String userId = getCurrentUserId();
        accountService.deleteAccount(userId, id);
        return ResponseEntity.ok(ApiResponse.success(null, "Account deleted successfully"));
    }

    private String getCurrentUserId() {
        // TODO: replace with real SecurityContext extraction once prod profile
        // JWT filter is active in this dev session
        return "hardcoded-dev-user-id";
    }
}

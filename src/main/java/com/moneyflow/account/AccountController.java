package com.moneyflow.account;

import com.moneyflow.shared.dto.ApiResponse;
import com.moneyflow.shared.security.BaseController;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController extends BaseController {
    private final AccountService accountService;

    @GetMapping
    public ResponseEntity<ApiResponse<AccountSummaryResponse>> getAccounts(
            @RequestParam(required = false) String type) {
        String userId = getCurrentUserId();
        return ResponseEntity.ok(
                ApiResponse.success(accountService.getAccountsSummary(userId, type)));
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
}

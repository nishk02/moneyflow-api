package com.moneyflow.transaction;

import com.moneyflow.shared.dto.ApiResponse;
import com.moneyflow.shared.security.BaseController;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController extends BaseController {
    private final TransactionService transactionService;

    @GetMapping
    public ResponseEntity<ApiResponse<java.util.List<TransactionResponse>>> getTransactions(
            @RequestParam(required = false) Integer calendarYear,
            @RequestParam(required = false) Integer calendarMonth,
            @RequestParam(required = false) String financialYear,
            @RequestParam(required = false) String financialMonth) {
        String userId = getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.success(
                transactionService.getTransactions(
                        userId, calendarYear, calendarMonth,
                        financialYear, financialMonth)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TransactionResponse>> createTransaction(
            @Valid @RequestBody CreateTransactionRequest request) {
        String userId = getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        transactionService.createTransaction(userId, request),
                        "Transaction created successfully"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TransactionResponse>> getTransaction(@PathVariable String id) {
        String userId = getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.success(transactionService.getTransaction(userId, id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TransactionResponse>> updateTransaction(
            @PathVariable String id, @Valid @RequestBody UpdateTransactionRequest request) {
        String userId = getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.success(transactionService.updateTransaction(userId, id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteTransaction(@PathVariable String id) {
        String userId = getCurrentUserId();
        transactionService.deleteTransaction(userId, id);
        return ResponseEntity.ok(ApiResponse.success(null, "Transaction deleted successfully"));
    }
}

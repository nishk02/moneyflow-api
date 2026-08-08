package com.moneyflow.analytics;

import com.moneyflow.shared.dto.ApiResponse;
import com.moneyflow.shared.security.BaseController;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController extends BaseController {
    private final AnalyticsService analyticsService;

    @GetMapping("/cashflow-summary")
    public ResponseEntity<ApiResponse<AnalyticsResponse>> getCashflowSummary(
            @RequestParam(required = false)
            String mode,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate anchor,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String userId = getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getCashflowSummary(userId, mode, anchor, from, to)));
    }
}

package com.moneyflow.invite;

import com.moneyflow.auth.AuthResponse;
import com.moneyflow.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Public, unauthenticated endpoints for the invited person's side of the flow.
 * Mounted under /auth/** so it rides the existing permitAll rule in
 * ProdSecurityConfig/DevSecurityConfig — no security config change needed.
 * (Admin-side invite creation lives separately in AdminInviteController,
 * under /api/admin/invites, which does require a JWT + ADMIN role.)
 */
@RestController
@RequestMapping("/auth/invites/{token}")
@RequiredArgsConstructor
public class InviteSignupController {
    private final InviteService inviteService;

    @GetMapping
    public ResponseEntity<ApiResponse<InviteResponse>> getInviteInfo(@PathVariable String token) {
        InviteResponse response = inviteService.getInviteInfo(token);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<Void>> submitSignup(
            @PathVariable String token,
            @Valid @RequestBody InviteSignupRequest request
    ) {
        inviteService.submitSignup(token, request);

        return ResponseEntity.ok(ApiResponse.success(null, "Verification code sent to your email"));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<ApiResponse<AuthResponse>> verifyOtp(
            @PathVariable String token,
            @Valid @RequestBody VerifyOtpRequest request
    ) {
        AuthResponse response = inviteService.verifyOtp(token, request);

        return ResponseEntity.ok(ApiResponse.success(response, "Account verified — welcome to MnyFlo"));
    }

    @PostMapping("/resend-otp")
    public ResponseEntity<ApiResponse<Void>> resendOtp(@PathVariable String token) {
        inviteService.resendOtp(token);

        return ResponseEntity.ok(ApiResponse.success(null, "A new code has been sent to your email"));
    }
}

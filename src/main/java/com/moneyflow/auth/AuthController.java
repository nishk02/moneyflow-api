package com.moneyflow.auth;

import com.moneyflow.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping("/signin")
    public ResponseEntity<ApiResponse<AuthResponse>> signIn(
            @Valid @RequestBody SignInRequest request
    ) {
        AuthResponse response = authService.signIn(request);

        return ResponseEntity.ok(ApiResponse.success(response, "Signed in successfully"));
    }
}
package com.moneyflow.invite;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyOtpRequest(
        @NotBlank(message = "OTP code is required")
        @Pattern(regexp = "\\d{6}", message = "OTP code must be 6 digits")
        String code
) {
}

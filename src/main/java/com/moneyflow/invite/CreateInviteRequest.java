package com.moneyflow.invite;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateInviteRequest(@NotBlank @Email String email) {
}

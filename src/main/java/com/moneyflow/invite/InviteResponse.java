package com.moneyflow.invite;

import java.time.LocalDateTime;

public record InviteResponse(
        String id,
        String email,
        String token,
        InviteStatus status,
        LocalDateTime expiresAt
) {
    public static InviteResponse from(Invite invite) {
        return new InviteResponse(
                invite.getId(), invite.getEmail(), invite.getToken(), invite.getStatus(), invite.getExpiresAt());
    }
}

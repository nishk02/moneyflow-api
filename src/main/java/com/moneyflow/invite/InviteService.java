package com.moneyflow.invite;

import com.moneyflow.auth.User;
import com.moneyflow.auth.UserRepository;
import com.moneyflow.shared.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class InviteService {
    private static final int TOKEN_BYTES = 32;
    private static final int VALIDITY_DAYS = 7;

    private final InviteRepository inviteRepository;
    private final UserRepository userRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.max-users:50}")
    private int maxUsers;

    public InviteResult createInvite(String adminUserId, CreateInviteRequest request) {
        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> ApiException.unauthorized("Not authenticated"));

        Invite invite = new Invite();
        invite.setEmail(request.email());
        invite.setToken(generateToken());
        invite.setStatus(InviteStatus.PENDING);
        invite.setInvitedBy(admin);
        invite.setExpiresAt(LocalDateTime.now().plusDays(VALIDITY_DAYS));

        Invite saved = inviteRepository.save(invite);

        long registeredMembers = userRepository.countByRole("MEMBER");
        String warning = registeredMembers >= maxUsers
                ? ("You're at or past your %d-user cap (%d registered members)." +
                "This invite was still created — the hard limit is enforced at sign-up, " +
                "not here.").formatted(maxUsers, registeredMembers)
                : null;

        return new InviteResult(InviteResponse.from(saved), warning);
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

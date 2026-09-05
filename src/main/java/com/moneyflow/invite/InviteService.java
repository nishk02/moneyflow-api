package com.moneyflow.invite;

import com.moneyflow.auth.AuthResponse;
import com.moneyflow.auth.User;
import com.moneyflow.auth.UserRepository;
import com.moneyflow.shared.email.EmailService;
import com.moneyflow.shared.exception.ApiException;
import com.moneyflow.shared.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class InviteService {
    private static final int TOKEN_BYTES = 32;
    private static final int VALIDITY_DAYS = 7;
    private static final int OTP_TTL_MINUTES = 10;
    private static final int MAX_OTP_ATTEMPTS = 5;
    private static final int RESEND_COOLDOWN_SECONDS = 60;

    private final InviteRepository inviteRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
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
        emailService.sendInviteEmail(saved.getEmail(), saved.getToken());

        long registeredMembers = userRepository.countByRole("MEMBER");
        String warning = registeredMembers >= maxUsers
                ? ("You're at or past your %d-user cap (%d registered members)." +
                "This invite was still created — the hard limit is enforced at sign-up, " +
                "not here.").formatted(maxUsers, registeredMembers)
                : null;

        return new InviteResult(InviteResponse.from(saved), warning);
    }

    /**
     * Step 1 of accepting an invitation
     */
    public void submitSignup(String token, InviteSignupRequest request) {
        Invite invite = getInviteOrThrow(token);
        requireStatus(invite, InviteStatus.PENDING, "This invite has already been used or is awaiting a code.");
        requireNotExpired(invite);

        if (!request.password().equals(request.confirmPassword())) {
            throw ApiException.badRequest("Passwords do not match");
        }

        invite.setPendingFirstName(request.firstName());
        invite.setPendingLastName(request.lastName());
        invite.setPendingPasswordHash(passwordEncoder.encode(request.password()));

        issueNewOtp(invite);
        invite.setStatus(InviteStatus.AWAITING_OTP);

        inviteRepository.save(invite);
    }

    /**
     * Step 2: the invited person submits the 6-digit code from their inbox.
     * On success this is the moment the real User row is finally created.
     */
    public AuthResponse verifyOtp(String token, VerifyOtpRequest request) {
        Invite invite = getInviteOrThrow(token);
        requireStatus(invite, InviteStatus.AWAITING_OTP, "This invite is not awaiting a verification code.");
        requireNotExpired(invite);

        if (invite.getOtpExpiresAt() == null || invite.getOtpExpiresAt().isBefore(LocalDateTime.now())) {
            throw ApiException.badRequest("This code has expired. Request a new one.");
        }
        if (invite.getOtpAttempts() >= MAX_OTP_ATTEMPTS) {
            throw ApiException.badRequest("Too many incorrect attempts. Request a new code.");
        }
        if (userRepository.countByRole("MEMBER") >= maxUsers) {
            throw ApiException.conflict("The user limit for this workspace has been reached.");
        }

        if (!passwordEncoder.matches(request.code(), invite.getOtpCodeHash())) {
            invite.setOtpAttempts(invite.getOtpAttempts() + 1);
            inviteRepository.save(invite);
            throw ApiException.unauthorized("Incorrect code");
        }

        User user = new User();
        user.setFirstName(invite.getPendingFirstName());
        user.setLastName(invite.getPendingLastName());
        user.setEmail(invite.getEmail());
        user.setPasswordHash(invite.getPendingPasswordHash());
        user.setRole("MEMBER");
        User savedUser = userRepository.save(user);

        // Clear staged secrets now that they've done their job - no reason a
        // completed invite row should keep holding a password hash forever.
        invite.setStatus(InviteStatus.COMPLETED);
        invite.setPendingPasswordHash(null);
        invite.setOtpCodeHash(null);
        inviteRepository.save(invite);

        String jwt = jwtUtil.generateToken(savedUser.getId(), savedUser.getEmail(), savedUser.getRole());
        return new AuthResponse(jwt, AuthResponse.UserSummary.from(savedUser));
    }

    /**
     * Step 2b: the code expired, landed in spam, or was never received. Issues a
     * fresh code without making the person restart from their name/password again.
     */
    public void resendOtp(String token) {
        Invite invite = getInviteOrThrow(token);
        requireStatus(invite, InviteStatus.AWAITING_OTP, "This invite is not awaiting a verification code.");
        requireNotExpired(invite);

        LocalDateTime cooldownEnds = invite.getUpdatedAt().plusSeconds(RESEND_COOLDOWN_SECONDS);
        if (cooldownEnds.isAfter(LocalDateTime.now())) {
            long secondsLeft = ChronoUnit.SECONDS.between(LocalDateTime.now(), cooldownEnds);
            throw ApiException.badRequest("Please wait " + secondsLeft + "s before requesting another code.");
        }

        issueNewOtp(invite);
        inviteRepository.save(invite);
    }

    private void issueNewOtp(Invite invite) {
        String code = generateOtpCode();
        invite.setOtpCodeHash(passwordEncoder.encode(code));
        invite.setOtpExpiresAt(LocalDateTime.now().plusMinutes(OTP_TTL_MINUTES));
        invite.setOtpAttempts(0);
        emailService.sendOtpEmail(invite.getEmail(), code);
    }

    private Invite getInviteOrThrow(String token) {
        return inviteRepository.findByToken(token)
                .orElseThrow(() -> ApiException.notFound("Invite not found"));
    }

    private void requireStatus(Invite invite, InviteStatus expected, String message) {
        if (invite.getStatus() != expected) {
            throw ApiException.conflict(message);
        }
    }

    private void requireNotExpired(Invite invite) {
        if (invite.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw ApiException.badRequest("This invite has expired. Ask for a new one.");
        }
    }

    private String generateOtpCode() {
        int code = secureRandom.nextInt(1_000_000);
        return String.format("%06d", code);
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

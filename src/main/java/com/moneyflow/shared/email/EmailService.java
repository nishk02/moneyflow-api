package com.moneyflow.shared.email;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailService {
    private final JavaMailSender mailSender;

    @Value("${app.invite-signup-url}")
    private String inviteSignupUrl;

    @Value("${app.mail-from}")
    private String fromAddress;

    public void sendInviteEmail(String toEmail, String token) {
        String link = inviteSignupUrl + "?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject("You're invited to MnyFlo");
        message.setText("""
                You've been invited to MnyFlo.

                Complete your sign-up here (valid for 7 days):
                %s

                If you weren't expecting this, you can ignore this email.
                """.formatted(link));

        mailSender.send(message);
    }
}

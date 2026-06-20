package com.moneyflow.auth;

import com.moneyflow.shared.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthResponse signUp(SignUpRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw ApiException.conflict("Email is already registered");
        }

        User user = new User();

        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));

        User savedUser = userRepository.save(user);

        String token = "placeholder-token";

        return new AuthResponse(token, AuthResponse.UserSummary.from(savedUser));
    }
}
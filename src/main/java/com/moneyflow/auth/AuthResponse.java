package com.moneyflow.auth;

public record AuthResponse(
        String token,
        UserSummary user
) {
    public record UserSummary(
            String id,
            String firstName,
            String lastName,
            String email,
            int onboardingStep
    ) {
        public static UserSummary from (User user) {
            return new UserSummary(
                    user.getId(),
                    user.getFirstName(),
                    user.getLastName(),
                    user.getEmail(),
                    user.getOnboardingStep()
            );
        }
    }
}

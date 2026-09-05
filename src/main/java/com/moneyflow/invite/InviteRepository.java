package com.moneyflow.invite;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InviteRepository extends JpaRepository<Invite, String> {
    Optional<Invite> findByToken(String token);
}

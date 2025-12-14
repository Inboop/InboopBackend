package com.inboop.backend.auth.repository;

import com.inboop.backend.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByOauthProviderAndOauthId(String provider, String oauthId);
    boolean existsByEmail(String email);
}

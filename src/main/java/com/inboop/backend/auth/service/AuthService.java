package com.inboop.backend.auth.service;

import com.inboop.backend.auth.dto.AuthResponse;
import com.inboop.backend.auth.dto.LoginRequest;
import com.inboop.backend.auth.dto.RegisterRequest;
import com.inboop.backend.auth.entity.User;
import com.inboop.backend.auth.repository.UserRepository;
import com.inboop.backend.auth.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;

    @Value("${jwt.expiration:86400000}")
    private long jwtExpiration;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil, AuthenticationManager authenticationManager) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.authenticationManager = authenticationManager;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Check if email already exists
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email already exists");
        }

        User user = new User();
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole("USER");
        user.setOauthProvider("LOCAL");

        user = userRepository.save(user);

        return generateAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));

        return generateAuthResponse(user);
    }

    public AuthResponse refreshToken(String refreshToken) {
        if (!jwtUtil.isRefreshToken(refreshToken)) {
            throw new RuntimeException("Invalid refresh token");
        }

        String email = jwtUtil.extractUsername(refreshToken);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!jwtUtil.validateToken(refreshToken, email)) {
            throw new RuntimeException("Invalid or expired refresh token");
        }

        return generateAuthResponse(user);
    }

    private AuthResponse generateAuthResponse(User user) {
        String accessToken = jwtUtil.generateToken(user.getEmail());
        String refreshToken = jwtUtil.generateRefreshToken(user.getEmail());

        AuthResponse.UserDto userDto = new AuthResponse.UserDto(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole()
        );

        return new AuthResponse(accessToken, refreshToken, jwtExpiration / 1000, userDto);
    }

    public User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}

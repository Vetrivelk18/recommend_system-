package com.example.service;

import com.example.dto.AuthResponse;
import com.example.dto.LoginRequest;
import com.example.dto.SignupRequest;
import com.example.entity.User;
import com.example.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAccountService {

    private final UserRepository userRepository;
    private final PreferenceResolver preferenceResolver;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public UserAccountService(UserRepository userRepository,
                              PreferenceResolver preferenceResolver,
                              PasswordEncoder passwordEncoder,
                              JwtService jwtService) {
        this.userRepository = userRepository;
        this.preferenceResolver = preferenceResolver;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * user_id is never set here: the database identity assigns it from 206210,
     * keeping real signups clear of the dataset users and genuinely cold.
     */
    @Transactional
    public AuthResponse signup(SignupRequest request) {
        if (userRepository.existsByMobile(request.mobile())) {
            throw new MobileAlreadyRegisteredException(request.mobile());
        }
        if (request.preferences() != null) {
            preferenceResolver.resolve(request.preferences());
        }
        User user = new User(
                request.name(),
                request.mobile(),
                passwordEncoder.encode(request.password()),
                request.preferences());
        User saved = userRepository.save(user);
        return new AuthResponse(jwtService.issue(saved.getUserId(), saved.getName()),
                saved.getUserId(), saved.getName(), saved.getPreferences());
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByMobile(request.mobile())
                .filter(u -> passwordEncoder.matches(request.password(), u.getPassword()))
                .orElseThrow(InvalidCredentialsException::new);
        return new AuthResponse(jwtService.issue(user.getUserId(), user.getName()),
                user.getUserId(), user.getName(), user.getPreferences());
    }

    public static class MobileAlreadyRegisteredException extends RuntimeException {
        public MobileAlreadyRegisteredException(String mobile) {
            super("mobile already registered: " + mobile);
        }
    }

    /** Deliberately does not say whether the mobile or the password was wrong. */
    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() {
            super("invalid mobile or password");
        }
    }
}

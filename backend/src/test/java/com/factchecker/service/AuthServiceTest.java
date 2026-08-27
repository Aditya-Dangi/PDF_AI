package com.factchecker.service;

import com.factchecker.domain.User;
import com.factchecker.dto.AuthResponse;
import com.factchecker.dto.LoginRequest;
import com.factchecker.dto.RegisterRequest;
import com.factchecker.exception.ConflictException;
import com.factchecker.repository.UserRepository;
import com.factchecker.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;

    private AuthService authService() {
        return new AuthService(userRepository, passwordEncoder, jwtService);
    }

    @Test
    void registerCreatesAUserWithAHashedPasswordAndReturnsAToken() {
        when(userRepository.existsByEmail("person@example.com")).thenReturn(false);
        when(passwordEncoder.encode("plain-password")).thenReturn("hashed-password");
        when(jwtService.generateToken(anyString(), eq("person@example.com"))).thenReturn("jwt-token");

        AuthResponse response = authService().register(new RegisterRequest("person@example.com", "plain-password"));

        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUser.capture());
        assertThat(savedUser.getValue().getEmail()).isEqualTo("person@example.com");
        assertThat(savedUser.getValue().getPasswordHash()).isEqualTo("hashed-password");

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.email()).isEqualTo("person@example.com");
    }

    @Test
    void registerNormalizesEmailCasingAndWhitespaceBeforeStoringOrChecking() {
        when(userRepository.existsByEmail("person@example.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-password");
        when(jwtService.generateToken(anyString(), anyString())).thenReturn("jwt-token");

        authService().register(new RegisterRequest("  Person@Example.COM  ", "plain-password"));

        verify(userRepository).existsByEmail("person@example.com");
        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUser.capture());
        assertThat(savedUser.getValue().getEmail()).isEqualTo("person@example.com");
    }

    @Test
    void registerRejectsADuplicateEmailWithoutTouchingThePasswordOrSaving() {
        when(userRepository.existsByEmail("person@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService().register(new RegisterRequest("person@example.com", "plain-password")))
                .isInstanceOf(ConflictException.class);

        verify(userRepository, never()).save(any());
        verifyNoInteractions(passwordEncoder, jwtService);
    }

    @Test
    void loginSucceedsWithCorrectCredentials() {
        User user = new User();
        user.setEmail("person@example.com");
        user.setPasswordHash("hashed-password");

        when(userRepository.findByEmail("person@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("plain-password", "hashed-password")).thenReturn(true);
        when(jwtService.generateToken(user.getId(), "person@example.com")).thenReturn("jwt-token");

        AuthResponse response = authService().login(new LoginRequest("person@example.com", "plain-password"));

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.userId()).isEqualTo(user.getId());
    }

    @Test
    void loginRejectsAnUnknownEmailWithoutLeakingWhetherTheAccountExists() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService().login(new LoginRequest("nobody@example.com", "whatever")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid email or password");
    }

    @Test
    void loginRejectsAWrongPasswordWithTheSameGenericMessageAsAnUnknownEmail() {
        User user = new User();
        user.setEmail("person@example.com");
        user.setPasswordHash("hashed-password");

        when(userRepository.findByEmail("person@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "hashed-password")).thenReturn(false);

        assertThatThrownBy(() -> authService().login(new LoginRequest("person@example.com", "wrong-password")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid email or password");

        verify(jwtService, never()).generateToken(any(), any());
    }
}

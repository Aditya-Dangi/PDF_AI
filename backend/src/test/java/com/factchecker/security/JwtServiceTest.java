package com.factchecker.security;

import com.factchecker.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JwtService is the entire trust boundary for auth (see JwtAuthFilter, which trusts whatever this
 * class hands back without further checks). A bug here is a security bug, so it gets tested like one:
 * round-trip correctness, rejection of tokens signed with a different secret, and expiry.
 */
class JwtServiceTest {

    private JwtService serviceWith(String secret, long expirationMinutes) {
        AppProperties props = new AppProperties();
        props.getJwt().setSecret(secret);
        props.getJwt().setExpirationMinutes(expirationMinutes);
        return new JwtService(props);
    }

    @Test
    void tokenRoundTripsBackToTheSameUserIdAndEmail() {
        JwtService service = serviceWith("test-secret-value", 60);

        String token = service.generateToken("user-123", "person@example.com");
        Claims claims = service.parseClaims(token);

        assertThat(claims.getSubject()).isEqualTo("user-123");
        assertThat(claims.get("email", String.class)).isEqualTo("person@example.com");
    }

    @Test
    void tokenSignedWithADifferentSecretIsRejected() {
        JwtService issuer = serviceWith("secret-a", 60);
        JwtService verifier = serviceWith("secret-b", 60);

        String token = issuer.generateToken("user-123", "person@example.com");

        assertThatThrownBy(() -> verifier.parseClaims(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService service = serviceWith("test-secret-value", -1); // already expired the instant it's issued

        String token = service.generateToken("user-123", "person@example.com");

        assertThatThrownBy(() -> service.parseClaims(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void garbageInputIsRejectedRatherThanCrashingWithAnUncheckedException() {
        JwtService service = serviceWith("test-secret-value", 60);

        assertThatThrownBy(() -> service.parseClaims("not.a.jwt")).isInstanceOf(JwtException.class);
    }

    @Test
    void shortSecretsStillWorkBecauseTheyAreNormalizedViaHashing() {
        // JwtService hashes whatever secret string is configured to a fixed-length HMAC key, so a
        // short/weak-looking configured secret must not break token generation.
        JwtService service = serviceWith("short", 60);

        String token = service.generateToken("user-123", "person@example.com");

        assertThat(service.parseClaims(token).getSubject()).isEqualTo("user-123");
    }
}

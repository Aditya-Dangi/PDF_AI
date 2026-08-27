package com.factchecker.security;

import com.factchecker.config.AppProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory, per-instance rate limiting - correct as long as the backend runs as a single
 * instance (it does today). If this app ever runs multiple instances behind a load balancer,
 * this MUST move to a shared store (Postgres- or Redis-backed) first - otherwise each instance
 * enforces its own independent quota, silently multiplying the real limit by the instance count.
 *
 * Registered after JwtAuthFilter (see SecurityConfig), so the authenticated principal - when
 * present - is already on the security context: authenticated requests are keyed per user rather
 * than per IP, so users sharing a network (e.g. an office) don't share a quota. Unauthenticated
 * requests (chiefly /api/auth/**, the most common target for credential-stuffing or spam
 * registration) fall back to client IP and get a stricter budget.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final AppProperties.RateLimit config;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(AppProperties appProperties) {
        this.config = appProperties.getRateLimit();
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        boolean isAuthEndpoint = request.getRequestURI().startsWith("/api/auth/");
        String key = (isAuthEndpoint ? "auth:" : "std:") + resolveKey(request);

        Bucket bucket = buckets.computeIfAbsent(key, k -> newBucket(isAuthEndpoint));

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded - please slow down.\"}");
        }
    }

    private Bucket newBucket(boolean isAuthEndpoint) {
        int capacity = isAuthEndpoint ? config.getAuthCapacity() : config.getStandardCapacity();
        Bandwidth limit = Bandwidth.classic(capacity, Refill.intervally(capacity, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }

    private String resolveKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return user.id();
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        return forwardedFor != null ? forwardedFor.split(",")[0].trim() : request.getRemoteAddr();
    }
}

package com.factchecker.security;

/** Lightweight principal carried in the security context after a valid JWT is parsed. */
public record AuthenticatedUser(String id, String email) {
}

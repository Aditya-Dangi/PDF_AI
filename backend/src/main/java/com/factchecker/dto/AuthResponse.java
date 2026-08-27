package com.factchecker.dto;

public record AuthResponse(String token, String userId, String email) {
}

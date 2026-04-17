package io.pockethive.auth.contract;

import java.time.Instant;

public record SessionResponseDto(
    String accessToken,
    String tokenType,
    Instant expiresAt,
    AuthenticatedUserDto user
) {
    public SessionResponseDto {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken must not be null or blank");
        }
        if (tokenType == null || tokenType.isBlank()) {
            throw new IllegalArgumentException("tokenType must not be null or blank");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt must not be null");
        }
        if (user == null) {
            throw new IllegalArgumentException("user must not be null");
        }
        accessToken = accessToken.trim();
        tokenType = tokenType.trim();
    }
}

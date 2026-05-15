package io.pockethive.auth.contract;

public record DevLoginRequestDto(String username) {
    public DevLoginRequestDto {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be null or blank");
        }
        username = username.trim();
    }
}

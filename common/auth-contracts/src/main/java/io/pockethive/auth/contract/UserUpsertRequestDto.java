package io.pockethive.auth.contract;

public record UserUpsertRequestDto(
    String username,
    String displayName,
    boolean active
) {
    public UserUpsertRequestDto {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be null or blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be null or blank");
        }
        username = username.trim();
        displayName = displayName.trim();
    }
}

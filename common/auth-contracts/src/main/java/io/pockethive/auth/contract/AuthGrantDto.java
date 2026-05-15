package io.pockethive.auth.contract;

public record AuthGrantDto(
    AuthProduct product,
    String permission,
    String resourceType,
    String resourceSelector
) {
    public AuthGrantDto {
        if (product == null) {
            throw new IllegalArgumentException("product must not be null");
        }
        permission = requireText(permission, "permission");
        resourceType = requireText(resourceType, "resourceType");
        resourceSelector = requireText(resourceSelector, "resourceSelector");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
        return value.trim();
    }
}

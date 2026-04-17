package io.pockethive.auth.contract;

public record ServiceLoginRequestDto(
    String serviceName,
    String serviceSecret
) {
    public ServiceLoginRequestDto {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be null or blank");
        }
        if (serviceSecret == null || serviceSecret.isBlank()) {
            throw new IllegalArgumentException("serviceSecret must not be null or blank");
        }
        serviceName = serviceName.trim();
        serviceSecret = serviceSecret.trim();
    }
}

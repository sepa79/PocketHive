package io.pockethive.auth.service.service;

import io.pockethive.auth.service.config.AuthServiceProperties;
import io.pockethive.auth.service.domain.StoredServiceAccount;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InMemoryServiceAccountStore {
    private final Map<String, StoredServiceAccount> accountsByName = new LinkedHashMap<>();

    public InMemoryServiceAccountStore(AuthServiceProperties properties) {
        for (AuthServiceProperties.ServiceAccountConfig serviceAccount : properties.getServiceAccounts()) {
            StoredServiceAccount stored = toStoredServiceAccount(serviceAccount);
            accountsByName.put(stored.serviceName(), stored);
        }
    }

    public synchronized Optional<StoredServiceAccount> findByServiceName(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(accountsByName.get(serviceName.trim()));
    }

    public synchronized StoredServiceAccount requireById(UUID serviceAccountId) {
        return accountsByName.values().stream()
            .filter(account -> account.id().equals(serviceAccountId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown service principal"));
    }

    public synchronized List<StoredServiceAccount> listServiceAccounts() {
        return accountsByName.values().stream()
            .sorted(Comparator.comparing(StoredServiceAccount::serviceName))
            .toList();
    }

    private static StoredServiceAccount toStoredServiceAccount(AuthServiceProperties.ServiceAccountConfig config) {
        if (config.getId() == null) {
            throw new IllegalStateException("pockethive.auth-service.service-accounts[].id must not be null");
        }
        return new StoredServiceAccount(
            config.getId(),
            config.getServiceName(),
            config.getDisplayName(),
            config.getSecret(),
            config.isActive(),
            config.getGrants()
        );
    }
}

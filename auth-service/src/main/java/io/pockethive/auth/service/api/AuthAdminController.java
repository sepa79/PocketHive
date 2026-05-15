package io.pockethive.auth.service.api;

import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.UserGrantsReplaceRequestDto;
import io.pockethive.auth.contract.UserUpsertRequestDto;
import io.pockethive.auth.service.config.AuthServiceProperties;
import io.pockethive.auth.service.service.AuthAccessService;
import io.pockethive.auth.service.service.InMemoryUserStore;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/admin/users")
public class AuthAdminController {
    private final AuthAccessService access;
    private final InMemoryUserStore users;
    private final AuthServiceProperties properties;

    public AuthAdminController(AuthAccessService access, InMemoryUserStore users, AuthServiceProperties properties) {
        this.access = access;
        this.users = users;
        this.properties = properties;
    }

    @GetMapping
    public List<AuthenticatedUserDto> listUsers(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        access.requireAdmin(authorization);
        return users.listUsers().stream()
            .map(user -> user.toDto(properties.getProvider()))
            .toList();
    }

    @PutMapping("/{userId}")
    public AuthenticatedUserDto upsertUser(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @PathVariable("userId") UUID userId,
        @RequestBody UserUpsertRequestDto request
    ) {
        access.requireAdmin(authorization);
        return users.upsertUser(userId, request).toDto(properties.getProvider());
    }

    @PutMapping("/{userId}/grants")
    public AuthenticatedUserDto replaceGrants(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @PathVariable("userId") UUID userId,
        @RequestBody UserGrantsReplaceRequestDto request
    ) {
        access.requireAdmin(authorization);
        return users.replaceGrants(userId, request.grants()).toDto(properties.getProvider());
    }
}

package io.pockethive.auth.service.api;

import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.DevLoginRequestDto;
import io.pockethive.auth.contract.ServiceLoginRequestDto;
import io.pockethive.auth.contract.SessionResponseDto;
import io.pockethive.auth.service.service.AuthAccessService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthAccessService access;

    public AuthController(AuthAccessService access) {
        this.access = access;
    }

    @PostMapping("/dev/login")
    public SessionResponseDto devLogin(@RequestBody DevLoginRequestDto request) {
        return access.devLogin(request);
    }

    @PostMapping("/service/login")
    public SessionResponseDto serviceLogin(@RequestBody ServiceLoginRequestDto request) {
        return access.serviceLogin(request);
    }

    @GetMapping("/me")
    public AuthenticatedUserDto me(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return access.requireAuthenticated(authorization);
    }

    @PostMapping("/resolve")
    public AuthenticatedUserDto resolve(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return access.requireAuthenticated(authorization);
    }
}

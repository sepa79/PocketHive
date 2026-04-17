package io.pockethive.auth.client;

import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.DevLoginRequestDto;
import io.pockethive.auth.contract.SessionResponseDto;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

public final class AuthServiceClient {
    private final RestClient restClient;

    public AuthServiceClient(URI baseUrl, Duration connectTimeout, Duration readTimeout) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(readTimeout, "readTimeout");

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.toIntExact(connectTimeout.toMillis()));
        requestFactory.setReadTimeout(Math.toIntExact(readTimeout.toMillis()));

        this.restClient = RestClient.builder()
            .baseUrl(baseUrl.toString())
            .requestFactory(requestFactory)
            .build();
    }

    AuthServiceClient(RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
    }

    public AuthenticatedUserDto me(String authorizationHeader) {
        return get("/api/auth/me", authorizationHeader);
    }

    public AuthenticatedUserDto resolve(String authorizationHeader) {
        try {
            return restClient.post()
                .uri("/api/auth/resolve")
                .header(HttpHeaders.AUTHORIZATION, requireAuthorizationHeader(authorizationHeader))
                .retrieve()
                .body(AuthenticatedUserDto.class);
        } catch (RestClientResponseException e) {
            throw new AuthServiceClientException("Auth service resolve failed", e.getStatusCode().value(), e);
        }
    }

    public SessionResponseDto devLogin(String username) {
        Objects.requireNonNull(username, "username");
        try {
            return restClient.post()
                .uri("/api/auth/dev/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new DevLoginRequestDto(username))
                .retrieve()
                .body(SessionResponseDto.class);
        } catch (RestClientResponseException e) {
            throw new AuthServiceClientException("Auth service dev login failed", e.getStatusCode().value(), e);
        }
    }

    private AuthenticatedUserDto get(String path, String authorizationHeader) {
        try {
            return restClient.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, requireAuthorizationHeader(authorizationHeader))
                .retrieve()
                .body(AuthenticatedUserDto.class);
        } catch (RestClientResponseException e) {
            throw new AuthServiceClientException("Auth service request failed for " + path, e.getStatusCode().value(), e);
        }
    }

    private static String requireAuthorizationHeader(String authorizationHeader) {
        return Objects.requireNonNull(authorizationHeader, "authorizationHeader");
    }
}

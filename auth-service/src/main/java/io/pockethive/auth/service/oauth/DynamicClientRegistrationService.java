package io.pockethive.auth.service.oauth;

import io.pockethive.auth.contract.PocketHiveMcpScopes;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponseType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

/** Validates RFC 7591 public-client metadata and stores one exact normalized registration. */
public final class DynamicClientRegistrationService {
    static final String REGISTRATION_PATH = "/oauth/register";
    private static final int MAX_CLIENT_NAME_LENGTH = 128;
    private static final int MAX_REDIRECT_URIS = 8;
    private static final int MAX_REDIRECT_URI_LENGTH = 2048;
    private static final int MAX_CLIENT_ID_ATTEMPTS = 8;
    private static final String CLIENT_ID_PREFIX = "phmcp_client_";
    private static final String TOKEN_AUTHENTICATION_NONE = ClientAuthenticationMethod.NONE.getValue();
    private static final String AUTHORIZATION_CODE = AuthorizationGrantType.AUTHORIZATION_CODE.getValue();
    private static final String REFRESH_TOKEN = AuthorizationGrantType.REFRESH_TOKEN.getValue();
    private static final String RESPONSE_CODE = OAuth2AuthorizationResponseType.CODE.getValue();

    private final PocketHiveRegisteredClientRepository clients;
    private final TokenSettings tokenSettings;
    private final Clock clock;
    private final SecureRandom random;

    public DynamicClientRegistrationService(PocketHiveRegisteredClientRepository clients,
                                            TokenSettings tokenSettings, Clock clock) {
        this(clients, tokenSettings, clock, new SecureRandom());
    }

    DynamicClientRegistrationService(PocketHiveRegisteredClientRepository clients,
                                     TokenSettings tokenSettings, Clock clock, SecureRandom random) {
        this.clients = java.util.Objects.requireNonNull(clients, "clients");
        this.tokenSettings = java.util.Objects.requireNonNull(tokenSettings, "tokenSettings");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.random = java.util.Objects.requireNonNull(random, "random");
    }

    public DynamicClientRegistrationResponse register(DynamicClientRegistrationRequest request) {
        if (request == null) throw invalidMetadata("Registration metadata is required");
        String clientName = clientName(request.clientName());
        List<String> redirectUris = redirectUris(request.redirectUris());
        List<String> grantTypes = grantTypes(request.grantTypes());
        responseTypes(request.responseTypes());
        if (!TOKEN_AUTHENTICATION_NONE.equals(request.tokenEndpointAuthMethod())) {
            throw invalidMetadata("Only public clients using token_endpoint_auth_method none are supported");
        }
        List<String> scopes = scopes(request.scope());
        String clientId = newClientId();
        Instant issuedAt = clock.instant();

        RegisteredClient.Builder builder = RegisteredClient.withId("dynamic:" + clientId)
            .clientId(clientId)
            .clientIdIssuedAt(issuedAt)
            .clientName(clientName)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .clientSettings(ClientSettings.builder()
                .requireProofKey(true)
                .requireAuthorizationConsent(true)
                .build())
            .tokenSettings(tokenSettings);
        if (grantTypes.contains(REFRESH_TOKEN)) {
            builder.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
        }
        redirectUris.forEach(builder::redirectUri);
        scopes.forEach(builder::scope);
        try {
            clients.save(builder.build());
        } catch (DynamicClientCapacityException exception) {
            throw new DynamicClientRegistrationException("temporarily_unavailable", exception.getMessage(),
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
        }
        return new DynamicClientRegistrationResponse(
            clientId, issuedAt.getEpochSecond(), clientName, redirectUris, grantTypes,
            List.of(RESPONSE_CODE), TOKEN_AUTHENTICATION_NONE, String.join(" ", scopes));
    }

    private String newClientId() {
        byte[] entropy = new byte[32];
        for (int attempt = 0; attempt < MAX_CLIENT_ID_ATTEMPTS; attempt++) {
            random.nextBytes(entropy);
            String candidate = CLIENT_ID_PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
            if (clients.findByClientId(candidate) == null) return candidate;
        }
        throw new DynamicClientRegistrationException(
            "temporarily_unavailable",
            "Unable to allocate a unique client identifier",
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static String clientName(String value) {
        if (value == null) throw invalidMetadata("client_name is required");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_CLIENT_NAME_LENGTH
            || normalized.chars().anyMatch(Character::isISOControl)) {
            throw invalidMetadata("client_name is invalid");
        }
        return normalized;
    }

    private static List<String> redirectUris(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > MAX_REDIRECT_URIS
            || new HashSet<>(values).size() != values.size()) {
            throw invalidRedirect("One to eight unique redirect_uris are required");
        }
        List<String> accepted = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null || value.length() > MAX_REDIRECT_URI_LENGTH) {
                throw invalidRedirect("redirect_uri is invalid");
            }
            URI uri;
            try {
                uri = URI.create(value);
            } catch (IllegalArgumentException exception) {
                throw invalidRedirect("redirect_uri is invalid");
            }
            if (!trustedRedirect(uri)) throw invalidRedirect("redirect_uri is not trusted");
            accepted.add(uri.toASCIIString());
        }
        return List.copyOf(accepted);
    }

    private static boolean trustedRedirect(URI uri) {
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            return false;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) return true;
        if (!"http".equalsIgnoreCase(uri.getScheme())) return false;
        return "localhost".equalsIgnoreCase(uri.getHost())
            || "127.0.0.1".equals(uri.getHost())
            || "[::1]".equals(uri.getHost())
            || "[0:0:0:0:0:0:0:1]".equals(uri.getHost());
    }

    private static List<String> grantTypes(List<String> values) {
        if (values == null || values.isEmpty() || new HashSet<>(values).size() != values.size()) {
            throw invalidMetadata("grant_types must be unique and non-empty");
        }
        Set<String> requested = Set.copyOf(values);
        if (!requested.contains(AUTHORIZATION_CODE)
            || !Set.of(AUTHORIZATION_CODE, REFRESH_TOKEN).containsAll(requested)) {
            throw invalidMetadata("Only authorization_code and optional refresh_token are supported");
        }
        return requested.contains(REFRESH_TOKEN)
            ? List.of(AUTHORIZATION_CODE, REFRESH_TOKEN)
            : List.of(AUTHORIZATION_CODE);
    }

    private static void responseTypes(List<String> values) {
        if (values == null || !values.equals(List.of(RESPONSE_CODE))) {
            throw invalidMetadata("response_types must contain only code");
        }
    }

    private static List<String> scopes(String value) {
        if (value == null || value.isBlank()) throw invalidMetadata("scope is required");
        List<String> requested = List.of(value.trim().split("\\s+"));
        Set<String> unique = Set.copyOf(requested);
        if (unique.size() != requested.size() || !PocketHiveMcpScopes.COMPANION.containsAll(unique)) {
            throw invalidMetadata("scope contains a duplicate, unknown, or prohibited value");
        }
        return PocketHiveMcpScopes.COMPANION_ORDERED.stream().filter(unique::contains).toList();
    }

    private static DynamicClientRegistrationException invalidMetadata(String description) {
        return new DynamicClientRegistrationException("invalid_client_metadata", description);
    }

    private static DynamicClientRegistrationException invalidRedirect(String description) {
        return new DynamicClientRegistrationException("invalid_redirect_uri", description);
    }
}

package io.pockethive.auth.service.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.auth.contract.PocketHiveMcpScopes;
import io.pockethive.auth.service.config.AuthServiceOAuthProperties;
import io.pockethive.auth.service.config.AuthServiceProperties;
import io.pockethive.auth.service.service.InMemoryUserStore;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponseType;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadataClaimNames;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

/**
 * Responsibility: Configure the PocketHive OAuth authorization server from validated canonical properties.
 * Must not: Bypass canonical scope policy, client authentication, or Spring Authorization Server contracts.
 * Contract: docs/architecture/AUTH_SERVICE_API_SPEC.md and docs/AUTH-BEHAVIOR.md.
 */

@Configuration
public class PocketHiveOAuthConfiguration {
    private static final String PKCE_S256 = "S256";

    @Bean
    TokenSettings oauthTokenSettings(AuthServiceProperties properties) {
        AuthServiceOAuthProperties oauth = requireValid(properties);
        return TokenSettings.builder()
            .authorizationCodeTimeToLive(oauth.getAuthorizationCodeTtl())
            .accessTokenTimeToLive(oauth.getAccessTokenTtl())
            .refreshTokenTimeToLive(oauth.getRefreshTokenTtl())
            .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
            .reuseRefreshTokens(false)
            .build();
    }

    @Bean
    Clock oauthClock() {
        return Clock.systemUTC();
    }

    @Bean
    DynamicClientStateStore dynamicClientStateStore(AuthServiceProperties properties, ObjectMapper mapper) {
        AuthServiceOAuthProperties oauth = requireValid(properties);
        return new JsonFileDynamicClientStateStore(mapper, oauth.getDynamicClientStatePath());
    }

    @Bean
    PocketHiveRegisteredClientRepository registeredClients(AuthServiceProperties properties,
                                                            PasswordEncoder encoder, TokenSettings tokens,
                                                            Clock clock, DynamicClientStateStore stateStore) {
        AuthServiceOAuthProperties oauth = requireValid(properties);
        RegisteredClient vscode = RegisteredClient.withId("pockethive-vscode-public")
            .clientId(oauth.getVscodeClientId())
            .clientName("PocketHive VS Code")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .redirectUri(oauth.getVscodeRedirectUri().toString())
            .scopes(scopes -> scopes.addAll(PocketHiveMcpScopes.COMPANION))
            .clientSettings(ClientSettings.builder()
                .requireProofKey(true)
                .requireAuthorizationConsent(true)
                .build())
            .tokenSettings(tokens)
            .build();
        RegisteredClient introspection = RegisteredClient.withId("pockethive-mcp-resource-server")
            .clientId(oauth.getIntrospectionClientId())
            .clientSecret(encoder.encode(oauth.getIntrospectionClientSecret()))
            .clientName("PocketHive MCP resource server")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(new AuthorizationGrantType("urn:pockethive:grant-type:introspection-only"))
            .tokenSettings(tokens)
            .build();
        return new PocketHiveRegisteredClientRepository(List.of(vscode, introspection),
            oauth.getDynamicClientCapacity(), oauth.getDynamicClientTtl(), clock, stateStore, tokens);
    }

    @Bean
    DynamicClientRegistrationService dynamicClientRegistrationService(
        PocketHiveRegisteredClientRepository clients, TokenSettings tokens, Clock clock
    ) {
        return new DynamicClientRegistrationService(clients, tokens, clock);
    }

    @Bean
    PasswordEncoder oauthClientPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    OAuth2AuthorizationService oauthAuthorizations() {
        return new InMemoryOAuth2AuthorizationService();
    }

    @Bean
    OAuth2AuthorizationConsentService oauthConsents() {
        return new InMemoryOAuth2AuthorizationConsentService();
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(AuthServiceProperties properties) {
        return AuthorizationServerSettings.builder()
            .issuer(properties.getOauth().getIssuer().toString())
            .authorizationEndpoint("/oauth/authorize")
            .tokenEndpoint("/oauth/token")
            .tokenRevocationEndpoint("/oauth/revoke")
            .tokenIntrospectionEndpoint("/oauth/introspect")
            .build();
    }

    @Bean
    OAuth2TokenGenerator<? extends OAuth2Token> oauthTokenGenerator(AuthServiceProperties properties,
                                                                    InMemoryUserStore users) {
        return new DelegatingOAuth2TokenGenerator(
            new PocketHiveAuthorizationCodeGenerator(),
            new PocketHiveAccessTokenGenerator(properties, users),
            new PocketHiveRefreshTokenGenerator());
    }

    @Bean
    @Order(1)
    SecurityFilterChain oauthEndpoints(HttpSecurity http, AuthServiceProperties properties,
                                       InMemoryUserStore users, RegisteredClientRepository clients,
                                       AuthorizationServerSettings settings,
                                       OAuthBrowserAuthorizationFailureHandler authorizationFailureHandler)
        throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer =
            OAuth2AuthorizationServerConfigurer.authorizationServer();
        http.securityMatcher(authorizationServer.getEndpointsMatcher())
            .with(authorizationServer, server -> server
                .authorizationServerMetadataEndpoint(metadata -> metadata
                    .authorizationServerMetadataCustomizer(builder -> builder.claims(claims -> {
                        String issuer = properties.getOauth().getIssuer().toString();
                        claims.clear();
                        claims.put(OAuth2AuthorizationServerMetadataClaimNames.ISSUER, issuer);
                        claims.put(OAuth2AuthorizationServerMetadataClaimNames.AUTHORIZATION_ENDPOINT,
                            issuer + "/oauth/authorize");
                        claims.put(OAuth2AuthorizationServerMetadataClaimNames.TOKEN_ENDPOINT,
                            issuer + "/oauth/token");
                        claims.put(OAuth2AuthorizationServerMetadataClaimNames.REGISTRATION_ENDPOINT,
                            issuer + DynamicClientRegistrationService.REGISTRATION_PATH);
                        claims.put(OAuth2AuthorizationServerMetadataClaimNames.TOKEN_ENDPOINT_AUTH_METHODS_SUPPORTED,
                            List.of(ClientAuthenticationMethod.NONE.getValue()));
                        claims.put(OAuth2AuthorizationServerMetadataClaimNames.SCOPES_SUPPORTED,
                            PocketHiveMcpScopes.COMPANION_ORDERED);
                        claims.put(OAuth2AuthorizationServerMetadataClaimNames.RESPONSE_TYPES_SUPPORTED,
                            List.of(OAuth2AuthorizationResponseType.CODE.getValue()));
                        claims.put(OAuth2AuthorizationServerMetadataClaimNames.GRANT_TYPES_SUPPORTED,
                            List.of(AuthorizationGrantType.AUTHORIZATION_CODE.getValue(),
                                AuthorizationGrantType.REFRESH_TOKEN.getValue()));
                        claims.put(OAuth2AuthorizationServerMetadataClaimNames.REVOCATION_ENDPOINT,
                            issuer + "/oauth/revoke");
                        claims.put(
                            OAuth2AuthorizationServerMetadataClaimNames.REVOCATION_ENDPOINT_AUTH_METHODS_SUPPORTED,
                            List.of(ClientAuthenticationMethod.NONE.getValue()));
                        claims.put(OAuth2AuthorizationServerMetadataClaimNames.INTROSPECTION_ENDPOINT,
                            issuer + "/oauth/introspect");
                        claims.put(
                            OAuth2AuthorizationServerMetadataClaimNames.INTROSPECTION_ENDPOINT_AUTH_METHODS_SUPPORTED,
                            List.of(ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue()));
                        claims.put(OAuth2AuthorizationServerMetadataClaimNames.CODE_CHALLENGE_METHODS_SUPPORTED,
                            List.of(PKCE_S256));
                    })))
                .clientAuthentication(client -> client
                    .authenticationConverters(converters -> converters.add(0,
                        new PocketHivePublicSessionClientAuthenticationConverter(settings)))
                    .authenticationProviders(providers -> providers.add(0,
                        new PocketHivePublicSessionClientAuthenticationProvider(clients))))
                .authorizationEndpoint(endpoint -> endpoint
                    .consentPage("/oauth/consent")
                    .errorResponseHandler(authorizationFailureHandler)
                    .authorizationRequestConverters(converters -> converters.add(0,
                        new PocketHiveInteractiveAuthorizationRequestConverter(users)))
                    .authenticationProviders(providers -> providers.forEach(provider -> {
                        if (provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider code) {
                            code.setAuthenticationValidator(new McpScopeAuthorizationValidator(users));
                        }
                    }))))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/.well-known/oauth-authorization-server").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors.authenticationEntryPoint(
                new LoginUrlAuthenticationEntryPoint("/oauth/dev/login")))
            .csrf(csrf -> csrf.ignoringRequestMatchers(authorizationServer.getEndpointsMatcher()));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain applicationEndpoints(HttpSecurity http) throws Exception {
        return http
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/.well-known/oauth-authorization-server", "/oauth/dev/login").permitAll()
                .requestMatchers(DynamicClientRegistrationService.REGISTRATION_PATH).permitAll()
                .requestMatchers("/oauth/consent").authenticated()
                .anyRequest().permitAll())
            .csrf(csrf -> csrf.ignoringRequestMatchers(
                "/api/**", "/actuator/**", DynamicClientRegistrationService.REGISTRATION_PATH))
            .build();
    }

    @Bean
    FilterRegistrationBean<OAuthResourceParameterFilter> oauthResourceParameterFilter(
        AuthServiceProperties properties) {
        var registration = new FilterRegistrationBean<>(
            new OAuthResourceParameterFilter(properties.getOauth().getResource().toString()));
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 1);
        return registration;
    }

    static AuthServiceOAuthProperties requireValid(AuthServiceProperties properties) {
        AuthServiceOAuthProperties oauth = properties.getOauth();
        if (oauth == null || oauth.getIssuer() == null || oauth.getResource() == null
            || oauth.getVscodeRedirectUri() == null || blank(oauth.getVscodeClientId())
            || blank(oauth.getIntrospectionClientId()) || blank(oauth.getIntrospectionClientSecret())
            || oauth.getAuthorizationCodeTtl() == null || oauth.getAuthorizationCodeTtl().isNegative()
            || oauth.getAuthorizationCodeTtl().isZero() || oauth.getAccessTokenTtl() == null
            || oauth.getAccessTokenTtl().isNegative() || oauth.getAccessTokenTtl().isZero()
            || oauth.getRefreshTokenTtl() == null || oauth.getRefreshTokenTtl().isNegative()
            || oauth.getRefreshTokenTtl().isZero()
            || oauth.getDynamicClientTtl() == null || oauth.getDynamicClientTtl().isNegative()
            || oauth.getDynamicClientTtl().isZero()
            || oauth.getDynamicClientTtl().compareTo(oauth.getRefreshTokenTtl()) <= 0
            || oauth.getDynamicClientCapacity() < 1
            || oauth.getDynamicClientStatePath() == null
            || !oauth.getDynamicClientStatePath().isAbsolute()
            || !secureOrLoopback(oauth.getIssuer()) || !secureOrLoopback(oauth.getResource())
            || !vscodeRedirectBase(oauth.getVscodeRedirectUri())) {
            throw new IllegalStateException("POCKETHIVE_OAUTH_CONFIGURATION_INVALID");
        }
        return oauth;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean secureOrLoopback(java.net.URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme())
            || ("http".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())
                    || "::1".equals(uri.getHost())));
    }

    private static boolean vscodeRedirectBase(java.net.URI uri) {
        return "http".equalsIgnoreCase(uri.getScheme())
            && "127.0.0.1".equals(uri.getHost())
            && uri.getPort() == -1
            && "/callback".equals(uri.getPath())
            && uri.getUserInfo() == null
            && uri.getFragment() == null
            && uri.getQuery() == null;
    }
}

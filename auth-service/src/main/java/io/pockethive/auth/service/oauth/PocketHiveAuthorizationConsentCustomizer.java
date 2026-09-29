package io.pockethive.auth.service.oauth;

import java.util.Set;
import java.util.function.Consumer;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationToken;

/**
 * Responsibility: Apply explicit browser Decline to Spring's already validated consent proposal.
 * Must not: Validate client/state/scopes, mutate authorization stores, or issue an authorization outcome.
 * Contract: RESP-OAUTH-CONSENT-ACTION — docs/architecture/runtime-responsibilities.md#resp-oauth-consent-action.
 */
final class PocketHiveAuthorizationConsentCustomizer
    implements Consumer<OAuth2AuthorizationConsentAuthenticationContext> {

    @Override
    public void accept(OAuth2AuthorizationConsentAuthenticationContext context) {
        OAuth2AuthorizationConsentAuthenticationToken request = context.getAuthentication();
        Object supplied = request.getAdditionalParameters().get(OAuthConsentAction.PARAMETER);
        // Scope-only consent submissions use Spring's standard semantics.
        if (supplied == null) return;
        OAuthConsentAction action;
        try {
            action = OAuthConsentAction.parse(supplied);
        } catch (IllegalArgumentException exception) {
            throw new OAuth2AuthorizationCodeRequestAuthenticationException(
                new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST), null);
        }
        if (action == OAuthConsentAction.CANCEL) {
            context.getAuthorizationConsent().authorities(Set::clear);
        }
    }
}

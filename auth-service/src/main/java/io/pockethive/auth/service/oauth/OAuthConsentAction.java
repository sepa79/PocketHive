package io.pockethive.auth.service.oauth;

/**
 * Responsibility: Define the browser consent action field and its exact wire values.
 * Must not: Validate pending authorization, mutate consent, or infer an action from scopes.
 * Contract: RESP-OAUTH-CONSENT-ACTION — docs/architecture/runtime-responsibilities.md#resp-oauth-consent-action.
 */
enum OAuthConsentAction {
    APPROVE("approve"),
    CANCEL("cancel");

    static final String PARAMETER = "consent_action";
    private final String value;

    OAuthConsentAction(String value) {
        this.value = value;
    }

    String value() {
        return value;
    }

    static OAuthConsentAction parse(Object value) {
        for (OAuthConsentAction action : values()) {
            if (action.value.equals(value)) return action;
        }
        throw new IllegalArgumentException("Invalid consent action");
    }
}

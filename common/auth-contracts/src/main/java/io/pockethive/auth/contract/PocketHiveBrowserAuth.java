package io.pockethive.auth.contract;

/**
 * Responsibility: Name the first-party browser identity contract.
 * Must not: Grant product access or define MCP scope policy.
 * Contract: RESP-BROWSER-SSO — docs/architecture/runtime-responsibilities.md#resp-browser-sso.
 */
public final class PocketHiveBrowserAuth {
    public static final String RESOURCE = "urn:pockethive:web";
    public static final String SCOPE = "pockethive:web";
    public static final String TOKEN_PREFIX = "phweb_";
    public static final String CLIENT_SETTING = "pockethive.browser-client";
    public static final String PH_CLIENT_ID = "pockethive-web";
    public static final String PP_CLIENT_ID = "pocketplanner-web";
    public static final String PD_CLIENT_ID = "pocketdata-web";

    private PocketHiveBrowserAuth() {}
}

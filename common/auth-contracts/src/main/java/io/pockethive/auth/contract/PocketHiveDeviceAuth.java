package io.pockethive.auth.contract;

/**
 * Responsibility: Name the callback-free first-party device-login contract.
 * Must not: Authenticate clients, issue tokens or decide product permissions.
 * Contract: RESP-BROWSER-DEVICE-LOGIN — docs/architecture/runtime-responsibilities.md#resp-browser-device-login.
 */
public final class PocketHiveDeviceAuth {
    public static final String HIVE_CLIENT_ID = "pockethive-device";
    public static final String PLANNER_CLIENT_ID = "pocketplanner-device";
    public static final String DATA_CLIENT_ID = "pocketdata-device";
    public static final String AUTHORIZATION_PATH = "/oauth/device_authorization";
    public static final String VERIFICATION_PATH = "/oauth/device_verification";
    public static final String CONSENT_PATH = "/oauth/device/consent";
    public static final String COMPLETE_PATH = "/oauth/device/complete";
    public static final String CONFIG_PATH = "/api/auth/device/config";
    public static final String LOGOUT_PATH = "/oauth/device/logout";

    private PocketHiveDeviceAuth() {}
}

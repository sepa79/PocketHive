package io.pockethive.worker.sdk.auth;


/**
 * Responsibility: read normalized ordinary credential fields and resolved token keys. Must not:
 * load profiles, acquire tokens or validate the signed request wire contract. Contract:
 * RESP-WORK-AUTH-APPLICATION —
 * docs/architecture/runtime-responsibilities.md#resp-work-auth-application.
 */
final class AuthProfileFields {
  static String tokenKey(AuthProfile profile) {
    return profile.getStorage().getMode() == AuthStorageMode.REDIS
        ? profile.getStorage().getTokenKey()
        : null;
  }

  static String required(AuthProfile profile, String key) {
    String value = optional(profile, key);
    if (value == null) {
      throw new IllegalArgumentException("Auth profile missing required field '" + key + "'");
    }
    return value;
  }

  static String optional(AuthProfile profile, String key) {
    Object value = profile.mergedProperties().get(key);
    if (value == null) {
      return null;
    }
    String text = value.toString().trim();
    return text.isEmpty() ? null : text;
  }
}

package io.pockethive.worker.sdk.templating;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ThreadLocal holder for auth tokens accessible from SpEL templates.
 */
public final class AuthTokenHolder {

    private static final ThreadLocal<Map<String, String>> TOKENS = ThreadLocal.withInitial(ConcurrentHashMap::new);

    public static void setToken(String tokenKey, String token) {
        if (tokenKey != null && token != null) {
            TOKENS.get().put(tokenKey, token);
        }
    }

    static String getToken(String tokenKey) {
        if (tokenKey == null) {
            return "";
        }
        return TOKENS.get().getOrDefault(tokenKey, "");
    }

    static void clear() {
        TOKENS.get().clear();
    }

    private AuthTokenHolder() {
    }
}

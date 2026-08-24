package io.pockethive.auth.contract;

import java.util.List;
import java.util.Set;

public final class PocketHiveMcpScopes {
    public static final String DISCOVER = "pockethive:mcp:discover";
    public static final String READ = "pockethive:mcp:read";
    public static final String OPERATE = "pockethive:mcp:operate";
    public static final String AUTHOR = "pockethive:mcp:author";
    public static final String PUBLISH = "pockethive:mcp:publish";
    public static final String CLEANUP = "pockethive:mcp:cleanup";
    public static final List<String> ALL_ORDERED = List.of(
        DISCOVER, READ, OPERATE, AUTHOR, PUBLISH, CLEANUP);
    public static final Set<String> ALL = Set.copyOf(ALL_ORDERED);
    public static final List<String> COMPANION_ORDERED = List.of(
        DISCOVER, READ, OPERATE, AUTHOR, PUBLISH);
    public static final Set<String> COMPANION = Set.copyOf(COMPANION_ORDERED);

    private PocketHiveMcpScopes() {
    }
}

package io.pockethive.work.config;

import java.util.regex.Pattern;

/**
 * Responsibility: expose an immutable compiled projection produced by WorkConfigurationParser.
 * Must not: accept raw declarations, select a destination or perform Redis operations.
 * Contract: RESP-WORK-REDIS-ROUTES — docs/architecture/runtime-responsibilities.md#resp-work-redis-routes.
 */
public final class RedisRoute {
    private final Pattern payloadPattern;
    private final String headerName;
    private final Pattern headerPattern;
    private final String list;

    RedisRoute(Pattern payloadPattern, String headerName, Pattern headerPattern, String list) {
        this.payloadPattern = payloadPattern;
        this.headerName = headerName;
        this.headerPattern = headerPattern;
        this.list = list;
    }

    public Pattern payloadPattern() { return payloadPattern; }
    public String headerName() { return headerName; }
    public Pattern headerPattern() { return headerPattern; }
    public String list() { return list; }
}

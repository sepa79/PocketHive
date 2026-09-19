package io.pockethive.redis.config;

/**
 * Responsibility: carry original route field values from decoding to canonical validation.
 * Must not: coerce fields to text, validate regexes or be used as a resolved runtime route.
 * Contract: RESP-WORK-REDIS-ROUTES — docs/architecture/runtime-responsibilities.md#resp-work-redis-routes.
 */
public record RedisRouteDefinition(Object match, Object header, Object headerMatch, Object list) {
}

package io.pockethive.artemis.topology;

import io.pockethive.artemis.config.ArtemisSettingValues;
import io.pockethive.topology.work.WorkResourceIdentity;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Responsibility: resolve collision-free channel names and reversible native resource addresses.
 * Must not: create resources, read environment settings or retain runtime ownership state.
 * Contract: RESP-ARTEMIS-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-artemis-resource-names.
 */
public final class ArtemisResourceNames {
    private static final String RESOURCE_SCHEME = "artemis";
    private final String namespace;

    public ArtemisResourceNames(String namespace) {
        this.namespace = ArtemisSettingValues.requiredText(namespace, "namespace");
    }

    public String namespace() { return namespace; }

    public String channel(String swarmId, String logicalChannel) {
        return encode(namespace) + "." + encode(ArtemisSettingValues.requiredText(swarmId, "swarmId"))
            + "." + encode(ArtemisSettingValues.requiredText(logicalChannel, "logicalChannel"));
    }

    public String debugTapDivert(String swarmId, String role, String tapId) {
        return debugTap(swarmId, role, tapId) + ".divert";
    }

    public String debugTap(String swarmId, String role, String tapId) {
        return encode(namespace) + "." + encode(ArtemisSettingValues.requiredText(swarmId, "swarmId"))
            + ".tap." + encode(ArtemisSettingValues.requiredText(role, "role"))
            + "." + encode(ArtemisSettingValues.requiredText(tapId, "tapId"));
    }

    public static String resourceAddress(WorkResourceIdentity resource) {
        var kind = ArtemisResourceKind.require(resource);
        return RESOURCE_SCHEME + "://" + kind.name().toLowerCase(Locale.ROOT) + "/" + encode(resource.name());
    }

    public static WorkResourceIdentity identify(String address) {
        URI uri = URI.create(ArtemisSettingValues.requiredText(address, "resource address"));
        if (!RESOURCE_SCHEME.equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
            || uri.getPort() != -1 || uri.getQuery() != null || uri.getFragment() != null
            || uri.getRawPath() == null || !uri.getRawPath().startsWith("/")) {
            throw new IllegalArgumentException("Expected an exact Artemis resource address");
        }
        var kind = ArtemisResourceKind.valueOf(uri.getHost().toUpperCase(Locale.ROOT));
        String name = URLDecoder.decode(uri.getRawPath().substring(1), StandardCharsets.UTF_8);
        var resource = kind.identity(name);
        if (!resourceAddress(resource).equals(address)) {
            throw new IllegalArgumentException("Expected a canonical Artemis resource address");
        }
        return resource;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace(".", "%2E").replace("*", "%2A");
    }
}

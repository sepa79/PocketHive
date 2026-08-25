package io.pockethive.mcp.security;

import io.pockethive.auth.contract.PocketHiveMcpScopes;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProtectedResourceMetadataController {
    private final PocketHiveMcpProperties properties;

    public ProtectedResourceMetadataController(PocketHiveMcpProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/.well-known/oauth-protected-resource")
    public Map<String, Object> metadata() {
        return Map.of(
            "resource", properties.oauthResource().toString(),
            "authorization_servers", List.of(properties.oauthIssuer().toString()),
            "scopes_supported", PocketHiveMcpScopes.COMPANION_ORDERED,
            "bearer_methods_supported", List.of("header"));
    }
}

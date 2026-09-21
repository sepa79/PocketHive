package io.pockethive.orchestrator.infra.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Responsibility: project packaged canonical Control Plane schemas into one immutable bootstrap document.
 * Must not: rewrite validation constraints, fetch remote schemas or own HTTP responses.
 * Contract: RESP-CONTROL-SCHEMA-BOOTSTRAP — docs/architecture/runtime-responsibilities.md#resp-control-schema-bootstrap--control-plane-schema-delivery.
 */
@Component
public final class ControlPlaneSchemaBundle {
    private static final String SCHEMA_ROOT = "/io/pockethive/controlplane/schema/";
    private static final String LIFECYCLE_SCHEMA = "swarm-lifecycle.schema.json";
    private final byte[] bytes;
    private final String etag;

    public ControlPlaneSchemaBundle(ObjectMapper mapper) {
        try {
            ObjectNode root = readSchema(mapper, "control-events.schema.json");
            ObjectNode lifecycle = readSchema(mapper, LIFECYCLE_SCHEMA);
            ObjectNode definitions = (ObjectNode) root.required("$defs");
            if (definitions.has(LIFECYCLE_SCHEMA)) {
                throw new IllegalStateException("Embedded schema key already exists: " + LIFECYCLE_SCHEMA);
            }
            definitions.set(LIFECYCLE_SCHEMA, lifecycle);
            bytes = mapper.writeValueAsBytes(root);
            etag = "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + "\"";
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot build canonical control-plane schema bundle", exception);
        }
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public String etag() {
        return etag;
    }

    private static ObjectNode readSchema(ObjectMapper mapper, String name) throws Exception {
        try (InputStream input = ControlPlaneSchemaBundle.class.getResourceAsStream(SCHEMA_ROOT + name)) {
            if (input == null) {
                throw new IllegalStateException("Missing packaged schema: " + name);
            }
            return (ObjectNode) mapper.readTree(input);
        }
    }
}

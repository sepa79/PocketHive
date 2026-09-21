package io.pockethive.orchestrator.infra.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneSchemaBundleTest {
    @Test
    void embedsUnchangedCanonicalDependencyAndHashesCompleteDocument() throws Exception {
        var mapper = new ObjectMapper();
        var bundle = new ControlPlaneSchemaBundle(mapper);
        var actual = (ObjectNode) mapper.readTree(bundle.bytes());
        try (var root = getClass().getResourceAsStream("/io/pockethive/controlplane/schema/control-events.schema.json");
             var lifecycle = getClass().getResourceAsStream("/io/pockethive/controlplane/schema/swarm-lifecycle.schema.json")) {
            var definitions = (ObjectNode) actual.required("$defs");
            assertThat(definitions.remove("swarm-lifecycle.schema.json")).isEqualTo(mapper.readTree(lifecycle));
            assertThat(actual).isEqualTo(mapper.readTree(root));
        }
        assertThat(bundle.etag()).isEqualTo("\"" + HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bundle.bytes())) + "\"");
    }
}

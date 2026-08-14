package io.pockethive.controlplane.codec;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PackagedControlPlaneSchemaTest {

  @ParameterizedTest
  @ValueSource(strings = {"control-events.schema.json", "swarm-lifecycle.schema.json"})
  void runtimeResourceIsTheExactAuthoritativeDocumentationSchema(String schemaName) throws Exception {
    Path source = repositoryRoot().resolve("docs/spec").resolve(schemaName);
    byte[] expected = Files.readAllBytes(source);
    String resource = "/io/pockethive/controlplane/schema/" + schemaName;

    try (InputStream input = PackagedControlPlaneSchemaTest.class.getResourceAsStream(resource)) {
      assertThat(input).as("packaged schema %s", resource).isNotNull();
      assertThat(input.readAllBytes()).isEqualTo(expected);
    }
  }

  private static Path repositoryRoot() {
    String configured = System.getProperty("maven.multiModuleProjectDirectory");
    Path current = configured == null || configured.isBlank()
        ? Path.of("").toAbsolutePath()
        : Path.of(configured);
    while (current != null && !Files.exists(current.resolve("docs/spec/control-events.schema.json"))) {
      current = current.getParent();
    }
    if (current == null) {
      throw new IllegalStateException("Cannot locate PocketHive repository root");
    }
    return current;
  }
}

package io.pockethive.controlplane.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.control.ControlPlaneEnvelope;
import io.pockethive.control.AlertMessage;
import io.pockethive.control.CommandOutcome;
import io.pockethive.control.CommandResult;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.JournalEvent;
import io.pockethive.control.StatusMetric;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.messaging.SignalMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ControlPlaneBoundaryArchitectureTest {

  private static final Pattern CANONICAL_ENVELOPE_READ = Pattern.compile(
      "(readValue|treeToValue|convertValue)\\([^;]*"
          + "(ControlSignal|CommandResult|CommandOutcome|JournalEvent|AlertMessage|StatusMetric)\\.class",
      Pattern.DOTALL);
  private static final Pattern RAW_LISTENER_READ = Pattern.compile(
      "(readTree|readValue)\\s*\\(", Pattern.DOTALL);

  @Test
  void transportMessagesCanOnlyCarryCanonicalEnvelopes() throws Exception {
    assertThat(SignalMessage.class.getRecordComponents()[1].getType()).isEqualTo(ControlPlaneEnvelope.class);
    assertThat(EventMessage.class.getRecordComponents()[1].getType()).isEqualTo(ControlPlaneEnvelope.class);
  }

  @Test
  void canonicalEnvelopeHierarchyIsClosedToServiceLocalDtos() {
    assertThat(ControlPlaneEnvelope.class.isSealed()).isTrue();
    assertThat(ControlPlaneEnvelope.class.getPermittedSubclasses())
        .containsExactlyInAnyOrder(
            AlertMessage.class,
            CommandOutcome.class,
            CommandResult.class,
            ControlSignal.class,
            JournalEvent.class,
            StatusMetric.class);
  }

  @Test
  void productionCodeHasNoAlternateEnvelopeJsonBoundary() throws Exception {
    Path root = repositoryRoot();
    List<String> violations = new ArrayList<>();
    try (var files = Files.walk(root)) {
      files.filter(path -> path.toString().contains("/src/main/java/"))
          .filter(path -> !path.startsWith(root.resolve("e2e-tests")))
          .filter(path -> path.toString().endsWith(".java"))
          .forEach(path -> inspect(root, path, violations));
    }
    assertThat(violations).as("alternate control-plane JSON boundaries").isEmpty();
  }

  private static void inspect(Path root, Path path, List<String> violations) {
    try {
      String source = Files.readString(path);
      String relative = root.relativize(path).toString();
      if (source.contains("ControlPlaneJson.write(")) {
        violations.add(relative + " serializes a control-plane envelope outside ControlPlaneCodec");
      }
      if (!relative.contains("/codec/") && CANONICAL_ENVELOPE_READ.matcher(source).find()) {
        violations.add(relative + " parses a raw control-plane envelope outside ControlPlaneCodec");
      }
      if (isControlPlaneListener(source) && RAW_LISTENER_READ.matcher(source).find()) {
        violations.add(relative + " parses raw JSON inside a control-plane listener");
      }
      if (isControlPlaneListener(source)
          && !source.contains("codec.decode(")
          && !source.contains("controlPlaneCodec.decode(")
          && !source.contains("controlPlane.consume(")
          && !source.contains("controlPlaneRuntime.handle(")) {
        violations.add(relative + " does not delegate receive validation to ControlPlaneCodec");
      }
      if (source.contains("convertAndSend(")
          && !relative.endsWith("AmqpControlPlanePublisher.java")
          && !relative.endsWith("RabbitMessageWorkerAdapter.java")) {
        violations.add(relative + " publishes AMQP traffic outside an approved transport adapter");
      }
      if (!relative.endsWith("ControlPlaneCommonAutoConfiguration.java")
          && source.contains("ControlPlaneCodec.create()")) {
        violations.add(relative + " creates a codec outside the shared Spring configuration");
      }
      if (relative.contains("/controlplane/codec/")
          && (source.contains("java.nio.file") || source.contains("Path.of("))) {
        violations.add(relative + " loads a control-plane schema from the runtime filesystem");
      }
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot inspect " + path, exception);
    }
  }

  private static boolean isControlPlaneListener(String source) {
    return source.contains("@RabbitListener")
        && (source.contains("workerControlQueueName")
            || source.contains("swarmControllerControlQueueName")
            || source.contains("managerControlQueueName")
            || source.contains("controllerStatusQueue"));
  }

  private static Path repositoryRoot() {
    String root = System.getProperty("maven.multiModuleProjectDirectory");
    Path current = root == null || root.isBlank() ? Path.of("").toAbsolutePath() : Path.of(root);
    while (current != null && !Files.exists(current.resolve("docs/spec/control-events.schema.json"))) {
      current = current.getParent();
    }
    if (current == null) {
      throw new IllegalStateException("Cannot locate PocketHive repository root");
    }
    return current;
  }
}

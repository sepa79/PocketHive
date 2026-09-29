package io.pockethive.orchestrator.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.pockethive.artemis.api.ArtemisConnectionSettings;
import io.pockethive.artemis.topology.ArtemisResourceNames;
import io.pockethive.artemis.transport.ArtemisSessions;
import io.pockethive.artemis.work.ArtemisWorkResources;
import io.pockethive.artemis.work.ArtemisWorkTopologyResolver;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.swarm.model.lifecycle.RemoveResource;
import io.pockethive.swarm.model.lifecycle.RemoveResourceType;
import io.pockethive.swarm.model.lifecycle.ResourcePlane;
import io.pockethive.topology.work.WorkPlaneResources;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class RuntimeOwnershipManifestFactoryTest {
    @Test
    void nativeWorkDoesNotBlockLaunchProjectionOrBecomeRabbitInventory(CapturedOutput output) {
        var topology = new ArtemisWorkTopologyResolver(new ArtemisResourceNames("ph.work"))
            .resolve("swarm", Set.of("jobs"));
        // No broker: projecting a launch manifest must not open a WORK connection.
        try (var sessions = new ArtemisSessions(new ArtemisConnectionSettings(
            "vm://2147483647", "test-user", "test-password", 500))) {
            var work = new ArtemisWorkResources(sessions);
            var manifest = new RuntimeOwnershipManifestFactory(control(), work)
                .resources("swarm", "controller", topology);

            assertThat(manifest.controlQueues()).containsExactly("ph.control.swarm.swarm-controller.controller");
            assertThat(manifest.workQueues()).isEmpty();
            assertThat(manifest.exchanges()).isEmpty();
            assertThat(topology.resources()).hasSize(2);
            assertThat(work.appliedResources()).isEmpty();
            assertThat(output).contains("Rabbit-only ownership manifest excludes 2 native WORK resources");
        }
    }

    @Test
    void wrongPlaneAndOwnerMappingFailuresStillRejectProjection() {
        var topology = new ArtemisWorkTopologyResolver(new ArtemisResourceNames("ph.work"))
            .resolve("swarm", Set.of("jobs"));
        var work = mock(WorkPlaneResources.class);
        var resource = topology.resources().getFirst();
        when(work.removalTarget(resource)).thenReturn(new RemoveResource(
            RemoveResourceType.RABBIT_QUEUE, "control-queue", ResourcePlane.CONTROL));
        var factory = new RuntimeOwnershipManifestFactory(control(), work);
        assertThatThrownBy(() -> factory.resources("swarm", "controller", topology))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Expected WORK manifest resource");

        when(work.removalTarget(resource)).thenThrow(new IllegalArgumentException("Foreign Work resource"));
        assertThatThrownBy(() -> factory.resources("swarm", "controller", topology))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Foreign Work resource");
    }

    private static ControlPlaneProperties control() {
        var properties = new ControlPlaneProperties();
        properties.setControlQueuePrefix("ph.control");
        return properties;
    }
}

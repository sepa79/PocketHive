package io.pockethive.artemis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.pockethive.artemis.api.ArtemisWorkPlane;
import io.pockethive.artemis.topology.ArtemisResourceNames;
import io.pockethive.artemis.transport.ArtemisManagement;
import io.pockethive.artemis.transport.ArtemisSessions;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtemisDebugTapCleanupTest {
    @TempDir Path directory;

    @Test void closeRestoresAddressSettingsAndRemovesOwnedResources() throws Exception {
        try (var broker = new EmbeddedArtemis(directory);
             var plane = new ArtemisWorkPlane(broker.settings(), "ph");
             var connections = new ArtemisSessions(broker.settings());
             var session = connections.open()) {
            var topology = plane.topology().resolve("cleanup", Set.of("jobs"));
            plane.resources().ensure(topology);
            var name = new ArtemisResourceNames("ph").debugTap("cleanup", "processor", "settings");
            var management = new ArtemisManagement(session, 2000);
            var before = management.invoke(ResourceNames.BROKER, "getAddressSettingsAsJSON", name);
            int sessionsBefore = broker.sessionCount();
            var tap = plane.debugTaps().open("cleanup", "processor", "settings", topology.channel("jobs"), 30, 2);
            try {
                assertThat(management.invoke(ResourceNames.BROKER, "getAddressSettingsAsJSON", name)).isNotEqualTo(before);
                assertThat(tap.sourceGroup()).isEqualTo(topology.channel("jobs").outputAddress());
                assertThat(tap.sourceAddress()).isEqualTo(topology.channel("jobs").outputAddress());
            } finally {
                tap.close();
            }
            tap.close();
            assertThatThrownBy(tap::receive).isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
            assertThat(management.invoke(ResourceNames.BROKER, "getAddressSettingsAsJSON", name)).isEqualTo(before);
            assertThat(session.queueQuery(SimpleString.of(name)).isExists()).isFalse();
            assertThat(session.addressQuery(SimpleString.of(name)).isExists()).isFalse();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(broker.sessionCount()).isEqualTo(sessionsBefore));
        }
    }

    @Test void queueCollisionCleansPartialEffectsAndPreservesPreexistingResources() throws Exception {
        try (var broker = new EmbeddedArtemis(directory);
             var plane = new ArtemisWorkPlane(broker.settings(), "ph");
             var connections = new ArtemisSessions(broker.settings());
             var session = connections.open()) {
            var topology = plane.topology().resolve("cleanup", Set.of("jobs"));
            plane.resources().ensure(topology);
            var name = new ArtemisResourceNames("ph").debugTap("cleanup", "processor", "collision");
            session.createAddress(SimpleString.of("fixture-owned"), RoutingType.ANYCAST, false);
            session.createQueue(QueueConfiguration.of(name).setAddress("fixture-owned")
                .setRoutingType(RoutingType.ANYCAST).setDurable(true));
            var management = new ArtemisManagement(session, 2000);
            var before = management.invoke(ResourceNames.BROKER, "getAddressSettingsAsJSON", name);
            int sessionsBefore = broker.sessionCount();

            assertThatThrownBy(() -> plane.debugTaps().open("cleanup", "processor", "collision",
                topology.channel("jobs"), 30, 2)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot open Artemis diagnostic capture");

            assertThat(session.addressQuery(SimpleString.of(name)).isExists()).isFalse();
            assertThat(management.invoke(ResourceNames.BROKER, "getAddressSettingsAsJSON", name)).isEqualTo(before);
            var preserved = session.queueQuery(SimpleString.of(name));
            assertThat(preserved.isExists()).isTrue();
            assertThat(preserved.getAddress().toString()).isEqualTo("fixture-owned");
            assertThat(session.addressQuery(SimpleString.of("fixture-owned")).isExists()).isTrue();
            assertThat(plane.resources().observeInput(topology.channel("jobs").inputAddress())).isPresent();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(broker.sessionCount()).isEqualTo(sessionsBefore));
        }
    }
}

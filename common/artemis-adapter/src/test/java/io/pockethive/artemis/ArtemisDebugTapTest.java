package io.pockethive.artemis;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import io.pockethive.artemis.api.ArtemisWorkPlane;
import io.pockethive.artemis.topology.ArtemisResourceKind;
import io.pockethive.topology.work.WorkResourceIdentity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtemisDebugTapTest {
    @TempDir Path directory;

    @Test void captureRetainsOnlyNewestCopiesAndCloseRemovesItsResources() throws Exception {
        try (var broker = new EmbeddedArtemis(directory);
             var plane = new ArtemisWorkPlane(broker.settings(), "ph")) {
            var topology = plane.topology().resolve("swarm", Set.of("jobs"));
            plane.resources().ensure(topology);
            var source = topology.channel("jobs");
            var capture = plane.debugTaps().open("swarm", "processor", "tap1", source, 30, 2);
            try {
                for (int i = 0; i < 5; i++) broker.sendRaw(source.outputAddress(), ("body-" + i).getBytes(StandardCharsets.UTF_8));
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(plane.resources().observeInput(source.inputAddress()).orElseThrow().messages()).isEqualTo(5));
                var copies = new ArrayList<String>();
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    capture.receive().ifPresent(bytes -> copies.add(new String(bytes, StandardCharsets.UTF_8)));
                    assertThat(copies).containsExactly("body-3", "body-4");
                });
                assertThat(capture.receive()).isEmpty();
                assertThat(plane.resources().observeInput(source.inputAddress()).orElseThrow().messages()).isEqualTo(5);
            } finally { capture.close(); }
            assertThat(plane.resources().observe(new WorkResourceIdentity("ARTEMIS", ArtemisResourceKind.QUEUE.name(),
                capture.captureAddress()))).isEmpty();
            assertThat(plane.resources().observe(new WorkResourceIdentity("ARTEMIS", ArtemisResourceKind.ADDRESS.name(),
                capture.captureAddress()))).isEmpty();
            // Reopening the same ID also verifies that the native divert and settings were removed.
            try (var reopened = plane.debugTaps().open("swarm", "processor", "tap1", source, 30, 2)) {
                broker.sendRaw(source.outputAddress(), "new".getBytes(StandardCharsets.UTF_8));
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(reopened.receive()).isPresent());
            }
        }
    }

    @Test void captureExpiryDiscardsCopiesAndPreservesSourceExpiryPolicy() throws Exception {
        try (var broker = new EmbeddedArtemis(directory);
             var plane = new ArtemisWorkPlane(broker.settings(), "ph")) {
            var topology = plane.topology().resolve("swarm", Set.of("jobs"));
            plane.resources().ensure(topology);
            var source = topology.channel("jobs");
            try (var capture = plane.debugTaps().open("swarm", "processor", "tap2", source, 1, 2)) {
                broker.sendRaw(source.outputAddress(), "keep original".getBytes(StandardCharsets.UTF_8));
                await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(plane.resources().observeInput(capture.captureAddress()).orElseThrow().messages()).isEqualTo(1));
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(plane.resources().observeInput(capture.captureAddress()).orElseThrow().messages()).isZero());
                assertThat(capture.receive()).isEmpty();
                assertThat(plane.resources().observeInput(source.inputAddress()).orElseThrow().messages()).isEqualTo(1);
                assertThat(plane.resources().observeInput(EmbeddedArtemis.EXPIRY_QUEUE).orElseThrow().messages()).isZero();

                // A genuine WORK expiration must still reach the broker's expiry queue while the tap is active.
                broker.sendRaw(source.outputAddress(), "expired work".getBytes(StandardCharsets.UTF_8),
                    System.currentTimeMillis() + 500);
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertThat(plane.resources().observeInput(capture.captureAddress()).orElseThrow().messages()).isZero();
                    assertThat(plane.resources().observeInput(source.inputAddress()).orElseThrow().messages()).isEqualTo(1);
                    assertThat(plane.resources().observeInput(EmbeddedArtemis.EXPIRY_QUEUE).orElseThrow().messages()).isEqualTo(1);
                });
            }
            assertThat(plane.resources().observeInput(EmbeddedArtemis.EXPIRY_QUEUE).orElseThrow().messages()).isEqualTo(1);
        }
    }
}

package io.pockethive.worker.sdk.input.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.pockethive.artemis.api.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ArtemisWorkAdmissionTest {
    private static final AtomicInteger IDS = new AtomicInteger(1000);
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void acksAcceptedWorkBeforeCompletionAndPreservesOnlyWaitingWorkAcrossStop(int limit) throws Exception {
        String url = "vm://" + IDS.incrementAndGet();
        var config = new ConfigurationImpl().setPersistenceEnabled(false).setSecurityEnabled(false)
            .setJMXManagementEnabled(false).setThreadPoolMaxSize(4).setScheduledThreadPoolMaxSize(2)
            .setJournalDirectory(directory.resolve("journal").toString())
            .setBindingsDirectory(directory.resolve("bindings").toString())
            .setPagingDirectory(directory.resolve("paging").toString())
            .setLargeMessagesDirectory(directory.resolve("large").toString())
            .addAcceptorConfiguration("core-test", url)
            .addAddressSetting("#", new AddressSettings().setAutoCreateAddresses(false).setAutoCreateQueues(false));
        var broker = ActiveMQServers.newActiveMQServer(config);
        broker.start();
        try (var plane = new ArtemisWorkPlane(new ArtemisConnectionSettings(url, "user", "password", 2000), "ph")) {
            var topology = plane.topology().resolve("swarm", Set.of("jobs"));
            plane.resources().ensure(topology);
            var route = topology.channel("jobs");
            var channel = plane.inputs().create("worker", new ArtemisInputSettings(route.inputAddress(), 0));
            var output = plane.outputs().create(new ArtemisOutputSettings(route.outputAddress(), true));
            try (var scenario = new WorkAdmissionScenario(channel, limit)) {
                for (int i = 0; i < limit; i++) output.publish(WorkAdmissionScenario.item("accepted-" + i));
                await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                    assertThat(scenario.started.getCount()).isZero();
                    assertThat(plane.resources().observeInput(route.inputAddress()).orElseThrow().messages()).isZero();
                });
                assertThat(scenario.failed.getCount()).isEqualTo(limit);
                output.publish(WorkAdmissionScenario.item("waiting"));
                scenario.pauseWhileCapacityIsFull();
                assertThat(plane.resources().observeInput(route.inputAddress()).orElseThrow().messages()).isEqualTo(1);
                scenario.finishAcceptedAndResume();
                scenario.assertExecutedExactlyOnce();
                await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(plane.resources().observeInput(route.inputAddress()).orElseThrow().messages()).isZero());
            }
        } finally { broker.stop(); }
    }
}

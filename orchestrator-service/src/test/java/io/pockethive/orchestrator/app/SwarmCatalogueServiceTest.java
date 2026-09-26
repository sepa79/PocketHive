package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pockethive.docker.compute.PocketHiveDockerLabels;
import io.pockethive.orchestrator.domain.*;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeInventoryPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeResource;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.lifecycle.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SwarmCatalogueServiceTest {
    private final SwarmStore store = new SwarmStore();
    private final SwarmOperationCoordinator operations = new SwarmOperationCoordinator();
    private final ComputeRuntimeInventoryPort inventory = mock(ComputeRuntimeInventoryPort.class);
    private final HiveJournal journal = mock(HiveJournal.class);
    private final SwarmCatalogueService service = new SwarmCatalogueService(store, operations, inventory, journal);
    private final Swarm swarm = register("run-1");

    private Swarm register(String run) {
        var entry = new Swarm("alpha", "controller-1", "runtime-1", run, NetworkMode.DIRECT);
        entry.attachTemplate(new SwarmTemplateMetadata("template-1", "controller", List.of()));
        return store.register(entry);
    }

    @Test void forgetsOnlyCatalogueAndJournalsIdentity() {
        when(inventory.list()).thenReturn(List.of());
        service.forget(swarm);
        assertThat(store.find("alpha")).isEmpty();
        assertThat(operations.operations()).isEmpty();
        verify(journal).appendDurably(eq("run-1"), argThat(entry -> entry.data().get("runId").equals("run-1")));
    }

    @Test void refusesWorkerFromAnotherRunEvenWhenStopped() {
        when(inventory.list()).thenReturn(List.of(new ComputeRuntimeResource(
            "worker", "container", "worker", "image", "exited",
            Map.of(PocketHiveDockerLabels.SWARM_ID, "alpha", PocketHiveDockerLabels.RUN_ID, "older-run"))));
        assertThatThrownBy(() -> service.forget(swarm)).isInstanceOf(IllegalStateException.class);
        assertThat(store.find("alpha")).contains(swarm);
    }

    @Test void registeredControllerIdBlocksEvenWithoutLabels() {
        when(inventory.list()).thenReturn(List.of(new ComputeRuntimeResource(
            "runtime-1", "container", "controller", "image", "running", Map.of())));
        assertThatThrownBy(() -> service.forget(swarm)).isInstanceOf(IllegalStateException.class);
    }

    @Test void unavailableInventoryNeverDeletes() {
        when(inventory.list()).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> service.forget(swarm)).isInstanceOf(CatalogueInventoryUnavailableException.class);
        assertThat(store.find("alpha")).contains(swarm);
        verifyNoInteractions(journal);
    }

    @Test void replacementDuringInventoryIsPreserved() {
        doAnswer(call -> { register("run-2"); return List.of(); }).when(inventory).list();
        assertThatThrownBy(() -> service.forget(swarm)).isInstanceOf(IllegalStateException.class);
        assertThat(store.find("alpha").orElseThrow().getRunId()).isEqualTo("run-2");
    }

    @Test void lifecycleAdmittedDuringInventoryBlocksDeletion() {
        when(inventory.list()).thenAnswer(call -> {
            operations.reserve("alpha", OperationType.START, new Target("swarm-controller", "controller-1"),
                swarm.runtimeMetadata(), "correlation", "key", Instant.now(), Instant.now().plusSeconds(30));
            return List.of();
        });
        assertThatThrownBy(() -> service.forget(swarm)).isInstanceOf(IllegalStateException.class);
        assertThat(store.find("alpha")).contains(swarm);
    }

    @Test void operationCannotDispatchWithMetadataCapturedBeforeForget() {
        var metadata = swarm.runtimeMetadata();
        when(inventory.list()).thenReturn(List.of());
        service.forget(swarm);
        var dispatcher = new OperationDispatchService(operations, mock(OperationOutcomePublisher.class), store);
        assertThatThrownBy(() -> dispatcher.dispatch("alpha", OperationType.START,
            new Target("swarm-controller", "controller-1"), "correlation", "key", Duration.ofSeconds(30),
            metadata, ignored -> fail("Must not execute"))).isInstanceOf(IllegalStateException.class);
        assertThat(operations.operations()).isEmpty();
    }

    @Test void httpUsesCanonicalPermissionAndReturnsNoContent() throws Exception {
        var access = mock(SwarmAccessService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new SwarmCatalogueController(store, access, service)).build();
        mvc.perform(delete("/api/swarms/alpha/catalogue-entry")).andExpect(status().isForbidden());
        verifyNoInteractions(inventory);
        when(access.canManage(null, swarm)).thenReturn(true);
        when(inventory.list()).thenReturn(List.of());
        mvc.perform(delete("/api/swarms/alpha/catalogue-entry")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/swarms/alpha/catalogue-entry")).andExpect(status().isNotFound());
    }

    @Test void httpDistinguishesInventoryFailureAndConflict() throws Exception {
        var access = mock(SwarmAccessService.class);
        when(access.canManage(null, swarm)).thenReturn(true);
        var mvc = MockMvcBuilders.standaloneSetup(new SwarmCatalogueController(store, access, service)).build();
        when(inventory.list()).thenThrow(new IllegalStateException("offline"));
        mvc.perform(delete("/api/swarms/alpha/catalogue-entry")).andExpect(status().isServiceUnavailable());
        doAnswer(call -> { register("run-2"); return List.of(); }).when(inventory).list();
        mvc.perform(delete("/api/swarms/alpha/catalogue-entry")).andExpect(status().isConflict());
    }

    @Test void journalFailureDoesNotMisreportCompletedDeletion() {
        when(inventory.list()).thenReturn(List.of());
        doThrow(new IllegalStateException("journal offline")).when(journal).appendDurably(eq("run-1"), any());
        assertThatCode(() -> service.forget(swarm)).doesNotThrowAnyException();
        assertThat(store.find("alpha")).isEmpty();
    }

    @Test void blockedInventoryDoesNotBlockCommandAdmission() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(inventory.list()).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Inventory was not released");
            return List.of();
        });
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var forget = executor.submit(() -> service.forget(swarm));
            try {
                assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var dispatcher = new OperationDispatchService(operations, mock(OperationOutcomePublisher.class), store);
                var command = executor.submit(() -> dispatcher.dispatch("alpha", OperationType.STOP,
                    new Target("swarm-controller", "controller-1"), "key", Duration.ofSeconds(30), ignored -> {}));
                assertThat(command.get(5, java.util.concurrent.TimeUnit.SECONDS).operation().state())
                    .isEqualTo(OperationState.DISPATCHED);
            } finally {
                release.countDown();
            }
            assertThatThrownBy(() -> forget.get(5, java.util.concurrent.TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(store.find("alpha")).contains(swarm);
        }
    }
}

package io.pockethive.orchestrator.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.pockethive.control.AlertMessage;
import io.pockethive.control.ControlPlaneEnvelopeVersion;
import io.pockethive.control.ControlScope;
import io.pockethive.controlplane.messaging.Alerts;
import io.pockethive.orchestrator.config.RuntimeLogSnapshotMode;
import io.pockethive.orchestrator.config.RuntimeLogSnapshotProperties;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.HiveJournal.HiveJournalEntry;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeLogsRequest;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeLogsResponse;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeTarget;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RuntimeLogSnapshotJournalServiceTest {

    private static final String SWARM_ID = "swarm-1";
    private static final Instant ALERT_TIME = Instant.parse("2026-07-07T10:00:00Z");

    @Mock
    private RuntimeDebugService runtimeDebug;

    @Captor
    private ArgumentCaptor<RuntimeLogsRequest> requestCaptor;

    @Test
    void capturesBoundedWorkerLogsIntoJournal() {
        ListJournal journal = new ListJournal();
        RuntimeLogSnapshotJournalService service = service(journal);
        when(runtimeDebug.logs(any())).thenReturn(new RuntimeLogsResponse(
            target("worker-1", "worker", "processor", "processor-1"),
            50,
            "2026-07-07T09:58:00Z",
            true,
            3,
            "first\nsecond\nthird"));

        service.captureForAlert("event.alert.alert.swarm-1.processor.processor-1", alert("processor", "processor-1"));

        verify(runtimeDebug).logs(requestCaptor.capture());
        RuntimeLogsRequest request = requestCaptor.getValue();
        assertThat(request.swarmId()).isEqualTo(SWARM_ID);
        assertThat(request.runId()).isEqualTo("run-1");
        assertThat(request.role()).isEqualTo("processor");
        assertThat(request.instance()).isEqualTo("processor-1");
        assertThat(request.resourceKind()).isEqualTo("worker");
        assertThat(request.tailLines()).isEqualTo(50);
        assertThat(request.since()).isEqualTo("2026-07-07T09:58:00Z");

        assertThat(journal.entries).hasSize(1);
        HiveJournalEntry entry = journal.entries.getFirst();
        assertThat(entry.kind()).isEqualTo(RuntimeLogSnapshotJournalService.JOURNAL_KIND);
        assertThat(entry.type()).isEqualTo(RuntimeLogSnapshotJournalService.JOURNAL_TYPE_CAPTURED);
        assertThat(entry.direction()).isEqualTo(HiveJournal.Direction.LOCAL);
        assertThat(entry.data()).containsEntry("status", "captured");
        assertThat(entry.data()).containsEntry("targetRuntimeId", "worker-1");
        assertThat(entry.data()).containsEntry("truncated", true);
        assertThat(entry.extra()).containsEntry("logs", "second\nthird");
    }

    @Test
    void mapsSwarmControllerAlertsToManagerRuntimeLogs() {
        ListJournal journal = new ListJournal();
        RuntimeLogSnapshotJournalService service = service(journal);
        when(runtimeDebug.logs(any())).thenReturn(new RuntimeLogsResponse(
            target("manager-1", "manager", "swarm-controller", "controller-1"),
            50,
            "2026-07-07T09:58:00Z",
            true,
            1,
            "manager failure"));

        service.captureForAlert("event.alert.alert.swarm-1.swarm-controller.controller-1",
            alert("swarm-controller", "controller-1"));

        verify(runtimeDebug).logs(requestCaptor.capture());
        assertThat(requestCaptor.getValue().resourceKind()).isEqualTo("manager");
    }

    @Test
    void writesUnavailableEntryWhenRuntimeLogsCannotBeRead() {
        ListJournal journal = new ListJournal();
        RuntimeLogSnapshotJournalService service = service(journal);
        when(runtimeDebug.logs(any())).thenThrow(new RuntimeException("no matching runtime"));

        service.captureForAlert("event.alert.alert.swarm-1.processor.processor-1", alert("processor", "processor-1"));

        assertThat(journal.entries).hasSize(1);
        HiveJournalEntry entry = journal.entries.getFirst();
        assertThat(entry.type()).isEqualTo(RuntimeLogSnapshotJournalService.JOURNAL_TYPE_UNAVAILABLE);
        assertThat(entry.data()).containsEntry("status", "unavailable");
        assertThat(entry.data().get("reason")).asString().contains("no matching runtime");
    }

    @Test
    void skipsNonErrorAlerts() {
        ListJournal journal = new ListJournal();
        RuntimeLogSnapshotJournalService service = service(journal);

        service.captureForAlert("event.alert.alert.swarm-1.processor.processor-1", warningAlert());

        verify(runtimeDebug, never()).logs(any());
        assertThat(journal.entries).isEmpty();
    }

    private RuntimeLogSnapshotJournalService service(ListJournal journal) {
        return new RuntimeLogSnapshotJournalService(
            runtimeDebug,
            journal,
            new RuntimeLogSnapshotProperties(
                RuntimeLogSnapshotMode.ERROR_ALERTS,
                50,
                Duration.ofMinutes(2),
                12));
    }

    private static AlertMessage alert(String role, String instance) {
        return Alerts.error(
            instance,
            ControlScope.forInstance(SWARM_ID, role, instance),
            "corr-1",
            "idem-1",
            Map.of("runId", "run-1"),
            Alerts.Codes.RUNTIME_EXCEPTION,
            "boom",
            "java.lang.IllegalStateException",
            "boom",
            null,
            Map.of("phase", "work"),
            ALERT_TIME);
    }

    private static AlertMessage warningAlert() {
        return new AlertMessage(
            ALERT_TIME,
            ControlPlaneEnvelopeVersion.CURRENT,
            "event",
            Alerts.TYPE,
            "processor-1",
            ControlScope.forInstance(SWARM_ID, "processor", "processor-1"),
            "corr-1",
            "idem-1",
            Map.of("templateId", "template-1", "runId", "run-1"),
            new AlertMessage.AlertData(
                "warn",
                "runtime.warning",
                "warning",
                null,
                null,
                null,
                Map.of("phase", "work")));
    }

    private static RuntimeTarget target(String runtimeId, String resourceKind, String role, String instance) {
        return new RuntimeTarget(
            runtimeId,
            "container",
            runtimeId,
            resourceKind,
            SWARM_ID,
            "run-1",
            role,
            instance,
            instance,
            "running",
            role + ":0.15.33",
            Map.of());
    }

    private static final class ListJournal implements HiveJournal {
        private final List<HiveJournalEntry> entries = new ArrayList<>();

        @Override
        public void append(HiveJournalEntry entry) {
            entries.add(entry);
        }

        @Override
        public void appendDurably(String runId, HiveJournalEntry entry) {
            entries.add(entry);
        }
    }
}

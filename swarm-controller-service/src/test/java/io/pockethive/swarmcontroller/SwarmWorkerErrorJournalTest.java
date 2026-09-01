package io.pockethive.swarmcontroller;

import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TEST_SWARM_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.StatusMetric;
import io.pockethive.swarmcontroller.runtime.SwarmJournal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SwarmWorkerErrorJournalTest {

  @Mock
  private SwarmJournal journal;

  private final ObjectMapper mapper = new ObjectMapper();
  private SwarmWorkerErrorJournal workerErrors;

  @BeforeEach
  void setUp() {
    workerErrors = new SwarmWorkerErrorJournal(
        journal, SwarmControllerTestProperties.defaults(), "controller-1");
  }

  @Test
  void journalsAnIncreaseWithAvailableWorkerContext() throws Exception {
    workerErrors.observe("processor", "processor-1", status(4, 2.5, "payments", "/templates/http"));

    ArgumentCaptor<SwarmJournal.SwarmJournalEntry> entry =
        ArgumentCaptor.forClass(SwarmJournal.SwarmJournalEntry.class);
    verify(journal).append(entry.capture());
    assertThat(entry.getValue().swarmId()).isEqualTo(TEST_SWARM_ID);
    assertThat(entry.getValue().severity()).isEqualTo("ERROR");
    assertThat(entry.getValue().type()).isEqualTo("worker-error");
    assertThat(entry.getValue().origin()).isEqualTo("controller-1");
    assertThat(entry.getValue().scope().role()).isEqualTo("processor");
    assertThat(entry.getValue().scope().instance()).isEqualTo("processor-1");
    assertThat(entry.getValue().data()).containsEntry("errorCount", 4L);
    assertThat(entry.getValue().data()).containsEntry("errorDelta", 4L);
    assertThat(entry.getValue().data()).containsEntry("errorTps", 2.5);
    assertThat(entry.getValue().data()).containsEntry("serviceId", "payments");
    assertThat(entry.getValue().data()).containsEntry("templateRoot", "/templates/http");
    assertThat(entry.getValue().extra()).containsEntry("source", StatusMetric.STATUS_DELTA);
  }

  @Test
  void doesNotRepeatAnUnchangedCounter() throws Exception {
    JsonNode observation = status(3, null, null, null);

    workerErrors.observe("generator", "generator-1", observation);
    workerErrors.observe("generator", "generator-1", observation);

    verify(journal, times(1)).append(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void usesADecreasedCounterAsTheNewDeltaBaseline() throws Exception {
    workerErrors.observe("generator", "generator-1", status(5, null, null, null));
    workerErrors.observe("generator", "generator-1", status(2, null, null, null));
    workerErrors.observe("generator", "generator-1", status(4, null, null, null));

    ArgumentCaptor<SwarmJournal.SwarmJournalEntry> entries =
        ArgumentCaptor.forClass(SwarmJournal.SwarmJournalEntry.class);
    verify(journal, times(2)).append(entries.capture());
    assertThat(entries.getAllValues().get(1).data()).containsEntry("errorCount", 4L);
    assertThat(entries.getAllValues().get(1).data()).containsEntry("errorDelta", 2L);
  }

  @Test
  void ignoresMissingAndZeroErrorCounters() throws Exception {
    workerErrors.observe("generator", "generator-1", mapper.readTree("{\"data\":{\"context\":{}}}"));
    workerErrors.observe("generator", "generator-1", status(0, null, null, null));

    verify(journal, never()).append(org.mockito.ArgumentMatchers.any());
  }

  private JsonNode status(
      long errorCount, Double errorTps, String serviceId, String templateRoot) throws Exception {
    var context = mapper.createObjectNode().put("errorCount", errorCount);
    if (errorTps != null) {
      context.put("errorTps", errorTps);
    }
    if (serviceId != null) {
      context.put("serviceId", serviceId);
    }
    if (templateRoot != null) {
      context.put("templateRoot", templateRoot);
    }
    return mapper.createObjectNode().set("data", mapper.createObjectNode().set("context", context));
  }
}

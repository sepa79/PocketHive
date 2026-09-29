package io.pockethive.orchestrator.app;

import io.pockethive.journal.api.JournalRunMetadata;
import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
import org.springframework.stereotype.Service;

/**
 * Responsibility: project swarm-start template identity into the journal metadata port.
 * Must not: write SQL or own metadata normalization.
 * Contract: RESP-JOURNAL-WRITES — docs/architecture/runtime-responsibilities.md#resp-journal-writes.
 */
@Service
public class JournalRunRegistration {
  private final JournalRunMetadata metadata;

  public JournalRunRegistration(JournalRunMetadata metadata) {
    this.metadata = metadata;
  }

  public void upsertOnSwarmStart(String swarmId, String runId, SwarmTemplateMetadata templateMetadata) {
    metadata.register(swarmId, runId, templateMetadata == null ? null : templateMetadata.templateId());
  }
}

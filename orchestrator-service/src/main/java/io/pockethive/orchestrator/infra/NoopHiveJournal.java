package io.pockethive.orchestrator.infra;

import io.pockethive.orchestrator.domain.HiveJournal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(HiveJournal.class)
public class NoopHiveJournal implements HiveJournal {
  @Override
  public void append(HiveJournalEntry entry) {
    // intentionally no-op
  }
}


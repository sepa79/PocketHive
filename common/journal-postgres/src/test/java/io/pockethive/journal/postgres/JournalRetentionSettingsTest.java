package io.pockethive.journal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import io.pockethive.journal.api.JournalRetentionSettings;
import org.junit.jupiter.api.Test;

class JournalRetentionSettingsTest {
    @Test
    void preservesExistingMinimumBounds() {
        assertThat(new JournalRetentionSettings(-1, -2, -3, 0, -4))
            .isEqualTo(new JournalRetentionSettings(1, 0, 0, 1, 1));
        assertThat(new JournalRetentionSettings(14, 1, 2, 5000, 14).defaultMoveBatchSize()).isEqualTo(5000);
    }
}

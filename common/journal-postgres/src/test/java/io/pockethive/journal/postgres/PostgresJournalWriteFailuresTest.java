package io.pockethive.journal.postgres;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.journal.api.JournalRetentionSettings;
import io.pockethive.journal.api.JournalRunQueries;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PostgresJournalWriteFailuresTest {
    @Test
    void startupMetadataRemainsBestEffortWhenStorageFails() {
        var jdbc = mock(JdbcTemplate.class);
        doThrow(new IllegalStateException("offline")).when(jdbc).update(anyString(), any(), any(), any());
        var metadata = new PostgresJournalRunMetadata(jdbc, new ObjectMapper(), mock(JournalRunQueries.class));
        assertThatCode(() -> metadata.register("alpha", "run", "scenario")).doesNotThrowAnyException();
        verify(jdbc).update(anyString(), eq("alpha"), eq("run"), eq("scenario"));
    }

    @Test
    void failedPartitionCreationStopsReconcileBeforeRetentionDeletes() {
        var jdbc = mock(JdbcTemplate.class);
        doThrow(new IllegalStateException("offline")).when(jdbc).execute(anyString());
        var retention = new PostgresJournalRetention(jdbc, new JournalRetentionSettings(14, 1, 2, 5000, 14));
        assertThatCode(retention::reconcile).doesNotThrowAnyException();
        verify(jdbc).execute(anyString());
        verifyNoMoreInteractions(jdbc);
    }
}

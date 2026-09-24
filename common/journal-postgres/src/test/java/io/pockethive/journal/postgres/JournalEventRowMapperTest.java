package io.pockethive.journal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JournalEventRowMapperTest {
    private final JournalEventRowMapper mapper = new JournalEventRowMapper(new ObjectMapper());

    @Test
    void retainsEnvelopeIdentityNullFieldsAndNestedPayloads() throws SQLException {
        ResultSet rs = row();
        Instant ts = Instant.parse("2026-09-23T10:00:00Z");
        when(rs.getTimestamp("ts")).thenReturn(Timestamp.from(ts));
        when(rs.getString("data")).thenReturn("{\"nested\":{\"ok\":true}}");
        when(rs.getString("raw")).thenReturn("{\"wire\":\"original\"}");
        var result = mapper.map(rs, true);
        assertThat(result.id()).isEqualTo(42);
        assertThat(result.timestamp()).isEqualTo(ts);
        assertThat(result.entry()).containsEntry("eventId", 42L).containsEntry("timestamp", ts)
            .containsEntry("scope", new ControlScope("alpha", "processor", "worker-1"))
            .containsEntry("correlationId", "corr-1").containsEntry("idempotencyKey", "idem-1")
            .containsEntry("data", Map.of("nested", Map.of("ok", true)))
            .containsEntry("raw", Map.of("wire", "original")).containsEntry("extra", null);
        assertThatThrownBy(() -> result.entry().put("other", true)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void keepsDistinctPageAndTimelineProjectionWithoutIntroducingFields() throws SQLException {
        ResultSet rs = row();
        var page = mapper.map(rs, true).entry();
        var timeline = mapper.map(rs, false).entry();
        assertThat(page).containsEntry("timestamp", Instant.EPOCH).containsEntry("eventId", 42L);
        assertThat(timeline).containsEntry("timestamp", null).doesNotContainKey("eventId");
        assertThat(timeline).hasSize(15);
    }

    @Test
    void retainsExistingNullProjectionForNonMapAndMalformedJson() throws SQLException {
        ResultSet rs = row();
        for (String json : new String[] {"", " ", "null", "[]", "42", "broken"}) {
            when(rs.getString("data")).thenReturn(json);
            assertThat(mapper.map(rs, false).entry()).containsEntry("data", null);
        }
    }

    private ResultSet row() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(42L);
        when(rs.getString("swarm_id")).thenReturn("alpha");
        when(rs.getString("run_id")).thenReturn("run-1");
        when(rs.getString("scope_role")).thenReturn("processor");
        when(rs.getString("scope_instance")).thenReturn("worker-1");
        when(rs.getString("correlation_id")).thenReturn("corr-1");
        when(rs.getString("idempotency_key")).thenReturn("idem-1");
        return rs;
    }
}

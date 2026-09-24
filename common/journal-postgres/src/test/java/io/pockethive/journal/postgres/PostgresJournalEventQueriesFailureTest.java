package io.pockethive.journal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class PostgresJournalEventQueriesFailureTest {
    @Test
    @SuppressWarnings("unchecked")
    void lookupFailuresKeepBestEffortAbsenceButEventReadFailuresPropagate() {
        var jdbc = mock(JdbcTemplate.class);
        var failure = new DataAccessResourceFailureException("unavailable");
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(ResultSetExtractor.class))).thenThrow(failure);
        when(jdbc.query(anyString(), any(Object[].class), any(RowMapper.class))).thenThrow(failure);
        var queries = new PostgresJournalEventQueries(jdbc, new ObjectMapper());
        assertThat(queries.latestSwarmRun("alpha")).isNull();
        assertThat(queries.pinnedSwarmCapture("alpha", "run-1")).isNull();
        assertThatThrownBy(() -> queries.swarmTimeline("alpha", "run-1", null)).isSameAs(failure);
        assertThatThrownBy(() -> queries.hivePage(null, null, null, null, null, 10)).isSameAs(failure);
    }
}

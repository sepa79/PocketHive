package io.pockethive.journal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;

class JournalRunRowMapperTest {
    private final JournalRunRowMapper mapper = new JournalRunRowMapper(new ObjectMapper());

    @Test
    void decodesPersistedTagsWithoutApplyingWriteLimits() throws SQLException {
        var rs = mock(ResultSet.class);
        String longTag = "x".repeat(70);
        when(rs.getString("tags")).thenReturn("[\" a \",42,null,\"\",\"a\",\"" + longTag + "\"]");
        assertThat(mapper.full(rs, true).tags()).isEqualTo(List.of("a", longTag));
        assertThat(mapper.forSwarm(rs, "alpha", true).tags()).isNull();
    }

    @Test
    void preservesAbsentAndMalformedTagsAsNull() throws SQLException {
        var rs = mock(ResultSet.class);
        for (String value : new String[] {"", " ", "null", "{}", "[]", "[1,null]", "broken"}) {
            when(rs.getString("tags")).thenReturn(value);
            assertThat(mapper.full(rs, false).tags()).isNull();
        }
    }
}

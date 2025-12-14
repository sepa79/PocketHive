package io.pockethive.orchestrator.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.orchestrator.domain.HiveJournal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "pockethive.journal.sink", havingValue = "postgres")
public class PostgresHiveJournal implements HiveJournal {

  private static final Logger log = LoggerFactory.getLogger(PostgresHiveJournal.class);

  private final ObjectMapper mapper;
  private final JdbcTemplate jdbc;

  public PostgresHiveJournal(ObjectMapper mapper, JdbcTemplate jdbc) {
    this.mapper = Objects.requireNonNull(mapper, "mapper").findAndRegisterModules();
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public void append(HiveJournalEntry entry) {
    Objects.requireNonNull(entry, "entry");
    String sql = """
        INSERT INTO journal_event (
          ts,
          scope,
          swarm_id,
          scope_role,
          scope_instance,
          severity,
          direction,
          kind,
          type,
          origin,
          correlation_id,
          idempotency_key,
          routing_key,
          data,
          raw,
          extra
        ) VALUES (
          ?,
          'HIVE',
          ?,
          ?,
          ?,
          ?,
          ?,
          ?,
          ?,
          ?,
          ?,
          ?,
          ?,
          ?::jsonb,
          ?::jsonb,
          ?::jsonb
        )
        """;
    try {
      jdbc.update(con -> {
        PreparedStatement ps = con.prepareStatement(sql);
        Instant ts = entry.timestamp() != null ? entry.timestamp() : Instant.now();
        ps.setTimestamp(1, Timestamp.from(ts));
        ps.setString(2, entry.swarmId());
        ControlScope scope = entry.scope();
        ps.setString(3, scope != null ? scope.role() : null);
        ps.setString(4, scope != null ? scope.instance() : null);
        ps.setString(5, entry.severity());
        ps.setString(6, entry.direction() != null ? entry.direction().name() : null);
        ps.setString(7, entry.kind());
        ps.setString(8, entry.type());
        ps.setString(9, entry.origin());
        ps.setString(10, entry.correlationId());
        ps.setString(11, entry.idempotencyKey());
        ps.setString(12, entry.routingKey());
        ps.setString(13, toJson(entry.data()));
        ps.setString(14, toJson(entry.raw()));
        ps.setString(15, toJson(entry.extra()));
        return ps;
      });
    } catch (Exception e) {
      log.warn("Hive journal insert failed: {}", e.getMessage());
    }
  }

  private String toJson(Map<String, Object> value) {
    if (value == null) {
      return null;
    }
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      return null;
    }
  }
}


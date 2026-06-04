package io.pockethive.dbquery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NamedSqlParserTest {

  private final NamedSqlParser parser = new NamedSqlParser();

  @Test
  void parsesNamedParamsWithoutTouchingCastsStringsOrComments() {
    NamedSql sql = parser.parse("""
        select value::text, ':ignored' as literal
        from probe
        where id = :probeId
          and status = :status
        -- and skipped = :commented
        """);

    assertThat(sql.jdbcSql()).contains("value::text").contains("':ignored'");
    assertThat(sql.jdbcSql()).contains("id = ?").contains("status = ?");
    assertThat(sql.parameterNames()).containsExactly("probeId", "status");
  }
}

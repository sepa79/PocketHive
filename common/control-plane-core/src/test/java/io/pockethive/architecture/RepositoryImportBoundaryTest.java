package io.pockethive.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Responsibility: Check explicit forbidden imports across all repository Maven production sources.
 * Must not: Parse Java, infer effects/ownership or grow a second scanner or policy framework.
 * Contract: docs/REVIEW_RULES.md, sole source-scanning exception. This table owns import rules.
 * Matches import text only; fully qualified usages and wildcard contents are not resolved.
 */
class RepositoryImportBoundaryTest {

  private static final Pattern IMPORT = Pattern.compile(
      "(?m)^\\h*import\\s+(?:static\\s+)?([\\w.$]+(?:\\.\\*)?)\\s*;");
  private static final Pattern SOURCE = Pattern.compile("(.+)/src/main/java/.+\\.java");

  // Current owners, not the final migration layout. Narrow these module scopes with each slice.
  private static final List<Rule> RULES = List.of(
      rule("core-no-infrastructure",
          "common/(work-api|work-config|rabbit-config|request-templates|templating-api|observability-core|auth-contracts|control-plane-core"
              + "|topology-core|swarm-model|scenario-validation-contracts)",
          "(org\\.springframework|io\\.lettuce|redis\\.clients|com\\.rabbitmq|com\\.clickhouse"
              + "|com\\.github\\.dockerjava|java\\.sql|javax\\.sql)\\..*"),
      rule("control-core-no-work", "common/control-plane-core",
          "io\\.pockethive\\.(work|worker)\\..*"),
      rule("rabbit-spring-config-owner", outside("common/control-plane-spring"),
          "org\\.springframework\\.boot\\.autoconfigure\\.amqp\\..*"),
      rule("redis-client-owner", outside("common/(worker-sdk|templating)|e2e-tests"),
          "(io\\.lettuce|redis\\.clients)\\..*"),
      rule("rabbit-client-owner",
          outside("common/(worker-sdk|control-plane-spring)|orchestrator-service"
              + "|swarm-controller-service|generator-service|e2e-tests"),
          "com\\.rabbitmq\\..*|org\\.springframework\\.amqp\\.(?!rabbit\\.annotation\\.EnableRabbit$).*"),
      rule("docker-client-owner",
          outside("common/docker-client|orchestrator-service|swarm-controller-service|e2e-tests"),
          "com\\.github\\.dockerjava\\..*"),
      rule("clickhouse-client-owner", outside("common/sink-clickhouse|e2e-tests"),
          "(com\\.clickhouse|ru\\.yandex\\.clickhouse)\\..*"),
      rule("jdbc-owner", outside("common/journal-postgres|db-query-service|e2e-tests"),
          "(java|javax)\\.sql\\..*"),
      rule("production-no-test-imports", outside("common/work-test-fixtures|e2e-tests"),
          "(org\\.junit|org\\.mockito|org\\.assertj|com\\.tngtech\\.archunit"
              + "|io\\.pockethive\\.worker\\.sdk\\.testing)\\..*")
  );

  @Test
  void allModulesRespectImportBoundaries() throws IOException {
    String repositoryRoot = System.getProperty("pockethive.repositoryRoot");
    assertThat(repositoryRoot).as("repository root supplied by Maven Surefire").isNotBlank();
    Path root = Path.of(repositoryRoot);
    assertThat(root.resolve("common/control-plane-core/pom.xml")).isRegularFile();
    List<String> violations = new ArrayList<>();
    int scanned = 0;
    try (var files = Files.walk(root)) {
      for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        var source = SOURCE.matcher(relative);
        if (!source.matches() || !Files.isRegularFile(root.resolve(source.group(1)).resolve("pom.xml"))) {
          continue;
        }
        scanned++;
        for (String violation : violations(source.group(1), Files.readString(file))) {
          violations.add(relative + ":" + violation);
        }
      }
    }
    assertThat(scanned).as("Java production files scanned").isPositive();
    assertThat(violations).as("forbidden imports across %s Java production files", scanned).isEmpty();
  }

  @Test
  void importRulesRejectViolationsAndAllowTheirOwners() {
    assertThat(violations("trigger-service", "import io.lettuce.core.RedisClient;"))
        .containsExactly("1 [redis-client-owner] io.lettuce.core.RedisClient");
    assertThat(violations("common/worker-sdk", "import io.lettuce.core.RedisClient;")).isEmpty();
    assertThat(violations("common/work-api", "\nimport static org.springframework.util.Assert.*;"))
        .containsExactly("2 [core-no-infrastructure] org.springframework.util.Assert.*");
    assertThat(violations("processor-service", "import com.rabbitmq.client.*;"))
        .containsExactly("1 [rabbit-client-owner] com.rabbitmq.client.*");
    assertThat(violations("processor-service",
        "import org.springframework.amqp.rabbit.annotation.EnableRabbit;")).isEmpty();
    assertThat(violations("new-service", "import org.junit.jupiter.api.Test;"))
        .containsExactly("1 [production-no-test-imports] org.junit.jupiter.api.Test");
  }

  private static List<String> violations(String module, String source) {
    List<String> violations = new ArrayList<>();
    var imports = IMPORT.matcher(source);
    while (imports.find()) {
      String imported = imports.group(1);
      for (Rule rule : RULES) {
        if (rule.modules().matcher(module).matches() && rule.imports().matcher(imported).matches()) {
          long line = source.substring(0, imports.start()).chars().filter(c -> c == '\n').count() + 1;
          violations.add(line + " [" + rule.id() + "] " + imported);
        }
      }
    }
    return violations;
  }

  private static Rule rule(String id, String modules, String imports) {
    return new Rule(id, Pattern.compile(modules), Pattern.compile(imports));
  }

  private static String outside(String modules) {
    return "(?!(?:" + modules + ")$).+";
  }

  private record Rule(String id, Pattern modules, Pattern imports) {}
}

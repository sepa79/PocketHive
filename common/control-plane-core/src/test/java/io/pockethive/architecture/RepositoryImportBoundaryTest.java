package io.pockethive.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Responsibility: Check forbidden imports in production source trees declared by the repository Maven POMs.
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
      rule("acceptance-no-legacy-or-service-implementation", "acceptance-tests",
          "io\\.pockethive\\.(e2e|orchestrator|scenarios|rabbit|artemis)\\..*"),
      rule("core-no-infrastructure",
          "common/(work-api|work-config|request-templates|templating-api|observability-core|auth-contracts|control-plane-core"
              + "|topology-core|swarm-model|scenario-validation-contracts)",
          "(org\\.springframework|io\\.lettuce|redis\\.clients|com\\.rabbitmq|com\\.clickhouse"
              + "|com\\.github\\.dockerjava|java\\.sql|javax\\.sql)\\..*"),
      rule("scenario-no-adapter-settings", "scenario-manager-service",
          "io\\.pockethive\\.(rabbit\\.config|redis\\.config|work\\.local)\\..*"),
      rule("rabbit-resource-client-owner", outside("common/rabbit-adapter|e2e-tests"),
          "org\\.springframework\\.amqp\\.(core\\.(AmqpAdmin|Queue|QueueBuilder|ExchangeBuilder|TopicExchange|Binding|BindingBuilder|Declarables)|rabbit\\.core\\.RabbitAdmin)"),
      rule("rabbit-template-owner", outside("common/rabbit-adapter|e2e-tests"),
          "org\\.springframework\\.amqp\\.(core\\.AmqpTemplate|rabbit\\.core\\.RabbitTemplate)"),
      rule("rabbit-internals-owner", outside("common/rabbit-adapter"),
          "io\\.pockethive\\.rabbit\\.(topology|transport|config)\\..*"),
      rule("rabbit-no-worker-runtime", "common/rabbit-adapter",
          "io\\.pockethive\\.worker\\.sdk\\..*"),
      rule("worker-sdk-no-rabbit-work", "common/worker-sdk",
          "io\\.pockethive\\.rabbit\\.work\\..*"),
      rule("neutral-core-no-rabbit-implementation", "common/(control-plane-core|topology-core|work-api|work-config)",
          "io\\.pockethive\\.rabbit\\..*"),
      rule("rabbit-work-projection-owner", outside("common/rabbit-adapter|orchestrator-service|swarm-controller-service"),
          "io\\.pockethive\\.rabbit\\.api\\.(RabbitWorkAddress|RabbitWorkTopologySettings)"),
      rule("rabbit-debug-spec-owner", outside("common/rabbit-adapter"),
          "io\\.pockethive\\.rabbit\\.api\\.RabbitDebugTapSpec"),
      rule("controller-no-rabbit-work-field-mapping", "swarm-controller-service",
          "io\\.pockethive\\.rabbit\\.api\\.(RabbitWorkEnvironment|RabbitWorkSettingsBootstrap|RabbitQueueSpec|RabbitBindingSpec|RabbitExchangeSpec)"),
      rule("control-core-no-work", "common/control-plane-core",
          "io\\.pockethive\\.(work|worker)\\..*"),
      rule("rabbit-spring-config-owner", outside("common/rabbit-adapter"),
          "org\\.springframework\\.boot\\.autoconfigure\\.amqp\\..*"),
      rule("redis-client-owner", outside("common/(worker-sdk|templating)|e2e-tests"),
          "(io\\.lettuce|redis\\.clients)\\..*"),
      rule("artemis-client-owner", outside("common/artemis-adapter"),
          "org\\.apache\\.activemq\\.artemis\\..*"),
      rule("artemis-internals-owner", outside("common/artemis-adapter"),
          "io\\.pockethive\\.artemis\\.(config|topology|transport|work)\\..*"),
      rule("artemis-no-worker-runtime", "common/artemis-adapter",
          "io\\.pockethive\\.worker\\.sdk\\..*"),
      rule("neutral-core-no-artemis", "common/(control-plane-core|topology-core|work-api|work-config)",
          "io\\.pockethive\\.artemis\\..*"),
      rule("rabbit-client-owner", outside("common/rabbit-adapter|e2e-tests"),
          "com\\.rabbitmq\\..*|org\\.springframework\\.amqp\\..*"),
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
  void allModulesRespectImportBoundaries() throws Exception {
    String repositoryRoot = System.getProperty("pockethive.repositoryRoot");
    assertThat(repositoryRoot).as("repository root supplied by Maven Surefire").isNotBlank();
    Path root = Path.of(repositoryRoot);
    assertThat(root.resolve("common/control-plane-core/pom.xml")).isRegularFile();
    List<String> violations = new ArrayList<>();
    int scanned = 0;
    for (Path file : productionSources(root).stream().sorted().toList()) {
      String relative = root.relativize(file).toString().replace('\\', '/');
      var source = SOURCE.matcher(relative);
      if (!source.matches()) continue;
      scanned++;
      for (String violation : violations(source.group(1), Files.readString(file))) {
        violations.add(relative + ":" + violation);
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
        "import org.springframework.amqp.rabbit.annotation.EnableRabbit;"))
        .containsExactly("1 [rabbit-client-owner] org.springframework.amqp.rabbit.annotation.EnableRabbit");
    assertThat(violations("new-service", "import org.junit.jupiter.api.Test;"))
        .containsExactly("1 [production-no-test-imports] org.junit.jupiter.api.Test");
  }

  @Test
  void sourceDiscoveryFollowsDeclaredModulesAndIgnoresOtherTrees(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("pom.xml"), """
        <project xmlns="http://maven.apache.org/POM/4.0.0"><modules>
          <module>common/work-api</module><module>aggregate</module><module>resources-only</module>
        </modules></project>
        """);
    Path direct = Files.createDirectories(root.resolve("common/work-api/src/main/java"))
        .resolve("Untracked.java");
    Files.writeString(root.resolve("common/work-api/pom.xml"), "<project/>");
    Files.writeString(direct, "import org.springframework.context.ApplicationContext;");
    Path nested = Files.createDirectories(root.resolve("aggregate/worker/src/main/java"))
        .resolve("Worker.java");
    Files.writeString(root.resolve("aggregate/pom.xml"),
        "<project><modules><module>worker</module></modules></project>");
    Files.writeString(root.resolve("aggregate/worker/pom.xml"), "<project/>");
    Files.writeString(nested, "class Worker {}");
    Files.createDirectories(root.resolve("resources-only"));
    Files.writeString(root.resolve("resources-only/pom.xml"), "<project/>");
    Path generated = Files.createDirectories(root.resolve("build-output/src/main/java"))
        .resolve("Generated.java");
    Files.writeString(root.resolve("build-output/pom.xml"), "<not-a-project");
    Files.writeString(generated, "import org.junit.jupiter.api.Test;");
    Files.writeString(root.resolve(".attach_pid80970"), "temporary JVM file");

    assertThat(productionSources(root)).containsExactlyInAnyOrder(direct, nested);
    assertThat(violations("common/work-api", Files.readString(direct)))
        .containsExactly("1 [core-no-infrastructure] org.springframework.context.ApplicationContext");
  }

  private static List<Path> productionSources(Path module) throws Exception {
    var xml = DocumentBuilderFactory.newDefaultInstance();
    xml.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    xml.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    var pom = xml.newDocumentBuilder().parse(module.resolve("pom.xml").toFile());
    var result = new ArrayList<Path>();
    Path sources = module.resolve("src/main/java");
    if (Files.exists(sources)) {
      try (var files = Files.walk(sources)) {
        result.addAll(files.filter(path -> path.toString().endsWith(".java"))
            .filter(Files::isRegularFile).toList());
      }
    }
    var modules = pom.getElementsByTagName("module");
    for (int i = 0; i < modules.getLength(); i++) {
      result.addAll(productionSources(module.resolve(modules.item(i).getTextContent().trim()).normalize()));
    }
    return result;
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

package io.pockethive.mcp.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.mcp.application.ToolCatalogue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class NodeToolMigrationLedgerTest {
    private static final int BASELINE_TOOL_COUNT = 103;
    private static final Pattern LEDGER_ROW = Pattern.compile(
        "(?m)^\\| `([^`]+)` \\| `(MIGRATED|REPLACED_BY ([^`]+)|REMOVED_WITH_REASON|BLOCKED_BY_MISSING_OWNER_API)`");

    @Test
    void preservesTheCompleteCutoverLedgerWithoutKeepingTheNodeImplementation() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        String ledger = Files.readString(root.resolve("docs/mcp/NODE_TOOL_MIGRATION_LEDGER.md"));
        List<String> ledgerEntries = matches(ledger, LEDGER_ROW, 1);
        List<String> migrated = dispositionEntries(ledger, "MIGRATED");
        Set<String> published = ToolCatalogue.canonical().tools().stream()
            .map(tool -> tool.id()).collect(java.util.stream.Collectors.toSet());

        assertThat(ledgerEntries).hasSize(BASELINE_TOOL_COUNT);
        assertThat(new HashSet<>(ledgerEntries)).hasSize(BASELINE_TOOL_COUNT);
        assertThat(migrated.stream().map(NodeToolMigrationLedgerTest::javaToolId))
            .allMatch(published::contains);
        assertThat(Files.exists(root.resolve("tools/pockethive-mcp/server.mjs"))).isFalse();
    }

    private static List<String> dispositionEntries(String source, String disposition) {
        List<String> entries = new ArrayList<>();
        Matcher matcher = LEDGER_ROW.matcher(source);
        while (matcher.find()) {
            if (disposition.equals(matcher.group(2))) {
                entries.add(matcher.group(1));
            }
        }
        return entries;
    }

    private static String javaToolId(String nodeToolId) {
        return nodeToolId.replace('.', '_').replace('-', '_');
    }

    private static List<String> matches(String source, Pattern pattern, int group) {
        List<String> values = new ArrayList<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            values.add(matcher.group(group));
        }
        return values;
    }
}

package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.MessageTypeMapping;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MessageTypeRegistryTest {
    @TempDir Path root;

    private MessageTypeRegistry restart() {
        return new MessageTypeRegistry(new MappingFileStore(root), () -> {
            throw new AssertionError("Saved catalogue must not read startup seeds");
        });
    }

    @Test
    void editsDeletesAndEmptyCatalogueSurviveRestartWithoutReseeding() {
        var registry = new MessageTypeRegistry(new MappingFileStore(root),
            () -> List.of(new MessageTypeMapping("seed", ".*", "original", "seed")));
        registry.addMapping(new MessageTypeMapping("seed", ".*", "edited", "runtime"));
        registry.removeMapping("echo");
        var restored = restart();
        assertFalse(restored.getAllMappings().stream().anyMatch(m -> m.getId().equals("echo")));
        assertEquals("edited", restored.getAllMappings().stream().filter(m -> m.getId().equals("seed"))
            .findFirst().orElseThrow().getResponseTemplate());
        restored.clearMappings();
        assertTrue(restart().getAllMappings().isEmpty());
    }

    @Test
    void rejectedWritePreservesLiveAndSavedCatalogue() {
        var registry = new MessageTypeRegistry(new MappingFileStore(root), List::of);
        var bad = new MessageTypeMapping("echo", ".*", "rejected", "bad");
        bad.setAdvancedMatching(Map.of("unserializable", new Object()));
        assertThrows(UncheckedIOException.class, () -> registry.addMapping(bad));
        for (var state : List.of(registry, restart())) {
            assertEquals("{{message}}", state.getAllMappings().stream().filter(m -> m.getId().equals("echo"))
                .findFirst().orElseThrow().getResponseTemplate());
        }
    }

    @Test
    void concurrentChangesAreBothDurable() throws Exception {
        var registry = new MessageTypeRegistry(new MappingFileStore(root), List::of);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); registry.addMapping(new MessageTypeMapping("one", ".*", "1", "")); return null; });
            var second = executor.submit(() -> { start.await(); registry.addMapping(new MessageTypeMapping("two", ".*", "2", "")); return null; });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        var ids = restart().getAllMappings().stream().map(MessageTypeMapping::getId).toList();
        assertTrue(ids.containsAll(List.of("one", "two")));
    }

    @Test
    void invalidSavedIdsFailWithoutReplacingSnapshot() throws Exception {
        for (String body : List.of("[{}]", "[{\"id\":\"same\"},{\"id\":\"same\"}]")) {
            Files.writeString(root.resolve("mapping-catalogue.json"), body);
            assertThrows(RuntimeException.class, this::restart);
            assertEquals(body, Files.readString(root.resolve("mapping-catalogue.json")));
        }
    }

    @Test
    void seedFailureDoesNotCreatePartialSnapshot() throws Exception {
        Path seed = Files.createDirectory(root.resolve("seed"));
        Files.writeString(seed.resolve("valid.json"), "{\"id\":\"valid\"}");
        Files.writeString(seed.resolve("broken.json"), "broken");
        assertThrows(UncheckedIOException.class, () -> new MessageTypeRegistry(new MappingFileStore(root), new FileBasedMappingLoader(seed)));
        assertFalse(new MappingFileStore(root).hasSnapshot());
    }
    @Test
    void equalPriorityResponseKeepsCatalogueOrderAcrossUpdatesAndRestart() {
        // Both orders must work, independently of JVM immutable-map iteration order.
        for (var ids : List.of(List.of("a", "b"), List.of("b", "a"))) {
            var store = new MappingFileStore(root);
            store.save(List.of());
            var registry = restart();
            for (String id : ids) {
                registry.addMapping(new MessageTypeMapping(id, "^PING$", id, "test"));
            }
            assertEquals(ids.getFirst(), responseToPing(registry));
            assertEquals(ids, store.load().stream().map(MessageTypeMapping::getId).toList());
            assertEquals(ids.getFirst(), responseToPing(restart()));

            registry.addMapping(new MessageTypeMapping(ids.getFirst(), "^PING$", "updated", "test"));
            registry.addMapping(new MessageTypeMapping("unrelated", "^OTHER$", "other", "test"));
            assertEquals("updated", responseToPing(registry));
            registry = restart();
            assertEquals("updated", responseToPing(registry));
            registry.removeMapping(ids.getFirst());
            assertEquals(ids.getLast(), responseToPing(restart()));
        }
    }

    private String responseToPing(MessageTypeRegistry registry) {
        var executor = new MappingExecutor(registry, new io.pockethive.tcpmock.util.PatternCache(),
            new io.pockethive.tcpmock.util.AdvancedRequestMatcher(), null,
            new EnhancedTemplateEngine(), new RequestVerificationService());
        return executor.processMessage("PING").getResponse();
    }
}

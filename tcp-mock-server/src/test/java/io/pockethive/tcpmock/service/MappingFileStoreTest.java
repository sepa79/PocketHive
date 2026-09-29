package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.MessageTypeMapping;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MappingFileStoreTest {
    @TempDir Path root;

    @Test
    void storesAndReplacesTheCompleteCatalogueIncludingEmptyState() {
        var store = new MappingFileStore(root);
        assertFalse(store.hasSnapshot());
        var mapping = new MessageTypeMapping("one", "HELLO", "reply", "test");
        mapping.setFixedDelayMs(125);
        mapping.setResponseDelimiter("\r\n");
        store.save(List.of(mapping));
        assertTrue(store.hasSnapshot());
        var restored = new MappingFileStore(root).load().getFirst();
        assertEquals("one", restored.getId());
        assertEquals("reply", restored.getResponseTemplate());
        assertEquals(125, restored.getFixedDelayMs());
        assertEquals("\r\n", restored.getResponseDelimiter());
        store.save(List.of(new MessageTypeMapping("two", ".*", "changed", "test")));
        assertEquals(List.of("two"), store.load().stream().map(MessageTypeMapping::getId).toList());
        store.save(List.of());
        assertTrue(store.hasSnapshot());
        assertTrue(store.load().isEmpty());
    }

    @Test
    void failedSerializationKeepsPreviousSnapshotAndLeavesNoTemporaryFiles() throws Exception {
        var store = new MappingFileStore(root);
        store.save(List.of(new MessageTypeMapping("old", ".*", "old reply", "test")));
        byte[] before = Files.readAllBytes(root.resolve("mapping-catalogue.json"));
        var invalid = new MessageTypeMapping("new", ".*", "new reply", "test");
        invalid.setAdvancedMatching(Map.of("unserializable", new Object()));
        assertThrows(UncheckedIOException.class, () -> store.save(List.of(invalid)));
        assertArrayEquals(before, Files.readAllBytes(root.resolve("mapping-catalogue.json")));
        try (var files = Files.list(root)) {
            assertEquals(List.of("mapping-catalogue.json"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void replacementFailureRemovesTemporaryFileAndPropagates() throws Exception {
        var target = Files.createDirectories(root.resolve("mapping-catalogue.json"));
        Files.writeString(target.resolve("blocked"), "keep");
        assertThrows(UncheckedIOException.class, () -> new MappingFileStore(root).save(List.of()));
        assertEquals("keep", Files.readString(target.resolve("blocked")));
        try (var files = Files.list(root)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void blockedDataRootFailsExplicitly() throws Exception {
        var blocked = Files.writeString(root.resolve("file"), "not a directory");
        assertThrows(UncheckedIOException.class, () -> new MappingFileStore(blocked).save(List.of()));
        assertEquals("not a directory", Files.readString(blocked));
    }

    @Test
    void missingOrInvalidSnapshotIsNotAnEmptyCatalogue() throws Exception {
        var store = new MappingFileStore(root);
        assertThrows(UncheckedIOException.class, store::load);
        for (String invalid : List.of("not json", "null", "{}")) {
            Files.writeString(root.resolve("mapping-catalogue.json"), invalid);
            assertTrue(store.hasSnapshot());
            assertThrows(UncheckedIOException.class, store::load);
        }
    }
}

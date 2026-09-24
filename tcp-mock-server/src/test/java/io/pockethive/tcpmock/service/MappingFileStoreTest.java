package io.pockethive.tcpmock.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.pockethive.tcpmock.model.MessageTypeMapping;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MappingFileStoreTest {
    @TempDir Path root;

    @Test
    void savesJsonUnderConfiguredDataRootAndReplacesTheFile() throws Exception {
        var store = new MappingFileStore(root.toString());
        var mapping = mapping();
        store.saveMappingToFile(mapping);
        Path file = root.resolve("mappings/test.json");
        var mapper = new ObjectMapper();
        assertEquals(mapper.valueToTree(mapping), mapper.readTree(Files.readString(file)));
        assertTrue(Files.readString(file).contains("\n"));
        mapping.setResponseTemplate("changed");
        store.saveMappingToFile(mapping);
        assertEquals("changed", mapper.readTree(Files.readString(file)).get("responseTemplate").asText());
    }

    @Test
    void writesYamlOnlyForTheExistingExactFormatValue() throws Exception {
        var store = new MappingFileStore(root.toString());
        var mapping = mapping();
        store.saveMappingToFile(mapping, "yaml");
        var mapper = new ObjectMapper(new YAMLFactory());
        assertEquals(mapper.valueToTree(mapping), mapper.readTree(Files.readString(root.resolve("mappings/test.yaml"))));
        assertFalse(Files.exists(root.resolve("mappings/test.json")));
        store.saveMappingToFile(mapping, "YAML");
        assertTrue(Files.exists(root.resolve("mappings/test.json")));
    }

    @Test
    void deletesAllThreeVariantsAndLeavesUnrelatedFiles() throws Exception {
        Path dir = Files.createDirectories(root.resolve("mappings"));
        for (String extension : new String[]{"json", "yaml", "yml"}) {
            Files.writeString(dir.resolve("test." + extension), "content");
        }
        Files.writeString(dir.resolve("other.json"), "other");
        var store = new MappingFileStore(root.toString());
        store.deleteMappingFile("test");
        for (String extension : new String[]{"json", "yaml", "yml"}) {
            assertFalse(Files.exists(dir.resolve("test." + extension)));
        }
        assertEquals("other", Files.readString(dir.resolve("other.json")));
        assertDoesNotThrow(() -> store.deleteMappingFile("test"));
    }

    @Test
    void deletingAbsentMappingDoesNotCreateDirectories() {
        new MappingFileStore(root.toString()).deleteMappingFile("missing");
        assertFalse(Files.exists(root.resolve("mappings")));
    }

    @Test
    void preservesSuppressedWriteIoFailure() throws Exception {
        Path blocked = Files.writeString(root.resolve("blocked"), "file, not directory");
        var store = new MappingFileStore(blocked.toString());
        assertDoesNotThrow(() -> store.saveMappingToFile(mapping()));
        assertEquals("file, not directory", Files.readString(blocked));
    }

    @Test
    void preservesDeleteFailureOrderAndSuppression() throws Exception {
        Path blocked = Files.createDirectories(root.resolve("mappings/test.json"));
        Files.writeString(blocked.resolve("child"), "nonempty");
        Path yaml = Files.writeString(root.resolve("mappings/test.yaml"), "retained");
        var store = new MappingFileStore(root.toString());
        assertDoesNotThrow(() -> store.deleteMappingFile("test"));
        assertTrue(Files.exists(blocked.resolve("child")));
        assertEquals("retained", Files.readString(yaml));
    }

    @Test
    void doesNotTurnRuntimeFailuresIntoSuppressedIoFailures() {
        var store = new MappingFileStore(root.toString());
        assertThrows(NullPointerException.class, () -> store.saveMappingToFile(null));
    }

    private static MessageTypeMapping mapping() {
        var mapping = new MessageTypeMapping("test", "^HELLO$", "response", "description");
        mapping.setPriority(7);
        mapping.setFixedDelayMs(125);
        return mapping;
    }
}

package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.MessageTypeMapping;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FileBasedMappingLoaderTest {
    @TempDir Path root;

    @Test
    void readsSingleAndArraySeedsInBothFormatsAcrossDirectories() throws Exception {
        Files.writeString(root.resolve("single.json"), "{\"id\":\"json-single\"}");
        Files.writeString(root.resolve("array.json"), "[{\"id\":\"json-array\"}]");
        Files.writeString(root.resolve("single.yaml"), "id: yaml-single\n");
        var nested = Files.createDirectory(root.resolve("nested"));
        Files.writeString(nested.resolve("array.yml"), "- id: yaml-array\n");
        Files.writeString(root.resolve("README.txt"), "ignored");
        assertEquals(Set.of("json-single", "json-array", "yaml-single", "yaml-array"),
            new FileBasedMappingLoader(root).load().stream().map(MessageTypeMapping::getId).collect(Collectors.toSet()));
    }

    @Test
    void absentSeedDirectoryIsEmpty() {
        assertTrue(new FileBasedMappingLoader(root.resolve("absent")).load().isEmpty());
    }
}

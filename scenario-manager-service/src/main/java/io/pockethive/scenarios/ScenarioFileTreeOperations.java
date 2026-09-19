package io.pockethive.scenarios;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Responsibility: Provide the single recursive clear and copy implementation used by Scenario Manager filesystem workflows.
 * Must not: Resolve domain paths, validate bundles, or decide when destructive filesystem operations are allowed.
 * Contract: callers must validate and resolve source and target paths before invocation.
 */
final class ScenarioFileTreeOperations {
    private ScenarioFileTreeOperations() {
    }

    static void clear(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (Files.isDirectory(path)) {
                    clear(path);
                }
                Files.deleteIfExists(path);
            }
        }
    }

    static void copy(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(
                        path,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }
}

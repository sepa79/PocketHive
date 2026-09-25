package io.pockethive.tcpmock.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.tcpmock.model.MessageTypeMapping;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Responsibility: read and atomically replace the durable runtime mapping snapshot.
 * Must not: select defaults, mutate the registry or suppress storage failures.
 * Contract: RESP-TCP-MOCK-MAPPING-FILES — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-mapping-files.
 */
@Component
public class MappingFileStore implements MappingPersistence {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path snapshot;

    @Autowired
    public MappingFileStore() {
        this(Path.of("/app/data"));
    }

    MappingFileStore(Path dataRoot) {
        snapshot = dataRoot.resolve("mapping-catalogue.json");
    }

    @Override
    public boolean hasSnapshot() {
        // An inaccessible path must proceed to load and fail, not masquerade as fresh state.
        return !Files.notExists(snapshot);
    }

    @Override
    public List<MessageTypeMapping> load() {
        try {
            List<MessageTypeMapping> mappings = mapper.readValue(Files.readAllBytes(snapshot), new TypeReference<>() {});
            if (mappings == null) {
                throw new IOException("Mapping catalogue must be a JSON array");
            }
            return mappings;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load mapping catalogue: " + snapshot, e);
        }
    }

    @Override
    public void save(Collection<MessageTypeMapping> mappings) {
        Path temporary = null;
        try {
            byte[] content = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(mappings);
            Files.createDirectories(snapshot.getParent());
            temporary = Files.createTempFile(snapshot.getParent(), ".mapping-catalogue-", ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, snapshot, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            throw new UncheckedIOException("Cannot save mapping catalogue: " + snapshot, e);
        }
    }
}

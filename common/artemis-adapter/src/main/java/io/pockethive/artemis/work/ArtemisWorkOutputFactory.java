package io.pockethive.artemis.work;

import io.pockethive.artemis.api.ArtemisOutputSettings;
import io.pockethive.artemis.api.ArtemisWorkIoType;
import io.pockethive.artemis.transport.ArtemisSessions;
import io.pockethive.work.api.transport.WorkOutput;
import io.pockethive.work.api.transport.WorkOutputTransportFactory;
import io.pockethive.work.config.binding.WorkOutputConfig;
import java.util.Objects;

/**
 * Responsibility: create an Artemis output using the already validated adapter settings.
 * Must not: parse settings again, select a fallback or decide publication policy.
 * Contract: RESP-WORK-ARTEMIS-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-artemis-transport.
 */
public final class ArtemisWorkOutputFactory implements WorkOutputTransportFactory {
    private final ArtemisSessions sessions;

    public ArtemisWorkOutputFactory(ArtemisSessions sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override public ArtemisWorkIoType type() { return ArtemisWorkIoType.ARTEMIS; }

    @Override
    public WorkOutput create(WorkOutputConfig config) {
        if (!(config instanceof ArtemisOutputSettings settings)) {
            throw new IllegalArgumentException("Artemis output settings required");
        }
        return new ArtemisWorkOutput(sessions, settings);
    }
}

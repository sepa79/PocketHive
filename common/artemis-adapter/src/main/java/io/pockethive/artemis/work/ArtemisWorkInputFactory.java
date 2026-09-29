package io.pockethive.artemis.work;

import io.pockethive.artemis.api.ArtemisInputSettings;
import io.pockethive.artemis.api.ArtemisWorkIoType;
import io.pockethive.artemis.config.ArtemisSettingValues;
import io.pockethive.artemis.transport.ArtemisSessions;
import io.pockethive.work.api.transport.WorkInputChannel;
import io.pockethive.work.api.transport.WorkInputTransportFactory;
import io.pockethive.work.config.binding.WorkInputConfig;
import java.util.Objects;

/**
 * Responsibility: create an Artemis input using the already validated adapter settings.
 * Must not: parse settings again, select a fallback or own worker execution.
 * Contract: RESP-WORK-ARTEMIS-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-artemis-transport.
 */
public final class ArtemisWorkInputFactory implements WorkInputTransportFactory {
    private final ArtemisSessions sessions;

    public ArtemisWorkInputFactory(ArtemisSessions sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override public ArtemisWorkIoType type() { return ArtemisWorkIoType.ARTEMIS; }

    @Override
    public WorkInputChannel create(String workerName, WorkInputConfig config) {
        ArtemisSettingValues.requiredText(workerName, "workerName");
        if (!(config instanceof ArtemisInputSettings settings)) {
            throw new IllegalArgumentException("Artemis input settings required");
        }
        return new ArtemisWorkInputChannel(sessions, settings);
    }
}

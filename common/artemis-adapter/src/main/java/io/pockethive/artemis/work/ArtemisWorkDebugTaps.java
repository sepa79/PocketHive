package io.pockethive.artemis.work;

import io.pockethive.artemis.config.ArtemisSettingValues;
import io.pockethive.artemis.topology.ArtemisResourceKind;
import io.pockethive.artemis.topology.ArtemisResourceNames;
import io.pockethive.artemis.transport.ArtemisSessions;
import io.pockethive.topology.work.*;
import java.util.Objects;

/**
 * Responsibility: open an Artemis diagnostic copy of an owner-resolved Work channel.
 * Must not: reconstruct source addresses, consume worker deliveries or own request expiry.
 * Contract: RESP-WORK-ARTEMIS-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-artemis-transport.
 */
public final class ArtemisWorkDebugTaps implements WorkDebugTaps {
    private final ArtemisSessions sessions;
    private final ArtemisResourceNames names;
    public ArtemisWorkDebugTaps(ArtemisSessions sessions, ArtemisResourceNames names) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.names = Objects.requireNonNull(names, "names");
    }
    @Override public WorkDebugTap open(String swarmId, String role, String tapId, WorkChannelAddress source,
                                      int ttlSeconds, int maxItems) {
        if (ArtemisResourceKind.require(source.resource()) != ArtemisResourceKind.QUEUE
            || !source.resource().name().equals(source.outputAddress())) {
            throw new IllegalArgumentException("Resolved Artemis source queue required");
        }
        ArtemisSettingValues.positive(ttlSeconds, "ttlSeconds");
        ArtemisSettingValues.positive(maxItems, "maxItems");
        return new ArtemisWorkDebugTap(sessions, source.outputAddress(), names.debugTap(swarmId, role, tapId), names.debugTapDivert(swarmId, role, tapId),
            ttlSeconds, maxItems);
    }
}

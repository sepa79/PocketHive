package io.pockethive.artemis.api;

import io.pockethive.artemis.topology.ArtemisResourceNames;
import io.pockethive.artemis.transport.ArtemisSessions;
import io.pockethive.artemis.work.ArtemisWorkInputFactory;
import io.pockethive.artemis.work.ArtemisWorkDebugTaps;
import io.pockethive.topology.work.WorkDebugTaps;
import io.pockethive.artemis.work.ArtemisWorkOutputFactory;
import io.pockethive.artemis.work.ArtemisWorkResources;
import io.pockethive.artemis.work.ArtemisWorkTopologyResolver;
import io.pockethive.topology.work.WorkPlaneResources;
import io.pockethive.topology.work.WorkTopologyResolver;
import io.pockethive.work.api.transport.WorkInputTransportFactory;
import io.pockethive.work.api.transport.WorkOutputTransportFactory;
import java.util.Objects;

/**
 * Responsibility: compose an explicitly configured Artemis connection into the existing Work ports.
 * Must not: expose vendor clients, choose an adapter implicitly or own swarm lifecycle.
 * Contract: RESP-ARTEMIS-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-artemis-connection.
 */
public final class ArtemisWorkPlane implements AutoCloseable {
    private final ArtemisSessions sessions;
    private final WorkTopologyResolver topology;
    private final WorkDebugTaps debugTaps;
    private final WorkPlaneResources resources;
    private final WorkInputTransportFactory inputs;
    private final WorkOutputTransportFactory outputs;

    public ArtemisWorkPlane(ArtemisConnectionSettings settings, String namespace) {
        Objects.requireNonNull(settings, "settings");
        var names = new ArtemisResourceNames(namespace);
        topology = new ArtemisWorkTopologyResolver(names);
        sessions = new ArtemisSessions(settings);
        try {
            resources = new ArtemisWorkResources(sessions);
            debugTaps = new ArtemisWorkDebugTaps(sessions, names);
            inputs = new ArtemisWorkInputFactory(sessions);
            outputs = new ArtemisWorkOutputFactory(sessions);
        } catch (RuntimeException failure) {
            sessions.close();
            throw failure;
        }
    }

    public WorkDebugTaps debugTaps() { return debugTaps; }

    public WorkTopologyResolver topology() { return topology; }
    public WorkPlaneResources resources() { return resources; }
    public WorkInputTransportFactory inputs() { return inputs; }
    public WorkOutputTransportFactory outputs() { return outputs; }

    @Override public void close() { sessions.close(); }
}

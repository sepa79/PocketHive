package io.pockethive.worker.sdk.testing;

import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.transport.WorkInputChannel;
import io.pockethive.work.api.transport.WorkOutput;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: index explicit test-only Work resources and delegate their state to each channel.
 * Must not: mutate channel state directly, run delivery handlers under the index lock or communicate between containers.
 * Contract: RESP-WORK-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-transport.
 */
public final class InMemoryWorkTransport {
    private final Map<String, InMemoryWorkChannel> channels = new HashMap<>();

    public synchronized void create(String address) {
        InMemoryWorkAddress.require(address);
        if (channels.containsKey(address)) {
            throw new IllegalArgumentException("An explicit, unused memory address is required");
        }
        channels.put(address, new InMemoryWorkChannel(address));
    }

    public WorkInputChannel input(String address) { return required(address); }

    public WorkOutput output(String address) {
        return required(address)::publish;
    }

    public List<WorkItem> pending(String address) { return required(address).pending(); }

    public synchronized boolean exists(String address) { return channels.containsKey(address); }

    public synchronized java.util.Set<String> addresses() { return java.util.Set.copyOf(channels.keySet()); }

    public synchronized java.util.Optional<io.pockethive.topology.work.WorkResourceObservation> observe(String address) {
        InMemoryWorkChannel channel = channels.get(address);
        return channel == null ? java.util.Optional.empty() : java.util.Optional.of(channel.observation());
    }

    public synchronized void remove(String address) {
        InMemoryWorkChannel channel = required(address);
        channel.remove();
        channels.remove(address);
    }

    private synchronized InMemoryWorkChannel required(String address) {
        InMemoryWorkChannel channel = channels.get(address);
        if (channel == null) throw new IllegalArgumentException("Resource does not exist: " + address);
        return channel;
    }

}

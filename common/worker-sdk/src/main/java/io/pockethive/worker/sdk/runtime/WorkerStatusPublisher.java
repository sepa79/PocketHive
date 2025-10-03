package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.api.StatusPublisher;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

final class WorkerStatusPublisher implements StatusPublisher {

    private final WorkerState state;
    private final Runnable fullEmitter;
    private final Runnable deltaEmitter;

    WorkerStatusPublisher(WorkerState state, Runnable fullEmitter, Runnable deltaEmitter) {
        this.state = Objects.requireNonNull(state, "state");
        this.fullEmitter = Objects.requireNonNull(fullEmitter, "fullEmitter");
        this.deltaEmitter = Objects.requireNonNull(deltaEmitter, "deltaEmitter");
    }

    @Override
    public void update(Consumer<MutableStatus> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        state.mutateStatusData(current -> {
            Map<String, Object> mutable = new LinkedHashMap<>(current);
            consumer.accept(new Mutable(mutable));
            return mutable;
        });
    }

    @Override
    public StatusPublisher workIn(String route) {
        state.addInboundRoute(route);
        return this;
    }

    @Override
    public StatusPublisher workOut(String route) {
        state.addOutboundRoute(route);
        return this;
    }

    @Override
    public void emitFull() {
        fullEmitter.run();
    }

    @Override
    public void emitDelta() {
        deltaEmitter.run();
    }

    @Override
    public void recordProcessed() {
        state.recordWork();
    }

    private record Mutable(Map<String, Object> data) implements MutableStatus {

        @Override
        public MutableStatus data(String key, Object value) {
            Objects.requireNonNull(key, "key");
            data.put(key, value);
            return this;
        }
    }
}

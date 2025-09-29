package io.pockethive.controlplane.payload;

import io.pockethive.observability.StatusEnvelopeBuilder;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Factory for producing control-plane status payloads with embedded role context.
 */
public final class StatusPayloadFactory {

    private final RoleContext context;
    private final Supplier<StatusEnvelopeBuilder> builderSupplier;

    public StatusPayloadFactory(RoleContext context) {
        this(context, StatusEnvelopeBuilder::new);
    }

    StatusPayloadFactory(RoleContext context, Supplier<StatusEnvelopeBuilder> builderSupplier) {
        this.context = Objects.requireNonNull(context, "context");
        this.builderSupplier = Objects.requireNonNull(builderSupplier, "builderSupplier");
    }

    public String snapshot(Consumer<StatusEnvelopeBuilder> customiser) {
        return build("status-full", customiser);
    }

    public String delta(Consumer<StatusEnvelopeBuilder> customiser) {
        return build("status-delta", customiser);
    }

    private String build(String kind, Consumer<StatusEnvelopeBuilder> customiser) {
        Objects.requireNonNull(customiser, "customiser");
        StatusEnvelopeBuilder builder = Objects.requireNonNull(builderSupplier.get(), "builder");
        builder.kind(kind)
            .role(context.role())
            .instance(context.instanceId())
            .swarmId(context.swarmId());
        customiser.accept(builder);
        return builder.toJson();
    }
}

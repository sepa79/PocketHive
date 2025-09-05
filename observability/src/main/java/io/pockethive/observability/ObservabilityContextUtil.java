package io.pockethive.observability;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.MDC;

public final class ObservabilityContextUtil {
    public static final String HEADER = "x-ph-trace";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ObservabilityContextUtil() {
    }

    public static ObservabilityContext init(String service, String instance) {
        return init(service, instance, null);
    }

    public static ObservabilityContext init(String service, String instance, String swarmId) {
        ObservabilityContext ctx = new ObservabilityContext();
        ctx.setTraceId(UUID.randomUUID().toString());
        ctx.setHops(new ArrayList<>());
        ctx.setSwarmId(swarmId);
        appendHop(ctx, service, instance, Instant.now(), Instant.now());
        return ctx;
    }

    public static void appendHop(ObservabilityContext ctx, String service, String instance, Instant receivedAt, Instant processedAt) {
        ctx.getHops().add(new Hop(service, instance, receivedAt, processedAt));
    }

    public static String toHeader(ObservabilityContext ctx) {
        try {
            return MAPPER.writeValueAsString(ctx);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize observability context", e);
        }
    }

    public static ObservabilityContext fromHeader(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(header, ObservabilityContext.class);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to deserialize observability context", e);
        }
    }

    public static void populateMdc(ObservabilityContext ctx) {
        if (ctx != null) {
            if (ctx.getTraceId() != null) {
                MDC.put("traceId", ctx.getTraceId());
            }
            if (ctx.getSwarmId() != null) {
                MDC.put("swarmId", ctx.getSwarmId());
            }
        }
    }
}

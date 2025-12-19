package io.pockethive.worker.sdk.autoconfigure;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;

/**
 * Centralises consumption of the worker control-plane queue so individual services no longer need
 * to duplicate the listener wiring. The component is only created when the worker control-plane
 * runtime is available in the application context.
 */
public class WorkerControlQueueListener {

    private static final Logger log = LoggerFactory.getLogger(WorkerControlQueueListener.class);

    private final WorkerControlPlaneRuntime controlPlaneRuntime;

    public WorkerControlQueueListener(WorkerControlPlaneRuntime controlPlaneRuntime) {
        this.controlPlaneRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
    }

    @RabbitListener(queues = "#{@workerControlQueueName}")
    public void onControl(String payload,
                          @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey,
                          @Header(value = ObservabilityContextUtil.HEADER, required = false) String traceHeader) {
        ObservabilityContext context = ObservabilityContextUtil.fromHeader(traceHeader);
        ObservabilityContextUtil.populateMdc(context);
        try {
            if (routingKey == null || routingKey.isBlank()) {
                throw rejectInvalid("Control routing key must not be null or blank", null, payload, routingKey);
            }
            if (payload == null || payload.isBlank()) {
                throw rejectInvalid("Control payload must not be null or blank", null, payload, routingKey);
            }
            try {
                boolean handled = controlPlaneRuntime.handle(payload, routingKey);
                if (!handled) {
                    log.debug("Ignoring control-plane payload on routing key {}", routingKey);
                }
            } catch (RuntimeException ex) {
                if (isPoisonMessage(ex)) {
                    throw rejectInvalid("Invalid control-plane message (dropping, no requeue)", ex, payload, routingKey);
                }
                throw ex;
            }
        } finally {
            MDC.clear();
        }
    }

    private AmqpRejectAndDontRequeueException rejectInvalid(String reason,
                                                           Throwable cause,
                                                           String payload,
                                                           String routingKey) {
        String safeRoutingKey = routingKey == null ? "n/a" : routingKey.trim();
        log.warn("{} rk={} payload={}", reason, safeRoutingKey, snippet(payload), cause);
        return cause == null
            ? new AmqpRejectAndDontRequeueException(reason)
            : new AmqpRejectAndDontRequeueException(reason, cause);
    }

    private boolean isPoisonMessage(Throwable ex) {
        return findCause(ex, JsonProcessingException.class).isPresent();
    }

    private static <T extends Throwable> Optional<T> findCause(Throwable ex, Class<T> type) {
        Throwable current = ex;
        while (current != null) {
            if (type.isInstance(current)) {
                return Optional.of(type.cast(current));
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    private static String snippet(String payload) {
        if (payload == null) {
            return "";
        }
        String trimmed = payload.strip();
        if (trimmed.length() > 300) {
            return trimmed.substring(0, 300) + "…";
        }
        return trimmed;
    }
}

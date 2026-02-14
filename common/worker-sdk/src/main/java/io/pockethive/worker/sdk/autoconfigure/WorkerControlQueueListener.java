package io.pockethive.worker.sdk.autoconfigure;

import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
                log.warn("Received control-plane payload with null/blank routing key (dropping).");
                return;
            }
            if (payload == null || payload.isBlank()) {
                log.warn("Received control-plane payload with null/blank body on routing key {} (dropping).", routingKey);
                return;
            }
            try {
                boolean handled = controlPlaneRuntime.handle(payload, routingKey);
                if (!handled) {
                    log.debug("Ignoring control-plane payload on routing key {}", routingKey);
                }
            } catch (Exception e) {
                log.warn("Control-plane handler error on routing key {} (ack + drop)", routingKey, e);
            }
        } finally {
            MDC.clear();
        }
    }
}

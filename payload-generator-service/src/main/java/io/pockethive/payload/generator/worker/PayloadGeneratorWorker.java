package io.pockethive.payload.generator.worker;

import io.pockethive.payload.generator.config.PayloadGeneratorConfig;
import io.pockethive.payload.generator.config.PayloadGeneratorProperties;
import io.pockethive.payload.generator.runtime.PayloadTemplateRenderer;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component("payloadGeneratorWorker")
@PocketHiveWorker(
    role = "payload-generator",
    input = WorkerInputType.SCHEDULER,
    output = WorkerOutputType.RABBITMQ,
    capabilities = {WorkerCapability.SCHEDULER},
    config = PayloadGeneratorConfig.class
)
public class PayloadGeneratorWorker implements PocketHiveWorkerFunction {

    private static final Logger log = LoggerFactory.getLogger(PayloadGeneratorWorker.class);

    private final PayloadGeneratorProperties properties;
    private final PayloadTemplateRenderer templateRenderer;

    public PayloadGeneratorWorker(
        PayloadGeneratorProperties properties,
        PayloadTemplateRenderer templateRenderer
    ) {
        this.properties = properties;
        this.templateRenderer = templateRenderer;

        PayloadGeneratorConfig defaultConfig = properties.defaultConfig();
        PayloadGeneratorProperties.Template template = properties.getTemplate();
        log.info(
            "payloadGeneratorWorker initialized (defaultRatePerSec={}, singleRequest={}, templateBodyLength={}, headerTemplateCount={})",
            defaultConfig.ratePerSec(),
            defaultConfig.singleRequest(),
            template.getBody() == null ? 0 : template.getBody().length(),
            template.getHeaders() == null ? 0 : template.getHeaders().size()
        );
    }

    @Override
    public WorkResult onMessage(WorkMessage seed, WorkerContext context) {
        // Pull worker config from the context or fall back to defaults.
        PayloadGeneratorConfig config = context.config(PayloadGeneratorConfig.class)
            .orElseGet(properties::defaultConfig);

        Map<String, Object> headers = seed.headers();
        Object correlationId = headers.get("correlationId");
        if (correlationId == null) {
            correlationId = headers.get("correlation-id");
        }
        if (correlationId == null) {
            correlationId = "n/a";
        }

        log.info(
            "payloadGeneratorWorker processing seed (instance={}, correlationId={}, ratePerSec={}, singleRequest={})",
            context.info().instanceId(),
            correlationId,
            config.ratePerSec(),
            config.singleRequest()
        );

        // Pebble renders the configured template (`pockethive.workers.payload-generator.template.body/headers`)
        // using the incoming seed (available as `seed.body` and `seed.headers`, see https://pebbletemplates.io/).
        PayloadTemplateRenderer.RenderedMessage rendered = templateRenderer.render(seed);

        // Publish live status so operators can observe current generation settings.
        context.statusPublisher()
            .update(status -> status
                .data("ratePerSec", config.ratePerSec())
                .data("singleRequest", config.singleRequest())
                .data("templateBodyLength", rendered.body().length()));

        // Build a new work message from the rendered payload while preserving its headers.
        WorkMessage.Builder builder = WorkMessage.text(rendered.body());
        rendered.headers().forEach(builder::header);
        return WorkResult.message(builder.build());
    }
}

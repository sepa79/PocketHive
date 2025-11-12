package io.pockethive.payload.generator.worker;

import io.pockethive.payload.generator.config.PayloadGeneratorConfig;
import io.pockethive.payload.generator.config.PayloadGeneratorProperties;
import io.pockethive.payload.generator.runtime.PayloadTemplateRenderer;
import io.pockethive.payload.generator.runtime.StaticDatasetRecordProvider;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
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

    private final PayloadGeneratorProperties properties;
    private final StaticDatasetRecordProvider recordProvider;
    private final PayloadTemplateRenderer templateRenderer;

    public PayloadGeneratorWorker(
        PayloadGeneratorProperties properties,
        StaticDatasetRecordProvider recordProvider,
        PayloadTemplateRenderer templateRenderer
    ) {
        this.properties = properties;
        this.recordProvider = recordProvider;
        this.templateRenderer = templateRenderer;
    }

    @Override
    public WorkResult onMessage(WorkMessage seed, WorkerContext context) {
        PayloadGeneratorConfig config = context.config(PayloadGeneratorConfig.class)
            .orElseGet(properties::defaultConfig);

        StaticDatasetRecordProvider.PayloadRecord record = recordProvider.nextRecord();
        PayloadTemplateRenderer.RenderedMessage rendered = templateRenderer.render(record);

        context.statusPublisher()
            .update(status -> status
                .data("dataset", record.dataset())
                .data("ratePerSec", config.ratePerSec())
                .data("singleRequest", config.singleRequest())
                .data("templateBodyLength", rendered.body().length()));

        WorkMessage.Builder builder = WorkMessage.text(rendered.body());
        rendered.headers().forEach(builder::header);
        builder.header("x-ph-dataset", record.dataset());
        return WorkResult.message(builder.build());
    }
}

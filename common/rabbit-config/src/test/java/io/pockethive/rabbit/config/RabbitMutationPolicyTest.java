package io.pockethive.rabbit.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
import io.pockethive.work.config.WorkMutationRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RabbitMutationPolicyTest {
    @Test void policiesDeclareOnlyRabbitMqTypes() {
        assertThat(new RabbitInputMutationPolicy().type()).isEqualTo(WorkerInputType.RABBITMQ);
        assertThat(new RabbitOutputMutationPolicy().type()).isEqualTo(WorkerOutputType.RABBITMQ);
        assertThat(new RabbitInputMutationPolicy().descriptors().liveMutablePaths()).isEmpty();
        new RabbitOutputMutationPolicy().validate(new WorkMutationRequest(
            "worker", Map.of(), Map.of(), Map.of(), "outputs.rabbit", null, null, false));
    }
}

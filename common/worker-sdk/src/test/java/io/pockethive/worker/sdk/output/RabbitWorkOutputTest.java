package io.pockethive.worker.sdk.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.worker.sdk.config.RabbitOutputProperties;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import io.pockethive.rabbit.api.RabbitMessage;
import io.pockethive.rabbit.api.RabbitPublisher;

class RabbitWorkOutputTest {

    @Test
    void publishesWorkItemEnvelopeAsJson() {
        RabbitPublisher template = mock(RabbitPublisher.class);
        RabbitOutputProperties properties = new RabbitOutputProperties();
        properties.setExchange("ex");
        properties.setRoutingKey("rk");
        RabbitWorkOutput output = new RabbitWorkOutput(template, properties);

        // Later mutations of the source settings/template cannot redirect this output instance.
        properties.setExchange("changed");
        properties.setRoutingKey("changed");
        properties.setPublisherConfirms(true);

        WorkerInfo info = new WorkerInfo("processor", "swarm", "instance", null, null);
        WorkItem outbound = WorkItem.json(info, Map.of("status", 200))
            .contentType("application/json")
            .header("x-test", "value")
            .observabilityContext(ObservabilityContextUtil.init(info.role(), info.instanceId(), info.swarmId()))
            .build();

        WorkerDefinition definition = mock(WorkerDefinition.class);
        output.publish(outbound, definition);

        ArgumentCaptor<RabbitMessage> captor = ArgumentCaptor.forClass(RabbitMessage.class);
        verify(template).send(eq("ex"), eq("rk"), captor.capture());
        RabbitMessage sent = captor.getValue();
        assertThat(sent.contentType()).isEqualTo("application/json");
        assertThat(sent.headers()).isEmpty();
    }
    @Test
    void publisherConfirmsSettingRetainsSubmissionOnlyBehavior() {
        var publisher = mock(RabbitPublisher.class);
        var properties = new RabbitOutputProperties();
        properties.setExchange("exchange");
        properties.setRoutingKey("route");
        properties.setPublisherConfirms(true);
        var output = new RabbitWorkOutput(publisher, properties);
        properties.setPublisherConfirms(false);
        var failure = new IllegalStateException("client send failed");
        org.mockito.Mockito.doThrow(failure).when(publisher).send(eq("exchange"), eq("route"), org.mockito.ArgumentMatchers.any());
        var info = new WorkerInfo("processor", "swarm", "instance", null, null);
        var item = WorkItem.json(info, Map.of("status", 200))
            .observabilityContext(ObservabilityContextUtil.init(info.role(), info.instanceId(), info.swarmId())).build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> output.publish(item, mock(WorkerDefinition.class))).isSameAs(failure);
    }

}

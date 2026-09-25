package io.pockethive.rabbit.work;

import io.pockethive.work.api.transport.WorkOutput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.rabbit.api.RabbitOutputSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import io.pockethive.rabbit.api.RabbitMessage;
import io.pockethive.rabbit.api.RabbitPublisher;

class RabbitWorkOutputTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void publishesWorkItemEnvelopeAsJson(boolean persistent) {
        RabbitPublisher template = mock(RabbitPublisher.class);
        var settings = new RabbitOutputSettings("ex", "rk", persistent, false);
        RabbitWorkOutput output = new RabbitWorkOutput(template, settings);

        WorkerInfo info = new WorkerInfo("processor", "swarm", "instance", null, null);
        WorkItem outbound = WorkItem.json(info, Map.of("status", 200))
            .contentType("application/json")
            .header("x-test", "value")
            .observabilityContext(ObservabilityContextUtil.init(info.role(), info.instanceId(), info.swarmId()))
            .build();

        output.publish(outbound);

        ArgumentCaptor<RabbitMessage> captor = ArgumentCaptor.forClass(RabbitMessage.class);
        verify(template).send(eq("ex"), eq("rk"), captor.capture());
        RabbitMessage sent = captor.getValue();
        assertThat(sent.contentType()).isEqualTo("application/json");
        assertThat(sent.headers()).isEmpty();
        assertThat(sent.persistent()).isEqualTo(persistent);
    }
    @Test
    void rejectsDelayedIntentWithoutSending() {
        var publisher = mock(RabbitPublisher.class);
        var output = new RabbitWorkOutput(publisher, new RabbitOutputSettings("exchange", "route", true, false));
        var info = new WorkerInfo("processor", "swarm", "instance", null, null);
        var item = WorkItem.text(info, "payload").build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> output.publish(item,
            new io.pockethive.work.config.WorkDelivery(io.pockethive.work.config.WorkDeliveryMode.DELAYED, 1000)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not support DELAYED");
        org.mockito.Mockito.verifyNoInteractions(publisher);
    }

    @Test
    void publisherConfirmsSettingRetainsSubmissionOnlyBehavior() {
        var publisher = mock(RabbitPublisher.class);
        var settings = new RabbitOutputSettings("exchange", "route", true, true);
        var output = new RabbitWorkOutput(publisher, settings);
        var failure = new IllegalStateException("client send failed");
        org.mockito.Mockito.doThrow(failure).when(publisher).send(eq("exchange"), eq("route"), org.mockito.ArgumentMatchers.any());
        var info = new WorkerInfo("processor", "swarm", "instance", null, null);
        var item = WorkItem.json(info, Map.of("status", 200))
            .observabilityContext(ObservabilityContextUtil.init(info.role(), info.instanceId(), info.swarmId())).build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> output.publish(item)).isSameAs(failure);
    }

}

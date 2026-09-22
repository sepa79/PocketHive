package io.pockethive.artemis.broker;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.core.message.impl.CoreMessage;
import org.junit.jupiter.api.Test;

class DiagnosticCaptureTransformerTest {
    private final DiagnosticCaptureTransformer transformer = new DiagnosticCaptureTransformer();

    @Test
    void removesOnlyTheCopyScheduleWhilePreservingSourceAndDiagnosticContent() {
        var source = new CoreMessage(1, 128);
        source.setAddress("work");
        source.setRoutingType(RoutingType.ANYCAST);
        source.setExpiration(9000);
        source.setScheduledDeliveryTime(7000L);
        source.putStringProperty("correlationId", "correlation");
        source.getBodyBuffer().writeString("payload");
        var copy = source.copy(2).toCore();

        assertThat(transformer.transform(copy)).isSameAs(copy);

        assertThat(copy.containsProperty(Message.HDR_SCHEDULED_DELIVERY_TIME)).isFalse();
        assertThat(copy.getScheduledDeliveryTime()).isZero();
        assertThat(copy.getAddress()).isEqualTo(source.getAddress());
        assertThat(copy.getRoutingType()).isEqualTo(RoutingType.ANYCAST);
        assertThat(copy.getExpiration()).isEqualTo(9000);
        assertThat(copy.getStringProperty("correlationId")).isEqualTo("correlation");
        assertThat(copy.getBodyBuffer().readString()).isEqualTo("payload");
        assertThat(source.getScheduledDeliveryTime()).isEqualTo(7000);
        assertThat(source.getBodyBuffer().readString()).isEqualTo("payload");
    }

    @Test
    void immediateCopiesRemainImmediateWithoutAddingAScheduleProperty() {
        var copy = new CoreMessage(1, 128);
        assertThat(transformer.transform(copy)).isSameAs(copy);
        assertThat(copy.containsProperty(Message.HDR_SCHEDULED_DELIVERY_TIME)).isFalse();
        assertThat(copy.getScheduledDeliveryTime()).isZero();
    }
}

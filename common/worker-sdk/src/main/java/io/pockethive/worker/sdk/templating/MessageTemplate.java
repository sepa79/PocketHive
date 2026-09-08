package io.pockethive.worker.sdk.templating;

import io.pockethive.work.api.WorkItem;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable template definition for rendering messages from a {@link WorkItem}.
 * <p>
 * Responsibility: describe body, path, method and header templates for one rendered message.
 * Must not: open transport connections or implement a second Pebble/SpEL evaluator.
 * Contract: RESP-WORK-MESSAGE-TEMPLATE — docs/architecture/runtime-responsibilities.md#resp-work-message-template.
 */
public record MessageTemplate(
    MessageBodyType bodyType,
    String pathTemplate,
    String methodTemplate,
    String bodyTemplate,
    Map<String, String> headerTemplates
) {

    public MessageTemplate {
        Objects.requireNonNull(bodyType, "bodyType");
        headerTemplates = headerTemplates == null ? Map.of() : Map.copyOf(headerTemplates);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private MessageBodyType bodyType;
        private String pathTemplate;
        private String methodTemplate;
        private String bodyTemplate;
        private Map<String, String> headerTemplates = Map.of();

        public Builder bodyType(MessageBodyType bodyType) {
            this.bodyType = bodyType;
            return this;
        }

        public Builder pathTemplate(String pathTemplate) {
            this.pathTemplate = pathTemplate;
            return this;
        }

        public Builder methodTemplate(String methodTemplate) {
            this.methodTemplate = methodTemplate;
            return this;
        }

        public Builder bodyTemplate(String bodyTemplate) {
            this.bodyTemplate = bodyTemplate;
            return this;
        }

        public Builder headerTemplates(Map<String, String> headerTemplates) {
            this.headerTemplates = headerTemplates;
            return this;
        }

        public MessageTemplate build() {
            Objects.requireNonNull(bodyType, "bodyType");
            return new MessageTemplate(bodyType, pathTemplate, methodTemplate, bodyTemplate, headerTemplates);
        }
    }
}

package io.pockethive.worker.sdk.templating;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable template definition for rendering messages from a {@link io.pockethive.worker.sdk.api.WorkItem}.
 */
public record MessageTemplate(
    MessageBodyType bodyType,
    String pathTemplate,
    String methodTemplate,
    String bodyTemplate,
    Map<String, String> headerTemplates
) {

    public MessageTemplate {
        bodyType = bodyType == null ? MessageBodyType.HTTP : bodyType;
        headerTemplates = headerTemplates == null ? Map.of() : Map.copyOf(headerTemplates);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private MessageBodyType bodyType = MessageBodyType.HTTP;
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

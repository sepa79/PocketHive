package io.pockethive.payload.generator.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.config.CanonicalWorkerProperties;
import java.util.Collections;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pockethive.workers.payload-generator")
public class PayloadGeneratorProperties extends CanonicalWorkerProperties<PayloadGeneratorConfig> {

    private static final PayloadGeneratorConfig FALLBACK = new PayloadGeneratorConfig(1.0, false);

    private final ObjectMapper mapper;
    private Template template = new Template();

    public PayloadGeneratorProperties(ObjectMapper mapper) {
        super("payload-generator", PayloadGeneratorConfig.class, mapper);
        this.mapper = mapper;
    }

    public Template getTemplate() {
        return template;
    }

    public void setTemplate(Template template) {
        this.template = template == null ? new Template() : template;
    }

    @JsonIgnore
    public PayloadGeneratorConfig defaultConfig() {
        return toConfig(mapper).orElse(FALLBACK);
    }

    public static final class Template {

        private String method = "POST";
        private String url = "";
        private String baseUrl = "";
        private String path = "/";
        private Map<String, String> query = Collections.emptyMap();
        private String body = "{{ seed.body | default('Hello from PayloadGenerator') }}";
        private Map<String, String> headers = Collections.emptyMap();

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method == null ? "" : method;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url == null ? "" : url;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl == null ? "" : baseUrl;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path == null ? "/" : path;
        }

        public Map<String, String> getQuery() {
            return query;
        }

        public void setQuery(Map<String, String> query) {
            this.query = query == null ? Collections.emptyMap() : Map.copyOf(query);
        }

        public String getBody() {
            return body;
        }

        public void setBody(String body) {
            this.body = body == null ? "" : body;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers == null ? Collections.emptyMap() : Map.copyOf(headers);
        }

        public void setPayload(String payload) {
            setBody(payload);
        }
    }
}

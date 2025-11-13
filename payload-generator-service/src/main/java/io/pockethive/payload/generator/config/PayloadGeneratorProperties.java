package io.pockethive.payload.generator.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Collections;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pockethive.workers.payload-generator")
public class PayloadGeneratorProperties {

    private Scheduler scheduler = new Scheduler();
    private Template template = new Template();

    public Scheduler getScheduler() {
        return scheduler;
    }

    public void setScheduler(Scheduler scheduler) {
        this.scheduler = scheduler == null ? new Scheduler() : scheduler;
    }

    public Template getTemplate() {
        return template;
    }

    public void setTemplate(Template template) {
        this.template = template == null ? new Template() : template;
    }

    @JsonIgnore
    public PayloadGeneratorConfig defaultConfig() {
        return PayloadGeneratorConfig.of(scheduler.ratePerSec, scheduler.singleRequest);
    }

    public static final class Scheduler {

        private double ratePerSec = 1.0;
        private boolean singleRequest;

        public double getRatePerSec() {
            return ratePerSec;
        }

        public void setRatePerSec(double ratePerSec) {
            this.ratePerSec = Double.isNaN(ratePerSec) || ratePerSec < 0 ? 0.0 : ratePerSec;
        }

        public boolean isSingleRequest() {
            return singleRequest;
        }

        public void setSingleRequest(boolean singleRequest) {
            this.singleRequest = singleRequest;
        }
    }

    public static final class Template {

        private String body = "{{ seed.body | default('Hello from PayloadGenerator') }}";
        private Map<String, String> headers = Collections.emptyMap();

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
    }
}

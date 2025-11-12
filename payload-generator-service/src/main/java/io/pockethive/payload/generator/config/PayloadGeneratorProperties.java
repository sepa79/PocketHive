package io.pockethive.payload.generator.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pockethive.workers.payload-generator")
public class PayloadGeneratorProperties {

    private Scheduler scheduler = new Scheduler();
    private Template template = new Template();
    private StaticDataset dataset = new StaticDataset();

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

    public StaticDataset getDataset() {
        return dataset;
    }

    public void setDataset(StaticDataset dataset) {
        this.dataset = dataset == null ? new StaticDataset() : dataset;
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

        private String body = "Hello from PayloadGenerator";
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

    public static final class StaticDataset {

        private String name = "default-dataset";
        private List<Map<String, Object>> records = List.of(Map.of("message", "Hello"));

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = (name == null || name.isBlank()) ? "default-dataset" : name.trim();
        }

        public List<Map<String, Object>> getRecords() {
            return records;
        }

        public void setRecords(List<Map<String, Object>> records) {
            if (records == null || records.isEmpty()) {
                this.records = List.of(Map.of("message", "Hello"));
                return;
            }
            this.records = List.copyOf(records);
        }
    }
}

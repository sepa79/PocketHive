package io.pockethive.payload.generator.runtime;

import io.pockethive.payload.generator.config.PayloadGeneratorProperties;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public final class StaticDatasetRecordProvider {

    private final String datasetName;
    private final List<Map<String, Object>> records;
    private final AtomicInteger cursor = new AtomicInteger();

    public StaticDatasetRecordProvider(PayloadGeneratorProperties.StaticDataset dataset) {
        Objects.requireNonNull(dataset, "dataset");
        this.datasetName = dataset.getName();
        this.records = dataset.getRecords();
        if (records.isEmpty()) {
            throw new IllegalStateException("Payload generator dataset must contain at least one record");
        }
    }

    public PayloadRecord nextRecord() {
        int index = Math.floorMod(cursor.getAndIncrement(), records.size());
        Map<String, Object> record = records.get(index);
        return new PayloadRecord(datasetName, record);
    }

    public record PayloadRecord(String dataset, Map<String, Object> attributes) {

        public PayloadRecord {
            dataset = (dataset == null || dataset.isBlank()) ? "default-dataset" : dataset;
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}

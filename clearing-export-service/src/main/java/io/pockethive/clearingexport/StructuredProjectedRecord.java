package io.pockethive.clearingexport;

import java.util.Map;

record StructuredProjectedRecord(
    Map<String, String> values,
    Map<String, Double> numericValues
) {
}


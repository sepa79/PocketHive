# CSV Dataset Input Requirement

## Overview
New `WorkInput` implementation that reads CSV files and dispatches rows as JSON at a configured rate.

## Configuration

```yaml
pockethive:
  inputs:
    type: CSV_DATASET
    csv:
      filePath: /app/csv-data/test-data.csv
      ratePerSec: 10.0
      rotate: true
      skipHeader: true
```

## CSV to JSON

**CSV**:
```csv
customer_code,transaction_id,product_class_id
2114119999,22102716640474270,NFT_DA_SUCCESS2
```

**JSON**:
```json
{"customer_code":"2114119999","transaction_id":"22102716640474270","product_class_id":"NFT_DA_SUCCESS2"}
```

## Headers
- `x-ph-csv-file`: Source file
- `x-ph-csv-row`: Row number
- `x-ph-csv-remaining`: Rows left (if not rotating)

## Implementation

```java
package io.pockethive.worker.sdk.input.csv;

public final class CsvDataSetWorkInput implements WorkInput {
    // Follows RedisDataSetWorkInput pattern
}

public final class CsvDataSetInputProperties {
    private String filePath;
    private double ratePerSec = 1.0;
    private boolean rotate = false;
    private boolean skipHeader = true;
}
```

## Dependencies

```xml
<dependency>
  <groupId>org.apache.commons</groupId>
  <artifactId>commons-csv</artifactId>
  <version>1.10.0</version>
</dependency>
```

# CSV Test Data

This directory contains CSV files for PocketHive scenarios using the CSV_DATASET input type.

## CSV Format

Each CSV file must have:
1. **Header row** (first line) - Column names become JSON keys
2. **Data rows** - Values for each column

Example:
```csv
first_name,last_name, location
Roger, Moore, London
Mark, Harris, Paris
```

Converts to JSON:
```json
{"first_name":"Roger","last_name":"Moore","location":"London"}
{"first_name":"Mark","last_name":"Harris","location":"Paris"}
```

## Usage in Scenarios

Mount this directory into worker containers:

```yaml
- role: dataProvider
  image: generator:latest
  volumes:
    - ./csv-data:/app/csv-data:ro
  config:
    inputs:
      type: CSV_DATASET
      csv:
        filePath: /app/csv-data/users.csv
        ratePerSec: 5
        rotate: true  # loop again if exausted
        skipHeaders: false # don't treat headers as a row (set true when no headers for col0,col1,col2,etc as automatic headers)
```

## Adding New CSV Files

1. Create CSV file in this directory
2. Ensure first row contains column headers
3. Add data rows (one per line)
4. Reference in scenario using `/app/csv-data/<filename>.csv`

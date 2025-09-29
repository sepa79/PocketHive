# Scenario Manager Service

Manages simulation scenarios over a REST API.

See the [architecture reference](../docs/ARCHITECTURE.md) for endpoint and signal details.

## Scenario payload schema

Scenarios embed references to registered assets. Requests submitted to `POST /scenarios`
and `PUT /scenarios/{id}` must conform to [`src/main/resources/scenario-schema.json`](src/main/resources/scenario-schema.json).

```json
{
  "id": "mock-1",
  "name": "Mock scenario",
  "assets": {
    "suts": [
      {
        "id": "sut-generic",
        "name": "Generic workload",
        "entrypoint": "/opt/start.sh",
        "version": "1.0.0"
      }
    ],
    "datasets": [
      {
        "id": "dataset-gen",
        "name": "Generator seed",
        "uri": "s3://bucket/datasets/generator.json",
        "format": "json"
      }
    ],
    "swarmTemplates": [
      {
        "id": "swarm-default",
        "name": "Default swarm",
        "sutId": "sut-generic",
        "datasetId": "dataset-gen",
        "swarmSize": 1
      }
    ]
  },
  "template": {
    "image": "pockethive-swarm-controller:latest",
    "bees": []
  },
  "tracks": []
}
```

All identifiers, entrypoints, URIs, and formats must be non-blank. Swarm template
references must use registered SUT and dataset identifiers, and `swarmSize` must be a
positive integer. Submit empty arrays when an asset catalog entry is not yet required.


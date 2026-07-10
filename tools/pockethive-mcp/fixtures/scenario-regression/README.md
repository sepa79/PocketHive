# Scenario validation regression seed

`v0.15.28-local-rest-topology/scenario.yaml` is a source-derived copy of
`v0.15.28:scenarios/bundles/local-rest-topology/scenario.yaml`. It deliberately
keeps legacy `template.bees[].id`, `config.worker`, and topology `beeId` fields.

The manual MCP regression acceptance creates temporary invalid variants from
this seed and submits every variant through `bundle_validate`. It contains no
independent validator: Scenario Manager remains the only bundle validation
authority.

# Processor Service

Executes requests against the system under test using moderated messages.

## Responsibilities
- Consume from `ph.<swarmId>.mod` and forward calls to the SUT.
- Adjust behaviour based on control-plane `config-update` signals.

See [control-plane rules](../docs/rules/control-plane-rules.md) for signal formats.

# Observability

## Buzz Handshake Filter

The Buzz panel can isolate swarm handshake events to help track controller provisioning.

1. Open the **Buzz** view in the UI.
2. Connect to the control plane if not already connected.
3. Select the **Handshake** tab to display messages routed through
   `ev.ready.swarm-controller.*` and `ev.swarm-created.*`.

Each entry shows the event timestamp, routing key and payload so operators
can verify when a swarm controller reports ready and when a swarm is created.

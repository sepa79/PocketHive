# Observability

## Buzz Handshake Filter

The Buzz panel can isolate swarm handshake events to help track controller provisioning.

1. Open the **Buzz** view in the UI.
2. Connect to the control plane if not already connected.
3. Select the **Handshake** tab to display `sig.swarm-template.*`,
   `ev.swarm-created.*` and `ev.swarm-ready.*` messages.
   Filtering expression: `sig.swarm-template.*|ev.swarm-created.*|ev.swarm-ready.*`

Each entry shows the event timestamp, routing key and payload so operators
can verify when a template is dispatched, a controller launches and when a swarm reports ready.

## Swarm Debug Panel

The debug panel visualizes the handshake timeline for each swarm.

1. Click the **Debug** button in the UI header to enable debug mode.
2. Open the **Buzz** view.
3. After initiating a swarm, the panel lists template, created, ready and start
   events with their timestamps and highlights missing steps after a delay.

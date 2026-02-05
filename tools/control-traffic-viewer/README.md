# PocketHive Control-Plane Traffic Viewer (MVP)

Statyczny viewer do przeglądania nagrań z `tools/mcp-orchestrator-debug/rabbit-recorder.mjs`
(`control-recording*.jsonl`).

## Uruchomienie

Viewer używa ES modules, więc najlepiej odpalić prosty serwer HTTP.

Z repo root:

```bash
python3 -m http.server 8000
```

Otwórz w przeglądarce:

```text
http://localhost:8000/tools/control-traffic-viewer/
```

## Użycie

1. Kliknij **Load recording…** albo przeciągnij plik `.jsonl` na drop-zone.
2. Użyj filtrów (swarm/kind/type/role/instance/origin/correlation/routingKey).
3. Kliknij thread (`correlationId`), żeby zawęzić timeline.
4. Kliknij wpis w timeline, żeby zobaczyć szczegóły i skopiować JSON.

## Format wejścia

Każda linia to JSON:

```json
{"timestamp":"...","routingKey":"...","headers":{},"body":"{...json string...}"}
```

`body` jest parsowany jako JSON (string → obiekt).


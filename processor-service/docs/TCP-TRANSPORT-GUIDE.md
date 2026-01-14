# TCP Transport Guide (Processor)

## Overview

The Processor supports TCP calls in addition to HTTP. TCP calls use `TcpProtocolHandler` and a pluggable `tcpTransport` implementation.

## Execution mode (pacing)

`mode: RATE_PER_SEC` + `ratePerSec` is a shared pacing knob across HTTP and TCP calls for a Processor instance.

## Transport types

Set `tcpTransport.type` to choose an implementation:

- `socket` (default): Java `Socket`; supports keep-alive reuse when `tcpTransport.keepAlive=true` and `tcpTransport.connectionReuse != NONE`.
- `nio`: Java NIO; opens a new connection per request (plaintext only).
- `netty`: Netty client; opens a new connection per request (supports TLS).

## Common TCP options

TCP calls honour these config values (control-plane overrides included):

- `tcpTransport.timeout`: read timeout (milliseconds)
- `tcpTransport.maxBytes`: maximum bytes to read for `STREAMING` (and max frame size for `REQUEST_RESPONSE` when using delimiter framing)
- `tcpTransport.keepAlive`: enable TCP keep-alive (socket transport only)
- `tcpTransport.tcpNoDelay`: enable `TCP_NODELAY` (socket transport only)
- `tcpTransport.connectionReuse`: `GLOBAL`, `PER_THREAD`, `NONE` (socket transport only)
- `tcpTransport.maxRetries`: retry attempts on TCP failures
- `tcpTransport.sslVerify`: verify remote certificate for `tcps://` (socket and netty)
  - for `netty`, `sslVerify=false` accepts self-signed certificates; `sslVerify=true` uses the default JVM trust store.

## Behaviors

The `behavior` field in the call envelope controls read semantics:

- `FIRE_FORGET`: write only; return empty body
- `ECHO`: read until at least the request payload length is received
- `STREAMING`: read up to `tcpTransport.maxBytes`
- `REQUEST_RESPONSE`: read until `endTag` delimiter is observed

# WebAuth Loop Redis Scenario (with Postprocessor Metrics)

Flow (closed loop):
- `webauth.TOP.custA` -> `webauth.RED.custA`
- `webauth.RED.custA` -> `webauth.BAL.custA`
- `webauth.BAL.custA` -> `webauth.TOP.custA`

## Worker count

This bundle keeps postprocessor metrics and uses:
- 1 x `generator` (Redis multi-list input)
- 1 x `request-builder`
- 1 x `processor`
- 1 x `postprocessor`

Total: **4 bees**.

## How it works

- Generator reads from three Redis lists via `inputs.redis.sources`.
- It preserves source list header (`x-ph-redis-list`) from Redis input.
- Request-builder uses one template (`callId=webauth-loop`) and maps source list to WebAuth request type.
- Processor calls WebAuth and forwards results to postprocessor via RabbitMQ.
- Postprocessor publishes metrics (`publishAllMetrics=true`) and routes original payload to next loop list using native Redis output (`outputs.redis.routes`) with header matching.

## Required dataset payload in Redis

Each list item should be JSON with:

```json
{
  "AccountNumber": "86010100418512",
  "Amount": "10"
}
```

## WebAuth properties (required in `vars.*`)

- `client`
- `sendMD5`
- `timestampMD5`
- `md5Mechanism`
- `md5Secret`
- `customerCode`
- `productClassCode`
- `origin`
- `currency`
- `timestampMode`

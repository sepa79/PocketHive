# WebAuth Loop Redis Scenario (5 Customers, Shared BAL/TOP)

Flow (closed loop):
- `webauth.RED.<customer>` -> `webauth.BAL.shared`
- `webauth.BAL.shared` -> `webauth.TOP.shared`
- `webauth.TOP.shared` -> `webauth.RED.<customer>`

Customers in this example:
- `custA`
- `custB`
- `custC`
- `custD`
- `custE`

## Worker count

- 2 x `generator`
- 1 x `request-builder`
- 1 x `processor`
- 1 x `postprocessor`

Total: **5 bees**.

## How it works

- Generator #1 reads only per-customer RED lists (`webauth.RED.custA..custE`) with `WEIGHTED_RANDOM`
  (weights: `custA=40`, `custB=25`, `custC=15`, `custD=12`, `custE=8`).
- Generator #2 reads shared BAL/TOP lists (`webauth.BAL.shared`, `webauth.TOP.shared`) with `ROUND_ROBIN`.
- Both generators publish to one shared pipeline (`request-builder` -> `processor` -> `postprocessor`).
- Postprocessor routes:
  - `RED.<customer>` -> `BAL.shared`
  - `BAL.shared` -> `TOP.shared`
  - `TOP.shared` -> `RED.<customer>` using `targetListTemplate: webauth.RED.{{ payloadAsJson.Customer }}`

## Per-customer WebAuth config

- Per-customer WebAuth profile is stored in `variables.yaml` under
  `values.sut.default.webauth-local.customers`.
- Template resolves profile with `vars.customers[payloadAsJson.Customer]`.
- No fallback/default chain is used.
- Swarm creation must include:
  - `variablesProfileId=default`
  - `sutId=webauth-local`

## Required dataset payload in Redis

Each list item should be JSON with:

```json
{
  "Customer": "custA",
  "AccountNumber": "86010100418512",
  "Amount": "10"
}
```

`Customer` must match one of the configured entries in `vars.customers`.

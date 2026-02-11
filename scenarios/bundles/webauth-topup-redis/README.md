# WebAuth Topup Redis Scenario

Flow:
- input list: `webauth.TOP.custA`
- callId: `webauth-topup`
- output list (after processing): `webauth.RED.custA`
- Redis write is done by native `processor` output (`outputs.type=REDIS`).

## Legacy connectionUrl mapping

Old tooling used:
`http://<host>:<port>/webauth/xmlauth?client=<id>`

In this scenario:
- `sut.endpoints.webauth.baseUrl` = `http://<host>:<port>`
- template `pathTemplate` adds `/webauth/xmlauth?client=...`

## Required dataset payload (Redis)

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

Other request fields (id, timestamp, XML body shape, hash calculation) are generated directly in template.

# WebAuth Redemption Redis Scenario

Flow:
- input list: `webauth.RED.custA`
- callId: `webauth-redemption`
- output list (after processing): `webauth.BAL.custA`
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
  "Amount": "0"
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

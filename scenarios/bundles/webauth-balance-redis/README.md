# WebAuth Balance Redis Scenario

> **Authoring example only:** do not run the checked-in bundle as a local
> customer scenario. Its `webauth-local` SUT points to
> `http://tcp-mock-server:8080`, while the template sends
> `GET /webauth/xmlauth`; the bundled mock has no matching route. Configure an
> explicit WebAuth-compatible SUT and requalify the bundle before creating a
> swarm.

Flow:
- input list: `webauth.BAL.custA`
- callId: `webauth-balance`
- output list (after processing): `webauth.TOP.custA`
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

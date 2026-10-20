# Template Capabilities (SSOT)

This document defines the single source of truth for template functions and editor hints.
It is shared by Web UI, VS Code, and any other clients.

## Goals
- One contract for template functions and editor metadata.
- Reusable across scenario fields and other template-backed surfaces (HTTP/TCP, etc).
- No duplicated validators/parsers in clients.

## API surface
`GET /api/template-capabilities`

Returns a list of template capability manifests. Clients cache and render editors based
on this response.

## Manifest shape (draft)
Each manifest is a JSON object:

- `schemaVersion` (string, required)
- `engine` (string, required) Example: `spel`, `pebble`, `mustache`
- `functions` (array, required)
  - `name` (string, required)
  - `description` (string, optional)
  - `returns` (string, required) Example: `string`, `number`, `json`
  - `args` (array, required)
    - `name` (string, required)
    - `type` (string, required) Example: `string`, `number`, `json`
    - `required` (boolean, optional)
    - `default` (any, optional)
    - `allowed` (array, optional) Allowed literal values
    - `validation` (object, optional) Client hints for range/pattern
  - `ui` (object, optional) Editor hints
    - `kind` (string, optional) Example: `weighted-list`
    - `label` (string, optional)
    - `help` (string, optional)
    - `group` (string, optional)
    - `examples` (array, optional)
  - `scopes` (array, optional) Where the function is valid
    - Example values: `headers`, `body`, `inputs`, `outputs`, `interceptors`

## Example
```json
{
  "schemaVersion": "1.0",
  "engine": "pebble",
  "functions": [
    {
      "name": "pickWeighted",
      "description": "Pick a value based on weights.",
      "returns": "string",
      "args": [
        { "name": "valueA", "type": "string", "required": true },
        { "name": "weightA", "type": "number", "required": true },
        { "name": "valueB", "type": "string", "required": true },
        { "name": "weightB", "type": "number", "required": true }
      ],
      "ui": {
        "kind": "weighted-list",
        "label": "Pick Weighted"
      },
      "scopes": ["headers", "body"]
    }
  ]
}
```

## Notes
- Clients should expose a "Template editor" button for fields containing `{{ ... }}`.
- The editor should be a reusable component that consumes this manifest.

## Composable EMV and MAC functions

These registered SpEL functions run through Pebble `{{ eval("…") }}` without
configuration. `#{ … }` is not a supported template delimiter.

| Function | Required arguments | Result |
| --- | --- | --- |
| `#emv_icc_mk_a(imkHex, pan, panSeqNo)` | 32 hex characters; 1–19 ASCII PAN digits including check digit; exactly two ASCII sequence digits | 32 uppercase hex characters: Option A ICC master key |
| `#emv_common_sk(iccMkHex, diversificationHex)` | 32 hex characters; exactly 16 hex characters (eight bytes of diversification data) | 32 uppercase hex characters: common session key |
| `#iso9797_alg3_mac_m2(keyHex, dataHex)` | 32 hex characters; any even-length hex string, including empty | 16 uppercase hex characters: eight-byte MAC |

All arguments are required strings. Hex accepts either case, without whitespace,
separators or `0x`. Leading PAN zeros are retained; no Luhn check is performed.
Null, malformed input or incorrect fixed lengths fail rendering. The boundary
validates and decodes once; byte-oriented operations consume validated inputs.
Diagnostics from these helpers identify the argument/rule without supplied values.
All operations are deterministic and use per-call cipher/MAC state.

### Algorithms

- **ICC master key:** EMV Book 2 Annex A1.4.1 Option A. Take the rightmost
  16 digits of `PAN || PSN`, left-pad with zeros to 16 digits, decode to `Y`,
  and derive `TDES(IMK, Y) || TDES(IMK, Y XOR FFFFFFFFFFFFFFFF)`. Set odd DES
  parity. This explicitly named Option A operation never selects Option B,
  including for PANs longer than 16 digits.
- **Common session key:** Annex A1.3.1. Copy the eight-byte diversification
  value twice; replace byte 2 (zero-based) with `F0` and `0F`, respectively.
  Encrypt both blocks with the ICC key, concatenate, and set odd DES parity.
  The caller supplies the entire diversification value; this function does
  not interpret ATC, UN or a card profile.
- **MAC:** ISO/IEC 9797-1 Algorithm 3, double-length TDES key, zero IV, eight-byte
  output, padding method 2. Append `80`, then zeros to the next eight-byte
  boundary. Empty and already-aligned data receive a whole padding block.
  This generic MAC does not parse TLV or enforce an EMV field layout.

Bouncy Castle implements the cipher, parity and MAC primitives. PocketHive owns
argument mapping and the composition of the documented derivation steps.

### Generic template examples

Use the existing `#json_path(payload, '/field')` helper to read raw JSON payloads
inside SpEL. Compute a MAC over caller-supplied, unpadded hex data with a supplied
key:

```yaml
bodyTemplate: |
  {{ eval("#iso9797_alg3_mac_m2(vars.macKeyHex, #json_path(payload, '/dataHex'))") }}
```

To compose all three operations, supply PAN, sequence number, the full eight-byte
diversification value and unpadded message data:

```yaml
bodyTemplate: |
  {{ eval("#iso9797_alg3_mac_m2(#emv_common_sk(#emv_icc_mk_a(vars.imkHex, #json_path(payload, '/pan'), #json_path(payload, '/panSeqNo')), #json_path(payload, '/diversificationHex')), #json_path(payload, '/dataHex'))") }}
```

Both examples return only the MAC as 16 uppercase hex characters. The caller owns
the data layout, diversification construction and any output framing. No
transaction profile or tag sequence is prescribed by these examples.

Supply test keys through context rather than expression source: existing template
diagnostics can include source snippets. Derived keys returned as strings are
sensitive too; do not render them into messages or logs. The nested example
passes intermediate keys directly to the next function.

### Scope and verification

This addition exposes only the three functions above; it does not register
`#emv_arqc(...)`. Existing template syntax, context mapping and functions retain
their existing behavior. The new primitives do not own transaction-specific
length or ATC/UN equality checks.

- EMV 4.3 Book 2, section 8.1 and Annex A1.2, A1.3.1, A1.4.1.
- [IBM session-key schemes](https://www.ibm.com/docs/en/zos/3.1.0?topic=keys-derive-session-key-csnbdsk-csnedsk).
- Independent vectors: [PyEMV 1.5.0](https://github.com/knovichikhin/pyemv),
  `kd.derive_icc_mk_a`, `kd.derive_common_sk`, and
  `ac.generate_ac(..., PaddingType.EMV)`.
- Each byte operation has its own known-answer tests. `CryptoSpelFunctionsTest`
  checks boundary failures; `EmvCryptoRenderingTest` and
  `EmvCryptoMessageTemplateTest` check real rendering and WorkItem JSON input.

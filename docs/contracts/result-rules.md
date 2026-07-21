# Result Rules (Processor Result Extraction)

`resultRules` is an optional contract that lets templates declare how to extract:

- **business code** (for example: `"00"`, `"OK"`, `"ERR42"`)
- **business success** (regex evaluated against the extracted business code)
- **custom dimensions** (arbitrary extracted values)

The rules are forwarded from `request-builder-service` to `processor-service` inside the request envelope and are evaluated by the processor. Extracted values are emitted as **processor step headers** (so they are visible in the run history and can be consumed by UI/metrics).

## Where It Lives (SSOT)

Canonical DTO: `io.pockethive.swarm.model.ResultRules`

Canonical outcome header names: `io.pockethive.swarm.model.OutcomeHeaders`

Forwarded via request envelopes:

- `io.pockethive.worker.sdk.api.HttpRequestEnvelope.resultRules`
- `io.pockethive.worker.sdk.api.TcpRequestEnvelope.resultRules`
- `io.pockethive.worker.sdk.api.Iso8583RequestEnvelope.resultRules`

## Contract Shape

```json
{
  "resultRules": {
    "businessCode": {
      "source": "RESPONSE_BODY",
      "pattern": "RC=([A-Z0-9]+)",
      "header": "X-Foo" 
    },
    "successRegex": "^(00)$",
    "dimensions": [
      {
        "name": "segment",
        "source": "REQUEST_HEADER",
        "pattern": "(.+)",
        "header": "X-Segment"
      }
    ]
  }
}
```

### `source` enum

- `REQUEST_BODY`
- `RESPONSE_BODY`
- `REQUEST_HEADER`
- `RESPONSE_HEADER`

For `*_HEADER` sources, `header` is required and is matched case-insensitively against the header map keys.

### Regex semantics

- `pattern` is a Java regex.
- The extractor returns **capture group 1** from the first match.
- If there is no match, or the pattern has no capture groups, the value is treated as missing.

## Output (Step Headers)

When extraction succeeds, the processor adds these step headers:

- `x-ph-business-code`: extracted business code (string)
- `x-ph-business-success`: `"true"` or `"false"` (string), only when `businessCode` and `successRegex` are both present
- `x-ph-dim-<name>`: extracted dimension values (string)

Dimension header name is normalized:

- lowercased
- characters outside `[a-z0-9_-]` are replaced with `-`

## Failure Behavior (Intentional)

- Envelopes are deserialized with `FAIL_ON_UNKNOWN_PROPERTIES`.
- Invalid envelope shape, unknown fields, or invalid regex patterns are **fail-loud** and fail the processor call.
- Regex patterns are compiled and cached (LRU) to avoid recompilation per call.

## Protocol Notes

- HTTP:
  - `REQUEST_BODY` is the rendered request body (string form)
  - `RESPONSE_BODY` is the raw response body (string)
  - `*_HEADER` values come from the request/response header maps
- TCP:
  - `REQUEST_BODY` is the rendered request body (string)
  - `RESPONSE_BODY` is the raw TCP response body (string)
- ISO8583:
  - `REQUEST_BODY` and `RESPONSE_BODY` are **hex strings** (payload/response)

## Managed Dataset producer integration status

The current evaluator is invoked by `processor-service`; it is not yet a shared
source/sequence-worker classifier. In particular, `http-sequence-service`
currently determines call error from HTTP status and does not apply
`resultRules` as an authoritative business-completion decision.

The proposed Managed Dataset design requires a contract-first evolution:

1. move the evaluator behind one framework-free Worker SDK port used by both
   processor and source/sequence workers;
2. retain the existing bounded `RESPONSE_BODY` extraction for declared text
   TCP protocols and require explicit payload decoders for binary TCP;
3. add a versioned typed JSON Pointer extractor for structured HTTP responses;
4. feed the extracted business code into `SourceResultPolicy/v1`, which owns
   closed completion/wrong-state/pending/invalid/uncertain classification and
   exhaustive authorised Dataset routing; and
5. keep Dataset output/interceptor adapters free of raw protocol parsing.

`RESPONSE_JSON_POINTER` and `SourceResultPolicy/v1` are target contracts, not
implemented values in the current DTO. Their normative proposal is in
`docs/inProgress/managed-test-data-lifecycle-generic-spec.md`; implementation
must update this contract, the canonical DTO/schema and cross-protocol contract
tests together before enabling the capability.

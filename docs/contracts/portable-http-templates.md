# Portable HTTP templates — portable-v1

Human-approved on 2026-10-20 and implemented in the current shared PH/PD source.
This contract covers one request and its response interpretation, not a shared
workflow runtime. Current source and component tests do not certify deployment.
Existing PH template entrypoints and `payload` / `payloadAsJson` views remain.
WorkItem payloads remain opaque at the envelope/transport boundary. Issue #529
and PR #528 crypto changes are separate work.

## Authored call and binding

An authored call has exactly `profile`, `requires`, `request`, `response`:

```json
{
  "profile": "portable-v1",
  "requires": ["xml_path", "xml_text", "soap11"],
  "request": {
    "serviceId": "cards", "callId": "create", "protocol": "HTTP",
    "method": "POST", "pathTemplate": "{{ endpoint.path }}",
    "headersTemplate": {},
    "bodyTemplate": "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><card>{{ xml_text(record.customerId) }}</card></s:Body></s:Envelope>"
  },
  "response": {
    "kind": "SOAP11", "soapAction": "urn:create-card", "expectedStatus": 200,
    "extract": {"cardNumber": "/soap:Envelope/soap:Body/c:created/c:cardNumber"},
    "namespaces": {"c": "urn:cards"}
  }
}
```

The caller supplies one explicit endpoint URI, `record` data and independent
`params`. The renderer exposes only `record`, `params`, and `endpoint.path` in
this profile. Extra record fields survive. Actual supplied JSON types remain
types; string identifiers are never guessed numeric. There is no alias mapping
between record and params, `vars` or payload. No format detection or profile
downgrade occurs. A missing field or unsupported profile/capability fails.

The request uses the existing canonical HTTP definition/parser. Portable calls
require literal HTTP method, explicit body/header templates and no authRef or
resultRules. The admitted methods are POST, PUT, PATCH, GET and DELETE; SOAP
requires POST. At most 30 explicitly named headers are accepted, with unique
case-insensitive names and no transport-owned fields. Rendered header values are
limited to 8,192 UTF-16 code units and reject control characters except tab.
Rendered paths must be origin-relative, without authority, backslashes or
fragments. HTTPS uses the runtime's normal TLS policy. Credential binding,
network execution and retries are not library responsibilities.

## Functions and capabilities

Supported capability names are `json_path`, `json_string`, `xml_path`, `xml_text`, `xml_attr`,
`soap11`, `soap12`. Unknown/duplicate capabilities fail before execution.
`requires` is nonempty. Only its explicitly declared data functions are enabled;
SOAP capabilities select protocol semantics, not callable template functions.
Actual Pebble compilation checks syntax/functions. No regex scanner, eval
rewriting or heuristic downgrade occurs. The portable engine is strict,
inline-only, and limits both each template and its rendered output to 65,536
UTF-16 code units, not UTF-8 bytes. It does not register eval/SpEL/sequence.
Existing PH SpEL remains a separate native adapter.

Version 1 admits text and output expressions containing string, numeric,
boolean or null literals; map/array literals; references rooted only at `record`,
`params` or `endpoint`, with dot/bracket access; and declared function calls,
including nested calls. Function arguments may be positional or named, but every
declared argument must be supplied exactly once. Method-call access is rejected.
Loops, conditionals, assignments, blocks/inheritance, imports/includes, macros,
operators and filters are not part of this profile. Admission uses a Pebble
compiler AST visitor, not a source parser/token scanner or trial rendering.
Unknown functions in any position fail before HTTP effects; product loops remain
outside the template. Data-dependent binding/rendering failures occur before
the affected request, not by executing a trial request.

- `json_path(source, pointer)` explicitly interprets JSON; RFC6901 only, including
  strict `~0`/`~1` escaping. Exactly one non-null scalar is required. Empty string,
  whitespace and leading zeroes are real values; missing/null/malformed are not.
  String sources are documents; Jackson trees, maps, lists and scalar data retain
  their explicit types. There is no JSONPath dialect or format detection.
- `json_string(string)` returns one fully quoted/escaped JSON string, so dynamic
  strings cannot inject new JSON structure. It does not guess or coerce types.
- `xml_path(source, xpath, namespaces)` explicitly interprets XML, using namespace
  URIs, not particular source prefixes. Exactly one scalar node is required;
  extension functions, variables, DTD, external resources and XInclude are rejected.
- `xml_text(string)` and `xml_attr(string)` escape XML text/attributes, validate
  XML 1.0 characters and reject other input types. JSON escaping is explicit via
  normal JSON authoring/functions, not automatic XML/JSON conversion.

Generic document limits are 1 MiB of UTF-8, XML depth 256. JSON rejects duplicate
keys at every nesting level and trailing documents/tokens, while allowing trailing
whitespace. `JsonDocumentReader.read(byte[])` checks the byte budget before strict
UTF-8 decoding with malformed/unmappable input reported, never replaced. Invalid
UTF-8 is `INVALID_DOCUMENT`; an exceeded byte budget is `LIMIT_EXCEEDED`. A caller
requiring byte-level validation must supply bytes rather than a replacement-decoded
String. `readObject(String)` additionally requires a complete JSON object.

JSON text decimals are exact `BigDecimal` values, retaining scale and avoiding
floating-point overflow/underflow, including `1e999` and `1e-999`. Parsed trees
receive the same validation as supplied trees/maps/lists; finite native numeric
types are retained, and explicit Float/Double NaN or infinities are `INVALID_VALUE`.
Non-JSON tokens such as bare NaN remain invalid documents, not numeric values.
Required scalar selection uses the node's text representation, not the original
numeric token spelling; string identifiers and their leading zeroes remain intact.

One `JsonStringValidation` owner rejects unpaired UTF-16 surrogates in document
text, decoded JSON keys/values and supplied tree/map/list string data, including
record/params bindings. `JsonEscaping` reuses that validation before encoding.
These failures are `INVALID_VALUE`; valid supplementary characters such as emoji,
empty strings and whitespace survive. This is JSON string validation, not XML 1.0
character validation. Other categorized failures are `INVALID_SELECTOR` and
`MISSING_VALUE`; parser causes and source values are not exposed.

Named optional JSON views retain their explicit null/original-text behavior for
invalid documents but delegate to the same strict reader. They cannot first
discard trailing data and pass a misleading valid map onward. There is no eager
payloadAsXML projection.

## Existing native interfaces

Portable bindings do not replace native contexts. SDK message/Redis contexts keep
raw `payload` and optional `payloadAsJson` (object, array or scalar). Invocation
templating keeps object-or-original-text `payload`, with JSON object fields
accessed through map keys, without adding a `payloadAsJson` alias there. Existing `headers`, `vars`
and `workItem` bindings remain. Clearing keeps raw `steps.*.payload`, per-step
headers and optional object-only `steps.*.json`, with its existing history aliases.
Native HTTP Sequence keeps its journey-map `payload` / `payloadAsJson` / `ctx`
bindings, native extract/set behavior and retry configuration. Its SCHEDULER
producer explicitly supplies an empty JSON object (`{}`) as the initial journey;
message inputs still require valid object JSON. These are distinct
named projections, not portable aliases or competing document decoders. Their
names/shapes remain; shared strict document rules do not promise the old lenient
handling of malformed, duplicate-key or trailing JSON.

## Response and ownership

`response` has exactly kind (JSON/SOAP11/SOAP12), soapAction (empty for JSON),
expectedStatus (explicit 2xx), extract (output name → pointer/XPath), namespaces.
Extraction is nonempty, with at most 200 output fields; names match
`[A-Za-z][A-Za-z0-9_]{0,79}`, and selectors are limited to 2,048 UTF-16 code units.
At most 30 namespace bindings are accepted. JSON requires json_path and empty
SOAP action/namespaces; SOAP requires xml_path and its version capability. One
narrow SOAP owner controls Envelope/Body/version/Fault checks and Content-Type/action.
The `soap` XPath prefix is reserved and bound to the selected version. Faults fail
even with HTTP 200. Applications retain their own outcome/retry decisions.

Current source owners are RESP-TEMPLATE-DATA, RESP-TEMPLATE-RENDER,
RESP-PORTABLE-HTTP and RESP-TEMPLATE-SOAP in the existing architecture responsibility
records. SDK context, clearing projection and PH portable adaptation retain their
own records; these consumers do not acquire document or engine ownership. Template-data has
no WorkItem, SDK, Pebble, Spring or transport dependency. Request contracts and
response interpretation stay independent from engine rendering.

PD steps bind a named SUT endpoint to an authored call; its finite loop, Stop,
audit CSV before extraction/next request, final CSV before Redis and no automatic
retry/resume remain product-owned. PH owns its WorkItem/state/lifecycle and uses
the same call for portable HTTP steps. No record checkout/recovery machinery.

## Acceptance

### Reusing a call

In PD, author a recipe with values/params/steps and place this object under a
step's `call`; the step's endpoint key selects one endpoint of its selected SUT.
In PH, save exactly that call object as a JSON template file under templateRoot.
Select the existing HTTP Sequence step by its inner request serviceId/callId;
the existing baseUrl/SUT endpoint resolver supplies the URI, the current journey
object supplies record, and config.vars supplies params. Do not put CSV/Redis/job
settings into the call. Portable steps require maxAttempts=1,
continueOnNon2xx=false and no native extracts; response.extract owns selection.
Scenario Manager validates portable syntax with the same bounded compiler used
by execution and delegates these step constraints to the shared HTTP Sequence
policy. Calls that fail either admission are reported as template errors and are
not offered as available scenarios. Request Builder's native adapter does not
support portable calls; a bundle referencing one through that adapter fails
admission rather than advertising an unexecutable scenario. Unreferenced portable
calls may share a root with native templates. Native templates retain their native engine.

Known authoring/runtime admission gaps remain tracked in
[#533](https://github.com/sepa79/PocketHive/issues/533): numeric step identifiers can
skip the authoring policy check, and trailing JSON can pass bundle validation while
the runtime loader rejects it. The same issue records overbroad reference and
variable scanning. These cases are deferred to the SUT work; the shared compiler
and policy do not establish complete equivalence between bundle admission and execution.

The authored shared examples are packaged at `/portable/examples/account-json.json`,
`card-soap11.json`, `card-soap12.json` in request-templates. Both product adapter
tests consume those same resources, not independently rewritten templates.

### Verification gates

Source/unit/component and isolated transport results are separate from deployed
PH/PD qualification. A green local suite is not evidence of live ingress, runtime
configuration or operational-data effects.

Use the same authored call and record/params/endpoint through real PH and PD
adapters: identical URI/method/body/headers, extracted values and error categories.
Cover XML/JSON, namespaces/prefix changes, Fault at HTTP200, missing/multiple/null,
empty/whitespace/zeros, strict documents/pointers, escaping, XML limits and no
effects for unsupported capabilities. Preserve current PH template regressions
and PD durability/Stop/authorization tests. Unit/component evidence is not proof
of a deployed mock-SOAP ingress; report that qualification separately.

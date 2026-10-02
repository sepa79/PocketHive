# Processor ISO8583 - plan V1 + zarys V2

Status: in progress

## Cel

Rozszerzyc `processor-service`, aby obslugiwal ISO8583 bez trzymania definicji schematow (MC/VISA itp.) w repo PocketHive.

## Tracking

- [x] Utworzyc branch roboczy pod ISO8583 plan/implementacje.
- [x] Zaimplementowac V1.1 (nowy envelope `iso8583.request` + routing w Processorze).
- [x] Zaimplementowac V1.2 (`Iso8583ProtocolHandler`, framing, result envelope).
- [x] Zaimplementowac V1.3 (schema pack registry dla adapterow polowych).
- [x] Przeniesc kompilacje `FIELD_LIST_XML` do `request-builder-service` (template protocol `ISO8583`).
- [x] Uproscic `processor-service` do trybu transportowego `RAW_HEX` (bez parserow schemy).
- [ ] Dodac testy V1.4 (unit + integracyjne + negatywne).
- [ ] Uzupelnic dokumentacje uzycia (przyklady configu i payloadow).
- [ ] Przygotowac PR dla V1.
- [ ] Rozpoczac V2 (server MC) w osobnym PR.

## Ograniczenia (must-have)

- NFF: brak fallback chain i brak auto-przelaczania adapterow.
- Jawny wybor adaptera i profilu wire w config.
- Definicje pol/schematow poza PH (`schema pack` montowany jako pliki).
- Brak zmian w kontraktach control-plane.

## V1 (scope implementacyjny)

V1 to tryb **klienta ISO8583** (Processor laczy sie do zewnetrznego hosta i wysyla wiadomosci z kolejki).

### V1.1 Kontrakt i routing

- [x] Dodac nowy envelope `kind: iso8583.request` (nie przeciazac `tcp.request`).
- [x] Dodac wynik `kind: iso8583.result`.
- [x] `ProcessorWorkerImpl` rozszerzyc o `Iso8583ProtocolHandler`.

Proponowane pola `iso8583.request` (na wejsciu do Processora):

- `operation`: `SEND`
- `target`: `host`, `port`, `tls`, `timeoutMs`
- `wireProfileId`: np. `MC_2BYTE_LEN_BIN_BITMAP`
- `payloadAdapter`: `RAW_HEX`
- `payload`: hex gotowej wiadomosci ISO

### V1.2 Runtime i transport

- [ ] Reuzyc istniejace zarzadzanie transportem TCP (pool/retry/ssl) tam, gdzie to sensowne.
- [x] Dodac jawny codec ISO8583 po stronie handlera:
  - framing po `wireProfileId` (np. 2-byte length),
  - mapowanie request->bytes,
  - mapowanie response bytes->`iso8583.result`.
- [x] Brak domyslowego profilu: niepodany `wireProfileId` => blad.

### V1.3 Schema pack (zewnetrzny)

- [x] Wzorzec jak w `clearing-export`: loader z filesystemu, cache po `schemaId:schemaVersion:root`.
- [x] Lookup tylko z podanego `schemaRegistryRoot/schemaId/schemaVersion`.
- [x] Brak pliku => hard fail.
- [x] W repo PH tylko interfejs loadera i kontrakt metadanych; bez plikow MC/VISA.
- [x] Adapter `FIELD_LIST_XML` + `schemaRef` (`schemaAdapter=J8583_XML`) zaimplementowany w `request-builder-service` i kompilowany do `RAW_HEX`.

### V1.4 Testy

- [x] Unit: DTO + codec/framing + walidacja adapter/profile.
- [x] Unit: loader schema pack.
- [ ] Integracyjne: end-to-end Processor -> `mcsim` (jako zewnetrzny host) dla `SEND`.
- [ ] Negatywne: brak schema pack.
- [x] Negatywne: nieznany `wireProfileId` i nieznany adapter.

### V1 Definition of Done

- [ ] Processor wysyla ISO8583 do hosta i zwraca `iso8583.result`.
- [ ] Konfiguracja i adaptery sa jawne (zero fallbackow).
- [ ] Schema definition nie jest przechowywana natywnie w PocketHive.

## V2 (zarys historyczny)

V2 to tryb **server MC w Processorze** (SUT laczy sie do workera PH).

### Zakres V2

- [ ] Worker utrzymuje listener ISO8583 (port bind).
- [ ] Rejestr aktywnych polaczen SUT.
- [ ] `onMessage` wysyla wiadomosc do aktywnego polaczenia.
- [ ] Gdy brak polaczenia:
  - minimalny wariant: `onMessage` czeka (blokuje) do czasu pojawienia sie polaczenia.

### Dalsze rozszerzenia V2.x

- [ ] polityka wyboru sesji (`ROUND_ROBIN`, `BY_CONNECTION_KEY`),
- [ ] bootstrap/handshake po polaczeniu,
- [ ] asynchroniczny odbior wiadomosci z SUT i publikacja do queue,
- [ ] korelacja po STAN/RRN.

## Kolejnosc realizacji

1. [x] V1.1 + V1.2 (`RAW_HEX`, klient ISO, wynik `iso8583.result`).
2. [x] V1.3 (schema pack registry + adapter `FIELD_LIST_XML` w request-builder -> `RAW_HEX`).
3. [ ] V1.4 (testy + dokumentacja uzycia).
4. [ ] V2 (server mode, osobny PR po domknieciu V1).


## V2 MIP implementation slice (2026-10-02)

Reviewed base: `01e80a45`, branch `codex/mip-server-layouts`.
The requested first slice supports only `0100`, `0110`, `0800`, `0810`.
It emulates the TCP-facing MIP role for a Pacasso test host; it does not claim
Mastercard certification or provide a current proprietary CIS pack.

The explicit `baseUrl: mip://<bind-host>:<port>` selects the accepted-connection
adapter. Existing `tcp://` and `tcps://` select the outbound ISO client.
No protocol detection, adapter fallback, retry of an uncertain authorization,
or modification of shared Work/control-plane envelopes is included.

Ownership transfer: extract the builder's schema pack resolver and field-list
codec into `common/iso8583-codec`, removing the service-local implementations.
Both builder encoding and MIP decoding/response encoding use this single owner.
J8583 owns parse guides, including `extends`; no supplementary competing guide
parser remains. Packs stay external and must declare all four MTIs explicitly
or through supported J8583 inheritance. Repository examples use synthetic fields.

MIP composition starts the listener from the bound worker configuration before
Work intake. `privateConfig.mipServer` declares the external `schemaRef`, a
positive `maxPending`, and an explicit network-management response layout.
The URI alone owns the bind address. One accepted connection is supported;
a second simultaneous peer is rejected. Changed listener/schema/layout settings
immediately close and retire the old listener through the accepted-config observer;
restarting MIP requires a worker restart. Enabling MIP after a configured client
also requires restart. An explicit switch to an existing HTTP/TCP client target
retires MIP and permits that client's existing path, without automatically falling
back from a failed MIP request. Timeout and pacing may change live. Disabling Work intake retains network management;
shutdown closes the listener, peer and pending calls.

A dedicated session owner sends already packed `0100` or `0800` and completes
only a matching `0110` or `0810` on the same connection, correlated by DE11.
Both responses require DE39 and `0810` must match the request DE70.
Each request STAN may be used once per connection, so late/duplicate replies
cannot settle a newer request. Waiting for a peer, writing and response waiting
share one timeout; interruption, disconnect, write failure and capacity exhaustion
fail explicitly. Netty listeners frame and dispatch without waiting for Work calls.
Unsolicited replies never count as a successful exchange.

Incoming `0800` is independently handled on the same connection even while
an authorization is pending. The external layout declares supported DE70 codes,
required fields to copy, and static response fields; `0810` construction goes
through the shared codec. The synthetic example uses CIS echo `270` and host
activation/deactivation `081`/`082`, copies DE7/11/70 and sets DE39=`00`.
Production field selection must follow the company's actual Pacasso/CIS pack.
Unsupported types/codes or malformed frames fail the peer explicitly.
There is no inferred sign-on protocol or automatic periodic outbound echo;
explicit `0800` Work requests can initiate network management.

Decoded ISO response fields are supplied to the existing ResultRulesExtractor
as response-header values named `iso8583.de.<number>` (for example DE39), with
`iso8583.mti` identifying the response. The existing result envelope retains
raw response hex. ResultRules alone decides business success from the selected
fields; a correlated transport response is not automatically a business approval.

Acceptance: synthetic pack encoding/decoding including inherited MTIs; exact
2-byte big-endian framing and fragmented/coalesced reads; echo interleaved with
an authorization; matching both response families; mismatched/duplicate/late
replies; timeout/no peer, write failure, disconnect/reconnect, interruption,
capacity and repeated-STAN failure; configuration errors and existing builder/
client regressions. Unit ports and Netty EmbeddedChannel avoid direct service
port tests. Actual Pacasso and deployed ingress acceptance remain external checks.


### MIP slice verification (2026-10-02)

Implementation is complete on `codex/mip-server-layouts` from `01e80a45`; changes
remain uncommitted. Independent six-pass review found no remaining blockers.
The broader historical V1/V2 checklist above is not a claim of deployed acceptance.

- Targeted Maven reactor: 107 tests, zero failures/errors (12 codec, 3 repository
  import boundary, 78 processor, 14 builder). Includes required guide/layout
  validation and fail-closed retirement after malformed configuration changes.
- One additional example-layout test passes through the canonical template parser,
  renderer and codec for both documented templates: 108 total passing tests.
- Processor and Request Builder packaging succeeds. Processor's executable archive
  contains the shared codec and existing J8583 dependency.
- An external temporary copy of the original December-2025 mcsim XML, augmented
  explicitly with `<parse type="0810" extends="0800"/>`, passes all four MTI
  encode/decode roundtrips using synthetic fields. Original files are unchanged.
- Tests use port doubles and EmbeddedChannel; no direct service-port test or
  Pacasso connection was performed. Real Pacasso wire acceptance, current CIS
  conformance, MAC packing and deployment port exposure remain external checks.

Verification commands:

```bash
mvn -B -pl common/iso8583-codec,processor-service,request-builder-service,common/control-plane-core -am \
  -Dtest='Iso8583CodecTest,Mip*Test,Iso8583*Test,RequestBuilderWorkerImplTest,RepositoryImportBoundaryTest,ProcessorTest#workerAppliesIso8583MacAuthBeforeFraming+workerRejectsIso8583RawHexPayloadContainingWhitespace+workerRejectsUnsupportedIso8583PayloadAdapter+workerRejectsUnknownIso8583WireProfile' \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -pl request-builder-service -am -Dtest=Iso8583ExampleLayoutTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -pl processor-service,request-builder-service -am -DskipTests package
```

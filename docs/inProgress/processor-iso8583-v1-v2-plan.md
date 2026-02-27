# Processor ISO8583 - plan V1 + zarys V2

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

## V2 (zarys)

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

# ScenarioControllerTest — raport 26 niepowodzeń

Status: raport przyjęty; użytkownik zatwierdził opisany zakres naprawy 2026-09-14. Poprawki wdrożone, pełny reactor przeszedł.
Poniższe ustalenia opisują stan przed poprawkami. Zakres: klasa ScenarioControllerTest i rzeczywista
ścieżka walidacji Work, bez refaktoru SM S1–S10 i bez zmian semantyki transportu.

## Wynik

26 niepowodzeń nie oznacza 26 niezależnych usterek produkcji. Podział wszystkich przypadków:

| Grupa | Liczba | Przyczyna |
| --- | ---: | --- |
| A | 17 | Niepełne dane I/O w fixture testującej inną odpowiedzialność; dodatkowe błędy przesuwają findings lub zmieniają `ok` |
| B | 6 | Prawidłowe odrzucenie konfiguracji, ale inny tekst lub ścieżka diagnostyki wspólnego parsera niż w starych asercjach SM |
| C | 2 | Jednocześnie brak I/O w fixture i nieaktualne oczekiwanie diagnostyki |
| D | 1 | Nieaktualne oczekiwanie oraz potwierdzony nadmiarowy komunikat produkcyjnego parsera AUTHORING |

W tym zbiorze nie potwierdzono regresji dopuszczania poprawnych, kompletnych konfiguracji,
obsługi pustych credentials DB, rozwiązywania opcji szablonowych ani wyboru katalogu templates.
Nie jest to deklaracja poprawności całego SM: badano konkretne niepowodzenia, a nie wszystkie
kontrakty serwisu. Znane S1–S10 pozostają poza zakresem.

## A — niekompletne fixture, 17 przypadków

`ScenarioBundleValidator.validateScenarioConfigShape` wywołuje dla każdej bee
`WorkConfigurationFindings.validate` → wstrzyknięty `WorkConfigurationParser` w AUTHORING.
Parser wymaga obiektów `inputs` i `outputs` oraz jawnych selektorów. Niepełna konfiguracja
użyta jako tło testu innej funkcji daje więc dodatkowe findings.

Przykłady potwierdzone pełnymi odpowiedziami endpointów:

- `bundleValidationAcceptsDbQueryBlankCredentialsWhenExplicit`: oba findings dotyczą wyłącznie
  brakujących `inputs`/`outputs`. Nie ma błędu pustego username/password.
- `bundleValidationAllowsTemplatedCapabilityOption`: oba findings dotyczą brakującego I/O;
  nie ma odrzucenia `txOutcomeSinkMode` ani wyrażenia.
- Testy brakującego/duplikowanego callId nadal zwracają oczekiwany błąd templates, poprzedzony
  czterema błędami I/O dla dwóch bees. Testy katalogu templates oczekujące `ok=true` zwracają
  tylko te cztery błędy I/O.
- `bundleValidationDoesNotDuplicateNumericRangeFindingAfterTypeMismatch`: właściwy błąd
  `rateValue` jest jeden. Pozostałe dwa findings to I/O, nie zdublowana walidacja zakresu.
- Test generatora bez bodyType dodatkowo pomija scheduler `ratePerSec`, `maxMessages` i outputs.

Naprawa do wykonania po raporcie: kompletna, jawna konfiguracja tła dla konkretnej fixture,
bez automatycznego wstrzykiwania defaults do wszystkich scenariuszy testowych. Pozostawić
celowo błędne pole testowanego przypadku i sprawdzać jego diagnostykę.

## B/C — diagnostyka kanonicznego parsera

Wspólny parser odrzuca konfiguracje, które te testy mają odrzucać, ale nie powtarza dawnego
tekstu walidatora capability/SM:

- Brak całego obiektu: `config.inputs` / `Settings must be an object.` zamiast `config.inputs.type`.
- Brak selektora: `Type must be configured as nonblank text.`; nie zgaduje typu z obecnego subbloku.
- Nieznany selektor: `Unsupported type.` bez dawnej listy opcji.
- Ujemne Redis rate: komunikat właściciela `ratePerSec must be a finite number >= 0.0.`
  zamiast komunikatu capability range `expected >= 0`.
- Brak scheduler maxMessages: komunikat parsera liczby całkowitej zamiast `missing required field`.
- Obcy subblok: błąd wskazuje ten subblok, zamiast sugerować przełączenie selektora na jego typ.

Grupa C dodatkowo nie deklaruje outputs. Dostosowanie asercji musi zachować kontrolę
konkretnego odrzucenia i sens testu. Nie usuwać testów ani nie zmieniać ich na samo `ok=false`.
Nie przywracać lokalnego parsera SM, aby odtworzyć poprzedni tekst. Szczegółowość komunikatów
można poprawiać u właściciela; ich ogólniejszy tekst sam nie dowodzi zmiany akceptacji danych.

## D — rzeczywisty błąd diagnostyki AUTHORING

`WorkConfigurationParser.requireSelectedBlock`, linie 143–153, uzależnia dopuszczenie pominiętego
bloku AUTHORING od braku obcych kluczy. Dwie próby na rzeczywistych parserach Rabbit:

```text
inputs: {type: RABBITMQ}, outputs: {type: RABBITMQ}
  → brak błędów

inputs: {type: RABBITMQ, redis: {listName: ph:dataset}}, outputs: {type: RABBITMQ}
  → inputs.redis: Unsupported or unselected settings block.
  → inputs.rabbit: Selected settings block is required.
```

Pierwszy błąd drugiego wejścia jest właściwy. Drugi błędnie przedstawia pominięty blok Rabbit
jako obowiązkowy, choć AUTHORING akceptuje jego brak. To nie powoduje dopuszczenia błędnego
wejścia ani odrzucenia poprawnego: dodaje mylący komunikat do już odrzucanego wejścia.

Proponowana poprawka jest lokalna u właściciela outer I/O: zachować odrzucenie obcych bloków
i nie dopisywać sprzecznego wymogu AUTHORING. Zachować wymagany blok w RESOLVED. Test
`bundleValidationRejectsInputIoSubblockWithMismatchedSelector` powinien nadal sprawdzać
odrzucenie obcego `inputs.redis`, bez sugerowania zmiany jawnie wybranego adaptera.

## Mapa wszystkich niepowodzeń

| Test | Grupa | Konkretna różnica |
| --- | --- | --- |
| bundleValidationRejectsShellTriggerWithoutExplicitCommand | A | command + dwa brakujące obiekty I/O |
| templateValidationClassifiesRequestTemplateConsumerByImage | A | oczekiwany TEMPLATE_CALL_ID_MISSING + cztery błędy I/O |
| bundleValidationRejectsImageCapabilityConfigTypeMismatches | A | pięć oczekiwanych błędów typów + dwa I/O |
| templateValidationReportsMissingCallId | A | oczekiwany TEMPLATE_CALL_ID_MISSING + cztery I/O |
| bundleValidationRejectsUnknownOutputSelectorOption | B | jedno odrzucenie outputs.type, inny tekst |
| existingBundleValidationReportsDefunctFindingOnce | A | jeden CAPABILITY_MANIFEST_MISSING + dwa I/O |
| templateValidationUsesWorkerTemplateRootForTcpTemplates | A | tylko cztery błędy I/O |
| bundleValidationAllowsTemplatedCapabilityOption | A | tylko dwa błędy I/O |
| bundleValidationRejectsProcessorWithoutExplicitIoSelectors | B | dwa odrzucenia całych obiektów zamiast ścieżek `.type` |
| bundleValidationRejectsRequestBuilderWithoutExplicitTemplateSettings | A | trzy oczekiwane brakujące pola + dwa I/O |
| bundleValidationRejectsInputIoSubblockWithMismatchedSelector | D | obcy inputs.redis + nadmiarowy wymóg inputs.rabbit |
| bundleValidationRejectsHttpSequenceWithoutExplicitRuntimeConfig | A | pięć oczekiwanych brakujących pól + dwa I/O |
| templateValidationIgnoresDuplicateCallIdOutsideWorkerTemplateRoot | A | tylko cztery błędy I/O |
| bundleValidationRejectsOutputIoSubblockWithoutExplicitSelector | B | jedno odrzucenie outputs.type, inny tekst |
| bundleValidationRejectsSelectedIoMissingRequiredManifestConfig | C | właściwy maxMessages ma inny tekst; dodatkowo brak outputs |
| templateValidationReportsDuplicateVisibleTemplateKey | A | oczekiwany TEMPLATE_CALL_ID_DUPLICATE + cztery I/O |
| bundleValidationDoesNotDuplicateNumericRangeFindingAfterTypeMismatch | A | jeden błąd rateValue + dwa I/O |
| bundleValidationRejectsGeneratorWithoutExplicitMessageBodyType | A | bodyType + brak ratePerSec, maxMessages i outputs |
| bundleValidationRejectsInputIoSubblockWithoutExplicitSelector | B | jedno odrzucenie inputs.type, inny tekst |
| bundleValidationRejectsSelectedIoConfigTypeMismatches | A | trzy oczekiwane błędy + brak outputs; tekstowe ratePerSec nadal akceptowane |
| bundleValidationRejectsCsvInputIoSubblockWithMismatchedSelector | C | obcy inputs.csv, brak wybranych settings Redis i brak outputs; stara asercja wskazuje selector |
| bundleValidationRejectsSelectedIoNumericRangeViolations | B | te same cztery ścieżki błędów, inny tekst ratePerSec |
| bundleValidationRejectsRestTriggerWithoutExplicitRestFields | A | cztery oczekiwane brakujące pola + dwa I/O |
| templateValidationDoesNotClassifyRequestTemplateConsumerByRole | A | tylko cztery błędy I/O |
| bundleValidationAcceptsDbQueryBlankCredentialsWhenExplicit | A | tylko dwa błędy I/O |
| bundleValidationRejectsUnknownInputSelectorOption | B | jedno odrzucenie inputs.type, inny tekst |

## Pochodzenie i weryfikacja

Przepięcie SM na jeden WorkConfigurationParser jest w `2e5b691e` z 2026-09-11.
Ostatnia zmiana ScenarioControllerTest jest w `8e99c673` z 2026-09-10. Fragment powodujący
nadmiarowy wymóg bloku AUTHORING również istnieje w `2e5b691e`; nie został dodany w ostatnim R1–R6.
To wskazuje niedomkniętą adaptację testów po wcześniejszych transferach. Nie odtwarzano historycznego
reactora, więc nie przypisuje się każdej pojedynczej asercji jednemu commitowi.

- Ostatni pełny przebieg tej klasy: 89 testów, 26 Failures, 0 Errors, 0 Skipped;
  `/tmp/workplane-r6-trigger-and-sm-tests.log` i oryginalny raport Surefire.
- Ponowiono 26 niezmienionych metod testowych na istniejących skompilowanych artefaktach R6,
  przez Spring TestContext i MockMvc. Instrumentacja poza repo wyłącznie zbierała odpowiedzi.
  Wszystkie 26 ponownie zakończyły się asercją; zapisano 26 pełnych odpowiedzi walidacji:
  `/tmp/scenario-failure-probe-results.json`, `/tmp/scenario-failure-probe.log`.
- Osobna próba dwóch wejść AUTHORING: `/tmp/scenario-authoring-diagnostic-probe.log`.
- Nie uruchamiano pełnego reactora ani E2E w tej analizie. Nie poprawiano ani nie wykluczano testów.

Źródła kodu: `ScenarioBundleValidator.java:899–903`, `WorkConfigurationFindings.java:21–27`,
`common/work-config/.../WorkConfigurationParser.java:137–155`, `SchedulerSettingsParser.java:49–59`.

## Proponowany zakres naprawy po raporcie

1. Uzupełnić jawne I/O w fixture grup A/C, zachowując osobne przypadki celowo brakującego I/O.
2. Poprawić nadmiarową diagnostykę grupy D we wspólnym parserze, bez zmiany akceptacji danych.
3. Uaktualnić asercje diagnostyki B/C/D do kanonicznego wyniku, zachowując kontrolę przyczyn.
4. Uruchomić pełną klasę, odpowiednie testy parsera i pełny reactor bez wykluczenia ScenarioControllerTest.

## Realizacja zatwierdzonej naprawy — 2026-09-14

- Uzupełniono jawne selektory i wymagane I/O w poszczególnych fixture; wspólny helper ZIP
  i przypadki celowo brakującego I/O pozostają bez automatycznego uzupełniania danych.
- Asercje nadal sprawdzają liczbę findings, konkretne ścieżki/przyczyny oraz oczekiwany wynik
  szablonów i zmiennych. Zachowano wszystkie 89 metod testowych, bez wykluczeń.
- Jedyna produkcyjna korekta tej naprawy jest w `WorkConfigurationParser.requireSelectedBlock`:
  AUTHORING nadal odrzuca obcy blok, ale nie dopisuje wymogu pominiętych settings. RESOLVED
  zachowuje poprzednie wymagania. Dwie nowe regresje obejmują wejście i wyjście w obu trybach.
- Testy celowane przeszły: ScenarioControllerTest 89/89, WorkConfigurationContractTest 16/16.
  Log: `/tmp/scenario-controller-fix-focused-unsandboxed.log`. Pierwszą próbę w sandboxie
  przerwała blokada self-attach Mockito, nie nieudane asercje; poza sandboxem wynik jest zielony.
- Pierwszy pełny root reactor ujawnił dodatkową, niezaadaptowaną fixture
  `ProcessorTopologyProvisioningTest`: mock `RabbitPublisher` nie miał wymaganej nazwy
  `RabbitTransportBeans.CONTROL_PUBLISHER`, więc kontekst nie uruchamiał się przed asercjami.
  Poprawiono wyłącznie rejestrację mocka; asercje i kod produkcyjny Processora bez zmian.
  Dowód pierwszego przebiegu: `/tmp/scenario-controller-fix-full-reactor.log`.
- Drugi pełny przebieg przeszedł ScenarioControllerTest i Processor, ale ujawnił dwa
  niepowodzenia `HttpSequenceMultiEndpointAcceptanceTest`: test wybierał serviceId `journey`,
  a zapisane przez jego helper szablony miały `default`. Poprawiono jeden literał fixture na
  `journey`; asercje wykonania sekwencji i odrzucenia traversal przed HTTP pozostały bez zmian.
  Dowód: `/tmp/scenario-controller-fix-full-reactor-final.log`.
- Końcowy pełny root reactor `./mvnw -fae clean test`: **BUILD SUCCESS**, 45 modułów,
  354 klasy, 1859 przypadków (1858 przeszło, 1 pominięty), 0 Failures i 0 Errors.
  W tym ScenarioControllerTest 89/89, WorkConfigurationContractTest 16/16,
  ProcessorTopologyProvisioningTest 1/1, HttpSequenceMultiEndpointAcceptanceTest 2/2.
  Bez selekcji klas, nowych wykluczeń ani ignorowania błędów. Log:
  `/tmp/scenario-controller-fix-full-reactor-complete.log`.
- Jedyny pominięty przypadek to istniejący `RedisSequenceGeneratorTest.demonstrateIntegrationTestPlaceholder`,
  warunkowany property `redis.integration.test`. Użyto standardowego profilu Maven;
  stackowe Cucumber E2E należą do osobnego profilu/skryptu i nie były ponawiane w tej naprawie.
  Wcześniejszy wynik R6 E2E pozostaje osobnym dowodem, nie wynikiem tego przebiegu.

Ta naprawa nie stanowi zbiorczego odbioru całego refaktoru Rabbit.

Późniejsza decyzja użytkownika: pusty placeholder Redis usunięto wraz z warunkiem i mylącą
instrukcją w JavaDoc. Ponowny pełny reactor przeszedł 1858/1858, bez pominięć;
`/tmp/rabbit-r6-aggregate-review-tests.log`. [Zbiorcze review R1–R6](rabbit-workplane-r6-review-2026-09-14.md)
znalazło osobną rozbieżność SSOT selektora Rabbit; nie jest to powrót problemu 26 testów SM.

### Kontrola zakresu tej naprawy

- Plan: zamknięto rozpoznane przyczyny A–D; dodatkowe poprawki Processora i HTTP Sequence
  dotyczą wyłącznie niespójnych fixture ujawnionych podczas wymaganej regresji.
- Styl i granice: właściciel i nagłówek `WorkConfigurationParser` pozostają zgodne z
  `RESP-WORK-CONFIGURATION-PARSER`. Nie dodano odpowiedzialności do kontrolera SM.
- Prostota: korekta warunku we wspólnym parserze i jawne dane poszczególnych testów;
  bez nowej warstwy ani helpera uzupełniającego brakującą konfigurację.
- Bezpieczeństwo: nie zmieniono autoryzacji, obsługi ścieżek, transportu ani kryterium
  dopuszczenia konfiguracji. Błędny obcy blok nadal uniemożliwia utworzenie konfiguracji.
- Biblioteki: bez nowych zależności; mock Processora używa istniejącej stałej API Rabbit.
- Czytelność: fixture pokazują wymagane I/O w miejscu użycia, a asercje zachowują przyczynę,
  ścieżkę i liczbę błędów. Powtórzenie danych fixture jest celowe i nie tworzy nowych defaults.

Sprawdzona ścieżka: `ScenarioWorkConfigurationComposition` →
`CurrentWorkConfigurationProviders.workConfigurationParser` → wstrzyknięty parser →
`ScenarioBundleValidator` → `WorkConfigurationFindings.validate` →
`WorkConfigurationParser.requireSelectedBlock`. Wyszukiwanie w repo potwierdza jeden
produkcyjny właściciel wymogu wybranego bloku; SM jedynie dodaje ścieżkę i kategorię finding.
Testy kontrolera korzystają z MockMvc, testy kontraktu z publicznej metody `validate`.
Nie badano ponownie kompletności izolacji wszystkich operacji Rabbit; to pozostaje
przedmiotem osobnego zbiorczego review.

# Rabbit/WorkPlane R1–R6 — zbiorcze review ścieżek

Data: 2026-09-14. Punkt odniesienia: `817113a1`, bieżący katalog roboczy wraz z nowymi
plikami; bez commita. Zakres: zaakceptowany plan R1–R6, naprawa ScenarioControllerTest
i usunięcie pustego placeholdera Redis. Review wykonane na źródłach, nie tylko na diffie.

**Wynik końcowy: uzgodniony zakres Rabbit gotowy do PR; review poprawki i pełne E2E przeszły.**
Odroczenia części R4 pozostają aktualne. O1/O2 Orchestratora nie należą do tego pakietu.

**Pierwotny wynik review: jeden bloker SSOT selektora (R6-REV-1).** Poprawka została następnie
wykonana na polecenie użytkownika; jej dowody znajdują się poniżej. Opis pierwotnego
odtworzenia i zakres zbiorczego review pozostają historycznym zapisem.

## Odbiór poprawki R6-REV-1 i zakres commita — 2026-09-14

Osobne review, zlecone po wdrożeniu poprawki: **bez nowych ustaleń; R6-REV-1 zamknięte**.
Prześledzono obie drogi od surowego ENV: parser/katalog SDK oraz warunki aktywacji Rabbit.
`WorkIoTypeParser.normalize` jest wspólną implementacją dla `parse` i `matches`.
`RabbitWorkerIoCondition` składa dokładnie te same warunki kierunkowe, których używają
fabryki transportów. Pełna walidacja nadal wymaga jawnego, jednoznacznego typu i deskryptora;
niedopasowanie warunku bootstrapu nie jest akceptacją konfiguracji workera.

Sprawdzono kolejność auto-konfiguracji połączenia przed transportami, odczyty obu selektorów,
brak nowych efektów/parserów w warunkach oraz regresje startu i odrzucenia settings.
Ponowione wyszukiwania wszystkich produkcyjnych plików Java dla WorkIoTypeParser,
surowych warunków RABBITMQ i dawnych porównań nie wykazały drugiego właściciela w tej ścieżce.
Nagłówki odpowiadają §10 work-plane-boundaries oraz RESP-WORK-IO-CONFIG/RESP-RABBIT-CONNECTION.

Sześć przejść review: cel poprawki spełniony; styl i odpowiedzialności zgodne; jedna
normalizacja bez dodatkowego frameworka; wymagania WORK i granice uprawnień zachowane;
bez nowych zależności; rozdział porównania selektora od walidacji katalogu jest jawny.
ACK, publikacja, reguły settings i stan workera nie zostały zmienione przez tę poprawkę.
Pozostałe adaptery i jawne odroczenia nie zostały uznane za sprawdzone przy tym odbiorze.

Przed commitem wydzielono pakiet Rabbit w osobnym katalogu roboczym. Wcześniejsze,
nadal oczekujące O1/O2 Orchestratora oraz zmiany wyłącznie zakończeń linii TCP mocka
pozostają poza commitem. Wspólne pliki dokumentacji i testu reconciliacji rozdzielono
w indeksie Git; oryginalny katalog zachowuje tamte zmiany. Wcześniejsze wyniki 1876 testów
dotyczą szerszego katalogu. Końcowy odbiór używa wyłącznie przygotowanego pakietu Rabbit.

Weryfikacja wydzielonego pakietu:

- Pełny `build-hive.sh`: część Maven **44 moduły SUCCESS**, 1841 przypadków — 1839
  przeszło, dwa wymagały brokera zatrzymanego przez przebudowę. Log:
  `/tmp/rabbit-r6-isolated-build.log`. Początkowy build strony zatrzymał link do planu
  wyłączonego z publikacji; dołączono istniejącą korektę tego linku.
- `build-hive.sh --quick` po korekcie dokumentacji: **SUCCESS**, cały lokalny stack
  uruchomiony; `/tmp/rabbit-r6-isolated-redeploy.log`. Ingress `/healthz` zwraca `ok`.
- SwarmLifecycleManagerIntegrationTest po starcie brokera: **2/2, bez pominięć**;
  `/tmp/rabbit-r6-isolated-broker-tests.log`. Tym samym wszystkie 1841 przypadków
  budowy mają pozytywny wynik; pominięcie z pierwszego przebiegu pozostaje zapisane jawnie.
- Zawartość wszystkich 310 zmienionych/dodanych/usuniętych ścieżek pakietu porównano
  z indeksem Git: bez różnic. Odbiór E2E uruchomiono z tego samego izolowanego katalogu.
- Normalny `start-e2e-tests.sh`, bez dodatkowych filtrów: **39/39 scenariuszy,
  463/463 kroki, BUILD SUCCESS**, 12:12 min; `/tmp/rabbit-r6-isolated-e2e.log`.
  Auth, Rabbit Management i TCP mock używały odpowiednich ścieżek oficjalnego
  ingressu `http://localhost:8088`; pozostałe ustawienia zgodne ze standardowym skryptem.
  Po tej próbie dopisano wyłącznie wyniki odbioru do dokumentacji; kod pakietu nie zmienił się.

Rekomendacja: zapisać wydzielony pakiet Rabbit w commicie. Nie dołączać O1/O2 ani nie
rozszerzać odbioru na natywne manifesty, osierocony cleanup innych adapterów i kompletność diagnostyki.

## R6-REV-1 — P1: aktywacja Rabbit interpretuje selektor poza jego właścicielem

Właścicielem normalizacji jest `WorkIoTypeParser.parse`: trim, wielkość liter i wybór
jednej jawnie zadeklarowanej tożsamości. SDK używa go przez `WorkIoConfigurationCatalog`.
Nowe `RabbitWorkerIoCondition:19–20` oraz `RabbitWorkAutoConfiguration:30,36` ponownie
interpretują surowe properties, bez kanonicznej normalizacji. To konkurujące reguły
tego samego wyboru i bloker SSOT według AGENTS.md §1.

Odtworzenie na istniejącej fixture startu workera, z jedną zmienną ENV nadpisaną przez
`SystemEnvironmentPropertySource`, przy zachowanych ustawieniach Rabbit i mockach transportu:

| Wejście | Wynik |
| --- | --- |
| `RABBITMQ` | Kontekst workera uruchamia się |
| `POCKETHIVE_INPUTS_TYPE=" RABBITMQ "` | `No WorkInputFactory found for worker 'processorWorker'` |
| `POCKETHIVE_OUTPUTS_TYPE=" RABBITMQ "` | `No WorkOutputFactory found for worker 'processorWorker' (output=RABBITMQ)` |
| `WorkIoTypeParser.parse(" RABBITMQ ", ...)` | `RABBITMQ` |
| Dotychczasowy Spring binding do `WorkerInputType` | `RABBITMQ` |

Próba: `/tmp/rabbit-selector-review-probe.log`; kod odtworzenia wyłącznie w `/tmp`.
Nie wykonywano połączeń z usługami. Poprzednie właściwości SDK używały enumów, więc
odrzucenie tej wcześniej akceptowanej reprezentacji jest regresją startu. Zwykły eksport
Controllera już normalizuje selektor i nie ujawnia błędu; dotyczy on m.in. bezpośrednich
ustawień startowych workera. Przy obu surowych selektorach ze spacjami również warunek
połączenia nie aktywuje WORK.

Naprawa: warunki aktywacji połączenia i obu transportów mają korzystać z tego samego
rozstrzygnięcia selektora co parser/SDK. Nie dodawać niezależnych kopii `trim` i porównań
w kolejnych miejscach. Zachować brak Rabbit WORK dla nie-Rabbit I/O i wymagane jawne
ustawienia po wybraniu Rabbit. Regresja powinna sprawdzać przyjęcie konfiguracji i start,
nie samą obecność nazwanych beanów. Review nie wdraża tej poprawki automatycznie.

### Poprawka R6-REV-1 — wykonana na polecenie użytkownika

`WorkIoTypeParser` udostępnia porównanie selektora przez `matches`; `parse` i `matches`
korzystają z tej samej prywatnej normalizacji. `RabbitWorkerInputCondition` i
`RabbitWorkerOutputCondition` delegują do tego właściciela. Są używane zarówno przez
fabryki transportu, jak i łączący ich wyniki `RabbitWorkerIoCondition` dla połączenia WORK.
Usunięto oba surowe warunki `ConditionalOnProperty(...RABBITMQ)` i porównania
`equalsIgnoreCase` z tej ścieżki. Pełna walidacja listy typów pozostaje w parserze/katalogu;
brak dopasowania w warunku aktywacji nie jest poprawnym wynikiem walidacji workera.

Regresje przed poprawką: RabbitConnectionActivationTest **2 błędy / 8 przypadków**,
PocketHiveWorkerSdkAutoConfigurationQueueResolutionTest **3 błędy / 6 przypadków**.
Logi: `/tmp/rabbit-r6-selector-before.log`, `/tmp/rabbit-r6-selector-sdk-before.log`.
Po poprawce: te same **14/14** oraz WorkIoTypeParserTest **10/10**, bez pominięć;
`/tmp/rabbit-r6-selector-after.log`. ENV zachowuje spacje dzięki
SystemEnvironmentPropertySource. Asercje sprawdzają start i przekazane adresy, odrzucenie
brakujących ustawień WORK, dostarczenie CONTROL bez WORK oraz brak rejestracji odbioru WORK
przy innym adapterze. Parser sprawdza też własny typ testowy i odrzucenie niejednoznaczności.

Pełna regresja po poprawce: `./mvnw -fae clean test` — **BUILD SUCCESS, 45 modułów,
355 klas, 1876/1876 testów, 0 Failures, 0 Errors, 0 Skipped**; log
`/tmp/rabbit-r6-selector-full-reactor.log`. Liczby pochodzą z tego przebiegu reactora,
bez starych raportów po usuniętym module rabbit-config. `git diff --check` przechodzi.
Stackowego E2E nie ponawiano. R6-REV-1 jest naprawione i ma regresję; szersze odroczenia
oraz granice wcześniejszego review pozostają aktualne.

Wyszukiwanie całego repozytorium po `WorkIoTypeParser`, `havingValue = "RABBITMQ"`,
`WorkerInputType.valueOf`, `WorkerOutputType.valueOf` i dawnych porównaniach Rabbit
potwierdza usunięcie zgłoszonych konkurencyjnych reguł z produkcji. SDK i parser konfiguracji
nadal używają `WorkIoTypeParser.parse`; warunki Rabbit używają jego `matches`.
Osobno znaleziono istniejące już w `817113a1` surowe warunki SDK dla SCHEDULER, NONE,
REDIS, REDIS_DATASET i CSV_DATASET. Nie objęto ich tą poprawką Rabbit ani nie uznano
za zweryfikowane dla selektorów ze spacjami; pozostają długiem pozostałych adapterów I/O.

Zmiana realizuje wcześniej opisany kontrakt §10 work-plane-boundaries. Nie zmienia
ustawień adaptera, ACK/requeue, publikacji, kontraktów publicznych ani odroczeń R4.
Sześć przejść dla poprawki: plan — usunięcie odtworzonej rozbieżności; styl — oddzielne
warunki i zgodne nagłówki; prostota — jedna normalizacja bez frameworka; bezpieczeństwo —
jawne wymagania WORK, bez nowych odczytów lub uprawnień; biblioteki — bez nowych zależności;
czytelność — warunki porównują wybór, katalog waliduje jego definicję. To dowody implementacji
poprawki, nie ponowne zbiorcze review pozostałych ścieżek R1–R6.

## Sprawdzeni właściciele i ścieżki

Kontrakty: `docs/architecture/work-plane-boundaries.md`, w szczególności §4, §5 i §10;
rekordy i nagłówki `docs/architecture/runtime-responsibilities.md`. Poniższy wykaz opisuje
sprawdzone realizacje istniejących odpowiedzialności, nie tworzy nowego katalogu kontraktów.

| Odpowiedzialność | Faktyczna ścieżka, właściciel i działania | Wynik / dowód |
| --- | --- | --- |
| RESP-WORK-ADAPTER-SELECTION, RESP-RABBIT-CONNECTION | Parser → WorkIoTypeParser; discovery → WorkIoConfigurationCatalog → binding descriptor; RabbitWorkerIoCondition i warunki fabryk są dodatkowym rozstrzygnięciem. CONTROL dekoduje własne settings; WORK ma osobną konfigurację/klienta. | R6-REV-1 narusza SSOT wyboru. RabbitConnectionActivationTest 3/3 przechodzi, lecz nie obejmuje tej reprezentacji. |
| RESP-WORK-CONFIGURATION-PARSER, RESP-WORK-RABBIT-SETTINGS | ScenarioWorkConfigurationComposition → CurrentWorkConfigurationProviders → WorkConfigurationParser → porty parserów Rabbit. RabbitSettingValues i RabbitInput/OutputSettings posiadają reguły/defaults; properties delegują do RabbitConfiguration. WorkConfigurationFindings wyłącznie projektuje diagnostykę. | Korekta AUTHORING usuwa sprzeczny komunikat, nadal odrzuca obcy blok. RESOLVED bez zmiany. ScenarioControllerTest 89/89, kontrakt parsera 16/16. Nie znaleziono drugiego parsera tych settings w SM. |
| RESP-WORK-RESOURCE-NAMES | WorkTopologyChannels → wybrany WorkTopologyResolver → RabbitResourceNames → ResolvedWorkTopology. Controller planuje wszystkich workerów przed ensure. WorkerWorkConfigurationAdapter i SwarmWorkerSpecFactory eksportują projekcję; SwarmWorkBindingsProjector i collector odczytują te same adresy. | Brak odtworzenia formuł Rabbit w konsumentach tej ścieżki. RabbitWorkTopologyTest 5/5; WorkPlaneFlowTest 1/1. |
| RESP-WORK-TRANSPORT, RESP-WORK-RABBIT-TRANSPORT | RabbitWorkInputChannel → WorkItemJsonCodec → MessageWorkExecution → DefaultWorkerRuntime → jedyny WorkOutputRegistry → RabbitWorkOutput → RabbitPublisher. SDK posiada wykonanie/błędy, Rabbit mechanikę transportu. | Zachowane callback-return ACK, async admission, raportowanie błędów bez requeue i pojedyncza publikacja. Rabbit converter/output po 3/3; fake sprawdza wykonanie, błąd i brak dodatkowego wyniku. |
| RESP-RABBIT-RESOURCES, RESP-RUNTIME-CLEANUP | RabbitWorkResources → RabbitResources → SpringRabbitResources. Verifier kieruje WORK do WorkPlaneResources, CONTROL do portu Rabbit; obserwacja 404 oznacza brak, inne awarie propagują się. Fingerprint cleanup używa tożsamości tego samego połączenia. | RuntimeRemovalPostconditionVerifierTest 7/7. Scoped target zachowuje plane. Nie znaleziono nowego klienta brokera ani alternatywnej realizacji operacji poza modułem Rabbit. |
| RESP-WORK-RESOURCE-NAMES: manifest/debug | ContainerLifecycleManager rozwiązuje topologię i żąda projekcji manifestu przed compute. RuntimeOwnershipManifestFactory odrzuca natywne typy. DebugTapService → WorkDebugTaps → RabbitWorkDebugTaps → RabbitDebugTapSpec/RabbitResources; próbki należą do DebugTapSession. | Zachowana jawna granica manifestu Rabbit i HTTP 501 dla nieobsługiwanej diagnostyki; nie uznano odroczeń za zaimplementowane. |

Nagłówki sprawdzonych klas wskazują powyższe odpowiedzialności. Naruszenie R6-REV-1
dotyczy faktycznego działania mimo opisanej w architekturze jednej normalizacji.

Wyszukiwania repozytorium: `rg` po Java production z wykluczeniem `src/test`, `target`,
modułu Rabbit i fixture dla `RabbitWork`, `RabbitInput`, `RabbitOutput`, `RabbitConnections`,
`RabbitResources`, `RabbitResourceNames`, `RABBITMQ`, `spring.amqp`, `com.rabbitmq`.
75 trafień zapisano w `/tmp/rabbit-r6-review-references.txt` i sprawdzono ich kontekst.
Dodatkowo sprawdzono wywołania declare/delete/bind/send, formuły `.hive`/queue prefix,
requireSelectedBlock i ścieżkę publikacji WorkOutputRegistry. Kandydaci obejmują m.in.
kompozycję usług, projekcje CP, InputLifecyclePolicy oraz jawnie wyłączone E2E. Istniejące
wyłączenia fixture i narzędzi legacy pozostają ograniczeniem tego odbioru.

Nie dodano skanera. Sprawdzono aktualną tabelę RepositoryImportBoundaryTest i ograniczenia
Maven Enforcer: neutralne moduły nie zależą od Rabbit/Spring; Rabbit nie zależy od SDK/usług.
Importy adaptera Work w usługach występują w kompozycji. Automatyczne ograniczenia nie
wykrywają konkurencyjnej interpretacji selektora R6-REV-1.

## Zachowane ograniczenia i odziedziczony dług

- Natywny manifest/orphan cleanup, kompletność diagnostyki UI/MCP, reset i szerszy lifecycle
  pozostają odroczone zgodnie z planem. Nie stanowią nowych findings tego review.
- `appliedResources` oznacza ukończone deklaracje/bindingi. Kolejka utworzona przed błędem
  bindingu nie trafia do tej projekcji; taki sam zapis po bind istniał w usuniętym
  SwarmWorkTopologyManager. Nie przedstawiać projekcji jako pełnego wykazu skutków częściowego
  prepare. To zachowany zakres lifecycle, nie nowa regresja ani dowód braku zasobów.
- `QueueStats.empty()` dla potwierdzonego braku kolejki istniało przed transferem i jest
  jawnie zachowane w rekordzie odpowiedzialności; błąd odczytu nie jest zamieniany na zero.
- Po usunięciu placeholdera w RedisSequenceGeneratorTest pozostają cztery stare metody
  badające wyłącznie stałe/obliczenia lokalne: testSequenceFormatPatterns,
  testExpectedSequenceOutputs, testComplexFormatPatterns, testBase36Calculations.
  Nie zapewniają regresji kodu generatora. Dwa pozostałe przypadki rzeczywiście wywołują
  formatowanie. Ten odziedziczony dług testowy nie jest blokerem izolacji Rabbit.

## Weryfikacja i sześć przejść review

- Usunięto wyłącznie pusty demonstrateIntegrationTestPlaceholder, jego import warunku
  i mylącą instrukcję uruchamiania integracji w JavaDoc. Nie zastąpiono go kolejnym placeholderem.
- `./mvnw -fae clean test`: **BUILD SUCCESS, 45 modułów, 354 klasy, 1858/1858 przypadków,
  0 Failures, 0 Errors, 0 Skipped**. Log: `/tmp/rabbit-r6-aggregate-review-tests.log`.
- `git diff --check` przechodzi. Dodatkowe odtworzenie R6-REV-1 pokazuje lukę istniejących
  testów; nie włączono tej próby do reactora ani nie naprawiano produkcji podczas review.
- E2E stacka nie ponawiano. Wcześniejsze R6 E2E 39/39 pozostaje osobnym dowodem wdrożonej
  ścieżki Rabbit, a nie dowodem poprawności każdego wariantu konfiguracji.

Plan: zakres R1–R6 i jawne odroczenia zachowane, odbiór wstrzymany przez R6-REV-1.
Styl/granice: rozdzieleni właściciele i nagłówki, lecz aktywacja łamie SSOT selektora.
Prostota: naprawa powinna usunąć konkurencyjne rozstrzygnięcie, bez frameworka providerów.
Bezpieczeństwo: cleanup zachowuje plane, tożsamość połączenia i odczyt postcondition;
zaakceptowanego ograniczenia pośrednich ENV overrides nie rozszerzano w hardening.
Biblioteki: obecne porty/Spring i ograniczenia Maven wystarczają, bez nowych zależności review.
Czytelność: jawne fixture i rozdzielone projekcje pozwalają prześledzić dane; zielone testy
i wycofany placeholder nie są utożsamiane z pełnym pokryciem.

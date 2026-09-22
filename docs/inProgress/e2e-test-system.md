# Nowy framework E2E — budowa od zera i zastąpienie starego zestawu

Status na 2026-09-22: N0 i N1 zamknięte. Bieżąca macierz zawiera **42 PASS / 0 PARTIAL**.
NW-4 przeszło lokalnie na Rabbit oraz Artemis, a następnie między hostami
Swarm/NFS na Artemis. Końcowe dowody są zapisane w macierzy i oczekują review N3.
NW-4 i retencja dowodów zapisane w `51227a32`.
Lokalna analiza N3 jest zakończona; [raport](../ci/acceptance-replacement-review.md)
porównuje wymagania, asercje i dowody. Znaleziona luka DA-3 została uzupełniona i
przeszła oba adaptery. Osobny review poprawki i raportu zakończył się bez uwag
(53 testy granic przeszły; zweryfikowano zapisane dowody obu adapterów).
Końcowy odbiór N3 oczekuje osobnego review dowodów zamykających NW-4.
Decyzja użytkownika z 2026-09-18: wracamy do A5 Artemis/3DS; N4 odkładamy do
ręcznych testów użytkownika i jego potwierdzenia. Ani usunięcie starego frameworka,
ani odłożony NW-4 nie są warunkiem rozpoczęcia A5. Stary zestaw pozostaje zamrożony.

Przy przygotowaniu N3 agent wykonał Maven clean bez archiwizacji i usunął wcześniejsze
lokalne artefakty `acceptance-tests/target/runs`. Historyczne wyniki w macierzy nie są
ponownie wykonanym dowodem; zachowały się logi i część podsumowań. Luka dostępności
surowych dowodów pozostaje jawna. Analiza N3 sprawdziła 29 dostępnych refów i nie
znalazła w nich śledzonych archiwów. Zachowane logi i podsumowania oceniono względem
asercji; grupy bez dostępnego potwierdzenia wykonano ponownie. Dokładny zakres podaje
raport. Nowe targety zapisują poza `target`; usuniętych JSON-ów nie odtworzono.

Bieżące wymagania i wyniki określa [macierz pokrycia](../ci/acceptance-coverage.md).
Datowane wpisy poniżej zachowują historię, nie zastępują aktualnego statusu.

## Decyzja i granica

Budujemy całkowicie nowy framework oraz nowe testy w osobnym katalogu/module,
z niezależnym entrypointem i konfiguracją. Stary `e2e-tests` pozostaje zamrożony
w całości do potwierdzenia, że nowy system go zastępuje. Wtedy kasujemy stary zestaw.

Nie przenosimy, nie wydzielamy i nie kopiujemy jego kroków, klientów, helperów,
hooków, konfiguracji ani mechanizmu uruchamiania do nowej implementacji.
Nowy framework nie zależy od starego i nie deleguje do niego żadnego działania.
Nie przepinamy pojedynczych starych testów na nowe wsparcie ani nie kasujemy ich
stopniowo podczas budowy. To zastąpienie całego systemu, nie refaktor starego.

Stary kod jest materiałem do rozpoznania wymaganych zachowań i luk pokrycia.
Oczekiwane zachowanie określają kontrakty produktu i uzgodnione wymagania;
błędy oraz przypadkowe ograniczenia starego harnessu nie stają się wymaganiami.
Kanoniczne kontrakty/codec produktu i biblioteki ogólnego przeznaczenia pozostają
właściwymi zależnościami. Nie piszemy ich duplikatów tylko dlatego, że framework jest nowy.
Wybór runnera i bibliotek wynika z nowego projektu; obecny Cucumber nie jest wymaganiem.

Tymczasowe współistnienie dwóch niezależnych zestawów do porównania pokrycia jest
jawną decyzją użytkownika. Nie oznacza dwóch właścicieli zachowania produktu ani
zgody na fallback, wspólny stan lub most kompatybilności między frameworkami.
Każde uruchomienie jawnie wybiera zestaw; nowy nie próbuje starego po niepowodzeniu.

## Co nowy projekt ma zapewnić

Test deklaruje cel, wymagane środowisko i dane, działania, obserwowalny wynik oraz
sprzątanie. Grupy działają samodzielnie. Stan należy do jednego testu; nie ma
wielkiego TestContext, globalnego mutable state ani zależności od poprzedniego testu.

| Część nowego frameworka | Odpowiedzialność | Granica |
| --- | --- | --- |
| Target | Jeden resolver jawnej konfiguracji środowiska, ingress, limitów czasu i fixtures. | Runner nie powtarza defaultów. Brak wykrywania brokera i automatycznej zmiany adaptera. |
| API | Nowy wspólny transport HTTP i mali klienci publicznych API; jawny aktor dla autoryzacji. | Bez zależności od starych klientów E2E. Odpowiedź HTTP jest danymi, np. oczekiwane 403 ocenia test auth. |
| Operations | Oczekiwanie na konkretny operationUrl/correlationId i odczyt kanonicznego SwarmOperation z deadline. | Bez odtwarzania powodzenia operacji ze statusów workerów lub pojedynczych eventów. |
| Resources | Uchwyty zasobów utworzonych przez test i wspólne zamknięcie ich życia, także po błędzie. | Bez drugiego rejestru swarma, usuwania po prefiksie, resetu i orphan cleanupu. |
| Capture | Publiczne debug taps, logiczny swarm/role/direction/ioName i kanoniczny codec WorkItem. | Bez klienta Rabbit/Artemis, nazw fizycznych kolejek i konkurowania z workerem o odbiór. |
| Reporting | Czytelna przyczyna błędu, odpowiedzi API, tożsamość operacji i wynik cleanupu. | Bez własnego audytu/protokolarnego parsera, drugiego kalkulatora wyniku domenowego i tokenów w raporcie. |

To odpowiedzialności do zaprojektowania w małych jednostkach, nie nakaz sześciu
modułów Maven ani sześciu wielkich klas. Testy wyrażają przebieg i asercje danej
funkcji. Żaden wspólny element nie rozpoznaje nazw scenariuszy, aby wybrać zachowanie.

## Zakres testów

Nowy zestaw ma osobne grupy: smoke, scenariusze, auth, lifecycle swarmów,
konfiguracja workerów, przetwarzanie wiadomości, network oraz data/outputs.
Każda grupa wymaga wyłącznie potrzebnych fixtures i usług. Pełne przebiegi sprawdzają
współpracę wdrożonych komponentów, w tym rzeczywisty wynik pracy i usunięcie swarma.

Szczegóły walidacji, codec i natywnego brokera należą do testów właścicieli modułów.
Przy ocenie zastąpienia wskazujemy konkretne nowe lub istniejące pokrycie tych wymagań;
samo określenie czegoś jako testu niższej warstwy nie zamyka luki. Pokrycie integracji
całej ścieżki pozostaje wymagane. Rabbit CONTROL nadal istnieje przy Artemis WORK.

Nowe fixtures tworzymy jawnie dla wymaganych zachowań i wybranego adaptera.
Nie kopiujemy mechanizmu starego zestawu ani nie konwertujemy Rabbit YAML heurystyką.
Wspólne zachowania WORK odbieramy na Rabbit i Artemis; przypadki niezależne od WORK
nie wymagają automatycznie powtarzania całej macierzy na każdym brokerze.

## Warunki działania frameworka

- Testy produktu korzystają z oficjalnego ingress/API. Fixtures Redis/TCP/ClickHouse
  wymagają jawnego, wspieranego interfejsu setup/obserwacji. Brak takiego interfejsu
  jest konkretną luką projektu, nie pozwoleniem na obejście ingress. Dedykowane testy
  interfejsów usług wymagają granicy i zgody zgodnie z AGENTS.md.
- Wymagane API lub dane niedostępne w wybranym teście powodują błąd, nie skip.
  Wybór grup jest jawny przed startem; brak ukrytego filtrowania niezgodnych testów.
- Żądanie HTTP i oczekiwanie operacji mają ograniczony czas. Terminalny błąd zgłaszamy
  od razu; timeout nie ponawia automatycznie mutacji i nie uruchamia starego frameworka.
- Capture powstaje przed ruchem. Obserwacje wiążemy z właściwym swarm/run/worker
  lub operacją według kontraktu, bez uznawania starych statusów za dowód nowej zmiany.
- Każdy utworzony zasób ma uchwyt w zakresie testu. Normalne zamknięcie i cleanup po
  błędzie używają tego samego właściciela. Dla zaakceptowanego remove czekamy na wynik
  i sprawdzamy kanoniczne removed/remaining/errors oraz brak swarma; samo 202 lub
  zniknięcie z rejestru nie zastępuje terminalnego wyniku operacji.
- Błąd cleanupu pozostaje widoczny obok pierwotnej awarii. Nieustalony stan i trwająca
  operacja są raportowane wraz z niezamkniętymi zasobami; bez drugiej ścieżki kasowania.
- Dane SUT mają osobnego właściciela i zakres konkretnego testu. Początkowo wykonanie
  jest szeregowe; równoległość wymaga wykazanej izolacji danych i cleanupu.

## Etapy i odbiór

### N0 — wymagania i projekt nowego systemu

Sporządzić mapę: wymagane zachowanie → nowy test lub konkretne pokrycie przy właścicielu
→ wymagane środowisko → obserwowalny dowód. Stare przypadki i ich asercje są jednym
ze źródeł tej mapy, obok aktualnych kontraktów. Nie kopiujemy implementacji testów.
Każda różnica zakresu wymaga jawnego rozstrzygnięcia; nie pomijamy trudnych przypadków.

Ustalić katalog/moduł, runner, konfigurację i interfejsy małych właścicieli.
Opisać projekt w kanonicznej strategii testów i odpowiedzialnościach przed kodem,
a nagłówki utrzymywać zgodne z faktyczną implementacją. Wybrać biblioteki potrzebne
nowej konstrukcji; nie dziedziczyć struktury Cucumber ani starego runnera.

### N1 — nowy framework i pierwszy kompletny test

Napisać od zera konfigurację, API, obsługę operacji, zasobów, capture i raportowania
potrzebne do jednego przebiegu: create → start → rzeczywisty WorkItem → stop → remove.
Uruchomić ten sam nowy test z nowymi jawnymi fixtures Rabbit i Artemis.

Sprawdzić również błąd po create oraz nieudany cleanup: pierwszy nie gubi zasobów,
drugi jest widoczną awarią. Brak zależności od starego modułu i natywnych klientów
brokerów w nowym E2E. To pierwsza bramka integracyjna dla A6 Artemis, nie dowód
zastąpienia całego starego zestawu.

### N2 — nowe testy wszystkich uzgodnionych zachowań

Napisać niezależne testy kolejnych grup według mapy N0. Nowe API/lifecycle/cleanup
mają wspólnych właścicieli w nowym frameworku. Stary zestaw pozostaje zamrożony;
żadnych ekstrakcji, delegacji, kopiowania helperów ani częściowego usuwania.
Dla każdego wymagania zapisać wynik i dowód nowego pokrycia, również przypadków błędów.

### N3 — potwierdzenie zastąpienia

Warunki łączne:

1. Każde wymaganie ma wykonany, oceniony test lub jawnie uzgodnioną zmianę zakresu.
   Liczba zielonych testów, podobne nazwy i stary zielony raport nie dowodzą równoważności.
2. Nowy system samodzielnie buduje się i uruchamia, bez importów, zależności, helperów,
   konfiguracji i ukrytych wywołań starego. Wspólne zachowania WORK przeszły oba adaptery.
3. Potwierdzono cleanup sukcesu i błędu oraz czytelne raportowanie awarii i timeoutu.
4. Review porównuje wymagania z faktycznymi asercjami, granicami API i dowodami wykonania.
   Pozostające luki są jawne; bez potwierdzenia zastąpienia nie kasujemy starego systemu.

### N4 — usunięcie starego systemu

Decyzja użytkownika z 2026-09-18: wykonać dopiero po jego ręcznych testach
oraz potwierdzeniu usunięcia. Do tego czasu kontynuować Artemis/3DS.
Po potwierdzeniu N3 i spełnieniu powyższego warunku usunąć cały stary moduł wraz ze starymi krokami, klientami,
helperami, konfiguracją, zbędnymi fixtures, zależnościami i podłączeniami runnera/CI.
Każdy zasób przed usunięciem musi być potwierdzony jako należący wyłącznie do starego
systemu; scenariuszy produktu ani współdzielonych kontraktów nie kasujemy przypadkiem.
Oficjalny punkt uruchamiania i dokumentacja wskazują już wyłącznie nowy framework.
Nie zostają aliasy, delegacje ani fallback do starego. Zweryfikować samodzielny build
oraz reprezentatywny przebieg po usunięciu i brak pozostałych zależności od starego.

## Poza zakresem

Bez zmian zachowania ACK, resetu Orchestratora, manifestów i orphan cleanupu.
A4 Artemis nadal oczekuje osobnego review, A5 delayed publish pozostaje osobnym etapem.
## Bieżące wykonanie N1/N2 — 2026-09-15

Nowy moduł `acceptance-tests`, Java 21/JUnit 5/JDK HTTP, ma własny runner
`run-acceptance-tests.sh`, jawne targety i nowe fixtures `scenarios/acceptance`.
[Mapa pokrycia](../ci/acceptance-coverage.md) zawiera wyniki, artefakty i jawne luki;
[rekordy odpowiedzialności](../architecture/acceptance-tests.md) opisują właścicieli.

**N1 wykonane:** 38 testów frameworka oraz oba przebiegi przez publiczny ingress
na Rabbit i Artemis przeszły. `HttpLifecycleAcceptanceIT` sprawdza create/start,
trzy różne WorkItems z wynikiem HTTP 200, stop/remove i oczekiwane role workerów.
`FailureCleanupAcceptanceIT` przerywa ciało testu po create i potwierdza cleanup
z zachowaniem pierwotnego błędu. Nieudany cleanup i połączone błędy są pokryte
w testach komponentowych. Osobne review poprawek HTTP timeout, raportowania oraz
request/receipt identity nie zgłosiło nowych ustaleń przed obecnym odbiorem Rabbit.

**Pierwszy wycinek N2 wykonany:** `TargetStateLifecycleAcceptanceIT` sprawdza STOP
przed pierwszym START, ponowny STOP oraz ponowny START po osiągnięciu RUNNING.
Każda komenda ma nowy idempotencyKey i osobny correlationId; operacje muszą zakończyć
się SUCCEEDED, z tym samym runId i oczekiwanym stanem publicznego API. Kontrakt pozostaje
w ORCHESTRATOR-REST §3.1–3.2. Test korzysta z istniejących LiveRun, SwarmResource
oraz OperationAwaiter i wspólnego cleanupu. Przeszedł na obu adapterach i osobne review bez nowych ustaleń. Dokładny replay tego samego klucza i brak ponownej emisji CP nie są
asercjami tego testu i nadal wymagają własnego dowodu w pełnej mapie zastąpienia.

Ostatnie wykonanie: Rabbit 2/2 N1 oraz 1/1 N2, Artemis 3/3 grupy lifecycle, bez błędów
lub pominięć. Każdy z sześciu REMOVE zakończył się SUCCEEDED, z 15 usuniętymi zasobami
na Rabbit lub 16 na Artemis, pustymi remainingResources/errors oraz 404 swarma.
Liczby są dowodem konkretnego przebiegu; framework nie wpisuje ich jako reguły adaptera.
Maven Enforcer przeszedł; wcześniejsze 3 przypadki RepositoryImportBoundaryTest także
przeszły i nie były powtarzane przy dodaniu wyłącznie nowej klasy testowej.

Lokalne wykonanie użyło tymczasowego override WORK=RABBITMQ, po czym przywrócono
ARTEMIS. Przeładowano wyłącznie Orchestrator, bez przebudowy kodu czy zmiany pliku compose.
Przed restartem trzy wcześniejsze swarmy miały wyłącznie zakończone kontenery i brak
aktywnych operacji. Ich kontenery pozostawiono; restart usunął ich nieaktualne wpisy
z pamięci Orchestratora. Wszystkie nowe swarmy usunęły własne uchwyty testowe, a końcowa
lista swarmów z API jest pusta. Nie wykonywano resetu ani orphan cleanupu.

Logi: `/tmp/acceptance-n1-rabbit.log`, `/tmp/acceptance-n2-rabbit-target-state.log`,
`/tmp/acceptance-n1-n2-artemis.log`. Dokładne katalogi dowodów podaje mapa pokrycia.
Bez zmian kodu produktu, starego E2E i jego runnera, bez commita lub push.

Otwarte: pozostałe grupy N2, pełne dowody CP i budżetów oczekiwania, interfejsy fixtures
Redis/TCP/ClickHouse/eksportów, N3 i N4. Capture dowodzi wyniku procesora HTTP;
nie jest pomiarem throughput postprocessora. Kolejny wycinek to samodzielna grupa
Scenario API: odczyt zadeklarowanych ustawień/rate/interceptorów/history policy.
Nie wymaga ona przełączenia Work Plane ani uruchamiania swarma.

## N2 — Scenario API: zakres wykonania 2026-09-16

SC-1/SC-2/SC-3: trzy niezależne odczyty nowego fixture `acceptance-scenario-authoring`
przez Scenario Manager za publicznym ingress. Asercje: zadeklarowane ratePerSec,
pełna treść konfiguracji templating oraz komplet czterech ról z przypisanymi FULL,
LATEST_ONLY. Błąd odpowiedzi lub brak fixture jest błędem testu, bez skip.
Bez uruchamiania swarma, kontaktu z brokerem lub zapożyczania implementacji starego E2E.

TargetLoader pozostaje jedynym resolverem. Wspólne ustawienia HTTP/aktora/raportów
mają projekcję ApiTarget; target lifecycle dodaje własne wymagane limity i HttpFixture,
a ScenarioTarget wymaga tylko scenarioId. Typ odczytu targetu wybiera jawnie suite,
bez wykrywania grup po nazwach fixture i bez opcjonalnych pól dla nieużywanych funkcji.
ApiRun posiada wspólny zakres HTTP, logowania i evidence; LiveRun składa na nim
wyłącznie lifecycle. Nowa grupa nie wymaga konfiguracji operacji, SUT ani capture.
Weryfikacja obejmuje ścisłe klucze obu targetów, nowe odczyty przez ingress i regresję
istniejącego lifecycle po rozdzieleniu wspólnego zakresu. Stary E2E pozostaje zamrożony.

Wykonanie 2026-09-16: punkt startowy zapisany w commicie `20adcfaa` na jawne polecenie
użytkownika; bez push. Następnie dodano ApiTarget/ScenarioTarget, wspólny ApiRun
i trzy testy ScenarioReadAcceptanceIT. Odczyty przeszły 3/3 przez ingress (0.466 s),
a wszystkie 44 testy frameworka i trzy testy granic importów są zielone.
Regresja lifecycle po wydzieleniu zakresu HTTP przeszła na Artemis 3/3, każdy REMOVE
SUCCEEDED z 16 usuniętymi zasobami, bez pozostałości/błędów i z 404 swarma.
Zatrzymany lokalny stack uruchomiono z istniejących kontenerów; nie przebudowywano
produktu ani nie przełączano WorkPlane. Rabbit nie był ponownie wykonywany w tym wycinku.

Logi: `/tmp/acceptance-scenario-component-tests.log`, `/tmp/acceptance-scenario-live.log`,
`/tmp/acceptance-scenario-lifecycle-regression.log`. Wycinek przeszedł osobne review i został zacommitowany jako `71205fcf`. Następna niezależna grupa: auth API,
z jawną macierzą endpointów i aktorów zgodnie z AU-1–AU-13. SC-4 (zmienne w ruchu)
oraz WK-1 (runtime history) nadal wymagają osobnych testów ruchu/konfiguracji workerów.

Kontrolna próba z nieistniejącym scenarioId zakończyła się zgodnie z oczekiwaniem:
3 błędy HTTP 404, zero skip, kod runnera 1. Log:
`/tmp/acceptance-scenario-missing-fixture.log`. To celowy test błędnego wejścia;
zielony przebieg grupy zapisano
w `/tmp/acceptance-scenario-live.log`.


### Następny wycinek N2: auth-read

SC-1–SC-3 przeszły osobne review bez ustaleń (44 testy frameworka + 3 odczyty).
Commit: `71205fcf`. Implementujemy teraz AU-1/AU-2: jawną macierz odczytów
przez ingress, z parą 200 dla wybranego uprawnionego aktora i 401 bez credentials.
Obejmuje listę swarmów/scenariuszy, szczegóły/raw scenariusza, capabilities,
workspaces, raw network/SUT, CP schema, hive journal i network bindings/proxies.
Używamy istniejącego targetu scenariusza i ApiRun; brak mutacji i nowych fixtures.
Pozostałe AU-3–AU-13 wymagają osobnego pokrycia uprawnień i zasobów.


Wynik auth-read: 13/13 przez ingress oraz 45 testów frameworka i 3 granic importów
przeszły. Logi: `/tmp/acceptance-auth-read-final.log`,
`/tmp/acceptance-auth-import-boundaries.log`. Wspólny klient obsługuje teraz jawny
Accept dla tekstowych endpointów raw; nie dodano drugiego klienta ani parsera.
Kod auth-read pozostaje niecommitowany i czeka na osobne review.
Następny wycinek: jawny aktor viewer i jego grant, odczyty AU-6 oraz brak prawa
uruchamiania AU-3; potem scoped runner i zasoby zarządzane przez test.


### Viewer AU-3/AU-6

Wykonano: dokładny grant PocketHive VIEW, odczyty list/detail/raw, pusta lista runnable,
CREATE403 i odczyt admina404 przed/po próbie. ApiRun/TargetLoader pozostają właścicielami
sesji/konfiguracji. SwarmResource obsługuje jawnego aktora CREATE i osobnego właściciela
obserwacji/usuwania; test frameworka potwierdza cleanup po błędnej akceptacji.
OperationLimits oddziela wymagania operacji od capture. Nowy target local-viewer nie
wymaga tapów. Log: `/tmp/acceptance-viewer-final.log` (49 + 3 testy zielone).
Nowy wycinek pozostaje do review. Następnie scoped runner AU-4/AU-5.

Regresja lifecycle Artemis: 3/3, każdy REMOVE SUCCEEDED (16 usuniętych zasobów,
zero pozostałości/błędów, registry404). Trzy testy importów przeszły. Logi:
`/tmp/acceptance-viewer-lifecycle-regression.log`, `/tmp/acceptance-viewer-import-boundaries.log`.


### Scoped runner AU-4/AU-5 — wykonanie

Używamy istniejącego local-runner (VIEW deployment + RUN demo), nowego fixture
`demo/acceptance-runner-artemis` oraz istniejącego `acceptance/http-artemis` poza zakresem.
Target deklaruje oba scenariusze, folder, aktorów i limity. Test najpierw potwierdza granty
oraz dostępność/foldery fixtures w katalogu admina, potem katalog runnera, dozwolone
CREATE i odmowę poza folderem. Cleanup obsługuje istniejący SwarmResource przez admina.
Oddzielny przypadek sprawdza sześć odczytów deployment z AU-5. Wspólne asercje profilu
wyodrębniono do ActorAssertions; nie zmieniamy użytkowników ani grantów produktu.
Nie deklarujemy START/traffic ani odmowy STOP dla RUN-only w tym wycinku.


Wynik runnera: 53 testy frameworka, 2 testy runnera, regresja viewera 3/3 i importy 3/3
zielone. CREATE w demo przyjęte, poza zakresem403. REMOVE SUCCEEDED (16 zasobów,
zero pozostałości/błędów, registry404); końcowa lista swarmów pusta. Logi:
`/tmp/acceptance-runner-final.log`, `/tmp/acceptance-runner-viewer-regression.log`,
`/tmp/acceptance-runner-import-boundaries.log`. Wycinek pozostaje niecommitowany do review.
Dalsze auth: AU-8/AU-13 mogą użyć istniejących aktorów. AU-7/AU-9/AU-10 wymagające
provisioningu aktorów muszą jawnie rozwiązać cleanup: obecne API admina nie ma DELETE
użytkownika. Nie dokładamy tego kontraktu ani nie zostawiamy użytkowników po cichu.


### Shared network auth AU-8/AU-13

Wykonano trzy przypadki przez ingress: viewer odczytuje network profiles/SUT raw,
a PUT tego samego tekstu jest odrzucony403; runner dostaje403 dla manual override,
viewer odczytuje status. Wszystkie readbacki pozostają identyczne. Target wymaga tylko
API/aktorów, bez scenariuszy i lifecycle. Wspólny klient obsługuje jawne text/plain UTF-8.
55 testów frameworka i3 deployed zielone: `/tmp/acceptance-network-access.log`.
Wycinek pozostaje niecommitowany do review. Pozostałe AU-7/AU-9–AU-12 oraz reszta
N2 nadal otwarte; brak DELETE user w API pozostaje kwestią dla izolowanych fixtures aktorów.


### AU-10 — RUN-only STOP (bieżący wycinek)

AU-8/AU-13 przeszły osobne review i są zapisane w `5c9ea201`.
Następny wycinek używa istniejącego targetu runnera: własny swarm w demo,
START admina, STOP runnera403, niezmieniony RUNNING i STOP admina SUCCEEDED.
SwarmResource zachowuje receipt także przy błędnej akceptacji STOP; jawna odmowa403
nie blokuje jego cleanupu. Aktor folder ALL i provisioning pozostają otwarte.
Warunek odbioru: test deployed przez ingress oraz testy cleanupu po odmowie,
błędnej akceptacji i niepewnym wyniku dispatch. Bez zmian produktu/starego E2E.


Wynik: 58 testów frameworka +3 deployed runnera i3 importów zielone, zero skips.
Oba swarmy usunięte przez canonical REMOVE (16/0/0 i registry404).
Logi: `/tmp/acceptance-runner-stop.log`, `/tmp/acceptance-runner-stop-imports.log`.
AU-10 oznaczone PARTIAL; ten wycinek niecommitowany, czeka na osobne review.
Dalej trzeba ustalić izolowane fixtures aktorów dla folder ALL/bundle RUN;
AU-7/AU-9/AU-11/AU-12 oraz pozostałe N2/N3/N4 pozostają otwarte.


### Korekta po review AU-10

Zawężono reset pending po403 wyłącznie do STOP. REMOVE403 pozostawia ślad próby,
a close zgłasza brak potwierdzonego cleanupu bez ponawiania POST i bez deklaracji
usunięcia. Test rejectedExplicitRemovalIsNotRetriedByClose odtworzył regresję przed
poprawką (niespodziewany drugi POST REMOVE). Właścicielem pozostaje SwarmResource;
nie zmieniamy polityki produktu ani dodanych wcześniej postconditions.

Weryfikacja poprawki: 59 testów frameworka +3 deployed runnera zielone;
`/tmp/acceptance-remove403-fixed.log`. Regresja przed poprawką:
`/tmp/acceptance-remove403-red.log`. Oba swarmy REMOVE SUCCEEDED16/0/0 i registry404.
Brak zmian granic importów/dependencies; bez ponownego skanu importów. Bez commita.


### Poprawki całościowego review frameworka F1–F3

F1: klient scenariuszy przekazuje zakodowany segment ID, bez własnej gramatyki;
ApiSurface jest wspólnym właścicielem kodowania także dla auth/read/raw.
F2: TapResource zapisuje wybrane surowe próbki pod osobnymi nazwami dla każdego
oczekiwania; RunEvidence nadal jedynym właścicielem zapisu. Dowody częściowe pozostają
po błędzie dekodowania i timeout, niezależnie od ostatniego snapshotu API.
F3: jawny close w DebugTapService zwraca500 po błędzie adaptera, zamiast pozornego200.
Nie odtwarza rejestracji i nie ponawia automatycznie operacji; polityka expiry bez zmian.
Zakres zatwierdzony poleceniem poprawy trzech ustaleń. Regresje: ID z kropką i kodowanie
segmentu, ring eviction/partial capture, API close success/failure i widoczny błąd cleanupu.


Wynik F1–F3: 65 testów frameworka, 5 testów DebugTapService i 3 deployed lifecycle
na Artemis przeszły, zero błędów/skips. Logi: `/tmp/acceptance-framework-fixes-focused.log`
i `/tmp/acceptance-framework-fixes-lifecycle.log`. Regresje sprawdzają kodowanie ID,
zgodność zapisanych/wybranych próbek mimo eviction, częściowe dowody po decode failure
oraz timeout i zachowanie pierwotnego błędu razem z błędem cleanupu. MockMvc potwierdza
HTTP 500 rzeczywistego kontrolera po błędzie adaptera. Stack nie był przebudowany:
nowa ścieżka błędu produktu ma dowód komponentowy, nie deployed. Granice zależności
nie zmieniły się; testu importów nie powtarzano. Poprawki czekają na osobne review;
nie wykonano commita ani zmian starego zestawu.


Review F1–F3: 65 testów frameworka, 5 obsługi tapów i 3 granic importów przeszły.
Raport: `/tmp/acceptance-fixes-review.md`; log: `/tmp/acceptance-fixes-review-tests.log`.
Osobne review nie zgłosiło usterek; użytkownik zatwierdził commit i dalsze N2.


### N2 — worker runtime WK-1/WK-2

Zreviewowane AU-10/F1–F3 zapisano w `bb551f1f`. Następny wycinek: dwa samodzielne
testy grupy workers, nowe jawne fixtures czterech workerów dla Rabbit i Artemis.
WK-1 porównuje config.historyPolicy z autoringu z publicznym observation.workers
po starcie i poprawnym ruchu HTTP. Obserwacje muszą być aktualne, z tym samym runId,
kompletem unikalnych instancji i konfiguracją każdego workera. WK-2 potwierdza wynik
HTTP i przenosi asercję header separation na kanoniczne WorkItem/OutcomeHeaders.

LiveRun zachowa już odczytany scenario jako projekcję tylko do odczytu; SwarmApi
przekaże pozostały deadline do istniejącej ścieżki GET state. Wspólne asercje wyniku
HTTP będą w HttpWorkAssertions, bez nowego klienta/parsera. Capture i REMOVE pozostają
u dotychczasowych właścicieli. Plan nie przenosi starego harnessu ani nie dodaje
odbiornika CP. Warunek odbioru: wykonanie przez ingress i dowód cleanupu; pokrycie
obu brokerów zostanie zapisane osobno. Brak jeszcze twierdzenia o pełnym/delta statusie,
rzeczywistej liczbie kroków historii, wszystkich ustawieniach WK-4/WK-5 czy N3/N4.


Pierwsze uruchomienie, przed naprawą identity: WK-1/WK-2 ujawniły defekty produktu: ph.step.instance procesora wskazuje generator,
a zgłoszone DISABLED nadal zachowuje poprzednie kroki. Przyczyny potwierdzone w
DefaultWorkerContextFactory (message headers przed configured identity; history z
PocketHiveWorkerProperties zamiast accepted config). Nie osłabiono asercji i nie
zmieniono SDK. Nowe testy pozostają czerwone; wspólne asercje HTTP wykrywają ten sam
problem również w istniejącym lifecycle. Szczegóły i dalsze naprawy: F02 planu
functional-module-boundaries; wyniki obu adapterów są w mapie pokrycia.


Wynik worker slice: framework 66/66, E2E Artemis 0/2 i Rabbit 0/2 — ten sam błąd
instancji producenta. Po agregacji asercji na Rabbit wszystkie pozostałe sprawdzenia
HTTP/nagłówków przeszły. Capture obu adapterów potwierdza również trzy kroki mimo
DISABLED. Każdy REMOVE SUCCEEDED, remaining/errors puste, registry404; lokalny stack
przywrócony na Artemis, lista swarmów pusta. Logi `/tmp/acceptance-workers-artemis.log`
i `/tmp/acceptance-workers-rabbit.log`. Bez zmian produktu i bez następnego commita;
wycinek do review, blokery SDK jawnie zapisane jako osobna naprawa.


### Naprawa executing identity — 2026-09-16

Zatwierdzony wyłącznie punkt 1: fabryka kontekstu wymaga ControlPlaneIdentity i nie
wybiera swarm/instance z nagłówków wiadomości; usunięto konstruktory bez identity.
SDK/context/composition 19/19, framework 66/66 i workers 2/2 na każdym z Rabbit/Artemis.
Wszystkie cztery swarms usunięte, lokalny stack przywrócony na Artemis z pustą listą.
Szczegóły bieżącego dowodu zastępującego czerwone uruchomienia są w mapie pokrycia.
HistoryPolicy odłożone decyzją użytkownika do rozmowy o użyciu/semantyce; brak zmian
polityki i brak twierdzenia o retencji kroków. N3/N4 pozostają otwarte.
Zmiany nie są commitowane; następny etap to osobny review.


### Dwie polityki historii — decyzja 2026-09-16

Usuwamy wyłącznie redundantne DISABLED. FULL i LATEST_ONLY zachowują dotychczasowe
operacje; brak aliasu zgodności, nowego parsera lub zmian doboru polityki runtime.
Jawne fixtures i oczekiwania testów korzystają z LATEST_ONLY zamiast usuniętej wartości.
Wcześniejsze dowody z DISABLED opisują stan sprzed tej decyzji; rozjazd pomiędzy
raportowaną konfiguracją a ustawieniem startowym pozostaje odłożony.

Weryfikacja tego wycinka: WorkItem/kodek 13, framework 66, SDK 7 i odczyt scenariuszy
przez ingress 3 — wszystkie zielone. Bez rebuilda workerów i bez zmiany statusu N3/N4.


### Runtime honoruje politykę scenariusza — 2026-09-16

Użytkownik otworzył odłożoną naprawę: runtime musi honorować config.historyPolicy.
Plan przed implementacją: ConfigMerger tworzy kompletny kandydat; jedna
WorkerRuntimeConfiguration parsuje wspólną politykę, a WorkerControlPlaneRuntime
akceptuje ją razem z raw config w WorkerState. Fabryka kontekstu odczytuje gotowy
enum dla danej inwokacji. Usuwamy osobną właściwość startową i wybór po beanie/roli.
FULL przy braku pola, patch zachowuje poprzednią politykę, reset przywraca FULL;
niepoprawne wartości odrzucamy przed zmianą stanu i efektami. Nie zmieniamy operacji
WorkItem, semantyki ACK ani adapterów Rabbit/Artemis.

Plan pass: usuwa przyczynę rozjazdu i drugi tor konfiguracji. Kontrakt właściciela
jest w RESP-WORK-STATE/CONTEXT. Dowód: test komend CP przez rzeczywisty runtime i
WorkerInvocation (retencja, zmiana FULL/LATEST_ONLY, patch, reset, odrzucenie), oraz
asercja rzeczywistych kroków w istniejącym deployed worker slice. Wyniki dopiszemy
po wykonaniu; poprzednie przebiegi nie są dowodem tej zmiany.


Wynik naprawy: SDK 270/270, framework 66/66 i testy zależności zielone;
pełny lokalny build-hive.sh --quick zakończony. Workers E2E 2/2 na Artemis i 2/2
na Rabbit, już z asercją faktycznego jednego kroku wyniku LATEST_ONLY. Wszystkie
REMOVE zakończone SUCCEEDED, remaining/errors puste, registry404. Przywrócono Artemis,
lista swarmów pusta. Szczegóły i dokładne logi w mapie pokrycia, sekcja
Scenario-selected runtime history. Odłożony rozjazd polityki jest naprawiony;
FULL/aktualizacje/reset/odrzucenie mają dowód komponentowy, LATEST_ONLY również
deployed. N3/N4 pozostają otwarte. Bez commita; zmiana do osobnego review.


### N2 — templating i zmienne WK-3/SC-4 — 2026-09-17

Zatwierdzony następny wycinek: nowa grupa `templating` z niezależnymi fixtures
Rabbit i Artemis. Dwa samodzielne przypadki wybierają różne profile variables.yaml
przez kanoniczne SwarmCreateRequest. Każdy tworzy własny swarm generator → processor
→ postprocessor, otwiera tap przed START i wymaga trzech różnych wyników HTTP.
FULL zachowuje wygenerowany HttpRequestEnvelope obok wyniku procesora. Asercje
sprawdzają dokładny JSON z interceptora templating, nagłówki generatora, wybrany
profil/global+SUT variables, typy liczb/bool i wynik eval. Oczekiwane wartości są
stałymi przykładami testu; test nie wykonuje szablonu ani resolvera zmiennych.

LiveRun rozszerza istniejącą fabrykę żądania create o jawny variablesProfileId.
TargetLoader, API, capture, codec, oczekiwanie operacji i cleanup mają dotychczasowych
właścicieli. Bez nowego parsera YAML/konfiguracji, odbiornika brokera, modyfikacji SUT
ani zapożyczeń ze starego E2E. Wybrany SUT używa istniejącego read-only /api/test.
Odbiór: component build oraz oba profile na każdym adapterze przez ingress;
kanoniczny REMOVE SUCCEEDED, brak pozostałości/błędów i registry404.
Plan pass: ten wycinek zamyka rzeczywistą treść ruchu WK-3/SC-4, bez roszczenia
do całej macierzy walidacji variables, WK-4/WK-5 lub N3/N4. Wyniki po wykonaniu.


Wynik WK-3/SC-4: framework 66/66, deployed templating Artemis 2/2 i Rabbit 2/2,
bez pominięć. Każdy profil sprawdził trzy rzeczywiste wiadomości z dokładnym JSON,
nagłówkami i poprawnym wynikiem HTTP. Cztery REMOVE SUCCEEDED, remaining/errors
puste i registry404. Przywrócono Artemis; publiczna lista swarmów jest pusta. Dokładne dowody są w mapie pokrycia, sekcja z 2026-09-17.
Bez zmian produktu/starego E2E w tym wycinku, bez commita. Osobne review zakończone bez findings;
następne WK-4/WK-5: konfiguracja/overrides oraz jawne pokrycie full/delta CP u właściciela.


### N2 — konfiguracja workerów WK-4/WK-5 — 2026-09-17

Plan pass: istniejące fixtures workers są wariantem bazowym; nowe jawne fixtures
worker-overrides zmieniają ustawienia wszystkich czterech ról, w tym scheduler
oraz tuning wybranego adaptera. Osobne targety Rabbit/Artemis, bez automatycznej
konwersji konfiguracji. Grupy worker-config i worker-overrides korzystają z tych
samych właścicieli lifecycle, tap i cleanup. Porównanie pól jawnie napisanych w
scenariuszu z observation.workers[].config nie dodaje defaults ani resolvera.
Wyrenderowany baseUrl sprawdzamy na konkretnym oczekiwanym przykładzie, a poprawne
wywołanie HTTP stanowi dodatkowy dowód działania. Nie odtwarzamy nazw zasobów.

Oczekiwanie na świeżą konfigurację bieżącego runId zostaje wydzielone z WK-1 do
jednego WorkerObservations, używanego przez obie grupy. WK-4 sprawdza też metadata
każdego runtime workera. Full/delta są sprawdzane u producenta: SDK emituje rzeczywistym
ControlPlaneEmitter i canonical codec; full zawiera config/runtime, delta nie zawiera
config, kolejny full nadal zawiera zaakceptowany config. Metadata controllera ma dowód
u SwarmControllerStatusPublisher z kanonicznym codec. Nie tworzymy odbiornika CP w frameworku ani nowych kontraktów.
Odbiór: testy frameworka/producenta statusów, oba warianty przez ingress na obu
adapterach, verified REMOVE i registry404. Produkt i stare E2E poza tym wycinkiem.


Wynik WK-4/WK-5: framework 66/66, statusy SDK 4/4, controller 6/6. Deployed baseline
oraz overrides przeszły po 1/1 na każdym adapterze; regresja WK-1/WK-2 na Rabbit 2/2.
Sześć niezależnych swarmów, 18 próbek, 24 operacje SUCCEEDED, verified REMOVE bez
remaining/errors i registry404. Przywrócono Artemis, lista swarmów pusta. Szczegółowe
artefakty i ograniczenia są w mapie pokrycia. Bez zmian produktu, bez commita; do osobnego
review. Następny niezależny wycinek N2: NW-1 (HTTP przez wybrany proxy, konfiguracja,
binding i jego usunięcie); pozostałe NW/DA/EX/AU/lifecycle i N3/N4 nadal otwarte.


### WK-4/WK-5 — poprawka review F1 (TTL tapu)

Zakres zatwierdzony przez użytkownika: zamknąć tap bezpośrednio po pobraniu próbek.
Oczekiwanie na konfigurację, asercje i STOP następują poza zakresem otwartego tapu;
zachowujemy pobrane WorkItem do asercji. Bez zmiany TTL, interpretacji404 ani właścicieli
cleanup. Odbiór: kompilacja/testy frameworka oraz reprodukcja wolnego START/STOP
z review przechodząca po poprawce. Bez nowych zmian zachowania produktu.


F1 poprawione: tap zamyka się przed obserwacją konfiguracji/asercjami/STOP.
Framework66/66 oraz reprodukcja baseline/overrides2/2 zielone: TTL4s, zamknięcie po2.6s,
cały przebieg7.8s, cleanup potwierdzony. To izolowane odtworzenie odpowiedzi API,
bez ponownego deployed E2E ani zmiany stacka. Dowody w mapie pokrycia. Bez commita;
poprawka do osobnego review.


### N2 — NW-1 HTTP przez proxy — plan pass (2026-09-17)

Poprzednie wycinki WK-1–WK-5/SC-4, naprawy history/identity i F1 TTL tapu zapisano
na polecenie użytkownika w `a3cdf0d5`. Bez push.

NW-1 dodaje samodzielne fixtures HTTP proxy Rabbit/Artemis i jawny target profilu
oraz endpointu. Korzysta z istniejących lifecycle, capture, WorkerObservations i
HTTP assertions. TargetLoader pozostaje jedynym resolverem ustawień testu;
NetworkBindingApi tylko czyta canonical NetworkBinding przez ingress. ScenarioApi
czyta bundle-local SutEnvironment, bez alternatywnego źródła konfiguracji.

Odbiór: PROXIED + wybrany profil w CREATE; binding tego swarma wskazuje wybrany
endpoint i adresy jawnego SUT; świeży config procesora i URL w HttpResultEnvelope
zgadzają się z bindingiem; trzy poprawne odpowiedzi HTTP; zamknięcie tapu od razu
po capture; STOP/REMOVE oraz binding404 i registry404. Test nie wylicza portów proxy,
nie tworzy bindingów bezpośrednio i nie implementuje cleanup sieci. REMOVE produktu
pozostaje jedynym właścicielem usunięcia. Brak zmian kontraktów/publicznych manifestów,
nowych zależności i kopiowania legacy E2E. Testy frameworka oraz deployed przez
ingress na obu adapterach; NW-2++ i N3/N4 pozostają poza tym wycinkiem.


Wynik NW-1: framework72/72; deployed HTTP-proxy1/1 na Artemis i1/1 na Rabbit.
Sześć różnych WorkItems z HTTP200 przez adres proxy; zgodne SUT/binding/config/result;
osiem operacji SUCCEEDED, verified REMOVE, registry404 i binding404. Przywrócono
Artemis, publiczna lista swarmów pusta. Dowody i ograniczenia zapisane w mapie pokrycia.
Bez zmian produktu/starego E2E, bez nowego commita; wycinek do osobnego review.
Następny: NW-2 HTTPS przez proxy. Pozostałe NW/DA/EX/AU/lifecycle i N3/N4 nadal otwarte.


### Review NW-1 i większy wycinek sieciowy (2026-09-17)

NW-1: bez ustaleń w sześciu przeglądach (plan, styl, prostota, security, biblioteki,
czytelność). Sprawdzono TargetLoader/ProxyTarget, ScenarioApi/NetworkBindingApi,
LiveRun → SwarmResource/TapResource/WorkerObservations oraz produktowych właścicieli
SUT resolution i bindingów. Świeże72 testy frameworka zielone; ponownie odczytane
artefakty obu adapterów potwierdzają sześć HTTP200, osiem operacji SUCCEEDED i binding404.
Raport `/tmp/nw1-review.md`, testy `/tmp/nw1-review-tests.log`. Nie powtarzano deployed
NW-1 w samym review; native proxy absence nie wynika z tego testu.

Plan pass kolejnego pakietu: NW-2 HTTPS i NW-3 TCPS przez proxy oraz NW-5 TCP timeout.
Wspólne asercje bindingu zostają wyodrębnione z NW-1; brak kopiowania resolvera.
Osobne jawne fixtures/targety dla obu WORK adapterów. HTTPS potwierdza scheme i sslVerify;
TCPS canonical TcpResultEnvelope, adres i rzeczywistą odpowiedź. Timeout używa
istniejącego jawnego slow-response mocka, odczytanego przez `/tcp-mock/` ingress;
wynik to runtime.exception w journalu własnego swarma przy krótszym read timeout,
a nie niepoprawny WorkItem udający wynik. API journalu zastępuje bezpośredni odbiornik
CP. Sprawdzamy brak publikacji wyniku w jawnym oknie i verified cleanup. Odczyty mocka
nie zmieniają globalnych mappingów/journalu. NW-4 pozostaje otwarte: wymaga NFS topology.


Wynik większego pakietu: NW-2/NW-3/NW-5 mają PASS na Rabbit i Artemis. Framework78/78;
pięć przypadków na adapter (HTTP regresja, HTTPS, TCPS, delayed TCP control, TCP timeout),
łącznie10 zielonych E2E,20 odpowiedzi i40 operacji SUCCEEDED. Dwa przypadki błędu mają
alert właściwego runId/procesora i pusty tap wyniku przez jawne2s. Wszystkie swarms usunięte;
proxy binding404 w sześciu przypadkach. Dowody i ograniczenie wrapper exception są w mapie.
Przywrócono Artemis; lista swarmów pusta. Bez zmian produktu i bez commita; nowy pakiet
czeka na review. Macierz23 PASS/3 PARTIAL/15 OPEN, czyli18 pozycji do domknięcia.
NW-4 nadal wymaga środowiska NFS. Następny większy pakiet: pozostałe wymagania auth;
N3/N4 niezamknięte. Bez usuwania legacy E2E.

### 2026-09-17 — kolejny pakiet lokalny: provisioning i zakresy auth

NW-1/2/3/5 po review zapisane w e6883a03. Plan pass następnego pakietu:
AU-9 (dokładny grant bundle), pełne AU-10 (folder ALL kontra RUN-only) i AU-11
(refresh/reset uprawnienia). Własne UUID użytkowników, istniejące DTO/API oraz
SwarmResource/OperationAwaiter; brak alternatywnego silnika uprawnień lub lifecycle.
Auth nie oferuje DELETE użytkownika: jawny cleanup to brak grantów + inactive +
odrzucony login. Pozostawiony nieaktywny rekord jest ograniczeniem publicznego API.
Testy uruchamiamy przez ingress na obu WORK adapterach. NW-4 czeka na Swarm/NFS;
środowiska 1-host i 4-host są dostępne, deployment nie jest częścią tego pakietu.

Do tego pakietu dokładamy domknięcie FW-2: komponentowe próby limitu z receipt,
limitu operatora, pozostałego budżetu kolejnego odczytu i braku próbek przed timeoutem.
Korzystamy z istniejących Deadline/OperationAwaiter/TapResource; nie zmieniamy ich
semantyki ani nie wprowadzamy kolejnego mechanizmu czekania.

Wykonanie AU-9/AU-10/AU-11 i FW-2 zakończone: po3/3 E2E na Artemis/Rabbit,
93/93 testy frameworka i3/3 regresji istniejącego scoped runner. Sprawdzone dowody
usunięcia6 swarmów oraz dezaktywacji8 własnych użytkowników (bez grantów, login401).
Przywrócono bazowy Artemis. Szczegóły i identyfikatory artefaktów w macierzy.
Pakiet pozostaje niecommitowany do osobnego review.

Kolejność przed NW-4: AU-7/AU-12; SW-3 i pozostałe dowody właścicieli SW-1/SM-1;
SM-2 na jawnie świeżym, dedykowanym uruchomieniu. DA-1..4 i EX-1..3 wymagają
potwierdzenia wspieranych granic przygotowania/odczytu danych. Nie zastępujemy ich
bezpośrednimi portami Redis/ClickHouse ani czytaniem filesystemu kontenerów.
Nie oznacza to, że lokalna część planu jest już cała zamknięta. NW-4 zostaje na
późniejszy deployment przez HiveForge, zgodnie z docs/HIVEFORGE.md.

### 2026-09-17 — AU-7/AU-12: plan wykonania

Rozszerzamy istniejące API/zasoby frameworka o CRUD własnych scenariuszy/folderów
oraz konfigurację kontrolera, journal, pin, metadane, tap i konflikt network bez SUT.
Publiczne kontrakty/produkt pozostają bez zmian. Każda operacja asynchroniczna trafia
przez istniejący SwarmResource/OperationAwaiter. Osobne API HTTP nie może być drugim
właścicielem cleanupu lub wyniku operacji. Nowe fixtures/targety są jawne dla obu brokerów.
Runtime materialization sprawdzamy u właściciela w ScenarioManagerAuthFilterTest:
endpoint czyści cały swarmRoot i nie ma osobnego cleanup API, więc próba na aktywnym
swarmie niszczyłaby jego startup artifacts. Test komponentowy ma oddzielny temp root
oraz dowody braku skutku dla VIEW i skopiowanego pliku dla RUN. Nie nazywamy go E2E.
Pin/metadata journalu pozostają w historii: API nie ma DELETE/unpin; zapisujemy captureId.
Plan pass: ograniczony zakres auth, istniejące kontrakty/JDK/Jackson, jawne granty,
unikalne zasoby i weryfikowany cleanup. Nowy pakiet czeka potem na osobne review.


Wynik AU-7/AU-12: po3/3 nowych E2E na Artemis i Rabbit,107/107 testów frameworka,
21/21 ScenarioManagerAuthFilterTest oraz po3/3 regresji lifecycle na obu adapterach.
Materializacja runtime ma jawne dowody komponentowe; nie udajemy deployed testu.
Usunięto własne scenariusze/foldery/tapy/swarms, konta pozbawiono grantów i dezaktywowano.
Dwa piny i metadane pozostają w historii zgodnie z ograniczeniem API; captureId są w macierzy.
Przywrócono Artemis, publiczna lista swarmów pusta. Pakiet do osobnego review, bez commita.
Macierz29 PASS/1 PARTIAL/11 OPEN (12 pozycji do domknięcia). Następne: SW-3,
uzupełnienie SW-1/SM-1, SM-2 na świeżym dedykowanym target. DA/EX wymagają wspieranych
interfejsów przygotowania/obserwacji; NW-4 nadal czeka na Swarm/NFS. N3/N4 pozostają otwarte.


### 2026-09-17 — review auth i SW-3

Review niecommitowanego auth/FW-2: dwa P2 w cleanupie (niepotwierdzony user + pusty
odczyt oraz CONFIG_UPDATE403 blokujący REMOVE); poprawione,109 testów zielonych.
Pozostałe sześć passów bez dodatkowych ustaleń; raport /tmp/auth-review-report.md.
Plan pass SW-3: nowy jawny timeline dla obu adapterów, normalne CREATE/START, potem
wyłącznie obserwacja zmiany rate, pause/resume generatora i końcowego STOPPED przez
istniejący WorkerObservations. Journal tylko z API własnego swarma/runu; kroki mają
potwierdzone efekty w workerach. Dwa krótkie tapy potwierdzają HTTP przed pause i po
resume; REMOVE pozostaje w SwarmResource. Brak kopii schedulera, CP receivera,
legacy helperów i zmian produktu. NW-4/deployment nadal poza zakresem.


Wynik SW-3: PASS na Artemis/Rabbit. Na adapter: pięć obserwowanych faz, sześć HTTP200,
pięć kroków journalu we właściwej kolejności i jeden plan-completed. Test wysłał tylko
CREATE/START/REMOVE (3 SUCCEEDED); końcowy STOPPED pochodzi z planu. Cleanup zweryfikowany.
Regresja worker-config1/1 na każdym adapterze;109 testów frameworka zielonych. Przywrócono
Artemis i pustą listę swarmów. Macierz30 PASS/1 PARTIAL/10 OPEN (11 do domknięcia).
SW-3 do osobnego review, bez commita. Następne: dowody właścicieli SW-1/SM-1 i świeży
SM-2; nadal wymagane wspierane granice DA/EX, osobny deployment NW-4 oraz N3/N4.

### Next implementation slice: SM-1 and remaining SW-1 evidence

Add a read-only ingress smoke using the existing ApiTarget/HTTP/evidence owners.
Run named owner tests for CONTROL envelopes, topology effects, exact-key replay and
no rebroadcast at target state. Mock interaction evidence must remain distinguished
from real broker evidence. Keep any unproven SW-1 requirement explicit. SM-2 remains
reserved for a fresh dedicated deployment; NW-4 remains deferred to Docker Swarm.

Wynik SM-1/SW-1: smoke przez ingress1/1, framework110/110 oraz83/83 nazwanych testów
właścicieli (w tym3 testy granic importów), bez pominięć. Rabbit ma nowy izolowany
test prawdziwego brokera; Artemis używa istniejących testów z embedded broker.
Ponowienie tego samego klucza przed/po terminalnym wyniku nie wykonuje komendy ponownie;
START/RUNNING i STOP/STOPPED nie rozgłaszają ponownie. Konkretne dowody i rozróżnienie
mock/real/deployed w macierzy. **32 PASS / 0 PARTIAL / 9 OPEN**. Pozostają SM-2, NW-4,
DA-1..4, EX-1..3. Bez redeploymentu, zmian produktu, commita i push. Pakiet do osobnego
review. Następny lokalny zakres to rozpoznanie wspieranych granic DA/EX; SM-2 wymaga
świeżego dedykowanego deploymentu, NW-4 Swarm/NFS. N3/N4 nadal otwarte.

### 2026-09-17 — DA/EX boundary discovery and Redis fixture slice

Plan pass: existing nginx `/redis/` reaches Redis Commander. Deployed UI uses
GET/POST `apiv2/key/{connectionId}/{key}`, deletion by `?action=delete`. A unique
probe verified JSON POST creates one list item, GET reports list/length, DELETE
returns ok and subsequent GET reports type=none. The probe was removed.
Use this existing boundary with explicit connection id, one UUID-owned key per
resource, shared HTTP/evidence and verified cleanup. No native Redis client or
generic command executor. First implement and test resource safety and deployed
fixture preparation; then add independently authored dataset scenarios/templates
and assert actual Request Builder/Processor output on both WORK adapters.

EX-1..3: LocalDirectoryClearingExportSink currently writes worker-local files; no
public finalized-file listing/content observation was found. A supported export
observation contract still needs separate review; do not replace E2E with container
filesystem reads. DA-3 has an existing route through Grafana `/grafana/api/ds/query`:
its provisioned `clickhouse` datasource returned a successful count query scoped to
a unique nonexistent swarm (0 rows). This proves connectivity/query capability only,
not successful outcome persistence. No new product endpoint is required for that read.
This slice changes no product API/deployment/security contracts. NW-4 and
fresh-deployment SM-2 remain deferred.

Wynik pierwszego wycinka DA:118/118 testów frameworka oraz1/1 deployed
`RedisFixtureAcceptanceIT`, wyłącznie przez ingress. Dwie unikalne listy, celowy błąd
po zapisie, usunięcie pierwszej bez zmiany drugiej i końcowe type=none dla obu.
Log `/tmp/acceptance-redis-fixture.log`; dowody
`redis-fixture-8e60fe1e-cc2b-4f25-a6b8-7f11458e99e0`. Bez nowych bibliotek, zmian
produktu i commita. Nowy kod czeka na osobne review.

Macierz nadal32 PASS/9 OPEN: przygotowanie fixtures nie zamyka testów przetwarzania.
Następne: niezależne scenariusze DA-1/DA-2 z kluczami własnego testu, Request Builder
i Processor, dokładne payloady z tapów na Rabbit i Artemis. DA-3 może użyć istniejącej
Grafany; DA-4 łączy te same uchwyty Redis z odczytem własnego ruchu TCP przez ingress
(bez czyszczenia całego journalu). EX wymaga zaprojektowania publicznego odczytu plików.

### 2026-09-18 — DA-1/DA-2 implementation plan

NW-4 stays last per user decision. Next local slice: two independently authored
Rabbit/Artemis dataset bundles, two UUID Redis lists, per-test scenario via existing
Scenario Manager CRUD/template/SUT APIs. Check actual FULL history: exact Redis
payloads, rendered Request Builder HTTP envelope, successful processor output and
owned worker identity. Open tap before seeding, after fresh STOPPED/disabled worker observation; START follows seed; remove swarm before scenario/data.
Use shared target/HTTP/codec/resource owners, no native clients or product changes.

### 2026-09-18 — DA-1/DA-2 implemented, awaiting review

Redis → Generator → Request Builder → HTTP Processor passes on local Rabbit and
Artemis with two exact, isolated records and verified cleanup. Shared resource owners,
canonical WorkItem/HTTP contracts and Scenario Manager content APIs are reused.
Framework119 tests pass. Evidence and final run IDs are in the coverage ledger.
Preparation explicitly waits for disabled workers before seeding; finite input must
not be available during CREATE before capture is installed. The local stack was rebuilt,
then restored to Artemis WORK after Rabbit verification. No product behavior changed.

Remaining **7 OPEN**: DA-3, DA-4, EX-1..3, SM-2, NW-4. Next local slice: DA-3 outcome
storage observed through Grafana's existing ClickHouse datasource API. NW-4 remains
last, per user instruction. Separate review before accepting this implementation.

### 2026-09-18 — review correction: tap lifetime

DA-1/DA-2 seed/readback now precedes tap creation, after fresh STOPPED/disabled
worker observations. Only START and capture use the tap lifetime. Review finding1
(dependent fixtures deleted after unresolved swarm removal) remains open pending
the discussion of recovery; no automatic retry or orphan cleanup added.

### 2026-09-18 — review correction: dependent dataset cleanup

Finding1 implemented: RedisDatasetResources releases existing scenario/list handles
only when SwarmResource.permitsDependentCleanup allows it from its existing acquisition
state. No extra lifecycle state or retry. Unconfirmed CREATE, HTTP failure and terminal
FAILED removal preserve identifiers in retained-dataset-resources evidence and the
failure report. Definitively rejected/not-attempted CREATE and verified removal still
clean dependencies. Seven real-handle HTTP regression cases pass, including original
assertion preservation. Framework126 tests pass; Artemis dataset E2E passes with final
scenario404 and both Redis keys absent: `redis-dataset-7230bcc5-d264-4967-a580-f933d5bb77c2`.
Log: `/tmp/redis-cleanup-artemis.log`. Rabbit was not rerun for this cleanup-only fix.
The earlier tap-budget fix is also exercised. Both corrections await separate review.

### 2026-09-18 — DA-3 plan after DA-1/DA-2 commit

Reviewed DA-1/DA-2 committed as433e470f. Next: real tx-outcome persistence through
existing Grafana ingress; explicit datasource/table/auth target, scoped observations,
matching captured HTTP results by trace ID/swarm/sink instance. No new product API,
no direct DB port, no duplicate outcome calculator. Run on Rabbit and Artemis, keep
normal telemetry under its existing retention policy. NW-4 remains last.

### 2026-09-18 — DA-3 implemented, awaiting review

Tx-outcome persistence verified through existing Grafana ingress on both WORK adapters.
Each run starts with an empty observation for a fresh swarm and matches two captured
processor traces to actual stored rows, with sink identity, call ID, status/success and
duration checked. No duplicated TxOutcomeEvent or production projection/calculator.
Framework132 tests pass; four lifecycle operations and removal postconditions verified
per adapter. Artefacts/IDs are in the coverage ledger. Base Artemis restored.

Matrix35 PASS/6 OPEN: DA-4, EX-1..3, SM-2, NW-4. DA-4 is next local candidate; NW-4 last.
DA-3 remains uncommitted pending separate review. The reviewed preceding DA-1/DA-2
package was committed as433e470f at the user's explicit request; no push performed.

### 2026-09-18 — DA-3 committed; DA-4 plan

DA-3 committed as492d63b6 after separate review (32 focused tests, both deployed
artifact sets audited). DA-4 will verify all five customers through an isolated
RED/BAL/TOP/RED loop, exact TCP journal observations and captured results. Reuse
existing Redis/scenario/swarm handles; generalize only the dataset handle collection
and support explicitly owned initially empty producer lists. No shared data clearing,
new product APIs or production behavior changes. NW-4 remains deferred.

### 2026-09-18 — DA-4 implemented, awaiting separate review

Both Rabbit and Artemis pass the five-customer RED/BAL/TOP/RED loop through Redis,
Request Builder, TCP Processor and Redis output. Exact journal payload/response/order
and captured processor results replace the old any-request check. Seven UUID keys
and a private scenario are cleaned only after confirmed swarm removal; shared mock
state is unchanged. Framework141 tests pass; both deployed runs and cleanup artifacts
are recorded in the coverage ledger. No production code/contract/manifest changed.

DA-3 is committed as492d63b6; DA-4 remains uncommitted for separate review.
Matrix36 PASS/5 OPEN: EX-1..3, SM-2, NW-4. Next local work is export coverage;
NW-4 remains explicitly deferred until last.

### 2026-09-18 — DA-4 P2 corrected; awaiting review

Actual Redis source keys now travel in fixture-only sourceList attributes and are
matched to owned keys for each customer/stage, including return RED. The regression
rejects another customer's list for each of five customers. Red/green evidence plus
142 passing framework tests and both Rabbit/Artemis deployed runs are recorded in
the coverage ledger. Product behavior unchanged; correction not committed.

### 2026-09-18 — DA-4 committed; export layout corrected by human decision

DA-4 committed as ea4f428d. EX-1..3 still require actual finalized-file observation.
The human requires output inside the swarm directory. The proposed separate test
export root/mount is withdrawn; use the existing shared runtime filesystem, with one
output-path projection owned by RuntimeFilesystemLayout. The target run/worker-scoped
shape and integration sequence are documented in acceptance architecture.

FilesystemSwarmRemoveStore already recursively removes the swarm runtime directory.
Read/validate/export evidence before REMOVE; do not introduce a second test cleanup
owner. This correction changes the plan only, not runtime output behavior. Direct
local-file observation remains distinct from container inspection/remote access.
Matrix remains36 PASS/5 OPEN; NW-4 remains last.

### EX runtime output implementation — 2026-09-18

Human clarified mandatory swarm-owned output for every exporter, including config-update.
RuntimeFilesystemLayout now projects swarm/run/worker output directories; exporter
composition supplies the immutable projection directly to the existing sink.
Removed localTargetDir from configuration, capabilities and active demo scenarios.
File and manifest paths cannot escape the runtime output directory. Existing REMOVE
owns deletion, including pending/finalized output; no second cleanup path added.
69 focused tests passed: 57 exporter, 9 filesystem/layout/removal and 3 import-boundary
tests, including rejection before IO for escaping file/manifest paths. No redeployment yet; EX-1..3 and matrix remain OPEN.

### 2026-09-18 — output isolation committed; EX-1 implemented

Commit `bba0bcb9` contains reviewed mandatory exporter output directories. EX-1 now
uses canonical path projection for read-only local output observations and verifies
twenty distinct records in two finalized files on both Rabbit and Artemis.
148 framework tests pass; both deployed runs verify content before/after STOP and
normal REMOVE cleanup,20 absent Redis keys and scenario404. Evidence in coverage ledger.
Artemis WORK restored. New acceptance changes await separate review, not committed.
Matrix37 PASS/4 OPEN: EX-2(XML),EX-3(streaming),SM-2,NW-4. NW-4 stays last.

Local rebuild note: build-hive.sh built the requested exporter/Scenario Manager images
but partial compose-up included dynamic worker clearing-export, which is not a Compose
service. Completed the already-built Scenario Manager restart with compose. No script
refactor included in this test slice.

### 2026-09-18 — EX-1 committed; EX-2 implemented

EX-1 committed as `b7552ea8`. EX-2 proves applied structured config/schema and two
actual XML files of ten records on Rabbit and Artemis, with exact IDs/amounts and
per-file totals. Existing observation, lifecycle and cleanup reused.151 framework
tests pass, both runs cleaned20 Redis keys and their scenario after successful REMOVE.
Artifacts/logs recorded in coverage ledger. Artemis restored. New EX-2 changes await
separate review, uncommitted. No product changes or fresh deployment needed.
Matrix38 PASS/3 OPEN: EX-3(streaming window),SM-2(fresh deployment),NW-4(remote Swarm last).


### 2026-09-18 — EX-2 committed; EX-3 implemented

EX-2 committed as `132df24e`. EX-3 verifies applied streaming settings and one actual
file containing twenty exact records, header/trailer and no pending output before
STOP, below the100-record limit. Both Rabbit and Artemis finalized about15.6s after
START with a15s window; the ordinary flush interval is15min. Content remains unchanged
after STOP. Existing file observation, lifecycle and cleanup owners are reused.
152 framework tests pass; both deployed runs have four successful lifecycle operations,
20 absent Redis keys and scenario404. Artifacts and timing limits are in the coverage
ledger. Artemis WORK restored. No product changes; EX-3 awaits separate review.
Matrix39 PASS/2 OPEN: SM-2 (fresh dedicated deployment), NW-4 (remote Swarm/NFS last).


### 2026-09-18 — EX-3 review P2 corrected

The initial timing assertion used the instant after blocking START returned. A delayed
confirmation could therefore hide a file finalized too early. StreamingExportObservation
now samples read-only files concurrently with START, timestamps each snapshot in the
observer and returns its timeline. The caller checks every sample with finalized output
against the configured window before STOP. Lifecycle calls and evidence writes remain
on the caller thread. A single-thread executor is cancelled and closed before cleanup.

Controlled-clock regression covers a file at 5s with START confirmation at 20s (rejected),
a valid file at 15s before confirmation (accepted), early output accompanied by pending
files (rejected), and cancellation/join after START failure. Reintroducing the old timing
semantics produced RED (5000ms replaced by 20000ms); restoring the fix produced GREEN.
A full framework run also exposed premature observer shutdown with the initial virtual
executor; a single-thread executor fixed it and the cancellation regression now passes.
Final deployed evidence is recorded in the coverage ledger. Product code is unchanged.


### 2026-09-18 — SM-2 implemented and verified on a fresh local deployment

After the EX-3 correction passed Rabbit/Artemis, the human requested continuation.
FreshDeploymentAcceptanceIT now verifies a declared fresh target with deployment-wide
administrator grants and an empty canonical public swarm list. TargetLoader requires
an explicit deploymentId; no freshness is inferred from the result and no reset is used.
The test reuses ApiRun, ActorAssertions and SwarmApi. Canonical Compose plus a test-only
isolation overlay provisioned a separate local project with new network/volumes/runtime
and public ingress18088. Platform health and SM-2 both passed; creation log, image IDs,
actor and response are retained in the coverage ledger's artifact directory.
157 framework tests pass. The disposable project was removed; existing Artemis on8088
remains available. No production changes or commits. Separate review remains next.
Matrix40 PASS/1 OPEN: NW-4, the deferred remote Swarm/NFS deployment test.


### 2026-09-18 — EX-3 evidence and SM-2 shell-scope P2 corrections

EX-3 now saves its immutable timeline through RunEvidence after joining the observer,
even on timeout, read failure, START failure or interruption. Original failures survive
artifact-write errors. Two missing-artifact regressions produced RED before the fix;
161 framework tests now pass. SM-2's complete procedure and failure recovery run in
subshells, preserving the operator's prior environment. Six stubbed shell scenarios
verify normal/failure exits and scoped cleanup, without deploying a stack.
Details and logs are recorded in the coverage ledger. No product change or E2E rerun;
new changes remain uncommitted for separate review. Matrix40 PASS/1 OPEN: NW-4.


### 2026-09-18 — przygotowanie N3/N4 przed NW-4

Commit `cdf2be57` zamyka sprawdzony pakiet EX-3/SM-2. Czysty, niezależny build
`-pl acceptance-tests -am clean verify` przeszedł: 161 testów frameworka oraz
jego zależności. Reactor obejmuje dziewięć modułów; nie buduje `e2e-tests`, usług
produktu ani adapterów brokerów. Runner nadal wywołuje wyłącznie nowy moduł.
Nie jest to zamknięcie N3 ani osobne review zgodności całej macierzy z asercjami.

**Retencja dowodów:** wykonujący agent nie zabezpieczył `target/runs` przed clean.
Nie znaleziono pełnej kopii usuniętych katalogów. Pozostałe logi i podsumowania
nie są odtworzonymi artefaktami. Jawne lokalne targety oraz instrukcja SM-2 wskazują
teraz `acceptance-tests/runs`; katalog jest wyłączony z Git i kontekstu Docker.
TargetLoader i RunEvidence zachowują dotychczasowe odpowiedzialności. Nie dodano
defaultu, walidatora lokalizacji ani nowego mechanizmu archiwizacji do frameworka.
Przed N3 trzeba rozstrzygnąć wystarczalność zachowanych dowodów lub odtworzyć
wymagane przebiegi. Nowe wykonania są opisywane osobno w macierzy.

**Zakres późniejszego N4 — nic jeszcze nie usunięto:**

| Element | Ustalona własność i działanie po N3 |
| --- | --- |
| `e2e-tests/` | Cały stary moduł: Cucumber, kroki, klienci, wsparcie, konfiguracja i fixtures testowe. Usunąć razem. Zależności Cucumber są zadeklarowane wyłącznie w jego POM. |
| `start-e2e-tests.sh`, `deploy/e2e-targets/` | Runner i jego profile należą do starego zestawu. Usunąć bez aliasu/delegacji do nowego runnera. |
| Główny `pom.xml` | Usunąć wpis modułu `e2e-tests`. Zachować wspólne biblioteki produktu oraz `acceptance-tests`. |
| `RepositoryImportBoundaryTest` | Usunąć osiem wyjątków importów dla nieistniejącego już `e2e-tests`; zachować reguły i pozostałych właścicieli. |
| `.github/workflows/ci.yml` | CI uruchamia ogólne `mvn test`; nie ma wywołania starego runnera do przepięcia. Usunięcie modułu usuwa go z reactora. Wdrożone acceptance nadal wymagają jawnego targetu. |
| `scenarios/e2e/` | Nie jest katalogiem wyłącznie starego frameworka. `tools/auth-proving/run-auth-proving.mjs` używa zestawu `auth-proving-*`. Każdy kandydat do usunięcia wymaga sprawdzenia konsumentów; nie kasować katalogu hurtem. |
| Dokumentacja i historia | Bieżące polecenia mają wskazywać nowy runner. Dawne raporty i changelog pozostają opisem historycznych wykonań, nie uruchamialną zależnością. |

Podstawa inwentaryzacji: wyszukiwanie w całym repozytorium `e2e-tests`,
`start-e2e-tests`, `e2e-targets`, `cucumber`, `scenarios/e2e` oraz nazw fixtures;
odczyt POM, obu runnerów, workflow CI, reguł importów i konsumentów auth-proving.
Nowy moduł ma jawną blokadę zależności od legacy i używa własnych fixtures
`scenarios/acceptance` oraz `demo/acceptance-runner-artemis`.

**NW-4, rozpoznanie bez deploymentu:** HiveForge udostępnia środowisko `swarm`
z czterema gotowymi węzłami i współdzielonym NFS. Profil PocketHive to `swarm-full`.
W zwróconych rejestracjach brak `codex/artemis-work-plane`; projekt development
wskazuje inną gałąź. Nie przestawiono repozytorium, polityki ani działającego stacka.
Do wykonania potrzebny jest dostępny przez HiveForge ref/artefakt właściwej wersji,
publiczny ingress i aktor testowy oraz test NW-4. Samo istnienie czterech węzłów
nie dowodzi rozdzielenia NPM/HAProxy ani poprawnego zachowania NFS.


Weryfikacja retencji i bieżących targetów: 161 testów frameworka oraz pięć wdrożonych
przypadków (smoke, trzy lifecycle, EX-3) przeszło. Sześć rzeczywistych plików dowodów
przetrwało późniejszy clean bez zmiany skrótów. Przeszło też sześć wariantów shellowych
SM-2 z podstawionymi poleceniami. Artefakty i archiwalne raporty JUnit leżą już poza
`target`; dokładne nowe run IDs podaje macierz. Rabbit i pozostałych grup nie ponawiano.
Końcowa lista swarmów pusta. Zmiany konfiguracji i dokumentacji czekają na osobne review;
N3/N4 pozostają otwarte. Nie wdrożono nic na zdalnym Swarmie.


### 2026-09-18 — NW-4: lokalne wykonanie zatwierdzone

Użytkownik zatwierdził napisanie NW-4 i sprawdzenie najpierw na lokalnym deploymencie;
eventualne problemy Swarm będą rozwiązywane podczas późniejszego wykonania.
Plan: zwykły własny PROXIED swarm → ruch HTTP → STOP → błędny kandydat przez publiczne
API → jawny błąd apply i identyczny poprzedni binding → nowy tap/START i poprawny ruch
→ STOP → jawny clear/GET404 → normalny REMOVE. Brak osobnego właściciela cleanupu.
TargetLoader zachowuje konfigurację, NetworkBindingApi mapuje istniejące endpointy,
RunEvidence zapisuje dowody poza target. Nie kopiujemy starego harnessu.
Kontrakt testu zapisano przed implementacją w architecture/acceptance-tests.md,
sekcja Binding recovery acceptance (NW-4). Nie zmieniamy kontraktów ani kodu produktu.


### 2026-09-18 — NW-4 wykonane lokalnie; do osobnego review

Nowy test przeszedł na obu lokalnych WORK adapterach. Błędny kandydat dostał500 po
10082ms (Artemis) i10049ms (Rabbit); poprzedni binding pozostał identyczny. Osobne tapy
zarejestrowały po trzy różne poprawne wyniki HTTP przed i po odrzuceniu. Jawny clear
zwrócił200, potem binding404. Każdy swarm ma sześć SUCCEEDED operacji, kompletny REMOVE
bez remaining/errors oraz potwierdzone zniknięcie bindingu i wpisu swarma.

177 testów frameworka PASS, w tym zachowane testy odczytu/braku bindingu i nowe testy
mutacji, negatywnych asercji oraz konfiguracji czasu. `TargetLoader` wspólnie rozwiązuje
ustawienia ProxyTarget/BindingRecoveryTarget; NetworkBindingApi jedynie przesyła
kanoniczne DTO i zwraca odpowiedzi. Sprzątanie pozostało w SwarmResource/Orchestrator.
Nie zmieniono kodu ani kontraktów produktu, nie kopiowano legacy i nie wdrażano na Swarm.

Logi, run IDs i archiwalne raporty JUnit podaje macierz pokrycia. Przywrócono Artemis
WORK po pustym rejestrze. Bieżący zakres gotowy do osobnego review, bez commita/push.
Lokalne przypadki funkcjonalne są wykonane; NW-4 pozostaje PARTIAL wyłącznie z powodu
niewykonanego testu między hostami/NFS. N3/N4 nadal otwarte.


### 2026-09-18 — N3: uzupełnienie przejścia DA-3

Porównanie starych wymagań z faktycznymi nowymi asercjami wykazało brak runtime
config-update w DA-3. Dotychczasowy test dowodzi zapisu przy CLICKHOUSE_V2 już w
scenariuszu; nie dowodzi włączenia sinka w działającym swarmie. Uzupełniamy test:
NONE z rzeczywistym ruchem i pustą obserwacją → CONFIG_UPDATE na istniejącym API →
świeża konfiguracja CLICKHOUSE_V2 → nowe próbki i zgodne zapisane wyniki. Mapowanie
pozostaje w SwarmManagementApi, operacja i cleanup w SwarmResource/OperationAwaiter.
Metoda konfiguracji komponentu przyjmuje jawną rolę zamiast specjalizacji tylko dla
controllera; obecny test AU-12 pozostaje jej konsumentem. Nie zmieniamy produktu,
publicznego kontraktu, klientów brokerów ani kodu starego frameworka.


### 2026-09-18 — N3 lokalnie: analiza i wykonania zakończone

Raport `docs/ci/acceptance-replacement-review.md` zawiera porównanie wszystkich
41 wierszy macierzy z rzeczywistymi asercjami, właścicielami i dostępnymi dowodami.
DA-3 po poprawce potwierdza runtime włączenie sinka na Rabbit i Artemis; AU-12
zachowuje dotychczasową ścieżkę konfiguracji controllera przez ten sam mapper.
178 testów frameworka, 126 wybranych testów właścicieli i 67 wdrożonych wykonań
przeszło bez błędów/pominięć. Artefakty zachowane poza `target`. Lokalny stack
przywrócony do Artemis WORK; rejestr swarmów pusty.

Następne: osobne review poprawki DA-3 i zbiorczego raportu, NW-4 między hostami
Swarm/NFS, końcowy odbiór N3, dopiero potem N4. Nie usunięto legacy, nie wdrażano
zdalnie i nie wykonano commita ani push. Bieżąca macierz: 40 PASS / 1 PARTIAL.

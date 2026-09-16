# Nowy framework E2E — budowa od zera i zastąpienie starego zestawu

Status: kierunek ustalony przez użytkownika 2026-09-15; projekt wykonania poniżej.
N0: projekt i mapa 39 dotychczasowych scenariuszy zapisane. N1: wykonanie zamknięte,
oba pierwsze testy przeszły na Rabbit i Artemis. N2: pierwszy przypadek operacji
w osiągniętym stanie także przeszedł na obu adapterach; pozostałe pokrycie otwarte.
N3–N4 niewykonane. Stary zestaw pozostaje bez zmian.

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

Po potwierdzeniu N3 usunąć cały stary moduł wraz ze starymi krokami, klientami,
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
oraz OperationAwaiter i wspólnego cleanupu. Przeszedł na obu adapterach; jest materiałem
do osobnego review. Dokładny replay tego samego klucza i brak ponownej emisji CP nie są
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

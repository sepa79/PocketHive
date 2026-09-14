# Rabbit SSOT i izolacja WorkPlane — plan domknięcia

Status: odbiór techniczny uzgodnionego zakresu R1–R6 zakończony 2026-09-14; pakiet gotowy do PR.
Zaimplementowano bieżący zakres transferu i wykonano weryfikację R6 z opisanymi niżej lukami. Natywny manifest i osierocony
cleanup z R4 zostały jawnie odłożone przez użytkownika. Ten plan zastępuje wcześniejszą kolejność
„izolacja + Artemis w jednym PR”. Bloker SSOT selektora R6-REV-1 ze zbiorczego review
2026-09-14 został poprawiony na polecenie użytkownika; regresje przed/po opisano w raporcie.
Review poprawki i normalne E2E wydzielonego pakietu Rabbit przeszły. O1/O2 Orchestratora
pozostają osobnymi zmianami, poza tym pakietem.

## Cel i granica odbioru

Domknąć jednego właściciela Rabbit oraz pełną granicę WorkPlane. Produkcyjna implementacja
pozostaje Rabbit; druga implementacja jest małym, stanowym adapterem testowym w pamięci.
Control Plane nadal używa Rabbit przez to samo API technologii, z oddzielnym połączeniem.

Odbiór dotyczy całej ścieżki: wybór → walidacja/rozwiązanie konfiguracji i adresów → utworzenie
zasobów → transport → status/statystyki/diagnostyka → usunięcie i sprawdzenie nieobecności.
Same interfejsy Input/Output albo przeniesienie klas nie zamykają tego zadania.

**Artemis, broker Artemis, delayed publish i projekt jego kontraktu należą do osobnego,
późniejszego PR:** [Artemis/3DS](../todo/work-plane-artemis-3ds.md). Nie są warunkami odbioru
izolacji Rabbit. I/O pozostaje szersze od WorkPlane: Redis, CSV i scheduler zachowują obecne role.

## Punkt wyjścia — zachować działających właścicieli

- `common/rabbit-adapter`, namespace `io.pockethive.rabbit`, już posiada Rabbit API, klientów,
  konfigurację/defaults, nazwy, operacje zasobów i mechanikę transportu dla obu planes.
- `RabbitWorkSettingsBootstrap` waliduje ustawienia przez parser Rabbit; `RabbitWorkEnvironment`
  eksportuje jego wynik do ENV. Spring w workerze odtwarza typy. W1 z review workerów jest
  wycofany; nie przepisywać bindingu ani nie dodawać walidatora na podstawie tamtych prób.
- `DefaultWorkerRuntime` publikuje wynik przez jeden WorkOutputRegistry. Funkcje dziewięciu
  workerów operują na WorkItem; zachować ich logikę i istniejący WorkItemJsonCodec.
- Nazwy i broker mechanics już mają właściciela. Brakuje domknięcia wyboru pełnego WorkPlane
  w bootstrapie, SDK, Swarm Controllerze i Orchestratorze.

Właścicieli i ograniczenia definiuje [boundary design](../architecture/work-plane-boundaries.md).
Aktualne nagłówki kodu odwołują się do [runtime responsibilities](../architecture/runtime-responsibilities.md).
Podczas przenoszenia implementacji aktualizować odpowiadające im rekordy i nagłówki; ten plan
nie ogłasza przyszłych właścicieli jako już zaimplementowanych.

## Kolejność wykonania

### R1 — domknąć kontrakty i kierunek zależności

- Spisać minimalne kontrakty wybranego WorkPlane: konfiguracja, wynik rozwiązania topologii,
  tożsamość zasobów, operacje zasobów/obserwacji oraz transport. Użyć istniejących portów
  w `work-config`, `work-api`, `topology-core`, gdzie pasują. Nie tworzyć uniwersalnego broker managera.
- Neutralny wynik topologii przenosi jawne dane właściciela. Nie wymaga wymyślonego exchange
  dla każdego adaptera ani nie wyprowadza routingKey z queue. Projekcje dla ENV, statusu,
  statystyk i cleanup pochodzą z tego samego wyniku; brak drugiego mutable rejestru topologii.
- Wybór adaptera i wymagane ustawienia są jawne. Nieobecny/niejednoznaczny provider powoduje
  błąd przed skutkami. Nie dodawać fallbacku ani automatycznego wyboru na podstawie obecnych beanów.
- Wydzielić tylko zależności potrzebne I/O: adapter nie może wymagać konkretnego WorkerDefinition
  ani zagnieżdżonego stanu WorkerControlPlaneRuntime. SDK przekazuje neutralne dane/callbacki
  do istniejącego wykonania. Nie przepisywać całego runtime ani jego maszyny stanów.
- Kod adapterowy Work↔Rabbit wydzielić do pakietu `io.pockethive.rabbit.work` w istniejącym
  `common/rabbit-adapter`, implementującego neutralne kontrakty. Reguły Work i pojedynczy dispatch
  pozostają w SDK; most deleguje kodowanie do kanonicznego codec. Klientów/namingu/parserów
  Rabbit nie kopiować do nowego modułu. Moduł Rabbit nie zależy od worker-sdk ani usług.
- Kompozycja wybiera konkretny adapter poza neutralnymi konsumentami. Przenosząc fabryki i ich
  konfigurację usuwać stare bezpośrednie konstruktory; nie tworzyć cyklu zależności SDK↔adapter.
- Przy tych kontraktach rozpocząć mały stanowy fake. Pierwszy przenoszony konsument ma mieć
  test efektów na Rabbit i fake; rozszerzać fixture wraz z R2–R4. R5 domyka pokrycie całej
  ścieżki, nie odkłada pierwszej próby drugiego adaptera do końca refaktoru.

Przed zmianą publicznych pól/tożsamości uzupełnić właściwy kontrakt REST/schema i uzyskać jego
review zgodnie z AGENTS.md. Zachować istniejącą reprezentację Rabbit i rozróżnienie CONTROL/WORK.
Nie dodawać pól ani enumów Artemis. Ten krok nie zmienia reguł domenowego routingu Control Plane.

### R2 — rozdzielić bootstrap i eksport wybranego WORK

- Rozdzielić Rabbit CONTROL od tworzenia Rabbit WORK w RabbitConnectionConfiguration,
  RabbitTransportAutoConfiguration i kompozycji zasobów/listenerów.
- WorkConnectionEnvironmentResolver i ControlPlaneContainerEnvironmentFactory mają pobierać
  konfigurację WORK od wybranego właściciela. Brak Rabbit WORK przy adapterze testowym jest
  prawidłowy; konfiguracja CONTROL pozostaje wymagana. Rabbit WORK nigdy nie dziedziczy CONTROL.
- Usunąć gałęzie rozpoznające konkretne Properties/projekcje Rabbit z neutralnego discovery SDK.
  Wybrany provider dostarcza konfigurację i jej projekcję. Zachować dokładnie jedną fabrykę
  dla każdego kierunku oraz obecny pipeline walidacji przed utworzeniem klientów/zasobów.
- Scenario Manager używa istniejącego WorkConfigurationParser i wspólnych providerów. Nie
  tworzyć lokalnej walidacji ani refaktorować zarządzania plikami/katalogiem scenariuszy.

Odbiór: wybrany WORK działa bez ustawień Rabbit WORK, przy zachowanym CONTROL Rabbit;
niepoprawny kandydat nie tworzy zasobów i nie zastępuje zaakceptowanej konfiguracji.

### R3 — przełączyć całą ścieżkę Swarm Controllera

- SwarmLifecycleManager/SwarmRuntimeInfrastructure delegują create/observe/remove WORK do
  wybranego adaptera. Zachować osobnych właścicieli compute, CONTROL i lifecycle swarma.
- WorkerWorkConfigurationAdapter korzysta z wyniku wybranego właściciela, a SwarmWorkerSpecFactory
  przekazuje tę samą konfigurację/projekcję do workera. Usunąć bezwarunkowy wybór RabbitResourceNames.
- SwarmWorkBindingsProjector i SwarmQueueStatsPortAdapter/collector projektują dane adaptera.
  Buffer guard zachowuje algorytm i otrzymuje statystyki przez istniejącą granicę obserwacji.
- Rabbit-specific deklaracje, mapowanie ustawień i nazwy pozostają po stronie modułu Rabbit.
  Nie przenosić całego managera swarma do adaptera.

Odbiór: utworzone zasoby, konfiguracja wykonywana przez worker, status i statystyki dotyczą tych
samych adresów. Wynik błędu/pustej obserwacji nie jest zamieniany na udany efekt lub wymyślone zero.

### R4 — domknąć zasoby i diagnostykę w Orchestratorze

- Przełączyć WORK w planowaniu zasobów, manifestach, RuntimeRemovalPostconditionVerifier oraz
  diagnostyce Work/DebugTapService na tożsamość i API wybranego adaptera. Tożsamość zachowuje
  scope/plane i właściciela zasobu; adapter testowy nie udaje typu RABBIT_QUEUE/RABBIT_EXCHANGE.
- Przenieść Rabbit-specific mapowania do jego implementacji. Odpytywanie adaptera o efekt jest
  czym innym niż domenowa decyzja o zakończeniu operacji — właściciel tej decyzji się nie zmienia.
- Zachować uprawnienia cleanup, planowanie przed wykonaniem, fingerprint połączenia i weryfikację
  nieobecności zasobów. Błąd brokera nie oznacza nieobecności; zasób nadal obecny blokuje sukces.
- Diagnostyka używa rozwiązanych adresów wybranego WORK. Możliwość nieobsługiwana przez adapter
  ma jawny wynik; nie uruchamia w zastępstwie operacji Rabbit. Obecne funkcje Rabbit zachować.
- UI/MCP nadal konsumują projekcje właściciela. Zmienić tylko kontrakty/projekcje wymagane przez
  tę ścieżkę; CONTROL STOMP i jego informacyjny endpoint pozostają na Rabbit.

Odbiór: usunięcie WORK jest potwierdzone odczytem u jego właściciela i nie dotyka CONTROL,
również przy takich samych nazwach zasobów. Diagnostyka WORK nie wybiera Rabbit na stałe.

### R5 — domknąć testy zachowania całej ścieżki

- Uzupełnić rozwijany od R1 minimalny stanowy fake o wszystkie potrzebne kontrakty: konfigurację/adresy,
  create/read/delete zasobów, send/receive wiadomości i obserwacje/statystyki. NOP zwracający
  zawsze OK nie spełnia tego celu. To test fixture, bez nowej produkcyjnej opcji NOP/mock.
- Rejestrować go jawnie w kompozycji testowej z własną tożsamością i adresami. Nie podszywać
  się pod Rabbit. Nie odtwarzać całego brokera, rozproszonego transportu ani mechanizmu opóźnień.
- Testy komponentowe składają rzeczywistych konsumentów z jednym stanem fake'a w obrębie procesu
  testowego. Oddzielne kontenery nie współdzielą pamięci; nie planować „E2E in-memory” pomiędzy nimi.
- Przez publiczne API komponentów sprawdzić pełny przebieg: konfiguracja → zasoby → przyjęcie
  wiadomości → wykonanie workera → pojedyncze wyjście → obserwacja → usunięcie/potwierdzenie.
  Sprawdzić też odrzucenie konfiguracji przed skutkami, zachowanie poprzedniego stanu oraz
  nieudane usunięcie. Jawne adresy fake'a ujawniają rekonstrukcję Rabbit poza właścicielem.
- Dobierać testy do efektów, nie do nazw beanów, klas czy liczby wywołań wrappera. Fake wspiera
  izolację, lecz właścicieli nadal weryfikują review wywołań oraz ograniczenia zależności/importów.

### R6 — usunąć stare ścieżki i odebrać refaktor

- Usunąć zastąpione helpery, konstruktory, wybory Rabbit w neutralnych konsumentach i duplikaty
  reguł. Jedna implementacja każdej reguły Rabbit zostaje w `common/rabbit-adapter`.
- Uaktualnić istniejące Maven Enforcer i RepositoryImportBoundaryTest do rzeczywistych granic.
  Test fixture pozostaje zależnością testową. Żadnego nowego skanera ani frameworka pluginów.
- Przenieść/adaptować testy zachowania razem z właścicielami i uruchomić testy dotkniętych modułów.
  Zebrać wyniki fake'a oraz regresji Rabbit/CONTROL, zgodność konfiguracji i sprawdzenie cleanup.
- Odbiór lokalnego stacka: kanoniczny build-hive.sh i normalny start-e2e-tests.sh przez oficjalny
  ingress; uwzględnić istniejący browser smoke CONTROL/schema/STOMP. Nie zastępować tego
  testami bezpośrednich portów usług. Szczegóły uruchomienia w docs/USAGE.md.
- Przekazać do zbiorczego review krótki wykaz właściciel → konsumenci → usunięte alternatywy,
  wyniki testów i ograniczenia. Gate obejmuje również oczekujące lokalne poprawki Rabbit.
  Nie uruchamiać automatycznego cyklu review/fix ani nie przydzielać subagentów.

## Jawne odroczenie części R4 — 2026-09-14

Użytkownik zatwierdził lifecycle `WORK_RESOURCE`/`WORK`, lecz odłożył dodatek
`manifest.workResources`, `DELETE_WORK_RESOURCE`, `includeWorkResources`.
Manifest i orphan cleanup pozostają Rabbit-only. Natywny manifest jest odrzucany przed
skutkami; fake nie udaje Rabbit. Pełne uruchomienie Orchestratora na fake'u i osierocony
cleanup natywnych zasobów nie są odbiorem bieżącego zakresu. Zachować jawny zapis luki R4;
pozostałe testy Controller/worker oraz verifier nie zastępują tej odłożonej próby.
Szczegóły: `docs/todo/work-plane-artemis-3ds.md`.

Użytkownik odłożył również poprawę kompletności diagnostyki UI/MCP do osobnego refaktoru.
Runtime inspector odczytuje aktualny stan zasobów z ograniczonej listy manifestu/deskryptorów;
nie jest pełnym bieżącym wykazem zasobów brokera. Pusta lista nie dowodzi ich nieobecności.
Faktyczne zachowanie i zakres odroczenia zapisano w
[planie diagnostyki](runtime-debug-mcp-cleanup-spec.md#deferred-diagnostic-completeness--user-decision-2026-09-14).
To ograniczenie nie jest naprawiane przez samo dodanie natywnego manifestu dla Artemis.

## Niezmienne zachowanie i wyłączenia

Rabbit zachowuje nazwy, routing, WorkItem envelope, tuning i izolację CONTROL/WORK.
ACK jest na powrocie callbacku; async po przekazaniu do wykonania. Błędy dekodera/dispatch są
raportowane bez requeue, executor rejection zachowuje dotychczasowy synchroniczny dispatch,
disabled invocation zwraca null. Publisher confirms pozostają nieaktywne. Bez nowych retry,
shutdown/drain, DLQ, migracji, compatibility ani przywracania usuniętego legacy binding cleanup.

Zachować zaakceptowany limit ochrony przed pośrednimi ENV overrides. Redis SEL-R1, naprawy
Scenario Managera S1–S10, reset/correctness Orchestratora, szerszy lifecycle Swarm Controllera,
Docker, journal, auth/templates i pozostałe refaktory pozostają osobnymi PR-ami.
Legacy Node debug tooling i starsze fixture naming mają wcześniejsze jawne wyłączenia;
nie ogłaszać ich migracji. Produkcyjne Java Work diagnostics należą do R4.

## Definition of done

- Rabbit jest jednym właścicielem technologii dla CONTROL i WORK; neutralni konsumenci nie
  odtwarzają jego konfiguracji, nazw, operacji ani reguł sukcesu.
- Cała ścieżka WORK działa przez wybranego właściciela. Stanowy fake przechodzi testy zachowania
  bez Rabbit WORK; istniejący Rabbit przechodzi regresję z niezmienionym CONTROL.
- Wszystkie projekcje pochodzą z kanonicznego rozwiązania, stare alternatywy są usunięte,
  dokumentacja/nagłówki/importy odpowiadają kodowi, a zbiorcze review domyka rzeczywiste wywołania.
- Artemis można następnie dodać przez tę granicę. Jego implementacja i delayed publish nie są
  potrzebne do uznania izolacji Rabbit za zakończoną.

## Materiał do implementacji i stan weryfikacji

Czytać ten plan, boundary design, AGENTS.md i aktualny kod. Punkty styku:
[Swarm Controller review](../architecture/swarm-controller-work-plane-review-2026-09-14.md) oraz
[worker/SDK review](../architecture/workers-work-plane-review-2026-09-14.md).
Odwołania tych raportów do Artemis wskazują przyszły konsument granicy; W1 jest wycofany,
a sam brak delayed publish nie jest blokerem tego planu.

### Wykonanie — 2026-09-14

Punkt odniesienia implementacji: commit planu `817113a1`. Końcowy pakiet do commita
wydzielono z katalogu roboczego i zweryfikowano bez oczekujących poprawek O1/O2 Orchestratora.

| Zakres | Właściciel i rzeczywiści konsumenci | Usunięta droga |
| --- | --- | --- |
| R1: transport i wybór I/O | `WorkIoType`, parser/provider ports; Rabbit w `rabbit.work`; wykonanie i pojedyncze wyjście w SDK | Klasy Rabbit Work w SDK, zamknięte enumy w neutralnych sygnaturach, osobny Rabbit dispatch |
| R2: bootstrap | CONTROL ma osobny eksport; WORK: `WorkAdapterEnvironment`; discovery używa deskryptorów | Wymaganie Rabbit WORK przez CONTROL; Rabbit materialization w Controllerze; wybór klas Rabbit w discovery |
| R3: topologia i zasoby | `WorkTopologyResolver` → `ResolvedWorkTopology`; worker ENV, provisioning, guard/status/stats/remove konsumują wynik | `SwarmWorkTopologyManager`, osobne listy logicznych kanałów, lokalny cache deklaracji |
| R4: lifecycle i diagnostyka | Verifier używa wybranego WORK; `WorkDebugTaps` → Rabbit; `WORK_RESOURCE`/`WORK` zatwierdzony i wdrożony | WorkResourceNamesPort, Rabbit capture operations w DebugTapService, odczyt WORK przez CONTROL |
| R5: adapter w pamięci | `WorkPlaneFlowTest`: Controller bootstrap → typed worker binding → config-update → wykonanie → jedno wyjście → statystyki → usunięcie; osobno verifier | Dotychczasowy test transportowy z metadanymi RABBITMQ zastąpiony pełną próbą z własnym typem MEMORY |
| R6: stare ścieżki i odbiór | RabbitWorkAddress/TopologySettings przeniesione do Rabbit API; aktualizowana istniejąca tabela importów | Stary port nazw i zastąpione konstruktory/helpery; bez nowego skanera |

Guard pobiera adresy przyjętej topologii; dodatkowy alias obserwacji jest rozwiązywany u tego
samego właściciela bez deklarowania zasobu. Partial prepare zachowuje cleanup zakończonych
bindingów. Błąd odczytu propaguje się; algorytm guard i semantyka ACK pozostają niezmienione.

Fake jest wyłącznie fixture testowym z jednym stanem w procesie. Próba odrzuca błędną konfigurację
przed utworzeniem zasobów, zachowuje zaakceptowany stan po błędnym update i sprawdza nieudane
usunięcie aktywnego wejścia. Błąd workera jest raportowany bez ponownego dostarczenia. Rabbit
WORK nie jest wymagany. Diagnostyka nieobsługiwana przez wybrany adapter zwraca HTTP 501.

**Jawna luka R4, odłożona decyzją użytkownika:** natywny manifest i osierocony cleanup.
Manifest Orchestratora zachowuje obecny format Rabbit i odrzuca natywne zasoby przed skutkami.
Nie ogłaszać pełnego uruchomienia Orchestratora na fake'u ani tej części planu jako odebranej.

Weryfikacja — wcześniejsze przebiegi i końcowy pakiet do commita:

| Próba | Wynik / dowód lokalny |
| --- | --- |
| Czysta regresja SDK, Controller, Orchestrator i zależności, bez ScenarioControllerTest | 1263 testy, 0 błędów, 1 pominięty; `/tmp/workplane-r6-clean-tests.log` |
| Poprawiony import w TriggerSchedulerIntegrationTest | 1 test przeszedł; `/tmp/workplane-r6-trigger-test.log` |
| Generator kontraktu + UI schema tests | Kontrakt aktualny; 3 testy UI przeszły; `/tmp/workplane-r6-ui-schema-tests.log` |
| Kanoniczny rebuild/redeploy | `build-hive.sh --quick` ukończony; `/tmp/workplane-r6-build-hive.log` |
| UI/schema/CONTROL STOMP przez ingress | Połączenie `/ws`, subskrypcja zgodna z info Orchestratora, schema 200/ETag/304, WORK_RESOURCE obecny, Buzz valid, brak błędów konsoli; `/tmp/workplane-r6-browser-smoke.json` |
| Normalny start-e2e-tests.sh, bez filtrowania scenariuszy | 39/39 scenariuszy, 463/463 kroki, kod wyjścia 0; `/tmp/workplane-r6-e2e.log` |
| Pełny root reactor po naprawie ScenarioControllerTest, standardowy profil bez nowych wykluczeń | `./mvnw -fae clean test`: 45 modułów SUCCESS, 1858 testów przeszło, 1 warunkowo pominięty, 0 błędów; `/tmp/scenario-controller-fix-full-reactor-complete.log` |
| Pełny root reactor po usunięciu placeholdera Redis, podczas zbiorczego review | 45 modułów SUCCESS, 1858/1858 testów, 0 błędów i pominięć; `/tmp/rabbit-r6-aggregate-review-tests.log` |
| Pełny root reactor po poprawce R6-REV-1 | `./mvnw -fae clean test`: 45 modułów SUCCESS, 1876/1876 testów, 0 błędów i pominięć; `/tmp/rabbit-r6-selector-full-reactor.log`. Regresje selektora: 24/24; przed poprawką 5 przypadków wykazywało błąd |
| Budowa wydzielonego pakietu Rabbit bez O1/O2 | Maven: 44 moduły SUCCESS; 1839 testów przeszło podczas budowy, dwa wymagające brokera przeszły 2/2 po starcie stacka. Łącznie pozytywny wynik wszystkich 1841 przypadków budowy. Pełny rebuild/redeploy ukończony; logi `/tmp/rabbit-r6-isolated-build.log`, `/tmp/rabbit-r6-isolated-broker-tests.log`, `/tmp/rabbit-r6-isolated-redeploy.log` |
| Końcowe normalne E2E wydzielonego pakietu | `start-e2e-tests.sh`: 39/39 scenariuszy, 463/463 kroki, BUILD SUCCESS; `/tmp/rabbit-r6-isolated-e2e.log`. Kod zgodny z przygotowanym indeksem Git; później dopisano tylko wyniki do dokumentacji |

HTTP w E2E kierowano przez oficjalny ingress, w tym jawne `AUTH_SERVICE_BASE_URL=http://localhost:8088/auth-service`,
`RABBITMQ_MANAGEMENT_BASE_URL=http://localhost:8088/rabbitmq/api` i
`POCKETHIVE_TCP_MOCK_URL=http://localhost:8088/tcp-mock`. Pozostałe adresy HTTP/UI/WS to
domyślny ingress normalnego skryptu. Próba obejmuje lifecycle/idempotency, konfigurację
defaults/overrides widzianą przez workerów, proxy HTTP/HTTPS/TCPS, Redis, ClickHouse i clearing export.

**Luka ScenarioControllerTest zamknięta — 2026-09-14.** Raport rozliczył wszystkie 26
niepowodzeń po wcześniejszym transferze walidacji. Po zatwierdzeniu naprawy uzupełniono jawne
I/O w fixture, dostosowano asercje do kanonicznego parsera i usunięto jego nadmiarowy wymóg
bloku settings w AUTHORING przy obcym subbloku. Kryteria dopuszczenia konfiguracji pozostały
bez zmian; produkcyjny SM nie otrzymał lokalnego parsera ani refaktoru S1–S10.

Pełna klasa przeszła 89/89, testy kontraktu parsera 16/16, a czysty root reactor zakończył
się sukcesem. Regresja ujawniła jeszcze niezgodną nazwę mocka CONTROL w teście Processora
i serviceId w fixture HTTP Sequence; skorygowano wyłącznie te dane testowe, zachowując asercje.
Jedyny wcześniej pomijany przypadek był pustym placeholderem Redis; usunięto go na polecenie użytkownika.
Stackowe E2E nie były ponawiane po tej naprawie. Szczegóły i granice dowodów:
[raport naprawy](../architecture/scenario-controller-test-failures-2026-09-14.md).

### Pozostałe kroki i odroczenia — potwierdzenie 2026-09-14

- Bieżący PR: R6-REV-1 poprawione — aktywacja połączenia i obu transportów Rabbit deleguje
  do WorkIoTypeParser. Regresje obejmują selektory ENV ze spacjami, wymagane ustawienia WORK
  i działający CONTROL bez WORK. [Raport](../architecture/rabbit-workplane-r6-review-2026-09-14.md)
  zawiera dowody przed/po oraz odróżnia poprawkę od pierwotnego review. Starsze warunki
  innych adapterów I/O pozostają osobnym długiem, opisanym w raporcie.
  Odbiór poprawki i pełne E2E zakończone pozytywnie; nie ma otwartego blokera uzgodnionego zakresu Rabbit.
- Osobny PR Artemis: natywny manifest i osierocony cleanup dla zasobów innych niż Rabbit.
  Istniejący Rabbit orphan cleanup pozostaje; zatwierdzony WORK_RESOURCE obsługuje zwykły
  lifecycle remove. Pełne create przez Orchestrator nadal wymaga obsługi natywnego manifestu.
- Osobne refaktory: kompletność diagnostyki UI/MCP według odroczenia powyżej oraz
  rejestr/reset Orchestratora według `orchestrator-correctness.md`.

Implementacja przekazuje dowody do osobnego zbiorczego review. Nie jest samodzielnym odbiorem
architektury ani automatycznym cyklem review/fix. Artemis i odłożone rozszerzenia publiczne
pozostają w osobnym planie.

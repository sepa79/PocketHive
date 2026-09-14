# Swarm Controller — review ścieżki WorkPlane, 2026-09-14

Stan: HEAD `4062be40` z bieżącymi lokalnymi zmianami; nie jest to review diffu.
Nazwa „swarm manager” została odniesiona do `swarm-controller-service` i jego konsumentów
`common/manager-sdk`. Po doprecyzowaniu celu zakres ograniczono do przygotowania kolejnego PR:
[WorkPlane Rabbit + Artemis delayed publish dla 3DS](../todo/work-plane-artemis-3ds.md).
Późniejsze doprecyzowanie zakresu: najpierw [izolacja Rabbit](../inProgress/work-plane-module-boundaries.md)
z adapterem testowym, potem Artemis/3DS. WP1–WP3 i granica I/O z WP4 wyznaczają miejsca do
domknięcia teraz; sam adapter Artemis i kontrakt opóźnienia są poza obecnym refaktorem.
Kod produkcyjny nie był zmieniany. Pozostałe naprawy i refaktory są osobnymi PR-ami.

**Wynik:** w prześledzonych wywołaniach technologia Rabbit korzysta z jednego API.
Nie znaleziono drugiej implementacji nazw ani bezpośrednich klientów Rabbit w tym serwisie.
Kompletny WorkPlane nadal jest jednak skonfigurowany jako Rabbit w orkiestracji zasobów,
materializacji konfiguracji, obserwacjach i usuwaniu. Sam nowy WorkInput/WorkOutput nie wystarczy
do podłączenia Artemis. Poniższe WP1–WP4 to miejsca wymagane przez następny PR, a nie żądanie
przebudowy całego Swarm Controllera ani nowe regresje przypisane ekstrakcji Rabbit.

## WP1 — operacje zasobów WorkPlane są wybierane jako Rabbit w kompozycji usługi

Startup artifact → `SwarmControllerStartupInitializer` → `SwarmLifecycleManager.prepare` →
`SwarmRuntimeCore.prepare` → `SwarmRuntimeInfrastructure.declareWorkTopology/provisionWorkers`.
Usuwanie idzie przez `SwarmRemoveCommandHandler` → core → `removeWorkers/removeWorkTopology`.

SwarmLifecycleManager bezwarunkowo tworzy `SwarmWorkTopologyManager` z WORK RabbitResources
(105) i `SwarmQueueStatsPortAdapter` z tych samych zasobów (118). Infrastructure łączy wywołania
compute, usuwanie kolejek CONTROL i zasoby WORK; core woła konkretny obiekt infrastruktury.
Work topology zawsze deklaruje Rabbit exchange, nawet jeśli zbiór kolejek jest pusty.

Przy wycinaniu WorkPlane oddzielić wybór/operacje zasobów WORK od compute i CONTROL.
Implementacja Rabbit nadal ma używać istniejącego RabbitResources. Statystyki i buffer guard
powinny korzystać z obserwacji wybranego adaptera przez istniejący QueueStatsPort albo jego
uzgodniony, minimalny kontrakt. Nie przenosić algorytmu guard ani przebudowywać lifecycle.

Usuwanie wymaga pełnego domknięcia: `removeWorkTopology` zwraca typy RABBIT_QUEUE/RABBIT_EXCHANGE,
a Orchestrator `RuntimeRemovalPostconditionVerifier:51–52` weryfikuje je przez RabbitTopologyPort.
CONTROL/WORK jest identyfikacją plane, nie technologią brokera. Artemis potrzebuje własnej jawnej
tożsamości zasobów i weryfikacji postcondition; nie oznaczać jego zasobów jako Rabbit.

Źródła: [SwarmLifecycleManager.java](/home/sepa/PocketHive/swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/SwarmLifecycleManager.java:105),
[SwarmRuntimeInfrastructure.java](/home/sepa/PocketHive/swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/runtime/SwarmRuntimeInfrastructure.java:92).

## WP2 — materializacja ustawień i połączeń ma konkretne założenia Rabbit

Plan workerów → `SwarmWorkerSpecFactory.plan` → WorkerWorkConfigurationPort →
`WorkerWorkConfigurationAdapter.compose` → RabbitWorkSettingsBootstrap / RabbitWorkEnvironment,
pozostali właściciele ustawień i neutralny WorkConfigurationParser w trybie RESOLVED.

Kompozycja `WorkerWorkConfigurationComposition.workResourceNames:25–26` zawsze wybiera
RabbitResourceNames. Adapter ustala środowisko wejścia/wyjścia przez nazwy Rabbit, a następnie
materializuje wybrany blok `rabbit`. To delegowanie zasad do właściciela, ale nie wybieralny adapter
całej ścieżki. `WorkConnectionEnvironmentResolver:38` zawsze dekoduje oba połączenia Rabbit.
`ControlPlaneContainerEnvironmentFactory.workerEnvironment` również eksportuje obydwa;
RabbitConnections wymaga jednocześnie control i work.

Następny PR musi zapewnić jawny wybór konfiguracji WORK i jej projekcji, aby WorkPlane Artemis
nie wymagał konfiguracji nieużywanego Rabbit WORK. Rabbit CONTROL pozostaje wymagany zgodnie
z obecnym kontraktem. Reguły/defaults Rabbit pozostają w module Rabbit; nie kopiować ich do nowego
adaptera usługi. Zachować finalną walidację przed deklarowaniem zasobów/provisioning.

Źródła: [WorkerWorkConfigurationAdapter.java](/home/sepa/PocketHive/swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/infra/configuration/WorkerWorkConfigurationAdapter.java:93),
[WorkConnectionEnvironmentResolver.java](/home/sepa/PocketHive/swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/runtime/environment/WorkConnectionEnvironmentResolver.java:38).

## WP3 — nazwa portu jest neutralna, ale kontrakt topologii i statusu opisuje Rabbit

WorkResourceNamesPort i WorkAddress opisują exchange/queue/routingKey. SwarmRuntimePlanAnalyzer
zbiera aliasy z `work.in/out`; SwarmWorkBindingsProjector sam projektuje pola exchange oraz queue/
routingKey, a SwarmQueueStatsCollector i buffer guard ponownie rozwiązują te aliasy tym samym portem.

Nie ma tu drugiej implementacji składania nazw: obecny port deleguje do RabbitResourceNames.
Brakuje natomiast kontraktu wyniku topologii wybranego WorkPlane, który może służyć wszystkim
konsumentom. Przy dodawaniu Artemis nie rekonstruować jego adresów niezależnie w każdym z nich
i nie wymuszać fikcyjnego Rabbit exchange. Uzgodnić wynik właściciela topologii i jego projekcje
do konfiguracji, statusu, statystyk oraz usuwania. Istniejący format statusu Rabbit zachować;
ewentualne nowe pola są jawną zmianą publicznego kontraktu w następnym PR.

Źródła: [WorkResourceNamesPort.java](/home/sepa/PocketHive/common/topology-core/src/main/java/io/pockethive/topology/work/WorkResourceNamesPort.java:9),
[SwarmWorkBindingsProjector.java](/home/sepa/PocketHive/swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/runtime/SwarmWorkBindingsProjector.java:63).

## WP4 — I/O ma punkty rozszerzenia, ale nie ma implementacji Artemis ani kontraktu opóźnienia

SDK wybiera WorkInputFactory/WorkOutputFactory. Mosty RabbitWorkInput i RabbitWorkOutput używają
RabbitListeners/RabbitPublisher; kod WorkItem i jego dispatch pozostają po stronie Work/SDK.
Parsery i mutation policies są składane przez CurrentWorkConfigurationProviders. WorkerInputType
i WorkerOutputType nie mają Artemis. To miejsca do rozszerzenia wspólnie ze Scenario authoring,
nie powód do tworzenia osobnego parsera w Scenario Managerze.

WorkOutput udostępnia `publish(WorkItem, WorkerDefinition)`. W tym kontrakcie i zbadanej ścieżce
Rabbit output nie ma jawnej operacji/parametru delayed publish. Nie znaleziono aktywnej implementacji
Artemis ani plików nazwanych dla 3DS. Archiwalny plan Artemis jest historyczny, nie ma mocy
rozszerzenia tego PR o migracje czy inną semantykę dostarczenia.

Przed implementacją ustalić minimalny kontrakt opóźnienia dla rzeczywistego przepływu 3DS
i odbiór czasowy. Właściciel adaptera interpretuje mechanizm brokera. Nie dodawać sleep/timerów
ani brokerowych nagłówków do poszczególnych workerów. Wydzielenie kontraktów/mostów I/O ma być
ograniczone do zależności potrzebnych tej ścieżce; bez całego refaktoru WorkerRuntime.

Źródła: [WorkOutput.java](/home/sepa/PocketHive/common/worker-sdk/src/main/java/io/pockethive/worker/sdk/output/WorkOutput.java:13),
[RabbitWorkOutput.java](/home/sepa/PocketHive/common/worker-sdk/src/main/java/io/pockethive/worker/sdk/output/RabbitWorkOutput.java:29).

## Dowody i ograniczenia

| Odpowiedzialność | Właściciel/kontrakt i sprawdzenie | Ocena |
|---|---|---|
| Broker resources/names | RESP-RABBIT-RESOURCES, RESP-WORK-RESOURCE-NAMES: SwarmWorkTopologyManager → RabbitResources i RabbitResourceNames; prześledzono declare/bind/read/delete, statystyki oraz CONTROL queue removal. | Prawidłowe użycie API technologii Rabbit w sprawdzonym zakresie; WP1/WP3 dotyczą przyszłego wyboru WorkPlane. |
| Konfiguracja | RESP-CONTROLLER-WORKER-PLAN, RESP-CONTROLLER-WORK-CONFIGURATION: WorkerWorkConfigurationPort → adapter → właściciele parserów/ENV → RESOLVED. | Reguły Rabbit nie są ponownie implementowane w serwisie; WP2 wyznacza konkretną zależność do rozdzielenia. |
| Publikacja i odbiór | RESP-WORK-RABBIT-TRANSPORT: SDK mosty → RabbitPublisher/RabbitListeners; CP binding → Rabbit API → SwarmSignalListener. | Istniejąca semantyka ACK nie była zmieniana. WP4 wymaga rozszerzenia Work kontraktu. |
| Sterowanie/statystyki | RESP-CONTROLLER-CONTROL, RESP-CONTROLLER-BUFFER-GUARD: core → infrastructure; guard service → manager-sdk coordinator → QueueStatsPort. | Wrapper guard i jego algorytm są odrębnymi rolami, nie duplikatem wyłącznie przez nazwę klasy. |

Wyszukiwania `rg` objęły produkcję serwisu/manager-sdk dla raw Rabbit/Spring-AMQP importów,
repo dla implementacji WorkResourceNamesPort, RabbitResources, WorkInput/WorkOutput i Artemis,
oraz konsumentów removal typu RABBIT_*. Odczytano wskazane wywołania i kompozycję, nie tylko importy.

Przeszło **14 istniejących testów**: WorkTopologyConfigurationTest (2),
WorkerWorkConfigurationAdapterTest (4), SwarmWorkBindingsProjectorTest (3),
SwarmQueueStatsCollectorTest (1), WorkConnectionEnvironmentResolverTest (4).
Pierwszą próbę zablokowało dołączanie agenta Mockito w sandboxie; ponowiono te same testy
poza nim i zakończyły się sukcesem. [Log](/tmp/swarm-controller-workplane-review-verified.log).
Maven: `-pl swarm-controller-service -am`, powyższe `-Dtest`, `-Dsurefire.failIfNoSpecifiedTests=false test`.
Test topologii sprawdza zgodność zadeklarowanych zasobów z ENV i bootstrapem, także gdy routing key
różni się od nazwy kolejki. Testy używają atrap API brokera, bez bezpośrednich portów usług.

Nie wykonano nowych prób lifecycle, pełnego E2E, live Rabbit ani Artemis. Nie oceniano obecnego
workera 3DS ani właściwości klienta Artemis. Zielone testy nie dowodzą gotowości Artemis.
Nie zmieniano import rules ani zależności; nie dodawano testów certyfikujących wybór konkretnej klasy.

Sześć przejść review: plan — WP1–WP4 ograniczone do celu; styl — nagłówki wskazują dzisiejsze
API, nie kompletną neutralność WorkPlane; zwięzłość — zachować parsery i technologię Rabbit zamiast
powielać; security — zachować istniejącą izolację CONTROL/WORK i jawne zasoby; biblioteki — brak
nowych zależności, wybór klienta Artemis później; utrzymanie — wymagane domknięcie create/use/observe/remove.
HiveMind nie był dostępny; nie uruchamiano lokalnego fallbacku. Bez commita ani zmian produkcyjnych.

## Wstępne obserwacje poza zakresem — osobny review/PR

Przed doprecyzowaniem celu odczytano także wejścia lifecycle. Do osobnego potwierdzenia pozostają:
status przyjmowany bez porównania runId/zaplanowanej instancji (`SwarmWorkerStatusHandler.observe`,
`SwarmRuntimeCore.updateHeartbeat/markReady`); skrót sukcesu START/STOP po samym workloadState
(`SwarmLifecycleCommandHandler.handle`); częściowe skutki config-update przed kontrolą dalszych pól
(`SwarmConfigUpdateHandler.handle/applyScenarioOverrides`). Rozdzielone liczenie świeżości
workerów jest już zapisane w F08. Nie wykonano reprodukcji tych problemów w tym review i nie
są one dodatkowymi zadaniami w PR WorkPlane/Artemis. Nie należy ogłaszać pełnego review lifecycle
Swarm Controllera na podstawie tego raportu.

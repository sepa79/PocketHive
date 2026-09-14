# Orchestrator — przegląd ścieżek kodu, 2026-09-14

Zakres: bieżące źródła przy HEAD `4062be40` wraz z lokalnymi poprawkami Rabbit i schema bootstrap.
HEAD identyfikuje stan, nie zakres diffu. Przegląd zaczyna się na wejściach Orchestratora i śledzi
wywołania do właścicieli w bibliotekach oraz koniecznych kontraktów innych serwisów.
To raport diagnostyczny, nie nowa definicja odpowiedzialności ani zgoda na zmianę zachowania.

**Werdykt:** nie można uznać podziału funkcjonalnego Orchestratora za zamknięty.
W prześledzonych ścieżkach Rabbit ma właściciela technologii; otwarte problemy obejmują
przyjmowanie dowodów, granicę rejestru/obserwacji i aktywne duplikaty odpowiedzialności.
Nie przypisuję tych problemów ostatniemu commitowi ani ekstrakcji Rabbit.

Dalsze działania: O1/O2 zostały następnie poprawione i przeszły 74 testy; oczekują osobnego review.
Plan poprawności Orchestratora (plik w repozytorium: `docs/inProgress/orchestrator-correctness.md`) oddziela te poprawki oraz
projekt resetu od ekstrakcji SSOT. Poniższe ustalenia zachowują stan z momentu przeglądu.

## Ustalenia

### O1 — P1: status nie jest sprawdzany względem aktualnego kontrolera i runId

`OrchestratorControlQueueConfiguration.controllerStatusRabbitBinding` →
`ControllerStatusListener.handle` → `SwarmStore` / `Swarm.updateObservation` →
`SwarmOperationObservationHandler`.

`ControllerStatusListener.java:107–124` sprawdza rolę i obecność swarmId, ale nie zgodność
`scope.instance` z zarejestrowanym kontrolerem ani `runtime.runId` z bieżącym uruchomieniem.
Codec sprawdza kontrakt i spójność routingu z kopertą; nie zna rejestru aktualnych uruchomień.
`SwarmStore.cacheControllerStatusFull/applyControllerStatusDelta` również nie sprawdzają tej tożsamości.
Status poprzedniego kontrolera może nadpisać workload, health, kontekst i czas odbioru aktualnego roju.
Ścieżka CREATE później sprawdza gotowość/digest, lecz nie kontrolera/runId przed terminalizacją.

Dwie próby przez rzeczywisty codec i listener potwierdziły nadpisanie: inna instancja oraz
stary runId przy tej samej instancji. Naprawa: jeden właściciel przyjmowania obserwacji,
który przed jakąkolwiek mutacją sprawdza tożsamość aktualnego uruchomienia; listener deleguje.
Kontrakt: `RESP-ORCHESTRATOR-INGRESS`, AGENTS SSOT i review wymagające kontroli target/runId.

### O2 — P1: config-update omija dopasowanie idempotencyKey wyniku

`SwarmSignalListener.handle` → `SwarmOperationTerminalHandler.accept:81` →
`SwarmOperationObservationHandler.awaitConfigObservation:49` → `completePendingConfigUpdates:135`.

Dla sukcesu config-update wymagającego obserwacji wynik trafia do pending po correlationId
bez dopasowania pełnej tożsamości operacji. `requireTerminalTargetMatchesEnvelope` porównuje
wynik z jego własnym routingiem, nie z zarejestrowaną operacją. Przy zamknięciu handler podaje do
`operations.recordResult` swarmId/target/idempotencyKey pobrane z operacji, zamiast sprawdzić dane
przyjętego wyniku. Omija w ten sposób istniejący warunek NO_MATCH w koordynatorze.

Próba przez codec i `SwarmSignalListener` potwierdziła SUCCEEDED dla wyniku z `wrong-idem`,
mimo że operacja oczekiwała `expected-idem`. Naprawa: przyjmowanie i dopasowanie dowodu raz,
u właściciela operacji, również przed wejściem w oczekiwanie na obserwację. Nie zastępować
odrzuconych pól poprawnymi wartościami z operacji. Weryfikacja powinna obejmować również runId,
którego `recordResult` obecnie nie przyjmuje.

### O3 — P2: debug reset usuwa rejestr, którego statusy już nie odtwarzają

`POST /api/control-plane/reset` → `ControlPlaneSyncService.sync:65` → `SwarmStore.clear` →
status-request → `ControllerStatusListener` ignoruje niezarejestrowany swarm.

Próba reset + poprawny status-full potwierdziła pusty rejestr. Runtime nadal może działać,
ale zwykłe sterowanie rojem traci zarejestrowany cel. To konflikt obecnego modelu rejestru
z recovery API, nie propozycja odtwarzania rejestru ze statusów. REST §5.2 jawnie opisuje clear,
więc potrzebna jest decyzja o kontrakcie: np. reset wyłącznie obserwacji albo usunięcie tej operacji.
Nie zmieniać zachowania pod pretekstem samej ekstrakcji.

### O4 — P2, blokuje zamknięcie granicy HTTP: SwarmController nadal wykonuje workflow

`POST /api/swarms/{id}/create` → `SwarmController.create:222–409` pobiera scenariusz,
rozwiązuje zmienne/SUT, przepisuje konfiguracje workerów, przygotowuje wolumen, zapisuje startup
artifact, stosuje binding sieci i uruchamia runtime wraz z rollbackiem. Metody
`applyScenarioVarTemplates`, `applySutConfigTemplates` i `toStateView` również należą do tej klasy.

To aktywna logika aplikacyjna, transformacja konfiguracji, persistence i projekcja w kontrolerze.
`FilesystemSwarmStartupArtifactStore` jest konkretną zależnością REST. Wydzielenie Rabbit tego
nie rozwiązuje. Potrzebne odrębne: przygotowanie planu, workflow CREATE, projekcja odczytu oraz
port zapisu artefaktu. Zachować istniejące reguły i przenieść je wraz z usunięciem starej ścieżki.
`RESP-ORCHESTRATOR-CONTAINER-LIFECYCLE` dokumentuje dalszy mieszany kod; nie stanowi wyjątku od
ENGINEERING_RULES. ComponentController/SwarmManagerController też składają dispatch config-update
w HTTP — powinny korzystać ze wspólnej usługi polecenia.

### O5 — P1 / CRITICAL SSOT: journal ma aktywne powielone mapowanie i ścieżkę pliku

`GET /api/journal/hive/page` → `JournalController:137` oraz
`GET /api/swarms/{id}/journal/page` → `SwarmJournalController:520` niezależnie mapują ten sam
zestaw kolumn rekordu na publiczne pola journal entry i konstruują paginację.
Osobne scope HIVE/SWARM uzasadniają filtr i reguły dostępu, nie duplikat formatu rekordu.
Oba kontrolery wykonują SQL; SwarmJournalController dodatkowo czyta pliki i obsługuje archiwizację.

`SwarmJournalController:382–383` rekonstruuje `<swarm>/<run>/journal.ndjson`, a
`swarm-controller/.../FileSwarmJournal:48` niezależnie ustala plik zapisu.
Istnieją `RuntimeFilesystemLayout` i `journal-postgres`, lecz nie domykają tego kontraktu.
Naprawa: jedna projekcja ścieżki journal, mapper rekordu i API query/archive; adaptery SQL/file
pod tym API. Nie scalać odrębnych domenowych strumieni HIVE/SWARM. To konkretny zakres F04.

### O6 — P1 / CRITICAL SSOT: dwa resolvery metadanych do autoryzacji

`SwarmController.resolveTemplateMetadata:1100` i
`OrchestratorEndpointAuthorization.resolveTemplateMetadata:101` mają tę samą regułę:
sprawdzenie bundlePath → pobranie ScenarioTemplateDescriptor → złożenie metadanych →
`swarm.attachTemplate`. Pierwszą ścieżkę wywołuje list/view i sterowanie rojem; drugą
m.in. komponenty i journal. Obie aktywnie zapisują ten sam fakt w Swarm.

Nie stwierdzono tu obejścia uprawnień; problemem jest podwójne właścicielstwo rozwiązywania
metadanych. Wyznaczyć jeden resolver/projekcję i skierować obie ścieżki do niego, zachowując
istniejące decyzje `OrchestratorAuthorization` / wspólnych grant checks.

### O7 — P2: Docker nie ma jeszcze zamkniętego modułu technologii

CREATE → `ContainerLifecycleManager` → `ComputeAdapter` → `common/docker-client`.
Debug/cleanup → `RuntimeDebugService` / `RuntimeReconciliationService` →
`DockerRuntimeAdapter:50–66` → bezpośredni DockerClient (remove/inspect, dalej list/logs).
`infra/docker/DockerConfiguration` lokalnie buduje klienta i wybiera adapter.

To dwie drogi dostępu do tej samej technologii. Tryb force cleanup i zwykły stop mogą mieć
różną semantykę — nie należy jej ujednolicać. Mechanikę i jawne operacje przenieść do istniejącego
`docker-client`; Orchestrator ma konsumować API inventory/debug/removal. Zakres F03.

### O8 — P2, SSOT/NFF: ustawienia wciąż są rozwiązywane w konsumentach

`ContainerLifecycleManager.applyClickHouseSinkEnv:322` oraz
`SwarmWorkerSpecFactory.applyClickHouseSinkEnvironment:94` kopiują eksport tych samych pól
ClickHouseSinkProperties do ENV. Wspólny kontrakt properties nie jest właścicielem tego mapowania.

`ScenarioManagerClient.resolveTimeout:247` oraz `NetworkProxyManagerClient.resolveTimeout:147`
niezależnie zamieniają zero/ujemny timeout na stałą domyślną. `OrchestratorHttpProperties`
sprawdza non-null, lecz nie dodatniość. Jawnie podana błędna konfiguracja staje się inną skuteczną
konfiguracją zamiast zostać odrzucona. To nie jest kwestia użycia dwóch klientów HTTP.
Przenieść walidację/defaulty do właściciela ustawień, eksport ClickHouse do właściciela sinka.
Zmianę polityki błędnych timeoutów rozliczyć jawnie jako poprawkę zachowania, nie sam refactor.

## Sprawdzone granice i właściciele

| Wejście / ścieżka | Właściciel i rezultat przeglądu |
| --- | --- |
| HTTP create/start/stop/remove/network | SwarmController → OperationDispatchService → SwarmOperationCoordinator; START/STOP/REMOVE używają SwarmLifecycleCommandService. O1–O4/O6; duży CREATE nadal w REST. |
| HTTP config/manager enabled | ComponentController/SwarmManagerController → dispatch/coordinator → ControlSignals/ControlPlaneRouting → ControlPlanePublisher. O2/O4. |
| Status/result/timer | Rabbit binding → codec → listeners → observation/terminal/remove handlers → coordinator/outcome publisher. Właściciel operacji istnieje, ale O1/O2 omijają jego granicę. |
| REMOVE | Filesystem result → identity matching → RuntimeRemovalPostconditionVerifier → scoped RabbitTopologyPort/compute inventory → network binding verification → controller removal → filesystem/registry → durable journal. Osobne próby istniejące przeszły; bez live cleanup. |
| Rabbit resources/debug tap | WorkResourceNamesPort / RabbitResourceNames → RabbitDebugTapSpec/RabbitResources/RabbitReceiver; AmqpRabbitTopologyAdapter wybiera CONTROL/WORK. Mechanika w SpringRabbitResources, osobne połączenia przez RabbitResourceAutoConfiguration. Bez znalezionego bypassu w tych ścieżkach. |
| CP transport | ControlPlaneRabbitBindings ustala klasyfikację; RabbitListenerBinding trafia do modułu Rabbit. Publikacja przez wspólny CP publisher. RESP-RABBIT-RESOURCES/TRANSPORT/CONNECTION i RESP-CP-LISTENER-POLICY. |
| Info/schema | ControlPlaneInfoController → RabbitResourceNames.controlStompSubscription; ControlPlaneSchemaController → ControlPlaneSchemaBundle → kanoniczne zasoby core. RESP-CONTROL-STOMP-INFO / RESP-CONTROL-SCHEMA-BOOTSTRAP. Bez nowego ustalenia. |
| Runtime debug/assessment/cleanup | Cienkie kontrolery delegują do usług; manifest przez RuntimeOwnershipManifestStore, Rabbit przez scoped port. Docker O7. Nie uznaję assessment automatycznie za drugi lifecycle state machine: to odrębna diagnoza. |
| Journal/auth/settings | Prześledzone rzeczywiste zapytania, zapis metadanych, konstrukcja ścieżki i eksport ENV. O5/O6/O8. |

Przeszukano repozytorium `rg` dla konsumentów i alternatyw: Rabbit API/raw imports,
`resolveTemplateMetadata`, `journal.ndjson`, mapperów/paginacji journal, Docker remove/inspect,
eksportu ClickHouse, `resolveTimeout`, `recordResult`, aktualizacji obserwacji oraz renderowania
konfiguracji. Kandydatów opisanych powyżej odczytano i prześledzono; brak trafienia w wyszukiwaniu
nie został potraktowany jako dowód pełnej izolacji. Nie tworzono skanera architektury.

## Weryfikacja i ograniczenia

- 37 istniejących testów przeszło, w tym jedyny zatwierdzony RepositoryImportBoundaryTest.
- 4 dodatkowe tymczasowe próby potwierdziły obecność O1/O2/O3, asercjami na rzeczywiście
  zaobserwowane błędne zachowanie. Ich zielony wynik nie oznacza naprawy.
- Próby używają kanonicznego codec i wejść listenerów; bez bezpośrednich portów usług/brokera.
- Źródło prób: `/tmp/OrchestratorPathReviewProbeTest.java`; log: `/tmp/orchestrator-path-review-tests.log`.
  Tymczasowy plik usunięto z drzewa testów po wykonaniu. Kod produkcyjny nie został zmieniony.
- Uruchomienie: `./mvnw -pl orchestrator-service -am test` z wyborem klas opisanych w logu;
  m.in. ControllerStatusListener, observation/terminal handlers, sync, removal verifier,
  reconciliation, debug taps i schema bundle/controller. `git diff --check` czysty.
- Brak nowego E2E/deploy/cleanup. Nie odtworzono awarii zewnętrznego DB/Docker, wyścigów
  wielowątkowych ani wszystkich kombinacji uprawnień. To przegląd funkcjonalnych ścieżek,
  nie deklaracja sprawdzenia każdej metody i każdej gałęzi wszystkich zależności.
- HiveMind nie jest dostępny w narzędziach tej sesji; nie uruchamiano lokalnego zamiennika.

Sześć wymaganych przebiegów: cel/planu — granice pozostają otwarte jak wyżej;
style — O4/O5 i istniejące publiczne nested DTO/brak RESP headers;
zwięzłość — usuwać duplikaty O5/O6/O8 bez nowej warstwy dla samej warstwy;
security — sprawdzono filter → auth client/grant checks → endpoint authorization, O6 jest
problemem SSOT, a nie postulatem dodatkowego hardeningu; biblioteki — istniejące moduły wystarczą;
maintainability — najpierw zabezpieczyć przyjmowanie obserwacji/dowodów, potem rozdzielać workflow.
ACK, inactive confirms, pośrednie ENV overrides, legacy Node/E2E tooling i odroczone refaktory
pozostają zgodne z wcześniejszymi decyzjami. Raport nie autoryzuje ich zmiany.

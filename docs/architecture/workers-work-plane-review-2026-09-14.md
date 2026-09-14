# Workery — review ścieżki WorkPlane, 2026-09-14

Stan: HEAD `4062be40` z bieżącymi lokalnymi zmianami. Review rzeczywistych wywołań,
nie diffu. Zakres: dziewięć usług workerów, wspólne SDK oraz używane API Rabbit,
Pierwotnie pod wspólny zakres WorkPlane + Artemis. Aktualna kolejność:
[domknięcie izolacji Rabbit](../inProgress/work-plane-module-boundaries.md) z adapterem testowym,
następnie [Artemis/3DS](../todo/work-plane-artemis-3ds.md). W2/W3 i granice I/O z W4 dotyczą
obecnego refaktoru; kontrakt opóźnienia i implementacja Artemis są późniejszym etapem.
Nie zmieniano kodu produkcyjnego ani semantyki dostarczenia.

**Wynik:** funkcje workerów korzystają z neutralnego WorkItem. W sprawdzonym kodzie usług
nie znaleziono własnych klientów Rabbit, deklaracji zasobów ani składania adresów Rabbit.
W1 wycofano jako finding po sprawdzeniu pełnej ścieżki walidacji i eksportu ENV.
W2–W4 opisują istniejące ograniczenia wymagające pracy w następnym PR; brak Artemis nie jest
nową regresją. Próby bezpośredniego bindingu nie wykazały naruszenia SSOT w normalnym przepływie.

## W1 — wycofany: standardowy binding wcześniej zwalidowanych ustawień z ENV

Pełna ścieżka: WorkerWorkConfigurationAdapter materializuje ustawienia przez
`RabbitWorkSettingsBootstrap.input/output`. Bootstrap używa parsera Rabbit w AUTHORING,
następnie w RESOLVED, i zwraca projekcję typowanych ustawień. Dopiero potem
`RabbitWorkEnvironment.input/output` eksportuje wartości do tekstowej mapy ENV.
SwarmWorkerSpecFactory przekazuje tę mapę do specyfikacji kontenera. Po uruchomieniu workera
Spring Binder odtwarza typy w RabbitInputProperties/RabbitOutputProperties, a ich
`validateConfigured` deleguje do tego samego właściciela reguł Rabbit.

Moduł Rabbit posiada walidację/defaults i eksport ENV; Spring obsługuje reprezentację typów
na wejściu procesu. Standardowa konwersja Springa sama w sobie nie dowodzi drugiego SSOT.
W tym przepływie `2.9` lub tekst `"yes"` zostają odrzucone przed eksportem. Poprawne wartości
trafiają do ENV jako tekst liczby całkowitej oraz `true`/`false`.

Pięć prób bezpośredniego bindingu pokazało obcięcie liczbowego `2.9` do `2` dla prefetch/
concurrentConsumers oraz przyjęcie tekstu `"yes"` dla trzech pól boolean. Próby ominęły
właściciela przygotowującego ENV; liczba `2.9` była obiektem Number w źródle właściwości,
nie tekstem z ENV. Nie dowodzą przepuszczenia takich danych przez normalny scenariusz ani
akceptacji `PREFETCH=2.9`. Pierwotna klasyfikacja P2 i propozycja zastąpienia bindingu były
nieuzasadnione zakresem dowodu. W1 nie jest zadaniem ani blokerem PR Rabbit/Artemis.
Nie dodawać na tej podstawie hardeningu ręcznie zmienianej konfiguracji.

Źródła: [RabbitWorkSettingsBootstrap.java](/home/sepa/PocketHive/common/rabbit-adapter/src/main/java/io/pockethive/rabbit/api/RabbitWorkSettingsBootstrap.java:32),
[RabbitWorkEnvironment.java](/home/sepa/PocketHive/common/rabbit-adapter/src/main/java/io/pockethive/rabbit/api/RabbitWorkEnvironment.java:40),
[WorkerWorkConfigurationAdapter.java](/home/sepa/PocketHive/swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/infra/configuration/WorkerWorkConfigurationAdapter.java:87),
[SwarmWorkerSpecFactory.java](/home/sepa/PocketHive/swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/runtime/SwarmWorkerSpecFactory.java:78).

## W2 — bootstrap wiąże Rabbit CONTROL z wymaganym Rabbit WORK

Każda usługa używa Rabbit dla CONTROL. `RabbitConnectionConfiguration.rabbitConnections`
dekoduje obydwa bloki, a `workRabbitConnection` tworzy WorkRabbitConnection bez warunku wyboru
Work I/O. `RabbitTransportAutoConfiguration` tworzy WORK publisher/receiver, a RabbitListeners
wymaga także WorkRabbitConnection, mimo że obsługuje również CONTROL.

Warunki `inputs/outputs.type=RABBITMQ` w SDK dotyczą dopiero fabryk Work I/O. Nie odłączają
wcześniejszego wymagania konfiguracji Rabbit WORK. Samo dodanie Artemis do enumów i fabryk
pozostawiłoby obowiązek dostarczenia ustawień nieużywanego brokera WORK.

W następnym PR rozdzielić kompozycję połączeń/transportu CONTROL i wybranego WORK. Rabbit
CONTROL nadal korzysta z tego samego API; brak Rabbit WORK przy Artemis ma być prawidłowym
wyborem, nie fallbackiem. To druga strona problemu WP2 ze Swarm Controllera.

Źródła: [RabbitConnectionConfiguration.java](/home/sepa/PocketHive/common/rabbit-adapter/src/main/java/io/pockethive/rabbit/config/RabbitConnectionConfiguration.java:24),
[RabbitTransportAutoConfiguration.java](/home/sepa/PocketHive/common/rabbit-adapter/src/main/java/io/pockethive/rabbit/transport/RabbitTransportAutoConfiguration.java:26).

## W3 — adaptery I/O mają SPI, ale ich konfiguracja i projekcje nadal są w SDK

`WorkerDefinitionDiscovery` wybiera klasę Properties przez switch i odczytuje Rabbit-specific
settings w `resolveIo`. WorkIoBindings opisuje inQueue/outQueue/exchange; outQueue pochodzi
z routingKey, nie z odkrytej fizycznej kolejki. Tę projekcję konsumują WorkerState,
DefaultWorkerContextFactory, lokalne wejścia oraz status WorkerControlPlaneRuntime.
Nie jest to drugi algorytm budowania nazw, ale projekcja ograniczona do Rabbit.

WorkInput/WorkOutput i ich fabryki są w worker-sdk. Ich sygnatury odnoszą się do WorkerDefinition,
WorkInput/OutputConfig, a WorkInput.update także do zagnieżdżonego snapshotu konkretnego
WorkerControlPlaneRuntime. W tym samym module są mosty Rabbit oraz ich autokonfiguracja.
Samo przeniesienie klas mostów do modułu zależnego od SDK, przy zachowaniu jego bezpośrednich
konstruktorów w autokonfiguracji SDK, stworzyłoby zależność w obie strony.

Minimalne cięcie: jawny provider wybranej konfiguracji i jej projekcji, potrzebne kontrakty I/O
oraz kompozycja poza neutralnym konsumentem. Domknąć zależności faktycznych konstruktorów,
bez przenoszenia całego WorkerControlPlaneRuntime ani refaktoru Redis/CSV/schedulera.
Artemis ma dostarczyć własne rozwiązane adresy; status/context mają je projektować, nie odtwarzać
ani wymagać fikcyjnego exchange. Utrzymać obecny format Rabbit; nowe publiczne dane wymagają
uzgodnienia kontraktu. Create/observe/destroy zasobów pozostają po stronie wybranego właściciela
WorkPlane wywoływanego przez zarządzanie swarmem, nie przez poszczególne funkcje workerów.

Źródła: [WorkerDefinitionDiscovery.java](/home/sepa/PocketHive/common/worker-sdk/src/main/java/io/pockethive/worker/sdk/autoconfigure/WorkerDefinitionDiscovery.java:118),
[WorkInput.java](/home/sepa/PocketHive/common/worker-sdk/src/main/java/io/pockethive/worker/sdk/input/WorkInput.java:17),
[WorkIoBindings.java](/home/sepa/PocketHive/common/worker-sdk/src/main/java/io/pockethive/worker/sdk/runtime/WorkIoBindings.java:7).

## W4 — jedna publikacja wyniku już istnieje; brakuje jawnego zamiaru opóźnienia

Odbiór Rabbit: SpringRabbitListeners → RabbitWorkInput → RabbitMessageWorkerAdapter →
RabbitWorkExecution → RabbitWorkItemConverter/WorkItemJsonCodec → WorkerRuntime.dispatch.
Wykonanie: DefaultWorkerRuntime → WorkerInvocation → PocketHiveWorkerFunction.onMessage.
Wynik: DefaultWorkerRuntime → WorkOutputRegistry → RabbitWorkOutput → RabbitPublisher.
Tylko wynik różny od null trafia do wyjścia. Adapter wejścia nie publikuje zwróconego wyniku ponownie.

`WorkOutput.publish(WorkItem, WorkerDefinition)` nie ma kontraktu opóźnienia. Dodanie osobnego
Artemis publish bezpośrednio w workerze ominęłoby dzisiejszego właściciela publikacji. Rozszerzyć
tę ścieżkę o jeden uzgodniony zamiar opóźnienia, interpretowany przez adapter. Producent, odbiorca,
źródło czasu i odpowiedź adaptera bez tej możliwości nadal wymagają ustalenia dla faktycznego 3DS.
Nie znaleziono aktywnej implementacji Artemis ani bieżącego przepływu nazwanego 3DS.

RabbitWorkExecution zawiera również wykonanie synchroniczne/asynchroniczne, limit in-flight
i raportowanie błędów. Przy dodawaniu Artemis nie kopiować tych samych zasad do drugiej klasy.
Rozdzielić tylko potrzebną politykę wykonania od dekodowania i mechaniki brokera, zachowując
dzisiejsze działanie. Rabbit AUTO ACK pozostaje na powrocie callbacku: w async po przekazaniu
do wykonania, w sync po obsłudze. Błąd dekodera/worker dispatch jest raportowany i nie opuszcza
callbacku; odrzucenie zadania przez executor zachowuje historyczne wykonanie synchroniczne.
Disabled invocation zwraca null. Nie dodawać requeue po błędzie parsera/executora.

Źródła: [DefaultWorkerRuntime.java](/home/sepa/PocketHive/common/worker-sdk/src/main/java/io/pockethive/worker/sdk/runtime/DefaultWorkerRuntime.java:63),
[WorkOutput.java](/home/sepa/PocketHive/common/worker-sdk/src/main/java/io/pockethive/worker/sdk/output/WorkOutput.java:13),
[RabbitWorkExecution.java](/home/sepa/PocketHive/common/worker-sdk/src/main/java/io/pockethive/worker/sdk/transport/rabbit/RabbitWorkExecution.java:103).

## Sprawdzone funkcje workerów

| Usługa / wejście onMessage | Przetwarzanie i wynik dla SDK |
|---|---|
| generator / GeneratorWorkerImpl:110 | Renderuje dane, dopisuje krok, zwraca WorkItem. |
| moderator / ModeratorWorkerImpl:67 | Stosuje ograniczenie tempa, zwraca wejściowy WorkItem. |
| processor / ProcessorWorkerImpl:158 | Deleguje HTTP/TCP/ISO8583 do handlera, zwraca wynik lub zgłasza błąd. |
| request-builder / RequestBuilderWorkerImpl:88 | Renderuje request envelope, zwraca WorkItem; brak szablonu według istniejącej polityki. |
| http-sequence / HttpSequenceWorkerImpl:56 | HttpSequenceRunner wykonuje kroki HTTP, zwraca zebrany WorkItem. |
| db-query / DbQueryWorkerImpl:36 | DbQueryRunner wykonuje zapytanie przez executor, dopisuje wynik do WorkItem. |
| postprocessor / PostProcessorWorkerImpl:128 | Metryki i wybrany TxOutcomeSink; zwraca null. |
| clearing-export / ClearingExportWorkerImpl:126 | Projektuje rekord i deleguje zapis do batchWriter; zwraca null. |
| trigger / TriggerWorkerImpl:92 | Wykonuje istniejącą akcję shell/REST; zwraca null. |

HTTP/TCP/DB/file/shell w tych funkcjach są ich operacjami testowymi lub wyjściem biznesowym,
nie omijaniem WorkPlane Rabbit. Nie przeprowadzono pełnego audytu ich domenowej poprawności.
Ten review nie uzasadnia dziewięciu refaktorów usług przed Artemis.

## Właściciele, dowody i ograniczenia

Kontrakty odpowiedzialności pochodzą z [runtime-responsibilities.md](runtime-responsibilities.md).

| Odpowiedzialność | Kontrakt, właściciel i konsumenci | Wynik |
|---|---|---|
| Ustawienia/defaults | RESP-WORK-RABBIT-SETTINGS: RabbitConfiguration → parsery/RabbitSettingValues; bootstrap → RabbitWorkEnvironment → SDK Properties; CurrentWorkConfigurationProviders → authoring/runtime candidate | Reguły i eksport po stronie Rabbit; Spring odtwarza typy z ENV. W1 wycofany. Defaults wskazują stałe właściciela. |
| Wybór I/O | RESP-WORK-ADAPTER-SELECTION: registry initializers → dokładnie jedna wspierająca fabryka; brak/duplikat kończy startup błędem | Prześledzone source; W3 opisuje granicę modułów i discovery. |
| Wywołanie/publikacja | RESP-WORK-INVOCATION, RESP-WORK-RABBIT-TRANSPORT: WorkerInvocation/DefaultWorkerRuntime → registry → RabbitWorkOutput | Jeden publisher wyników; wspólny codec; Rabbit input ignoruje nagłówki transportu. |
| Odbiór i stan | RESP-WORK-RABBIT-POLICY: SpringRabbitListeners; RESP-WORK-STATE: WorkerControlPlaneRuntime → snapshot → RabbitMessageWorkerAdapter | Broker mechanics w Rabbit, projekcja enablement w adapterze; zachowane błędy/callback return. |
| Połączenia/adresy | RESP-RABBIT-CONNECTION: RabbitConnectionConfiguration/Environment; RESP-WORK-CAPABILITY: discovery → WorkIoBindings → context/status | W2/W3; worker nie deklaruje ani nie rekonstruuje zasobów. |

Wyszukiwania `rg` objęły dziewięć produkcyjnych drzew Java dla klientów Rabbit/Spring-AMQP,
deklaracji kolejek/exchange i routing keys; SDK dla Rabbit, RABBITMQ, bindingów, konstruktorów,
publikacji, stanu i parserów; całe repo dla alternatywnych implementacji mostów, settings parsers,
WorkItemJsonCodec, RabbitResourceNames i Artemis/3DS. Wyniki odczytano w miejscach wywołań.
Nie utworzono skanera architektury ani nowych testów tożsamości beanów.

**113 istniejących testów przeszło:** SDK 96 (DefaultWorkerRuntimeTest, RabbitMessageWorkerAdapterTest,
RabbitWorkItemConverterTest, RabbitWorkExecutionTest, RabbitWorkInputFactoryTest,
WorkIOConfigBinderTest, RabbitWorkOutputTest), Rabbit 15 (SpringRabbitListenersTest,
SpringRabbitTransportTest, RabbitConnectionConfigurationTest), RepositoryImportBoundaryTest 2.
Maven: `./mvnw -pl common/worker-sdk -am -Dtest=<powyższe klasy> -Dsurefire.failIfNoSpecifiedTests=false test`.
Pierwszą próbę zatrzymał self-attach Mockito w sandboxie. Te same testy poza sandboxem przeszły;
[pełny log](/tmp/workers-workplane-review-verified.log).

**5 dodatkowych prób sprawdziło konwersje przy bezpośrednim bindingu** — ominęły produkcyjne
przygotowanie ENV i nie wykazały błędu tej ścieżki (wycofany W1).
[Źródło prób](/tmp/RabbitBindingReviewProbeTest.java), [log](/tmp/workers-rabbit-binding-probes.log).
Tymczasowe źródło testu i jego skompilowaną klasę usunięto z modułu. Nie dodano poprawki produkcyjnej.

Testy odbioru używają rzeczywistych kontenerów listenerów z atrapą klienta; nie wykonano live broker,
pełnego E2E ani deploymentu. Nie sprawdzano klienta Artemis ani czasowego odbioru 3DS.
Nie jest to rekomendacja merge całego brancha. Odbiór izolacji Rabbit wymaga zamknięcia W2/W3,
granicy I/O z W4 oraz zasobów po stronie Swarm Controllera/Orchestratora według aktywnego planu.
Sam brak delayed publish nie jest blokerem tej izolacji.

Sześć przejść: plan — konkretne cięcia W2–W4 w uzgodnionym zakresie; styl — nagłówki transportu
rozróżniają SDK i Rabbit, binding odtwarza ustawienia wyeksportowane przez właściciela; zwięzłość —
zachować istniejące I/O i jedną publikację; security — zachować rozdział połączeń CONTROL/WORK,
bez nowego hardeningu ENV ani audytu auth; biblioteki — żadnych nowych zależności, klient Artemis
do późniejszej weryfikacji; utrzymanie — W3 wymaga spójnych kontraktów i zależności, nie samej
zmiany pakietów. HiveMind niedostępny; nie uruchamiano lokalnego fallbacku. Bez commita.

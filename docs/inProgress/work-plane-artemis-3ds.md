# Artemis WorkPlane i opóźnione dostarczenie dla 3DS

Status: plan zatwierdzony przez użytkownika 2026-09-15; implementacja rozpoczęta.
Branch: `codex/artemis-work-plane`, punkt wyjścia: `863694be`.

## Cel i zakres

Dodać Artemis jako produkcyjny adapter istniejącej granicy WorkPlane, następnie
obsłużyć opóźnione dostarczenie dla 3DS przez jedną istniejącą ścieżkę publikacji.
CONTROL pozostaje Rabbit. Zachować zwykły lifecycle start/remove i jego weryfikację
efektów; nie uzależniać Artemis od nowego manifestu ani drugiego cleanupu.

## Właściciel

`common/artemis-adapter`, namespace `io.pockethive.artemis`, posiada konfigurację
i jej reguły/defaulty, nazwy/adresy, połączenia, operacje zasobów, transport i obserwacje.
Małe klasy o osobnych odpowiedzialnościach tworzą jedno API technologii. Konsumenci
używają istniejących portów Work i rozwiązanych projekcji; nie importują klienta Artemis,
nie składają nazw ani nie odtwarzają ustawień. Adapter nie zależy od SDK ani usług.
Aktualny zakres właścicieli określają rekordy w `docs/architecture/runtime-responsibilities.md`.

## Kolejność wykonania

### A1 — moduł, kontrakt konfiguracji i granice

- Utworzyć moduł adaptera i jawne, typowane ustawienia. Jedna implementacja reguł;
  późniejsze parsery authoring i binding mają konsumować tego właściciela.
- Użyć natywnego Java Core API. Pierwszy etap korzysta z wersji 2.40.0 zarządzanej
  przez już używany Spring Boot BOM 3.5.14, bez zmiany stosu zależności usług.
- Dodać reguły do istniejącego Maven Enforcer i RepositoryImportBoundaryTest:
  klient wyłącznie wewnątrz adaptera, brak zależności adaptera od worker-sdk/usług.
- Odbiór: nieprawidłowe ustawienia i kolizje nazw odrzucane przed skutkami;
  rozwiązana topologia daje spójne adresy dla konfiguracji, zasobów i obserwacji.

### A2 — rzeczywiste zasoby i zwykły transport

- Zaimplementować istniejące porty resolvera topologii, zasobów i fabryk input/output.
  Podstawowy kanał to jawny adres ANYCAST i przypisana kolejka, izolowane nazwą swarma.
- Przekazywać WorkItem przez kanoniczny WorkItemJsonCodec. Adapter dekoduje i przekazuje
  callback do SDK; nie wykonuje workerów i nie publikuje ich wyników samodzielnie.
- ACK po powrocie callbacku/przekazaniu do wykonania. Błędy danych/callbacku raportowane
  i rozliczane bez requeue. Nie czekać na wynik asynchronicznego executora.
- Sesje/połączenia mają jednego właściciela cyklu życia; nie otwierać sesji per wiadomość
  ani współdzielić sesji konsumenta z asynchronicznym producentem.
- Odbiór na rzeczywistym silniku Artemis: create → send/receive → observe → remove
  → potwierdzenie nieobecności, błędna wiadomość nie blokuje następnej i nie wraca.
  Pierwsza próba jest testem komponentowym z brokerem osadzonym w procesie testowym;
  nie jest osobnym PoC ani dowodem uruchomienia całego PocketHive.

### A3 — podłączenie istniejących konsumentów

- Jawny selektor ARTEMIS oraz providery authoring/binding/ENV w istniejącej kompozycji.
  Scenario Manager deleguje do wspólnego WorkConfigurationParser.
- Orchestrator, Swarm Controller i workery otrzymują projekcje tego samego właściciela.
  Nie dodawać lokalnych parserów, formuł nazw ani wymagań konfiguracji Rabbit WORK.
- Status, statystyki i guard korzystają z istniejących portów. Nieobsługiwana funkcja
  diagnostyczna ma jawny wynik, bez uruchamiania Rabbit w zastępstwie.
- Odbiór: rzeczywisty worker wykonuje wejście Artemis i publikuje jeden wynik; błędna
  konfiguracja nie zastępuje przyjętej. Rabbit CONTROL działa niezależnie.

### A4 — istniejący start/remove bez zależności od manifestu Rabbit

- Zachować pliki SwarmStartupArtifact jako wejście planu. Controller tworzy i usuwa
  zasoby przez rozwiązany WorkPlane; Orchestrator weryfikuje cele z RemoveResult.
  Ta zwykła ścieżka usuwania nie korzysta z ownership manifestu.
- Usunąć uzależnienie startu od reprezentowalności WORK w RuntimeRabbitManifest.
  Nie udawać zasobów Rabbit ani kompletnej diagnostyki przez pustą listę.
- Nie dodawać manifest.workResources, DELETE_WORK_RESOURCE, includeWorkResources,
  nowego rejestru ani drugiej ścieżki usuwania. Odtwarzanie własności, osierocony cleanup
  i zastąpienie manifestu w diagnostyce są osobnym refaktorem.
- Zmiany publicznej odpowiedzi diagnostyki, jeśli konieczne do uczciwego wskazania
  zakresu, najpierw opisać konkretnie w kanonicznym kontrakcie i uzgodnić z użytkownikiem.
- Odbiór: pełne create/remove Artemis przez istniejące API, z potwierdzeniem
  nieobecności zasobów WORK i zachowaniem niezależnego CONTROL.

### A5 — opóźnienie dla rzeczywistego przepływu 3DS

- Przed zmianą API uzgodnić producenta/odbiorcę, źródło wartości i moment liczenia
  opóźnienia. Względne milisekundy to propozycja, nie zatwierdzony kontrakt.
- Neutralny zamiar dostarczenia przechodzi przez DefaultWorkerRuntime →
  WorkOutputRegistry → wybrany WorkOutput. Bez dodatkowego publish w workerze.
- Adapter wysyła od razu do brokera, który udostępnia wiadomość nie wcześniej niż
  w terminie. Tylko adapter zna właściwości brokera i przeliczenie na jego termin.
- Adapter bez tej możliwości jawnie odrzuca żądanie. Nie dodawać timerów, sleep,
  retry/DLQ ani fallbacku w workerach.
- Odbiór: brak przedwczesnego odbioru, odbiór po terminie i poprawne usuwanie kolejek
  z wiadomościami oczekującymi. Nie obiecywać dostarczenia dokładnie co do milisekundy.

### A6 — odbiór całej ścieżki

- Testy zachowania z A1–A5 powstają razem z implementacją. Review śledzi wywołania,
  jednego właściciela i brak alternatywnych ścieżek; zielone testy nie zastępują review.
- Zbudować lokalny stack przez build-hive.sh; oficjalnym API przejść pełny scenariusz
  Artemis/3DS, następnie normalny start-e2e-tests.sh dla regresji Rabbit/CONTROL.
- Zapisać wyniki, ograniczenia i stan etapów. Nie uruchamiać samodzielnego cyklu review/fix.

## Niezmienne zachowanie i wyłączenia

Rabbit zachowuje konfigurację, nazwy, ACK po przyjęciu/przekazaniu do wykonania,
brak requeue po błędach danych/executora i nieaktywne publisher confirms. Nie zmieniać
WorkItem wire envelope bez odrębnego uzgodnienia. Bez migracji/compatibility, HA,
refaktorów Orchestrator reset/O1/O2, Scenario Manager S1–S10, Redis, Docker, auth
ani kompletności Runtime inspector/MCP. Nie tworzyć nowego rejestru własności.

## Korekta wcześniejszego planu R4 — decyzja 2026-09-15

Wcześniejsze uzależnienie Artemis od natywnego ownership manifestu i orphan cleanupu
jest wycofane. Manifest jest dodatkową projekcją diagnostyczną, nie wejściem zwykłego
remove. Istniejący zapis startowy, rozwiązana topologia i wynik usuwania zachowują
swoje role. Stare raporty opisują historyczną blokadę fabryki manifestu, nie wymaganie
rozbudowy manifestu w tym PR. Zastępuje to wcześniejsze instrukcje w planie Rabbit.

## Pierwszy etap — stan przed review

A1/A2: pierwszy samodzielny adapter zaimplementowany i sprawdzony komponentowo.
Istnieją typowane ustawienia, nazwy/projekcje, zasoby i transport za istniejącymi portami.
Klient nie wychodzi poza moduł; nie zmieniono kodu usług ani zachowania Rabbit.

Weryfikacja 2026-09-15: 309 testów, 0 błędów i pominięć, w 10 modułach wybranego
reactora. W tym 23 testy Artemis (7 z rzeczywistym brokerem in-process), 79 Rabbit
i 69 control-plane-core obejmujących istniejący test importów. Polecenie:
`./mvnw -B -ntp -pl common/artemis-adapter,common/control-plane-core -am test`.
Log: `/tmp/artemis-final-slice-tests.log`.

Sprawdzone efekty: spójny WorkItem wraz z observability, rozdzielenie zasobów swarmów,
ochrona przed kolizją separatorów/wildcard, stop/start odbioru, konsumpcja błędnej
wiadomości i błędu callbacku bez requeue, ACK bez oczekiwania na async executor,
współbieżna publikacja przez osobną sesję, odrzucenie obcych zasobów przed skutkami,
odmowa usunięcia używanego adresu i odróżnienie niedostępnego połączenia od nieobecności.

Ograniczenia pierwszego etapu: endpoint TCP/deployment nie był testowany; broker
testowy korzysta z jawnego Core in-VM, bez sieciowego obejścia ingress PocketHive.
Usuwanie adresów używa Core Management na standardowym adresie biblioteki, z timeoutem
połączenia; uprawnienia i konfiguracja brokera wdrożeniowego należą do podłączenia A3/A6.
Brak odczytu oldest-age jest reprezentowany przez istniejące OptionalLong w kontrakcie.
Spring/authoring/ENV connection binding, A4 i opóźnienie są jeszcze niezaimplementowane.
Nie uruchomiono stacka ani E2E. Kolejny krok: A3, następnie A4. Materiał do osobnego
review; wyniki testów nie oznaczają samodzielnego odbioru architektury.

## Osobny review A1/A2 — 2026-09-15

Odbiór wstrzymany: AR-REV-1 (stop zamyka sesję przed ACK długiego callbacku,
wiadomość wraca po starcie) i AR-REV-2 (martwa subskrypcja nadal zgłasza RUNNING).
Oba przypadki odtworzono na rzeczywistym brokerze osadzonym; ponowne 309 testów
reactora przeszło. Nie wykryto konkurencyjnego właściciela konfiguracji/nazw Artemis.
Raport i dowody: [review A1/A2](../architecture/artemis-a1-a2-review-2026-09-15.md).
W zadaniu review nie poprawiano kodu produkcyjnego; przed odbiorem potrzebne są
poprawki tych przypadków i osobny review. A3–A6 nadal pozostają do wykonania.

### Poprawka AR-REV-2 — 2026-09-15

Na polecenie użytkownika poprawiono wyłącznie AR-REV-2. Stan kanału pochodzi z
rejestracji handlera i rzeczywistego stanu konsumenta Core; usunięto kopię RUNNING.
Utrata brokera/zamknięcie adaptera daje STOPPED, a nieudany jawny start zgłasza błąd.
Dwa testy odtwarzają błąd przed poprawką i przechodzą po niej. Finalny reactor:
311 testów, 0 błędów/pominięć (25 Artemis). Poprawiono też asercję roundtrip JSON,
która błędnie wymagała stałej kolejności pól obiektu. Kod codec/ACK bez zmian.
AR-REV-1 pozostaje do omówienia; poprawka AR-REV-2 oczekuje osobnego review.

## Źródła mechaniki klienta

- [Core API 2.40.0](https://artemis.apache.org/components/artemis/documentation/previous/2.40.0/core.html)
- [Scheduled messages](https://artemis.apache.org/components/artemis/documentation/latest/scheduled-messages)

## Zatwierdzona korekta przyjęcia Work — 2026-09-15

Użytkownik zatwierdził usunięcie wykonania inline także dla Rabbita. Jeden executor
przy każdym maxInFlight; ACK po przyjęciu zadania, bez zależności od wyniku.
Stop najpierw zamyka przyjmowanie i budzi oczekujących na pojemność. Tylko zadanie
nieprzyjęte pozostaje do dostarczenia; przyjęte kończy się bez requeue. Jeden właściciel
polityki w SDK, adaptery wykonują rozliczenie transportu. To jawny wyjątek od wcześniejszego
zamrożenia historycznej gałęzi synchronicznej, bez zmiany CONTROL ani WorkItem wire.
Korekta jest zaimplementowana. MessageWorkExecutor w SDK jest jedynym właścicielem
pojemności i przyjęcia zadania; usunięto gałąź inline i fallback po odrzuceniu executora.
MessageWorkInput zamyka przyjmowanie przed stopem transportu. Adaptery tłumaczą
wyłącznie WorkNotAcceptedException: Rabbit na requeue, Artemis na brak ACK do zamknięcia
sesji. Błędy przyjętych zadań pozostają diagnostyczne, bez redelivery.

Weryfikacja: 704 testy w 21 modułach reactora, bez błędów i pominięć.
`./mvnw -B -ntp -pl common/worker-sdk,common/artemis-adapter -am test`
(log `/tmp/work-admission-reactor-tests.log`). Po uzupełnieniu regresji pamięciowego
adaptera wszystkie jego 6 testów również przeszło, bez błędów i pominięć
(log `/tmp/work-admission-fixture-tests.log`).
Testy komponentowe sprawdzają limity 1 i 2, ACK bez czekania na koniec pracy,
stop przy zajętej pojemności i ponowne dostarczenie tylko nieprzyjętej wiadomości.
Artemis: rzeczywisty broker osadzony. Rabbit: rzeczywisty listener Spring z mockiem
klienta AMQP. Testy executora obejmują również zmianę limitu i zamknięcie bez
przerywania przyjętych zadań. Stack/E2E nie były uruchamiane.

AR-REV-1: usunięto blokowanie callbacku wykonywaniem workera i oczekiwaniem na
pojemność podczas stopu w ścieżce SDK. To nie zmienia natywnego limitu zamykania
Artemis dla dowolnego zewnętrznego callbacku ani gwarancji przy utracie połączenia.
AR-REV-1/2 oczekują osobnego review; A3–A6 pozostają do wykonania.


## Review korekty przyjęcia Work — 2026-09-15

Odbiór wstrzymany: WA-REV-1 — zbiorczy ACK Artemis może usunąć wcześniejszą
nieprzyjętą wiadomość; WA-REV-2 — monitor SDK blokuje stop przy starcie z zaległościami
adaptera pamięciowego; WA-REV-3 — wygaszanie wątków cached pool zmienia reużycie
połączeń PER_THREAD. Trzy regresje odtworzone; istniejące 705 testów przechodzi.
AR-REV-2 (martwa subskrypcja raportująca RUNNING) ponownie sprawdzony — poprawka
potwierdzona. Review nie zmienia kodu produkcyjnego. A3–A6 nadal odłożone do
zakończenia bieżącego etapu. [Raport i dowody](../architecture/work-admission-review-2026-09-15.md).


### Poprawki WA-REV-1/2/3 — 2026-09-15

Wszystkie trzy poprawki zaimplementowane: indywidualny ACK Artemis; pauzowanie
przyjmowania bez czekania na monitor zajęty startem transportu; stałe wątki executora
z rozmiarami wynikającymi z jednego limitu. Close jest końcowe. Adapter pamięciowy
i procesor nie dostały dodatkowej polityki wykonania.

Sześć nowych przypadków regresyjnych nie przechodziło przed poprawkami; przechodzi
po nich. Finalnie 711 testów w 21 modułach reactora, 0 błędów i pominięć, wraz z
rzeczywistą 61-sekundową przerwą sprawdzającą zasoby PER_THREAD. Reprodukcja SDK
z późniejszym błędnym JSON-em pozostawia teraz nieprzyjętą wiadomość w kolejce.
Log `/tmp/wa-rev-fixed-reactor.log`; szczegóły w powyższym raporcie review,
w sekcji implementation follow-up. Poprawki oczekują osobnego review. A3–A6 bez zmian;
stack/E2E nie były uruchamiane.


### Ponowny review WA-REV — 2026-09-15

Poprzednie trzy regresje potwierdzone jako poprawione w ich odtworzonych przypadkach;
30 testów focused review przechodzi, w tym 61-sekundowy test zasobów wątków.
WA-REV-4 wycofany jako P2 po sprawdzeniu rzeczywistych wywołań: aktualny odbiorca
CONTROL wykonuje zmiany stanu szeregowo, a normalny lifecycle Springa nie dostarcza
przeplotu wymuszonego przez probe. Test używał trzech ręcznie utworzonych wątków i
wstrzymanego powrotu startu; nie wykazał osiągalności w aktualnym flow aplikacji.
Obserwacja pozostaje warunkowa na wypadek zmiany współbieżności CONTROL lub dodania
równoległych wywołań lifecycle. Nie blokuje A3–A6 i nie wymaga teraz zmiany kodu.
Dowody i granice w sekcji korekty powyższego raportu. Bez ponownego uruchamiania
testów, zmian implementacji ani rozszerzania zakresu A3–A6.

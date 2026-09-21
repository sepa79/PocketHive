# Artemis WorkPlane i opóźnione dostarczenie dla 3DS

Status na 2026-09-21: A1–A5 wykonane. Osobne review A4 i A5 zakończone bez findings; A5 zapisane w `6e3b0aa4`. PR #517 zmergowany w `713e7559`. A6 (pełny 3DS i obciążenie) pozostaje osobnym etapem. Datowane wpisy poniżej zachowują historię; dawne „oczekuje review” nie opisują bieżącego statusu.
Branch: `codex/artemis-work-plane`, punkt wyjścia: `863694be`.

## Domknięcie A4/A5 i merge OAuth — 2026-09-21

- A4: osobny review na `713e7559` bez findings. Prześledzono projekcję startową,
  Controller → WorkPlaneResources → native remove oraz niezależną weryfikację
  nieobecności w Orchestratorze. **59 testów**, zero błędów i pominięć.
  Manifest diagnostyczny nie jest źródłem zwykłego remove; jego zakres pozostaje Rabbit-only.
- A5: osobny review bez findings, **16 testów** powtórzonych; implementacja i dowody
  zapisane w `6e3b0aa4`. Kontrakt §12 pozostaje właścicielem zachowania.
- PR #517 zmergowany w `713e7559`: **2523 testy Javy, 187 testów VS Code**, zero
  błędów i pominięć; build dokumentacji i kontrola zawartości VSIX przeszły.
  Dodano poradnik OAuth i jego stronę testów do publikowanych dokumentów.
- Stack z `713e7559` przebudowany przez `build-hive.sh --quick`. Smoke, trzy
  przypadki lifecycle i delayed delivery przeszły przez publiczny ingress; końcowa
  lista swarmów pusta. Pierwsze wspólne wywołanie smoke/lifecycle miało błędny target
  dla smoke; poprawne osobne powtórzenie przeszło. Pełne dowody i ograniczenia:
  [weryfikacja po merge](../ci/acceptance-coverage.md#post-merge-local-verification--2026-09-21).
  Lokalny odbiór A1–A5 jest domknięty; pozostaje przygotowanie PR/domknięcie brancha.
- Osobny zakres nadal otwarty: A6, selector/splitter, mock APATA/App i CloseLook;
  NW-4 na Swarm/NFS, końcowy N3 i zatwierdzone przez użytkownika odłożenie N4;
  natywny orphan cleanup oraz kompletność diagnostyki manifestu.

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

## Aktualne ustalenia 3DS — 2026-09-21

Ta sekcja zastępuje wcześniejsze robocze założenia o PROSA i pollingu.
Jest zapisem uzgodnień i kolejności prac, nie deklaracją gotowej implementacji
ani zatwierdzeniem nowych pól publicznych kontraktów.

### Przepływ i granice mocka

Inicjatorem jest **MC/APATA**, nie PROSA. PH symuluje MC/APATA oraz aplikację
mobilną w **jednym mocku**, z osobnymi handlerami i jednym właścicielem stanu:

1. MC/APATA → nasz system: POST po dane kart; odpowiedź zawiera dane.
2. MC/APATA → nasz system: POST SCASend. Nasz system wywołuje webhook aplikacji
   (endpoint mocka), następnie odpowiada APATA.
3. PUSH do aplikacji; nadawca prawdopodobnie APATA, nadal do potwierdzenia.
4. Aplikacja → nasz system: PATCH z działaniem użytkownika.
5. Nasz system → APATA: Authenticate/Decline przez mTLS.
6. APATA → nasz system: POST finalise.
7. Transakcja ISO; kierunek i końcowy warunek odbioru do ustalenia.

Nie zakładać pętli pollingu: nowszy opis jej nie zawiera. Potwierdzić kontrakty
HTTP, identyfikatory korelacji, relację webhook/PUSH i zdarzenie rozpoczynające
czas reakcji aplikacji. PUSH można zastąpić wewnętrznym zdarzeniem mocka dopiero
po potwierdzeniu, że jego transport nie jest przedmiotem testu. Osobno ustalić SMS.

### Stan w pamięci, oczekiwanie w Artemis

- Pierwsza wersja: jedna instancja mocka, małe rekordy challenge w pamięci procesu.
  Rekord przechowuje identyfikatory przebiegu/testu, etap, wybraną decyzję i kontekst
  kolejnych wywołań. Handlery obu ról korzystają z jednego API właściciela stanu.
- Oczekiwanie realizuje Artemis. Opóźnione zadanie wskazuje rekord konkretnego
  przebiegu i po dostarczeniu uruchamia krótką akcję, np. PATCH. Publikacja przez
  porty PH i adapter; bez bezpośredniego klienta brokera w mocku.
- Nie tworzyć wątku, otwartego HTTP ani osobnego timera na oczekujący challenge.
  Cel: dziesiątki tysięcy oczekujących challenge, heavy load i udział 10–20%.
  Kilka sekund poślizgu brokera (np. 5 s) jest akceptowalne; nie zmienia to
  rzeczywistego deadline/expiry testowanego systemu. Pojemność trzeba zmierzyć.
- Zakończone rekordy zwalniać po przekazaniu wymaganych dowodów; dla niedokończonych
  uzgodnić jawny czas retencji i cleanup przebiegu. Restart mocka traci stan:
  zgłosić przerwanie/błąd testu; zadanie bez rekordu nie powoduje requeue.
- Wiele replik i magazyn Redis dla stanu mocka to późniejszy wariant za tym samym
  API, nie wymaganie pierwszej wersji i nie równoległy drugi właściciel stanu.

### Rozliczanie testu — uzgodniony kierunek, osobny etap

Stan mocka posiada zachowanie symulowanych uczestników; raportowanie posiada
ocenę testu. Nie uznawać wysłanego finalise za dowód zakończenia transakcji ISO.

Domyślnie lekki bilans liczników rozpoczętych transakcji, końców w PP i znanych
błędów terminalnych. Liczyć transakcje, nie wszystkie wiadomości. Zgodność
liczników nie dowodzi zgodności identyfikatorów. Zaległych kolejek po Stop nie
opróżniać i nie produkować osobnego błędu dla każdego niewykonanego elementu.

Opcjonalny CloseLook: zapis oczekiwania przed pierwszą publikacją do Summary Table
w Redis, wynik z PP i natychmiastowe zgłoszenie błędu (także templating/publikacja).
Aktualizacje postępu i pełne payloady są opcją debug. Jeden moduł posiada klucze,
rekordy i ocenę; interceptory, PP i SC delegują do niego. Kontrola przez delayed
work oraz Final Summary wywoływane przez SC wykorzystują tę samą ocenę. Zachować
informację o spóźnionych wynikach. Włączenie CloseLook później nie odtworzy
niezapisanych wcześniej identyfikatorów. Granicę Stop/zbierania wyników uzgodnić
przed implementacją, bez rozszerzania teraz refaktoru lifecycle.

### Co robimy dalej

1. **A5.1 — kontrakt zatwierdzony 2026-09-21.** Kanoniczny opis:
   [Delayed Work delivery](../architecture/work-plane-boundaries.md#12-delayed-work-delivery).
2. **A5.2 — wykonane i odebrane lokalnie 2026-09-21.** Neutralna polityka,
   eksport startupu i pojedyncza ścieżka publikacji są podłączone. Rzeczywisty broker,
   przepływ Controller/SDK i nowy E2E przez ingress przeszły testy. Dowody:
   [A5 w mapie pokrycia](../ci/acceptance-coverage.md#delayed-delivery-a5--2026-09-21).
3. **Kolejny krok — OutputSelector i splitter.** Selector wybiera logiczny cel,
   splitter jawnie rozdziela pracę na zadeklarowane wyjścia, np. przepływ główny
   i CloseLook. Jeden właściciel reguł wspólny z wyborem Redis; bez drugiego SSOT,
   samodzielnego składania adresów i dodatkowych publisherów w interceptorach.
   To osobny projekt kontraktu, nie warunek pierwszego testu delayed delivery.
4. **Mock APATA/App oraz CloseLook** jako oddzielne części implementacji po
   uzgodnieniu ich kontraktów. Następnie A6: pełny przepływ, jawne negatywne przypadki
   i pomiar utrzymywanego obciążenia razem z kosztem raportowania.

A5 realizuje termin natywnie w ArtemisWorkOutput. N4/usunięcie starego E2E,
NW-4 na Swarm/NFS i wcześniej odłożone refaktory pozostają poza tym krokiem.

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

### A3 — konkretny zakres wykonania (2026-09-15)

Punkt kontrolny A1/A2 i poprawek przyjęcia Work: commit `7e6c63db`.
Wybór pozostaje jeden na uruchomienie PocketHive, zgodnie z warunkowym wyborem #1
użytkownika: zachować działające funkcje WorkPlane Rabbita. Nie dodajemy wyboru per swarm.
Oprócz zwykłego transportu obejmuje to debug tap. Wiek najstarszej wiadomości nie jest
obecnie dostarczany również przez SpringRabbitResources; brak tej obserwacji w obu
adapterach nie jest utratą funkcji przy zmianie WorkPlane.

- Adapter posiada authoring parsery, mapowanie RESOLVED/ENV i warunki bootstrapu.
  AUTHORING zawiera `consumerWindowBytes` dla input i `persistent` dla output;
  oba ustawienia są jawne, bez dodatkowych wartości domyślnych. Fizyczne `queue` i
  `address` pochodzą z resolvera topologii. Rekordy ustawień i parsery korzystają
  z tych samych reguł skalarnych. Binding nie może zastąpić brakującej wartości zerem/false.
- Połączenie Artemis wymaga brokerUrl, username, password i callTimeoutMillis.
  Właściciel połączenia eksportuje je do ENV, bez przejmowania ustawień Rabbit CONTROL.
- `CurrentWorkConfigurationProviders` dopina providery Artemis do istniejącego
  neutralnego parsera i polityki aktualizacji. Ustawienia transportu są startup-only.
- Kompozycja wybiera jawnie WorkPlane dla Orchestratora i przekazuje ten wybór
  Controllerowi. Workery nadal deklarują adaptery wejścia i wyjścia w konfiguracji.
  Rabbitowe ustawienia traffic mają być wymagane wyłącznie przy wybranym Rabbit WORK.
- Lokalny docker-compose uruchamia broker Artemis w ramach standardowego build-hive.sh.
  Testy stacka korzystają z publicznego ingress. A4 nadal odpowiada za zdjęcie blokady
  RuntimeRabbitManifest ze zwykłego create/remove; A5 za zamiar opóźnienia.

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

2026-09-21: użytkownik wybrał domknięcie A5 przed zamknięciem brancha.
Kontrakt zatwierdzony przez użytkownika 2026-09-21: [kontrakt delayed delivery](../architecture/work-plane-boundaries.md#12-delayed-work-delivery).
Projekt ustala konfigurację scenariusza, początek odliczania, właścicieli i odbiór;
implementacja i lokalny odbiór wykonane 2026-09-21.

Aktualizacja kolejności 2026-09-18: po osobnym review poprawki DA-3 i lokalnego
raportu N3 bez uwag wracamy do tego etapu. Użytkownik odkłada usunięcie starego
frameworka do swoich ręcznych testów i potwierdzenia. N4 oraz odłożone wykonanie
NW-4 na Swarm/NFS nie blokują rozpoczęcia A5; nie oznacza to zamknięcia N3 ani A6.
Bieżące bramki zastąpienia posiada [plan E2E](e2e-test-system.md).

- Producent/odbiorca, źródło wartości i moment liczenia są zatwierdzone w kanonicznym kontrakcie §12.
- Neutralny zamiar dostarczenia przechodzi przez DefaultWorkerRuntime →
  WorkOutputRegistry → wybrany WorkOutput. Bez dodatkowego publish w workerze.
- Adapter wysyła od razu do brokera, który udostępnia wiadomość nie wcześniej niż
  w terminie. Tylko adapter zna właściwości brokera i przeliczenie na jego termin.
- Adapter bez tej możliwości jawnie odrzuca żądanie. Nie dodawać timerów, sleep,
  retry/DLQ ani fallbacku w workerach.
- Odbiór: brak przedwczesnego odbioru, odbiór po terminie i poprawne usuwanie kolejek
  z wiadomościami oczekującymi. Nie obiecywać dostarczenia dokładnie co do milisekundy.

Odbiór A5: pełny reactor 1905 testów bez błędów/pominięć; po końcowych zmianach
16 testów obszaru, wszystkie zielone. Nowy E2E `delayed-delivery` przeszedł na
lokalnym stacku przebudowanym przez `build-hive.sh --quick`: delay 3000 ms, odczytane
odstępy generator → processor 3054/3143/3004 ms, poprawne HTTP oraz CREATE/START/STOP/REMOVE.
[Mapa pokrycia](../ci/acceptance-coverage.md#delayed-delivery-a5--2026-09-21)
posiada identyfikator przebiegu i zakres dowodów. To nie jest odbiór obciążenia ani A6.

### A6 — odbiór całej ścieżki

Aktualizacja kolejności 2026-09-15: użytkownik zdecydował o napisaniu
[całkowicie nowego frameworka E2E](e2e-test-system.md). Bez przenoszenia starych
kroków/helperów i bez zależności od starego zestawu. N1 odbiera pierwszy kompletny
przebieg przez ingress na Rabbit i Artemis; N2 buduje pozostałe wymagane pokrycie.
Stary zestaw pozostaje zamrożony do potwierdzenia zastąpienia N3, po czym N4 go usuwa.
N1 wykonane: 38 testów frameworka oraz oba nowe testy ingress przeszły na Rabbit
i Artemis (create/start/HTTP/stop/remove oraz cleanup po celowym błędzie). Pierwszy
przypadek N2 — operacje w osiągniętym stanie — także przeszedł na obu adapterach.
Lokalny stack po odbiorze Rabbit przywrócono do Artemis. Szczegóły i jawne luki
zawiera mapa pokrycia nowego frameworka. To nie zamyka A6 ani zastąpienia starego zestawu.
Masowe uruchamianie obecnego harnessu wymagającego Rabbit WORK nie odbiera Artemis.

- Testy zachowania z A1–A5 powstają razem z implementacją. Review śledzi wywołania,
  jednego właściciela i brak alternatywnych ścieżek; zielone testy nie zastępują review.
- Zbudować lokalny stack przez build-hive.sh; nowym frameworkiem przez oficjalny
  ingress sprawdzić Artemis/3DS oraz wspólne zachowania Rabbit/CONTROL według
  jawnej macierzy wymagań. Ukończenie N1 samo nie zastępuje całego starego zestawu.
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
W chwili zakończenia A1/A2 Spring/authoring/ENV binding, A4 i opóźnienie nie były jeszcze zaimplementowane.
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


## Przekazanie implementacji A3 — 2026-09-15

Zrealizowano jawny wybór jednego WorkPlane na uruchomienie, authoring/RESOLVED/ENV,
kompozycję Orchestratora/Controllera i transportów SDK oraz debug tap Artemis.
Konfiguracja ma jednego właściciela w adapterze; Rabbit traffic jest odczytywany tylko
przez wybraną kompozycję Rabbit. Scenario Manager pozostaje konsumentem neutralnego
parsera. Rabbit CONTROL nie wymaga Artemis ani Rabbit WORK dla workerów bez Rabbit IO.

Weryfikacja: łącznie **1391 testów zaliczonych, 0 błędów, 0 pominięć** w dotkniętym
reactorze i końcowej powtórce całego Swarm Controllera. Przebieg
`/tmp/artemis-a3-final-reactor.log` zaliczył 1173 testy poza Controllerem; w Controllerze
ujawnił stary test zakładający wykonanie synchroniczne i pominął dwa testy wymagające
lokalnego RabbitMQ. Po poprawieniu synchronizacji testu i uruchomieniu brokera cały
zestaw Controllera zaliczył **218/218** (`/tmp/artemis-a3-controller-with-rabbit.log`).
Nie zmieniono przy tym kodu wykonania/ACK. RepositoryImportBoundaryTest i Enforcer przeszły.

ArtemisWorkPlaneFlowTest wykonuje Controller compose → bootstrap/ENV → Spring binding
oraz rzeczywiste transporty SDK → DefaultWorkerRuntime → jeden poprawny wynik.
Odrzucona aktualizacja zachowuje przyjęty stan; błąd wykonania daje work error bez
ponownego dostarczenia. ArtemisDebugTapTest sprawdza kopie bez odbierania oryginałów,
limit najnowszych próbek, TTL tylko kopii i usunięcie zasobów przy close.
ArtemisConfigurationTest sprawdza wymagane ustawienia, authoring bez adresów oraz
zgodność rozwiązanej konfiguracji i danych odczytanych z ENV. Dotychczasowe testy
Rabbit sprawdzają osobne połączenia CONTROL/WORK i projekcję traffic.

Przeszukano produkcyjne użycia ArtemisConfiguration/ArtemisEnvironmentKeys,
consumerWindowBytes, WorkPlaneSelection, RabbitControllerTopologyEnvironment i
kompozycje usług. Reguły pól i fizycznych nazw pozostają wewnątrz adapterów;
kompozycje delegują do ich API. To materiał do osobnego review, nie jego zastępstwo.

Lokalny compose: Artemis **2.40.0** i RabbitMQ działają, oba healthy. Artemis nie
publikuje portu hosta. Domyślna deklaracja w lokalnym compose pozostaje RABBITMQ.
Nie wykonano pełnego startu swarma Artemis ani start-e2e-tests.sh: A4 usuwa jeszcze
bramkę RuntimeRabbitManifest ze zwykłego create, A5 dodaje zamiar opóźnienia,
A6 odbiera cały stack i regresję E2E.

Jawne ograniczenia: ustawienia transportu Artemis są startup-only; po utracie procesu
natywny divert/reguła adresu debug tap mogą pozostać w brokerze. Normalny close je
usuwa i raportuje błędy. Odzyskiwanie po awarii oraz manifest/orphan cleanup pozostają
osobnym refactorem, bez dodatkowego rejestru. Obaj adapterzy nie dostarczają obecnie
wieku najstarszej wiadomości. A3 pozostaje bez commita, do review.


## Review A3 po ścieżkach kodu — 2026-09-15

Zakres: niezacommitowane A3 nad `7e6c63db`, w tym nowe pliki. Prześledzono
Orchestrator → Controller → bootstrap workera → konfiguracja/SDK/transport oraz
Orchestrator → debug tap. Review nie zmienia implementacji. **Odbiór wstrzymany**
do poprawy dwóch uwag P2:

- **A3-REV-1 — brak zależności startowej od Artemis.** Lokalny compose deklaruje
  endpoint Artemis, lecz `orchestrator.depends_on` pomija ten broker. Wybranie
  ARTEMIS aktywuje ArtemisWorkPlaneConfiguration i otwiera połączenie podczas
  bootstrapu Springa. ArtemisSessions celowo nie ma reconnect i otwarcie może
  zakończyć start usługi błędem. Częściowy start przez build-hive/compose uruchamia
  tylko zależności Orchestratora, więc brakujący broker nie zostanie uruchomiony;
  przy pełnym starcie nie ma też gwarancji oczekiwania na jego healthcheck.
  Potwierdzenie: `docker compose --dry-run` z tym samym plikiem i tymczasowym
  nadpisaniem tylko selektora na ARTEMIS pomija Artemis w planie startu.
  Poprawka: zadeklarować wymaganą zależność/zdrowie brokera w lokalnej kompozycji,
  bez retry/fallback w kliencie. Nie dotyczy odłożonej bramki manifestu A4.
- **A3-REV-2 — wygasłe kopie tapu pozostają w globalnej ExpiryQueue.**
  ArtemisWorkDebugTap ustawia expiryDelay i ringSize, lecz jego dokładna reguła
  adresu dziedziczy expiry-address=ExpiryQueue z reguły `#` obrazu 2.40.0.
  Odtworzono na brokerze in-process: kopia z TTL 1 s znika z capture, oryginał
  pozostaje (1 wiadomość), ale ExpiryQueue zawiera kopię także po close tapu.
  Ring/TTL tapu nie ograniczają dalszego przechowywania tych diagnostycznych kopii.
  To zwykłe wygaśnięcie wiadomości, nie odłożony przypadek awarii procesu/orphans.
  Poprawka powinna ograniczyć politykę expiry do capture i odrzucać wygasłe kopie,
  zachowując politykę źródłowego WORK. Obecny EmbeddedArtemis nie ustawia globalnego
  expiry-address, dlatego istniejący test TTL nie obejmuje tej konfiguracji.

Właściciele i dowody ścieżek:

| Odpowiedzialność / kontrakt | Sprawdzona ścieżka i właściciel | Wynik |
| --- | --- | --- |
| RESP-WORK-ADAPTER-SELECTION | Kompozycje obu usług → CurrentWorkPlaneSelection/WorkPlaneSelection → warunki adapterów; Controller dostaje selection z connectionEnvironment | Jeden jawny wybór; luka wdrożeniowa A3-REV-1 |
| RESP-ARTEMIS-CONFIGURATION | CurrentWorkConfigurationProviders → neutralny parser → ArtemisConfiguration/parsery/ArtemisSettingValues; bootstrap → typed settings → ENV → Binder | Jedna implementacja reguł skalarnych, jawne tuning/połączenie, brak adresów AUTHORING |
| RESP-WORK-RESOURCE-NAMES / RESP-ARTEMIS-RESOURCE-NAMES | ContainerLifecycleManager i Controller → WorkTopologyResolver → ArtemisResourceNames; te same projekcje dla ENV, zasobów, statystyk, debug | Nie znaleziono alternatywnych formuł nazw; Rabbit traffic przeniesiony do RabbitControllerTopologyEnvironment |
| RESP-ARTEMIS-CONNECTION | Manager import → ArtemisWorkPlane/ArtemisSessions; worker IO condition → własne sesje z wyeksportowanego połączenia | Rozdzielone połączenia CONTROL/WORK, bez aktywacji Rabbit WORK dla Artemis |
| RESP-WORK-STATE / RESP-WORK-PATCH-POLICY | WorkerDefinitionDiscovery/Binder → WorkerControlPlaneRuntime → candidate validator i rejestr polityk | Odrzucony patch zachowuje stan; tuning Artemis pozostaje startup-only |
| RESP-WORK-ARTEMIS-TRANSPORT | MessageWorkInput → MessageWorkExecutor → DefaultWorkerRuntime → WorkOutputRegistry → ArtemisWorkOutput | Admission/indywidualny ACK zachowane; przyjęty błąd workera nie wraca do kolejki |
| RESP-WORK-RESOURCE-NAMES / debug | DebugTapService → WorkDebugTaps → ArtemisWorkDebugTap → natywna kopia, ring/expiry i close | Źródło nienaruszone; A3-REV-2 narusza ograniczenie retencji kopii |

Przeszukano całe produkcyjne drzewo Java pod kątem consumerWindowBytes,
ArtemisEnvironmentKeys/POCKETHIVE_WORK_ARTEMIS, createDivert/expiryDelay,
WorkPlaneSelection/CurrentWorkPlaneSelection, RabbitControllerTopologyEnvironment,
controllerEnvironment/connectionEnvironment i kompozycji usług. Oceniono wskazane
implementacje oraz ich delegacje, nie sam wynik wyszukiwania/import check.

Sześć przebiegów review: plan — uwagi A3-REV-1/2 dotyczą A3, A4–A6 pozostają jawnie
odłożone; styl — separacja typów i nagłówki zgodne w zmienionej produkcji;
zwięzłość — brak dodatkowego rejestru/state machine, wspólne reguły skalarne;
security — jawne połączenia, brak URI-overrides i logowania hasła, nazwy kodują
segmenty; retencja diagnostyczna wymaga A3-REV-2; biblioteki — auto-config korzysta
z już przyjętego Spring Boot, broker testowy pozostaje test-only, Core wersjonowany
przez istniejący BOM; czytelność — nagłówki i dokumenty określają właścicieli,
znane ograniczenia nie są przedstawione jako zrealizowane funkcje.

Niezależna weryfikacja review: **23 testy zaliczone, 0 pominięć**, w tym
ArtemisConfigurationTest, ArtemisDebugTapTest, ArtemisWorkPlaneTest,
ArtemisWorkAdmissionTest, ArtemisWorkPlaneFlowTest, RabbitPlaneOperationsTest,
RabbitWorkTopologyTest i RepositoryImportBoundaryTest. Enforcer i diff-check przeszły.
Artefakty lokalne: `/tmp/artemis-a3-review/focused-tests.log`, `debug-probe.log`,
`A3DebugTapProbe.java`, `local-broker.xml`, `compose-dry-run.log` w tym samym katalogu.
Dry-run nie zmienił działających usług (nadal tylko Artemis/Rabbit). Nie wykonano
pełnego stacka/E2E ani napraw produkcyjnych. Nie zgłoszono ponownie WA-REV-4 ani
odłożonych manifestów/orphan cleanupu.

Mechanizmy potwierdzone także w dokumentacji dostawców:
[Compose startup order](https://docs.docker.com/compose/how-tos/startup-order/),
[Artemis message expiry](https://artemis.apache.org/components/artemis/documentation/latest/message-expiry.html),
[Artemis address settings](https://artemis.apache.org/components/artemis/documentation/latest/address-settings.html).
Wersję i ustawienie expiry potwierdza dodatkowo lokalny broker 2.40.0, a efekt kopii
reprodukcja na bibliotekach 2.40.0 używanych przez projekt.

## Poprawka A3-REV-2 — zakres zatwierdzony 2026-09-15

Użytkownik zatwierdził naprawę wyłącznie punktu 2; A3-REV-1 pozostaje do rozmowy.
Właściciel nadal RESP-WORK-ARTEMIS-TRANSPORT / ArtemisWorkDebugTap. Dokładna reguła
capture ma jawnie wyłączać expiry forwarding. Odbiór poprawki: rzeczywisty broker
2.40.0 z globalnym expiry-address=ExpiryQueue usuwa wygasłe kopie tapu, zachowuje
oryginał bez TTL i nadal przekazuje zwykłe wygasłe wiadomości WORK do ExpiryQueue.
Nie zmieniamy kompozycji startowej, ACK, publicznych portów ani orphan cleanupu.

Wdrożone: `ArtemisWorkDebugTap` ustawia `expiryAddress: ""` wyłącznie dla swojego
capture. Pusta wartość jawnie wyłącza forwarding; `null` dziedziczyłby wildcard.
To natywne zachowanie potwierdzone w kodzie biblioteki 2.40.0 i teście brokerskim;
[opis dostawcy](https://artemis.apache.org/components/artemis/documentation/latest/message-expiry.html#_dropping_expired_messages).
Fixture EmbeddedArtemis odtwarza teraz globalną ExpiryQueue obrazu, a skan expiry
co 100 ms skraca czas testu. Nie zmieniono konfiguracji wdrożonego brokera.

Regresja `ArtemisDebugTapTest.captureExpiryDiscardsCopiesAndPreservesSourceExpiryPolicy`
przed poprawką: **1 błąd / 2 testy**, oczekiwane 0 kopii w ExpiryQueue, faktycznie 1.
Po poprawce: **170 testów zaliczonych, 0 błędów/pominięć** w reactorze adaptera i jego
zależności, w tym 32 testy samego adaptera. Test wykazuje usunięcie kopii, pozostawienie
oryginału bez TTL oraz zachowanie routingu prawdziwego expiry WORK przy aktywnym tapie
i brak dodatkowej kopii po jego close. Enforcer i diff-check przeszły.
Logi: `/tmp/artemis-a3-fix2-before.log`, `/tmp/artemis-a3-fix2-after.log`.

Wyszukanie `expiryDelay|expiryAddress|expiry-address|createDivert|setExpiryAddress` w
całym drzewie Java/XML/YAML wskazało ArtemisWorkDebugTap jako jedynego właściciela tej
polityki produkcyjnej. Porty, konsumenci oraz granice importów pozostają bez zmian.
To przekazanie poprawki do odbioru, bez ponownego self-review ani pełnego stacka/E2E.
**A3-REV-1 pozostaje otwarte**; historyczne uwagi review powyżej zachowano jako dowód.

## Poprawka A3-REV-1 — zatwierdzone odroczenie połączenia, 2026-09-15

Po rozmowie użytkownik wybrał start CONTROL z Rabbit i otwarcie połączenia Artemis
przy pierwszej operacji WORK. To zastępuje proponowane w review depends_on/profil.
Właściciel połączenia pozostaje ArtemisSessions; ArtemisWorkResources usuwa własne
żądanie sesji z konstruktora. Konfiguracja, topologia i projekcje nie potrzebują brokera.
Niedostępność podczas operacji jest jawnym błędem; nie dodajemy automatycznego retry,
failover, oczekiwania w Compose ani zmian ACK. Następna jawna operacja może ponownie
spróbować otworzyć pierwsze połączenie. Zamknięty właściciel nie może się reaktywować.

Warunki odbioru: kompozycja portów Orchestratora startuje bez brokera; operacje
zasobów zgłaszają błąd bez fałszywej nieobecności/sukcesu; broker uruchomiony później
pozwala wykonać jawną operację; zamknięcie przed pierwszym użyciem jest bezpieczne
i terminalne. A4–A6 oraz manifest/orphan cleanup pozostają poza tą poprawką.

Wdrożone: ArtemisSessions konfiguruje locator bez połączenia w konstruktorze,
otwiera i zachowuje fabrykę przy pierwszym open(), a close() jest terminalne także
przed inicjalizacją. ArtemisWorkResources pobiera własną sesję dopiero po walidacji
ensure/observe/remove; kolejne operacje korzystają z tej samej sesji. Wywołania
management używają jej przez istniejący ArtemisManagement. Nie dodano nowego
właściciela połączenia, flagi wyboru adaptera ani profilu/zależności Compose.

Ścieżka startu: Orchestrator WorkPlaneConfiguration → ArtemisWorkPlaneConfiguration
→ ArtemisWorkPlane → właściciele portów. RuntimeOwnershipManifestFactory i
AmqpRabbitTopologyAdapter odczytują projekcje/identity bez I/O; dopiero jawne
wywołania DebugTapService i RuntimeRemovalPostconditionVerifier potrzebują brokera.
Przeszukano produkcyjne drzewo pod kątem createServerLocator/createSessionFactory,
ArtemisWorkResources, sessions.open oraz inicjalizacji i konsumentów portów
Orchestratora. Jedynym właścicielem fabryki nadal jest ArtemisSessions. Sesje zasobów,
inputu, outputu i tapu pozostają oddzielnymi sesjami konkretnych operacji tego właściciela.

Dowody: nowe 3 testy ArtemisDeferredConnectionTest przed poprawką kończyły się
błędem konstruktora (Cannot open Artemis connection). Po poprawce **46 testów
zaliczonych, 0 błędów/pominięć**: 35 adaptera, 2 admission SDK, 1 rzeczywistej kompozycji
portów Orchestratora bez brokera, 7 postconditions usuwania oraz 1 przepływu
Controller → konfiguracja/SDK → transport Artemis. Sprawdzono późny start brokera,
jawne błędy ensure/observe/remove bez fałszywego sukcesu, późniejszą jawną próbę
oraz terminalne close przed pierwszym użyciem. Enforcer i diff-check przeszły.
Logi: `/tmp/artemis-a3-fix1-before.log`, `/tmp/artemis-a3-fix1-after.log`.

A3-REV-1 jest rozwiązane zatwierdzoną zmianą aktywacji, a wcześniejsza propozycja
oczekiwania w Compose pozostaje wyłącznie historycznym wynikiem review. Nie wykonano
pełnego stacka/E2E ani ponownego self-review. A4–A6 pozostają otwarte.

## Ponowny review A3 po poprawkach — 2026-09-15

Zakres: bieżące niezacommitowane A3 nad `7e6c63db`, z poprawkami A3-REV-1/2 i nowymi
plikami. **Brak nowych ustaleń blokujących w sprawdzonych ścieżkach.** Obie poprawki
można odebrać; nie jest to odbiór pełnego uruchomienia Artemis z A4–A6.

| Kontrakt / właściciel | Prześledzona ścieżka i wynik |
| --- | --- |
| RESP-WORK-ADAPTER-SELECTION / CurrentWorkPlaneSelection | Kompozycje Orchestratora i Controllera wybierają przez jeden parser; connectionEnvironment przenosi wybór, controllerEnvironment przenosi namespace. Rabbit CONTROL niezależny. |
| RESP-ARTEMIS-CONFIGURATION / ArtemisSettingValues, ArtemisConnectionEnvironment | Neutralne providery → parsery/typed settings → bootstrap → ENV/binding; te same reguły i jawne pola, bez dostępu do brokera. |
| RESP-ARTEMIS-RESOURCE-NAMES / ArtemisResourceNames | Resolver → projekcje dla transportu, ENV, statystyk, usuwania i debug; brak alternatywnych formuł. |
| RESP-ARTEMIS-CONNECTION / ArtemisSessions | Kompozycja → WorkPlane/fabryki bez połączenia; pierwsze open tworzy współdzieloną fabrykę. Nieudana próba nie zgłasza sukcesu, kolejna jawna próba może działać; close jest terminalne. Tworzenie konkretnego outputu, start inputu i otwarcie tapu nadal są operacjami żądającymi sesji. |
| RESP-ARTEMIS-RESOURCES / ArtemisWorkResources, ArtemisManagement | ensure/observe/remove walidują przed pobraniem sesji; odczyt błędu różni się od nieobecności. RuntimeRemovalPostconditionVerifier zachowuje błąd w wyniku. Mapping manifestu jest bez I/O, lecz jego bramka pozostaje zadaniem A4. |
| RESP-WORK-ARTEMIS-TRANSPORT / ArtemisWorkInputChannel, ArtemisWorkDebugTap | Input → MessageWorkExecutor → runtime → output: indywidualny ACK przyjęcia, bez oczekiwania na worker i bez requeue jego błędu. DebugTapService → tap: osobna sesja/kopia, lokalny expiry discard, ring i jawne zwalnianie zasobów; polityka źródła zachowana. |

Nagłówki sprawdzonych właścicieli odpowiadają powyższym rekordom architektury.
Przeszukano całe produkcyjne drzewo Java dla createServerLocator/createSessionFactory,
expiryDelay/expiryAddress/createDivert, consumerWindowBytes, ArtemisResourceNames,
ArtemisConnectionEnvironment i WorkPlaneSelection, następnie sprawdzono delegacje
oraz wywołania connectionEnvironment/controllerEnvironment w obu usługach.

Sześć przebiegów: plan — zakres zgodny z zatwierdzonym odroczeniem połączenia;
styl — istniejący właściciele i nagłówki spójne; zwięzłość — bez nowej warstwy lub
stanu swarma; security — zakres adresów i jawne połączenia zachowane, bez nowych
zmian autoryzacji; biblioteki — istniejący klient/broker 2.40.0, bez nowej zależności;
czytelność — jawna inicjalizacja i terminalne close, udokumentowane granice odbioru.

Niezależnie uruchomiono **54 testy, 0 błędów/pominięć**: cały adapter Artemis,
SDK admission, start kompozycji Orchestratora bez brokera, postconditions usuwania,
przepływ Controller/SDK/Artemis, RabbitPlaneOperationsTest, RabbitWorkTopologyTest
oraz RepositoryImportBoundaryTest. Enforcer i diff-check przeszły.
Log: `/tmp/artemis-a3-rereview-tests.log`. Nie zmieniano kodu produkcyjnego/testów.
Nie uruchamiano pełnego stacka/E2E; A4–A6 i wcześniej odłożony orphan cleanup
pozostają otwarte. Nie przywracano wycofanego ustalenia o hipotetycznym równoległym
wywoływaniu komend CONTROL.

## Lokalne przełączenie na Artemis — 2026-09-15

Po zgłoszonym przez użytkownika zielonym normalnym E2E na Rabbit lokalny
`docker-compose.yml` wybiera jawnie `POCKETHIVE_WORK_TYPE: ARTEMIS`. Odtworzono
wyłącznie Orchestrator z już zbudowanego obrazu; kod Java nie zmienił się w tym kroku.
Rabbit pozostaje CONTROL. Przed przełączeniem lista swarmów przez ingress była pusta.

Potwierdzono w uruchomionym kontenerze selektor ARTEMIS, zdrowe kontenery obu brokerów
i HTTP 200 / status UP z `http://localhost:8088/orchestrator/actuator/health`.
Próba `create` swarma `artemis-switch-check-20260915` przez oficjalny ingress
(`local-rest-defaults`, SUT `wiremock-local`, DIRECT) dała HTTP 500:
`Unsupported resource in current public manifest: WORK_RESOURCE`. Log wskazuje
`RuntimeOwnershipManifestFactory.resources:41` wywołane przez
`ContainerLifecycleManager.startSwarm:141`, przed utworzeniem Controllera.
Ponowny odczyt listy swarmów zwrócił pustą listę; przygotowanie plików runtime
Scenario Managera nastąpiło przed błędem. Nie wykonywano osobnego cleanupu.

To potwierdzenie znanej blokady A4, nie test transportu Artemis. Użyty szablon
nadal deklaruje Rabbit input/output; selektor wdrożenia nie konwertuje scenariuszy.
Kolejny krok: zamknąć zatwierdzone A4 i przygotować jawny scenariusz Artemis
do pełnego create → traffic → remove. Nie uruchomiono pełnego E2E na Artemis.
Walidacja Compose i `git diff --check` przeszły. Zmiana bez commita/pusha.

### A4 — zakres bieżącej poprawki

Jedyny właściciel projekcji, RuntimeOwnershipManifestFactory, zachowuje w `rabbit`
wyłącznie istniejące typy zasobów Rabbit. WORK_RESOURCE pozostaje w rozwiązanej
topologii i zwykłym remove; jego pominięcie w manifeście jest ostrzegane w logu.
Jawny zakres projekcji opisuje kanoniczny kontrakt REST i rekord odpowiedzialności.
Bez nowych pól publicznego API, rejestru, akcji orphan cleanupu ani zmiany ACK.
Odbiór: regresja odtwarzająca blokadę fabryki, istniejące testy projekcji Rabbit
i postconditions, a następnie jawny scenariusz Artemis przez ingress.

### A4 — wynik wykonania, 2026-09-15

Zmiana produkcyjna mieści się w RuntimeOwnershipManifestFactory (RESP-RUNTIME-CLEANUP):
WORK_RESOURCE nie blokuje projekcji Rabbit, pominięty zakres daje jawne ostrzeżenie.
Kontrola plane i błędy mapowania właściciela nadal przerywają projekcję. Rabbit
CONTROL, compute identity i dotychczasowa projekcja Rabbit WORK pozostają zachowane.
Kontrakt JSON bez nowych pól; zakres istniejącego `rabbit` doprecyzowano w REST.

- Regresja przed poprawką: RuntimeOwnershipManifestFactoryTest odtwarza
  `Unsupported resource in current public manifest: WORK_RESOURCE`.
- Po poprawce: 43/43 testy, zero błędów/pominięć: fabryka manifestu,
  ContainerLifecycleManager, weryfikacja nieobecności, reconciliation i assessment.
  `./mvnw -B -ntp -pl orchestrator-service -am clean test
  -Dtest=RuntimeOwnershipManifestFactoryTest,ContainerLifecycleManagerTest,RuntimeRemovalPostconditionVerifierTest,RuntimeReconciliationServiceTest,RuntimeAssessmentServiceTest
  -Dsurefire.failIfNoSpecifiedTests=false`; log `/tmp/artemis-a4-focused.log`.
- Wdrożenie przez `./build-hive.sh --service orchestrator --quick`
  (testy uruchomiono osobno); log `/tmp/artemis-a4-deploy.log`.
- Scenario Manager po reload zaakceptował `artemis-rest` jako `defunct=false`.
  Wszystkie WORK input/output zgłaszają ARTEMIS i adresy rozwiązanego właściciela.
- Swarm `artemis-a4-smoke-20260915`, runId `f2ac9948-643d-4e42-ac95-e4cf38c86ab8`:
  create `97561dbb-1cee-45a1-9fd6-b9f91b17708e` i start
  `e40edc68-fc9a-47f1-876f-2490abece0c2` zakończyły się SUCCEEDED.
  Przed startem READY/STOPPED/HEALTHY; po starcie RUNNING/HEALTHY.
- Publiczny debug tap na wyjściu processora: 5 różnych messageId, HTTP 200,
  `default generator response`, hopy generator/moderator/processor. Tap zamknięto.
  Dowód: `/tmp/artemis-a4-capture.json`. Nie jest to pomiar dokładnego throughputu.
- Zwykły remove z działającego swarma `5b8747f0-918b-4a28-b5e0-bf3be2ef4fa8`:
  SUCCEEDED, 6 WORK_RESOURCE (3 kolejki i 3 adresy Artemis) potwierdzonych jako
  nieobecne przez Orchestrator, 4 workery, Controller, 5 kolejek CONTROL, binding,
  katalog runtime i wpis rejestru usunięte. remainingResources/errors puste; lista
  swarmów pusta. Dowód: `/tmp/artemis-a4-remove-operation.json`.

Wszystkie wywołania runtime przez `http://localhost:8088` (również auth). Nie
uruchamiano całego normalnego E2E po A4; jest zestawem Rabbit, zielonym według
użytkownika przed przełączeniem. Native orphan cleanup, kompletna diagnostyka
i opóźnienie 3DS pozostają odłożone. Historyczna nieudana próba przełączenia
pozostawiła pliki przygotowane przez Scenario Managera; nie uruchamiano jej cleanupu.

Dowody granicy do osobnego review: repo-wide wyszukiwanie RuntimeOwnershipManifestFactory,
new RuntimeRabbitManifest, Unsupported resource in current public manifest i workQueues;
jedna fabryka produkcyjna. ContainerLifecycleManager konsumuje projekcję; lifecycle
remove idzie przez Controller → WorkPlaneResources i RuntimeRemovalPostconditionVerifier,
a RuntimeRabbitResourcePlanner/RuntimeAssessmentService konsumują wyłącznie zakres Rabbit.
Bez zmiany parserów, nazw, ACK, wykonania workerów i bez dodatkowej biblioteki.
Implementacja i dowody przekazane do osobnego review, bez pętli self-review.

Końcowe kontrole granic: 3 testy RepositoryImportBoundaryTest oraz 1 istniejący
ArchitectureTest Orchestratora przeszły, zero błędów/pominięć; razem 47 testów
weryfikacji A4. Log `/tmp/artemis-a4-boundaries.log`. Maven Enforcer i
`git diff --check` bez błędów. Zmiana bez commita/pusha.

# Rabbit SSOT i izolacja WorkPlane — plan domknięcia

Status: plan zaakceptowany do wykonania przez użytkownika 2026-09-14.
Transfer technologii Rabbit jest zaimplementowany. Opisana niżej końcowa izolacja pozostaje
do wykonania. Ten plan zastępuje wcześniejszą kolejność „izolacja + Artemis w jednym PR”.

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

Baseline: 14 testów ścieżki Controllera i 113 testów SDK/Rabbit/importów z review 2026-09-14;
normalny E2E 39 scenariuszy z 2026-09-11 poprzedza późniejsze zmiany. To wyniki historyczne,
nie odbiór R1–R6. Ta aktualizacja zmienia dokumentację, bez nowych testów runtime/deploymentu.

Plan pass: wymagane efekty obejmują wszystkich konsumentów i usunięcie alternatyw; styl/SRP:
jeden typ i odpowiedzialność, bez powiększania runtime; zwięzłość: istniejące moduły i mały fake;
security: obecne uprawnienia/scope cleanup bez nowego hardeningu; biblioteki: brak nowego klienta;
utrzymanie: jednokierunkowe zależności i jawne oddzielenie obecnego refaktoru od następnej funkcji.
Weryfikacja dokumentacji nie oznacza wykonania osobnego review kodu ani zamknięcia refaktoru.

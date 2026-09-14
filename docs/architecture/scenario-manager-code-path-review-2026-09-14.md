# Scenario Manager — przegląd ścieżek kodu, 2026-09-14

Zakres: bieżące źródła przy HEAD `4062be40`, niezależnie od granic commita. Scenario Manager
nie miał lokalnych zmian przed tym przeglądem. Istniejące zmiany w Orchestratorze, Rabbit i innych
modułach pozostawiono bez zmian. Prześledzono wejścia HTTP, wywoływane usługi, parsery i efekty
filesystem oraz konieczne kontrakty konsumentów. To diagnoza, nie zgoda na zmianę zachowania.

**Werdykt:** podziału funkcjonalnego Scenario Managera nie można uznać za zamknięty.
Delegacja konfiguracji Work i formatu request templates jest rzeczywista. Pozostały aktywne
duplikaty odpowiedzialności oraz odrębne błędy publikacji, walidacji i projekcji katalogu.
Ustalenia dotyczą aktualnych ścieżek; nie przypisują winy ostatniemu commitowi ani ekstrakcji Rabbit.

## Uzgodniony dalszy zakres

Decyzja po review: zachować ustalenia do osobnej realizacji i kontynuować przegląd następnego serwisu.
Żaden punkt S1–S10 nie wymaga zmiany modułu Rabbit; delegacja konfiguracji Work/Rabbit jest prawidłowa
w prześledzonej ścieżce. Ten raport nie rozszerza planu Rabbit.

| Nurt | Punkty i kolejność |
|---|---|
| Naprawy poprawności | Najpierw S1, następnie wspólnie S2/S3; dalej S9, brakujące pole odpowiedzi S5 i fingerprint S10. |
| Refaktor SM z zachowaniem semantyki | S6: jeden eksport ZIP; S7: API layoutu bundla; S5: projekcja metadanych od właścicieli kontraktów. |
| Refaktory wspólne | S4 → F06 worker-auth; S8 → F07 kontrakty usług; część S7 dotycząca mountu obejmuje Orchestrator. |

Naprawa błędnej odpowiedzi S5 i wydzielenie właściciela jej metadanych są osobnymi krokami.
Zapisanie tego podziału nie oznacza rozpoczęcia implementacji ani zamknięcia ustaleń.

## Ustalenia

### S1 — P1, poprawność: CREATE może usunąć inny istniejący bundle

`POST /scenarios/bundles` → `ScenarioBundlePublicationService.create:41–54` →
`ScenarioBundleOrganizationService.defaultUploadDirectory` → `writeBundle:199–205`.

CREATE sprawdza obecność **scenario ID**, ale nie zajętość docelowego katalogu. Wspólny z REPLACE
`writeBundle` czyści istniejący target. Bundle `bundles/new-id/scenario.yaml` może zgodnie z modelem
katalogu zawierać `id: old-id`. Publikacja ZIP z `id: new-id` usuwa wtedy zawartość pierwszego bundla,
usuwa `old-id` z rejestru i odpowiada `201 Created`.

Próba przez endpoint potwierdziła utratę `old.txt` i poprzedniego scenariusza. Kontrakt publikacji
w SCENARIO_MANAGER_BUNDLE_REST jawnie mówi, że CREATE nigdy nie zmienia istniejącego bundla.
Naprawa: właściciel publikacji musi rozróżniać warunki CREATE i REPLACE przed pierwszym efektem;
zajęty target CREATE oznacza konflikt. To naprawa zachowania, nie samo przeniesienie helpera.

Źródło: `scenario-manager-service/src/main/java/io/pockethive/scenarios/ScenarioBundlePublicationService.java:48`.

### S2 — P1, poprawność: validate-existing nie waliduje tego samego wejścia co publikacja/runtime

`POST /validation/scenario-bundles/existing` → `ScenarioBundlePublicationService.validateExisting:95–118`
podaje do walidatora `candidate.scenario()` z katalogu. `ScenarioBundleValidator.validateWithContext:132–145`
odczytuje i wybiera deskryptor z plików **tylko gdy przekazany Scenario jest null**. Digest w `resultOf`
jest tymczasem obliczany z aktualnych plików. Runtime i walidacja ZIP podają null i czytają deskryptor.

Dwie próby potwierdziły różnicę:

- Po zmianie pliku między reloadami z protokołu `2.0.0` na `99.0.0`, validate-existing nadal zwraca
  `ok=true`, dawną nazwę i protokół, ale nowy digest. Runtime zwraca błąd walidacji.
- Zapis `example/scenario.yaml` przez oficjalny endpoint tworzenia pliku workspace wykonuje reload.
  Mimo aktualnego katalogu validate-existing zewnętrznego bundla zwraca `ok=true`; runtime odrzuca
  wiele deskryptorów. Problem nie ogranicza się więc do braku odświeżenia po ręcznej edycji.

Kontrakt dowodu walidacji wymaga identyfikacji faktycznie zwalidowanych bajtów. Naprawa: jeden
workflow odczytu wejścia do walidacji, obejmujący wybór deskryptora; katalog dostarcza lokalizację
i konflikty katalogowe. Nie może zastępować deskryptora w dowodzie dla aktualnego digestu.
Zakres współbieżnego zapisu wymaga osobnego ustalenia; te próby były sekwencyjne.

Źródło: `scenario-manager-service/src/main/java/io/pockethive/scenarios/ScenarioBundlePublicationService.java:111`.

### S3 — P1, poprawność/SSOT wyniku: katalog ma węższą definicję runnability

`reload` → `ScenarioService.recordForLoaded:371–376` → `ScenarioBundleValidator.defunctReason:374–405`
sprawdza obecność ID/template/obrazów i capability manifests. Nie korzysta z pełnego wyniku
walidacji, zawierającego m.in. kontrolę protocolVersion, konfiguracji Work i plików bundla.
Ten częściowy wynik staje się `defunct` w `/api/templates` i filtrem AvailableScenarioRegistry.

Próba: bundle z protokołem `99.0.0`, istniejącym manifestem kontrolera i pustą listą bees po reloadzie
ma `defunct=false`. Zarówno validate-existing, jak i runtime odrzucają go. To narusza udokumentowane
znaczenie `defunct` i instrukcję dla klientów, by właśnie katalog traktowali jako SSOT runnability.
Runtime zachowuje właściwy końcowy warunek odrzucenia przed czyszczeniem workspace.

Naprawa wymaga jednej definicji wyniku walidacji i projekcji katalogowej z tego wyniku.
Nie dopisywać kolejnych wybranych kontroli do `defunctReason`. Samo przeniesienie klas nie usunie
różnicy; uzgodnić tę korektę zachowania osobno od ekstrakcji. Nie utożsamiać dozwolonych ostrzeżeń
AUTHORING o nierozwiązanych wyrażeniach z błędami, które blokują bundle.

Źródło: `scenario-manager-service/src/main/java/io/pockethive/scenarios/ScenarioService.java:371`.

### S4 — P1 / CRITICAL SSOT: reguły profili worker-auth mają dwie implementacje

Walidacja bundla → `ScenarioBundleValidator.validateAuthContracts` → `readAuthProfiles` →
`validateAuthProfileStorage:1719–1790`. W workerze `AuthRuntime.fromFile` rozwiązuje profil i wywołuje
`validateProfile:482–500`. Obie ścieżki samodzielnie rozstrzygają, które typy wymagają odświeżania,
obowiązek REDIS dla tych typów i obowiązek NONE dla pozostałych.

Wspólne AuthType/AuthStorageMode/AuthTokenKeys nie usuwają duplikatu reguł. Rozwiązanie wyrażeń
w runtime i sprawdzanie istnienia profilu w bundlu to odrębne, uzasadnione role; wymienione reguły
storage są tą samą odpowiedzialnością. To potwierdzenie otwartego F06, nie nowa lista wymagań.

Właściciel docelowy: parser/walidator profilu worker-auth, używany przez authoring i runtime.
Scenario zachowuje kontekst pliku/referencji i prezentację findings. Zachować istniejące reguły
refresh/token/claim; nie przenosić do Scenario implementacji runtime z SDK.
Kontrakty: `RESP-AUTH-VALUES`, `RESP-WORK-AUTH-RUNTIME`, `RESP-SCENARIO-VALIDATE`.
Nagłówki opisują obecne moduły, ale nie uzasadniają dwóch aktywnych implementacji tej samej reguły.

Źródła: `scenario-manager-service/src/main/java/io/pockethive/scenarios/validation/ScenarioBundleValidator.java:1759`,
`common/worker-sdk/src/main/java/io/pockethive/worker/sdk/auth/AuthRuntime.java:482`.

### S5 — P1 / CRITICAL SSOT: REST sam definiuje kontrakt authoringu; definicje już się rozjechały

`GET /api/authoring-contract` → `CapabilityCatalogueController.buildAuthoringContract:139–199`
sam wpisuje wymagane pola deskryptora, pola template HTTP, wersję variables i listy enumów.
Nagłówek tej klasy wprost zabrania jej definiowania kontraktów authoringu.

Próba potwierdziła, że `scenario.requiredTopLevelFields` pomija `protocolVersion`, choć walidator
odrzuca bundle bez tego pola. Wymagane pola HTTP są również ręcznie przepisane względem
RequestTemplateParser; scopes/types względem typów variables. To nie jest wyłącznie duży kontroler,
lecz niezależna definicja danych opisujących reguły innych właścicieli.

Naprawa: kontrakt authoringu powinien być projekcją metadanych właścicieli, a HTTP tylko ją zwracać.
Przeniesienie obecnej mapy do nowej klasy pozostawi duplikat. Uzupełnienie brakującego pola jest
korektą odpowiedzi publicznej i powinno być jawne w osobnym zakresie poprawności.

Źródło: `scenario-manager-service/src/main/java/io/pockethive/capabilities/api/CapabilityCatalogueController.java:160`.

### S6 — P1 / CRITICAL SSOT: eksport tego samego bundla do ZIP ma dwóch właścicieli

`GET /scenarios/{id}/bundle` → `ScenarioController.downloadBundle:663–702` sam wykonuje Files.walk,
buduje ścieżki wpisów ZIP i kopiuje bajty. `GET /scenarios/bundles/download` deleguje te same operacje
do `ScenarioBundleWorkspaceService.download:40–63`. Pierwsza ścieżka nie używa również blokady
`synchronized(scenarios)`, którą stosuje druga.

Adresowanie przez scenarioId albo bundleKey jest odrębnym wyborem celu, nie odrębnym formatem eksportu.
Nagłówek kontrolera i reguła cienkiego REST nie odpowiadają rzeczywistym efektom tej metody.
Oba endpointy powinny korzystać z jednego eksportera po rozwiązaniu celu; zachować obecne nazwy
plików odpowiedzi i mapowanie HTTP. Nie przebudowywać publikacji ZIP przy okazji tej ekstrakcji.

Źródła: `scenario-manager-service/src/main/java/io/pockethive/scenarios/ScenarioController.java:679`,
`scenario-manager-service/src/main/java/io/pockethive/scenarios/ScenarioBundleWorkspaceService.java:40`.

### S7 — P1 / CRITICAL SSOT: wspólny layout nie zamyka budowania ścieżek

ScenarioBundleLayout istnieje, ale walidator sam ustala `bundle.resolve("sut")` (linia 701),
BundleSutService sam dokłada `sut/<sutId>` (99), a BundleContentService powtarza `templates` (131, 163).
To budowanie tego samego układu, który jest reklamowany przez ScenarioBundleLayout i authoring API.
Właściciel layoutu powinien udostępniać rozwiązane ścieżki/katalogi, a nie tylko część nazw plików.

Analogicznie `/app/scenario` jest literalem w walidatorze (83), miejscu montowania w Orchestratorze
(`SwarmController:247`) i poszukiwaniu profili przez AuthRuntime (411). Zmiana miejsca montowania
może rozjechać runtime z analizą ścieżek authoringu. `/app/scenarios-runtime` z RuntimeFilesystemContract
jest innym poziomem drzewa: nie należy mechanicznie podmieniać nim `/app/scenario`.

Naprawa: jedno API layoutu bundla oraz jedna projekcja docelowego mountu dla konsumenta.
Zachować dzisiejsze ścieżki i zasady doboru plików. Zmiana istniejącej polityki wyszukiwania auth
to osobne zachowanie. Brak kompletnego rekordu odpowiedzialności tego layoutu należy zamknąć
przed implementacją; raport nie tworzy zastępczego katalogu właścicieli.

Źródła: `common/scenario-validation-contracts/src/main/java/io/pockethive/scenarios/ScenarioBundleLayout.java:5`,
`scenario-manager-service/src/main/java/io/pockethive/scenarios/ScenarioBundleSutService.java:97`.

### S8 — P1 / CRITICAL SSOT: kontrakty runtime i variables są skopiowane w konsumencie

Scenario Manager publikuje samodzielne `RuntimeRequest`, `ScenarioRuntimeResponse` oraz
`VariablesResolveResponse`. Orchestrator `ScenarioManagerClient:277–287` utrzymuje własne rekordy
tych samych komunikatów, a metody prepareScenarioRuntime/resolveScenarioVariables używają lokalnych kopii.

To nie są lokalne projekcje o innych polach/znaczeniu. Wspólna definicja producenta albo generowanie
konsumenta powinny zamknąć ten kontrakt. `ScenarioTemplateResponse` będący węższym modelem odczytu
trzeba oceniać osobno; nie każda projekcja klienta jest duplikatem pełnego kontraktu.
Potwierdzenie istniejącego F07. Ekstrakcja ma zachować dokładny obecny wire format.

Źródło: `orchestrator-service/src/main/java/io/pockethive/orchestrator/infra/scenario/ScenarioManagerClient.java:277`.

### S9 — P2, poprawność: filtr katalogu wraca z bundleKey do niejednoznacznego scenarioId

`CapabilityCatalogueController.isRunnableTemplate:246–257` usuwa każdy wpis bez scenarioId dla
uwierzytelnionego użytkownika, a pozostałe sprawdza przez `findScenarioAccess(id)` — pierwszy wpis
o tym ID. `ScenarioController.canReadBundleSummary:828–838` również wybiera ID, jeśli je posiada.

Dwie próby przez API i rzeczywiste grant checks potwierdziły:

- malformed bundle znika z `/api/templates` nawet dla użytkownika z globalnym ALL, choć istnieje
  i jest dostępny w `/scenarios/bundles/workspaces`; przeczy to kontraktowi pełnego katalogu;
- dwa bundle o tym samym ID w dwóch folderach są oba widoczne użytkownikowi uprawnionemu tylko
  do folderu pierwszego wpisu. Filtr obu pozycji używa lokalizacji pierwszego bundla.

Naprawa: projekcja katalogu ma stosować uprawnienia do konkretnego bundleKey/lokalizacji.
Nie zmieniać semantyki grantów ani dodawać nowych zabezpieczeń. To obsługa zwykłych stanów
malformed/duplicate, które system już oficjalnie wspiera.

Źródło: `scenario-manager-service/src/main/java/io/pockethive/capabilities/api/CapabilityCatalogueController.java:246`.

### S10 — P2, poprawność: fingerprint nie identyfikuje zwracanej projekcji

`CapabilityCatalogueController.fingerprint:218–243` hashuje z templateCatalog tylko bundleKey,
ID, defunct i defunctReason. Odpowiedź zawiera też m.in. nazwę, opis i obrazy.

Próba zmiany nazwy przez `PUT /scenarios/demo/raw` potwierdziła nową nazwę w authoring-contract
przy identycznym fingerprint. Klient postępujący według umowy odświeżania cache nie wykryje zmiany
treści, którą wcześniej otrzymał. Naprawa: fingerprint obliczany z jednej kompletnej, deterministycznej
projekcji kontraktu. Samo przeniesienie hashowania poza REST nie naprawi pominiętych danych.

Źródło: `scenario-manager-service/src/main/java/io/pockethive/capabilities/api/CapabilityCatalogueController.java:223`.

## Sprawdzone granice i właściciele

| Ścieżka / kontrakt | Faktyczne delegowanie i efekty | Ocena |
|---|---|---|
| `RESP-SCENARIO-VALIDATE`, `RESP-WORK-IO-CONFIG` | ScenarioWorkConfigurationComposition → CurrentWorkConfigurationProviders → wstrzyknięty WorkConfigurationParser; WorkConfigurationFindings tylko projektuje AUTHORING problems/deferred paths. Lokalne kontrole capability wyłączają całe inputs/outputs (`isSharedWorkConfigurationField`). | Brak ponownego składania parserów adapterów w Scenario. Kontrole konfiguracji mają właściciela; S3 dotyczy wykorzystania wyniku. |
| `RESP-REQUEST-TEMPLATE-PARSE` | RequestTemplateFindings → RequestTemplateParser; TemplateLoader w request-template-files służy runtime odczytowi plików. Scenario sprawdza widoczność i referencje bundla. | Uzasadnione odrębne role, bez powielenia parsera formatu. Metadane HTTP w S5 pozostają odrębnym problemem. |
| `RESP-SCENARIO-VALIDATE`, bundle REST/diagnostics | PublicationService waliduje ZIP przed kopiowaniem; RuntimeMaterializer waliduje przed czyszczeniem runtime. ScenarioService jest writerem katalogu; AvailableScenarioRegistry to delegująca projekcja. | S1–S3; wrapper rejestru sam w sobie nie jest drugim SSOT. |
| SCENARIO_VARIABLES i bundle-local SUT REST | VariablesService deleguje parse/validate do ScenarioBundleValidator, rozwiązuje wybrany profil/SUT i zapisuje variables.yaml. BundleSutService deleguje kontrakt sut.yaml do tego walidatora. | Nie stwierdzono drugiej implementacji reguł tych dokumentów w badanej ścieżce. Layout S7 i współbieżność poniżej pozostają otwarte. |
| Globalne SUT / network profiles | Osobne kontrolery delegują do SutEnvironmentService/NetworkProfileService; te czytają i zapisują skonfigurowane rejestry. | Globalny rejestr i bundle-local SUT są odrębnymi źródłami, nie automatycznie dwoma writerami tego samego pliku. Przegląd źródeł, bez nowych testów tych endpointów. |
| Bundle REST / ENGINEERING_RULES | Workspace, Content, Organization, Publication i RuntimeMaterializer wykonują efekty filesystem. Kontroler nadal eksportuje ZIP; authoring controller definiuje metadane. | S5–S7. Nie wystarczy nazwać klas Service. |
| Product auth / worker auth | Filtr rozwiązuje użytkownika przez AuthServiceClient i czyści kontekst w finally; granty sprawdza PocketHiveGrantChecks. Worker auth jest inną domeną. | S9 dotyczy wybranego zasobu, S4 duplikatu zasad worker-auth. Nie scalać obu domen. |

Wyszukiwania `rg` obejmowały całe repo dla alternatywnych implementacji: `new ZipOutputStream`,
`validateAuthProfileStorage|validateProfile`, `requiredTopLevelFields`, deklaracji rekordów runtime/variables,
`/app/scenario`, `resolve("sut")`, `resolve("templates")` oraz konsumentów WorkConfigurationParser,
RequestTemplateParser i ScenarioBundleLayout. Następnie odczytano wskazane implementacje i ich wywołania.
Nie używano nazw symboli ani zielonego import-testu jako dowodu braku duplikacji.

## Weryfikacja i granice wniosków

Wykonano **67 testów: 59 istniejących oraz 8 tymczasowych prób odtwarzających defekty**.
Wszystkie przeszły. Zielone próby oznaczają potwierdzenie obecnych błędów, nie poprawność systemu.

- 30: ScenarioServiceTest (12), Publication/Content/Variables/RuntimeMaterializer/BundleSutServiceTest
  (po 2) oraz ScenarioManagerReviewProbeTest (8).
- 37: WorkConfigurationFindingsTest (4), RequestTemplateFindingsTest (2),
  InputSettingsValidationComponentTest (10), RedisConfigurationValidationComponentTest (21).

Próby używają oficjalnych tras przez MockMvc z rzeczywistymi usługami i parserami, w katalogach
tymczasowych. Dla prób uprawnień ustawiono principal i użyto rzeczywistych grant checks; nie testowano
ponownie HTTP do auth-service. Brak testowania przez bezpośrednie porty serwisów.

Polecenia: Maven `-pl scenario-manager-service -am test`, z powyższymi listami `-Dtest` oraz
`-Dsurefire.failIfNoSpecifiedTests=false`. Artefakty lokalne:

- `/tmp/scenario-manager-review-probes.log`,
- `/tmp/scenario-manager-review-validation.log`,
- `/tmp/scenario-manager-review/ScenarioManagerReviewProbeTest.java`.

Pierwszy przebieg prób ujawnił dwa błędy samej próby (zła trasa tworzenia pliku i nazwa pola odpowiedzi);
poprawiono je przed końcowym przebiegiem. Źródło prób i ich pojedynczy skompilowany plik usunięto
z drzewa testów po weryfikacji. W repo pozostał wyłącznie ten nowy raport; kod produkcyjny niezmieniony.

Nie wykonywano pełnego E2E, redeploy, HiveMap ani testów obciążenia/współbieżności. Wspólny monitor
ScenarioService nie obejmuje m.in. `VariablesService.write` (osobny writeLock) oraz zapisów template/schema
w ContentService; reload publikuje mapę i katalog w oddzielnych krokach. Nie certyfikuję więc atomowości
odczytu/walidacji/kopii bundla ani snapshotu katalogu. Wymaga to osobnego przeglądu kontraktu współbieżności,
bez dokładania modelu transakcyjnego do samej ekstrakcji. Nie oceniano odporności na awarie filesystem
podczas replace/copy. Globalne narzędzia HiveMind nie były dostępne; nie uruchamiano lokalnego fallbacku.

Sześć przejść review: plan — zachować oddzielenie ekstrakcji i poprawności; styl — nagłówki REST nie
odpowiadają S5/S6, mieszany walidator nadal obejmuje obce reguły S4; zwięzłość — usuwać duplikaty zamiast
dokładać fasady; security — S9 w istniejącym modelu grantów, bez nowej polityki; biblioteki — prawidłowe
delegowanie Work/request-template, otwarte S4/S7/S8; utrzymanie — S1/S2/S3/S10 podważają jednolity wynik.

Rekomendacja: nie zamykać przeglądu granic. S1 i S2 są pierwszymi kandydatami do osobnej naprawy
poprawności; S3/S9/S10 oraz korekta publicznych metadanych S5 należą do tego samego oddzielnego nurtu.
Ekstrakcje S4/S6/S7/S8 i właściciela metadanych S5 muszą zachować zachowanie, usuwać stare wywołania
i być powiązane z istniejącym planem F06/F07 lub uzupełnionym zakresem bundle API. Bez automatycznych
napraw, zmian semantyki runtime, migracji ani commita w tym review.

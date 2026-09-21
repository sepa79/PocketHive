# Artemis i delayed publish dla 3DS — osobny PR po izolacji Rabbit

Status: odłożony etap funkcjonalny, doprecyzowanie 2026-09-14; implementacja nie rozpoczęta.
Najpierw zamykamy [Rabbit SSOT i izolację WorkPlane](../inProgress/work-plane-module-boundaries.md)
na istniejącym Rabbit oraz stanowym adapterze testowym. Ten dokument nie jest planem refaktoru Rabbit.

## Warunek rozpoczęcia

Granica WorkPlane obejmuje konfigurację i adresy, zasoby, transport, obserwacje i cleanup,
a jej konsumenci są sprawdzeni przez Rabbit i fake. CONTROL Rabbit działa niezależnie od WORK.
Nie powtarzać tutaj ekstrakcji ani nie traktować innych refaktorów usług jako warunku Artemis.

## Zakres tego PR

1. Zaimplementować adapter Artemis przez przygotowane kontrakty. Jeden właściciel posiada
   jego konfigurację/defaults, eksport ENV, adresy, create/read/delete zasobów, transport
   i obserwacje. Nie kopiować Rabbit, nie rekonstruować adresów w usługach.
2. Dodać jawną selekcję Artemis i jego providerów do wspólnej konfiguracji/authoring.
   Scenario Manager korzysta z obecnego parsera przez porty; CONTROL pozostaje Rabbit.
3. Uzgodnić kontrakt delayed publish dla rzeczywistego przepływu 3DS i dodać zamiar opóźnienia
   do istniejącej pojedynczej ścieżki publikacji wyniku. Szczegóły brokera należą do Artemis.
4. Przygotować broker w wybranym środowisku testowym i sprawdzić rzeczywistą wymianę wiadomości,
   czas udostępnienia oraz utworzenie/obserwację/usunięcie zasobów przez oficjalne interfejsy.

## Kontrakt opóźnienia do uzgodnienia

Wskazać producenta, odbiorcę i źródło wartości opóźnienia. Wybrać opóźnienie albo termin,
jednostkę/zegar oraz jawny wynik dla adaptera bez tej możliwości. Nie projektować tego API
w refaktorze Rabbit na podstawie przypuszczeń. Klienta i mechanizm Artemis zweryfikować
w aktualnej oficjalnej dokumentacji podczas projektowania implementacji.

## Odbiór i granice

- Rzeczywisty przepływ 3DS zachowuje uzgodniony czas dostarczenia i poprawnie sprząta zasoby.
- Wspólna walidacja poprzedza skutki, ustawienia wykonania odpowiadają ustawieniom scenariusza.
- CONTROL i dotychczasowy Rabbit zachowują działanie, w tym ACK po przyjęciu/przekazaniu do
  wykonania, brak requeue po błędzie parsera/executora oraz nieaktywne publisher confirms.
- Bez timerów i nagłówków brokera budowanych niezależnie w workerach, fallbacku na Rabbit,
  migracji/compatibility, dodatkowego programu PoC, HA ani nowych polityk ACK/retry/DLQ.
- Orchestrator correctness/reset, Scenario Manager S1–S10, Redis, Docker, journal, auth oraz
  szerszy lifecycle są osobnymi PR-ami. Archiwalny plan Artemis nie przywraca ich do tego zakresu.

## Odłożony dodatek R4 (decyzja użytkownika 2026-09-14)

Przed wdrożeniem innego produkcyjnego WORK uzgodnić manifest natywnych zasobów i ich
osierocony cleanup. Użytkownik odłożył propozycję `manifest.workResources` (wyłącznie
`WORK_RESOURCE`/`WORK`), akcji `DELETE_WORK_RESOURCE` i jawnej flagi
`includeWorkResources` (domyślnie false). Nie są wdrożone ani zatwierdzone.
`includeRabbit` zachowuje dotychczasowy zakres. Nie podszywać natywnych zasobów pod Rabbit.
Osobno zatwierdzony typ lifecycle `WORK_RESOURCE` z adresem właściciela pozostaje w kontrakcie.
Pełna próba Orchestratora z natywnym manifestem pozostaje zależna od tego dodatku.

To luka dla zasobów innego adaptera; istniejący Rabbit orphan cleanup nie jest brakującą
implementacją tego PR. Osobno odłożono poprawę kompletności listy zasobów w Runtime inspector
i diagnostyce MCP: [zakres i ograniczenia](../inProgress/runtime-debug-mcp-cleanup-spec.md#deferred-diagnostic-completeness--user-decision-2026-09-14).
Natywny manifest nie naprawia tej diagnostyki ani rejestru/resetu Orchestratora.

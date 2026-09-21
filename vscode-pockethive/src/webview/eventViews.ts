/**
 * Responsibility: Render compact Buzz and Journal event lists, filters, and exact navigation commands.
 * Must not: Call MCP services, infer event semantics, or mutate owner evidence.
 * Contract: vscode-pockethive/README.md and docs/mcp/README.md.
 */

interface PocketHiveEventViewPort {
  readonly filterEvents: <T extends Record<string, unknown>>(
    events: readonly T[], criteria: WebviewEventFilterCriteria,
  ) => T[];
  readonly timeWindows: Readonly<Record<'ALL' | 'FIFTEEN_MINUTES' | 'ONE_HOUR', string>>;
  readonly button: (
    label: string, action: () => void, className: string, enabledWhileBusy?: boolean,
  ) => HTMLButtonElement;
  readonly dataRows: (rows: string[][]) => HTMLElement;
  readonly displayValue: (value: unknown) => string;
  readonly el: (tag: string, className?: string, children?: Array<Node | undefined>) => HTMLElement;
  readonly emptyState: (message: string) => HTMLElement;
  readonly errorFrom: (value: unknown) => string | undefined;
  readonly errorState: (message: string) => HTMLElement;
  readonly eventDisclosureKey: (event: Model, context: 'Buzz' | 'Journal') => string;
  readonly eventIcon: (kind: string) => string;
  readonly icon: (name: string, extraClass?: string) => HTMLElement;
  readonly iconButton: (
    label: string, iconName: string, action: () => void, className: string, enabledWhileBusy?: boolean,
  ) => HTMLButtonElement;
  readonly iconSummary: (label: string, iconName: string) => HTMLElement;
  readonly objectValue: (value: unknown) => Model | undefined;
  readonly ownerDataError: (value: unknown, expected: string) => HTMLElement;
  readonly refreshStableDetails: (key: string, className: string, openByDefault?: boolean) => HTMLDetailsElement;
  readonly searchInput: (
    label: string, id: string, value: string, placeholder: string, visuallyHiddenLabel?: boolean,
  ) => { wrapper: HTMLElement; control: HTMLInputElement };
  readonly searchableChoice: (
    label: string, id: string, choices: readonly string[], value: string, placeholder: string,
  ) => { wrapper: HTMLElement; control: HTMLInputElement };
  readonly select: (
    label: string, id: string, options: string[][], value: string,
  ) => { wrapper: HTMLElement; control: HTMLSelectElement };
  readonly send: (message: unknown, whileBusy?: boolean) => void;
  readonly sendExactChoice: (
    control: HTMLInputElement, choices: readonly string[], subject: string,
    onChoice: (choice: string) => void,
  ) => void;
  readonly statusPill: (status: string) => HTMLElement;
  readonly stringField: (value: Model, field: string) => string | undefined;
  readonly text: (tag: string, value: string, className?: string, role?: string) => HTMLElement;
  readonly titled: (tag: string, value: string, className?: string) => HTMLElement;
  readonly topLevelRecords: (value: unknown) => Model[] | undefined;
}

interface PocketHiveEventViewsApi {
  renderBuzz(model: Model, view: PocketHiveEventViewPort): HTMLElement;
  renderJournal(model: Model, view: PocketHiveEventViewPort): HTMLElement;
}

(() => {
let applyEventFilters: PocketHiveEventViewPort['filterEvents'];
let EVENT_TIME_WINDOWS: PocketHiveEventViewPort['timeWindows'];
let eventCriteria: Record<'Buzz' | 'Journal', WebviewEventFilterCriteria> | undefined;
let model: Model;
let button: PocketHiveEventViewPort['button'];
let dataRows: PocketHiveEventViewPort['dataRows'];
let displayValue: PocketHiveEventViewPort['displayValue'];
let el: PocketHiveEventViewPort['el'];
let emptyState: PocketHiveEventViewPort['emptyState'];
let errorFrom: PocketHiveEventViewPort['errorFrom'];
let errorState: PocketHiveEventViewPort['errorState'];
let eventDisclosureKey: PocketHiveEventViewPort['eventDisclosureKey'];
let eventIcon: PocketHiveEventViewPort['eventIcon'];
let icon: PocketHiveEventViewPort['icon'];
let iconButton: PocketHiveEventViewPort['iconButton'];
let iconSummary: PocketHiveEventViewPort['iconSummary'];
let objectValue: PocketHiveEventViewPort['objectValue'];
let ownerDataError: PocketHiveEventViewPort['ownerDataError'];
let refreshStableDetails: PocketHiveEventViewPort['refreshStableDetails'];
let searchInput: PocketHiveEventViewPort['searchInput'];
let searchableChoice: PocketHiveEventViewPort['searchableChoice'];
let select: PocketHiveEventViewPort['select'];
let send: PocketHiveEventViewPort['send'];
let sendExactChoice: PocketHiveEventViewPort['sendExactChoice'];
let statusPill: PocketHiveEventViewPort['statusPill'];
let stringField: PocketHiveEventViewPort['stringField'];
let text: PocketHiveEventViewPort['text'];
let titled: PocketHiveEventViewPort['titled'];
let topLevelRecords: PocketHiveEventViewPort['topLevelRecords'];

function bind(nextModel: Model, view: PocketHiveEventViewPort): void {
  model = nextModel;
  applyEventFilters = view.filterEvents;
  EVENT_TIME_WINDOWS = view.timeWindows;
  eventCriteria ??= {
    Buzz: { timeWindow: EVENT_TIME_WINDOWS.ALL, kind: 'ALL', severity: 'ALL', search: '' },
    Journal: { timeWindow: EVENT_TIME_WINDOWS.ALL, kind: 'ALL', severity: 'ALL', search: '' },
  };
  ({
    button, dataRows, displayValue, el, emptyState, errorFrom, errorState, eventDisclosureKey,
    eventIcon, icon, iconButton, iconSummary, objectValue, ownerDataError, refreshStableDetails,
    searchInput, searchableChoice, select, send, sendExactChoice, statusPill, stringField, text,
    titled, topLevelRecords,
  } = view);
}

function renderBuzz(nextModel: Model, view: PocketHiveEventViewPort): HTMLElement {
  bind(nextModel, view);
  return eventListView(model.workspaceData, 'No hive events were observed.', 'Buzz');
}

function renderJournal(nextModel: Model, view: PocketHiveEventViewPort): HTMLElement {
  bind(nextModel, view);
  return journalView();
}

function journalView(): HTMLElement {
  const result = el('div', 'journal');
  const swarms = topLevelRecords(model.workspaceData);
  if (swarms === undefined) return ownerDataError(model.workspaceData, 'swarm list');
  const swarmIds = swarms.map(item => stringField(item, 'id')).filter((id): id is string => Boolean(id));
  if (swarmIds.length === 0) {
    result.append(emptyState('No swarms are available for journal evidence.'));
    return result;
  }
  const swarm = searchableChoice(
    'Exact swarm',
    'journalSwarm',
    swarmIds,
    String(model.journalSwarmId ?? ''),
    'Search exact swarm…',
  );
  swarm.control.addEventListener('change', () => {
    sendExactChoice(swarm.control, swarmIds, 'swarm', swarmId =>
      send({ type: 'selectJournalSwarm', swarmId }));
  });
  result.append(swarm.wrapper);
  if (!model.journalSwarmId) result.append(emptyState('Select one exact swarm to load its bounded journal.'));
  else {
    if (model.journalRunId) {
      result.append(el('div', 'selection-banner', [
        el('div', '', [text('span', 'Exact run', 'eyebrow'), titled('strong', String(model.journalRunId), 'mono truncate')]),
        button('All runs', () => send({ type: 'selectJournalSwarm', swarmId: String(model.journalSwarmId) }), 'quiet compact'),
      ]));
    }
    result.append(eventListView(model.journalResult, 'No journal events were observed for this swarm.', 'Journal'));
  }
  return result;
}



function eventListView(value: unknown, emptyMessage: string, context: 'Buzz' | 'Journal'): HTMLElement {
  if (value === undefined) return emptyState(model.busy ? 'Loading current events…' : 'No event data observed yet.');
  const ownerError = errorFrom(value);
  if (ownerError) return errorState(ownerError);
  const root = objectValue(value);
  const rawItems = root?.items;
  if (!Array.isArray(rawItems)) return ownerDataError(value, 'event page');
  if (rawItems.some(item => !objectValue(item))) return ownerDataError(value, 'event record');
  const events = rawItems as Model[];
  if (events.length === 0) return emptyState(emptyMessage);
  const result = el('section', 'event-stream');
  const criteriaByContext = eventCriteria!;
  const criteria = criteriaByContext[context];
  const search = searchInput('Search events', `${context.toLowerCase()}Search`, criteria.search,
    context === 'Buzz' ? 'Search routing, type or origin' : 'Search journal');
  search.control.required = false;
  const time = select('Time', `${context.toLowerCase()}Time`, [
    [EVENT_TIME_WINDOWS.ALL, 'All captured'],
    [EVENT_TIME_WINDOWS.FIFTEEN_MINUTES, 'Last 15 minutes'],
    [EVENT_TIME_WINDOWS.ONE_HOUR, 'Last hour'],
  ], criteria.timeWindow);
  const kinds = distinctEventField(events, 'kind');
  const severities = distinctEventField(events, 'severity');
  const kind = select('Kind', `${context.toLowerCase()}Kind`, [['ALL', 'All kinds'], ...kinds.map(item => [item, item])], criteria.kind);
  const severity = select('Severity', `${context.toLowerCase()}Severity`, [['ALL', 'All severities'], ...severities.map(item => [item, item])], criteria.severity);
  const advanced = refreshStableDetails(`${context.toLowerCase()}:filters`, 'advanced-filters');
  const advancedSummary = iconSummary('Advanced filters', 'filter');
  advancedSummary.setAttribute('aria-label', 'Advanced filters');
  const advancedCount = text('span', '', 'filter-count');
  advancedSummary.append(advancedCount);
  advanced.append(advancedSummary, el('div', 'advanced-filters__panel', [
    time.wrapper,
    kind.wrapper,
    severity.wrapper,
  ]));
  const filters = el('div', 'event-search', [search.wrapper, advanced]);
  const count = text('p', '', 'result-count muted');
  const list = el('div', 'event-list');
  const apply = () => {
    const next: WebviewEventFilterCriteria = {
      search: search.control.value,
      timeWindow: time.control.value,
      kind: kind.control.value,
      severity: severity.control.value,
    };
    criteriaByContext[context] = next;
    const filtered = applyEventFilters(events, next);
    const activeFilterCount = [
      next.timeWindow !== EVENT_TIME_WINDOWS.ALL,
      next.kind !== 'ALL',
      next.severity !== 'ALL',
    ].filter(Boolean).length;
    advancedCount.textContent = activeFilterCount ? String(activeFilterCount) : '';
    advancedCount.hidden = activeFilterCount === 0;
    count.textContent = `${filtered.length} of ${events.length} captured events`;
    list.replaceChildren(...filtered.map(event => eventRow(event, context)));
    if (filtered.length === 0) list.append(emptyState('No captured events match these filters.'));
  };
  for (const control of [time.control, kind.control, severity.control]) control.addEventListener('change', apply);
  search.control.addEventListener('input', apply);
  result.append(filters, count, list);
  apply();
  return result;
}

function eventRow(event: Model, context: 'Buzz' | 'Journal'): HTMLElement {
    const kind = stringField(event, 'kind');
    const timestamp = stringField(event, 'timestamp');
    const detailId = stringField(event, 'detailId');
    if (!kind || !timestamp || !detailId) {
      return ownerDataError(event, 'event record');
    }
    const severity = stringField(event, 'severity') ?? 'UNKNOWN';
    const type = stringField(event, 'type') ?? 'untyped';
    const swarmId = stringField(event, 'swarmId');
    const details = refreshStableDetails(eventDisclosureKey(event, context), 'event-row');
    details.append(el('summary', '', [
      icon(severity.toLocaleUpperCase() === 'ERROR' ? 'error' : eventIcon(kind), 'event-row__icon'),
      el('span', 'event-row__identity', [
        titled('strong', `${kind}/${type}`, 'event-row__type truncate'),
        titled('span', swarmId ?? displayValue(event.origin), 'event-row__scope muted truncate'),
      ]),
      statusPill(severity),
      titled('time', compactTime(timestamp), 'event-row__time mono'),
      icon('chevron-right', 'disclosure-chevron'),
    ]));
    const actions = el('div', 'event-row__actions');
    actions.append(iconButton('Open technical details', 'code', () =>
      send({ type: 'openEventDetails', detailId }), 'secondary compact'));
    const runId = stringField(event, 'runId') ?? (context === 'Journal'
      ? stringField(model, 'journalRunId')
      : undefined);
    if (context === 'Journal' && swarmId && runId) {
      actions.append(iconButton('View run in Web UI', 'link-external', () =>
        send({ type: 'openWebUi', destination: 'JOURNAL_RUN', swarmId, runId }), 'secondary compact'));
    }
    details.append(el('div', 'event-row__body', [
      dataRows([
        ['Observed', timestamp],
        ['Swarm', swarmId ?? 'Not reported'],
        ['Origin', displayValue(event.origin)],
        ['Direction', displayValue(event.direction)],
        ['Routing', displayValue(event.routingKey)],
      ]),
      actions,
    ]));
    return details;
}

function distinctEventField(events: Model[], field: string): string[] {
  return [...new Set(events.map(item => stringField(item, field)).filter((item): item is string => Boolean(item)))].sort();
}

function compactTime(timestamp: string): string {
  const value = new Date(timestamp);
  if (Number.isNaN(value.getTime())) return timestamp;
  return value.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

const api: PocketHiveEventViewsApi = Object.freeze({ renderBuzz, renderJournal });
(globalThis as typeof globalThis & { PocketHiveEventViews: PocketHiveEventViewsApi }).PocketHiveEventViews = api;
})();

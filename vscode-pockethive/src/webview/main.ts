/**
 * Responsibility: Compose PocketHive companion view state into the active webview presentation.
 * Must not: Call backend services directly or own MCP authorization and owner-service contracts.
 * Contract: vscode-pockethive/README.md.
 */
declare function acquireVsCodeApi(): { postMessage(message: unknown): void };

interface WebviewEventFilterCriteria {
  readonly timeWindow: string;
  readonly kind: string;
  readonly severity: string;
  readonly search: string;
}
declare const PocketHiveEventFilters: {
  readonly TIME_WINDOWS: Readonly<Record<'ALL' | 'FIFTEEN_MINUTES' | 'ONE_HOUR', string>>;
  filterEvents<T extends Record<string, unknown>>(events: readonly T[], criteria: WebviewEventFilterCriteria): T[];
};
declare const PocketHiveDebugEvidence: PocketHiveDebugEvidenceApi;
declare const PocketHiveScenarioViews: PocketHiveScenarioViewsApi;
declare const PocketHiveHiveViews: PocketHiveHiveViewsApi;
declare const PocketHiveDebugViews: PocketHiveDebugViewsApi;
declare const PocketHiveEventViews: PocketHiveEventViewsApi;
declare const PocketHiveEnvironmentViews: PocketHiveEnvironmentViewsApi;

const vscode = acquireVsCodeApi();
const appElement = document.querySelector<HTMLElement>('#app');
const announcerElement = document.querySelector<HTMLElement>('#announcer');
if (!appElement || !announcerElement) throw new Error('PocketHive webview root missing');
const app: HTMLElement = appElement;
const announcer: HTMLElement = announcerElement;

type Model = Record<string, any>;
const TABS = ['Hive', 'Buzz', 'Journal', 'Scenarios', 'Debug'] as const;
const TAB_ICONS: Readonly<Record<(typeof TABS)[number], string>> = Object.freeze({
  Hive: 'server-environment',
  Buzz: 'broadcast',
  Journal: 'book',
  Scenarios: 'folder-library',
  Debug: 'debug',
});
let model: Model = { page: 'environments', profiles: [], activeTab: 'Hive', debugActions: [], busy: false };
const disclosureState = new Map<string, boolean>();
const scenarioViewPort: PocketHiveScenarioViewPort = Object.freeze({
  brandMark,
  button,
  dataRows,
  displayValue,
  el,
  emptyState,
  errorFrom,
  errorState,
  icon,
  iconButton,
  iconSummary,
  input,
  objectValue,
  ownerDataError,
  publicationAttemptId,
  rerender: render,
  refreshStableDetails,
  resultCard,
  searchInput,
  select,
  send,
  shortHash,
  statusPill,
  stringField,
  stringList,
  technicalDetails,
  text,
  titled,
  topLevelRecords,
});
const hiveViewPort: PocketHiveHiveViewPort = Object.freeze({
  brandMark,
  button,
  displayValue,
  el,
  emptyState,
  errorFrom,
  errorState,
  icon,
  iconButton,
  input,
  objectValue,
  ownerDataError,
  refreshStableDetails,
  rerender: render,
  searchInput,
  select,
  send,
  showError,
  statusPill,
  statusToken,
  stringField,
  stringList,
  technicalDetails,
  text,
  titled,
  topLevelRecords,
  workspaceActionIconButton,
});
const debugViewPort: PocketHiveDebugViewPort = Object.freeze({
  button,
  debugEvidence,
  el,
  icon,
  iconButton,
  identifiers,
  rerender: render,
  searchableChoice,
  send,
  sendExactChoice,
  stringField,
  text,
  titled,
  topLevelRecords,
});
const eventViewPort: PocketHiveEventViewPort = Object.freeze({
  filterEvents: PocketHiveEventFilters.filterEvents,
  timeWindows: PocketHiveEventFilters.TIME_WINDOWS,
  button,
  dataRows,
  displayValue,
  el,
  emptyState,
  errorFrom,
  errorState,
  eventDisclosureKey,
  eventIcon,
  icon,
  iconButton,
  iconSummary,
  objectValue,
  ownerDataError,
  refreshStableDetails,
  searchInput,
  searchableChoice,
  select,
  send,
  sendExactChoice,
  statusPill,
  stringField,
  text,
  titled,
  topLevelRecords,
});
const environmentViewPort: PocketHiveEnvironmentViewPort = Object.freeze({
  authenticationStatus,
  brandMark,
  button,
  el,
  endpointStatus,
  icon,
  iconButton,
  iconSummary,
  iconText,
  input,
  objectValue,
  refreshStableDetails,
  select,
  send,
  stage,
  statusPill,
  statusRow,
  stringField,
  testStatus,
  text,
  titled,
});

window.addEventListener('message', event => {
  const message: unknown = event.data;
  if (!message || typeof message !== 'object' || Array.isArray(message)) return;
  const value = message as Record<string, unknown>;
  if (value.type === 'viewModel' && value.model && typeof value.model === 'object') {
    const nextModel = value.model as Model;
    const preserveInteractionState = samePresentationContext(model, nextModel);
    if (!preserveInteractionState) {
      disclosureState.clear();
      PocketHiveEnvironmentViews.resetInteractionState();
    }
    PocketHiveHiveViews.reconcile(nextModel, hiveViewPort);
    model = nextModel;
    render(preserveInteractionState);
    return;
  }
  if (value.type === 'error' && value.error && typeof value.error === 'object') {
    const error = value.error as Record<string, unknown>;
    announcer.textContent = String(error.message ?? 'PocketHive request failed');
    showError(String(error.message ?? 'PocketHive request failed'));
  }
});

vscode.postMessage({ type: 'ready' });

function render(preserveInteractionState = true): void {
  const focus = preserveInteractionState ? captureFocus() : undefined;
  if (preserveInteractionState) captureDisclosureState();
  app.replaceChildren();
  if (model.page === 'workspace') app.append(workspace());
  else app.append(PocketHiveEnvironmentViews.renderLanding(model, environmentViewPort));
  announcer.textContent = statusAnnouncement();
  restoreFocus(focus);
  requestAnimationFrame(() => document.querySelector<HTMLElement>('[role="tab"][aria-selected="true"]')
    ?.scrollIntoView({ block: 'nearest', inline: 'nearest' }));
}

function samePresentationContext(current: Model, next: Model): boolean {
  return current.page === next.page
    && current.activeTab === next.activeTab
    && current.activeProfile?.id === next.activeProfile?.id
    && current.session?.status === next.session?.status;
}


function workspace(): HTMLElement {
  const profile = model.activeProfile ?? {};
  const session = model.session ?? {
    status: 'Needs sign-in', message: 'Sign in again to reconnect this environment',
    canUseWorkspace: false, canSignIn: true, canSignOut: false,
  };
  const activeTab = String(model.activeTab);
  const section = el('section', 'workspace');
  section.append(text('h1', String(profile.displayName ?? 'PocketHive environment'), 'sr-only'));
  section.append(iconButton('Environments', 'arrow-left', () =>
    send({ type: 'backToEnvironments' }), 'back-link compact'));
  if (!session.canUseWorkspace) {
    section.append(PocketHiveEnvironmentViews.renderSessionNotice(model, session, environmentViewPort));
  }
  section.append(tabStrip());
  const content = el('section', 'tab-content');
  content.id = panelId(activeTab);
  content.setAttribute('role', 'tabpanel');
  content.setAttribute('aria-labelledby', tabId(activeTab));
  content.tabIndex = 0;
  content.append(el('div', 'section-heading', [
    el('div', 'section-heading__copy', [
      text('h2', tabTitle(activeTab)),
      text('p', tabSubtitle(activeTab), 'muted'),
    ]),
    sectionActions(activeTab),
  ]));
  if (activeTab === 'Debug') content.append(PocketHiveDebugViews.render(model, debugViewPort));
  else if (activeTab === 'Journal') content.append(PocketHiveEventViews.renderJournal(model, eventViewPort));
  else if (activeTab === 'Scenarios') content.append(PocketHiveScenarioViews.render(model, scenarioViewPort));
  else if (activeTab === 'Hive') content.append(PocketHiveHiveViews.render(model, hiveViewPort));
  else content.append(PocketHiveEventViews.renderBuzz(model, eventViewPort));
  section.append(content, PocketHiveEnvironmentViews.renderHealth(model, profile, session, environmentViewPort));
  return section;
}


function workspaceActionIconButton(
  label: string,
  iconName: string,
  action: () => void,
  className: string,
): HTMLButtonElement {
  const control = iconButton(label, iconName, action, className);
  control.disabled = Boolean(model.busy) || model.session?.canUseWorkspace === false;
  return control;
}

function sectionActions(activeTab: string): HTMLElement {
  const actions = el('div', 'actions');
  if (activeTab === 'Hive') {
    const createOpen = objectValue(model.createSwarmForm) !== undefined;
    actions.append(iconButton(createOpen ? 'Cancel create' : 'Create swarm', createOpen ? 'close' : 'add', () => send({
      type: createOpen ? 'cancelCreateSwarm' : 'openCreateSwarm',
    }), createOpen ? 'quiet compact' : 'primary compact'));
    return actions;
  }
  if (activeTab === 'Buzz') {
    actions.append(workspaceActionIconButton('Open Buzz in Web UI', 'link-external', () =>
      send({ type: 'openWebUi', destination: 'BUZZ' }), 'secondary compact icon-only-at-narrow'));
  }
  actions.append(workspaceActionIconButton(model.busy ? 'Loading…' : 'Refresh', 'refresh', () =>
    send({ type: 'refresh' }), 'secondary compact icon-only-at-narrow'));
  return actions;
}


function tabStrip(): HTMLElement {
  const nav = el('nav', 'tabs');
  nav.setAttribute('aria-label', 'Environment sections');
  nav.setAttribute('role', 'tablist');
  for (const tab of TABS) {
    const control = button(tab, () => send({ type: 'selectTab', tab }), `tab${model.activeTab === tab ? ' active' : ''}`);
    control.append(icon(TAB_ICONS[tab], 'tab__icon'));
    control.id = tabId(tab);
    control.setAttribute('role', 'tab');
    control.setAttribute('aria-controls', panelId(tab));
    control.disabled = Boolean(model.busy) || model.session?.canUseWorkspace === false;
    control.setAttribute('aria-selected', String(model.activeTab === tab));
    control.tabIndex = model.activeTab === tab ? 0 : -1;
    nav.append(control);
  }
  nav.addEventListener('keydown', event => {
    if (!(event.target instanceof HTMLElement) || event.target.getAttribute('role') !== 'tab') return;
    const current = TABS.indexOf(event.target.textContent as typeof TABS[number]);
    if (current < 0) return;
    let next: number | undefined;
    if (event.key === 'ArrowRight') next = (current + 1) % TABS.length;
    else if (event.key === 'ArrowLeft') next = (current - 1 + TABS.length) % TABS.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = TABS.length - 1;
    if (next === undefined) return;
    event.preventDefault();
    const target = nav.querySelector<HTMLElement>(`#${tabId(TABS[next])}`);
    target?.focus();
    target?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    target?.click();
  });
  return nav;
}


function brandMark(className: string): HTMLImageElement {
  const logo = document.createElement('img');
  logo.src = app.dataset.logo ?? '';
  logo.alt = '';
  logo.className = className;
  return logo;
}

function topLevelRecords(value: unknown): Model[] | undefined {
  const ownerError = errorFrom(value);
  if (ownerError) return undefined;
  if (!Array.isArray(value)) return undefined;
  if (value.some(item => !objectValue(item))) return undefined;
  return value as Model[];
}

function ownerDataError(value: unknown, expected: string): HTMLElement {
  return errorState(errorFrom(value) ?? `PocketHive returned an invalid ${expected}.`);
}

function errorFrom(value: unknown): string | undefined {
  const root = objectValue(value);
  const error = objectValue(root?.error);
  if (!error) return undefined;
  const code = stringField(error, 'code');
  const message = stringField(error, 'message');
  return [code, message].filter(Boolean).join(': ') || 'PocketHive request failed.';
}

function publicationAttemptId(value: unknown): string | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const publicationError = (value as Record<string, unknown>).publicationError;
  if (!publicationError || typeof publicationError !== 'object' || Array.isArray(publicationError)) return undefined;
  const attemptId = (publicationError as Record<string, unknown>).attemptId;
  return typeof attemptId === 'string' && attemptId.trim() ? attemptId.trim() : undefined;
}

function objectValue(value: unknown): Model | undefined {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Model : undefined;
}

function stringField(value: Model, field: string): string | undefined {
  const item = value[field];
  return typeof item === 'string' && item.trim() ? item.trim() : undefined;
}

function stringList(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string' && item.trim().length > 0).map(item => item.trim())
    : [];
}

function displayValue(value: unknown): string {
  if (typeof value === 'string' && value.trim()) return value.trim();
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  if (value && typeof value === 'object') return JSON.stringify(value);
  return 'Not reported';
}

function dataRows(rows: string[][]): HTMLElement {
  const result = el('dl', 'data-grid');
  for (const [label, value] of rows) result.append(text('dt', label), titled('dd', value));
  return result;
}

function technicalDetails(value: unknown, key = 'workspace:technical'): HTMLElement {
  const details = refreshStableDetails(key, 'technical-details');
  details.append(iconSummary('Technical details', 'code'));
  const pre = document.createElement('pre');
  pre.tabIndex = 0;
  pre.textContent = JSON.stringify(value, null, 2);
  details.append(pre);
  return details;
}

interface FocusSnapshot {
  readonly id: string;
  readonly selectionStart: number | null;
  readonly selectionEnd: number | null;
}

function captureFocus(): FocusSnapshot | undefined {
  const active = document.activeElement as HTMLInputElement | HTMLTextAreaElement | undefined;
  if (!active?.id || (active.tagName !== 'INPUT' && active.tagName !== 'TEXTAREA')) return undefined;
  return { id: active.id, selectionStart: active.selectionStart, selectionEnd: active.selectionEnd };
}

function restoreFocus(snapshot: FocusSnapshot | undefined): void {
  if (!snapshot || typeof document.getElementById !== 'function') return;
  const replacement = document.getElementById(snapshot.id) as HTMLInputElement | HTMLTextAreaElement | null;
  if (!replacement || (replacement.tagName !== 'INPUT' && replacement.tagName !== 'TEXTAREA')) return;
  replacement.focus({ preventScroll: true });
  if (snapshot.selectionStart !== null && snapshot.selectionEnd !== null) {
    replacement.setSelectionRange(snapshot.selectionStart, snapshot.selectionEnd);
  }
}

function captureDisclosureState(): void {
  if (typeof document.querySelectorAll !== 'function') return;
  document.querySelectorAll<HTMLDetailsElement>('details[data-refresh-key]').forEach(details => {
    const key = details.getAttribute('data-refresh-key');
    if (key) disclosureState.set(key, details.open);
  });
}

function refreshStableDetails(key: string, className: string, openByDefault = false): HTMLDetailsElement {
  const details = document.createElement('details');
  details.className = className;
  details.setAttribute('data-refresh-key', key);
  if (disclosureState.get(key) ?? openByDefault) details.setAttribute('open', '');
  return details;
}

function eventDisclosureKey(event: Model, context: 'Buzz' | 'Journal'): string {
  return [
    context,
    displayValue(event.eventId),
    displayValue(event.timestamp),
    displayValue(event.kind),
    displayValue(event.type),
    displayValue(event.swarmId),
    displayValue(event.origin),
    displayValue(event.routingKey),
  ].join(':');
}

function emptyState(message: string): HTMLElement {
  return text('p', message, 'muted callout empty-state');
}

function errorState(message: string): HTMLElement {
  return text('p', message, 'error-message callout', 'alert');
}

function tabId(tab: string): string {
  return `pockethive-tab-${tab.toLowerCase()}`;
}

function panelId(tab: string): string {
  return `pockethive-panel-${tab.toLowerCase()}`;
}


function debugEvidence(value: unknown, id: string): HTMLElement {
  return PocketHiveDebugEvidence.render(value, id, String(model.debugAction ?? 'Result'), {
    el,
    text,
    titled,
    icon,
    iconButton,
    iconText,
    statusPill,
    dataRows,
    technicalDetails,
    resultCard,
    ownerDataError,
    objectValue,
    stringField,
    displayValue,
    send: message => send(message),
  });
}

function resultCard(value: unknown): HTMLElement {
  if (value === undefined) return text('p', model.busy ? 'Loading current data…' : 'No data observed yet.', 'muted callout');
  const card = el('article', 'card result-card');
  const pre = document.createElement('pre');
  pre.tabIndex = 0;
  pre.textContent = JSON.stringify(value, null, 2);
  card.append(pre);
  return card;
}

function statusRow(label: string, status: string): HTMLElement {
  return el('div', 'status-row', [text('span', label), text('strong', status)]);
}

function endpointStatus(attempt: Model): string {
  return attempt.endpointValidated ? 'Validated' : attempt.state === 'EDITING' && attempt.failure ? 'Invalid' : 'Not tested';
}

function authenticationStatus(attempt: Model): string {
  if (attempt.authenticated) return 'Authenticated';
  if (attempt.state === 'AUTHENTICATION_FAILED' || attempt.state === 'CANCELLED') return 'Needs sign-in';
  if (attempt.state === 'AUTHENTICATING') return 'Signing in…';
  return 'Not started';
}

function testStatus(attempt: Model): string {
  if (attempt.state === 'READY_TO_SAVE' || attempt.state === 'SAVED') return 'PocketHive MCP reachable';
  if (attempt.state === 'CONNECTION_TEST_FAILED') return 'Unavailable';
  if (attempt.state === 'TESTING') return 'Testing…';
  return 'Not started';
}

function stage(number: string, label: string, done: boolean): HTMLElement {
  const item = el('li', done ? 'done' : '');
  item.append(text('span', number, 'stage-number'), text('span', done ? `${label} complete` : label));
  return item;
}

function statusPill(status: string): HTMLElement {
  const result = text('span', status, `status status--${statusToken(status)}`);
  result.append(icon('circle-filled', 'status__dot'));
  return result;
}

function statusToken(status: string): string {
  return status.toLowerCase().replace(/\s+/g, '-');
}

function input(
  label: string,
  id: string,
  value: string,
  placeholder: string,
  visuallyHiddenLabel = false,
): { wrapper: HTMLElement; control: HTMLInputElement } {
  const control = document.createElement('input');
  control.id = id;
  control.name = id;
  control.value = value;
  control.placeholder = placeholder;
  control.required = true;
  control.autocomplete = 'off';
  const wrapper = el('label', 'field', [text('span', label, visuallyHiddenLabel ? 'sr-only' : ''), control]);
  return { wrapper, control };
}

function searchInput(
  label: string,
  id: string,
  value: string,
  placeholder: string,
  visuallyHiddenLabel = true,
): { wrapper: HTMLElement; control: HTMLInputElement } {
  const field = input(label, id, value, placeholder, visuallyHiddenLabel);
  field.wrapper.className += ' search-field';
  field.wrapper.append(icon('search', 'search-field__icon'));
  return field;
}

function select(label: string, id: string, options: string[][], value: string): { wrapper: HTMLElement; control: HTMLSelectElement } {
  const control = document.createElement('select');
  control.id = id;
  control.name = id;
  for (const [optionValue, title] of options) {
    const option = document.createElement('option');
    option.value = optionValue;
    option.textContent = title;
    option.selected = optionValue === value;
    control.append(option);
  }
  control.value = value;
  return { wrapper: el('label', 'field', [text('span', label), control]), control };
}

function searchableChoice(
  label: string,
  id: string,
  choices: readonly string[],
  value: string,
  placeholder: string,
): { wrapper: HTMLElement; control: HTMLInputElement } {
  const control = document.createElement('input');
  const wrapper = el('div', 'field searchable-choice');
  const labelElement = text('label', label);
  const list = el('div', 'choice-popover');
  const listId = `${id}Choices`;
  control.id = id;
  control.name = id;
  control.value = value;
  control.placeholder = placeholder;
  control.autocomplete = 'off';
  control.setAttribute('role', 'combobox');
  control.setAttribute('aria-label', label);
  control.setAttribute('aria-autocomplete', 'list');
  control.setAttribute('aria-controls', listId);
  control.setAttribute('aria-expanded', 'false');
  labelElement.setAttribute('for', id);
  list.id = listId;
  list.setAttribute('role', 'listbox');
  list.hidden = true;
  const open = (visible: boolean): void => {
    list.hidden = !visible;
    control.setAttribute('aria-expanded', String(visible));
  };
  const renderChoices = (): void => {
    const query = control.value.trim().toLocaleLowerCase();
    const matches = choices.filter(choice => !query || choice.toLocaleLowerCase().includes(query));
    const options = matches.map(choice => {
      const option = button(choice, () => {
        control.value = choice;
        control.setAttribute('aria-invalid', 'false');
        open(false);
        control.dispatchEvent(new Event('change', { bubbles: true }));
      }, 'choice-option');
      option.setAttribute('role', 'option');
      option.setAttribute('aria-selected', String(choice === control.value));
      option.title = choice;
      return option;
    });
    list.replaceChildren(...options);
    if (matches.length === 0) list.append(text('p', 'No exact matches', 'muted choice-empty'));
  };
  control.addEventListener('focus', () => {
    renderChoices();
    open(true);
  });
  control.addEventListener('input', () => {
    renderChoices();
    open(true);
  });
  control.addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      event.preventDefault();
      open(false);
      return;
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      open(true);
      list.querySelector<HTMLElement>('[role="option"]')?.focus();
    }
  });
  wrapper.addEventListener('focusout', event => {
    const next = event.relatedTarget;
    if (!(next instanceof Node) || !wrapper.contains(next)) open(false);
  });
  wrapper.append(labelElement, el('div', 'choice-control', [
    icon('search', 'choice-control__icon'),
    control,
    icon('chevron-down', 'choice-control__chevron'),
  ]), list);
  renderChoices();
  return { wrapper, control };
}

function sendExactChoice(
  control: HTMLInputElement,
  choices: readonly string[],
  subject: string,
  onChoice: (choice: string) => void,
): void {
  const choice = control.value.trim();
  if (!choice) return;
  if (!choices.includes(choice)) {
    control.setAttribute('aria-invalid', 'true');
    showError(`Choose an exact ${subject} from the current PocketHive results.`);
    return;
  }
  control.setAttribute('aria-invalid', 'false');
  onChoice(choice);
}

function identifiers(value: unknown, keys: string[]): string[] {
  const found = new Set<string>();
  visit(value, 0);
  return [...found].sort();
  function visit(current: unknown, depth: number): void {
    if (depth > 5 || current === null || current === undefined) return;
    if (Array.isArray(current)) {
      current.slice(0, 1000).forEach(item => visit(item, depth + 1));
      return;
    }
    if (typeof current !== 'object') return;
    const record = current as Record<string, unknown>;
    for (const key of keys) {
      if (typeof record[key] === 'string' && record[key]) found.add(record[key] as string);
    }
    Object.values(record).slice(0, 1000).forEach(item => visit(item, depth + 1));
  }
}

function tabTitle(tab: string): string {
  return tab === 'Hive' ? 'Swarms' : tab;
}

function tabSubtitle(tab: string): string {
  if (tab === 'Hive') return 'Live workloads from PocketHive MCP';
  if (tab === 'Buzz') return 'Live control-plane traffic';
  if (tab === 'Journal') return 'Control-plane evidence';
  if (tab === 'Scenarios') return 'Deployed and committed repository Scenario Bundles';
  return 'Targeted runtime diagnostics';
}

function eventIcon(kind: string): string {
  const normalized = kind.toLocaleLowerCase();
  if (normalized === 'signal') return 'radio-tower';
  if (normalized === 'outcome') return 'check';
  if (normalized === 'command') return 'terminal';
  return 'pulse';
}

function statusAnnouncement(): string {
  return model.busy ? 'PocketHive operation in progress' : String(model.attempt?.state ?? model.activeProfile?.status ?? 'Ready');
}


function shortHash(value: string): string {
  return value.length > 12 ? value.slice(0, 12) : value;
}

function send(message: unknown, whileBusy = false): void {
  if (!model.busy || whileBusy) vscode.postMessage(message);
}

function showError(message: string): void {
  const existing = document.querySelector('.global-error');
  existing?.remove();
  const error = text('p', message, 'global-error', 'alert');
  app.prepend(error);
}

function icon(name: string, extraClass = ''): HTMLElement {
  const result = text('span', '', `codicon codicon-${name}${extraClass ? ` ${extraClass}` : ''}`);
  result.setAttribute('aria-hidden', 'true');
  return result;
}

function iconText(name: string, label: string, className = ''): HTMLElement {
  return el('span', `icon-text${className ? ` ${className}` : ''}`, [icon(name), text('span', label)]);
}

function iconSummary(label: string, iconName: string): HTMLElement {
  const summary = el('summary', 'icon-summary', [icon(iconName), text('span', label, 'sr-only')]);
  summary.setAttribute('aria-label', label);
  summary.title = label;
  return summary;
}

function iconButton(
  label: string,
  iconName: string,
  action: () => void,
  className: string,
  enabledWhileBusy = false,
): HTMLButtonElement {
  const control = button(label, action, className, enabledWhileBusy);
  control.setAttribute('aria-label', label);
  control.append(icon(iconName));
  return control;
}

function button(
  label: string,
  action: () => void,
  className: string,
  enabledWhileBusy = false,
): HTMLButtonElement {
  const control = document.createElement('button');
  control.type = 'button';
  control.className = `button ${className}`;
  control.textContent = label;
  control.disabled = Boolean(model.busy) && !enabledWhileBusy;
  control.addEventListener('click', action);
  return control;
}

function text(tag: string, value: string, className = '', role?: string): HTMLElement {
  const result = document.createElement(tag);
  result.className = className;
  result.textContent = value;
  if (role) result.setAttribute('role', role);
  return result;
}

function titled(tag: string, value: string, className = ''): HTMLElement {
  const result = text(tag, value, className);
  result.title = value;
  return result;
}

function el(tag: string, className = '', children: Array<Node | undefined> = []): HTMLElement {
  const result = document.createElement(tag);
  result.className = className;
  for (const child of children) if (child) result.append(child);
  return result;
}

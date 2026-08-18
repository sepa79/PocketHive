declare function acquireVsCodeApi(): { postMessage(message: unknown): void };

const vscode = acquireVsCodeApi();
const appElement = document.querySelector<HTMLElement>('#app');
const announcerElement = document.querySelector<HTMLElement>('#announcer');
if (!appElement || !announcerElement) throw new Error('PocketHive webview root missing');
const app: HTMLElement = appElement;
const announcer: HTMLElement = announcerElement;

type Model = Record<string, any>;
let model: Model = { page: 'environments', profiles: [], activeTab: 'Hive', debugActions: [], busy: false };

window.addEventListener('message', event => {
  const message: unknown = event.data;
  if (!message || typeof message !== 'object' || Array.isArray(message)) return;
  const value = message as Record<string, unknown>;
  if (value.type === 'viewModel' && value.model && typeof value.model === 'object') {
    model = value.model as Model;
    render();
    return;
  }
  if (value.type === 'error' && value.error && typeof value.error === 'object') {
    const error = value.error as Record<string, unknown>;
    announcer.textContent = String(error.message ?? 'PocketHive request failed');
    showError(String(error.message ?? 'PocketHive request failed'));
  }
});

vscode.postMessage({ type: 'ready' });

function render(): void {
  app.replaceChildren();
  app.append(header());
  if (model.page === 'workspace') app.append(workspace());
  else app.append(environments());
  announcer.textContent = statusAnnouncement();
}

function header(): HTMLElement {
  const result = el('header', 'brand');
  const logo = document.createElement('img');
  logo.src = app.dataset.logo ?? '';
  logo.alt = '';
  logo.className = 'brand__logo';
  result.append(logo, el('div', 'brand__copy', [
    text('h1', 'PocketHive'),
    text('p', model.page === 'workspace' ? 'MCP environment' : 'MCP environments'),
  ]));
  return result;
}

function environments(): HTMLElement {
  const section = el('section', 'page');
  const titleRow = el('div', 'title-row', [
    el('div', '', [text('h2', 'Environments'), text('p', 'Connect to a PocketHive MCP environment.')]),
    button('Add', () => send({ type: 'addEnvironment' }), 'secondary compact'),
  ]);
  section.append(titleRow);
  const profiles = Array.isArray(model.profiles) ? model.profiles : [];
  if (profiles.length === 0 && model.page !== 'add') {
    section.append(el('div', 'empty card', [
      text('h3', 'No environments yet'),
      text('p', 'Add the MCP URL supplied by your PocketHive administrator.'),
      button('Add environment', () => send({ type: 'addEnvironment' }), 'primary'),
    ]));
  } else {
    const list = el('div', 'card-list');
    for (const profile of profiles) list.append(environmentCard(profile));
    section.append(list);
  }
  if (model.page === 'add') section.append(connectionForm());
  return section;
}

function environmentCard(profile: Model): HTMLElement {
  const status = String(profile.status ?? 'Not connected');
  const card = el('article', 'card environment-card');
  card.append(
    el('div', 'environment-card__head', [
      text('h3', String(profile.displayName ?? 'Environment')),
      statusPill(status),
    ]),
    titled('p', String(profile.mcpUrl ?? ''), 'mono truncate'),
  );
  if (profile.principalLabel) card.append(text('p', `Signed in as ${String(profile.principalLabel)}`, 'muted'));
  card.append(el('div', 'actions', [
    button('Open', () => send({ type: 'openEnvironment', profileId: String(profile.id) }), 'primary compact'),
    button('Remove', () => send({ type: 'removeEnvironment', profileId: String(profile.id) }), 'quiet compact'),
  ]));
  return card;
}

function connectionForm(): HTMLElement {
  const draft = model.draft ?? {};
  const attempt = model.attempt ?? { state: 'EDITING' };
  const form = document.createElement('form');
  form.className = 'card connect-card';
  form.noValidate = true;
  const stages = el('ol', 'stages', [
    stage('1', 'Endpoint', Boolean(attempt.endpointValidated)),
    stage('2', 'Connect', Boolean(attempt.authenticated)),
    stage('3', 'Ready', attempt.state === 'READY_TO_SAVE' || attempt.state === 'SAVED'),
  ]);
  const name = input('Name', 'displayName', String(draft.displayName ?? ''), 'NFT Lab');
  const url = input('MCP URL', 'mcpUrl', String(draft.mcpUrl ?? ''), 'https://environment.example/mcp');
  const mode = select('Endpoint security', 'endpointSecurityMode', [
    ['REMOTE_HTTPS', 'Remote HTTPS'],
    ['LOCAL_LOOPBACK_HTTP', 'Local loopback HTTP'],
  ], String(draft.endpointSecurityMode ?? 'REMOTE_HTTPS'));
  form.append(text('h3', draft.id ? 'Connection' : 'Add environment'), stages, name.wrapper, url.wrapper, mode.wrapper);
  form.append(el('div', 'status-list', [
    statusRow('Endpoint', endpointStatus(attempt)),
    statusRow('Authentication', authenticationStatus(attempt)),
    statusRow('Connection test', testStatus(attempt)),
  ]));
  if (attempt.failure?.message) form.append(text('p', String(attempt.failure.message), 'error-message', 'alert'));
  const controls = el('div', 'form-actions');
  if (model.busy && (attempt.state === 'AUTHENTICATING' || attempt.state === 'TESTING')) {
    controls.append(button('Cancel connection', () => send({ type: 'cancelConnection' }, true), 'secondary', true));
  } else if (attempt.state === 'AUTHENTICATION_FAILED' || attempt.state === 'CANCELLED') {
    controls.append(button('Sign in again', () => send({ type: 'signInAgain' }), 'primary'));
  } else if (attempt.state === 'CONNECTION_TEST_FAILED') {
    controls.append(button('Retry test', () => send({ type: 'retryTest' }), 'primary'));
  } else if (attempt.state !== 'READY_TO_SAVE' && attempt.state !== 'SAVED') {
    const connect = button(model.busy ? 'Connecting…' : 'Connect', () => {
      send({
        type: 'connect',
        displayName: name.control.value,
        mcpUrl: url.control.value,
        endpointSecurityMode: mode.control.value,
      });
    }, 'primary');
    connect.type = 'submit';
    controls.append(connect);
  }
  const save = button('Save & open', () => send({ type: 'saveOpen' }), 'primary');
  save.disabled = attempt.state !== 'READY_TO_SAVE' || model.busy;
  controls.append(save, button('Cancel', () => send({ type: 'backToEnvironments' }), 'quiet'));
  form.append(controls);
  form.addEventListener('submit', event => {
    event.preventDefault();
    if (!model.busy) send({
      type: 'connect', displayName: name.control.value, mcpUrl: url.control.value,
      endpointSecurityMode: mode.control.value,
    });
  });
  return form;
}

function workspace(): HTMLElement {
  const profile = model.activeProfile ?? {};
  const section = el('section', 'workspace');
  section.append(button('‹ Environments', () => send({ type: 'backToEnvironments' }), 'back-link'));
  section.append(el('div', 'workspace-heading', [
    el('div', '', [
      text('h2', String(profile.displayName ?? 'Environment')),
      profile.principalLabel ? text('p', `Signed in as ${String(profile.principalLabel)}`, 'muted') : undefined,
    ]),
    statusPill(String(profile.status ?? 'Not connected')),
  ].filter(Boolean) as Node[]));
  section.append(tabStrip());
  const content = el('section', 'tab-content');
  content.append(el('div', 'section-heading', [
    el('div', '', [text('h3', String(model.activeTab)), text('p', tabDescription(String(model.activeTab)))]),
    button(model.busy ? 'Loading…' : 'Refresh', () => send({ type: 'refresh' }), 'secondary compact'),
  ]));
  if (model.activeTab === 'Debug') content.append(debugView());
  else if (model.activeTab === 'Scenarios') content.append(scenarioBundleView(), resultCard(model.workspaceData));
  else content.append(resultCard(model.workspaceData));
  section.append(content, el('footer', 'connection-footer', [
    statusPill(String(profile.status ?? 'Not connected')),
    text('span', model.busy ? 'Refreshing' : 'Live MCP observation', 'muted'),
  ]));
  return section;
}

function scenarioBundleView(): HTMLElement {
  const pending = model.pendingBundle;
  const result = el('section', 'card scenario-upload');
  result.append(
    text('h4', 'Committed bundle'),
    text('p', 'Select an exact committed Git directory. PocketHive validates the retained ZIP before any explicit publication.', 'muted'),
  );
  if (!pending) {
    result.append(button('Validate committed bundle', () => send({ type: 'validateCommittedBundle' }), 'secondary'));
  } else {
    const source = pending.source ?? {};
    result.append(
      titled('p', String(source.bundlePath ?? ''), 'mono truncate'),
      text('p', `${String(pending.fileCount ?? 0)} files · commit ${shortHash(String(source.commit ?? ''))}`, 'muted'),
    );
    const replaceId = input('Scenario ID for REPLACE', 'publicationScenarioId', '', 'db-query-postgres-smoke');
    const actions = el('div', 'form-actions', [
      button('Publish CREATE', () => send({ type: 'publishCommittedBundle', mode: 'CREATE' }), 'primary'),
      button('Publish REPLACE', () => {
        if (replaceId.control.value.trim()) send({
          type: 'publishCommittedBundle', mode: 'REPLACE', scenarioId: replaceId.control.value.trim(),
        });
      }, 'guarded'),
      button('Discard', () => send({ type: 'discardPendingBundle' }), 'quiet'),
    ]);
    result.append(replaceId.wrapper, actions);
  }
  if (model.bundleResult !== undefined) result.append(resultCard(model.bundleResult));
  return result;
}

function tabStrip(): HTMLElement {
  const nav = el('nav', 'tabs');
  nav.setAttribute('aria-label', 'Environment sections');
  nav.setAttribute('role', 'tablist');
  for (const tab of ['Hive', 'Buzz', 'Journal', 'Scenarios', 'Debug']) {
    const control = button(tab, () => send({ type: 'selectTab', tab }), `tab${model.activeTab === tab ? ' active' : ''}`);
    control.setAttribute('role', 'tab');
    control.setAttribute('aria-selected', String(model.activeTab === tab));
    nav.append(control);
  }
  return nav;
}

function debugView(): HTMLElement {
  const result = el('div', 'debug');
  const swarms = identifiers(model.workspaceData, ['swarmId', 'id']);
  const swarm = select('Exact swarm', 'debugSwarm', [['', 'Select a swarm'], ...swarms.map(id => [id, id])], model.debugSwarmId ?? '');
  swarm.control.addEventListener('change', () => {
    if (swarm.control.value) send({ type: 'selectDebugSwarm', swarmId: swarm.control.value });
  });
  result.append(swarm.wrapper);
  const workers = identifiers(model.debugResult, ['runtimeId', 'id', 'name']);
  if (workers.length > 0) {
    const worker = select('Exact worker', 'debugWorker', [['', 'Select a worker'], ...workers.map(id => [id, id])], model.debugRuntimeId ?? '');
    worker.control.addEventListener('change', () => {
      if (worker.control.value) send({ type: 'selectDebugWorker', runtimeId: worker.control.value });
    });
    result.append(worker.wrapper);
  }
  const actions = el('div', 'debug-actions');
  for (const action of model.debugActions ?? []) {
    const needsWorker = Boolean(action.needsWorker);
    const control = button(String(action.label), () => {
      const message: Model = { type: 'runDebug', action: String(action.label) };
      if (action.label === 'Logs') message.tailLines = 200;
      send(message);
    }, action.label === 'Cleanup plan' ? 'guarded' : 'secondary');
    control.disabled = model.busy || !model.debugSwarmId || (needsWorker && !model.debugRuntimeId);
    actions.append(control);
  }
  result.append(text('h4', 'Actions'), actions);
  if (model.debugResult !== undefined) result.append(resultCard(model.debugResult));
  else result.append(text('p', 'Choose an exact target and action. PocketHive will not guess a worker or resource.', 'muted callout'));
  return result;
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
  return text('span', status, `status status--${status.toLowerCase().replace(/\s+/g, '-')}`);
}

function input(label: string, id: string, value: string, placeholder: string): { wrapper: HTMLElement; control: HTMLInputElement } {
  const control = document.createElement('input');
  control.id = id;
  control.name = id;
  control.value = value;
  control.placeholder = placeholder;
  control.required = true;
  control.autocomplete = 'off';
  const wrapper = el('label', 'field', [text('span', label), control]);
  return { wrapper, control };
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
  return { wrapper: el('label', 'field', [text('span', label), control]), control };
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

function tabDescription(tab: string): string {
  const descriptions: Record<string, string> = {
    Hive: 'Live swarms from PocketHive MCP',
    Buzz: 'Bounded hive-wide event timeline',
    Journal: 'Choose an exact swarm for journal evidence',
    Scenarios: 'Bundles sourced from Scenario Manager',
    Debug: 'Bounded Orchestrator diagnostics',
  };
  return descriptions[tab] ?? '';
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

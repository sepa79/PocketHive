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
const applyEventFilters = PocketHiveEventFilters.filterEvents;
const EVENT_TIME_WINDOWS = PocketHiveEventFilters.TIME_WINDOWS;

const vscode = acquireVsCodeApi();
const appElement = document.querySelector<HTMLElement>('#app');
const announcerElement = document.querySelector<HTMLElement>('#announcer');
if (!appElement || !announcerElement) throw new Error('PocketHive webview root missing');
const app: HTMLElement = appElement;
const announcer: HTMLElement = announcerElement;

type Model = Record<string, any>;
const TABS = ['Hive', 'Buzz', 'Journal', 'Scenarios', 'Debug'] as const;
type ScenarioSection = 'OVERVIEW' | 'FILES' | 'INPUTS';
let model: Model = { page: 'environments', profiles: [], activeTab: 'Hive', debugActions: [], busy: false };
let expandedHistorySwarmId: string | undefined;
let expandedScenarioId: string | undefined;
let scenarioSearch = '';
let scenarioFolder = 'ALL';
let createSwarmDraft: {
  swarmId: string;
  templateId: string;
  scenarioId: string;
  sutId: string;
  variablesProfileId: string;
} | undefined;
const eventCriteria: Record<'Buzz' | 'Journal', WebviewEventFilterCriteria> = {
  Buzz: { timeWindow: EVENT_TIME_WINDOWS.ALL, kind: 'ALL', severity: 'ALL', search: '' },
  Journal: { timeWindow: EVENT_TIME_WINDOWS.ALL, kind: 'ALL', severity: 'ALL', search: '' },
};

window.addEventListener('message', event => {
  const message: unknown = event.data;
  if (!message || typeof message !== 'object' || Array.isArray(message)) return;
  const value = message as Record<string, unknown>;
  if (value.type === 'viewModel' && value.model && typeof value.model === 'object') {
    reconcileCreateSwarmDraft(value.model as Model);
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
  requestAnimationFrame(() => document.querySelector<HTMLElement>('[role="tab"][aria-selected="true"]')
    ?.scrollIntoView({ block: 'nearest', inline: 'nearest' }));
}

function header(): HTMLElement {
  const result = el('header', 'brand');
  const logo = document.createElement('img');
  logo.src = app.dataset.logo ?? '';
  logo.alt = '';
  logo.className = 'brand__logo';
  const name = el('h1', 'brand__name', [
    text('span', 'Pocket'),
    text('span', 'Hive', 'brand__hive'),
  ]);
  result.append(logo, el('div', 'brand__copy', [
    name,
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
  const session = model.session ?? {
    status: 'Needs sign-in', message: 'Sign in again to reconnect this environment',
    canUseWorkspace: false, canSignIn: true, canSignOut: false,
  };
  const activeTab = String(model.activeTab);
  const section = el('section', 'workspace');
  section.append(button('‹ Environments', () => send({ type: 'backToEnvironments' }), 'back-link'));
  section.append(el('div', 'workspace-heading', [
    el('div', '', [
      text('h2', String(profile.displayName ?? 'Environment')),
      profile.principalLabel ? text('p', `Signed in as ${String(profile.principalLabel)}`, 'muted') : undefined,
    ]),
    el('div', 'workspace-heading__account', [
      statusPill(String(session.status ?? profile.status ?? 'Not connected')),
      accountMenu(profile, session),
    ]),
  ].filter(Boolean) as Node[]));
  if (!session.canUseWorkspace) section.append(sessionNotice(session));
  section.append(tabStrip());
  const content = el('section', 'tab-content');
  content.id = panelId(activeTab);
  content.setAttribute('role', 'tabpanel');
  content.setAttribute('aria-labelledby', tabId(activeTab));
  content.tabIndex = 0;
  content.append(el('div', 'section-heading', [
    el('div', '', [text('h3', activeTab), text('p', tabDescription(activeTab))]),
    sectionActions(activeTab),
  ]));
  if (activeTab === 'Debug') content.append(debugView());
  else if (activeTab === 'Journal') content.append(journalView());
  else if (activeTab === 'Scenarios') content.append(scenarioBundleView(), scenarioListView(model.workspaceData));
  else if (activeTab === 'Hive') {
    if (model.createSwarmForm !== undefined) content.append(createSwarmView(model.createSwarmForm));
    content.append(swarmListView(model.workspaceData));
  }
  else content.append(eventListView(model.workspaceData, 'No hive events were observed.', 'Buzz'));
  section.append(content, el('footer', 'connection-footer', [
    statusPill(String(session.status ?? profile.status ?? 'Not connected')),
    text('span', model.busy ? 'Refreshing' : String(session.message ?? 'Secure session unavailable'), 'muted'),
  ]));
  return section;
}

function accountMenu(profile: Model, session: Model): HTMLElement {
  const details = document.createElement('details');
  details.className = 'account-menu';
  const summary = document.createElement('summary');
  summary.textContent = 'Account';
  details.append(summary);
  const panel = el('div', 'account-menu__panel', [
    text('strong', profile.principalLabel ? String(profile.principalLabel) : 'PocketHive user'),
    text('span', String(session.message ?? 'Secure session unavailable'), 'muted'),
  ]);
  const actions = el('div', 'account-menu__actions');
  if (session.canSignIn) {
    actions.append(button('Sign in', () => send({ type: 'reauthorizeEnvironment' }), 'primary compact'));
  }
  if (!session.canUseWorkspace && !session.canSignIn && session.status !== 'Connecting') {
    actions.append(button('Retry connection', () => send({ type: 'refresh' }), 'secondary compact'));
  }
  if (session.canSignOut) {
    actions.append(button('Sign out', () => send({ type: 'signOut' }), 'quiet compact'));
  }
  panel.append(actions);
  details.append(panel);
  return details;
}

function sessionNotice(session: Model): HTMLElement {
  const result = el('section', 'session-notice');
  result.setAttribute('role', session.canSignIn ? 'alert' : 'status');
  result.append(
    text('strong', String(session.status ?? 'Session unavailable')),
    text('span', String(session.message ?? 'The secure session is unavailable'), 'muted'),
  );
  if (session.canSignIn) {
    result.append(button('Sign in', () => send({ type: 'reauthorizeEnvironment' }), 'primary compact'));
  } else if (session.status !== 'Connecting') {
    result.append(button('Retry', () => send({ type: 'refresh' }), 'secondary compact'));
  }
  return result;
}

function workspaceActionButton(label: string, action: () => void, className: string): HTMLButtonElement {
  const control = button(label, action, className);
  control.disabled = Boolean(model.busy) || model.session?.canUseWorkspace === false;
  return control;
}

function sectionActions(activeTab: string): HTMLElement {
  const actions = el('div', 'actions');
  if (activeTab === 'Hive') {
    const createOpen = objectValue(model.createSwarmForm) !== undefined;
    const primaryActions = objectValue(model.swarmPrimaryActions) ?? {};
    const available = Object.values(primaryActions);
    actions.append(button(createOpen ? 'Cancel create' : 'Create swarm', () => send({
      type: createOpen ? 'cancelCreateSwarm' : 'openCreateSwarm',
    }), createOpen ? 'quiet compact' : 'primary compact'));
    if (available.includes(model.swarmOperations?.START)) {
      actions.append(button('Start all', () => send({ type: 'runSwarmBatchOperation', action: 'START' }), 'secondary compact'));
    }
    if (available.includes(model.swarmOperations?.STOP)) {
      actions.append(button('Stop all', () => send({ type: 'runSwarmBatchOperation', action: 'STOP' }), 'secondary compact'));
    }
  }
  actions.append(workspaceActionButton(model.busy ? 'Loading…' : 'Refresh', () => send({ type: 'refresh' }), 'secondary compact'));
  return actions;
}

function scenarioBundleView(): HTMLElement {
  const pending = model.pendingBundle;
  const attemptId = publicationAttemptId(model.bundleResult);
  const result = el('section', 'card scenario-upload');
  result.append(
    text('h4', 'Committed bundle'),
    text('p', 'Select an exact committed Git directory. PocketHive validates the retained ZIP before any explicit publication.', 'muted'),
  );
  if (!pending) {
    const actions = el('div', 'form-actions', [
      button('Validate committed bundle', () => send({ type: 'validateCommittedBundle' }), 'secondary'),
    ]);
    if (attemptId) {
      actions.append(button('Reconcile attempt', () => send({ type: 'reconcilePublicationAttempt', attemptId }), 'guarded'));
    }
    result.append(actions);
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
  for (const tab of TABS) {
    const control = button(tab, () => send({ type: 'selectTab', tab }), `tab${model.activeTab === tab ? ' active' : ''}`);
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

function journalView(): HTMLElement {
  const result = el('div', 'journal');
  const swarms = topLevelRecords(model.workspaceData);
  if (swarms === undefined) return ownerDataError(model.workspaceData, 'swarm list');
  const swarmIds = swarms.map(item => stringField(item, 'id')).filter((id): id is string => Boolean(id));
  if (swarmIds.length === 0) {
    result.append(emptyState('No swarms are available for journal evidence.'));
    return result;
  }
  const swarm = select('Exact swarm', 'journalSwarm', [
    ['', 'Select a swarm'],
    ...swarmIds.map(id => [id, id]),
  ], String(model.journalSwarmId ?? ''));
  swarm.control.addEventListener('change', () => {
    if (swarm.control.value) send({ type: 'selectJournalSwarm', swarmId: swarm.control.value });
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

function swarmListView(value: unknown): HTMLElement {
  const swarms = topLevelRecords(value);
  if (swarms === undefined) return ownerDataError(value, 'swarm list');
  if (swarms.length === 0) return emptyState('No live swarms are currently visible.');
  const list = el('div', 'swarm-list');
  if (model.swarmOperationResult !== undefined) {
    list.append(el('div', 'operation-result callout', [
      text('strong', 'Lifecycle request accepted'),
      text('span', 'The list below was refreshed from Orchestrator.', 'muted'),
      technicalDetails(model.swarmOperationResult),
    ]));
  }
  for (const swarm of swarms) {
    const id = stringField(swarm, 'id');
    if (!id) {
      list.append(ownerDataError(swarm, 'swarm record'));
      continue;
    }
    const status = swarmStatus(swarm);
    const bees = swarmBeeCount(swarm);
    const operation = model.swarmPrimaryActions?.[id];
    const card = el('article', 'swarm-row');
    const identity = el('div', 'swarm-row__identity', [
      brandMark('swarm-mark'),
      el('div', 'swarm-row__copy', [
        titled('h4', id, 'truncate'),
        text('p', [displayValue(swarm.templateId), `${bees} ${bees === 1 ? 'bee' : 'bees'}`].join(' · '), 'muted truncate'),
      ]),
    ]);
    const actions = el('div', 'swarm-row__actions');
    actions.append(statusPill(status));
    if (operation) {
      const label = operation === model.swarmOperations?.START ? 'Start' : 'Stop';
      actions.append(button(label, () => send({ type: 'runSwarmOperation', action: operation, swarmId: id }), 'secondary compact'));
    }
    actions.append(button('Details', () => send({ type: 'openSwarmDetails', swarmId: id }), 'secondary compact'));
    actions.append(button('Debug', () => send({ type: 'openDebugForSwarm', swarmId: id }), 'secondary compact'));
    const more = el('details', 'row-menu');
    more.append(text('summary', 'More'), el('div', 'row-menu__panel', [
      button('Remove swarm', () => send({ type: 'runSwarmOperation', action: model.swarmOperations?.REMOVE, swarmId: id }), 'guarded compact'),
      technicalDetails(swarm),
    ]));
    actions.append(more);
    card.append(el('div', 'swarm-row__main', [identity, actions]));

    const expanded = expandedHistorySwarmId === id;
    const history = button(expanded ? 'Hide run history' : 'Run history', () => {
      if (expanded) {
        expandedHistorySwarmId = undefined;
        render();
      } else {
        expandedHistorySwarmId = id;
        send({ type: 'loadSwarmHistory', swarmId: id });
      }
    }, 'history-toggle quiet compact');
    history.setAttribute('aria-expanded', String(expanded));
    card.append(history);
    if (expanded) card.append(swarmRunHistory(id));
    list.append(card);
  }
  return list;
}

function createSwarmView(value: unknown): HTMLElement {
  const formValue = objectValue(value);
  if (!formValue) return ownerDataError(value, 'create swarm form');
  const result = el('section', 'card scenario-upload');
  result.append(
    text('h4', 'Create swarm'),
    text('p', 'Choose one exact deployed template and optional overrides. PocketHive sends one explicit swarm-create request through MCP.', 'muted'),
  );
  const templates = createSwarmOptions(formValue.templates);
  if (templates === undefined) {
    result.append(ownerDataError(formValue.templates, 'Scenario Manager template catalogue'));
    return result;
  }
  if (templates.length === 0) {
    result.append(emptyState('No non-defunct templates are currently available for swarm creation.'));
    return result;
  }
  const selectedTemplateId = createSwarmDraft?.templateId
    && templates.some(option => option.templateId === createSwarmDraft?.templateId)
    ? createSwarmDraft.templateId
    : stringField(formValue, 'selectedTemplateId') ?? templates[0].templateId;
  const selectedScenarioId = createSwarmDraft?.scenarioId
    && templates.some(option => option.scenarioId === createSwarmDraft?.scenarioId)
    ? createSwarmDraft.scenarioId
    : stringField(formValue, 'selectedScenarioId')
      ?? templates.find(option => option.templateId === selectedTemplateId)?.scenarioId
      ?? templates[0].scenarioId;
  const template = select('Template', 'createSwarmTemplate', templates
    .map(option => [option.templateId, `${option.name} (${option.templateId})`]), selectedTemplateId);
  template.control.addEventListener('change', () => {
    const draft = ensureCreateSwarmDraft();
    const nextTemplate = templates.find(option => option.templateId === template.control.value);
    draft.templateId = template.control.value;
    draft.scenarioId = nextTemplate?.scenarioId ?? template.control.value;
    draft.sutId = '';
    send({
      type: 'selectCreateSwarmTemplate',
      templateId: draft.templateId,
      scenarioId: draft.scenarioId,
    });
  });
  const swarmId = input('Swarm ID', 'createSwarmId', createSwarmDraft?.swarmId ?? '', 'checkout-load');
  swarmId.control.addEventListener('input', () => { ensureCreateSwarmDraft().swarmId = swarmId.control.value; });
  const sutIds = stringList(formValue.sutIds);
  const sut = select('SUT override', 'createSwarmSut', [
    ['', 'Use bundle default'],
    ...sutIds.map(id => [id, id]),
  ], sutIds.includes(createSwarmDraft?.sutId ?? '') ? createSwarmDraft?.sutId ?? '' : '');
  sut.control.addEventListener('change', () => { ensureCreateSwarmDraft().sutId = sut.control.value; });
  const variables = input('Variables profile ID', 'createSwarmVariablesProfile',
    createSwarmDraft?.variablesProfileId ?? '', 'Leave blank to use bundle default');
  variables.control.required = false;
  variables.control.addEventListener('input', () => { ensureCreateSwarmDraft().variablesProfileId = variables.control.value; });
  result.append(template.wrapper, swarmId.wrapper, sut.wrapper, variables.wrapper);
  if (sutIds.length === 0) result.append(text('p', 'No bundle-local SUT overrides were published for this template.', 'muted'));
  result.append(el('div', 'form-actions', [
    button('Create swarm', () => {
      const draft = ensureCreateSwarmDraft();
      if (!draft.swarmId.trim()) {
        showError('Exact swarm ID required.');
        return;
      }
      send({
        type: 'submitCreateSwarm',
        swarmId: draft.swarmId,
        templateId: draft.templateId || selectedTemplateId,
        scenarioId: draft.scenarioId || selectedScenarioId,
        sutId: draft.sutId,
        variablesProfileId: draft.variablesProfileId,
      });
    }, 'primary'),
    button('Cancel', () => send({ type: 'cancelCreateSwarm' }), 'quiet'),
  ]));
  return result;
}

function swarmRunHistory(swarmId: string): HTMLElement {
  const panel = el('section', 'run-history');
  panel.setAttribute('aria-label', `Run history for ${swarmId}`);
  if (model.swarmHistorySwarmId !== swarmId || model.swarmHistoryResult === undefined) {
    panel.append(emptyState(model.busy ? 'Loading authoritative run history…' : 'Run history has not been loaded.'));
    return panel;
  }
  const error = errorFrom(model.swarmHistoryResult);
  if (error) {
    panel.append(errorState(error));
    return panel;
  }
  const runs = topLevelRecords(model.swarmHistoryResult);
  if (!runs) {
    panel.append(ownerDataError(model.swarmHistoryResult, 'journal run list'));
    return panel;
  }
  if (runs.length === 0) {
    panel.append(emptyState('No authoritative journal runs were reported.'));
    return panel;
  }
  for (const run of runs) {
    const runId = stringField(run, 'runId');
    if (!runId) {
      panel.append(ownerDataError(run, 'journal run'));
      continue;
    }
    panel.append(el('article', 'run-row', [
      el('div', 'run-row__copy', [
        titled('strong', runId, 'mono truncate'),
        text('span', runSummary(run), 'muted'),
      ]),
      button('Open journal', () => send({ type: 'openJournalRun', swarmId, runId }), 'secondary compact'),
    ]));
  }
  return panel;
}

function runSummary(run: Model): string {
  const first = stringField(run, 'firstTs');
  const last = stringField(run, 'lastTs');
  const entries = typeof run.entries === 'number' ? run.entries : undefined;
  const parts = [
    first && last ? formatDuration(first, last) : undefined,
    entries === undefined ? undefined : `${entries} ${entries === 1 ? 'entry' : 'entries'}`,
    run.pinned === true ? 'Pinned' : undefined,
  ].filter(Boolean);
  return parts.length ? parts.join(' · ') : 'No run metadata reported';
}

function publicationAttemptId(value: unknown): string | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const publicationError = (value as Record<string, unknown>).publicationError;
  if (!publicationError || typeof publicationError !== 'object' || Array.isArray(publicationError)) return undefined;
  const attemptId = (publicationError as Record<string, unknown>).attemptId;
  return typeof attemptId === 'string' && attemptId.trim() ? attemptId.trim() : undefined;
}

function formatDuration(first: string, last: string): string | undefined {
  const start = Date.parse(first);
  const end = Date.parse(last);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return undefined;
  const seconds = Math.round((end - start) / 1000);
  if (seconds < 60) return `${seconds}s`;
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}

function swarmBeeCount(swarm: Model): number {
  if (Array.isArray(swarm.bees)) return swarm.bees.length;
  if (typeof swarm.beeCount === 'number' && swarm.beeCount >= 0) return swarm.beeCount;
  return 0;
}

function scenarioListView(value: unknown): HTMLElement {
  const scenarios = topLevelRecords(value);
  if (scenarios === undefined) return ownerDataError(value, 'scenario list');
  if (scenarios.length === 0) return emptyState('No deployed Scenario Bundles are visible.');
  const result = el('section', 'scenario-catalogue');
  const folders = [...new Set(scenarios.map(item => stringField(item, 'folderPath')).filter((item): item is string => Boolean(item)))].sort();
  const search = input('Search bundles', 'scenarioSearch', scenarioSearch, 'Name or scenario ID');
  search.control.required = false;
  const folder = select('Folder', 'scenarioFolder', [['ALL', 'All folders'], ...folders.map(item => [item, item])], scenarioFolder);
  const filters = el('div', 'filter-bar scenario-filters', [search.wrapper, folder.wrapper]);
  const list = el('div', 'scenario-list');
  const apply = () => {
    scenarioSearch = search.control.value;
    scenarioFolder = folder.control.value;
    const query = scenarioSearch.trim().toLocaleLowerCase();
    const filtered = scenarios.filter(item => {
      const exactFolder = stringField(item, 'folderPath') ?? '';
      const searchable = [
        stringField(item, 'id'),
        stringField(item, 'name'),
        stringField(item, 'bundleKey'),
        stringField(item, 'description'),
        exactFolder,
      ]
        .filter(Boolean).join(' ').toLocaleLowerCase();
      return (scenarioFolder === 'ALL' || exactFolder === scenarioFolder) && (!query || searchable.includes(query));
    });
    list.replaceChildren(...filtered.map(scenarioRow));
    if (filtered.length === 0) list.append(emptyState('No Scenario Bundles match these filters.'));
  };
  search.control.addEventListener('input', apply);
  folder.control.addEventListener('change', apply);
  result.append(filters, list);
  apply();
  return result;
}

function scenarioRow(scenario: Model): HTMLElement {
  const bundleKey = stringField(scenario, 'bundleKey');
  const scenarioId = stringField(scenario, 'id');
  const name = stringField(scenario, 'name') ?? bundleKey ?? scenarioId;
  if (!bundleKey || !name) return ownerDataError(scenario, 'scenario record');
  const rowId = scenarioRowId(scenarioId, bundleKey);
  const focused = model.scenarioFocusScenarioId === scenarioId && model.scenarioFocusBundleKey === bundleKey;
  const section = focused ? model.scenarioFocusSection as ScenarioSection ?? 'OVERVIEW' : undefined;
  const defunct = scenario.defunct === true;
  const details = el('details', 'scenario-row');
  if (expandedScenarioId === rowId || focused) details.setAttribute('open', '');
  const summaryMeta = scenarioId ? scenarioId : bundleKey;
  details.append(el('summary', '', [
    el('div', 'scenario-row__copy', [
      titled('strong', name, 'truncate'),
      titled('span', summaryMeta, 'mono muted truncate'),
    ]),
    statusPill(defunct ? 'Defunct' : 'Deployed'),
  ]));
  const body = el('div', 'scenario-row__body');
  body.append(dataRows([
    ['Scenario ID', scenarioId ?? 'Unavailable'],
    ['Bundle key', bundleKey],
    ['Folder', displayValue(scenario.folderPath)],
    ['Bundle path', displayValue(scenario.bundlePath)],
  ]));
  if (scenarioId) {
    body.append(el('div', 'actions', [
      button('Open details', () => send({ type: 'openScenarioDetails', scenarioId }), 'secondary compact'),
      button('Open scenario.yaml', () => send({ type: 'openScenarioRaw', scenarioId }), 'secondary compact'),
      button('Open schema…', () => send({ type: 'openScenarioSchema', scenarioId }), 'secondary compact'),
      button('Open template…', () => send({ type: 'openScenarioTemplate', scenarioId }), 'secondary compact'),
    ]));
    const sectionTabs = el('div', 'scenario-section-tabs', [
      scenarioSectionButton('Overview', 'OVERVIEW', scenarioId, bundleKey, section, rowId),
      scenarioSectionButton('Files', 'FILES', scenarioId, bundleKey, section, rowId),
      scenarioSectionButton('Inputs', 'INPUTS', scenarioId, bundleKey, section, rowId),
    ]);
    body.append(sectionTabs);
    if (section === 'OVERVIEW') body.append(scenarioOverviewSection(scenario));
    if (section === 'FILES') body.append(scenarioFilesSection(bundleKey));
    if (section === 'INPUTS') body.append(scenarioInputsSection(bundleKey));
  } else {
    body.append(text('p', 'This bundle is defunct or missing a canonical scenario ID, so bundle drill-down actions are unavailable.', 'muted callout'));
  }
  body.append(technicalDetails(scenario));
  details.append(body);
  return details;
}

function scenarioSectionButton(
  label: string,
  section: ScenarioSection,
  scenarioId: string,
  bundleKey: string,
  activeSection: ScenarioSection | undefined,
  rowId: string,
): HTMLButtonElement {
  const control = button(label, () => {
    expandedScenarioId = rowId;
    send({ type: 'selectScenarioSection', scenarioId, bundleKey, section });
  }, `secondary compact${activeSection === section ? ' active-chip' : ''}`);
  control.setAttribute('aria-pressed', String(activeSection === section));
  return control;
}

function scenarioOverviewSection(scenario: Model): HTMLElement {
  const cards = el('div', 'scenario-detail-grid');
  if (stringField(scenario, 'description')) {
    cards.append(scenarioInfoCard('Description', String(scenario.description)));
  }
  if (stringField(scenario, 'controllerImage')) {
    cards.append(scenarioInfoCard('Controller', String(scenario.controllerImage), 'mono'));
  }
  const bees = Array.isArray(scenario.bees) ? scenario.bees as Model[] : [];
  if (bees.length > 0) {
    cards.append(el('article', 'card scenario-info-card', [
      text('span', 'Bees', 'eyebrow'),
      el('div', 'scenario-bees', bees.map(bee => text(
        'span',
        [stringField(bee, 'role') ?? 'worker', stringField(bee, 'image')].filter(Boolean).join(' · '),
        'scenario-chip',
      ))),
    ]));
  }
  return cards.children.length > 0 ? cards : emptyState('No additional overview metadata was reported for this bundle.');
}

function scenarioInfoCard(label: string, value: string, extraClass = ''): HTMLElement {
  return el('article', `card scenario-info-card${extraClass ? ` ${extraClass}` : ''}`, [
    text('span', label, 'eyebrow'),
    titled('p', value, `${extraClass} truncate`),
  ]);
}

function scenarioFilesSection(bundleKey: string): HTMLElement {
  if (model.scenarioFocusBundleKey !== bundleKey || model.scenarioFocusTree === undefined) {
    return emptyState(model.busy ? 'Loading deployed bundle tree…' : 'Choose Files to inspect the deployed bundle tree.');
  }
  const tree = objectValue(model.scenarioFocusTree);
  if (errorFrom(model.scenarioFocusTree)) return errorState(String(errorFrom(model.scenarioFocusTree)));
  const nodes = Array.isArray(tree?.nodes) ? tree.nodes.filter(item => objectValue(item)) as Model[] : undefined;
  if (!nodes) return ownerDataError(model.scenarioFocusTree, 'scenario bundle tree');
  if (nodes.length === 0) return emptyState('No deployed files were reported for this bundle.');
  const list = el('div', 'scenario-tree');
  for (const node of nodes) list.append(scenarioFileNode(bundleKey, node));
  return list;
}

function scenarioFileNode(bundleKey: string, node: Model): HTMLElement {
  const path = stringField(node, 'path');
  const name = stringField(node, 'name');
  const nodeType = stringField(node, 'nodeType');
  if (!path || !name || !nodeType) return ownerDataError(node, 'bundle tree node');
  const depth = Math.min(path.split('/').length - 1, 6);
  const row = el('article', `scenario-tree__row depth-${depth}`);
  const meta = el('div', 'scenario-tree__meta', [
    text('span', nodeType === 'directory' ? 'Dir' : 'File', `scenario-chip ${nodeType === 'directory' ? 'scenario-chip--dir' : ''}`),
    titled('strong', name, 'truncate'),
    titled('span', path, 'mono muted truncate'),
  ]);
  row.append(meta);
  if (nodeType === 'file') {
    const editorKind = stringField(node, 'editorKind');
    const label = editorKind === 'unsupported' ? 'Metadata' : 'Preview';
    row.append(el('div', 'scenario-tree__actions', [
      text('span', displayValue(node.size), 'muted mono'),
      button(label, () => send({ type: 'openScenarioBundleFile', bundleKey, path }), 'secondary compact'),
    ]));
  }
  return row;
}

function scenarioInputsSection(bundleKey: string): HTMLElement {
  if (model.scenarioFocusBundleKey !== bundleKey || model.scenarioFocusInputs === undefined) {
    return emptyState(model.busy ? 'Loading scenario inputs…' : 'Choose Inputs to inspect SUTs and supporting files.');
  }
  const inputs = objectValue(model.scenarioFocusInputs);
  if (errorFrom(model.scenarioFocusInputs)) return errorState(String(errorFrom(model.scenarioFocusInputs)));
  if (!inputs) return ownerDataError(model.scenarioFocusInputs, 'scenario inputs');
  const result = el('div', 'scenario-inputs');
  result.append(el('div', 'scenario-detail-grid', [
    scenarioFilePresenceCard('Variables', inputs.variablesPath, bundleKey),
    scenarioFilePresenceCard('Auth profiles', inputs.authProfilesPath, bundleKey),
  ]));
  const suts = Array.isArray(inputs.suts) ? inputs.suts.filter(item => objectValue(item)) as Model[] : [];
  if (suts.length === 0) {
    result.append(emptyState('No bundle-local SUT descriptors were reported for this bundle.'));
    return result;
  }
  const list = el('div', 'data-list');
  for (const sut of suts) list.append(scenarioSutCard(sut));
  result.append(list);
  return result;
}

function scenarioFilePresenceCard(label: string, path: unknown, bundleKey: string): HTMLElement {
  const exactPath = typeof path === 'string' && path.trim() ? path.trim() : undefined;
  const card = el('article', 'card scenario-info-card', [
    text('span', label, 'eyebrow'),
    titled('p', exactPath ?? 'Not present in deployed bundle', `${exactPath ? 'mono truncate' : 'muted'}`),
  ]);
  if (exactPath) {
    card.append(el('div', 'actions', [
      button('Preview', () => send({ type: 'openScenarioBundleFile', bundleKey, path: exactPath }), 'secondary compact'),
    ]));
  }
  return card;
}

function scenarioSutCard(value: Model): HTMLElement {
  const sutId = stringField(value, 'sutId') ?? 'SUT';
  const error = errorFrom(value.error);
  const card = el('article', 'card data-card');
  card.append(el('div', 'data-heading', [
    el('div', '', [text('span', 'Bundle-local SUT', 'eyebrow'), titled('h4', sutId, 'truncate')]),
  ]));
  if (error) {
    card.append(errorState(error));
    return card;
  }
  const descriptor = objectValue(value.descriptor);
  if (!descriptor) return ownerDataError(value, 'bundle-local SUT');
  const endpoints = objectValue(descriptor.endpoints) ?? {};
  const endpointRows = Object.entries(endpoints).map(([endpointId, endpointValue]) => {
    const endpoint = objectValue(endpointValue) ?? {};
    return [
      endpointId,
      typeof endpoint.baseUrl === 'string' && endpoint.baseUrl.trim()
        ? endpoint.baseUrl.trim()
        : JSON.stringify(endpoint),
    ] as string[];
  });
  card.append(dataRows([
    ['Name', displayValue(descriptor.name)],
    ['Endpoint count', String(endpointRows.length)],
  ]));
  if (endpointRows.length > 0) {
    card.append(el('div', 'scenario-endpoints', [
      text('span', 'Endpoints', 'eyebrow'),
      dataRows(endpointRows),
    ]));
  }
  return card;
}

function scenarioRowId(scenarioId: string | undefined, bundleKey: string): string {
  return `${scenarioId ?? 'bundle'}::${bundleKey}`;
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
  const criteria = eventCriteria[context];
  const search = input('Search events', `${context.toLowerCase()}Search`, criteria.search, 'Swarm, type, origin…');
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
  const filters = el('div', 'filter-bar event-filters', [search.wrapper, time.wrapper, kind.wrapper, severity.wrapper]);
  const count = text('p', '', 'result-count muted');
  const list = el('div', 'event-list');
  const apply = () => {
    const next: WebviewEventFilterCriteria = {
      search: search.control.value,
      timeWindow: time.control.value,
      kind: kind.control.value,
      severity: severity.control.value,
    };
    eventCriteria[context] = next;
    const filtered = applyEventFilters(events, next);
    count.textContent = `${filtered.length} of ${events.length} captured events`;
    list.replaceChildren(...filtered.map(eventRow));
    if (filtered.length === 0) list.append(emptyState('No captured events match these filters.'));
  };
  for (const control of [time.control, kind.control, severity.control]) control.addEventListener('change', apply);
  search.control.addEventListener('input', apply);
  result.append(filters, count, list);
  apply();
  return result;
}

function eventRow(event: Model): HTMLElement {
    const kind = stringField(event, 'kind');
    const timestamp = stringField(event, 'timestamp');
    if (!kind || !timestamp) {
      return ownerDataError(event, 'event record');
    }
    const severity = stringField(event, 'severity') ?? 'UNKNOWN';
    const type = stringField(event, 'type') ?? 'untyped';
    const swarmId = stringField(event, 'swarmId');
    const details = el('details', 'event-row');
    details.append(el('summary', '', [
      titled('time', compactTime(timestamp), 'event-row__time mono'),
      statusPill(severity),
      titled('strong', `${kind}/${type}`, 'event-row__type truncate'),
      titled('span', swarmId ?? displayValue(event.origin), 'event-row__scope muted truncate'),
    ]));
    const actions = el('div', 'event-row__actions');
    if (swarmId) actions.append(button('Open Debug', () => send({ type: 'openDebugForSwarm', swarmId }), 'secondary compact'));
    details.append(el('div', 'event-row__body', [
      dataRows([
        ['Observed', timestamp],
        ['Swarm', swarmId ?? 'Not reported'],
        ['Origin', displayValue(event.origin)],
        ['Direction', displayValue(event.direction)],
        ['Routing', displayValue(event.routingKey)],
      ]),
      actions,
      technicalDetails(event),
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

function technicalDetails(value: unknown): HTMLElement {
  const details = el('details', 'technical-details');
  details.append(text('summary', 'Technical details'));
  const pre = document.createElement('pre');
  pre.tabIndex = 0;
  pre.textContent = JSON.stringify(value, null, 2);
  details.append(pre);
  return details;
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

function debugView(): HTMLElement {
  const result = el('div', 'debug');
  const swarms = (topLevelRecords(model.workspaceData) ?? [])
    .map(item => stringField(item, 'id')).filter((id): id is string => Boolean(id));
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
  const groups = [
    { name: 'Runtime', labels: ['Workers', 'Logs', 'Versions', 'Inspect', 'Runtime drift'] },
    { name: 'Messaging', labels: ['Control plane', 'Rabbit topology', 'Timeline'] },
    { name: 'Definition', labels: ['Manifest'] },
    { name: 'Maintenance', labels: ['Cleanup plan'] },
  ];
  const actions = el('div', 'debug-groups');
  for (const group of groups) {
    const details = el('details', 'debug-group');
    if (group.name === 'Runtime') details.setAttribute('open', '');
    const configured = (model.debugActions ?? []).filter((action: Model) => group.labels.includes(String(action.label)));
    details.append(el('summary', '', [text('strong', group.name), text('span', `${configured.length} actions`, 'muted')]));
    const controls = el('div', 'debug-actions');
    for (const action of configured) {
      const needsWorker = Boolean(action.needsWorker);
      const control = button(String(action.label), () => {
        const message: Model = { type: 'runDebug', action: String(action.label) };
        if (action.label === 'Logs') message.tailLines = 200;
        send(message);
      }, action.label === 'Cleanup plan' ? 'guarded' : 'secondary');
      control.disabled = model.busy || !model.debugSwarmId || (needsWorker && !model.debugRuntimeId);
      controls.append(control);
    }
    details.append(controls);
    actions.append(details);
  }
  result.append(text('h4', 'Diagnostic actions'), actions);
  if (model.debugResult !== undefined) {
    result.append(el('section', 'debug-output', [
      el('div', 'debug-output__heading', [text('h4', 'Result'), text('span', 'Bounded MCP evidence', 'muted')]),
      resultCard(model.debugResult),
    ]));
  }
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
  control.value = value;
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
    Scenarios: 'Deployed bundle overview, files, and inputs',
    Debug: 'Bounded Orchestrator diagnostics',
  };
  return descriptions[tab] ?? '';
}

function statusAnnouncement(): string {
  return model.busy ? 'PocketHive operation in progress' : String(model.attempt?.state ?? model.activeProfile?.status ?? 'Ready');
}

function reconcileCreateSwarmDraft(nextModel: Model): void {
  const formValue = objectValue(nextModel.createSwarmForm);
  if (!formValue) {
    createSwarmDraft = undefined;
    return;
  }
  const templates = createSwarmOptions(formValue.templates) ?? [];
  const fallback = templates[0];
  const templateId = createSwarmDraft?.templateId
    && templates.some(option => option.templateId === createSwarmDraft?.templateId)
    ? createSwarmDraft.templateId
    : stringField(formValue, 'selectedTemplateId') ?? fallback?.templateId ?? '';
  const scenarioId = createSwarmDraft?.scenarioId
    && templates.some(option => option.scenarioId === createSwarmDraft?.scenarioId)
    ? createSwarmDraft.scenarioId
    : stringField(formValue, 'selectedScenarioId')
      ?? templates.find(option => option.templateId === templateId)?.scenarioId
      ?? fallback?.scenarioId ?? '';
  const sutIds = stringList(formValue.sutIds);
  const sutId = createSwarmDraft?.sutId && sutIds.includes(createSwarmDraft.sutId) ? createSwarmDraft.sutId : '';
  createSwarmDraft = {
    swarmId: createSwarmDraft?.swarmId ?? '',
    templateId,
    scenarioId,
    sutId,
    variablesProfileId: createSwarmDraft?.variablesProfileId ?? '',
  };
}

function ensureCreateSwarmDraft(): NonNullable<typeof createSwarmDraft> {
  if (!createSwarmDraft) {
    createSwarmDraft = {
      swarmId: '',
      templateId: '',
      scenarioId: '',
      sutId: '',
      variablesProfileId: '',
    };
  }
  return createSwarmDraft;
}

function createSwarmOptions(value: unknown): Array<{ templateId: string; scenarioId: string; name: string }> | undefined {
  if (errorFrom(value)) return undefined;
  if (!Array.isArray(value)) return undefined;
  const result: Array<{ templateId: string; scenarioId: string; name: string }> = [];
  for (const item of value) {
    const record = objectValue(item);
    const templateId = record ? stringField(record, 'id') : undefined;
    if (!record || !templateId || record.defunct === true) continue;
    result.push({
      templateId,
      scenarioId: templateId,
      name: stringField(record, 'name') ?? templateId,
    });
  }
  return result;
}

function swarmStatus(value: unknown): string {
  const swarm = objectValue(value);
  if (!swarm) return 'UNKNOWN';
  const runtimeResourceState = stringField(swarm, 'runtimeResourceState');
  if (runtimeResourceState === 'REMOVING') return 'REMOVING';
  const controllerState = stringField(swarm, 'controllerState');
  const workloadState = stringField(swarm, 'workloadState');
  if (controllerState === 'PROVISIONING' || controllerState === 'FAILED') return controllerState;
  if (workloadState === 'RUNNING'
      || workloadState === 'STARTING'
      || workloadState === 'STOPPING'
      || workloadState === 'UNAVAILABLE') return workloadState;
  if (workloadState === 'STOPPED') return controllerState === 'READY' ? 'READY' : 'STOPPED';
  return controllerState ?? workloadState ?? 'UNKNOWN';
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

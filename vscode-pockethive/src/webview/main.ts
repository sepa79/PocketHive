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
const TAB_ICONS: Readonly<Record<(typeof TABS)[number], string>> = Object.freeze({
  Hive: 'server-environment',
  Buzz: 'broadcast',
  Journal: 'book',
  Scenarios: 'folder-library',
  Debug: 'debug',
});
const DEBUG_ACTION_PRESENTATION = Object.freeze([
  { label: 'Workers', icon: 'organization', context: 'SWARM' },
  { label: 'Logs', icon: 'output', context: 'WORKER', tailLines: 200 },
  { label: 'Inspect', icon: 'inspect', context: 'WORKER' },
  { label: 'Version', icon: 'versions', context: 'WORKER' },
  { label: 'Runtime drift', icon: 'pulse', context: 'SWARM' },
  { label: 'Control plane', icon: 'radio-tower', context: 'SWARM' },
  { label: 'Rabbit topology', icon: 'type-hierarchy', context: 'SWARM' },
  { label: 'Timeline', icon: 'history', context: 'SWARM' },
  { label: 'Manifest', icon: 'file-code', context: 'SWARM' },
  { label: 'Cleanup plan', icon: 'trash', context: 'MAINTENANCE' },
] as const);
const ENVIRONMENT_SERVICE_ICONS: Readonly<Record<string, string>> = Object.freeze({
  'pockethive-ui': 'home',
  orchestrator: 'server-process',
  'scenario-manager': 'folder-library',
  'network-proxy-manager': 'globe',
  wiremock: 'beaker',
  'tcp-mock': 'plug',
  grafana: 'graph-line',
});
type DebugContext = 'WORKER' | 'SWARM';
type ScenarioSection = 'OVERVIEW' | 'FILES' | 'INPUTS';
type ScenarioSource = 'DEPLOYED' | 'REPOSITORY';
type ScenarioTreeNodeType = 'directory' | 'file';
interface ScenarioTreeEntry {
  readonly node: Model;
  readonly path: string;
  readonly name: string;
  readonly nodeType: ScenarioTreeNodeType;
  readonly children: ScenarioTreeEntry[];
}
let model: Model = { page: 'environments', profiles: [], activeTab: 'Hive', debugActions: [], busy: false };
let expandedHistorySwarmId: string | undefined;
let expandedScenarioId: string | undefined;
let swarmSearch = '';
let scenarioSearch = '';
let scenarioFolder = 'ALL';
let scenarioSource: ScenarioSource = 'DEPLOYED';
let expandedRepositoryCandidateId: string | null = null;
let repositoryScenarioSection: ScenarioSection = 'FILES';
let repositorySearch = '';
let repositoryWorkspace = 'ALL';
let debugContext: DebugContext = 'WORKER';
const disclosureState = new Map<string, boolean>();
let environmentHealthExpanded = false;
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
    const nextModel = value.model as Model;
    const preserveInteractionState = samePresentationContext(model, nextModel);
    if (!preserveInteractionState) {
      disclosureState.clear();
      environmentHealthExpanded = false;
    }
    reconcileCreateSwarmDraft(nextModel);
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
  else app.append(environments());
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

function environments(): HTMLElement {
  const section = el('section', 'page environments-page');
  const titleRow = el('div', 'title-row', [
    el('div', '', [text('h1', 'Environments'), text('p', 'Connect to a PocketHive MCP environment.')]),
    iconButton('Add', 'add', () => send({ type: 'addEnvironment' }), 'primary compact'),
  ]);
  section.append(titleRow);
  const profiles = Array.isArray(model.profiles) ? model.profiles : [];
  if (profiles.length === 0 && model.page !== 'add') {
    section.append(el('div', 'empty card', [
      text('h2', 'No environments yet'),
      text('p', 'Add the MCP URL supplied by your PocketHive administrator.'),
      button('Add environment', () => send({ type: 'addEnvironment' }), 'primary'),
    ]));
  } else {
    if (profiles.length > 0) {
      section.append(text('p', `${profiles.length} ${profiles.length === 1 ? 'environment' : 'environments'}`, 'environment-count muted'));
    }
    const list = el('div', 'card-list');
    for (const profile of profiles) list.append(environmentCard(profile));
    section.append(list);
  }
  if (model.page === 'add') section.append(connectionForm());
  section.append(iconText('shield', 'Credentials are stored securely by VS Code.', 'environment-security muted'));
  return section;
}

function environmentCard(profile: Model): HTMLElement {
  const status = String(profile.status ?? 'Not connected');
  const card = el('article', 'card environment-card');
  card.append(
    el('div', 'environment-card__main', [
      brandMark('environment-mark'),
      el('div', 'environment-card__copy', [
        el('div', 'environment-card__head', [
          titled('h2', String(profile.displayName ?? 'Environment'), 'truncate'),
          statusPill(status),
        ]),
        titled('p', String(profile.mcpUrl ?? ''), 'mono truncate'),
        profile.principalLabel
          ? iconText('account', `Signed in as ${String(profile.principalLabel)}`, 'muted environment-card__meta')
          : undefined,
      ]),
    ]),
  );
  const menu = el('details', 'row-menu environment-card__menu');
  menu.append(iconSummary('More environment actions', 'ellipsis'), el('div', 'row-menu__panel', [
    iconButton('Remove environment', 'trash', () =>
      send({ type: 'removeEnvironment', profileId: String(profile.id) }), 'guarded compact'),
  ]));
  card.append(el('div', 'environment-card__actions', [
    iconButton('Open', 'arrow-right', () =>
      send({ type: 'openEnvironment', profileId: String(profile.id) }), 'secondary compact'),
    menu,
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
  form.append(text('h2', draft.id ? 'Connection' : 'Add environment'), stages, name.wrapper, url.wrapper, mode.wrapper);
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
  section.append(text('h1', String(profile.displayName ?? 'PocketHive environment'), 'sr-only'));
  section.append(iconButton('Environments', 'arrow-left', () =>
    send({ type: 'backToEnvironments' }), 'back-link compact'));
  if (!session.canUseWorkspace) section.append(sessionNotice(session));
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
  if (activeTab === 'Debug') content.append(debugView());
  else if (activeTab === 'Journal') content.append(journalView());
  else if (activeTab === 'Scenarios') content.append(scenariosView());
  else if (activeTab === 'Hive') {
    if (model.createSwarmForm !== undefined) content.append(createSwarmView(model.createSwarmForm));
    content.append(swarmListView(model.workspaceData));
  }
  else content.append(eventListView(model.workspaceData, 'No hive events were observed.', 'Buzz'));
  section.append(content, environmentHealth(profile, session));
  return section;
}

function accountMenu(profile: Model, session: Model): HTMLElement {
  const details = refreshStableDetails('workspace:account', 'account-menu');
  const summary = document.createElement('summary');
  summary.setAttribute('aria-label', 'Account');
  summary.title = 'Account';
  summary.append(icon('account', 'account-menu__avatar'));
  details.append(summary);
  const panel = el('div', 'account-menu__panel', [
    text('strong', profile.principalLabel ? String(profile.principalLabel) : 'PocketHive user'),
    text('span', session.canUseWorkspace
      ? `Signed in to ${String(profile.displayName ?? 'PocketHive')}`
      : String(session.message ?? 'Secure session unavailable'), 'muted'),
  ]);
  const actions = el('div', 'account-menu__actions');
  if (session.canSignIn) {
    actions.append(iconButton('Sign in', 'sign-in', () =>
      send({ type: 'reauthorizeEnvironment' }), 'primary compact'));
  }
  if (!session.canUseWorkspace && !session.canSignIn && session.status !== 'Connecting') {
    actions.append(iconButton('Retry connection', 'refresh', () =>
      send({ type: 'refresh' }), 'secondary compact'));
  }
  if (session.canSignOut) {
    actions.append(iconButton('Sign out', 'sign-out', () => send({ type: 'signOut' }), 'quiet compact'));
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

function environmentHealth(profile: Model, session: Model): HTMLElement {
  const connected = session.canUseWorkspace === true && session.status === 'Connected';
  const services = environmentHealthRows(model.environmentHealth);
  const unavailable = services.filter(service => service.status === 'UNAVAILABLE').length;
  const status = connected
    ? services.length === 0
      ? 'Health unavailable'
      : unavailable === 0
        ? `${services.length} services healthy`
        : `${unavailable} service${unavailable === 1 ? '' : 's'} unavailable`
    : String(session.status ?? profile.status ?? 'Not connected');
  const footer = el('footer', `environment-health${connected ? ' environment-health--connected' : ''}${environmentHealthExpanded ? ' environment-health--open' : ''}`);
  const panel = el('section', 'environment-health__panel');
  panel.hidden = !environmentHealthExpanded;
  panel.setAttribute('aria-label', 'Environment services');
  if (services.length === 0) {
    panel.append(el('div', 'environment-health__empty', [
      icon('warning'),
      text('span', 'Service health is unavailable from PocketHive MCP.'),
    ]));
  } else {
    for (const service of services) panel.append(environmentServiceRow(service));
  }
  const toggle = document.createElement('button');
  toggle.type = 'button';
  toggle.className = 'environment-health__toggle';
  toggle.setAttribute('aria-expanded', String(environmentHealthExpanded));
  toggle.setAttribute('aria-label', `Environment health: ${status}`);
  toggle.append(
    brandMark('environment-health__mark'),
    el('span', 'environment-health__copy', [
      text('strong', String(profile.displayName ?? 'Environment'), 'truncate'),
      text('span', status, 'environment-health__state truncate'),
    ]),
    icon('chevron-up', 'environment-health__chevron'),
  );
  toggle.addEventListener('click', () => {
    environmentHealthExpanded = !environmentHealthExpanded;
    toggle.setAttribute('aria-expanded', String(environmentHealthExpanded));
    panel.hidden = !environmentHealthExpanded;
    footer.classList.toggle('environment-health--open', environmentHealthExpanded);
  });
  footer.append(panel, el('div', 'environment-health__rail', [toggle, accountMenu(profile, session)]));
  return footer;
}

interface EnvironmentHealthRow {
  readonly id: string;
  readonly name: string;
  readonly endpoint: string;
  readonly status: 'HEALTHY' | 'UNAVAILABLE';
}

function environmentHealthRows(value: unknown): EnvironmentHealthRow[] {
  const health = objectValue(value);
  if (!health || !Array.isArray(health.services)) return [];
  const result: EnvironmentHealthRow[] = [];
  for (const item of health.services.slice(0, 50)) {
    const service = objectValue(item);
    if (!service) continue;
    const id = stringField(service, 'id');
    const name = stringField(service, 'name');
    const endpoint = stringField(service, 'endpoint');
    const status = stringField(service, 'status');
    if (id && name && endpoint && (status === 'HEALTHY' || status === 'UNAVAILABLE')) {
      result.push({ id, name, endpoint, status });
    }
  }
  return result;
}

function environmentServiceRow(service: EnvironmentHealthRow): HTMLElement {
  const healthy = service.status === 'HEALTHY';
  const endpoint = titled('span', service.endpoint, 'environment-service__endpoint');
  return el('div', `environment-service environment-service--${healthy ? 'healthy' : 'unavailable'}`, [
    icon(ENVIRONMENT_SERVICE_ICONS[service.id] ?? 'globe', 'environment-service__icon'),
    el('div', 'environment-service__copy', [text('strong', service.name), endpoint]),
    el('span', 'environment-service__status', [
      icon(healthy ? 'check' : 'error'),
      text('span', healthy ? 'Healthy' : 'Unavailable'),
    ]),
  ]);
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

function scenarioBundleView(): HTMLElement {
  const pending = model.pendingBundle;
  const attemptId = publicationAttemptId(model.bundleResult);
  const result = el('section', `scenario-upload${pending ? ' card' : ''}`);
  if (!pending) {
    const actions = el('div', 'form-actions', [
      iconButton('Choose committed folder', 'folder-opened', () =>
        send({ type: 'validateCommittedBundle' }), 'secondary'),
    ]);
    if (attemptId) {
      actions.append(iconButton('Reconcile attempt', 'sync', () =>
        send({ type: 'reconcilePublicationAttempt', attemptId }), 'guarded'));
    }
    result.append(actions);
  } else {
    result.append(
      text('h3', 'Committed bundle'),
      text('p', 'PocketHive validates the retained committed ZIP before explicit publication.', 'muted'),
    );
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

function scenariosView(): HTMLElement {
  const result = el('div', 'scenario-workspace');
  const deployedCount = topLevelRecords(model.workspaceData)?.length ?? 0;
  const repositoryCount = repositoryCandidateCount(model.repositoryScenarios);
  const sourceSwitch = el('div', 'scenario-source-switch');
  sourceSwitch.setAttribute('role', 'group');
  sourceSwitch.setAttribute('aria-label', 'Scenario source');
  for (const source of ['DEPLOYED', 'REPOSITORY'] as const) {
    const label = source === 'DEPLOYED' ? 'Deployed' : 'Repository';
    const count = source === 'DEPLOYED' ? deployedCount : repositoryCount;
    const control = button(label, () => {
      scenarioSource = source;
      render();
    }, `scenario-source-switch__button${scenarioSource === source ? ' active' : ''}`);
    control.setAttribute('aria-pressed', String(scenarioSource === source));
    control.append(text('span', String(count), 'count-badge'));
    sourceSwitch.append(control);
  }
  result.append(sourceSwitch);
  if (scenarioSource === 'DEPLOYED') result.append(scenarioListView(model.workspaceData));
  else result.append(repositoryScenarioView());
  return result;
}

function repositoryScenarioView(): HTMLElement {
  const value = objectValue(model.repositoryScenarios);
  if (!value) {
    const state = model.busy
      ? emptyState('Scanning committed Scenario Bundles…')
      : ownerDataError(model.repositoryScenarios, 'repository scenarios');
    if (!model.repositoryResultCandidateId && publicationAttemptId(model.bundleResult)) {
      return el('section', 'repository-scenarios', [scenarioBundleView(), state]);
    }
    return state;
  }
  const state = stringField(value, 'state');
  if (state === 'NO_WORKSPACE') {
    return emptyState('Open a Git repository as a VS Code workspace to discover committed Scenario Bundles.');
  }
  if (state === 'UNTRUSTED') {
    return emptyState('Trust this workspace before PocketHive runs read-only Git discovery.');
  }
  if (state !== 'SCANNED') return ownerDataError(value, 'repository scenarios');

  const result = el('section', 'repository-scenarios');
  if (model.pendingBundle && !model.repositoryPendingCandidateId) {
    result.append(scenarioBundleView());
  } else if (!model.repositoryResultCandidateId && publicationAttemptId(model.bundleResult)) {
    result.append(scenarioBundleView());
  }
  result.append(el('div', 'repository-scenarios__notice', [
    el('div', 'repository-scenarios__notice-copy', [
      icon('git-commit'),
      text('p', 'Committed HEAD only. Edit, commit, then refresh before validation or deployment.', 'muted'),
    ]),
    iconButton('Choose committed folder', 'folder-opened', () =>
      send({ type: 'validateCommittedBundle' }), 'quiet compact icon-only-at-narrow'),
  ]));
  const repositories = Array.isArray(value.repositories) ? value.repositories as Model[] : [];
  const failures = Array.isArray(value.failures) ? value.failures as Model[] : [];
  if (repositories.length === 0 && failures.length === 0) {
    result.append(emptyState('No canonical scenarios/**/scenario.yaml bundles were found at HEAD.'));
  }
  const candidates: Array<{ repository: Model; candidate: Model }> = [];
  for (const repository of repositories) {
    if (!stringField(repository, 'workspaceName') || !stringField(repository, 'commit')
      || !Array.isArray(repository.candidates)) {
      result.append(ownerDataError(repository, 'Git repository'));
      continue;
    }
    for (const candidate of repository.candidates as Model[]) candidates.push({ repository, candidate });
  }
  const workspaceNames = [...new Set(candidates
    .map(item => stringField(item.repository, 'workspaceName'))
    .filter((name): name is string => Boolean(name)))].sort();
  if (repositoryWorkspace !== 'ALL' && !workspaceNames.includes(repositoryWorkspace)) {
    repositoryWorkspace = 'ALL';
  }
  const search = searchInput(
    'Search repository scenarios', 'repositorySearch', repositorySearch, 'Find a scenario', true,
  );
  search.control.required = false;
  const workspace = select('Workspace', 'repositoryWorkspace', [
    ['ALL', 'All workspaces'], ...workspaceNames.map(name => [name, name]),
  ], repositoryWorkspace);
  const advanced = refreshStableDetails(
    'scenarios:repository:filters',
    'advanced-filters repository-advanced-filters',
  );
  const advancedSummary = iconSummary('Repository filters', 'filter');
  const workspaceBadge = text('span', repositoryWorkspace === 'ALL' ? '' : '1', 'filter-count');
  workspaceBadge.hidden = repositoryWorkspace === 'ALL';
  advancedSummary.append(workspaceBadge);
  advanced.append(advancedSummary, el('div', 'advanced-filters__panel', [workspace.wrapper]));
  const filters = el('div', 'event-search repository-filters', [search.wrapper, advanced]);
  const list = el('div', 'repository-scenario-list');
  const availableIds = new Set(candidates.map(item => stringField(item.candidate, 'candidateId')).filter(Boolean));
  if (expandedRepositoryCandidateId && !availableIds.has(expandedRepositoryCandidateId)) {
    expandedRepositoryCandidateId = null;
  }
  const apply = () => {
    repositorySearch = search.control.value;
    repositoryWorkspace = workspace.control.value;
    workspaceBadge.textContent = repositoryWorkspace === 'ALL' ? '' : '1';
    workspaceBadge.hidden = repositoryWorkspace === 'ALL';
    const query = repositorySearch.trim().toLocaleLowerCase();
    const matches = candidates.filter(item => {
      const workspaceName = stringField(item.repository, 'workspaceName') ?? '';
      const bundlePath = stringField(item.candidate, 'bundlePath') ?? '';
      return (repositoryWorkspace === 'ALL' || workspaceName === repositoryWorkspace)
        && (!query || `${workspaceName} ${bundlePath}`.toLocaleLowerCase().includes(query));
    });
    const focusedVisible = matches.some(item => stringField(item.candidate, 'candidateId')
      === expandedRepositoryCandidateId);
    if (expandedRepositoryCandidateId !== null && !focusedVisible) {
      expandedRepositoryCandidateId = null;
    }
    list.replaceChildren(...matches.map(item => repositoryScenarioCard(item.repository, item.candidate)));
    if (matches.length === 0) list.append(emptyState('No Repository scenarios match these filters.'));
  };
  search.control.addEventListener('input', apply);
  workspace.control.addEventListener('change', apply);
  result.append(filters, list);
  apply();
  for (const failure of failures) {
    result.append(el('article', 'callout repository-scenarios__failure', [
      el('div', 'repository-scenarios__identity', [
        icon('warning'),
        titled('strong', displayValue(failure.workspaceName), 'truncate'),
      ]),
      text('p', displayValue(failure.code), 'muted mono'),
    ]));
  }
  const conflict = objectValue(model.repositoryDeploymentConflict);
  if (conflict) result.append(repositoryDeploymentDialog(conflict));
  return result;
}

function repositoryScenarioCard(repository: Model, candidate: Model): HTMLElement {
  const workspaceName = stringField(repository, 'workspaceName');
  const commit = stringField(repository, 'commit');
  const candidateId = stringField(candidate, 'candidateId');
  const bundlePath = stringField(candidate, 'bundlePath');
  const files = Array.isArray(candidate.files)
    ? candidate.files.filter((path): path is string => typeof path === 'string' && Boolean(path.trim()))
    : undefined;
  if (!workspaceName || !commit || !candidateId || !bundlePath || !files) {
    return ownerDataError(candidate, 'repository scenario candidate');
  }
  const pending = model.repositoryPendingCandidateId === candidateId ? objectValue(model.pendingBundle) : undefined;
  const receipt = objectValue(pending?.validationReceipt);
  const title = (receipt ? stringField(receipt, 'scenarioName') : undefined) ?? bundlePath.split('/').at(-1)!;
  const subtitle = (receipt ? stringField(receipt, 'scenarioId') : undefined) ?? bundlePath;
  const focused = expandedRepositoryCandidateId === candidateId;
  const details = el('details', 'scenario-row repository-scenario');
  if (focused) details.setAttribute('open', '');
  const summary = el('summary', '', [
    el('div', 'scenario-row__identity', [
      brandMark('scenario-mark'),
      el('div', 'scenario-row__copy', [
        titled('strong', title, 'truncate'),
        titled('span', subtitle, 'mono muted truncate'),
      ]),
    ]),
    el('div', 'scenario-row__status', [
      statusPill(receipt ? 'Valid' : 'Repository'),
      icon('chevron-right', 'disclosure-chevron'),
    ]),
  ]);
  summary.addEventListener('click', event => {
    event.preventDefault();
    expandedRepositoryCandidateId = focused ? null : candidateId;
    render();
  });
  details.append(summary);
  const body = el('div', 'scenario-row__body repository-scenario__body');
  const actions = el('div', 'repository-scenario__actions', [
    iconButton('Edit', 'edit', () => send({
      type: 'openRepositoryBundleFile', candidateId, path: 'scenario.yaml',
    }), 'quiet'),
    iconButton('Validate', 'pass-filled', () =>
      send({ type: 'validateRepositoryBundle', candidateId }), 'quiet'),
    iconButton('Deploy', 'cloud-upload', () =>
      send({ type: 'deployRepositoryBundle', candidateId }), 'quiet'),
  ]);
  for (const control of Array.from(actions.children) as HTMLButtonElement[]) {
    control.disabled = Boolean(model.busy);
  }
  body.append(actions, el('div', 'compact-tabs scenario-section-tabs repository-scenario__tabs', [
    repositorySectionButton('Overview', 'OVERVIEW'),
    repositorySectionButton('Files', 'FILES'),
    repositorySectionButton('Inputs', 'INPUTS'),
  ]));
  if (repositoryScenarioSection === 'OVERVIEW') {
    body.append(repositoryOverview(repository, bundlePath, receipt));
  } else if (repositoryScenarioSection === 'FILES') {
    body.append(repositoryFiles(candidateId, files));
  } else {
    body.append(repositoryInputs(candidateId, files));
  }
  if (receipt) body.append(repositoryValidation(receipt, files.length));
  details.append(body);
  return details;
}

function repositorySectionButton(label: string, section: ScenarioSection): HTMLButtonElement {
  const control = button(label, () => {
    repositoryScenarioSection = section;
    render();
  }, 'compact-tab scenario-section-tab');
  control.append(icon(section === 'OVERVIEW' ? 'preview' : section === 'FILES' ? 'list-tree' : 'symbol-variable'));
  control.setAttribute('aria-pressed', String(repositoryScenarioSection === section));
  return control;
}

function repositoryOverview(repository: Model, bundlePath: string, receipt: Model | undefined): HTMLElement {
  const overview = el('div', 'scenario-detail-grid scenario-overview');
  overview.append(
    scenarioInfoCard('Scenario', receipt
      ? `${displayValue(receipt.scenarioName)} · ${displayValue(receipt.scenarioId)}`
      : 'Validate to load the exact scenario.yaml identity.', '', 'scenario-info-card--full'),
    scenarioInfoCard('Source', bundlePath, 'mono', 'scenario-info-card--full'),
    scenarioInfoCard('Commit', `${displayValue(repository.workspaceName)} · ${shortHash(displayValue(repository.commit))}`,
      'mono', 'scenario-info-card--full'),
  );
  return overview;
}

function repositoryFiles(candidateId: string, files: readonly string[]): HTMLElement {
  const hierarchy = scenarioTreeHierarchy(repositoryTreeNodes(files));
  if (!hierarchy) return ownerDataError(files, 'repository scenario file hierarchy');
  const tree = el('div', 'scenario-tree repository-scenario__tree');
  for (const entry of hierarchy) tree.append(repositoryFileNode(candidateId, entry));
  return tree;
}

function repositoryTreeNodes(files: readonly string[]): Model[] {
  const directories = new Set<string>();
  for (const path of files) {
    const segments = path.split('/');
    for (let index = 1; index < segments.length; index++) {
      directories.add(segments.slice(0, index).join('/'));
    }
  }
  return [
    ...[...directories].sort().map(path => ({
      path, name: path.split('/').at(-1), nodeType: 'directory',
    })),
    ...files.map(path => ({ path, name: path.split('/').at(-1), nodeType: 'file' })),
  ];
}

function repositoryFileNode(candidateId: string, entry: ScenarioTreeEntry): HTMLElement {
  if (entry.nodeType === 'directory') {
    const branch = refreshStableDetails(
      `scenarios:repository:${candidateId}:directory:${entry.path}`,
      'scenario-tree__branch',
      true,
    );
    branch.append(el('summary', 'scenario-tree__row scenario-tree__row--directory', [
      icon('chevron-right', 'scenario-tree__twistie'),
      icon('folder', 'scenario-tree__icon'),
      titled('strong', entry.name, 'truncate'),
    ]));
    const children = el('div', 'scenario-tree__children');
    for (const child of entry.children) children.append(repositoryFileNode(candidateId, child));
    branch.append(children);
    return branch;
  }
  return el('article', 'scenario-tree__row scenario-tree__row--file', [
    el('div', 'scenario-tree__meta', [
      icon('file-code', 'scenario-tree__icon'),
      titled('strong', entry.name, 'truncate'),
    ]),
    el('div', 'scenario-tree__actions', [
      iconButton('Edit', 'edit', () => send({
        type: 'openRepositoryBundleFile', candidateId, path: entry.path,
      }), 'secondary compact'),
    ]),
  ]);
}

function repositoryInputs(candidateId: string, files: readonly string[]): HTMLElement {
  const inputPaths = files.filter(path => path === 'variables.yaml' || path === 'authProfiles.yaml'
    || /^sut\/[^/]+\/sut\.yaml$/.test(path));
  if (inputPaths.length === 0) return emptyState('No variables, auth profiles, or SUT descriptors are committed.');
  return el('div', 'repository-scenario__inputs', inputPaths.map(path => el('div', 'repository-scenario__input', [
    el('div', 'repository-scenarios__identity', [icon('symbol-variable'), titled('span', path, 'mono truncate')]),
    iconButton('Edit', 'edit', () => send({
      type: 'openRepositoryBundleFile', candidateId, path,
    }), 'secondary compact'),
  ])));
}

function repositoryValidation(receipt: Model, fileCount: number): HTMLElement {
  return el('div', 'repository-scenario__validation', [
    icon('pass-filled'),
    text('strong', 'Valid'),
    text('span', `${fileCount} ${fileCount === 1 ? 'file' : 'files'} checked`, 'muted'),
    titled('span', displayValue(receipt.scenarioId), 'mono truncate'),
  ]);
}

function repositoryDeploymentDialog(conflict: Model): HTMLElement {
  const candidateId = stringField(conflict, 'candidateId');
  const scenarioId = stringField(conflict, 'scenarioId');
  const scenarioName = stringField(conflict, 'scenarioName');
  const suggestedId = stringField(conflict, 'suggestedScenarioId');
  const suggestedName = stringField(conflict, 'suggestedScenarioName');
  if (!candidateId || !scenarioId || !scenarioName || !suggestedId || !suggestedName) {
    return ownerDataError(conflict, 'repository deployment conflict');
  }
  const renameId = input('New scenario ID', 'repositoryRenameScenarioId', suggestedId, suggestedId);
  const renameName = input('New scenario name', 'repositoryRenameScenarioName', suggestedName, suggestedName);
  const dialog = el('section', 'repository-deployment-dialog', [
    el('div', 'repository-deployment-dialog__panel', [
      el('div', 'repository-deployment-dialog__heading', [
        icon('warning'),
        el('div', '', [text('h2', 'Scenario already deployed'),
          text('p', `${scenarioName} (${scenarioId}) already exists. Choose one explicit path.`, 'muted')]),
      ]),
      el('div', 'repository-deployment-dialog__choice', [
        text('h3', 'Replace existing'),
        text('p', 'Publish the exact validated committed bytes over the existing scenario.', 'muted'),
        iconButton('Replace existing', 'replace-all', () =>
          send({ type: 'replaceRepositoryBundle', candidateId }), 'guarded'),
      ]),
      el('div', 'repository-deployment-dialog__choice', [
        text('h3', 'Rename source'),
        text('p', 'PocketHive opens local scenario.yaml. Apply these values, commit, refresh, validate, and deploy again.', 'muted'),
        renameId.wrapper,
        renameName.wrapper,
        iconButton('Open scenario.yaml', 'go-to-file', () => send({
          type: 'openRepositoryRename', candidateId,
          scenarioId: renameId.control.value,
          scenarioName: renameName.control.value,
        }), 'primary'),
      ]),
      button('Cancel', () => send({ type: 'discardPendingBundle' }), 'quiet'),
    ]),
  ]);
  dialog.setAttribute('role', 'dialog');
  dialog.setAttribute('aria-modal', 'true');
  dialog.setAttribute('aria-label', 'Scenario deployment conflict');
  return dialog;
}

function repositoryCandidateCount(value: unknown): number {
  const repositoryView = objectValue(value);
  if (!repositoryView || !Array.isArray(repositoryView.repositories)) return 0;
  return (repositoryView.repositories as Model[]).reduce((count, repository) =>
    count + (Array.isArray(repository.candidates) ? repository.candidates.length : 0), 0);
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

function swarmListView(value: unknown): HTMLElement {
  const swarms = topLevelRecords(value);
  if (swarms === undefined) return ownerDataError(value, 'swarm list');
  if (swarms.length === 0) return emptyState('No live swarms are currently visible.');
  const result = el('section', 'swarm-catalogue');
  const search = searchInput('Search swarms', 'swarmSearch', swarmSearch, 'Find a swarm', true);
  search.control.required = false;
  const list = el('div', 'swarm-list');
  if (model.swarmOperationResult !== undefined) {
    result.append(el('div', 'operation-result callout', [
      text('strong', 'Lifecycle request accepted'),
      text('span', 'The list below was refreshed from Orchestrator.', 'muted'),
      technicalDetails(model.swarmOperationResult),
    ]));
  }
  const renderMatches = () => {
    swarmSearch = search.control.value;
    const query = swarmSearch.trim().toLocaleLowerCase();
    const matches = swarms.filter(swarm => !query || [
      stringField(swarm, 'id'),
      stringField(swarm, 'templateId'),
    ].filter(Boolean).some(value => value?.toLocaleLowerCase().includes(query)));
    list.replaceChildren(...matches.map(swarmRowView));
    if (matches.length === 0) list.append(emptyState('No swarms match this search.'));
  };
  search.control.addEventListener('input', renderMatches);
  result.append(
    el('div', 'swarm-search', [search.wrapper]),
    swarmBatchActions(),
    list,
  );
  renderMatches();
  return result;
}

function swarmBatchActions(): HTMLElement {
  const actions = el('div', 'swarm-batch-actions');
  const available = Object.values(objectValue(model.swarmPrimaryActions) ?? {});
  if (available.includes(model.swarmOperations?.START)) {
    actions.append(workspaceActionIconButton('Start all', 'run-all', () =>
      send({ type: 'runSwarmBatchOperation', action: 'START' }), 'secondary compact'));
  }
  if (available.includes(model.swarmOperations?.STOP)) {
    actions.append(workspaceActionIconButton('Stop all', 'debug-stop', () =>
      send({ type: 'runSwarmBatchOperation', action: 'STOP' }), 'secondary compact'));
  }
  actions.append(workspaceActionIconButton(model.busy ? 'Loading…' : 'Refresh', 'refresh', () =>
    send({ type: 'refresh' }), 'secondary compact'));
  return actions;
}

function swarmRowView(swarm: Model): HTMLElement {
  const id = stringField(swarm, 'id');
  if (!id) return ownerDataError(swarm, 'swarm record');
  const status = swarmStatus(swarm);
  const bees = swarmBeeCount(swarm);
  const operation = model.swarmPrimaryActions?.[id];
  const card = el('article', `swarm-row swarm-row--${statusToken(status)}`);
  const identity = el('div', 'swarm-row__identity', [
    brandMark('swarm-mark'),
    el('div', 'swarm-row__copy', [
      el('div', 'swarm-row__heading', [
        titled('h3', id, 'truncate'),
        statusPill(status),
      ]),
      text('p', [displayValue(swarm.templateId), `${bees} ${bees === 1 ? 'bee' : 'bees'}`].join(' · '), 'muted truncate'),
    ]),
  ]);
  let primaryAction: HTMLButtonElement | undefined;
  if (operation) {
    const label = operation === model.swarmOperations?.START ? 'Start' : 'Stop';
    primaryAction = iconButton(label, label === 'Start' ? 'play' : 'debug-stop', () =>
      send({ type: 'runSwarmOperation', action: operation, swarmId: id }), 'secondary compact swarm-row__primary-action');
  }
  const secondaryActions = el('div', 'swarm-row__secondary');
  secondaryActions.append(iconButton('Debug', 'debug', () =>
    send({ type: 'openDebugForSwarm', swarmId: id }), 'quiet compact'));
  const openWeb = iconButton('Open in Web UI', 'link-external', () =>
    send({ type: 'openWebUi', destination: 'SWARM', swarmId: id }), 'quiet compact');
  openWeb.setAttribute('aria-label', 'View swarm in Web UI');
  secondaryActions.append(openWeb);
  const remove = iconButton('Remove', 'trash', () =>
    send({ type: 'runSwarmOperation', action: model.swarmOperations?.REMOVE, swarmId: id }), 'quiet compact');
  remove.setAttribute('aria-label', 'Remove swarm');
  const removeEligible = operation === model.swarmOperations?.START;
  remove.disabled = remove.disabled || !removeEligible;
  if (!removeEligible) remove.title = operation === model.swarmOperations?.STOP
    ? 'Stop the swarm before removing it.'
    : 'Remove is available only for a fresh ready stopped swarm.';
  secondaryActions.append(remove);
  card.append(el('div', 'swarm-row__main', [
    el('div', 'swarm-row__primary', [identity, primaryAction]),
    swarmWorkersView(swarm, id),
    secondaryActions,
  ]));

  const expanded = expandedHistorySwarmId === id;
  const history = iconButton(expanded ? 'Hide run history' : 'Run history', 'history', () => {
    if (expanded) {
      expandedHistorySwarmId = undefined;
      render();
    } else {
      expandedHistorySwarmId = id;
      send({ type: 'loadSwarmHistory', swarmId: id });
    }
  }, 'history-toggle quiet compact');
  history.setAttribute('aria-expanded', String(expanded));
  history.append(icon('chevron-right', 'history-toggle__chevron'));
  card.append(history);
  if (expanded) card.append(swarmRunHistory(id));
  return card;
}

function swarmWorkersView(swarm: Model, swarmId: string): HTMLElement {
  const workers = Array.isArray(swarm.bees) ? swarm.bees : [];
  const details = refreshStableDetails(`hive:workers:${swarmId}`, 'swarm-workers');
  const summary = el('summary', 'swarm-workers__summary', [
    icon('organization'),
    text('span', 'Workers'),
    text('span', String(workers.length), 'count-badge'),
    icon('chevron-right', 'swarm-workers__chevron'),
  ]);
  summary.setAttribute('aria-label', `Workers, ${workers.length}`);
  details.append(summary);
  const list = el('div', 'swarm-workers__list');
  if (workers.length === 0) {
    list.append(text('p', 'No worker summaries were reported.', 'muted swarm-workers__empty'));
  } else {
    for (const workerValue of workers.slice(0, 1000)) {
      const worker = objectValue(workerValue);
      const instance = worker && stringField(worker, 'instance');
      const role = worker && stringField(worker, 'role');
      if (!worker || !instance || !role) {
        list.append(ownerDataError(workerValue, 'worker summary'));
        continue;
      }
      list.append(el('article', 'swarm-worker', [
        icon('package', 'swarm-worker__icon'),
        el('div', 'swarm-worker__copy', [
          titled('strong', instance, 'truncate'),
          titled('span', workerRoleLabel(role), 'muted truncate'),
        ]),
        el('div', 'swarm-worker__actions', [
          iconButton('Inspect', 'eye', () => send({
            type: 'openDebugForWorker', swarmId, instance, action: 'Inspect',
          }), 'quiet compact'),
          iconButton('Logs', 'output', () => send({
            type: 'openDebugForWorker', swarmId, instance, action: 'Logs',
          }), 'quiet compact'),
        ]),
      ]));
    }
  }
  details.append(list);
  return details;
}

function workerRoleLabel(role: string): string {
  return role.split(/[-_]/).filter(Boolean)
    .map(part => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ');
}

function createSwarmView(value: unknown): HTMLElement {
  const formValue = objectValue(value);
  if (!formValue) return ownerDataError(value, 'create swarm form');
  const result = el('section', 'card scenario-upload');
  result.append(
    text('h3', 'Create swarm'),
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
    iconButton('Cancel', 'close', () => send({ type: 'cancelCreateSwarm' }), 'quiet'),
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
  const search = searchInput('Search bundles', 'scenarioSearch', scenarioSearch, 'Find a bundle');
  search.control.required = false;
  const folder = select('Folder', 'scenarioFolder', [['ALL', 'All folders'], ...folders.map(item => [item, item])], scenarioFolder);
  const advanced = refreshStableDetails(
    'scenarios:deployed:filters',
    'advanced-filters scenario-advanced-filters',
  );
  const advancedSummary = iconSummary('Scenario filters', 'filter');
  const folderBadge = text('span', scenarioFolder === 'ALL' ? 'All' : '1', 'filter-count');
  advancedSummary.append(folderBadge);
  advanced.append(advancedSummary, el('div', 'advanced-filters__panel', [folder.wrapper]));
  const filters = el('div', 'filter-bar scenario-filters', [search.wrapper, advanced]);
  const list = el('div', 'scenario-list');
  const apply = () => {
    scenarioSearch = search.control.value;
    scenarioFolder = folder.control.value;
    folderBadge.textContent = scenarioFolder === 'ALL' ? 'All' : '1';
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
    el('div', 'scenario-row__identity', [
      brandMark('scenario-mark'),
      el('div', 'scenario-row__copy', [
        titled('strong', name, 'truncate'),
        titled('span', summaryMeta, 'mono muted truncate'),
      ]),
    ]),
    el('div', 'scenario-row__status', [
      statusPill(defunct ? 'Defunct' : 'Deployed'),
      icon('chevron-right', 'disclosure-chevron'),
    ]),
  ]));
  const body = el('div', 'scenario-row__body');
  body.append(dataRows([
    ['Scenario ID', scenarioId ?? 'Unavailable'],
    ['Bundle key', bundleKey],
    ['Folder', displayValue(scenario.folderPath)],
    ['Bundle path', displayValue(scenario.bundlePath)],
  ]));
  if (scenarioId) {
    const sectionTabs = el('div', 'compact-tabs scenario-section-tabs', [
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
  body.append(technicalDetails(scenario, `scenarios:deployed:${rowId}:technical`));
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
  }, 'compact-tab scenario-section-tab');
  control.append(icon(section === 'OVERVIEW' ? 'preview' : section === 'FILES' ? 'list-tree' : 'symbol-variable'));
  control.setAttribute('aria-pressed', String(activeSection === section));
  return control;
}

function scenarioOverviewSection(scenario: Model): HTMLElement {
  const cards = el('div', 'scenario-detail-grid scenario-overview');
  if (stringField(scenario, 'description')) {
    cards.append(scenarioInfoCard('Description', String(scenario.description), '', 'scenario-info-card--full'));
  }
  if (stringField(scenario, 'controllerImage')) {
    cards.append(scenarioInfoCard('Controller', String(scenario.controllerImage), 'mono', 'scenario-info-card--full'));
  }
  const bees = Array.isArray(scenario.bees) ? scenario.bees as Model[] : [];
  if (bees.length > 0) {
    cards.append(el('article', 'card scenario-info-card scenario-info-card--full', [
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

function scenarioInfoCard(label: string, value: string, valueClass = '', cardClass = ''): HTMLElement {
  return el('article', `card scenario-info-card${cardClass ? ` ${cardClass}` : ''}`, [
    text('span', label, 'eyebrow'),
    titled('p', value, `scenario-info-card__value${valueClass ? ` ${valueClass}` : ''}`),
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
  const roots = scenarioTreeHierarchy(nodes);
  if (!roots) return ownerDataError(model.scenarioFocusTree, 'scenario bundle tree hierarchy');
  const list = el('div', 'scenario-tree');
  for (const entry of roots) list.append(scenarioFileNode(bundleKey, entry));
  return list;
}

function scenarioTreeHierarchy(nodes: Model[]): ScenarioTreeEntry[] | undefined {
  const entries = new Map<string, ScenarioTreeEntry>();
  for (const node of nodes) {
    const path = stringField(node, 'path');
    const name = stringField(node, 'name');
    const rawNodeType = stringField(node, 'nodeType');
    if (!path || !name || (rawNodeType !== 'directory' && rawNodeType !== 'file')
      || path.split('/').at(-1) !== name || entries.has(path)) return undefined;
    entries.set(path, { node, path, name, nodeType: rawNodeType, children: [] });
  }

  const roots: ScenarioTreeEntry[] = [];
  for (const entry of entries.values()) {
    const separator = entry.path.lastIndexOf('/');
    if (separator < 0) {
      roots.push(entry);
      continue;
    }
    const parent = entries.get(entry.path.slice(0, separator));
    if (!parent || parent.nodeType !== 'directory') return undefined;
    parent.children.push(entry);
  }
  return roots;
}

function scenarioFileNode(bundleKey: string, entry: ScenarioTreeEntry): HTMLElement {
  if (entry.nodeType === 'directory') {
    const branch = refreshStableDetails(
      `scenarios:deployed:${bundleKey}:directory:${entry.path}`,
      'scenario-tree__branch',
      true,
    );
    branch.append(el('summary', 'scenario-tree__row scenario-tree__row--directory', [
      icon('chevron-right', 'scenario-tree__twistie'),
      icon('folder', 'scenario-tree__icon'),
      titled('strong', entry.name, 'truncate'),
    ]));
    const children = el('div', 'scenario-tree__children');
    for (const child of entry.children) children.append(scenarioFileNode(bundleKey, child));
    branch.append(children);
    return branch;
  }

  const row = el('article', 'scenario-tree__row scenario-tree__row--file');
  const meta = el('div', 'scenario-tree__meta', [
    icon('file-code', 'scenario-tree__icon'),
    titled('strong', entry.name, 'truncate'),
  ]);
  row.append(meta);
  const editorKind = stringField(entry.node, 'editorKind');
  const label = editorKind === 'unsupported' ? 'Metadata' : 'Preview';
  row.append(el('div', 'scenario-tree__actions', [
    text('span', displayValue(entry.node.size), 'muted mono'),
    iconButton(label, editorKind === 'unsupported' ? 'info' : 'preview', () =>
      send({ type: 'openScenarioBundleFile', bundleKey, path: entry.path }), 'secondary compact'),
  ]));
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
      iconButton('Preview', 'preview', () =>
        send({ type: 'openScenarioBundleFile', bundleKey, path: exactPath }), 'secondary compact'),
    ]));
  }
  return card;
}

function scenarioSutCard(value: Model): HTMLElement {
  const sutId = stringField(value, 'sutId') ?? 'SUT';
  const error = errorFrom(value.error);
  const card = el('article', 'card data-card');
  card.append(el('div', 'data-heading', [
    el('div', '', [text('span', 'Bundle-local SUT', 'eyebrow'), titled('h3', sutId, 'truncate')]),
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
    eventCriteria[context] = next;
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

function debugView(): HTMLElement {
  const result = el('div', 'debug');
  const swarms = (topLevelRecords(model.workspaceData) ?? [])
    .map(item => stringField(item, 'id')).filter((id): id is string => Boolean(id));
  const configured = Array.isArray(model.debugActions) ? model.debugActions as Model[] : [];
  const runtimeTarget = debugRuntimeTarget(swarms, configured);
  const swarmTools = debugSwarmTools(configured);
  const maintenance = debugMaintenance(configured);
  result.append(debugContextControl(), runtimeTarget, swarmTools, maintenance);
  return result;
}

function debugContextControl(): HTMLElement {
  const control = el('section', 'debug-context', [text('h3', 'Target context')]);
  const switcher = el('div', 'debug-scope');
  for (const item of [
    { context: 'WORKER' as const, label: 'Worker', iconName: 'account', targetId: 'debug-runtime-target' },
    { context: 'SWARM' as const, label: 'Swarm', iconName: 'package', targetId: 'debug-swarm-tools' },
  ]) {
    const active = debugContext === item.context;
    const option = iconButton(item.label, item.iconName, () => {
      debugContext = item.context;
      render();
      requestAnimationFrame(() => document.querySelector<HTMLElement>(`#${item.targetId}`)
        ?.scrollIntoView({ block: 'start' }));
    }, `debug-scope__button${active ? ' active' : ''}`, true);
    option.setAttribute('aria-pressed', String(active));
    option.setAttribute('aria-controls', item.targetId);
    switcher.append(option);
  }
  control.append(switcher);
  return control;
}

function debugRuntimeTarget(swarms: string[], configured: Model[]): HTMLElement {
  const section = el('section', 'debug-section debug-runtime-target');
  section.id = 'debug-runtime-target';
  section.append(el('div', 'debug-section__header', [text('h3', 'Runtime target')]));
  const body = el('div', 'debug-section__body');
  const swarm = searchableChoice(
    'Exact swarm',
    'debugSwarm',
    swarms,
    model.debugSwarmId ?? '',
    'Search exact swarm…',
  );
  swarm.control.addEventListener('change', () => {
    sendExactChoice(swarm.control, swarms, 'swarm', swarmId =>
      send({ type: 'selectDebugSwarm', swarmId }));
  });
  const targetRow = el('div', 'debug-target-row', [swarm.wrapper]);
  const workersAction = configuredDebugAction(configured, 'Workers');
  if (workersAction) targetRow.append(debugActionButton(workersAction, 'Load workers', 'primary compact'));
  body.append(targetRow);

  const workers = identifiers(model.debugWorkersResult, ['runtimeId', 'id', 'name']);
  if (workers.length > 0) {
    const worker = searchableChoice(
      'Exact worker',
      'debugWorker',
      workers,
      model.debugRuntimeId ?? '',
      'Search exact worker…',
    );
    worker.control.addEventListener('change', () => {
      sendExactChoice(worker.control, workers, 'worker', runtimeId =>
        send({ type: 'selectDebugWorker', runtimeId }));
    });
    body.append(worker.wrapper);
  }
  if (model.debugRuntimeId) {
    body.append(el('div', 'debug-worker-resource', [
      el('span', 'debug-worker-resource__identity', [
        icon('server-process'),
        titled('strong', String(model.debugRuntimeId), 'truncate'),
      ]),
      text('span', 'Selected', 'debug-worker-resource__state'),
    ]));
  }

  const tabs = el('div', 'compact-tabs debug-worker-tabs');
  tabs.setAttribute('role', 'tablist');
  tabs.setAttribute('aria-label', 'Worker diagnostics');
  const workerPresentations = DEBUG_ACTION_PRESENTATION.filter(item => item.context === 'WORKER');
  const activeWorkerAction = workerPresentations.some(item => item.label === model.debugAction)
    ? String(model.debugAction) : workerPresentations[0].label;
  for (const presentation of workerPresentations) {
    const action = configuredDebugAction(configured, presentation.label);
    if (!action) continue;
    const tab = debugActionButton(action, presentation.label, 'compact-tab debug-worker-tab');
    tab.setAttribute('role', 'tab');
    tab.setAttribute('aria-selected', String(presentation.label === activeWorkerAction));
    tab.setAttribute('aria-controls', 'debug-worker-evidence');
    tabs.append(tab);
  }
  body.append(tabs);
  if (workerPresentations.some(item => item.label === model.debugAction) && model.debugResult !== undefined) {
    body.append(debugEvidence(model.debugResult, 'debug-worker-evidence'));
  } else {
    const guidance = el('section', 'debug-evidence debug-evidence--empty', [
      text('p', model.debugRuntimeId
        ? 'Choose a worker diagnostic. PocketHive will not infer another target.'
        : 'Load and select one exact worker to run worker diagnostics.', 'muted debug-guidance'),
    ]);
    guidance.id = 'debug-worker-evidence';
    guidance.setAttribute('role', 'tabpanel');
    body.append(guidance);
  }
  section.append(body);
  return section;
}

function debugSwarmTools(configured: Model[]): HTMLElement {
  const section = el('section', 'debug-section');
  section.id = 'debug-swarm-tools';
  section.append(el('div', 'debug-section__header', [text('h3', 'Swarm tools')]));
  const tools = el('div', 'debug-swarm-tools');
  for (const presentation of DEBUG_ACTION_PRESENTATION.filter(item => item.context === 'SWARM')) {
    const action = configuredDebugAction(configured, presentation.label);
    if (!action) continue;
    const control = debugActionButton(action, presentation.label, 'secondary debug-tool-button');
    control.append(icon('chevron-right', 'debug-tool-button__chevron'));
    tools.append(control);
  }
  section.append(tools);
  if (DEBUG_ACTION_PRESENTATION.some(item => item.context === 'SWARM' && item.label === model.debugAction)
      && model.debugResult !== undefined) {
    section.append(debugEvidence(model.debugResult, 'debug-swarm-evidence'));
  }
  return section;
}

function debugMaintenance(configured: Model[]): HTMLElement {
  const section = el('section', 'debug-section debug-maintenance');
  section.append(el('div', 'debug-section__header', [text('h3', 'Maintenance')]));
  const action = configuredDebugAction(configured, 'Cleanup plan');
  if (action) {
    const row = el('div', 'debug-maintenance__row', [
      debugActionButton(action, 'Cleanup plan', 'guarded debug-tool-button'),
      text('span', 'Plan only', 'debug-plan-badge'),
    ]);
    section.append(row);
  }
  if (model.debugAction === 'Cleanup plan' && model.debugResult !== undefined) {
    section.append(debugEvidence(model.debugResult, 'debug-maintenance-evidence'));
  }
  return section;
}

function configuredDebugAction(configured: Model[], label: string): Model | undefined {
  return configured.find(action => action.label === label);
}

function debugActionButton(action: Model, label: string, className: string): HTMLButtonElement {
  const presentation = DEBUG_ACTION_PRESENTATION.find(item => item.label === action.label);
  const control = button('', () => {
    const message: Model = { type: 'runDebug', action: String(action.label) };
    if (presentation && 'tailLines' in presentation) message.tailLines = presentation.tailLines;
    send(message);
  }, className);
  control.setAttribute('aria-label', label);
  control.title = label;
  control.append(icon(presentation?.icon ?? 'tools'), text('span', label, 'debug-action-label truncate'));
  control.disabled = model.busy || !model.debugSwarmId || (Boolean(action.needsWorker) && !model.debugRuntimeId);
  return control;
}

function debugEvidence(value: unknown, id: string): HTMLElement {
  const action = String(model.debugAction ?? 'Result');
  let evidence: HTMLElement;
  if (action === 'Logs') evidence = debugLogsEvidence(value);
  else if (action === 'Inspect') evidence = debugInspectEvidence(value);
  else if (action === 'Version') evidence = debugVersionEvidence(value);
  else if (action === 'Cleanup plan') evidence = debugCleanupPlanEvidence(value);
  else evidence = genericDebugEvidence(value, action);
  evidence.id = id;
  evidence.setAttribute('role', 'tabpanel');
  return evidence;
}

function genericDebugEvidence(value: unknown, action: string): HTMLElement {
  const evidence = el('section', 'debug-evidence', [
    el('div', 'debug-evidence__heading', [
      text('h4', 'Bounded MCP evidence'),
      text('span', action, 'muted'),
    ]),
    resultCard(value),
  ]);
  return evidence;
}

function debugLogsEvidence(value: unknown): HTMLElement {
  const result = objectValue(value);
  const target = result && objectValue(result.target);
  const logs = result && typeof result.logs === 'string' ? result.logs : undefined;
  const tailLines = result && typeof result.tailLines === 'number' ? result.tailLines : undefined;
  if (!result || !target || logs === undefined || tailLines === undefined) {
    return ownerDataError(value, 'runtime log evidence');
  }
  const pre = document.createElement('pre');
  pre.tabIndex = 0;
  pre.textContent = logs;
  return el('section', 'debug-evidence debug-evidence--logs', [
    el('div', 'debug-evidence__heading', [
      text('h4', 'Container logs'),
      text('span', runtimeTargetLabel(target), 'muted truncate'),
    ]),
    el('article', 'debug-log-output', [pre]),
    el('div', 'debug-evidence__provenance', [
      icon('output'),
      text('span', `Docker stdout/stderr · tail ${tailLines}`),
      text('span', result.redacted === true ? 'Redacted' : 'Not redacted', 'muted'),
    ]),
  ]);
}

function debugInspectEvidence(value: unknown): HTMLElement {
  const result = objectValue(value);
  const target = result && objectValue(result.target);
  const source = result && objectValue(result.source);
  const state = result && objectValue(result.state);
  const mounts = result && Array.isArray(result.mounts) ? result.mounts : undefined;
  const networks = result && Array.isArray(result.networks) ? result.networks : undefined;
  if (!result || !target || !source || !state || !mounts || !networks) {
    return ownerDataError(value, 'runtime inspect evidence');
  }
  const projection = {
    state,
    createdAt: result.createdAt ?? null,
    restartCount: result.restartCount ?? null,
    restartPolicy: result.restartPolicy ?? null,
    mounts,
    networks,
  };
  const pre = document.createElement('pre');
  pre.tabIndex = 0;
  pre.textContent = JSON.stringify(projection, null, 2);
  return el('section', 'debug-evidence debug-evidence--inspect', [
    el('div', 'debug-evidence__heading', [
      text('h4', 'Container inspect'),
      text('span', runtimeTargetLabel(target), 'muted truncate'),
    ]),
    el('article', 'debug-inspect-output', [pre]),
    el('div', 'debug-evidence__provenance', [
      icon('json'),
      text('span', 'Orchestrator inspect projection'),
      text('span', source.available === true ? 'Available' : 'Unavailable', 'muted'),
    ]),
  ]);
}

function debugVersionEvidence(value: unknown): HTMLElement {
  const result = objectValue(value);
  const target = result && objectValue(result.target);
  if (!result || !target) return ownerDataError(value, 'runtime version evidence');
  return el('section', 'debug-evidence debug-evidence--version', [
    el('div', 'debug-evidence__heading', [
      text('h4', 'Deployed version'),
      text('span', runtimeTargetLabel(target), 'muted truncate'),
    ]),
    dataRows([
      ['Version', displayValue(result.reportedVersion)],
      ['Source', displayValue(result.reportedVersionSource)],
      ['Declared', displayValue(result.declaredVersion)],
      ['Image', displayValue(result.image)],
      ['Tag', displayValue(result.imageTag)],
      ['Digest', displayValue(result.imageDigest)],
    ]),
  ]);
}

function debugCleanupPlanEvidence(value: unknown): HTMLElement {
  const result = objectValue(value);
  const candidateSetHash = result && stringField(result, 'candidateSetHash');
  const candidates = result && Array.isArray(result.candidates) ? result.candidates : undefined;
  const blocked = result && Array.isArray(result.blocked) ? result.blocked : [];
  if (!result || !candidateSetHash || candidates === undefined) {
    return ownerDataError(value, 'runtime cleanup plan');
  }
  const count = candidates.length;
  const evidence = el('section', 'debug-evidence debug-cleanup-plan', [
    el('div', 'debug-evidence__heading', [
      text('h4', `${count} cleanup ${count === 1 ? 'candidate' : 'candidates'}`),
      statusPill(String(result.executionRisk ?? 'UNKNOWN')),
    ]),
    dataRows([
      ['Candidate set', candidateSetHash],
      ['Blocked', String(blocked.length)],
    ]),
  ]);
  const list = el('div', 'debug-cleanup-candidates');
  for (const candidateValue of candidates.slice(0, 1000)) {
    const candidate = objectValue(candidateValue);
    const candidateId = candidate && stringField(candidate, 'candidateId');
    if (!candidate || !candidateId) {
      list.append(ownerDataError(candidateValue, 'cleanup candidate'));
      continue;
    }
    list.append(el('article', 'debug-cleanup-candidate', [
      icon('trash'),
      el('div', 'debug-cleanup-candidate__copy', [
        titled('strong', candidateId, 'mono truncate'),
        titled('span', displayValue(candidate.reason), 'muted truncate'),
      ]),
    ]));
  }
  if (count > 0) evidence.append(list);
  const generate = iconButton('Generate new plan', 'refresh', () =>
    send({ type: 'runDebug', action: 'Cleanup plan' }), 'secondary compact');
  const execute = iconButton('Execute cleanup', 'lock', () => undefined, 'secondary compact');
  execute.disabled = true;
  execute.title = 'Cleanup execution requires HiveGate approval.';
  evidence.append(el('div', 'debug-cleanup-actions', [
    generate,
    execute,
    iconText('lock', 'Requires HiveGate approval', 'debug-cleanup-lock muted'),
  ]));
  return evidence;
}

function runtimeTargetLabel(target: Model): string {
  return stringField(target, 'runtimeId')
    ?? stringField(target, 'name')
    ?? stringField(target, 'instance')
    ?? 'Exact runtime target';
}

function compactJson(value: unknown): string {
  if (value === undefined || value === null) return 'Not reported';
  if (typeof value === 'string') return value;
  return JSON.stringify(value);
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

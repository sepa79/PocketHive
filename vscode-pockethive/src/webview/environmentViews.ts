/**
 * Responsibility: Render environment onboarding, account session, and ingress-health presentation.
 * Must not: Authenticate users, probe services, or infer environment configuration.
 * Contract: vscode-pockethive/README.md and docs/mcp/README.md.
 */

interface PocketHiveEnvironmentViewPort {
  readonly authenticationStatus: (attempt: Model) => string;
  readonly brandMark: (className: string) => HTMLImageElement;
  readonly button: (
    label: string, action: () => void, className: string, enabledWhileBusy?: boolean,
  ) => HTMLButtonElement;
  readonly el: (tag: string, className?: string, children?: Array<Node | undefined>) => HTMLElement;
  readonly endpointStatus: (attempt: Model) => string;
  readonly icon: (name: string, extraClass?: string) => HTMLElement;
  readonly iconButton: (
    label: string, iconName: string, action: () => void, className: string, enabledWhileBusy?: boolean,
  ) => HTMLButtonElement;
  readonly iconSummary: (label: string, iconName: string) => HTMLElement;
  readonly iconText: (name: string, label: string, className?: string) => HTMLElement;
  readonly input: (
    label: string, id: string, value: string, placeholder: string, visuallyHiddenLabel?: boolean,
  ) => { wrapper: HTMLElement; control: HTMLInputElement };
  readonly objectValue: (value: unknown) => Model | undefined;
  readonly refreshStableDetails: (key: string, className: string, openByDefault?: boolean) => HTMLDetailsElement;
  readonly select: (
    label: string, id: string, options: string[][], value: string,
  ) => { wrapper: HTMLElement; control: HTMLSelectElement };
  readonly send: (message: unknown, whileBusy?: boolean) => void;
  readonly stage: (number: string, label: string, done: boolean) => HTMLElement;
  readonly statusPill: (status: string) => HTMLElement;
  readonly statusRow: (label: string, status: string) => HTMLElement;
  readonly stringField: (value: Model, field: string) => string | undefined;
  readonly testStatus: (attempt: Model) => string;
  readonly text: (tag: string, value: string, className?: string, role?: string) => HTMLElement;
  readonly titled: (tag: string, value: string, className?: string) => HTMLElement;
}

interface PocketHiveEnvironmentViewsApi {
  renderLanding(model: Model, view: PocketHiveEnvironmentViewPort): HTMLElement;
  renderHealth(model: Model, profile: Model, session: Model, view: PocketHiveEnvironmentViewPort): HTMLElement;
  renderSessionNotice(model: Model, session: Model, view: PocketHiveEnvironmentViewPort): HTMLElement;
  resetInteractionState(): void;
}

(() => {
const ENVIRONMENT_SERVICE_ICONS: Readonly<Record<string, string>> = Object.freeze({
  'pockethive-ui': 'home',
  orchestrator: 'server-process',
  'scenario-manager': 'folder-library',
  'network-proxy-manager': 'globe',
  wiremock: 'beaker',
  'tcp-mock': 'plug',
  grafana: 'graph-line',
});
let model: Model;
let environmentHealthExpanded = false;
let authenticationStatus: PocketHiveEnvironmentViewPort['authenticationStatus'];
let brandMark: PocketHiveEnvironmentViewPort['brandMark'];
let button: PocketHiveEnvironmentViewPort['button'];
let el: PocketHiveEnvironmentViewPort['el'];
let endpointStatus: PocketHiveEnvironmentViewPort['endpointStatus'];
let icon: PocketHiveEnvironmentViewPort['icon'];
let iconButton: PocketHiveEnvironmentViewPort['iconButton'];
let iconSummary: PocketHiveEnvironmentViewPort['iconSummary'];
let iconText: PocketHiveEnvironmentViewPort['iconText'];
let input: PocketHiveEnvironmentViewPort['input'];
let objectValue: PocketHiveEnvironmentViewPort['objectValue'];
let refreshStableDetails: PocketHiveEnvironmentViewPort['refreshStableDetails'];
let select: PocketHiveEnvironmentViewPort['select'];
let send: PocketHiveEnvironmentViewPort['send'];
let stage: PocketHiveEnvironmentViewPort['stage'];
let statusPill: PocketHiveEnvironmentViewPort['statusPill'];
let statusRow: PocketHiveEnvironmentViewPort['statusRow'];
let stringField: PocketHiveEnvironmentViewPort['stringField'];
let testStatus: PocketHiveEnvironmentViewPort['testStatus'];
let text: PocketHiveEnvironmentViewPort['text'];
let titled: PocketHiveEnvironmentViewPort['titled'];

function bind(nextModel: Model, view: PocketHiveEnvironmentViewPort): void {
  model = nextModel;
  ({
    authenticationStatus, brandMark, button, el, endpointStatus, icon, iconButton, iconSummary,
    iconText, input, objectValue, refreshStableDetails, select, send, stage, statusPill, statusRow,
    stringField, testStatus, text, titled,
  } = view);
}

function renderLanding(nextModel: Model, view: PocketHiveEnvironmentViewPort): HTMLElement {
  bind(nextModel, view);
  return environments();
}

function renderHealth(
  nextModel: Model,
  profile: Model,
  session: Model,
  view: PocketHiveEnvironmentViewPort,
): HTMLElement {
  bind(nextModel, view);
  return environmentHealth(profile, session);
}

function renderSessionNotice(
  nextModel: Model,
  session: Model,
  view: PocketHiveEnvironmentViewPort,
): HTMLElement {
  bind(nextModel, view);
  return sessionNotice(session);
}

function resetInteractionState(): void {
  environmentHealthExpanded = false;
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

const api: PocketHiveEnvironmentViewsApi = Object.freeze({
  renderLanding,
  renderHealth,
  renderSessionNotice,
  resetInteractionState,
});
(globalThis as typeof globalThis & { PocketHiveEnvironmentViews: PocketHiveEnvironmentViewsApi })
  .PocketHiveEnvironmentViews = api;
})();

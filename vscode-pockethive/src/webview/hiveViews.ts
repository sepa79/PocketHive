/**
 * Responsibility: Render the Hive swarm lifecycle, workers, history, and explicit creation controls.
 * Must not: Call MCP services, infer lifecycle policy, or reinterpret Orchestrator outcomes.
 * Contract: vscode-pockethive/README.md and docs/mcp/README.md.
 */

interface PocketHiveHiveViewPort {
  readonly brandMark: (className: string) => HTMLImageElement;
  readonly button: (label: string, action: () => void, className: string) => HTMLButtonElement;
  readonly displayValue: (value: unknown) => string;
  readonly el: (tag: string, className?: string, children?: Array<Node | undefined>) => HTMLElement;
  readonly emptyState: (message: string) => HTMLElement;
  readonly errorFrom: (value: unknown) => string | undefined;
  readonly errorState: (message: string) => HTMLElement;
  readonly icon: (name: string, extraClass?: string) => HTMLElement;
  readonly iconButton: (
    label: string, iconName: string, action: () => void, className: string,
  ) => HTMLButtonElement;
  readonly input: (
    label: string, id: string, value: string, placeholder: string, visuallyHiddenLabel?: boolean,
  ) => { wrapper: HTMLElement; control: HTMLInputElement };
  readonly objectValue: (value: unknown) => Model | undefined;
  readonly ownerDataError: (value: unknown, expected: string) => HTMLElement;
  readonly refreshStableDetails: (key: string, className: string, openByDefault?: boolean) => HTMLDetailsElement;
  readonly rerender: () => void;
  readonly searchInput: (
    label: string, id: string, value: string, placeholder: string, visuallyHiddenLabel?: boolean,
  ) => { wrapper: HTMLElement; control: HTMLInputElement };
  readonly select: (
    label: string, id: string, options: string[][], value: string,
  ) => { wrapper: HTMLElement; control: HTMLSelectElement };
  readonly send: (message: unknown, whileBusy?: boolean) => void;
  readonly showError: (message: string) => void;
  readonly statusPill: (status: string) => HTMLElement;
  readonly statusToken: (status: string) => string;
  readonly stringField: (value: Model, field: string) => string | undefined;
  readonly stringList: (value: unknown) => string[];
  readonly technicalDetails: (value: unknown, key?: string) => HTMLElement;
  readonly text: (tag: string, value: string, className?: string, role?: string) => HTMLElement;
  readonly titled: (tag: string, value: string, className?: string) => HTMLElement;
  readonly topLevelRecords: (value: unknown) => Model[] | undefined;
  readonly workspaceActionIconButton: (
    label: string, iconName: string, action: () => void, className: string,
  ) => HTMLButtonElement;
}

interface PocketHiveHiveViewsApi {
  render(model: Model, view: PocketHiveHiveViewPort): HTMLElement;
  reconcile(model: Model, view: PocketHiveHiveViewPort): void;
}

(() => {
let model: Model;
let expandedHistorySwarmId: string | undefined;
let swarmSearch = '';
let createSwarmDraft: {
  swarmId: string;
  templateId: string;
  scenarioId: string;
  sutId: string;
  variablesProfileId: string;
  autoPullImages: boolean;
  networkMode: 'DIRECT' | 'PROXIED';
  networkProfileId: string;
} | undefined;
let brandMark: PocketHiveHiveViewPort['brandMark'];
let button: PocketHiveHiveViewPort['button'];
let displayValue: PocketHiveHiveViewPort['displayValue'];
let el: PocketHiveHiveViewPort['el'];
let emptyState: PocketHiveHiveViewPort['emptyState'];
let errorFrom: PocketHiveHiveViewPort['errorFrom'];
let errorState: PocketHiveHiveViewPort['errorState'];
let icon: PocketHiveHiveViewPort['icon'];
let iconButton: PocketHiveHiveViewPort['iconButton'];
let input: PocketHiveHiveViewPort['input'];
let objectValue: PocketHiveHiveViewPort['objectValue'];
let ownerDataError: PocketHiveHiveViewPort['ownerDataError'];
let refreshStableDetails: PocketHiveHiveViewPort['refreshStableDetails'];
let rerender: PocketHiveHiveViewPort['rerender'];
let searchInput: PocketHiveHiveViewPort['searchInput'];
let select: PocketHiveHiveViewPort['select'];
let send: PocketHiveHiveViewPort['send'];
let showError: PocketHiveHiveViewPort['showError'];
let statusPill: PocketHiveHiveViewPort['statusPill'];
let statusToken: PocketHiveHiveViewPort['statusToken'];
let stringField: PocketHiveHiveViewPort['stringField'];
let stringList: PocketHiveHiveViewPort['stringList'];
let technicalDetails: PocketHiveHiveViewPort['technicalDetails'];
let text: PocketHiveHiveViewPort['text'];
let titled: PocketHiveHiveViewPort['titled'];
let topLevelRecords: PocketHiveHiveViewPort['topLevelRecords'];
let workspaceActionIconButton: PocketHiveHiveViewPort['workspaceActionIconButton'];

function renderView(nextModel: Model, view: PocketHiveHiveViewPort): HTMLElement {
  model = nextModel;
  bind(view);
  const result = el('div', 'hive-workspace');
  if (model.createSwarmForm !== undefined) result.append(createSwarmView(model.createSwarmForm));
  result.append(swarmListView(model.workspaceData));
  return result;
}

function bind(view: PocketHiveHiveViewPort): void {
  ({
    brandMark, button, displayValue, el, emptyState, errorFrom, errorState, icon, iconButton, input,
    objectValue, ownerDataError, refreshStableDetails, rerender, searchInput, select, send, showError,
    statusPill, statusToken, stringField, stringList, technicalDetails, text, titled, topLevelRecords,
    workspaceActionIconButton,
  } = view);
}

function reconcile(nextModel: Model, view: PocketHiveHiveViewPort): void {
  bind(view);
  reconcileCreateSwarmDraft(nextModel);
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
        rerender();
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
  const configuredAutoPullImages = formValue.autoPullImages;
  const configuredNetworkMode = stringField(formValue, 'networkMode');
  if (typeof configuredAutoPullImages !== 'boolean'
    || (configuredNetworkMode !== 'DIRECT' && configuredNetworkMode !== 'PROXIED')) {
    return ownerDataError(value, 'create swarm execution settings');
  }
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
  const autoPullImages = select('Pull images', 'createSwarmAutoPullImages', [
    ['true', 'Yes'],
    ['false', 'No'],
  ], String(createSwarmDraft?.autoPullImages ?? configuredAutoPullImages));
  autoPullImages.control.addEventListener('change', () => {
    ensureCreateSwarmDraft().autoPullImages = autoPullImages.control.value === 'true';
  });
  const networkMode = select('Network mode', 'createSwarmNetworkMode', [
    ['DIRECT', 'Direct'],
    ['PROXIED', 'Proxied'],
  ], createSwarmDraft?.networkMode ?? configuredNetworkMode);
  const networkProfile = input('Network profile ID', 'createSwarmNetworkProfile',
    createSwarmDraft?.networkProfileId ?? '', 'Required only when the selected scenario uses a profile');
  networkProfile.control.required = false;
  networkProfile.control.addEventListener('input', () => {
    ensureCreateSwarmDraft().networkProfileId = networkProfile.control.value;
  });
  networkMode.control.addEventListener('change', () => {
    ensureCreateSwarmDraft().networkMode = networkMode.control.value as 'DIRECT' | 'PROXIED';
  });
  result.append(
    template.wrapper,
    swarmId.wrapper,
    sut.wrapper,
    variables.wrapper,
    autoPullImages.wrapper,
    networkMode.wrapper,
    networkProfile.wrapper,
  );
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
        autoPullImages: draft.autoPullImages,
        sutId: draft.sutId.trim() || null,
        variablesProfileId: draft.variablesProfileId.trim() || null,
        networkMode: draft.networkMode,
        networkProfileId: draft.networkProfileId.trim() || null,
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
  const autoPullImages = typeof formValue.autoPullImages === 'boolean' ? formValue.autoPullImages : undefined;
  const networkMode = stringField(formValue, 'networkMode');
  if (autoPullImages === undefined || (networkMode !== 'DIRECT' && networkMode !== 'PROXIED')) {
    createSwarmDraft = undefined;
    return;
  }
  createSwarmDraft = {
    swarmId: createSwarmDraft?.swarmId ?? '',
    templateId,
    scenarioId,
    sutId,
    variablesProfileId: createSwarmDraft?.variablesProfileId ?? '',
    autoPullImages: createSwarmDraft?.autoPullImages ?? autoPullImages,
    networkMode: createSwarmDraft?.networkMode ?? networkMode,
    networkProfileId: createSwarmDraft?.networkProfileId ?? '',
  };
}

function ensureCreateSwarmDraft(): NonNullable<typeof createSwarmDraft> {
  if (!createSwarmDraft) throw new Error('Create swarm presentation state is unavailable');
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

const api: PocketHiveHiveViewsApi = Object.freeze({ render: renderView, reconcile });
(globalThis as typeof globalThis & { PocketHiveHiveViews: PocketHiveHiveViewsApi }).PocketHiveHiveViews = api;
})();

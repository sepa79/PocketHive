/**
 * Responsibility: Render exact worker, swarm, and maintenance Debug controls for the companion.
 * Must not: Call MCP services, infer runtime targets, or execute governed cleanup.
 * Contract: vscode-pockethive/README.md and docs/mcp/README.md.
 */

interface PocketHiveDebugViewPort {
  readonly button: (
    label: string, action: () => void, className: string, enabledWhileBusy?: boolean,
  ) => HTMLButtonElement;
  readonly debugEvidence: (value: unknown, id: string) => HTMLElement;
  readonly el: (tag: string, className?: string, children?: Array<Node | undefined>) => HTMLElement;
  readonly icon: (name: string, extraClass?: string) => HTMLElement;
  readonly iconButton: (
    label: string, iconName: string, action: () => void, className: string, enabledWhileBusy?: boolean,
  ) => HTMLButtonElement;
  readonly identifiers: (value: unknown, keys: string[]) => string[];
  readonly rerender: () => void;
  readonly searchableChoice: (
    label: string, id: string, choices: readonly string[], value: string, placeholder: string,
  ) => { wrapper: HTMLElement; control: HTMLInputElement };
  readonly send: (message: unknown, whileBusy?: boolean) => void;
  readonly sendExactChoice: (
    control: HTMLInputElement, choices: readonly string[], subject: string,
    onChoice: (choice: string) => void,
  ) => void;
  readonly stringField: (value: Model, field: string) => string | undefined;
  readonly text: (tag: string, value: string, className?: string, role?: string) => HTMLElement;
  readonly titled: (tag: string, value: string, className?: string) => HTMLElement;
  readonly topLevelRecords: (value: unknown) => Model[] | undefined;
}

interface PocketHiveDebugViewsApi {
  render(model: Model, view: PocketHiveDebugViewPort): HTMLElement;
}

(() => {
type DebugContext = 'WORKER' | 'SWARM';
const DEBUG_ACTION_PRESENTATION = Object.freeze([
  { label: 'Workers', icon: 'organization', context: 'SWARM' },
  { label: 'Logs', icon: 'output', context: 'WORKER', tailLines: 200 },
  { label: 'Inspect', icon: 'inspect', context: 'WORKER' },
  { label: 'Version', icon: 'versions', context: 'WORKER' },
  { label: 'Runtime assessment', icon: 'pulse', context: 'SWARM' },
  { label: 'Rabbit topology', icon: 'type-hierarchy', context: 'SWARM' },
  { label: 'Timeline', icon: 'history', context: 'SWARM' },
  { label: 'Cleanup plan', icon: 'trash', context: 'MAINTENANCE' },
] as const);
let model: Model;
let debugContext: DebugContext = 'WORKER';
let button: PocketHiveDebugViewPort['button'];
let debugEvidence: PocketHiveDebugViewPort['debugEvidence'];
let el: PocketHiveDebugViewPort['el'];
let icon: PocketHiveDebugViewPort['icon'];
let iconButton: PocketHiveDebugViewPort['iconButton'];
let identifiers: PocketHiveDebugViewPort['identifiers'];
let rerender: PocketHiveDebugViewPort['rerender'];
let searchableChoice: PocketHiveDebugViewPort['searchableChoice'];
let send: PocketHiveDebugViewPort['send'];
let sendExactChoice: PocketHiveDebugViewPort['sendExactChoice'];
let stringField: PocketHiveDebugViewPort['stringField'];
let text: PocketHiveDebugViewPort['text'];
let titled: PocketHiveDebugViewPort['titled'];
let topLevelRecords: PocketHiveDebugViewPort['topLevelRecords'];

function renderView(nextModel: Model, view: PocketHiveDebugViewPort): HTMLElement {
  model = nextModel;
  ({
    button, debugEvidence, el, icon, iconButton, identifiers, rerender, searchableChoice, send,
    sendExactChoice, stringField, text, titled, topLevelRecords,
  } = view);
  return debugView();
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
      rerender();
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

const api: PocketHiveDebugViewsApi = Object.freeze({ render: renderView });
(globalThis as typeof globalThis & { PocketHiveDebugViews: PocketHiveDebugViewsApi }).PocketHiveDebugViews = api;
})();

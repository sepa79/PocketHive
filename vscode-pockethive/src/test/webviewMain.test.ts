import assert from 'node:assert/strict';
import test from 'node:test';

interface FakeMessageEvent {
  readonly data: unknown;
}

class FakeElement {
  readonly children: FakeElement[] = [];
  readonly dataset: Record<string, string> = {};
  readonly attributes = new Map<string, string>();
  readonly listeners = new Map<string, Array<() => void>>();
  className = '';
  textContent = '';
  title = '';
  id = '';
  name = '';
  value = '';
  placeholder = '';
  type = '';
  autocomplete = '';
  required = false;
  disabled = false;
  tabIndex = 0;

  constructor(readonly tagName: string) {}

  append(...children: Array<FakeElement | undefined>): void {
    for (const child of children) {
      if (child) this.children.push(child);
    }
  }

  replaceChildren(...children: Array<FakeElement | undefined>): void {
    this.children.length = 0;
    this.append(...children);
  }

  setAttribute(name: string, value: string): void {
    this.attributes.set(name, value);
  }

  getAttribute(name: string): string | null {
    return this.attributes.get(name) ?? null;
  }

  addEventListener(type: string, listener: () => void): void {
    const listeners = this.listeners.get(type) ?? [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }

  click(): void {
    if (this.disabled) return;
    for (const listener of this.listeners.get('click') ?? []) listener();
  }

  dispatch(type: string): void {
    for (const listener of this.listeners.get(type) ?? []) listener();
  }

  scrollIntoView(): void {}

  querySelector(selector: string): FakeElement | null {
    if (selector.startsWith('#')) {
      return findFirst(this, element => element.id === selector.slice(1));
    }
    return null;
  }
}

function findFirst(root: FakeElement, predicate: (element: FakeElement) => boolean): FakeElement | null {
  if (predicate(root)) return root;
  for (const child of root.children) {
    const found = findFirst(child, predicate);
    if (found) return found;
  }
  return null;
}

test('scenarios view emits reconcile command for ambiguous publication attempt', async () => {
  const postedMessages: unknown[] = [];
  let windowMessageHandler: ((event: FakeMessageEvent) => void) | undefined;
  const app = new FakeElement('main');
  app.dataset.logo = 'logo.svg';
  const announcer = new FakeElement('div');

  const previousWindow = globalThis.window;
  const previousDocument = globalThis.document;
  const previousAcquire = (globalThis as Record<string, unknown>).acquireVsCodeApi;
  const previousFilters = (globalThis as Record<string, unknown>).PocketHiveEventFilters;
  const previousAnimationFrame = globalThis.requestAnimationFrame;
  const modulePath = require.resolve('../webview/main');

  (globalThis as Record<string, unknown>).window = {
    addEventListener: (type: string, listener: (event: FakeMessageEvent) => void) => {
      if (type === 'message') windowMessageHandler = listener;
    },
  };
  (globalThis as Record<string, unknown>).document = {
    querySelector: (selector: string) => {
      if (selector === '#app') return app;
      if (selector === '#announcer') return announcer;
      return null;
    },
    createElement: (tag: string) => new FakeElement(tag),
  };
  (globalThis as Record<string, unknown>).acquireVsCodeApi = () => ({
    postMessage: (message: unknown) => { postedMessages.push(message); },
  });
  (globalThis as Record<string, unknown>).PocketHiveEventFilters = {
    TIME_WINDOWS: { ALL: 'ALL', FIFTEEN_MINUTES: 'FIFTEEN_MINUTES', ONE_HOUR: 'ONE_HOUR' },
    filterEvents: <T,>(events: readonly T[]) => [...events],
  };
  globalThis.requestAnimationFrame = callback => {
    callback(0);
    return 1;
  };

  delete require.cache[modulePath];
  try {
    require('../webview/main');
    assert.deepEqual(postedMessages, [{ type: 'ready' }]);
    assert.ok(windowMessageHandler);

    windowMessageHandler!({
      data: {
        type: 'viewModel',
        model: {
          page: 'workspace',
          profiles: [],
          activeProfile: { displayName: 'NFT Lab', status: 'Connected' },
          activeTab: 'Scenarios',
          workspaceData: [],
          swarmOperations: { START: 'START', STOP: 'STOP', REMOVE: 'REMOVE' },
          swarmPrimaryActions: {},
          debugActions: [],
          session: {
            status: 'Connected',
            message: 'Connected',
            canUseWorkspace: true,
            canSignIn: false,
            canSignOut: true,
          },
          bundleResult: {
            publicationError: {
              code: 'PUBLICATION_RESULT_AMBIGUOUS',
              message: 'PUBLICATION_RESULT_AMBIGUOUS: HTTP 409',
              attemptId: 'pa-123',
            },
          },
          busy: false,
        },
      },
    });

    const button = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Reconcile attempt');
    assert.ok(button);

    button.click();

    assert.deepEqual(postedMessages.at(-1), {
      type: 'reconcilePublicationAttempt',
      attemptId: 'pa-123',
    });
  } finally {
    delete require.cache[modulePath];
    globalThis.requestAnimationFrame = previousAnimationFrame;
    (globalThis as Record<string, unknown>).window = previousWindow;
    (globalThis as Record<string, unknown>).document = previousDocument;
    (globalThis as Record<string, unknown>).acquireVsCodeApi = previousAcquire;
    (globalThis as Record<string, unknown>).PocketHiveEventFilters = previousFilters;
  }
});

test('hive view emits exact bulk lifecycle and swarm detail commands', async () => {
  const postedMessages: unknown[] = [];
  let windowMessageHandler: ((event: FakeMessageEvent) => void) | undefined;
  const app = new FakeElement('main');
  app.dataset.logo = 'logo.svg';
  const announcer = new FakeElement('div');

  const previousWindow = globalThis.window;
  const previousDocument = globalThis.document;
  const previousAcquire = (globalThis as Record<string, unknown>).acquireVsCodeApi;
  const previousFilters = (globalThis as Record<string, unknown>).PocketHiveEventFilters;
  const previousAnimationFrame = globalThis.requestAnimationFrame;
  const modulePath = require.resolve('../webview/main');

  (globalThis as Record<string, unknown>).window = {
    addEventListener: (type: string, listener: (event: FakeMessageEvent) => void) => {
      if (type === 'message') windowMessageHandler = listener;
    },
  };
  (globalThis as Record<string, unknown>).document = {
    querySelector: (selector: string) => {
      if (selector === '#app') return app;
      if (selector === '#announcer') return announcer;
      return null;
    },
    createElement: (tag: string) => new FakeElement(tag),
  };
  (globalThis as Record<string, unknown>).acquireVsCodeApi = () => ({
    postMessage: (message: unknown) => { postedMessages.push(message); },
  });
  (globalThis as Record<string, unknown>).PocketHiveEventFilters = {
    TIME_WINDOWS: { ALL: 'ALL', FIFTEEN_MINUTES: 'FIFTEEN_MINUTES', ONE_HOUR: 'ONE_HOUR' },
    filterEvents: <T,>(events: readonly T[]) => [...events],
  };
  globalThis.requestAnimationFrame = callback => {
    callback(0);
    return 1;
  };

  delete require.cache[modulePath];
  try {
    require('../webview/main');
    assert.ok(windowMessageHandler);

    windowMessageHandler!({
      data: {
        type: 'viewModel',
        model: {
          page: 'workspace',
          profiles: [],
          activeProfile: { displayName: 'NFT Lab', status: 'Connected' },
          activeTab: 'Hive',
          workspaceData: [
            {
              id: 'checkout-load',
              controllerState: 'READY',
              workloadState: 'RUNNING',
              runtimeResourceState: 'PRESENT',
              observationStale: false,
              beeCount: 8,
            },
            {
              id: 'nightly-smoke',
              controllerState: 'READY',
              workloadState: 'STOPPED',
              runtimeResourceState: 'PRESENT',
              observationStale: false,
              beeCount: 4,
            },
          ],
          swarmOperations: { START: 'START', STOP: 'STOP', REMOVE: 'REMOVE' },
          swarmPrimaryActions: { 'checkout-load': 'STOP', 'nightly-smoke': 'START' },
          debugActions: [],
          session: {
            status: 'Connected',
            message: 'Connected',
            canUseWorkspace: true,
            canSignIn: false,
            canSignOut: true,
          },
          busy: false,
        },
      },
    });

    const startAll = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Start all');
    const stopAll = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Stop all');
    const create = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Create swarm');
    const details = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Details');

    assert.ok(startAll);
    assert.ok(stopAll);
    assert.ok(create);
    assert.ok(details);

    create.click();
    assert.deepEqual(postedMessages.at(-1), { type: 'openCreateSwarm' });

    startAll.click();
    assert.deepEqual(postedMessages.at(-1), { type: 'runSwarmBatchOperation', action: 'START' });

    stopAll.click();
    assert.deepEqual(postedMessages.at(-1), { type: 'runSwarmBatchOperation', action: 'STOP' });

    details.click();
    assert.deepEqual(postedMessages.at(-1), { type: 'openSwarmDetails', swarmId: 'checkout-load' });
  } finally {
    delete require.cache[modulePath];
    globalThis.requestAnimationFrame = previousAnimationFrame;
    (globalThis as Record<string, unknown>).window = previousWindow;
    (globalThis as Record<string, unknown>).document = previousDocument;
    (globalThis as Record<string, unknown>).acquireVsCodeApi = previousAcquire;
    (globalThis as Record<string, unknown>).PocketHiveEventFilters = previousFilters;
  }
});

test('scenarios view emits exact scenario section and deployed-file preview commands', async () => {
  const postedMessages: unknown[] = [];
  let windowMessageHandler: ((event: FakeMessageEvent) => void) | undefined;
  const app = new FakeElement('main');
  app.dataset.logo = 'logo.svg';
  const announcer = new FakeElement('div');

  const previousWindow = globalThis.window;
  const previousDocument = globalThis.document;
  const previousAcquire = (globalThis as Record<string, unknown>).acquireVsCodeApi;
  const previousFilters = (globalThis as Record<string, unknown>).PocketHiveEventFilters;
  const previousAnimationFrame = globalThis.requestAnimationFrame;
  const modulePath = require.resolve('../webview/main');

  (globalThis as Record<string, unknown>).window = {
    addEventListener: (type: string, listener: (event: FakeMessageEvent) => void) => {
      if (type === 'message') windowMessageHandler = listener;
    },
  };
  (globalThis as Record<string, unknown>).document = {
    querySelector: (selector: string) => {
      if (selector === '#app') return app;
      if (selector === '#announcer') return announcer;
      return null;
    },
    createElement: (tag: string) => new FakeElement(tag),
  };
  (globalThis as Record<string, unknown>).acquireVsCodeApi = () => ({
    postMessage: (message: unknown) => { postedMessages.push(message); },
  });
  (globalThis as Record<string, unknown>).PocketHiveEventFilters = {
    TIME_WINDOWS: { ALL: 'ALL', FIFTEEN_MINUTES: 'FIFTEEN_MINUTES', ONE_HOUR: 'ONE_HOUR' },
    filterEvents: <T,>(events: readonly T[]) => [...events],
  };
  globalThis.requestAnimationFrame = callback => {
    callback(0);
    return 1;
  };

  delete require.cache[modulePath];
  try {
    require('../webview/main');
    assert.ok(windowMessageHandler);

    windowMessageHandler!({
      data: {
        type: 'viewModel',
        model: {
          page: 'workspace',
          profiles: [],
          activeProfile: { displayName: 'NFT Lab', status: 'Connected' },
          activeTab: 'Scenarios',
          workspaceData: [{
            bundleKey: 'bundles/mixed-smoke',
            bundlePath: 'bundles/mixed-smoke',
            id: 'mixed-smoke',
            name: 'Mixed Smoke',
            description: 'Mixed assets smoke test',
            folderPath: 'bundles',
            bees: [{ role: 'generator', image: 'generator:latest' }],
            defunct: false,
          }],
          scenarioFocusScenarioId: 'mixed-smoke',
          scenarioFocusBundleKey: 'bundles/mixed-smoke',
          scenarioFocusSection: 'FILES',
          scenarioFocusTree: {
            bundleKey: 'bundles/mixed-smoke',
            nodes: [{
              bundleKey: 'bundles/mixed-smoke',
              path: 'scenario.yaml',
              name: 'scenario.yaml',
              nodeType: 'file',
              mediaType: 'text/plain',
              editorKind: 'yaml',
              writable: false,
              size: 120,
            }],
          },
          swarmOperations: { START: 'START', STOP: 'STOP', REMOVE: 'REMOVE' },
          swarmPrimaryActions: {},
          debugActions: [],
          session: {
            status: 'Connected',
            message: 'Connected',
            canUseWorkspace: true,
            canSignIn: false,
            canSignOut: true,
          },
          busy: false,
        },
      },
    });

    const details = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Open details');
    const inputs = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Inputs');
    const preview = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Preview');

    assert.ok(details);
    assert.ok(inputs);
    assert.ok(preview);

    details.click();
    assert.deepEqual(postedMessages.at(-1), { type: 'openScenarioDetails', scenarioId: 'mixed-smoke' });

    inputs.click();
    assert.deepEqual(postedMessages.at(-1), {
      type: 'selectScenarioSection',
      scenarioId: 'mixed-smoke',
      bundleKey: 'bundles/mixed-smoke',
      section: 'INPUTS',
    });

    preview.click();
    assert.deepEqual(postedMessages.at(-1), {
      type: 'openScenarioBundleFile',
      bundleKey: 'bundles/mixed-smoke',
      path: 'scenario.yaml',
    });
  } finally {
    delete require.cache[modulePath];
    globalThis.requestAnimationFrame = previousAnimationFrame;
    (globalThis as Record<string, unknown>).window = previousWindow;
    (globalThis as Record<string, unknown>).document = previousDocument;
    (globalThis as Record<string, unknown>).acquireVsCodeApi = previousAcquire;
    (globalThis as Record<string, unknown>).PocketHiveEventFilters = previousFilters;
  }
});

test('create swarm form emits exact template selection and create commands', async () => {
  const postedMessages: unknown[] = [];
  let windowMessageHandler: ((event: FakeMessageEvent) => void) | undefined;
  const app = new FakeElement('main');
  app.dataset.logo = 'logo.svg';
  const announcer = new FakeElement('div');

  const previousWindow = globalThis.window;
  const previousDocument = globalThis.document;
  const previousAcquire = (globalThis as Record<string, unknown>).acquireVsCodeApi;
  const previousFilters = (globalThis as Record<string, unknown>).PocketHiveEventFilters;
  const previousAnimationFrame = globalThis.requestAnimationFrame;
  const modulePath = require.resolve('../webview/main');

  (globalThis as Record<string, unknown>).window = {
    addEventListener: (type: string, listener: (event: FakeMessageEvent) => void) => {
      if (type === 'message') windowMessageHandler = listener;
    },
  };
  (globalThis as Record<string, unknown>).document = {
    querySelector: (selector: string) => {
      if (selector === '#app') return app;
      if (selector === '#announcer') return announcer;
      return null;
    },
    createElement: (tag: string) => new FakeElement(tag),
  };
  (globalThis as Record<string, unknown>).acquireVsCodeApi = () => ({
    postMessage: (message: unknown) => { postedMessages.push(message); },
  });
  (globalThis as Record<string, unknown>).PocketHiveEventFilters = {
    TIME_WINDOWS: { ALL: 'ALL', FIFTEEN_MINUTES: 'FIFTEEN_MINUTES', ONE_HOUR: 'ONE_HOUR' },
    filterEvents: <T,>(events: readonly T[]) => [...events],
  };
  globalThis.requestAnimationFrame = callback => {
    callback(0);
    return 1;
  };

  delete require.cache[modulePath];
  try {
    require('../webview/main');
    assert.ok(windowMessageHandler);

    windowMessageHandler!({
      data: {
        type: 'viewModel',
        model: {
          page: 'workspace',
          profiles: [],
          activeProfile: { displayName: 'NFT Lab', status: 'Connected' },
          activeTab: 'Hive',
          workspaceData: [],
          createSwarmForm: {
            templates: [
              { id: 'mixed-smoke', name: 'Mixed Smoke', defunct: false },
              { id: 'defunct-template', name: 'Defunct', defunct: true },
            ],
            selectedTemplateId: 'mixed-smoke',
            selectedScenarioId: 'mixed-smoke',
            sutIds: ['wiremock-local'],
          },
          swarmOperations: { START: 'START', STOP: 'STOP', REMOVE: 'REMOVE' },
          swarmPrimaryActions: {},
          debugActions: [],
          session: {
            status: 'Connected',
            message: 'Connected',
            canUseWorkspace: true,
            canSignIn: false,
            canSignOut: true,
          },
          busy: false,
        },
      },
    });

    const template = findFirst(app, element => element.tagName === 'select' && element.id === 'createSwarmTemplate');
    const swarmId = findFirst(app, element => element.tagName === 'input' && element.id === 'createSwarmId');
    const sut = findFirst(app, element => element.tagName === 'select' && element.id === 'createSwarmSut');
    const variables = findFirst(app, element => element.tagName === 'input' && element.id === 'createSwarmVariablesProfile');
    const create = findFirst(app, element => element.tagName === 'button' && element.textContent === 'Create swarm');

    assert.ok(template);
    assert.ok(swarmId);
    assert.ok(sut);
    assert.ok(variables);
    assert.ok(create);

    template.value = 'mixed-smoke';
    template.dispatch('change');
    assert.deepEqual(postedMessages.at(-1), {
      type: 'selectCreateSwarmTemplate',
      templateId: 'mixed-smoke',
      scenarioId: 'mixed-smoke',
    });

    swarmId.value = 'checkout-load';
    swarmId.dispatch('input');
    sut.value = 'wiremock-local';
    sut.dispatch('change');
    variables.value = 'vars-smoke';
    variables.dispatch('input');
    create.click();

    assert.deepEqual(postedMessages.at(-1), {
      type: 'submitCreateSwarm',
      swarmId: 'checkout-load',
      templateId: 'mixed-smoke',
      scenarioId: 'mixed-smoke',
      sutId: 'wiremock-local',
      variablesProfileId: 'vars-smoke',
    });
  } finally {
    delete require.cache[modulePath];
    globalThis.requestAnimationFrame = previousAnimationFrame;
    (globalThis as Record<string, unknown>).window = previousWindow;
    (globalThis as Record<string, unknown>).document = previousDocument;
    (globalThis as Record<string, unknown>).acquireVsCodeApi = previousAcquire;
    (globalThis as Record<string, unknown>).PocketHiveEventFilters = previousFilters;
  }
});

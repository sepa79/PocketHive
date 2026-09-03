import assert from 'node:assert/strict';
import test from 'node:test';

require('../webview/debugEvidence');
require('../webview/scenarioViews');
require('../webview/hiveViews');
require('../webview/debugViews');
require('../webview/eventViews');
require('../webview/environmentViews');

interface FakeMessageEvent {
  readonly data: unknown;
}

interface FakeDomEvent {
  preventDefault(): void;
}

class FakeElement {
  readonly children: FakeElement[] = [];
  readonly dataset: Record<string, string> = {};
  readonly attributes = new Map<string, string>();
  readonly listeners = new Map<string, Array<(event: FakeDomEvent) => void>>();
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

  addEventListener(type: string, listener: (event: FakeDomEvent) => void): void {
    const listeners = this.listeners.get(type) ?? [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }

  click(): void {
    if (this.disabled) return;
    const event = { preventDefault: () => undefined };
    for (const listener of this.listeners.get('click') ?? []) listener(event);
  }

  dispatch(type: string): void {
    const event = { preventDefault: () => undefined };
    for (const listener of this.listeners.get(type) ?? []) listener(event);
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

function findAll(root: FakeElement, predicate: (element: FakeElement) => boolean): FakeElement[] {
  const matches = predicate(root) ? [root] : [];
  for (const child of root.children) matches.push(...findAll(child, predicate));
  return matches;
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

    const workspaceModel = {
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
          environmentHealth: {
            status: 'DEGRADED',
            observedAt: '2026-08-21T12:00:00Z',
            services: [
              {
                id: 'pockethive-ui', name: 'PocketHive UI', endpoint: 'https://nft-lab.example/',
                status: 'HEALTHY', observedAt: '2026-08-21T12:00:00Z',
              },
              {
                id: 'wiremock', name: 'WireMock', endpoint: 'https://nft-lab.example/wiremock/',
                status: 'UNAVAILABLE', observedAt: '2026-08-21T12:00:00Z',
              },
              {
                id: 'custom-proxy', name: 'Network Proxy', endpoint: 'https://nft-lab.example/proxy/',
                status: 'HEALTHY', observedAt: '2026-08-21T12:00:00Z',
              },
            ],
          },
          bundleResult: {
            publicationError: {
              code: 'PUBLICATION_RESULT_AMBIGUOUS',
              message: 'PUBLICATION_RESULT_AMBIGUOUS: HTTP 409',
              attemptId: 'pa-123',
            },
          },
          busy: false,
    };
    windowMessageHandler!({ data: { type: 'viewModel', model: workspaceModel } });

    assert.equal(findFirst(app, element => element.tagName === 'header'), null,
      'the narrow workspace must not reserve space for a global brand header');
    const back = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Environments');
    assert.ok(back);
    assert.ok(findFirst(back, element => element.className.includes('codicon-arrow-left')));
    const accessibleTitle = findFirst(app, element => element.tagName === 'h1');
    assert.ok(accessibleTitle, 'the compact workspace must retain one accessible page title');
    assert.equal(accessibleTitle.textContent, 'NFT Lab');
    assert.ok(accessibleTitle.className.includes('sr-only'),
      'the accessible title must not restore the removed visual headspace');
    assert.ok(findFirst(app, element =>
      element.tagName === 'summary' && element.getAttribute('aria-label') === 'Account'));
    assert.equal(findFirst(app, element => element.className.includes('workspace-heading')), null,
      'environment identity must have exactly one presentation owner in the footer');
    const health = findFirst(app, element => element.className.includes('environment-health'));
    assert.ok(health, 'an open environment must keep one fixed environment health surface');
    assert.ok(findFirst(health, element => element.tagName === 'img'
      && element.className.includes('environment-health__mark')),
    'the environment footer must use the packaged PocketHive hexagon');
    assert.ok(findFirst(health, element => element.className.includes('environment-health__chevron')
      && element.className.includes('codicon-chevron-up')),
    'the closed bottom health drawer must continue to point toward its upward opening direction');
    assert.ok(findFirst(health, element => element.textContent === 'NFT Lab'));
    assert.ok(findFirst(health, element => element.textContent === '1 service unavailable'));
    assert.ok(findFirst(health, element => element.textContent === 'PocketHive UI'));
    assert.ok(findFirst(health, element => element.textContent === 'https://nft-lab.example/'));
    assert.ok(findFirst(health, element => element.textContent === 'Healthy'));
    assert.ok(findFirst(health, element => element.textContent === 'WireMock'));
    assert.ok(findFirst(health, element => element.textContent === 'Unavailable'));
    const serviceRows = findAll(health, element => element.className.split(' ').includes('environment-service'));
    const uiService = serviceRows.find(row => findFirst(row, element => element.textContent === 'PocketHive UI'));
    const wireMockService = serviceRows.find(row => findFirst(row, element => element.textContent === 'WireMock'));
    const unexpectedService = serviceRows.find(row => findFirst(row, element => element.textContent === 'Network Proxy'));
    assert.ok(uiService && findFirst(uiService, element => element.className.includes('codicon-home')));
    assert.ok(wireMockService && findFirst(wireMockService, element => element.className.includes('codicon-beaker')));
    assert.ok(unexpectedService && findFirst(unexpectedService, element => element.className.includes('codicon-globe')),
      'an unexpected MCP service must use the explicit neutral globe fallback');
    assert.ok(findFirst(health, element =>
      element.tagName === 'summary' && element.getAttribute('aria-label') === 'Account'),
    'the account overlay trigger must be owned by the footer');

    const tabs = findAll(app, element => element.getAttribute('role') === 'tab');
    assert.equal(tabs.length, 5);
    assert.equal(tabs.every(tab => Boolean(findFirst(tab, element =>
      element.className.includes('codicon')))), true,
    'every top-level tab must have a coherent local icon');

    const deployedSource = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Deployed');
    const repositorySource = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Repository');
    assert.ok(deployedSource);
    assert.ok(repositorySource);
    assert.equal(deployedSource.getAttribute('aria-pressed'), 'true',
      'publication state must not override the explicit scenario source control');
    repositorySource.click();

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

    const workspaceModel = {
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
              bees: [
                { instance: 'checkout-generator-1', role: 'generator', image: 'generator:1.2.3' },
                { instance: 'checkout-request-builder-1', role: 'request-builder', image: 'request-builder:1.2.3' },
              ],
            },
            {
              id: 'nightly-smoke',
              controllerState: 'READY',
              workloadState: 'STOPPED',
              runtimeResourceState: 'PRESENT',
              observationStale: false,
              bees: [{ instance: 'nightly-generator-1', role: 'generator', image: 'generator:1.2.3' }],
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
    };
    windowMessageHandler!({ data: { type: 'viewModel', model: workspaceModel } });

    const swarmSearch = findFirst(app, element => element.name === 'swarmSearch');
    assert.equal(swarmSearch?.tagName, 'input');
    swarmSearch.value = 'nightly';
    swarmSearch.dispatch('input');
    assert.equal(findFirst(app, element => element.tagName === 'h3' && element.textContent === 'checkout-load'), null);
    assert.ok(findFirst(app, element => element.tagName === 'h3' && element.textContent === 'nightly-smoke'));
    swarmSearch.value = '';
    swarmSearch.dispatch('input');

    const startAll = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Start all');
    const stopAll = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Stop all');
    const create = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Create swarm');
    const details = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Details');
    const workers = findFirst(app, element =>
      element.tagName === 'summary' && element.getAttribute('aria-label') === 'Workers, 2');
    const stop = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Stop');
    const start = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Start');

    assert.ok(startAll);
    assert.ok(stopAll);
    assert.ok(create);
    assert.equal(details, null, 'technical Details must be replaced by a human-readable worker disclosure');
    assert.ok(workers);
    assert.ok(findFirst(workers, element => element.className.includes('codicon-chevron-right')),
      'a closed worker disclosure must point right');
    assert.ok(stop);
    assert.ok(start);
    assert.ok(findFirst(create, element => element.className.includes('codicon-add')),
      'primary actions must use an icon-led button treatment');

    const swarmRows = findAll(app, element =>
      element.tagName === 'article' && element.className.split(' ').includes('swarm-row'));
    assert.equal(swarmRows.length, 2, 'each swarm must render as one independently grouped surface');
    const runningRemove = findFirst(swarmRows[0], element =>
      element.tagName === 'button' && element.getAttribute('aria-label') === 'Remove swarm');
    const readyRemove = findFirst(swarmRows[1], element =>
      element.tagName === 'button' && element.getAttribute('aria-label') === 'Remove swarm');
    assert.equal(runningRemove?.disabled, true, 'running swarms must not expose executable remove');
    assert.equal(readyRemove?.disabled, false, 'a fresh ready stopped swarm may be removed after confirmation');
    assert.ok(findFirst(swarmRows[0], element => element.textContent === 'checkout-generator-1'));
    assert.ok(findFirst(swarmRows[0], element => element.textContent === 'Generator'));
    const firstWorker = findFirst(swarmRows[0], element =>
      element.tagName === 'article' && element.className.split(' ').includes('swarm-worker'));
    assert.ok(firstWorker);
    const workerButtons = findAll(firstWorker, element => element.tagName === 'button');
    assert.deepEqual(workerButtons.map(button => button.getAttribute('aria-label')), ['Inspect', 'Logs']);
    workerButtons[0].click();
    assert.deepEqual(postedMessages.at(-1), {
      type: 'openDebugForWorker', swarmId: 'checkout-load', instance: 'checkout-generator-1', action: 'Inspect',
    });
    workerButtons[1].click();
    assert.deepEqual(postedMessages.at(-1), {
      type: 'openDebugForWorker', swarmId: 'checkout-load', instance: 'checkout-generator-1', action: 'Logs',
    });
    assert.ok(swarmRows[0].className.includes('swarm-row--running'),
      'running swarm must expose its lifecycle class for the group accent');
    assert.ok(swarmRows[1].className.includes('swarm-row--ready'),
      'ready swarm must expose its lifecycle class for the group accent');
    for (const row of swarmRows) {
      const heading = findFirst(row, element => element.className.includes('swarm-row__heading'));
      assert.ok(heading, 'swarm identity and status must share one scannable heading');
      assert.ok(findFirst(heading, element => element.className.split(' ').includes('status')),
        'swarm status must remain adjacent to its identity');
      assert.ok(findFirst(row, element => element.className.includes('swarm-row__secondary')),
        'supporting swarm actions must have a distinct secondary row');
      const historyDisclosure = findFirst(row, element =>
        element.tagName === 'button' && element.className.includes('history-toggle'));
      assert.ok(historyDisclosure, 'each swarm must own one full-width run-history disclosure');
      assert.equal(historyDisclosure.getAttribute('aria-expanded'), 'false');
      assert.ok(findFirst(historyDisclosure, element => element.className.includes('history-toggle__chevron')),
        'run-history label must remain left-aligned with a separate far-edge chevron');
      assert.ok(findFirst(historyDisclosure, element => element.className.includes('codicon-chevron-right')),
        'a closed run-history disclosure must point right');
    }

    create.click();
    assert.deepEqual(postedMessages.at(-1), { type: 'openCreateSwarm' });

    startAll.click();
    assert.deepEqual(postedMessages.at(-1), { type: 'runSwarmBatchOperation', action: 'START' });

    stopAll.click();
    assert.deepEqual(postedMessages.at(-1), { type: 'runSwarmBatchOperation', action: 'STOP' });

    const viewInWebUi = findFirst(swarmRows[0], element =>
      element.tagName === 'button' && element.getAttribute('aria-label') === 'View swarm in Web UI');
    assert.ok(viewInWebUi);
    viewInWebUi.click();
    assert.deepEqual(postedMessages.at(-1), {
      type: 'openWebUi', destination: 'SWARM', swarmId: 'checkout-load',
    });

    stop.click();
    assert.deepEqual(postedMessages.at(-1), {
      type: 'runSwarmOperation', action: 'STOP', swarmId: 'checkout-load',
    });

    start.click();
    assert.deepEqual(postedMessages.at(-1), {
      type: 'runSwarmOperation', action: 'START', swarmId: 'nightly-smoke',
    });

    readyRemove!.click();
    assert.deepEqual(postedMessages.at(-1), {
      type: 'runSwarmOperation', action: 'REMOVE', swarmId: 'nightly-smoke',
    });

    const runHistory = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Run history');
    assert.ok(runHistory);
    runHistory.click();
    assert.deepEqual(postedMessages.at(-1), { type: 'loadSwarmHistory', swarmId: 'checkout-load' });

    const debugActions = [
      { label: 'Workers', needsWorker: false },
      { label: 'Logs', needsWorker: true },
      { label: 'Version', needsWorker: true },
      { label: 'Inspect', needsWorker: true },
      { label: 'Runtime assessment', needsWorker: false },
      { label: 'Rabbit topology', needsWorker: false },
      { label: 'Timeline', needsWorker: false },
      { label: 'Cleanup plan', needsWorker: false },
    ];
    windowMessageHandler!({
      data: {
        type: 'viewModel',
        model: {
          ...workspaceModel,
          swarmHistorySwarmId: 'checkout-load',
          swarmHistoryResult: [{ runId: 'run-42', entries: 9 }],
        },
      },
    });
    const openJournal = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Open journal');
    assert.ok(openJournal, 'historical runs must offer evidence navigation, not lifecycle restart');
    openJournal.click();
    assert.deepEqual(postedMessages.at(-1), {
      type: 'openJournalRun', swarmId: 'checkout-load', runId: 'run-42',
    });

    windowMessageHandler!({
      data: {
        type: 'viewModel',
        model: {
          ...workspaceModel,
          activeTab: 'Buzz',
          workspaceData: {
            items: [{
              detailId: 'buzz-detail-1',
              timestamp: '2026-08-19T10:42:18Z', severity: 'INFO', kind: 'signal',
              type: 'swarm-start', swarmId: 'nightly-smoke', origin: 'orchestrator', direction: 'OUT',
            }],
          },
        },
      },
    });
    const openBuzzWebUi = findFirst(app, element =>
      element.tagName === 'button' && element.getAttribute('aria-label') === 'Open Buzz in Web UI');
    assert.ok(openBuzzWebUi);
    openBuzzWebUi.click();
    assert.deepEqual(postedMessages.at(-1), { type: 'openWebUi', destination: 'BUZZ' });
    assert.ok(findFirst(app, element =>
      element.tagName === 'details' && element.className.includes('advanced-filters')),
    'secondary event filters must stay collapsed behind one advanced-filter control');
    const advancedFilter = findFirst(app, element =>
      element.tagName === 'summary' && element.getAttribute('aria-label') === 'Advanced filters');
    assert.ok(advancedFilter);
    assert.ok(findFirst(advancedFilter, element => element.className.includes('codicon-filter')),
      'advanced filters must use the compact filter icon treatment');
    assert.ok(findFirst(app, element => element.className.includes('event-row__identity')),
      'event summary must use one condensed identity line');
    const buzzEvent = findFirst(app, element => element.className.split(' ').includes('event-row'));
    assert.ok(buzzEvent);
    assert.equal(findFirst(buzzEvent, element =>
      element.tagName === 'button' && element.getAttribute('aria-label') === 'Open Debug'), null,
    'Buzz records must leave diagnostics to the dedicated Debug workspace');

    windowMessageHandler!({
      data: {
        type: 'viewModel',
        model: { ...workspaceModel, activeTab: 'Journal' },
      },
    });
    const exactSwarm = findFirst(app, element => element.name === 'journalSwarm');
    assert.equal(exactSwarm?.tagName, 'input');
    assert.equal(exactSwarm?.getAttribute('role'), 'combobox');
    assert.equal(exactSwarm?.getAttribute('aria-label'), 'Exact swarm');
    assert.equal(exactSwarm?.getAttribute('aria-autocomplete'), 'list');
    assert.ok(findFirst(app, element => element.tagName === 'label'
      && element.getAttribute('for') === 'journalSwarm'),
    'the exact-swarm label must target the input without wrapping interactive options');
    assert.ok(findFirst(app, element => element.getAttribute('role') === 'listbox'),
      'exact swarm must expose an integrated searchable listbox');

    windowMessageHandler!({
      data: {
        type: 'viewModel',
        model: {
          ...workspaceModel,
          activeTab: 'Journal',
          journalSwarmId: 'nightly-smoke',
          journalRunId: 'run-42',
          journalResult: {
            items: [{
              detailId: 'journal-detail-1',
              timestamp: '2026-08-19T10:42:18Z', severity: 'INFO', kind: 'outcome',
              type: 'swarm-start', swarmId: 'nightly-smoke', runId: 'run-42', origin: 'orchestrator',
            }],
          },
        },
      },
    });
    const journalEvent = findFirst(app, element => element.className.split(' ').includes('event-row'));
    assert.ok(journalEvent);
    const viewJournalRun = findFirst(journalEvent, element =>
      element.tagName === 'button' && element.getAttribute('aria-label') === 'View run in Web UI');
    assert.ok(viewJournalRun);
    const openJournalDetails = findFirst(journalEvent, element =>
      element.tagName === 'button' && element.getAttribute('aria-label') === 'Open technical details');
    assert.ok(openJournalDetails);
    assert.equal(findFirst(journalEvent, element =>
      element.tagName === 'button' && element.getAttribute('aria-label') === 'Open Debug'), null,
    'Journal records must leave diagnostics to the dedicated Debug workspace');
    openJournalDetails.click();
    assert.deepEqual(postedMessages.at(-1), {
      type: 'openEventDetails', detailId: 'journal-detail-1',
    });
    viewJournalRun.click();
    assert.deepEqual(postedMessages.at(-1), {
      type: 'openWebUi', destination: 'JOURNAL_RUN', swarmId: 'nightly-smoke', runId: 'run-42',
    });

    windowMessageHandler!({
      data: {
        type: 'viewModel',
        model: {
          ...workspaceModel,
          activeTab: 'Debug',
          debugSwarmId: 'checkout-load',
          debugRuntimeId: 'request-builder-7f8c9',
          debugWorkersResult: [{ runtimeId: 'request-builder-7f8c9' }],
          debugAction: 'Logs',
          debugResult: {
            target: { runtimeId: 'request-builder-7f8c9', instance: 'request-builder-1' },
            tailLines: 200,
            since: null,
            redacted: true,
            lineCount: 1,
            logs: 'Bounded evidence',
          },
          debugActions,
        },
      },
    });

    const targetContext = findFirst(app, element => element.className.includes('debug-scope'));
    assert.ok(targetContext, 'Debug must expose compact Worker and Swarm context navigation');
    assert.deepEqual(findAll(targetContext, element => element.tagName === 'button').map(item => item.textContent),
      ['Worker', 'Swarm']);

    const runtimeTarget = findFirst(app, element => element.className.includes('debug-runtime-target'));
    assert.ok(runtimeTarget, 'worker diagnostics must share one coherent runtime-target surface');
    const loadWorkers = findFirst(runtimeTarget, element => element.tagName === 'button'
      && element.getAttribute('aria-label') === 'Load workers');
    assert.ok(loadWorkers);
    loadWorkers.click();
    assert.deepEqual(postedMessages.at(-1), { type: 'runDebug', action: 'Workers' });

    const exactWorker = findFirst(runtimeTarget, element => element.name === 'debugWorker');
    assert.equal(exactWorker?.tagName, 'input');
    assert.equal(exactWorker?.getAttribute('role'), 'combobox');
    assert.equal(exactWorker?.getAttribute('aria-label'), 'Exact worker');
    const workerResource = findFirst(runtimeTarget, element =>
      element.className.includes('debug-worker-resource'));
    assert.ok(workerResource);
    assert.ok(findFirst(workerResource, element => element.textContent === 'request-builder-7f8c9'),
      'the exact selected runtime resource must remain visible beside worker diagnostics');

    const diagnosticTabs = findFirst(runtimeTarget, element => element.className.includes('debug-worker-tabs'));
    assert.ok(diagnosticTabs);
    const workerActions = findAll(diagnosticTabs, element => element.tagName === 'button');
    assert.deepEqual(workerActions.map(item => item.getAttribute('aria-label')), ['Logs', 'Inspect', 'Version']);
    assert.deepEqual(workerActions.map(item => item.getAttribute('role')), ['tab', 'tab', 'tab']);
    assert.equal(workerActions[0].getAttribute('aria-selected'), 'true');
    workerActions[0].click();
    assert.deepEqual(postedMessages.at(-1), { type: 'runDebug', action: 'Logs', tailLines: 200 });
    workerActions[1].click();
    assert.deepEqual(postedMessages.at(-1), { type: 'runDebug', action: 'Inspect' });
    workerActions[2].click();
    assert.deepEqual(postedMessages.at(-1), { type: 'runDebug', action: 'Version' });

    const workerEvidence = findFirst(runtimeTarget, element => element.className.includes('debug-evidence'));
    assert.ok(workerEvidence);
    assert.ok(findFirst(workerEvidence, element => element.textContent === 'Container logs'));
    assert.ok(findFirst(workerEvidence, element => element.textContent === 'Docker stdout/stderr · tail 200'));

    windowMessageHandler!({ data: { type: 'viewModel', model: {
      ...workspaceModel,
      activeTab: 'Debug',
      debugSwarmId: 'checkout-load',
      debugRuntimeId: 'request-builder-7f8c9',
      debugWorkersResult: [{ runtimeId: 'request-builder-7f8c9' }],
      debugAction: 'Inspect',
      debugResult: {
        target: { runtimeId: 'request-builder-7f8c9' },
        source: { owner: 'orchestrator', available: true },
        state: { status: 'running', running: true },
        createdAt: '2026-08-23T10:00:00Z',
        restartCount: 2,
        restartPolicy: 'on-failure',
        mounts: [{ type: 'volume', name: 'ph-data', destination: '/data' }],
        networks: ['pockethive'],
      },
      debugActions,
    } } });
    assert.ok(findFirst(app, element => element.textContent === 'Container inspect'));
    assert.ok(findFirst(app, element => element.tagName === 'pre'
      && element.textContent.includes('"restartPolicy": "on-failure"')));

    windowMessageHandler!({ data: { type: 'viewModel', model: {
      ...workspaceModel,
      activeTab: 'Debug',
      debugSwarmId: 'checkout-load',
      debugRuntimeId: 'request-builder-7f8c9',
      debugWorkersResult: [{ runtimeId: 'request-builder-7f8c9' }],
      debugAction: 'Version',
      debugResult: {
        target: { runtimeId: 'request-builder-7f8c9' },
        declaredVersion: '1.2.3',
        image: 'request-builder:1.2.3',
        imageTag: '1.2.3',
        imageDigest: 'sha256:abc',
        reportedVersion: '1.2.3',
        reportedVersionSource: 'pockethive.version',
      },
      debugActions,
    } } });
    assert.ok(findFirst(app, element => element.textContent === 'Deployed version'));
    assert.ok(findFirst(app, element => element.textContent === 'sha256:abc'));

    const swarmTools = findFirst(app, element => element.className.includes('debug-swarm-tools'));
    assert.ok(swarmTools);
    assert.deepEqual(findAll(swarmTools, element => element.tagName === 'button')
      .map(item => item.getAttribute('aria-label')), [
      'Workers', 'Runtime assessment', 'Rabbit topology', 'Timeline',
    ]);
    const maintenance = findFirst(app, element => element.className.includes('debug-maintenance'));
    assert.ok(maintenance);
    assert.ok(findFirst(maintenance, element => element.tagName === 'button'
      && element.getAttribute('aria-label') === 'Cleanup plan'));
    assert.ok(findFirst(maintenance, element => element.textContent === 'Plan only'));
    assert.equal(findFirst(app, element => element.className.includes('debug-group')), null,
      'the redesigned Debug page must not retain the disjointed disclosure stack');

    windowMessageHandler!({ data: { type: 'viewModel', model: {
      ...workspaceModel,
      activeTab: 'Debug',
      debugSwarmId: 'checkout-load',
      debugAction: 'Runtime assessment',
      debugResult: {
        assessmentContractVersion: '1',
        overall: 'DRIFTED',
        checks: [
          { check: 'REGISTRY', state: 'CONSISTENT', summary: 'Exact swarm is registered.' },
          { check: 'RUNTIME_INVENTORY', state: 'DRIFTED', summary: 'One runtime is missing.' },
        ],
      },
      debugActions,
    } } });
    assert.ok(findFirst(app, element => element.textContent === 'Runtime assessment'));
    assert.ok(findFirst(app, element => element.textContent === 'DRIFTED'));
    assert.ok(findFirst(app, element => element.textContent === 'RUNTIME INVENTORY'));
    assert.ok(findFirst(app, element => element.textContent === 'One runtime is missing.'));

    windowMessageHandler!({ data: { type: 'viewModel', model: {
      ...workspaceModel,
      activeTab: 'Debug',
      debugSwarmId: 'checkout-load',
      debugAction: 'Cleanup plan',
      debugResult: {
        candidateSetHash: 'sha256:a91c7be2',
        executionRisk: 'standard',
        candidates: [{ candidateId: 'docker:container:abc', reason: 'stopped PocketHive runtime resource' }],
        blocked: [],
      },
      debugActions: [
        { label: 'Workers', needsWorker: false },
        { label: 'Cleanup plan', needsWorker: false },
      ],
    } } });
    const executeCleanup = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Execute cleanup');
    assert.equal(executeCleanup?.disabled, true);
    assert.ok(findFirst(app, element => element.textContent === 'Requires HiveGate approval'));
    assert.ok(findFirst(app, element => element.textContent === '1 cleanup candidate'));
  } finally {
    delete require.cache[modulePath];
    globalThis.requestAnimationFrame = previousAnimationFrame;
    (globalThis as Record<string, unknown>).window = previousWindow;
    (globalThis as Record<string, unknown>).document = previousDocument;
    (globalThis as Record<string, unknown>).acquireVsCodeApi = previousAcquire;
    (globalThis as Record<string, unknown>).PocketHiveEventFilters = previousFilters;
  }
});

test('scenarios view renders a nested file tree, one-column overview, and exact commands', async () => {
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
            controllerImage: 'swarm-controller:latest',
            bees: [{ role: 'generator', image: 'generator:latest' }],
            defunct: false,
          }],
          bundleResult: {
            publicationError: {
              attemptId: 'pa-retained',
              code: 'PUBLICATION_RESULT_AMBIGUOUS',
            },
          },
          scenarioFocusScenarioId: 'mixed-smoke',
          scenarioFocusBundleKey: 'bundles/mixed-smoke',
          scenarioFocusSection: 'FILES',
          scenarioFocusTree: {
            bundleKey: 'bundles/mixed-smoke',
            nodes: [
              { path: 'datasets', name: 'datasets', nodeType: 'directory' },
              { path: 'templates', name: 'templates', nodeType: 'directory' },
              { path: 'templates/http', name: 'http', nodeType: 'directory' },
              { path: 'templates/http/capability-controls', name: 'capability-controls', nodeType: 'directory' },
              {
                path: 'datasets/capability-controls.csv',
                name: 'capability-controls.csv',
                nodeType: 'file',
                editorKind: 'text',
                size: 57,
              },
              {
                path: 'scenario.yaml',
                name: 'scenario.yaml',
                nodeType: 'file',
                editorKind: 'yaml',
                size: 120,
              },
              {
                path: 'templates/http/capability-controls/control.yaml',
                name: 'control.yaml',
                nodeType: 'file',
                editorKind: 'yaml',
                size: 154,
              },
            ],
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

    const deployed = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Deployed');
    assert.ok(deployed);
    deployed.click();

    const inputs = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Inputs');
    const preview = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Preview');
    const sectionTabs = findFirst(app, element => element.className.includes('scenario-section-tabs'));

    assert.ok(inputs);
    assert.ok(preview);
    assert.ok(sectionTabs);
    assert.equal(sectionTabs.className.includes('compact-tabs'), true,
      'scenario sections must reuse the compact segmented tab treatment');
    const sectionButtons = findAll(sectionTabs, element => element.tagName === 'button');
    assert.deepEqual(sectionButtons.map(button => button.textContent), ['Overview', 'Files', 'Inputs']);
    assert.equal(sectionButtons.every(button => button.className.includes('compact-tab')), true);
    assert.equal(sectionButtons[1].getAttribute('aria-pressed'), 'true');
    for (const removedLabel of ['Open details', 'Open scenario.yaml', 'Open schema…', 'Open template…']) {
      assert.equal(findFirst(app, element =>
        element.tagName === 'button' && element.textContent === removedLabel), null);
    }

    const datasets = findFirst(app, element =>
      element.className.includes('scenario-tree__branch')
      && Boolean(findFirst(element, child => child.tagName === 'strong' && child.textContent === 'datasets')));
    const templates = findFirst(app, element =>
      element.className.includes('scenario-tree__branch')
      && Boolean(findFirst(element, child => child.tagName === 'strong' && child.textContent === 'templates')));
    assert.ok(datasets);
    assert.ok(templates);
    assert.ok(findFirst(datasets, element => element.textContent === 'capability-controls.csv'),
      'dataset files must be children of datasets');
    assert.ok(findFirst(templates, element => element.textContent === 'http'),
      'nested template directories must remain inside their parent');
    assert.ok(findFirst(templates, element => element.textContent === 'control.yaml'),
      'deep files must remain inside their complete directory ancestry');

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
      path: 'datasets/capability-controls.csv',
    });

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
            controllerImage: 'swarm-controller:latest',
            bees: [{ role: 'generator', image: 'generator:latest' }],
            defunct: false,
          }],
          bundleResult: {
            publicationError: {
              attemptId: 'pa-retained',
              code: 'PUBLICATION_RESULT_AMBIGUOUS',
            },
          },
          scenarioFocusScenarioId: 'mixed-smoke',
          scenarioFocusBundleKey: 'bundles/mixed-smoke',
          scenarioFocusSection: 'OVERVIEW',
          repositoryScenarios: {
            state: 'SCANNED',
            repositories: [{
              workspaceName: 'PocketHive',
              commit: 'a'.repeat(40),
              candidates: [{
                candidateId: 'candidate-1',
                bundlePath: 'scenarios/bundles/mixed-smoke',
                files: [
                  'datasets/capability-controls.csv',
                  'scenario.yaml',
                  'templates/http/capability-controls/control.yaml',
                ],
              }],
            }],
            failures: [],
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
    const deployedAfterRefresh = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Deployed');
    assert.ok(deployedAfterRefresh);
    assert.equal(deployedAfterRefresh.getAttribute('aria-pressed'), 'true',
      'deployed scenario actions must retain the explicitly selected source');
    const overview = findFirst(app, element => element.className.includes('scenario-overview'));
    assert.ok(overview);
    assert.equal(overview.children.length, 3);
    assert.deepEqual(overview.children.map(card => card.children[0]?.textContent), [
      'Description', 'Controller', 'Bees',
    ]);
    assert.equal(overview.children.every(card => card.className.includes('scenario-info-card--full')), true,
      'overview cards must each occupy a full row');
    assert.equal(findFirst(overview, element => element.className.includes('truncate')), null,
      'overview values must not be truncated');

    const repository = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Repository');
    assert.ok(repository, 'Scenarios must distinguish Git authoring candidates from deployed scenarios');
    repository.click();

    assert.ok(findFirst(app, element => element.textContent === 'scenarios/bundles/mixed-smoke'));
    assert.ok(findFirst(app, element => element.textContent.includes('Committed HEAD only')));
    assert.equal(findFirst(app, element => element.textContent.includes('/workspace/')), null,
      'repository view must not receive or render host filesystem paths');
    assert.ok(findFirst(app, element => element.id === 'repositorySearch'),
      'Repository scenarios must retain a compact local search at Side Bar width');
    assert.ok(findFirst(app, element => element.tagName === 'summary'
      && element.getAttribute('aria-label') === 'Repository filters'),
    'multi-root repository filtering must remain hidden behind one advanced filter control');

    let repositoryCard = findFirst(app, element =>
      element.className.split(' ').includes('repository-scenario'));
    assert.ok(repositoryCard, 'each committed candidate must render as a self-contained scenario card');
    assert.equal(repositoryCard.getAttribute('open'), null, 'Repository scenarios are collapsed by default');
    const repositorySummary = findFirst(repositoryCard, element => element.tagName === 'summary');
    assert.ok(repositorySummary);
    repositorySummary.click();
    repositoryCard = findFirst(app, element =>
      element.className.split(' ').includes('repository-scenario'));
    assert.ok(repositoryCard);
    assert.equal(repositoryCard.getAttribute('open'), '', 'a collapsed Repository scenario can be opened');
    const openRepositorySummary = findFirst(repositoryCard, element => element.tagName === 'summary');
    assert.ok(openRepositorySummary);
    openRepositorySummary.click();
    repositoryCard = findFirst(app, element =>
      element.className.split(' ').includes('repository-scenario'));
    assert.ok(repositoryCard);
    assert.equal(repositoryCard.getAttribute('open'), null,
      'the final open Repository scenario can be collapsed again');
    const collapsedRepositorySummary = findFirst(repositoryCard, element => element.tagName === 'summary');
    assert.ok(collapsedRepositorySummary);
    collapsedRepositorySummary.click();
    repositoryCard = findFirst(app, element =>
      element.className.split(' ').includes('repository-scenario'));
    assert.ok(repositoryCard);
    assert.equal(repositoryCard.getAttribute('open'), '', 'a collapsed Repository scenario can be reopened');
    const repositoryActions = findFirst(repositoryCard, element =>
      element.className.includes('repository-scenario__actions'));
    assert.ok(repositoryActions);
    assert.deepEqual(findAll(repositoryActions, element => element.tagName === 'button')
      .map(control => control.textContent), ['Edit', 'Validate', 'Deploy']);

    const edit = findFirst(repositoryCard, element =>
      element.tagName === 'button' && element.textContent === 'Edit');
    assert.ok(edit);
    edit.click();
    assert.deepEqual(postedMessages.at(-1), {
      type: 'openRepositoryBundleFile', candidateId: 'candidate-1', path: 'scenario.yaml',
    });

    const repositoryFiles = findFirst(repositoryCard, element =>
      element.tagName === 'button' && element.textContent === 'Files');
    assert.ok(repositoryFiles);
    repositoryFiles.click();
    const repositoryFileTree = findFirst(app, element =>
      element.className.includes('repository-scenario__tree'));
    assert.ok(repositoryFileTree);
    assert.ok(findFirst(repositoryFileTree, element => element.textContent === 'datasets'));
    assert.ok(findFirst(repositoryFileTree, element => element.textContent === 'control.yaml'));
    const fileEdit = findFirst(repositoryFileTree, element =>
      element.tagName === 'button' && element.textContent === 'Edit');
    assert.ok(fileEdit);
    fileEdit.click();
    assert.equal((postedMessages.at(-1) as { type?: string }).type, 'openRepositoryBundleFile');

    const validate = findFirst(repositoryCard, element =>
      element.tagName === 'button' && element.textContent === 'Validate');
    assert.ok(validate);
    validate.click();
    assert.deepEqual(postedMessages.at(-1), {
      type: 'validateRepositoryBundle', candidateId: 'candidate-1',
    });

    const deploy = findFirst(repositoryCard, element =>
      element.tagName === 'button' && element.textContent === 'Deploy');
    assert.ok(deploy);
    deploy.click();
    assert.deepEqual(postedMessages.at(-1), {
      type: 'deployRepositoryBundle', candidateId: 'candidate-1',
    });

    const chooseFolder = findFirst(app, element =>
      element.tagName === 'button' && element.textContent === 'Choose committed folder');
    assert.ok(chooseFolder);
    chooseFolder.click();
    assert.deepEqual(postedMessages.at(-1), { type: 'validateCommittedBundle' });

    windowMessageHandler!({
      data: {
        type: 'viewModel',
        model: {
          page: 'workspace', profiles: [], activeProfile: { displayName: 'NFT Lab', status: 'Connected' },
          activeTab: 'Scenarios', workspaceData: [{ id: 'mixed-smoke', name: 'Mixed Smoke' }],
          repositoryScenarios: {
            state: 'SCANNED', repositories: [{
              workspaceName: 'PocketHive', commit: 'a'.repeat(40), candidates: [{
                candidateId: 'candidate-1', bundlePath: 'scenarios/bundles/mixed-smoke',
                files: ['scenario.yaml'],
              }],
            }], failures: [],
          },
          pendingBundle: {
            source: { bundlePath: 'scenarios/bundles/mixed-smoke', commit: 'a'.repeat(40) },
            fileCount: 1,
            validationReceipt: {
              receiptId: 'vr-1', scenarioId: 'mixed-smoke', scenarioName: 'Mixed Smoke',
            },
          },
          repositoryPendingCandidateId: 'candidate-1',
          repositoryResultCandidateId: 'candidate-1',
          bundleResult: {
            validationReceipt: {
              receiptId: 'vr-1', scenarioId: 'mixed-smoke', scenarioName: 'Mixed Smoke',
            },
          },
          repositoryDeploymentConflict: {
            candidateId: 'candidate-1', scenarioId: 'mixed-smoke', scenarioName: 'Mixed Smoke',
            suggestedScenarioId: 'mixed-smoke-01', suggestedScenarioName: 'Mixed Smoke-01',
          },
          swarmOperations: { START: 'START', STOP: 'STOP', REMOVE: 'REMOVE' }, swarmPrimaryActions: {},
          debugActions: [], session: {
            status: 'Connected', message: 'Connected', canUseWorkspace: true, canSignIn: false, canSignOut: true,
          }, busy: false,
        },
      },
    });

    assert.ok(findFirst(app, element => element.textContent === 'Valid'));
    const validatedRepositoryCard = findFirst(app, element =>
      element.className.split(' ').includes('repository-scenario'));
    assert.ok(validatedRepositoryCard);
    assert.equal(findFirst(validatedRepositoryCard, element => element.tagName === 'pre'), null,
      'Repository validation evidence must remain a concise card projection, not duplicate raw JSON');
    const conflictDialog = findFirst(app, element => element.getAttribute('role') === 'dialog');
    assert.ok(conflictDialog);
    assert.ok(findFirst(conflictDialog, element => element.textContent.includes('already deployed')));
    const renameId = findFirst(conflictDialog, element => element.id === 'repositoryRenameScenarioId');
    const renameName = findFirst(conflictDialog, element => element.id === 'repositoryRenameScenarioName');
    assert.ok(renameId);
    assert.ok(renameName);
    assert.equal(renameId.value, 'mixed-smoke-01');
    assert.equal(renameName.value, 'Mixed Smoke-01');
    const replace = findFirst(conflictDialog, element =>
      element.tagName === 'button' && element.textContent === 'Replace existing');
    assert.ok(replace);
    replace.click();
    assert.deepEqual(postedMessages.at(-1), {
      type: 'replaceRepositoryBundle', candidateId: 'candidate-1',
    });
    renameId.value = 'mixed-smoke-copy';
    renameName.value = 'Mixed Smoke Copy';
    const openRename = findFirst(conflictDialog, element =>
      element.tagName === 'button' && element.textContent === 'Open scenario.yaml');
    assert.ok(openRename);
    openRename.click();
    assert.deepEqual(postedMessages.at(-1), {
      type: 'openRepositoryRename', candidateId: 'candidate-1',
      scenarioId: 'mixed-smoke-copy', scenarioName: 'Mixed Smoke Copy',
    });

    windowMessageHandler!({
      data: {
        type: 'viewModel',
        model: {
          page: 'workspace', profiles: [], activeProfile: { displayName: 'NFT Lab', status: 'Connected' },
          activeTab: 'Scenarios', workspaceData: [], repositoryScenarios: {
            state: 'UNTRUSTED', repositories: [], failures: [],
          },
          swarmOperations: { START: 'START', STOP: 'STOP', REMOVE: 'REMOVE' }, swarmPrimaryActions: {},
          debugActions: [], session: {
            status: 'Connected', message: 'Connected', canUseWorkspace: true, canSignIn: false, canSignOut: true,
          }, busy: false,
        },
      },
    });
    assert.ok(findFirst(app, element => element.textContent.includes('Trust this workspace')));

    windowMessageHandler!({
      data: {
        type: 'viewModel',
        model: {
          page: 'workspace', profiles: [], activeProfile: { displayName: 'NFT Lab', status: 'Connected' },
          activeTab: 'Scenarios', workspaceData: [], repositoryScenarios: {
            state: 'NO_WORKSPACE', repositories: [], failures: [],
          },
          swarmOperations: { START: 'START', STOP: 'STOP', REMOVE: 'REMOVE' }, swarmPrimaryActions: {},
          debugActions: [], session: {
            status: 'Connected', message: 'Connected', canUseWorkspace: true, canSignIn: false, canSignOut: true,
          }, busy: false,
        },
      },
    });
    assert.ok(findFirst(app, element => element.textContent.includes('Open a Git repository')));
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
            autoPullImages: true,
            networkMode: 'DIRECT',
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
    const autoPullImages = findFirst(app, element => element.tagName === 'select' && element.id === 'createSwarmAutoPullImages');
    const networkMode = findFirst(app, element => element.tagName === 'select' && element.id === 'createSwarmNetworkMode');
    const networkProfile = findFirst(app, element => element.tagName === 'input' && element.id === 'createSwarmNetworkProfile');
    const create = findFirst(app, element => element.tagName === 'button' && element.textContent === 'Create swarm');

    assert.ok(template);
    assert.ok(swarmId);
    assert.ok(sut);
    assert.ok(variables);
    assert.ok(autoPullImages);
    assert.ok(networkMode);
    assert.ok(networkProfile);
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
    autoPullImages.value = 'true';
    autoPullImages.dispatch('change');
    networkMode.value = 'PROXIED';
    networkMode.dispatch('change');
    networkProfile.value = 'proxy-a';
    networkProfile.dispatch('input');
    create.click();

    assert.deepEqual(postedMessages.at(-1), {
      type: 'submitCreateSwarm',
      swarmId: 'checkout-load',
      templateId: 'mixed-smoke',
      scenarioId: 'mixed-smoke',
      sutId: 'wiremock-local',
      variablesProfileId: 'vars-smoke',
      autoPullImages: true,
      networkMode: 'PROXIED',
      networkProfileId: 'proxy-a',
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

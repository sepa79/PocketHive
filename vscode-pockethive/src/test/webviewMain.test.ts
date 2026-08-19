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

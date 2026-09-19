import assert from 'node:assert/strict';
import test from 'node:test';

import { CurrentView } from '../webview/currentView';

test('disposing an obsolete webview cannot detach its replacement', () => {
  const current = new CurrentView<object>();
  const first = {};
  const replacement = {};

  current.attach(first);
  current.attach(replacement);
  current.detach(first);

  assert.equal(current.value(), replacement);
});

test('disposing the current webview detaches it', () => {
  const current = new CurrentView<object>();
  const view = {};

  current.attach(view);
  current.detach(view);

  assert.equal(current.value(), undefined);
});

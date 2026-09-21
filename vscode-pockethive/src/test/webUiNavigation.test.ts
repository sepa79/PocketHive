import assert from 'node:assert/strict';
import test from 'node:test';

import {
  resolvePocketHiveWebUiUrl,
  WEB_UI_DESTINATIONS,
} from '../webview/webUiNavigation';

const health = {
  status: 'DEGRADED',
  services: [
    { id: 'orchestrator', endpoint: 'https://lab.example/orchestrator/', status: 'HEALTHY' },
    { id: 'pockethive-ui', endpoint: 'https://lab.example/platform/', status: 'HEALTHY' },
  ],
};

test('web UI destinations resolve only from the exact environment-health UI endpoint', () => {
  assert.equal(resolvePocketHiveWebUiUrl(health, {
    destination: WEB_UI_DESTINATIONS.BUZZ,
  }), 'https://lab.example/platform/v2/buzz');
  assert.equal(resolvePocketHiveWebUiUrl(health, {
    destination: WEB_UI_DESTINATIONS.SWARM,
    swarmId: 'checkout/load',
  }), 'https://lab.example/platform/v2/hive/checkout%2Fload/view');
  assert.equal(resolvePocketHiveWebUiUrl(health, {
    destination: WEB_UI_DESTINATIONS.JOURNAL_RUN,
    swarmId: 'nightly smoke',
    runId: 'run/42',
  }), 'https://lab.example/platform/v2/journal/swarms/nightly%20smoke?runId=run%2F42');
});

test('web UI navigation rejects missing, duplicate, malformed, and unsafe owner endpoints', () => {
  assert.throws(() => resolvePocketHiveWebUiUrl({ services: [] }, {
    destination: WEB_UI_DESTINATIONS.BUZZ,
  }), /WEB_UI_ENDPOINT_MISSING/);
  assert.throws(() => resolvePocketHiveWebUiUrl({ services: [
    { id: 'pockethive-ui', endpoint: 'https://one.example/' },
    { id: 'pockethive-ui', endpoint: 'https://two.example/' },
  ] }, { destination: WEB_UI_DESTINATIONS.BUZZ }), /WEB_UI_ENDPOINT_AMBIGUOUS/);
  for (const endpoint of [
    'file:///tmp/pockethive',
    'https://user:secret@lab.example/',
    'https://lab.example/?redirect=other',
    'not-a-url',
  ]) {
    assert.throws(() => resolvePocketHiveWebUiUrl({ services: [
      { id: 'pockethive-ui', endpoint },
    ] }, { destination: WEB_UI_DESTINATIONS.BUZZ }), /WEB_UI_ENDPOINT_INVALID/);
  }
});

test('web UI navigation requires exact non-blank destination identifiers', () => {
  assert.throws(() => resolvePocketHiveWebUiUrl(health, {
    destination: WEB_UI_DESTINATIONS.SWARM,
    swarmId: ' ',
  }), /WEB_UI_SWARM_REQUIRED/);
  assert.throws(() => resolvePocketHiveWebUiUrl(health, {
    destination: WEB_UI_DESTINATIONS.JOURNAL_RUN,
    swarmId: 'nightly-smoke',
    runId: ' ',
  }), /WEB_UI_RUN_REQUIRED/);
});

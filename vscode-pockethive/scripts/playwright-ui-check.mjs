import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { mkdir, readFile, readdir, unlink, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import path from 'node:path';

import AxeBuilder from '@axe-core/playwright';
import { chromium } from 'playwright';

const require = createRequire(import.meta.url);
const { McpHttpClient } = require('../out/mcp/httpClient.js');
const { SIDEBAR_EVENT_LIMIT } = require('../out/webview/workspaceTool.js');
const root = path.resolve(import.meta.dirname, '..');
const auditDirectory = path.resolve(root, 'reports', 'playwright-ui');
const manifest = JSON.parse(await readFile(path.join(root, 'package.json'), 'utf8'));
const endpoint = 'http://localhost:8088/mcp';
const redirectUri = 'http://127.0.0.1:57548/callback';
const connectionScopes = ['pockethive:mcp:discover', 'pockethive:mcp:read'];
const TABS = ['Hive', 'Buzz', 'Journal', 'Scenarios', 'Debug'];
const findings = [];

await mkdir(auditDirectory, { recursive: true });
for (const name of await readdir(auditDirectory)) {
  if (name.endsWith('.png')) await unlink(path.join(auditDirectory, name));
}
const server = await startUiServer();
const browser = await chromium.launch({ headless: true });

try {
  const local = await connectLocalMcp(browser);
  assert.equal(local.evidence.principalLabel, 'local-admin', 'MCP must expose the verified username as its label');
  const context = await browser.newContext({ viewport: { width: 280, height: 900 }, colorScheme: 'dark' });
  const page = await context.newPage();
  page.on('pageerror', error => findings.push({ state: 'browser', kind: 'pageerror', detail: error.message }));
  page.on('console', message => {
    if (message.type() === 'error') findings.push({ state: 'browser', kind: 'console-error', detail: message.text() });
  });
  await page.goto(server.url, { waitUntil: 'networkidle' });
  await page.waitForFunction(() => globalThis.__pockethiveMessages?.some(message => message.type === 'ready'));

  const base = {
    profiles: [],
    activeTab: 'Hive',
    debugActions: [
      { label: 'Workers', needsWorker: false },
      { label: 'Logs', needsWorker: true },
      { label: 'Versions', needsWorker: true },
      { label: 'Inspect', needsWorker: true },
      { label: 'Runtime drift', needsWorker: false },
      { label: 'Control plane', needsWorker: false },
      { label: 'Rabbit topology', needsWorker: false },
      { label: 'Timeline', needsWorker: false },
      { label: 'Manifest', needsWorker: false },
      { label: 'Cleanup plan', needsWorker: false },
    ],
    swarmOperations: { START: 'START', STOP: 'STOP', REMOVE: 'REMOVE' },
    swarmPrimaryActions: primaryActions(local.swarms),
    busy: false,
  };
  const profile = {
    id: 'local-mcp',
    displayName: 'Local MCP',
    mcpUrl: endpoint,
    endpointSecurityMode: 'LOCAL_LOOPBACK_HTTP',
    authenticationMode: 'OAUTH_AUTHORIZATION_CODE_PKCE',
    secretKey: 'pockethive.oauth.local-mcp',
    status: 'Connected',
    principalLabel: local.evidence.principalLabel,
  };

  await show(page, { ...base, page: 'environments' }, '01-environments-empty');
  await clickAndExpectMessage(page, 'Add', { type: 'addEnvironment' });

  const draft = { ...profile, displayName: '', status: undefined, principalLabel: undefined };
  const editing = { profileId: profile.id, state: 'EDITING', endpointValidated: false, authenticated: false };
  await show(page, { ...base, page: 'add', draft, attempt: editing }, '02-add-local-mcp');
  await page.getByLabel('Name').fill('Local MCP');
  await page.getByLabel('MCP URL').fill(endpoint);
  await page.getByLabel('Endpoint security').selectOption('LOCAL_LOOPBACK_HTTP');
  await clickAndExpectMessage(page, 'Connect', {
    type: 'connect',
    displayName: 'Local MCP',
    mcpUrl: endpoint,
    endpointSecurityMode: 'LOCAL_LOOPBACK_HTTP',
  });

  const ready = {
    profileId: profile.id,
    state: 'READY_TO_SAVE',
    endpointValidated: true,
    authenticated: true,
    evidence: local.evidence,
  };
  await show(page, { ...base, page: 'add', draft: { ...draft, displayName: 'Local MCP' }, attempt: ready },
    '03-local-mcp-ready');
  await clickAndExpectMessage(page, 'Save & open', { type: 'saveOpen' });

  const workspaceBase = {
    ...base,
    page: 'workspace',
    profiles: [profile],
    draft: profile,
    activeProfile: profile,
    session: {
      status: 'Connected',
      message: 'Secure session active',
      canUseWorkspace: true,
      canSignIn: false,
      canSignOut: true,
    },
  };
  await show(page, { ...workspaceBase, activeTab: 'Hive', workspaceData: local.swarms }, '04-workspace-hive');
  assert.equal(await page.locator('#app > .brand').count(), 0,
    'the narrow workspace must not reserve vertical space for a global brand header');
  await page.getByLabel('Account', { exact: true }).click();
  const accountPanel = page.locator('.account-menu__panel');
  await accountPanel.getByText('local-admin', { exact: true }).waitFor({ state: 'visible' });
  await accountPanel.getByText('Secure session active', { exact: true }).waitFor({ state: 'visible' });
  await clickAndExpectMessage(page, 'Sign out', { type: 'signOut' });

  const needsSignInSession = {
    status: 'Needs sign-in',
    message: 'Sign in again to reconnect this environment',
    canUseWorkspace: false,
    canSignIn: true,
    canSignOut: false,
  };
  await show(page, {
    ...workspaceBase,
    session: needsSignInSession,
    activeTab: 'Hive',
    workspaceData: local.swarms,
  }, '05-workspace-needs-sign-in');
  assert.equal(await page.locator('.workspace').count(), 1, 'reauthorization must retain the workspace shell');
  await clickAndExpectMessage(page, 'Sign in', { type: 'reauthorizeEnvironment' });

  await show(page, {
    ...workspaceBase,
    busy: true,
    session: {
      status: 'Connecting',
      message: 'Restoring the secure session',
      canUseWorkspace: false,
      canSignIn: false,
      canSignOut: false,
    },
    activeTab: 'Hive',
    workspaceData: local.swarms,
  }, '06-workspace-restoring-session');
  assert.equal(await page.locator('.workspace').count(), 1, 'session restoration must not flash the environments page');
  assert.equal(await page.locator('.session-notice').count(), 1, 'session restoration must use one calm in-place status');

  await dispatch(page, { ...workspaceBase, activeTab: 'Hive', workspaceData: local.swarms });
  await selectTab(page, workspaceBase, 'Scenarios', local.scenarios, '05-workspace-scenarios');
  await selectTab(page, workspaceBase, 'Buzz', local.buzz, '06-workspace-buzz');
  await selectTab(page, workspaceBase, 'Journal', local.swarms, '07-workspace-journal');
  if (local.journal) {
    await dispatch(page, {
      ...workspaceBase,
      activeTab: 'Journal',
      workspaceData: [{ id: local.journal.swarmId }],
    });
    await chooseExactAutocomplete(page, 'Exact swarm', local.journal.swarmId);
    await page.waitForFunction(value => globalThis.__pockethiveMessages?.some(message => (
      message.type === 'selectJournalSwarm' && message.swarmId === value
    )), local.journal.swarmId);
    await show(page, {
      ...workspaceBase,
      activeTab: 'Journal',
      workspaceData: [{ id: local.journal.swarmId }],
      journalSwarmId: local.journal.swarmId,
      journalResult: local.journal.data,
    }, '08-workspace-journal-selected');
  }
  await selectTab(page, workspaceBase, 'Debug', local.swarms, '09-workspace-debug');

  const interactionSwarms = [
    {
      id: 'checkout-load',
      templateId: 'checkout-load',
      controllerState: 'READY',
      workloadState: 'RUNNING',
      runtimeResourceState: 'PRESENT',
      observationStale: false,
      bees: [{}, {}],
    },
    {
      id: 'nightly-smoke',
      templateId: 'nightly-smoke',
      controllerState: 'READY',
      workloadState: 'STOPPED',
      runtimeResourceState: 'PRESENT',
      observationStale: false,
      bees: [{}],
    },
  ];
  const interactionModel = {
    ...workspaceBase,
    activeTab: 'Hive',
    workspaceData: interactionSwarms,
    swarmPrimaryActions: primaryActions(interactionSwarms),
  };
  await dispatch(page, interactionModel);
  await captureSelected(page, '11-selected-hive');
  const runningRow = page.locator('.swarm-row').filter({ hasText: 'checkout-load' });
  const readyRow = page.locator('.swarm-row').filter({ hasText: 'nightly-smoke' });
  await clickWithinAndExpectMessage(page, runningRow, 'Stop', {
    type: 'runSwarmOperation', action: 'STOP', swarmId: 'checkout-load',
  });
  await clickWithinAndExpectMessage(page, readyRow, 'Start', {
    type: 'runSwarmOperation', action: 'START', swarmId: 'nightly-smoke',
  });
  await clickWithinAndExpectMessage(page, runningRow, 'Debug', {
    type: 'openDebugForSwarm', swarmId: 'checkout-load',
  });
  await runningRow.getByText('More', { exact: true }).click();
  await clickWithinAndExpectMessage(page, runningRow, 'Remove swarm', {
    type: 'runSwarmOperation', action: 'REMOVE', swarmId: 'checkout-load',
  });

  await clickWithinAndExpectMessage(page, runningRow, 'Run history', {
    type: 'loadSwarmHistory', swarmId: 'checkout-load',
  });
  await clickAndExpectMessage(page, 'Create swarm', { type: 'openCreateSwarm' });
  const checkoutRuns = [
    { runId: 'run-checkout-42', firstTs: '2026-08-19T10:40:00Z', lastTs: '2026-08-19T10:42:00Z', entries: 18, pinned: true },
  ];
  await dispatch(page, {
    ...interactionModel,
    swarmHistorySwarmId: 'checkout-load',
    swarmHistoryResult: checkoutRuns,
  });
  assert.equal(await page.locator('.run-history').count(), 1, 'one run history panel must be open');
  await clickWithinAndExpectMessage(page, readyRow, 'Run history', {
    type: 'loadSwarmHistory', swarmId: 'nightly-smoke',
  });
  await dispatch(page, {
    ...interactionModel,
    swarmHistorySwarmId: 'nightly-smoke',
    swarmHistoryResult: [{
      runId: 'run-nightly-7', firstTs: '2026-08-19T10:43:00Z', lastTs: '2026-08-19T10:44:30Z', entries: 12, pinned: false,
    }],
  });
  assert.equal(await page.locator('.run-history').count(), 1, 'opening another history must collapse the first');
  await captureSelected(page, '12-selected-hive-history');
  await clickAndExpectMessage(page, 'Open journal', {
    type: 'openJournalRun', swarmId: 'nightly-smoke', runId: 'run-nightly-7',
  });

  const eventFixture = { items: [
    { timestamp: '2026-08-19T10:42:18Z', severity: 'INFO', kind: 'signal', type: 'swarm-start', swarmId: 'nightly-smoke', origin: 'orchestrator', direction: 'OUT' },
    { timestamp: '2026-08-19T10:41:53Z', severity: 'ERROR', kind: 'runtime-debug', type: 'runtime-log-snapshot', swarmId: 'auth-regression', origin: 'orchestrator', direction: 'LOCAL' },
  ] };
  await dispatch(page, {
    ...workspaceBase,
    activeTab: 'Journal',
    workspaceData: interactionSwarms,
    journalSwarmId: 'nightly-smoke',
    journalRunId: 'run-nightly-7',
    journalResult: eventFixture,
  });
  await captureSelected(page, '13-selected-journal');
  await dispatch(page, { ...workspaceBase, activeTab: 'Buzz', workspaceData: eventFixture });
  await captureSelected(page, '14-selected-buzz');
  assert.equal(await page.locator('.event-row').count(), 2, 'Buzz must render one compact row per event');
  await page.getByLabel('Advanced filters').click();
  await page.getByLabel('Severity').selectOption('ERROR');
  assert.equal(await page.locator('.event-row').count(), 1, 'severity filter must narrow the rendered page');
  await page.getByLabel('Search events').fill('auth-regression');
  assert.equal(await page.locator('.event-row').count(), 1, 'search filter must compose with severity');
  await page.locator('.event-row').first().getByText('runtime-debug/runtime-log-snapshot', { exact: true }).click();
  await clickAndExpectMessage(page, 'Open Debug', {
    type: 'openDebugForSwarm', swarmId: 'auth-regression',
  });

  const scenarioFixture = [
    {
      id: 'checkout-smoke',
      name: 'Checkout smoke',
      folderPath: 'bundles',
      bundleKey: 'bundles/checkout-smoke',
      bundlePath: 'bundles/checkout-smoke',
    },
    {
      id: 'postgres-smoke',
      name: 'DB query Postgres smoke',
      folderPath: 'database',
      bundleKey: 'database/postgres-smoke',
      bundlePath: 'database/postgres-smoke',
    },
  ];
  await dispatch(page, { ...workspaceBase, activeTab: 'Scenarios', workspaceData: scenarioFixture });
  await captureSelected(page, '15-selected-scenarios');
  await page.getByLabel('Search bundles').fill('postgres');
  assert.equal(await page.locator('.scenario-row').count(), 1, 'scenario search must narrow compact rows');
  await page.getByLabel('Folder').selectOption('bundles');
  assert.equal(await page.locator('.scenario-row').count(), 0, 'folder and search filters must compose');

  await dispatch(page, { ...workspaceBase, activeTab: 'Debug', workspaceData: interactionSwarms });
  await captureSelected(page, '16-selected-debug');
  assert.deepEqual(await page.locator('.debug-group > summary strong').allTextContents(),
    ['Runtime', 'Messaging', 'Definition', 'Maintenance']);
  await chooseExactAutocomplete(page, 'Exact swarm', 'checkout-load');
  await page.waitForFunction(() => globalThis.__pockethiveMessages?.some(message => (
    message.type === 'selectDebugSwarm' && message.swarmId === 'checkout-load'
  )));

  for (const width of [140, 240, 280, 320, 480]) {
    await page.setViewportSize({ width, height: 900 });
    await dispatch(page, { ...workspaceBase, activeTab: 'Debug', workspaceData: local.swarms });
    await inspectState(page, `debug-${width}`);
    const tabVisibility = await page.getByRole('tab', { name: 'Debug', exact: true }).evaluate(tab => {
      const tabRect = tab.getBoundingClientRect();
      const stripRect = tab.parentElement.getBoundingClientRect();
      return { left: tabRect.left, right: tabRect.right, stripLeft: stripRect.left, stripRight: stripRect.right };
    });
    if (tabVisibility.left < tabVisibility.stripLeft - 1 || tabVisibility.right > tabVisibility.stripRight + 1) {
      findings.push({ state: `debug-${width}`, kind: 'active-tab-hidden', detail: tabVisibility });
    }
    const tabOverflow = await page.locator('.tabs').evaluate(strip => strip.scrollWidth - strip.clientWidth);
    if (width >= 280 && tabOverflow > 1) {
      findings.push({ state: `debug-${width}`, kind: 'tab-strip-overflow', detail: tabOverflow });
    }
    if (width === 140) {
      await page.screenshot({ path: path.join(auditDirectory, '10-workspace-debug-200pct.png'), fullPage: true });
    }
  }

  await page.setViewportSize({ width: 280, height: 900 });
  await dispatch(page, {
    ...workspaceBase,
    activeProfile: {
      ...profile,
      displayName: 'Local MCP environment with a deliberately long display name',
      principalLabel: 'local-admin-with-a-deliberately-long-verified-principal-label',
    },
    activeTab: 'Hive',
    workspaceData: local.swarms,
  });
  await inspectState(page, 'workspace-long-values-280');
  await dispatch(page, { ...workspaceBase, activeTab: 'Hive', workspaceData: [null] });
  await inspectState(page, 'workspace-invalid-owner-data');
  if (!await page.getByRole('alert').filter({ hasText: 'invalid swarm list' }).isVisible()) {
    findings.push({ state: 'workspace-invalid-owner-data', kind: 'fail-closed', detail: 'Invalid owner data was not explicit' });
  }

  const tabSemantics = await page.locator('[role="tab"]').evaluateAll(tabs => tabs.map(tab => ({
    id: tab.id,
    controls: tab.getAttribute('aria-controls'),
    selected: tab.getAttribute('aria-selected'),
    tabIndex: tab.getAttribute('tabindex'),
  })));
  if (tabSemantics.some(tab => !tab.id || !tab.controls)) {
    findings.push({ state: 'workspace-tabs', kind: 'accessibility', detail: 'Tabs lack id/aria-controls bindings', tabSemantics });
  }
  const tabPanelCount = await page.locator('[role="tabpanel"]').count();
  if (tabPanelCount !== 1) findings.push({ state: 'workspace-tabs', kind: 'accessibility', detail: `Expected one tabpanel, found ${tabPanelCount}` });
  const selectedTabs = tabSemantics.filter(tab => tab.selected === 'true' && tab.tabIndex === '0');
  const dormantTabs = tabSemantics.filter(tab => tab.selected === 'false' && tab.tabIndex === '-1');
  if (selectedTabs.length !== 1 || dormantTabs.length !== TABS.length - 1) {
    findings.push({ state: 'workspace-tabs', kind: 'accessibility', detail: 'Tabs must use one roving tab stop', tabSemantics });
  }

  await dispatch(page, { ...workspaceBase, activeTab: 'Hive', workspaceData: local.swarms });
  await keyboardTab(page, 'Hive', 'ArrowRight', 'Buzz');
  await keyboardTab(page, 'Buzz', 'Home', 'Hive');
  await keyboardTab(page, 'Hive', 'ArrowLeft', 'Debug');
  await keyboardTab(page, 'Hive', 'End', 'Debug');

  const report = {
    endpoint,
    serverName: local.evidence.serverName,
    serverVersion: local.evidence.serverVersion,
    principalLabel: local.evidence.principalLabel,
    toolCount: local.toolCount,
    journalRunsProbe: local.journalRunsProbe,
    sessionRefresh: local.sessionRefresh,
    screenshots: (await readFileNames()).map(name => path.join(auditDirectory, name)),
    findings,
  };
  await writeFile(path.join(auditDirectory, 'report.json'), `${JSON.stringify(report, null, 2)}\n`);
  console.log(JSON.stringify({
    endpoint: report.endpoint,
    serverName: report.serverName,
    serverVersion: report.serverVersion,
    principalLabel: report.principalLabel,
    toolCount: report.toolCount,
    journalRunsProbe: report.journalRunsProbe,
    sessionRefresh: report.sessionRefresh,
    screenshotCount: report.screenshots.length,
    findingCount: findings.length,
    findings,
  }, null, 2));
  assert.deepEqual(findings, [], 'Playwright UI findings must be empty');
  await context.close();
  await local.client.close();
  await local.revoke();
} finally {
  await browser.close();
  await server.close();
}

async function selectTab(page, workspaceBase, tab, workspaceData, screenshotName) {
  await clickAndExpectMessage(page, tab, { type: 'selectTab', tab }, 'tab');
  await show(page, { ...workspaceBase, activeTab: tab, workspaceData }, screenshotName);
}

async function show(page, model, screenshotName) {
  await dispatch(page, model);
  await page.evaluate(() => scrollTo(0, 0));
  await page.setViewportSize({ width: 280, height: 900 });
  await inspectState(page, screenshotName);
  await page.screenshot({ path: path.join(auditDirectory, `${screenshotName}.png`), fullPage: true });
}

async function captureSelected(page, screenshotName) {
  await page.setViewportSize({ width: 428, height: 917 });
  await page.evaluate(() => scrollTo(0, 0));
  await inspectState(page, screenshotName);
  await page.screenshot({ path: path.join(auditDirectory, `${screenshotName}.png`) });
}

async function dispatch(page, model) {
  await page.evaluate(value => window.dispatchEvent(new MessageEvent('message', {
    data: { type: 'viewModel', model: value },
  })), model);
  await page.locator('#app > .workspace, #app > .page').waitFor({ state: 'visible' });
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

async function chooseExactAutocomplete(page, label, value) {
  const control = page.getByLabel(label, { exact: true });
  await control.fill(value);
  await control.press('Tab');
}

async function clickAndExpectMessage(page, label, expected, role = 'button') {
  await page.evaluate(() => { globalThis.__pockethiveMessages = []; });
  await page.getByRole(role, { name: label, exact: true }).click();
  await page.waitForFunction(value => globalThis.__pockethiveMessages?.some(message => (
    JSON.stringify(message) === JSON.stringify(value)
  )), expected);
}

async function clickWithinAndExpectMessage(page, container, label, expected) {
  await page.evaluate(() => { globalThis.__pockethiveMessages = []; });
  await container.getByRole('button', { name: label, exact: true }).click();
  await page.waitForFunction(value => globalThis.__pockethiveMessages?.some(message => (
    JSON.stringify(message) === JSON.stringify(value)
  )), expected);
}

async function keyboardTab(page, from, key, expected) {
  await page.evaluate(() => { globalThis.__pockethiveMessages = []; });
  await page.getByRole('tab', { name: from, exact: true }).focus();
  await page.keyboard.press(key);
  await page.waitForFunction(value => globalThis.__pockethiveMessages?.some(message => (
    message.type === 'selectTab' && message.tab === value
  )), expected);
  if (await page.evaluate(() => document.activeElement?.textContent) !== expected) {
    findings.push({ state: 'workspace-tabs', kind: 'keyboard', detail: `${key} from ${from} did not focus ${expected}` });
  }
}

async function inspectState(page, state) {
  const geometry = await page.evaluate(() => {
    const viewportWidth = document.documentElement.clientWidth;
    const visible = [...document.body.querySelectorAll('*')].filter(node => {
      const style = getComputedStyle(node);
      return style.display !== 'none' && style.visibility !== 'hidden' && node.getClientRects().length > 0;
    });
    const outside = visible.filter(node => {
      if (node.closest('.tabs')) return false;
      const rect = node.getBoundingClientRect();
      return rect.width > 0 && (rect.left < -1 || rect.right > viewportWidth + 1);
    }).map(node => `${node.tagName}.${typeof node.className === 'string' ? node.className : ''}`).slice(0, 20);
    const clipped = visible.filter(node => {
      if (node.matches('pre, .truncate, .tabs, .sr-only') || node.closest('.tabs, .sr-only')) return false;
      const style = getComputedStyle(node);
      const intentionallyScrollable = ['auto', 'scroll'].includes(style.overflowX)
        || ['auto', 'scroll'].includes(style.overflowY);
      const clips = ['hidden', 'clip'].includes(style.overflowX)
        || ['hidden', 'clip'].includes(style.overflowY);
      return !intentionallyScrollable
        && clips
        && (node.scrollWidth > node.clientWidth + 1 || node.scrollHeight > node.clientHeight + 1);
    }).map(node => `${node.tagName}.${typeof node.className === 'string' ? node.className : ''}`).slice(0, 20);
    const undersized = visible.filter(node => node.matches('button, input, select')).map(node => {
      const rect = node.getBoundingClientRect();
      return { label: node.getAttribute('aria-label') || node.textContent || node.id, width: rect.width, height: rect.height };
    }).filter(rect => rect.width < 24 || rect.height < 24);
    return {
      documentOverflow: Math.max(document.documentElement.scrollWidth, document.body.scrollWidth) - viewportWidth,
      outside,
      clipped,
      undersized,
    };
  });
  if (geometry.documentOverflow > 1) findings.push({ state, kind: 'overflow', detail: geometry.documentOverflow });
  if (geometry.outside.length) findings.push({ state, kind: 'outside-viewport', detail: geometry.outside });
  if (geometry.clipped.length) findings.push({ state, kind: 'clipped-content', detail: geometry.clipped });
  if (geometry.undersized.length) findings.push({ state, kind: 'target-size', detail: geometry.undersized });
  if (!state.includes('debug')) {
    const primaryJson = await page.locator('.tab-content pre').evaluateAll(nodes => nodes
      .filter(node => !node.closest('details')).length);
    if (primaryJson > 0) findings.push({ state, kind: 'raw-owner-data', detail: `${primaryJson} primary JSON blocks` });
  }
  const axe = await new AxeBuilder({ page }).analyze();
  for (const violation of axe.violations) {
    findings.push({
      state,
      kind: 'axe',
      detail: `${violation.id}: ${violation.help}`,
      impact: violation.impact,
      targets: violation.nodes.map(node => node.target),
    });
  }
}

async function connectLocalMcp(browser) {
  const context = await browser.newContext({ viewport: { width: 520, height: 760 }, colorScheme: 'dark' });
  const page = await context.newPage();
  let callback;
  let signInCaptured = false;
  let consentCaptured = false;
  const observedLocations = [];
  page.on('pageerror', error => findings.push({ state: 'auth-browser', kind: 'pageerror', detail: error.message }));
  page.on('console', message => {
    if (message.type() === 'error') {
      findings.push({ state: 'auth-browser', kind: 'console-error', detail: message.text() });
    }
  });
  page.on('request', request => {
    const requested = new URL(request.url());
    observedLocations.push(`${requested.origin}${requested.pathname}`);
    if (`${requested.origin}${requested.pathname}` === redirectUri) callback = requested;
  });
  await context.route('**/*', async route => {
    const requested = new URL(route.request().url());
    if (`${requested.origin}${requested.pathname}` === redirectUri) {
      callback = requested;
      await route.fulfill({ status: 200, contentType: 'text/html', body: '<p>Complete</p>' });
      return;
    }
    await route.continue();
  });

  const resource = await json(`${new URL(endpoint).origin}/.well-known/oauth-protected-resource`);
  assert.equal(resource.resource, endpoint);
  assert.deepEqual(resource.authorization_servers, [`${new URL(endpoint).origin}/auth-service`]);
  const issuer = resource.authorization_servers[0];
  const metadata = await json(`${issuer}/.well-known/oauth-authorization-server`);
  const verifier = randomBytes(64).toString('base64url');
  const challenge = createHash('sha256').update(verifier, 'ascii').digest('base64url');
  const state = randomBytes(32).toString('base64url');
  const authorization = new URL(metadata.authorization_endpoint);
  authorization.search = new URLSearchParams({
    response_type: 'code',
    client_id: 'pockethive-vscode',
    redirect_uri: redirectUri,
    resource: endpoint,
    scope: connectionScopes.join(' '),
    state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
  }).toString();

  const authorizationResponse = await page.goto(authorization.toString(), { waitUntil: 'domcontentloaded' });
  const deadline = Date.now() + 20_000;
  while (!callback && Date.now() < deadline) {
    const username = page.getByLabel('Configured username');
    const allow = page.getByRole('button', { name: 'Allow' });
    if (await username.isVisible()) {
      if (!signInCaptured) {
        await inspectAuthPage(page, 'auth-sign-in', '17-auth-sign-in');
        signInCaptured = true;
      }
      await username.fill('local-admin');
      await page.getByRole('button', { name: 'Sign in' }).click();
    } else if (await allow.isVisible()) {
      if (!consentCaptured) {
        await inspectAuthPage(page, 'auth-consent', '18-auth-consent');
        await page.setViewportSize({ width: 360, height: 800 });
        await inspectAuthPage(page, 'auth-consent-mobile', '19-auth-consent-mobile');
        await page.setViewportSize({ width: 520, height: 760 });
        consentCaptured = true;
      }
      await allow.click();
    } else {
      await page.waitForTimeout(100);
    }
  }
  assert.equal(signInCaptured, true, 'the live PocketHive sign-in page must be rendered and audited');
  if (!callback) {
    const location = new URL(page.url());
    throw new Error(`OAuth callback was not observed (initial status ${authorizationResponse?.status() ?? 'none'}) at ${
      location.origin}${location.pathname}; requests=${JSON.stringify(observedLocations.slice(-20))}: ${
      (await page.locator('body').innerText()).replaceAll(/\s+/g, ' ').slice(0, 500)}`);
  }
  assert.equal(callback.searchParams.get('state'), state);
  const code = callback.searchParams.get('code');
  assert.ok(code, 'OAuth authorization code missing');
  if (!consentCaptured) {
    const consent = new URL(`${issuer}/oauth/consent`);
    consent.search = new URLSearchParams({
      client_id: 'pockethive-vscode',
      state: 'visual-audit-only',
      scope: connectionScopes.join(' '),
    }).toString();
    const consentPage = await context.newPage();
    consentPage.on('pageerror', error => findings.push({
      state: 'auth-consent-browser', kind: 'pageerror', detail: error.message,
    }));
    consentPage.on('console', message => {
      if (message.type() === 'error') {
        findings.push({ state: 'auth-consent-browser', kind: 'console-error', detail: message.text() });
      }
    });
    await consentPage.goto(consent.toString(), { waitUntil: 'networkidle' });
    await inspectAuthPage(consentPage, 'auth-consent', '18-auth-consent');
    await consentPage.setViewportSize({ width: 360, height: 800 });
    await inspectAuthPage(consentPage, 'auth-consent-mobile', '19-auth-consent-mobile');
    await consentPage.close();
    consentCaptured = true;
  }
  assert.equal(consentCaptured, true, 'the live PocketHive consent page must be rendered and audited');
  const tokenResponse = await fetch(metadata.token_endpoint, {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: 'pockethive-vscode',
      code,
      redirect_uri: redirectUri,
      resource: endpoint,
      code_verifier: verifier,
    }),
  });
  assert.equal(tokenResponse.ok, true, `OAuth token exchange failed: ${tokenResponse.status}`);
  const token = await tokenResponse.json();
  assert.equal(token.token_type, 'Bearer');
  assert.equal(typeof token.access_token, 'string');
  assert.equal(typeof token.refresh_token, 'string', 'the base companion session must be renewable');

  const refreshResponse = await fetch(metadata.token_endpoint, {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'refresh_token',
      client_id: 'pockethive-vscode',
      refresh_token: token.refresh_token,
      resource: endpoint,
    }),
  });
  assert.equal(refreshResponse.ok, true, `OAuth session renewal failed: ${refreshResponse.status}`);
  const refreshed = await refreshResponse.json();
  assert.equal(typeof refreshed.access_token, 'string');
  assert.equal(typeof refreshed.refresh_token, 'string');
  assert.notEqual(refreshed.access_token, token.access_token, 'renewal must replace the access token');
  assert.notEqual(refreshed.refresh_token, token.refresh_token, 'renewal must rotate the refresh token');

  const replay = await fetch(metadata.token_endpoint, {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'refresh_token',
      client_id: 'pockethive-vscode',
      refresh_token: token.refresh_token,
      resource: endpoint,
    }),
  });
  assert.equal(replay.status, 400, 'a retired refresh token must be rejected');
  assert.equal((await replay.json()).error, 'invalid_grant');

  const client = new McpHttpClient(manifest.version);
  const evidence = await client.connect(endpoint, refreshed.access_token);
  const toolList = await client.listTools();
  const tools = Array.isArray(toolList?.tools) ? toolList.tools : [];
  const toolNames = new Set(tools.map(tool => tool?.name).filter(name => typeof name === 'string'));
  assert.equal(tools.length >= 50, true, 'live MCP must expose the expected full tool catalogue');
  for (const requiredTool of [
    'scenario_list',
    'scenario_templates_catalog',
    'scenario_suts_list',
    'swarm_list',
    'swarm_get',
    'swarm_create',
    'swarm_start',
    'swarm_stop',
    'swarm_remove',
    'debug_journal_runs',
  ]) {
    assert.equal(toolNames.has(requiredTool), true, `live MCP catalogue must expose ${requiredTool}`);
  }
  const [swarms, scenarios, buzz] = await Promise.all([
    client.callTool('swarm_list'),
    client.callTool('scenario_list'),
    client.callTool('debug_hive_journal', { limit: SIDEBAR_EVENT_LIMIT }),
  ]);
  const swarmId = swarms.find?.(item => typeof item?.id === 'string')?.id;
  const journalRunsSwarmId = swarmId ?? 'pockethive-ui-acceptance-missing-swarm';
  let journalRunsProbe;
  try {
    const journalRuns = await client.callTool('debug_journal_runs', { swarmId: journalRunsSwarmId });
    assert.equal(Array.isArray(journalRuns), true, 'debug_journal_runs must preserve the owner list shape');
    journalRunsProbe = { swarmId: journalRunsSwarmId, outcome: 'owner-list', count: journalRuns.length };
  } catch (error) {
    assert.equal(swarmId, undefined, 'an existing swarm run-history lookup must not fail');
    assert.equal(error?.code, 'MCP_TOOL_FAILED');
    assert.match(error?.message ?? '', /OWNER_REQUEST_REJECTED/,
      'an absent exact swarm must preserve the owner error code');
    assert.match(error?.message ?? '', /HTTP 404/,
      'an absent exact swarm must preserve the owner HTTP status without fallback');
    journalRunsProbe = { swarmId: journalRunsSwarmId, outcome: 'owner-not-found' };
  }
  const journal = swarmId
    ? { swarmId, data: await client.callTool('debug_journal', { swarmId, limit: SIDEBAR_EVENT_LIMIT }) }
    : undefined;
  await context.close();
  return {
    client,
    evidence,
    swarms,
    scenarios,
    buzz,
    journal,
    toolCount: tools.length,
    journalRunsProbe,
    sessionRefresh: { rotated: true, replayRejected: true },
    revoke: async () => {
      for (const [value, hint] of [
        [refreshed.access_token, 'access_token'],
        [refreshed.refresh_token, 'refresh_token'],
      ]) {
        const response = await fetch(metadata.revocation_endpoint, {
          method: 'POST',
          headers: { Accept: 'application/json', 'Content-Type': 'application/x-www-form-urlencoded' },
          body: new URLSearchParams({ client_id: 'pockethive-vscode', token: value, token_type_hint: hint }),
        });
        assert.equal(response.ok, true, `OAuth ${hint} revocation failed: ${response.status}`);
      }
    },
  };
}

async function inspectAuthPage(page, state, screenshotName) {
  await page.waitForLoadState('networkidle');
  const assets = await page.evaluate(() => {
    const logo = document.querySelector('.auth-brand__logo');
    const card = document.querySelector('.auth-card');
    return {
      logoLoaded: logo instanceof HTMLImageElement && logo.complete && logo.naturalWidth > 0,
      themedCard: card instanceof HTMLElement && getComputedStyle(card).backgroundColor !== 'rgba(0, 0, 0, 0)',
      overflow: Math.max(document.documentElement.scrollWidth, document.body.scrollWidth)
        - document.documentElement.clientWidth,
    };
  });
  if (!assets.logoLoaded) findings.push({ state, kind: 'brand-asset', detail: 'PocketHive logo did not load' });
  if (!assets.themedCard) findings.push({ state, kind: 'theme', detail: 'PocketHive auth theme did not load' });
  if (assets.overflow > 1) findings.push({ state, kind: 'overflow', detail: assets.overflow });
  const axe = await new AxeBuilder({ page }).analyze();
  for (const violation of axe.violations) {
    findings.push({
      state,
      kind: 'axe',
      detail: `${violation.id}: ${violation.help}`,
      impact: violation.impact,
      targets: violation.nodes.map(node => node.target),
    });
  }
  await page.screenshot({ path: path.join(auditDirectory, `${screenshotName}.png`), fullPage: true });
}

async function json(url) {
  const response = await fetch(url, { headers: { Accept: 'application/json' }, redirect: 'error' });
  assert.equal(response.ok, true, `${url} returned ${response.status}`);
  assert.match(response.headers.get('content-type') ?? '', /^application\/json/);
  return response.json();
}

async function startUiServer() {
  const files = new Map([
    ['/media/companion.css', ['text/css', path.join(root, 'media', 'companion.css')]],
    ['/resources/brand-tokens.css', ['text/css', path.join(root, 'resources', 'brand-tokens.css')]],
    ['/resources/logo-mark.svg', ['image/svg+xml', path.join(root, 'resources', 'logo-mark.svg')]],
    ['/out/webview/eventFilters.js', ['text/javascript', path.join(root, 'out', 'webview', 'eventFilters.js')]],
    ['/out/webview/main.js', ['text/javascript', path.join(root, 'out', 'webview', 'main.js')]],
  ]);
  const http = createServer(async (request, response) => {
    if (request.url === '/') {
      response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      response.end(`<!doctype html><html lang="en"><head><meta charset="UTF-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <link rel="stylesheet" href="/resources/brand-tokens.css">
        <link rel="stylesheet" href="/media/companion.css">
        <style>:root {
          --vscode-button-background:#147ba8;--vscode-button-foreground:#fff;
          --vscode-panel-border:#34445b;--vscode-contrastBorder:#34445b;
          --vscode-sideBar-background:#07111f;--vscode-sideBar-foreground:#e8edf4;
          --vscode-foreground:#e8edf4;--vscode-descriptionForeground:#aebac9;
          --vscode-testing-iconPassed:#3fb950;--vscode-editorWarning-foreground:#d29922;
          --vscode-errorForeground:#ff8585;--vscode-focusBorder:#58a6ff;
          --vscode-textLink-foreground:#34c6f4;--vscode-input-foreground:#e8edf4;
          --vscode-input-background:#07111f;--vscode-input-border:#4a5c73;
          --vscode-font-family:Inter,ui-sans-serif,system-ui,sans-serif;
          --vscode-editor-font-family:"SFMono-Regular",Consolas,monospace;
        }</style><title>PocketHive UI check</title></head>
        <body class="vscode-dark"><main id="app" data-logo="/resources/logo-mark.svg"></main>
        <div id="announcer" class="sr-only" aria-live="polite"></div>
        <script>globalThis.__pockethiveMessages=[];globalThis.acquireVsCodeApi=()=>({postMessage(message){globalThis.__pockethiveMessages.push(message);}});</script>
        <script src="/out/webview/eventFilters.js"></script>
        <script src="/out/webview/main.js"></script></body></html>`);
      return;
    }
    const file = files.get(request.url ?? '');
    if (!file) {
      response.writeHead(404);
      response.end();
      return;
    }
    response.writeHead(200, { 'Content-Type': file[0] });
    response.end(await readFile(file[1]));
  });
  await new Promise((resolve, reject) => {
    http.once('error', reject);
    http.listen(0, '127.0.0.1', resolve);
  });
  const address = http.address();
  assert.ok(address && typeof address !== 'string');
  return {
    url: `http://127.0.0.1:${address.port}`,
    close: () => new Promise(resolve => http.close(resolve)),
  };
}

async function readFileNames() {
  return (await readdir(auditDirectory)).filter(name => name.endsWith('.png')).sort();
}

function primaryActions(swarms) {
  return Object.fromEntries((Array.isArray(swarms) ? swarms : []).flatMap(swarm => {
    if (!swarm || typeof swarm !== 'object') return [];
    if (swarm.runtimeResourceState === 'REMOVING' || swarm.observationStale !== false) return [];
    if (swarm.activeOperation && ['ACCEPTED', 'DISPATCHED'].includes(String(swarm.activeOperation.state ?? ''))) return [];
    if (swarm.controllerState !== 'READY') return [];
    if (swarm.workloadState === 'RUNNING') return [[swarm.id, 'STOP']];
    if (swarm.workloadState === 'STOPPED') return [[swarm.id, 'START']];
    return [];
  }));
}

import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { mkdir, readFile, readdir, unlink, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import path from 'node:path';

import AxeBuilder from '@axe-core/playwright';
import { chromium } from 'playwright';

const require = createRequire(import.meta.url);
const { POCKETHIVE_COMPANION_SCOPES } = require('../out/connection/contracts.js');
const { LoopbackBrowserAuthorization } = require('../out/connection/loopbackBrowser.js');
const { McpHttpClient } = require('../out/mcp/httpClient.js');
const {
  boundCompanionViewModel,
  VIEW_FIELD_BYTE_LIMIT,
} = require('../out/webview/viewModelBoundary.js');
const { EventPagePresentation } = require('../out/webview/eventPresentation.js');
const { SIDEBAR_EVENT_LIMIT } = require('../out/webview/workspaceTool.js');
const root = path.resolve(import.meta.dirname, '..');
const auditDirectory = path.resolve(root, 'reports', 'playwright-ui');
const manifest = JSON.parse(await readFile(path.join(root, 'package.json'), 'utf8'));
const endpoint = 'http://localhost:8088/mcp';
const connectionScopes = [...POCKETHIVE_COMPANION_SCOPES];
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
  await captureCallbackOutcome(browser, 'auth-callback-cancelled',
    '?error=access_denied&state=visual-audit', '21-auth-callback-cancelled');
  await captureCallbackOutcome(browser, 'auth-callback-error',
    '?error=server_error&state=visual-audit', '22-auth-callback-error');
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
      { label: 'Version', needsWorker: true },
      { label: 'Inspect', needsWorker: true },
      { label: 'Runtime assessment', needsWorker: false },
      { label: 'Rabbit topology', needsWorker: false },
      { label: 'Timeline', needsWorker: false },
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
  await show(page, { ...base, page: 'environments', profiles: [profile] }, '03-connected-environment');
  await captureSelected(page, '10-selected-environments');
  await page.getByText('Credentials are stored securely by VS Code.', { exact: true }).waitFor({ state: 'visible' });
  await clickAndExpectMessage(page, 'Open', { type: 'openEnvironment', profileId: profile.id });

  const workspaceBase = {
    ...base,
    page: 'workspace',
    profiles: [profile],
    draft: profile,
    activeProfile: profile,
    environmentHealth: local.environmentHealth,
    session: {
      status: 'Connected',
      message: 'Secure session active',
      canUseWorkspace: true,
      canSignIn: false,
      canSignOut: true,
    },
  };
  await show(page, { ...workspaceBase, activeTab: 'Hive', workspaceData: local.swarms }, '04-workspace-hive');
  assert.equal(await page.locator('#app > .brand, .workspace-heading').count(), 0,
    'the narrow workspace must not reserve vertical space for duplicated environment identity');
  assert.equal(await page.locator('[role="tab"] .codicon').count(), TABS.length,
    'every workspace tab must expose a local VS Code icon');
  assert.equal(await page.evaluate(() => document.fonts.check('16px codicon')), true,
    'the packaged Codicon font must load in the webview');
  const healthGeometry = await page.locator('.environment-health').evaluate(rail => {
    const rect = rail.getBoundingClientRect();
    return { position: getComputedStyle(rail).position, bottom: rect.bottom, viewport: innerHeight };
  });
  assert.equal(healthGeometry.position, 'fixed', 'environment health must remain a fixed slim rail');
  assert.equal(Math.abs(healthGeometry.viewport - healthGeometry.bottom) <= 1, true,
    'environment health must remain pinned to the viewport bottom');
  assert.equal(await page.locator('.environment-health__mark').getAttribute('src'), '/resources/logo-mark.svg',
    'the environment identity must use the packaged PocketHive hexagon');
  assert.equal(await page.locator('.environment-health').getByText('Local MCP', { exact: true }).count(), 1);
  const expectedUnavailable = local.environmentHealth.services
    .filter(service => service.status === 'UNAVAILABLE').length;
  const expectedHealthSummary = expectedUnavailable === 0
    ? `${local.environmentHealth.services.length} services healthy`
    : `${expectedUnavailable} service${expectedUnavailable === 1 ? '' : 's'} unavailable`;
  await page.locator('.environment-health').getByText(expectedHealthSummary, { exact: true })
    .waitFor({ state: 'visible' });
  await page.getByLabel(`Environment health: ${expectedHealthSummary}`).click();
  await page.getByText('PocketHive UI', { exact: true }).waitFor({ state: 'visible' });
  await page.getByText('TCP Mock', { exact: true }).waitFor({ state: 'visible' });
  await page.getByText('Grafana', { exact: true }).waitFor({ state: 'visible' });
  const healthDrawerGeometry = await page.evaluate(() => {
    const panel = document.querySelector('.environment-health__panel');
    const rail = document.querySelector('.environment-health__rail');
    const rows = panel?.querySelectorAll('.environment-service');
    if (!panel || !rail || !rows?.length) return undefined;
    const panelRect = panel.getBoundingClientRect();
    const railRect = rail.getBoundingClientRect();
    const lastRect = rows.item(rows.length - 1).getBoundingClientRect();
    const style = getComputedStyle(panel);
    return {
      panelToRailGap: Math.abs(panelRect.bottom - railRect.top),
      trailingGap: Math.abs(panelRect.bottom - lastRect.bottom),
      topLeftRadius: style.borderTopLeftRadius,
      topRightRadius: style.borderTopRightRadius,
    };
  });
  assert.deepEqual(healthDrawerGeometry, {
    panelToRailGap: 0,
    trailingGap: 0,
    topLeftRadius: '0px',
    topRightRadius: '0px',
  }, 'the square health drawer must end at its final service row and meet the rail directly');
  await page.getByLabel('Account', { exact: true }).click();
  const accountPanel = page.locator('.account-menu__panel');
  await accountPanel.getByText('local-admin', { exact: true }).waitFor({ state: 'visible' });
  await accountPanel.getByText('Signed in to Local MCP', { exact: true }).waitFor({ state: 'visible' });
  assert.equal(await accountPanel.evaluate(panel => getComputedStyle(panel).position), 'absolute',
    'the account menu must overlay the health drawer without reserving layout height');
  await captureSelected(page, '20-environment-health-account-overlay');
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
      bees: [
        { instance: 'checkout-generator-1', role: 'generator', image: 'generator:1.2.3' },
        { instance: 'checkout-request-builder-1', role: 'request-builder', image: 'request-builder:1.2.3' },
      ],
    },
    {
      id: 'nightly-smoke',
      templateId: 'nightly-smoke',
      controllerState: 'READY',
      workloadState: 'STOPPED',
      runtimeResourceState: 'PRESENT',
      observationStale: false,
      bees: [{ instance: 'nightly-generator-1', role: 'generator', image: 'generator:1.2.3' }],
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
  assert.equal(await runningRow.locator('.swarm-row__heading .status').count(), 1,
    'running lifecycle state must remain adjacent to its swarm identity');
  assert.equal(await readyRow.locator('.swarm-row__heading .status').count(), 1,
    'ready lifecycle state must remain adjacent to its swarm identity');
  const swarmGroupGeometry = await page.locator('.swarm-row').evaluateAll(rows => rows.map(row => {
    const rect = row.getBoundingClientRect();
    const style = getComputedStyle(row);
    return {
      top: rect.top,
      bottom: rect.bottom,
      borderLeftWidth: Number.parseFloat(style.borderLeftWidth),
      borderLeftColor: style.borderLeftColor,
    };
  }));
  assert.equal(swarmGroupGeometry.length, 2, 'each swarm must render as one bounded group');
  assert.equal(swarmGroupGeometry[1].top - swarmGroupGeometry[0].bottom >= 8, true,
    'separate swarms must retain a visible group gap');
  assert.equal(swarmGroupGeometry.every(row => row.borderLeftWidth >= 3), true,
    'each swarm must expose a lifecycle edge');
  assert.notEqual(swarmGroupGeometry[0].borderLeftColor, swarmGroupGeometry[1].borderLeftColor,
    'running and ready swarms must use distinct lifecycle accents');
  const historyGeometry = await runningRow.evaluate(row => {
    const disclosure = row.querySelector('.history-toggle');
    const chevron = row.querySelector('.history-toggle__chevron');
    const rowRect = row.getBoundingClientRect();
    const disclosureRect = disclosure.getBoundingClientRect();
    const chevronRect = chevron.getBoundingClientRect();
    return {
      rowWidth: rowRect.width,
      disclosureWidth: disclosureRect.width,
      disclosureRight: disclosureRect.right,
      chevronRight: chevronRect.right,
    };
  });
  assert.equal(Math.abs(historyGeometry.rowWidth - historyGeometry.disclosureWidth) <= 4, true,
    'run history must use a full-width disclosure row owned by its swarm');
  assert.equal(historyGeometry.disclosureRight - historyGeometry.chevronRight <= 12, true,
    'run-history chevron must remain at the far edge of the disclosure row');
  await clickWithinAndExpectMessage(page, runningRow, 'Stop', {
    type: 'runSwarmOperation', action: 'STOP', swarmId: 'checkout-load',
  });
  await clickWithinAndExpectMessage(page, readyRow, 'Start', {
    type: 'runSwarmOperation', action: 'START', swarmId: 'nightly-smoke',
  });
  await clickWithinAndExpectMessage(page, runningRow, 'Debug', {
    type: 'openDebugForSwarm', swarmId: 'checkout-load',
  });
  await clickWithinAndExpectMessage(page, runningRow, 'View swarm in Web UI', {
    type: 'openWebUi', destination: 'SWARM', swarmId: 'checkout-load',
  });
  assert.equal(await runningRow.getByRole('button', { name: 'Remove swarm', exact: true }).isDisabled(), true,
    'a running swarm must not expose executable removal');
  await clickWithinAndExpectMessage(page, readyRow, 'Remove swarm', {
    type: 'runSwarmOperation', action: 'REMOVE', swarmId: 'nightly-smoke',
  });
  await runningRow.getByLabel('Workers, 2').click();
  await runningRow.getByText('checkout-generator-1', { exact: true }).waitFor({ state: 'visible' });
  const workerLayout = await runningRow.evaluate(row => {
    const details = row.querySelector('.swarm-workers');
    const summary = row.querySelector('.swarm-workers__summary');
    const list = row.querySelector('.swarm-workers__list');
    const actions = row.querySelector('.swarm-row__secondary');
    const actionButtons = [...actions.querySelectorAll('button')].map(button => button.getBoundingClientRect());
    const detailsRect = details.getBoundingClientRect();
    const summaryRect = summary.getBoundingClientRect();
    const listRect = list.getBoundingClientRect();
    const actionsRect = actions.getBoundingClientRect();
    return {
      detailsWidth: detailsRect.width,
      summaryWidth: summaryRect.width,
      listWidth: listRect.width,
      actionsWidth: actionsRect.width,
      listTop: listRect.top,
      summaryBottom: summaryRect.bottom,
      actionsTop: actionsRect.top,
      listBottom: listRect.bottom,
      actionButtonTops: actionButtons.map(rect => rect.top),
      actionButtonWidths: actionButtons.map(rect => rect.width),
    };
  });
  assert.equal(Math.abs(workerLayout.detailsWidth - workerLayout.summaryWidth) <= 2, true,
    'workers must use a full-width disclosure row');
  assert.equal(Math.abs(workerLayout.detailsWidth - workerLayout.listWidth) <= 2, true,
    'expanded worker resources must remain full width inside their swarm');
  assert.equal(workerLayout.listTop >= workerLayout.summaryBottom, true,
    'worker resources must follow the disclosure without overlap');
  assert.equal(workerLayout.actionsTop >= workerLayout.listBottom, true,
    'swarm actions must remain a distinct row below expanded workers');
  assert.equal(Math.max(...workerLayout.actionButtonTops) - Math.min(...workerLayout.actionButtonTops) <= 2, true,
    'Debug, Web UI, and Remove must remain on one compact action line');
  assert.equal(Math.max(...workerLayout.actionButtonWidths) - Math.min(...workerLayout.actionButtonWidths) <= 2, true,
    'Debug, Web UI, and Remove must use three equal horizontal grid sections');
  assert.equal(Math.abs(workerLayout.actionsWidth - workerLayout.actionButtonWidths.reduce((total, width) => total + width, 0)) <= 3, true,
    'the three swarm actions must fill their complete action row');
  const swarmSearchControl = page.getByLabel('Search swarms');
  await swarmSearchControl.fill('checkout');
  await runningRow.getByLabel('Workers, 2').click();
  await page.getByLabel(`Environment health: ${expectedHealthSummary}`).click();
  await swarmSearchControl.evaluate(control => {
    control.focus();
    control.setSelectionRange(3, 3);
  });
  await dispatch(page, { ...interactionModel, workspaceData: [...interactionSwarms] });
  assert.equal(await runningRow.locator('.swarm-workers').getAttribute('open'), '',
    'a same-tab background replacement must preserve an expanded worker disclosure');
  assert.equal(await page.locator('.environment-health__panel').isVisible(), true,
    'a same-tab background replacement must preserve the health disclosure');
  assert.deepEqual(await page.evaluate(() => ({
    id: document.activeElement?.id,
    selectionStart: document.activeElement?.selectionStart,
    selectionEnd: document.activeElement?.selectionEnd,
  })), { id: 'swarmSearch', selectionStart: 3, selectionEnd: 3 },
  'a same-tab background replacement must preserve focused search and its caret');
  await swarmSearchControl.fill('');
  const firstWorker = runningRow.locator('.swarm-worker').first();
  await clickWithinAndExpectMessage(page, firstWorker, 'Inspect', {
    type: 'openDebugForWorker', swarmId: 'checkout-load', instance: 'checkout-generator-1', action: 'Inspect',
  });
  await clickWithinAndExpectMessage(page, firstWorker, 'Logs', {
    type: 'openDebugForWorker', swarmId: 'checkout-load', instance: 'checkout-generator-1', action: 'Logs',
  });
  await page.getByText('PocketHive UI', { exact: true }).waitFor({ state: 'visible' });
  await captureSelected(page, '11a-selected-hive-workers-health');
  await page.getByLabel(`Environment health: ${expectedHealthSummary}`).click();

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
    { detailId: 'fixture-detail-1', timestamp: '2026-08-19T10:42:18Z', severity: 'INFO', kind: 'signal', type: 'swarm-start', swarmId: 'nightly-smoke', origin: 'orchestrator', direction: 'OUT' },
    { detailId: 'fixture-detail-2', timestamp: '2026-08-19T10:41:53Z', severity: 'ERROR', kind: 'runtime-debug', type: 'runtime-log-snapshot', swarmId: 'auth-regression', origin: 'orchestrator', direction: 'LOCAL' },
  ], nextCursor: null, hasMore: false };
  await dispatch(page, {
    ...workspaceBase,
    activeTab: 'Journal',
    workspaceData: interactionSwarms,
    journalSwarmId: 'nightly-smoke',
    journalRunId: 'run-nightly-7',
    journalResult: eventFixture,
  });
  await captureSelected(page, '13-selected-journal');
  await page.locator('.event-row').first().locator(':scope > summary').click();
  assert.equal(await page.getByRole('button', { name: 'Open Debug', exact: true }).count(), 0,
    'Journal event rows must leave diagnostics to the dedicated Debug workspace');
  await clickAndExpectMessage(page, 'View run in Web UI', {
    type: 'openWebUi', destination: 'JOURNAL_RUN', swarmId: 'nightly-smoke', runId: 'run-nightly-7',
  });
  await dispatch(page, { ...workspaceBase, activeTab: 'Buzz', workspaceData: eventFixture });
  await captureSelected(page, '14-selected-buzz');
  await clickAndExpectMessage(page, 'Open Buzz in Web UI', { type: 'openWebUi', destination: 'BUZZ' });
  assert.equal(await page.locator('.event-row').count(), 2, 'Buzz must render one compact row per event');
  assert.equal(await page.getByLabel('Severity').isVisible(), false,
    'advanced event fields must remain collapsed until requested');
  const eventRowHeights = await page.locator('.event-row > summary').evaluateAll(rows => rows.map(row => (
    row.getBoundingClientRect().height
  )));
  assert.equal(eventRowHeights.every(height => height <= 48), true,
    'Buzz and Journal event summaries must remain single-line rows');
  await page.getByLabel('Advanced filters').click();
  await page.getByLabel('Severity').selectOption('ERROR');
  assert.equal(await page.locator('.event-row').count(), 1, 'severity filter must narrow the rendered page');
  await page.getByLabel('Search events').fill('auth-regression');
  assert.equal(await page.locator('.event-row').count(), 1, 'search filter must compose with severity');
  await page.locator('.event-row').first().getByText('runtime-debug/runtime-log-snapshot', { exact: true }).click();
  const buzzSearchControl = page.getByLabel('Search events');
  await buzzSearchControl.evaluate(control => {
    control.focus();
    control.setSelectionRange(4, 4);
  });
  await dispatch(page, { ...workspaceBase, activeTab: 'Buzz', workspaceData: { ...eventFixture } });
  assert.equal(await page.getByLabel('Severity').isVisible(), true,
    'a same-tab background replacement must preserve expanded advanced filters');
  assert.equal(await page.locator('.event-row').first().getAttribute('open'), '',
    'a same-tab background replacement must preserve an expanded event');
  assert.deepEqual(await page.evaluate(() => ({
    id: document.activeElement?.id,
    selectionStart: document.activeElement?.selectionStart,
    selectionEnd: document.activeElement?.selectionEnd,
  })), { id: 'buzzSearch', selectionStart: 4, selectionEnd: 4 },
  'a same-tab background replacement must preserve event-search focus and its caret');
  await clickAndExpectMessage(page, 'Open technical details', {
    type: 'openEventDetails', detailId: 'fixture-detail-2',
  });
  assert.equal(await page.getByRole('button', { name: 'Open Debug', exact: true }).count(), 0,
    'Buzz event rows must leave diagnostics to the dedicated Debug workspace');

  const scenarioFixture = [
    {
      id: 'checkout-smoke',
      name: 'Checkout smoke',
      description: 'Exercises the complete checkout path with representative test data and bounded load.',
      controllerImage: 'swarm-controller:latest',
      bees: [
        { role: 'generator', image: 'generator:latest' },
        { role: 'processor', image: 'http-sequence:latest' },
      ],
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
  const scenarioFocus = {
    scenarioFocusScenarioId: 'checkout-smoke',
    scenarioFocusBundleKey: 'bundles/checkout-smoke',
    scenarioFocusSection: 'FILES',
    scenarioFocusTree: { nodes: [
      { nodeType: 'directory', name: 'datasets', path: 'datasets' },
      { nodeType: 'directory', name: 'templates', path: 'templates' },
      { nodeType: 'directory', name: 'http', path: 'templates/http' },
      { nodeType: 'directory', name: 'checkout', path: 'templates/http/checkout' },
      { nodeType: 'file', name: 'checkout.csv', path: 'datasets/checkout.csv', size: 164, editorKind: 'text' },
      { nodeType: 'file', name: 'scenario.yaml', path: 'scenario.yaml', size: 812, editorKind: 'yaml' },
      { nodeType: 'file', name: 'request.yaml', path: 'templates/http/checkout/request.yaml', size: 204, editorKind: 'yaml' },
    ] },
  };
  const retainedPublication = {
    publicationError: {
      attemptId: 'pa-retained-acceptance',
      code: 'PUBLICATION_RESULT_AMBIGUOUS',
    },
  };
  await dispatch(page, {
    ...workspaceBase, activeTab: 'Scenarios', workspaceData: scenarioFixture, ...scenarioFocus,
    bundleResult: retainedPublication,
  });
  assert.equal(await page.getByRole('button', { name: /^Deployed/ }).getAttribute('aria-pressed'), 'true',
    'retained publication state must not override the deployed scenario source');
  await captureSelected(page, '15-selected-scenarios');
  assert.equal(await page.locator('.scenario-tree__row').count(), 7,
    'the selected scenario must expose its exact mixed-file deployed tree');
  assert.equal(await page.getByRole('button', { name: 'Open details', exact: true }).count(), 0,
    'redundant scenario shortcuts must not compete with the drill-down');
  const datasetBranch = page.locator('details.scenario-tree__branch').filter({
    has: page.locator(':scope > summary strong', { hasText: 'datasets' }),
  });
  const templateBranch = page.locator('details.scenario-tree__branch').filter({
    has: page.locator(':scope > summary strong', { hasText: 'templates' }),
  });
  assert.equal(await datasetBranch.locator('.scenario-tree__row--file strong', { hasText: 'checkout.csv' }).count(), 1,
    'dataset files must render inside the datasets branch');
  assert.equal(await templateBranch.locator('.scenario-tree__row--file strong', { hasText: 'request.yaml' }).count(), 1,
    'deep template files must render inside their complete directory ancestry');
  const datasetFile = page.locator('.scenario-tree__row--file').filter({
    has: page.getByText('checkout.csv', { exact: true }),
  });
  await clickWithinAndExpectMessage(page, datasetFile, 'Preview', {
    type: 'openScenarioBundleFile', bundleKey: 'bundles/checkout-smoke', path: 'datasets/checkout.csv',
  });
  await dispatch(page, {
    ...workspaceBase, activeTab: 'Scenarios', workspaceData: scenarioFixture, ...scenarioFocus,
    bundleResult: retainedPublication,
  });
  assert.equal(await page.getByRole('button', { name: /^Deployed/ }).getAttribute('aria-pressed'), 'true',
    'deployed file preview refreshes must retain their scenario source');

  await clickAndExpectMessage(page, 'Overview', {
    type: 'selectScenarioSection',
    scenarioId: 'checkout-smoke',
    bundleKey: 'bundles/checkout-smoke',
    section: 'OVERVIEW',
  });
  await dispatch(page, {
    ...workspaceBase,
    activeTab: 'Scenarios',
    workspaceData: scenarioFixture,
    ...scenarioFocus,
    scenarioFocusSection: 'OVERVIEW',
    bundleResult: retainedPublication,
  });
  assert.equal(await page.getByRole('button', { name: /^Deployed/ }).getAttribute('aria-pressed'), 'true',
    'deployed overview refreshes must retain their scenario source');
  await captureSelected(page, '15a-selected-scenario-overview');
  assert.equal(await page.locator('.scenario-overview > .scenario-info-card').count(), 3,
    'Description, Controller, and Bees must each occupy one overview row');
  const overviewWidths = await page.locator('.scenario-overview > .scenario-info-card').evaluateAll(cards =>
    cards.map(card => Math.round(card.getBoundingClientRect().width)));
  assert.equal(new Set(overviewWidths).size, 1, 'all overview rows must use the same full width');
  await clickAndExpectMessage(page, 'Inputs', {
    type: 'selectScenarioSection',
    scenarioId: 'checkout-smoke',
    bundleKey: 'bundles/checkout-smoke',
    section: 'INPUTS',
  });
  await dispatch(page, {
    ...workspaceBase,
    activeTab: 'Scenarios',
    workspaceData: scenarioFixture,
    ...scenarioFocus,
    scenarioFocusSection: 'INPUTS',
    scenarioFocusInputs: {
      variablesPath: 'variables.yaml',
      authProfilesPath: 'authProfiles.yaml',
      suts: [{
        sutId: 'checkout',
        descriptor: { name: 'Checkout', endpoints: { api: { baseUrl: 'https://checkout.example/api' } } },
      }],
    },
    bundleResult: retainedPublication,
  });
  assert.equal(await page.getByRole('button', { name: /^Deployed/ }).getAttribute('aria-pressed'), 'true',
    'deployed inputs refreshes must retain their scenario source');
  await captureSelected(page, '15-selected-scenarios-inputs');
  await page.getByText('variables.yaml', { exact: true }).waitFor({ state: 'visible' });
  await page.getByText('authProfiles.yaml', { exact: true }).waitFor({ state: 'visible' });
  await page.getByText('https://checkout.example/api', { exact: true }).waitFor({ state: 'visible' });
  await page.getByLabel('Search bundles').fill('postgres');
  assert.equal(await page.locator('.scenario-row').count(), 1, 'scenario search must narrow compact rows');
  await page.getByLabel('Scenario filters').click();
  await page.getByLabel('Folder').selectOption('bundles');
  assert.equal(await page.locator('.scenario-row').count(), 0, 'folder and search filters must compose');

  await dispatch(page, {
    ...workspaceBase,
    activeTab: 'Scenarios',
    workspaceData: scenarioFixture,
    repositoryScenarios: {
      state: 'SCANNED',
      repositories: [{
        workspaceName: 'PocketHive',
        commit: 'a'.repeat(40),
        candidates: [{
          candidateId: 'candidate-acceptance-1',
          bundlePath: 'scenarios/bundles/checkout-smoke',
          files: [
            'datasets/checkout.csv',
            'scenario.yaml',
            'sut/checkout/sut.yaml',
            'templates/http/request.yaml',
            'variables.yaml',
          ],
        }],
      }, {
        workspaceName: 'SharedScenarios',
        commit: 'b'.repeat(40),
        candidates: [{
          candidateId: 'candidate-acceptance-2',
          bundlePath: 'scenarios/bundles/nightly-smoke',
          files: ['scenario.yaml', 'templates/http/control.yaml'],
        }],
      }],
      failures: [{ workspaceName: 'notes', code: 'GIT_REPOSITORY_REQUIRED' }],
    },
  });
  await page.getByRole('button', { name: 'Repository' }).click();
  await captureSelected(page, '15b-repository-scenarios');
  await page.getByText('Committed HEAD only. Edit, commit, then refresh before validation or deployment.', { exact: true })
    .waitFor({ state: 'visible' });
  assert.equal(await page.getByText('scenarios/bundles/checkout-smoke', { exact: true }).count(), 1,
    'the repository view must render the exact committed candidate path');
  assert.equal((await page.locator('.repository-scenarios').textContent()).includes('/workspace/'), false,
    'the repository projection must not expose extension-host filesystem paths');
  const repositoryCards = page.locator('.repository-scenario');
  assert.equal(await repositoryCards.count(), 2,
    'each committed candidate must render as one self-contained scenario card');
  const repositoryCard = repositoryCards.first();
  assert.equal(await repositoryCard.getAttribute('open'), null,
    'Repository scenarios must be collapsed by default');
  await repositoryCard.locator('summary').first().click();
  assert.equal(await repositoryCard.getAttribute('open'), '',
    'a Repository scenario must open from its own summary');
  await repositoryCard.locator('summary').first().click();
  assert.equal(await repositoryCard.getAttribute('open'), null,
    'the final open Repository scenario must remain collapsed');
  assert.equal(await repositoryCards.evaluateAll(cards => cards.every(card => !card.hasAttribute('open'))), true,
    'Repository scenarios must support an explicit all-collapsed state');
  await repositoryCard.locator('summary').first().click();
  assert.equal(await repositoryCard.getAttribute('open'), '',
    'a collapsed Repository scenario must reopen from its own summary');
  assert.deepEqual(await repositoryCard.locator('.repository-scenario__actions button').allTextContents(),
    ['Edit', 'Validate', 'Deploy'],
    'each Repository scenario must own its complete three-action row');
  assert.deepEqual(await repositoryCard.locator('.repository-scenario__tabs button').allTextContents(),
    ['Overview', 'Files', 'Inputs'],
    'Repository drill-down must remain inside the selected scenario card');
  await page.getByLabel('Search repository scenarios').fill('nightly');
  assert.equal(await page.locator('.repository-scenario').count(), 1,
    'Repository search must narrow self-contained cards without calling another owner');
  await page.getByLabel('Search repository scenarios').fill('');
  await page.getByLabel('Repository filters').click();
  await page.getByLabel('Workspace').selectOption('PocketHive');
  assert.equal(await page.locator('.repository-scenario').count(), 1,
    'the advanced workspace filter must compose with Repository search');
  await page.getByLabel('Workspace').selectOption('ALL');
  await page.getByLabel('Repository filters').click();
  await repositoryCard.locator('summary').first().click();
  assert.equal(await repositoryCard.getAttribute('open'), '',
    'a filtered Repository scenario remains explicitly reopenable');
  await clickWithinAndExpectMessage(page, repositoryCard.locator('.repository-scenario__actions'), 'Edit', {
    type: 'openRepositoryBundleFile', candidateId: 'candidate-acceptance-1', path: 'scenario.yaml',
  });
  const requestFile = repositoryCard.locator('.scenario-tree__row--file').filter({
    has: page.getByText('request.yaml', { exact: true }),
  });
  await clickWithinAndExpectMessage(page, requestFile, 'Edit', {
    type: 'openRepositoryBundleFile', candidateId: 'candidate-acceptance-1', path: 'templates/http/request.yaml',
  });
  await clickWithinAndExpectMessage(page, repositoryCard, 'Validate', {
    type: 'validateRepositoryBundle', candidateId: 'candidate-acceptance-1',
  });
  const validationReceipt = {
    receiptId: 'vr-acceptance-1',
    archiveDigest: `sha256:${'a'.repeat(64)}`,
    bundleContentDigest: `sha256:${'b'.repeat(64)}`,
    scenarioId: 'checkout-smoke',
    scenarioName: 'Checkout smoke',
  };
  await dispatch(page, {
    ...workspaceBase,
    activeTab: 'Scenarios',
    workspaceData: scenarioFixture,
    repositoryScenarios: {
      state: 'SCANNED', repositories: [{
        workspaceName: 'PocketHive', commit: 'a'.repeat(40), candidates: [{
          candidateId: 'candidate-acceptance-1',
          bundlePath: 'scenarios/bundles/checkout-smoke',
          files: ['datasets/checkout.csv', 'scenario.yaml', 'sut/checkout/sut.yaml',
            'templates/http/request.yaml', 'variables.yaml'],
        }],
      }], failures: [],
    },
    repositoryPendingCandidateId: 'candidate-acceptance-1',
    repositoryResultCandidateId: 'candidate-acceptance-1',
    pendingBundle: {
      source: { bundlePath: 'scenarios/bundles/checkout-smoke', commit: 'a'.repeat(40) },
      fileCount: 5,
      validationReceipt,
    },
    bundleResult: { validationReceipt },
  });
  const validatedRepositoryCard = page.locator('.repository-scenario').first();
  await validatedRepositoryCard.getByText('Checkout smoke', { exact: true }).waitFor({ state: 'visible' });
  await validatedRepositoryCard.locator('.repository-scenario__validation strong')
    .getByText('Valid', { exact: true }).waitFor({ state: 'visible' });
  await clickWithinAndExpectMessage(page, validatedRepositoryCard, 'Deploy', {
    type: 'deployRepositoryBundle', candidateId: 'candidate-acceptance-1',
  });
  await clickAndExpectMessage(page, 'Choose committed folder', { type: 'validateCommittedBundle' });

  await dispatch(page, {
    ...workspaceBase,
    activeTab: 'Scenarios',
    workspaceData: scenarioFixture,
    repositoryScenarios: {
      state: 'SCANNED', repositories: [{
        workspaceName: 'PocketHive', commit: 'a'.repeat(40), candidates: [{
          candidateId: 'candidate-acceptance-1',
          bundlePath: 'scenarios/bundles/checkout-smoke', files: ['scenario.yaml'],
        }],
      }], failures: [],
    },
    repositoryPendingCandidateId: 'candidate-acceptance-1',
    repositoryResultCandidateId: 'candidate-acceptance-1',
    pendingBundle: {
      source: { bundlePath: 'scenarios/bundles/checkout-smoke', commit: 'a'.repeat(40) },
      fileCount: 1,
      validationReceipt,
    },
    bundleResult: { validationReceipt },
    repositoryDeploymentConflict: {
      kind: 'CONFLICT', candidateId: 'candidate-acceptance-1',
      scenarioId: 'checkout-smoke', scenarioName: 'Checkout smoke',
      suggestedScenarioId: 'checkout-smoke-01', suggestedScenarioName: 'Checkout smoke-01',
    },
  });
  await captureSelected(page, '15c-repository-deployment-conflict');
  assert.equal(await page.locator('.repository-scenario pre').count(), 0,
    'Repository validation evidence must stay concise and self-contained without duplicate raw JSON');
  const deploymentDialog = page.getByRole('dialog', { name: 'Scenario deployment conflict' });
  assert.equal(await deploymentDialog.getByLabel('New scenario ID').inputValue(), 'checkout-smoke-01');
  assert.equal(await deploymentDialog.getByLabel('New scenario name').inputValue(), 'Checkout smoke-01');
  await clickWithinAndExpectMessage(page, deploymentDialog, 'Replace existing', {
    type: 'replaceRepositoryBundle', candidateId: 'candidate-acceptance-1',
  });
  await deploymentDialog.getByLabel('New scenario ID').fill('checkout-smoke-copy');
  await deploymentDialog.getByLabel('New scenario name').fill('Checkout smoke copy');
  await clickWithinAndExpectMessage(page, deploymentDialog, 'Open scenario.yaml', {
    type: 'openRepositoryRename', candidateId: 'candidate-acceptance-1',
    scenarioId: 'checkout-smoke-copy', scenarioName: 'Checkout smoke copy',
  });

  await dispatch(page, {
    ...workspaceBase, activeTab: 'Debug', workspaceData: interactionSwarms, debugSwarmId: 'checkout-load',
    debugRuntimeId: 'request-builder-7f8c9',
    debugWorkersResult: [{ runtimeId: 'request-builder-7f8c9' }],
    debugAction: 'Logs',
    debugResult: {
      target: { runtimeId: 'request-builder-7f8c9', instance: 'request-builder-1' },
      tailLines: 200,
      since: null,
      redacted: true,
      lineCount: 2,
      logs: 'Bounded evidence line 1\nBounded evidence line 2',
    },
  });
  await captureSelected(page, '16-selected-debug');
  assert.deepEqual(await page.locator('.debug-worker-tabs [role="tab"]').allTextContents(),
    ['Logs', 'Inspect', 'Version']);
  await page.getByText('Container logs', { exact: true }).waitFor({ state: 'visible' });
  await page.getByText('Docker stdout/stderr · tail 200', { exact: true }).waitFor({ state: 'visible' });
  assert.equal(await page.locator('.debug-runtime-target .debug-evidence').count(), 1,
    'worker evidence must remain adjacent to the selected worker diagnostic');
  assert.deepEqual(await page.locator('.debug-swarm-tools .button').allTextContents(),
    ['Workers', 'Runtime assessment', 'Rabbit topology', 'Timeline']);
  assert.equal(await page.locator('.debug-maintenance').getByText('Plan only', { exact: true }).count(), 1,
    'cleanup must remain a visibly plan-only maintenance action');
  assert.equal(await page.locator('.debug-group').count(), 0,
    'the Debug page must not retain the disjointed disclosure stack');
  assert.equal(await page.getByLabel('Exact swarm', { exact: true }).getAttribute('role'), 'combobox');
  assert.equal(await page.getByLabel('Exact swarm', { exact: true }).getAttribute('aria-autocomplete'), 'list');
  assert.equal(await page.getByLabel('Exact worker', { exact: true }).getAttribute('role'), 'combobox');
  await clickAndExpectMessage(page, 'Logs', { type: 'runDebug', action: 'Logs', tailLines: 200 }, 'tab');
  const toolColumns = await page.locator('.debug-swarm-tools').evaluate(element =>
    getComputedStyle(element).gridTemplateColumns.split(' ').length);
  assert.equal(toolColumns, 2, 'the normal Side Bar width must keep the compact two-column swarm-tool matrix');
  await page.getByRole('button', { name: 'Swarm', exact: true }).click();
  await page.getByRole('button', { name: 'Swarm', exact: true }).waitFor({ state: 'visible' });
  assert.equal(await page.getByRole('button', { name: 'Swarm', exact: true }).getAttribute('aria-pressed'), 'true',
    'the compact context control must expose its active swarm target');
  await page.waitForFunction(() => scrollY > 0);
  await page.screenshot({ path: path.join(auditDirectory, '16-selected-debug-swarm-tools.png') });
  await page.getByRole('button', { name: 'Worker', exact: true }).click();
  assert.equal(await page.getByRole('button', { name: 'Worker', exact: true }).getAttribute('aria-pressed'), 'true',
    'the compact context control must return to the worker target');
  await page.evaluate(() => scrollTo(0, 0));
  const dropdownSwarms = Array.from({ length: 12 }, (_, index) => ({
    id: `debug-swarm-${String(index + 1).padStart(2, '0')}`,
  }));
  const dropdownWorkers = Array.from({ length: 12 }, (_, index) => ({
    runtimeId: `debug-worker-${String(index + 1).padStart(2, '0')}`,
  }));
  await dispatch(page, {
    ...workspaceBase,
    activeTab: 'Debug',
    workspaceData: dropdownSwarms,
    debugSwarmId: dropdownSwarms[0].id,
    debugWorkersResult: dropdownWorkers,
  });
  for (const label of ['Exact swarm', 'Exact worker']) {
    const control = page.getByLabel(label, { exact: true });
    await control.fill('');
    await control.focus();
    const menu = control.locator('..').locator('..').locator('.choice-popover');
    const geometry = await menu.evaluate(popover => {
      const popoverRect = popover.getBoundingClientRect();
      const clippingAncestors = [];
      for (let ancestor = popover.parentElement; ancestor; ancestor = ancestor.parentElement) {
        const style = getComputedStyle(ancestor);
        const clipsX = ['hidden', 'clip'].includes(style.overflowX);
        const clipsY = ['hidden', 'clip'].includes(style.overflowY);
        if (!clipsX && !clipsY) continue;
        const rect = ancestor.getBoundingClientRect();
        if ((clipsX && (popoverRect.left < rect.left - 1 || popoverRect.right > rect.right + 1))
            || (clipsY && (popoverRect.top < rect.top - 1 || popoverRect.bottom > rect.bottom + 1))) {
          clippingAncestors.push(`${ancestor.tagName}.${ancestor.className}`);
        }
      }
      return {
        clippingAncestors,
        clientHeight: popover.clientHeight,
        scrollHeight: popover.scrollHeight,
      };
    });
    assert.ok(geometry.scrollHeight > geometry.clientHeight,
      `${label} fixture must exercise a long, internally scrollable list`);
    assert.deepEqual(geometry.clippingAncestors, [],
      `${label} choices must escape every clipping ancestor`);
    if (label === 'Exact worker') {
      await page.screenshot({ path: path.join(auditDirectory, '16b-selected-debug-dropdowns.png') });
    }
    await control.press('Escape');
  }
  await dispatch(page, {
    ...workspaceBase,
    activeTab: 'Debug',
    workspaceData: interactionSwarms,
    debugSwarmId: 'checkout-load',
    debugAction: 'Cleanup plan',
    debugResult: {
      candidateSetHash: 'sha256:a91c7be2',
      executionRisk: 'standard',
      candidates: [
        { candidateId: 'docker:container:abc', reason: 'stopped PocketHive runtime resource' },
        { candidateId: 'docker:network:def', reason: 'orphaned PocketHive runtime network' },
      ],
      blocked: [],
    },
  });
  await page.getByText('2 cleanup candidates', { exact: true }).waitFor({ state: 'visible' });
  assert.equal(await page.getByRole('button', { name: 'Execute cleanup', exact: true }).isDisabled(), true,
    'cleanup execution must stay disabled without governed HiveGate approval');
  await page.locator('.debug-maintenance').scrollIntoViewIfNeeded();
  await page.screenshot({ path: path.join(auditDirectory, '16a-selected-debug-cleanup-plan.png') });
  await dispatch(page, { ...workspaceBase, activeTab: 'Debug', workspaceData: interactionSwarms });
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
    scenarioCapabilitiesProbe: local.scenarioCapabilitiesProbe,
    disposableRuntimeProbe: local.disposableRuntimeProbe,
    journalRunsProbe: local.journalRunsProbe,
    runtimeDiagnosticsProbe: local.runtimeDiagnosticsProbe,
    debugTapProbe: local.debugTapProbe,
    compactReviewProbe: local.compactReviewProbe,
    eventPageBoundaryProbe: local.eventPageBoundaryProbe,
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
    scenarioCapabilitiesProbe: report.scenarioCapabilitiesProbe,
    disposableRuntimeProbe: report.disposableRuntimeProbe,
    journalRunsProbe: report.journalRunsProbe,
    runtimeDiagnosticsProbe: report.runtimeDiagnosticsProbe,
    debugTapProbe: report.debugTapProbe,
    compactReviewProbe: report.compactReviewProbe,
    eventPageBoundaryProbe: report.eventPageBoundaryProbe,
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
      const closedDisclosure = node.closest('details:not([open])');
      if (closedDisclosure && node !== closedDisclosure.querySelector(':scope > summary')
          && !node.closest('summary')) return false;
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
  let redirectUri;
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
    if (redirectUri && `${requested.origin}${requested.pathname}` === redirectUri) callback = requested;
  });
  const resource = await json(`${new URL(endpoint).origin}/.well-known/oauth-protected-resource`);
  assert.equal(resource.resource, endpoint);
  assert.deepEqual(resource.authorization_servers, [`${new URL(endpoint).origin}/auth-service`]);
  const issuer = resource.authorization_servers[0];
  const metadata = await json(`${issuer}/.well-known/oauth-authorization-server`);
  const verifier = randomBytes(64).toString('base64url');
  const challenge = createHash('sha256').update(verifier, 'ascii').digest('base64url');
  const state = randomBytes(32).toString('base64url');
  let authorizationResponse;
  const callbackController = new AbortController();
  const callbackResult = new LoopbackBrowserAuthorization(async url => {
    authorizationResponse = await page.goto(url, { waitUntil: 'domcontentloaded' });
    return true;
  }).authorize(runtimeRedirectUri => {
    redirectUri = runtimeRedirectUri;
    const authorizationUrl = new URL(metadata.authorization_endpoint);
    authorizationUrl.search = new URLSearchParams({
      response_type: 'code',
      client_id: 'pockethive-vscode',
      redirect_uri: redirectUri,
      resource: endpoint,
      scope: connectionScopes.join(' '),
      state,
      code_challenge: challenge,
      code_challenge_method: 'S256',
    }).toString();
    return authorizationUrl.toString();
  }, callbackController.signal);
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
    callbackController.abort();
    const location = new URL(page.url());
    throw new Error(`OAuth callback was not observed (initial status ${authorizationResponse?.status() ?? 'none'}) at ${
      location.origin}${location.pathname}; requests=${JSON.stringify(observedLocations.slice(-20))}: ${
      (await page.locator('body').innerText()).replaceAll(/\s+/g, ' ').slice(0, 500)}`);
  }
  const completedCallback = await callbackResult;
  assert.equal(completedCallback.callback.toString(), callback.toString(),
    'the live OAuth browser must reach the exact local callback listener');
  assert.equal(completedCallback.redirectUri, redirectUri,
    'authorization and token exchange must use the listener-owned redirect URI');
  await inspectAuthPage(page, 'auth-callback-success', '20-auth-callback-success');
  await page.setViewportSize({ width: 280, height: 760 });
  await inspectAuthPage(page, 'auth-callback-success-mobile', '23-auth-callback-success-mobile');
  await page.setViewportSize({ width: 520, height: 760 });
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
  assert.equal(typeof token.refresh_token, 'string', 'the companion session must be renewable');

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
  assert.equal(tools.length, 59, 'live MCP must expose the immutable complete tool catalogue');
  assert.equal(tools.every(tool => tool?.outputSchema?.type || tool?.outputSchema?.oneOf), true,
    'every live MCP tool must declare one explicit output root contract');
  for (const requiredTool of [
    'scenario_list',
    'scenario_templates_catalog',
    'scenario_suts_list',
    'scenario_workflow_review_prepare',
    'scenario_workflow_review_submit',
    'swarm_list',
    'swarm_get',
    'swarm_create',
    'swarm_start',
    'swarm_stop',
    'swarm_remove',
    'debug_tap',
    'debug_tap_read',
    'debug_tap_close',
    'debug_journal_runs',
    'runtime_tail_worker_logs',
    'runtime_inspect_worker',
    'runtime_get_worker_version',
    'runtime_assess_swarm',
    'runtime_diff_swarm_runtime',
    'runtime_control_plane_status',
    'runtime_manifest_validate',
  ]) {
    assert.equal(toolNames.has(requiredTool), true, `live MCP catalogue must expose ${requiredTool}`);
  }
  const debugTapTool = tools.find(tool => tool?.name === 'debug_tap');
  const debugTapReadTool = tools.find(tool => tool?.name === 'debug_tap_read');
  assert.deepEqual(debugTapTool?.inputSchema?.required,
    ['swarmId', 'role', 'direction', 'ioName', 'maxItems', 'ttlSeconds'],
    'debug_tap must require the complete explicit owner contract');
  assert.deepEqual(debugTapTool?.inputSchema?.properties?.ttlSeconds,
    { type: 'integer', minimum: 1, maximum: 2147483647 },
    'debug_tap ttlSeconds must be an explicit positive integer');
  assert.deepEqual(debugTapReadTool?.inputSchema?.properties?.drain,
    { type: 'integer', minimum: 0, maximum: 1000 },
    'debug_tap_read drain must be a bounded non-negative integer');
  const scenarioCapabilitiesTool = tools.find(tool => tool?.name === 'scenario_capabilities_get');
  assert.deepEqual(Object.keys(scenarioCapabilitiesTool?.inputSchema?.properties ?? {}).sort(),
    ['all', 'imageDigest', 'imageName'],
    'scenario capabilities must expose only exact owner selectors');
  assert.equal(Array.isArray(scenarioCapabilitiesTool?.outputSchema?.oneOf), true,
    'scenario capabilities must declare the owner array-or-object result union');
  const allCapabilities = await client.callTool('scenario_capabilities_get');
  assert.equal(Array.isArray(allCapabilities), true,
    'the characterised complete capability read must remain an array');
  assert.equal(allCapabilities.length > 0, true,
    'the live Scenario Manager must publish at least one worker capability');
  const exactImageName = allCapabilities.find(item =>
    typeof item?.image?.name === 'string' && item.image.name.trim().length > 0)?.image.name;
  assert.equal(typeof exactImageName, 'string',
    'a live worker capability must expose its exact owner image name');
  const exactCapability = await client.callTool('scenario_capabilities_get', { imageName: exactImageName });
  assert.equal(Array.isArray(exactCapability), false,
    'an exact image-name capability read must remain one owner object');
  assert.equal(exactCapability?.image?.name, exactImageName,
    'the exact capability selector must reach the selected owner record');
  const scenarioCapabilitiesProbe = {
    allShape: 'array',
    count: allCapabilities.length,
    exactShape: 'object',
    exactImageName,
  };
  const compactReviewTool = tools.find(tool => tool?.name === 'scenario_workflow_review_prepare');
  const compactTopics = compactReviewTool?.inputSchema?.properties?.answers?.items?.properties?.topic?.enum;
  assert.equal(Array.isArray(compactTopics), true, 'compact review must publish its canonical QA topics');
  assert.equal(compactTopics.length, 12, 'compact review must cover every canonical QA topic');
  const requirementSource = 'Public-ingress compact review acceptance requirement';
  const compactAnswers = compactTopics.map(topic => ({
    topic,
    disposition: 'USER_CONFIRMED_SOURCE',
    answer: `Explicit acceptance value for ${topic}`,
  }));
  const authoringSession = await client.callTool('agent_session_create');
  const compactWorkflow = await client.callTool('scenario_workflow_create', {
    agentSessionId: authoringSession.agentSessionId,
    expectedSessionRevision: authoringSession.revision,
  });
  const compactCandidate = {
    workflowId: compactWorkflow.workflowId,
    expectedRevision: compactWorkflow.revision,
    sourceName: 'playwright public-ingress acceptance requirement',
    sourceDigest: `sha256:${createHash('sha256').update(requirementSource).digest('hex')}`,
    answers: compactAnswers,
  };
  const preparedReview = await client.callTool('scenario_workflow_review_prepare', compactCandidate);
  assert.equal(preparedReview.captureMode, 'COMPACT_REVIEW');
  assert.equal(preparedReview.workflowRevision, 0,
    'preparing a compact review must not mutate the workflow');
  assert.match(preparedReview.message, /Review every requirement below/);
  assert.match(preparedReview.answerSetDigest, /^sha256:[0-9a-f]{64}$/);
  const submittedReview = await client.callTool('scenario_workflow_review_submit', {
    ...compactCandidate,
    reviewId: preparedReview.reviewId,
    requestedSchemaDigest: preparedReview.requestedSchemaDigest,
    answerSetDigest: preparedReview.answerSetDigest,
  });
  assert.equal(submittedReview.revision, 1,
    'one accepted compact review must record all topics in one workflow revision');
  assert.equal(submittedReview.state, 'REVIEW_REQUIRED');
  assert.equal(Object.keys(submittedReview.requirements ?? {}).length, compactTopics.length);
  await client.callTool('agent_session_close', {
    agentSessionId: authoringSession.agentSessionId,
    expectedRevision: 1,
  });
  const compactReviewProbe = {
    topicCount: compactTopics.length,
    prepareRevision: preparedReview.workflowRevision,
    submitRevision: submittedReview.revision,
    state: submittedReview.state,
  };
  const [swarms, scenarios, buzz, environmentHealth] = await Promise.all([
    client.callTool('swarm_list'),
    client.callTool('scenario_list'),
    client.callTool('debug_hive_journal', { limit: SIDEBAR_EVENT_LIMIT }),
    client.readResource('pockethive://environment/health'),
  ]);
  const disposableRuntimeProbe = await probeDisposableRuntime(client, scenarios);
  const buzzBytes = Buffer.byteLength(JSON.stringify(buzz));
  const eventPresentation = new EventPagePresentation();
  const presentedBuzz = eventPresentation.replace(buzz);
  const projectedBytes = Buffer.byteLength(JSON.stringify(presentedBuzz));
  const boundedBuzz = boundCompanionViewModel({ activeTab: 'Buzz', workspaceData: presentedBuzz }).workspaceData;
  assert.equal(projectedBytes <= VIEW_FIELD_BYTE_LIMIT, true,
    'live Buzz summaries must remain inside the general companion field limit');
  assert.notEqual(boundedBuzz?.error?.code, 'COMPANION_VIEW_DATA_TOO_LARGE',
    'normal live Buzz summaries must survive the companion presentation boundary');
  const rawBuzzJson = JSON.stringify(buzz);
  const presentedBuzzJson = JSON.stringify(presentedBuzz);
  assert.equal(presentedBuzzJson.includes('"logs"'), false,
    'live Buzz must not send runtime logs to the webview');
  if (rawBuzzJson.includes('runtime-log-snapshot')) {
    assert.equal(presentedBuzzJson.includes('runtime-log-snapshot'), true,
      'live Buzz must retain runtime-log-snapshot identity when the bounded owner page contains it');
  }
  if (presentedBuzz.items.length > 0) {
    assert.equal(eventPresentation.require(presentedBuzz.items[0].detailId), buzz.items[0],
      'the live summary must resolve to the exact current owner record');
  }
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
    ? {
        swarmId,
        data: new EventPagePresentation().replace(
          await client.callTool('debug_journal', { swarmId, limit: SIDEBAR_EVENT_LIMIT }),
        ),
      }
    : undefined;
  let runtimeDiagnosticsProbe = { outcome: 'no-swarm' };
  let debugTapProbe = { outcome: 'no-swarm' };
  if (swarmId) {
    const swarm = await client.callTool('swarm_get', { swarmId });
    const edge = swarm?.observation?.bindings?.work?.edges?.find(item =>
      typeof item?.to?.role === 'string' && typeof item?.to?.port === 'string');
    if (edge) {
      let tapId;
      try {
        const created = await client.callTool('debug_tap', {
          swarmId,
          role: edge.to.role,
          direction: 'IN',
          ioName: edge.to.port,
          maxItems: 3,
          ttlSeconds: 45,
        });
        tapId = created?.tapId;
        assert.equal(typeof tapId === 'string' && tapId.length > 0, true,
          'debug_tap must return an exact tap ID');
        assert.equal(created?.maxItems, 3, 'debug_tap must preserve the explicit item cap');
        assert.equal(created?.ttlSeconds, 45, 'debug_tap must preserve the explicit TTL');
        const metadata = await client.callTool('debug_tap_read', { tapId, drain: 0 });
        assert.equal(metadata?.tapId, tapId, 'metadata-only debug tap reads must preserve tap identity');
        assert.equal(Array.isArray(metadata?.samples), true,
          'metadata-only debug tap reads must preserve the owner sample shape');
        const drained = await client.callTool('debug_tap_read', { tapId, drain: 2 });
        assert.equal(drained?.tapId, tapId, 'bounded debug tap drains must preserve tap identity');
        const closed = await client.callTool('debug_tap_close', { tapId });
        const closedTapId = tapId;
        tapId = undefined;
        assert.equal(closed?.tapId, closedTapId, 'debug_tap_close must close the exact created tap');
        debugTapProbe = {
          outcome: 'created-read-drained-closed',
          swarmId,
          role: edge.to.role,
          direction: 'IN',
          ioName: edge.to.port,
          maxItems: created.maxItems,
          ttlSeconds: created.ttlSeconds,
        };
      } finally {
        if (tapId) await client.callTool('debug_tap_close', { tapId });
      }
    } else {
      debugTapProbe = { outcome: 'no-work-binding', swarmId };
    }
    const resources = await client.callTool('runtime_list_workers', { swarmId });
    const worker = Array.isArray(resources?.workers)
      ? resources.workers.find(item => typeof item?.runtimeId === 'string')
      : undefined;
    if (worker) {
      const target = { swarmId, runtimeId: worker.runtimeId };
      const [version, inspect] = await Promise.all([
        client.callTool('runtime_get_worker_version', target),
        client.callTool('runtime_inspect_worker', target),
      ]);
      assert.equal(typeof version?.reportedVersion === 'string' && version.reportedVersion.trim().length > 0, true,
        'runtime version must preserve the non-blank owner-provided runtime value');
      assert.equal(['pockethive.version', 'imageTag'].includes(version?.reportedVersionSource), true,
        'runtime version source must be the exact Orchestrator projection');
      assert.equal(inspect?.source?.owner, 'orchestrator',
        'runtime inspect must identify the established owner projection');
      assert.equal(inspect?.state && typeof inspect.state === 'object', true,
        'runtime inspect must preserve the owner state projection');
      assert.equal(Array.isArray(inspect?.mounts), true,
        'runtime inspect must preserve the owner mount projection');
      assert.equal(Array.isArray(inspect?.networks), true,
        'runtime inspect must preserve the owner network projection');
      const inspectKeys = Object.keys(inspect).sort();
      runtimeDiagnosticsProbe = {
        outcome: 'owner-results',
        swarmId,
        runtimeId: worker.runtimeId,
        version: {
          version: version?.reportedVersion ?? null,
          source: version?.reportedVersionSource ?? null,
          imageTag: version?.imageTag ?? null,
          imageDigest: version?.imageDigest ?? null,
        },
        inspectKeys,
      };
    } else {
      runtimeDiagnosticsProbe = { outcome: 'no-worker', swarmId };
    }
  }
  await context.close();
  return {
    client,
    evidence,
    swarms,
    scenarios,
    buzz: boundedBuzz,
    environmentHealth,
    journal,
    toolCount: tools.length,
    scenarioCapabilitiesProbe,
    disposableRuntimeProbe,
    journalRunsProbe,
    runtimeDiagnosticsProbe,
    debugTapProbe,
    compactReviewProbe,
    eventPageBoundaryProbe: {
      bytes: buzzBytes,
      projectedBytes,
      generalLimitBytes: VIEW_FIELD_BYTE_LIMIT,
      exceededGeneralLimit: buzzBytes > VIEW_FIELD_BYTE_LIMIT,
      outcome: 'master-detail',
    },
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

async function probeDisposableRuntime(client, scenarios) {
  const templateId = 'capability-controls-io-matrix';
  assert.equal(scenarios.some?.(scenario => scenario?.id === templateId && scenario?.defunct !== true), true,
    `live Scenario Manager must expose the ${templateId} acceptance template`);
  const swarmId = `mcp-acceptance-${randomBytes(6).toString('hex')}`;
  const key = action => `${swarmId}-${action}`;
  let created = false;
  try {
    await client.callTool('swarm_create', {
      swarmId,
      templateId,
      idempotencyKey: key('create'),
    });
    created = true;
    const ready = await pollUntil('disposable swarm readiness',
      () => client.callTool('swarm_wait_ready', { swarmId }),
      value => value?.ready === true);
    assert.equal(ready?.swarmStatus, 'READY');

    const beforePreview = await client.callTool('swarm_get', { swarmId });
    const observedWorker = beforePreview?.observation?.workers?.find(worker =>
      typeof worker?.role === 'string'
      && typeof worker?.instance === 'string'
      && worker?.config
      && typeof worker.config === 'object'
      && !Array.isArray(worker.config));
    assert.ok(observedWorker, 'a ready disposable swarm must expose an exact observed worker config');
    const preview = await client.callTool('component_config_preview', {
      swarmId,
      role: observedWorker.role,
      instanceId: observedWorker.instance,
      patch: { enabled: false },
    });
    assert.equal(preview?.sideEffect, 'NONE', 'component config preview must declare no side effect');
    assert.deepEqual(preview?.target, {
      swarmId,
      role: observedWorker.role,
      instance: observedWorker.instance,
    });
    assert.equal(preview?.effectiveConfig?.enabled, false,
      'component config preview must return the deterministic effective patch');
    const afterPreview = await client.callTool('swarm_get', { swarmId });
    const afterWorker = afterPreview?.observation?.workers?.find(worker =>
      worker?.role === observedWorker.role && worker?.instance === observedWorker.instance);
    assert.deepEqual(afterWorker?.config, observedWorker.config,
      'component config preview must not mutate the owner observation');

    const assessment = await client.callTool('runtime_assess_swarm', { swarmId });
    assert.equal(assessment?.assessmentContractVersion, '1');
    assert.equal(['CONSISTENT', 'DRIFTED', 'INCOMPLETE'].includes(assessment?.overall), true,
      'runtime assessment must return one canonical typed conclusion');
    assert.deepEqual(assessment?.checks?.map(check => check?.check), [
      'REGISTRY',
      'CONTROL_PLANE',
      'OWNERSHIP_MANIFEST',
      'RUNTIME_INVENTORY',
      'RABBIT_TOPOLOGY',
    ], 'runtime assessment must preserve the complete owner check order');
    for (const compatibilityTool of [
      'runtime_diff_swarm_runtime',
      'runtime_control_plane_status',
      'runtime_manifest_validate',
    ]) {
      const compatibility = await client.callTool(compatibilityTool, { swarmId });
      for (const field of [
        'assessmentContractVersion', 'overall', 'swarmId', 'runId', 'checks',
        'resources', 'manifest', 'rabbitTopology',
      ]) {
        assert.deepEqual(compatibility?.[field], assessment?.[field],
          `${compatibilityTool} must remain a projection of the canonical assessment`);
      }
      const { observedAt: assessmentObservedAt, ...assessmentSwarm } = assessment?.swarm ?? {};
      const { observedAt: compatibilityObservedAt, ...compatibilitySwarm } = compatibility?.swarm ?? {};
      assert.deepEqual(compatibilitySwarm, assessmentSwarm,
        `${compatibilityTool} must preserve the canonical swarm projection`);
      assert.equal(Number.isNaN(Date.parse(assessmentObservedAt)), false,
        'the canonical swarm projection must carry a valid observation timestamp');
      assert.equal(Number.isNaN(Date.parse(compatibilityObservedAt)), false,
        `${compatibilityTool} must carry its own valid observation timestamp`);
    }

    await client.callTool('swarm_start', { swarmId, idempotencyKey: key('start') });
    await pollUntil('disposable swarm running state',
      () => client.callTool('swarm_get', { swarmId }),
      value => value?.workloadState === 'RUNNING' && value?.observationStale === false);
    const safePlan = await client.callTool('runtime_cleanup_plan', {
      swarmId,
      includeRunning: false,
      includeRabbit: false,
    });
    assert.equal(safePlan?.blocked?.some(candidate =>
      candidate?.action === 'LIFECYCLE_REMOVE_SWARM'
      && candidate?.reason === 'active registered swarm requires includeRunning=true'), true,
    'active cleanup must block canonical swarm removal unless includeRunning is explicit');
    const runningPlan = await client.callTool('runtime_cleanup_plan', {
      swarmId,
      includeRunning: true,
      includeRabbit: false,
    });
    const lifecycleCandidate = runningPlan?.candidates?.find(candidate =>
      candidate?.action === 'LIFECYCLE_REMOVE_SWARM');
    assert.equal(lifecycleCandidate?.running, true,
      'an explicitly included active swarm must remain marked running');
    assert.equal(lifecycleCandidate?.highRisk, true,
      'an explicitly included active swarm must remain marked high risk');

    await client.callTool('swarm_stop', { swarmId, idempotencyKey: key('stop') });
    await waitForStoppedIdleSwarm(client, swarmId, 'disposable swarm stopped state');
    await client.callTool('swarm_remove', { swarmId, idempotencyKey: key('remove') });
    await pollUntil('disposable swarm removal',
      () => client.callTool('swarm_list'),
      value => Array.isArray(value) && !value.some(swarm => swarm?.id === swarmId));
    created = false;
    return {
      swarmId,
      templateId,
      readiness: 'READY',
      configPreview: 'NO_MUTATION',
      assessment: assessment.overall,
      assessmentChecks: assessment.checks.map(check => ({
        check: check.check,
        state: check.state,
        differenceKinds: Array.isArray(check.differences)
          ? check.differences.map(difference => difference?.kind)
          : [],
      })),
      compatibilityTools: 3,
      includeRunningFalse: 'BLOCKED',
      includeRunningTrue: 'HIGH_RISK',
      removed: true,
    };
  } catch (error) {
    if (created) await removeDisposableSwarm(client, swarmId, key).catch(() => undefined);
    throw error;
  }
}

async function removeDisposableSwarm(client, swarmId, key) {
  const swarm = await client.callTool('swarm_get', { swarmId }).catch(() => undefined);
  if (!swarm) return;
  if (swarm.workloadState !== 'STOPPED') {
    await client.callTool('swarm_stop', { swarmId, idempotencyKey: key('cleanup-stop') }).catch(() => undefined);
  }
  await waitForStoppedIdleSwarm(client, swarmId, 'failed-probe swarm stopped state');
  await client.callTool('swarm_remove', { swarmId, idempotencyKey: key('cleanup-remove') });
}

async function waitForStoppedIdleSwarm(client, swarmId, label) {
  return pollUntil(label,
    () => client.callTool('swarm_get', { swarmId }),
    value => value?.workloadState === 'STOPPED'
      && value?.observationStale === false
      && value?.activeOperation == null);
}

async function pollUntil(label, read, accept, timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    last = await read();
    if (accept(last)) return last;
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  throw new Error(`${label} timed out; last=${JSON.stringify(last)?.slice(0, 1000)}`);
}

async function inspectAuthPage(page, state, screenshotName) {
  await page.waitForLoadState('networkidle');
  const assets = await page.evaluate(() => {
    const logo = document.querySelector('.auth-brand__logo');
    const card = document.querySelector('.auth-card');
    return {
      logoLoaded: logo instanceof HTMLImageElement && logo.complete && logo.naturalWidth > 0,
      logoSource: logo instanceof HTMLImageElement ? logo.getAttribute('src') : null,
      cssMarkPresent: document.querySelector('.auth-brand__mark') !== null,
      themedCard: card instanceof HTMLElement && getComputedStyle(card).backgroundColor !== 'rgba(0, 0, 0, 0)',
      overflow: Math.max(document.documentElement.scrollWidth, document.body.scrollWidth)
        - document.documentElement.clientWidth,
    };
  });
  if (!assets.logoLoaded) findings.push({ state, kind: 'brand-asset', detail: 'PocketHive logo did not load' });
  if (assets.cssMarkPresent) findings.push({ state, kind: 'brand-asset', detail: 'CSS-drawn substitute logo remains' });
  if (state.startsWith('auth-callback') && !assets.logoSource?.startsWith('data:image/svg+xml;base64,')) {
    findings.push({ state, kind: 'brand-asset', detail: 'Callback did not use the generated canonical data URI' });
  }
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

async function captureCallbackOutcome(browser, state, query, screenshotName) {
  const context = await browser.newContext({ viewport: { width: 520, height: 760 }, colorScheme: 'dark' });
  const page = await context.newPage();
  let listenerReady;
  let redirectUri;
  const ready = new Promise(resolve => { listenerReady = resolve; });
  const callbackResult = new LoopbackBrowserAuthorization(async () => true).authorize(runtimeRedirectUri => {
    redirectUri = runtimeRedirectUri;
    listenerReady();
    return 'https://issuer.invalid/oauth/authorize';
  }, new AbortController().signal);
  await ready;
  const callbackUrl = `${redirectUri}${query}`;
  await page.goto(callbackUrl, { waitUntil: 'domcontentloaded' });
  assert.equal((await callbackResult).callback.toString(), callbackUrl);
  await inspectAuthPage(page, state, screenshotName);
  await context.close();
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
    ['/resources/codicon.css', ['text/css', path.join(root, 'resources', 'codicon.css')]],
    ['/resources/codicon.ttf?9aab6318a6710999273bab9c78a9fd71', ['font/ttf', path.join(root, 'resources', 'codicon.ttf')]],
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
        <link rel="stylesheet" href="/resources/codicon.css">
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

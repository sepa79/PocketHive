import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(new URL('../../../vscode-pockethive/package.json', import.meta.url));
const { chromium } = require('playwright');
const ingress = new URL(process.env.PH_UI_TEST_URL).origin;
const tlsOnlyMapping = process.env.PH_TLS_TEST_MAPPING_ID;
assert.ok(tlsOnlyMapping, 'Set PH_TLS_TEST_MAPPING_ID to a seed mapping present only on the TLS instance');

const browser = await chromium.launch();
try {
    const page = await browser.newPage();
    const tlsDashboard = ingress + '/tcp-mock-tls/';
    const mappingsUrl = tlsDashboard + 'api/mappings';

    assert.equal((await page.request.get(mappingsUrl)).status(), 401);
    const module = page.waitForResponse(response =>
        response.url() === ingress + '/auth-session.js' && response.ok());
    await page.goto(ingress + '/tcp-mock-tls');
    assert.equal(page.url(), tlsDashboard);
    await module;

    await page.getByRole('link', { name: 'Sign in to PocketHive' }).click();
    await page.waitForURL(ingress + '/login');
    const confirmationTab = page.waitForEvent('popup');
    await page.getByRole('button', { name: 'Sign in with PocketHive', exact: true }).click();
    const confirmation = await confirmationTab;
    await confirmation.getByLabel('Configured username').fill('local-admin');
    await confirmation.getByRole('button', { name: 'Sign in', exact: true }).click();
    await confirmation.getByRole('button', { name: 'Confirm sign in', exact: true }).click();
    await confirmation.getByRole('heading', { name: 'Confirmation complete', exact: true }).waitFor();
    await page.getByRole('button', { name: 'Sign out', exact: true }).waitFor();

    const user = page.waitForResponse(response =>
        response.url() === tlsDashboard + 'api/auth/me' && response.ok());
    const mappings = page.waitForResponse(response => response.url() === mappingsUrl && response.ok());
    await page.goto(tlsDashboard);
    assert.equal((await (await user).json()).provider, 'POCKETHIVE');
    await page.locator('#loginModal').waitFor({ state: 'hidden' });
    const skipTour = page.getByRole('button', { name: 'Skip', exact: true });
    if (await skipTour.isVisible()) await skipTour.click();
    await page.locator('[data-tab="mappings"]').click();
    const response = await mappings;
    assert.ok((await response.json()).some(mapping => mapping.id === tlsOnlyMapping));
    console.log('TCP Mock TLS: same-origin login, unauthenticated denial and protected mapping read verified');
} finally {
    await browser.close();
}

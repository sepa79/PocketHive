const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function fixture(file, className, extra = {}) {
    const storage = new Map([['current-workspace', 'keep-selection']]);
    const calls = [];
    const context = vm.createContext({
        sessionStorage: { getItem: key => storage.get(key) ?? null,
            setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) },
        HttpClient: { endpoint: path => '/tcp-mock' + path },
        btoa: value => Buffer.from(value).toString('base64'),
        fetch: async (url, options) => { calls.push({ url, options }); return { status: 200, ok: true,
            json: async () => ({ provider: 'NATIVE', subject: 'fixture', displayName: 'Fixture' }) }; },
        ...extra,
    });
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../src/main/resources/static', file), 'utf8') + `\nglobalThis.Type = ${className};`, context);
    return { context, storage, calls };
}

test('native login validates before storing credentials and never touches PocketHive session data', async () => {
    const { context, storage, calls } = fixture('native-auth-session.js', 'NativeAuthSession');
    const session = new context.Type();
    assert.equal(await session.init(), false);
    assert.equal(calls.length, 0);
    assert.equal(await session.login('fixture', 'password'), true);
    assert.equal(calls[0].url, '/tcp-mock/api/auth/me');
    assert.equal(session.getCurrentUser().subject, 'fixture');
    context.fetch = async () => ({ status: 401, ok: false });
    assert.equal(await session.init(), false);
    assert.equal(storage.has('auth-credentials'), false);
    assert.equal(storage.get('current-workspace'), 'keep-selection');
});

test('PocketHive session resolves identity and clears only expired sessions, with no native fallback', async () => {
    let cleared = 0;
    const shared = { readStoredAuthSession: () => ({ accessToken: 'fixture-token' }),
        readStoredAccessToken: () => 'fixture-token', clearAuthSession: () => { cleared++; } };
    const { context, calls } = fixture('pockethive-auth-session.js', 'PocketHiveAuthSession');
    const session = new context.Type(shared);
    assert.equal(await session.init(), true);
    assert.equal(calls[0].options.headers.Authorization, 'Bearer fixture-token');
    context.fetch = async () => ({ status: 503, ok: false });
    await assert.rejects(session.init(), /unavailable/);
    assert.equal(cleared, 0);
    context.fetch = async () => ({ status: 401, ok: false });
    assert.equal(await session.init(), false);
    assert.equal(cleared, 1);
});

test('missing shared session does not request a native login or resolve credentials', async () => {
    const { context, calls } = fixture('pockethive-auth-session.js', 'PocketHiveAuthSession');
    const session = new context.Type({ readStoredAuthSession: () => null });
    assert.equal(await session.init(), false);
    assert.equal(calls.length, 0);
});

test('unknown configured provider fails without invoking a native provider', async () => {
    const { context } = fixture('auth.js', 'AuthModule', {
        fetch: async () => ({ ok: true, json: async () => ({ provider: 'UNKNOWN' }) }),
        NativeAuthSession: class { constructor() { throw new Error('Must not instantiate'); } },
    });
    await assert.rejects(new context.Type().init(), /Unsupported authentication provider/);
});

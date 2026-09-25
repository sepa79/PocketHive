const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const primary = { id: 'server-default', name: 'Default', shared: false, defaultWorkspace: true, deletable: false };
const secondary = { id: 'second', name: 'Second', shared: false, defaultWorkspace: false, deletable: true };
const ok = value => ({ ok: true, status: 200, json: async () => structuredClone(value) });
function fixture() {
    const storage = new Map();
    const errors = [];
    const context = vm.createContext({ sessionStorage: {
        getItem: key => storage.get(key) ?? null,
        setItem: (key, value) => storage.set(key, value),
        removeItem: key => storage.delete(key),
    }, ErrorHandler: { handle: (error, message) => errors.push(message) } });
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../src/main/resources/static/workspace.js'), 'utf8') + '\nglobalThis.Workspace = WorkspaceModule;', context);
    const http = { get: async () => ok([primary, secondary]) };
    return { module: new context.Workspace(http), http, storage, errors };
}

test('stale selection uses server default, never an ID convention or first entry', async () => {
    const { module, http, storage } = fixture();
    storage.set('current-workspace', 'removed');
    http.get = async () => ok([secondary, primary]);
    assert.equal(await module.init(), true);
    assert.equal(module.getCurrentWorkspace().id, primary.id);
    assert.equal(storage.get('current-workspace'), primary.id);
});

test('failed and malformed initial loads never fabricate workspaces', async () => {
    const { module, http, errors } = fixture();
    for (const response of [{ ok: false, status: 503 }, ok([secondary])]) {
        http.get = async () => response;
        assert.equal(await module.init(), false);
        assert.equal(module.getAll().length, 0);
        assert.equal(module.getCurrentWorkspace(), null);
    }
    assert.equal(errors.length, 2);
});

for (const failure of ['status', 'network']) {
    test(`failed create, rename, delete and reload preserve catalogue and selection: ${failure}`, async () => {
        const { module, http, storage, errors } = fixture();
        await module.init();
        await module.switch(secondary.id);
        const before = JSON.stringify(module.getAll());
        const fail = async () => {
            if (failure === 'network') throw new Error('Disconnected');
            return { ok: false, status: 409 };
        };
        http.post = http.put = http.delete = http.get = fail;
        assert.equal(await module.create('New'), null);
        assert.equal(await module.rename(secondary.id, 'Changed'), false);
        assert.equal(await module.delete(secondary.id), false);
        assert.equal(await module.loadWorkspaces(), false);
        assert.equal(JSON.stringify(module.getAll()), before);
        assert.equal(module.getCurrentWorkspace().name, 'Second');
        assert.equal(storage.get('current-workspace'), secondary.id);
        assert.equal(errors.length, 4);
    });
}

test('rename commits returned server metadata and active deletion selects server default', async () => {
    const { module, http, storage } = fixture();
    await module.init();
    await module.switch(secondary.id);
    http.put = async (url, request) => {
        assert.equal(url, '/api/workspaces/second');
        assert.equal(JSON.stringify(request), JSON.stringify({ name: ' New ', shared: false }));
        return ok({ ...secondary, name: 'New' });
    };
    assert.equal(await module.rename(secondary.id, ' New '), true);
    assert.equal(module.getCurrentWorkspace().name, 'New');
    http.delete = async () => ({ ok: true, status: 204 });
    assert.equal(await module.delete(secondary.id), true);
    assert.equal(module.getCurrentWorkspace().id, primary.id);
    assert.equal(storage.get('current-workspace'), primary.id);
});

test('default refusal is decided by the server and reported without local deletion', async () => {
    const { module, http, errors } = fixture();
    await module.init();
    let requests = 0;
    http.delete = async () => { requests++; return { ok: false, status: 409 }; };
    assert.equal(await module.delete(primary.id), false);
    assert.equal(requests, 1);
    assert.equal(module.getAll().length, 2);
    assert.equal(module.getCurrentWorkspace().id, primary.id);
    assert.equal(errors.length, 1);
});

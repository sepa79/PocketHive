import assert from 'node:assert/strict';
import test, { mock } from 'node:test';
import dns from 'node:dns/promises';
import { getEventListeners } from 'node:events';

import { PocketHiveEndpointValidator } from '../connection/endpointValidator';
import { createConnectionProfile } from '../connection/profile';
import { ConnectionContractError } from '../connection/contracts';

const remoteProfile = createConnectionProfile({
  id: 'remote', displayName: 'Remote', mcpUrl: 'https://nft-lab.example/mcp',
  endpointSecurityMode: 'REMOTE_HTTPS', secretKey: 'secret',
});

const validMetadata = {
  resource: remoteProfile.mcpUrl,
  authorization_servers: ['https://nft-lab.example/auth-service'],
};

test('cancels an in-flight fetch and forwards cancellation to the transport', async () => {
  let transportSignal!: AbortSignal;
  const validator = new PocketHiveEndpointValidator(async (_url, init) => {
    transportSignal = init!.signal!;
    return new Promise<Response>(() => {});
  });
  const controller = new AbortController();
  const pending = validator.validate(remoteProfile, controller.signal);
  controller.abort(new Error('user cancelled'));
  await assert.rejects(pending, /user cancelled/);
  assert.equal(transportSignal.aborted, true);
  assert.equal(getEventListeners(controller.signal, 'abort').length, 0);
});

for (const failure of [false, true]) {
  test(`completed discovery releases cancellation and deadline after ${failure ? 'failure' : 'success'}`, async () => {
    let transportSignal!: AbortSignal;
    const controller = new AbortController();
    const validator = new PocketHiveEndpointValidator(async (_url, init) => {
      transportSignal = init!.signal!;
      return new Response(JSON.stringify(validMetadata), {
        status: failure ? 503 : 200, headers: { 'Content-Type': 'application/json' },
      });
    }, undefined, 20);
    const pending = validator.validate(remoteProfile, controller.signal);
    if (failure) await assert.rejects(pending, /MCP_RESOURCE_METADATA_UNAVAILABLE/);
    else await pending;
    assert.equal(getEventListeners(controller.signal, 'abort').length, 0);
    assert.equal(getEventListeners(transportSignal, 'abort').length, 0);
    controller.abort(new Error('late cancellation'));
    await new Promise(resolve => setTimeout(resolve, 40));
    assert.equal(transportSignal.aborted, false, 'Completed transport must not receive a late abort');
  });
}

for (const [name, body, status, contentType, code, message] of [
  ['HTTP failure', JSON.stringify(validMetadata), 404, 'application/json', 'MCP_RESOURCE_METADATA_UNAVAILABLE', 'HTTP 404'],
  ['HTML', JSON.stringify(validMetadata), 200, 'text/html', 'MCP_RESOURCE_METADATA_INVALID', 'response must be application/json'],
  ['missing content type', JSON.stringify(validMetadata), 200, null, 'MCP_RESOURCE_METADATA_INVALID', 'response must be application/json'],
  ['invalid JSON', '{', 200, 'application/json', 'MCP_RESOURCE_METADATA_INVALID', ''],
  ...['null', '[]', '42', '"text"', 'false'].map(body =>
    [`non-object ${body}`, body, 200, 'application/json', 'MCP_RESOURCE_METADATA_INVALID', 'not an object'] as const),
] as const) {
  test(`rejects ${name} with the metadata contract error`, async () => {
    const validator = new PocketHiveEndpointValidator(async () => {
      const response = new Response(body, { status });
      response.headers.delete('Content-Type');
      if (contentType) response.headers.set('Content-Type', contentType);
      return response;
    });
    await assert.rejects(validator.validate(remoteProfile, new AbortController().signal), error => {
      assert.ok(error instanceof ConnectionContractError);
      assert.equal(error.code, code);
      assert.ok(error.message.includes(message));
      return true;
    });
  });
}

for (const authorizationServers of [undefined, null, [], [42], ['https://a.example', 'https://b.example']]) {
  test(`rejects invalid issuer list ${JSON.stringify(authorizationServers)}`, async () => {
    const validator = new PocketHiveEndpointValidator(async () => new Response(JSON.stringify({
      ...validMetadata, authorization_servers: authorizationServers,
    }), { headers: { 'Content-Type': 'application/json' } }));
    await assert.rejects(validator.validate(remoteProfile, new AbortController().signal), {
      code: 'MCP_AUTHORIZATION_SERVER_INVALID',
      message: 'MCP_AUTHORIZATION_SERVER_INVALID: MCP_AUTHORIZATION_SERVER_INVALID: exactly one authorization server is required',
    });
  });
}

for (const length of [65_536, 65_537]) {
  test(`enforces the metadata size boundary at ${length} characters`, async () => {
    const metadata = JSON.stringify(validMetadata).padEnd(length, ' ');
    const validator = new PocketHiveEndpointValidator(async (_url, init) => {
      assert.equal(init?.method, 'GET');
      assert.deepEqual(init?.headers, { Accept: 'application/json' });
      assert.equal(init?.redirect, 'error');
      return new Response(metadata, { headers: { 'Content-Type': 'application/json; charset=utf-8' } });
    });
    const pending = validator.validate(remoteProfile, new AbortController().signal);
    if (length === 65_536) assert.equal((await pending).authorizationServer, validMetadata.authorization_servers[0]);
    else await assert.rejects(pending, {
      code: 'MCP_RESOURCE_METADATA_INVALID',
      message: 'MCP_RESOURCE_METADATA_INVALID: MCP_RESOURCE_METADATA_INVALID: response exceeded the size limit',
    });
  });
}

for (const addresses of [[], ['127.0.0.1', '192.0.2.4'], ['::1', '::ffff:192.0.2.4']]) {
  test(`rejects non-loopback resolution ${JSON.stringify(addresses)} before network effects`, async () => {
    let requests = 0;
    const profile = createConnectionProfile({ ...remoteProfile, mcpUrl: 'http://localhost:8088/mcp',
      endpointSecurityMode: 'LOCAL_LOOPBACK_HTTP' });
    const validator = new PocketHiveEndpointValidator(async () => {
      requests++;
      throw new Error('must not fetch');
    }, async () => addresses);
    await assert.rejects(validator.validate(profile, new AbortController().signal), {
      code: 'MCP_ENDPOINT_LOOPBACK_RESOLUTION_FAILED',
      message: 'MCP_ENDPOINT_LOOPBACK_RESOLUTION_FAILED: MCP_ENDPOINT_LOOPBACK_RESOLUTION_FAILED: every resolved address must remain loopback',
    });
    assert.equal(requests, 0);
  });
}

test('accepts all resolved loopback address forms and normalizes the issuer trailing slash', async () => {
  const profile = createConnectionProfile({ ...remoteProfile, mcpUrl: 'http://localhost:8088/mcp',
    endpointSecurityMode: 'LOCAL_LOOPBACK_HTTP' });
  const validator = new PocketHiveEndpointValidator(async () => new Response(JSON.stringify({
    resource: profile.mcpUrl, authorization_servers: ['http://localhost:8088/auth-service/'],
  }), { headers: { 'Content-Type': 'application/json' } }), async () => ['127.0.0.1', '127.2.3.4', '::1', '::FFFF:127.0.0.1']);
  assert.equal((await validator.validate(profile, new AbortController().signal)).authorizationServer,
    'http://localhost:8088/auth-service');
});

for (const blockedPhase of ['headers', 'body', 'dns'] as const) {
  test(`the discovery deadline covers stalled ${blockedPhase}`, async () => {
    let transportSignal: AbortSignal | undefined;
    let fetches = 0;
    let releaseDns!: (addresses: string[]) => void;
    const dns = new Promise<string[]>(resolve => { releaseDns = resolve; });
    const validator = new PocketHiveEndpointValidator(async (_url, init) => {
      fetches++;
      transportSignal = init!.signal!;
      if (blockedPhase === 'headers') return new Promise<Response>(() => {});
      return new Response(new ReadableStream({ start() {} }), {
        headers: { 'Content-Type': 'application/json' },
      });
    }, async () => dns, 20);
    const profile = blockedPhase === 'dns' ? createConnectionProfile({
      id: 'local', displayName: 'Local', mcpUrl: 'http://localhost:8088/mcp',
      endpointSecurityMode: 'LOCAL_LOOPBACK_HTTP', secretKey: 'secret',
    }) : remoteProfile;
    await assert.rejects(validator.validate(profile, new AbortController().signal), {
      code: 'MCP_ENDPOINT_DISCOVERY_TIMEOUT',
      message: 'MCP_ENDPOINT_DISCOVERY_TIMEOUT: Endpoint discovery exceeded its time limit',
    });
    if (blockedPhase === 'dns') {
      releaseDns(['127.0.0.1']);
      await new Promise(resolve => setImmediate(resolve));
      assert.equal(fetches, 0, 'Late DNS must not initiate traffic');
    } else assert.equal(transportSignal?.aborted, true);
  });
}

test('an already cancelled discovery performs no lookup or fetch', async () => {
  const validator = new PocketHiveEndpointValidator(async () => { throw new Error('must not fetch'); });
  const controller = new AbortController();
  controller.abort(new Error('already cancelled'));
  await assert.rejects(validator.validate(remoteProfile, controller.signal), /already cancelled/);
});

test('discovery rejects an invalid profile before any transport call', async () => {
  let requests = 0;
  const validator = new PocketHiveEndpointValidator(async () => {
    requests++;
    throw new Error('must not fetch');
  });
  await assert.rejects(validator.validate({ ...remoteProfile, mcpUrl: 'http://nft-lab.example/mcp' },
    new AbortController().signal), { code: 'MCP_ENDPOINT_HTTPS_REQUIRED' });
  assert.equal(requests, 0);
});

test('the production resolver checks the entered hostname rather than substituting a loopback literal', async () => {
  const lookup = mock.method(dns, 'lookup', async (hostname: string) => [{
    address: hostname === 'localhost' ? '192.0.2.4' : '::1', family: hostname === 'localhost' ? 4 : 6,
  }]);
  try {
    let requests = 0;
    const profile = createConnectionProfile({ ...remoteProfile, mcpUrl: 'http://localhost:8088/mcp',
      endpointSecurityMode: 'LOCAL_LOOPBACK_HTTP' });
    const validator = new PocketHiveEndpointValidator(async () => {
      requests++;
      return new Response(JSON.stringify({ resource: profile.mcpUrl, authorization_servers: ['http://localhost:8088/auth-service'] }),
        { headers: { 'Content-Type': 'application/json' } });
    });
    await assert.rejects(validator.validate(profile, new AbortController().signal), {
      code: 'MCP_ENDPOINT_LOOPBACK_RESOLUTION_FAILED',
    });
    assert.equal(requests, 0);
  } finally {
    lookup.mock.restore();
  }
});

test('cancelled response headers never lead to a body read', async () => {
  let release!: (response: Response) => void;
  let reads = 0;
  const validator = new PocketHiveEndpointValidator(async () => new Promise(resolve => { release = resolve; }));
  const controller = new AbortController();
  const pending = validator.validate(remoteProfile, controller.signal);
  controller.abort(new Error('cancel before headers'));
  await assert.rejects(pending, /cancel before headers/);
  const response = new Response(JSON.stringify(validMetadata), { headers: { 'Content-Type': 'application/json' } });
  response.text = async () => { reads++; return JSON.stringify(validMetadata); };
  release(response);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(reads, 0);
});

test('accepts only metadata whose resource exactly matches the entered MCP URL', async () => {
  const urls: string[] = [];
  const validator = new PocketHiveEndpointValidator(async url => {
    urls.push(String(url));
    return new Response(JSON.stringify({
      resource: 'https://nft-lab.example/mcp',
      authorization_servers: ['https://nft-lab.example/auth-service'],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });
  }, async () => ['203.0.113.4']);
  const profile = createConnectionProfile({
    id: 'nft', displayName: 'NFT Lab', mcpUrl: 'https://nft-lab.example/mcp',
    endpointSecurityMode: 'REMOTE_HTTPS', secretKey: 'secret',
  });

  const endpoint = await validator.validate(profile, new AbortController().signal);

  assert.deepEqual(endpoint, {
    mcpUrl: 'https://nft-lab.example/mcp',
    resourceMetadataUrl: 'https://nft-lab.example/.well-known/oauth-protected-resource',
    authorizationServer: 'https://nft-lab.example/auth-service',
  });
  assert.deepEqual(urls, ['https://nft-lab.example/.well-known/oauth-protected-resource']);
});

test('rejects resource mismatch and never tries a different metadata location', async () => {
  let calls = 0;
  const validator = new PocketHiveEndpointValidator(async () => {
    calls += 1;
    return new Response(JSON.stringify({
      resource: 'https://nft-lab.example/another-mcp',
      authorization_servers: ['https://nft-lab.example/auth-service'],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });
  }, async () => ['203.0.113.4']);
  const profile = createConnectionProfile({
    id: 'nft', displayName: 'NFT Lab', mcpUrl: 'https://nft-lab.example/mcp',
    endpointSecurityMode: 'REMOTE_HTTPS', secretKey: 'secret',
  });

  await assert.rejects(validator.validate(profile, new AbortController().signal), {
    code: 'MCP_RESOURCE_METADATA_MISMATCH',
    message: 'MCP_RESOURCE_METADATA_MISMATCH: MCP_RESOURCE_METADATA_MISMATCH: resource must equal the entered MCP URL',
  });
  assert.equal(calls, 1);
});

test('rechecks that a local hostname resolves only to loopback before HTTP', async () => {
  let fetched = false;
  const validator = new PocketHiveEndpointValidator(async () => {
    fetched = true;
    throw new Error('must not fetch');
  }, async () => ['127.0.0.1', '192.168.1.2']);
  const profile = createConnectionProfile({
    id: 'local', displayName: 'Local', mcpUrl: 'http://localhost:8080/mcp',
    endpointSecurityMode: 'LOCAL_LOOPBACK_HTTP', secretKey: 'secret',
  });

  await assert.rejects(validator.validate(profile, new AbortController().signal), /MCP_ENDPOINT_LOOPBACK_RESOLUTION_FAILED/);
  assert.equal(fetched, false);
});

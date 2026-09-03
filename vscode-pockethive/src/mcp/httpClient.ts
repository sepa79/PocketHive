import {
  ConnectionContractError,
  ConnectionEvidence,
  EXPECTED_MCP_SERVER_NAME,
  MCP_PROTOCOL_REVISION,
} from '../connection/contracts';

interface JsonRpcResponse {
  readonly jsonrpc: '2.0';
  readonly id?: number;
  readonly result?: unknown;
  readonly error?: { readonly code?: number; readonly message?: string; readonly data?: unknown };
}

interface InitializeResult {
  readonly protocolVersion: string;
  readonly serverInfo?: { readonly name?: string; readonly version?: string };
  readonly capabilities?: { readonly tools?: unknown; readonly resources?: unknown };
}

interface ResourceResult {
  readonly contents?: Array<{ readonly uri?: string; readonly text?: string }>;
}

const MCP_CLIENT_NAME = 'pockethive-vscode';

export class McpHttpClient {
  private endpoint?: string;
  private accessToken?: string;
  private sessionId?: string;
  private nextId = 1;

  constructor(
    private readonly clientVersion: string,
    private readonly fetcher: typeof fetch = fetch,
    private readonly now: () => Date = () => new Date(),
  ) {}

  async connect(endpoint: string, accessToken: string, signal?: AbortSignal): Promise<ConnectionEvidence> {
    this.endpoint = endpoint;
    this.accessToken = accessToken;
    const initialize = await this.request<InitializeResult>('initialize', {
      protocolVersion: MCP_PROTOCOL_REVISION,
      capabilities: {},
      clientInfo: { name: MCP_CLIENT_NAME, version: this.clientVersion },
    }, false, signal);
    if (initialize.protocolVersion !== MCP_PROTOCOL_REVISION) {
      throw new ConnectionContractError(
        'MCP_PROTOCOL_REVISION_MISMATCH',
        `MCP_PROTOCOL_REVISION_MISMATCH: ${initialize.protocolVersion}`,
      );
    }
    const serverInfo = initialize.serverInfo;
    if (serverInfo?.name !== EXPECTED_MCP_SERVER_NAME) {
      throw new ConnectionContractError(
        'MCP_SERVER_IDENTITY_MISMATCH',
        `MCP_SERVER_IDENTITY_MISMATCH: ${String(serverInfo?.name)}`,
      );
    }
    if (!initialize.capabilities?.tools || !initialize.capabilities.resources) {
      throw new ConnectionContractError(
        'MCP_CAPABILITY_MISSING',
        'MCP_CAPABILITY_MISSING: tools and resources are required',
      );
    }
    await this.notification('notifications/initialized', signal);
    const capabilities = await this.readResource(
      'pockethive://capabilities/current', signal, 'MCP_CAPABILITY_RESOURCE_INVALID',
      'MCP_CAPABILITY_RESOURCE_INVALID: missing current capabilities');
    const catalogueDigest = requiredString(capabilities, 'catalogueDigest', 'MCP_CAPABILITY_RESOURCE_INVALID');
    const principalLabel = requiredString(capabilities, 'principalLabel', 'MCP_CAPABILITY_RESOURCE_INVALID');
    return {
      serverName: EXPECTED_MCP_SERVER_NAME,
      serverVersion: requiredString(serverInfo, 'version', 'MCP_SERVER_IDENTITY_MISMATCH'),
      principalLabel,
      capabilityFingerprint: catalogueDigest,
      observedAt: this.now().toISOString(),
    };
  }

  async listTools(): Promise<unknown> {
    return this.request('tools/list', {});
  }

  async callTool(name: string, args: Record<string, unknown> = {}): Promise<unknown> {
    const result = await this.request<Record<string, unknown>>('tools/call', { name, arguments: args });
    if (result.isError === true) {
      throw new ConnectionContractError('MCP_TOOL_FAILED', JSON.stringify(result.structuredContent ?? result.content));
    }
    return successfulToolOwnerResult(result);
  }

  async readResource(
    uri: string,
    signal?: AbortSignal,
    errorCode = 'MCP_RESOURCE_INVALID',
    missingMessage = `${errorCode}: missing ${uri}`,
  ): Promise<Record<string, unknown>> {
    const resource = await this.request<ResourceResult>('resources/read', { uri }, true, signal);
    const content = resource.contents?.find(item => item.uri === uri)?.text;
    if (!content) {
      throw new ConnectionContractError(errorCode, missingMessage);
    }
    return parseObject(content, errorCode);
  }

  async uploadArchive(uploadUrl: string, archive: Uint8Array, signal?: AbortSignal): Promise<unknown> {
    this.requireConnection(true);
    const target = this.requireUploadTarget(uploadUrl);
    const requestBody = new ArrayBuffer(archive.byteLength);
    new Uint8Array(requestBody).set(archive);
    const response = await this.fetcher(target, {
      method: 'PUT',
      headers: {
        Authorization: `Bearer ${this.accessToken}`,
        'Content-Type': 'application/zip',
        'Content-Length': String(archive.byteLength),
        Origin: new URL(this.endpoint!).origin,
        'MCP-Protocol-Version': MCP_PROTOCOL_REVISION,
      },
      body: requestBody,
      signal,
    });
    const responseBody = await response.text();
    if (responseBody.length > 100_000) {
      throw new ConnectionContractError('MCP_UPLOAD_RESPONSE_INVALID', 'MCP upload response was too large');
    }
    let payload: Record<string, unknown>;
    try {
      payload = parseObject(responseBody, 'MCP_UPLOAD_RESPONSE_INVALID');
    } catch (error) {
      if (!response.ok) {
        throw new ConnectionContractError('MCP_UPLOAD_FAILED', `MCP upload returned ${response.status}`);
      }
      throw error;
    }
    if (!response.ok) {
      const code = typeof payload.code === 'string' ? payload.code : 'MCP_UPLOAD_FAILED';
      throw new ConnectionContractError(code, `${code}: HTTP ${response.status}`, payload);
    }
    return payload;
  }

  async close(): Promise<void> {
    if (!this.sessionId) return;
    const response = await this.fetcher(this.endpoint!, {
      method: 'DELETE',
      headers: this.headers(true),
    });
    if (!response.ok && response.status !== 404) {
      throw new ConnectionContractError('MCP_SESSION_CLOSE_FAILED', `MCP session close returned ${response.status}`);
    }
    this.endpoint = undefined;
    this.accessToken = undefined;
    this.sessionId = undefined;
  }

  private async request<T>(
    method: string,
    params: Record<string, unknown>,
    requireSession = true,
    signal?: AbortSignal,
  ): Promise<T> {
    const id = this.nextId++;
    const response = await this.fetcher(this.endpoint!, {
      method: 'POST',
      headers: this.headers(requireSession),
      body: JSON.stringify({ jsonrpc: '2.0', id, method, params }),
      signal,
    });
    if (!response.ok) {
      throw new ConnectionContractError('MCP_HTTP_FAILED', `MCP HTTP request returned ${response.status}`);
    }
    if (!requireSession) {
      const created = response.headers.get('Mcp-Session-Id');
      if (!created) {
        throw new ConnectionContractError('MCP_SESSION_ID_MISSING', 'MCP initialize did not return a session ID');
      }
      this.sessionId = created;
    }
    const payload = await responsePayload(response);
    if (payload.error) {
      throw new ConnectionContractError(
        'MCP_JSON_RPC_FAILED',
        `MCP JSON-RPC error: ${payload.error.message ?? String(payload.error.code)}`,
      );
    }
    if (payload.id !== id || payload.result === undefined) {
      throw new ConnectionContractError('MCP_JSON_RPC_INVALID', 'MCP JSON-RPC response did not match the request');
    }
    return payload.result as T;
  }

  private async notification(method: string, signal?: AbortSignal): Promise<void> {
    const response = await this.fetcher(this.endpoint!, {
      method: 'POST',
      headers: this.headers(true),
      body: JSON.stringify({ jsonrpc: '2.0', method }),
      signal,
    });
    if (!response.ok) {
      throw new ConnectionContractError('MCP_HTTP_FAILED', `MCP notification returned ${response.status}`);
    }
  }

  private headers(requireSession: boolean): Record<string, string> {
    this.requireConnection(requireSession);
    const headers: Record<string, string> = {
      Authorization: `Bearer ${this.accessToken}`,
      'Content-Type': 'application/json',
      Accept: 'application/json, text/event-stream',
      Origin: new URL(this.endpoint!).origin,
      'MCP-Protocol-Version': MCP_PROTOCOL_REVISION,
    };
    if (requireSession) {
      headers['Mcp-Session-Id'] = this.sessionId!;
    }
    return headers;
  }

  private requireConnection(requireSession: boolean): void {
    if (!this.endpoint) this.notConnected();
    if (!this.accessToken) this.notConnected();
    if (requireSession && !this.sessionId) this.notConnected();
  }

  private notConnected(): never {
    throw new ConnectionContractError('MCP_NOT_CONNECTED', 'MCP client is not connected');
  }

  private requireUploadTarget(candidate: string): string {
    try {
      const endpoint = new URL(this.endpoint!);
      const target = new URL(candidate);
      if (endpoint.pathname !== '/mcp' || endpoint.search || endpoint.hash
          || target.origin !== endpoint.origin || target.username || target.password
          || target.search || target.hash
          || !/^\/mcp\/uploads\/(?:uv|up)-[0-9a-f-]{36}$/.test(target.pathname)) {
        throw new Error();
      }
      return target.toString();
    } catch {
      throw new ConnectionContractError('MCP_UPLOAD_URL_INVALID', 'MCP_UPLOAD_URL_INVALID');
    }
  }
}

async function responsePayload(response: Response): Promise<JsonRpcResponse> {
  const body = await response.text();
  const contentType = response.headers.get('Content-Type');
  const value = contentType?.startsWith('text/event-stream') === true
    ? body.split(/\r?\n/).filter(line => line.startsWith('data:')).map(line => line.slice(5)).at(-1)
    : body;
  if (!value) {
    throw new ConnectionContractError('MCP_JSON_RPC_INVALID', 'MCP response body was empty');
  }
  return parseObject(value, 'MCP_JSON_RPC_INVALID') as unknown as JsonRpcResponse;
}

function parseObject(value: string, code: string): Record<string, unknown> {
  try {
    const parsed: unknown = JSON.parse(value);
    if (parsed === null) throw new Error('not an object');
    if (Array.isArray(parsed)) throw new Error('not an object');
    if (typeof parsed !== 'object') throw new Error('not an object');
    return parsed as Record<string, unknown>;
  } catch (error) {
    throw new ConnectionContractError(code, `${code}: ${error instanceof Error ? error.message : String(error)}`);
  }
}

function successfulToolOwnerResult(result: Record<string, unknown>): unknown {
  if (result.structuredContent !== undefined) return result.structuredContent;
  const content = result.content;
  if (!Array.isArray(content) || content.length !== 1) {
    throw new ConnectionContractError(
      'MCP_TOOL_RESULT_INVALID',
      'expected exactly one content item',
    );
  }
  const item = content[0];
  if (item === null || Array.isArray(item)
      || (item as Record<string, unknown>).type !== 'text'
      || typeof (item as Record<string, unknown>).text !== 'string') {
    throw new ConnectionContractError(
      'MCP_TOOL_RESULT_INVALID',
      'content item must contain JSON text',
    );
  }
  try {
    return JSON.parse((item as Record<string, string>).text);
  } catch {
    throw new ConnectionContractError(
      'MCP_TOOL_RESULT_INVALID',
      'text content was not valid JSON',
    );
  }
}

function requiredString(value: object, key: string, code: string): string {
  const field = (value as Record<string, unknown>)[key];
  if (typeof field !== 'string' || !field.trim()) {
    throw new ConnectionContractError(code, `${code}: ${key} missing`);
  }
  return field;
}

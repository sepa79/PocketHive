import * as vscode from 'vscode';
import { existsSync } from 'node:fs';
import { execSync } from 'node:child_process';
import { McpClient } from './client';

export type McpStatus = 'starting' | 'running' | 'stopped' | 'error';

const _onStatusChange = new vscode.EventEmitter<McpStatus>();
export const onMcpStatusChange = _onStatusChange.event;

let _client: McpClient | null = null;
let _restartAttempts = 0;
const MAX_RESTARTS = 3;

export function getMcpClient(): McpClient | null {
  return _client;
}

export function isMcpRunning(): boolean {
  return _client !== null;
}

export async function startMcpServer(context: vscode.ExtensionContext): Promise<void> {
  // Stop any existing instance first
  if (_client) {
    await _client.close().catch(() => {});
    _client = null;
  }

  let serverPath: string;
  try {
    serverPath = resolveServerPath();
  } catch (err) {
    _onStatusChange.fire('error');
    const msg = err instanceof Error ? err.message : String(err);
    const choice = await vscode.window.showErrorMessage(
      `PocketHive: ${msg}`,
      'Download Node.js'
    );
    if (choice === 'Download Node.js') {
      vscode.env.openExternal(vscode.Uri.parse('https://nodejs.org/en/download'));
    }
    return;
  }

  const env = await buildMcpEnv(context);
  _onStatusChange.fire('starting');

  const client = new McpClient(serverPath, env);
  try {
    await client.connect();
    _client = client;
    _restartAttempts = 0;
    _onStatusChange.fire('running');
  } catch (err) {
    _client = null;
    _onStatusChange.fire('error');
    scheduleRestart(context);
  }
}

export async function restartMcpServer(context: vscode.ExtensionContext): Promise<void> {
  if (_client) { await _client.close().catch(() => {}); _client = null; }
  _onStatusChange.fire('stopped');
  await new Promise(r => setTimeout(r, 500));
  await startMcpServer(context);
}

export async function stopMcpServer(): Promise<void> {
  if (_client) { await _client.close().catch(() => {}); _client = null; }
  _onStatusChange.fire('stopped');
}

function scheduleRestart(context: vscode.ExtensionContext): void {
  if (_restartAttempts >= MAX_RESTARTS) {
    vscode.window.showErrorMessage(
      'PocketHive: MCP server failed to start after 3 attempts.',
      'Retry'
    ).then(choice => {
      if (choice === 'Retry') { _restartAttempts = 0; startMcpServer(context); }
    });
    return;
  }
  const delayMs = [2000, 4000, 8000][_restartAttempts] ?? 8000;
  _restartAttempts++;
  setTimeout(() => startMcpServer(context), delayMs);
}

function resolveServerPath(): string {
  const override = vscode.workspace.getConfiguration('pockethive').get<string>('mcpServerPath') ?? '';
  if (override.trim() && existsSync(override.trim())) return override.trim();

  // Try globally installed npm package
  try {
    const root = execSync('npm root -g', { encoding: 'utf8', timeout: 5000 }).trim();
    const p = `${root}/@pockethive/mcp-server/server.mjs`;
    if (existsSync(p)) return p;
  } catch { /* fall through */ }

  throw new Error(
    'PocketHive MCP server not found.\n' +
    'Run: npm install -g @pockethive/mcp-server\n' +
    'Or set pockethive.mcpServerPath in settings to point to server.mjs directly.'
  );
}

async function buildMcpEnv(context: vscode.ExtensionContext): Promise<NodeJS.ProcessEnv> {
  const cfg = vscode.workspace.getConfiguration('pockethive');
  const envs = cfg.get<Environment[]>('environments') ?? [];
  const activeName = cfg.get<string>('activeEnvironment') ?? '';
  const env = envs.find(e => e.name === activeName) ?? envs[0];
  const bundlesFolder = cfg.get<string>('activeBundlesFolder') ?? '';
  const pockethiveRoot = cfg.get<string>('pockethiveRoot') ?? '';
  const allFolders = cfg.get<string[]>('bundlesFolders') ?? [];

  const rabbitPass = env ? (await context.secrets.get(`ph.env.${env.name}.rabbitPass`) ?? 'guest') : 'guest';
  const authToken = env ? (await context.secrets.get(`ph.env.${env.name}.authToken`) ?? '') : '';

  return {
    ...process.env,
    POCKETHIVE_BASE_URL: env?.baseUrl ?? 'http://localhost:8088',
    POCKETHIVE_ROOT: pockethiveRoot,
    BUNDLES_ROOT: bundlesFolder,
    RABBITMQ_DEFAULT_USER: env?.rabbitUser ?? 'guest',
    RABBITMQ_DEFAULT_PASS: rabbitPass,
    GITHUB_TOKEN: authToken,
    PH_BUNDLES_ROOTS: JSON.stringify(allFolders),
    ...(env?.tcpMockUrl ? { TCP_MOCK_BASE_URL: env.tcpMockUrl } : {}),
    ...(env?.wiremockUrl ? { WIREMOCK_BASE_URL: env.wiremockUrl } : {}),
  };
}

export interface Environment {
  name: string;
  baseUrl: string;
  rabbitUser?: string;
  tcpMockUrl?: string;
  wiremockUrl?: string;
}

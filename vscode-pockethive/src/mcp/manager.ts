import * as vscode from 'vscode';
import { existsSync } from 'node:fs';
import { execSync } from 'node:child_process';
import { McpClient } from './client';
import { getOutputChannel } from '../output';

export type McpStatus = 'starting' | 'running' | 'stopped' | 'error';

const _onStatusChange = new vscode.EventEmitter<McpStatus>();
export const onMcpStatusChange = _onStatusChange.event;

let _client: McpClient | null = null;
let _restartAttempts = 0;
let _status: McpStatus = 'stopped';
let _statusMessage = 'MCP server has not been started.';
const MAX_RESTARTS = 3;

export function getMcpClient(): McpClient | null {
  return _client;
}

export function isMcpRunning(): boolean {
  return _client !== null;
}

export function getMcpStatusSnapshot(): { status: McpStatus; running: boolean; message: string } {
  return { status: _status, running: _client !== null, message: _statusMessage };
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
    const msg = err instanceof Error ? err.message : String(err);
    setStatus('error', msg);
    logMcpStatus(`MCP server path error: ${msg}`);
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
  setStatus('starting', `Starting ${serverPath}`);
  logMcpStatus(`Starting MCP server: ${serverPath}`);

  const client = new McpClient(serverPath, env);
  try {
    await client.connect();
    _client = client;
    _restartAttempts = 0;
    setStatus('running', `Running ${serverPath}`);
    logMcpStatus('MCP server running.');
  } catch (err) {
    _client = null;
    const msg = err instanceof Error ? err.message : String(err);
    setStatus('error', msg);
    logMcpStatus(`MCP server start failed: ${msg}`);
    scheduleRestart(context);
  }
}

export async function restartMcpServer(context: vscode.ExtensionContext): Promise<void> {
  if (_client) { await _client.close().catch(() => {}); _client = null; }
  setStatus('stopped', 'Restart requested.');
  await new Promise(r => setTimeout(r, 500));
  await startMcpServer(context);
}

export async function stopMcpServer(): Promise<void> {
  if (_client) { await _client.close().catch(() => {}); _client = null; }
  setStatus('stopped', 'Stopped by extension.');
}

function scheduleRestart(context: vscode.ExtensionContext): void {
  if (_restartAttempts >= MAX_RESTARTS) {
    setStatus('error', `MCP server failed to start after ${MAX_RESTARTS} attempts. ${_statusMessage}`);
    logMcpStatus(_statusMessage);
    vscode.window.showErrorMessage(
      'PocketHive: MCP server failed to start after 3 attempts.',
      'Retry',
      'Show Output'
    ).then(choice => {
      if (choice === 'Retry') { _restartAttempts = 0; startMcpServer(context); }
      if (choice === 'Show Output') getOutputChannel().show(true);
    });
    return;
  }
  const delayMs = [2000, 4000, 8000][_restartAttempts] ?? 8000;
  _restartAttempts++;
  setTimeout(() => startMcpServer(context), delayMs);
}

function setStatus(status: McpStatus, message: string): void {
  _status = status;
  _statusMessage = message;
  _onStatusChange.fire(status);
}

function logMcpStatus(message: string): void {
  try {
    getOutputChannel().appendLine(`[${new Date().toISOString()}] ${message}`);
  } catch {
    // The output channel is initialized during extension activation.
  }
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
  const safeEnvs = envs.map(({ authToken: _authToken, ...safe }) => safe);
  const bundlesFolder = cfg.get<string>('activeBundlesFolder') ?? '';
  const pockethiveRoot = cfg.get<string>('pockethiveRoot') ?? '';
  const allFolders = cfg.get<string[]>('bundlesFolders') ?? [];
  const workflowSourceRoots = cfg.get<string[]>('workflowSourceRoots') ?? [];

  const rabbitPass = env ? (await context.secrets.get(`ph.env.${env.name}.rabbitPass`) ?? 'guest') : 'guest';
  return {
    ...process.env,
    POCKETHIVE_BASE_URL: env?.baseUrl ?? 'http://localhost:8088',
    POCKETHIVE_AUTH_TOKEN: env?.authToken?.trim() ?? '',
    POCKETHIVE_AUTH_USERNAME: env?.authUsername?.trim() ?? '',
    POCKETHIVE_ROOT: pockethiveRoot,
    BUNDLES_ROOT: bundlesFolder,
    RABBITMQ_DEFAULT_USER: env?.rabbitUser ?? 'guest',
    RABBITMQ_DEFAULT_PASS: rabbitPass,
    PH_ACTIVE_ENVIRONMENT: env?.name ?? '',
    PH_ENVIRONMENTS: JSON.stringify(safeEnvs),
    PH_BUNDLES_ROOTS: JSON.stringify(allFolders),
    PH_WORKFLOW_SOURCE_ROOTS: JSON.stringify(workflowSourceRoots),
    ...(env?.tcpMockUrl ? { TCP_MOCK_BASE_URL: env.tcpMockUrl } : {}),
    ...(env?.wiremockUrl ? { WIREMOCK_BASE_URL: env.wiremockUrl } : {}),
  };
}

export interface Environment {
  name: string;
  baseUrl: string;
  authToken?: string;
  authUsername?: string;
  rabbitUser?: string;
  tcpMockUrl?: string;
  wiremockUrl?: string;
}

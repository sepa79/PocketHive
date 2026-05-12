import { getMcpClient } from './manager';

async function call(name: string, args: Record<string, unknown> = {}): Promise<unknown> {
  const client = getMcpClient();
  if (!client) throw new Error('MCP server is not running');
  return client.callTool(name, args);
}

// ── Bundle ────────────────────────────────────────────────────────────────────

export async function bundleList() {
  return call('bundle.list') as Promise<{ bundles: BundleSummary[] }>;
}

export async function bundleRead(bundle: string, file: string) {
  return call('bundle.read', { bundle, file }) as Promise<{ content: string; path: string }>;
}

export async function bundleScaffold(bundleId: string, pattern: string, sutType = 'none') {
  return call('bundle.scaffold', { bundleId, pattern, sutType });
}

export async function bundleValidate(bundle: string): Promise<{ jobId: string }> {
  return call('bundle.validate', { bundle }) as Promise<{ jobId: string }>;
}

export async function bundleValidateResult(jobId: string): Promise<ValidationResult> {
  return call('bundle.validate.result', { jobId }) as Promise<ValidationResult>;
}

// ── Scenario ──────────────────────────────────────────────────────────────────

export async function scenarioDeploy(bundle: string) {
  return call('scenario.deploy', { bundle });
}

export async function scenarioList(): Promise<ScenarioSummary[]> {
  return call('scenario.list') as Promise<ScenarioSummary[]>;
}

export async function scenarioGet(scenarioId: string): Promise<ScenarioDetail> {
  return call('scenario.get', { scenarioId }) as Promise<ScenarioDetail>;
}

// ── Swarm ─────────────────────────────────────────────────────────────────────

export async function swarmList(): Promise<SwarmSummary[]> {
  return call('swarm.list') as Promise<SwarmSummary[]>;
}

export async function swarmGet(swarmId: string): Promise<SwarmDetail> {
  return call('swarm.get', { swarmId }) as Promise<SwarmDetail>;
}

export async function swarmCreate(swarmId: string, templateId: string, sutId?: string, variablesProfileId?: string) {
  return call('swarm.create', { swarmId, templateId, ...(sutId ? { sutId } : {}), ...(variablesProfileId ? { variablesProfileId } : {}) });
}

export async function swarmWaitReady(swarmId: string, timeoutSec = 90) {
  return call('swarm.wait-ready', { swarmId, timeoutSec }) as Promise<{ ready: boolean; swarmStatus: string }>;
}

export async function swarmStart(swarmId: string) {
  return call('swarm.start', { swarmId });
}

export async function swarmStop(swarmId: string) {
  return call('swarm.stop', { swarmId });
}

export async function swarmRemove(swarmId: string) {
  return call('swarm.remove', { swarmId });
}

// ── Debug ─────────────────────────────────────────────────────────────────────

export async function debugQueues(swarmId?: string) {
  return call('debug.queues', swarmId ? { swarmId } : {});
}

export async function debugJournal(swarmId: string, limit = 20) {
  return call('debug.journal', { swarmId, limit });
}

// ── Health ────────────────────────────────────────────────────────────────────

export async function healthCheck(): Promise<HealthResult> {
  return call('health.check') as Promise<HealthResult>;
}

// ── Context ───────────────────────────────────────────────────────────────────

export async function contextGet() {
  return call('context.get') as Promise<ContextInfo>;
}

// ── Types ─────────────────────────────────────────────────────────────────────

export interface BundleSummary {
  name: string;
  hasScenario: boolean;
  hasTemplates: boolean;
  hasDatasets: boolean;
  hasSut: boolean;
  hasVariables: boolean;
  hasReadme: boolean;
}

export interface ValidationResult {
  jobId: string;
  status: 'running' | 'done' | 'error';
  generator?: string;
  httpTemplates?: string;
  error?: string;
  elapsedSeconds?: number;
}

export interface ScenarioSummary {
  id: string;
  name?: string;
}

export interface ScenarioDetail {
  id?: string;
  name?: string;
  description?: string;
  template?: { image?: string; bees?: unknown[] };
}

export interface SwarmSummary {
  id: string;
  status?: string;
  health?: string;
  templateId?: string;
}

export interface SwarmDetail {
  id?: string;
  envelope?: { data?: { context?: { swarmStatus?: string; totals?: { desired: number; healthy: number } } } };
}

export interface HealthResult {
  orchestrator: string;
  'scenario-manager': string;
  rabbitmq: string;
  prometheus: string;
  baseUrl: string;
  [key: string]: unknown;
}

export interface ContextInfo {
  bundlesRoot: string;
  bundlesRootName: string;
  pockethiveRoot: string;
  baseUrl: string;
  platform: string;
}

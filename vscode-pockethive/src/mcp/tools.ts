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

export async function bundleRead(bundle: unknown, file: string) {
  return call('bundle.read', { bundle: requireBundleName(bundle), file }) as Promise<{ content: string; path: string }>;
}

export async function bundleScaffold(bundleId: string, pattern: string, sutType = 'none') {
  return call('bundle.scaffold', { bundleId, pattern, sutType });
}

export async function bundleCheck(bundle: unknown): Promise<BundleCheckResult> {
  return call('bundle.check', { bundle: requireBundleName(bundle) }) as Promise<BundleCheckResult>;
}

export async function bundleValidate(bundle: unknown): Promise<{ jobId: string }> {
  return call('bundle.validate', { bundle: requireBundleName(bundle) }) as Promise<{ jobId: string }>;
}

export async function bundleValidateResult(jobId: string): Promise<ValidationResult> {
  return call('bundle.validate.result', { jobId }) as Promise<ValidationResult>;
}

// ── Scenario ──────────────────────────────────────────────────────────────────

export async function scenarioDeploy(bundle: unknown) {
  return call('scenario.deploy', { bundle: requireBundleName(bundle) });
}

export async function scenarioList(): Promise<ScenarioSummary[]> {
  return call('scenario.list') as Promise<ScenarioSummary[]>;
}

export async function scenarioGet(scenarioId: unknown): Promise<ScenarioDetail> {
  return call('scenario.get', { scenarioId: requireScenarioId(scenarioId) }) as Promise<ScenarioDetail>;
}

export async function scenarioRawRead(scenarioId: unknown): Promise<{ scenarioId: string; content: string }> {
  return call('scenario.raw.read', { scenarioId: requireScenarioId(scenarioId) }) as Promise<{ scenarioId: string; content: string }>;
}

export async function scenarioRawWrite(scenarioId: unknown, content: string): Promise<{ scenarioId: string; written: boolean; response?: string }> {
  return call('scenario.raw.write', { scenarioId: requireScenarioId(scenarioId), content }) as Promise<{ scenarioId: string; written: boolean; response?: string }>;
}

export async function scenarioSchemaRead(scenarioId: unknown, path: string): Promise<{ scenarioId: string; path: string; content: string }> {
  return call('scenario.schema.read', { scenarioId: requireScenarioId(scenarioId), path }) as Promise<{ scenarioId: string; path: string; content: string }>;
}

export async function scenarioTemplateRead(scenarioId: unknown, path: string): Promise<{ scenarioId: string; path: string; content: string }> {
  return call('scenario.template.read', { scenarioId: requireScenarioId(scenarioId), path }) as Promise<{ scenarioId: string; path: string; content: string }>;
}

export async function scenarioCapabilities(all = true): Promise<unknown[]> {
  return call('scenario.capabilities.get', { all }) as Promise<unknown[]>;
}

// ── Swarm ─────────────────────────────────────────────────────────────────────

export async function swarmList(): Promise<SwarmSummary[]> {
  return call('swarm.list') as Promise<SwarmSummary[]>;
}

export async function swarmGet(swarmId: unknown): Promise<SwarmDetail> {
  return call('swarm.get', { swarmId: requireSwarmId(swarmId) }) as Promise<SwarmDetail>;
}

export async function swarmCreate(swarmId: string, templateId: string, sutId?: string, variablesProfileId?: string) {
  return call('swarm.create', { swarmId, templateId, ...(sutId ? { sutId } : {}), ...(variablesProfileId ? { variablesProfileId } : {}) });
}

export async function swarmWaitReady(swarmId: unknown, timeoutSec = 90) {
  return call('swarm.wait-ready', { swarmId: requireSwarmId(swarmId), timeoutSec }) as Promise<{ ready: boolean; swarmStatus: string }>;
}

export async function swarmStart(swarmId: unknown) {
  return call('swarm.start', { swarmId: requireSwarmId(swarmId) });
}

export async function swarmStop(swarmId: unknown) {
  return call('swarm.stop', { swarmId: requireSwarmId(swarmId) });
}

export async function swarmRemove(swarmId: unknown) {
  return call('swarm.remove', { swarmId: requireSwarmId(swarmId) });
}

// ── Debug ─────────────────────────────────────────────────────────────────────

export async function debugQueues(swarmId?: unknown) {
  return call('debug.queues', swarmId ? { swarmId: requireSwarmId(swarmId) } : {});
}

export async function debugJournal(swarmId: unknown, limit = 20) {
  return call('debug.journal', { swarmId: requireSwarmId(swarmId), limit });
}

export async function debugHiveJournal(limit = 50) {
  return call('debug.hive-journal', { limit });
}

export async function evidenceSummary(swarmId: unknown, includeTapSample = false): Promise<EvidenceSummary> {
  return call('evidence.summary', { swarmId: requireSwarmId(swarmId), includeTapSample }) as Promise<EvidenceSummary>;
}

// ── Workflow Status/Config ───────────────────────────────────────────────────

export async function workflowConfigGet(): Promise<WorkflowConfig> {
  return call('workflow.config.get') as Promise<WorkflowConfig>;
}

export async function workflowConfigValidate(): Promise<WorkflowConfigValidation> {
  return call('workflow.config.validate') as Promise<WorkflowConfigValidation>;
}

export async function workflowList(includeQuestions = true): Promise<WorkflowListResult> {
  return call('workflow.list', { includeQuestions }) as Promise<WorkflowListResult>;
}

export async function workflowStatus(workflowId: string): Promise<WorkflowStatus> {
  return call('workflow.status', { workflowId }) as Promise<WorkflowStatus>;
}

// ── Health ────────────────────────────────────────────────────────────────────

export async function healthCheck(): Promise<HealthResult> {
  return call('health.check') as Promise<HealthResult>;
}

export async function envStatus(): Promise<EnvironmentStatusResult> {
  return call('env.status') as Promise<EnvironmentStatusResult>;
}

// ── Context ───────────────────────────────────────────────────────────────────

export async function contextGet() {
  return call('context.get') as Promise<ContextInfo>;
}

function requireBundleName(value: unknown): string {
  const bundleName = resolveBundleName(value);
  if (!bundleName) throw new Error('bundle name is required');
  return bundleName;
}

function requireScenarioId(value: unknown): string {
  const scenarioId = resolveScenarioId(value);
  if (!scenarioId) throw new Error('scenario id is required');
  return scenarioId;
}

function resolveScenarioId(value: unknown): string | undefined {
  if (!value) return undefined;
  if (typeof value === 'string') return nonBlank(value);
  if (typeof value !== 'object') return undefined;

  if ('id' in value) {
    const id = (value as { id?: unknown }).id;
    if (typeof id === 'string') return nonBlank(id);
  }

  if ('scenario' in value) {
    const scenario = (value as { scenario?: unknown }).scenario;
    const id = resolveScenarioId(scenario);
    if (id) return id;
  }

  if ('command' in value) {
    const first = (value as { command?: { arguments?: unknown[] } }).command?.arguments?.[0];
    const id = resolveScenarioId(first);
    if (id) return id;
  }

  if ('label' in value) {
    const label = (value as { label?: unknown }).label;
    if (typeof label === 'string') return nonBlank(label);
    if (label && typeof label === 'object' && 'label' in label) {
      const nested = (label as { label?: unknown }).label;
      if (typeof nested === 'string') return nonBlank(nested);
    }
  }

  return undefined;
}

function resolveBundleName(value: unknown): string | undefined {
  if (!value) return undefined;
  if (typeof value === 'string') return nonBlank(value);
  if (typeof value !== 'object') return undefined;

  if ('name' in value) {
    const name = (value as { name?: unknown }).name;
    if (typeof name === 'string') return nonBlank(name);
  }

  if ('id' in value) {
    const id = (value as { id?: unknown }).id;
    if (typeof id === 'string') return nonBlank(id);
  }

  if ('bundle' in value) {
    const bundle = (value as { bundle?: unknown }).bundle;
    const name = resolveBundleName(bundle);
    if (name) return name;
  }

  if ('command' in value) {
    const first = (value as { command?: { arguments?: unknown[] } }).command?.arguments?.[0];
    const name = resolveBundleName(first);
    if (name) return name;
  }

  if ('label' in value) {
    const label = (value as { label?: unknown }).label;
    if (typeof label === 'string') return nonBlank(label);
    if (label && typeof label === 'object' && 'label' in label) {
      const nested = (label as { label?: unknown }).label;
      if (typeof nested === 'string') return nonBlank(nested);
    }
  }

  return undefined;
}

function requireSwarmId(value: unknown): string {
  const swarmId = resolveSwarmId(value);
  if (!swarmId) throw new Error('swarm id is required');
  return swarmId;
}

function resolveSwarmId(value: unknown): string | undefined {
  if (!value) return undefined;
  if (typeof value === 'string') return nonBlank(value);
  if (typeof value !== 'object') return undefined;

  if ('id' in value) {
    const id = (value as { id?: unknown }).id;
    if (typeof id === 'string') return nonBlank(id);
  }

  if ('swarm' in value) {
    const swarm = (value as { swarm?: unknown }).swarm;
    const id = resolveSwarmId(swarm);
    if (id) return id;
  }

  if ('command' in value) {
    const first = (value as { command?: { arguments?: unknown[] } }).command?.arguments?.[0];
    const id = resolveSwarmId(first);
    if (id) return id;
  }

  if ('label' in value) {
    const label = (value as { label?: unknown }).label;
    if (typeof label === 'string') return nonBlank(label);
    if (label && typeof label === 'object' && 'label' in label) {
      const nested = (label as { label?: unknown }).label;
      if (typeof nested === 'string') return nonBlank(nested);
    }
  }

  return undefined;
}

function nonBlank(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed ? trimmed : undefined;
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
  mode?: string;
  source?: string;
  structural?: BundleCheckResult;
  note?: string;
  error?: string;
  elapsedSeconds?: number;
}

export interface BundleCheckResult {
  ok: boolean;
  bundle: string;
  path: string;
  scenarioId?: string;
  checks: Array<{ id: string; ok: boolean; message: string; severity: string }>;
  errors: Array<{ id: string; message: string }>;
  warnings: Array<{ id: string; message: string }>;
  artifacts?: Record<string, boolean>;
  source?: string;
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

export interface EvidenceSummary {
  swarmId: string;
  lifecycle: Record<string, unknown>;
  queues: Record<string, unknown>;
  journal: Record<string, unknown>;
  metrics: Record<string, unknown>;
  mocks: Record<string, unknown>;
  datasets: Record<string, unknown>;
  missingEvidence: Array<{ source: string; reason?: string }>;
  sources: Array<{ name: string; status: string; error?: string }>;
}

export interface WorkflowConfig {
  workflowType: string;
  sessionTtlMs: number;
  sourceMaxBytes: number;
  supportedSourceTypes: string[];
  bundleRoot: string;
  allowedSourceRoots: string[];
  runtime: Record<string, string>;
  pluginBoundary: {
    mayAnswerQuestions: false;
    readOnlyTools: string[];
    mutatingTools: string;
  };
}

export interface WorkflowConfigValidation {
  ok: boolean;
  checks: Array<{ id: string; ok: boolean; value?: unknown; message: string }>;
  missing: string[];
  config: WorkflowConfig;
}

export interface WorkflowQuestion {
  id: string;
  prompt: string;
  type: string;
  options?: string[];
}

export interface WorkflowSummary {
  workflowId: string;
  workflowType: string;
  state: string;
  source: { type: string; path: string | null; sha256: string; bytes: number };
  bundle: { id: string; path: string } | null;
  generated: { bundleId: string; path: string } | null;
  missing: string[];
  nextQuestions: WorkflowQuestion[];
  allowedActions: string[];
  evidenceGaps: Array<{ id: string; status: string }>;
  historyCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface WorkflowListResult {
  workflows: WorkflowSummary[];
  count: number;
}

export interface WorkflowStatus {
  workflowId: string;
  workflowType: string;
  state: string;
  source: { type: string; path: string | null; sha256: string; bytes: number };
  plan: Record<string, unknown> | null;
  bundle: { id: string; path: string } | null;
  generated: { bundleId: string; path: string } | null;
  missing: string[];
  nextQuestions: WorkflowQuestion[];
  allowedActions: string[];
  evidenceGaps: Array<{ id: string; status: string }>;
  evidence: Record<string, unknown>;
  history: Array<Record<string, unknown>>;
}

export interface ContextInfo {
  bundlesRoot: string;
  bundlesRootName: string;
  pockethiveRoot: string;
  baseUrl: string;
  platform: string;
}

export interface EnvironmentStatusResult {
  activeEnvironment: string;
  environments: EnvironmentStatus[];
  source: string;
}

export interface EnvironmentStatus {
  name: string;
  baseUrl: string;
  active: boolean;
  state: 'active' | 'inactive' | 'inaccessible' | 'degraded' | 'auth-required' | 'auth-error' | 'invalid';
  message?: string;
  services?: Record<string, { ok: boolean; status: string; httpStatus?: number | null; error?: string | null }>;
}

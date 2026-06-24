import { getMcpClient } from './manager';
import {
  resolveBundleName as resolveBundleNameTarget,
  resolveScenarioId as resolveScenarioIdTarget,
  resolveSwarmId as resolveSwarmIdTarget,
} from '../targetResolver';

async function call(name: string, args: Record<string, unknown> = {}): Promise<unknown> {
  const client = getMcpClient();
  if (!client) throw new Error('MCP server is not running');
  return client.callTool(name, args);
}

// ── Bundle ────────────────────────────────────────────────────────────────────

export async function bundleList() {
  return call('bundle_list') as Promise<{ bundles: BundleSummary[] }>;
}

export async function bundleRead(bundle: unknown, file: string) {
  return call('bundle_read', { bundle: requireBundleName(bundle), file }) as Promise<{ content: string; path: string }>;
}

export async function bundleScaffold(bundleId: string, pattern: string, sutType = 'none') {
  return call('bundle_scaffold', { bundleId, pattern, sutType });
}

export async function bundleValidate(bundle: unknown): Promise<{ jobId: string }> {
  return call('bundle_validate', { bundle: requireBundleName(bundle) }) as Promise<{ jobId: string }>;
}

export async function bundleValidateResult(jobId: string): Promise<ValidationResult> {
  return call('bundle_validate_result', { jobId }) as Promise<ValidationResult>;
}

// ── Scenario ──────────────────────────────────────────────────────────────────

export async function scenarioDeploy(bundle: unknown) {
  return call('scenario_deploy', { bundle: requireBundleName(bundle) });
}

export async function scenarioList(): Promise<ScenarioSummary[]> {
  return call('scenario_list') as Promise<ScenarioSummary[]>;
}

export async function scenarioGet(scenarioId: unknown): Promise<ScenarioDetail> {
  return call('scenario_get', { scenarioId: requireScenarioId(scenarioId) }) as Promise<ScenarioDetail>;
}

export async function scenarioRawRead(scenarioId: unknown): Promise<{ scenarioId: string; content: string }> {
  return call('scenario_raw_read', { scenarioId: requireScenarioId(scenarioId) }) as Promise<{ scenarioId: string; content: string }>;
}

export async function scenarioRawWrite(scenarioId: unknown, content: string): Promise<{ scenarioId: string; written: boolean; response?: string }> {
  return call('scenario_raw_write', { scenarioId: requireScenarioId(scenarioId), content }) as Promise<{ scenarioId: string; written: boolean; response?: string }>;
}

export async function scenarioSchemaRead(scenarioId: unknown, path: string): Promise<{ scenarioId: string; path: string; content: string }> {
  return call('scenario_schema_read', { scenarioId: requireScenarioId(scenarioId), path }) as Promise<{ scenarioId: string; path: string; content: string }>;
}

export async function scenarioTemplateRead(scenarioId: unknown, path: string): Promise<{ scenarioId: string; path: string; content: string }> {
  return call('scenario_template_read', { scenarioId: requireScenarioId(scenarioId), path }) as Promise<{ scenarioId: string; path: string; content: string }>;
}

export async function scenarioCapabilities(all = true): Promise<unknown[]> {
  return call('scenario_capabilities_get', { all }) as Promise<unknown[]>;
}

// ── Swarm ─────────────────────────────────────────────────────────────────────

export async function swarmList(): Promise<SwarmSummary[]> {
  return call('swarm_list') as Promise<SwarmSummary[]>;
}

export async function swarmGet(swarmId: unknown): Promise<SwarmDetail> {
  return call('swarm_get', { swarmId: requireSwarmId(swarmId) }) as Promise<SwarmDetail>;
}

export async function swarmCreate(swarmId: string, templateId: string, sutId?: string, variablesProfileId?: string) {
  return call('swarm_create', { swarmId, templateId, ...(sutId ? { sutId } : {}), ...(variablesProfileId ? { variablesProfileId } : {}) });
}

export async function swarmWaitReady(swarmId: unknown, timeoutSec = 90) {
  return call('swarm_wait_ready', { swarmId: requireSwarmId(swarmId), timeoutSec }) as Promise<{ ready: boolean; swarmStatus: string }>;
}

export async function swarmStart(swarmId: unknown) {
  return call('swarm_start', { swarmId: requireSwarmId(swarmId) });
}

export async function swarmStop(swarmId: unknown) {
  return call('swarm_stop', { swarmId: requireSwarmId(swarmId) });
}

export async function swarmRemove(swarmId: unknown) {
  return call('swarm_remove', { swarmId: requireSwarmId(swarmId) });
}

// ── Debug ─────────────────────────────────────────────────────────────────────

export async function debugQueues(swarmId?: unknown) {
  return call('debug_queues', swarmId ? { swarmId: requireSwarmId(swarmId) } : {});
}

export async function debugJournal(swarmId: unknown, limit = 20) {
  return call('debug_journal', { swarmId: requireSwarmId(swarmId), limit });
}

export async function debugHiveJournal(limit = 50) {
  return call('debug_hive_journal', { limit });
}

export async function evidenceSummary(swarmId: unknown, includeTapSample = false): Promise<EvidenceSummary> {
  return call('evidence_summary', { swarmId: requireSwarmId(swarmId), includeTapSample }) as Promise<EvidenceSummary>;
}

// ── Workflow Status/Config ───────────────────────────────────────────────────

export async function workflowConfigGet(): Promise<WorkflowConfig> {
  return call('workflow_config_get') as Promise<WorkflowConfig>;
}

export async function workflowConfigValidate(): Promise<WorkflowConfigValidation> {
  return call('workflow_config_validate') as Promise<WorkflowConfigValidation>;
}

export async function workflowList(includeQuestions = true): Promise<WorkflowListResult> {
  return call('workflow_list', { includeQuestions }) as Promise<WorkflowListResult>;
}

export async function workflowStatus(workflowId: string): Promise<WorkflowStatus> {
  return call('workflow_status', { workflowId }) as Promise<WorkflowStatus>;
}

// ── Health ────────────────────────────────────────────────────────────────────

export async function healthCheck(): Promise<HealthResult> {
  return call('health_check') as Promise<HealthResult>;
}

export async function envStatus(): Promise<EnvironmentStatusResult> {
  return call('env_status') as Promise<EnvironmentStatusResult>;
}

// ── Context ───────────────────────────────────────────────────────────────────

export async function contextGet() {
  return call('context_get') as Promise<ContextInfo>;
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
  return resolveScenarioIdTarget(value);
}

function resolveBundleName(value: unknown): string | undefined {
  return resolveBundleNameTarget(value);
}

function requireSwarmId(value: unknown): string {
  const swarmId = resolveSwarmId(value);
  if (!swarmId) throw new Error('swarm id is required');
  return swarmId;
}

function resolveSwarmId(value: unknown): string | undefined {
  return resolveSwarmIdTarget(value);
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
  scenarioManager?: ScenarioManagerValidationResult;
  note?: string;
  error?: string;
  elapsedSeconds?: number;
}

export interface ScenarioManagerValidationResult {
  ok?: boolean;
  source?: string;
  scenarioId?: string;
  summary?: { errors?: number; warnings?: number };
  findings?: Array<{ severity?: string; code?: string; path?: string; message?: string; fix?: string }>;
  [key: string]: unknown;
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
  defaultProfileId?: string;
  roles?: WorkflowRole[];
  profiles?: WorkflowProfile[];
  supportedSourceTypes: string[];
  supportedModes?: string[];
  examples?: { sourceOrder: Array<{ id: string; authority: string; root: string }> };
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
  questionKind?: 'missing-field' | 'invalid-answer' | 'provenance-confirmation' | string;
  field?: string;
  questionGroup?: string;
  order?: number;
  dependsOn?: string[];
  triggeredBy?: Record<string, unknown> | null;
  answerType?: string;
  answerOwner?: string;
  canAgentInfer?: boolean;
  confidence?: string;
  options?: string[];
  allowedProvenance?: string[];
  whyAsked?: string;
  resolution?: {
    tool: string;
    planField?: string | null;
    provenanceField?: string | null;
  };
  blockedAction?: string | null;
}

export interface WorkflowQuestionGraph {
  nodes: Array<{
    id: string;
    field?: string;
    questionKind?: string;
    questionGroup?: string;
    order?: number;
    dependsOn?: string[];
    resolvedBy?: string | null;
  }>;
  edges: Array<{ from: string; to: string; questionId: string }>;
}

export interface WorkflowRole {
  id: string;
  label: string;
  mission?: string;
  authority?: string;
  mustDo?: string[];
  mustNot?: string[];
}

export interface WorkflowProfile {
  id: string;
  label: string;
  purpose?: string;
  authority?: string;
  roleSequence?: string[];
}

export interface WorkflowReviewStage {
  id: string;
  label: string;
  beforeAction: string;
  purpose?: string;
  status: string;
  requiredRoles: Array<{ roleId: string; label?: string; status: string; check?: { outcome?: string; summary?: string } | null }>;
}

export interface WorkflowEvidenceRequirement {
  id: string;
  label: string;
  requiredBefore: string;
  status: string;
}

export interface WorkflowExampleRef {
  bundleId: string;
  source: string;
  authority?: string;
  path?: string;
  demonstrates?: string[];
}

export interface WorkflowValidationIssue {
  severity: 'error' | 'warn' | string;
  code: string;
  field: string;
  message: string;
}

export interface WorkflowClaim {
  id: string;
  claim: string;
  status: string;
  required: boolean;
  evidence: string[];
  gap: string | null;
}

export interface WorkflowEvidenceContractClaim {
  id: string;
  label: string;
  stage: string;
  required: boolean;
  proofTool: string | null;
  sourceFields: string[];
  status: string;
}

export interface WorkflowBlocker {
  id: string;
  kind: string;
  field?: string | null;
  blockedAction?: string | null;
  resolvedBy?: string | null;
  unresolvable?: boolean;
  stageId?: string;
  roleId?: string;
  status?: string;
}

export interface WorkflowStuckState {
  stuck: boolean;
  attempts: number;
  action?: string;
  failureCode?: string;
  reason?: string;
  suggestedNextActions?: string[];
  patchScope?: string[];
}

export interface WorkflowOperationSummary {
  operationId: string;
  type: 'deploy' | 'verify' | string;
  status: 'running' | 'succeeded' | 'failed' | 'cancelled' | string;
  phase: string;
  createdAt?: string;
  updatedAt?: string;
  nextPollAfterMs?: number;
  lastStep?: {
    id: string;
    phase: string;
    ok: boolean;
    at: string;
    code?: string | null;
    ready?: boolean;
    apiActions?: WorkflowOperationApiAction[];
  } | null;
  apiActions?: WorkflowOperationApiAction[];
  phaseTimeline?: WorkflowOperationPhase[];
}

export interface WorkflowOperationApiAction {
  at: string;
  phase: string;
  action: string;
  method: string;
  target: string;
  result: string;
  evidenceKey: string;
}

export interface WorkflowOperationPhase {
  phase: string;
  firstSeenAt: string;
  lastSeenAt: string;
  attempts: number;
  status: string;
  lastCode?: string | null;
}

export interface WorkflowSummary {
  workflowId: string;
  workflowType: string;
  mode?: 'create' | 'modify' | string;
  state: string;
  profile?: { id: string; label: string; authority?: string };
  activeRole?: WorkflowRole;
  roleChecklist?: Array<{ id: string; roleId: string; text: string; authority?: string }>;
  reviewStages?: WorkflowReviewStage[];
  source: { type: string; path: string | null; sha256: string; bytes: number };
  example?: WorkflowExampleRef | null;
  bundle: { id: string; path: string } | null;
  generated: { bundleId: string; path: string } | null;
  missing: string[];
  nextQuestions: WorkflowQuestion[];
  questionGraph?: WorkflowQuestionGraph;
  validationIssues?: WorkflowValidationIssue[];
  provenanceGaps?: Array<{ field: string; reason: string }>;
  allowedActions: string[];
  evidenceRequirements?: WorkflowEvidenceRequirement[];
  evidenceContract?: WorkflowEvidenceContractClaim[];
  evidenceGaps: Array<{ id: string; status: string }>;
  claimMatrix?: WorkflowClaim[];
  blockers?: WorkflowBlocker[];
  unresolvableBlockers?: WorkflowBlocker[];
  stuckState?: WorkflowStuckState;
  activeOperations?: Record<string, string>;
  operations?: Record<string, WorkflowOperationSummary>;
  remediation?: { failureCode: string; suggestedNextActions: string[]; patchScope: string[]; stuckState?: WorkflowStuckState } | null;
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
  mode?: 'create' | 'modify' | string;
  state: string;
  profile?: WorkflowProfile;
  activeRole?: WorkflowRole;
  roleChecklist?: Array<{ id: string; roleId: string; text: string; authority?: string }>;
  reviewStages?: WorkflowReviewStage[];
  source: { type: string; path: string | null; sha256: string; bytes: number };
  example?: WorkflowExampleRef | null;
  plan: Record<string, unknown> | null;
  provenance?: Record<string, unknown>;
  bundle: { id: string; path: string } | null;
  generated: { bundleId: string; path: string } | null;
  missing: string[];
  nextQuestions: WorkflowQuestion[];
  questionGraph?: WorkflowQuestionGraph;
  validationIssues?: WorkflowValidationIssue[];
  provenanceGaps?: Array<{ field: string; reason: string }>;
  allowedActions: string[];
  evidenceRequirements?: WorkflowEvidenceRequirement[];
  evidenceContract?: WorkflowEvidenceContractClaim[];
  evidenceGaps: Array<{ id: string; status: string }>;
  claimMatrix?: WorkflowClaim[];
  blockers?: WorkflowBlocker[];
  unresolvableBlockers?: WorkflowBlocker[];
  stuckState?: WorkflowStuckState;
  activeOperations?: Record<string, string>;
  operations?: Record<string, WorkflowOperationSummary>;
  evidence: Record<string, unknown>;
  remediation?: { failureCode: string; suggestedNextActions: string[]; patchScope: string[]; stuckState?: WorkflowStuckState } | null;
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

import * as vscode from 'vscode';
import { getEnvironments, getActiveEnvironmentName, getBundlesFolders, getActiveBundlesFolder } from '../config';
import { getMcpStatusSnapshot, isMcpRunning, onMcpStatusChange, type McpStatus } from '../mcp/manager';
import {
  envStatus,
  workflowConfigValidate,
  workflowList,
  type EnvironmentStatus,
  type WorkflowConfigValidation,
  type WorkflowQuestion,
  type WorkflowSummary,
} from '../mcp/tools';

type SettingsNode =
  | { kind: 'section'; label: string }
  | { kind: 'environment'; name: string; baseUrl: string; active: boolean; state: EnvironmentStatus['state'] | 'unchecked'; message?: string }
  | { kind: 'add-env' }
  | { kind: 'folder'; path: string; active: boolean }
  | { kind: 'add-folder' }
  | { kind: 'mcp-status'; running: boolean; status: McpStatus; message: string }
  | { kind: 'workflow-config'; validation: WorkflowConfigValidation | null }
  | { kind: 'workflow'; workflow: WorkflowSummary }
  | { kind: 'workflow-detail'; label: string; description?: string; icon: string; severity?: 'ok' | 'warn' | 'error' }
  | { kind: 'workflow-question'; question: WorkflowQuestion }
  | { kind: 'message'; message: string };

type EnvironmentViewState = EnvironmentStatus['state'] | 'unchecked';

export class SettingsProvider implements vscode.TreeDataProvider<SettingsNode> {
  private readonly emitter = new vscode.EventEmitter<SettingsNode | undefined>();
  readonly onDidChangeTreeData = this.emitter.event;

  constructor() {
    onMcpStatusChange(() => this.emitter.fire(undefined));
  }

  refresh(): void { this.emitter.fire(undefined); }

  getTreeItem(node: SettingsNode): vscode.TreeItem {
    switch (node.kind) {
      case 'section': {
        const item = new vscode.TreeItem(node.label, vscode.TreeItemCollapsibleState.None);
        item.iconPath = new vscode.ThemeIcon('symbol-namespace');
        return item;
      }
      case 'environment': {
        const item = new vscode.TreeItem(node.name, vscode.TreeItemCollapsibleState.None);
        item.id = node.name;
        item.description = `${environmentStateLabel(node.state)}  ${node.baseUrl}`;
        item.tooltip = node.message ? `${node.baseUrl}\n${node.message}` : node.baseUrl;
        item.iconPath = environmentIcon(node.state, node.active);
        item.contextValue = node.active ? 'environment-active' : 'environment';
        item.command = {
          command: 'pockethive.setActiveEnvironment',
          title: 'Use environment',
          arguments: [node.name],
        };
        return item;
      }
      case 'add-env': {
        const item = new vscode.TreeItem('+ Add environment', vscode.TreeItemCollapsibleState.None);
        item.iconPath = new vscode.ThemeIcon('add');
        item.command = { command: 'pockethive.addEnvironment', title: 'Add environment' };
        return item;
      }
      case 'folder': {
        const parts = node.path.replace(/\\/g, '/').split('/').filter(Boolean);
        const name = parts[parts.length - 1] ?? node.path;
        const item = new vscode.TreeItem(name, vscode.TreeItemCollapsibleState.None);
        item.id = node.path;
        item.description = node.path;
        item.tooltip = node.path;
        item.iconPath = node.active
          ? new vscode.ThemeIcon('folder-active')
          : new vscode.ThemeIcon('folder');
        item.contextValue = node.active ? 'bundlesFolder-active' : 'bundlesFolder';
        item.command = {
          command: 'pockethive.setActiveBundlesFolder',
          title: 'Use bundles folder',
          arguments: [node.path],
        };
        return item;
      }
      case 'add-folder': {
        const item = new vscode.TreeItem('+ Add bundles folder', vscode.TreeItemCollapsibleState.None);
        item.iconPath = new vscode.ThemeIcon('add');
        item.command = { command: 'pockethive.addBundlesFolder', title: 'Add bundles folder' };
        return item;
      }
      case 'mcp-status': {
        const item = new vscode.TreeItem(
          `MCP Server: ${mcpStatusLabel(node.status, node.running)}`,
          vscode.TreeItemCollapsibleState.None
        );
        item.tooltip = node.message;
        item.iconPath = node.running
          ? new vscode.ThemeIcon('circle-filled', new vscode.ThemeColor('testing.iconPassed'))
          : node.status === 'error'
            ? new vscode.ThemeIcon('error', new vscode.ThemeColor('testing.iconFailed'))
            : node.status === 'starting'
              ? new vscode.ThemeIcon('sync~spin', new vscode.ThemeColor('testing.iconQueued'))
              : new vscode.ThemeIcon('circle-outline');
        item.contextValue = 'mcp-status';
        return item;
      }
      case 'workflow-config': {
        const ok = node.validation?.ok ?? false;
        const item = new vscode.TreeItem(
          `Workflow config: ${ok ? 'Ready' : 'Needs attention'}`,
          vscode.TreeItemCollapsibleState.None
        );
        item.description = node.validation?.config.bundleRoot;
        item.tooltip = node.validation
          ? workflowConfigTooltip(node.validation)
          : 'MCP server is not running';
        item.iconPath = ok
          ? new vscode.ThemeIcon('check', new vscode.ThemeColor('testing.iconPassed'))
          : new vscode.ThemeIcon('warning', new vscode.ThemeColor('testing.iconQueued'));
        return item;
      }
      case 'workflow': {
        const questions = node.workflow.nextQuestions.length;
        const details = workflowDetailNodes(node.workflow).length;
        const item = new vscode.TreeItem(
          node.workflow.workflowId,
          questions || details ? vscode.TreeItemCollapsibleState.Collapsed : vscode.TreeItemCollapsibleState.None
        );
        item.description = `${node.workflow.state}  ${node.workflow.activeRole?.label ?? node.workflow.source.type}`;
        item.tooltip = workflowTooltip(node.workflow);
        item.iconPath = workflowIcon(node.workflow.state, questions);
        item.contextValue = 'workflow-status';
        return item;
      }
      case 'workflow-detail': {
        const item = new vscode.TreeItem(node.label, vscode.TreeItemCollapsibleState.None);
        item.description = node.description;
        item.tooltip = node.description ? `${node.label}\n${node.description}` : node.label;
        item.iconPath = detailIcon(node.icon, node.severity);
        return item;
      }
      case 'workflow-question': {
        const item = new vscode.TreeItem(node.question.prompt, vscode.TreeItemCollapsibleState.None);
        item.description = node.question.questionGroup
          ? `${node.question.questionKind ?? 'question'}  ${node.question.questionGroup}`
          : node.question.questionKind ?? node.question.id;
        const details = [
          node.question.id,
          node.question.answerOwner ? `Owner: ${node.question.answerOwner}` : undefined,
          node.question.canAgentInfer === false ? 'Agent inference: no' : undefined,
          node.question.dependsOn?.length ? `Depends on: ${node.question.dependsOn.join(', ')}` : undefined,
          node.question.resolution?.tool ? `Resolve with: ${node.question.resolution.tool}` : undefined,
          node.question.options?.length ? `Options: ${node.question.options.join(', ')}` : undefined,
          node.question.allowedProvenance?.length ? `Accepted provenance: ${node.question.allowedProvenance.join(', ')}` : undefined,
          node.question.whyAsked,
        ].filter(Boolean);
        item.tooltip = details.join('\n');
        item.iconPath = new vscode.ThemeIcon('question');
        return item;
      }
      case 'message': {
        return new vscode.TreeItem(node.message, vscode.TreeItemCollapsibleState.None);
      }
    }
  }

  async getChildren(node?: SettingsNode): Promise<SettingsNode[]> {
    if (node?.kind === 'workflow') {
      return [
        ...workflowDetailNodes(node.workflow),
        ...node.workflow.nextQuestions.map(question => ({ kind: 'workflow-question' as const, question })),
      ];
    }

    const envs = getEnvironments();
    const activeName = getActiveEnvironmentName();
    const folders = getBundlesFolders();
    const activeFolder = getActiveBundlesFolder();
    let statuses = new Map<string, EnvironmentStatus>();
    let workflowValidation: WorkflowConfigValidation | null = null;
    let workflows: WorkflowSummary[] = [];
    if (isMcpRunning()) {
      try {
        const result = await envStatus();
        statuses = new Map(result.environments.map(env => [env.name, env]));
      } catch {
        statuses = new Map();
      }
      try {
        workflowValidation = await workflowConfigValidate();
        workflows = (await workflowList(true)).workflows;
      } catch {
        workflowValidation = null;
        workflows = [];
      }
    }

    const environmentNodes: SettingsNode[] = envs.map(e => {
      const status = statuses.get(e.name);
      const state: EnvironmentViewState = status?.state ?? 'unchecked';
      return {
        kind: 'environment',
        name: e.name,
        baseUrl: e.baseUrl,
        active: e.name === activeName,
        state,
        message: statusMessage(status),
      };
    });

    const mcpStatus = getMcpStatusSnapshot();
    const nodes: SettingsNode[] = [
      { kind: 'section', label: 'ENVIRONMENTS' },
      ...environmentNodes,
      { kind: 'add-env' },
      { kind: 'section', label: 'BUNDLES FOLDERS' },
      ...folders.map(p => ({ kind: 'folder' as const, path: p, active: p === activeFolder })),
      { kind: 'add-folder' },
      { kind: 'section', label: 'MCP SERVER' },
      { kind: 'mcp-status', running: mcpStatus.running, status: mcpStatus.status, message: mcpStatus.message },
      { kind: 'section', label: 'WORKFLOWS' },
      { kind: 'workflow-config', validation: workflowValidation },
      ...workflows.map(workflow => ({ kind: 'workflow' as const, workflow })),
    ];

    return nodes;
  }
}

function mcpStatusLabel(status: McpStatus, running: boolean): string {
  if (running) return 'Running';
  switch (status) {
    case 'starting': return 'Starting';
    case 'error': return 'Error';
    case 'stopped': return 'Stopped';
    default: return status;
  }
}

function environmentStateLabel(state: EnvironmentStatus['state'] | 'unchecked'): string {
  switch (state) {
    case 'active': return 'Active';
    case 'inactive': return 'Inactive';
    case 'inaccessible': return 'Inaccessible';
    case 'degraded': return 'Degraded';
    case 'auth-required': return 'Auth required';
    case 'auth-error': return 'Auth error';
    case 'invalid': return 'Invalid';
    default: return 'Unchecked';
  }
}

function environmentIcon(state: EnvironmentStatus['state'] | 'unchecked', active: boolean): vscode.ThemeIcon {
  if (state === 'active') return new vscode.ThemeIcon('check', new vscode.ThemeColor('testing.iconPassed'));
  if (state === 'inactive') return new vscode.ThemeIcon('circle-outline', new vscode.ThemeColor('charts.green'));
  if (state === 'degraded') return new vscode.ThemeIcon('warning', new vscode.ThemeColor('testing.iconQueued'));
  if (state === 'auth-required' || state === 'auth-error') return new vscode.ThemeIcon('lock', new vscode.ThemeColor('testing.iconQueued'));
  if (state === 'inaccessible' || state === 'invalid') return new vscode.ThemeIcon('error', new vscode.ThemeColor('testing.iconFailed'));
  return active ? new vscode.ThemeIcon('sync~spin') : new vscode.ThemeIcon('circle-outline');
}

function statusMessage(status: EnvironmentStatus | undefined): string | undefined {
  if (!status) return undefined;
  if (status.message) return status.message;
  const parts = Object.entries(status.services ?? {}).map(([name, service]) => {
    const suffix = service.ok ? service.status : (service.error || service.status);
    return `${name}: ${suffix}`;
  });
  return parts.length ? parts.join('\n') : undefined;
}

function workflowConfigTooltip(validation: WorkflowConfigValidation): string {
  const lines = [
    `Bundle root: ${validation.config.bundleRoot}`,
    `Source roots: ${validation.config.allowedSourceRoots.join(', ') || 'none'}`,
  ];
  if (!validation.ok) lines.push(`Missing: ${validation.missing.join(', ')}`);
  return lines.join('\n');
}

function workflowTooltip(workflow: WorkflowSummary): string {
  const lines = [
    `State: ${workflow.state}`,
    `Mode: ${workflow.mode ?? 'create'}`,
    `Profile: ${workflow.profile?.label ?? 'unknown'}`,
    `Active role: ${workflow.activeRole?.label ?? 'unknown'}`,
    `Source: ${workflow.source.path ?? workflow.source.type}`,
    `Example: ${workflow.example?.bundleId ?? 'none'}`,
    `Bundle: ${workflow.bundle?.id ?? 'not generated'}`,
    `Remaining questions: ${workflow.nextQuestions.length}`,
    `Validation issues: ${workflow.validationIssues?.length ?? 0}`,
    `Blockers: ${workflow.blockers?.length ?? 0}`,
    `Unresolvable blockers: ${workflow.unresolvableBlockers?.length ?? 0}`,
    `Active operations: ${Object.keys(workflow.activeOperations ?? {}).length}`,
    `Evidence contract: ${workflow.evidenceContract?.length ?? 0}`,
    `Evidence gaps: ${workflow.evidenceGaps.length}`,
  ];
  if (workflow.stuckState?.stuck) lines.push(`Stuck: ${workflow.stuckState.failureCode ?? workflow.stuckState.action}`);
  if (workflow.remediation?.failureCode) lines.push(`Remediation: ${workflow.remediation.failureCode}`);
  return lines.join('\n');
}

function workflowDetailNodes(workflow: WorkflowSummary): SettingsNode[] {
  const nodes: SettingsNode[] = [];
  if (workflow.profile) {
    nodes.push({
      kind: 'workflow-detail',
      label: `Profile: ${workflow.profile.label}`,
      description: workflow.profile.authority ?? workflow.profile.id,
      icon: 'account',
    });
  }
  if (workflow.activeRole) {
    nodes.push({
      kind: 'workflow-detail',
      label: `Role: ${workflow.activeRole.label}`,
      description: workflow.activeRole.mission,
      icon: 'person',
    });
  }
  for (const issue of workflow.validationIssues ?? []) {
    nodes.push({
      kind: 'workflow-detail',
      label: `Validation: ${issue.field}`,
      description: issue.code,
      icon: 'error',
      severity: issue.severity === 'error' ? 'error' : 'warn',
    });
  }
  for (const claim of workflow.claimMatrix ?? []) {
    if (claim.status === 'satisfied' || claim.status === 'not-applicable') continue;
    nodes.push({
      kind: 'workflow-detail',
      label: `Claim: ${claim.id}`,
      description: claim.status,
      icon: claim.status === 'failed' ? 'error' : 'debug-pause',
      severity: claim.status === 'failed' ? 'error' : claim.required ? 'warn' : undefined,
    });
  }
  for (const stage of workflow.reviewStages ?? []) {
    nodes.push({
      kind: 'workflow-detail',
      label: `${stage.label}: ${stage.status}`,
      description: stage.requiredRoles.map(role => `${role.roleId}: ${role.check?.outcome ?? role.status}`).join(', '),
      icon: 'checklist',
      severity: stage.status === 'complete' ? 'ok' : stage.status === 'failed' ? 'error' : 'warn',
    });
  }
  const operations = Object.values(workflow.operations ?? {});
  const activeOperationIds = new Set(Object.values(workflow.activeOperations ?? {}));
  for (const operation of operations) {
    const active = activeOperationIds.has(operation.operationId);
    nodes.push({
      kind: 'workflow-detail',
      label: `${operation.type}: ${operation.status}`,
      description: `${operation.phase}${operation.lastStep?.code ? `  ${operation.lastStep.code}` : ''}${active ? '  active' : ''}`,
      icon: operation.status === 'failed' ? 'error' : operation.status === 'succeeded' ? 'check' : 'sync',
      severity: operation.status === 'failed' ? 'error' : operation.status === 'succeeded' ? 'ok' : 'warn',
    });
  }
  for (const requirement of workflow.evidenceRequirements ?? []) {
    nodes.push({
      kind: 'workflow-detail',
      label: `${requirement.label}: ${requirement.status}`,
      description: requirement.requiredBefore,
      icon: 'verified',
      severity: requirement.status === 'satisfied' ? 'ok' : 'warn',
    });
  }
  for (const claim of workflow.evidenceContract ?? []) {
    if (claim.status === 'satisfied' || claim.status === 'not-applicable') continue;
    nodes.push({
      kind: 'workflow-detail',
      label: `Evidence contract: ${claim.id}`,
      description: `${claim.status}${claim.proofTool ? ` via ${claim.proofTool}` : ''}`,
      icon: 'symbol-event',
      severity: claim.required ? 'warn' : undefined,
    });
  }
  for (const gap of workflow.evidenceGaps ?? []) {
    nodes.push({
      kind: 'workflow-detail',
      label: `Evidence gap: ${gap.id}`,
      description: gap.status,
      icon: 'warning',
      severity: gap.status === 'missing' ? 'warn' : undefined,
    });
  }
  if (workflow.stuckState?.stuck) {
    nodes.push({
      kind: 'workflow-detail',
      label: `Stuck: ${workflow.stuckState.failureCode ?? workflow.stuckState.action}`,
      description: workflow.stuckState.suggestedNextActions?.join(', ') ?? workflow.stuckState.reason,
      icon: 'debug-rerun',
      severity: 'warn',
    });
  }
  if (workflow.remediation?.failureCode) {
    nodes.push({
      kind: 'workflow-detail',
      label: `Remediation: ${workflow.remediation.failureCode}`,
      description: workflow.remediation.suggestedNextActions.join(', '),
      icon: 'wrench',
      severity: 'warn',
    });
  }
  return nodes;
}

function detailIcon(icon: string, severity?: 'ok' | 'warn' | 'error'): vscode.ThemeIcon {
  const color = severity === 'ok'
    ? new vscode.ThemeColor('testing.iconPassed')
    : severity === 'error'
      ? new vscode.ThemeColor('testing.iconFailed')
      : severity === 'warn'
        ? new vscode.ThemeColor('testing.iconQueued')
        : undefined;
  return color ? new vscode.ThemeIcon(icon, color) : new vscode.ThemeIcon(icon);
}

function workflowIcon(state: string, questionCount: number): vscode.ThemeIcon {
  if (questionCount > 0) return new vscode.ThemeIcon('question', new vscode.ThemeColor('testing.iconQueued'));
  if (state === 'verified' || state === 'reported') return new vscode.ThemeIcon('check', new vscode.ThemeColor('testing.iconPassed'));
  if (state === 'generated' || state === 'validated' || state === 'deployed') return new vscode.ThemeIcon('beaker');
  return new vscode.ThemeIcon('checklist');
}

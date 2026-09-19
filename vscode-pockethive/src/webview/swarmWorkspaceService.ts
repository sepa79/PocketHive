/**
 * Responsibility: Coordinate bounded swarm workspace use cases through the PocketHive MCP tool port.
 * Must not: Own VS Code presentation, authentication, confirmation dialogs, or PocketHive lifecycle state.
 * Contract: docs/mcp/README.md and vscode-pockethive/README.md.
 */
import { randomUUID } from 'node:crypto';

import { ConnectionContractError } from '../connection/contracts';
import {
  lifecycleToolCall,
  swarmIdsForOperation,
  SwarmOperation,
} from '../operations/swarmOperations';
import { SwarmNetworkMode } from './messages';

export interface SwarmToolPort {
  callTool(name: string, arguments_?: Record<string, unknown>): Promise<unknown>;
}

export interface CreateSwarmFormState {
  readonly templates: unknown;
  readonly selectedTemplateId?: string;
  readonly selectedScenarioId?: string;
  readonly sutIds?: readonly string[];
  readonly autoPullImages: boolean;
  readonly networkMode: SwarmNetworkMode;
}

export interface CreateSwarmInput {
  readonly swarmId: string;
  readonly templateId: string;
  readonly scenarioId: string;
  readonly autoPullImages: boolean;
  readonly sutId: string | null;
  readonly variablesProfileId: string | null;
  readonly networkMode: SwarmNetworkMode;
  readonly networkProfileId: string | null;
}

export interface SwarmMutationResult {
  readonly operationResult: unknown;
  readonly swarms: unknown;
}

export class SwarmWorkspaceService {
  constructor(
    private readonly tools: SwarmToolPort,
    private readonly nextIdempotencyKey: () => string = randomUUID,
  ) {}

  async history(swarmId: string): Promise<unknown> {
    return this.tools.callTool('debug_journal_runs', { swarmId });
  }

  async details(swarmId: string): Promise<unknown> {
    return this.tools.callTool('swarm_get', { swarmId });
  }

  async operate(action: SwarmOperation, swarmId: string): Promise<SwarmMutationResult> {
    const call = lifecycleToolCall(action, swarmId, this.nextIdempotencyKey());
    const operationResult = await this.tools.callTool(call.name, call.arguments);
    return { operationResult, swarms: await this.tools.callTool('swarm_list') };
  }

  async operateBatch(action: SwarmOperation, currentSwarms: unknown): Promise<SwarmMutationResult> {
    const targets = swarmIdsForOperation(currentSwarms, action);
    if (targets.length === 0) {
      throw new ConnectionContractError(
        'SWARM_BATCH_TARGETS_MISSING',
        `No swarms are eligible for ${action.toLowerCase()}`,
      );
    }
    const succeeded: string[] = [];
    const failed: Array<{ swarmId: string; error: { code: string; message: string } }> = [];
    for (const swarmId of targets) {
      try {
        const call = lifecycleToolCall(action, swarmId, this.nextIdempotencyKey());
        await this.tools.callTool(call.name, call.arguments);
        succeeded.push(swarmId);
      } catch (error) {
        failed.push({ swarmId, error: safeSwarmError(error) });
      }
    }
    return {
      operationResult: {
        batchOperation: action,
        requested: targets.length,
        succeeded,
        failed,
      },
      swarms: await this.tools.callTool('swarm_list'),
    };
  }

  async createForm(): Promise<CreateSwarmFormState> {
    const templates = await this.tools.callTool('scenario_templates_catalog');
    const selected = firstCreatableTemplate(templates);
    return {
      templates,
      selectedTemplateId: selected?.templateId,
      selectedScenarioId: selected?.scenarioId,
      sutIds: selected ? await this.scenarioSutIds(selected.scenarioId) : [],
      autoPullImages: true,
      networkMode: 'DIRECT',
    };
  }

  async selectTemplate(
    current: CreateSwarmFormState,
    templateId: string,
    scenarioId: string,
  ): Promise<CreateSwarmFormState> {
    requireCreateSwarmSelection(current.templates, templateId, scenarioId);
    return {
      ...current,
      selectedTemplateId: templateId,
      selectedScenarioId: scenarioId,
      sutIds: await this.scenarioSutIds(scenarioId),
    };
  }

  async create(
    form: CreateSwarmFormState,
    input: CreateSwarmInput,
  ): Promise<SwarmMutationResult> {
    requireCreateSwarmSelection(form.templates, input.templateId, input.scenarioId);
    const operationResult = await this.tools.callTool('swarm_create', {
      swarmId: input.swarmId,
      templateId: input.templateId,
      idempotencyKey: this.nextIdempotencyKey(),
      autoPullImages: input.autoPullImages,
      sutId: input.sutId,
      variablesProfileId: input.variablesProfileId,
      networkMode: input.networkMode,
      networkProfileId: input.networkProfileId,
    });
    return { operationResult, swarms: await this.tools.callTool('swarm_list') };
  }

  private async scenarioSutIds(scenarioId: string): Promise<string[]> {
    const value = await this.tools.callTool('scenario_suts_list', { scenarioId });
    if (!Array.isArray(value) || value.some(item => typeof item !== 'string' || !item.trim())) {
      throw new ConnectionContractError(
        'SCENARIO_SUTS_INVALID',
        `Scenario Manager returned invalid SUT identifiers for ${scenarioId}`,
      );
    }
    return value.map(item => item.trim());
  }
}

function firstCreatableTemplate(value: unknown): { templateId: string; scenarioId: string } | undefined {
  return creatableTemplates(value)[0];
}

function requireCreateSwarmSelection(value: unknown, templateId: string, scenarioId: string): void {
  const match = creatableTemplates(value).some(template =>
    template.templateId === templateId && template.scenarioId === scenarioId);
  if (!match) {
    throw new ConnectionContractError(
      'CREATE_SWARM_TEMPLATE_INVALID',
      'Select one exact non-defunct Scenario Manager template',
    );
  }
}

function creatableTemplates(value: unknown): Array<{ templateId: string; scenarioId: string }> {
  if (!Array.isArray(value)) return [];
  const result: Array<{ templateId: string; scenarioId: string }> = [];
  for (const item of value) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) continue;
    const record = item as Record<string, unknown>;
    const templateId = typeof record.id === 'string' ? record.id.trim() : '';
    if (!templateId || record.defunct === true) continue;
    result.push({ templateId, scenarioId: templateId });
  }
  return result;
}

function safeSwarmError(error: unknown): { code: string; message: string } {
  if (error instanceof ConnectionContractError) return { code: error.code, message: error.message };
  return {
    code: error instanceof Error ? error.name : 'COMPANION_ERROR',
    message: error instanceof Error ? error.message : String(error),
  };
}

import * as vscode from 'vscode';

import { formatError } from './format';
import { getOutputChannel } from './output';
import { ScenarioSummary, SwarmSummary } from './types';
import { scenarioList, swarmList } from './mcp/tools';

export async function pickSwarmId(): Promise<string | undefined> {
  let swarms: SwarmSummary[] = [];
  const outputChannel = getOutputChannel();

  try {
    swarms = await swarmList();
  } catch (error) {
    outputChannel.appendLine(`[${new Date().toISOString()}] WARN ${formatError(error)}`);
  }

  if (swarms.length > 0) {
    const items = swarms.map((swarm) => ({
      label: swarm.id,
      description: swarm.workloadState,
      detail: `controller: ${swarm.controllerState}, health: ${swarm.health}`,
      swarmId: swarm.id
    }));

    const pick = await vscode.window.showQuickPick(items, {
      placeHolder: 'Select a swarm',
      ignoreFocusOut: true
    });

    return pick?.swarmId;
  }

  const manual = await vscode.window.showInputBox({
    prompt: 'Swarm id',
    ignoreFocusOut: true,
    validateInput: (value) => (value.trim().length === 0 ? 'Swarm id is required.' : null)
  });

  return manual?.trim();
}

export async function pickScenarioId(): Promise<string | undefined> {
  let scenarios: ScenarioSummary[] = [];
  const outputChannel = getOutputChannel();

  try {
    scenarios = await scenarioList();
  } catch (error) {
    outputChannel.appendLine(`[${new Date().toISOString()}] WARN ${formatError(error)}`);
  }

  if (scenarios.length > 0) {
    const items = scenarios.map((scenario) => ({
      label: scenario.name || scenario.id,
      description: scenario.name && scenario.name !== scenario.id ? scenario.id : undefined,
      scenarioId: scenario.id
    }));

    const pick = await vscode.window.showQuickPick(items, {
      placeHolder: 'Select a scenario',
      ignoreFocusOut: true
    });

    return pick?.scenarioId;
  }

  const manual = await vscode.window.showInputBox({
    prompt: 'Scenario id',
    ignoreFocusOut: true,
    validateInput: (value) => (value.trim().length === 0 ? 'Scenario id is required.' : null)
  });

  return manual?.trim();
}

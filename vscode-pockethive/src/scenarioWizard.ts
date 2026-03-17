import { spawn } from 'child_process';
import { existsSync } from 'fs';
import { isAbsolute, resolve } from 'path';
import * as vscode from 'vscode';

import { getOutputChannel } from './output';

export type WizardTemplateType = 'rest-basic' | 'rest-request-builder';

type WizardResponse = {
  ok: boolean;
  export?: {
    targetPath: string;
    files: string[];
  };
  validation: {
    ok: boolean;
    issues: Array<{
      code: string;
      severity: string;
      path: string;
      message: string;
    }>;
  };
};

export type ScenarioWizardResult = {
  templateType: WizardTemplateType;
  exportPath: string;
  scenarioFilePath: string;
};

export async function createScenarioWizard(defaultTemplateType?: WizardTemplateType): Promise<ScenarioWizardResult | undefined> {
  const workspaceFolder = await pickWorkspaceFolder();
  if (!workspaceFolder) {
    return undefined;
  }
  const repoRoot = workspaceFolder.uri.fsPath;

  const templateType = defaultTemplateType ?? (await pickTemplateType());
  if (!templateType) {
    return undefined;
  }

  const scenarioId = await promptRequiredString('Scenario id', 'Unique bundle id, for example: demo-rest-basic');
  if (!scenarioId) {
    return undefined;
  }

  const scenarioName = await promptRequiredString('Scenario name', 'Human-friendly scenario name', scenarioId);
  if (!scenarioName) {
    return undefined;
  }

  const description = await vscode.window.showInputBox({
    title: 'Scenario description',
    prompt: 'Optional short description',
    ignoreFocusOut: true
  });
  if (description === undefined) {
    return undefined;
  }

  const baseUrl = await promptRequiredString(
    'Processor baseUrl',
    "Base URL expression for processor, for example: {{ sut.endpoints['default'].baseUrl }}/api",
    "{{ sut.endpoints['default'].baseUrl }}"
  );
  if (!baseUrl) {
    return undefined;
  }

  const ratePerSec = await promptPositiveNumber('Generator rate', 'Messages per second', '10');
  if (ratePerSec === undefined) {
    return undefined;
  }

  const stopTime = await promptRequiredString('Plan stop time', 'ISO-8601 duration, for example PT90S', 'PT90S');
  if (!stopTime) {
    return undefined;
  }

  const exportPath = await vscode.window.showInputBox({
    title: 'Export path',
    prompt: 'Absolute target directory for the generated bundle',
    value: resolve(workspaceFolder.uri.fsPath, 'scenarios', 'bundles', scenarioId),
    ignoreFocusOut: true,
    validateInput: (input) => {
      if (input.trim().length === 0) {
        return 'Value is required.';
      }
      return isAbsolute(input.trim()) ? null : 'Export path must be absolute.';
    }
  });
  if (!exportPath) {
    return undefined;
  }

  const httpRequest = templateType === 'rest-basic' ? await promptHttpRequest() : undefined;
  if (templateType === 'rest-basic' && !httpRequest) {
    return undefined;
  }

  const httpTemplate = templateType === 'rest-request-builder' ? await promptHttpTemplate() : undefined;
  if (templateType === 'rest-request-builder' && !httpTemplate) {
    return undefined;
  }

  const generatorConfig = {
    inputs: {
      type: 'SCHEDULER' as const,
      scheduler: {
        ratePerSec
      }
    },
    worker: templateType === 'rest-basic' ? httpRequest : httpTemplate?.generatorWorker
  };

  const payload = {
    workspacePath: workspaceFolder.uri.fsPath,
    scenarioId,
    scenarioName,
    description: description || undefined,
    templateType,
    processor: {
      config: {
        baseUrl
      }
    },
    generator: {
      config: generatorConfig
    },
    plan: {
      swarm: [
        {
          stepId: 'swarm-stop',
          name: 'Stop generated swarm',
          time: stopTime,
          type: 'stop'
        }
      ]
    },
    exportPath,
    ...(templateType === 'rest-basic'
      ? {}
      : {
          requestBuilder: httpTemplate?.requestBuilder,
          templateHttp: httpTemplate?.template
        })
  };

  const output = getOutputChannel();
  output.appendLine(`[${new Date().toISOString()}] Scenario wizard start (${templateType}) -> ${scenarioId}`);
  output.show(true);

  let result: WizardResponse;
  try {
    result = await runWizardCli(repoRoot, payload);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    output.appendLine(message);
    vscode.window.showErrorMessage(`PocketHive: scenario wizard failed. ${message}`);
    return undefined;
  }

  if (!result.ok || !result.export) {
    const validationSummary = result.validation.issues
      .map((issue) => `${issue.severity.toUpperCase()} ${issue.code} ${issue.path}: ${issue.message}`)
      .join('\n');
    output.appendLine(validationSummary || 'Scenario wizard failed without validation details.');
    vscode.window.showErrorMessage(`PocketHive: scenario wizard validation failed.\n${validationSummary || 'Unknown error.'}`);
    return undefined;
  }

  const scenarioFilePath = resolve(result.export.targetPath, 'scenario.yaml');
  output.appendLine(`[${new Date().toISOString()}] Scenario wizard exported ${scenarioFilePath}`);

  const openChoice = await vscode.window.showInformationMessage(
    `PocketHive: scenario '${scenarioId}' exported to ${result.export.targetPath}.`,
    'Open scenario',
    'Reveal folder'
  );

  if (openChoice === 'Open scenario') {
    await openScenarioFile(scenarioFilePath);
  } else if (openChoice === 'Reveal folder') {
    await vscode.commands.executeCommand('revealFileInOS', vscode.Uri.file(result.export.targetPath));
  }

  return {
    templateType,
    exportPath: result.export.targetPath,
    scenarioFilePath
  };
}

export async function openScenarioBuilderPocDoc(): Promise<void> {
  const workspaceFolder = await pickWorkspaceFolder();
  if (!workspaceFolder) {
    return;
  }
  const repoRoot = workspaceFolder.uri.fsPath;
  const docUri = vscode.Uri.file(resolve(repoRoot, 'docs', 'concepts', 'pockethive-chat-wizard-architecture.md'));
  if (!existsSync(docUri.fsPath)) {
    vscode.window.showErrorMessage(`PocketHive: architecture doc not found at ${docUri.fsPath}.`);
    return;
  }
  await vscode.commands.executeCommand('vscode.open', docUri);
}

async function pickWorkspaceFolder(): Promise<vscode.WorkspaceFolder | undefined> {
  const folders = vscode.workspace.workspaceFolders;
  if (!folders || folders.length === 0) {
    vscode.window.showErrorMessage('PocketHive: open a workspace folder before starting the scenario wizard.');
    return undefined;
  }
  if (folders.length === 1) {
    return folders[0];
  }

  const choice = await vscode.window.showQuickPick(
    folders.map((folder) => ({
      label: folder.name,
      description: folder.uri.fsPath,
      folder
    })),
    {
      title: 'Select workspace folder',
      ignoreFocusOut: true
    }
  );

  return choice?.folder;
}

async function pickTemplateType(): Promise<WizardTemplateType | undefined> {
  const choice = await vscode.window.showQuickPick(
    [
      {
        label: 'REST Basic',
        description: 'generator -> processor -> postprocessor',
        value: 'rest-basic' as WizardTemplateType
      },
      {
        label: 'REST + Request Builder',
        description: 'generator -> request-builder -> processor -> postprocessor',
        value: 'rest-request-builder' as WizardTemplateType
      }
    ],
    {
      title: 'Select wizard template',
      ignoreFocusOut: true
    }
  );

  return choice?.value;
}

async function promptHttpRequest(): Promise<{
  message: {
    bodyType: 'HTTP';
    method: string;
    path: string;
    body: string;
    headers: Record<string, string>;
  };
} | undefined> {
  const method = await pickHttpMethod('HTTP method');
  if (!method) {
    return undefined;
  }

  const path = await promptRequiredString('HTTP path', 'Request path', '/api/demo');
  if (!path) {
    return undefined;
  }

  const body = await promptRequiredString('HTTP body', 'Raw request body', '{"event":"demo"}');
  if (!body) {
    return undefined;
  }

  return {
    message: {
      bodyType: 'HTTP',
      method,
      path,
      body,
      headers: {
        'content-type': 'application/json'
      }
    }
  };
}

async function promptHttpTemplate(): Promise<{
  requestBuilder: {
    config: {
      templateRoot: string;
      serviceId: string;
    };
  };
  generatorWorker: {
    message: {
      bodyType: 'SIMPLE';
      body: string;
      headers: Record<string, string>;
    };
  };
  template: {
    serviceId: string;
    callId: string;
    protocol: 'HTTP';
    method: string;
    pathTemplate: string;
    bodyTemplate: string;
    headersTemplate: Record<string, string>;
  };
} | undefined> {
  const callId = await promptRequiredString('Template callId', 'HTTP template call id', 'default-call');
  if (!callId) {
    return undefined;
  }

  const serviceId = await promptRequiredString('Template serviceId', 'HTTP template service id', 'default');
  if (!serviceId) {
    return undefined;
  }

  const method = await pickHttpMethod('HTTP template method');
  if (!method) {
    return undefined;
  }

  const pathTemplate = await promptRequiredString('Template path', 'HTTP path template', '/api/demo');
  if (!pathTemplate) {
    return undefined;
  }

  const bodyTemplate = await promptRequiredString('Template body', 'HTTP body template', '{{ payload }}');
  if (!bodyTemplate) {
    return undefined;
  }

  const seedBody = await promptRequiredString('Seed payload', 'Generator seed payload body', '{"customer":"A"}');
  if (!seedBody) {
    return undefined;
  }

  return {
    requestBuilder: {
      config: {
        templateRoot: '/app/scenario/templates/http',
        serviceId
      }
    },
    generatorWorker: {
      message: {
        bodyType: 'SIMPLE',
        body: seedBody,
        headers: {
          'content-type': 'application/json',
          'x-ph-call-id': callId
        }
      }
    },
    template: {
      serviceId,
      callId,
      protocol: 'HTTP',
      method,
      pathTemplate,
      bodyTemplate,
      headersTemplate: {
        'content-type': 'application/json'
      }
    }
  };
}

async function pickHttpMethod(title: string): Promise<string | undefined> {
  return vscode.window.showQuickPick(['GET', 'POST', 'PUT', 'PATCH', 'DELETE'], {
    title,
    ignoreFocusOut: true
  });
}

async function promptRequiredString(title: string, prompt: string, value?: string): Promise<string | undefined> {
  const response = await vscode.window.showInputBox({
    title,
    prompt,
    value,
    ignoreFocusOut: true,
    validateInput: (input) => (input.trim().length === 0 ? 'Value is required.' : null)
  });

  return response?.trim();
}

async function promptPositiveNumber(title: string, prompt: string, value: string): Promise<number | undefined> {
  const raw = await vscode.window.showInputBox({
    title,
    prompt,
    value,
    ignoreFocusOut: true,
    validateInput: (input) => {
      const parsed = Number(input);
      return Number.isFinite(parsed) && parsed > 0 ? null : 'Enter a positive number.';
    }
  });

  if (raw === undefined) {
    return undefined;
  }

  return Number(raw);
}

async function runWizardCli(repoRoot: string, payload: unknown): Promise<WizardResponse> {
  const cliPath = resolve(repoRoot, 'tools', 'scenario-builder-mcp', 'cli.mjs');

  if (!existsSync(cliPath)) {
    throw new Error(`PocketHive scenario builder CLI not found at ${cliPath}.`);
  }

  const output = getOutputChannel();
  output.appendLine(`[${new Date().toISOString()}] node ${cliPath} create-from-wizard`);

  return new Promise<WizardResponse>((resolvePromise, reject) => {
    const child = spawn(process.execPath, [cliPath, 'create-from-wizard'], {
      cwd: repoRoot,
      stdio: ['pipe', 'pipe', 'pipe']
    });

    let stdout = '';
    let stderr = '';

    child.stdout.on('data', (chunk) => {
      stdout += chunk.toString();
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk.toString();
    });
    child.on('error', reject);
    child.on('close', (code) => {
      if (code !== 0) {
        reject(new Error(stderr.trim() || stdout.trim() || `Scenario wizard CLI exited with code ${code}.`));
        return;
      }

      try {
        resolvePromise(JSON.parse(stdout) as WizardResponse);
      } catch (error) {
        reject(new Error(`Scenario wizard CLI returned invalid JSON: ${stdout || stderr || String(error)}`));
      }
    });

    child.stdin.write(JSON.stringify(payload));
    child.stdin.end();
  });
}

async function openScenarioFile(filePath: string): Promise<void> {
  const document = await vscode.workspace.openTextDocument(vscode.Uri.file(filePath));
  await vscode.window.showTextDocument(document, { preview: false });
}

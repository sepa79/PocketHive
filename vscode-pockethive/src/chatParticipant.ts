import { basename } from 'path';
import * as vscode from 'vscode';

import {
  EvidenceSession,
  addArtifacts,
  addNote,
  analyzeEvidenceSession,
  getActiveEvidenceSession,
  getSessionFileReferences,
  pickEvidenceArtifacts,
  startEvidenceSession
} from './evidenceSession';
import { ScenarioWizardResult } from './scenarioWizard';
import { getOutputChannel } from './output';

const PARTICIPANT_ID = 'pockethive-vscode.scenario-wizard';

type EvidenceNextStepChoice = 'add-evidence' | 'answer-blocking' | 'proceed-scaffold';
type EvidenceNextStepOption = {
  choice: EvidenceNextStepChoice;
  description: string;
};

export function registerScenarioChatParticipant(context: vscode.ExtensionContext): vscode.Disposable {
  const chatApi = (
    vscode as unknown as {
      chat?: {
        createChatParticipant?: typeof vscode.chat.createChatParticipant;
      };
    }
  ).chat;

  if (!chatApi?.createChatParticipant) {
    getOutputChannel().appendLine(
      `[${new Date().toISOString()}] Chat participant not registered: VS Code Chat API is unavailable.`
    );
    return new vscode.Disposable(() => undefined);
  }

  const participant = chatApi.createChatParticipant(PARTICIPANT_ID, async (request, _context, stream, token) => {
    if (request.command === 'wizard') {
      return runWizardFlow(undefined, stream);
    }
    if (request.command === 'restBasic') {
      return runWizardFlow('rest-basic', stream);
    }
    if (request.command === 'requestBuilder') {
      return runWizardFlow('rest-request-builder', stream);
    }
    if (request.command === 'tutorial') {
      return showTutorial(stream);
    }
    if (request.command === 'fromEvidence') {
      return startEvidenceFlow(request, stream, token);
    }
    if (request.command === 'addEvidence') {
      return addEvidenceFlow(request, stream, token);
    }

    const prompt = request.prompt.toLowerCase();
    if (prompt.includes('request builder')) {
      return runWizardFlow('rest-request-builder', stream);
    }
    if (prompt.includes('cucumber') || prompt.includes('postman') || prompt.includes('evidence')) {
      return startEvidenceFlow(request, stream, token);
    }
    if (prompt.includes('tutorial') || prompt.includes('how do i use') || prompt.includes('how to use')) {
      return showTutorial(stream);
    }
    if (prompt.includes('mvp doc') || prompt.includes('design doc') || prompt.includes('open doc')) {
      await vscode.commands.executeCommand('pockethive.openScenarioBuilderPocDoc');
      stream.markdown('Opened the PocketHive chat wizard architecture document.');
      return {
        metadata: {
          kind: 'wizard-doc'
        }
      };
    }
    if (hasActiveEvidenceSession()) {
      return continueEvidenceFlow(request, stream, token);
    }
    if (prompt.includes('rest') || prompt.includes('scenario') || prompt.includes('wizard')) {
      return runWizardFlow(undefined, stream);
    }

    stream.markdown(
      [
        'PocketHive scenario wizard is available for new developers.',
        '',
        'Use `/tutorial` for onboarding, `/wizard` for the guided flow, `/restBasic` for a direct REST scaffold, `/requestBuilder` for a REST + request-builder scaffold, or `/fromEvidence` for Cucumber/Postman/Java driven analysis.',
        '',
        'The wizard exports a canonical PocketHive bundle and opens `scenario.yaml` in the editor.'
      ].join('\n')
    );
    stream.button({
      command: 'pockethive.createScenarioWizard',
      title: 'Start Wizard'
    });
    stream.button({
      command: 'pockethive.createScenarioWizard',
      title: 'REST Basic',
      arguments: ['rest-basic']
    });
    stream.button({
      command: 'pockethive.openActiveEvidenceSession',
      title: 'Open Evidence Session'
    });
    stream.button({
      command: 'pockethive.openScenarioBuilderPocDoc',
      title: 'Open Architecture Doc'
    });

    return {
      metadata: {
        kind: 'wizard-help'
      }
    };
  });

  participant.iconPath = vscode.Uri.joinPath(context.extensionUri, 'resources', 'hive.svg');
  participant.followupProvider = {
    provideFollowups(result) {
      if (result.metadata?.kind === 'wizard-complete') {
        return [
          { prompt: '/tutorial', label: 'Open tutorial', participant: PARTICIPANT_ID },
          { prompt: '/wizard', label: 'Create another scenario', participant: PARTICIPANT_ID },
          { prompt: 'Open the architecture doc', label: 'Review the design', participant: PARTICIPANT_ID }
        ];
      }
      if (result.metadata?.kind === 'evidence-analysis') {
        return [
          { prompt: '/addEvidence', label: 'Add evidence', participant: PARTICIPANT_ID },
          { prompt: 'The login call uses a token from step 2 response.', label: 'Add note and re-analyze', participant: PARTICIPANT_ID },
          { prompt: 'Proceed with scaffold creation despite the missing response-contract details.', label: 'Proceed with scaffold', participant: PARTICIPANT_ID },
          { prompt: 'Open the architecture doc', label: 'Review the design', participant: PARTICIPANT_ID }
        ];
      }

      return [
        { prompt: '/tutorial', label: 'Show tutorial', participant: PARTICIPANT_ID },
        { prompt: '/wizard', label: 'Start guided wizard', participant: PARTICIPANT_ID },
        { prompt: '/fromEvidence', label: 'Start from evidence', participant: PARTICIPANT_ID },
        { prompt: '/restBasic', label: 'Create REST Basic scenario', participant: PARTICIPANT_ID },
        { prompt: '/requestBuilder', label: 'Create REST + Request Builder scenario', participant: PARTICIPANT_ID }
      ];
    }
  };

  context.subscriptions.push(participant);
  return participant;
}

async function runWizardFlow(
  templateType: 'rest-basic' | 'rest-request-builder' | undefined,
  stream: vscode.ChatResponseStream
): Promise<vscode.ChatResult> {
  stream.progress('Starting PocketHive scenario wizard...');

  const result = await vscode.commands.executeCommand<ScenarioWizardResult | undefined>(
    'pockethive.createScenarioWizard',
    templateType
  );

  if (!result) {
    stream.markdown('Scenario wizard cancelled.');
    return {
      metadata: {
        kind: 'wizard-cancelled'
      }
    };
  }

  const scenarioUri = vscode.Uri.file(result.scenarioFilePath);
  const exportUri = vscode.Uri.file(result.exportPath);
  const bundleName = basename(result.exportPath);

  stream.markdown(
    [
      `Created a ${result.templateType} scenario bundle.`,
      '',
      `Export path: \`${result.exportPath}\``
    ].join('\n')
  );
  stream.reference(scenarioUri);
  stream.button({
    command: 'vscode.open',
    title: 'Open scenario.yaml',
    arguments: [scenarioUri]
  });
  stream.button({
    command: 'revealFileInOS',
    title: 'Reveal bundle folder',
    arguments: [exportUri]
  });
  stream.filetree(
    [
      {
        name: bundleName,
        children: [
          { name: 'scenario.yaml' },
          ...(result.templateType === 'rest-request-builder'
            ? [
                {
                  name: 'templates',
                  children: [
                    {
                      name: 'http',
                      children: [{ name: '*.yaml' }]
                    }
                  ]
                }
              ]
            : [])
        ]
      }
    ],
    exportUri
  );

  return {
    metadata: {
      kind: 'wizard-complete',
      templateType: result.templateType
    }
  };
}

function showTutorial(stream: vscode.ChatResponseStream): vscode.ChatResult {
  stream.markdown(
    [
      '# PocketHive Tutorial',
      '',
      'Use this participant when you want a guided way to create a canonical PocketHive scenario bundle.',
      '',
      '## What `@pockethive` actually does',
      '',
      '1. `@pockethive` is the chat entry point contributed by the PocketHive VS Code extension.',
      '2. It launches the extension wizard command.',
      '3. The wizard collects the required scenario inputs.',
      '4. The extension calls the local scenario-builder backend.',
      '5. The backend exports a canonical scenario bundle into your workspace.',
      '',
      '## Main commands',
      '',
      '- `/wizard`: choose the template during the flow.',
      '- `/restBasic`: create `generator -> processor -> postprocessor`.',
      '- `/requestBuilder`: create `generator -> request-builder -> processor -> postprocessor`.',
      '- `/fromEvidence`: start an AI-assisted evidence session from Cucumber, Java, Postman, or notes.',
      '- `/addEvidence`: attach more files to the active evidence session and re-analyze.',
      '',
      '## Recommended first test',
      '',
      'Create a small REST Basic scenario with:',
      '',
      '- `scenarioId`: `demo-rest-basic`',
      '- `baseUrl`: `{{ sut.endpoints[\'default\'].baseUrl }}/api`',
      '- `ratePerSec`: `10`',
      '- `plan.swarm[0].time`: `PT60S`',
      '- `path`: `/test`',
      '',
      'Export it to `scenarios/bundles/demo-rest-basic` and inspect the generated `scenario.yaml`.',
      '',
      '## Output shape',
      '',
      '- REST Basic exports `scenario.yaml`',
      '- REST + Request Builder exports `scenario.yaml` and `templates/http/*.yaml`',
      '',
      '## Discussion doc',
      '',
      'If you need the architecture overview and diagram for team discussion, open the architecture doc.'
    ].join('\n')
  );
  stream.button({
    command: 'pockethive.createScenarioWizard',
    title: 'Start Wizard'
  });
  stream.button({
    command: 'pockethive.createScenarioWizard',
    title: 'REST + Request Builder',
    arguments: ['rest-request-builder']
  });
  stream.button({
    command: 'pockethive.openActiveEvidenceSession',
    title: 'Open Evidence Session'
  });
  stream.button({
    command: 'pockethive.openScenarioBuilderPocDoc',
    title: 'Open Architecture Doc'
  });

  return {
    metadata: {
      kind: 'wizard-tutorial'
    }
  };
}

async function startEvidenceFlow(
  request: vscode.ChatRequest,
  stream: vscode.ChatResponseStream,
  token: vscode.CancellationToken
): Promise<vscode.ChatResult> {
  const workspaceFolder = pickWorkspaceFolder();
  if (!workspaceFolder) {
    stream.markdown('Open the PocketHive repo root as a workspace before starting an evidence session.');
    return {
      metadata: {
        kind: 'evidence-no-workspace'
      }
    };
  }

  const goal = request.prompt.trim() || (await vscode.window.showInputBox({
    title: 'Scenario goal',
    prompt: 'Describe the scenario you want to derive from evidence',
    ignoreFocusOut: true,
    validateInput: (value) => (value.trim().length === 0 ? 'Value is required.' : null)
  }));
  if (!goal) {
    stream.markdown('Evidence session cancelled.');
    return {
      metadata: {
        kind: 'evidence-cancelled'
      }
    };
  }

  const artifactPaths = await pickEvidenceArtifacts(workspaceFolder);
  if (!artifactPaths || artifactPaths.length === 0) {
    stream.markdown('No evidence files were selected.');
    return {
      metadata: {
        kind: 'evidence-cancelled'
      }
    };
  }

  stream.progress('Creating evidence session...');
  let session = await startEvidenceSession(workspaceFolder.uri.fsPath, goal, artifactPaths);
  stream.progress('Analyzing evidence with the current Copilot model...');
  session = await analyzeEvidenceSession(session, request.model, token);
  return renderEvidenceResult(stream, session);
}

async function addEvidenceFlow(
  request: vscode.ChatRequest,
  stream: vscode.ChatResponseStream,
  token: vscode.CancellationToken
): Promise<vscode.ChatResult> {
  const workspaceFolder = pickWorkspaceFolder();
  if (!workspaceFolder) {
    stream.markdown('Open the PocketHive repo root as a workspace before updating evidence.');
    return {
      metadata: {
        kind: 'evidence-no-workspace'
      }
    };
  }

  let session = getActiveEvidenceSession(workspaceFolder.uri.fsPath);
  if (!session) {
    stream.markdown('No active evidence session. Start one with `/fromEvidence`.');
    return {
      metadata: {
        kind: 'evidence-missing-session'
      }
    };
  }

  const artifactPaths = await pickEvidenceArtifacts(workspaceFolder);
  if (!artifactPaths || artifactPaths.length === 0) {
    stream.markdown('No additional evidence files were selected.');
    return {
      metadata: {
        kind: 'evidence-cancelled'
      }
    };
  }

  session = addArtifacts(session, artifactPaths);
  if (request.prompt.trim().length > 0) {
    session = addNote(session, request.prompt.trim());
  }

  stream.progress('Re-analyzing updated evidence...');
  session = await analyzeEvidenceSession(session, request.model, token);
  return renderEvidenceResult(stream, session);
}

async function continueEvidenceFlow(
  request: vscode.ChatRequest,
  stream: vscode.ChatResponseStream,
  token: vscode.CancellationToken
): Promise<vscode.ChatResult> {
  const workspaceFolder = pickWorkspaceFolder();
  if (!workspaceFolder) {
    return {
      metadata: {
        kind: 'evidence-no-workspace'
      }
    };
  }

  let session = getActiveEvidenceSession(workspaceFolder.uri.fsPath);
  if (!session) {
    return {
      metadata: {
        kind: 'evidence-missing-session'
      }
    };
  }

  const note = request.prompt.trim();
  if (note.length === 0) {
    stream.markdown('There is an active evidence session. Use `/addEvidence` to attach files or type a note/answer to re-analyze.');
    return {
      metadata: {
        kind: 'evidence-analysis'
      }
    };
  }

  const explicitChoice = parseEvidenceNextStepChoice(note, session.lastAnalysis);
  if (explicitChoice === 'add-evidence') {
    return addEvidenceArtifactsToSession(session, stream, request.model, token);
  }
  if (explicitChoice === 'answer-blocking') {
    stream.markdown(
      [
        'Reply with the missing detail as a normal chat message and I will re-analyze the active evidence session.',
        '',
        'If you want to attach files instead, use `/addEvidence`.'
      ].join('\n')
    );
    return {
      metadata: {
        kind: 'evidence-analysis'
      }
    };
  }
  if (explicitChoice === 'proceed-scaffold') {
    return renderProceedWithScaffold(stream, session);
  }

  session = addNote(session, note);

  stream.progress('Re-analyzing the active evidence session...');
  session = await analyzeEvidenceSession(session, request.model, token);
  return renderEvidenceResult(stream, session);
}

function renderEvidenceResult(stream: vscode.ChatResponseStream, session: EvidenceSession): vscode.ChatResult {
  const analysis = session.lastAnalysis;
  if (!analysis) {
    stream.markdown('Evidence session exists, but no analysis result is available yet.');
    return {
      metadata: {
        kind: 'evidence-analysis'
      }
    };
  }

  const refs = getSessionFileReferences(session);
  stream.markdown(
    [
      `# Evidence Session ${session.sessionId}`,
      '',
      `Status: \`${analysis.status}\``,
      `Recommended pattern: \`${analysis.recommendedPattern}\``,
      '',
      '## Known facts',
      ...formatList(analysis.knownFacts),
      '',
      '## Missing evidence',
      ...formatList(analysis.missingEvidence),
      '',
      '## Blocking questions',
      ...formatList(analysis.blockingQuestions),
      '',
      '## Rationale',
      analysis.rationale,
      ...renderNextStepOptions(analysis)
    ].join('\n')
  );
  stream.reference(refs.sessionUri);
  stream.reference(refs.eventsUri);
  stream.button({
    command: 'pockethive.openActiveEvidenceSession',
    title: 'Open Session JSON'
  });
  stream.button({
    command: 'pockethive.openScenarioBuilderPocDoc',
    title: 'Open Architecture Doc'
  });

  return {
    metadata: {
      kind: 'evidence-analysis',
      status: analysis.status
    }
  };
}

function renderProceedWithScaffold(
  stream: vscode.ChatResponseStream,
  session: EvidenceSession
): vscode.ChatResult {
  const analysis = session.lastAnalysis;
  const refs = getSessionFileReferences(session);

  if (!analysis) {
    stream.markdown('This evidence session has no analysis yet. Start with `/fromEvidence` or add more evidence first.');
    return {
      metadata: {
        kind: 'evidence-analysis'
      }
    };
  }

  const templateType = mapEvidencePatternToWizardTemplate(analysis.recommendedPattern);
  if (!templateType) {
    stream.markdown(
      [
        'You chose to proceed with scaffold creation, but the current evidence does not map to a supported wizard template.',
        '',
        `Recommended pattern from the analysis: \`${analysis.recommendedPattern}\``,
        '',
        'Use `/wizard` to choose a template manually, or add more evidence so the session maps to `rest-basic` or `request-builder`.'
      ].join('\n')
    );
    stream.reference(refs.sessionUri);
    stream.button({
      command: 'pockethive.createScenarioWizard',
      title: 'Start Wizard'
    });
    stream.button({
      command: 'pockethive.openActiveEvidenceSession',
      title: 'Open Session JSON'
    });
    return {
      metadata: {
        kind: 'evidence-proceed-unsupported',
        recommendedPattern: analysis.recommendedPattern
      }
    };
  }

  const slashCommand = templateType === 'rest-basic' ? '/restBasic' : '/requestBuilder';
  const buttonTitle = templateType === 'rest-basic' ? 'Create REST Basic Scenario' : 'Create REST + Request Builder Scenario';

  stream.markdown(
    [
      'You chose to proceed with scaffold creation.',
      '',
      `Current evidence is enough to scaffold a \`${templateType}\` scenario, even though the response-contract details are incomplete.`,
      '',
      `Run \`${slashCommand}\` and follow the prompts to set the SUT endpoint, traffic profile, and request/template, then validate, preview, and export.`
    ].join('\n')
  );
  stream.reference(refs.sessionUri);
  stream.button({
    command: 'pockethive.createScenarioWizard',
    title: buttonTitle,
    arguments: [templateType]
  });
  stream.button({
    command: 'pockethive.openActiveEvidenceSession',
    title: 'Open Session JSON'
  });
  stream.button({
    command: 'pockethive.openScenarioBuilderPocDoc',
    title: 'Open Architecture Doc'
  });

  return {
    metadata: {
      kind: 'evidence-proceed',
      templateType
    }
  };
}

function formatList(items: string[]): string[] {
  return items.length > 0 ? items.map((item) => `- ${item}`) : ['- (none)'];
}

function renderNextStepOptions(analysis: EvidenceSession['lastAnalysis']): string[] {
  const options = getEvidenceNextStepOptions(analysis);
  if (options.length === 0) {
    return [];
  }

  return [
    '',
    '## Next Step',
    'Reply with `@pockethive <number>` to choose an option, or `@pockethive <details>` to add missing information for re-analysis.',
    ...options.map((option, index) => `${index + 1}. ${option.description} Reply with \`${index + 1}\`.`)
  ];
}

function getEvidenceNextStepOptions(
  analysis: EvidenceSession['lastAnalysis']
): EvidenceNextStepOption[] {
  if (!analysis) {
    return [];
  }

  const options: EvidenceNextStepOption[] = [];
  const hasEvidenceGaps = analysis.missingEvidence.length > 0;
  const hasBlockingQuestions = analysis.blockingQuestions.length > 0;
  const templateType = mapEvidencePatternToWizardTemplate(analysis.recommendedPattern);

  if (hasEvidenceGaps || analysis.status === 'need_more_evidence') {
    options.push({
      choice: 'add-evidence',
      description: 'Add more evidence.'
    });
  }

  if (hasBlockingQuestions) {
    options.push({
      choice: 'answer-blocking',
      description: 'Answer the blocking questions in chat, then send the missing details in your next message.'
    });
  }

  if (templateType) {
    options.push({
      choice: 'proceed-scaffold',
      description:
        analysis.status === 'ready_to_generate'
          ? `Create the ${templateType} scaffold now.`
          : 'Proceed with scaffold creation despite the remaining evidence gaps.'
    });
  }

  return options;
}

function mapEvidencePatternToWizardTemplate(
  pattern: string
): 'rest-basic' | 'rest-request-builder' | undefined {
  if (pattern === 'rest-basic') {
    return 'rest-basic';
  }
  if (pattern === 'request-builder') {
    return 'rest-request-builder';
  }
  return undefined;
}

function parseEvidenceNextStepChoice(
  note: string,
  analysis: EvidenceSession['lastAnalysis']
): EvidenceNextStepChoice | undefined {
  const normalized = note.trim();
  const match = normalized.match(/^(\d+)[.)]?$/);
  if (!match) {
    return undefined;
  }

  const index = Number.parseInt(match[1], 10) - 1;
  if (!Number.isInteger(index) || index < 0) {
    return undefined;
  }

  const options = getEvidenceNextStepOptions(analysis);
  return options[index]?.choice;
}

async function addEvidenceArtifactsToSession(
  session: EvidenceSession,
  stream: vscode.ChatResponseStream,
  model: vscode.LanguageModelChat,
  token: vscode.CancellationToken
): Promise<vscode.ChatResult> {
  const workspaceFolder = pickWorkspaceFolder();
  if (!workspaceFolder) {
    stream.markdown('Open the PocketHive repo root as a workspace before updating evidence.');
    return {
      metadata: {
        kind: 'evidence-no-workspace'
      }
    };
  }

  const artifactPaths = await pickEvidenceArtifacts(workspaceFolder);
  if (!artifactPaths || artifactPaths.length === 0) {
    stream.markdown('No additional evidence files were selected.');
    return {
      metadata: {
        kind: 'evidence-cancelled'
      }
    };
  }

  let nextSession = addArtifacts(session, artifactPaths);
  stream.progress('Re-analyzing updated evidence...');
  nextSession = await analyzeEvidenceSession(nextSession, model, token);
  return renderEvidenceResult(stream, nextSession);
}

function hasActiveEvidenceSession(): boolean {
  const workspaceFolder = pickWorkspaceFolder();
  return workspaceFolder ? getActiveEvidenceSession(workspaceFolder.uri.fsPath) !== null : false;
}

function pickWorkspaceFolder(): vscode.WorkspaceFolder | undefined {
  const folders = vscode.workspace.workspaceFolders;
  if (!folders || folders.length === 0) {
    return undefined;
  }
  return folders[0];
}

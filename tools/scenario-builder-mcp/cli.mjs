#!/usr/bin/env node

import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import {
  TEMPLATE_TYPES,
  WIZARD_STEP,
  applyWizardStep,
  createWizardSession,
  exportWizardSession,
  previewSessionBundle,
  validateWizardSession
} from "./scenario-builder-core.mjs";

const command = process.argv[2];

try {
  if (command === "create-from-wizard") {
    const payload = JSON.parse(readFileSync(0, "utf8"));
    const result = createFromWizard(payload);
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
    process.exit(0);
  }

  throw new Error(`Unsupported command '${command ?? ""}'.`);
} catch (error) {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exit(1);
}

function createFromWizard(payload) {
  const session = createWizardSession({
    sessionId: `sess-${randomUUID()}`,
    workspacePath: payload.workspacePath,
    scenarioId: payload.scenarioId,
    scenarioName: payload.scenarioName,
    description: payload.description,
    templateType: payload.templateType
  });

  applyWizardStep(session, WIZARD_STEP.PROCESSOR_CONFIG, {
    config: payload.processor.config
  });
  applyWizardStep(session, WIZARD_STEP.GENERATOR_INPUTS, {
    inputs: payload.generator.config.inputs
  });
  applyWizardStep(session, WIZARD_STEP.PLAN_SWARM, {
    plan: payload.plan
  });

  if (payload.templateType === TEMPLATE_TYPES.REST_REQUEST_BUILDER) {
    applyWizardStep(session, WIZARD_STEP.REQUEST_BUILDER_CONFIG, {
      requestBuilder: payload.requestBuilder
    });
  }

  applyWizardStep(session, WIZARD_STEP.GENERATOR_WORKER_MESSAGE, {
    worker: payload.generator.config.worker
  });

  if (payload.templateType === TEMPLATE_TYPES.REST_REQUEST_BUILDER) {
    applyWizardStep(session, WIZARD_STEP.TEMPLATE_HTTP_PUT, {
      template: payload.templateHttp
    });
  } else if (payload.templateType !== TEMPLATE_TYPES.REST_BASIC) {
    throw new Error(`Unsupported templateType '${payload.templateType}'.`);
  }

  const validation = validateWizardSession(session);
  if (!validation.ok) {
    return {
      ok: false,
      validation,
      preview: previewSessionBundle(session)
    };
  }

  return {
    ok: true,
    export: exportWizardSession(session, payload.exportPath),
    validation,
    preview: previewSessionBundle(session)
  };
}

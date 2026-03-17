#!/usr/bin/env node

import { randomUUID } from "node:crypto";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import {
  TEMPLATE_TYPES,
  WIZARD_STEP,
  applyWizardStep,
  createWizardSession,
  discardWizardSession,
  exportWizardSession,
  getWizardState,
  previewSessionBundle,
  validateWizardSession
} from "./scenario-builder-core.mjs";

const sessions = new Map();

const server = new McpServer({
  name: "pockethive-scenario-builder",
  version: "0.1.0"
});

server.registerTool(
  "session.start",
  {
    title: "Start PocketHive scenario wizard",
    description: "Create a working-copy scenario bundle for the guided wizard flow.",
    inputSchema: {
      workspacePath: z.string(),
      scenarioId: z.string(),
      scenarioName: z.string(),
      description: z.string().optional(),
      templateType: z.enum([TEMPLATE_TYPES.REST_BASIC, TEMPLATE_TYPES.REST_REQUEST_BUILDER])
    }
  },
  async ({ workspacePath, scenarioId, scenarioName, description, templateType }) => {
    const sessionId = `sess-${randomUUID()}`;
    const session = createWizardSession({
      sessionId,
      workspacePath,
      scenarioId,
      scenarioName,
      description,
      templateType
    });
    sessions.set(sessionId, session);
    return jsonResult({
      sessionId,
      state: getWizardState(session),
      preview: previewSessionBundle(session)
    });
  }
);

server.registerTool(
  "session.get_state",
  {
    title: "Get wizard state",
    description: "Inspect the current guided-authoring state for a PocketHive scenario session.",
    inputSchema: {
      sessionId: z.string()
    }
  },
  async ({ sessionId }) => {
    const session = requireSession(sessionId);
    return jsonResult({
      sessionId,
      state: getWizardState(session)
    });
  }
);

server.registerTool(
  "processor.set_config",
  {
    title: "Set processor config",
    description: "Set canonical processor config for the current session.",
    inputSchema: {
      sessionId: z.string(),
      config: z.object({
        baseUrl: z.string()
      })
    }
  },
  async ({ sessionId, config }) => {
    return updateWizardStep(sessionId, WIZARD_STEP.PROCESSOR_CONFIG, { config });
  }
);

server.registerTool(
  "generator.set_inputs",
  {
    title: "Set generator inputs",
    description: "Set canonical generator config.inputs for the current session.",
    inputSchema: {
      sessionId: z.string(),
      inputs: z.object({
        type: z.literal("SCHEDULER"),
        scheduler: z.object({
          ratePerSec: z.number().positive()
        })
      })
    }
  },
  async ({ sessionId, inputs }) => {
    return updateWizardStep(sessionId, WIZARD_STEP.GENERATOR_INPUTS, { inputs });
  }
);

server.registerTool(
  "plan.set_swarm",
  {
    title: "Set plan.swarm",
    description: "Set canonical plan.swarm steps for the current session.",
    inputSchema: {
      sessionId: z.string(),
      plan: z.object({
        swarm: z
          .array(
            z.object({
              stepId: z.string(),
              name: z.string(),
              time: z.string(),
              type: z.literal("stop")
            })
          )
          .min(1)
      })
    }
  },
  async ({ sessionId, plan }) => {
    return updateWizardStep(sessionId, WIZARD_STEP.PLAN_SWARM, { plan });
  }
);

server.registerTool(
  "generator.set_worker_message",
  {
    title: "Set generator worker.message",
    description: "Set canonical generator config.worker.message for the current session.",
    inputSchema: {
      sessionId: z.string(),
      worker: z.object({
        message: z.object({
          bodyType: z.literal("HTTP"),
          method: z.enum(["GET", "POST", "PUT", "PATCH", "DELETE"]),
          path: z.string(),
          body: z.string(),
          headers: z.record(z.string()).optional()
        })
      })
    }
  },
  async ({ sessionId, worker }) => {
    return updateWizardStep(sessionId, WIZARD_STEP.GENERATOR_WORKER_MESSAGE, { worker });
  }
);

server.registerTool(
  "request_builder.set_config",
  {
    title: "Set request-builder config",
    description: "Set canonical request-builder config for the current session.",
    inputSchema: {
      sessionId: z.string(),
      requestBuilder: z.object({
        config: z.object({
          templateRoot: z.string(),
          serviceId: z.string()
        })
      })
    }
  },
  async ({ sessionId, requestBuilder }) => {
    return updateWizardStep(sessionId, WIZARD_STEP.REQUEST_BUILDER_CONFIG, { requestBuilder });
  }
);

server.registerTool(
  "template_http.put",
  {
    title: "Put HTTP template file",
    description: "Set canonical content for one templates/http/*.yaml file in the current session.",
    inputSchema: {
      sessionId: z.string(),
      template: z.object({
        serviceId: z.string(),
        callId: z.string(),
        protocol: z.literal("HTTP"),
        method: z.enum(["GET", "POST", "PUT", "PATCH", "DELETE"]),
        pathTemplate: z.string(),
        bodyTemplate: z.string(),
        headersTemplate: z.record(z.string()).optional()
      })
    }
  },
  async ({ sessionId, template }) => {
    return updateWizardStep(sessionId, WIZARD_STEP.TEMPLATE_HTTP_PUT, {
      template
    });
  }
);

server.registerTool(
  "bundle.preview",
  {
    title: "Preview generated scenario bundle",
    description: "Return the current canonical scenario.yaml and template files for a wizard session.",
    inputSchema: {
      sessionId: z.string()
    }
  },
  async ({ sessionId }) => {
    const session = requireSession(sessionId);
    return jsonResult({
      sessionId,
      preview: previewSessionBundle(session),
      state: getWizardState(session)
    });
  }
);

server.registerTool(
  "bundle.validate",
  {
    title: "Validate scenario session",
    description: "Validate the current working-copy bundle against the canonical authoring contract.",
    inputSchema: {
      sessionId: z.string()
    }
  },
  async ({ sessionId }) => {
    const session = requireSession(sessionId);
    const validation = validateWizardSession(session);
    return jsonResult({
      sessionId,
      validation,
      state: getWizardState(session)
    });
  }
);

server.registerTool(
  "bundle.export",
  {
    title: "Export scenario bundle",
    description: "Write the current canonical scenario bundle to an explicit target directory.",
    inputSchema: {
      sessionId: z.string(),
      targetPath: z.string()
    }
  },
  async ({ sessionId, targetPath }) => {
    const session = requireSession(sessionId);
    const validation = validateWizardSession(session);
    if (!validation.ok) {
      throw new Error("Scenario session has validation errors. Run bundle.validate and fix them first.");
    }
    const exportResult = exportWizardSession(session, targetPath);
    return jsonResult({
      sessionId,
      export: exportResult,
      state: getWizardState(session)
    });
  }
);

server.registerTool(
  "session.discard",
  {
    title: "Discard wizard session",
    description: "Delete the local session working copy without exporting a bundle.",
    inputSchema: {
      sessionId: z.string()
    }
  },
  async ({ sessionId }) => {
    const session = requireSession(sessionId);
    discardWizardSession(session);
    sessions.delete(sessionId);
    return jsonResult({
      sessionId,
      discarded: true
    });
  }
);

const transport = new StdioServerTransport();
await server.connect(transport);

function requireSession(sessionId) {
  const session = sessions.get(sessionId);
  if (!session) {
    throw new Error(`Unknown session '${sessionId}'. Start a new wizard session first.`);
  }
  return session;
}

function updateWizardStep(sessionId, stepId, input) {
  const session = requireSession(sessionId);
  const state = applyWizardStep(session, stepId, input);
  return jsonResult({
    sessionId,
    state,
    preview: previewSessionBundle(session)
  });
}

function jsonResult(payload) {
  return {
    content: [
      {
        type: "text",
        text: JSON.stringify(payload, null, 2)
      }
    ],
    structuredContent: payload
  };
}

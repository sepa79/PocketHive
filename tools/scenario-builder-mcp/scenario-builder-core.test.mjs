import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import {
  TEMPLATE_TYPES,
  WIZARD_STEP,
  applyWizardStep,
  createWizardSession,
  exportWizardSession,
  previewSessionBundle,
  validateWizardSession
} from "./scenario-builder-core.mjs";

const workdirs = [];

afterEach(() => {
  for (const workdir of workdirs.splice(0)) {
    rmSync(workdir, { recursive: true, force: true });
  }
});

describe("scenario builder core", () => {
  it("builds and exports a valid rest-basic scenario bundle", () => {
    const workspacePath = createWorkspace();
    const session = createWizardSession({
      sessionId: "sess-basic",
      workspacePath,
      scenarioId: "demo-rest-basic",
      scenarioName: "Demo REST Basic",
      description: "Simple direct REST flow",
      templateType: TEMPLATE_TYPES.REST_BASIC
    });

    applyWizardStep(session, WIZARD_STEP.PROCESSOR_CONFIG, {
      config: {
        baseUrl: "{{ sut.endpoints['default'].baseUrl }}/api"
      }
    });
    applyWizardStep(session, WIZARD_STEP.GENERATOR_INPUTS, {
      inputs: {
        type: "SCHEDULER",
        scheduler: {
          ratePerSec: 25
        }
      }
    });
    applyWizardStep(session, WIZARD_STEP.PLAN_SWARM, {
      plan: {
        swarm: [
          {
            stepId: "swarm-stop",
            name: "Stop generated swarm",
            time: "PT2M",
            type: "stop"
          }
        ]
      }
    });
    applyWizardStep(session, WIZARD_STEP.GENERATOR_WORKER_MESSAGE, {
      worker: {
        message: {
          bodyType: "HTTP",
          method: "POST",
          path: "/payments",
          body: "{\"amount\":100}",
          headers: {
            "content-type": "application/json"
          }
        }
      }
    });

    const validation = validateWizardSession(session);
    expect(validation.ok).toBe(true);

    const preview = previewSessionBundle(session);
    expect(preview.scenarioYaml).toContain("id: demo-rest-basic");
    expect(preview.scenarioYaml).toContain("path: /payments");

    const targetPath = resolve(workspacePath, "scenarios", "bundles", "demo-rest-basic");
    const exportResult = exportWizardSession(session, targetPath);

    expect(exportResult.files).toContain("scenario.yaml");
    const exportedScenario = readFileSync(resolve(targetPath, "scenario.yaml"), "utf8");
    expect(exportedScenario).toContain("ratePerSec: 25");
  });

  it("builds and exports a valid rest-request-builder scenario bundle", () => {
    const workspacePath = createWorkspace();
    const session = createWizardSession({
      sessionId: "sess-rb",
      workspacePath,
      scenarioId: "demo-request-builder",
      scenarioName: "Demo Request Builder",
      description: "REST flow with request builder",
      templateType: TEMPLATE_TYPES.REST_REQUEST_BUILDER
    });

    applyWizardStep(session, WIZARD_STEP.PROCESSOR_CONFIG, {
      config: {
        baseUrl: "{{ sut.endpoints['default'].baseUrl }}"
      }
    });
    applyWizardStep(session, WIZARD_STEP.GENERATOR_INPUTS, {
      inputs: {
        type: "SCHEDULER",
        scheduler: {
          ratePerSec: 10
        }
      }
    });
    applyWizardStep(session, WIZARD_STEP.PLAN_SWARM, {
      plan: {
        swarm: [
          {
            stepId: "swarm-stop",
            name: "Stop generated swarm",
            time: "PT90S",
            type: "stop"
          }
        ]
      }
    });
    applyWizardStep(session, WIZARD_STEP.REQUEST_BUILDER_CONFIG, {
      requestBuilder: {
        config: {
          templateRoot: "/app/scenario/templates/http",
          serviceId: "default"
        }
      }
    });
    applyWizardStep(session, WIZARD_STEP.GENERATOR_WORKER_MESSAGE, {
      worker: {
        message: {
          bodyType: "SIMPLE",
          body: "{\"customer\":\"A\"}",
          headers: {
            "content-type": "application/json",
            "x-ph-call-id": "auth-call"
          }
        }
      }
    });
    applyWizardStep(session, WIZARD_STEP.TEMPLATE_HTTP_PUT, {
      template: {
        serviceId: "default",
        callId: "auth-call",
        protocol: "HTTP",
        method: "POST",
        pathTemplate: "/api/login",
        bodyTemplate: "{{ payload }}",
        headersTemplate: {
          "content-type": "application/json"
        }
      }
    });

    const validation = validateWizardSession(session);
    expect(validation.ok).toBe(true);

    const targetPath = resolve(workspacePath, "scenarios", "bundles", "demo-request-builder");
    exportWizardSession(session, targetPath);

    const exportedTemplate = readFileSync(resolve(targetPath, "templates/http/auth-call.yaml"), "utf8");
    expect(exportedTemplate).toContain("callId: auth-call");
    expect(exportedTemplate).toContain("pathTemplate: /api/login");
  });
});

function createWorkspace() {
  const workspacePath = mkdtempSync(resolve(tmpdir(), "pockethive-scenario-builder-"));
  workdirs.push(workspacePath);
  return workspacePath;
}

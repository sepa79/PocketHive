import { existsSync, mkdirSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { dirname, isAbsolute, resolve } from "node:path";
import YAML from "yaml";

const TEMPLATE_TYPES = Object.freeze({
  REST_BASIC: "rest-basic",
  REST_REQUEST_BUILDER: "rest-request-builder"
});

const WIZARD_STEP = Object.freeze({
  PROCESSOR_CONFIG: "processor_config",
  GENERATOR_INPUTS: "generator_inputs",
  PLAN_SWARM: "plan_swarm",
  GENERATOR_WORKER_MESSAGE: "generator_worker_message",
  REQUEST_BUILDER_CONFIG: "request_builder_config",
  TEMPLATE_HTTP_PUT: "template_http_put"
});

const ROLE = Object.freeze({
  GENERATOR: "generator",
  REQUEST_BUILDER: "request-builder",
  PROCESSOR: "processor",
  POSTPROCESSOR: "postprocessor"
});

const IMAGE = Object.freeze({
  SWARM_CONTROLLER: "swarm-controller:latest",
  GENERATOR: "generator:latest",
  REQUEST_BUILDER: "request-builder:latest",
  PROCESSOR: "processor:latest",
  POSTPROCESSOR: "postprocessor:latest"
});

const DEFAULT_TEMPLATE_ROOT = "/app/scenario/templates/http";

const WIZARD_STEPS_BY_TEMPLATE = Object.freeze({
  [TEMPLATE_TYPES.REST_BASIC]: [
    WIZARD_STEP.PROCESSOR_CONFIG,
    WIZARD_STEP.GENERATOR_INPUTS,
    WIZARD_STEP.PLAN_SWARM,
    WIZARD_STEP.GENERATOR_WORKER_MESSAGE
  ],
  [TEMPLATE_TYPES.REST_REQUEST_BUILDER]: [
    WIZARD_STEP.PROCESSOR_CONFIG,
    WIZARD_STEP.GENERATOR_INPUTS,
    WIZARD_STEP.PLAN_SWARM,
    WIZARD_STEP.REQUEST_BUILDER_CONFIG,
    WIZARD_STEP.GENERATOR_WORKER_MESSAGE,
    WIZARD_STEP.TEMPLATE_HTTP_PUT
  ]
});

export { TEMPLATE_TYPES, WIZARD_STEP, WIZARD_STEPS_BY_TEMPLATE };

export function createWizardSession({
  sessionId,
  workspacePath,
  scenarioId,
  scenarioName,
  description,
  templateType
}) {
  assertAbsoluteWorkspacePath(workspacePath);
  assertSupportedTemplateType(templateType);
  assertNonEmpty("sessionId", sessionId);
  assertNonEmpty("scenarioId", scenarioId);
  assertNonEmpty("scenarioName", scenarioName);

  const bundle = createBundleTemplate({
    scenarioId,
    scenarioName,
    description,
    templateType
  });

  const session = {
    sessionId,
    workspacePath,
    sessionPath: resolve(workspacePath, ".pockethive-mcp", "sessions", sessionId),
    templateType,
    wizard: {
      steps: WIZARD_STEPS_BY_TEMPLATE[templateType],
      completedSteps: []
    },
    bundle,
    templates: {
      http: {}
    },
    summary: []
  };

  persistSessionSnapshot(session);
  return session;
}

export function getWizardState(session) {
  const nextStepId = getNextStepId(session);
  return {
    sessionId: session.sessionId,
    templateType: session.templateType,
    workspacePath: session.workspacePath,
    sessionPath: session.sessionPath,
    completedSteps: [...session.wizard.completedSteps],
    nextStepId,
    remainingSteps: session.wizard.steps.filter((stepId) => !session.wizard.completedSteps.includes(stepId)),
    summary: [...session.summary]
  };
}

export function applyWizardStep(session, stepId, input) {
  const expectedStep = getNextStepId(session);
  if (!expectedStep) {
    throw new Error("This wizard session is already complete.");
  }
  if (expectedStep !== stepId) {
    throw new Error(`Expected wizard step '${expectedStep}', received '${stepId}'.`);
  }

  switch (stepId) {
    case WIZARD_STEP.PROCESSOR_CONFIG:
      applyProcessorConfigStep(session, input);
      break;
    case WIZARD_STEP.GENERATOR_INPUTS:
      applyGeneratorInputsStep(session, input);
      break;
    case WIZARD_STEP.PLAN_SWARM:
      applyPlanSwarmStep(session, input);
      break;
    case WIZARD_STEP.GENERATOR_WORKER_MESSAGE:
      applyGeneratorWorkerMessageStep(session, input);
      break;
    case WIZARD_STEP.REQUEST_BUILDER_CONFIG:
      applyRequestBuilderConfigStep(session, input);
      break;
    case WIZARD_STEP.TEMPLATE_HTTP_PUT:
      applyTemplateHttpPutStep(session, input);
      break;
    default:
      throw new Error(`Unsupported wizard step '${stepId}'.`);
  }

  session.wizard.completedSteps.push(stepId);
  persistSessionSnapshot(session);
  return getWizardState(session);
}

export function previewSessionBundle(session) {
  return {
    scenarioYaml: renderScenarioYaml(session.bundle),
    files: renderSessionFiles(session)
  };
}

export function validateWizardSession(session) {
  const issues = [];
  const bees = session.bundle?.template?.bees;

  if (!session.bundle?.id) {
    issues.push(validationIssue("SCENARIO_ID_REQUIRED", "error", "id", "Scenario id is required."));
  }
  if (!session.bundle?.name) {
    issues.push(validationIssue("SCENARIO_NAME_REQUIRED", "error", "name", "Scenario name is required."));
  }
  if (!Array.isArray(bees) || bees.length === 0) {
    issues.push(
      validationIssue("BEES_REQUIRED", "error", "template.bees", "Scenario must define at least one bee.")
    );
  }

  const beeIndex = new Map();
  for (const [index, bee] of (bees ?? []).entries()) {
    const path = `template.bees[${index}]`;
    if (!bee.id) {
      issues.push(validationIssue("BEE_ID_REQUIRED", "error", `${path}.id`, "Bee id is required."));
      continue;
    }
    if (beeIndex.has(bee.id)) {
      issues.push(
        validationIssue("BEE_ID_DUPLICATE", "error", `${path}.id`, `Bee id '${bee.id}' must be unique.`)
      );
    }
    beeIndex.set(bee.id, bee);
    if (!bee.role) {
      issues.push(validationIssue("BEE_ROLE_REQUIRED", "error", `${path}.role`, "Bee role is required."));
    }
    if (!bee.image) {
      issues.push(validationIssue("BEE_IMAGE_REQUIRED", "error", `${path}.image`, "Bee image is required."));
    }
    if (!bee.work || typeof bee.work !== "object") {
      issues.push(validationIssue("BEE_WORK_REQUIRED", "error", `${path}.work`, "Bee work is required."));
    }

    const portIds = new Set((bee.ports ?? []).map((port) => port.id));
    for (const direction of ["in", "out"]) {
      const workMap = bee.work?.[direction] ?? {};
      for (const workPortId of Object.keys(workMap)) {
        if (!portIds.has(workPortId)) {
          issues.push(
            validationIssue(
              "WORK_PORT_UNDECLARED",
              "error",
              `${path}.work.${direction}.${workPortId}`,
              `Work port '${workPortId}' is not declared in ports[].`
            )
          );
        }
      }
    }
  }

  const edges = session.bundle?.topology?.edges ?? [];
  for (const [index, edge] of edges.entries()) {
    if (!beeIndex.has(edge.from?.beeId)) {
      issues.push(
        validationIssue(
          "EDGE_SOURCE_UNKNOWN",
          "error",
          `topology.edges[${index}].from.beeId`,
          `Unknown source bee '${edge.from?.beeId}'.`
        )
      );
    }
    if (!beeIndex.has(edge.to?.beeId)) {
      issues.push(
        validationIssue(
          "EDGE_TARGET_UNKNOWN",
          "error",
          `topology.edges[${index}].to.beeId`,
          `Unknown target bee '${edge.to?.beeId}'.`
        )
      );
    }
  }

  const generator = findBeeByRole(session, ROLE.GENERATOR);
  if (!generator?.config?.inputs?.scheduler?.ratePerSec) {
    issues.push(
      validationIssue(
        "GENERATOR_RATE_REQUIRED",
        "warning",
        "template.bees[generator].config.inputs.scheduler.ratePerSec",
        "Generator should define a scheduler rate."
      )
    );
  }

  const processor = findBeeByRole(session, ROLE.PROCESSOR);
  if (!processor?.config?.baseUrl) {
    issues.push(
      validationIssue(
        "PROCESSOR_BASE_URL_REQUIRED",
        "error",
        "template.bees[processor].config.baseUrl",
        "Processor baseUrl is required for REST wizard flows."
      )
    );
  }

  if (session.templateType === TEMPLATE_TYPES.REST_REQUEST_BUILDER) {
    const templateNames = Object.keys(session.templates.http);
    if (templateNames.length === 0) {
      issues.push(
        validationIssue(
          "HTTP_TEMPLATE_REQUIRED",
          "error",
          "templates.http",
          "Request-builder scenarios require at least one HTTP template."
        )
      );
    }
    const requestBuilder = findBeeByRole(session, ROLE.REQUEST_BUILDER);
    if (!requestBuilder?.config?.templateRoot || !requestBuilder?.config?.serviceId) {
      issues.push(
        validationIssue(
          "REQUEST_BUILDER_CONFIG_REQUIRED",
          "error",
          "template.bees[request-builder].config",
          "Request-builder requires templateRoot and serviceId."
        )
      );
    }
  }

  return {
    ok: issues.every((issue) => issue.severity !== "error"),
    issues
  };
}

export function exportWizardSession(session, targetPath) {
  if (!isAbsolute(targetPath)) {
    throw new Error("export targetPath must be an absolute path.");
  }

  if (existsSync(targetPath) && readdirSync(targetPath).length > 0) {
    throw new Error("export targetPath must be empty or not exist.");
  }

  const files = renderSessionFiles(session);
  mkdirSync(targetPath, { recursive: true });

  for (const file of files) {
    const filePath = resolve(targetPath, file.path);
    mkdirSync(dirname(filePath), { recursive: true });
    writeFileSync(filePath, file.content, "utf8");
  }

  return {
    targetPath,
    files: files.map((file) => file.path)
  };
}

export function discardWizardSession(session) {
  rmSync(session.sessionPath, { recursive: true, force: true });
}

function createBundleTemplate({ scenarioId, scenarioName, description, templateType }) {
  const base = {
    id: scenarioId,
    name: scenarioName,
    description: description || null,
    template: {
      image: IMAGE.SWARM_CONTROLLER,
      bees: []
    },
    topology: {
      version: 1,
      edges: []
    },
    plan: null
  };

  if (templateType === TEMPLATE_TYPES.REST_BASIC) {
    base.template.bees = [
      createBee({
        id: "genA",
        role: ROLE.GENERATOR,
        image: IMAGE.GENERATOR,
        ports: [createPort("out", "out")],
        work: { out: { out: "genQ" } },
        config: {
          inputs: {
            type: "SCHEDULER",
            scheduler: {
              ratePerSec: 5
            }
          },
          worker: {
            message: {
              bodyType: "HTTP",
              method: "POST",
              path: "/test",
              body: JSON.stringify({ event: scenarioId }),
              headers: {
                "content-type": "application/json"
              }
            }
          }
        }
      }),
      createBee({
        id: "procA",
        role: ROLE.PROCESSOR,
        image: IMAGE.PROCESSOR,
        ports: [createPort("in", "in"), createPort("out", "out")],
        work: {
          in: { in: "genQ" },
          out: { out: "finalQ" }
        },
        config: {
          baseUrl: "{{ sut.endpoints['default'].baseUrl }}"
        }
      }),
      createBee({
        id: "postA",
        role: ROLE.POSTPROCESSOR,
        image: IMAGE.POSTPROCESSOR,
        ports: [createPort("in", "in")],
        work: {
          in: { in: "finalQ" }
        }
      })
    ];
    base.topology.edges = [
      createEdge("e1", "genA", "out", "procA", "in"),
      createEdge("e2", "procA", "out", "postA", "in")
    ];
    return base;
  }

  if (templateType === TEMPLATE_TYPES.REST_REQUEST_BUILDER) {
    base.template.bees = [
      createBee({
        id: "genA",
        role: ROLE.GENERATOR,
        image: IMAGE.GENERATOR,
        ports: [createPort("out", "out")],
        work: { out: { out: "genQ" } },
        config: {
          inputs: {
            type: "SCHEDULER",
            scheduler: {
              ratePerSec: 5
            }
          },
          worker: {
            message: {
              bodyType: "SIMPLE",
              body: JSON.stringify({ event: scenarioId }),
              headers: {
                "content-type": "application/json",
                "x-ph-call-id": "default-call"
              }
            }
          }
        }
      }),
      createBee({
        id: "rbA",
        role: ROLE.REQUEST_BUILDER,
        image: IMAGE.REQUEST_BUILDER,
        ports: [createPort("in", "in"), createPort("out", "out")],
        work: {
          in: { in: "genQ" },
          out: { out: "procQ" }
        },
        config: {
          templateRoot: DEFAULT_TEMPLATE_ROOT,
          serviceId: "default"
        }
      }),
      createBee({
        id: "procA",
        role: ROLE.PROCESSOR,
        image: IMAGE.PROCESSOR,
        ports: [createPort("in", "in"), createPort("out", "out")],
        work: {
          in: { in: "procQ" },
          out: { out: "finalQ" }
        },
        config: {
          baseUrl: "{{ sut.endpoints['default'].baseUrl }}"
        }
      }),
      createBee({
        id: "postA",
        role: ROLE.POSTPROCESSOR,
        image: IMAGE.POSTPROCESSOR,
        ports: [createPort("in", "in")],
        work: {
          in: { in: "finalQ" }
        }
      })
    ];
    base.topology.edges = [
      createEdge("e1", "genA", "out", "rbA", "in"),
      createEdge("e2", "rbA", "out", "procA", "in"),
      createEdge("e3", "procA", "out", "postA", "in")
    ];
    return base;
  }

  throw new Error(`Unsupported templateType '${templateType}'.`);
}

function applyProcessorConfigStep(session, input) {
  assertNonEmpty("config.baseUrl", input.config?.baseUrl);
  const processor = requireBeeByRole(session, ROLE.PROCESSOR);
  processor.config = structuredClone(input.config);
  session.summary.push(`Processor config.baseUrl set to '${input.config.baseUrl}'.`);
}

function applyGeneratorInputsStep(session, input) {
  assertNonEmpty("inputs.type", input.inputs?.type);
  assertPositiveNumber("inputs.scheduler.ratePerSec", input.inputs?.scheduler?.ratePerSec);

  const generator = requireBeeByRole(session, ROLE.GENERATOR);
  generator.config.inputs = structuredClone(input.inputs);
  session.summary.push(`Generator config.inputs.scheduler.ratePerSec set to ${input.inputs.scheduler.ratePerSec}.`);
}

function applyPlanSwarmStep(session, input) {
  assertNonEmpty("plan.swarm[0].time", input.plan?.swarm?.[0]?.time);
  assertNonEmpty("plan.swarm[0].type", input.plan?.swarm?.[0]?.type);
  session.bundle.plan = structuredClone(input.plan);
  session.summary.push(`plan.swarm[0].time set to ${input.plan.swarm[0].time}.`);
}

function applyGeneratorWorkerMessageStep(session, input) {
  assertNonEmpty("worker.message.bodyType", input.worker?.message?.bodyType);
  assertNonEmpty("worker.message.body", input.worker?.message?.body);
  if (input.worker.message.bodyType === "HTTP") {
    assertNonEmpty("worker.message.method", input.worker?.message?.method);
    assertNonEmpty("worker.message.path", input.worker?.message?.path);
  }

  const generator = requireBeeByRole(session, ROLE.GENERATOR);
  generator.config.worker = structuredClone(input.worker);

  session.summary.push(`Generator worker.message.bodyType set to ${input.worker.message.bodyType}.`);
}

function applyRequestBuilderConfigStep(session, input) {
  assertNonEmpty("requestBuilder.config.templateRoot", input.requestBuilder?.config?.templateRoot);
  assertNonEmpty("requestBuilder.config.serviceId", input.requestBuilder?.config?.serviceId);
  const requestBuilder = requireBeeByRole(session, ROLE.REQUEST_BUILDER);
  requestBuilder.config = structuredClone(input.requestBuilder.config);
  session.summary.push(
    `request-builder config.templateRoot set to '${input.requestBuilder.config.templateRoot}'.`
  );
}

function applyTemplateHttpPutStep(session, input) {
  assertNonEmpty("template.callId", input.template?.callId);
  assertNonEmpty("template.serviceId", input.template?.serviceId);
  assertNonEmpty("template.protocol", input.template?.protocol);
  assertNonEmpty("template.method", input.template?.method);
  assertNonEmpty("template.pathTemplate", input.template?.pathTemplate);
  assertNonEmpty("template.bodyTemplate", input.template?.bodyTemplate);
  session.templates.http[`${input.template.callId}.yaml`] = structuredClone(input.template);
  session.summary.push(
    `HTTP template '${input.template.callId}' configured for service '${input.template.serviceId}'.`
  );
}

function findBeeByRole(session, role) {
  return session.bundle.template.bees.find((bee) => bee.role === role) ?? null;
}

function requireBeeByRole(session, role) {
  const bee = findBeeByRole(session, role);
  if (!bee) {
    throw new Error(`Scenario bundle does not contain a '${role}' bee.`);
  }
  return bee;
}

function getNextStepId(session) {
  return session.wizard.steps.find((stepId) => !session.wizard.completedSteps.includes(stepId)) ?? null;
}

function renderSessionFiles(session) {
  const files = [
    {
      path: "scenario.yaml",
      content: renderScenarioYaml(session.bundle)
    }
  ];

  for (const [fileName, template] of Object.entries(session.templates.http)) {
    files.push({
      path: `templates/http/${fileName}`,
      content: YAML.stringify(template)
    });
  }

  return files;
}

function renderScenarioYaml(bundle) {
  const normalized = normalizeBundle(bundle);
  return YAML.stringify(normalized);
}

function normalizeBundle(bundle) {
  if (bundle.description == null) {
    const copy = structuredClone(bundle);
    delete copy.description;
    return copy;
  }
  return bundle;
}

function persistSessionSnapshot(session) {
  mkdirSync(session.sessionPath, { recursive: true });
  for (const file of renderSessionFiles(session)) {
    const filePath = resolve(session.sessionPath, file.path);
    mkdirSync(dirname(filePath), { recursive: true });
    writeFileSync(filePath, file.content, "utf8");
  }
  writeFileSync(
    resolve(session.sessionPath, "session-state.json"),
    JSON.stringify(getWizardState(session), null, 2),
    "utf8"
  );
}

function createBee({ id, role, image, ports, work, config }) {
  return {
    id,
    role,
    image,
    ports,
    config: config ?? {},
    work: {
      in: work.in ?? undefined,
      out: work.out ?? undefined
    }
  };
}

function createPort(id, direction) {
  return { id, direction };
}

function createEdge(id, fromBeeId, fromPort, toBeeId, toPort) {
  return {
    id,
    from: {
      beeId: fromBeeId,
      port: fromPort
    },
    to: {
      beeId: toBeeId,
      port: toPort
    }
  };
}

function validationIssue(code, severity, path, message) {
  return { code, severity, path, message };
}

function assertAbsoluteWorkspacePath(workspacePath) {
  if (!workspacePath || !isAbsolute(workspacePath)) {
    throw new Error("workspacePath must be an absolute path.");
  }
}

function assertSupportedTemplateType(templateType) {
  if (!Object.values(TEMPLATE_TYPES).includes(templateType)) {
    throw new Error(`Unsupported templateType '${templateType}'.`);
  }
}

function assertNonEmpty(name, value) {
  if (!value || typeof value !== "string" || value.trim() === "") {
    throw new Error(`${name} must be a non-empty string.`);
  }
}

function assertPositiveNumber(name, value) {
  if (typeof value !== "number" || !Number.isFinite(value) || value <= 0) {
    throw new Error(`${name} must be a positive number.`);
  }
}

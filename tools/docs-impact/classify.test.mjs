import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { accessSync, constants as FS_CONSTANTS, realpathSync } from "node:fs";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { stringify } from "yaml";
import {
  canonicalDigest,
  canonicalJson,
  canonicalJsonByteLength
} from "./canonical.mjs";
import { assertAnalysisSemantics } from "./analysis-semantics.mjs";
import {
  classifyRepository,
  mapGitStatusToChangeKind,
  resolveImpactRoutes
} from "./classify.mjs";
import {
  CHANGE_KIND,
  CLASSIFICATION,
  IMPACT_DEPTH,
  REASON
} from "./constants.mjs";
import { assertRepositoryPath, parsePolicyBytes } from "./policy.mjs";

const temporaryRoots = [];
const CLI_PATH = path.resolve("tools/docs-impact/cli.mjs");
const ALL_CHANGE_KINDS = Object.values(CHANGE_KIND);

function findTrustedGitExecutable() {
  const names = process.platform === "win32" ? ["git.exe"] : ["git"];
  for (const rawDirectory of (process.env.PATH ?? "").split(path.delimiter)) {
    const directory = rawDirectory.replace(/^"|"$/gu, "");
    if (!directory) {
      continue;
    }
    for (const name of names) {
      const candidate = path.resolve(directory, name);
      try {
        accessSync(candidate, FS_CONSTANTS.X_OK);
        return realpathSync(candidate);
      } catch {
        // Continue searching the trusted test-process PATH without executing a candidate.
      }
    }
  }
  throw new Error("Tests require an absolute Git executable on PATH");
}

const TEST_GIT_EXECUTABLE = findTrustedGitExecutable();

function git(repoRoot, args, { input } = {}) {
  return execFileSync(TEST_GIT_EXECUTABLE, args, {
    cwd: repoRoot,
    encoding: "utf8",
    input,
    env: process.env,
    stdio: [input === undefined ? "ignore" : "pipe", "pipe", "pipe"],
    windowsHide: true
  }).trim();
}

async function writeRepositoryFile(repoRoot, relativePath, content) {
  const target = path.join(repoRoot, ...relativePath.split("/"));
  await mkdir(path.dirname(target), { recursive: true });
  await writeFile(target, content, "utf8");
}

async function commitAll(repoRoot, message) {
  git(repoRoot, ["add", "--all"]);
  git(repoRoot, ["commit", "-m", message]);
  return git(repoRoot, ["rev-parse", "HEAD"]);
}

function decisions(propagation = "CONTINUE") {
  return [{ changeKinds: [...ALL_CHANGE_KINDS], propagation }];
}

function policyObject() {
  return {
    schemaVersion: 2,
    repositoryId: "test/example",
    platformProfile: "GITHUB_STRICT_HEAD",
    mode: "SHADOW",
    trustedToolPaths: ["package.json", "tools/docs-impact/**"],
    owners: [
      { id: "repository-maintainers", githubPrincipals: ["@repository-maintainer"] },
      { id: "architecture-maintainers", githubPrincipals: ["@architecture-maintainer"] },
      { id: "provider-maintainers", githubPrincipals: ["@provider-maintainer"] },
      { id: "consumer-maintainers", githubPrincipals: ["@consumer-maintainer"] },
      { id: "delivery-maintainers", githubPrincipals: ["@delivery-maintainer"] }
    ],
    checks: [
      { id: "contract-check", stageId: "contract-check", availability: "PLANNED" },
      { id: "docs-check", stageId: "docs-check", availability: "PLANNED" },
      { id: "repository-check", stageId: "repository-check", availability: "PLANNED" }
    ],
    publications: [
      {
        id: "repository-source",
        kind: "REPOSITORY",
        locatorKind: "REPOSITORY_PATH",
        contentRoot: "/",
        artifactSelector: "git-tree:{headSha}",
        producerPaths: [],
        contentInputPaths: ["docs/**", "apps/provider/README.md"],
        checkIds: ["repository-check"],
        ownerIds: ["repository-maintainers"]
      },
      {
        id: "customer-docs-site",
        kind: "DOCS_SITE",
        locatorKind: "DOCUSAURUS_ROUTE",
        contentRoot: "/",
        artifactSelector: "docs-site/build",
        producerPaths: ["package.json"],
        contentInputPaths: ["docs/**"],
        checkIds: ["docs-check"],
        ownerIds: ["delivery-maintainers"]
      },
      {
        id: "consumer-package",
        kind: "PACKAGE",
        locatorKind: "ARCHIVE_ENTRY",
        contentRoot: "package",
        artifactSelector: "consumer-*.zip",
        producerPaths: ["deploy/**"],
        contentInputPaths: ["docs/consumer.md"],
        checkIds: ["docs-check"],
        ownerIds: ["delivery-maintainers"]
      },
      {
        id: "delivery-client-config",
        kind: "CLIENT_CONFIG",
        locatorKind: "CLIENT_CONFIG_PATH",
        contentRoot: "/",
        artifactSelector: "delivery-client-config",
        producerPaths: ["deploy/**"],
        contentInputPaths: ["docs/delivery.md"],
        checkIds: ["docs-check"],
        ownerIds: ["delivery-maintainers"]
      }
    ],
    inventoryRules: [
      {
        id: "repository-control",
        classification: "NO_DOC_IMPACT",
        paths: ["package.json", "policy.yaml", "tools/docs-impact/**"]
      },
      { id: "tests", classification: "NO_DOC_IMPACT", paths: ["tests/**"] },
      {
        id: "documentation",
        classification: "DOCUMENTATION",
        paths: ["docs/**", "apps/provider/README.md"]
      },
      { id: "contracts", classification: "MATERIAL", paths: ["contracts/**"] },
      { id: "provider", classification: "MATERIAL", paths: ["apps/provider/service.mjs"] },
      { id: "consumer", classification: "MATERIAL", paths: ["apps/consumer/**"] },
      { id: "delivery", classification: "MATERIAL", paths: ["deploy/**"] }
    ],
    components: [
      {
        id: "shared-contract",
        kind: "SHARED_CONTRACT",
        ownerIds: ["architecture-maintainers"]
      },
      {
        id: "provider-app",
        kind: "APPLICATION",
        ownerIds: ["provider-maintainers"]
      },
      {
        id: "consumer-app",
        kind: "APPLICATION",
        ownerIds: ["consumer-maintainers"]
      },
      {
        id: "delivery-system",
        kind: "DELIVERY",
        ownerIds: ["delivery-maintainers"]
      }
    ],
    impactNodes: [
      {
        id: "contract-node",
        componentId: "shared-contract",
        sourcePaths: ["contracts/**"],
        evaluateChangeKinds: [...ALL_CHANGE_KINDS],
        noDocumentationChangeKinds: []
      },
      {
        id: "provider-node",
        componentId: "provider-app",
        sourcePaths: ["apps/provider/service.mjs"],
        evaluateChangeKinds: [...ALL_CHANGE_KINDS],
        noDocumentationChangeKinds: []
      },
      {
        id: "consumer-node",
        componentId: "consumer-app",
        sourcePaths: ["apps/consumer/**"],
        evaluateChangeKinds: [...ALL_CHANGE_KINDS],
        noDocumentationChangeKinds: []
      },
      {
        id: "delivery-node",
        componentId: "delivery-system",
        sourcePaths: ["deploy/**"],
        evaluateChangeKinds: [...ALL_CHANGE_KINDS],
        noDocumentationChangeKinds: []
      }
    ],
    impactEdges: [
      {
        id: "contract-to-provider",
        fromNodeId: "contract-node",
        toNodeId: "provider-node",
        relation: "CONTRACT_PROJECTION",
        decisions: decisions()
      },
      {
        id: "provider-to-consumer",
        fromNodeId: "provider-node",
        toNodeId: "consumer-node",
        relation: "API_CLIENT",
        decisions: decisions()
      },
      {
        id: "contract-to-delivery",
        fromNodeId: "contract-node",
        toNodeId: "delivery-node",
        relation: "DELIVERY_CONSUMER",
        decisions: decisions()
      }
    ],
    documents: [
      {
        id: "contract-guide",
        path: "docs/contract.md",
        role: "CANONICAL",
        publicationBindings: [{
          publicationId: "repository-source",
          artifactPath: "docs/contract.md"
        }],
        basePresence: "MUST_EXIST",
        ownerIds: ["architecture-maintainers"]
      },
      {
        id: "provider-guide",
        path: "docs/provider.md",
        role: "PROJECTION",
        publicationBindings: [
          {
            publicationId: "repository-source",
            artifactPath: "docs/provider.md"
          },
          {
            publicationId: "customer-docs-site",
            artifactPath: "/guides/provider"
          }
        ],
        basePresence: "MUST_EXIST",
        ownerIds: ["provider-maintainers"]
      },
      {
        id: "consumer-guide",
        path: "docs/consumer.md",
        role: "PROJECTION",
        publicationBindings: [{
          publicationId: "consumer-package",
          artifactPath: "README.md"
        }],
        basePresence: "MUST_EXIST",
        ownerIds: ["consumer-maintainers"]
      },
      {
        id: "delivery-guide",
        path: "docs/delivery.md",
        role: "PROJECTION",
        publicationBindings: [{
          publicationId: "delivery-client-config",
          artifactPath: "delivery.json"
        }],
        basePresence: "MUST_EXIST",
        ownerIds: ["delivery-maintainers"]
      },
      {
        id: "provider-readme",
        path: "apps/provider/README.md",
        role: "CANONICAL",
        publicationBindings: [{
          publicationId: "repository-source",
          artifactPath: "apps/provider/README.md"
        }],
        basePresence: "MUST_EXIST",
        ownerIds: ["provider-maintainers"]
      }
    ],
    documentationRules: [
      {
        id: "contract-self-docs",
        impactNodeIds: ["contract-node"],
        impactDepths: ["SELF"],
        sourceChangeKinds: [...ALL_CHANGE_KINDS],
        targets: [{
          documentId: "contract-guide",
          acceptedChangeKinds: ["MODIFY"]
        }],
        checkIds: ["contract-check", "repository-check"],
        ownerIds: ["architecture-maintainers", "repository-maintainers"]
      },
      {
        id: "provider-docs",
        impactNodeIds: ["provider-node"],
        impactDepths: ["SELF", "DIRECT"],
        sourceChangeKinds: [...ALL_CHANGE_KINDS],
        targets: [{
          documentId: "provider-guide",
          acceptedChangeKinds: ["MODIFY"]
        }],
        checkIds: ["docs-check", "repository-check"],
        ownerIds: [
          "provider-maintainers",
          "repository-maintainers",
          "delivery-maintainers"
        ]
      },
      {
        id: "consumer-docs",
        impactNodeIds: ["consumer-node"],
        impactDepths: ["SELF", "DIRECT", "TRANSITIVE"],
        sourceChangeKinds: [...ALL_CHANGE_KINDS],
        targets: [{
          documentId: "consumer-guide",
          acceptedChangeKinds: ["MODIFY"]
        }],
        checkIds: ["docs-check"],
        ownerIds: ["consumer-maintainers", "delivery-maintainers"]
      },
      {
        id: "delivery-docs",
        impactNodeIds: ["delivery-node"],
        impactDepths: ["SELF", "DIRECT"],
        sourceChangeKinds: [...ALL_CHANGE_KINDS],
        targets: [{
          documentId: "delivery-guide",
          acceptedChangeKinds: ["MODIFY"]
        }],
        checkIds: ["docs-check"],
        ownerIds: ["delivery-maintainers"]
      },
      {
        id: "provider-readme-docs",
        impactNodeIds: ["provider-node"],
        impactDepths: ["SELF", "DIRECT"],
        sourceChangeKinds: [...ALL_CHANGE_KINDS],
        targets: [{
          documentId: "provider-readme",
          acceptedChangeKinds: ["MODIFY"]
        }],
        checkIds: ["repository-check"],
        ownerIds: ["provider-maintainers", "repository-maintainers"]
      }
    ],
    protectionRules: [
      {
        id: "repository-control",
        kind: "CONTROL",
        paths: ["package.json", "policy.yaml", "tools/docs-impact/**"],
        ownerIds: ["repository-maintainers"]
      },
      {
        id: "protected-contract",
        kind: "PROTECTED",
        paths: ["contracts/**"],
        ownerIds: ["architecture-maintainers"]
      },
      {
        id: "protected-contract-document",
        kind: "PROTECTED",
        paths: ["docs/contract.md"],
        ownerIds: ["architecture-maintainers"]
      },
      {
        id: "protected-documentation",
        kind: "PROTECTED",
        paths: ["docs/**"],
        ownerIds: ["architecture-maintainers"]
      },
      {
        id: "delivery-control",
        kind: "CONTROL",
        paths: ["deploy/**"],
        ownerIds: ["delivery-maintainers"]
      }
    ]
  };
}

async function createFixture({
  mutatePolicy,
  omitBasePaths = [],
  extraBaseFiles = {}
} = {}) {
  const root = await mkdtemp(path.join(os.tmpdir(), "pockethive-docs-impact-v2-"));
  temporaryRoots.push(root);
  const repoRoot = path.join(root, "repo");
  await mkdir(repoRoot, { recursive: true });
  git(repoRoot, ["init", "--initial-branch=main"]);
  git(repoRoot, ["config", "user.name", "Docs Impact Test"]);
  git(repoRoot, ["config", "user.email", "docs-impact@example.invalid"]);
  const policy = policyObject();
  mutatePolicy?.(policy);
  await writeRepositoryFile(repoRoot, "package.json", "{}\n");
  await writeRepositoryFile(repoRoot, "policy.yaml", stringify(policy));
  await writeRepositoryFile(repoRoot, "tools/docs-impact/version.txt", "fixture-v2\n");
  if (!omitBasePaths.includes("docs/contract.md")) {
    await writeRepositoryFile(repoRoot, "docs/contract.md", "# Contract\n");
  }
  await writeRepositoryFile(repoRoot, "docs/provider.md", "# Provider\n");
  await writeRepositoryFile(repoRoot, "docs/consumer.md", "# Consumer\n");
  await writeRepositoryFile(repoRoot, "docs/delivery.md", "# Delivery\n");
  await writeRepositoryFile(repoRoot, "contracts/api.json", "{}\n");
  await writeRepositoryFile(repoRoot, "apps/provider/service.mjs", "export const value = 1;\n");
  await writeRepositoryFile(repoRoot, "apps/provider/README.md", "# Provider source\n");
  await writeRepositoryFile(repoRoot, "apps/consumer/service.mjs", "export const value = 1;\n");
  await writeRepositoryFile(repoRoot, "deploy/helm.yaml", "version: 1\n");
  await writeRepositoryFile(repoRoot, "tests/fixture.txt", "one\n");
  for (const [relativePath, content] of Object.entries(extraBaseFiles)) {
    await writeRepositoryFile(repoRoot, relativePath, content);
  }
  const base = await commitAll(repoRoot, "base");
  return { root, repoRoot, base, policy, policyPath: "policy.yaml" };
}

async function analyze(fixture, head) {
  return classifyRepository({
    repoRoot: fixture.repoRoot,
    gitExecutable: TEST_GIT_EXECUTABLE,
    repositoryId: "test/example",
    base: fixture.base,
    head,
    policyPath: fixture.policyPath
  });
}

test.after(async () => {
  await Promise.all(temporaryRoots.map((root) => rm(root, { recursive: true, force: true })));
});

test("maps only the four supported Git statuses to explicit change kinds", () => {
  assert.equal(mapGitStatusToChangeKind("A"), "ADD");
  assert.equal(mapGitStatusToChangeKind("M"), "MODIFY");
  assert.equal(mapGitStatusToChangeKind("D"), "DELETE");
  assert.equal(mapGitStatusToChangeKind("T"), "TYPE_CHANGE");
  assert.equal(mapGitStatusToChangeKind("R"), null);
});

test("output byte measurement uses emitted canonical JSON, not compact JSON", () => {
  const value = { nested: { values: ["one", "two"] } };
  assert.equal(
    canonicalJsonByteLength(value),
    Buffer.byteLength(canonicalJson(value), "utf8")
  );
  assert.ok(canonicalJsonByteLength(value) > Buffer.byteLength(JSON.stringify(value), "utf8"));
});

test("resolves self, direct, and transitive routes in deterministic shortest order", async () => {
  const { policy } = await parsePolicyBytes(stringify(policyObject()));
  policy.impactEdges.push({
    id: "contract-to-consumer-direct",
    fromNodeId: "contract-node",
    toNodeId: "consumer-node",
    relation: "CONTRACT_CONSUMER",
    decisions: decisions()
  });
  const routes = resolveImpactRoutes(policy, "contract-node", "MODIFY");
  assert.deepEqual(routes.map((route) => [
    route.impactNodeId,
    route.impactDepth,
    route.viaEdgeIds
  ]), [
    ["contract-node", IMPACT_DEPTH.SELF, []],
    ["consumer-node", IMPACT_DEPTH.DIRECT, ["contract-to-consumer-direct"]],
    ["delivery-node", IMPACT_DEPTH.DIRECT, ["contract-to-delivery"]],
    ["provider-node", IMPACT_DEPTH.DIRECT, ["contract-to-provider"]]
  ]);
});

test("STOP prevents traversal and explicit no-documentation kinds suppress the origin", async () => {
  const policy = policyObject();
  policy.impactEdges.find((edge) => edge.id === "provider-to-consumer").decisions = [
    { changeKinds: ["ADD", "MODIFY", "TYPE_CHANGE"], propagation: "CONTINUE" },
    { changeKinds: ["DELETE"], propagation: "STOP" }
  ];
  policy.impactNodes.find((node) => node.id === "provider-node").evaluateChangeKinds = [
    "ADD", "MODIFY", "TYPE_CHANGE"
  ];
  policy.impactNodes.find((node) => node.id === "provider-node").noDocumentationChangeKinds = [
    "DELETE"
  ];
  for (const rule of policy.documentationRules.filter((candidate) =>
    candidate.impactNodeIds.includes("provider-node") && candidate.impactDepths.includes("SELF")
  )) {
    rule.sourceChangeKinds = rule.sourceChangeKinds.filter((kind) => kind !== "DELETE");
  }
  const parsed = (await parsePolicyBytes(stringify(policy))).policy;
  assert.deepEqual(resolveImpactRoutes(parsed, "contract-node", "DELETE")
    .map((route) => route.impactNodeId), [
      "contract-node", "delivery-node", "provider-node", "consumer-node"
    ]);
  assert.deepEqual(resolveImpactRoutes(parsed, "provider-node", "DELETE"), []);
});

test("STOP reaches its destination, fires DIRECT rules, and prevents further expansion", async () => {
  const fixture = await createFixture({
    mutatePolicy(policy) {
      policy.impactEdges.find((edge) => edge.id === "contract-to-provider").decisions = [
        { changeKinds: ["MODIFY"], propagation: "STOP" },
        { changeKinds: ["ADD", "DELETE", "TYPE_CHANGE"], propagation: "CONTINUE" }
      ];
    }
  });
  await writeRepositoryFile(fixture.repoRoot, "contracts/api.json", "{\"version\":2}\n");
  const head = await commitAll(fixture.repoRoot, "stopped provider impact");
  const result = await analyze(fixture, head);
  const reachedIds = result.changes[0].reachableImpactNodes.map((route) => route.impactNodeId);
  assert.ok(reachedIds.includes("provider-node"));
  assert.ok(!reachedIds.includes("consumer-node"));
  assert.ok(result.documentationObligations.some((item) => item.ruleId === "provider-docs"));
  assert.ok(!result.documentationObligations.some((item) => item.ruleId === "consumer-docs"));
});

test("rejects cycle, dangling edge, incomplete decisions, and v1 policy", async () => {
  const cycle = policyObject();
  cycle.impactEdges.push({
    id: "delivery-to-contract",
    fromNodeId: "delivery-node",
    toNodeId: "contract-node",
    relation: "DELIVERY_CONSUMER",
    decisions: decisions()
  });
  await assert.rejects(parsePolicyBytes(stringify(cycle)), /directed acyclic graph/u);

  const dangling = policyObject();
  dangling.impactEdges[0].toNodeId = "missing-node";
  await assert.rejects(parsePolicyBytes(stringify(dangling)), /unknown id missing-node/u);

  const incomplete = policyObject();
  incomplete.impactEdges[0].decisions = [{
    changeKinds: ["MODIFY"],
    propagation: "CONTINUE"
  }];
  await assert.rejects(parsePolicyBytes(stringify(incomplete)), /cover every change kind exactly once/u);

  const parallel = policyObject();
  parallel.impactEdges.push({
    id: "parallel-contract-provider",
    fromNodeId: "contract-node",
    toNodeId: "provider-node",
    relation: "TOOL_ADAPTER",
    decisions: decisions("STOP")
  });
  await assert.rejects(
    parsePolicyBytes(stringify(parallel)),
    /duplicates connection from contract-node to provider-node/u
  );

  await assert.rejects(
    parsePolicyBytes("schemaVersion: 1\nrepositoryId: test/example\n"),
    /must equal 2/u
  );
});

test("rejects incomplete node decisions and rule ownership that bypasses document owners", async () => {
  const incomplete = policyObject();
  incomplete.impactNodes[0].evaluateChangeKinds = ["MODIFY"];
  await assert.rejects(
    parsePolicyBytes(stringify(incomplete)),
    /partition every change kind exactly once/u
  );

  const ownerBypass = policyObject();
  ownerBypass.components.find(
    (component) => component.id === "shared-contract"
  ).ownerIds = ["repository-maintainers"];
  ownerBypass.documentationRules[0].ownerIds = ["repository-maintainers"];
  await assert.rejects(
    parsePolicyBytes(stringify(ownerBypass)),
    /must include target document contract-guide owners architecture-maintainers/u
  );

  const componentOwnerBypass = policyObject();
  componentOwnerBypass.components.find(
    (component) => component.id === "shared-contract"
  ).ownerIds.push("delivery-maintainers");
  await assert.rejects(
    parsePolicyBytes(stringify(componentOwnerBypass)),
    /must include impact component shared-contract owners delivery-maintainers/u
  );
});

test("rejects invalid publication registries, bindings, and rule closure bypasses", async () => {
  const missingContentInputs = policyObject();
  delete missingContentInputs.publications[0].contentInputPaths;
  await assert.rejects(
    parsePolicyBytes(stringify(missingContentInputs)),
    /publications\[0\]: missing required property contentInputPaths/u
  );

  const unmappedContentInput = policyObject();
  unmappedContentInput.publications[0].contentInputPaths = ["unmapped/**"];
  await assert.rejects(
    parsePolicyBytes(stringify(unmappedContentInput)),
    /content input path unmapped\/\*\* must be contained by exactly one inventory rule/u
  );

  const uncoveredDocumentBinding = policyObject();
  uncoveredDocumentBinding.publications[0].contentInputPaths = ["apps/provider/README.md"];
  await assert.rejects(
    parsePolicyBytes(stringify(uncoveredDocumentBinding)),
    /document contract-guide publication repository-source source path docs\/contract.md must be covered/u
  );

  const emptyPublicationChecks = policyObject();
  emptyPublicationChecks.publications[0].checkIds = [];
  await assert.rejects(
    parsePolicyBytes(stringify(emptyPublicationChecks)),
    /publications\[0\]\.checkIds: must contain at least 1 items/u
  );

  const emptyBindings = policyObject();
  emptyBindings.documents[0].publicationBindings = [];
  await assert.rejects(
    parsePolicyBytes(stringify(emptyBindings)),
    /documents\[0\]\.publicationBindings: must contain at least 1 items/u
  );

  const duplicatePublication = policyObject();
  duplicatePublication.documents[0].publicationBindings.push({
    publicationId: "repository-source",
    artifactPath: "docs/other-contract.md"
  });
  await assert.rejects(
    parsePolicyBytes(stringify(duplicatePublication)),
    /must bind each publication id at most once/u
  );

  const unknownPublication = policyObject();
  unknownPublication.documents[0].publicationBindings[0].publicationId = "missing-publication";
  await assert.rejects(
    parsePolicyBytes(stringify(unknownPublication)),
    /document contract-guide publications references unknown id missing-publication/u
  );

  const unknownKind = policyObject();
  unknownKind.publications[0].kind = "UNKNOWN";
  await assert.rejects(
    parsePolicyBytes(stringify(unknownKind)),
    /publications\[0\]\.kind: must be one of/u
  );

  const unknownPublicationCheck = policyObject();
  unknownPublicationCheck.publications[0].checkIds = ["missing-check"];
  await assert.rejects(
    parsePolicyBytes(stringify(unknownPublicationCheck)),
    /publication repository-source checks references unknown id missing-check/u
  );

  const unknownPublicationOwner = policyObject();
  unknownPublicationOwner.publications[0].ownerIds = ["missing-owner"];
  await assert.rejects(
    parsePolicyBytes(stringify(unknownPublicationOwner)),
    /publication repository-source owners references unknown id missing-owner/u
  );

  const unsafeArtifactPath = policyObject();
  unsafeArtifactPath.documents[0].publicationBindings[0].artifactPath = "../contract.md";
  await assert.rejects(
    parsePolicyBytes(stringify(unsafeArtifactPath)),
    /artifactPath: does not match/u
  );

  const relativeDocsSiteRoute = policyObject();
  relativeDocsSiteRoute.documents[1].publicationBindings[1].artifactPath = "guides/provider";
  await assert.rejects(
    parsePolicyBytes(stringify(relativeDocsSiteRoute)),
    /is not a safe absolute route/u
  );

  const ambiguousDocsSiteRoute = policyObject();
  ambiguousDocsSiteRoute.documents[1].publicationBindings[1].artifactPath = "/guides//provider";
  await assert.rejects(
    parsePolicyBytes(stringify(ambiguousDocsSiteRoute)),
    /artifactPath: does not match/u
  );

  const rootDocsSiteRoute = policyObject();
  rootDocsSiteRoute.documents[1].publicationBindings[1].artifactPath = "/";
  await assert.doesNotReject(parsePolicyBytes(stringify(rootDocsSiteRoute)));

  const repositoryDestinationMismatch = policyObject();
  repositoryDestinationMismatch.documents[0].publicationBindings[0].artifactPath =
    "docs/not-the-contract.md";
  await assert.rejects(
    parsePolicyBytes(stringify(repositoryDestinationMismatch)),
    /REPOSITORY publication repository-source artifact path must equal document path/u
  );

  const ambiguousArtifact = policyObject();
  ambiguousArtifact.documents[0].publicationBindings.push({
    publicationId: "customer-docs-site",
    artifactPath: "/guides/provider"
  });
  await assert.rejects(
    parsePolicyBytes(stringify(ambiguousArtifact)),
    /bind ambiguous publication artifact customer-docs-site:\/guides\/provider/u
  );

  const publicationOwnerBypass = policyObject();
  publicationOwnerBypass.documentationRules.find(
    (rule) => rule.id === "provider-docs"
  ).ownerIds = ["provider-maintainers", "repository-maintainers"];
  await assert.rejects(
    parsePolicyBytes(stringify(publicationOwnerBypass)),
    /publication customer-docs-site owners delivery-maintainers/u
  );

  const publicationCheckBypass = policyObject();
  publicationCheckBypass.documentationRules.find(
    (rule) => rule.id === "provider-docs"
  ).checkIds = ["docs-check"];
  await assert.rejects(
    parsePolicyBytes(stringify(publicationCheckBypass)),
    /publication repository-source checks repository-check/u
  );
});

test("rejects evaluated dead ends and trusted tool paths without CONTROL protection", async () => {
  const deadEnd = policyObject();
  deadEnd.documentationRules = deadEnd.documentationRules.filter(
    (rule) => rule.id !== "consumer-docs"
  );
  await assert.rejects(
    parsePolicyBytes(stringify(deadEnd)),
    /consumer-node evaluated change kind .* must reach at least one matching documentation rule/u
  );

  const unprotectedTool = policyObject();
  unprotectedTool.protectionRules.find(
    (rule) => rule.id === "repository-control"
  ).paths = ["package.json", "policy.yaml"];
  await assert.rejects(
    parsePolicyBytes(stringify(unprotectedTool)),
    /trusted tool path tools\/docs-impact\/\*\* must be contained by at least one CONTROL/u
  );
});

test("shared contract fans out transitively and produces additive documentation and governance actions", async () => {
  const fixture = await createFixture();
  await writeRepositoryFile(fixture.repoRoot, "contracts/api.json", "{\"version\":2}\n");
  await writeRepositoryFile(fixture.repoRoot, "docs/contract.md", "# Contract v2\n");
  const head = await commitAll(fixture.repoRoot, "contract and canonical docs");
  const result = await analyze(fixture, head);

  assert.equal(result.schemaVersion, 2);
  assert.equal(result.classification, CLASSIFICATION.ACTION_REQUIRED);
  const contractChange = result.changes.find((change) => change.path === "contracts/api.json");
  assert.deepEqual(contractChange.reachableImpactNodes.map((route) => [
    route.impactNodeId,
    route.impactDepth
  ]), [
    ["contract-node", "SELF"],
    ["delivery-node", "DIRECT"],
    ["provider-node", "DIRECT"],
    ["consumer-node", "TRANSITIVE"]
  ]);
  assert.deepEqual(result.documentationObligations.map((item) => item.ruleId), [
    "consumer-docs",
    "contract-self-docs",
    "delivery-docs",
    "provider-docs",
    "provider-readme-docs"
  ]);
  const contractObligation = result.documentationObligations.find(
    (item) => item.ruleId === "contract-self-docs"
  );
  assert.equal(contractObligation.candidateState, "CANDIDATE_CHANGE_PRESENT");
  const providerObligation = result.documentationObligations.find(
    (item) => item.ruleId === "provider-docs"
  );
  assert.deepEqual(providerObligation.targets[0].publicationBindings, [
    {
      publicationId: "customer-docs-site",
      kind: "DOCS_SITE",
      locatorKind: "DOCUSAURUS_ROUTE",
      contentRoot: "/",
      artifactSelector: "docs-site/build",
      producerPaths: ["package.json"],
      contentInputPaths: ["docs/**"],
      artifactPath: "/guides/provider",
      checkIds: ["docs-check"],
      ownerIds: ["delivery-maintainers"]
    },
    {
      publicationId: "repository-source",
      kind: "REPOSITORY",
      locatorKind: "REPOSITORY_PATH",
      contentRoot: "/",
      artifactSelector: "git-tree:{headSha}",
      producerPaths: [],
      contentInputPaths: ["apps/provider/README.md", "docs/**"],
      artifactPath: "docs/provider.md",
      checkIds: ["repository-check"],
      ownerIds: ["repository-maintainers"]
    }
  ]);
  assert.deepEqual(providerObligation.checkIds, ["docs-check", "repository-check"]);
  assert.deepEqual(providerObligation.ownerIds, [
    "delivery-maintainers",
    "provider-maintainers",
    "repository-maintainers"
  ]);
  assert.deepEqual(result.governanceReviews.map((item) => item.protectionRuleId), [
    "protected-contract",
    "protected-contract-document",
    "protected-documentation"
  ]);
  const { obligationId, ...obligationPayload } = contractObligation;
  assert.equal(
    obligationId,
    canonicalDigest({ analysisId: result.analysisId, ...obligationPayload })
  );
  assert.notEqual(
    obligationId,
    canonicalDigest({
      analysisId: result.analysisId,
      ...obligationPayload,
      candidateState: "ACTION_REQUIRED"
    })
  );
  const { reviewId, ...reviewPayload } = result.governanceReviews[0];
  assert.equal(
    reviewId,
    canonicalDigest({ analysisId: result.analysisId, ...reviewPayload })
  );
});

test("deployment changes create both documentation and CONTROL governance actions", async () => {
  const fixture = await createFixture();
  await writeRepositoryFile(fixture.repoRoot, "deploy/helm.yaml", "version: 2\n");
  const head = await commitAll(fixture.repoRoot, "delivery change");
  const result = await analyze(fixture, head);
  assert.equal(result.classification, CLASSIFICATION.ACTION_REQUIRED);
  assert.ok(result.documentationObligations.some((item) => item.ruleId === "delivery-docs"));
  assert.deepEqual(result.governanceReviews.map((item) => [item.protectionRuleId, item.kind]), [
    ["delivery-control", "CONTROL"]
  ]);
});

test("NO_DOC_IMPACT suppresses propagation but still receives additive governance", async () => {
  const fixture = await createFixture();
  await writeRepositoryFile(fixture.repoRoot, "package.json", "{\"changed\":true}\n");
  const head = await commitAll(fixture.repoRoot, "control change");
  const result = await analyze(fixture, head);
  assert.equal(result.classification, CLASSIFICATION.ACTION_REQUIRED);
  assert.equal(result.documentationObligations.length, 0);
  assert.deepEqual(result.governanceReviews.map((item) => item.protectionRuleId), [
    "repository-control"
  ]);
  assert.equal(result.changes[0].inventoryClassification, "NO_DOC_IMPACT");
  assert.deepEqual(result.changes[0].reachableImpactNodes, []);
});

test("a registered document-only edit creates one validation per bound publication", async () => {
  const fixture = await createFixture();
  await writeRepositoryFile(fixture.repoRoot, "docs/provider.md", "# Provider projection v2\n");
  const head = await commitAll(fixture.repoRoot, "edit published documentation only");
  const result = await analyze(fixture, head);
  assert.equal(result.classification, CLASSIFICATION.ACTION_REQUIRED);
  assert.deepEqual(result.documentationObligations, []);
  assert.ok(result.reasonCodes.includes(REASON.PUBLICATION_VALIDATION_REQUIRED));
  assert.equal(result.publicationValidations.length, 2);
  const validationPayloads = result.publicationValidations.map(
    ({ validationId, ...validationPayload }) => validationPayload
  );
  assert.deepEqual(validationPayloads, [
    {
      actionType: "PUBLICATION_VALIDATION",
      publicationId: "customer-docs-site",
      kind: "DOCS_SITE",
      locatorKind: "DOCUSAURUS_ROUTE",
      contentRoot: "/",
      artifactSelector: "docs-site/build",
      producerPaths: ["package.json"],
      contentInputPaths: ["docs/**"],
      triggers: [{
        path: "docs/provider.md",
        changeKind: "MODIFY",
        triggerKinds: ["CONTENT_INPUT", "DOCUMENT_BINDING"],
        documentBindings: [{
          documentId: "provider-guide",
          artifactPath: "/guides/provider"
        }]
      }],
      checkIds: ["docs-check"],
      ownerIds: ["delivery-maintainers", "provider-maintainers"],
      candidateState: "ACTION_REQUIRED"
    },
    {
      actionType: "PUBLICATION_VALIDATION",
      publicationId: "repository-source",
      kind: "REPOSITORY",
      locatorKind: "REPOSITORY_PATH",
      contentRoot: "/",
      artifactSelector: "git-tree:{headSha}",
      producerPaths: [],
      contentInputPaths: ["apps/provider/README.md", "docs/**"],
      triggers: [{
        path: "docs/provider.md",
        changeKind: "MODIFY",
        triggerKinds: ["CONTENT_INPUT", "DOCUMENT_BINDING"],
        documentBindings: [{
          documentId: "provider-guide",
          artifactPath: "docs/provider.md"
        }]
      }],
      checkIds: ["repository-check"],
      ownerIds: ["provider-maintainers", "repository-maintainers"],
      candidateState: "ACTION_REQUIRED"
    }
  ]);
  for (const [index, validation] of result.publicationValidations.entries()) {
    assert.equal(
      validation.validationId,
      canonicalDigest({ analysisId: result.analysisId, ...validationPayloads[index] })
    );
  }
});

test("an unregistered content input ADD triggers each matching publication channel", async () => {
  const fixture = await createFixture();
  await writeRepositoryFile(fixture.repoRoot, "docs/new-unregistered.md", "# New content\n");
  const head = await commitAll(fixture.repoRoot, "add unregistered publication content");
  const result = await analyze(fixture, head);
  assert.equal(result.classification, CLASSIFICATION.ACTION_REQUIRED);
  assert.deepEqual(result.documentationObligations, []);
  assert.deepEqual(
    result.publicationValidations.map((validation) => validation.publicationId),
    ["customer-docs-site", "repository-source"]
  );
  for (const validation of result.publicationValidations) {
    assert.deepEqual(validation.triggers, [{
      path: "docs/new-unregistered.md",
      changeKind: "ADD",
      triggerKinds: ["CONTENT_INPUT"],
      documentBindings: []
    }]);
  }
});

test("a producer-only change creates a publication validation without a document binding", async () => {
  const fixture = await createFixture();
  await writeRepositoryFile(fixture.repoRoot, "package.json", "{\"scripts\":{}}\n");
  const head = await commitAll(fixture.repoRoot, "change docs-site producer");
  const result = await analyze(fixture, head);
  const validation = result.publicationValidations.find(
    (candidate) => candidate.publicationId === "customer-docs-site"
  );
  assert.ok(validation);
  assert.deepEqual(validation.triggers, [{
    path: "package.json",
    changeKind: "MODIFY",
    triggerKinds: ["PRODUCER_INPUT"],
    documentBindings: []
  }]);
  assert.deepEqual(validation.ownerIds, ["delivery-maintainers"]);
});

test("required target deletion fails closed and DELETE cannot be accepted as evidence", async () => {
  const rejectedFixture = await createFixture();
  await writeRepositoryFile(rejectedFixture.repoRoot, "contracts/api.json", "{\"version\":2}\n");
  git(rejectedFixture.repoRoot, ["rm", "docs/contract.md"]);
  const rejectedHead = await commitAll(rejectedFixture.repoRoot, "delete unaccepted target");
  const rejected = await analyze(rejectedFixture, rejectedHead);
  assert.equal(rejected.classification, CLASSIFICATION.POLICY_ERROR);
  assert.ok(rejected.policyErrors.some((error) => error.code === REASON.DOCUMENT_HEAD_MISSING));
  assert.deepEqual(rejected.documentationObligations, []);

  const acceptedFixture = await createFixture({
    mutatePolicy(policy) {
      policy.documentationRules.find(
        (rule) => rule.id === "contract-self-docs"
      ).targets[0].acceptedChangeKinds.push("DELETE");
    }
  });
  await assert.rejects(
    analyze(acceptedFixture, acceptedFixture.base),
    /acceptedChangeKinds: must equal \["MODIFY"\]/u
  );
});

test("an exact co-located documentation target can satisfy a code obligation", async () => {
  const fixture = await createFixture();
  await writeRepositoryFile(fixture.repoRoot, "apps/provider/service.mjs", "export const value = 2;\n");
  await writeRepositoryFile(fixture.repoRoot, "apps/provider/README.md", "# Provider source v2\n");
  const head = await commitAll(fixture.repoRoot, "provider and module readme");
  const result = await analyze(fixture, head);
  const obligation = result.documentationObligations.find(
    (item) => item.ruleId === "provider-readme-docs"
  );
  assert.equal(obligation.targets[0].path, "apps/provider/README.md");
  assert.equal(obligation.candidateState, "CANDIDATE_CHANGE_PRESENT");
});

test("policy rejects an obligation that can target its own triggering source", async () => {
  const fixture = await createFixture({
    mutatePolicy(policy) {
      policy.components.push({
        id: "documentation-system",
        kind: "DOCUMENTATION_SYSTEM",
        ownerIds: ["architecture-maintainers"]
      });
      policy.impactNodes.push({
        id: "contract-document-node",
        componentId: "documentation-system",
        sourcePaths: ["docs/contract.md"],
        evaluateChangeKinds: [...ALL_CHANGE_KINDS],
        noDocumentationChangeKinds: []
      });
      policy.documentationRules.push({
        id: "contract-document-self",
        impactNodeIds: ["contract-document-node"],
        impactDepths: ["SELF"],
        sourceChangeKinds: [...ALL_CHANGE_KINDS],
        targets: [{
          documentId: "contract-guide",
          acceptedChangeKinds: ["MODIFY"]
        }],
        checkIds: ["docs-check", "repository-check"],
        ownerIds: ["architecture-maintainers", "repository-maintainers"]
      });
    }
  });
  await assert.rejects(
    analyze(fixture, fixture.base),
    /triggers unsatisfiable documentation rule contract-document-self targeting its own source docs\/contract\.md/u
  );
});

test("MUST_EXIST document absence fails closed against the protected base", async () => {
  const fixture = await createFixture({ omitBasePaths: ["docs/contract.md"] });
  await writeRepositoryFile(fixture.repoRoot, "tests/fixture.txt", "two\n");
  const head = await commitAll(fixture.repoRoot, "test-only change");
  const result = await analyze(fixture, head);
  assert.equal(result.classification, CLASSIFICATION.POLICY_ERROR);
  assert.ok(result.policyErrors.some((error) => error.code === REASON.DOCUMENT_BASE_MISSING));
  assert.equal(result.documentationObligations.length, 0);
  assert.equal(result.governanceReviews.length, 0);
});

test("MUST_EXIST document deletion fails closed against the candidate head", async () => {
  const fixture = await createFixture();
  await rm(path.join(fixture.repoRoot, "docs/contract.md"));
  const head = await commitAll(fixture.repoRoot, "delete required document");
  const result = await analyze(fixture, head);
  assert.equal(result.classification, CLASSIFICATION.POLICY_ERROR);
  assert.ok(result.policyErrors.some((error) => error.code === REASON.DOCUMENT_HEAD_MISSING));
  assert.equal(result.documentationObligations.length, 0);
  assert.equal(result.governanceReviews.length, 0);
});

test("POLICY_ERROR clears authoritative actions and action reason codes", async () => {
  const fixture = await createFixture();
  await writeRepositoryFile(fixture.repoRoot, "contracts/api.json", "{\"version\":2}\n");
  await writeRepositoryFile(fixture.repoRoot, "unmapped.txt", "unexpected\n");
  const head = await commitAll(fixture.repoRoot, "valid impact plus unmapped path");
  const result = await analyze(fixture, head);
  assert.equal(result.classification, CLASSIFICATION.POLICY_ERROR);
  assert.ok(result.policyErrors.length > 0);
  assert.deepEqual(result.documentationObligations, []);
  assert.deepEqual(result.publicationValidations, []);
  assert.deepEqual(result.governanceReviews, []);
  assert.ok(!result.reasonCodes.includes(REASON.DOCUMENTATION_OBLIGATION_IDENTIFIED));
  assert.ok(!result.reasonCodes.includes(REASON.PUBLICATION_VALIDATION_REQUIRED));
  assert.ok(!result.reasonCodes.includes(REASON.GOVERNANCE_REVIEW_REQUIRED));
});

test("policy error diagnostics are bounded under many unmapped paths", async () => {
  const fixture = await createFixture();
  await Promise.all(Array.from({ length: 300 }, (_, index) =>
    writeRepositoryFile(
      fixture.repoRoot,
      `unmapped/path-${String(index).padStart(3, "0")}.txt`,
      "unexpected\n"
    )
  ));
  const head = await commitAll(fixture.repoRoot, "many unmapped paths");
  const result = await analyze(fixture, head);
  assert.equal(result.classification, CLASSIFICATION.POLICY_ERROR);
  assert.ok(result.policyErrors.length <= 256);
  assert.ok(result.policyErrors.some((error) =>
    error.code === REASON.ANALYSIS_LIMIT_EXCEEDED
    && error.detail.includes("diagnostics exceeded canonical output bounds")
  ));
  assert.deepEqual(result.documentationObligations, []);
  assert.deepEqual(result.governanceReviews, []);
});

test("analysis semantic owner rejects inconsistent classification states", () => {
  assert.throws(() => assertAnalysisSemantics({
    classification: "ACTION_REQUIRED",
    policyErrors: [],
    documentationObligations: [],
    publicationValidations: [],
    governanceReviews: []
  }), /must contain at least one action/u);
  assert.throws(() => assertAnalysisSemantics({
    classification: "NO_ACTION_REQUIRED",
    policyErrors: [],
    documentationObligations: [{}],
    publicationValidations: [],
    governanceReviews: []
  }), /must not contain actions/u);
  assert.throws(() => assertAnalysisSemantics({
    classification: "POLICY_ERROR",
    policyErrors: [],
    documentationObligations: [],
    publicationValidations: [],
    governanceReviews: []
  }), /must contain at least one policy error/u);
  assert.throws(() => assertAnalysisSemantics({
    classification: "POLICY_ERROR",
    policyErrors: [{}],
    documentationObligations: [{}],
    publicationValidations: [],
    governanceReviews: []
  }), /must not contain authoritative actions/u);
});

test("aggregate fan-out route bounds fail closed before unbounded output", async () => {
  const fanoutNodeCount = 50;
  const changedSourceCount = 190;
  const fixture = await createFixture({
    mutatePolicy(policy) {
      policy.components.push({
        id: "fanout-system",
        kind: "PLATFORM_SERVICE",
        ownerIds: ["architecture-maintainers"]
      });
      const fanoutNodeIds = [];
      for (let index = 0; index < fanoutNodeCount; index += 1) {
        const suffix = String(index).padStart(2, "0");
        const nodeId = `fanout-node-${suffix}`;
        fanoutNodeIds.push(nodeId);
        policy.inventoryRules.push({
          id: `fanout-inventory-${suffix}`,
          classification: "MATERIAL",
          paths: [`fanout/${suffix}/**`]
        });
        policy.impactNodes.push({
          id: nodeId,
          componentId: "fanout-system",
          sourcePaths: [`fanout/${suffix}/**`],
          evaluateChangeKinds: [...ALL_CHANGE_KINDS],
          noDocumentationChangeKinds: []
        });
        policy.impactEdges.push({
          id: `contract-to-fanout-${suffix}`,
          fromNodeId: "contract-node",
          toNodeId: nodeId,
          relation: "CONTRACT_CONSUMER",
          decisions: decisions()
        });
      }
      policy.documentationRules.push({
        id: "fanout-docs",
        impactNodeIds: fanoutNodeIds,
        impactDepths: ["SELF", "DIRECT"],
        sourceChangeKinds: [...ALL_CHANGE_KINDS],
        targets: [{
          documentId: "contract-guide",
          acceptedChangeKinds: ["MODIFY"]
        }],
        checkIds: ["docs-check", "repository-check"],
        ownerIds: ["architecture-maintainers", "repository-maintainers"]
      });
    }
  });
  await Promise.all(Array.from({ length: changedSourceCount }, (_, index) =>
    writeRepositoryFile(
      fixture.repoRoot,
      `contracts/generated-${String(index).padStart(3, "0")}.json`,
      "{}\n"
    )
  ));
  const head = await commitAll(fixture.repoRoot, "large contract fan-out");
  const result = await analyze(fixture, head);
  assert.equal(result.classification, CLASSIFICATION.POLICY_ERROR);
  assert.deepEqual(result.changes, []);
  assert.deepEqual(result.documentationObligations, []);
  assert.deepEqual(result.governanceReviews, []);
  assert.ok(result.policyErrors.some((error) => error.code === REASON.ANALYSIS_LIMIT_EXCEEDED));
});

test("aggregate overlapping governance matches are bounded before allocation fan-out", async () => {
  const protectionRuleCount = 60;
  const changedSourceCount = 170;
  const fixture = await createFixture({
    mutatePolicy(policy) {
      for (let index = 0; index < protectionRuleCount; index += 1) {
        policy.protectionRules.push({
          id: `additive-contract-protection-${String(index).padStart(2, "0")}`,
          kind: "PROTECTED",
          paths: ["contracts/**"],
          ownerIds: ["architecture-maintainers"]
        });
      }
    }
  });
  await Promise.all(Array.from({ length: changedSourceCount }, (_, index) =>
    writeRepositoryFile(
      fixture.repoRoot,
      `contracts/governed-${String(index).padStart(3, "0")}.json`,
      "{}\n"
    )
  ));
  const head = await commitAll(fixture.repoRoot, "large governance fan-out");
  const result = await analyze(fixture, head);
  assert.equal(result.classification, CLASSIFICATION.POLICY_ERROR);
  assert.deepEqual(result.changes, []);
  assert.deepEqual(result.documentationObligations, []);
  assert.deepEqual(result.governanceReviews, []);
  assert.ok(result.policyErrors.some((error) =>
    error.detail.includes("Governance matches exceed canonical limit")
  ));
});

test("repeated documentation targets are incrementally bounded before expansion", async () => {
  const documentCount = 101;
  const repeatedRuleCount = 20;
  const extraBaseFiles = {};
  const amplifiedDocuments = [];
  for (let index = 0; index < documentCount; index += 1) {
    const suffix = String(index).padStart(3, "0");
    const documentPath = `docs/amplified/doc-${suffix}.md`;
    extraBaseFiles[documentPath] = `# Amplified ${suffix}\n`;
    amplifiedDocuments.push({
      id: `amplified-document-${suffix}`,
      path: documentPath,
      role: "PROJECTION",
      publicationBindings: [{
        publicationId: "customer-docs-site",
        artifactPath: `/amplified/doc-${suffix}`
      }],
      basePresence: "MUST_EXIST",
      ownerIds: ["architecture-maintainers"]
    });
  }
  const fixture = await createFixture({
    extraBaseFiles,
    mutatePolicy(policy) {
      policy.documents.push(...amplifiedDocuments);
      for (let index = 0; index < repeatedRuleCount; index += 1) {
        policy.documentationRules.push({
          id: `amplified-rule-${String(index).padStart(2, "0")}`,
          impactNodeIds: ["contract-node"],
          impactDepths: ["SELF"],
          sourceChangeKinds: [...ALL_CHANGE_KINDS],
          targets: amplifiedDocuments.map((document) => ({
            documentId: document.id,
            acceptedChangeKinds: ["MODIFY"]
          })),
          checkIds: ["docs-check"],
          ownerIds: ["architecture-maintainers", "delivery-maintainers"]
        });
      }
    }
  });
  await writeRepositoryFile(fixture.repoRoot, "contracts/api.json", "{\"version\":2}\n");
  const head = await commitAll(fixture.repoRoot, "amplified documentation impact");
  const result = await analyze(fixture, head);
  assert.equal(result.classification, CLASSIFICATION.POLICY_ERROR);
  assert.deepEqual(result.changes, []);
  assert.deepEqual(result.documentationObligations, []);
  assert.deepEqual(result.governanceReviews, []);
  assert.ok(result.policyErrors.some((error) =>
    error.detail.includes("Documentation targets exceed canonical limit")
  ));
});

test("unchanged deterministic-impact paths require no action and output is canonical", async () => {
  const fixture = await createFixture();
  await writeRepositoryFile(fixture.repoRoot, "tests/fixture.txt", "two\n");
  const head = await commitAll(fixture.repoRoot, "test-only change");
  const first = await analyze(fixture, head);
  const second = await analyze(fixture, head);
  assert.equal(first.classification, CLASSIFICATION.NO_ACTION_REQUIRED);
  assert.deepEqual(first.reasonCodes, [REASON.NO_ACTION_REQUIRED]);
  assert.equal(first.documentationObligations.length, 0);
  assert.equal(first.governanceReviews.length, 0);
  assert.equal(canonicalJson(first), canonicalJson(second));
});

test("CLI preserves fail-closed exit meanings for v2 classifications", async () => {
  const fixture = await createFixture();
  await writeRepositoryFile(fixture.repoRoot, "apps/provider/service.mjs", "export const value = 2;\n");
  const head = await commitAll(fixture.repoRoot, "provider change");
  const result = spawnSync(process.execPath, [
    CLI_PATH,
    "classify",
    "--repo", fixture.repoRoot,
    "--git-executable", TEST_GIT_EXECUTABLE,
    "--repository-id", "test/example",
    "--base", fixture.base,
    "--head", head,
    "--policy-path", fixture.policyPath
  ], { encoding: "utf8", windowsHide: true });
  assert.equal(result.status, 3, result.stderr);
  assert.equal(JSON.parse(result.stdout).classification, CLASSIFICATION.ACTION_REQUIRED);
});

test("repository paths retain traversal, metadata, bidi, and Windows-name hardening", () => {
  for (const unsafe of [
    "../escape.md",
    "docs/.git/config",
    "docs/CON.txt",
    "docs/COM¹.txt",
    "docs/right\u202eto-left.md"
  ]) {
    assert.throws(() => assertRepositoryPath(unsafe), /repository path/u);
  }
});

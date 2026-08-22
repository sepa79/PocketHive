import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { accessSync, constants as FS_CONSTANTS, realpathSync } from "node:fs";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { classifyRepository } from "./classify.mjs";
import { CHANGE_KIND, LIMITS } from "./constants.mjs";
import { resolveImpactRoutes } from "./impact-graph.mjs";
import { loadPolicy, pathMatches } from "./policy.mjs";
import { publishedDocs } from "../docs-validation/docs-scope.mjs";

const REPOSITORY_ROOT = path.resolve(".");
const POLICY_PATH = path.join(REPOSITORY_ROOT, "docs/ci/docs-impact-map.yaml");

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
        // Keep searching the test process's trusted PATH.
      }
    }
  }
  throw new Error("Repository policy tests require an absolute Git executable on PATH");
}

function repositoryPaths() {
  return execFileSync(
    findTrustedGitExecutable(),
    ["ls-files", "--cached", "--others", "--exclude-standard", "-z"],
    {
      cwd: REPOSITORY_ROOT,
      encoding: "utf8",
      env: process.env,
      stdio: ["ignore", "pipe", "pipe"],
      windowsHide: true
    }
  )
    .split("\0")
    .filter(Boolean)
    .map((candidate) => candidate.replaceAll("\\", "/"));
}

function git(repositoryRoot, args) {
  return execFileSync(findTrustedGitExecutable(), args, {
    cwd: repositoryRoot,
    encoding: "utf8",
    env: process.env,
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true
  }).trim();
}

async function writeRepositoryFile(repositoryRoot, repositoryPath, content) {
  const target = path.join(repositoryRoot, ...repositoryPath.split("/"));
  await mkdir(path.dirname(target), { recursive: true });
  await writeFile(target, content, "utf8");
}

function routeIds(policy, originNodeId) {
  return new Set(
    resolveImpactRoutes(policy, originNodeId, "MODIFY")
      .map((route) => route.impactNodeId)
  );
}

function publicationSourcePaths(policy, publicationId) {
  return policy.documents
    .filter((document) => document.publicationBindings.some(
      (binding) => binding.publicationId === publicationId
    ))
    .map((document) => document.path)
    .sort();
}

function publicationCoversPath(policy, publicationId, repositoryPath) {
  const publication = policy.publications.find((candidate) => candidate.id === publicationId);
  return publication?.contentInputPaths.some((pattern) => pathMatches(pattern, repositoryPath))
    ?? false;
}

test("repository map covers every current path without ambiguity", async () => {
  const { policy } = await loadPolicy(POLICY_PATH);
  const paths = repositoryPaths();
  const counts = new Map();

  for (const repositoryPath of paths) {
    const inventoryMatches = policy.inventoryRules.filter((rule) =>
      rule.paths.some((pattern) => pathMatches(pattern, repositoryPath))
    );
    assert.equal(
      inventoryMatches.length,
      1,
      `${repositoryPath} must match exactly one inventory rule`
    );
    const classification = inventoryMatches[0].classification;
    counts.set(classification, (counts.get(classification) ?? 0) + 1);

    if (classification === "MATERIAL") {
      const nodeMatches = policy.impactNodes.filter((node) =>
        node.sourcePaths.some((pattern) => pathMatches(pattern, repositoryPath))
      );
      assert.equal(
        nodeMatches.length,
        1,
        `${repositoryPath} must match exactly one material impact node`
      );
    }
  }

  assert.ok((counts.get("MATERIAL") ?? 0) > 0);
  assert.ok((counts.get("DOCUMENTATION") ?? 0) > 0);
  assert.ok((counts.get("NO_DOC_IMPACT") ?? 0) > 0);

  for (const rule of policy.inventoryRules) {
    for (const pattern of rule.paths) {
      assert.ok(
        paths.some((repositoryPath) => pathMatches(pattern, repositoryPath)),
        `inventory pattern ${rule.id}:${pattern} must cover a current path`
      );
    }
  }
  for (const node of policy.impactNodes) {
    for (const pattern of node.sourcePaths) {
      assert.ok(
        paths.some((repositoryPath) => pathMatches(pattern, repositoryPath)),
        `impact node pattern ${node.id}:${pattern} must cover a current path`
      );
    }
  }
  for (const component of policy.components) {
    assert.ok(
      policy.impactNodes.some((node) => node.componentId === component.id),
      `component ${component.id} must be represented by an impact node`
    );
  }
  for (const document of policy.documents) {
    assert.ok(paths.includes(document.path), `document ${document.id} must exist in the repository`);
  }
});

test("publication bindings exhaustively match every current source-document manifest", async () => {
  const { policy } = await loadPolicy(POLICY_PATH);
  const paths = repositoryPaths();

  const docsSiteSources = (await publishedDocs())
    .map(({ relativePath }) => `docs/${relativePath}`)
    .sort();
  assert.deepEqual(
    publicationSourcePaths(policy, "docs-site"),
    docsSiteSources,
    "Docusaurus publication bindings must match its current include/exclude manifest exactly"
  );
  for (const repositoryPath of docsSiteSources) {
    assert.ok(
      publicationCoversPath(policy, "docs-site", repositoryPath),
      `${repositoryPath} must trigger standalone documentation-site validation`
    );
    assert.ok(
      publicationCoversPath(policy, "ui-image-docs", repositoryPath),
      `${repositoryPath} must trigger validation of the UI image's embedded /docs site`
    );
  }
  assert.deepEqual(
    policy.publications.find((publication) => publication.id === "ui-image-docs")
      ?.producerPaths,
    ["pom.xml", "ui-v2/Dockerfile", ".github/workflows/publish-images.yml"],
    "the UI image documentation carrier must identify both its image and CI producers"
  );
  const docsSitePublication = policy.publications.find(
    (publication) => publication.id === "docs-site"
  );
  for (const repositoryPath of [
    ".github/workflows/docs-pages.yml",
    "docs-site/docusaurus.config.ts",
    "docs-site/package-lock.json",
    "docs-site/package.json",
    "docs-site/scripts/check-rendered-docs.mjs",
    "docs-site/sidebars.ts",
    "docs-site/src/css/custom.css",
    "docs-site/static/img/logo.svg",
    "docs-site/tsconfig.json"
  ]) {
    assert.ok(
      docsSitePublication?.producerPaths.some((pattern) => pathMatches(pattern, repositoryPath))
        || docsSitePublication?.contentInputPaths.some(
          (pattern) => pathMatches(pattern, repositoryPath)
        ),
      `${repositoryPath} must trigger standalone documentation-site validation`
    );
  }
  for (const publicationId of [
    "deployment-archive-posix",
    "deployment-archive-windows",
    "tcp-mock-jar-docs",
    "tcp-mock-image-docs",
    "ui-image-docs"
  ]) {
    assert.ok(
      policy.publications.find((publication) => publication.id === publicationId)
        ?.producerPaths.includes("pom.xml"),
      `${publicationId} must validate when its root-POM version source changes`
    );
  }
  for (const repositoryPath of [
    "docs/spec/swarm-lifecycle.schema.json",
    "scripts/generate-swarm-lifecycle-contract.mjs",
    "packages/swarm-lifecycle-contract/index.js",
    "vscode-pockethive/src/extension.ts"
  ]) {
    const publication = policy.publications.find(
      (candidate) => candidate.id === "pockethive-vscode-vsix"
    );
    assert.ok(
      publication?.producerPaths.some((pattern) => pathMatches(pattern, repositoryPath))
        || publication?.contentInputPaths.some((pattern) => pathMatches(pattern, repositoryPath)),
      `${repositoryPath} must trigger VSIX publication validation`
    );
  }
  assert.ok(
    publicationCoversPath(
      policy,
      "pockethive-mcp-npm",
      "tools/pockethive-mcp/fixtures/future-version/scenario.yaml"
    ),
    "future fixture directories selected by npm files must trigger MCP package validation"
  );
  const mcpPackageManifest = JSON.parse(await readFile(
    path.join(REPOSITORY_ROOT, "tools/pockethive-mcp/package.json"),
    "utf8"
  ));
  for (const selectedPath of mcpPackageManifest.files) {
    const repositoryPath = `tools/pockethive-mcp/${selectedPath}`;
    const futureProbe = path.posix.extname(selectedPath)
      ? repositoryPath
      : `${repositoryPath}/__future_publication_input__`;
    const publication = policy.publications.find(
      (candidate) => candidate.id === "pockethive-mcp-npm"
    );
    assert.ok(
      publication?.producerPaths.some((pattern) => pathMatches(pattern, futureProbe))
        || publication?.contentInputPaths.some((pattern) => pathMatches(pattern, futureProbe)),
      `npm files entry ${selectedPath} must have protected-base publication trigger coverage`
    );
  }
  const mcpPublication = policy.publications.find(
    (candidate) => candidate.id === "pockethive-mcp-npm"
  );
  for (const repositoryPath of [
    "scripts/generate-scenario-config-defaults.mjs",
    "scenario-manager-service/capabilities/generator.latest.yaml"
  ]) {
    assert.ok(
      mcpPublication?.producerPaths.some((pattern) => pathMatches(pattern, repositoryPath)),
      `${repositoryPath} must trigger MCP package validation as a generated-file producer`
    );
  }

  const tcpMarkdownSources = paths
    .filter((repositoryPath) => /^tcp-mock-server\/docs\/[^/]+\.md$/u.test(repositoryPath))
    .sort();
  assert.deepEqual(
    publicationSourcePaths(policy, "tcp-mock-jar-docs"),
    tcpMarkdownSources,
    "JAR documentation bindings must match the Maven docs resource manifest"
  );
  assert.deepEqual(
    publicationSourcePaths(policy, "tcp-mock-image-docs"),
    tcpMarkdownSources,
    "runtime image documentation bindings must match Dockerfile.runtime's docs copy"
  );

  const mcpMarkdownSources = [
    "tools/pockethive-mcp/CHANGELOG.md",
    "tools/pockethive-mcp/README.md",
    "tools/pockethive-mcp/fixtures/scenario-regression/README.md"
  ].sort();
  assert.deepEqual(
    publicationSourcePaths(policy, "pockethive-mcp-npm"),
    mcpMarkdownSources,
    "npm documentation bindings must match Markdown included by the package files manifest"
  );

  const scenarioMarkdownSources = paths
    .filter((repositoryPath) => /^scenarios\/.*\.md$/u.test(repositoryPath))
    .sort();
  assert.deepEqual(
    publicationSourcePaths(policy, "deployment-archive-posix"),
    [
      "README.md",
      "docs/GHCR_SETUP.md",
      "docs/USAGE.md",
      ...scenarioMarkdownSources,
      "wiremock/README.md"
    ].sort(),
    "POSIX archive bindings must match every current source Markdown copied by its producer"
  );
  assert.deepEqual(
    publicationSourcePaths(policy, "deployment-archive-windows"),
    [
      "README.md",
      "docs/GHCR_SETUP.md",
      "docs/HIVEFORGE.md",
      "docs/USAGE.md",
      "wiremock/README.md"
    ].sort(),
    "Windows archive bindings must match every current source Markdown copied by its producer"
  );

  assert.deepEqual(publicationSourcePaths(policy, "pockethive-vscode-vsix"), [
    "vscode-pockethive/README.md"
  ]);
  assert.equal(
    policy.documents
      .find((document) => document.path === "docs/index.md")
      ?.publicationBindings.some((binding) => binding.publicationId === "docs-site") ?? false,
    false,
    "the excluded legacy docs/index.md must not be claimed as a site publication"
  );
  assert.equal(
    policy.documents
      .find((document) => document.id === "docs-home")
      ?.publicationBindings.find((binding) => binding.publicationId === "docs-site")
      ?.artifactPath,
    "/",
    "the Docusaurus home page must use the canonical absolute root route"
  );
});

test("assistant and client configuration cannot be silently exempted", async () => {
  const { policy } = await loadPolicy(POLICY_PATH);
  const noImpactPatterns = new Set(
    policy.inventoryRules
      .filter((rule) => rule.classification === "NO_DOC_IMPACT")
      .flatMap((rule) => rule.paths)
  );
  for (const forbiddenPattern of [
    ".amazonq/**",
    ".codex/**",
    ".cursor/**",
    ".github/**",
    ".vscode/**",
    ".windsurf/**"
  ]) {
    assert.ok(
      !noImpactPatterns.has(forbiddenPattern),
      `${forbiddenPattern} is too broad for NO_DOC_IMPACT`
    );
  }

  for (const repositoryPath of [
    ".amazonq/mcp.json",
    ".codex/config.toml",
    ".cursor/mcp.json",
    ".github/workflows/docs.yml",
    ".vscode/mcp.json",
    ".windsurf/mcp.json",
    "mcp.json"
  ]) {
    const classifications = policy.inventoryRules
      .filter((rule) => rule.paths.some((pattern) => pathMatches(pattern, repositoryPath)))
      .map((rule) => rule.classification);
    assert.deepEqual(classifications, ["MATERIAL"], `${repositoryPath} must remain material`);
  }
});

test("declared routes cover representative cross-app integration seams", async () => {
  const { policy } = await loadPolicy(POLICY_PATH);

  const networkProxyUiEdge = policy.impactEdges.find(
    (edge) => edge.id === "network-proxy-to-ui"
  );
  assert.deepEqual(
    {
      fromNodeId: networkProxyUiEdge?.fromNodeId,
      toNodeId: networkProxyUiEdge?.toNodeId,
      relation: networkProxyUiEdge?.relation,
      propagation: networkProxyUiEdge?.decisions[0]?.propagation
    },
    {
      fromNodeId: "service-network-proxy-manager",
      toNodeId: "app-ui",
      relation: "API_CLIENT",
      propagation: "STOP"
    },
    "the UI's direct Network Proxy Manager client must not be hidden behind a transitive route"
  );

  const clientRoutes = routeIds(policy, "client-integration-config");
  assert.ok(clientRoutes.has("app-pockethive-mcp"));
  assert.ok(clientRoutes.has("app-vscode"));

  const orchestratorRoutes = routeIds(policy, "service-orchestrator");
  for (const expected of [
    "service-swarm-controller",
    "app-ui",
    "app-pockethive-mcp",
    "app-vscode",
    "worker-generator",
    "worker-moderator",
    "worker-request-builder",
    "worker-processor",
    "worker-http-sequence",
    "worker-db-query",
    "worker-postprocessor",
    "worker-clearing-export",
    "worker-trigger"
  ]) {
    assert.ok(orchestratorRoutes.has(expected), `orchestrator route must reach ${expected}`);
  }

  const workerSdkRoutes = routeIds(policy, "shared-worker-sdk");
  for (const expected of [
    "worker-generator",
    "worker-moderator",
    "worker-request-builder",
    "worker-processor",
    "worker-http-sequence",
    "worker-db-query",
    "worker-postprocessor",
    "worker-clearing-export",
    "worker-trigger"
  ]) {
    assert.ok(workerSdkRoutes.has(expected), `worker SDK route must reach ${expected}`);
  }

  const scenarioRoutes = routeIds(policy, "contract-scenario");
  for (const expected of [
    "service-scenario-manager",
    "service-network-proxy-manager",
    "service-orchestrator",
    "service-swarm-controller",
    "app-ui",
    "app-pockethive-mcp"
  ]) {
    assert.ok(scenarioRoutes.has(expected), `scenario route must reach ${expected}`);
  }

  const observabilityRoutes = routeIds(policy, "shared-observability");
  for (const expected of [
    "shared-clickhouse-sink",
    "shared-journal-postgres",
    "service-orchestrator",
    "service-swarm-controller",
    "infra-grafana",
    "app-pockethive-mcp"
  ]) {
    assert.ok(observabilityRoutes.has(expected), `observability route must reach ${expected}`);
  }
});

test("every real-map origin and change kind is deterministic, bounded, and actionable", async () => {
  const { policy } = await loadPolicy(POLICY_PATH);
  for (const node of policy.impactNodes) {
    for (const changeKind of Object.values(CHANGE_KIND)) {
      const first = resolveImpactRoutes(policy, node.id, changeKind);
      const second = resolveImpactRoutes(policy, node.id, changeKind);
      assert.deepEqual(first, second, `${node.id}:${changeKind} routes must be deterministic`);
      assert.ok(first.length <= policy.impactNodes.length);
      assert.equal(
        new Set(first.map((route) => route.impactNodeId)).size,
        first.length,
        `${node.id}:${changeKind} must emit each reachable node once`
      );
      assert.ok(
        first.every((route) => route.viaEdgeIds.length <= LIMITS.maxTraversalDepth),
        `${node.id}:${changeKind} must stay inside the traversal-depth bound`
      );
      assert.ok(
        first.some((route) => policy.documentationRules.some((rule) =>
          rule.impactNodeIds.includes(route.impactNodeId)
          && rule.impactDepths.includes(route.impactDepth)
          && rule.sourceChangeKinds.includes(changeKind)
        )),
        `${node.id}:${changeKind} must reach an explicit documentation rule`
      );
    }
  }
});

test("no origin can trigger an obligation targeting one of its own source files", async () => {
  const { policy } = await loadPolicy(POLICY_PATH);
  const documentById = new Map(policy.documents.map((document) => [document.id, document]));

  for (const origin of policy.impactNodes) {
    for (const changeKind of Object.values(CHANGE_KIND)) {
      const routes = resolveImpactRoutes(policy, origin.id, changeKind);
      for (const rule of policy.documentationRules) {
        const ruleTriggered = routes.some((route) =>
          rule.impactNodeIds.includes(route.impactNodeId)
          && rule.impactDepths.includes(route.impactDepth)
          && rule.sourceChangeKinds.includes(changeKind)
        );
        if (!ruleTriggered) {
          continue;
        }
        for (const target of rule.targets) {
          const document = documentById.get(target.documentId);
          assert.ok(
            !origin.sourcePaths.some((pattern) => pathMatches(pattern, document.path)),
            `${origin.id}:${changeKind} rule ${rule.id} cannot target source ${document.path}`
          );
        }
      }
    }
  }
});

test("individual worker changes cannot fan out to sibling workers or applications", async () => {
  const { policy } = await loadPolicy(POLICY_PATH);
  const workerNodeIds = policy.impactNodes
    .filter((node) => node.id.startsWith("worker-"))
    .map((node) => node.id)
    .sort();
  assert.equal(workerNodeIds.length, 9);

  for (const workerNodeId of workerNodeIds) {
    for (const changeKind of Object.values(CHANGE_KIND)) {
      assert.deepEqual(
        resolveImpactRoutes(policy, workerNodeId, changeKind).map((route) => route.impactNodeId),
        [workerNodeId],
        `${workerNodeId}:${changeKind} must remain isolated at SELF`
      );
    }
  }
});

test("Edenred-sensitive control, contract, delivery, ingress, auth, and client paths are additive", async () => {
  const { policy } = await loadPolicy(POLICY_PATH);
  const expectedProtection = new Map([
    ["docs/ci/docs-impact-map.yaml", "documentation-automation-control"],
    [".github/workflows/docs.yml", "repository-governance-control"],
    ["CONTRIBUTING.md", "repository-governance-control"],
    ["docs/spec/asyncapi.yaml", "architecture-contracts-protected"],
    ["common/control-plane-core/src/example.java", "control-plane-contracts-protected"],
    ["auth-service/src/example.java", "authentication-contracts-protected"],
    [".amazonq/agents/default.json", "client-integration-protected"],
    [".amazonq/rules/pockethive.md", "client-integration-protected"],
    ["mcp.json", "client-integration-protected"],
    [".devcontainer/devcontainer.json", "local-development-environment-protected"],
    ["rabbitmq/rabbitmq.conf", "messaging-security-protected"],
    ["hiveforge.yaml", "managed-delivery-protected"],
    ["docker-compose.yml", "local-delivery-protected"],
    ["pom.xml", "repository-build-protected"],
    ["network-proxy-haproxy/haproxy.cfg", "network-ingress-protected"],
    ["ui-v2/nginx.conf", "network-ingress-protected"]
  ]);

  for (const [repositoryPath, expectedRuleId] of expectedProtection) {
    const matches = policy.protectionRules
      .filter((rule) => rule.paths.some((pattern) => pathMatches(pattern, repositoryPath)))
      .map((rule) => rule.id);
    assert.ok(matches.includes(expectedRuleId), `${repositoryPath} must trigger ${expectedRuleId}`);
  }

  const mcpRule = policy.documentationRules.find((rule) => rule.id === "mcp-application-docs");
  assert.deepEqual(
    new Set(mcpRule.targets.map((target) => target.documentId)),
    new Set([
      "mcp-package-readme",
      "mcp-integration-guide",
      "authoring-tools-guide"
    ])
  );
  assert.ok(
    routeIds(policy, "app-pockethive-mcp").has("app-vscode"),
    "VS Code projection ownership must remain on the explicit MCP-to-VS Code edge"
  );
});

test("real v2 policy classifies cross-app impact and a future published page from protected selectors", async () => {
  const temporaryRoot = await mkdtemp(path.join(os.tmpdir(), "pockethive-docs-impact-policy-"));
  try {
    const { policy } = await loadPolicy(POLICY_PATH);
    git(temporaryRoot, ["init", "--quiet"]);
    git(temporaryRoot, ["config", "user.name", "PocketHive Policy Test"]);
    git(temporaryRoot, ["config", "user.email", "policy-test@example.invalid"]);

    for (const document of policy.documents) {
      await writeRepositoryFile(temporaryRoot, document.path, `base document: ${document.id}\n`);
    }
    await writeRepositoryFile(
      temporaryRoot,
      "docs/ci/docs-impact-map.yaml",
      await readFile(POLICY_PATH, "utf8")
    );
    await writeRepositoryFile(
      temporaryRoot,
      "docs/ci/docs-impact-map.schema.json",
      "{}\n"
    );
    await writeRepositoryFile(temporaryRoot, "package-lock.json", "{}\n");
    await writeRepositoryFile(temporaryRoot, "package.json", "{}\n");
    await writeRepositoryFile(temporaryRoot, "tools/docs-impact/trusted-sentinel.mjs", "export {};\n");
    await writeRepositoryFile(
      temporaryRoot,
      "orchestrator-service/src/test-fixture.txt",
      "orchestrator base\n"
    );

    git(temporaryRoot, ["add", "--all"]);
    git(temporaryRoot, ["commit", "--quiet", "-m", "base"]);
    const baseSha = git(temporaryRoot, ["rev-parse", "HEAD"]);

    await writeRepositoryFile(
      temporaryRoot,
      "orchestrator-service/src/test-fixture.txt",
      "orchestrator changed\n"
    );
    await writeRepositoryFile(
      temporaryRoot,
      "docs/guides/future-publication-page.md",
      "# Future publication page\n"
    );
    git(temporaryRoot, ["add", "--all"]);
    git(temporaryRoot, ["commit", "--quiet", "-m", "head"]);
    const headSha = git(temporaryRoot, ["rev-parse", "HEAD"]);

    const analysis = await classifyRepository({
      repoRoot: temporaryRoot,
      gitExecutable: findTrustedGitExecutable(),
      repositoryId: policy.repositoryId,
      base: baseSha,
      head: headSha,
      policyPath: "docs/ci/docs-impact-map.yaml"
    });

    assert.equal(analysis.classification, "ACTION_REQUIRED");
    assert.deepEqual(analysis.policyErrors, []);
    assert.equal(analysis.changes.length, 2);
    const orchestratorChange = analysis.changes.find(
      (change) => change.path === "orchestrator-service/src/test-fixture.txt"
    );
    assert.equal(orchestratorChange?.impactNodeId, "service-orchestrator");
    const obligationRuleIds = new Set(
      analysis.documentationObligations.map((obligation) => obligation.ruleId)
    );
    for (const expectedRuleId of [
      "orchestration-implementation-docs",
      "controller-consumer-projections",
      "worker-consumer-projections",
      "ui-consumer-projections",
      "mcp-consumer-projections",
      "vscode-consumer-projections"
    ]) {
      assert.ok(
        obligationRuleIds.has(expectedRuleId),
        `orchestrator change must trigger ${expectedRuleId}`
      );
    }

    assert.deepEqual(
      analysis.publicationValidations.map((validation) => validation.publicationId),
      ["docs-site", "repository", "ui-image-docs"],
      "a new unregistered page must validate every protected-base publication channel that consumes it"
    );
    for (const validation of analysis.publicationValidations) {
      const trigger = validation.triggers.find(
        (candidate) => candidate.path === "docs/guides/future-publication-page.md"
      );
      assert.deepEqual(trigger?.documentBindings, []);
      assert.ok(trigger?.triggerKinds.includes("CONTENT_INPUT"));
    }
  } finally {
    await rm(temporaryRoot, { recursive: true, force: true });
  }
});

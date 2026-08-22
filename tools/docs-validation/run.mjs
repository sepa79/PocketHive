import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync } from "node:fs";
import {
  lstat,
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  realpath,
  rm,
  stat,
  unlink,
} from "node:fs/promises";
import { release, tmpdir } from "node:os";
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

import { canonicalDigest, canonicalJson, sha256 } from "../docs-impact/canonical.mjs";
import {
  adapterReceiptIdentity,
  loadAdapterManifest,
  requireConfiguredAdapter,
} from "./adapters.mjs";
import {
  ARTIFACT_KIND,
  CANDIDATE_MODE,
  CANDIDATE_STABILITY,
  CONTRACT_VALUES,
  RENDERED_ROUTE_SCHEMA_ID,
  RENDER_TARGET,
  RUN_STATUS,
  STAGE_ID,
  STAGE_STATUS,
  VALIDATION_SCHEMA_ID,
  artifactManifestDigest,
  assertReceiptSemantics,
  atomicWriteJson,
  candidateDigest,
  commandSpecDigest,
  fileIdentity,
  readAndValidateRenderedRouteReport,
  receiptId,
  summarizeResults,
  toolDigest,
} from "./evidence.mjs";

const GIT_TIMEOUT_MS = 30_000;
const GIT_MAX_BUFFER_BYTES = 256 * 1024 * 1024;
const NULL_SHA256 = sha256(Buffer.alloc(0));

export class SkipStage extends Error {}
export class StageFailure extends Error {}
export class StageTimeout extends Error {}
export class CommandStartError extends Error {}
export class CommandExitError extends Error {}

function log(message) {
  console.log(`[docs-validation] ${message}`);
}

function normalizedPath(path) {
  return path.split(sep).join("/");
}

function sameFilesystemPath(left, right) {
  const normalize = (value) => {
    const resolved = resolve(value);
    return process.platform === "win32" ? resolved.toLowerCase() : resolved;
  };
  return normalize(left) === normalize(right);
}

function isInside(parent, child) {
  const path = relative(parent, child);
  return path !== "" && path !== ".." && !path.startsWith(`..${sep}`) && !isAbsolute(path);
}

async function assertRepositoryRegularFilePath(repositoryRoot, repositoryPath) {
  if (
    typeof repositoryPath !== "string"
    || repositoryPath.length === 0
    || repositoryPath.includes("\\")
    || normalizedPath(repositoryPath) !== repositoryPath
  ) {
    throw new Error(`Repository file path is not canonical: ${repositoryPath}`);
  }
  const segments = repositoryPath.split("/");
  if (segments.some((segment) => segment.length === 0 || segment === "." || segment === "..")) {
    throw new Error(`Repository file path is not canonical: ${repositoryPath}`);
  }
  let currentPath = repositoryRoot;
  for (const [index, segment] of segments.entries()) {
    currentPath = join(currentPath, segment);
    const metadata = await lstat(currentPath);
    if (metadata.isSymbolicLink()) {
      throw new Error(`Repository file path must not traverse a symbolic link: ${repositoryPath}`);
    }
    const finalSegment = index === segments.length - 1;
    if ((!finalSegment && !metadata.isDirectory()) || (finalSegment && !metadata.isFile())) {
      throw new Error(`Repository identity target is not a regular file: ${repositoryPath}`);
    }
  }
  const resolvedPath = await realpath(currentPath);
  if (!sameFilesystemPath(resolvedPath, currentPath) || !isInside(repositoryRoot, resolvedPath)) {
    throw new Error(`Repository file path resolves through an unsupported filesystem alias: ${repositoryPath}`);
  }
  return currentPath;
}

function parseHttpUrl(value, label) {
  const url = new URL(value);
  if (!new Set(["http:", "https:"]).has(url.protocol)) {
    throw new Error(`${label} must use http or https`);
  }
  if (url.username || url.password) {
    throw new Error(`${label} must not contain credentials`);
  }
  return url.href;
}

export function parseOptions(argv, invocationDirectory = process.cwd()) {
  const allowed = new Set([
    "--profile",
    "--repo",
    "--report",
    "--candidate-mode",
    "--adapter-manifest",
    "--base-url",
    "--docs-url",
  ]);
  const supplied = new Map();
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (!allowed.has(argument)) {
      throw new Error(`Unknown argument: ${argument}`);
    }
    if (supplied.has(argument)) {
      throw new Error(`Duplicate argument: ${argument}`);
    }
    const value = argv[index + 1];
    if (!value || value.startsWith("--")) {
      throw new Error(`${argument} requires a value`);
    }
    supplied.set(argument, value);
    index += 1;
  }

  for (const required of [
    "--profile",
    "--repo",
    "--report",
    "--candidate-mode",
    "--adapter-manifest",
  ]) {
    if (!supplied.has(required)) {
      throw new Error(`${required} is required`);
    }
  }
  const profile = supplied.get("--profile");
  if (!CONTRACT_VALUES.profiles.includes(profile)) {
    throw new Error(
      `Unknown profile '${profile}'. Expected one of: ${CONTRACT_VALUES.profiles.join(", ")}`,
    );
  }
  const candidateMode = supplied.get("--candidate-mode");
  if (!CONTRACT_VALUES.candidateModes.includes(candidateMode)) {
    throw new Error(
      `Unknown candidate mode '${candidateMode}'. Expected one of: ${CONTRACT_VALUES.candidateModes.join(", ")}`,
    );
  }
  const baseUrl = supplied.has("--base-url")
    ? parseHttpUrl(supplied.get("--base-url"), "--base-url")
    : null;
  const docsUrl = supplied.has("--docs-url")
    ? parseHttpUrl(supplied.get("--docs-url"), "--docs-url")
    : null;
  if (new Set(["all", "runtime"]).has(profile) && baseUrl === null) {
    throw new Error(`${profile} profile requires --base-url for the official PocketHive ingress`);
  }
  if (new Set(["all", "deployed"]).has(profile) && docsUrl === null) {
    throw new Error(`${profile} profile requires --docs-url for the deployed documentation site`);
  }
  if (!new Set(["all", "runtime"]).has(profile) && baseUrl !== null) {
    throw new Error(`${profile} profile does not accept --base-url`);
  }
  if (!new Set(["all", "deployed"]).has(profile) && docsUrl !== null) {
    throw new Error(`${profile} profile does not accept --docs-url`);
  }

  const repositoryRoot = resolve(invocationDirectory, supplied.get("--repo"));
  const reportPath = resolve(invocationDirectory, supplied.get("--report"));
  const adapterManifestPath = supplied.get("--adapter-manifest");
  if (!isAbsolute(adapterManifestPath)) {
    throw new Error("--adapter-manifest must be an explicit absolute path");
  }
  if (!reportPath.toLowerCase().endsWith(".json")) {
    throw new Error("--report must identify a .json file");
  }
  return {
    artifactDirectory: `${reportPath}.artifacts`,
    adapterManifestPath,
    baseUrl,
    candidateMode,
    docsUrl,
    profile,
    reportPath,
    repositoryRoot,
  };
}

function gitEnvironment() {
  const environment = Object.fromEntries(
    Object.entries(process.env).filter(([key]) => !key.toUpperCase().startsWith("GIT_")),
  );
  return {
    ...environment,
    GIT_ATTR_NOSYSTEM: "1",
    GIT_CONFIG_GLOBAL: process.platform === "win32" ? "NUL" : "/dev/null",
    GIT_CONFIG_NOSYSTEM: "1",
    GIT_OPTIONAL_LOCKS: "0",
    GIT_TERMINAL_PROMPT: "0",
    LANG: "C",
    LC_ALL: "C",
  };
}

function runGitRaw(repositoryRoot, adapterManifest, args, { acceptStatuses = [0], encoding = "utf8" } = {}) {
  const git = requireConfiguredAdapter(adapterManifest, "git", "Git evidence capture");
  const result = spawnSync(git.path, [
    "--no-pager",
    "--no-replace-objects",
    "-C",
    repositoryRoot,
    ...args,
  ], {
    encoding,
    env: gitEnvironment(),
    maxBuffer: GIT_MAX_BUFFER_BYTES,
    stdio: ["ignore", "pipe", "pipe"],
    timeout: GIT_TIMEOUT_MS,
    windowsHide: true,
  });
  if (result.error) {
    throw new Error(`Git command failed to start: ${result.error.message}`);
  }
  if (!acceptStatuses.includes(result.status)) {
    const detail = Buffer.isBuffer(result.stderr)
      ? result.stderr.toString("utf8")
      : String(result.stderr || result.stdout || "");
    throw new Error(`Git ${args.join(" ")} failed (exit ${result.status}): ${detail.trim()}`);
  }
  return result;
}

function gitText(repositoryRoot, adapterManifest, args) {
  return runGitRaw(repositoryRoot, adapterManifest, args).stdout.trim();
}

async function assertOutputIsolation(repositoryRoot, adapterManifest, outputPath) {
  if (!sameFilesystemPath(repositoryRoot, outputPath) && !isInside(repositoryRoot, outputPath)) {
    return;
  }
  if (sameFilesystemPath(repositoryRoot, outputPath)) {
    throw new Error("Evidence output cannot replace the repository root");
  }
  const repositoryPath = normalizedPath(relative(repositoryRoot, outputPath));
  if (repositoryPath.startsWith(":")) {
    throw new Error("Evidence output repository path must not use Git pathspec magic");
  }
  const tracked = runGitRaw(
    repositoryRoot,
    adapterManifest,
    ["ls-files", "--error-unmatch", "--", repositoryPath],
    { acceptStatuses: [0, 1] },
  );
  if (tracked.status === 0) {
    throw new Error(`Evidence output is tracked by Git: ${repositoryPath}`);
  }
  const ignored = runGitRaw(repositoryRoot, adapterManifest, ["check-ignore", "--quiet", "--", repositoryPath], {
    acceptStatuses: [0, 1],
  });
  if (ignored.status !== 0) {
    throw new Error(
      `In-repository evidence output must be Git-ignored so it cannot change candidate identity: ${repositoryPath}`,
    );
  }
}

export async function resolveAndValidateOptions(options) {
  const repository = await stat(options.repositoryRoot).catch(() => undefined);
  if (!repository?.isDirectory()) {
    throw new Error(`--repo is not a directory: ${options.repositoryRoot}`);
  }
  const repositoryRoot = await realpath(options.repositoryRoot);
  const adapterManifest = await loadAdapterManifest({ manifestPath: options.adapterManifestPath });
  const gitTopLevel = await realpath(gitText(repositoryRoot, adapterManifest, ["rev-parse", "--show-toplevel"]));
  if (!sameFilesystemPath(repositoryRoot, gitTopLevel)) {
    throw new Error(`--repo must be the Git top level: received ${repositoryRoot}, Git reports ${gitTopLevel}`);
  }
  const reportPath = resolve(options.reportPath);
  const artifactDirectory = resolve(options.artifactDirectory);
  if (sameFilesystemPath(reportPath, artifactDirectory)) {
    throw new Error("Report and artifact paths must be distinct");
  }
  await assertOutputIsolation(repositoryRoot, adapterManifest, reportPath);
  await assertOutputIsolation(repositoryRoot, adapterManifest, join(artifactDirectory, ".identity-probe"));
  return {
    ...options,
    adapterManifest,
    artifactDirectory,
    gitTopLevel,
    reportPath,
    repositoryRoot,
  };
}

async function untrackedManifest(repositoryRoot, adapterManifest) {
  const raw = runGitRaw(
    repositoryRoot,
    adapterManifest,
    ["ls-files", "--others", "--exclude-standard", "-z"],
    { encoding: "buffer" },
  ).stdout;
  const paths = raw
    .toString("utf8")
    .split("\0")
    .filter(Boolean)
    .sort((left, right) => left.localeCompare(right));
  if (new Set(paths).size !== paths.length) {
    throw new Error("Git returned duplicate untracked paths");
  }
  if (paths.length > CONTRACT_VALUES.limits.maxUntrackedFiles) {
    throw new Error(
      `Candidate exceeds the explicit ${CONTRACT_VALUES.limits.maxUntrackedFiles}-file untracked limit`,
    );
  }
  const manifest = [];
  let aggregateBytes = 0;
  for (const repositoryPath of paths) {
    const absolutePath = await assertRepositoryRegularFilePath(repositoryRoot, repositoryPath);
    const identity = await fileIdentity(absolutePath, normalizedPath(repositoryPath));
    if (identity.sizeBytes > CONTRACT_VALUES.limits.maxUntrackedFileBytes) {
      throw new Error(
        `Untracked file ${repositoryPath} exceeds the explicit `
        + `${CONTRACT_VALUES.limits.maxUntrackedFileBytes}-byte limit`,
      );
    }
    aggregateBytes += identity.sizeBytes;
    manifest.push({ kind: "FILE", ...identity });
    if (aggregateBytes > CONTRACT_VALUES.limits.maxAggregateUntrackedBytes) {
      throw new Error(
        `Candidate exceeds the explicit `
        + `${CONTRACT_VALUES.limits.maxAggregateUntrackedBytes}-byte aggregate untracked limit`,
      );
    }
  }
  return manifest;
}

async function collectCandidateIdentityOnce(repositoryRoot, mode, adapterManifest) {
  const stagedEntries = runGitRaw(
    repositoryRoot,
    adapterManifest,
    ["ls-files", "--stage", "-z", "--"],
    { encoding: "buffer" },
  ).stdout;
  for (const entry of stagedEntries.subarray(0, Math.max(0, stagedEntries.length - 1)).toString("binary").split("\0")) {
    if (entry.startsWith("160000 ")) {
      throw new Error("Documentation validation candidate identity does not support Git submodules");
    }
  }
  const headSha = gitText(repositoryRoot, adapterManifest, ["rev-parse", "--verify", "HEAD"]);
  const headTreeSha = gitText(repositoryRoot, adapterManifest, ["rev-parse", "--verify", "HEAD^{tree}"]);
  const patch = runGitRaw(
    repositoryRoot,
    adapterManifest,
    [
      "diff",
      "--binary",
      "--no-ext-diff",
      "--no-textconv",
      "--ignore-submodules=none",
      "HEAD",
      "--",
    ],
    { encoding: "buffer" },
  ).stdout;
  const manifest = await untrackedManifest(repositoryRoot, adapterManifest);
  const identity = {
    mode,
    verification: "VERIFIED",
    headSha,
    headTreeSha,
    isClean: patch.byteLength === 0 && manifest.length === 0,
    trackedPatchSha256: sha256(patch),
    untrackedManifestSha256: canonicalDigest(manifest),
    untrackedFileCount: manifest.length,
  };
  return { ...identity, candidateDigest: candidateDigest(identity) };
}

export async function collectCandidateIdentity(repositoryRoot, mode, adapterManifest) {
  if (!adapterManifest) {
    throw new Error("collectCandidateIdentity requires the explicit adapter manifest");
  }
  const first = await collectCandidateIdentityOnce(repositoryRoot, mode, adapterManifest);
  const second = await collectCandidateIdentityOnce(repositoryRoot, mode, adapterManifest);
  if (canonicalJson(first) !== canonicalJson(second)) {
    throw new Error("Candidate changed while its identity was being captured");
  }
  if (mode === CANDIDATE_MODE.COMMITTED_GIT && !first.isClean) {
    throw new Error("COMMITTED_GIT requires a clean tracked and untracked Git worktree");
  }
  return first;
}

async function collectFileIdentities(repositoryRoot, paths) {
  const files = [];
  for (const repositoryPath of paths) {
    const absolutePath = await assertRepositoryRegularFilePath(repositoryRoot, repositoryPath);
    files.push(await fileIdentity(absolutePath, repositoryPath));
  }
  return files;
}

async function collectIdentity(options) {
  const [candidate, toolFiles, lockfiles] = await Promise.all([
    collectCandidateIdentity(options.repositoryRoot, options.candidateMode, options.adapterManifest),
    collectFileIdentities(options.repositoryRoot, CONTRACT_VALUES.toolFiles),
    collectFileIdentities(options.repositoryRoot, CONTRACT_VALUES.lockfilePaths),
  ]);
  return {
    repository: {
      root: options.repositoryRoot,
      gitTopLevel: options.gitTopLevel,
    },
    candidate,
    runtime: {
      nodeVersion: process.version,
      platform: process.platform,
      architecture: process.arch,
      osRelease: release(),
      gitVersion: gitText(options.repositoryRoot, options.adapterManifest, ["--version"]),
    },
    adapters: adapterReceiptIdentity(options.adapterManifest),
    tool: {
      version: CONTRACT_VALUES.toolVersion,
      digest: toolDigest(toolFiles),
      files: toolFiles,
    },
    lockfiles,
  };
}

function childEnvironment(overrides = {}) {
  const environment = { ...process.env, ...overrides };
  for (const legacyFallback of [
    "DOCS_TEST_BASH_EXECUTABLE",
    "DOCS_TEST_BROWSER_EXECUTABLE",
    "DOCS_TEST_MAVEN_EXECUTABLE",
    "DOCS_TEST_MAVEN_REPOSITORY",
    "DOCS_TEST_POWERSHELL_EXECUTABLE",
  ]) {
    delete environment[legacyFallback];
  }
  return environment;
}

function stageAdapter(options, adapterId, purpose) {
  try {
    return requireConfiguredAdapter(options.adapterManifest, adapterId, purpose);
  } catch (error) {
    if (error.code === "ADAPTER_NOT_APPLICABLE") {
      throw new SkipStage(error.message);
    }
    throw error;
  }
}

async function killProcessTree(child, options) {
  if (!child.pid) return;
  if (process.platform === "win32") {
    const taskkill = stageAdapter(options, "taskkill", "Windows process-tree termination");
    spawnSync(taskkill.path, ["/pid", String(child.pid), "/t", "/f"], {
      stdio: "ignore",
      timeout: 10_000,
      windowsHide: true,
    });
    return;
  }
  try {
    process.kill(-child.pid, "SIGKILL");
  } catch (error) {
    if (error?.code !== "ESRCH") throw error;
  }
}

function createStageContext(options, stage, abortController) {
  const children = new Set();
  const artifacts = [];

  async function terminateAll() {
    await Promise.all([...children].map((child) => killProcessTree(child, options)));
  }

  function runCommand(adapterId, args, { cwd = options.repositoryRoot, env = childEnvironment() } = {}) {
    if (abortController.signal.aborted) {
      return Promise.reject(abortController.signal.reason);
    }
    const adapter = stageAdapter(options, adapterId, `${stage.stageId} command execution`);
    if (process.platform === "win32") {
      stageAdapter(options, "taskkill", `${stage.stageId} timeout cleanup`);
    }
    const commandShell = /\.(?:bat|cmd)$/iu.test(adapter.path)
      ? stageAdapter(options, "commandShell", `${adapterId} command-file execution`)
      : null;
    const posixScriptShell = process.platform !== "win32"
      && (adapterId === "npm" || adapterId === "maven")
      ? stageAdapter(options, adapterId === "npm" ? "node" : "commandShell", `${adapterId} script execution`)
      : null;
    const executable = commandShell?.path ?? posixScriptShell?.path ?? adapter.path;
    const commandArgs = commandShell
      ? ["/d", "/s", "/c", "call", adapter.path, ...args]
      : posixScriptShell
        ? [adapter.path, ...args]
        : args;
    const commandEnvironment = childEnvironment(env);
    if (adapterId === "npm") {
      commandEnvironment.npm_config_script_shell = stageAdapter(
        options,
        "commandShell",
        "npm lifecycle scripts",
      ).path;
    }
    log(`COMMAND ${adapterId} ${adapter.path} ${args.join(" ")}`);
    return new Promise((resolvePromise, rejectPromise) => {
      const child = spawn(executable, commandArgs, {
        cwd,
        detached: process.platform !== "win32",
        env: commandEnvironment,
        shell: false,
        stdio: "inherit",
        windowsHide: true,
      });
      children.add(child);
      let settled = false;
      const settle = (action, value) => {
        if (settled) return;
        settled = true;
        abortController.signal.removeEventListener("abort", onAbort);
        children.delete(child);
        action(value);
      };
      const onAbort = () => {
        void killProcessTree(child, options).finally(() =>
          settle(rejectPromise, abortController.signal.reason),
        );
      };
      abortController.signal.addEventListener("abort", onAbort, { once: true });
      child.once("error", (error) =>
        settle(
          rejectPromise,
          new CommandStartError(`${adapterId} failed to start: ${error.message}`),
        ),
      );
      child.once("exit", (code, signal) => {
        if (abortController.signal.aborted) return;
        if (code === 0) {
          settle(resolvePromise);
        } else {
          settle(
            rejectPromise,
            new CommandExitError(
              `${adapterId} failed (${signal ? `signal ${signal}` : `exit ${code}`})`,
            ),
          );
        }
      });
    });
  }

  function addArtifact(artifact) {
    if (abortController.signal.aborted) {
      throw abortController.signal.reason;
    }
    if (artifacts.some((candidate) => candidate.artifactId === artifact.artifactId)) {
      throw new Error(`Duplicate stage artifact ID: ${artifact.artifactId}`);
    }
    artifacts.push(artifact);
  }

  return {
    addArtifact,
    artifacts,
    options,
    runCommand,
    signal: abortController.signal,
    stage,
    terminateAll,
  };
}

function javaEnvironment(context) {
  const java = stageAdapter(context.options, "java", "Maven Java runtime");
  const javaHome = dirname(dirname(java.path));
  return childEnvironment({
    JAVA_HOME: javaHome,
    JAVACMD: java.path,
    MAVEN_SKIP_RC: "1",
  });
}

async function withTemporaryBundlesRoot(action) {
  const bundlesRoot = await mkdtemp(join(tmpdir(), "pockethive-doc-validation-"));
  try {
    await action(bundlesRoot);
  } finally {
    await rm(bundlesRoot, { force: true, recursive: true });
  }
}

async function runMaven(context, args) {
  const maven = stageAdapter(context.options, "maven", "Maven documentation validation");
  if (/^mvnw(?:\.cmd)?$/iu.test(basename(maven.path))) {
    const wrapperJar = resolve(
      context.options.repositoryRoot,
      ".mvn",
      "wrapper",
      "maven-wrapper.jar",
    );
    if (!existsSync(wrapperJar)) {
      throw new StageFailure(
        "Maven wrapper JAR is absent; refusing the wrapper's undeclared PATH/download fallback",
      );
    }
  }
  const localRepository = stageAdapter(
    context.options,
    "localRepository",
    "isolated Maven local repository",
  );
  const effectiveArgs = [`-Dmaven.repo.local=${localRepository.path}`, ...args];
  await context.runCommand("maven", effectiveArgs, {
    env: javaEnvironment(context),
  });
}

async function runMavenTemplatingBuild(context) {
  await runMaven(context, [
    "-pl",
    "tools/scenario-templating-check",
    "-am",
    "-DskipTests",
    "install",
  ]);
}

async function warmMavenTemplatingClasspath(context) {
  const classpathFile = resolve(
    context.options.repositoryRoot,
    "tools",
    "scenario-templating-check",
    "target",
    ".docs-validation-classpath",
  );
  try {
    await runMaven(context, [
      "-f",
      "tools/scenario-templating-check/pom.xml",
      "dependency:build-classpath",
      "-Dmdep.outputFile=target/.docs-validation-classpath",
    ]);
  } finally {
    await rm(classpathFile, { force: true });
  }
}

async function setupDependencies(context) {
  for (const adapterId of ["npm", "java", "maven", "localRepository"]) {
    stageAdapter(context.options, adapterId, "documentation dependency setup");
  }
  await context.runCommand("npm", ["ci"]);
  await context.runCommand("npm", ["ci", "--prefix", "docs-site"]);
  await context.runCommand("npm", ["ci", "--prefix", "tools/pockethive-mcp"]);
  await context.runCommand("npm", ["ci", "--prefix", "tools/scenario-config-migrate"]);
  await runMavenTemplatingBuild(context);
  await warmMavenTemplatingClasspath(context);
  await runMaven(context, ["help:evaluate", "-Dexpression=revision", "-q", "-DforceStdout"]);
}

async function runMcpDoctor(context) {
  await withTemporaryBundlesRoot(async (bundlesRoot) => {
    const unusedBaseUrl = "http://127.0.0.1:9";
    await context.runCommand("npm", ["run", "mcp:doctor", "--", "--no-config"], {
      env: childEnvironment({
        BUNDLES_ROOT: bundlesRoot,
        ORCHESTRATOR_BASE_URL: `${unusedBaseUrl}/orchestrator`,
        PH_BUNDLES_ROOTS: JSON.stringify([bundlesRoot]),
        POCKETHIVE_AUTH_TOKEN: "",
        POCKETHIVE_AUTH_USERNAME: "",
        POCKETHIVE_BASE_URL: unusedBaseUrl,
        POCKETHIVE_ROOT: context.options.repositoryRoot,
        RABBITMQ_MANAGEMENT_BASE_URL: `${unusedBaseUrl}/rabbitmq/api`,
        SCENARIO_MANAGER_BASE_URL: `${unusedBaseUrl}/scenario-manager`,
      }),
    });
  });
}

async function runDocumentedTemplatingCommand(context) {
  await context.runCommand(
    "bash",
    [
      "tools/scenario-templating-check/run.sh",
      "--scenario",
      "scenarios/bundles/local-rest-topology/scenario.yaml",
    ],
    { env: javaEnvironment(context) },
  );
}

async function runComposeConfig(context) {
  await context.runCommand("docker", ["compose", "config", "--quiet"]);
}

async function verifyOfficialIngress(context) {
  const normalized = new URL(context.options.baseUrl);
  normalized.pathname = normalized.pathname.endsWith("/")
    ? normalized.pathname
    : `${normalized.pathname}/`;
  const healthUrl = new URL("healthz", normalized);
  const homeUrl = new URL(".", normalized);
  const health = await fetch(healthUrl, {
    redirect: "error",
    signal: context.signal,
  });
  const healthBody = (await health.text()).trim();
  if (!health.ok || healthBody !== "ok") {
    throw new StageFailure(
      `${healthUrl.href} expected HTTP 2xx with body 'ok'; received HTTP ${health.status} and '${healthBody}'`,
    );
  }
  const home = await fetch(homeUrl, {
    redirect: "follow",
    signal: context.signal,
  });
  await home.body?.cancel();
  if (!home.ok) {
    throw new StageFailure(`${homeUrl.href} returned HTTP ${home.status}`);
  }
  log(`Official ingress verified: ${healthUrl.href} and ${homeUrl.href}`);
}

async function runPackagingCommand(context) {
  const archivePattern = /^pockethive-deployment-.*\.(?:tar\.gz|zip)$/;
  const before = (await readdir(context.options.repositoryRoot)).filter((name) =>
    archivePattern.test(name),
  );
  if (before.length > 0) {
    throw new StageFailure(
      `Packaging test will not overwrite existing archive(s): ${before.join(", ")}`,
    );
  }
  let commandError;
  try {
    const maven = stageAdapter(context.options, "maven", "packaging version resolution");
    const localRepository = stageAdapter(
      context.options,
      "localRepository",
      "packaging Maven local repository",
    );
    const environment = javaEnvironment(context);
    environment.MAVEN_OPTS = `-Dmaven.repo.local=${localRepository.path}`;
    if (process.platform === "win32") {
      const powerShell = stageAdapter(
        context.options,
        "powerShell",
        "packaging PowerShell compression",
      );
      if (basename(maven.path).toLowerCase() !== "mvn.cmd") {
        throw new StageFailure("Windows packaging requires the declared Maven adapter to be mvn.cmd");
      }
      environment.PATH = [
        dirname(maven.path),
        dirname(powerShell.path),
        process.env.SystemRoot ? resolve(process.env.SystemRoot, "System32") : null,
      ]
        .filter(Boolean)
        .join(";");
      await context.runCommand(
        "commandShell",
        ["/d", "/s", "/c", "call", "package-deployment.bat"],
        { env: environment },
      );
    } else {
      if (basename(maven.path) !== "mvn") {
        throw new StageFailure("POSIX packaging requires the declared Maven adapter to be named mvn");
      }
      environment.PATH = `${dirname(maven.path)}:${process.env.PATH ?? ""}`;
      await context.runCommand("bash", ["package-deployment.sh"], { env: environment });
    }
  } catch (error) {
    commandError = error;
  }
  const generated = (await readdir(context.options.repositoryRoot)).filter((name) =>
    archivePattern.test(name),
  );
  try {
    if (commandError) throw commandError;
    if (generated.length !== 1) {
      throw new StageFailure(
        `Packaging command returned success but created ${generated.length} matching archives`,
      );
    }
    const archivePath = resolve(context.options.repositoryRoot, generated[0]);
    const archive = await stat(archivePath);
    if (!archive.isFile() || archive.size === 0) {
      throw new StageFailure(`Generated archive is empty or not a file: ${archivePath}`);
    }
    log(`Generated archive verified: ${generated[0]} (${archive.size} bytes)`);
  } finally {
    for (const archive of generated) {
      await unlink(resolve(context.options.repositoryRoot, archive));
    }
  }
}

async function runRenderedCheck(context, { basePath, suppliedBaseUrl, docsUrl, target }) {
  const chromium = stageAdapter(context.options, "chromium", `${context.stage.stageId} browser`);
  const node = stageAdapter(context.options, "node", `${context.stage.stageId} Node runtime`);
  await mkdir(context.options.artifactDirectory, { recursive: true });
  const artifactPath = resolve(
    context.options.artifactDirectory,
    `${context.stage.stageId.toLowerCase()}.json`,
  );
  await rm(artifactPath, { force: true });
  let commandError;
  try {
    await context.runCommand("npm", ["run", "test:docs:rendered", "--prefix", "docs-site"], {
      env: childEnvironment({
        DOCS_BASE_URL: basePath,
        DOCS_RENDER_TARGET: target,
        DOCS_RENDERED_REPORT_PATH: artifactPath,
        DOCS_TEST_BASE_URL: suppliedBaseUrl,
        DOCS_VALIDATION_CHROMIUM_EXECUTABLE: chromium.path,
        DOCS_VALIDATION_NODE_EXECUTABLE: node.path,
        DOCS_URL: docsUrl,
        POCKETHIVE_APP_URL: "",
      }),
    });
  } catch (error) {
    commandError = error;
  }
  if (existsSync(artifactPath)) {
    await readAndValidateRenderedRouteReport(artifactPath);
    const identity = await fileIdentity(artifactPath, artifactPath);
    context.addArtifact({
      artifactId: "RENDERED_ROUTE_REPORT",
      kind: ARTIFACT_KIND.RENDERED_ROUTE_REPORT,
      schemaId: RENDERED_ROUTE_SCHEMA_ID,
      ...identity,
    });
  }
  if (commandError) throw commandError;
  if (!existsSync(artifactPath)) {
    throw new Error(`Rendered documentation command did not create ${artifactPath}`);
  }
}

async function runContentAudit(context) {
  const bash = stageAdapter(context.options, "bash", "Bash documentation syntax audit");
  const powerShell = stageAdapter(
    context.options,
    "powerShell",
    "PowerShell documentation syntax audit",
  );
  await context.runCommand("node", ["tools/docs-validation/content-audit.mjs"], {
    env: childEnvironment({
      DOCS_VALIDATION_BASH_EXECUTABLE: bash.path,
      DOCS_VALIDATION_POWERSHELL_EXECUTABLE: powerShell.path,
    }),
  });
}

function deployedRenderConfiguration(docsUrl) {
  const deployedUrl = new URL(docsUrl);
  deployedUrl.hash = "";
  deployedUrl.search = "";
  deployedUrl.pathname = deployedUrl.pathname.endsWith("/")
    ? deployedUrl.pathname
    : `${deployedUrl.pathname}/`;
  return {
    basePath: deployedUrl.pathname,
    docsUrl: deployedUrl.origin,
    suppliedBaseUrl: deployedUrl.href,
    target: RENDER_TARGET.DEPLOYED,
  };
}

const STAGE_HANDLERS = Object.freeze({
  [STAGE_ID.DOCS_COMPOSE_AUDIT]: (context) =>
    context.runCommand("node", ["tools/docs-validation/compose-audit.mjs"]),
  [STAGE_ID.DOCS_CONTENT_AUDIT]: runContentAudit,
  [STAGE_ID.DOCS_DEPENDENCY_SETUP]: setupDependencies,
  [STAGE_ID.DOCS_MCP_CONTRACTS]: (context) =>
    context.runCommand("node", ["tools/pockethive-mcp/docs-contract-audit.mjs"]),
  [STAGE_ID.DOCS_RENDER_DEPLOYED]: (context) =>
    runRenderedCheck(context, deployedRenderConfiguration(context.options.docsUrl)),
  [STAGE_ID.DOCS_RENDER_GITHUB_PAGES]: (context) =>
    runRenderedCheck(context, {
      basePath: "/PocketHive/",
      docsUrl: "https://sepa79.github.io",
      suppliedBaseUrl: "",
      target: RENDER_TARGET.LOCAL_STATIC,
    }),
  [STAGE_ID.DOCS_RENDER_ROOT]: (context) =>
    runRenderedCheck(context, {
      basePath: "/",
      docsUrl: "http://127.0.0.1",
      suppliedBaseUrl: "",
      target: RENDER_TARGET.LOCAL_STATIC,
    }),
  [STAGE_ID.DOCS_TYPESCRIPT]: (context) =>
    context.runCommand("npm", ["run", "typecheck", "--prefix", "docs-site"]),
  [STAGE_ID.DOCKER_COMPOSE_CONFIG]: runComposeConfig,
  [STAGE_ID.MCP_DOCTOR]: runMcpDoctor,
  [STAGE_ID.MCP_UNIT_SUITE]: (context) => context.runCommand("npm", ["run", "mcp:test"]),
  [STAGE_ID.OFFICIAL_INGRESS]: verifyOfficialIngress,
  [STAGE_ID.PLATFORM_PACKAGING]: runPackagingCommand,
  [STAGE_ID.SCENARIO_MIGRATION_COMMANDS]: async (context) => {
    await context.runCommand("node", [
      "tools/scenario-config-migrate/cli.mjs",
      "check",
      "scenarios",
    ]);
    await context.runCommand("node", [
      "tools/scenario-config-migrate/cli.mjs",
      "migrate",
      "--dry-run",
      "scenarios",
    ]);
  },
  [STAGE_ID.SCENARIO_MIGRATOR_UNIT_SUITE]: (context) =>
    context.runCommand("npm", ["test", "--prefix", "tools/scenario-config-migrate"]),
  [STAGE_ID.SCENARIO_TEMPLATING_BUILD]: runMavenTemplatingBuild,
  [STAGE_ID.SCENARIO_TEMPLATING_COMMAND]: runDocumentedTemplatingCommand,
});

function stagesFor(profile) {
  return CONTRACT_VALUES.profileStages[profile].map((stageId) => {
    const handler = STAGE_HANDLERS[stageId];
    if (!handler) throw new Error(`No implementation exists for stage ${stageId}`);
    return { stageId, ...CONTRACT_VALUES.stages[stageId], run: handler };
  });
}

function statusForError(error, signal) {
  if (signal.aborted || error instanceof StageTimeout) return STAGE_STATUS.TIMEOUT;
  if (error instanceof SkipStage) return STAGE_STATUS.SKIP;
  if (error instanceof CommandStartError) return STAGE_STATUS.ERROR;
  if (error instanceof CommandExitError || error instanceof StageFailure) return STAGE_STATUS.FAIL;
  return STAGE_STATUS.ERROR;
}

export async function executeStage(stage, options) {
  const startedAt = new Date().toISOString();
  const started = Date.now();
  const abortController = new AbortController();
  const context = createStageContext(options, stage, abortController);
  const timeoutError = new StageTimeout(
    `${stage.stageId} exceeded its declared ${stage.declaredTimeoutMs}ms timeout`,
  );
  let timeoutHandle;
  const timeout = new Promise((resolvePromise, rejectPromise) => {
    timeoutHandle = setTimeout(() => {
      abortController.abort(timeoutError);
      rejectPromise(timeoutError);
    }, stage.declaredTimeoutMs);
  });
  let status = STAGE_STATUS.PASS;
  let detail = null;
  log(`\nSTART ${stage.stageId} - ${stage.name}`);
  try {
    await Promise.race([stage.run(context), timeout]);
  } catch (error) {
    status = statusForError(error, abortController.signal);
    detail = String(error?.message || error);
  } finally {
    clearTimeout(timeoutHandle);
    if (abortController.signal.aborted) {
      await context.terminateAll();
    }
  }
  const completedAt = new Date().toISOString();
  const durationMs = Date.now() - started;
  const artifacts = [...context.artifacts];
  const result = {
    stageId: stage.stageId,
    commandSpecId: stage.commandSpecId,
    commandSpecDigest: commandSpecDigest(stage.stageId, stage),
    name: stage.name,
    required: stage.required,
    declaredTimeoutMs: stage.declaredTimeoutMs,
    startedAt,
    completedAt,
    durationMs,
    status,
    detail,
    artifacts,
    artifactManifestDigest: artifactManifestDigest(artifacts),
  };
  log(`${status} ${stage.stageId} (${(durationMs / 1000).toFixed(1)}s)${detail ? `: ${detail}` : ""}`);
  return result;
}

function configurationFrom(options) {
  return {
    profile: options.profile,
    repositoryRoot: options.repositoryRoot,
    reportPath: options.reportPath,
    artifactDirectory: options.artifactDirectory,
    adapterManifestPath: options.adapterManifest.manifestIdentity.path,
    adapterManifestRawSha256: options.adapterManifest.manifestIdentity.rawSha256,
    adapterManifestCanonicalSha256: options.adapterManifest.manifestIdentity.canonicalSha256,
    candidateMode: options.candidateMode,
    baseUrl: options.baseUrl,
    docsUrl: options.docsUrl,
  };
}

function initialReceipt(options, identity) {
  const configuration = configurationFrom(options);
  const startedAt = new Date().toISOString();
  return {
    schemaVersion: CONTRACT_VALUES.receiptSchemaVersion,
    schemaId: VALIDATION_SCHEMA_ID,
    toolVersion: CONTRACT_VALUES.toolVersion,
    receiptId: NULL_SHA256,
    checkpointSequence: 0,
    runStatus: RUN_STATUS.RUNNING,
    runDetail: null,
    startedAt,
    completedAt: null,
    currentStageId: null,
    configuration,
    identity,
    candidateStability: {
      status: CANDIDATE_STABILITY.PENDING,
      initialCandidateDigest: identity.candidate.candidateDigest,
      completedCandidateDigest: null,
    },
    results: [],
    summary: summarizeResults([]),
  };
}

async function writeCheckpoint(options, receipt) {
  receipt.receiptId = receiptId(receipt);
  await atomicWriteJson(options.reportPath, receipt, assertReceiptSemantics);
  log(`Checkpoint ${receipt.checkpointSequence} written to ${options.reportPath}`);
}

async function finalizeCandidateStability(options, receipt) {
  const completed = await collectCandidateIdentity(
    options.repositoryRoot,
    options.candidateMode,
    options.adapterManifest,
  );
  receipt.candidateStability = {
    status: completed.candidateDigest === receipt.identity.candidate.candidateDigest
      ? CANDIDATE_STABILITY.MATCHED
      : CANDIDATE_STABILITY.MISMATCHED,
    initialCandidateDigest: receipt.identity.candidate.candidateDigest,
    completedCandidateDigest: completed.candidateDigest,
  };
  return completed;
}

export async function runValidation(rawOptions) {
  const options = await resolveAndValidateOptions(rawOptions);
  const identity = await collectIdentity(options);
  const receipt = initialReceipt(options, identity);
  await writeCheckpoint(options, receipt);
  try {
    for (const stage of stagesFor(options.profile)) {
      receipt.currentStageId = stage.stageId;
      receipt.checkpointSequence += 1;
      await writeCheckpoint(options, receipt);
      receipt.results.push(await executeStage(stage, options));
      receipt.currentStageId = null;
      receipt.summary = summarizeResults(receipt.results);
      receipt.checkpointSequence += 1;
      await writeCheckpoint(options, receipt);
    }
    await finalizeCandidateStability(options, receipt);
    if (receipt.candidateStability.status === CANDIDATE_STABILITY.MISMATCHED) {
      receipt.runStatus = RUN_STATUS.ERROR;
      receipt.runDetail = "Candidate identity changed during documentation validation";
    } else {
      receipt.runStatus = RUN_STATUS.COMPLETED;
      receipt.runDetail = null;
    }
  } catch (error) {
    try {
      await finalizeCandidateStability(options, receipt);
    } catch (identityError) {
      receipt.candidateStability = {
        status: CANDIDATE_STABILITY.MISMATCHED,
        initialCandidateDigest: receipt.identity.candidate.candidateDigest,
        completedCandidateDigest: NULL_SHA256,
      };
      receipt.runDetail = `${error.message}; completion identity failed: ${identityError.message}`;
    }
    receipt.runStatus = RUN_STATUS.ERROR;
    receipt.runDetail ||= String(error?.message || error);
  }
  receipt.currentStageId = null;
  receipt.completedAt = new Date().toISOString();
  receipt.summary = summarizeResults(receipt.results);
  receipt.checkpointSequence += 1;
  await writeCheckpoint(options, receipt);
  return receipt;
}

function receiptFailed(receipt) {
  return (
    receipt.runStatus !== RUN_STATUS.COMPLETED ||
    receipt.summary.failed > 0 ||
    receipt.summary.errors > 0 ||
    receipt.summary.timedOut > 0 ||
    receipt.summary.requiredNotPassed > 0
  );
}

async function main() {
  const options = parseOptions(process.argv.slice(2));
  log(`Profile: ${options.profile}`);
  log(`Repository input: ${options.repositoryRoot}`);
  log(`Candidate mode: ${options.candidateMode}`);
  log(`Adapter manifest: ${options.adapterManifestPath}`);
  log(`Report: ${options.reportPath}`);
  if (options.baseUrl) log(`Official ingress: ${options.baseUrl}`);
  if (options.docsUrl) log(`Deployed documentation: ${options.docsUrl}`);
  const receipt = await runValidation(options);

  console.log("\nDocumentation validation summary");
  for (const result of receipt.results) {
    console.log(
      `${result.status.padEnd(7)} ${(result.durationMs / 1000).toFixed(1).padStart(7)}s  ${result.stageId} - ${result.name}${result.detail ? ` - ${result.detail}` : ""}`,
    );
  }
  console.log(JSON.stringify(receipt.summary));
  if (receiptFailed(receipt)) process.exitCode = 1;
}

const invokedPath = process.argv[1] ? resolve(process.argv[1]) : "";
if (invokedPath === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error.stack || error.message);
    process.exitCode = 1;
  });
}

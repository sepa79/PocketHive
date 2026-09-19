import { spawn, spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { mkdir, mkdtemp, readdir, rm, stat, unlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";

import { REPOSITORY_ROOT } from "./docs-scope.mjs";

const PROFILES = new Set([
  "all",
  "deployed",
  "local",
  "packaging",
  "runtime",
  "setup",
  "static",
]);
const NPM = process.platform === "win32" ? "npm.cmd" : "npm";
const MAVEN_WRAPPER = resolve(
  REPOSITORY_ROOT,
  process.platform === "win32" ? "mvnw.cmd" : "mvnw",
);

class SkipStage extends Error {}

function log(message) {
  console.log(`[docs-validation] ${message}`);
}

function parseOptions(argv) {
  const options = {
    baseUrl: undefined,
    docsUrl: undefined,
    profile: "local",
    report: undefined,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (
      argument === "--profile" ||
      argument === "--base-url" ||
      argument === "--docs-url" ||
      argument === "--report"
    ) {
      const value = argv[index + 1];
      if (!value || value.startsWith("--")) {
        throw new Error(`${argument} requires a value`);
      }
      index += 1;
      if (argument === "--profile") options.profile = value;
      if (argument === "--base-url") options.baseUrl = value;
      if (argument === "--docs-url") options.docsUrl = value;
      if (argument === "--report") options.report = resolve(value);
      continue;
    }
    throw new Error(`Unknown argument: ${argument}`);
  }

  if (!PROFILES.has(options.profile)) {
    throw new Error(
      `Unknown profile '${options.profile}'. Expected one of: ${[...PROFILES].sort().join(", ")}`,
    );
  }
  if (["all", "runtime"].includes(options.profile) && !options.baseUrl) {
    throw new Error(`${options.profile} profile requires --base-url for the official PocketHive ingress`);
  }
  if (["all", "deployed"].includes(options.profile) && !options.docsUrl) {
    throw new Error(`${options.profile} profile requires --docs-url for the deployed documentation site`);
  }
  return options;
}

function childEnvironment(overrides = {}) {
  return { ...process.env, ...overrides };
}

function runCommand(command, args, { cwd = REPOSITORY_ROOT, env = process.env } = {}) {
  log(`COMMAND ${command} ${args.join(" ")}`);
  return new Promise((resolvePromise, rejectPromise) => {
    const useWindowsCommandAdapter =
      process.platform === "win32" && /\.(?:bat|cmd)$/i.test(command);
    const executable = useWindowsCommandAdapter
      ? process.env.ComSpec || "C:\\Windows\\System32\\cmd.exe"
      : command;
    const commandArgs = useWindowsCommandAdapter
      ? ["/d", "/s", "/c", "call", command, ...args]
      : args;
    const child = spawn(executable, commandArgs, {
      cwd,
      env,
      shell: false,
      stdio: "inherit",
      windowsHide: true,
    });
    child.once("error", rejectPromise);
    child.once("exit", (code, signal) => {
      if (code === 0) {
        resolvePromise();
      } else {
        rejectPromise(
          new Error(
            `${command} failed (${signal ? `signal ${signal}` : `exit ${code}`})`,
          ),
        );
      }
    });
  });
}

function commandAvailable(command, args = ["--version"]) {
  const result = spawnSync(command, args, {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true,
  });
  return !result.error && result.status === 0;
}

function bashExecutable() {
  const configured = process.env.DOCS_TEST_BASH_EXECUTABLE?.trim();
  if (configured) return existsSync(configured) ? configured : undefined;
  const candidates =
    process.platform === "win32"
      ? [
          "C:\\Program Files\\Git\\bin\\bash.exe",
          "C:\\Program Files\\Git\\usr\\bin\\bash.exe",
        ]
      : ["bash"];
  return candidates.find(
    (candidate) =>
      (candidate === "bash" || existsSync(candidate)) &&
      commandAvailable(candidate),
  );
}

function javaEnvironment() {
  const javaHome = process.env.JAVA_HOME?.trim();
  if (!javaHome) {
    throw new SkipStage("JAVA_HOME is not set; Java 21/Maven documentation checks were not run");
  }
  const javaExecutable = resolve(javaHome, "bin", process.platform === "win32" ? "java.exe" : "java");
  if (!existsSync(javaExecutable)) {
    throw new SkipStage(`JAVA_HOME does not contain a Java executable: ${javaHome}`);
  }
  return childEnvironment({
    JAVA_HOME: javaHome,
    PATH: `${resolve(javaHome, "bin")}${process.platform === "win32" ? ";" : ":"}${process.env.PATH || ""}`,
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

async function setupDependencies() {
  await runCommand(NPM, ["ci"]);
  await runCommand(NPM, ["ci", "--prefix", "docs-site"]);
  await runCommand(NPM, ["ci", "--prefix", "tools/pockethive-mcp"]);
  await runCommand(NPM, ["ci", "--prefix", "tools/scenario-config-migrate"]);
  await runMavenTemplatingBuild();
  await warmMavenTemplatingClasspath();
  await runMaven(["help:evaluate", "-Dexpression=revision", "-q", "-DforceStdout"]);
}

async function runMcpDoctor() {
  await withTemporaryBundlesRoot(async (bundlesRoot) => {
    const unusedBaseUrl = "http://127.0.0.1:9";
    await runCommand(NPM, ["run", "mcp:doctor", "--", "--no-config"], {
      env: childEnvironment({
        BUNDLES_ROOT: bundlesRoot,
        ORCHESTRATOR_BASE_URL: `${unusedBaseUrl}/orchestrator`,
        PH_BUNDLES_ROOTS: JSON.stringify([bundlesRoot]),
        POCKETHIVE_AUTH_TOKEN: "",
        POCKETHIVE_AUTH_USERNAME: "",
        POCKETHIVE_BASE_URL: unusedBaseUrl,
        POCKETHIVE_ROOT: REPOSITORY_ROOT,
        RABBITMQ_MANAGEMENT_BASE_URL: `${unusedBaseUrl}/rabbitmq/api`,
        SCENARIO_MANAGER_BASE_URL: `${unusedBaseUrl}/scenario-manager`,
      }),
    });
  });
}

function mavenExecutable() {
  const configuredMaven = process.env.DOCS_TEST_MAVEN_EXECUTABLE?.trim();
  const maven = configuredMaven || MAVEN_WRAPPER;
  if (!existsSync(maven)) {
    throw new Error(
      configuredMaven
        ? `DOCS_TEST_MAVEN_EXECUTABLE does not exist: ${configuredMaven}`
        : `Maven wrapper is missing: ${MAVEN_WRAPPER}`,
    );
  }
  return maven;
}

async function runMaven(args) {
  const mavenRepository = process.env.DOCS_TEST_MAVEN_REPOSITORY?.trim();
  const effectiveArgs = [...args];
  if (mavenRepository) {
    const repositoryPath = resolve(mavenRepository);
    await mkdir(repositoryPath, { recursive: true });
    effectiveArgs.unshift(`-Dmaven.repo.local=${repositoryPath}`);
  }
  await runCommand(mavenExecutable(), effectiveArgs, { env: javaEnvironment() });
}

async function runMavenTemplatingBuild() {
  await runMaven([
    "-pl",
    "tools/scenario-templating-check",
    "-am",
    "-DskipTests",
    "install",
  ]);
}

async function warmMavenTemplatingClasspath() {
  const classpathFile = resolve(
    REPOSITORY_ROOT,
    "tools",
    "scenario-templating-check",
    "target",
    ".docs-validation-classpath",
  );
  try {
    await runMaven([
      "-f",
      "tools/scenario-templating-check/pom.xml",
      "dependency:build-classpath",
      "-Dmdep.outputFile=target/.docs-validation-classpath",
    ]);
  } finally {
    await rm(classpathFile, { force: true });
  }
}

async function runDocumentedTemplatingCommand() {
  const bash = bashExecutable();
  if (!bash) {
    throw new SkipStage(
      "Git Bash/bash is unavailable; tools/scenario-templating-check/run.sh was not executed",
    );
  }
  await runCommand(
    bash,
    [
      "tools/scenario-templating-check/run.sh",
      "--scenario",
      "scenarios/bundles/local-rest-topology/scenario.yaml",
    ],
    { env: javaEnvironment() },
  );
}

async function runComposeConfig() {
  if (!commandAvailable("docker")) {
    throw new SkipStage("Docker is unavailable; docker compose config was not run");
  }
  await runCommand("docker", ["compose", "config", "--quiet"]);
}

async function verifyOfficialIngress(baseUrl) {
  const normalized = new URL(baseUrl);
  normalized.pathname = normalized.pathname.endsWith("/")
    ? normalized.pathname
    : `${normalized.pathname}/`;
  const healthUrl = new URL("healthz", normalized);
  const homeUrl = new URL(".", normalized);
  const health = await fetch(healthUrl, { redirect: "error" });
  const healthBody = (await health.text()).trim();
  if (!health.ok || healthBody !== "ok") {
    throw new Error(
      `${healthUrl.href} expected HTTP 2xx with body 'ok'; received HTTP ${health.status} and '${healthBody}'`,
    );
  }
  const home = await fetch(homeUrl, { redirect: "follow" });
  await home.body?.cancel();
  if (!home.ok) {
    throw new Error(`${homeUrl.href} returned HTTP ${home.status}`);
  }
  log(`Official ingress verified: ${healthUrl.href} and ${homeUrl.href}`);
}

async function runPackagingCommand() {
  const archivePattern = /^pockethive-deployment-.*\.(?:tar\.gz|zip)$/;
  const before = (await readdir(REPOSITORY_ROOT)).filter((name) => archivePattern.test(name));
  if (before.length > 0) {
    throw new Error(
      `Packaging test will not overwrite existing archive(s): ${before.join(", ")}`,
    );
  }

  let commandError;
  try {
    if (process.platform === "win32") {
      await runCommand("cmd.exe", ["/d", "/c", "package-deployment.bat"]);
    } else {
      const bash = bashExecutable();
      if (!bash) throw new Error("bash is required for package-deployment.sh");
      await runCommand(bash, ["package-deployment.sh"]);
    }
  } catch (error) {
    commandError = error;
  }

  const generated = (await readdir(REPOSITORY_ROOT)).filter((name) => archivePattern.test(name));
  try {
    if (commandError) throw commandError;
    if (generated.length !== 1) {
      throw new Error(
        `Packaging command returned success but created ${generated.length} matching archives`,
      );
    }
    const archivePath = resolve(REPOSITORY_ROOT, generated[0]);
    const archive = await stat(archivePath);
    if (!archive.isFile() || archive.size === 0) {
      throw new Error(`Generated archive is empty or not a file: ${archivePath}`);
    }
    log(`Generated archive verified: ${generated[0]} (${archive.size} bytes)`);
  } finally {
    for (const archive of generated) {
      await unlink(resolve(REPOSITORY_ROOT, archive));
    }
  }
}

function staticStages() {
  return [
    {
      name: "published content examples and shell syntax",
      run: () => runCommand(process.execPath, ["tools/docs-validation/content-audit.mjs"]),
    },
    {
      name: "documentation TypeScript",
      run: () => runCommand(NPM, ["run", "typecheck", "--prefix", "docs-site"]),
    },
    {
      name: "all rendered routes at desktop and mobile widths",
      run: () =>
        runCommand(NPM, ["run", "test:docs:rendered", "--prefix", "docs-site"], {
          env: childEnvironment({
            DOCS_BASE_URL: "/",
            DOCS_TEST_BASE_URL: "",
            DOCS_URL: "http://127.0.0.1",
            POCKETHIVE_APP_URL: "",
          }),
        }),
    },
    {
      name: "GitHub Pages target build",
      run: async () => {
        const env = childEnvironment({
          DOCS_BASE_URL: "/PocketHive/",
          DOCS_TEST_BASE_URL: "",
          DOCS_URL: "https://sepa79.github.io",
          POCKETHIVE_APP_URL: "",
        });
        await runCommand(NPM, ["run", "clear", "--prefix", "docs-site"], { env });
        await runCommand(NPM, ["run", "build", "--prefix", "docs-site"], { env });
      },
    },
    {
      name: "documented MCP tool contracts",
      run: () =>
        runCommand(process.execPath, ["tools/pockethive-mcp/docs-contract-audit.mjs"]),
    },
    {
      name: "Compose service and bind-mount documentation",
      run: () => runCommand(process.execPath, ["tools/docs-validation/compose-audit.mjs"]),
    },
  ];
}

function deployedStages(docsUrl) {
  const deployedUrl = new URL(docsUrl);
  deployedUrl.hash = "";
  deployedUrl.search = "";
  deployedUrl.pathname = deployedUrl.pathname.endsWith("/")
    ? deployedUrl.pathname
    : `${deployedUrl.pathname}/`;
  const docsBasePath = deployedUrl.pathname;
  return [
    {
      name: "deployed documentation routes and links",
      run: () =>
        runCommand(NPM, ["run", "test:docs:rendered", "--prefix", "docs-site"], {
          env: childEnvironment({
            DOCS_BASE_URL: docsBasePath,
            DOCS_TEST_BASE_URL: deployedUrl.href,
            DOCS_URL: deployedUrl.origin,
            POCKETHIVE_APP_URL: "",
          }),
        }),
    },
  ];
}

function localStages() {
  return [
    ...staticStages(),
    {
      name: "PocketHive MCP unit suite",
      run: () => runCommand(NPM, ["run", "mcp:test"]),
    },
    { name: "PocketHive MCP doctor", run: runMcpDoctor },
    {
      name: "scenario migrator unit suite",
      run: () =>
        runCommand(NPM, ["test", "--prefix", "tools/scenario-config-migrate"]),
    },
    {
      name: "documented scenario migration check",
      run: async () => {
        await runCommand(process.execPath, [
          "tools/scenario-config-migrate/cli.mjs",
          "check",
          "scenarios",
        ]);
        await runCommand(process.execPath, [
          "tools/scenario-config-migrate/cli.mjs",
          "migrate",
          "--dry-run",
          "scenarios",
        ]);
      },
    },
    { name: "scenario templating Maven build", run: runMavenTemplatingBuild },
    {
      name: "documented scenario templating shell command",
      run: runDocumentedTemplatingCommand,
    },
    { name: "Docker Compose configuration", run: runComposeConfig },
  ];
}

function stagesFor(options) {
  if (options.profile === "setup") {
    return [{ name: "documentation test dependencies", run: setupDependencies }];
  }
  if (options.profile === "static") return staticStages();
  if (options.profile === "deployed") return deployedStages(options.docsUrl);
  if (options.profile === "local") return localStages();
  if (options.profile === "packaging") {
    return [{ name: "platform deployment packaging command", run: runPackagingCommand }];
  }
  if (options.profile === "runtime") {
    return [
      ...localStages(),
      {
        name: "official ingress health and home",
        run: () => verifyOfficialIngress(options.baseUrl),
      },
    ];
  }
  return [
    ...localStages(),
    ...deployedStages(options.docsUrl),
    {
      name: "official ingress health and home",
      run: () => verifyOfficialIngress(options.baseUrl),
    },
    { name: "platform deployment packaging command", run: runPackagingCommand },
  ];
}

async function executeStages(stages) {
  const results = [];
  for (const stage of stages) {
    const started = Date.now();
    log(`\nSTART ${stage.name}`);
    try {
      await stage.run();
      const durationMs = Date.now() - started;
      results.push({ durationMs, name: stage.name, status: "PASS" });
      log(`PASS ${stage.name} (${(durationMs / 1000).toFixed(1)}s)`);
    } catch (error) {
      const durationMs = Date.now() - started;
      const status = error instanceof SkipStage ? "SKIP" : "FAIL";
      results.push({
        detail: error.message,
        durationMs,
        name: stage.name,
        status,
      });
      log(`${status} ${stage.name}: ${error.message}`);
    }
  }
  return results;
}

async function writeReport(path, options, results, startedAt) {
  if (!path) return;
  const report = {
    baseUrl: options.baseUrl,
    completedAt: new Date().toISOString(),
    docsUrl: options.docsUrl,
    node: process.version,
    platform: `${process.platform}/${process.arch}`,
    profile: options.profile,
    repositoryRoot: REPOSITORY_ROOT,
    results,
    startedAt,
    summary: {
      failed: results.filter((result) => result.status === "FAIL").length,
      passed: results.filter((result) => result.status === "PASS").length,
      skipped: results.filter((result) => result.status === "SKIP").length,
    },
  };
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, `${JSON.stringify(report, null, 2)}\n`, "utf8");
  log(`Report written to ${path}`);
}

async function main() {
  const options = parseOptions(process.argv.slice(2));
  const startedAt = new Date().toISOString();
  log(`Profile: ${options.profile}`);
  log(`Repository: ${REPOSITORY_ROOT}`);
  log(`Runtime: ${process.version} on ${process.platform}/${process.arch}`);
  if (options.baseUrl) log(`Official ingress: ${options.baseUrl}`);
  if (options.docsUrl) log(`Deployed documentation: ${options.docsUrl}`);

  const results = await executeStages(stagesFor(options));
  console.log("\nDocumentation validation summary");
  for (const result of results) {
    console.log(
      `${result.status.padEnd(4)} ${(result.durationMs / 1000).toFixed(1).padStart(7)}s  ${result.name}${result.detail ? ` - ${result.detail}` : ""}`,
    );
  }
  await writeReport(options.report, options, results, startedAt);

  if (results.some((result) => result.status === "FAIL")) {
    process.exitCode = 1;
  }
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});

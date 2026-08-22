import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { existsSync } from "node:fs";
import { readFile, readdir, stat } from "node:fs/promises";
import { dirname, extname, isAbsolute, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

import {
  CONTRACT_VALUES,
  RENDER_OUTCOME_STATUS,
  RENDER_TARGET,
  RENDERED_ROUTE_SCHEMA_ID,
  assertRenderedRouteSemantics,
  atomicWriteJson,
} from "../../tools/docs-validation/evidence.mjs";

const SITE_DIRECTORY = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const BUILD_DIRECTORY = join(SITE_DIRECTORY, "build");
const REQUEST_TIMEOUT_MS = 15_000;
const RENDER_TIMEOUT_MS = 20_000;
const OVERFLOW_TOLERANCE_PX = 2;

const ROUTE_REQUIREMENTS = new Map([
  [
    "system-architecture/",
    {
    minimumMermaidDiagrams: 6,
    },
  ],
  [
    "guides/operators/swarm-lifecycle/",
    {
      minimumMermaidDiagrams: 1,
      requirePagination: true,
    },
  ],
]);

const VIEWPORTS = CONTRACT_VALUES.viewports;

const MIME_TYPES = new Map([
  [".css", "text/css; charset=utf-8"],
  [".gif", "image/gif"],
  [".html", "text/html; charset=utf-8"],
  [".ico", "image/x-icon"],
  [".jpeg", "image/jpeg"],
  [".jpg", "image/jpeg"],
  [".js", "text/javascript; charset=utf-8"],
  [".json", "application/json; charset=utf-8"],
  [".map", "application/json; charset=utf-8"],
  [".png", "image/png"],
  [".svg", "image/svg+xml; charset=utf-8"],
  [".txt", "text/plain; charset=utf-8"],
  [".webp", "image/webp"],
  [".woff", "font/woff"],
  [".woff2", "font/woff2"],
]);

function log(message) {
  console.log(`[docs-rendered] ${message}`);
}

function normalizeBasePath(value) {
  const withLeadingSlash = value.startsWith("/") ? value : `/${value}`;
  return withLeadingSlash.endsWith("/")
    ? withLeadingSlash
    : `${withLeadingSlash}/`;
}

function normalizeBaseUrl(value) {
  const parsed = new URL(value);
  parsed.hash = "";
  parsed.search = "";
  parsed.pathname = normalizeBasePath(parsed.pathname);
  return parsed;
}

function routeUrl(baseUrl, path) {
  return new URL(path.replace(/^\/+/, ""), baseUrl).href;
}

async function findIndexFiles(directory, found = []) {
  const entries = await readdir(directory, { withFileTypes: true });
  for (const entry of entries) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      await findIndexFiles(path, found);
    } else if (entry.name === "index.html") {
      found.push(path);
    }
  }
  return found;
}

async function discoverRoutes() {
  const indexFiles = await findIndexFiles(BUILD_DIRECTORY);
  const routes = indexFiles.map((indexFile) => {
    const routeDirectory = relative(BUILD_DIRECTORY, dirname(indexFile));
    const path = routeDirectory
      ? `${routeDirectory.split(sep).join("/")}/`
      : "";
    return {
      name: path || "documentation home",
      path,
      ...(ROUTE_REQUIREMENTS.get(path) || {}),
    };
  });
  routes.sort((left, right) => left.path.localeCompare(right.path));
  if (routes.length === 0) {
    throw new Error(`No generated documentation routes found in ${BUILD_DIRECTORY}`);
  }
  return routes;
}

function runDocusaurus(command) {
  const docusaurusCli = join(
    SITE_DIRECTORY,
    "node_modules",
    "@docusaurus",
    "core",
    "bin",
    "docusaurus.mjs",
  );
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(process.execPath, [docusaurusCli, command], {
      cwd: SITE_DIRECTORY,
      env: process.env,
      stdio: "inherit",
    });

    child.once("error", rejectPromise);
    child.once("exit", (code, signal) => {
      if (code === 0) {
        resolvePromise();
        return;
      }

      rejectPromise(
        new Error(
          `Docusaurus ${command} failed (${signal ? `signal ${signal}` : `exit ${code}`})`,
        ),
      );
    });
  });
}

async function runBuild() {
  log("Clearing generated Docusaurus state");
  await runDocusaurus("clear");
  log("Building the documentation site");
  await runDocusaurus("build");
}

async function resolveStaticFile(requestPath, basePath) {
  if (basePath !== "/" && requestPath === basePath.slice(0, -1)) {
    requestPath = basePath;
  }

  if (!requestPath.startsWith(basePath)) {
    return undefined;
  }

  const relativePath = decodeURIComponent(requestPath.slice(basePath.length));
  const requestedFile = resolve(BUILD_DIRECTORY, relativePath);
  const buildPrefix = `${BUILD_DIRECTORY}${sep}`;
  if (requestedFile !== BUILD_DIRECTORY && !requestedFile.startsWith(buildPrefix)) {
    return undefined;
  }

  const candidates = requestPath.endsWith("/")
    ? [join(requestedFile, "index.html")]
    : [requestedFile, join(requestedFile, "index.html")];

  for (const candidate of candidates) {
    try {
      if ((await stat(candidate)).isFile()) {
        return candidate;
      }
    } catch (error) {
      if (error?.code !== "ENOENT") {
        throw error;
      }
    }
  }

  return undefined;
}

async function startStaticServer(basePath) {
  if (!existsSync(join(BUILD_DIRECTORY, "index.html"))) {
    throw new Error(`Build output is missing: ${BUILD_DIRECTORY}`);
  }

  const server = createServer(async (request, response) => {
    try {
      if (request.method !== "GET" && request.method !== "HEAD") {
        response.writeHead(405, { Allow: "GET, HEAD" });
        response.end();
        return;
      }

      const requestUrl = new URL(request.url || "/", "http://127.0.0.1");
      const file = await resolveStaticFile(requestUrl.pathname, basePath);
      if (!file) {
        response.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
        response.end("Not found");
        return;
      }

      const body = await readFile(file);
      response.writeHead(200, {
        "Cache-Control": "no-store",
        "Content-Length": body.byteLength,
        "Content-Type": MIME_TYPES.get(extname(file).toLowerCase()) || "application/octet-stream",
      });
      response.end(request.method === "HEAD" ? undefined : body);
    } catch (error) {
      response.writeHead(500, { "Content-Type": "text/plain; charset=utf-8" });
      response.end(String(error));
    }
  });

  await new Promise((resolvePromise, rejectPromise) => {
    server.once("error", rejectPromise);
    server.listen(0, "127.0.0.1", resolvePromise);
  });

  const address = server.address();
  if (!address || typeof address === "string") {
    throw new Error("Unable to determine the documentation test server address");
  }

  const baseUrl = new URL(basePath, `http://127.0.0.1:${address.port}`);
  return {
    baseUrl,
    close: () => new Promise((resolvePromise) => server.close(resolvePromise)),
  };
}

function findBrowserExecutable() {
  const configured = process.env.DOCS_VALIDATION_CHROMIUM_EXECUTABLE?.trim();
  if (!configured) {
    throw new Error(
      "DOCS_VALIDATION_CHROMIUM_EXECUTABLE must declare the Chromium-based browser adapter",
    );
  }
  if (!isAbsolute(configured) || !existsSync(configured)) {
    throw new Error(
      `DOCS_VALIDATION_CHROMIUM_EXECUTABLE must be an existing absolute file: ${configured}`,
    );
  }
  return configured;
}

function formatConsoleMessage(message) {
  const location = message.location();
  const source = location.url
    ? ` (${location.url}${location.lineNumber ? `:${location.lineNumber}` : ""})`
    : "";
  return `${message.text()}${source}`;
}

async function inspectPage(
  browser,
  baseUrl,
  route,
  viewport,
  internalLinks,
  imageSources,
) {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage();
  const url = routeUrl(baseUrl, route.path);
  const label = `${route.name} [${viewport.id} ${viewport.width}x${viewport.height}]`;
  const result = {
    routePath: route.path,
    routeName: route.name,
    viewportId: viewport.id,
    viewportWidth: viewport.width,
    viewportHeight: viewport.height,
    url,
    status: "FAIL",
    navigation: { statusCode: null, passed: false },
    consoleErrors: [],
    pageErrors: [],
    imageLoad: { checked: 0, failed: 0, failures: [] },
    mermaid: {
      minimumRequired: route.minimumMermaidDiagrams || 0,
      rendered: 0,
      passed: false,
      failures: [],
    },
    pagination: {
      required: route.requirePagination === true,
      previous: 0,
      next: 0,
      passed: route.requirePagination !== true,
    },
    overflow: {
      tolerancePx: OVERFLOW_TOLERANCE_PX,
      rootOverflowPx: 0,
      passed: false,
      offenders: [],
    },
    auditErrors: [],
  };

  page.on("console", (message) => {
    if (message.type() === "error") {
      result.consoleErrors.push(formatConsoleMessage(message));
    }
  });
  page.on("pageerror", (error) => {
    result.pageErrors.push(error.stack || error.message);
  });

  try {
    const response = await page.goto(url, {
      waitUntil: "networkidle",
      timeout: RENDER_TIMEOUT_MS,
    });
    result.navigation = {
      statusCode: response?.status() ?? null,
      passed: response?.ok() === true,
    };

    await page.locator("#__docusaurus").waitFor({
      state: "attached",
      timeout: RENDER_TIMEOUT_MS,
    });
    await page.evaluate(() => document.fonts?.ready);

    const imageState = await page.evaluate(() => ({
      checked: document.images.length,
      broken: [...document.images]
        .filter((image) => image.complete && image.naturalWidth === 0)
        .map((image) => ({ alt: image.alt, src: image.currentSrc || image.src })),
    }));
    result.imageLoad.checked = imageState.checked;
    result.imageLoad.failed = imageState.broken.length;
    result.imageLoad.failures = imageState.broken.map(
      (image) => `${image.src} did not load (alt=${JSON.stringify(image.alt)})`,
    );

    if (route.minimumMermaidDiagrams) {
      try {
        await page.waitForFunction(
          (minimum) =>
            document.querySelectorAll(".docusaurus-mermaid-container svg").length >= minimum,
          route.minimumMermaidDiagrams,
          { timeout: RENDER_TIMEOUT_MS },
        );
      } catch {
        result.mermaid.failures.push(
          `expected at least ${route.minimumMermaidDiagrams} rendered diagrams`,
        );
      }
    }
    result.mermaid.rendered = await page.locator(".docusaurus-mermaid-container svg").count();
    if (result.mermaid.rendered < result.mermaid.minimumRequired) {
      result.mermaid.failures.push(
        `expected at least ${result.mermaid.minimumRequired} rendered diagrams, found ${result.mermaid.rendered}`,
      );
    }

    const mermaidFailures = await page.evaluate(() => {
      const errorTextPattern = /mermaid|syntax error|parse error/i;
      return [...document.querySelectorAll("article [role='alert'], article .alert--danger")]
        .map((element) => element.textContent?.trim() || "")
        .filter((text) => errorTextPattern.test(text));
    });
    for (const failure of mermaidFailures) {
      result.mermaid.failures.push(failure);
    }
    result.mermaid.passed = result.mermaid.failures.length === 0;

    const pagination = await page.evaluate(() => ({
      next: document.querySelectorAll(".pagination-nav__link--next").length,
      previous: document.querySelectorAll(".pagination-nav__link--prev").length,
    }));
    result.pagination.previous = pagination.previous;
    result.pagination.next = pagination.next;
    result.pagination.passed =
      !result.pagination.required || (pagination.previous === 1 && pagination.next === 1);

    const overflow = await page.evaluate((tolerance) => {
      const rootOverflow = Math.max(0,
        document.documentElement.scrollWidth - document.documentElement.clientWidth,
        document.body.scrollWidth - document.body.clientWidth,
      );

      const offenders = [...document.body.querySelectorAll("*")]
        .filter((element) => {
          if (!(element instanceof HTMLElement)) {
            return false;
          }

          const style = getComputedStyle(element);
          if (
            element.tagName === "LI" ||
            style.display === "none" ||
            style.visibility === "hidden" ||
            style.overflowX === "auto" ||
            style.overflowX === "scroll" ||
            style.overflowX === "hidden" ||
            style.overflowX === "clip"
          ) {
            return false;
          }

          const rectangle = element.getBoundingClientRect();
          return (
            rectangle.width > 0 &&
            rectangle.height > 0 &&
            element.scrollWidth - element.clientWidth > tolerance
          );
        })
        .slice(0, 8)
        .map((element) => ({
          className: typeof element.className === "string" ? element.className : "",
          clientWidth: element.clientWidth,
          scrollWidth: element.scrollWidth,
          tag: element.tagName.toLowerCase(),
        }));

      return { offenders, rootOverflow };
    }, OVERFLOW_TOLERANCE_PX);
    result.overflow.rootOverflowPx = overflow.rootOverflow;
    result.overflow.offenders = overflow.offenders;
    result.overflow.passed =
      overflow.rootOverflow <= OVERFLOW_TOLERANCE_PX && overflow.offenders.length === 0;

    if (viewport.id === "DESKTOP") {
      const links = await page.locator("a[href]").evaluateAll((anchors) =>
        anchors.map((anchor) => anchor.href),
      );
      for (const link of links) {
        internalLinks.add(link);
      }

      const images = await page.locator("img[src]").evaluateAll((elements) =>
        elements.map((image) => image.currentSrc || image.src),
      );
      for (const image of images) {
        imageSources.add(image);
      }
    }
  } catch (error) {
    result.auditErrors.push(error.stack || error.message);
  } finally {
    await context.close();
  }
  result.status =
    result.navigation.passed &&
    result.consoleErrors.length === 0 &&
    result.pageErrors.length === 0 &&
    result.imageLoad.failed === 0 &&
    result.mermaid.passed &&
    result.pagination.passed &&
    result.overflow.passed &&
    result.auditErrors.length === 0
      ? "PASS"
      : "FAIL";
  if (result.status === "PASS") log(`PASS ${label}`);
  return result;
}

function escapeAttribute(value) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll('"', "&quot;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

async function validateInternalLinks(baseUrl, links) {
  const uniqueLinks = new Map();

  for (const href of links) {
    const url = new URL(href);
    if (url.origin !== baseUrl.origin || !url.pathname.startsWith(baseUrl.pathname)) {
      continue;
    }
    uniqueLinks.set(url.href, url);
  }

  const results = [];
  for (const url of uniqueLinks.values()) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    let response;
    const result = {
      url: url.href,
      status: "PASS",
      statusCode: null,
      fragment: url.hash ? decodeURIComponent(url.hash.slice(1)) : null,
      detail: null,
    };
    try {
      const requestUrl = new URL(url);
      requestUrl.hash = "";
      response = await fetch(requestUrl, {
        redirect: "follow",
        signal: controller.signal,
      });
      result.statusCode = response.status;
      if (!response.ok) {
        result.status = "FAIL";
        result.detail = `returned HTTP ${response.status}`;
      } else if (url.hash) {
        const contentType = response.headers.get("content-type") || "";
        if (!contentType.includes("text/html")) {
          result.status = "FAIL";
          result.detail = "fragment target is not an HTML document";
        } else {
          const body = await response.text();
          const fragment = escapeAttribute(result.fragment);
          if (
            fragment &&
            !body.includes(`id="${fragment}"`) &&
            !body.includes(`name="${fragment}"`)
          ) {
            result.status = "FAIL";
            result.detail = "points to a missing fragment";
          }
        }
      }
    } catch (error) {
      result.status = "FAIL";
      result.detail = `could not be checked (${error.message})`;
    } finally {
      clearTimeout(timeout);
      if (response && !response.bodyUsed) {
        await response.body?.cancel();
      }
    }
    results.push(result);
  }

  if (results.every((result) => result.status === "PASS")) {
    log(`PASS ${uniqueLinks.size} unique internal links`);
  }
  return results;
}

async function validateImageSources(baseUrl, sources) {
  const results = [];
  const uniqueSources = new Map();

  for (const source of sources) {
    const url = new URL(source);
    if (url.origin !== baseUrl.origin) {
      continue;
    }
    if (!url.pathname.startsWith(baseUrl.pathname)) {
      results.push({
        url: url.href,
        status: "FAIL",
        statusCode: null,
        contentType: null,
        detail: `escapes the configured base path ${baseUrl.pathname}`,
      });
      continue;
    }
    uniqueSources.set(url.href, url);
  }

  for (const url of uniqueSources.values()) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    let response;
    const result = {
      url: url.href,
      status: "PASS",
      statusCode: null,
      contentType: null,
      detail: null,
    };
    try {
      response = await fetch(url, {
        redirect: "follow",
        signal: controller.signal,
      });
      result.statusCode = response.status;
      result.contentType = response.headers.get("content-type");
      if (!response.ok) {
        result.status = "FAIL";
        result.detail = `returned HTTP ${response.status}`;
      } else if (!(result.contentType || "").startsWith("image/")) {
        result.status = "FAIL";
        result.detail = `returned ${result.contentType || "no content type"}`;
      }
    } catch (error) {
      result.status = "FAIL";
      result.detail = `could not be checked (${error.message})`;
    } finally {
      clearTimeout(timeout);
      if (response && !response.bodyUsed) {
        await response.body?.cancel();
      }
    }
    results.push(result);
  }

  if (results.every((result) => result.status === "PASS")) {
    log(`PASS ${uniqueSources.size} unique image sources`);
  }
  return results;
}

function requiredConfiguration() {
  const reportPath = process.env.DOCS_RENDERED_REPORT_PATH?.trim();
  const basePathValue = process.env.DOCS_BASE_URL?.trim();
  const renderTarget = process.env.DOCS_RENDER_TARGET?.trim();
  const nodeExecutable = process.env.DOCS_VALIDATION_NODE_EXECUTABLE?.trim();
  if (
    !nodeExecutable
    || !isAbsolute(nodeExecutable)
    || resolve(nodeExecutable).toLowerCase() !== resolve(process.execPath).toLowerCase()
  ) {
    throw new Error("DOCS_VALIDATION_NODE_EXECUTABLE must exactly identify process.execPath");
  }
  if (!reportPath || !isAbsolute(reportPath)) {
    throw new Error("DOCS_RENDERED_REPORT_PATH must be an explicit absolute JSON output path");
  }
  if (!basePathValue) {
    throw new Error("DOCS_BASE_URL is required");
  }
  if (!CONTRACT_VALUES.renderTargets.includes(renderTarget)) {
    throw new Error(
      `DOCS_RENDER_TARGET must be one of: ${CONTRACT_VALUES.renderTargets.join(", ")}`,
    );
  }
  const suppliedBaseUrl = process.env.DOCS_TEST_BASE_URL?.trim() || "";
  if (renderTarget === RENDER_TARGET.DEPLOYED && !suppliedBaseUrl) {
    throw new Error("DEPLOYED render target requires DOCS_TEST_BASE_URL");
  }
  if (renderTarget === RENDER_TARGET.LOCAL_STATIC && suppliedBaseUrl) {
    throw new Error("LOCAL_STATIC render target does not accept DOCS_TEST_BASE_URL");
  }
  return {
    basePath: normalizeBasePath(basePathValue),
    renderTarget,
    reportPath,
    suppliedBaseUrl,
  };
}

function emptyReport(configuration) {
  return {
    schemaVersion: 1,
    schemaId: RENDERED_ROUTE_SCHEMA_ID,
    generatedAt: new Date().toISOString(),
    status: RENDER_OUTCOME_STATUS.ERROR,
    detail: "Rendered documentation validation did not complete",
    renderTarget: configuration.renderTarget,
    configuredBasePath: configuration.basePath,
    testedBaseUrl: null,
    platform: {
      nodeVersion: process.version,
      operatingSystem: process.platform,
      architecture: process.arch,
    },
    browser: {
      engine: "CHROMIUM",
      executablePath: null,
      version: null,
    },
    build: {
      status: RENDER_OUTCOME_STATUS.ERROR,
      detail: "Docusaurus build did not complete",
    },
    summary: {
      routes: 0,
      viewports: 0,
      routeViewportChecks: 0,
      routeViewportPassed: 0,
      routeViewportFailed: 0,
      linksChecked: 0,
      linksFailed: 0,
      imagesChecked: 0,
      imagesFailed: 0,
    },
    routeViewportResults: [],
    links: [],
    images: [],
  };
}

function updateSummary(report) {
  report.summary = {
    routes: new Set(report.routeViewportResults.map((result) => result.routePath)).size,
    viewports: new Set(report.routeViewportResults.map((result) => result.viewportId)).size,
    routeViewportChecks: report.routeViewportResults.length,
    routeViewportPassed: report.routeViewportResults.filter((result) => result.status === "PASS").length,
    routeViewportFailed: report.routeViewportResults.filter((result) => result.status === "FAIL").length,
    linksChecked: report.links.length,
    linksFailed: report.links.filter((result) => result.status === "FAIL").length,
    imagesChecked: report.images.length,
    imagesFailed: report.images.filter((result) => result.status === "FAIL").length,
  };
}

async function main() {
  const configuration = requiredConfiguration();
  const report = emptyReport(configuration);
  let localServer;
  let browser;

  try {
    await runBuild();
    report.build = { status: RENDER_OUTCOME_STATUS.PASS, detail: null };
    const routes = await discoverRoutes();
    log(`Discovered ${routes.length} generated documentation routes`);

    let baseUrl;
    if (configuration.renderTarget === RENDER_TARGET.DEPLOYED) {
      baseUrl = normalizeBaseUrl(configuration.suppliedBaseUrl);
      log(`Testing supplied site ${baseUrl.href}`);
    } else {
      localServer = await startStaticServer(configuration.basePath);
      baseUrl = localServer.baseUrl;
      log(`Testing fresh build at ${baseUrl.href}`);
    }
    report.testedBaseUrl = baseUrl.href;

    const { chromium } = await import("playwright-core");
    const executablePath = findBrowserExecutable();
    log(`Using browser ${executablePath}`);
    report.browser.executablePath = executablePath;
    browser = await chromium.launch({ executablePath, headless: true });
    report.browser.version = browser.version();

    const internalLinks = new Set();
    const imageSources = new Set();
    for (const viewport of VIEWPORTS) {
      for (const route of routes) {
        report.routeViewportResults.push(
          await inspectPage(
              browser,
              baseUrl,
              route,
              viewport,
              internalLinks,
              imageSources,
            ),
        );
      }
    }
    report.links = await validateInternalLinks(baseUrl, internalLinks);
    report.images = await validateImageSources(baseUrl, imageSources);
    updateSummary(report);
    const failureCount =
      report.summary.routeViewportFailed +
      report.summary.linksFailed +
      report.summary.imagesFailed;
    if (failureCount > 0) {
      report.status = RENDER_OUTCOME_STATUS.FAIL;
      report.detail = `${failureCount} rendered documentation check(s) failed`;
      console.error(`\nRendered documentation check failed (${failureCount})`);
      process.exitCode = 1;
    } else {
      report.status = RENDER_OUTCOME_STATUS.PASS;
      report.detail = null;
      log(
        `PASS ${routes.length} routes at ${VIEWPORTS.length} viewports; rendered documentation is healthy`,
      );
    }
  } catch (error) {
    report.status = RENDER_OUTCOME_STATUS.ERROR;
    report.detail = error.stack || error.message;
    if (report.build.status !== RENDER_OUTCOME_STATUS.PASS) {
      report.build = {
        status: RENDER_OUTCOME_STATUS.ERROR,
        detail: error.stack || error.message,
      };
    }
    process.exitCode = 1;
    console.error(error.stack || error.message);
  } finally {
    try {
      await browser?.close();
      await localServer?.close();
    } catch (error) {
      report.status = RENDER_OUTCOME_STATUS.ERROR;
      report.detail = `Resource cleanup failed: ${error.stack || error.message}`;
      process.exitCode = 1;
    }
  }
  report.generatedAt = new Date().toISOString();
  updateSummary(report);
  await atomicWriteJson(
    configuration.reportPath,
    report,
    assertRenderedRouteSemantics,
  );
  log(`Structured report written to ${configuration.reportPath}`);
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});

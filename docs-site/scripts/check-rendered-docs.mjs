import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { existsSync } from "node:fs";
import { readFile, readdir, stat } from "node:fs/promises";
import { dirname, extname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

import { chromium } from "playwright-core";

const SITE_DIRECTORY = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const BUILD_DIRECTORY = join(SITE_DIRECTORY, "build");
const REQUEST_TIMEOUT_MS = 15_000;
const RENDER_TIMEOUT_MS = 20_000;
const OVERFLOW_TOLERANCE_PX = 2;

const ROUTE_REQUIREMENTS = new Map([
  [
    "system-architecture/",
    {
    minimumMermaidDiagrams: 7,
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

const VIEWPORTS = [
  { name: "desktop", width: 1440, height: 1000 },
  { name: "narrow", width: 390, height: 844 },
];

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

async function startStaticServer() {
  if (!existsSync(join(BUILD_DIRECTORY, "index.html"))) {
    throw new Error(`Build output is missing: ${BUILD_DIRECTORY}`);
  }

  const basePath = normalizeBasePath(process.env.DOCS_BASE_URL || "/");
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
  const configured = process.env.DOCS_TEST_BROWSER_EXECUTABLE?.trim();
  if (configured) {
    if (!existsSync(configured)) {
      throw new Error(
        `DOCS_TEST_BROWSER_EXECUTABLE does not exist: ${configured}`,
      );
    }
    return configured;
  }

  const localAppData = process.env.LOCALAPPDATA || "";
  const candidates =
    process.platform === "win32"
      ? [
          "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
          "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
          join(localAppData, "Google", "Chrome", "Application", "chrome.exe"),
          "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
          "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
        ]
      : process.platform === "darwin"
        ? [
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
          ]
        : [
            "/usr/bin/google-chrome",
            "/usr/bin/google-chrome-stable",
            "/usr/bin/chromium",
            "/usr/bin/chromium-browser",
          ];

  const executable = candidates.find((candidate) => candidate && existsSync(candidate));
  if (executable) {
    return executable;
  }

  throw new Error(
    "Chrome or Edge was not found. Set DOCS_TEST_BROWSER_EXECUTABLE to a Chromium-based browser executable.",
  );
}

function formatConsoleMessage(message) {
  const location = message.location();
  const source = location.url
    ? ` (${location.url}${location.lineNumber ? `:${location.lineNumber}` : ""})`
    : "";
  return `${message.text()}${source}`;
}

async function inspectPage(browser, baseUrl, route, viewport, internalLinks) {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage();
  const errors = [];
  const label = `${route.name} [${viewport.name} ${viewport.width}x${viewport.height}]`;

  page.on("console", (message) => {
    if (message.type() === "error") {
      errors.push(`console: ${formatConsoleMessage(message)}`);
    }
  });
  page.on("pageerror", (error) => {
    errors.push(`page: ${error.stack || error.message}`);
  });

  try {
    const url = routeUrl(baseUrl, route.path);
    const response = await page.goto(url, {
      waitUntil: "networkidle",
      timeout: RENDER_TIMEOUT_MS,
    });

    if (!response || !response.ok()) {
      errors.push(`navigation: ${response?.status() ?? "no response"} for ${url}`);
    }

    await page.locator("#__docusaurus").waitFor({
      state: "attached",
      timeout: RENDER_TIMEOUT_MS,
    });
    await page.evaluate(() => document.fonts?.ready);

    if (route.minimumMermaidDiagrams) {
      try {
        await page.waitForFunction(
          (minimum) =>
            document.querySelectorAll(".docusaurus-mermaid-container svg").length >= minimum,
          route.minimumMermaidDiagrams,
          { timeout: RENDER_TIMEOUT_MS },
        );
      } catch {
        const rendered = await page.locator(".docusaurus-mermaid-container svg").count();
        errors.push(
          `Mermaid: expected at least ${route.minimumMermaidDiagrams} rendered diagrams, found ${rendered}`,
        );
      }
    }

    const mermaidFailures = await page.evaluate(() => {
      const errorTextPattern = /mermaid|syntax error|parse error/i;
      return [...document.querySelectorAll("article [role='alert'], article .alert--danger")]
        .map((element) => element.textContent?.trim() || "")
        .filter((text) => errorTextPattern.test(text));
    });
    for (const failure of mermaidFailures) {
      errors.push(`Mermaid: ${failure}`);
    }

    if (route.requirePagination) {
      const pagination = await page.evaluate(() => ({
        next: document.querySelectorAll(".pagination-nav__link--next").length,
        previous: document.querySelectorAll(".pagination-nav__link--prev").length,
      }));
      if (pagination.previous !== 1 || pagination.next !== 1) {
        errors.push(
          `pagination: expected one Previous and one Next link, found Previous=${pagination.previous}, Next=${pagination.next}`,
        );
      }
    }

    const overflow = await page.evaluate((tolerance) => {
      const rootOverflow = Math.max(
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

    if (overflow.rootOverflow > OVERFLOW_TOLERANCE_PX) {
      errors.push(`overflow: document exceeds the viewport by ${overflow.rootOverflow}px`);
    }
    if (overflow.offenders.length > 0) {
      errors.push(
        `overflow: unclipped elements exceed their boxes: ${JSON.stringify(overflow.offenders)}`,
      );
    }

    if (viewport.name === "desktop") {
      const links = await page.locator("a[href]").evaluateAll((anchors) =>
        anchors.map((anchor) => anchor.href),
      );
      for (const link of links) {
        internalLinks.add(link);
      }
    }
  } catch (error) {
    errors.push(`audit: ${error.stack || error.message}`);
  } finally {
    await context.close();
  }

  if (errors.length > 0) {
    return errors.map((error) => `${label}: ${error}`);
  }

  log(`PASS ${label}`);
  return [];
}

function escapeAttribute(value) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll('"', "&quot;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

async function validateInternalLinks(baseUrl, links) {
  const failures = [];
  const uniqueLinks = new Map();

  for (const href of links) {
    const url = new URL(href);
    if (url.origin !== baseUrl.origin || !url.pathname.startsWith(baseUrl.pathname)) {
      continue;
    }
    uniqueLinks.set(url.href, url);
  }

  for (const url of uniqueLinks.values()) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    let response;
    try {
      const requestUrl = new URL(url);
      requestUrl.hash = "";
      response = await fetch(requestUrl, {
        redirect: "follow",
        signal: controller.signal,
      });
      if (!response.ok) {
        failures.push(`link: ${url.href} returned HTTP ${response.status}`);
        continue;
      }

      if (url.hash) {
        const contentType = response.headers.get("content-type") || "";
        if (!contentType.includes("text/html")) {
          failures.push(`link: ${url.href} has a fragment but is not an HTML document`);
          continue;
        }

        const body = await response.text();
        const fragment = escapeAttribute(decodeURIComponent(url.hash.slice(1)));
        if (
          fragment &&
          !body.includes(`id="${fragment}"`) &&
          !body.includes(`name="${fragment}"`)
        ) {
          failures.push(`link: ${url.href} points to a missing fragment`);
        }
      }
    } catch (error) {
      failures.push(`link: ${url.href} could not be checked (${error.message})`);
    } finally {
      clearTimeout(timeout);
      if (response && !response.bodyUsed) {
        await response.body?.cancel();
      }
    }
  }

  if (failures.length === 0) {
    log(`PASS ${uniqueLinks.size} unique internal links`);
  }
  return failures;
}

async function main() {
  const suppliedBaseUrl = process.env.DOCS_TEST_BASE_URL?.trim();
  let localServer;
  let browser;

  try {
    await runBuild();
    const routes = await discoverRoutes();
    log(`Discovered ${routes.length} generated documentation routes`);

    let baseUrl;
    if (suppliedBaseUrl) {
      baseUrl = normalizeBaseUrl(suppliedBaseUrl);
      log(`Testing supplied site ${baseUrl.href}`);
    } else {
      localServer = await startStaticServer();
      baseUrl = localServer.baseUrl;
      log(`Testing fresh build at ${baseUrl.href}`);
    }

    const executablePath = findBrowserExecutable();
    log(`Using browser ${executablePath}`);
    browser = await chromium.launch({ executablePath, headless: true });

    const failures = [];
    const internalLinks = new Set();
    for (const viewport of VIEWPORTS) {
      for (const route of routes) {
        failures.push(
          ...(await inspectPage(browser, baseUrl, route, viewport, internalLinks)),
        );
      }
    }
    failures.push(...(await validateInternalLinks(baseUrl, internalLinks)));

    if (failures.length > 0) {
      console.error(`\nRendered documentation check failed (${failures.length}):`);
      for (const failure of failures) {
        console.error(`- ${failure}`);
      }
      process.exitCode = 1;
      return;
    }

    log(
      `PASS ${routes.length} routes at ${VIEWPORTS.length} viewports; rendered documentation is healthy`,
    );
  } finally {
    await browser?.close();
    await localServer?.close();
  }
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});

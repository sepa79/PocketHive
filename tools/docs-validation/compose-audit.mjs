import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

import { parse } from "yaml";

import { REPOSITORY_ROOT } from "./docs-scope.mjs";

const COMPOSE_PATH = resolve(REPOSITORY_ROOT, "docker-compose.yml");
const QUICKSTART_PATH = resolve(
  REPOSITORY_ROOT,
  "docs",
  "guides",
  "onboarding",
  "quickstart-15min.md",
);

function relativeBindSource(volume) {
  if (typeof volume === "string") {
    const source = volume.split(":", 1)[0];
    return source.startsWith(".") ? source : undefined;
  }
  if (
    volume &&
    typeof volume === "object" &&
    volume.type === "bind" &&
    typeof volume.source === "string" &&
    volume.source.startsWith(".")
  ) {
    return volume.source;
  }
  return undefined;
}

async function main() {
  const compose = parse(await readFile(COMPOSE_PATH, "utf8"));
  const quickstart = await readFile(QUICKSTART_PATH, "utf8");
  const services = Object.entries(compose.services || {});
  const documentedCountMatch = /all\s+(\d+)\s+configured services/i.exec(quickstart);
  if (!documentedCountMatch) {
    throw new Error(
      "Unable to find the documented configured-service count in quickstart-15min.md",
    );
  }

  const documentedCount = Number.parseInt(documentedCountMatch[1], 10);
  const bindSources = new Set();
  for (const [, service] of services) {
    for (const volume of service.volumes || []) {
      const source = relativeBindSource(volume);
      if (source) bindSources.add(source);
    }
  }

  const missing = [...bindSources].filter(
    (source) => !existsSync(resolve(REPOSITORY_ROOT, source)),
  );
  console.log(`[docs-compose] Compose services: ${services.length}`);
  console.log(`[docs-compose] Quickstart expected services: ${documentedCount}`);
  console.log(`[docs-compose] Relative bind sources checked: ${bindSources.size}`);

  if (services.length !== documentedCount) {
    throw new Error(
      `Quickstart expects ${documentedCount} services but docker-compose.yml declares ${services.length}`,
    );
  }
  if (missing.length > 0) {
    throw new Error(`Missing bind sources: ${missing.join(", ")}`);
  }
  console.log("[docs-compose] PASS service count and every relative bind source");
}

main().catch((error) => {
  console.error(`[docs-compose] FAIL ${error.message}`);
  process.exitCode = 1;
});

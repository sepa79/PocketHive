#!/usr/bin/env node
import { readFileSync } from "node:fs";

const input = readFileSync(0, "utf8");
const config = JSON.parse(input);

delete config.name;

for (const service of Object.values(config.services ?? {})) {
  // Docker Swarm does not honor Compose startup ordering; readiness belongs in health checks.
  delete service.depends_on;

  if (service.command === null) {
    delete service.command;
  }
  if (service.entrypoint === null) {
    delete service.entrypoint;
  }

  for (const port of service.ports ?? []) {
    if (typeof port.published === "string") {
      if (!/^[0-9]+$/.test(port.published)) {
        throw new Error(`published port must be numeric: ${port.published}`);
      }
      port.published = Number(port.published);
    }
  }
}

process.stdout.write(`${JSON.stringify(config, null, 2)}\n`);

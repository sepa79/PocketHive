import { existsSync, readFileSync, readdirSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const root = resolve(import.meta.dirname, "..");
const workerServices = [
  "db-query-service",
  "generator-service",
  "http-sequence-service",
  "moderator-service",
  "network-proxy-manager-service",
  "postprocessor-service",
  "processor-service",
  "request-builder-service",
  "swarm-controller-service",
  "trigger-service",
];

describe("worker bootstrap SSOT", () => {
  it("owns shared logging only in observability", () => {
    const canonical = resolve(root, "observability/src/main/resources/logback-spring.xml");
    expect(readFileSync(canonical, "utf8")).toContain("org.springframework.amqp");
    for (const service of workerServices) {
      expect(existsSync(resolve(root, service, "src/main/resources/logback-spring.xml")), service)
          .toBe(false);
    }
  });

  it("uses the shared Jackson auto-configuration instead of package copies", () => {
    expect(existsSync(resolve(
      root,
      "observability/src/main/java/io/pockethive/observability/PocketHiveJacksonAutoConfiguration.java",
    ))).toBe(true);
    for (const service of workerServices) {
      const sourceRoot = resolve(root, service, "src/main/java");
      expect(findNamedSource(sourceRoot, "JacksonConfiguration.java"), service).toBeNull();
    }
  });

  it("keeps the HTTP template contract out of worker-sdk", () => {
    expect(existsSync(resolve(
      root,
      "common/worker-sdk/src/main/java/io/pockethive/httpbuilder/HttpTemplateDefinition.java",
    ))).toBe(false);
    expect(existsSync(resolve(
      root,
      "common/request-templates/src/main/java/io/pockethive/requesttemplates/HttpTemplateDefinition.java",
    ))).toBe(true);
  });
});

function findNamedSource(directory, filename) {
  if (!existsSync(directory)) return null;
  const stack = [directory];
  while (stack.length > 0) {
    const current = stack.pop();
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      const path = resolve(current, entry.name);
      if (entry.isDirectory()) stack.push(path);
      if (entry.isFile() && entry.name === filename) return path;
    }
  }
  return null;
}

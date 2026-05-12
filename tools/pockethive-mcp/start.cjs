#!/usr/bin/env node

/**
 * MCP server launcher — ensures dependencies are installed before starting.
 * This is the entry point referenced by .amazonq/mcp.json and codex.json.
 *
 * ESM imports in server.mjs resolve at parse time, so we must install
 * node_modules BEFORE importing the server module.
 */

const { execSync } = require("child_process");
const { existsSync } = require("fs");
const { resolve } = require("path");

const serverDir = __dirname;
const nodeModules = resolve(serverDir, "node_modules");
const sdkEntry = resolve(nodeModules, "@modelcontextprotocol", "sdk");

if (!existsSync(sdkEntry)) {
  try {
    execSync("npm install --silent", {
      cwd: serverDir,
      encoding: "utf8",
      timeout: 120000,
      stdio: "ignore",
    });
  } catch (err) {
    process.stderr.write(
      `Failed to install MCP server dependencies: ${err.message}\n` +
      `Run manually: cd tools/mcp-server && npm install\n`
    );
    process.exit(1);
  }
}

// Now safe to load the ESM server
import("./server.mjs").catch((err) => {
  process.stderr.write(`MCP server failed to start: ${err.message}\n`);
  process.exit(1);
});

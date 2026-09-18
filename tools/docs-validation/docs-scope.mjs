import { readdir, readFile } from "node:fs/promises";
import { dirname, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const SCRIPT_DIRECTORY = dirname(fileURLToPath(import.meta.url));
export const REPOSITORY_ROOT = resolve(SCRIPT_DIRECTORY, "../..");
export const DOCS_DIRECTORY = resolve(REPOSITORY_ROOT, "docs");
const DOCUSAURUS_CONFIG = resolve(
  REPOSITORY_ROOT,
  "docs-site",
  "docusaurus.config.ts",
);

function normalizePath(path) {
  return path.split(sep).join("/");
}

function extractConfiguredPatterns(source, property) {
  const match = new RegExp(`\\b${property}:\\s*\\[([\\s\\S]*?)\\]\\s*,`).exec(
    source,
  );
  if (!match) {
    throw new Error(
      `Unable to read docs.${property} from ${DOCUSAURUS_CONFIG}. Keep the documentation scope as a literal string array.`,
    );
  }

  const patterns = [];
  const stringPattern = /(["'])(.*?)\1/g;
  for (const stringMatch of match[1].matchAll(stringPattern)) {
    patterns.push(stringMatch[2]);
  }
  if (patterns.length === 0) {
    throw new Error(`docs.${property} in ${DOCUSAURUS_CONFIG} is empty`);
  }
  return patterns;
}

function globToRegExp(glob) {
  let expression = "^";
  for (let index = 0; index < glob.length; index += 1) {
    const character = glob[index];
    if (character === "*") {
      if (glob[index + 1] === "*") {
        index += 1;
        if (glob[index + 1] === "/") {
          index += 1;
          expression += "(?:.*/)?";
        } else {
          expression += ".*";
        }
      } else {
        expression += "[^/]*";
      }
      continue;
    }
    if (character === "?") {
      expression += "[^/]";
      continue;
    }
    expression += character.replace(/[|\\{}()[\]^$+?.]/g, "\\$&");
  }
  return new RegExp(`${expression}$`);
}

async function collectMarkdownFiles(directory, files = []) {
  const entries = await readdir(directory, { withFileTypes: true });
  for (const entry of entries) {
    const path = resolve(directory, entry.name);
    if (entry.isDirectory()) {
      await collectMarkdownFiles(path, files);
    } else if (/\.mdx?$/i.test(entry.name)) {
      files.push(path);
    }
  }
  return files;
}

export async function publishedDocs() {
  const config = await readFile(DOCUSAURUS_CONFIG, "utf8");
  const includes = extractConfiguredPatterns(config, "include").map(globToRegExp);
  const excludes = extractConfiguredPatterns(config, "exclude").map(globToRegExp);
  const candidates = await collectMarkdownFiles(DOCS_DIRECTORY);

  return candidates
    .map((path) => ({
      path,
      relativePath: normalizePath(relative(DOCS_DIRECTORY, path)),
    }))
    .filter(({ relativePath }) =>
      includes.some((pattern) => pattern.test(relativePath)),
    )
    .filter(({ relativePath }) =>
      !excludes.some((pattern) => pattern.test(relativePath)),
    )
    .sort((left, right) => left.relativePath.localeCompare(right.relativePath));
}

export function extractFences(source, relativePath) {
  const lines = source.split(/\r?\n/);
  const fences = [];

  for (let index = 0; index < lines.length; index += 1) {
    const opening = /^\s*(`{3,}|~{3,})\s*([^\s{]*)?.*$/.exec(lines[index]);
    if (!opening) continue;

    const marker = opening[1];
    const markerCharacter = marker[0];
    const language = (opening[2] || "").toLowerCase();
    const startLine = index + 2;
    const body = [];
    let closed = false;

    for (index += 1; index < lines.length; index += 1) {
      const closing = new RegExp(
        `^\\s*${markerCharacter === "`" ? "`" : "~"}{${marker.length},}\\s*$`,
      );
      if (closing.test(lines[index])) {
        closed = true;
        break;
      }
      body.push(lines[index]);
    }

    if (!closed) {
      throw new Error(
        `${relativePath}:${startLine - 1} contains an unclosed ${markerCharacter} code fence`,
      );
    }

    fences.push({
      content: body.join("\n"),
      language,
      line: startLine,
      relativePath,
    });
  }

  return fences;
}

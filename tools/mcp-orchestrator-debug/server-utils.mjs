import { existsSync, readFileSync } from "node:fs";

export function topicMatchesPattern(pattern, routingKey) {
  const patternParts = pattern.split(".");
  const keyParts = routingKey.split(".");

  return matches(patternParts, keyParts);
}

function matches(patternParts, keyParts) {
  if (patternParts.length === 0) {
    return keyParts.length === 0;
  }

  const [head, ...tail] = patternParts;
  if (head === "#") {
    if (tail.length === 0) {
      return true;
    }
    for (let index = 0; index <= keyParts.length; index += 1) {
      if (matches(tail, keyParts.slice(index))) {
        return true;
      }
    }
    return false;
  }

  if (keyParts.length === 0) {
    return false;
  }

  if (head === "*" || head === keyParts[0]) {
    return matches(tail, keyParts.slice(1));
  }

  return false;
}

export function parseRecordedEntries(logPath, pattern) {
  if (!existsSync(logPath)) {
    return [];
  }

  return readFileSync(logPath, "utf8")
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean)
    .flatMap((line) => {
      try {
        return [JSON.parse(line)];
      } catch {
        return [];
      }
    })
    .filter((entry) => !pattern || topicMatchesPattern(pattern, entry.routingKey));
}

export function childProcessIsRunning(child) {
  return child != null && child.exitCode === null && !child.killed;
}

export function childProcessExited(child) {
  return child != null && child.exitCode !== null;
}

export function randomId(): string {
  try {
    // Prefer the built-in crypto.randomUUID when available (modern browsers / Node).
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID()
    }
  } catch {
    // ignore and fall back
  }

  // Fallback: timestamp + random suffix. Not cryptographically strong, but good enough for correlation ids.
  const randomPart = Math.random().toString(16).slice(2)
  const timePart = Date.now().toString(16)
  return `ph-${timePart}-${randomPart}`
}


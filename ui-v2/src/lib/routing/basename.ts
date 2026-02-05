const ROUTE_ROOTS = ['/hive', '/scenarios', '/debug', '/journal', '/other', '/buzz']

function isBoundaryMatch(pathname: string, root: string, idx: number): boolean {
  const afterIdx = idx + root.length
  const afterOk = afterIdx === pathname.length || pathname[afterIdx] === '/'
  if (!afterOk) return false

  // `ROUTE_ROOTS` are absolute roots (they start with `/`). For those, the "before" boundary
  // check is not applicable: the match index points at `/` already and the preceding character
  // can be anything (e.g. `/v2/hive` => idx points at the `/` before `hive`).
  if (root.startsWith('/')) {
    return true
  }

  const beforeOk = idx === 0 || pathname[idx - 1] === '/'
  return beforeOk
}

export function detectUiBasename(pathname: string): string {
  const path = (pathname ?? '').trim() || '/'
  if (path === '/') return ''

  let bestIdx = Number.POSITIVE_INFINITY
  for (const root of ROUTE_ROOTS) {
    let idx = path.indexOf(root)
    while (idx >= 0) {
      if (isBoundaryMatch(path, root, idx)) {
        bestIdx = Math.min(bestIdx, idx)
        break
      }
      idx = path.indexOf(root, idx + 1)
    }
  }

  if (Number.isFinite(bestIdx)) {
    return path.slice(0, bestIdx)
  }

  return path.endsWith('/') ? path.slice(0, -1) : path
}

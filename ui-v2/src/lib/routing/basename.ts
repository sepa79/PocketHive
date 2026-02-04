const ROUTE_ROOTS = ['/hive', '/scenarios', '/debug', '/journal', '/other', '/buzz']

function isBoundaryMatch(pathname: string, root: string, idx: number): boolean {
  const beforeOk = idx === 0 || pathname[idx - 1] === '/'
  const afterIdx = idx + root.length
  const afterOk = afterIdx === pathname.length || pathname[afterIdx] === '/'
  return beforeOk && afterOk
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


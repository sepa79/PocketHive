import { useEffect, useRef, useState } from 'react'

/**
 * Responsibility: measure the available height for virtualized catalogue trees.
 * Must not: load catalogue data, select nodes or change container layout.
 * Contract: RESP-SCENARIO-CATALOGUE-ACCESS — docs/architecture/runtime-responsibilities.md#resp-scenario-catalogue-access (presentation only).
 */
export function useMeasuredTreeHeight(minimumHeight: number) {
  const ref = useRef<HTMLDivElement | null>(null)
  const [height, setHeight] = useState(minimumHeight)

  useEffect(() => {
    const element = ref.current
    if (!element) return
    const updateHeight = (next: number) => setHeight(Math.max(minimumHeight, Math.floor(next)))
    updateHeight(element.getBoundingClientRect().height)
    const observer = new ResizeObserver(([entry]) => updateHeight(entry.contentRect.height))
    observer.observe(element)
    return () => observer.disconnect()
  }, [minimumHeight])

  return { ref, height }
}

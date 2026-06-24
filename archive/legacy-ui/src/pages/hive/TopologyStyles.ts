import { DEFAULT_COLOR, DISABLED_COLOR, type NodeShape } from './TopologyShapes'
import type { RoleAppearanceMap } from '../../lib/capabilities'

const shapeOrder: NodeShape[] = [
  'square',
  'triangle',
  'diamond',
  'pentagon',
  'hexagon',
  'star',
]

export function normalizeRoleKey(value?: string): string {
  return typeof value === 'string' ? value.trim().toLowerCase() : ''
}

function fallbackColorForRole(role: string): string {
  const key = normalizeRoleKey(role)
  if (!key) return DEFAULT_COLOR
  let hash = 0
  for (let i = 0; i < key.length; i++) {
    hash = (hash << 5) - hash + key.charCodeAt(i)
    hash |= 0
  }
  const hue = Math.abs(hash) % 360
  const saturation = 65
  const lightness = 55
  return `hsl(${hue}, ${saturation}%, ${lightness}%)`
}

export function makeTopologyStyleHelpers(
  roleAppearances: RoleAppearanceMap,
  shapeMap: Record<string, NodeShape>,
) {
  const getFill = (type?: string, enabled?: boolean) => {
    if (enabled === false) return DISABLED_COLOR
    const key = normalizeRoleKey(type)
    if (!key) return DEFAULT_COLOR
    const appearance = roleAppearances[key]
    const color = appearance?.color?.trim()
    return color && color.length > 0 ? color : fallbackColorForRole(key)
  }

  const getRoleLabel = (componentRole: string | undefined, type: string) => {
    const key = normalizeRoleKey(type)
    const componentKey = normalizeRoleKey(componentRole)
    if (key === 'wiremock' || componentKey === 'wiremock') {
      return 'System Under Test'
    }
    const appearanceLabel = roleAppearances[key]?.label?.trim()
    if (appearanceLabel && appearanceLabel.length > 0) {
      return appearanceLabel
    }
    const normalizedComponent = componentRole?.trim()
    if (normalizedComponent && normalizedComponent.length > 0) {
      return normalizedComponent
    }
    return type
  }

  const getRoleAbbreviation = (type: string) => {
    const key = normalizeRoleKey(type)
    const abbreviation = roleAppearances[key]?.abbreviation?.trim()
    return abbreviation && abbreviation.length > 0 ? abbreviation : ''
  }

  const getShape = (type: string): NodeShape => {
    const key = normalizeRoleKey(type)
    const preferred = roleAppearances[key]?.shape
    if (preferred) {
      shapeMap[key] = preferred
      return preferred
    }
    if (!shapeMap[key]) {
      const used = new Set(Object.values(shapeMap))
      const next = shapeOrder.find((s) => !used.has(s)) ?? 'circle'
      shapeMap[key] = next
    }
    return shapeMap[key]
  }

  return {
    getFill,
    getRoleLabel,
    getRoleAbbreviation,
    getShape,
  }
}


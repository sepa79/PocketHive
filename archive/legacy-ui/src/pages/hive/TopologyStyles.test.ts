import { describe, it, expect } from 'vitest'
import { makeTopologyStyleHelpers, normalizeRoleKey } from './TopologyStyles'
import type { RoleAppearanceMap } from '../../lib/capabilities'

describe('TopologyStyles', () => {
  it('normalizes role keys to lowercase', () => {
    expect(normalizeRoleKey('Generator')).toBe('generator')
    expect(normalizeRoleKey('  MODERATOR ')).toBe('moderator')
    expect(normalizeRoleKey(undefined)).toBe('')
  })

  it('labels WireMock as System Under Test', () => {
    const appearances: RoleAppearanceMap = {}
    const helpers = makeTopologyStyleHelpers(appearances, {})

    expect(helpers.getRoleLabel(undefined, 'wiremock')).toBe('System Under Test')
    expect(helpers.getRoleLabel('WireMock', 'generator')).toBe('System Under Test')
  })

  it('prefers appearance label and abbreviation when provided', () => {
    const appearances: RoleAppearanceMap = {
      generator: {
        role: 'generator',
        label: 'Traffic generator',
        abbreviation: 'GEN',
        color: '#123456',
      },
    }
    const shapeMap: Record<string, any> = {}
    const helpers = makeTopologyStyleHelpers(appearances, shapeMap)

    expect(helpers.getRoleLabel(undefined, 'generator')).toBe('Traffic generator')
    expect(helpers.getRoleAbbreviation('generator')).toBe('GEN')
    // color comes from appearance when present
    expect(helpers.getFill('generator', true)).toBe('#123456')
  })

  it('allocates shapes in a deterministic order when none configured', () => {
    const appearances: RoleAppearanceMap = {}
    const shapeMap: Record<string, any> = {}
    const helpers = makeTopologyStyleHelpers(appearances, shapeMap)

    const a = helpers.getShape('generator')
    const b = helpers.getShape('moderator')
    const c = helpers.getShape('processor')

    expect(a).not.toBe(b)
    expect(b).not.toBe(c)
    // subsequent calls for the same type should be stable
    expect(helpers.getShape('generator')).toBe(a)
  })
})

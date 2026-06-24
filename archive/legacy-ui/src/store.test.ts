import { describe, expect, it, beforeEach } from 'vitest'
import { useUIStore } from './store'

describe('useUIStore messageLimit', () => {
  beforeEach(() => {
    useUIStore.setState({ messageLimit: 100 })
  })

  it('has default value of 100', () => {
    expect(useUIStore.getState().messageLimit).toBe(100)
  })

  it('enforces minimum limit of 10', () => {
    useUIStore.getState().setMessageLimit(5)
    expect(useUIStore.getState().messageLimit).toBe(10)
  })

  it('enforces maximum limit of 500', () => {
    useUIStore.getState().setMessageLimit(600)
    expect(useUIStore.getState().messageLimit).toBe(500)
  })

  it('accepts valid values within range', () => {
    useUIStore.getState().setMessageLimit(250)
    expect(useUIStore.getState().messageLimit).toBe(250)
  })
})

describe('useUIStore toast', () => {
  beforeEach(() => {
    useUIStore.setState({ toast: null })
  })

  it('sets and clears toast', () => {
    useUIStore.getState().setToast('err')
    expect(useUIStore.getState().toast).toBe('err')
    useUIStore.getState().clearToast()
    expect(useUIStore.getState().toast).toBeNull()
  })
})

describe('useUIStore buzz panel', () => {
  beforeEach(() => {
    useUIStore.setState({ buzzVisible: false, buzzDock: 'right', buzzSize: 30 })
  })

  it('toggles visibility', () => {
    useUIStore.getState().toggleBuzz()
    expect(useUIStore.getState().buzzVisible).toBe(true)
  })

  it('sets dock position', () => {
    useUIStore.getState().setBuzzDock('left')
    expect(useUIStore.getState().buzzDock).toBe('left')
  })

  it('clamps size between 10 and 90', () => {
    useUIStore.getState().setBuzzSize(5)
    expect(useUIStore.getState().buzzSize).toBe(10)
    useUIStore.getState().setBuzzSize(95)
    expect(useUIStore.getState().buzzSize).toBe(90)
    useUIStore.getState().setBuzzSize(40)
    expect(useUIStore.getState().buzzSize).toBe(40)
  })
})

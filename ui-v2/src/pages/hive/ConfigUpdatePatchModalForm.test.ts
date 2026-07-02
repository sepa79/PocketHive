import { describe, expect, it } from 'vitest'
import type { CapabilityConfigEntry } from '../../lib/capabilities'
import {
  configInputStep,
  configInputType,
  convertFormValue,
} from './ConfigUpdatePatchModalForm'

function entry(type: string, extra: Partial<CapabilityConfigEntry> = {}): CapabilityConfigEntry {
  return {
    name: 'threadCount',
    type,
    ...extra,
  }
}

describe('ConfigUpdatePatchModalForm', () => {
  it('rejects decimal values for integer capability fields', () => {
    expect(convertFormValue(entry('integer'), '1.5')).toEqual({
      ok: false,
      message: 'threadCount must be an integer.',
    })
  })

  it('accepts integer values for integer capability fields', () => {
    expect(convertFormValue(entry('integer'), '2')).toEqual({
      ok: true,
      apply: true,
      value: 2,
    })
  })

  it('keeps decimal values valid for number capability fields', () => {
    expect(convertFormValue(entry('number'), '1.5')).toEqual({
      ok: true,
      apply: true,
      value: 1.5,
    })
  })

  it('uses number inputs with integer step for integer fields', () => {
    expect(configInputType(entry('integer'))).toBe('number')
    expect(configInputStep(entry('integer'))).toBe(1)
  })

  it('sends blank string values when the capability field allows blanks', () => {
    expect(convertFormValue(entry('string', { name: 'connection.username', allowBlank: true }), '')).toEqual({
      ok: true,
      apply: true,
      value: '',
    })
  })

  it('normalizes whitespace-only allowed blank string values to an empty string', () => {
    expect(convertFormValue(entry('string', { name: 'connection.password', allowBlank: true }), '   ')).toEqual({
      ok: true,
      apply: true,
      value: '',
    })
  })

  it('continues to omit blank string values when blanks are not explicitly allowed', () => {
    expect(convertFormValue(entry('string', { name: 'connection.jdbcUrl' }), '')).toEqual({
      ok: true,
      apply: false,
      value: undefined,
    })
  })
})

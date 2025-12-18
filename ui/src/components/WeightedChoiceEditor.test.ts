import { describe, expect, it } from 'vitest'
import { parseWeightedTemplate, stringifyWeightedTemplate } from './WeightedChoiceEditor'

describe('parseWeightedTemplate', () => {
  it('parses templates with empty string options', () => {
    const model = parseWeightedTemplate("pickWeighted('a', 1, '', 0)")
    expect(model).not.toBeNull()
    expect(model?.options).toEqual([
      { value: 'a', weight: 1 },
      { value: '', weight: 0 },
    ])
  })

  it('supports 4+ options and roundtrips', () => {
    const input =
      "pickWeighted('redis-balance', 40, 'redis-topup', 40, 'redis-auth', 20, 'OptionName', 0)"
    const model = parseWeightedTemplate(input)
    expect(model).not.toBeNull()
    expect(model?.options.length).toBe(4)
    expect(stringifyWeightedTemplate(model!)).toBe(input)
  })
})


import { render, screen } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import { describe, expect, it } from 'vitest'
import { MetricToolbar } from './MetricToolbar'

describe('MetricToolbar', () => {
  it('renders title, unit and actions', () => {
    render(
      <MetricToolbar
        title="Processor Calls"
        unit="ops/s"
        subtitle="Average call rate"
        actions={<button type="button">Action</button>}
      />,
    )

    expect(screen.getByText('Processor Calls')).toBeInTheDocument()
    expect(screen.getByText('ops/s')).toBeInTheDocument()
    expect(screen.getByText('Average call rate')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Action' })).toBeInTheDocument()
  })
})

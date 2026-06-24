import type { ReactNode } from 'react'

export interface MetricToolbarProps {
  title: string
  unit?: string
  subtitle?: string
  actions?: ReactNode
}

export function MetricToolbar({ title, unit, subtitle, actions }: MetricToolbarProps) {
  return (
    <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between sm:gap-4">
      <div>
        <h3 className="kpi-title text-base font-semibold text-neutral-100">{title}</h3>
        {subtitle ? <p className="text-xs text-neutral-400">{subtitle}</p> : null}
      </div>
      <div className="flex items-center gap-2 text-xs text-neutral-400">
        {unit ? <span className="uppercase tracking-wide">{unit}</span> : null}
        {actions}
      </div>
    </div>
  )
}

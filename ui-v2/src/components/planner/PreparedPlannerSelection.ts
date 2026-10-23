import type { PlannerSelection } from './PlannerSelection'

export type PreparedPlannerSelection = Readonly<{ selection: PlannerSelection; generation: number }>

import { useToolsBarContext } from './ToolsBarContext'

export function PageToolsBar() {
  const toolsBar = useToolsBarContext()

  return (
    <div className="pageToolsBar" role="region" aria-label="Page tools">
      <div className="pageToolsBarInner">{toolsBar?.tools}</div>
    </div>
  )
}

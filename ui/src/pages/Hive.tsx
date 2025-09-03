import Connectivity from '../components/Connectivity'

export default function Hive() {
  const nodes = [
    { id: 'generator', x: 140, y: 160, label: 'GENERATOR' },
    { id: 'moderator', x: 480, y: 160, label: 'MODERATOR' },
    { id: 'processor', x: 820, y: 160, label: 'PROCESSOR' },
    { id: 'sut', x: 1060, y: 160, label: 'SUT' },
  ]
  const edges = [
    { a: 'generator', b: 'moderator' },
    { a: 'moderator', b: 'processor' },
    { a: 'processor', b: 'sut' },
  ]

  const find = (id: string) => nodes.find((n) => n.id === id)!

  return (
    <div id="view-hive" style={{ maxWidth: '1200px', margin: '24px auto', padding: '0 20px 40px', color: '#fff' }}>
      <div id="hive-toolbar" style={{ display: 'flex', gap: '10px', alignItems: 'center', marginBottom: '10px' }}>
        <Connectivity />
        <label>
          Hold time (s)
          <input id="hive-hold" type="number" min="1" max="120" step="1" defaultValue={15} style={{ width: '64px', marginLeft: '4px' }} />
        </label>
        <button id="hive-clear" className="tab-btn" style={{ cursor: 'pointer' }}>Clear &amp; Restart</button>
        <span id="hive-stats" className="small" style={{ color: '#9aa0a6' }}>Demo graph</span>
      </div>
      <svg id="hive-canvas" viewBox="0 0 1200 480" preserveAspectRatio="xMidYMid meet" style={{ width: '100%', height: '480px' }} aria-label="Hive view">
        {edges.map((e) => {
          const a = find(e.a)
          const b = find(e.b)
          return <line key={`${e.a}-${e.b}`} x1={a.x} y1={a.y} x2={b.x} y2={b.y} stroke="rgba(255,255,255,0.6)" strokeWidth={3} strokeLinecap="round" />
        })}
        {nodes.map((n) => (
          <g key={n.id} transform={`translate(${n.x - 60},${n.y - 46})`}>
            <rect x={0} y={0} width={120} height={92} rx={12} fill={n.id === 'sut' ? 'rgba(3,169,244,0.2)' : 'rgba(255,255,255,0.08)'} stroke="rgba(255,255,255,0.5)" strokeWidth={2} />
            <text x={60} y={20} textAnchor="middle" fill="#ffffff" fontFamily="Inter, Segoe UI, Arial, sans-serif" fontSize={13}>
              {n.label}
            </text>
          </g>
        ))}
      </svg>
    </div>
  )
}

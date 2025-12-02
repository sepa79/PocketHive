// @ts-nocheck
import type { NodeShape } from './TopologyShapes'

interface LegendItem {
  key: string
  label: string
  shape: NodeShape
  fill: string
}

interface Props {
  items: LegendItem[]
}

function polygonPoints(sides: number, r: number) {
  const cx = r
  const cy = r
  const pts: string[] = []
  for (let i = 0; i < sides; i++) {
    const angle = -Math.PI / 2 + (2 * Math.PI * i) / sides
    pts.push(`${cx + r * Math.cos(angle)},${cy + r * Math.sin(angle)}`)
  }
  return pts.join(' ')
}

function starPoints(r: number) {
  const outer = r
  const inner = r / 2
  const cx = r
  const cy = r
  const pts: string[] = []
  let rot = -Math.PI / 2
  const step = Math.PI / 5
  for (let i = 0; i < 10; i++) {
    const radius = i % 2 === 0 ? outer : inner
    pts.push(`${cx + Math.cos(rot) * radius},${cy + Math.sin(rot) * radius}`)
    rot += step
  }
  return pts.join(' ')
}

export default function TopologyLegend({ items }: Props) {
  return (
    <div className="topology-legend">
      {items.map((item) => {
        const { key, label, shape, fill } = item
        return (
          <div key={key} className="legend-item">
            <svg width="12" height="12" className="legend-icon">
              {shape === 'square' && (
                <rect x="1" y="1" width="10" height="10" fill={fill} stroke="black" />
              )}
              {shape === 'triangle' && (
                <polygon points="6,1 11,11 1,11" fill={fill} stroke="black" />
              )}
              {shape === 'diamond' && (
                <polygon points="6,1 11,6 6,11 1,6" fill={fill} stroke="black" />
              )}
              {shape === 'pentagon' && (
                <polygon points={polygonPoints(5, 5)} fill={fill} stroke="black" />
              )}
              {shape === 'hexagon' && (
                <polygon points={polygonPoints(6, 5)} fill={fill} stroke="black" />
              )}
              {shape === 'star' && (
                <polygon points={starPoints(5)} fill={fill} stroke="black" />
              )}
              {shape === 'circle' && <circle cx="6" cy="6" r="5" fill={fill} stroke="black" />}
            </svg>
            <span>{label}</span>
          </div>
        )
      })}
      <div className="legend-item">
        <svg width="30" height="6" className="legend-icon">
          <line x1="1" y1="3" x2="29" y2="3" stroke="#66aaff" strokeWidth="2" />
        </svg>
        <span>queue (empty)</span>
      </div>
      <div className="legend-item">
        <svg width="30" height="6" className="legend-icon">
          <line x1="1" y1="3" x2="29" y2="3" stroke="#ff6666" strokeWidth="4" />
        </svg>
        <span>queue (deep)</span>
      </div>
    </div>
  )
}


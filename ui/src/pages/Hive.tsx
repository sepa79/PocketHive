import { useEffect } from 'react'

export default function Hive() {
  useEffect(() => {
    // Hive rendering to be wired with real data in future iterations.
  }, [])

  return (
    <div id="view-hive" style={{ maxWidth: '1200px', margin: '24px auto', padding: '0 20px 40px', color: '#fff' }}>
      <div id="hive-toolbar" style={{ display: 'flex', gap: '10px', alignItems: 'center', marginBottom: '10px' }}>
        <label>
          Hold time (s)
          <input id="hive-hold" type="number" min="1" max="120" step="1" defaultValue={15} style={{ width: '64px', marginLeft: '4px' }} />
        </label>
        <button id="hive-clear" className="tab-btn" style={{ cursor: 'pointer' }}>Clear &amp; Restart</button>
        <span id="hive-stats" className="small" style={{ color: '#9aa0a6' }}></span>
      </div>
      <svg id="hive-canvas" viewBox="0 0 1200 480" preserveAspectRatio="xMidYMid meet" aria-label="Hive view"></svg>
    </div>
  )
}

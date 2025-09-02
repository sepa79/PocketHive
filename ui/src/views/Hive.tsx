import { useEffect } from 'react';
import { initHiveMenu } from '../legacy/hive';

export default function Hive() {
  useEffect(() => {
    const hive = initHiveMenu();
    return () => {
      // no cleanup needed for legacy script
    };
  }, []);

  return (
    <div id="view-hive">
      <div id="hive-toolbar">
        <label>
          Hold time (s)
          <input id="hive-hold" type="number" min="1" max="120" step="1" defaultValue="15" style={{ width: '64px', marginLeft: '4px' }} />
        </label>
        <button id="hive-clear" className="tab-btn" style={{ cursor: 'pointer' }}>
          Clear &amp; Restart
        </button>
        <span className="small" id="hive-stats" style={{ color: '#9aa0a6' }}></span>
      </div>
      <svg id="hive-canvas" viewBox="0 0 1200 480" preserveAspectRatio="xMidYMid meet" aria-label="Hive view"></svg>
    </div>
  );
}

/**
 * Periodically ping the UI's /healthz endpoint and log status transitions.
 *
 * @param {Object} opts Options bag.
 * @param {(line: string) => void} opts.appendSys Function to append system log lines.
 * @param {(state: string) => void} opts.setUiStatus Updates the UI health indicator.
 * @param {boolean} opts.demo When true, skip health pings entirely.
 */
export function startHealthPing({ appendSys, setUiStatus, demo }) {
  if (!window.fetch) return;
  if (demo) return;
  let lastStatus = null;
  const ping = async () => {
    try {
      const res = await fetch('/healthz', { cache: 'no-store' });
      const ok = res.ok;
      const txt = await res.text().catch(() => '');
      const status = ok && /ok/i.test(txt) ? 'healthy' : `unhealthy(${res.status})`;
      if (status !== lastStatus) {
        appendSys(`UI health: ${status}`);
        setUiStatus(/healthy/.test(status) ? 'healthy' : 'unhealthy');
        lastStatus = status;
      }
    } catch (e) {
      if (lastStatus !== 'down') {
        appendSys('UI health: down');
        setUiStatus('down');
        lastStatus = 'down';
      }
    }
  };
  ping();
  setInterval(ping, 15000);
}

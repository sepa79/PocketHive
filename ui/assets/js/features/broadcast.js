/**
 * Wire up the "Broadcast Status" button to send a status-request signal over STOMP.
 *
 * @param {() => StompJs.Client|null} getClient Function returning the current STOMP client.
 * @param {(line: string) => void} appendSys Function to append a line to the system log.
 * @param {() => boolean} isConnected Returns true when the client is connected.
 */
export function setupBroadcast(getClient, appendSys, isConnected) {
  const btn = document.getElementById('broadcast-status');
  if (!btn) return;
  btn.addEventListener('click', () => {
    const client = getClient();
    if (!client || !isConnected()) return;
    const payload = {
      type: 'status-request',
      version: '1.0',
      messageId: (crypto && crypto.randomUUID ? crypto.randomUUID() : (Math.random().toString(16).slice(2) + Date.now())),
      timestamp: new Date().toISOString(),
    };
    const rk = 'sig.status-request';
    const dest = '/exchange/ph.control/' + rk;
    try {
      client.publish({ destination: dest, body: JSON.stringify(payload), headers: { 'content-type': 'application/json' } });
    } catch (e) {
      appendSys('Broadcast error: ' + (e && e.message ? e.message : String(e)));
    }
  });
}

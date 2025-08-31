/**
 * Wire up the "Broadcast Status" button to send a status-request signal over STOMP.
 *
 * @param {StompJs.Client} client STOMP client used for publishing.
 * @param {(line: string) => void} appendSys Function to append a line to the system log.
 * @param {() => boolean} isConnected Returns true when the client is connected.
 */
export function setupBroadcast(client, appendSys, isConnected) {
  const btn = document.getElementById('broadcast-status');
  if (!btn) return;
  btn.addEventListener('click', () => {
    if (!client || !isConnected()) {
      appendSys('[BUZZ] SEND aborted: not connected');
      return;
    }
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
      appendSys(`[BUZZ] SEND ${rk} payload=status-request`);
    } catch (e) {
      appendSys('Broadcast error: ' + (e && e.message ? e.message : String(e)));
    }
  });
}

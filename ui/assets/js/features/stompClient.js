export function setupStompClient(onStatusFull, onErrorRate) {
  const client = new StompJs.Client({
    brokerURL: (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws'
  });
  client.onConnect = () => {
    client.subscribe('/exchange/ph.control/ev.status-full.#', onStatusFull);
    if (onErrorRate) client.subscribe('/exchange/ph.metrics/metrics.error-rate', onErrorRate);
    try {
      const payload = { type: 'status-request', timestamp: new Date().toISOString() };
      client.publish({ destination: '/exchange/ph.control/sig.status-request', body: JSON.stringify(payload) });
    } catch (err) {
      console.error('status-request', err);
    }
  };
  client.activate();
  return client;
}

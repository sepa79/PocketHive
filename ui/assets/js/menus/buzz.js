/**
 * Handle the Buzz panel which displays event logs and topic sniffing utilities.
 *
 * @param {{getClient: () => any, isConnected: () => boolean}} deps STOMP helpers.
 * @returns {{appendLog: (line: string) => void, appendSys: (line: string) => void}} Log appenders.
 */
export function initBuzzMenu({ getClient, isConnected }) {
  const LOG_LIMIT_DEFAULT = 100;
  const SYSLOG_LIMIT_DEFAULT = 100;
  let logLimit = LOG_LIMIT_DEFAULT;
  let sysLogLimit = SYSLOG_LIMIT_DEFAULT;
  const logLines = [];
  const sysOutLines = [];
  const sysInLines = [];
  const sysOtherLines = [];
  const topicLines = [];
  const logEl = document.getElementById('log');
  const sysOutEl = document.getElementById('syslog-out');
  const sysInEl = document.getElementById('syslog-in');
  const sysOtherEl = document.getElementById('syslog-other');

  function appendLog(line) {
    if (!logEl) return;
    const ts = new Date().toISOString();
    logLines.push(`[${ts}] ${line}`);
    if (logLines.length > logLimit) logLines.splice(0, logLines.length - logLimit);
    logEl.textContent = logLines.join('\n');
    logEl.scrollTop = logEl.scrollHeight;
  }

  function appendSys(line) {
    const ts = new Date().toISOString();
    const entry = `[${ts}] ${line}`;
    let arr, el;
    if (/\bSEND\b/.test(line)) { arr = sysOutLines; el = sysOutEl; }
    else if (/\bRECV\b/.test(line)) { arr = sysInLines; el = sysInEl; }
    else { arr = sysOtherLines; el = sysOtherEl; }
    arr.push(entry);
    if (arr.length > sysLogLimit) arr.splice(0, arr.length - sysLogLimit);
    if (el) { el.textContent = arr.join('\n'); el.scrollTop = el.scrollHeight; }
  }

  const logLimitInput = document.getElementById('log-limit');
  if (logLimitInput) {
    logLimitInput.addEventListener('change', () => {
      const v = Number(logLimitInput.value) || LOG_LIMIT_DEFAULT;
      logLimit = Math.min(500, Math.max(10, v));
      logLimitInput.value = String(logLimit);
      if (logLines.length > logLimit) {
        logLines.splice(0, logLines.length - logLimit);
        if (logEl) logEl.textContent = logLines.join('\n');
      }
      if (topicLines.length > logLimit) {
        topicLines.splice(0, topicLines.length - logLimit);
        const tl = document.getElementById('topic-log');
        if (tl) tl.textContent = topicLines.join('\n');
      }
    });
  }

  const sysLimitInput = document.getElementById('syslog-limit');
  if (sysLimitInput) {
    sysLimitInput.addEventListener('change', () => {
      const v = Number(sysLimitInput.value) || SYSLOG_LIMIT_DEFAULT;
      sysLogLimit = Math.min(500, Math.max(10, v));
      sysLimitInput.value = String(sysLogLimit);
      [sysOutLines, sysInLines, sysOtherLines].forEach(arr => {
        if (arr.length > sysLogLimit) arr.splice(0, arr.length - sysLogLimit);
      });
      if (sysOutEl) sysOutEl.textContent = sysOutLines.join('\n');
      if (sysInEl) sysInEl.textContent = sysInLines.join('\n');
      if (sysOtherEl) sysOtherEl.textContent = sysOtherLines.join('\n');
    });
  }

  (function() {
    const tEvents = document.getElementById('log-tab-events');
    const tTop = document.getElementById('log-tab-topic');
    const vEvents = document.getElementById('log-events');
    const vTop = document.getElementById('log-topic');
    if (!tEvents || !tTop || !vEvents || !vTop) return;
    const set = (which) => {
      if (which === 'events') {
        vEvents.style.display = 'block';
        vTop.style.display = 'none';
        tEvents.classList.add('tab-active');
        tTop.classList.remove('tab-active');
      } else {
        vEvents.style.display = 'none';
        vTop.style.display = 'block';
        tEvents.classList.remove('tab-active');
        tTop.classList.add('tab-active');
      }
    };
    tEvents.addEventListener('click', () => set('events'));
    tTop.addEventListener('click', () => set('topic'));
    set('events');
  })();

  (function() {
    const tOut = document.getElementById('syslog-tab-out');
    const tIn = document.getElementById('syslog-tab-in');
    const tOther = document.getElementById('syslog-tab-other');
    const vOut = document.getElementById('syslog-out');
    const vIn = document.getElementById('syslog-in');
    const vOther = document.getElementById('syslog-other');
    if (!tOut || !tIn || !tOther || !vOut || !vIn || !vOther) return;
    const set = (which) => {
      vOut.style.display = which === 'out' ? 'block' : 'none';
      vIn.style.display = which === 'in' ? 'block' : 'none';
      vOther.style.display = which === 'other' ? 'block' : 'none';
      tOut.classList.toggle('tab-active', which === 'out');
      tIn.classList.toggle('tab-active', which === 'in');
      tOther.classList.toggle('tab-active', which === 'other');
    };
    tOut.addEventListener('click', () => set('out'));
    tIn.addEventListener('click', () => set('in'));
    tOther.addEventListener('click', () => set('other'));
    set('out');
  })();

  (function() {
    const subBtn = document.getElementById('topic-sub');
    const unsubBtn = document.getElementById('topic-unsub');
    const rkInput = /** @type {HTMLInputElement|null} */(document.getElementById('topic-rk'));
    const log = document.getElementById('topic-log');
    let sub = null;
    function logLine(s) {
      if (!log) return;
      const ts = new Date().toISOString();
      topicLines.push(`[${ts}] ${s}`);
      if (topicLines.length > logLimit) topicLines.splice(0, topicLines.length - logLimit);
      log.textContent = topicLines.join('\n');
      log.scrollTop = log.scrollHeight;
    }
    if (!subBtn || !unsubBtn || !rkInput) return;
    subBtn.addEventListener('click', () => {
      const client = getClient();
      if (!client || !isConnected()) { appendSys('[BUZZ] Topic subscribe aborted: not connected'); return; }
      const rk = (rkInput.value || 'ev.#').trim();
      const dest = '/exchange/ph.control/' + rk;
      try {
        if (sub) { try { sub.unsubscribe(); } catch {} sub = null; }
        sub = client.subscribe(dest, (message) => {
          const b = message.body || '';
          logLine(`${message.headers.destination || ''} ${b}`);
        }, { ack: 'auto' });
        appendSys(`[BUZZ] SUB TOPIC ${dest}`);
      } catch (e) { appendSys('Topic subscribe error: ' + (e && e.message ? e.message : String(e))); }
    });
    unsubBtn.addEventListener('click', () => {
      if (sub) { try { sub.unsubscribe(); appendSys('[BUZZ] UNSUB TOPIC'); } catch {} sub = null; }
    });
  })();

  return { appendLog, appendSys };
}

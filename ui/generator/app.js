/* Copied from ZIP: generator.js */
(() => {
  const $ = (sel) => document.querySelector(sel);
  const $$ = (sel) => Array.from(document.querySelectorAll(sel));
  const fmt = (n) => Intl.NumberFormat().format(n);
  const kb = (b) => (b/1024).toFixed(1) + " KB";
  const LOG_LIMIT_DEFAULT = 500;
  let logLimit = LOG_LIMIT_DEFAULT;
  const logLines = [];

  // UUID v4 generator with fallbacks for older browsers
  function uuidv4(){
    try{
      if (typeof crypto !== 'undefined'){
        if (crypto.randomUUID) return crypto.randomUUID();
        if (crypto.getRandomValues){
          const b = crypto.getRandomValues(new Uint8Array(16));
          b[6] = (b[6] & 0x0f) | 0x40; // version 4
          b[8] = (b[8] & 0x3f) | 0x80; // variant 10xx
          const h = Array.from(b, x => x.toString(16).padStart(2,'0'));
          return `${h.slice(0,4).join('')}-${h.slice(4,6).join('')}-${h.slice(6,8).join('')}-${h.slice(8,10).join('')}-${h.slice(10).join('')}`;
        }
      }
    }catch{}
    // Math.random fallback (lower entropy but acceptable as last resort)
    const b = new Uint8Array(16);
    for(let i=0;i<16;i++) b[i] = (Math.random()*256)|0;
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    const h = Array.from(b, x => x.toString(16).padStart(2,'0'));
    return `${h.slice(0,4).join('')}-${h.slice(4,6).join('')}-${h.slice(6,8).join('')}-${h.slice(8,10).join('')}-${h.slice(10).join('')}`;
  }

  const PH_CONN_KEY = 'pockethive.conn';
  function loadConn(){ try{ const raw = localStorage.getItem(PH_CONN_KEY); return raw? JSON.parse(raw): null; }catch{ return null; } }
  function defaultWs(){ const scheme = location.protocol === 'https:' ? 'wss' : 'ws'; return `${scheme}://${location.host}/ws`; }

  let client = null;
  let connected = false;
  let sendTimer = null;
  let lastTick = 0;
  let sessionId = uuidv4();
  let resultQueuePrefix = "ph.results.";
  const inflight = new Map();
  const metrics = { sent: 0, ack: 0, errors: 0, inFlight: 0, recv: 0, tStart: 0, lastSecSent: 0, lastSecRecv: 0, latency: { count:0, total:0, min:Infinity, max:0, p95:0 } };
  let latencySamples = [];

  function log(msg) {
    const el = $("#log");
    if(!el) return;
    logLines.push(msg);
    if(logLines.length>logLimit) logLines.shift();
    el.textContent = logLines.join("\n");
    el.scrollTop = el.scrollHeight;
  }
  function setStatus(s, tone="off") { $("#status").textContent = s; const dot = $("#statusDot"); dot.className = "dot " + tone; }
  function updateMetrics() {
    $("#mSent .big").textContent = fmt(metrics.sent);
    $("#mAck .big").textContent = fmt(metrics.ack);
    $("#mErr .big").textContent = fmt(metrics.errors);
    $("#mInFlight .big").textContent = fmt(metrics.inFlight);
    $("#mRecv .big").textContent = fmt(metrics.recv);
    const elapsed = (performance.now() - metrics.tStart) / 1000 || 1;
    $("#mRate .big").textContent = (metrics.sent / elapsed).toFixed(1);
    const L = metrics.latency; if (L.count > 0) { $("#mLatAvg .big").textContent = (L.total / L.count).toFixed(1) + " ms"; $("#mLatMin .big").textContent = (L.min | 0) + " ms"; $("#mLatMax .big").textContent = (L.max | 0) + " ms"; $("#mLatP95 .big").textContent = (L.p95 | 0) + " ms"; }
  }
  function syncSlider(id) {
    const range = $("#" + id); const out = $("#" + id + "Val"); const number = $("#" + id + "Num");
    const set = (v) => { out.textContent = range.dataset.suffix ? (v + range.dataset.suffix) : v; if (number) number.value = v; };
    range.addEventListener("input", () => set(range.value)); if (number) number.addEventListener("input", () => { const v = Number(number.value) || 0; range.value = Math.min(range.max, Math.max(range.min, v)); set(range.value); }); set(range.value);
  }
  function randomAscii(size) { const chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_ .,:;!@#$%^&*()[]{}"; let out = ""; while (out.length < size) out += chars[Math.random()*chars.length|0]; return out; }

  function connect() {
    if (connected) return;
    const brokerURL = $("#brokerUrl").value.trim(); const vhost = $("#vhost").value.trim() || "/"; const login = $("#login").value.trim(); const passcode = $("#passcode").value.trim();
    if (!brokerURL || !login || !passcode) { log("Fill broker URL, login and passcode."); return; }
    if ($("#autoSession").checked) { sessionId = uuidv4(); $("#session").textContent = sessionId; } else { sessionId = $("#sessionInput").value.trim() || sessionId; $("#session").textContent = sessionId; }
    resultQueuePrefix = $("#resPrefix").value.trim() || "ph.results.";
    // eslint-disable-next-line no-undef
    client = new StompJs.Client({ brokerURL, connectHeaders: { login, passcode, host: vhost }, reconnectDelay: Number($("#reconnectMs").value) || 0, debug: (s) => { if ($("#debug").checked) log("[DEBUG] " + s); } });
    client.onConnect = () => { connected = true; setStatus("connected", "ok"); $("#btnConnect").disabled = true; $("#btnDisconnect").disabled = false; $("#btnSendOne").disabled = false; $("#btnStart").disabled = false; log("Connected."); const q = resultQueuePrefix + sessionId; client.subscribe(`/queue/${q}`, onResult, { ack: "auto" }); log(`Subscribed to /queue/${q}`); };
    client.onStompError = (frame) => { log("STOMP error: " + (frame.headers?.message || "") + "\n" + (frame.body || "")); setStatus("error", "err"); };
    client.onWebSocketClose = () => { connected = false; setStatus("disconnected", "off"); $("#btnConnect").disabled = false; $("#btnDisconnect").disabled = true; $("#btnSendOne").disabled = true; $("#btnStart").disabled = true; stopSending(); log("Disconnected."); };
    setStatus("connecting...", "warn"); client.activate();
  }
  function disconnect() { if (client) client.deactivate(); client = null; }
  function onResult(message) {
    metrics.recv++;
    if (message && message.headers && message.headers["correlation-id"]) { const cid = message.headers["correlation-id"]; const t0 = inflight.get(cid); if (t0) { const dt = performance.now() - t0; metrics.ack++; metrics.inFlight = Math.max(0, metrics.inFlight - 1); inflight.delete(cid); const L = metrics.latency; L.count++; L.total += dt; L.min = Math.min(L.min, dt); L.max = Math.max(L.max, dt); latencySamples.push(dt); if (latencySamples.length > 2000) latencySamples.shift(); const s = [...latencySamples].sort((a,b)=>a-b); L.p95 = s[Math.floor(s.length*0.95)] || dt; } }
    if ($("#showJson").checked) { try { const obj = message.body ? JSON.parse(message.body) : {}; log("Result:\n" + JSON.stringify(obj, null, 2)); } catch { log("Result (raw): " + message.body); } }
    updateMetrics();
  }
  function sendOne() {
    if (!client || !connected) { log("Not connected."); return; }
    const exchange = $("#exchange").value.trim() || "ph.hive"; const routingKey = $("#routingKey").value.trim() || "generator.request"; const expectReply = $("#expectReply").checked;
    const payloadSize = Number($("#payloadBytes").value); const prompt = $("#prompt").value.trim(); const nowIso = new Date().toISOString(); const correlationId = uuidv4();
    const body = { type: "GENERATION_REQUEST", prompt, sessionId, correlationId, timestamp: nowIso, payload: payloadSize > 0 ? randomAscii(payloadSize) : undefined };
    const headers = { "content-type":"application/json", "correlation-id": correlationId }; if (expectReply) headers["reply-to"] = (resultQueuePrefix + sessionId);
    try{ client.publish({ destination: `/exchange/${exchange}/${routingKey}`, body: JSON.stringify(body), headers }); metrics.sent++; metrics.inFlight++; inflight.set(correlationId, performance.now()); if (!metrics.tStart) metrics.tStart = performance.now(); } catch (e) { metrics.errors++; log("Publish error: " + e.message); }
    updateMetrics();
  }
  function startSending() {
    if (sendTimer) return; metrics.tStart = performance.now(); lastTick = performance.now(); const mode = $("#mode").value; const concurrency = Number($("#concurrency").value); const rps = Number($("#rps").value); const burst = Number($("#burst").value); const jitter = Number($("#jitter").value)/100; const maxIn = Number($("#maxInflight").value); const backpressure = $("#backpressure").checked; const duration = Number($("#duration").value);
    const tickMs = 100; let elapsedSec = 0; let sentThisSec = 0;
    sendTimer = setInterval(() => {
      if (!client || !connected) return; const now = performance.now(); const dt = now - lastTick; lastTick = now; elapsedSec += dt/1000; if (duration > 0 && elapsedSec >= duration) { stopSending(); return; } if (backpressure && metrics.inFlight >= maxIn) return;
      let toSend = 0; if (mode === "constant") { const base = rps * (dt/1000); const j = jitter > 0 ? base * ( (Math.random()*2-1) * jitter ) : 0; toSend = Math.max(0, Math.round(base + j)); } else if (mode === "burst") { if (Math.floor(elapsedSec) !== Math.floor(elapsedSec - dt/1000)) { toSend = burst; } else { toSend = 0; } } else if (mode === "poisson") { const p = Math.min(1, rps * (dt/1000)); toSend = 0; for (let i=0;i<concurrency;i++){ if (Math.random() < p) toSend++; } }
      if (backpressure && maxIn > 0) { const room = Math.max(0, maxIn - metrics.inFlight); toSend = Math.min(toSend, room); }
      toSend = Math.max(0, toSend * Math.max(1, concurrency)); for (let i=0;i<toSend;i++) sendOne(); sentThisSec += toSend; if (Math.floor(elapsedSec) !== Math.floor(elapsedSec - dt/1000)) { $("#rateNow").textContent = fmt(sentThisSec); sentThisSec = 0; }
    }, tickMs);
    $("#btnStart").disabled = true; $("#btnStop").disabled = false; setStatus("running", "ok");
  }
  function stopSending() { if (sendTimer) clearInterval(sendTimer); sendTimer = null; $("#btnStart").disabled = false; $("#btnStop").disabled = true; setStatus(connected ? "connected" : "disconnected", connected ? "ok" : "off"); }
  function resetMetrics() { metrics.sent = 0; metrics.ack = 0; metrics.errors = 0; metrics.inFlight = inflight.size; metrics.recv = 0; metrics.tStart = performance.now(); metrics.lastSecSent = 0; metrics.lastSecRecv = 0; metrics.latency = { count:0, total:0, min:Infinity, max:0, p95:0 }; latencySamples = []; updateMetrics(); }
  window.addEventListener("DOMContentLoaded", () => {
    // Prefill connection fields from main UI if present; else default to same-origin /ws
    try{
      const c = loadConn();
      if(c){
        if(c.url) $("#brokerUrl").value = c.url; else $("#brokerUrl").value = defaultWs();
        $("#vhost").value = c.vhost || '/';
        if(c.login) $("#login").value = c.login;
        if(c.pass) $("#passcode").value = c.pass;
      } else {
        $("#brokerUrl").value = defaultWs();
        $("#vhost").value = '/';
        $("#login").value = 'guest';
        $("#passcode").value = 'guest';
      }
    }catch{}
    $("#session").textContent = sessionId; syncSlider("rps"); syncSlider("burst"); syncSlider("payloadBytes"); syncSlider("concurrency"); syncSlider("maxInflight"); syncSlider("jitter"); syncSlider("duration"); $("#btnConnect").addEventListener("click", connect); $("#btnDisconnect").addEventListener("click", disconnect); $("#btnSendOne").addEventListener("click", sendOne); $("#btnStart").addEventListener("click", startSending); $("#btnStop").addEventListener("click", stopSending); $("#btnReset").addEventListener("click", resetMetrics); $("#autoSession").addEventListener("change", () => { const on = $("#autoSession").checked; $("#sessionInput").classList.toggle("hidden", on); });
    const logLimitInput = $("#logLimit");
    if(logLimitInput){
      logLimitInput.addEventListener("change", ()=>{
        const v = Number(logLimitInput.value) || LOG_LIMIT_DEFAULT;
        logLimit = Math.max(10, v);
        if(logLines.length>logLimit) logLines.splice(0, logLines.length - logLimit);
        const el = $("#log"); if(el) el.textContent = logLines.join("\n");
      });
    }
  });
})();

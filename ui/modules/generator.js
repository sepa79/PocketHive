export function renderGeneratorPanel(containerEl, instanceId){
  const client = window.phClient;
  containerEl.innerHTML = `\n    <div class="card" data-role="generator">\n      <h3>Generator ${instanceId}</h3>\n      <label>Rate per sec <input id="rate" type="range" min="0" max="100" value="5"></label>\n      <label>Mode <select id="mode"><option value="auto">Auto</option><option value="manual">Manual</option></select></label>\n      <div class="controls">\n        <button id="start">Start</button>\n        <button id="stop">Stop</button>\n        <button id="once">Once</button>\n      </div>\n      <div>TPS: <span id="tps">0</span></div>\n      <div>Latency: <span id="lat">0</span> ms</div>\n    </div>`;
  const rate = containerEl.querySelector('#rate');
  const mode = containerEl.querySelector('#mode');
  const startBtn = containerEl.querySelector('#start');
  const stopBtn = containerEl.querySelector('#stop');
  const onceBtn = containerEl.querySelector('#once');
  const tpsEl = containerEl.querySelector('#tps');
  const latEl = containerEl.querySelector('#lat');
  function sendConfig(data){
    const payload = {type:'config-update', role:'generator', instance:instanceId, data};
    const rk = `sig.config-update.generator.${instanceId}`;
    client.publish({destination:`/exchange/ph.control/${rk}`, body: JSON.stringify(payload)});
  }
  rate && rate.addEventListener('input', ()=> sendConfig({ratePerSec:Number(rate.value)}));
  mode && mode.addEventListener('change', ()=> sendConfig({mode:mode.value}));
  startBtn && startBtn.addEventListener('click', ()=> sendConfig({enabled:true}));
  stopBtn && stopBtn.addEventListener('click', ()=> sendConfig({enabled:false}));
  onceBtn && onceBtn.addEventListener('click', ()=> sendConfig({singleRequest:true}));
  if(client){
    client.subscribe(`/exchange/ph.control/ev.metric.generator.${instanceId}`, msg=>{
      try{ const data=JSON.parse(msg.body||'{}').data||{}; if(data.tps!=null && tpsEl) tpsEl.textContent=String(data.tps); if(data.latencyMs!=null && latEl) latEl.textContent=String(data.latencyMs); }catch(e){}
    });
    client.subscribe(`/exchange/ph.control/ev.status-full.generator.${instanceId}`, msg=>{
      try{ const data=JSON.parse(msg.body||'{}').data||{}; if(data.ratePerSec!=null && rate) rate.value=data.ratePerSec; }catch(e){}
    });
    const payload={type:'status-request',role:'generator',instance:instanceId};
    client.publish({destination:`/exchange/ph.control/sig.status-request.generator.${instanceId}`, body:JSON.stringify(payload)});
  }
}

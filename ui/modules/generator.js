export function renderGeneratorPanel(containerEl, instanceId, initial){
  const client = window.phClient;
  containerEl.innerHTML = `\n    <div class="card" data-role="generator">\n      <h3>Generator ${instanceId}</h3>\n      <div class="info">\n        <div>IN: <span id="in"></span></div>\n        <div>Routes: <span id="routes"></span></div>\n        <div>Publishes: <span id="pub"></span></div>\n        <div id="cfg"></div>\n      </div>\n      <label>Rate per sec <input id="rate" type="range" min="0" max="100" value="5"></label>\n      <div class="controls">\n        <button id="start">Start</button>\n        <button id="stop">Stop</button>\n        <button id="once">Once</button>\n      </div>\n      <div>TPS: <span id="tps">0</span></div>\n      <div>Latency: <span id="lat">0</span> ms</div>\n    </div>`;
  const rate = containerEl.querySelector('#rate');
  const startBtn = containerEl.querySelector('#start');
  const stopBtn = containerEl.querySelector('#stop');
  const onceBtn = containerEl.querySelector('#once');
  const tpsEl = containerEl.querySelector('#tps');
  const latEl = containerEl.querySelector('#lat');
  const inEl = containerEl.querySelector('#in');
  const routesEl = containerEl.querySelector('#routes');
  const pubEl = containerEl.querySelector('#pub');
  const cfgEl = containerEl.querySelector('#cfg');
  function sendConfig(data){
    const payload = {type:'config-update', role:'generator', instance:instanceId, data};
    const rk = `sig.config-update.generator.${instanceId}`;
    client.publish({destination:`/exchange/ph.control/${rk}`, body: JSON.stringify(payload)});
  }
  rate && rate.addEventListener('input', ()=> sendConfig({ratePerSec:Number(rate.value)}));
  startBtn && startBtn.addEventListener('click', ()=> sendConfig({enabled:true}));
  stopBtn && stopBtn.addEventListener('click', ()=> sendConfig({enabled:false}));
  onceBtn && onceBtn.addEventListener('click', ()=> sendConfig({singleRequest:true}));
  function apply(evt){
    if(!evt) return;
    const data=evt.data||{};
    if(data.ratePerSec!=null && rate) rate.value=data.ratePerSec;
    if(inEl) inEl.textContent=evt.inQueue&&evt.inQueue.name?evt.inQueue.name:'';
    if(routesEl) routesEl.textContent=evt.inQueue&&Array.isArray(evt.inQueue.routingKeys)?evt.inQueue.routingKeys.join(', '):'';
    if(pubEl) pubEl.textContent=Array.isArray(evt.publishes)?evt.publishes.join(', '):'';
    if(cfgEl){
      cfgEl.innerHTML='';
      const entries=[];
      if(evt.enabled!=null) entries.push(['enabled', evt.enabled]);
      for(const [k,v] of Object.entries(data)) entries.push([k,v]);
      entries.forEach(([k,v])=>{ const d=document.createElement('div'); d.textContent=`${k}: ${v}`; cfgEl.appendChild(d); });
    }
  }
  if(client){
    client.subscribe(`/exchange/ph.control/ev.metric.generator.${instanceId}`, msg=>{
      try{ const data=JSON.parse(msg.body||'{}').data||{}; if(data.tps!=null && tpsEl) tpsEl.textContent=String(data.tps); if(data.latencyMs!=null && latEl) latEl.textContent=String(data.latencyMs); }catch(e){}
    });
    client.subscribe(`/exchange/ph.control/ev.status-full.generator.${instanceId}`, msg=>{ try{ apply(JSON.parse(msg.body||'{}')); }catch(e){} });
    apply(initial);
    const payload={type:'status-request',role:'generator',instance:instanceId};
    client.publish({destination:`/exchange/ph.control/sig.status-request.generator.${instanceId}`, body:JSON.stringify(payload)});
  }
}

import { setupCommonInfo, applyCommonStatus } from './common.js';

export function renderGeneratorPanel(containerEl, instanceId, initial){
  const client = window.phClient;
  containerEl.innerHTML = `\n    <div class="card" data-role="generator">\n      <h3>Generator ${instanceId}</h3>\n      <div class="info"></div>\n      <label>Rate per sec <span id="rateVal">5</span> <input id="rate" type="range" min="0" max="100" value="5"></label>\n      <div class="controls">\n        <button id="start">Start</button>\n        <button id="stop">Stop</button>\n        <button id="once">Once</button>\n      </div>\n      <div>TPS: <span id="tps">0</span></div>\n      <div>Latency: <span id="lat">0</span> ms</div>\n    </div>`;
  const rate = containerEl.querySelector('#rate');
  const rateVal = containerEl.querySelector('#rateVal');
  const startBtn = containerEl.querySelector('#start');
  const stopBtn = containerEl.querySelector('#stop');
  const onceBtn = containerEl.querySelector('#once');
  const tpsEl = containerEl.querySelector('#tps');
  const latEl = containerEl.querySelector('#lat');
  const common = setupCommonInfo(containerEl.querySelector('.info'));
  function sendConfig(data){
    const payload = {type:'config-update', role:'generator', instance:instanceId, data};
    const rk = `sig.config-update.generator.${instanceId}`;
    client.publish({destination:`/exchange/ph.control/${rk}`, body: JSON.stringify(payload)});
  }
  rate && rate.addEventListener('input', ()=>{ const v=Number(rate.value); if(rateVal) rateVal.textContent=String(v); sendConfig({ratePerSec:v}); });
  startBtn && startBtn.addEventListener('click', ()=> sendConfig({enabled:true}));
  stopBtn && stopBtn.addEventListener('click', ()=> sendConfig({enabled:false}));
  onceBtn && onceBtn.addEventListener('click', ()=> sendConfig({singleRequest:true}));
  function apply(evt){
    if(!evt) return;
    const data=evt.data||{};
    if(data.ratePerSec!=null && rate){ rate.value=data.ratePerSec; if(rateVal) rateVal.textContent=String(data.ratePerSec); }
    applyCommonStatus(evt, common);
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

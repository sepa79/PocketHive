import { renderCommonPanel, applyCommonStatus } from './common.js';

export function renderGeneratorPanel(containerEl, instanceId, initial){
  const client = window.phClient;
  const extra = `
      <label>Rate per sec <span id="rateVal">5</span> <input id="rate" type="range" min="0" max="100" value="5"></label>
      <div class="controls"><button id="once">Once</button></div>`;
  const common = renderCommonPanel(containerEl, 'generator', instanceId, extra);
  const rate = containerEl.querySelector('#rate');
  const rateVal = containerEl.querySelector('#rateVal');
  const onceBtn = containerEl.querySelector('#once');
  function sendConfig(data){
    const payload = {type:'config-update', role:'generator', instance:instanceId, data};
    const rk = `sig.config-update.generator.${instanceId}`;
    client.publish({destination:`/exchange/ph.control/${rk}`, body: JSON.stringify(payload)});
  }
  rate && rate.addEventListener('input', ()=>{ const v=Number(rate.value); if(rateVal) rateVal.textContent=String(v); sendConfig({ratePerSec:v}); });
  onceBtn && onceBtn.addEventListener('click', ()=> sendConfig({singleRequest:true}));
  function apply(evt){
    if(!evt) return;
    const data=evt.data||{};
    if(data.ratePerSec!=null && rate){ rate.value=data.ratePerSec; if(rateVal) rateVal.textContent=String(data.ratePerSec); }
    applyCommonStatus(evt, common);
  }
  if(client){
    client.subscribe(`/exchange/ph.control/ev.status-full.generator.${instanceId}`, msg=>{ try{ apply(JSON.parse(msg.body||'{}')); }catch(e){} });
    apply(initial);
    const payload={type:'status-request',role:'generator',instance:instanceId};
    client.publish({destination:`/exchange/ph.control/sig.status-request.generator.${instanceId}`, body:JSON.stringify(payload)});
  }
}

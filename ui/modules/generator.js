import { renderCommonPanel, applyCommonStatus } from './common.js';

export function renderGeneratorPanel(containerEl, instanceId, initial){
  const client = window.phClient;
  const extra = `
      <div class="section">
        <div class="block-title">General</div>
        <label>Rate per sec <span id="rateVal">5.0</span> <input id="rate" type="range" min="0" max="100" step="0.1" value="5" style="width:400px"></label>
        <div class="controls"><button id="once">Once</button></div>
        <div class="controls"><button id="confirmGeneral">Confirm Changes</button></div>
      </div>
      <div class="section">
        <div class="block-title">Template Message</div>
        <label>Path <input id="msgPath" type="text"></label>
        <label>Method <input id="msgMethod" type="text"></label>
        <label>Body <input id="msgBody" type="text"></label>
        <label>Headers <textarea id="msgHeaders" rows="3"></textarea></label>
        <div class="controls"><button id="confirmMessage">Confirm Changes</button></div>
      </div>`;
  const common = renderCommonPanel(containerEl, 'generator', instanceId, extra);
  const rate = containerEl.querySelector('#rate');
  const rateVal = containerEl.querySelector('#rateVal');
  const onceBtn = containerEl.querySelector('#once');
  const confirmGeneral = containerEl.querySelector('#confirmGeneral');
  const pathInp = containerEl.querySelector('#msgPath');
  const methodInp = containerEl.querySelector('#msgMethod');
  const bodyInp = containerEl.querySelector('#msgBody');
  const headersInp = containerEl.querySelector('#msgHeaders');
  const confirmMessage = containerEl.querySelector('#confirmMessage');
  function sendConfig(data){
    const payload = {type:'config-update', role:'generator', instance:instanceId, data};
    const rk = `sig.config-update.generator.${instanceId}`;
    client.publish({destination:`/exchange/ph.control/${rk}`, body: JSON.stringify(payload)});
    const srPayload = {type:'status-request', role:'generator', instance:instanceId};
    client.publish({destination:`/exchange/ph.control/sig.status-request.generator.${instanceId}`, body: JSON.stringify(srPayload)});
  }
  rate && rate.addEventListener('input', ()=>{ const v=Number(rate.value); if(rateVal) rateVal.textContent=v.toFixed(1); });
  confirmGeneral && confirmGeneral.addEventListener('click', ()=>{ const v=Number(rate.value); sendConfig({ratePerSec:v}); });
  onceBtn && onceBtn.addEventListener('click', ()=> sendConfig({singleRequest:true}));
  confirmMessage && confirmMessage.addEventListener('click', ()=>{
    let h={};
    try{ h=JSON.parse(headersInp && headersInp.value || '{}'); }catch(e){}
    sendConfig({path:pathInp && pathInp.value, method:methodInp && methodInp.value, body:bodyInp && bodyInp.value, headers:h});
  });
  function apply(evt){
    if(!evt) return;
    const data=evt.data||{};
      if(data.ratePerSec!=null && rate){ rate.value=data.ratePerSec; if(rateVal) rateVal.textContent=Number(data.ratePerSec).toFixed(1); }
    if(data.path!=null && pathInp) pathInp.value=data.path;
    if(data.method!=null && methodInp) methodInp.value=data.method;
    if(data.body!=null && bodyInp) bodyInp.value=data.body;
    if(data.headers!=null && headersInp) headersInp.value=JSON.stringify(data.headers);
    applyCommonStatus(evt, common);
  }
  if(client){
    client.subscribe(`/exchange/ph.control/ev.status-full.generator.${instanceId}`, msg=>{ try{ apply(JSON.parse(msg.body||'{}')); }catch(e){} });
    apply(initial);
    const payload={type:'status-request',role:'generator',instance:instanceId};
    client.publish({destination:`/exchange/ph.control/sig.status-request.generator.${instanceId}`, body:JSON.stringify(payload)});
  }
}

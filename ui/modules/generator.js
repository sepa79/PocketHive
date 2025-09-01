import { renderCommonPanel, applyCommonStatus } from './common.js';

export function renderGeneratorPanel(containerEl, instanceId, initial){
  const client = window.phClient;
  const extra = `
      <label>Rate per sec <span id="rateVal">5</span> <input id="rate" type="range" min="0" max="100" value="5"></label>
      <div class="controls"><button id="once">Once</button></div>
      <div class="msg-config">
        <label>Path <input id="msgPath" type="text"></label>
        <label>Method <input id="msgMethod" type="text"></label>
        <label>Body <input id="msgBody" type="text"></label>
        <label>Headers <textarea id="msgHeaders" rows="3"></textarea></label>
      </div>`;
  const common = renderCommonPanel(containerEl, 'generator', instanceId, extra);
  const rate = containerEl.querySelector('#rate');
  const rateVal = containerEl.querySelector('#rateVal');
  const onceBtn = containerEl.querySelector('#once');
  const pathInp = containerEl.querySelector('#msgPath');
  const methodInp = containerEl.querySelector('#msgMethod');
  const bodyInp = containerEl.querySelector('#msgBody');
  const headersInp = containerEl.querySelector('#msgHeaders');
  function sendConfig(data){
    const payload = {type:'config-update', role:'generator', instance:instanceId, data};
    const rk = `sig.config-update.generator.${instanceId}`;
    client.publish({destination:`/exchange/ph.control/${rk}`, body: JSON.stringify(payload)});
    const srPayload = {type:'status-request', role:'generator', instance:instanceId};
    client.publish({destination:`/exchange/ph.control/sig.status-request.generator.${instanceId}`, body: JSON.stringify(srPayload)});
  }
  rate && rate.addEventListener('input', ()=>{ const v=Number(rate.value); if(rateVal) rateVal.textContent=String(v); sendConfig({ratePerSec:v}); });
  onceBtn && onceBtn.addEventListener('click', ()=> sendConfig({singleRequest:true}));
  pathInp && pathInp.addEventListener('change', ()=> sendConfig({path:pathInp.value}));
  methodInp && methodInp.addEventListener('change', ()=> sendConfig({method:methodInp.value}));
  bodyInp && bodyInp.addEventListener('change', ()=> sendConfig({body:bodyInp.value}));
  headersInp && headersInp.addEventListener('change', ()=>{ let h={}; try{ h=JSON.parse(headersInp.value||'{}'); }catch(e){} sendConfig({headers:h}); });
  function apply(evt){
    if(!evt) return;
    const data=evt.data||{};
    if(data.ratePerSec!=null && rate){ rate.value=data.ratePerSec; if(rateVal) rateVal.textContent=String(data.ratePerSec); }
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

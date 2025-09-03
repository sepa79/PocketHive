import { renderCommonPanel, applyCommonStatus } from './common.js';

export function renderTriggerPanel(containerEl, instanceId, initial){
  const client = window.phClient;
  const extra = `
        <div class="section">
          <div class="block-title">General</div>
          <label>Interval ms <input id="interval" type="number" min="0" step="1000" value="60000"></label>
          <label>Action Type
            <select id="actionType">
              <option value="shell">shell</option>
              <option value="rest">rest</option>
            </select>
          </label>
          <div class="controls"><button id="single">Trigger Now</button></div>
          <div class="controls"><button id="confirmGeneral">Confirm Changes</button></div>
        </div>
        <div class="section">
          <div class="block-title">Shell</div>
          <label>Command <input id="command" type="text"></label>
          <div class="controls"><button id="confirmShell">Confirm Changes</button></div>
        </div>
        <div class="section">
          <div class="block-title">REST</div>
          <label>URL <input id="url" type="text"></label>
          <label>Method <input id="method" type="text"></label>
          <label>Body <input id="body" type="text"></label>
          <label>Headers <textarea id="headers" rows="3"></textarea></label>
          <div class="controls"><button id="confirmRest">Confirm Changes</button></div>
        </div>`;
  const common = renderCommonPanel(containerEl, 'trigger', instanceId, extra);
  const intervalEl = containerEl.querySelector('#interval');
  const actionTypeEl = containerEl.querySelector('#actionType');
  const singleBtn = containerEl.querySelector('#single');
  const confirmGeneral = containerEl.querySelector('#confirmGeneral');
  const commandInp = containerEl.querySelector('#command');
  const confirmShell = containerEl.querySelector('#confirmShell');
  const urlInp = containerEl.querySelector('#url');
  const methodInp = containerEl.querySelector('#method');
  const bodyInp = containerEl.querySelector('#body');
  const headersInp = containerEl.querySelector('#headers');
  const confirmRest = containerEl.querySelector('#confirmRest');
  function sendConfig(data){
    const payload = {type:'config-update', role:'trigger', instance:instanceId, data};
    const rk = `sig.config-update.trigger.${instanceId}`;
    client.publish({destination:`/exchange/ph.control/${rk}`, body: JSON.stringify(payload)});
    const srPayload = {type:'status-request', role:'trigger', instance:instanceId};
    client.publish({destination:`/exchange/ph.control/sig.status-request.trigger.${instanceId}`, body: JSON.stringify(srPayload)});
  }
  confirmGeneral && confirmGeneral.addEventListener('click', ()=>{
    const interval = Number(intervalEl && intervalEl.value);
    const action = actionTypeEl && actionTypeEl.value;
    sendConfig({intervalMs:interval, actionType:action});
  });
  singleBtn && singleBtn.addEventListener('click', ()=> sendConfig({singleRequest:true}));
  confirmShell && confirmShell.addEventListener('click', ()=>{
    sendConfig({command:commandInp && commandInp.value});
  });
  confirmRest && confirmRest.addEventListener('click', ()=>{
    let h={};
    try{ h = JSON.parse(headersInp && headersInp.value || '{}'); }catch(e){}
    sendConfig({url:urlInp && urlInp.value, method:methodInp && methodInp.value, body:bodyInp && bodyInp.value, headers:h});
  });
  function apply(evt){
    if(!evt) return;
    const data = evt.data||{};
    if(data.intervalMs!=null && intervalEl) intervalEl.value=String(data.intervalMs);
    if(data.actionType!=null && actionTypeEl) actionTypeEl.value=data.actionType;
    if(data.command!=null && commandInp) commandInp.value=data.command;
    if(data.url!=null && urlInp) urlInp.value=data.url;
    if(data.method!=null && methodInp) methodInp.value=data.method;
    if(data.body!=null && bodyInp) bodyInp.value=data.body;
    if(data.headers!=null && headersInp) headersInp.value=JSON.stringify(data.headers);
    applyCommonStatus(evt, common);
  }
  if(client){
    client.subscribe(`/exchange/ph.control/ev.status-full.trigger.${instanceId}`, msg=>{ try{ apply(JSON.parse(msg.body||'{}')); }catch(e){} });
    apply(initial);
    const payload={type:'status-request',role:'trigger',instance:instanceId};
    client.publish({destination:`/exchange/ph.control/sig.status-request.trigger.${instanceId}`, body:JSON.stringify(payload)});
  }
}


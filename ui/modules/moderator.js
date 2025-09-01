import { renderCommonPanel, applyCommonStatus } from './common.js';

export function renderModeratorPanel(containerEl, instanceId, initial){
  const client = window.phClient;
  const extra = `
      <form class="controls">
        <label><input type="checkbox" id="rules"> Enable rules</label>
        <label>Filter <input id="filter" type="text"></label>
        <label>Limit <input id="limit" type="number" value="0"></label>
      </form>
      <div>TPS: <span id="tps">0</span></div>`;
  const common = renderCommonPanel(containerEl, 'moderator', instanceId, extra);
  const rules = containerEl.querySelector('#rules');
  const filter = containerEl.querySelector('#filter');
  const limit = containerEl.querySelector('#limit');
  const tpsEl = containerEl.querySelector('#tps');
  function sendConfig(){
    const data={rules:rules&&rules.checked, filter:filter?filter.value:'', limit:limit?Number(limit.value):0};
    const payload={type:'config-update',role:'moderator',instance:instanceId,data};
    const rk=`sig.config-update.moderator.${instanceId}`;
    client.publish({destination:`/exchange/ph.control/${rk}`, body:JSON.stringify(payload)});
  }
  rules && rules.addEventListener('change', sendConfig);
  filter && filter.addEventListener('change', sendConfig);
  limit && limit.addEventListener('change', sendConfig);
  function apply(evt){
    if(!evt) return;
    const data=evt.data||{};
    if(rules) rules.checked=!!data.rules;
    if(filter) filter.value=data.filter||'';
    if(limit) limit.value=data.limit!=null?String(data.limit):'0';
    applyCommonStatus(evt, common);
  }
  if(client){
    client.subscribe(`/exchange/ph.control/ev.metric.moderator.${instanceId}`, msg=>{
      try{ const data=JSON.parse(msg.body||'{}').data||{}; if(data.tps!=null && tpsEl) tpsEl.textContent=String(data.tps); }catch(e){}
    });
    client.subscribe(`/exchange/ph.control/ev.status-full.moderator.${instanceId}`, msg=>{ try{ apply(JSON.parse(msg.body||'{}')); }catch(e){} });
    apply(initial);
    const payload={type:'status-request',role:'moderator',instance:instanceId};
    client.publish({destination:`/exchange/ph.control/sig.status-request.moderator.${instanceId}`, body:JSON.stringify(payload)});
  }
}

export function renderModeratorPanel(containerEl, instanceId){
  const client = window.phClient;
  containerEl.innerHTML = `\n    <div class="card" data-role="moderator">\n      <h3>Moderator ${instanceId}</h3>\n      <form class="controls">\n        <label><input type="checkbox" id="rules"> Enable rules</label>\n        <label>Filter <input id="filter" type="text"></label>\n        <label>Limit <input id="limit" type="number" value="0"></label>\n      </form>\n      <div>TPS: <span id="tps">0</span></div>\n    </div>`;
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
  if(client){
    client.subscribe(`/exchange/ph.control/ev.metric.moderator.${instanceId}`, msg=>{
      try{ const data=JSON.parse(msg.body||'{}').data||{}; if(data.tps!=null && tpsEl) tpsEl.textContent=String(data.tps); }catch(e){}
    });
    const payload={type:'status-request',role:'moderator',instance:instanceId};
    client.publish({destination:`/exchange/ph.control/sig.status-request.moderator.${instanceId}`, body:JSON.stringify(payload)});
  }
}

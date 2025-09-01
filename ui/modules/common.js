export function setupCommonInfo(infoEl){
  if(!infoEl) return {};
  infoEl.innerHTML = `
    <div class="status-block">
      <div class="status-item"><span class="label">IN</span><span class="value" data-field="in"></span></div>
      <div class="status-item"><span class="label">Routes</span><span class="value" data-field="routes"></span></div>
      <div class="status-item"><span class="label">Publishes</span><span class="value" data-field="pub"></span></div>
    </div>
    <div class="config-block" data-field="cfg"></div>
  `;
  return {
    inEl: infoEl.querySelector('[data-field="in"]'),
    routesEl: infoEl.querySelector('[data-field="routes"]'),
    pubEl: infoEl.querySelector('[data-field="pub"]'),
    cfgEl: infoEl.querySelector('[data-field="cfg"]')
  };
}

export function applyCommonStatus(evt, refs){
  if(!evt) return;
  const {inEl, routesEl, pubEl, cfgEl} = refs || {};
  const queues = evt.queues || {};
  const inName = evt.inQueue && evt.inQueue.name
    ? evt.inQueue.name
    : (Array.isArray(queues.in) ? queues.in[0] : '');
  const routes = evt.inQueue && Array.isArray(evt.inQueue.routingKeys)
    ? evt.inQueue.routingKeys
    : (Array.isArray(evt.routes) ? evt.routes : []);
  const outs = Array.isArray(evt.publishes)
    ? evt.publishes
    : (Array.isArray(queues.out) ? queues.out : []);
  if(inEl) inEl.textContent = inName || '';
  if(routesEl) routesEl.textContent = Array.isArray(routes) ? routes.join(', ') : '';
  if(pubEl) pubEl.textContent = Array.isArray(outs) ? outs.join(', ') : '';
  if(cfgEl){
    cfgEl.innerHTML='';
    const data = evt.data || {};
    const entries = [];
    if(evt.enabled != null) entries.push(['enabled', evt.enabled]);
    for(const [k,v] of Object.entries(data)) entries.push([k,v]);
    entries.forEach(([k,v])=>{
      const d=document.createElement('div');
      d.className='config-item';
      d.innerHTML=`<span class="label">${k}</span><span class="value">${v}</span>`;
      cfgEl.appendChild(d);
    });
  }
}

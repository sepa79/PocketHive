function setupCommonInfo(infoEl){
  if(!infoEl) return {};
  infoEl.innerHTML = `
    <div class="block-title">Work Queues</div>
    <div class="status-block work-block">
      <div class="status-item"><span class="label">IN</span><span class="value" data-field="work-in"></span></div>
      <div class="status-item"><span class="label">Routes</span><span class="value" data-field="work-routes"></span></div>
      <div class="status-item"><span class="label">Publishes</span><span class="value" data-field="work-out"></span></div>
    </div>
    <div class="block-title">Control Queues</div>
    <div class="status-block control-block">
      <div class="status-item"><span class="label">IN</span><span class="value" data-field="ctrl-in"></span></div>
      <div class="status-item"><span class="label">Routes</span><span class="value" data-field="ctrl-routes"></span></div>
      <div class="status-item"><span class="label">Publishes</span><span class="value" data-field="ctrl-out"></span></div>
    </div>
    <div class="block-title">Config</div>
    <div class="config-block" data-field="cfg"></div>
  `;
  return {
    workInEl: infoEl.querySelector('[data-field="work-in"]'),
    workRoutesEl: infoEl.querySelector('[data-field="work-routes"]'),
    workOutEl: infoEl.querySelector('[data-field="work-out"]'),
    ctrlInEl: infoEl.querySelector('[data-field="ctrl-in"]'),
    ctrlRoutesEl: infoEl.querySelector('[data-field="ctrl-routes"]'),
    ctrlOutEl: infoEl.querySelector('[data-field="ctrl-out"]'),
    cfgEl: infoEl.querySelector('[data-field="cfg"]')
  };
}

export function renderCommonPanel(containerEl, role, instanceId, extraHtml=''){
  const title = role.charAt(0).toUpperCase() + role.slice(1);
  containerEl.innerHTML = `
    <div class="card" data-role="${role}">
      <h3>${title} ${instanceId}</h3>
      <div class="info"></div>
      <div class="controls common-controls">
        <button data-action="start">Start</button>
        <button data-action="stop">Stop</button>
      </div>
      <div class="custom">${extraHtml}</div>
    </div>`;
  const infoRefs = setupCommonInfo(containerEl.querySelector('.info'));
  const startBtn = containerEl.querySelector('[data-action="start"]');
  const stopBtn = containerEl.querySelector('[data-action="stop"]');
  const client = window.phClient;
  if(client){
    const sendEnabled = enabled => {
      const payload = {type:'config-update', role, instance:instanceId, data:{enabled}};
      const rk = `sig.config-update.${role}.${instanceId}`;
      client.publish({destination:`/exchange/ph.control/${rk}`, body: JSON.stringify(payload)});
    };
    startBtn && startBtn.addEventListener('click', ()=>sendEnabled(true));
    stopBtn && stopBtn.addEventListener('click', ()=>sendEnabled(false));
  }
  return infoRefs;
}

export function applyCommonStatus(evt, refs){
  if(!evt) return;
  const {
    workInEl, workRoutesEl, workOutEl,
    ctrlInEl, ctrlRoutesEl, ctrlOutEl,
    cfgEl
  } = refs || {};
  const queues = evt.queues || {};
  const work = queues.work || {};
  const control = queues.control || {};
  const win = Array.isArray(work.in) ? work.in[0] : '';
  const wroutes = Array.isArray(work.routes) ? work.routes : [];
  const wout = Array.isArray(work.out) ? work.out : (Array.isArray(evt.publishes) ? evt.publishes : []);
  const cin = Array.isArray(control.in) ? control.in[0] : (evt.inQueue && evt.inQueue.name ? evt.inQueue.name : '');
  const croutes = Array.isArray(control.routes) ? control.routes : (evt.inQueue && Array.isArray(evt.inQueue.routingKeys) ? evt.inQueue.routingKeys : []);
  const cout = Array.isArray(control.out) ? control.out : [];
  if(workInEl) workInEl.textContent = win || '';
  if(workRoutesEl) workRoutesEl.textContent = wroutes.join(', ');
  if(workOutEl) workOutEl.textContent = wout.join(', ');
  if(ctrlInEl) ctrlInEl.textContent = cin || '';
  if(ctrlRoutesEl) ctrlRoutesEl.textContent = croutes.join(', ');
  if(ctrlOutEl) ctrlOutEl.textContent = cout.join(', ');
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

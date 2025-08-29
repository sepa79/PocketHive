// PocketHive – Network background (v4: subtle XY Z float + skew)
(function(){
  const root = document.querySelector('.ph-bg-net') || document.body;
  if(!root) return;

  const canvas = document.createElement('canvas');
  canvas.className = 'net-layer';
  const ctx = canvas.getContext('2d');
  root.appendChild(canvas);

  // Center logo: wrapper with perspective -> inner object for transforms
  const logoWrap = document.createElement('div');
  logoWrap.className = 'net-logo';
  const logoObj = document.createElement('div');
  logoObj.className = 'net-logo-obj';
  const logoImg = document.createElement('img');
  logoImg.alt = 'PocketHive logo';
  logoImg.decoding = 'async';
  logoImg.src = './assets/logo.svg';
  logoObj.appendChild(logoImg);
  logoWrap.appendChild(logoObj);
  root.appendChild(logoWrap);

  // State
  const state = {
    density: 54,
    theme: 'cyan',
    nodeSize: 2.6,
    linkDist: 200,
    kNearest: 5,
    extraLinks: 2,
    forceConnected: true,
    pulseSpeed: 0.9,
    driftAmp: 18,
    driftSpeed: 0.22,
    edgeThickness: 1.0,
    glowStrength: 14,
    logoScale: 1.2,
    logoOpacity: 0.12,
    logoBlend: 'normal',
    // very subtle floating params
    logoFloatAmpX: 8,     // px
    logoFloatAmpY: 12,    // px
    logoFloatAmpZ: 30,    // px (perceived via perspective)
    logoFloatSpeedX: 0.30,
    logoFloatSpeedY: 0.45,
    logoFloatSpeedZ: 0.25,
    logoSkewAmp: 1.2,     // deg
    logoSkewSpeed: 0.35,
    centerHole: 0.26,
  };

  const colors = {
    amber: { edge: 'rgba(255,193,7,0.6)', pulse: 'rgba(255,193,7,1)', node: 'rgba(255,255,255,0.95)' },
    green: { edge: 'rgba(0,255,156,0.55)', pulse: 'rgba(0,255,156,1)', node: 'rgba(230,255,240,0.95)' },
    cyan:  { edge: 'rgba(51,225,255,0.60)', pulse: 'rgba(120,200,255,1)', node: 'rgba(230,245,255,0.96)' },
  };
  function setTheme(t){ state.theme = (t==='green'||t==='amber')? t : 'cyan'; }

  // Resize
  let W=0,H=0, DPR=1, CX=0, CY=0, RAD_EX=0;
  function applyLogoVars(){
    document.documentElement.style.setProperty('--net-logo-scale', state.logoScale);
    document.documentElement.style.setProperty('--net-logo-opacity', state.logoOpacity);
    document.documentElement.style.setProperty('--net-logo-blend', state.logoBlend);
  }
  function resize(){
    DPR = Math.max(1, Math.min(2, window.devicePixelRatio || 1));
    W = Math.floor(root.clientWidth || window.innerWidth);
    H = Math.floor(root.clientHeight || window.innerHeight);
    canvas.width = Math.floor(W * DPR);
    canvas.height = Math.floor(H * DPR);
    canvas.style.width = W+'px'; canvas.style.height = H+'px';
    ctx.setTransform(DPR,0,0,DPR,0,0);
    CX=W/2; CY=H/2; RAD_EX = Math.min(W,H) * state.centerHole;
    applyLogoVars();
  }
  resize(); window.addEventListener('resize', resize, {passive:true});

  // Graph
  let nodes=[], edges=[];
  function seedNodes(){
    nodes.length=0; edges.length=0;
    const N = state.density;
    for(let i=0;i<N;i++){
      let x,y, tries=0;
      do{
        x = Math.random()*W;
        y = Math.random()*H;
        tries++;
        if(tries>400) break;
      }while(((x-CX)**2 + (y-CY)**2) < RAD_EX*RAD_EX);
      nodes.push({
        x, y, ox:x, oy:y,
        phase: Math.random()*Math.PI*2,
        speed: (0.5 + Math.random())*state.driftSpeed,
        size: state.nodeSize * (0.8 + Math.random()*0.6),
        seed: Math.random()*1000
      });
    }
    rebuildEdges(true);
  }

  function dist2(a,b){ const dx=a.x-b.x, dy=a.y-b.y; return dx*dx+dy*dy; }

  function rebuildEdges(forceConnect=false){
    edges.length=0;
    const kd = state.kNearest, maxD = state.linkDist; const maxD2 = maxD*maxD;
    const seen = new Set();
    for(let i=0;i<nodes.length;i++){
      const a = nodes[i]; const list = [];
      for(let j=0;j<nodes.length;j++){
        if(i===j) continue;
        const b = nodes[j]; const d2 = dist2(a,b);
        if(d2 <= maxD2) list.push({j, d2});
      }
      list.sort((u,v)=> u.d2 - v.d2);
      const baseCount = Math.min(kd, list.length);
      for(let k=0;k<baseCount; k++){
        const j = list[k].j;
        const key = i<j? (i+'-'+j) : (j+'-'+i);
        if(!seen.has(key)){ seen.add(key); edges.push({ i: Math.min(i,j), j: Math.max(i,j), off: Math.random()*1000 }); }
      }
      let extra = state.extraLinks;
      for(let k=baseCount; k<list.length && extra>0; k++){
        const j = list[k].j;
        const key = i<j? (i+'-'+j) : (j+'-'+i);
        if(!seen.has(key)){
          seen.add(key);
          edges.push({ i: Math.min(i,j), j: Math.max(i,j), off: Math.random()*1000 });
          extra--;
        }
      }
    }
    if(forceConnect && state.forceConnected) ensureConnected(seen);
  }

  function ensureConnected(seen){
    const parent = Array.from({length:nodes.length}, (_,i)=>i);
    const find = (x)=>{ while(parent[x]!==x){ parent[x]=parent[parent[x]]; x=parent[x]; } return x; };
    const unite = (a,b)=>{ a=find(a); b=find(b); if(a!==b) parent[a]=b; };
    for(const e of edges){ unite(e.i, e.j); }
    const compMap = new Map();
    for(let i=0;i<nodes.length;i++){ const r=find(i); if(!compMap.has(r)) compMap.set(r, []); compMap.get(r).push(i); }
    const groups = Array.from(compMap.values());
    if(groups.length<=1) return;
    let base = groups[0].slice();
    for(let g=1; g<groups.length; g++){
      const other = groups[g]; let best = null;
      for(const i of base){ for(const j of other){
        const d2 = dist2(nodes[i], nodes[j]); if(best===null || d2 < best.d2) best = {i, j, d2};
      }}
      if(best){
        const i = Math.min(best.i, best.j), j = Math.max(best.i, best.j);
        const key = i+'-'+j;
        if(!seen.has(key)){ seen.add(key); edges.push({ i, j, off: Math.random()*1000 }); }
        base = base.concat(other);
      }
    }
  }

  // Animation
  let t0 = performance.now();
  let running = true;
  let raf = 0;
  function loop(now){
    if(!running) return;
    raf = requestAnimationFrame(loop);
    const dt = (now - t0) * 0.001; t0 = now;

    // Very subtle floating & skew (deg)
    const TAU = Math.PI * 2;
    const x = Math.sin(now*0.001 * (state.logoFloatSpeedX*TAU)) * state.logoFloatAmpX;
    const y = Math.sin(now*0.001 * (state.logoFloatSpeedY*TAU)) * state.logoFloatAmpY;
    const z = Math.sin(now*0.001 * (state.logoFloatSpeedZ*TAU)) * state.logoFloatAmpZ;
    const skewX = Math.sin(now*0.001 * (state.logoSkewSpeed*TAU)) * state.logoSkewAmp;
    const skewY = Math.cos(now*0.001 * (state.logoSkewSpeed*0.9*TAU)) * (state.logoSkewAmp*0.6);
    logoObj.style.transform = `translate3d(${x.toFixed(2)}px, ${y.toFixed(2)}px, ${z.toFixed(2)}px) skew(${skewX.toFixed(3)}deg, ${skewY.toFixed(3)}deg)`;

    // Drift nodes
    const A = state.driftAmp;
    for(const n of nodes){
      n.phase += n.speed * dt;
      const dx = Math.sin(n.phase + n.seed)*A;
      const dy = Math.cos(n.phase*0.9 + n.seed)*A;
      n.x = n.ox + dx; n.y = n.oy + dy;
    }

    // Draw
    ctx.clearRect(0,0,W,H);
    const col = colors[state.theme];

    // Edges
    ctx.lineCap='round';
    for(const e of edges){
      const a = nodes[e.i], b = nodes[e.j];
      const d = Math.hypot(a.x-b.x, a.y-b.y);
      const alpha = Math.max(0, Math.min(1, 1 - d/ state.linkDist));
      const baseAlpha = 0.30 + 0.60*alpha;
      ctx.strokeStyle = col.edge.replace(/0\.\d+/, baseAlpha.toFixed(2));
      ctx.lineWidth = (1.0 + 1.0*alpha) * state.edgeThickness;
      ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke();

      // pulse
      const dash = Math.max(18, d*0.18), gap = dash*0.7;
      e.off = (e.off + 68*state.pulseSpeed*dt) % (dash+gap);
      ctx.setLineDash([dash, gap]);
      ctx.lineDashOffset = -e.off;
      ctx.strokeStyle = col.pulse;
      ctx.lineWidth = (1.6 + 1.4*alpha) * state.edgeThickness;
      ctx.globalAlpha = 0.22 + 0.36*alpha;
      ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke();
      ctx.setLineDash([]);
      ctx.globalAlpha = 1;
    }

    // Nodes
    for(const n of nodes){
      ctx.save();
      ctx.shadowColor = col.pulse;
      ctx.shadowBlur = state.glowStrength;
      ctx.fillStyle = colors[state.theme].node;
      ctx.beginPath(); ctx.arc(n.x, n.y, n.size, 0, Math.PI*2); ctx.fill();
      ctx.restore();
    }
  }

  // API (including logo motion controls)
  function setDensity(n){ state.density=Math.max(0,Math.floor(n)); seedNodes(); }
  function setThemeAPI(name){ setTheme(name); }
  function setNodeSize(px){ state.nodeSize=Math.max(0.5, Number(px)||2.6); seedNodes(); }
  function setLinking(dist, k, extra, forceConn){
    if(dist!=null) state.linkDist=Math.max(20, Number(dist)||200);
    if(k!=null) state.kNearest=Math.max(1, Math.floor(k));
    if(extra!=null) state.extraLinks=Math.max(0, Math.floor(extra));
    if(forceConn!=null) state.forceConnected=!!forceConn;
    rebuildEdges(true);
  }
  function setPulseSpeed(s){ state.pulseSpeed=Math.max(0, Number(s)||0.9); }
  function setDrift(amp, speed){ if(amp!=null) state.driftAmp=Math.max(0, Number(amp)); if(speed!=null) state.driftSpeed=Math.max(0, Number(speed)); }
  function setEdgeThickness(v){ state.edgeThickness=Math.max(0.5, Math.min(3, Number(v)||1)); }
  function setGlow(v){ state.glowStrength=Math.max(0, Math.min(36, Number(v)||14)); }
  function setLogoScale(v){ state.logoScale=Math.max(0.2, Math.min(2.0, Number(v)||1.2)); applyLogoVars(); }
  function setLogoOpacity(v){ state.logoOpacity=Math.max(0.02, Math.min(0.6, Number(v)||0.12)); applyLogoVars(); }
  function setLogoBlend(mode){ state.logoBlend = (mode==='lighten'||mode==='screen')? mode : 'normal'; applyLogoVars(); }
  function setLogoFloat(amp, speed){
    if(amp && typeof amp==='object'){
      if(amp.x!=null) state.logoFloatAmpX=Math.max(0, Number(amp.x));
      if(amp.y!=null) state.logoFloatAmpY=Math.max(0, Number(amp.y));
      if(amp.z!=null) state.logoFloatAmpZ=Math.max(0, Number(amp.z));
      if(amp.skew!=null) state.logoSkewAmp=Math.max(0, Number(amp.skew));
    }else if(amp!=null){
      state.logoFloatAmpY=Math.max(0, Number(amp));
    }
    if(speed && typeof speed==='object'){
      if(speed.x!=null) state.logoFloatSpeedX=Math.max(0.01, Number(speed.x));
      if(speed.y!=null) state.logoFloatSpeedY=Math.max(0.01, Number(speed.y));
      if(speed.z!=null) state.logoFloatSpeedZ=Math.max(0.01, Number(speed.z));
      if(speed.skew!=null) state.logoSkewSpeed=Math.max(0.01, Number(speed.skew));
    }else if(speed!=null){
      state.logoFloatSpeedY=Math.max(0.01, Number(speed));
    }
  }
  function setCenterHole(r){ state.centerHole=Math.max(0.1, Math.min(0.5, Number(r)||0.26)); resize(); seedNodes(); }
  function reseed(){ seedNodes(); }

  function start(){ if(running) return; running=true; t0=performance.now(); raf=requestAnimationFrame(loop); }
  function pause(){ running=false; if(raf) { cancelAnimationFrame(raf); raf=0; } }

  window.PocketHiveNet = {
    setDensity, setTheme: setThemeAPI, setNodeSize, setLinking, setPulseSpeed,
    setDrift, setEdgeThickness, setGlow, setLogoScale, setLogoOpacity, setLogoBlend,
    setLogoFloat, setCenterHole, reseed,
    start, pause
  };

  setTheme(state.theme);
  resize(); seedNodes();
  raf = requestAnimationFrame(loop);
})();

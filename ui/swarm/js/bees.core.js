// PocketHive – Swarm Matrix Bees (core engine)
// Exposes window.PocketHiveBees API; no UI injected here.
(function(){
  const root = document.querySelector('.ph-bg-bees');
  if(!root) return;

  // Precomputed 8-bit style sine table (C64-like): 256 samples over 0..2π scaled to [-1,1]
  // Using a Float32Array for fast multiply; values are in range [-1, 1].
  const SIN256 = new Float32Array(256);
  for(let i=0;i<256;i++){
    SIN256[i] = Math.sin((i/256) * Math.PI * 2);
  }

  const state = {
    density: 20, variant: 'amber', pattern: 'bezier',
    glyphSpeedFactor: 0.75, trailCount: 9, trailBaseLag: 0.12, trailStepLag: 0.06, trailDecay: 1.00,
    durMin: 8, durMax: 16, stepMs: 33, blobSpeed: 28,
    running: true,
    sine: {
      // Simplified C64 traveling wave params
      ampFrac: 0.42,        // amplitude as fraction of screen height
      wavelength: 240,      // pixels per sine period
      speed: 160,           // horizontal speed in px/s
      quantize: true        // round positions for retro look
    },
    rain: { vyMin: 80, vyMax: 220 }
  };

  const layer = document.createElement('div'); layer.className = 'bee-layer'; root.appendChild(layer);
  function applyVariant(){ root.classList.toggle('matrix-green', state.variant==='green'); }
  function applyBlobSpeed(){ document.documentElement.style.setProperty('--blob-speed', state.blobSpeed+'s'); }
  applyVariant(); applyBlobSpeed();

  const supportsMotion = (()=>{ try{
    const okPath = CSS && CSS.supports && CSS.supports('offset-path', 'path("M0,0 L100,0")');
    const okDist = CSS && CSS.supports && CSS.supports('offset-distance', '0%');
    return !!(okPath && okDist);
  }catch(e){ return false; } })();

  const CHARS = '01/*+-<>|[]{}=~^⨉カナ日月水火土電光λπΣΞΔβ';
  const randChar = () => CHARS[Math.floor(Math.random()*CHARS.length)];
  const rand = (a,b)=> a + Math.random()*(b-a);
  const randint = (a,b)=> Math.floor(rand(a,b+1));
  function glyphTicker(el){ (function tick(){ const t = randint(120,420)*state.glyphSpeedFactor; setTimeout(()=>{ if(!state.running) return; el.textContent = randChar(); tick(); }, t); })(); }

  function randomPath(w,h){
    const x0 = rand(0.05*w,0.95*w), y0 = rand(0.05*h,0.95*h);
    const x1 = rand(0.05*w,0.95*w), y1 = rand(0.05*h,0.95*h);
    const x2 = rand(0.05*w,0.95*w), y2 = rand(0.05*h,0.95*h);
    const x3 = rand(0.05*w,0.95*w), y3 = rand(0.05*h,0.95*h);
    return {p0:[x0,y0], p1:[x1,y1], p2:[x2,y2], p3:[x3,y3]};
  }
  const pathD = p => `M ${p.p0[0]} ${p.p0[1]} C ${p.p1[0]} ${p.p1[1]}, ${p.p2[0]} ${p.p2[1]}, ${p.p3[0]} ${p.p3[1]}`;
  function cubic(p0,p1,p2,p3,t){ const mt=1-t;
    return [ mt*mt*mt*p0[0] + 3*mt*mt*t*p1[0] + 3*mt*t*t*p2[0] + t*t*t*p3[0],
             mt*mt*mt*p0[1] + 3*mt*mt*t*p1[1] + 3*mt*t*t*p2[1] + t*t*t*p3[1] ]; }

  function makeTrails(parent, lead){
    const trails = [];
    const baseOpacity=0.22, fadeStrength=0.70*state.trailDecay, baseBlur=1.2, maxExtraBlur=2.6*state.trailDecay, mixStrength=40*state.trailDecay;
    for(let i=0;i<state.trailCount;i++){
      const t = document.createElement('span'); t.className='trail';
      const k=(i+1)/Math.max(1,state.trailCount);
      const op = baseOpacity * (1 - k*fadeStrength);
      const blur = baseBlur + k*maxExtraBlur;
      const mix = 55 - k*mixStrength;
      t.style.setProperty('--op', op.toFixed(3));
      t.style.setProperty('--blur', blur.toFixed(2)+'px');
      t.style.setProperty('--mix', mix.toFixed(1)+'%');
      t.textContent = parent.firstChild ? parent.firstChild.textContent : randChar();
      setTimeout(()=> glyphTicker(t), randint(90,240));
      trails.push(t); parent.appendChild(t);
    }
    return trails;
  }

  function makeBeeBezier(w,h, i, n){
    const wrap=document.createElement('div'); wrap.className='bee'; wrap.style.setProperty('--size', randint(12,18)+'px');
    const lead=document.createElement('span'); lead.className='glyph'; lead.textContent=randChar(); glyphTicker(lead); wrap.appendChild(lead);
    const trails=makeTrails(wrap, lead);
    const path=randomPath(w,h); const d=pathD(path); const dur=rand(state.durMin,state.durMax); const start=performance.now()-rand(0,dur)*1000;
    if (supportsMotion){
      lead.style.offsetPath=`path("${d}")`; trails.forEach(t=>t.style.offsetPath=`path("${d}")`);
      const loop=(now)=>{ if(!state.running) return; const t=((now-start)/(dur*1000))%1; lead.style.offsetDistance=(t*100)+'%';
        for(let i=0;i<trails.length;i++){ const lag=state.trailBaseLag+i*state.trailStepLag; const dtd=(t - lag/dur + 1) % 1; trails[i].style.offsetDistance=(dtd*100)+'%'; }
        requestAnimationFrame(loop); }; requestAnimationFrame(loop);
    } else {
      const q=[]; const maxLag=state.trailBaseLag+state.trailCount*state.trailStepLag; const maxQ=Math.ceil((maxLag*1000)/state.stepMs)+4; let last=0;
      const loop=(now)=>{ if(!state.running) return; if(now-last<state.stepMs) return requestAnimationFrame(loop); last=now;
        const t=((now-start)/(dur*1000))%1; const [x,y]=cubic(path.p0,path.p1,path.p2,path.p3,t); q.push([x,y]); if(q.length>maxQ) q.shift();
        lead.style.transform=`translate(${x}px,${y}px) translate(-50%,-50%)`;
        for(let i=0;i<trails.length;i++){ const lagSec=state.trailBaseLag+i*state.trailStepLag; const idx=Math.max(0,q.length-Math.floor((lagSec*1000)/state.stepMs)); const p=q[idx]||q[0]||[x,y];
          trails[i].style.transform=`translate(${p[0]}px,${p[1]}px) translate(-50%,-50%)`; }
        requestAnimationFrame(loop); }; requestAnimationFrame(loop);
    }
    return wrap;
  }

  function makeBeeSine(w,h, i, n){
    const wrap=document.createElement('div'); wrap.className='bee'; wrap.style.setProperty('--size', randint(12,18)+'px');
    const lead=document.createElement('span'); lead.className='glyph'; lead.textContent=randChar(); glyphTicker(lead); wrap.appendChild(lead);
    const trails=makeTrails(wrap, lead);
    // Simplified C64 traveling sine wave: all bees lie on the same wave (row)
    // y = midY + amp * sin((x / wavelength) * 2π)
    const ampFrac = Math.max(0.05, Math.min(0.49, Number(state.sine.ampFrac)||0.42));
    const amp = Math.round(ampFrac * h);
    const wavelength = Math.max(40, Math.min(800, Number(state.sine.wavelength)||240));
    const speed = Math.max(10, Math.min(800, Number(state.sine.speed)||160)); // px/s, to the right
    const spacing = Math.max(8, w / Math.max(1,n));
    const midY = Math.round(h/2);
    // Stable global start to sync the row
    if(!window.__phSineStart) window.__phSineStart = performance.now();
    const start = window.__phSineStart;
    const q=[]; const maxLag=state.trailBaseLag+state.trailCount*state.trailStepLag; const maxQ=Math.ceil((maxLag*1000)/state.stepMs)+4; let last=0;
    const loop=(now)=>{ if(!state.running) return; if(now-last<state.stepMs) return requestAnimationFrame(loop); last=now;
      const t = (now - start) / 1000;
      // Horizontal travel with wrap
      let x = (i*spacing + (speed * t)) % (w + spacing);
      if(x < -16) x += (w + spacing);
      // Map x to sine index: idx = ((x / wavelength) * 256) mod 256
      let idx = Math.floor(((x / wavelength) * 256)) & 255;
      let y = midY + amp * SIN256[idx];
      if(state.sine.quantize){ x = Math.round(x); y = Math.round(y); }
      q.push([x,y]); if(q.length>maxQ) q.shift();
      lead.style.transform=`translate(${x}px,${y}px) translate(-50%,-50%)`;
      for(let i=0;i<trails.length;i++){ const lagSec=state.trailBaseLag+i*state.trailStepLag; const idx=Math.max(0,q.length-Math.floor((lagSec*1000)/state.stepMs)); const p=q[idx]||q[0]||[x,y];
        trails[i].style.transform=`translate(${p[0]}px,${p[1]}px) translate(-50%,-50%)`; }
      requestAnimationFrame(loop); }; requestAnimationFrame(loop);
    return wrap;
  }

  function makeBeeMatrix(w,h, i, n){
    const wrap=document.createElement('div'); wrap.className='bee'; wrap.style.setProperty('--size', randint(12,18)+'px');
    const lead=document.createElement('span'); lead.className='glyph'; lead.textContent=randChar(); glyphTicker(lead); wrap.appendChild(lead);
    const trails=makeTrails(wrap, lead);
    const x=rand(30,w-30), vy=rand(state.rain.vyMin,state.rain.vyMax); let y0=-rand(0,h); const start=performance.now()-rand(0,2000);
    const q=[]; const maxLag=state.trailBaseLag+state.trailCount*state.trailStepLag; const maxQ=Math.ceil((maxLag*1000)/state.stepMs)+4; let last=0;
    const loop=(now)=>{ if(!state.running) return; if(now-last<state.stepMs) return requestAnimationFrame(loop); last=now;
      const t=(now-start)/1000; let y=y0 + vy*t; if(y>h+40){ y0=-40; y=y0; }
      q.push([x,y]); if(q.length>maxQ) q.shift();
      lead.style.transform=`translate(${x}px,${y}px) translate(-50%,-50%)`;
      for(let i=0;i<trails.length;i++){ const lagSec=state.trailBaseLag+i*state.trailStepLag; const idx=Math.max(0,q.length-Math.floor((lagSec*1000)/state.stepMs)); const p=q[idx]||q[0]||[x,y];
        trails[i].style.transform=`translate(${p[0]}px,${p[1]}px) translate(-50%,-50%)`; }
      requestAnimationFrame(loop); }; requestAnimationFrame(loop);
    return wrap;
  }

  function seed(count){
    layer.innerHTML='';
    const w=root.clientWidth || window.innerWidth, h=root.clientHeight || window.innerHeight;
    const prefersReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const N = prefersReduced ? Math.min(20, Math.max(6, Math.floor(count*0.25))) : count;
    const factory = state.pattern==='bezier'? makeBeeBezier : state.pattern==='sine'? makeBeeSine : makeBeeMatrix;
    for(let i=0;i<N;i++) layer.appendChild(factory(w,h,i,N));
  }

  // Public API
  window.PocketHiveBees = {
    // Density changes require reseed to adjust population size
    setDensity(n){ state.density=Math.max(0,Number(n)||0); seed(state.density); },
    // Visual variant can switch instantly without reseed
    setVariant(v){ state.variant=(v==='green'?'green':'amber'); applyVariant(); },
    // Speed/Trail params update state; next animation ticks use new values
    setGlyphSpeedFactor(f){ state.glyphSpeedFactor=Math.max(0.1, Number(f)||0.1); },
    setTrail(count=9, base=0.12, step=0.06){ state.trailCount=Math.max(0,Math.floor(count)); state.trailBaseLag=Math.max(0,Number(base)||0); state.trailStepLag=Math.max(0,Number(step)||0); },
    setTrailDecay(d){ state.trailDecay=Math.max(0, Math.min(1, Number(d)||0)); },
    setBlobSpeed(sec){ state.blobSpeed=Math.max(4, Number(sec)||28); applyBlobSpeed(); },
    // Pattern change requires reseed to rebuild paths/behaviour
    setPattern(p){ state.pattern=(p==='sine'||p==='matrix')?p:'bezier'; seed(state.density); },
    setBezierParams(o){ if(o){ if(o.durMin!=null) state.durMin=Math.max(1,Number(o.durMin)); if(o.durMax!=null) state.durMax=Math.max(state.durMin,Number(o.durMax)); } },
    setSineParams(o){ if(o){ Object.assign(state.sine, o); } },
    setMatrixParams(o){ if(o){ Object.assign(state.rain, o); } },
    reseed(){ seed(state.density); },
    start(){ if(state.running) return; state.running = true; seed(state.density); },
    pause(){ state.running = false; },
    _getState(){ return JSON.parse(JSON.stringify(state)); }
  };

  seed(state.density);
  let raf=0; window.addEventListener('resize', ()=>{ cancelAnimationFrame(raf); raf=requestAnimationFrame(()=> seed(state.density)); }, {passive:true});
})();

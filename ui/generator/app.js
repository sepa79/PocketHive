(()=>{
  const PH_CONN_KEY='pockethive.conn';
  function loadConn(){try{const raw=localStorage.getItem(PH_CONN_KEY);return raw?JSON.parse(raw):null;}catch{return null;}}
  function defaultWs(){const scheme=location.protocol==='https:'?'wss':'ws';return `${scheme}://${location.host}/ws`;}
  let client=null,connected=false,timer=null;
  const logEl=document.getElementById('log');
  const startBtn=document.getElementById('start');
  const stopBtn=document.getElementById('stop');
  const rpsInput=document.getElementById('rps');
  const payloadInput=document.getElementById('payload');
  function log(s){if(!logEl)return;logEl.textContent+=s+'\n';logEl.scrollTop=logEl.scrollHeight;}
  function randomAscii(n){const chars='abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';let out='';while(out.length<n)out+=chars[Math.random()*chars.length|0];return out;}
  function connect(){
    const cfg=loadConn()||{url:defaultWs(),login:'guest',pass:'guest',vhost:'/'};
    // eslint-disable-next-line no-undef
    client=new StompJs.Client({brokerURL:cfg.url,connectHeaders:{login:cfg.login,passcode:cfg.pass,host:cfg.vhost||'/'},reconnectDelay:5000});
    client.onConnect=()=>{connected=true;log('connected');startBtn.disabled=false;};
    client.onStompError=(f)=>log('error '+(f&&f.headers&&f.headers.message));
    client.onWebSocketClose=()=>{connected=false;log('disconnected');startBtn.disabled=true;stop();};
    client.activate();
  }
  function sendOne(){
    if(!client||!connected)return;
    const id=(crypto.randomUUID?crypto.randomUUID():Math.random().toString(16).slice(2));
    const size=Number(payloadInput.value)||0;
    const bodyObj={id,path:'/api/test',method:'POST',body:'hello-world',createdAt:new Date().toISOString()};
    if(size>0) bodyObj.payload=randomAscii(size);
    const body=JSON.stringify(bodyObj);
    const headers={'content-type':'application/json','content-encoding':'utf-8','message-id':id,'x-ph-service':'generator'};
    try{client.publish({destination:'/exchange/ph.hive/ph.gen',body,headers});}catch(e){log('publish error '+e.message);}
  }
  function start(){
    if(timer||!connected)return;
    startBtn.disabled=true;stopBtn.disabled=false;
    const rps=Math.max(1,Number(rpsInput.value)||1);
    const interval=1000/Math.max(1,rps);
    sendOne();
    timer=setInterval(sendOne,interval);
  }
  function stop(){if(timer)clearInterval(timer);timer=null;startBtn.disabled=false;stopBtn.disabled=true;}
  startBtn.addEventListener('click',start);
  stopBtn.addEventListener('click',stop);
  window.addEventListener('DOMContentLoaded',connect);
})();

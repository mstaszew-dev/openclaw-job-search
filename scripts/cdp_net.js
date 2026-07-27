const WebSocket = require('/opt/homebrew/lib/node_modules/openclaw/node_modules/ws');
const wsUrl = process.argv[2];
const ws = new WebSocket(wsUrl);
let id=0; const pending={};
function send(m,p){const mid=++id;return new Promise((res,rej)=>{pending[mid]={res,rej};ws.send(JSON.stringify({id:mid,method:m,params:p||{}}));});}
const noise=/recaptcha|googleapis|gstatic|fonts|cookiebot|cdn|static|\.js|\.css|\.png|\.woff|\.svg/i;
const reqs={};
ws.on('message',d=>{const m=JSON.parse(d);
  if(m.id&&pending[m.id]){const{res,rej}=pending[m.id];delete pending[m.id];if(m.error)rej(new Error(m.error.message));else res(m.result);}
  else if(m.method==='Network.requestWillBeSent'){ const u=m.params.request.url; if(!noise.test(u)){ reqs[m.params.requestId]={method:m.params.request.method,url:u}; console.log('REQ',m.params.request.method,u.slice(0,110)); } }
  else if(m.method==='Network.responseReceived'){ const u=m.params.response.url; if(reqs[m.params.requestId]){ reqs[m.params.requestId].status=m.params.response.status; console.log('RESP',m.params.response.status,u.slice(0,110)); } }
  else if(m.method==='Network.loadingFailed'){ if(reqs[m.params.requestId]) console.log('FAIL', m.params.requestId, m.params.errorText); }
});
ws.on('error',e=>{console.error('WSERR',e.message);process.exit(1);});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
ws.on('open',async()=>{
  try{
    await send('Runtime.enable',{});
    await send('Network.enable',{});
    // solve captcha
    await send('Runtime.evaluate',{expression:`document.querySelector('iframe[src*="recaptcha/api2/anchor"]').scrollIntoView({block:'center'})`,returnByValue:true});
    await sleep(400);
    const rr=await send('Runtime.evaluate',{expression:`(()=>{const f=document.querySelector('iframe[src*="recaptcha/api2/anchor"]');const b=f.getBoundingClientRect();return {left:b.left,top:b.top};})()`,returnByValue:true});
    const cx=rr.result.value.left+26, cy=rr.result.value.top+40;
    await send('Input.dispatchMouseEvent',{type:'mousePressed',x:cx,y:cy,button:'left',clickCount:1});
    await send('Input.dispatchMouseEvent',{type:'mouseReleased',x:cx,y:cy,button:'left',clickCount:1});
    await sleep(2200);
    const g=await send('Runtime.evaluate',{expression:`document.querySelector('#g-recaptcha-response').value.length`,returnByValue:true});
    console.log('gresp', g.result.value);
    // click apply
    await send('Runtime.evaluate',{expression:`(()=>{const b=Array.from(document.querySelectorAll('form#frm button')).find(x=>(x.textContent||'').includes('Apply')); if(b)b.scrollIntoView({block:'center'});})()`,returnByValue:true});
    await sleep(400);
    const ar=await send('Runtime.evaluate',{expression:`(()=>{const b=Array.from(document.querySelectorAll('form#frm button')).find(x=>(x.textContent||'').includes('Apply'));const r=b.getBoundingClientRect();return {left:r.left,top:r.top,w:r.width,h:r.height};})()`,returnByValue:true});
    const ra=ar.result.value;
    console.log('clicking apply', ra.left+ra.w/2, ra.top+ra.h/2);
    await send('Input.dispatchMouseEvent',{type:'mousePressed',x:ra.left+ra.w/2,y:ra.top+ra.h/2,button:'left',clickCount:1});
    await send('Input.dispatchMouseEvent',{type:'mouseReleased',x:ra.left+ra.w/2,y:ra.top+ra.h/2,button:'left',clickCount:1});
    await sleep(8000);
    const st=await send('Runtime.evaluate',{expression:`(()=>{const t=document.body.innerText.toLowerCase();const succ=['dziękujemy','thank','wysłan','wyslano','została','otrzymaliśmy','pomyślnie','submitted','success'];return {gresp:document.querySelector('#g-recaptcha-response').value.length, succ:succ.filter(k=>t.includes(k)), hasForm:!!document.querySelector('form#frm')};})()`,returnByValue:true});
    console.log('FINAL', JSON.stringify(st.result.value));
    ws.close();
  }catch(e){console.error('ERR',e.message);ws.close();process.exit(1);}
});
setTimeout(()=>{console.error('TIMEOUT');process.exit(1);},35000);

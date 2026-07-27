const WebSocket = require('/opt/homebrew/lib/node_modules/openclaw/node_modules/ws');
const wsUrl = process.argv[2];
const ws = new WebSocket(wsUrl);
let id=0; const pending={};
function send(m,p){const mid=++id;return new Promise((res,rej)=>{pending[mid]={res,rej};ws.send(JSON.stringify({id:mid,method:m,params:p||{}}));});}
ws.on('message',d=>{const m=JSON.parse(d);
  if(m.id&&pending[m.id]){const{res,rej}=pending[m.id];delete pending[m.id];if(m.error)rej(new Error(m.error.message));else res(m.result);}
  else if(m.method==='Network.requestWillBeSent'){ const u=m.params.request.url; const meth=m.params.request.method; if(meth!=='GET'||/apply|submit|job|recruitify|decerto|api|form|cv|applic|send|mail/i.test(u)) console.log('REQ',meth,u.slice(0,140)); }
  else if(m.method==='Network.responseReceived'){ const u=m.params.response.url; const meth=m.params.response.status; if(/apply|submit|job|recruitify|decerto|api|form|cv|applic|send|mail/i.test(u)) console.log('RESP',m.params.response.status,u.slice(0,140)); }
  else if(m.method==='Network.loadingFailed'){ const u=m.params.requestId; }
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
    await sleep(2500);
    const g=await send('Runtime.evaluate',{expression:`grecaptcha.getResponse().length`,returnByValue:true});
    console.log('grec len', g.result.value);
    // click ALL apply buttons
    const clk=await send('Runtime.evaluate',{expression:`(()=>{const bs=Array.from(document.querySelectorAll('button')).filter(b=>(b.textContent||'').includes('Apply')); bs.forEach(b=>b.click()); return bs.length;})()`,returnByValue:true});
    console.log('clicked apply buttons:', clk.result.value);
    await sleep(2000);
    const sr=await send('Runtime.evaluate',{expression:`(()=>{const f=document.querySelector('form#frm'); f.requestSubmit(); return 'ok';})()`,returnByValue:true});
    console.log('requestSubmit:', sr.result.value);
    await sleep(6000);
    const st=await send('Runtime.evaluate',{expression:`(()=>{const t=document.body.innerText.toLowerCase();const succ=['dziękujemy','thank','wysłan','wyslano','została','został','otrzymaliśmy','pomyślnie','submitted','success','aplikacja'];return {succ:succ.filter(k=>t.includes(k)), hasForm:!!document.querySelector('form#frm')};})()`,returnByValue:true});
    console.log('FINAL', JSON.stringify(st.result.value));
    ws.close();
  }catch(e){console.error('ERR',e.message);ws.close();process.exit(1);}
});
setTimeout(()=>{console.error('TIMEOUT');process.exit(1);},40000);

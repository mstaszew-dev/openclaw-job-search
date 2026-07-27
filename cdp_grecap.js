const WebSocket = require('/opt/homebrew/lib/node_modules/openclaw/node_modules/ws');
const wsUrl = process.argv[2];
const ws = new WebSocket(wsUrl);
let id=0; const pending={};
function send(m,p){const mid=++id;return new Promise((res,rej)=>{pending[mid]={res,rej};ws.send(JSON.stringify({id:mid,method:m,params:p||{}}));});}
const noise=/recaptcha|googleapis|gstatic|fonts|cookiebot|cdn|static|\.js|\.css|\.png|\.woff|\.svg/i;
ws.on('message',d=>{const m=JSON.parse(d);
  if(m.id&&pending[m.id]){const{res,rej}=pending[m.id];delete pending[m.id];if(m.error)rej(new Error(m.error.message));else res(m.result);}
  else if(m.method==='Network.requestWillBeSent'){ const u=m.params.request.url; if(!noise.test(u)) console.log('REQ',m.params.request.method,u.slice(0,130)); }
  else if(m.method==='Network.responseReceived'){ const u=m.params.response.url; if(!noise.test(u)) console.log('RESP',m.params.response.status,u.slice(0,130)); }
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
    const gr=await send('Runtime.evaluate',{expression:`(()=>{try{return {resp: grecaptcha.getResponse(), len:(grecaptcha.getResponse()||'').length, hasGR: typeof grecaptcha};}catch(e){return {err:e.message};}})()`,returnByValue:true});
    console.log('grecaptcha.getResponse:', JSON.stringify(gr.result.value));
    // submit
    const sr=await send('Runtime.evaluate',{expression:`(()=>{const f=document.querySelector('form#frm'); f.requestSubmit(); return 'ok';})()`,returnByValue:true});
    console.log('submit:', sr.result.value);
    await sleep(5000);
    const st=await send('Runtime.evaluate',{expression:`(()=>{const t=document.body.innerText.toLowerCase();const succ=['dziękujemy','thank','wysłan','wyslano','została','został','otrzymaliśmy','pomyślnie','submitted','success','aplikacja'];const err=['błąd','blad','error','wypełnij','sprawdź','wymagane','niepopraw'];return {grespText:document.querySelector('#g-recaptcha-response').value.length, grec: (typeof grecaptcha!=='undefined')?(grecaptcha.getResponse()||'').length:-1, succ:succ.filter(k=>t.includes(k)), err:err.filter(k=>t.includes(k)), hasForm:!!document.querySelector('form#frm')};})()`,returnByValue:true});
    console.log('FINAL', JSON.stringify(st.result.value));
    ws.close();
  }catch(e){console.error('ERR',e.message);ws.close();process.exit(1);}
});
setTimeout(()=>{console.error('TIMEOUT');process.exit(1);},35000);

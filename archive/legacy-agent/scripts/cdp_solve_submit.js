const WebSocket = require('/opt/homebrew/lib/node_modules/openclaw/node_modules/ws');
const wsUrl = process.argv[2];
const ws = new WebSocket(wsUrl);
let id=0; const pending={};
function send(m,p){const mid=++id;return new Promise((res,rej)=>{pending[mid]={res,rej};ws.send(JSON.stringify({id:mid,method:m,params:p||{}}));});}
ws.on('message',d=>{const m=JSON.parse(d);if(m.id&&pending[m.id]){const{res,rej}=pending[m.id];delete pending[m.id];if(m.error)rej(new Error(m.error.message));else res(m.result);}});
ws.on('error',e=>{console.error('WSERR',e.message);process.exit(1);});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
ws.on('open',async()=>{
  try{
    await send('Runtime.enable',{});
    await send('Input.enable',{}).catch(()=>{});
    // 1) solve recaptcha
    await send('Runtime.evaluate',{expression:`document.querySelector('iframe[src*="recaptcha/api2/anchor"]').scrollIntoView({block:'center'})`,returnByValue:true});
    await sleep(400);
    const rr = await send('Runtime.evaluate',{expression:`(()=>{const f=document.querySelector('iframe[src*="recaptcha/api2/anchor"]');const b=f.getBoundingClientRect();return {left:b.left,top:b.top,w:b.width,h:b.height};})()`,returnByValue:true});
    const rc = rr.result.value;
    const cx=rc.left+26, cy=rc.top+40;
    await send('Input.dispatchMouseEvent',{type:'mousePressed',x:cx,y:cy,button:'left',clickCount:1});
    await send('Input.dispatchMouseEvent',{type:'mouseReleased',x:cx,y:cy,button:'left',clickCount:1});
    await sleep(2200);
    const g1 = await send('Runtime.evaluate',{expression:`document.querySelector('#g-recaptcha-response').value.length`,returnByValue:true});
    console.log('gresp after solve:', g1.result.value);
    if(!g1.result.value || g1.result.value < 10){ console.log('CAPTCHA NOT SOLVED'); ws.close(); process.exit(2); }
    // 2) immediately click Apply
    await send('Runtime.evaluate',{expression:`(()=>{const b=Array.from(document.querySelectorAll('form#frm button')).find(x=>(x.textContent||'').includes('Apply'))||Array.from(document.querySelectorAll('button')).find(x=>(x.textContent||'').includes('Apply')); if(b) b.scrollIntoView({block:'center'}); return !!b;})()`,returnByValue:true});
    await sleep(400);
    const ar = await send('Runtime.evaluate',{expression:`(()=>{const b=Array.from(document.querySelectorAll('form#frm button')).find(x=>(x.textContent||'').includes('Apply'))||Array.from(document.querySelectorAll('button')).find(x=>(x.textContent||'').includes('Apply'));const r=b.getBoundingClientRect();return {left:r.left,top:r.top,w:r.width,h:r.height};})()`,returnByValue:true});
    const ra = ar.result.value;
    const ax=ra.left+ra.w/2, ay=ra.top+ra.h/2;
    console.log('clicking Apply at', ax, ay);
    await send('Input.dispatchMouseEvent',{type:'mousePressed',x:ax,y:ay,button:'left',clickCount:1});
    await send('Input.dispatchMouseEvent',{type:'mouseReleased',x:ax,y:ay,button:'left',clickCount:1});
    await sleep(3500);
    const res = await send('Runtime.evaluate',{expression:`(()=>{const t=document.body.innerText||'';const low=t.toLowerCase();const successKw=['thank','wysłan','wyslano','aplikacja zosta','została przes','został przes','przesłano','przeslano','otrzymaliśmy','pomyślnie','succes','submitted'];const errKw=['błąd','blad','error','wypełnij','niepopraw','nie wypełni','required','nie moż','sprawdź','wymagane'];return {url:location.href, successHits:successKw.filter(k=>low.includes(k)), errHits:errKw.filter(k=>low.includes(k)), gresp:document.querySelector('#g-recaptcha-response').value.length, text:t.slice(0,400)};})()`,returnByValue:true});
    console.log(JSON.stringify(res.result.value,null,2));
    ws.close();
  }catch(e){console.error('ERR',e.message);ws.close();process.exit(1);}
});
setTimeout(()=>{console.error('TIMEOUT');process.exit(1);},30000);

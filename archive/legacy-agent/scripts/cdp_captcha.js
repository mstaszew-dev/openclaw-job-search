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
    // scroll recaptcha into view
    await send('Runtime.evaluate',{expression:`document.querySelector('iframe[src*="recaptcha/api2/anchor"]').scrollIntoView({block:'center'})`,returnByValue:true});
    await sleep(500);
    const rectR = await send('Runtime.evaluate',{expression:`(()=>{const f=document.querySelector('iframe[src*="recaptcha/api2/anchor"]');const b=f.getBoundingClientRect();return {left:b.left,top:b.top,w:b.width,h:b.height};})()`,returnByValue:true});
    const r = rectR.result.value;
    const cx = r.left + 26, cy = r.top + 40;
    console.log('clicking recaptcha at', cx, cy, 'iframeRect', JSON.stringify(r));
    await send('Input.dispatchMouseEvent',{type:'mousePressed',x:cx,y:cy,button:'left',clickCount:1});
    await send('Input.dispatchMouseEvent',{type:'mouseReleased',x:cx,y:cy,button:'left',clickCount:1});
    await sleep(2500);
    const g = await send('Runtime.evaluate',{expression:`(()=>{const el=document.querySelector('#g-recaptcha-response');return el?el.value:'(no el)';})()`,returnByValue:true});
    console.log('g-recaptcha-response length:', (g.result.value||'').length);
    console.log('g-recaptcha-response (first 60):', (g.result.value||'').slice(0,60));
    ws.close();
  }catch(e){console.error('ERR',e.message);ws.close();process.exit(1);}
});
setTimeout(()=>{console.error('TIMEOUT');process.exit(1);},20000);

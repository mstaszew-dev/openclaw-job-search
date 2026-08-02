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
    // scroll apply button into view
    await send('Runtime.evaluate',{expression:`(()=>{const b=Array.from(document.querySelectorAll('form#frm button')).find(x=>(x.textContent||'').includes('Apply'))||Array.from(document.querySelectorAll('button')).find(x=>(x.textContent||'').includes('Apply')); if(b) b.scrollIntoView({block:'center'}); return !!b;})()`,returnByValue:true});
    await sleep(500);
    const rectR = await send('Runtime.evaluate',{expression:`(()=>{const b=Array.from(document.querySelectorAll('form#frm button')).find(x=>(x.textContent||'').includes('Apply'))||Array.from(document.querySelectorAll('button')).find(x=>(x.textContent||'').includes('Apply'));const r=b.getBoundingClientRect();return {left:r.left,top:r.top,w:r.width,h:r.height};})()`,returnByValue:true});
    const r = rectR.result.value;
    const cx = r.left + r.w/2, cy = r.top + r.h/2;
    console.log('clicking Apply at', cx, cy, 'rect', JSON.stringify(r));
    await send('Input.dispatchMouseEvent',{type:'mousePressed',x:cx,y:cy,button:'left',clickCount:1});
    await send('Input.dispatchMouseEvent',{type:'mouseReleased',x:cx,y:cy,button:'left',clickCount:1});
    await sleep(3500);
    const res = await send('Runtime.evaluate',{expression:`(()=>{const t=document.body.innerText||'';const low=t.toLowerCase();const kw=['thank','wysłan','wyslano','aplikacja zosta','submitted','została przes','success','otrzymaliśmy','przyję'];const found=kw.filter(k=>low.includes(k));return {url:location.href,title:document.title,found,text:t.slice(0,600)};})()`,returnByValue:true});
    console.log(JSON.stringify(res.result.value,null,2));
    ws.close();
  }catch(e){console.error('ERR',e.message);ws.close();process.exit(1);}
});
setTimeout(()=>{console.error('TIMEOUT');process.exit(1);},25000);

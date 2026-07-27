const WebSocket = require('/opt/homebrew/lib/node_modules/openclaw/node_modules/ws');
const wsUrl = process.argv[2];
const url = process.argv[3];
const ws = new WebSocket(wsUrl);
let id=0; const pending={};
function send(m,p){const mid=++id;return new Promise((res,rej)=>{pending[mid]={res,rej};ws.send(JSON.stringify({id:mid,method:m,params:p||{}}));});}
ws.on('message',d=>{const m=JSON.parse(d);if(m.id&&pending[m.id]){const{res,rej}=pending[m.id];delete pending[m.id];if(m.error)rej(new Error(m.error.message));else res(m.result);}});
ws.on('error',e=>{console.error('WSERR',e.message);process.exit(1);});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
ws.on('open',async()=>{
  try{
    await send('Page.enable',{});
    await send('Runtime.enable',{});
    await send('Page.navigate',{url});
    await sleep(5000);
    const r=await send('Runtime.evaluate',{expression:`(()=>{
      const t=(document.body.innerText||'').replace(/\\s+/g,' ').trim();
      const noRes=/no results|no conversations|brak wynik|nic nie znalez/i.test(t);
      const snips=[...document.querySelectorAll('[role=main] .zA, .UI, .y6, .y2, .xT, .yP, span[email], .bog, .a4W, tr.zA')].slice(0,8).map(e=>(e.innerText||'').replace(/\\s+/g,' ').trim().slice(0,80));
      return {title:document.title, noResults:noRes, len:t.length, sample:t.slice(0,300), snips};
    })()`,returnByValue:true});
    console.log(JSON.stringify(r.result.value,null,2));
    ws.close();
  }catch(e){console.error('ERR',e.message);ws.close();process.exit(1);}
});
setTimeout(()=>{console.error('TIMEOUT');process.exit(1);},25000);

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
    await sleep(4500);
    const r=await send('Runtime.evaluate',{expression:`(()=>{
      const links=[...document.querySelectorAll('a[href*="/job/"]')];
      const seen=new Set(); const out=[];
      for(const a of links){
        const href=a.href; if(!href||seen.has(href)) continue; seen.add(href);
        const card=a.closest('[class*="post"], [class*="offer"], [class*="job"], li, article')||a.parentElement;
        const txt=(card?card.innerText:(''+a.textContent)).replace(/\\s+/g,' ').trim();
        out.push({href, txt:txt.slice(0,150)});
        if(out.length>=40) break;
      }
      return out;
    })()`,returnByValue:true});
    console.log(JSON.stringify(r.result.value,null,2));
    ws.close();
  }catch(e){console.error('ERR',e.message);ws.close();process.exit(1);}
});
setTimeout(()=>{console.error('TIMEOUT');process.exit(1);},20000);

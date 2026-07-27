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
    await sleep(6000);
    const r=await send('Runtime.evaluate',{expression:`(()=>{
      const links=[...document.querySelectorAll('a')];
      const seen=new Set(); const out=[];
      for(const a of links){
        const href=a.href||''; const txt=(''+(a.textContent||'')).replace(/\\s+/g,' ').trim();
        if(!href.includes('/job-offers/')) continue;
        if(href.includes('/remote/')||href.includes('/all-locations')||href.endsWith('/job-offers/')) continue;
        const key=href; if(seen.has(key)) continue; seen.add(key);
        if(/java|kotlin|spring|backend|php|laravel|nest|developer|engineer/i.test(txt)){
          out.push({href, txt:txt.slice(0,110)});
        }
        if(out.length>=45) break;
      }
      return {title:document.title, count:out.length, out};
    })()`,returnByValue:true});
    console.log(JSON.stringify(r.result.value,null,2));
    ws.close();
  }catch(e){console.error('ERR',e.message);ws.close();process.exit(1);}
});
setTimeout(()=>{console.error('TIMEOUT');process.exit(1);},30000);

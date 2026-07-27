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
    await sleep(4000);
    const r=await send('Runtime.evaluate',{expression:`(()=>{
      const out={};
      const btns=[...document.querySelectorAll('button, a')].filter(e=>(e.textContent||'').match(/apply|zaaplikuj|aplikuj|apply now/i));
      out.applyButtons=btns.slice(0,5).map(b=>({tag:b.tagName, text:(b.textContent||'').trim().slice(0,40), href:b.href||null, id:b.id||null, cls:b.className&&(''+b.className).slice(0,40)}));
      out.hasInlineForm=!!document.querySelector('form#applyForm, form[class*="apply"], #applyForm, .apply-form');
      out.iframeHosts=[...document.querySelectorAll('iframe')].map(f=>f.src).filter(Boolean).slice(0,8);
      out.title=document.title;
      out.url=location.href;
      const ext=document.querySelector('a[href*="recruitify"], a[href*="zoho"], a[href*="greenhouse"], a[href*="lever.co"], a[href*="ashby"], a[href*="smartrecruiters"], a[href*="talent"], a[href*="apply"], a[href*="career"]');
      out.extLink=ext?{href:ext.href, text:(ext.textContent||'').trim().slice(0,40)}:null;
      return out;
    })()`,returnByValue:true});
    console.log(JSON.stringify(r.result.value,null,2));
    ws.close();
  }catch(e){console.error('ERR',e.message);ws.close();process.exit(1);}
});
setTimeout(()=>{console.error('TIMEOUT');process.exit(1);},20000);

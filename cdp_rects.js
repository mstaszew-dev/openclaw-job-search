const WebSocket = require('/opt/homebrew/lib/node_modules/openclaw/node_modules/ws');
const wsUrl = process.argv[2];
const ws = new WebSocket(wsUrl);
let id=0; const pending={};
function send(m,p){const mid=++id;return new Promise((res,rej)=>{pending[mid]={res,rej};ws.send(JSON.stringify({id:mid,method:m,params:p||{}}));});}
ws.on('message',d=>{const m=JSON.parse(d);if(m.id&&pending[m.id]){const{res,rej}=pending[m.id];delete pending[m.id];if(m.error)rej(new Error(m.error.message));else res(m.result);}});
ws.on('error',e=>{console.error('WSERR',e.message);process.exit(1);});
ws.on('open',async()=>{
  try{
    await send('Runtime.enable',{});
    const r = await send('Runtime.evaluate',{expression:`(()=>{
      function rect(el){ if(!el) return null; const b=el.getBoundingClientRect(); return {x:Math.round(b.x),y:Math.round(b.y),w:Math.round(b.width),h:Math.round(b.height),top:b.top,left:b.left}; }
      const iframes = Array.from(document.querySelectorAll('iframe'));
      const rc = iframes.find(f=> (f.src||'').includes('recaptcha/api2/anchor'));
      const applyBtn = Array.from(document.querySelectorAll('form#frm button')).find(b=>(b.textContent||'').includes('Apply')) || Array.from(document.querySelectorAll('button')).find(b=(b.textContent||'').includes('Apply'));
      return {
        recaptchaIframe: rc ? rect(rc) : null,
        recaptchaIframeSrc: rc ? rc.src : null,
        applyBtn: applyBtn ? rect(applyBtn) : null,
        gresp: (document.querySelector('#g-recaptcha-response')||{}).value || ''
      };
    })()`,returnByValue:true});
    console.log(JSON.stringify(r.result&&r.result.value,null,2));
    ws.close();
  }catch(e){console.error('ERR',e.message);ws.close();process.exit(1);}
});
setTimeout(()=>{console.error('TIMEOUT');process.exit(1);},15000);

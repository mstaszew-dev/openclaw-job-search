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
    const r=await send('Runtime.evaluate',{expression:`(()=>{
      const f=document.querySelector('form#frm');
      const c=Array.from(f.querySelectorAll('input,textarea,select'));
      const labels=['name','phone','email','cv','linkedin','avail','salary','office','gdpr1','gdpr2','gresp'];
      return {
        formClass: f.className,
        formValid: f.checkValidity(),
        controls: c.map((el,i)=>({i:i<labels.length?labels[i]:i, cls:(el.className||'').toString().slice(0,60), valid: el.validity? el.validity.valid: null, value: (el.type==='checkbox')?el.checked:(el.value||'').slice(0,20)}))
      };
    })()`,returnByValue:true});
    console.log(JSON.stringify(r.result.value,null,2));
    ws.close();
  }catch(e){console.error('ERR',e.message);ws.close();process.exit(1);}
});
setTimeout(()=>{console.error('TIMEOUT');process.exit(1);},15000);

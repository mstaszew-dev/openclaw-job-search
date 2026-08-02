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
    const r = await send('Runtime.evaluate',{expression:`(()=>{const f=document.querySelector('form#frm');if(!f)return{noform:true};const c=Array.from(f.querySelectorAll('input,textarea,select'));return{len:c.length,items:c.map((e,i)=>({i,tag:e.tagName,type:e.type,id:e.id,name:e.name}))};})()`,returnByValue:true});
    console.log(JSON.stringify(r.result&&r.result.result?r.result.result.value:r.result,null,2));
    ws.close();
  }catch(e){console.error('ERR',e.message);ws.close();process.exit(1);}
});
setTimeout(()=>{console.error('TIMEOUT');process.exit(1);},15000);

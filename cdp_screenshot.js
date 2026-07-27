const WebSocket = require('/opt/homebrew/lib/node_modules/openclaw/node_modules/ws');
const fs = require('fs');
const wsUrl = process.argv[2];
const out = process.argv[3] || '/Users/mst/ZCodeProject/openclaw-job-search/decerto_shot.png';
const ws = new WebSocket(wsUrl);
let id=0; const pending={};
function send(m,p){const mid=++id;return new Promise((res,rej)=>{pending[mid]={res,rej};ws.send(JSON.stringify({id:mid,method:m,params:p||{}}));});}
ws.on('message',d=>{const m=JSON.parse(d);if(m.id&&pending[m.id]){const{res,rej}=pending[m.id];delete pending[m.id];if(m.error)rej(new Error(m.error.message));else res(m.result);}});
ws.on('error',e=>{console.error('WSERR',e.message);process.exit(1);});
ws.on('open',async()=>{
  try{
    await send('Page.enable',{});
    const r = await send('Page.captureScreenshot',{format:'png', captureBeyondViewport:true});
    fs.writeFileSync(out, Buffer.from(r.data, 'base64'));
    console.log('saved', out, r.data.length, 'bytes');
    ws.close();
  }catch(e){console.error('ERR',e.message);ws.close();process.exit(1);}
});
setTimeout(()=>{console.error('TIMEOUT');process.exit(1);},20000);

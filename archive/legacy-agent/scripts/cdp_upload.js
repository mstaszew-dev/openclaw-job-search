const WebSocket = require('/opt/homebrew/lib/node_modules/openclaw/node_modules/ws');
const wsUrl = process.argv[2];
const cvPath = '/Users/mst/Downloads/job-search/cv/michael-staszewski-cv.pdf';
const ws = new WebSocket(wsUrl);
let id=0; const pending={};
function send(m,p){const mid=++id;return new Promise((res,rej)=>{pending[mid]={res,rej};ws.send(JSON.stringify({id:mid,method:m,params:p||{}}));});}
ws.on('message',d=>{const m=JSON.parse(d);if(m.id&&pending[m.id]){const{res,rej}=pending[m.id];delete pending[m.id];if(m.error)rej(new Error(m.error.message));else res(m.result);}});
ws.on('error',e=>{console.error('WSERR',e.message);process.exit(1);});
ws.on('open',async()=>{
  try{
    await send('Runtime.enable',{});
    await send('DOM.enable',{});
    // get objectId of the file input (no returnByValue -> RemoteObject with objectId)
    const ev = await send('Runtime.evaluate',{expression:`document.querySelector('#cvuploader')`, objectGroup:'cv', generatePreview:false});
    const objId = ev.result && ev.result.objectId;
    console.log('objectId:', objId, 'backendNodeId:', ev.result && ev.result.backendNodeId);
    if(!objId){ throw new Error('no objectId for #cvuploader'); }
    const up = await send('DOM.setFileInputFiles',{objectId: objId, files:[cvPath]});
    console.log('setFileInputFiles result:', JSON.stringify(up));
    // confirm
    const conf = await send('Runtime.evaluate',{expression:`(()=>{const el=document.querySelector('#cvuploader');return el&&el.files&&el.files[0]?el.files[0].name:null;})()`,returnByValue:true});
    console.log('cv after upload:', conf.result && conf.result.value);
    ws.close();
  }catch(e){console.error('ERR',e.message);ws.close();process.exit(1);}
});
setTimeout(()=>{console.error('TIMEOUT');process.exit(1);},20000);

const WebSocket = require('/opt/homebrew/lib/node_modules/openclaw/node_modules/ws');
const wsUrl = process.argv[2];
const ws = new WebSocket(wsUrl);
let id = 0; const pending = {};
function send(method, params){ const mid=++id; return new Promise((res,rej)=>{pending[mid]={res,rej}; ws.send(JSON.stringify({id:mid,method,params:params||{}}));}); }
ws.on('message', d=>{ const m=JSON.parse(d); if(m.id&&pending[m.id]){const{res,rej}=pending[m.id]; delete pending[m.id]; if(m.error)rej(new Error(m.error.message)); else res(m.result);} });
ws.on('error', e=>{console.error('WSERR',e.message);process.exit(1);});
ws.on('open', async()=>{
  try{
    await send('DOM.enable',{});
    const doc = await send('DOM.getDocument',{depth:1});
    console.log('DOC keys:', Object.keys(doc));
    console.log('DOC.result keys:', doc.result?Object.keys(doc.result):'none');
    if(doc.result&&doc.result.root) console.log('root.nodeId:', doc.result.root.nodeId, 'rootName:', doc.result.root.nodeName);
    // try querySelector for cvuploader
    const rootId = doc.result && doc.result.root ? doc.result.root.nodeId : (doc.result && doc.result.nodeId);
    const q = await send('DOM.querySelector',{nodeId: rootId, selector:'#cvuploader'});
    console.log('QUERY cvuploader nodeId:', q.result ? q.result.nodeId : q);
    ws.close();
  }catch(e){ console.error('ERR', e.message); ws.close(); process.exit(1); }
});
setTimeout(()=>{console.error('TIMEOUT');process.exit(1);},20000);

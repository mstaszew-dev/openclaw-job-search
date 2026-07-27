// CDP Runtime.evaluate helper. Usage: node cdp_eval.js <wsUrl> <exprFile>
// Reads expression from exprFile (or arg), prints JSON result.
const WebSocket = require('/opt/homebrew/lib/node_modules/openclaw/node_modules/ws');
const wsUrl = process.argv[2];
const expr = require('fs').readFileSync(process.argv[3], 'utf8');

const ws = new WebSocket(wsUrl);
let id = 0;
const pending = {};

function send(method, params) {
  const msgId = ++id;
  return new Promise((resolve, reject) => {
    pending[msgId] = { resolve, reject };
    ws.send(JSON.stringify({ id: msgId, method, params: params || {} }));
  });
}

ws.on('open', async () => {
  try {
    await send('Runtime.enable', {});
    const res = await send('Runtime.evaluate', {
      expression: expr,
      returnByValue: true,
      awaitPromise: true,
    });
    console.log(JSON.stringify(res.result, null, 2));
    ws.close();
  } catch (e) {
    console.error('ERR', e.message);
    ws.close();
    process.exit(1);
  }
});

ws.on('message', (data) => {
  const msg = JSON.parse(data);
  if (msg.id && pending[msg.id]) {
    const { resolve, reject } = pending[msg.id];
    delete pending[msg.id];
    if (msg.error) reject(new Error(msg.error.message));
    else resolve(msg.result);
  }
});

ws.on('error', (e) => { console.error('WSERR', e.message); process.exit(1); });
setTimeout(() => { console.error('TIMEOUT'); process.exit(1); }, 20000);

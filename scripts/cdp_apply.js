// Fills the Decerto/Recruitify Angular form via CDP, uploads CV, returns field snapshot.
const WebSocket = require('/opt/homebrew/lib/node_modules/openclaw/node_modules/ws');
const fs = require('fs');
const wsUrl = process.argv[2];
const cvPath = '/Users/mst/Downloads/job-search/cv/michael-staszewski-cv.pdf';

const ws = new WebSocket(wsUrl);
let id = 0; const pending = {};
function send(method, params) {
  const mid = ++id;
  return new Promise((res, rej) => { pending[mid] = { res, rej }; ws.send(JSON.stringify({ id: mid, method, params: params || {} })); });
}
ws.on('message', d => { const m = JSON.parse(d); if (m.id && pending[m.id]) { const { res, rej } = pending[m.id]; delete pending[m.id]; if (m.error) rej(new Error(m.error.message)); else res(m.result); } });
ws.on('error', e => { console.error('WSERR', e.message); process.exit(1); });

const FILL = `
(() => {
  const f = document.querySelector('form#frm');
  const c = Array.from(f.querySelectorAll('input, textarea, select'));
  function setVal(el, val){
    let proto = el.tagName==='TEXTAREA' ? HTMLTextAreaElement.prototype : (el.tagName==='SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype);
    const setter = Object.getOwnPropertyDescriptor(proto,'value').set;
    setter.call(el, val);
    el.dispatchEvent(new Event('input',{bubbles:true}));
    el.dispatchEvent(new Event('change',{bubbles:true}));
    el.dispatchEvent(new Event('blur',{bubbles:true}));
  }
  // 0 name,1 phone,2 email,3 file,4 linkedin,5 select,6 salary,7 office textarea,8 gdpr1,9 gdpr2
  setVal(c[0], 'Michael Staszewski');
  setVal(c[1], '+48790775407');
  setVal(c[2], 'mst.rocking@gmail.com');
  setVal(c[4], 'https://www.linkedin.com/in/micha%C5%82-staszewski-220315179');
  setVal(c[5], '-7'); // Available immediately
  setVal(c[6], '15000'); // salary expectation (PLN net+VAT monthly B2B)
  setVal(c[7], 'Preferuję pracę w pełni zdalną, ale jestem elastyczny i otwarty na okazjonalne wizyty w biurze (hybryda 1–2 razy w miesiącu) w razie potrzeby.');
  function check(el){ if(!el.checked){ el.click(); } }
  check(c[8]); // GDPR required
  check(c[9]); // marketing consent
  return {
    name: c[0].value, phone: c[1].value, email: c[2].value, linkedin: c[4].value,
    avail: c[5].value, salary: c[6].value, office: c[7].value.slice(0,30),
    gdpr1: c[8].checked, gdpr2: c[9].checked,
    cvFileName: (document.querySelector('#cvuploader') && document.querySelector('#cvuploader').files[0]) ? document.querySelector('#cvuploader').files[0].name : null
  };
})()
`;

ws.on('open', async () => {
  try {
    await send('Runtime.enable', {});
    await send('DOM.enable', {});
    const fillRes = await send('Runtime.evaluate', { expression: FILL, returnByValue: true, awaitPromise: true });
    const fillVal = fillRes.result && fillRes.result.value !== undefined ? fillRes.result.value : fillRes.result;
    // upload CV via DOM.setFileInputFiles
    const doc = await send('DOM.getDocument', { depth: -1 });
    const rootId = doc.root ? doc.root.nodeId : (doc.result && doc.result.root ? doc.result.root.nodeId : null);
    const q = await send('DOM.querySelector', { nodeId: rootId, selector: '#cvuploader' });
    let uploadMsg = 'no-file-input';
    if (q.result && q.result.nodeId) {
      const up = await send('DOM.setFileInputFiles', { nodeId: q.result.nodeId, files: [cvPath] });
      uploadMsg = up.error ? ('upload-error:' + up.error.message) : 'uploaded';
    }
    // re-read state after upload
    const after = await send('Runtime.evaluate', { expression: `(()=>{const f=document.querySelector('form#frm');const c=Array.from(f.querySelectorAll('input,textarea,select'));const cv=document.querySelector('#cvuploader');return{name:c[0].value,phone:c[1].value,email:c[2].value,linkedin:c[4].value,avail:c[5].value,salary:c[6].value,office:c[7].value.slice(0,25),gdpr1:c[8].checked,gdpr2:c[9].checked,cv:cv&&cv.files&&cv.files[0]?cv.files[0].name:null};})()`, returnByValue: true });
    const afterVal = after.result && after.result.value !== undefined ? after.result.value : after.result;
    const out = { fill: fillVal, uploadMsg, after: afterVal };
    console.log(JSON.stringify(out, null, 2));
    ws.close();
  } catch (e) { console.error('ERR', e.message); ws.close(); process.exit(1); }
});
setTimeout(() => { console.error('TIMEOUT'); process.exit(1); }, 25000);

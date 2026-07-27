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
      const f=document.querySelector('form#frm');
      const c=f?Array.from(f.querySelectorAll('input,textarea,select')):[];
      const t=(document.body.innerText||'');
      const low=t.toLowerCase();
      const succ=['dziękujemy','dziekujemy','thank you','thank-you','wysłan','wyslano','została wysłana','został wysłany','otrzymaliśmy','aplikacja została','pomyślnie','zapisaliśmy','skontaktujemy','przyjęto','przyjeto','submitted','success'];
      const succHits=succ.filter(k=>low.includes(k));
      // look for a success/confirm/modal element
      const cand=Array.from(document.querySelectorAll('div,section,article,p,h1,h2')).filter(el=>{const tx=(el.innerText||'').toLowerCase(); return succ.some(k=>tx.includes(k)) && tx.length<300 && tx.length>3;});
      const modalText=cand.map(e=>e.innerText.trim().slice(0,120));
      return {
        url:location.href,
        hasForm:!!f,
        applyBtnPresent:!!Array.from(document.querySelectorAll('button')).find(b=>(b.textContent||'').includes('Apply')),
        name:c[0]?c[0].value:'', email:c[2]?c[2].value:'',
        cv:(document.querySelector('#cvuploader')||{}).files? (document.querySelector('#cvuploader').files[0]?document.querySelector('#cvuploader').files[0].name:null):null,
        gresp:((document.querySelector('#g-recaptcha-response')||{}).value||'').length,
        succHits, modalText, bodyLen:t.length
      };
    })()`,returnByValue:true});
    console.log(JSON.stringify(r.result.value,null,2));
    ws.close();
  }catch(e){console.error('ERR',e.message);ws.close();process.exit(1);}
});
setTimeout(()=>{console.error('TIMEOUT');process.exit(1);},15000);

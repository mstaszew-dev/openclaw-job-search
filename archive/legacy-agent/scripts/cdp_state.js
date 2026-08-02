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
      const g=document.querySelector('#g-recaptcha-response');
      const cv=document.querySelector('#cvuploader');
      const t=(document.body.innerText||'');
      const low=t.toLowerCase();
      const successKw=['thank','wysłan','wyslano','aplikacja zosta','została przes','został przes','przesłano','przeslano','otrzymaliśmy','pomyślnie','succes','submitted','wysłany'];
      const errKw=['błąd','blad','error','wypełnij','niepopraw','nie wypełni','required','nie moż','sprawdź','wymagane'];
      function find(kw){return kw.filter(k=>low.includes(k));}
      return {
        hasForm: !!f,
        name: c[0]?c[0].value:'',
        email: c[2]?c[2].value:'',
        phone: c[1]?c[1].value:'',
        salary: c[6]?c[6].value:'',
        avail: c[5]?c[5].value:'',
        gdpr1: c[8]?c[8].checked:null,
        gdpr2: c[9]?c[9].checked:null,
        cv: cv&&cv.files&&cv.files[0]?cv.files[0].name:null,
        grespLen: g? (g.value||'').length : -1,
        applyBtnPresent: !!Array.from(document.querySelectorAll('button')).find(b=>(b.textContent||'').includes('Apply')),
        successHits: find(successKw),
        errHits: find(errKw),
        bodyLen: t.length
      };
    })()`,returnByValue:true});
    console.log(JSON.stringify(r.result.value,null,2));
    ws.close();
  }catch(e){console.error('ERR',e.message);ws.close();process.exit(1);}
});
setTimeout(()=>{console.error('TIMEOUT');process.exit(1);},15000);

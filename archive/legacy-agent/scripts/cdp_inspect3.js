(() => {
  const out = {};
  const f = document.querySelector('form#frm');
  const ctrls = Array.from(f.querySelectorAll('input, textarea, select'));
  out.order = ctrls.map((el,i)=>({i, tag:el.tagName, type:el.type, id:el.id, name:el.name}));
  // select options
  const sel = ctrls.find(e=>e.tagName==='SELECT');
  out.selectOptions = sel ? Array.from(sel.options).map(o=>({value:o.value, text:o.text.slice(0,40)})) : null;
  // the apply submit buttons (type=submit) and their form association
  out.submitButtons = Array.from(document.querySelectorAll('button[type=submit]')).map(b=>({text:b.textContent.trim().slice(0,30), form:b.form?b.form.id:null}));
  // is recaptcha checkbox present in iframe? list iframe ids
  out.iframeIds = Array.from(document.querySelectorAll('iframe')).map(fr=>fr.id||fr.name||fr.src.slice(0,50));
  return out;
})()

(() => {
  const out = {};
  // iframes
  out.iframes = Array.from(document.querySelectorAll('iframe')).map(f => ({ src: f.src||'', id:f.id||'' , cls:(f.className||'').toString() }));
  // map each form control to nearby label text
  const ctrls = Array.from(document.querySelectorAll('form input, form textarea, form select'));
  out.controls = ctrls.map((el, i) => {
    let label = '';
    // try label[for]
    if (el.id) { const l = document.querySelector('label[for="'+el.id+'"]'); if (l) label = l.textContent.trim(); }
    if (!label) {
      // closest label ancestor
      let p = el;
      while (p && p.tagName !== 'LABEL' && p !== document.body) p = p.parentElement;
      if (p && p.tagName === 'LABEL') label = p.textContent.trim();
    }
    if (!label) {
      // previous sibling text or parent's previous sibling
      const sib = el.previousElementSibling;
      if (sib) label = (sib.textContent||'').trim().slice(0,40);
    }
    if (!label) {
      const par = el.parentElement;
      if (par) { const ps = par.previousElementSibling; if (ps) label = (ps.textContent||'').trim().slice(0,40); }
    }
    return { i, tag: el.tagName, type: el.type, id: el.id, name: el.name, ph: el.placeholder||'', aria: el.getAttribute('aria-label')||'', label: label.slice(0,50) };
  });
  // all buttons with text + classes
  out.buttons = Array.from(document.querySelectorAll('button')).map(b => ({ text:(b.textContent||'').trim().slice(0,40), cls:(b.className||'').toString().slice(0,40), type:b.type }));
  // headings
  out.h = Array.from(document.querySelectorAll('h1,h2,h3,label,legend')).map(x=>(x.textContent||'').trim().slice(0,50));
  // form action / method
  const f = document.querySelector('form');
  out.formInfo = f ? { action: f.action, method: f.method, id: f.id, cls:(f.className||'').toString().slice(0,40) } : null;
  return out;
})()

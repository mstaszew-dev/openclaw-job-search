(() => {
  const out = {};
  out.title = document.title;
  out.url = location.href;
  out.iframeCount = document.querySelectorAll('iframe').length;
  out.iframes = Array.from(document.querySelectorAll('iframe')).map(f => ({ src: f.src || '', id: f.id || '', name: f.name || '' }));
  out.forms = document.forms.length;
  const fields = Array.from(document.querySelectorAll('input, textarea, select'));
  out.fieldCount = fields.length;
  out.fields = fields.slice(0, 60).map(f => ({ tag: f.tagName, type: f.type, name: f.name, id: f.id, ph: f.placeholder, cls: (f.className||'').toString().slice(0,40) }));
  out.fileInputs = Array.from(document.querySelectorAll('input[type=file]')).map(f => ({ id: f.id, name: f.name, cls:(f.className||'').toString().slice(0,40) }));
  out.buttons = Array.from(document.querySelectorAll('button')).map(b => (b.textContent||'').trim().slice(0,40));
  // headings / labels to understand form
  out.h2 = Array.from(document.querySelectorAll('h1,h2,h3')).map(h=>(h.textContent||'').trim().slice(0,50));
  return out;
})()

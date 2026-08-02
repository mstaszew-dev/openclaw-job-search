import pkg from '/Users/mst/node_modules/playwright/index.js';
const { chromium } = pkg;
import http from 'node:http';
import fs from 'node:fs';

const PORT = 18888;
const CDP = 'http://127.0.0.1:9222';

let browser = null;
let page = null;

async function connect() {
  browser = await chromium.connectOverCDP(CDP);
  // close stray tabs, keep exactly one
  const contexts = browser.contexts();
  let kept = null;
  for (const ctx of contexts) {
    for (const p of ctx.pages()) {
      if (!kept) { kept = p; }
      else { try { await p.close(); } catch {} }
    }
  }
  if (!kept) {
    const ctx = contexts[0] || await browser.newContext();
    kept = await ctx.newPage();
  }
  page = kept;
  // detach: connectOverCDP may add its own; ignore
  console.log('connected, single tab:', page.url());
}

function send(res, code, obj) {
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(obj));
}

async function doClick(spec) {
  let loc;
  if (spec.role) loc = page.getByRole(spec.role, { name: spec.name, exact: spec.exact ?? false });
  else if (spec.text) loc = page.getByText(spec.text, { exact: spec.exact ?? false });
  else if (spec.selector) loc = page.locator(spec.selector);
  else if (spec.label) loc = page.getByLabel(spec.label, { exact: spec.exact ?? false });
  else throw new Error('click needs role/name, text, label, or selector');
  await loc.first().click({ timeout: spec.timeout ?? 15000 });
}

async function doFill(spec) {
  let loc;
  if (spec.role) loc = page.getByRole(spec.role, { name: spec.name });
  else if (spec.label) loc = page.getByLabel(spec.label, { exact: spec.exact ?? false });
  else if (spec.placeholder) loc = page.getByPlaceholder(spec.placeholder);
  else if (spec.selector) loc = page.locator(spec.selector);
  else throw new Error('fill needs label/placeholder/selector/role');
  await loc.first().fill(spec.value, { timeout: spec.timeout ?? 15000 });
}

const server = http.createServer(async (req, res) => {
  try {
    let body = '';
    for await (const c of req) body += c;
    let json = {};
    if (body) { try { json = JSON.parse(body); } catch { json = {}; } }
    const url = req.url.split('?')[0];
    if (!browser) await connect();

    if (req.method === 'GET' && url === '/tabs') {
      const out = [];
      for (const ctx of browser.contexts()) for (const p of ctx.pages()) out.push(p.url());
      return send(res, 200, { tabs: out, active: page.url() });
    }
    if (req.method === 'POST' && url === '/reset') {
      // enforce single tab: close all, open one
      for (const ctx of browser.contexts()) for (const p of ctx.pages()) { try { await p.close(); } catch {} }
      const ctx = browser.contexts()[0] || await browser.newContext();
      page = await ctx.newPage();
      return send(res, 200, { ok: true, url: page.url() });
    }
    if (req.method === 'POST' && url === '/goto') {
      await page.goto(json.url, { waitUntil: json.waitUntil || 'domcontentloaded', timeout: json.timeout ?? 30000 });
      return send(res, 200, { ok: true, url: page.url(), title: await page.title() });
    }
    if (req.method === 'GET' && url === '/snap') {
      const snap = await page.accessibility.snapshot({ interestingOnly: json.interesting ?? true });
      return send(res, 200, { url: page.url(), snapshot: snap });
    }
    if (req.method === 'POST' && url === '/eval') {
      const fn = new Function('arg', 'return (' + json.code + ')(arg);');
      const result = await page.evaluate(fn, json.arg ?? {});
      return send(res, 200, { result });
    }
    if (req.method === 'POST' && url === '/shot') {
      const path = json.path || '/tmp/cdp_shot.png';
      await page.screenshot({ path, fullPage: json.fullPage ?? false });
      return send(res, 200, { ok: true, path });
    }
    if (req.method === 'POST' && url === '/click') {
      await doClick(json);
      return send(res, 200, { ok: true, url: page.url() });
    }
    if (req.method === 'POST' && url === '/fill') {
      await doFill(json);
      return send(res, 200, { ok: true });
    }
    if (req.method === 'POST' && url === '/type') {
      let loc;
      if (json.label) loc = page.getByLabel(json.label, { exact: json.exact ?? false });
      else if (json.selector) loc = page.locator(json.selector);
      else if (json.placeholder) loc = page.getByPlaceholder(json.placeholder);
      else throw new Error('type needs label/selector/placeholder');
      await loc.first().fill(json.value);
      if (json.press) await loc.first().press(json.press);
      return send(res, 200, { ok: true });
    }
    if (req.method === 'POST' && url === '/upload') {
      const sel = json.selector || 'input[type=file]';
      await page.locator(sel).first().setInputFiles(json.paths);
      return send(res, 200, { ok: true });
    }
    if (req.method === 'POST' && url === '/wait') {
      await page.waitForTimeout(json.ms ?? 2000);
      return send(res, 200, { ok: true, url: page.url() });
    }
    if (req.method === 'POST' && url === '/waitText') {
      await page.getByText(json.text, { exact: json.exact ?? false }).first().waitFor({ timeout: json.timeout ?? 15000 });
      return send(res, 200, { ok: true, url: page.url() });
    }
    if (req.method === 'POST' && url === '/closeTab') {
      // close a tab by url substring (keep discipline)
      const tgt = json.url;
      for (const ctx of browser.contexts()) {
        for (const p of ctx.pages()) {
          if (tgt && p.url().includes(tgt)) { await p.close(); }
        }
      }
      return send(res, 200, { ok: true });
    }
    return send(res, 404, { error: 'unknown ' + req.method + ' ' + url });
  } catch (e) {
    send(res, 500, { error: e.message, stack: String(e.stack || '').split('\n').slice(0,3) });
  }
});

server.listen(PORT, '127.0.0.1', async () => {
  try { await connect(); } catch (e) { console.error('connect failed', e.message); }
  console.log('CDP control server on', PORT);
});

'use strict';

const $ = (s) => document.querySelector(s);
const store = {
  get: (k) => { try { return localStorage.getItem('ferry.' + k); } catch { return null; } },
  set: (k, v) => { try { v == null ? localStorage.removeItem('ferry.' + k) : localStorage.setItem('ferry.' + k, v); } catch {} },
};

let token = store.get('token');
let docks = [];
let cargo = [];             // { file, el, bar }
let picked = new Set(JSON.parse(store.get('picked') || '[]'));
let dir = null;             // FileSystemDirectoryHandle where arrivals land (Chromium only)
const canPickDir = 'showDirectoryPicker' in window;

// h('li', {class: 'x', onclick}, 'text', child) — builds DOM without innerHTML, so names stay inert.
function h(tag, props = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (v === true) el.setAttribute(k, '');
    else if (v !== false && v != null) el.setAttribute(k, v);
  }
  el.append(...kids.flat().filter((k) => k != null));
  return el;
}

async function api(path, opts = {}) {
  const res = await fetch(path, { ...opts, headers: { Authorization: 'Bearer ' + token, ...opts.headers } });
  if (res.status === 401 && token) return undock();
  if (!res.ok) throw new Error((await res.text()).trim() || res.statusText);
  return (res.headers.get('content-type') || '').includes('json') ? res.json() : res;
}

const size = (n) => n < 1e3 ? n + ' B' : n < 1e6 ? (n / 1e3).toFixed(1) + ' KB' : n < 1e9 ? (n / 1e6).toFixed(1) + ' MB' : (n / 1e9).toFixed(2) + ' GB';
function ago(sec) {
  const s = Date.now() / 1000 - sec;
  if (s < 60) return 'just now';
  if (s < 3600) return Math.floor(s / 60) + ' min ago';
  if (s < 86400) return Math.floor(s / 3600) + 'h ago';
  return Math.floor(s / 86400) + 'd ago';
}
function status(d) {
  if (d.online) return h('span', { class: 'sub ok' }, 'In port');
  if (d.platform === 'android') return h('span', { class: 'sub' }, `Anchored · seen ${ago(d.last_seen)} · picks it up on next check`);
  return h('span', { class: 'sub' }, `Away · seen ${ago(d.last_seen)} · waits up to 24h`);
}
// Phone / laptop outlines in the same stroke style as the icons in index.html; SVG
// rather than ▯ ▭ because many system fonts have no glyph for those.
const ICONS = {
  phone: ['M7 3h10a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z', 'M11 18h2'],
  laptop: ['M5 5h14a1 1 0 0 1 1 1v9H4V6a1 1 0 0 1 1-1z', 'M2 18h20'],
};
function deviceIcon(platform) {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  for (const [k, v] of Object.entries({ width: 18, height: 18, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor',
    'stroke-width': 1.8, 'stroke-linecap': 'round', 'stroke-linejoin': 'round' })) svg.setAttribute(k, v);
  for (const d of ICONS[platform === 'android' ? 'phone' : 'laptop']) {
    const p = document.createElementNS(ns, 'path');
    p.setAttribute('d', d);
    svg.append(p);
  }
  return h('span', { class: 'muted device-icon', 'aria-hidden': 'true' }, svg);
}

// ---------- theme: Auto (system) → Light → Dark ----------

const THEMES = { system: 'Auto', light: 'Light', dark: 'Dark' };
function applyTheme(t) {
  if (t === 'system') delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = t;
  for (const b of document.querySelectorAll('.theme')) {
    b.textContent = THEMES[t];
    b.setAttribute('aria-label', `Theme: ${THEMES[t]}. Click to change`);
  }
}
function cycleTheme() {
  const order = Object.keys(THEMES);
  const next = order[(order.indexOf(store.get('theme') || 'system') + 1) % order.length];
  store.set('theme', next === 'system' ? null : next);
  applyTheme(next);
}
document.querySelectorAll('.theme').forEach((b) => { b.onclick = cycleTheme; });
applyTheme(store.get('theme') || 'system');

// ---------- onboarding ----------

function defaultName() {
  const ua = navigator.userAgent;
  const browser = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser';
  const os = /Mac/.test(ua) ? 'Mac' : /Windows/.test(ua) ? 'Windows' : /Android/.test(ua) ? 'Android' : /Linux/.test(ua) ? 'Linux' : '';
  return os ? `${browser} on ${os}` : browser;
}

function welcome() {
  $('#view-welcome').hidden = false;
  $('#w-name').value = defaultName();
  const join = location.hash.match(/^#join=(\d{6})$/);
  if (join) $('#w-code').value = join[1];
  const go = async (path, body) => {
    $('#w-err').textContent = '';
    try {
      const res = await fetch(path, { method: 'POST', body: JSON.stringify({ name: $('#w-name').value, platform: 'web', ...body }) });
      if (!res.ok) throw new Error((await res.text()).trim());
      ({ token } = await res.json());
      store.set('token', token);
      if ('Notification' in window) Notification.requestPermission();
      location.hash = '#ferry';
      location.reload();
    } catch (e) { $('#w-err').textContent = e.message; }
  };
  $('#w-create').onclick = () => go('/api/fleet', {});
  $('#w-join').onclick = () => go('/api/dock/claim', { code: $('#w-code').value });
}

function undock() {
  store.set('token', null);
  location.hash = '';
  location.reload();
  return new Promise(() => {});
}

// ---------- views ----------

function route() {
  const view = location.hash === '#docks' ? 'docks' : 'ferry';
  $('#view-ferry').hidden = view !== 'ferry';
  $('#view-docks').hidden = view !== 'docks';
  document.querySelectorAll('nav a').forEach((a) => a.classList.toggle('on', a.dataset.view === view));
}

async function loadDocks() {
  docks = await api('/api/docks');
  const me = docks.find((d) => d.me);
  $('#me-name').textContent = 'This browser: ' + me.name;
  renderDestinations();
  renderDocks(me);
}

function renderDestinations() {
  const others = docks.filter((d) => !d.me);
  picked = new Set([...picked].filter((id) => others.some((d) => d.id === id)));
  $('#dest-list').replaceChildren(...(others.length ? others.map((d) =>
    h('label', { class: 'dest' },
      h('input', { type: 'checkbox', checked: picked.has(d.id), onchange: (e) => {
        e.target.checked ? picked.add(d.id) : picked.delete(d.id);
        store.set('picked', JSON.stringify([...picked]));
        renderSendButton();
      } }),
      deviceIcon(d.platform),
      h('span', { class: 'grow' }, h('span', { class: 'name' }, d.name), status(d)))
  ) : [h('p', { class: 'dest muted' }, 'No other docks yet. Dock your phone first.')]));
  renderSendButton();
}

function renderDocks(me) {
  $('#docks-title').textContent = `Docks · ${docks.length}`;
  const mine = $('#t-dock-me').content.firstElementChild.cloneNode(true);
  const input = mine.querySelector('.me-input');
  input.value = me.name;
  input.onchange = () => api('/api/docks/me', { method: 'PATCH', body: JSON.stringify({ name: input.value }) }).then(loadDocks);
  mine.querySelector('.save-label').textContent = dir ? dir.name : 'Browser downloads';
  const ask = mine.querySelector('.save-ask');
  ask.checked = store.get('ask') === '1';
  ask.onchange = () => store.set('ask', ask.checked ? '1' : null);
  if (!canPickDir) {
    mine.querySelector('.save-change').hidden = true;
    mine.querySelector('.save-note').hidden = false;
    ask.closest('label').hidden = true;
  }
  mine.querySelector('.save-change').onclick = pickFolder;

  const others = docks.filter((d) => !d.me).map((d) =>
    h('li', {},
      deviceIcon(d.platform),
      h('span', { class: 'grow' }, h('span', { class: 'name' }, d.name), status(d),
        d.save_label ? h('span', { class: 'sub' }, 'Saves to ' + d.save_label) : null),
      h('button', { class: 'link danger', onclick: async () => {
        if (!confirm(`Undock ${d.name}? It will stop receiving files until docked again.`)) return;
        await api('/api/docks/' + d.id, { method: 'DELETE' });
        loadDocks();
      } }, 'Undock')));
  const leave = h('li', {}, h('button', { class: 'link danger', onclick: async () => {
    if (!confirm('Undock this browser? You will need a boarding code to dock it again.')) return;
    await api('/api/docks/' + me.id, { method: 'DELETE' });
    undock();
  } }, 'Undock this browser'));
  $('#docks').replaceChildren(mine, ...others, leave);
}

// ---------- save location (File System Access API, Chromium only) ----------

function idb(mode, fn) {
  return new Promise((resolve, reject) => {
    const open = indexedDB.open('ferry', 1);
    open.onupgradeneeded = () => open.result.createObjectStore('kv');
    open.onerror = () => reject(open.error);
    open.onsuccess = () => {
      const tx = open.result.transaction('kv', mode);
      const req = fn(tx.objectStore('kv'));
      tx.oncomplete = () => resolve(req.result);
      tx.onerror = () => reject(tx.error);
    };
  });
}

async function pickFolder() {
  try {
    dir = await window.showDirectoryPicker({ id: 'ferry', mode: 'readwrite' });
  } catch { return; } // cancelled
  await idb('readwrite', (s) => s.put(dir, 'dir'));
  await api('/api/docks/me', { method: 'PATCH', body: JSON.stringify({ save_label: dir.name }) });
  loadDocks();
}

async function dirReady(prompt) {
  if (!dir) return false;
  const opts = { mode: 'readwrite' };
  if ((await dir.queryPermission(opts)) === 'granted') return true;
  return prompt && (await dir.requestPermission(opts)) === 'granted';
}

async function freeName(folder, name) {
  const dot = name.lastIndexOf('.');
  const [base, ext] = dot > 0 ? [name.slice(0, dot), name.slice(dot)] : [name, ''];
  for (let i = 0; ; i++) {
    const candidate = i ? `${base} (${i})${ext}` : name;
    try { await folder.getFileHandle(candidate); } catch { return candidate; }
  }
}

// ---------- cargo + ferrying ----------

function addCargo(files) {
  for (const file of files) {
    const bar = h('div', { class: 'bar-fill' });
    const item = { file, bar };
    item.el = h('li', {},
      h('span', { class: 'grow' }, h('span', { class: 'name' }, file.name),
        h('span', { class: 'sub mono' }, size(file.size)), h('div', { class: 'bar-track' }, bar)),
      h('button', { class: 'icon-btn', 'aria-label': 'Remove ' + file.name, onclick: () => {
        cargo = cargo.filter((c) => c !== item);
        renderCargo();
      } }, '✕'));
    cargo.push(item);
  }
  renderCargo();
}

function renderCargo() {
  $('#cargo').hidden = !cargo.length;
  $('#cargo-count').textContent = `${cargo.length} file${cargo.length === 1 ? '' : 's'} aboard`;
  $('#cargo-size').textContent = size(cargo.reduce((n, c) => n + c.file.size, 0));
  $('#cargo-list').replaceChildren(...cargo.map((c) => c.el));
  renderSendButton();
}

function renderSendButton() {
  const n = picked.size;
  $('#send').disabled = !cargo.length || !n;
  $('#send').textContent = n ? `Ferry to ${n} device${n === 1 ? '' : 's'}` : 'Pick a destination';
}

function upload(item, to) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `/api/crossings?name=${encodeURIComponent(item.file.name)}&to=${to.join(',')}`);
    xhr.setRequestHeader('Authorization', 'Bearer ' + token);
    xhr.upload.onprogress = (e) => { if (e.lengthComputable) item.bar.style.width = (100 * e.loaded / e.total) + '%'; };
    xhr.onload = () => xhr.status === 200 ? resolve() : reject(new Error(xhr.responseText.trim() || 'upload failed'));
    xhr.onerror = () => reject(new Error('connection lost, try again'));
    xhr.send(item.file);
  });
}

async function send() {
  const to = [...picked];
  $('#send').disabled = true;
  $('#send-err').textContent = '';
  for (const item of [...cargo]) {
    try {
      await upload(item, to);
      cargo = cargo.filter((c) => c !== item);
    } catch (e) {
      $('#send-err').textContent = `${item.file.name}: ${e.message}`;
      break;
    }
  }
  renderCargo();
  loadDepartures();
}

// ---------- arrivals + departures ----------

let arrivals = [];

const ARRIVALS_SHOWN = 5;

function arrivalRow(c) {
  return h('li', {},
    h('span', { class: 'grow' }, h('span', { class: 'name' }, c.name),
      h('span', { class: 'sub' }, h('span', { class: 'tag' }, c.from), ` ${ago(c.created_at)} · ${size(c.size)}`)),
    h('button', { class: 'secondary small-btn', onclick: () => land(c, true) }, c.status === 'pending' ? 'Save' : 'Save again'));
}

async function loadArrivals() {
  arrivals = await api('/api/crossings');
  $('#arrivals').replaceChildren(...(arrivals.length ? arrivals.slice(0, ARRIVALS_SHOWN).map(arrivalRow)
    : [h('li', { class: 'empty' }, 'Nothing has arrived yet.')]));
  $('#arrivals-more').hidden = arrivals.length <= ARRIVALS_SHOWN;
  $('#arrivals-more').textContent = `See all · ${arrivals.length}`;
  if ($('#arrivals-dialog').open) renderAllArrivals();
}

// Arrivals expire after 24h on the server, so this is the whole last day.
function renderAllArrivals() {
  $('#arrivals-all').replaceChildren(...arrivals.map(arrivalRow));
}

async function loadDepartures() {
  const out = await api('/api/crossings?box=out');
  $('#departures').replaceChildren(...(out.length ? out.map((c) =>
    h('li', {},
      h('span', { class: 'grow' }, h('span', { class: 'name' }, c.name),
        h('span', { class: 'sub' }, Object.entries(c.targets)
          .map(([name, st]) => `${name}: ${st === 'arrived' ? 'arrived' : 'crossing'}`).join(' · '))),
      h('span', { class: 'sub' }, ago(c.created_at)))
  ) : [h('li', { class: 'empty' }, 'No crossings yet.')]));
}

// land saves an arrival: chosen folder, a save dialog, or the browser's downloads.
async function land(c, userClicked) {
  const url = `/api/crossings/${c.id}/file`;
  try {
    let target = null;
    if (userClicked && store.get('ask') === '1' && 'showSaveFilePicker' in window) {
      target = await window.showSaveFilePicker({ suggestedName: c.name });
    } else if (await dirReady(userClicked)) {
      target = await dir.getFileHandle(await freeName(dir, c.name), { create: true });
    }
    if (target) {
      const res = await api(url);
      await res.body.pipeTo(await target.createWritable());
    } else if (userClicked) {
      // ponytail: token rides in the URL so the browser can stream the download itself; swap for a one-time download ticket if URLs get logged
      h('a', { href: `${url}?token=${token}`, download: c.name }).click();
    } else {
      return false;
    }
    await api(`/api/crossings/${c.id}/ack`, { method: 'POST' });
    loadArrivals();
    return true;
  } catch (e) {
    if (e.name !== 'AbortError') $('#send-err').textContent = `${c.name}: ${e.message}`;
    return false;
  }
}

async function onArrival(id) {
  await loadArrivals();
  const c = arrivals.find((a) => a.id === id);
  if (!c) return;
  const saved = store.get('ask') !== '1' && await land(c, false);
  if ('Notification' in window && Notification.permission === 'granted' && document.hidden) {
    new Notification(saved ? `${c.name} landed` : `${c.name} arrived`, { body: `From ${c.from}`, icon: '/logo.svg' });
  }
}

function listen() {
  const es = new EventSource('/api/events?token=' + token);
  es.onopen = () => { loadDocks(); loadArrivals(); loadDepartures(); }; // catch up on anything missed while away
  es.onmessage = (m) => {
    const e = JSON.parse(m.data);
    if (e.type === 'arrival') onArrival(e.id);
    else if (e.type === 'departure') loadDepartures();
    else if (e.type === 'docks') loadDocks();
    else if (e.type === 'undocked') undock();
  };
}

// ---------- boarding code ----------

let codeTimer;
async function newCode() {
  const { code, expires_at } = await api('/api/dock/code', { method: 'POST' });
  $('#code').textContent = code.slice(0, 3) + ' ' + code.slice(3);
  $('#qr').src = '/api/qr?code=' + code;
  $('#qr').hidden = false;
  $('#code-row').hidden = false;
  $('#copy-link').hidden = false;
  $('#copy-link').textContent = 'Copy boarding link';
  $('#new-code').textContent = 'Refresh boarding code';
  $('#copy-link').onclick = () => navigator.clipboard.writeText(`${location.origin}/#join=${code}`)
    .then(() => { $('#copy-link').textContent = 'Copied'; });
  clearInterval(codeTimer);
  const tick = () => {
    const left = Math.max(0, expires_at - Math.floor(Date.now() / 1000));
    $('#code-ttl').textContent = left ? `expires in ${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}` : 'expired';
    if (!left) { clearInterval(codeTimer); $('#qr').hidden = true; }
  };
  tick();
  codeTimer = setInterval(tick, 1000);
}

// ---------- boot ----------

async function boot() {
  if (!token) return welcome();
  $('#bar').hidden = false;
  route();
  window.addEventListener('hashchange', route);
  try { dir = canPickDir ? (await idb('readonly', (s) => s.get('dir'))) || null : null; } catch {}

  const input = $('#file');
  input.onchange = () => { addCargo(input.files); input.value = ''; };
  const drop = $('#drop');
  // Accept drops anywhere on the page, not just the box.
  document.addEventListener('dragover', (e) => { e.preventDefault(); drop.classList.add('over'); });
  document.addEventListener('dragleave', (e) => { if (!e.relatedTarget) drop.classList.remove('over'); });
  document.addEventListener('drop', (e) => {
    e.preventDefault();
    drop.classList.remove('over');
    if (e.dataTransfer.files.length) { addCargo(e.dataTransfer.files); location.hash = '#ferry'; }
  });
  document.addEventListener('paste', (e) => {
    if (e.target.matches('input')) return;
    if (e.clipboardData.files.length) addCargo(e.clipboardData.files);
  });
  $('#send').onclick = send;
  $('#new-code').onclick = newCode;
  $('#arrivals-more').onclick = () => { renderAllArrivals(); $('#arrivals-dialog').showModal(); };

  listen();
}

boot();

import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import http from 'node:http';
import { fileURLToPath } from 'node:url';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const DATA_DIR = process.env.GROOVE_DATA_DIR || path.join(ROOT, 'data');
const DB_FILE = path.join(DATA_DIR, 'library.json');
const CONFIG_FILE = path.join(DATA_DIR, 'config.json');
const PORT = Number(process.env.PORT || 8787);
const ORIGINS = (process.env.GROOVE_ORIGINS || '*').split(',').map(s => s.trim());
await fs.mkdir(DATA_DIR, { recursive: true });
let db;
try { db = JSON.parse(await fs.readFile(DB_FILE, 'utf8')); }
catch { db = { revision: 0, songs: [], playlists: [], queue: [], settings: {}, devices: [] }; }
db.deleted ||= {};
let config;
try { config = JSON.parse(await fs.readFile(CONFIG_FILE, 'utf8')); }
catch {
  config = { pairingCode: process.env.GROOVE_PAIR_CODE || crypto.randomBytes(5).toString('hex').toUpperCase() };
  await fs.writeFile(CONFIG_FILE, JSON.stringify(config, null, 2), { mode: 0o600 });
}
const clients = new Set();
let writeChain = Promise.resolve();
function save() {
  const snapshot = JSON.stringify(db, null, 2);
  writeChain = writeChain.then(async () => {
    const tmp = `${DB_FILE}.tmp`;
    await fs.writeFile(tmp, snapshot, { mode: 0o600 });
    await fs.rename(tmp, DB_FILE);
  });
  return writeChain;
}
function publicState() {
  return { revision: db.revision, songs: db.songs, playlists: db.playlists, queue: db.queue, settings: db.settings, deletedFingerprints: Object.entries(db.deleted).map(([fingerprint, deletedAt]) => ({ fingerprint, deletedAt })),
    devices: db.devices.map(({ id, name, platform, lastSeen }) => ({ id, name, platform, lastSeen })) };
}
function send(res, status, data) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(data));
}
function broadcast() {
  const packet = `id: ${db.revision}\nevent: library\ndata: ${JSON.stringify(publicState())}\n\n`;
  for (const res of clients) if (!res.destroyed) res.write(packet);
}
function auth(req) {
  const token = (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
  const device = db.devices.find(d => d.token === token);
  if (device) device.lastSeen = new Date().toISOString();
  return device;
}
async function readJson(req) {
  const chunks = []; let size = 0;
  for await (const chunk of req) { size += chunk.length; if (size > 2 * 1024 * 1024) throw Object.assign(new Error('Request is too large.'), { status: 413 }); chunks.push(chunk); }
  if (!chunks.length) return {};
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw Object.assign(new Error('Expected a JSON request body.'), { status: 400 }); }
}
function cleanSong(song) {
  if (!song || typeof song !== 'object') return null;
  const text = value => typeof value === 'string' ? value.slice(0, 500) : '';
  const fingerprint = text(song.fingerprint);
  if (!fingerprint) return null;
  return { fingerprint, title: text(song.title), artist: text(song.artist), album: text(song.album), genre: text(song.genre), folder: text(song.folder),
    duration: Math.max(0, Math.min(Number(song.duration) || 0, 86400)), size: Math.max(0, Number(song.size) || 0), addedAt: text(song.addedAt) || new Date().toISOString() };
}
function cleanState(body) {
  const songs = Array.isArray(body.songs) ? body.songs.map(cleanSong).filter(Boolean).slice(0, 30000) : [];
  const fingerprints = new Set(songs.map(s => s.fingerprint));
  const playlists = Array.isArray(body.playlists) ? body.playlists.slice(0, 1000).filter(p => p && typeof p.name === 'string').map(p => ({
    id: String(p.id || crypto.randomUUID()).slice(0, 100), name: p.name.slice(0, 150),
    trackIds: Array.isArray(p.trackIds) ? [...new Set(p.trackIds.filter(id => fingerprints.has(id)).slice(0, 10000))] : [],
    updatedAt: String(p.updatedAt || new Date().toISOString()).slice(0, 50)
  })) : [];
  const queue = Array.isArray(body.queue) ? [...new Set(body.queue.filter(id => fingerprints.has(id)).slice(0, 10000))] : [];
  const settings = body.settings && typeof body.settings === 'object' ? Object.fromEntries(Object.entries(body.settings).slice(0, 100).map(([k, v]) => [String(k).slice(0, 80), typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean' ? v : null])) : {};
  return { songs, playlists, queue, settings };
}

const server = http.createServer(async (req, res) => {
  const origin = req.headers.origin;
  if (origin && (ORIGINS.includes('*') || ORIGINS.includes(origin))) {
    res.setHeader('Access-Control-Allow-Origin', ORIGINS.includes('*') ? '*' : origin);
    res.setHeader('Vary', 'Origin');
  }
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Authorization,Content-Type,Last-Event-ID');
  res.setHeader('Cache-Control', 'no-store');
  if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }
  const url = new URL(req.url, 'http://localhost');
  try {
    if (url.pathname === '/health' && req.method === 'GET') return send(res, 200, { ok: true, service: 'groove-sync', revision: db.revision });
    if (url.pathname === '/api/pair' && req.method === 'POST') {
      const body = await readJson(req);
      const candidate = typeof body.code === 'string' ? Buffer.from(body.code) : Buffer.alloc(0);
      const expected = Buffer.from(config.pairingCode);
      if (candidate.length !== expected.length || !crypto.timingSafeEqual(candidate, expected)) return send(res, 403, { error: 'That pairing code is not valid.' });
      const device = { id: crypto.randomUUID(), name: String(body.name || 'Groove device').slice(0, 100), platform: String(body.platform || 'web').slice(0, 40), token: crypto.randomBytes(32).toString('base64url'), lastSeen: new Date().toISOString() };
      db.devices.push(device); await save(); broadcast();
      return send(res, 200, { token: device.token, device: { id: device.id, name: device.name, platform: device.platform }, state: publicState() });
    }
    if (url.pathname.startsWith('/api/')) {
      const device = auth(req);
      if (!device) return send(res, 401, { error: 'Pair this device with your Groove library.' });
      if (url.pathname === '/api/state' && req.method === 'GET') return send(res, 200, publicState());
      if (url.pathname === '/api/tracks/remove' && req.method === 'POST') {
        const body = await readJson(req); const fingerprint = String(body.fingerprint || '').slice(0, 128);
        if (!fingerprint) return send(res, 400, { error: 'A track fingerprint is required.' });
        db.deleted[fingerprint] = new Date().toISOString();
        db.songs = db.songs.filter(s => s.fingerprint !== fingerprint);
        db.queue = db.queue.filter(id => id !== fingerprint);
        db.playlists = db.playlists.map(p => ({ ...p, trackIds: p.trackIds.filter(id => id !== fingerprint) }));
        db.revision += 1; await save(); broadcast(); return send(res, 200, publicState());
      }
      if (url.pathname === '/api/state' && req.method === 'POST') {
        const incoming = cleanState(await readJson(req));
        const index = new Map(db.songs.map((s, i) => [s.fingerprint, i]));
        for (const song of incoming.songs) {
          const deletedAt = db.deleted[song.fingerprint];
          if (deletedAt) {
            const addedAt = Date.parse(song.addedAt) || 0;
            if (addedAt <= Date.parse(deletedAt)) continue;
            delete db.deleted[song.fingerprint];
          }
          const i = index.get(song.fingerprint);
          if (i === undefined) { index.set(song.fingerprint, db.songs.length); db.songs.push(song); }
          else db.songs[i] = { ...db.songs[i], ...song, addedAt: db.songs[i].addedAt || song.addedAt };
        }
        db.playlists = incoming.playlists; db.queue = incoming.queue; db.settings = { ...db.settings, ...incoming.settings }; db.revision += 1;
        await save(); broadcast(); return send(res, 200, publicState());
      }
      if (url.pathname === '/api/events' && req.method === 'GET') {
        res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Connection': 'keep-alive', 'X-Accel-Buffering': 'no' });
        res.write(`event: library\ndata: ${JSON.stringify(publicState())}\n\n`); clients.add(res);
        const heartbeat = setInterval(() => { if (!res.destroyed) res.write(': keepalive\n\n'); }, 25000);
        req.on('close', () => { clearInterval(heartbeat); clients.delete(res); }); return;
      }
    }
    send(res, 404, { error: 'Not found.' });
  } catch (error) { if (!res.headersSent) send(res, error.status || 500, { error: error.message || 'Internal error.' }); else res.destroy(); }
});
server.listen(PORT, '0.0.0.0', () => {
  console.log(`Groove Sync listening on port ${PORT}`);
  console.log(`Pairing code: ${config.pairingCode}`);
  console.log(`Persistent library: ${DB_FILE}`);
});

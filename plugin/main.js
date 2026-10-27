const { app, core, imaging } = require("photoshop");

const russian = /^ru/i.test(require("uxp").host.uiLocale || "en");
const t = (en, ru) => russian ? ru : en;
document.documentElement.setAttribute("lang", russian ? "ru" : "en");

const POLL_MS = 400;          // how often we check whether the document changed
const SEND_TIMEOUT_MS = 8000;

const $ = (id) => document.getElementById(id);
const addressEl = $("address");
const sizeEl = $("size");
const toggleBtn = $("toggle");
const onceBtn = $("once");
const statusEl = $("status");
const statusText = $("statusText");

let running = false;
let busy = false;
let lastSignature = null;
let timer = null;

// ---------- settings ----------

function loadSettings() {
  try {
    addressEl.value = localStorage.getItem("address") || "localhost:8765";
    const size = localStorage.getItem("size");
    if (size) sizeEl.value = size;
  } catch (e) {
    addressEl.value = "localhost:8765";
  }
}

function saveSettings() {
  try {
    localStorage.setItem("address", addressEl.value.trim());
    localStorage.setItem("size", String(sizeEl.value));
  } catch (e) { /* ignore */ }
}

// "host:port" for every address in the field.
function targets() {
  return addressEl.value
    .split(",")
    .map((s) => s.trim().replace(/^\w+:\/\//, "").replace(/\/.*$/, ""))
    .filter(Boolean);
}

function isThisComputer(address) {
  const host = address.replace(/:\d+$/, "").replace(/^\[|\]$/g, "");
  return host === "localhost" || host.startsWith("127.") || host === "::1";
}

function setStatus(text, kind) {
  statusText.textContent = text;
  statusEl.className = kind || "";
}

// ---------- capture ----------

// Changes whenever the user draws, edits, undoes or switches documents.
function documentSignature() {
  const doc = app.activeDocument;
  if (!doc) return null;
  let historyId = "";
  try { historyId = doc.activeHistoryState.id; } catch (e) { /* older PS */ }
  return `${doc.id}:${historyId}:${doc.width}x${doc.height}`;
}

async function captureJpegBase64() {
  const doc = app.activeDocument;
  if (!doc) throw new Error(t("No open document", "Нет открытого документа"));

  const maxSide = parseInt(sizeEl.value, 10) || 1600;
  const w = Math.round(doc.width);
  const h = Math.round(doc.height);
  const scale = Math.min(1, maxSide / Math.max(w, h));
  const targetSize = { width: Math.max(1, Math.round(w * scale)), height: Math.max(1, Math.round(h * scale)) };

  let base64 = null;
  await core.executeAsModal(async () => {
    const pixels = await imaging.getPixels({
      documentID: doc.id,
      targetSize,
      componentSize: 8,
      applyAlpha: true,
      colorSpace: "RGB",
      colorProfile: "sRGB IEC61966-2.1",
    });
    try {
      base64 = await imaging.encodeImageData({ imageData: pixels.imageData, base64: true });
    } finally {
      pixels.imageData.dispose();
    }
  }, { commandName: "Mobile Preview" });

  return base64;
}

function withTimeout(promise, ms) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(t("request timed out", "таймаут"))), ms)),
  ]);
}

// ---------- delivery ----------

// Photoshop lets plugins make plain-http requests only to this computer itself
// ("Network request failed" before anything leaves the Mac). WebSocket may go anywhere,
// so the phone app gets frames over a WebSocket that stays open between edits.
const sockets = new Map(); // address -> { ws, ready, pending }

function connect(address) {
  const existing = sockets.get(address);
  if (existing) return existing.ready;
  const entry = { pending: null };
  entry.ready = new Promise((resolve, reject) => {
    const ws = new WebSocket("ws://" + address + "/");
    entry.ws = ws;
    ws.onopen = () => resolve(entry);
    ws.onmessage = (ev) => {
      const p = entry.pending;
      entry.pending = null;
      if (p) (ev.data === "ok" ? p.resolve() : p.reject(new Error(String(ev.data))));
    };
    ws.onerror = ws.onclose = () => {
      if (sockets.get(address) === entry) sockets.delete(address);
      reject(new Error(t("no connection", "нет соединения")));
      const p = entry.pending;
      entry.pending = null;
      if (p) p.reject(new Error(t("connection closed", "соединение оборвалось")));
    };
  });
  sockets.set(address, entry);
  return entry.ready;
}

function disconnect(address) {
  const entry = sockets.get(address);
  sockets.delete(address);
  try { if (entry && entry.ws) entry.ws.close(); } catch (e) { /* ignore */ }
}

async function sendOverWebSocket(address, base64) {
  const entry = await connect(address);
  return new Promise((resolve, reject) => {
    entry.pending = { resolve, reject };
    entry.ws.send(base64);
  });
}

async function sendOverHttp(address, base64) {
  const r = await fetch("http://" + address + "/frame", {
    method: "POST",
    headers: { "Content-Type": "text/plain" },
    body: base64,
  });
  if (!r.ok) throw new Error("HTTP " + r.status);
}

function sendTo(address, base64) {
  if (isThisComputer(address)) return withTimeout(sendOverHttp(address, base64), SEND_TIMEOUT_MS);
  return withTimeout(sendOverWebSocket(address, base64), SEND_TIMEOUT_MS)
    .catch((e) => { disconnect(address); throw e; });
}

async function sendFrame() {
  const base64 = await captureJpegBase64();
  const addresses = targets();
  if (!addresses.length) throw new Error(t("Enter a phone or server address", "Не указан адрес"));

  const results = await Promise.allSettled(addresses.map((a) => sendTo(a, base64)));

  const failed = results.filter((r) => r.status === "rejected");
  if (failed.length === addresses.length) {
    throw new Error(t("Could not connect: ", "Не удалось подключиться: ") + failed[0].reason.message);
  }
  const kb = Math.round((base64.length * 3) / 4 / 1024);
  return failed.length
    ? t(`Sent ${kb} KB; ${failed.length} destination(s) unavailable`, `Отправлено (${kb} КБ), но ${failed.length} адрес(а) недоступны`)
    : t(`Sent ${new Date().toLocaleTimeString()} · ${kb} KB`, `Отправлено ${new Date().toLocaleTimeString()} · ${kb} КБ`);
}

// ---------- loop ----------

async function tick() {
  if (!running || busy) return;
  let signature;
  try {
    signature = documentSignature();
  } catch (e) {
    return;
  }
  if (!signature) {
    setStatus(t("No open document", "Нет открытого документа"), "on");
    lastSignature = null;
    return;
  }
  if (signature === lastSignature) return;

  busy = true;
  try {
    const msg = await sendFrame();
    lastSignature = signature;
    setStatus(msg, "on");
  } catch (e) {
    // Photoshop is busy (dialog open, transform in progress...) — just try again on the next tick.
    const text = String(e && e.message || e);
    if (/modal/i.test(text)) return;
    setStatus(text, "err");
    lastSignature = signature; // don't hammer an unreachable address; retry on the next edit
    setTimeout(() => { if (running) lastSignature = null; }, 3000);
  } finally {
    busy = false;
  }
}

function start() {
  saveSettings();
  running = true;
  lastSignature = null;
  toggleBtn.textContent = t("Stop", "Стоп");
  toggleBtn.setAttribute("variant", "warning");
  setStatus(t("Connecting\u2026", "Подключаюсь…"), "on");
  timer = setInterval(tick, POLL_MS);
  tick();
}

function stop() {
  running = false;
  clearInterval(timer);
  for (const address of [...sockets.keys()]) disconnect(address);
  toggleBtn.textContent = t("Start", "Старт");
  toggleBtn.setAttribute("variant", "cta");
  setStatus(t("Stopped", "Выключено"), "");
}

toggleBtn.addEventListener("click", () => (running ? stop() : start()));

onceBtn.addEventListener("click", async () => {
  if (busy) return;
  saveSettings();
  busy = true;
  try {
    setStatus(await sendFrame(), running ? "on" : "");
  } catch (e) {
    setStatus(String(e && e.message || e), "err");
  } finally {
    busy = false;
  }
});

addressEl.addEventListener("change", () => {
  saveSettings();
  lastSignature = null;
  for (const address of [...sockets.keys()]) disconnect(address);
});
sizeEl.addEventListener("change", () => { saveSettings(); lastSignature = null; });

$("addressLabel").textContent = t("Phone or server address (comma-separated for multiple devices)", "Адрес телефона или сервера (можно несколько через запятую)");
$("sizeLabel").textContent = t("Image size (longest side)", "Размер картинки (длинная сторона)");
onceBtn.textContent = t("Send once", "Отправить");
$("hint").textContent = t(
  "The Android app shows its address. For iPhone / browser: run the server on your computer and use localhost:8765.",
  "Android-приложение показывает свой адрес. Для iPhone / браузера: запусти сервер на компьютере и оставь localhost:8765.");
stop();
loadSettings();

const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

async function checkLocale(locale, expectedStart, expectedError) {
  const elements = new Map();
  const element = (id) => {
    if (!elements.has(id)) elements.set(id, { value: "", textContent: "", setAttribute() {}, addEventListener() {} });
    return elements.get(id);
  };
  let disposed = false;
  const photoshop = {
    app: { activeDocument: null },
    core: { executeAsModal: async (fn) => fn() },
    imaging: {
      getPixels: async () => ({ imageData: { dispose() { disposed = true; } } }),
      encodeImageData: async () => "/9j/2Q==",
    },
  };
  const context = vm.createContext({
    require: (name) => name === "uxp" ? { host: { uiLocale: locale } } : photoshop,
    document: { getElementById: element, documentElement: { setAttribute() {} } },
    localStorage: { getItem() { return null; }, setItem() {} },
    setInterval() {}, clearInterval() {}, setTimeout() {},
    fetch: async () => ({ ok: true }),
  });
  vm.runInContext(fs.readFileSync("plugin/main.js", "utf8"), context);
  assert.equal(element("toggle").textContent, expectedStart);
  assert.equal(element("address").value, "localhost:8765");
  await assert.rejects(vm.runInContext("captureJpegBase64()", context), { message: expectedError });
  element("address").value = " http://localhost:8765/, 192.168.1.2:8765, ";
  assert.equal(vm.runInContext("JSON.stringify(targets())", context), '["localhost:8765","192.168.1.2:8765"]');
  assert.equal(vm.runInContext('isThisComputer("localhost:8765")', context), true);
  assert.equal(vm.runInContext('isThisComputer("192.168.1.2:8765")', context), false);
  photoshop.app.activeDocument = { id: 1, width: 100, height: 200, activeHistoryState: { id: 2 } };
  element("address").value = "localhost:8765";
  const result = await vm.runInContext("sendFrame()", context);
  assert.match(result, /^Sent |^Отправлено /);
  assert.equal(disposed, true);
}

(async () => {
  await checkLocale("en_US", "Start", "No open document");
  await checkLocale("ru_RU", "Старт", "Нет открытого документа");
  await checkLocale("de_DE", "Start", "No open document");
  // Parse the browser script as well; it is served without a build step.
  const browser = fs.readFileSync("server/viewer.html", "utf8").match(/<script>([\s\S]*?)<\/script>/)[1];
  new vm.Script(browser);
  console.log("Plugin EN/RU/fallback, delivery and browser syntax checks passed.");
})().catch((error) => { console.error(error); process.exitCode = 1; });

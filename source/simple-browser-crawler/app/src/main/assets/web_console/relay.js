(() => {
  "use strict";

  const CHANNEL = "cue-web-console-v1";
  const MAX_ARGUMENTS = 64;
  const validCategories = new Set(["console", "javascript-error", "unhandled-rejection"]);
  const validLevels = new Set(["log", "warn", "error"]);
  let captureActive = false;
  let nativePort = null;

  function controlPage(capture) {
    try {
      window.postMessage({ channel: CHANNEL, capture: capture === true }, "*");
    } catch (_) {
      // A non-cooperating page cannot break the browser or native relay.
    }
  }

  function onPageMessage(event) {
    if (!captureActive || event.source !== window || event.origin !== location.origin) return;
    const data = event.data;
    if (!data || data.channel !== CHANNEL || data.type !== "entry") return;
    if (!validCategories.has(data.category) || !validLevels.has(data.level)) return;
    if (data.category !== "console" && data.level !== "error") return;
    if (!Number.isInteger(data.argumentCount) || data.argumentCount < 0
        || data.argumentCount > MAX_ARGUMENTS) return;
    try {
      nativePort.postMessage({
        type: "entry",
        category: data.category,
        level: data.level,
        argumentCount: data.argumentCount
      });
    } catch (_) {
      // Ignore metadata if the app is closing the session or port.
    }
  }

  try {
    nativePort = browser.runtime.connectNative("browser");
    nativePort.onMessage.addListener((control) => {
      if (!control || control.type !== "capture-state" || typeof control.active !== "boolean") return;
      captureActive = control.active;
      if (captureActive) window.addEventListener("message", onPageMessage, false);
      else window.removeEventListener("message", onPageMessage, false);
      controlPage(captureActive);
    });
    nativePort.onDisconnect.addListener(() => {
      captureActive = false;
      window.removeEventListener("message", onPageMessage, false);
      controlPage(false);
      nativePort = null;
    });
    nativePort.postMessage({ type: "ready" });
  } catch (_) {
    captureActive = false;
    window.removeEventListener("message", onPageMessage, false);
    controlPage(false);
  }
})();

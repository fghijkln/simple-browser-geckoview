(() => {
  "use strict";

  const CHANNEL = "cue-web-console-v1";
  const DEFAULT_MAX_ARGUMENTS = 0;
  const DEFAULT_MAX_ENTRY_CHARS = 16384;
  const validCategories = new Set(["console", "javascript-error", "unhandled-rejection"]);
  const validLevels = new Set(["log", "warn", "error"]);
  let captureActive = false;
  let nativePort = null;
  let maxArguments = DEFAULT_MAX_ARGUMENTS;
  let maxEntryChars = DEFAULT_MAX_ENTRY_CHARS;

  function normalizeLimit(value, fallback) {
    return Number.isSafeInteger(value) && value >= 0 ? value : fallback;
  }

  function controlPage(capture) {
    try {
      window.postMessage({
        channel: CHANNEL,
        capture: capture === true,
        maxArguments,
        maxEntryChars,
        maxArgumentChars: maxEntryChars
      }, "*");
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
    if (!Number.isSafeInteger(data.argumentCount) || data.argumentCount < 0
        || (maxArguments > 0 && data.argumentCount > maxArguments)) return;
    if (typeof data.content !== "string"
        || (maxEntryChars > 0 && data.content.length > maxEntryChars)) return;
    try {
      nativePort.postMessage({
        type: "entry",
        category: data.category,
        level: data.level,
        argumentCount: data.argumentCount,
        content: data.content
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
      maxEntryChars = normalizeLimit(control.maxEntryChars, DEFAULT_MAX_ENTRY_CHARS);
      maxArguments = normalizeLimit(control.maxArguments, maxEntryChars === 0 ? 0 : DEFAULT_MAX_ARGUMENTS);
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

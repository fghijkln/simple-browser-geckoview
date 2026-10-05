(() => {
  "use strict";

  const CHANNEL = "cue-web-console-v1";
  const MAX_ARGUMENTS = 64;
  const reflectApply = Reflect.apply;
  const defineProperty = Object.defineProperty;
  const numberMin = Math.min;
  const pageOrigin = location.origin;
  const postMessage = window.postMessage.bind(window);
  const addEventListener = window.addEventListener.bind(window);
  const removeEventListener = window.removeEventListener.bind(window);
  let active = false;
  let savedConsole = null;
  let savedWrappers = null;

  function emit(category, level, argumentCount) {
    if (!active) return;
    try {
      const record = {
        channel: CHANNEL,
        type: "entry",
        category,
        level,
        argumentCount
      };
      // No origin, URL, or page identity is included in the event or target argument.
      reflectApply(postMessage, window, [record, "*"]);
    } catch (_) {
      // A failed metadata-only relay must not affect the page.
    }
  }

  function onUncaughtError(_event) {
    // Do not read message, filename, line/column, error, or stack fields.
    emit("javascript-error", "error", 0);
  }

  function onUnhandledRejection(_event) {
    // Do not read the rejection reason, which can contain arbitrary page/user text.
    emit("unhandled-rejection", "error", 0);
  }

  function disableCapture() {
    if (!active) return;
    active = false;
    removeEventListener("error", onUncaughtError, true);
    removeEventListener("unhandledrejection", onUnhandledRejection, true);
    if (savedConsole && savedWrappers) {
      for (const level of ["log", "warn", "error"]) {
        try {
          if (savedConsole[level] === savedWrappers.wrapped[level]) {
            savedConsole[level] = savedWrappers.originals[level];
          }
        } catch (_) {
          // Do not overwrite page changes made after the wrapper was installed.
        }
      }
    }
    savedConsole = null;
    savedWrappers = null;
  }

  function enableCapture() {
    if (active) return;
    const pageConsole = window.console;
    if (!pageConsole) return;
    const originals = {};
    const wrappers = {};
    for (const level of ["log", "warn", "error"]) {
      let original;
      try {
        original = pageConsole[level];
      } catch (_) {
        continue;
      }
      if (typeof original !== "function") continue;
      originals[level] = original;
      wrappers[level] = function (...args) {
        emit("console", level, reflectApply(numberMin, Math, [args.length, MAX_ARGUMENTS]));
        return reflectApply(original, this, args);
      };
      try {
        defineProperty(pageConsole, level, {
          configurable: true,
          enumerable: true,
          writable: true,
          value: wrappers[level]
        });
      } catch (_) {
        // A page-defined non-writable console method cannot be observed.
      }
    }
    savedConsole = pageConsole;
    savedWrappers = { originals, wrapped: wrappers };
    active = true;
    addEventListener("error", onUncaughtError, true);
    addEventListener("unhandledrejection", onUnhandledRejection, true);
  }

  function onControlMessage(event) {
    if (event.source !== window || event.origin !== pageOrigin) return;
    const data = event.data;
    if (!data || data.channel !== CHANNEL || typeof data.capture !== "boolean") return;
    if (data.capture) enableCapture();
    else disableCapture();
  }

  // Control-only while the explicitly opened panel is installed; no page content is read.
  addEventListener("message", onControlMessage, false);
})();

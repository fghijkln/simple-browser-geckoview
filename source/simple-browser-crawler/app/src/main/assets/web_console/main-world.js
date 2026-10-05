(() => {
  "use strict";
  const CHANNEL = "cue-web-console-v1";
  const DEFAULT_MAX_ARGUMENTS = 0;
  const DEFAULT_MAX_ARGUMENT_CHARS = 16384;
  const DEFAULT_MAX_ENTRY_CHARS = 16384;
  const reflectApply = Reflect.apply;
  const defineProperty = Object.defineProperty;
  const numberMin = Math.min;
  const pageOrigin = location.origin;
  const postMessage = window.postMessage.bind(window);
  const addEventListener = window.addEventListener.bind(window);
  const removeEventListener = window.removeEventListener.bind(window);
  const objectToString = Object.prototype.toString;
  let active = false;
  let savedConsole = null;
  let savedWrappers = null;
  let maxArguments = DEFAULT_MAX_ARGUMENTS;
  let maxArgumentChars = DEFAULT_MAX_ARGUMENT_CHARS;
  let maxEntryChars = DEFAULT_MAX_ENTRY_CHARS;

  function normalizeLimit(value, fallback) {
    return Number.isSafeInteger(value) && value >= 0 ? value : fallback;
  }

  function bounded(value, limit = maxArgumentChars) {
    const text = String(value);
    if (limit === 0) return text;
    const suffix = "…[truncated]";
    if (text.length <= limit) return text;
    if (limit <= suffix.length) return suffix.slice(0, limit);
    return text.slice(0, limit - suffix.length) + suffix;
  }

  // Never enumerate arbitrary objects or DOM nodes. Only scalar console arguments and explicit
  // Error message/stack fields are rendered; this avoids traversing form or document contents.
  function formatArgument(value) {
    try {
      if (value === null) return "null";
      const type = typeof value;
      if (type === "string") return bounded(value);
      if (type === "number" || type === "boolean" || type === "bigint") return bounded(value);
      if (type === "undefined") return "undefined";
      if (type === "symbol") return bounded(String(value));
      if (type === "function") return bounded("[Function " + (value.name || "anonymous") + "]");
      const tag = reflectApply(objectToString, value, []);
      if (tag === "[object Error]" || /Error\]$/.test(tag) || tag === "[object DOMException]") {
        let name = "Error";
        let message = "";
        let stack = "";
        try { if (typeof value.name === "string") name = value.name; } catch (_) {}
        try { if (typeof value.message === "string") message = value.message; } catch (_) {}
        try { if (typeof value.stack === "string") stack = value.stack; } catch (_) {}
        return bounded(name + (message ? ": " + message : "") + (stack ? "\n" + stack : ""));
      }
      if (Array.isArray(value)) return "[Array(" + bounded(value.length, 32) + ")]";
      return bounded(tag);
    } catch (_) {
      return "[Uninspectable value]";
    }
  }

  function formatArguments(args) {
    let text = "";
    const count = maxArguments === 0 ? args.length : numberMin(args.length, maxArguments);
    for (let i = 0; i < count; i++) {
      const part = formatArgument(args[i]);
      const separator = i === 0 ? "" : "  ";
      if (maxEntryChars > 0 && text.length + separator.length + part.length > maxEntryChars) {
        text += separator + part.slice(0, Math.max(0, maxEntryChars - text.length - separator.length)) + "…[truncated]";
        break;
      }
      text += separator + part;
    }
    if (count < args.length && (maxEntryChars === 0 || text.length < maxEntryChars)) {
      const more = "  …[more arguments omitted]";
      text += maxEntryChars > 0 ? more.slice(0, Math.max(0, maxEntryChars - text.length)) : more;
    }
    return maxEntryChars > 0 ? bounded(text, maxEntryChars) : text;
  }

  function emit(category, level, argumentCount, content) {
    if (!active) return;
    try {
      const record = {
        channel: CHANNEL,
        type: "entry",
        category,
        level,
        argumentCount,
        content: bounded(content, maxEntryChars)
      };
      // No origin, URL, absolute time, page identity, or object payload is included.
      reflectApply(postMessage, window, [record, "*"]);
    } catch (_) {
      // Diagnostic capture must never alter page behavior.
    }
  }

  function onUncaughtError(event) {
    let detail = "Uncaught JavaScript error";
    try {
      const error = event && event.error;
      if (error) detail = formatArgument(error);
      else if (event && typeof event.message === "string") {
        const file = typeof event.filename === "string" ? event.filename : "";
        const line = Number.isFinite(event.lineno) ? ":" + event.lineno : "";
        const column = Number.isFinite(event.colno) ? ":" + event.colno : "";
        detail = event.message + (file ? "\n" + file + line + column : "");
      }
    } catch (_) {}
    emit("javascript-error", "error", 1, detail);
  }

  function onUnhandledRejection(event) {
    let detail = "Unhandled promise rejection";
    try {
      if (event && "reason" in event) detail = formatArgument(event.reason);
    } catch (_) {}
    emit("unhandled-rejection", "error", 1, detail);
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
      try { original = pageConsole[level]; } catch (_) { continue; }
      if (typeof original !== "function") continue;
      originals[level] = original;
      wrappers[level] = function (...args) {
        emit("console", level, args.length, formatArguments(args));
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
    maxEntryChars = normalizeLimit(data.maxEntryChars, DEFAULT_MAX_ENTRY_CHARS);
    maxArguments = normalizeLimit(data.maxArguments, maxEntryChars === 0 ? 0 : DEFAULT_MAX_ARGUMENTS);
    maxArgumentChars = normalizeLimit(data.maxArgumentChars,
      maxEntryChars === 0 ? 0 : DEFAULT_MAX_ARGUMENT_CHARS);
    if (data.capture) enableCapture();
    else disableCapture();
  }
  addEventListener("message", onControlMessage, false);
})();

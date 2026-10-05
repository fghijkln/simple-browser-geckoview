#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const base = path.join(root, 'app/src/main/assets/web_console');
const manifest = JSON.parse(fs.readFileSync(path.join(base, 'manifest.json'), 'utf8'));
const mainSource = fs.readFileSync(path.join(base, 'main-world.js'), 'utf8');
const relaySource = fs.readFileSync(path.join(base, 'relay.js'), 'utf8');

const sentinels = [
  'SENTINEL_EMAIL_alice@example.invalid',
  'SENTINEL_TOKEN_eyJhbGciOiJub25lIn0',
  'SENTINEL_PASSWORD_correct-horse-battery',
  'SENTINEL_ERROR_message_private',
  'SENTINEL_STACK_/private/app.js:91',
  'SENTINEL_URL_https://private.example.invalid/account?token=secret',
  'SENTINEL_DOM_<input value=private>',
  'SENTINEL_COOKIE_session=secret',
  'SENTINEL_REQUEST_body=secret',
  'SENTINEL_RESPONSE_body=secret'
];

assert.equal(manifest.manifest_version, 2);
assert.deepEqual(manifest.permissions.filter(p => /^(cookies|webRequest|tabs)$/.test(p)), []);
assert(manifest.permissions.includes('nativeMessagingFromContent'));
assert(manifest.permissions.includes('http://*/*') && manifest.permissions.includes('https://*/*'));
assert.equal(manifest.content_scripts.length, 2);
assert.deepEqual(manifest.content_scripts.map(s => s.world), ['MAIN', 'ISOLATED']);
for (const script of manifest.content_scripts) {
  assert.equal(script.run_at, 'document_start');
  assert.equal(script.all_frames, false, 'only top-level frames may be injected');
  assert.deepEqual(script.matches, ['http://*/*', 'https://*/*']);
}
assert(!/\beval\s*\(|new Function\s*\(|evaluateJS|<script/i.test(mainSource + relaySource));
assert(!/document\.(querySelector|querySelectorAll|getElementById)|document\.forms|\.value\b|document\.cookie|fetch\s*\(|XMLHttpRequest/i.test(mainSource + relaySource));
assert(!/data\.message|event\.message|event\.reason|\.stack\b|\.filename\b|requestBody|responseBody/i.test(mainSource + relaySource),
  'capture scripts must not inspect content/error text, stacks, paths, or payloads');
assert(!/message\s*:|stack\s*:|url\s*:|timestamp\s*:|dom\s*:|cookie\s*:|requestBody\s*:|responseBody\s*:/i.test(mainSource + relaySource),
  'capture IPC payload constructors must contain no free-text/source/page fields');

const listeners = new Map();
const sentToRelay = [];
const actualCalls = [];
const originalConsole = {};
const pageConsole = {};
for (const level of ['log', 'warn', 'error']) {
  originalConsole[level] = (...args) => actualCalls.push([level, ...args]);
  pageConsole[level] = originalConsole[level];
}
const fakeWindow = {
  console: pageConsole,
  addEventListener(type, fn, capture) {
    const list = listeners.get(type) || [];
    list.push({ fn, capture: !!capture });
    listeners.set(type, list);
  },
  removeEventListener(type, fn, capture) {
    listeners.set(type, (listeners.get(type) || []).filter(e => e.fn !== fn || e.capture !== !!capture));
  },
  postMessage(data, targetOrigin) { sentToRelay.push({ data, targetOrigin }); }
};
const ctx = vm.createContext({ window: fakeWindow, location: { origin: 'https://example.test' }, Reflect, Object, Number, Math });
vm.runInContext(mainSource, ctx, { filename: 'main-world.js' });

function fireWindow(type, event) {
  for (const entry of [...(listeners.get(type) || [])]) entry.fn(event);
}
const initial = sentToRelay.length;
pageConsole.log(sentinels[0], sentinels[1], sentinels[2]);
fireWindow('error', { message: sentinels[3], filename: sentinels[5], lineno: 777,
  error: { stack: sentinels[4], url: sentinels[5] } });
fireWindow('unhandledrejection', { reason: { message: sentinels[2], stack: sentinels[4], value: sentinels[0] } });
assert.equal(sentToRelay.length, initial, 'default/off mode must not capture, patch console, or relay errors');
assert.equal(pageConsole.log, originalConsole.log);

fireWindow('message', { source: fakeWindow, origin: 'https://example.test', data: { channel: 'cue-web-console-v1', capture: true } });
pageConsole.log(sentinels[0], sentinels[1], sentinels[2]);
pageConsole.warn(sentinels[0]);
pageConsole.error(sentinels[1], sentinels[2]);
fireWindow('error', { message: sentinels[3], filename: sentinels[5], lineno: 777,
  error: { stack: sentinels[4], url: sentinels[5] } });
fireWindow('unhandledrejection', { reason: { message: sentinels[2], stack: sentinels[4], value: sentinels[0] } });
const entries = sentToRelay.slice(initial).map(x => x.data).filter(x => x && x.type === 'entry');
assert.deepEqual(entries.map(x => [x.category, x.level, x.argumentCount]), [
  ['console', 'log', 3], ['console', 'warn', 1], ['console', 'error', 2],
  ['javascript-error', 'error', 0], ['unhandled-rejection', 'error', 0]
]);
for (const record of entries) {
  assert.deepEqual(Object.keys(record).sort(), ['argumentCount', 'category', 'channel', 'level', 'type']);
}
assert(!JSON.stringify(entries).includes('SENTINEL_'), 'MAIN-world message payloads must not contain sentinels');
assert.equal(actualCalls.length, 4, 'wrappers preserve native console behavior');
assert.notEqual(pageConsole.log, originalConsole.log);

fireWindow('message', { source: fakeWindow, origin: 'https://example.test', data: { channel: 'cue-web-console-v1', capture: false } });
assert.equal(pageConsole.log, originalConsole.log, 'disable must restore original console methods');
assert.equal((listeners.get('error') || []).length, 0, 'disable removes window error listener');
assert.equal((listeners.get('unhandledrejection') || []).length, 0, 'disable removes rejection listener');
const afterClose = sentToRelay.length;
pageConsole.log(sentinels[0]);
fireWindow('error', { message: sentinels[3], filename: sentinels[5] });
assert.equal(sentToRelay.length, afterClose, 'closed panel must not capture');
assert.equal(actualCalls.length, 5, 'restored page console continues to work');

// Relay harness: output is an exact allowlist even if its page-world input contains hostile fields.
const windowListeners = new Map();
const windowMessages = [];
const portSent = [];
let nativeListener;
let disconnectListener;
const fakePort = {
  postMessage(record) { portSent.push(record); },
  onMessage: { addListener(fn) { nativeListener = fn; } },
  onDisconnect: { addListener(fn) { disconnectListener = fn; } }
};
const relayWindow = {
  addEventListener(type, fn) { const list = windowListeners.get(type) || []; list.push(fn); windowListeners.set(type, list); },
  removeEventListener(type, fn) { windowListeners.set(type, (windowListeners.get(type) || []).filter(item => item !== fn)); },
  postMessage(data, targetOrigin) { windowMessages.push({ data, targetOrigin }); }
};
const browser = { runtime: { connectNative(name) { assert.equal(name, 'browser'); return fakePort; } } };
const relayCtx = vm.createContext({ window: relayWindow, location: { origin: 'https://example.test' }, browser, Set, Number });
vm.runInContext(relaySource, relayCtx, { filename: 'relay.js' });
assert.equal(JSON.stringify(portSent), JSON.stringify([{ type: 'ready' }]));
function fireRelayMessage(data) {
  for (const fn of [...(windowListeners.get('message') || [])]) {
    fn({ source: relayWindow, origin: 'https://example.test', data });
  }
}
fireRelayMessage({ channel: 'cue-web-console-v1', type: 'entry', category: 'console', level: 'log', argumentCount: 1,
  message: sentinels[0], stack: sentinels[4], url: sentinels[5], dom: sentinels[6], value: sentinels[2],
  cookie: sentinels[7], requestBody: sentinels[8], responseBody: sentinels[9] });
assert.equal(portSent.length, 1, 'relay must not forward before native opt-in');
nativeListener({ type: 'capture-state', active: true });
fireRelayMessage({ channel: 'cue-web-console-v1', type: 'entry', category: 'console', level: 'log', argumentCount: 3,
  message: sentinels[0], stack: sentinels[4], url: sentinels[5], dom: sentinels[6], value: sentinels[2],
  cookie: sentinels[7], requestBody: sentinels[8], responseBody: sentinels[9] });
assert.deepEqual(JSON.parse(JSON.stringify(portSent[1])), {
  type: 'entry', category: 'console', level: 'log', argumentCount: 3
});
for (const record of portSent) assert(!JSON.stringify(record).includes('SENTINEL_'));
assert.deepEqual(Object.keys(portSent[1]).sort(), ['argumentCount', 'category', 'level', 'type']);
fireRelayMessage({ channel: 'cue-web-console-v1', type: 'entry', category: 'javascript-error', level: 'log', argumentCount: 0 });
fireRelayMessage({ channel: 'cue-web-console-v1', type: 'entry', category: 'javascript-error', level: 'error', argumentCount: 65 });
assert.equal(portSent.length, 2, 'invalid level/count combinations must be rejected');
nativeListener({ type: 'capture-state', active: false });
fireRelayMessage({ channel: 'cue-web-console-v1', type: 'entry', category: 'console', level: 'log', argumentCount: 0 });
assert.equal(portSent.length, 2, 'relay stops after native disables capture');
disconnectListener();

console.log('Web console privacy tests: PASS (sensitive sentinel strings absent from MAIN/ISOLATED IPC; exact metadata allowlist; opt-in/disable; errors; no page-data reads)');

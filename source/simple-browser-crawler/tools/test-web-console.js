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
const policySource = fs.readFileSync(path.join(root, 'app/src/main/java/com/cue/simplebrowser/WebDebugPolicy.java'), 'utf8');

const privateValues = [
  'SENTINEL_EMAIL_alice@example.invalid',
  'SENTINEL_TOKEN_eyJhbGciOiJub25lIn0',
  'SENTINEL_PASSWORD_correct-horse-battery',
  'SENTINEL_ERROR_message_private',
  'SENTINEL_STACK_/private/app.js:91',
  'SENTINEL_URL_https://private.example.invalid/account?token=secret'
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
assert(/REMOTE_DEBUGGING_DEFAULT\s*=\s*true/.test(policySource));
assert(/IN_APP_CONSOLE_DEFAULT\s*=\s*true/.test(policySource));
assert(!/\beval\s*\(|new Function\s*\(|evaluateJS|<script/i.test(mainSource + relaySource));
assert(!/document\.(querySelector|querySelectorAll|getElementById)|document\.forms|document\.cookie|fetch\s*\(|XMLHttpRequest/i.test(mainSource + relaySource));
assert(!/requestBody\s*:|responseBody\s*:|timestamp\s*:|url\s*:/i.test(mainSource + relaySource),
  'capture payloads must not contain app-generated URL/time/cookie/DOM/network-body fields');

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
const ctx = vm.createContext({ window: fakeWindow, location: { origin: 'https://example.test' }, Reflect, Object, Number, Math, Array });
vm.runInContext(mainSource, ctx, { filename: 'main-world.js' });

function fireWindow(type, event) {
  for (const entry of [...(listeners.get(type) || [])]) entry.fn(event);
}
const initial = sentToRelay.length;
pageConsole.log(privateValues[0]);
fireWindow('error', { message: privateValues[3], filename: privateValues[5], lineno: 777,
  error: { stack: privateValues[4], message: privateValues[3], name: 'TypeError' } });
fireWindow('unhandledrejection', { reason: privateValues[2] });
assert.equal(sentToRelay.length, initial, 'capture is inactive until explicitly opened');
assert.equal(pageConsole.log, originalConsole.log);

fireWindow('message', { source: fakeWindow, origin: 'https://example.test', data: { channel: 'cue-web-console-v1', capture: true } });
pageConsole.log(privateValues[0], privateValues[1], privateValues[2]);
pageConsole.warn(privateValues[0]);
pageConsole.error(privateValues[1], privateValues[2]);
const error = vm.runInContext(`new TypeError(${JSON.stringify(privateValues[3])})`, ctx);
error.stack = `TypeError: ${privateValues[3]}\n    at run (${privateValues[4]})`;
fireWindow('error', { message: privateValues[3], filename: privateValues[5], lineno: 777, error });
fireWindow('unhandledrejection', { reason: privateValues[2] });
let getterCalls = 0;
const ordinaryObject = {};
Object.defineProperty(ordinaryObject, 'secret', { get() { getterCalls++; return privateValues[2]; } });
pageConsole.log(ordinaryObject);
const large = 'x'.repeat(30_000);
pageConsole.log(large);
const entries = sentToRelay.slice(initial).map(x => x.data).filter(x => x && x.type === 'entry');
assert.deepEqual(entries.map(x => [x.category, x.level, x.argumentCount]), [
  ['console', 'log', 3], ['console', 'warn', 1], ['console', 'error', 2],
  ['javascript-error', 'error', 1], ['unhandled-rejection', 'error', 1],
  ['console', 'log', 1], ['console', 'log', 1]
]);
assert(entries[0].content.includes(privateValues[0]) && entries[0].content.includes(privateValues[1])
  && entries[0].content.includes(privateValues[2]), 'scalar console content is available to diagnostics');
assert(entries[3].content.includes(privateValues[3]) && entries[3].content.includes(privateValues[4]),
  'error text and stack/path are exposed');
assert(entries[4].content.includes(privateValues[2]), 'rejection reason is exposed');
assert(entries[5].content.includes('[object Object]') && getterCalls === 0,
  'ordinary objects are not traversed or enumerated');
assert(entries[6].content.length <= 16_384 && entries[6].content.includes('[truncated]'),
  'each message stays within the configured character cap');
for (const record of entries) {
  assert.deepEqual(Object.keys(record).sort(), ['argumentCount', 'category', 'channel', 'content', 'level', 'type']);
  assert(!('timestamp' in record) && !('url' in record) && !('dom' in record));
}
const unlimitedStart = sentToRelay.length;
fireWindow('message', { source: fakeWindow, origin: 'https://example.test', data: {
  channel: 'cue-web-console-v1', capture: true, maxEntryChars: 0, maxArguments: 0, maxArgumentChars: 0
} });
const unlimitedText = 'u'.repeat(30_000);
pageConsole.log(unlimitedText);
pageConsole.log(...Array.from({ length: 70 }, (_, index) => `arg-${index}`));
const unlimitedEntries = sentToRelay.slice(unlimitedStart).map(x => x.data).filter(x => x && x.type === 'entry');
assert.equal(unlimitedEntries.length, 2, 'unlimited settings forward large entries and argument lists');
assert.equal(unlimitedEntries[0].content.length, unlimitedText.length,
  'Unlimited has no hidden application character cap');
assert.equal(unlimitedEntries[1].argumentCount, 70);
assert(unlimitedEntries[1].content.includes('arg-69'), 'arguments beyond the former count cap are preserved');
assert.equal(actualCalls.length, 8, 'wrappers preserve original console behavior in both modes');
assert.notEqual(pageConsole.log, originalConsole.log);

fireWindow('message', { source: fakeWindow, origin: 'https://example.test', data: { channel: 'cue-web-console-v1', capture: false } });
assert.equal(pageConsole.log, originalConsole.log, 'disable restores original console methods');
assert.equal((listeners.get('error') || []).length, 0, 'disable removes window error listener');
assert.equal((listeners.get('unhandledrejection') || []).length, 0, 'disable removes rejection listener');
const afterClose = sentToRelay.length;
pageConsole.log(privateValues[0]);
fireWindow('error', { message: privateValues[3], filename: privateValues[5] });
assert.equal(sentToRelay.length, afterClose, 'closed panel stops capture');
assert.equal(actualCalls.length, 9, 'restored page console continues to work');

// Relay harness: only the exact bounded fields are forwarded after native opt-in.
const windowListeners = new Map();
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
  postMessage() {}
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
  content: privateValues[0], url: privateValues[5], timestamp: 123, cookie: 's=secret', dom: '<input>', requestBody: 'secret' });
assert.equal(portSent.length, 1, 'relay must not forward before native opt-in');
nativeListener({ type: 'capture-state', active: true });
fireRelayMessage({ channel: 'cue-web-console-v1', type: 'entry', category: 'console', level: 'log', argumentCount: 3,
  content: privateValues[0] + ' ' + privateValues[1], url: privateValues[5], timestamp: 123, cookie: 's=secret', dom: '<input>', responseBody: 'secret' });
assert.deepEqual(JSON.parse(JSON.stringify(portSent[1])), {
  type: 'entry', category: 'console', level: 'log', argumentCount: 3, content: privateValues[0] + ' ' + privateValues[1]
});
assert.deepEqual(Object.keys(portSent[1]).sort(), ['argumentCount', 'category', 'content', 'level', 'type']);
fireRelayMessage({ channel: 'cue-web-console-v1', type: 'entry', category: 'console', level: 'log', argumentCount: 1,
  content: 'x'.repeat(16_385) });
fireRelayMessage({ channel: 'cue-web-console-v1', type: 'entry', category: 'javascript-error', level: 'log', argumentCount: 0, content: 'invalid' });
assert.equal(portSent.length, 2, 'oversized content and invalid category/level combinations are rejected');
nativeListener({ type: 'capture-state', active: false });
nativeListener({ type: 'capture-state', active: true, maxEntryChars: 0, maxArguments: 0 });
fireRelayMessage({ channel: 'cue-web-console-v1', type: 'entry', category: 'console', level: 'log',
  argumentCount: 70, content: 'u'.repeat(30_000) });
assert.equal(portSent.length, 3, 'Unlimited relay accepts large content and more than 64 arguments');
assert.equal(portSent[2].content.length, 30_000);
nativeListener({ type: 'capture-state', active: false });
fireRelayMessage({ channel: 'cue-web-console-v1', type: 'entry', category: 'console', level: 'log', argumentCount: 0, content: 'closed' });
assert.equal(portSent.length, 3, 'relay stops after native disables capture');
disconnectListener();

console.log('Web console diagnostics tests: PASS (finite and Unlimited raw strings/argument counts, errors/stacks, top-frame only, explicit lifecycle, no extra DOM/cookie/network reads)');

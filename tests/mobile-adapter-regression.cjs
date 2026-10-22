#!/usr/bin/env node
/* Regression for WebView timing: the injected adapter can run before <body>. */
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const source = fs.readFileSync(path.join(__dirname, '..', 'app/src/main/assets/mobile/mobile.js'), 'utf8');
let body = null;
let domReady = [];
let observed = 0;
let disconnected = 0;
const attrs = new Set();
const document = {
  documentElement: {
    hasAttribute: name => attrs.has(name),
    setAttribute: name => attrs.add(name),
    removeAttribute: name => attrs.delete(name),
    style: { setProperty() {}, removeProperty() {} }
  },
  get body() { return body; },
  querySelector: () => null,
  querySelectorAll: () => [],
  addEventListener: (name, fn) => { if (name === 'DOMContentLoaded') domReady.push(fn); },
  removeEventListener: (name, fn) => { if (name === 'DOMContentLoaded') domReady = domReady.filter(item => item !== fn); }
};
const context = {
  document,
  window: {},
  innerHeight: 640,
  requestAnimationFrame: fn => fn(),
  MutationObserver: class {
    observe() { observed++; }
    disconnect() { disconnected++; }
  }
};
context.window.visualViewport = null;
context.window = context;
context.addEventListener = () => {};
vm.runInNewContext(source, context);
assert.equal(observed, 0, 'Adapter must not observe a missing body');
assert.equal(domReady.length, 1, 'Adapter waits for DOMContentLoaded exactly once');
body = {};
const ready = domReady.slice();
for (const callback of ready) callback();
assert.equal(observed, 1, 'Adapter observes body when it becomes available');
context.window.__dshAndroidMobile.setEnabled(false);
assert.equal(disconnected, 1, 'Disabling disconnects the observer');
assert.equal(domReady.length, 0, 'Disabling removes the pending body listener');
console.log('PASS: mobile adapter tolerates pre-body injection and cleans up lifecycle listeners');

// Official bundles use both hashed locals and __local suffixes. Validate shape,
// not one hash spelling; never inspect editor values or React private state.
for (const style of ['hashed', 'suffix']) {
  const cls = name => style === 'hashed' ? '_' + name + '_fixture' : 'Fixture-module__' + name;
  function element(name, children = []) {
    const attributes = new Set();
    const e = {classList: [cls(name)], children, firstElementChild: children[0] || null,
      parentElement: null, hasAttribute: n => attributes.has(n), setAttribute: n => attributes.add(n)};
    for (const child of children) child.parentElement = e;
    return e;
  }
  const brand = element('root', [element('stack', [element('headline')])]);
  const card = element('card');
  const hero = element('composerHero', [brand, card]);
  const frames = [];
  const ctx = {innerHeight: 320, window: null, addEventListener() {},
    requestAnimationFrame: fn => frames.push(fn),
    MutationObserver: class {observe() {} disconnect() {}},
    document: {body: {}, documentElement: {setAttribute() {}, removeAttribute() {},
      style: {setProperty() {}, removeProperty() {}}},
      querySelector: () => null,
      querySelectorAll: selector => selector === '[data-composer-card]' ? [card] : [],
      addEventListener() {}, removeEventListener() {}}};
  ctx.window = ctx;
  vm.runInNewContext(source, ctx);
  for (const frame of frames.splice(0)) frame();
  assert(hero.hasAttribute('data-dsh-mobile-hero-stack'), 'Hero ownership: ' + style);
  assert(brand.hasAttribute('data-dsh-mobile-hero-brand'), 'Brand structure: ' + style);
}
console.log('PASS: hero/brand annotations support both official CSS-module naming formats');

import assert from 'node:assert/strict';
import vm from 'node:vm';

class Element {
  closest() { return this; }
}
class HTMLElement extends Element {
  constructor() { super(); this.attributes = new Map(); }
  hasAttribute(name) { return this.attributes.has(name); }
  getAttribute(name) { return this.attributes.get(name); }
  setAttribute(name, value) { this.attributes.set(name, value); }
}
class HTMLFormElement extends HTMLElement {}
const listeners = new Map();
const location = { href: 'https://example.test/start' };
const window = {
  location, frames: [],
  addEventListener(type, callback) {
    const callbacks = listeners.get(type) ?? [];
    callbacks.push(callback); listeners.set(type, callbacks);
  },
};
window.top = window;
const page = vm.createContext({
  window, document: { baseURI: 'https://example.test/base/' },
  navigator: { userActivation: { isActive: true } },
  Element, HTMLElement, HTMLFormElement, URL,
});
const script = SCRIPT_UNDER_TEST;
vm.runInContext(script, page);
assert.equal(window.open('../next'), null);
assert.equal(location.href, 'https://example.test/next');
page.navigator.userActivation.isActive = false;
window.open('/blocked');
assert.equal(location.href, 'https://example.test/next');
page.navigator.userActivation.isActive = true;
const link = new Element(); link.target = '_blank'; link.href = 'https://example.test/clicked';
const click = { target: link, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
listeners.get('click')[0](click);
assert.equal(click.defaultPrevented, true);
assert.equal(location.href, link.href);
const form = new HTMLFormElement(); form.target = '_blank';
listeners.get('submit')[0]({ target: form });
assert.equal(form.target, '_self');
const open = window.open;
vm.runInContext(script, page);
assert.equal(window.open, open, 'installing twice leaves the original wrapper');
assert.deepEqual([...listeners].map(([type, callbacks]) => [type, callbacks.length]), [['click', 1], ['submit', 1]]);

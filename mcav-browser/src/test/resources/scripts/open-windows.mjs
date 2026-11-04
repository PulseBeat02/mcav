/*
 * This file is part of mcav, a media playback library for Java
 * Copyright (C) Brandon Li <https://brandonli.me/>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

import assert from "node:assert/strict";
import vm from "node:vm";

const START_URL = "https://example.test/start";
const BASE_URL = "https://example.test/base/";
const NEXT_URL = "https://example.test/next";
const CLICKED_URL = "https://example.test/clicked";

const NEXT_PATH = "../next";
const BLOCKED_PATH = "/blocked";

const NEW_WINDOW_TARGET = "_blank";
const SAME_WINDOW_TARGET = "_self";

class Element {
  closest() {
    return this;
  }
}

class HTMLElement extends Element {
  constructor() {
    super();
    this.attributes = new Map();
  }

  hasAttribute(name) {
    return this.attributes.has(name);
  }

  getAttribute(name) {
    return this.attributes.get(name);
  }

  setAttribute(name, value) {
    this.attributes.set(name, value);
  }
}

class HTMLFormElement extends HTMLElement {}

const listeners = new Map();
const location = { href: START_URL };

const addWindowListener = (type, callback) => {
  const callbacks = listeners.get(type) ?? [];
  callbacks.push(callback);
  listeners.set(type, callbacks);
};

const countListeners = ([type, callbacks]) => [type, callbacks.length];

const window = {
  location,
  frames: [],
  addEventListener: addWindowListener,
};
window.top = window;

const page = vm.createContext({
  window,
  document: { baseURI: BASE_URL },
  navigator: { userActivation: { isActive: true } },
  Element,
  HTMLElement,
  HTMLFormElement,
  URL,
});
const script = SCRIPT_UNDER_TEST;
vm.runInContext(script, page);
const opened = window.open(NEXT_PATH);
assert.equal(opened, null);
const openedAddress = location.href;
assert.equal(openedAddress, NEXT_URL);

page.navigator.userActivation.isActive = false;
window.open(BLOCKED_PATH);
const blockedAddress = location.href;
assert.equal(blockedAddress, NEXT_URL);

page.navigator.userActivation.isActive = true;
const link = new Element();
link.target = NEW_WINDOW_TARGET;
link.href = CLICKED_URL;
const click = {
  target: link,
  defaultPrevented: false,
  preventDefault() {
    this.defaultPrevented = true;
  },
};
listeners.get("click")[0](click);
const prevented = click.defaultPrevented;
assert.equal(prevented, true);
const clickedAddress = location.href;
assert.equal(clickedAddress, link.href);

const form = new HTMLFormElement();
form.target = NEW_WINDOW_TARGET;
listeners.get("submit")[0]({ target: form });
const formTarget = form.target;
assert.equal(formTarget, SAME_WINDOW_TARGET);

const open = window.open;
vm.runInContext(script, page);
const reinstalledOpen = window.open;
assert.equal(reinstalledOpen, open, "Installing twice leaves the original wrapper!");

const listenerCounts = [...listeners].map(countListeners);
assert.deepEqual(listenerCounts, [
  ["click", 1],
  ["submit", 1],
]);

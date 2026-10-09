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

import assert from 'node:assert/strict';
import vm from 'node:vm';

const listeners = new Map();
const contexts = [];
const processors = [];
const gains = [];
const received = [];
class EventTarget {
  constructor() { this.listeners = new Map(); }
  addEventListener(type, action) {
    const callbacks = this.listeners.get(type) ?? [];
    callbacks.push(action);
    this.listeners.set(type, callbacks);
  }
}
class AudioNode extends EventTarget {
  constructor(context) { super(); this.context = context; this.connections = []; }
  connect(target) { this.connections.push(target); return target; }
  disconnect() { this.connections = []; }
}
class AudioParam {
  constructor() { this.current = 1; }
  get value() { return this.current; }
  set value(value) { this.current = value; }
}
class GainNode extends AudioNode {
  constructor(context) { super(context); this.parameter = new AudioParam(); gains.push(this); }
  get gain() { return this.parameter; }
}
class BaseAudioContext {
  constructor(options) {
    this.sampleRate = options.sampleRate;
    this.output = new AudioNode(this);
    this.currentState = 'suspended';
    contexts.push(this);
  }
  get destination() { return this.output; }
  get state() { return this.currentState; }
  createGain() { return new GainNode(this); }
  createScriptProcessor(size, inputs, outputs) {
    assert.deepEqual([size, inputs, outputs], [2048, 2, 2]);
    const processor = new AudioNode(this);
    processors.push(processor);
    return processor;
  }
}
class AudioContext extends BaseAudioContext {
  resume() { this.currentState = 'running'; return Promise.resolve(); }
  createMediaElementSource(element) { return new MediaElementAudioSourceNode(this, { mediaElement: element }); }
}
class AudioBuffer {
  constructor(channels) { this.channels = channels; }
  get numberOfChannels() { return this.channels.length; }
  getChannelData(channel) { return this.channels[channel]; }
}
class AudioProcessingEvent {
  constructor(buffer) { this.buffer = buffer; }
  get inputBuffer() { return this.buffer; }
}
class HTMLMediaElement extends EventTarget {
  play() { return Promise.resolve(); }
}
class MediaElementAudioSourceNode extends AudioNode {
  constructor(context, options) { super(context); this.element = options.mediaElement; }
}
const page = vm.createContext({
  AudioContext, BaseAudioContext, AudioNode, AudioParam, GainNode, EventTarget,
  AudioBuffer, AudioProcessingEvent, HTMLMediaElement, MediaElementAudioSourceNode,
  btoa: text => Buffer.from(text, 'binary').toString('base64'),
  __mcavAudio: encoded => received.push(Buffer.from(encoded, 'base64')),
  addEventListener(type, action) {
    const callbacks = listeners.get(type) ?? [];
    callbacks.push(action);
    listeners.set(type, callbacks);
  },
});
const script = SCRIPT_UNDER_TEST;
vm.runInContext(script, page);
assert.equal(page.__mcavAudio, undefined, 'the binding is removed from the page');
const first = new page.AudioContext({ sampleRate: 8000 });
assert.equal(first.sampleRate, 48000, 'the captured pipeline uses the required rate');
assert.equal(new page.AudioContext({ sampleRate: 44100 }), first, 'one shared context');
assert.equal(contexts.length, 1);
assert.equal(gains.at(-1).gain.value, 0, 'captured sound never reaches server speakers');
const sharedConstructor = page.AudioContext;
vm.runInContext(script, page);
assert.equal(page.AudioContext, sharedConstructor, 'installing twice leaves the same proxy');
assert.deepEqual([...listeners].map(([type, callbacks]) => [type, callbacks.length]),
  [['pointerdown', 1], ['mousedown', 1], ['keydown', 1], ['touchend', 1], ['play', 1]]);
listeners.get('pointerdown')[0]();
assert.equal(first.state, 'running');
const deliver = channels => processors[0].listeners.get('audioprocess')[0](new AudioProcessingEvent(new AudioBuffer(channels)));
for (let chunk = 0; chunk < 50; chunk++) deliver([new Float32Array(2048)]);
assert.equal(received.length, 0, 'silence before the first sound emits no message');
deliver([new Float32Array([-1, -0.5, 0, 0.5, 1, 2, NaN]), new Float32Array([1, 0.5, 0, -0.5, -1, -2, NaN])]);
const values = [-32768, 32767, -16384, 16384, 0, 0, 16384, -16384, 32767, -32768, 32767, -32768, 0, 0];
const expected = Buffer.alloc(values.length * 2);
values.forEach((value, index) => expected.writeInt16LE(value, index * 2));
assert.deepEqual(received, [expected], 'the installed callback emits complete little-endian stereo samples');
const assertPackets = (packets, message) => {
  assert.equal(received.length, packets.length, message);
  packets.forEach((packet, index) => assert.deepEqual(received[index], packet, message + ' at packet ' + index));
};
const silentChunk = Buffer.alloc(2048 * 4);
const afterSound = [expected];
for (let chunk = 0; chunk < 47; chunk++) {
  deliver([new Float32Array(2048)]);
  afterSound.push(silentChunk);
  assertPackets(afterSound, 'each chunk of the two-second quiet window is exact stereo silence');
}
for (let chunk = 0; chunk < 5; chunk++) deliver([new Float32Array(2048)]);
assertPackets(afterSound, 'silence beyond the finite window emits no message');
const monoSound = Buffer.from([0, 64, 0, 64]);
deliver([new Float32Array([0.5])]);
afterSound.push(monoSound);
deliver([new Float32Array(2048)]);
afterSound.push(silentChunk);
assertPackets(afterSound, 'new sound reopens the quiet window');
deliver([new Float32Array([0.5])]);
afterSound.push(monoSound);
for (let chunk = 0; chunk < 47; chunk++) {
  deliver([new Float32Array(2048)]);
  afterSound.push(silentChunk);
  assertPackets(afterSound, 'sound during a quiet window renews the complete window');
}
deliver([new Float32Array(2048)]);
assertPackets(afterSound, 'the renewed window is finite too');

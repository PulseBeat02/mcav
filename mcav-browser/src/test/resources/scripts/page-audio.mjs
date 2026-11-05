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

const CHUNK_FRAMES = 2048;
const CHANNEL_COUNT = 2;

const REQUESTED_SAMPLE_RATE = 8000;
const OTHER_SAMPLE_RATE = 44100;
const CAPTURED_SAMPLE_RATE = 48000;

const LEADING_SILENT_CHUNKS = 50;
const QUIET_WINDOW_CHUNKS = 47;
const TRAILING_SILENT_CHUNKS = 5;

const SAMPLE_BYTES = 2;
const STEREO_FRAME_BYTES = SAMPLE_BYTES * CHANNEL_COUNT;

const BINARY_ENCODING = "binary";
const BASE64_ENCODING = "base64";

const RUNNING_STATE = "running";

const listeners = new Map();
const contexts = [];
const processors = [];
const gains = [];
const received = [];

class EventTarget {
  constructor() {
    this.listeners = new Map();
  }

  addEventListener(type, action) {
    const callbacks = this.listeners.get(type) ?? [];
    callbacks.push(action);
    this.listeners.set(type, callbacks);
  }
}

class AudioNode extends EventTarget {
  constructor(context) {
    super();
    this.context = context;
    this.connections = [];
  }

  connect(target) {
    this.connections.push(target);
    return target;
  }

  disconnect() {
    this.connections = [];
  }
}

class AudioParam {
  constructor() {
    this.current = 1;
  }

  get value() {
    return this.current;
  }

  set value(value) {
    this.current = value;
  }
}

class GainNode extends AudioNode {
  constructor(context) {
    super(context);
    this.parameter = new AudioParam();
    gains.push(this);
  }

  get gain() {
    return this.parameter;
  }
}

class BaseAudioContext {
  constructor(options) {
    this.sampleRate = options.sampleRate;
    this.output = new AudioNode(this);
    this.currentState = "suspended";
    contexts.push(this);
  }

  get destination() {
    return this.output;
  }

  get state() {
    return this.currentState;
  }

  createGain() {
    return new GainNode(this);
  }

  createScriptProcessor(size, inputs, outputs) {
    assert.deepEqual([size, inputs, outputs], [CHUNK_FRAMES, CHANNEL_COUNT, CHANNEL_COUNT]);

    const processor = new AudioNode(this);
    processors.push(processor);
    return processor;
  }
}

class AudioContext extends BaseAudioContext {
  resume() {
    this.currentState = RUNNING_STATE;
    return Promise.resolve();
  }

  createMediaElementSource(element) {
    return new MediaElementAudioSourceNode(this, { mediaElement: element });
  }
}

class AudioBuffer {
  constructor(channels) {
    this.channels = channels;
  }

  get numberOfChannels() {
    return this.channels.length;
  }

  getChannelData(channel) {
    return this.channels[channel];
  }
}

class AudioProcessingEvent {
  constructor(buffer) {
    this.buffer = buffer;
  }

  get inputBuffer() {
    return this.buffer;
  }
}

class HTMLMediaElement extends EventTarget {
  play() {
    return Promise.resolve();
  }
}

class MediaElementAudioSourceNode extends AudioNode {
  constructor(context, options) {
    super(context);
    this.element = options.mediaElement;
  }
}

const addPageListener = (type, action) => {
  const callbacks = listeners.get(type) ?? [];
  callbacks.push(action);
  listeners.set(type, callbacks);
};

const encodeBase64 = text => Buffer.from(text, BINARY_ENCODING).toString(BASE64_ENCODING);

const receiveAudio = encoded => received.push(Buffer.from(encoded, BASE64_ENCODING));

const countListeners = ([type, callbacks]) => [type, callbacks.length];

const deliver = channels => {
  const buffer = new AudioBuffer(channels);
  const event = new AudioProcessingEvent(buffer);
  const onAudioProcess = processors[0].listeners.get("audioprocess")[0];
  onAudioProcess(event);
};

const deliverSilence = () => {
  const silence = new Float32Array(CHUNK_FRAMES);
  deliver([silence]);
};

const assertPacket = (packet, index, message) => {
  assert.deepEqual(received[index], packet, `${message} at packet ${index}!`);
};

const assertPackets = (packets, message) => {
  assert.equal(received.length, packets.length, `${message}!`);
  packets.forEach((packet, index) => assertPacket(packet, index, message));
};

const page = vm.createContext({
  AudioContext,
  BaseAudioContext,
  AudioNode,
  AudioParam,
  GainNode,
  EventTarget,
  AudioBuffer,
  AudioProcessingEvent,
  HTMLMediaElement,
  MediaElementAudioSourceNode,
  btoa: encodeBase64,
  __mcavAudio: receiveAudio,
  addEventListener: addPageListener,
});
const script = SCRIPT_UNDER_TEST;
vm.runInContext(script, page);
assert.equal(page.__mcavAudio, undefined, "The binding is removed from the page!");

const first = new page.AudioContext({ sampleRate: REQUESTED_SAMPLE_RATE });
assert.equal(first.sampleRate, CAPTURED_SAMPLE_RATE, "The captured pipeline uses the required rate!");
assert.equal(new page.AudioContext({ sampleRate: OTHER_SAMPLE_RATE }), first, "One shared context!");
assert.equal(contexts.length, 1);
assert.equal(gains.at(-1).gain.value, 0, "Captured sound never reaches server speakers!");

const sharedConstructor = page.AudioContext;
vm.runInContext(script, page);
assert.equal(page.AudioContext, sharedConstructor, "Installing twice leaves the same proxy!");

const listenerCounts = [...listeners].map(countListeners);
assert.deepEqual(listenerCounts, [
  ["pointerdown", 1],
  ["mousedown", 1],
  ["keydown", 1],
  ["touchend", 1],
  ["play", 1],
]);

listeners.get("pointerdown")[0]();
assert.equal(first.state, RUNNING_STATE);

for (let chunk = 0; chunk < LEADING_SILENT_CHUNKS; chunk++) {
  deliverSilence();
}
assert.equal(received.length, 0, "Silence before the first sound emits no message!");

deliver([new Float32Array([-1, -0.5, 0, 0.5, 1, 2, NaN]), new Float32Array([1, 0.5, 0, -0.5, -1, -2, NaN])]);
const values = [-32768, 32767, -16384, 16384, 0, 0, 16384, -16384, 32767, -32768, 32767, -32768, 0, 0];
const expected = Buffer.alloc(values.length * SAMPLE_BYTES);
values.forEach((value, index) => expected.writeInt16LE(value, index * SAMPLE_BYTES));
assert.deepEqual(received, [expected], "The installed callback emits complete little-endian stereo samples!");

const silentChunk = Buffer.alloc(CHUNK_FRAMES * STEREO_FRAME_BYTES);
const afterSound = [expected];
for (let chunk = 0; chunk < QUIET_WINDOW_CHUNKS; chunk++) {
  deliverSilence();
  afterSound.push(silentChunk);
  assertPackets(afterSound, "Each chunk of the two-second quiet window is exact stereo silence");
}

for (let chunk = 0; chunk < TRAILING_SILENT_CHUNKS; chunk++) {
  deliverSilence();
}
assertPackets(afterSound, "Silence beyond the finite window emits no message");

const monoSound = Buffer.from([0, 64, 0, 64]);
deliver([new Float32Array([0.5])]);
afterSound.push(monoSound);
deliverSilence();
afterSound.push(silentChunk);
assertPackets(afterSound, "New sound reopens the quiet window");

deliver([new Float32Array([0.5])]);
afterSound.push(monoSound);
for (let chunk = 0; chunk < QUIET_WINDOW_CHUNKS; chunk++) {
  deliverSilence();
  afterSound.push(silentChunk);
  assertPackets(afterSound, "Sound during a quiet window renews the complete window");
}

deliverSilence();
assertPackets(afterSound, "The renewed window is finite too");

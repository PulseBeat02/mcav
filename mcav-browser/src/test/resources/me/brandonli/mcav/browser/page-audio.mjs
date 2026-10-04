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
deliver([new Float32Array([-1, -0.5, 0, 0.5, 1, 2, NaN]), new Float32Array([1, 0.5, 0, -0.5, -1, -2, NaN])]);
const values = [-32768, 32767, -16384, 16384, 0, 0, 16384, -16384, 32767, -32768, 32767, -32768, 0, 0];
const expected = Buffer.alloc(values.length * 2);
values.forEach((value, index) => expected.writeInt16LE(value, index * 2));
assert.deepEqual(received, [expected], 'the installed callback emits complete little-endian stereo samples');
deliver([new Float32Array(3)]);
assert.equal(received.length, 1, 'silence emits no binding message');

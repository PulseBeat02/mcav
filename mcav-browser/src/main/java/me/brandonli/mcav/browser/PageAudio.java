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
package me.brandonli.mcav.browser;

import com.google.common.annotations.VisibleForTesting;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.cef.browser.CefDevToolsClient;
import org.checkerframework.checker.nullness.qual.Nullable;

// The sound of the page: JCEF hands over no CEF audio, so SCRIPT sends what each document plays through a DevTools
// binding. The page can call the binding too, so only exact calls of whole frames within a budget pass. Not captured:
// out-of-process frames, cross-origin media without CORS, protected media, and Web Audio subclasses the page defines.
final class PageAudio implements CefDevToolsClient.EventListener {

  static final String BINDING = "__mcavAudio";

  static final String BINDING_EVENT = "Runtime.bindingCalled";

  static final String SCRIPT = """
  // MCAV: hands what a document plays to the browser helper, see PageAudio.java. Without a sound card every context runs
  // on its own clock, so the document gets one 48 kHz context; its tap sends PCM and outputs silence.
  (() => {
    'use strict';
    // the helper may place the script twice; once is enough
    const PLACED = Symbol.for('mcav.audio');
    if (Object.prototype.hasOwnProperty.call(globalThis, PLACED)) {
      return;
    }

    Object.defineProperty(globalThis, PLACED, { value: true });
    const BINDING = '__mcavAudio';
    const INSTALLED = Symbol.for('mcav.audio.installed');
    const RATE = 48000;
    const CHUNK = 2048;
    // a pause after sound is sent as two seconds of silence, so the server keeps its time; longer pauses are not sent
    const QUIET_CHUNKS = Math.ceil((2 * RATE) / CHUNK);
    const GESTURES = ['pointerdown', 'mousedown', 'keydown', 'touchend'];
    if (typeof AudioContext !== 'function' || globalThis[INSTALLED] === true) {
      return;
    }

    Object.defineProperty(globalThis, INSTALLED, { value: true });

    // originals, so a page cannot reach the tap through a method it wrapped
    const getter = (type, name) => Object.getOwnPropertyDescriptor(type.prototype, name).get;
    const NativeAudioContext = AudioContext;
    const nativeCreateGain = BaseAudioContext.prototype.createGain;
    const nativeCreateScriptProcessor = BaseAudioContext.prototype.createScriptProcessor;
    const nativeResume = AudioContext.prototype.resume;
    const nativeAddEventListener = EventTarget.prototype.addEventListener;
    const nativeGetChannelData = AudioBuffer.prototype.getChannelData;
    const destinationOf = getter(BaseAudioContext, 'destination');
    const stateOf = getter(BaseAudioContext, 'state');
    const gainOf = getter(GainNode, 'gain');
    const setValue = Object.getOwnPropertyDescriptor(AudioParam.prototype, 'value').set;
    const inputBufferOf = getter(AudioProcessingEvent, 'inputBuffer');
    const channelsOf = getter(AudioBuffer, 'numberOfChannels');
    const NativeElementSource = MediaElementAudioSourceNode;
    const nativeConnect = AudioNode.prototype.connect;
    const nativeDisconnect = AudioNode.prototype.disconnect;
    const nativeCreateElementSource = AudioContext.prototype.createMediaElementSource;
    const nativePlay = HTMLMediaElement.prototype.play;
    const toBase64 = btoa.bind(globalThis);
    const fromCharCode = String.fromCharCode;
    const construct = Reflect.construct;

    let send = null;
    let quietChunks = QUIET_CHUNKS;
    const findSend = () => {
      if (send === null && typeof globalThis[BINDING] === 'function') {
        send = globalThis[BINDING];
        // the page cannot send through it once it is taken
        delete globalThis[BINDING];
      }
      return send;
    };
    findSend();

    const toSample = (value) => {
      const clamped = value > 1 ? 1 : value < -1 ? -1 : value || 0;
      return Math.round(clamped < 0 ? clamped * 32768 : clamped * 32767);
    };

    const deliver = (buffer) => {
      const deliverTo = findSend();
      if (deliverTo === null) {
        return;
      }

      const left = nativeGetChannelData.call(buffer, 0);
      const right = channelsOf.call(buffer) > 1 ? nativeGetChannelData.call(buffer, 1) : left;
      const bytes = new Uint8Array(left.length * 4);
      const view = new DataView(bytes.buffer);
      let loud = false;
      for (let frame = 0; frame < left.length; frame++) {
        const first = toSample(left[frame]);
        const second = toSample(right[frame]);
        view.setInt16(frame * 4, first, true);
        view.setInt16(frame * 4 + 2, second, true);
        loud = loud || first !== 0 || second !== 0;
      }
      if (loud) {
        quietChunks = 0;
      } else if (quietChunks >= QUIET_CHUNKS) {
        return;
      } else {
        quietChunks++;
      }
      let text = '';
      for (let start = 0; start < bytes.length; start += 0x2000) {
        text += fromCharCode.apply(null, bytes.subarray(start, start + 0x2000));
      }
      deliverTo(toBase64(text));
    };

    // the one context of the document, made again should the page close it
    let mixer = null;
    const createMixer = () => {
      const context = construct(NativeAudioContext, [{ sampleRate: RATE }], NativeAudioContext);
      const bus = nativeCreateGain.call(context);
      const tap = nativeCreateScriptProcessor.call(context, CHUNK, 2, 2);
      const silence = nativeCreateGain.call(context);
      setValue.call(gainOf.call(silence), 0);
      nativeConnect.call(bus, tap);
      nativeConnect.call(tap, silence);
      nativeConnect.call(silence, destinationOf.call(context));
      nativeAddEventListener.call(tap, 'audioprocess', (event) => deliver(inputBufferOf.call(event)));
      return { context, bus };
    };
    const mixerOf = () => {
      if (mixer === null || stateOf.call(mixer.context) === 'closed') {
        mixer = createMixer();
      }
      return mixer;
    };
    // a page may play sound only after the first click on the screen
    const resume = () => {
      if (mixer !== null && stateOf.call(mixer.context) === 'suspended') {
        nativeResume.call(mixer.context).catch(() => {});
      }
    };
    for (const type of GESTURES) {
      addEventListener(type, resume, { capture: true, passive: true });
    }

    const SharedAudioContext = new Proxy(NativeAudioContext, {
      construct(target, args, newTarget) {
        if (newTarget !== SharedAudioContext) {
          // a subclass gets a context of its own, whose sound is not captured
          return construct(target, args, newTarget);
        }

        return mixerOf().context;
      },
    });
    globalThis.AudioContext = SharedAudioContext;

    // whatever the page connects to the destination also reaches the tap
    const isOutput = (target) => mixer !== null && target === destinationOf.call(mixer.context);
    AudioNode.prototype.connect = function connect(target, ...rest) {
      const result = nativeConnect.call(this, target, ...rest);
      if (isOutput(target)) {
        nativeConnect.call(this, mixer.bus, rest.length > 0 ? rest[0] : 0);
      }
      return result;
    };
    AudioNode.prototype.disconnect = function disconnect(...args) {
      const result = nativeDisconnect.apply(this, args);
      if (isOutput(args[0])) {
        try {
          nativeDisconnect.call(this, mixer.bus);
        } catch (error) {
          // it was not connected to the tap
        }
      }
      return result;
    };

    // an element plays into the context once it plays; a page asking for its source gets that node, cut from the tap
    const sources = new WeakMap();
    const sourceOf = (context, element, byPage) => {
      const known = sources.get(element);
      if (known !== undefined && known.node.context === context) {
        if (byPage && !known.byPage) {
          known.byPage = true;
          nativeDisconnect.call(known.node);
        }
        return known.node;
      }

      const node = nativeCreateElementSource.call(context, element);
      sources.set(element, { node, byPage });
      if (!byPage) {
        nativeConnect.call(node, mixerOf().bus);
      }
      return node;
    };
    NativeAudioContext.prototype.createMediaElementSource = function createMediaElementSource(element) {
      return sourceOf(this, element, true);
    };
    const SharedElementSource = new Proxy(NativeElementSource, {
      construct(target, args, newTarget) {
        const options = args[1];
        const element = options !== null && typeof options === 'object' ? options.mediaElement : undefined;
        if (newTarget === SharedElementSource && element instanceof HTMLMediaElement) {
          return sourceOf(args[0], element, true);
        }

        return construct(target, args, newTarget);
      },
    });
    globalThis.MediaElementAudioSourceNode = SharedElementSource;
    const capture = (element) => {
      if (sources.has(element)) {
        return;
      }

      try {
        sourceOf(mixerOf().context, element, false);
      } catch (error) {
        // the element plays as without MCAV
      }
    };
    HTMLMediaElement.prototype.play = function play(...args) {
      capture(this);
      return nativePlay.apply(this, args);
    };
    addEventListener(
      'play',
      (event) => {
        if (event.target instanceof HTMLMediaElement) {
          capture(event.target);
        }
      },
      true
    );
  })();
  """;

  // also the burst after a quiet time
  static final int BUDGET_SECONDS_PER_SECOND = 2;

  // quiet of the passing frame before the sound of another frame may pass
  static final int QUIET_MILLISECONDS = 250;

  private static final int BYTES_PER_SECOND = 48_000 * HelperProtocol.AUDIO_FRAME_BYTES;
  private static final long BUDGET_BYTES = (long) BUDGET_SECONDS_PER_SECOND * BYTES_PER_SECOND;
  private static final String PREFIX = "{\"name\":\"" + BINDING + "\",\"payload\":\"";
  private static final int MAXIMUM_PAYLOAD_CHARACTERS = ((HelperProtocol.MAXIMUM_AUDIO_BYTES + 2) / 3) * 4;
  private static final String CONTEXT_FIELD = "\",\"executionContextId\":";
  private static final Pattern CONTEXT = Pattern.compile(Pattern.quote(CONTEXT_FIELD) + "-?[0-9]{1,10}}");
  private static final int MAXIMUM_TAIL = CONTEXT_FIELD.length() + 12;

  private static final String ENABLE_RUNTIME_METHOD = "Runtime.enable";
  private static final String ADD_BINDING_METHOD = "Runtime.addBinding";

  private static final long BASE64_GROUP_CHARACTERS = 4L;
  private static final long BASE64_GROUP_BYTES = 3L;
  private static final long MAXIMUM_BASE64_PADDING = 2L;

  private final Consumer<byte[]> sink;
  private final LongSupplier clock;
  private long available;
  private long last;
  private long speaker;
  private long spoken;

  PageAudio(final Consumer<byte[]> sink, final LongSupplier clock) {
    this.sink = sink;
    this.clock = clock;
    this.available = BUDGET_BYTES;
    this.last = clock.getAsLong();
    this.spoken = this.last - TimeUnit.MILLISECONDS.toNanos(QUIET_MILLISECONDS);
  }

  static List<DevToolsInput.DevToolsCall> install() {
    final DevToolsInput.DevToolsCall enable = new DevToolsInput.DevToolsCall(ENABLE_RUNTIME_METHOD, "{}");
    final DevToolsInput.DevToolsCall binding = new DevToolsInput.DevToolsCall(
      ADD_BINDING_METHOD,
      "{\"name\":" + DevToolsInput.quote(BINDING) + "}"
    );
    final DevToolsInput.DevToolsCall capture = new DevToolsInput.DevToolsCall(
      DevToolsInput.ADD_SCRIPT_METHOD,
      "{\"source\":" + DevToolsInput.quote(SCRIPT) + "}"
    );
    return List.of(enable, binding, capture);
  }

  @Override
  public void onEvent(final String method, final String parameters) {
    if (!this.mayTake(leastBytes(parameters))) {
      return;
    }

    final Chunk chunk = parse(method, parameters);
    if (chunk != null && this.take(chunk)) {
      this.sink.accept(chunk.samples());
    }
  }

  // from the length alone, so an event over the budget is refused before it is decoded
  @VisibleForTesting
  static long leastBytes(final String parameters) {
    return ((parameters.length() - PREFIX.length() - MAXIMUM_TAIL) / BASE64_GROUP_CHARACTERS) * BASE64_GROUP_BYTES - MAXIMUM_BASE64_PADDING;
  }

  private synchronized boolean mayTake(final long bytes) {
    this.refill(this.clock.getAsLong());

    return bytes <= this.available;
  }

  private synchronized boolean take(final Chunk chunk) {
    final long now = this.clock.getAsLong();
    final boolean quiet = now - this.spoken >= TimeUnit.MILLISECONDS.toNanos(QUIET_MILLISECONDS);
    if (chunk.context() != this.speaker && !quiet) {
      return false;
    }

    if (!this.spend(chunk.samples().length, now)) {
      return false;
    }

    this.speaker = chunk.context();

    if (!this.isSilent(chunk.samples())) {
      this.spoken = now;
    }
    return true;
  }

  private boolean isSilent(final byte[] samples) {
    for (final byte sample : samples) {
      if (sample != 0) {
        return false;
      }
    }
    return true;
  }

  private void refill(final long now) {
    final long elapsed = Math.min(Math.max(now - this.last, 0L), TimeUnit.SECONDS.toNanos(1));
    this.last = now;
    this.available = Math.min(BUDGET_BYTES, this.available + (elapsed * BUDGET_BYTES) / TimeUnit.SECONDS.toNanos(1));
  }

  private boolean spend(final int bytes, final long now) {
    this.refill(now);

    if (bytes > this.available) {
      return false;
    }

    this.available -= bytes;

    return true;
  }

  @VisibleForTesting
  static @Nullable Chunk parse(final String method, final String parameters) {
    if (!BINDING_EVENT.equals(method) || !parameters.startsWith(PREFIX)) {
      return null;
    }

    final int start = PREFIX.length();
    final int end = parameters.indexOf('"', start);
    if (end == -1 || end - start > MAXIMUM_PAYLOAD_CHARACTERS) {
      return null;
    }

    if (!CONTEXT.matcher(parameters).region(end, parameters.length()).matches()) {
      return null;
    }

    final byte[] samples;
    try {
      samples = Base64.getDecoder().decode(parameters.substring(start, end));
    } catch (final IllegalArgumentException exception) {
      return null;
    }
    final int length = samples.length;
    if (length == 0 || length % HelperProtocol.AUDIO_FRAME_BYTES != 0) {
      return null;
    }

    final long context = Long.parseLong(parameters.substring(end + CONTEXT_FIELD.length(), parameters.length() - 1));
    return new Chunk(context, samples);
  }

  static final class Chunk {

    private final long context;
    private final byte[] samples;

    private Chunk(final long context, final byte[] samples) {
      this.context = context;
      this.samples = samples;
    }

    long context() {
      return this.context;
    }

    // not copied: the receiver owns them
    byte[] samples() {
      return this.samples;
    }
  }
}

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

/**
 * The sound of the page. JCEF hands over no audio of CEF (it has no audio handler), so the page hands it over itself:
 * {@link #SCRIPT} runs in every document before the page's own scripts and plays whatever the document plays through
 * Web Audio and its audio and video elements in one 48 kHz stereo context, whose samples it sends as Base64 of 16-bit
 * little-endian PCM through a DevTools binding. The DevTools client reports every call of the binding as an event,
 * which this listener turns into samples for the server. Every frame of the page that plays sound has a context of its
 * own; the sound of one of them passes at a time, and another may take over after {@value #QUIET_MILLIS} ms of quiet.
 *
 * <p>Every byte of an event comes from the page, which can also call the binding itself before the script takes it
 * away, so an event is only taken when it is exactly a call of the binding with at most
 * {@link HelperProtocol#MAX_AUDIO_BYTES} of whole frames, and never more than {@value #BUDGET_SECONDS_PER_SECOND}
 * seconds of sound per second pass on; the page could play that sound anyway. Sound of frames that run in another
 * process (those of another site), of media from another site that does not allow it (CORS), of protected media, and
 * of Web Audio contexts of subclasses the page defines is not captured.
 */
final class PageAudio implements CefDevToolsClient.EventListener {

  /**
   * The name of the binding, which the script takes away from the page.
   */
  static final String BINDING = "__mcavAudio";

  /**
   * The event of a call of a binding.
   */
  static final String BINDING_EVENT = "Runtime.bindingCalled";

  /**
   * The script that hands the sound of a document to the binding: it mixes what the top document and the frames of its
   * origin play into one 48 kHz stereo Web Audio context, whose tap sends 16-bit little-endian PCM, and outputs silence.
   */
  static final String SCRIPT =
    """
    // mcav: hands what a document plays to the browser helper, see PageAudio.java. Runs in every document before the
    // page's own scripts. Without a sound card every Web Audio context and media element runs on a clock of its own, and
    // sound carried between them stutters, so a document has one context: every AudioContext the page makes is the same
    // one, at 48 kHz, and its audio and video elements play into it. A tap on it sends 16-bit little-endian PCM through
    // the DevTools binding, and nothing reaches the speakers of the server: the tap outputs silence.
    (() => {
      'use strict';
      // the helper places the script again when it could not tell whether the first time worked; once is enough
      const PLACED = Symbol.for('mcav.audio');
      if (Object.prototype.hasOwnProperty.call(globalThis, PLACED)) {
        return;
      }
      Object.defineProperty(globalThis, PLACED, { value: true });
      const BINDING = '__mcavAudio';
      const INSTALLED = Symbol.for('mcav.audio.installed');
      const RATE = 48000;
      const CHUNK = 2048;
      const GESTURES = ['pointerdown', 'mousedown', 'keydown', 'touchend'];
      if (typeof AudioContext !== 'function' || globalThis[INSTALLED] === true) {
        return;
      }
      Object.defineProperty(globalThis, INSTALLED, { value: true });

      // the page runs after this script and may replace any of these; the originals are kept, so a page cannot reach the
      // tap through a method it wrapped
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
        if (!loud) {
          // silence is not sent: the server plays nothing when nothing arrives
          return;
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
      // before the first click on the screen a page may not play sound; the click lets the context play
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

      // an element plays into the context once it plays; should the page ask for its source, it gets the same node, now
      // without the way to the tap, which its own graph takes over
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
          // the element plays as without mcav
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

  /**
   * How many seconds of sound pass on per second at most, and at once after a quiet time.
   */
  static final int BUDGET_SECONDS_PER_SECOND = 2;

  /**
   * How long the frame whose sound passes must be quiet before the sound of another frame may pass, in milliseconds.
   */
  static final int QUIET_MILLIS = 250;

  private static final int BYTES_PER_SECOND = 48_000 * HelperProtocol.AUDIO_FRAME_BYTES;
  private static final long BUDGET_BYTES = (long) BUDGET_SECONDS_PER_SECOND * BYTES_PER_SECOND;
  private static final String PREFIX = "{\"name\":\"" + BINDING + "\",\"payload\":\"";
  // the Base64 of the most sound a message holds; a longer payload holds more and is refused before it is decoded
  private static final int MAX_PAYLOAD_CHARS = ((HelperProtocol.MAX_AUDIO_BYTES + 2) / 3) * 4;
  // what follows the payload: the context of the call, and nothing else
  private static final String CONTEXT_FIELD = "\",\"executionContextId\":";
  private static final Pattern CONTEXT = Pattern.compile(Pattern.quote(CONTEXT_FIELD) + "-?[0-9]{1,10}}");
  // the most that follows the payload: that field, an id of ten digits with its sign, and the closing brace
  private static final int MAX_TAIL = CONTEXT_FIELD.length() + 12;

  private final Consumer<byte[]> sink;
  private final LongSupplier clock;
  private long available;
  private long last;
  private long speaker;
  private long spoken;

  /**
   * Creates the listener.
   *
   * @param sink  receives the samples of every event that is taken
   * @param clock a monotonic clock in nanoseconds
   */
  PageAudio(final Consumer<byte[]> sink, final LongSupplier clock) {
    this.sink = sink;
    this.clock = clock;
    this.available = BUDGET_BYTES;
    this.last = clock.getAsLong();
    this.spoken = this.last - TimeUnit.MILLISECONDS.toNanos(QUIET_MILLIS);
  }

  /**
   * Gets the DevTools calls that let a page hand over its sound: the binding, and the script for every new document.
   *
   * @return the calls, to be made before the page loads
   */
  static List<DevToolsInput.DevToolsCall> install() {
    final DevToolsInput.DevToolsCall enable = new DevToolsInput.DevToolsCall("Runtime.enable", "{}");
    final DevToolsInput.DevToolsCall binding = new DevToolsInput.DevToolsCall(
      "Runtime.addBinding",
      "{\"name\":" + DevToolsInput.quote(BINDING) + "}"
    );
    final DevToolsInput.DevToolsCall capture = new DevToolsInput.DevToolsCall(
      DevToolsInput.ADD_SCRIPT_METHOD,
      "{\"source\":" + DevToolsInput.quote(SCRIPT) + "}"
    );
    return List.of(enable, binding, capture);
  }

  /**
   * Takes the samples of a call of the binding and passes them on, unless they are not sound or over the budget.
   *
   * @param method     the method of the event
   * @param parameters the parameters of the event, JSON
   */
  @Override
  public void onEvent(final String method, final String parameters) {
    // an event with more sound than the budget allows by now is not even decoded
    if (!this.mayTake(leastBytes(parameters))) {
      return;
    }
    final Chunk chunk = parse(method, parameters);
    if (chunk != null && this.take(chunk)) {
      this.sink.accept(chunk.samples());
    }
  }

  /**
   * Gets how many bytes of sound an event holds at least, from its length alone, so an event over the budget is refused
   * before it is decoded.
   *
   * @param parameters the parameters of the event
   * @return the least number of bytes a call of the binding this long decodes to, negative if it may hold none
   */
  @VisibleForTesting
  static long leastBytes(final String parameters) {
    // four characters of Base64 hold three bytes, and the padding of the last group takes two of them at most
    return ((parameters.length() - PREFIX.length() - MAX_TAIL) / 4L) * 3L - 2L;
  }

  private synchronized boolean mayTake(final long bytes) {
    this.refill(this.clock.getAsLong());
    return bytes <= this.available;
  }

  private synchronized boolean take(final Chunk chunk) {
    final long now = this.clock.getAsLong();
    final boolean quiet = now - this.spoken >= TimeUnit.MILLISECONDS.toNanos(QUIET_MILLIS);
    if (chunk.context() != this.speaker && !quiet) {
      // another frame plays; its sound would garble what passes
      return false;
    }
    if (!this.spend(chunk.samples().length, now)) {
      return false;
    }
    this.speaker = chunk.context();
    this.spoken = now;
    return true;
  }

  private void refill(final long now) {
    // a long quiet time refills the budget, never beyond it, so the elapsed time is capped before it is multiplied
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

  /**
   * Reads an event: exactly a call of the binding whose payload is Base64 of 16-bit stereo samples, whole frames and at
   * most {@link HelperProtocol#MAX_AUDIO_BYTES}.
   *
   * @param method     the method of the event
   * @param parameters the parameters of the event, JSON as Chromium writes it
   * @return the samples and the context that sent them, or null if the event is anything else
   */
  @VisibleForTesting
  static @Nullable Chunk parse(final String method, final String parameters) {
    if (!BINDING_EVENT.equals(method) || !parameters.startsWith(PREFIX)) {
      return null;
    }
    final int start = PREFIX.length();
    final int end = parameters.indexOf('"', start);
    if (end == -1 || end - start > MAX_PAYLOAD_CHARS) {
      return null;
    }
    if (!CONTEXT.matcher(parameters).region(end, parameters.length()).matches()) {
      return null;
    }
    final byte[] samples;
    try {
      // a quote the page wrote is escaped with a backslash, which is no Base64
      samples = Base64.getDecoder().decode(parameters.substring(start, end));
    } catch (final IllegalArgumentException exception) {
      return null;
    }
    // the length of the text leaves at most two bytes past the largest message, so whole frames never exceed it
    final int length = samples.length;
    if (length == 0 || length % HelperProtocol.AUDIO_FRAME_BYTES != 0) {
      return null;
    }
    // the id of the context is what lies between the name of its field and the closing brace
    final long context = Long.parseLong(parameters.substring(end + CONTEXT_FIELD.length(), parameters.length() - 1));
    return new Chunk(context, samples);
  }

  /**
   * Samples of sound and the execution context of the frame that sent them.
   */
  static final class Chunk {

    private final long context;
    private final byte[] samples;

    /**
     * Creates a chunk.
     *
     * @param context the id of the execution context
     * @param samples 16-bit little-endian stereo samples at 48 kHz, whole frames, which the chunk owns
     */
    Chunk(final long context, final byte[] samples) {
      this.context = context;
      this.samples = samples;
    }

    /**
     * Gets the execution context of the frame that sent the samples.
     *
     * @return the id of the context
     */
    long context() {
      return this.context;
    }

    /**
     * Gets the samples, which the receiver owns: they are not copied.
     *
     * @return the samples
     */
    byte[] samples() {
      return this.samples;
    }
  }
}

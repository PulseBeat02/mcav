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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PageAudioTest {

  private static final byte[] FRAMES = { 1, 0, 2, 0, 3, 0, 4, 0 };
  private static final int SECOND_BYTES = 48_000 * 4;

  private static String call(final String name, final String payload, final String context) {
    return "{\"name\":\"" + name + "\",\"payload\":\"" + payload + "\",\"executionContextId\":" + context + "}";
  }

  private static String call(final byte[] samples) {
    return call(PageAudio.BINDING, Base64.getEncoder().encodeToString(samples), "7");
  }

  private static byte[] samplesOf(final PageAudio.Chunk chunk) {
    assertNotNull(chunk);
    return chunk.samples();
  }

  @Test
  void aCallOfTheBindingIsSoundOfWholeFramesFromItsContext() {
    final PageAudio.Chunk chunk = PageAudio.parse(PageAudio.BINDING_EVENT, call(FRAMES));
    assertNotNull(chunk);
    assertEquals(7, chunk.context());
    assertEquals(-12, PageAudio.parse(PageAudio.BINDING_EVENT, call(PageAudio.BINDING, "AAAAAA==", "-12")).context());
  }

  @Test
  void theSoundOfOneFramePassesAtATimeAndAnotherAfterAQuietMoment() {
    final AtomicLong now = new AtomicLong();
    final List<Byte> passed = new ArrayList<>();
    final PageAudio audio = new PageAudio(samples -> passed.add(samples[0]), now::get);
    final String first = call(PageAudio.BINDING, Base64.getEncoder().encodeToString(new byte[] { 1, 0, 0, 0 }), "1");
    final String second = call(PageAudio.BINDING, Base64.getEncoder().encodeToString(new byte[] { 2, 0, 0, 0 }), "2");
    audio.onEvent(PageAudio.BINDING_EVENT, second);
    audio.onEvent(PageAudio.BINDING_EVENT, first);
    now.addAndGet(TimeUnit.MILLISECONDS.toNanos(PageAudio.QUIET_MILLIS - 1));
    audio.onEvent(PageAudio.BINDING_EVENT, first);
    audio.onEvent(PageAudio.BINDING_EVENT, second);
    assertEquals(List.of((byte) 2, (byte) 2), passed, "the frame that spoke first keeps the word");
    now.addAndGet(TimeUnit.MILLISECONDS.toNanos(PageAudio.QUIET_MILLIS));
    audio.onEvent(PageAudio.BINDING_EVENT, first);
    audio.onEvent(PageAudio.BINDING_EVENT, second);
    assertEquals(List.of((byte) 2, (byte) 2, (byte) 1), passed, "after a quiet moment another frame speaks");
  }

  @Test
  void aCallOfTheBindingIsSoundOfWholeFrames() {
    assertArrayEquals(FRAMES, samplesOf(PageAudio.parse(PageAudio.BINDING_EVENT, call(FRAMES))));
    final String encoded = Base64.getEncoder().encodeToString(FRAMES);
    assertArrayEquals(FRAMES, samplesOf(PageAudio.parse(PageAudio.BINDING_EVENT, call(PageAudio.BINDING, encoded, "-12"))));
    assertArrayEquals(FRAMES, samplesOf(PageAudio.parse(PageAudio.BINDING_EVENT, call(PageAudio.BINDING, encoded, "1234567890"))));
  }

  @Test
  void anythingButExactlyACallOfTheBindingIsNotSound() {
    final String encoded = Base64.getEncoder().encodeToString(FRAMES);
    assertNull(PageAudio.parse("Runtime.consoleAPICalled", call(FRAMES)), "another event");
    assertNull(PageAudio.parse(PageAudio.BINDING_EVENT, call("other", encoded, "7")), "another binding");
    assertNull(PageAudio.parse(PageAudio.BINDING_EVENT, call(PageAudio.BINDING + "X", encoded, "7")), "a longer name");
    assertNull(PageAudio.parse(PageAudio.BINDING_EVENT, call(PageAudio.BINDING, encoded, "12345678901")), "a long id");
    assertNull(PageAudio.parse(PageAudio.BINDING_EVENT, call(PageAudio.BINDING, encoded, "x")), "no id");
    assertNull(PageAudio.parse(PageAudio.BINDING_EVENT, call(FRAMES) + " "), "more after the call");
    assertNull(PageAudio.parse(PageAudio.BINDING_EVENT, call(FRAMES).replace("}", ",\"more\":1}")), "another field");
    final String unterminated = "{\"name\":\"" + PageAudio.BINDING + "\",\"payload\":\"" + encoded;
    assertNull(PageAudio.parse(PageAudio.BINDING_EVENT, unterminated), "no end of the payload");
    // a page that writes a quote into its payload gets it escaped, and a backslash is no Base64
    assertNull(PageAudio.parse(PageAudio.BINDING_EVENT, call(PageAudio.BINDING, "AAAA\\\"AAAA", "7")), "an escaped quote");
    assertNull(PageAudio.parse(PageAudio.BINDING_EVENT, call(PageAudio.BINDING, "AAAA AAAA", "7")), "not Base64");
  }

  @Test
  void soundIsWholeFramesUpToTheLimitOfAMessage() {
    assertNull(PageAudio.parse(PageAudio.BINDING_EVENT, call(new byte[0])), "no sound");
    assertNull(PageAudio.parse(PageAudio.BINDING_EVENT, call(new byte[6])), "half a frame more");
    final byte[] largest = new byte[HelperProtocol.MAX_AUDIO_BYTES];
    largest[largest.length - 1] = 9;
    assertArrayEquals(largest, samplesOf(PageAudio.parse(PageAudio.BINDING_EVENT, call(largest))));
    // refused for the length of its text alone, which is longer than that of any sound within the limit
    assertNull(PageAudio.parse(PageAudio.BINDING_EVENT, call(new byte[HelperProtocol.MAX_AUDIO_BYTES + 4])), "a frame more");
    assertNull(PageAudio.parse(PageAudio.BINDING_EVENT, call(new byte[HelperProtocol.MAX_AUDIO_BYTES + 8])), "longer still");
    // within the length of the largest sound, but more bytes than a message holds: refused once decoded
    final String longest = "A".repeat(87_384);
    assertNull(PageAudio.parse(PageAudio.BINDING_EVENT, call(PageAudio.BINDING, longest, "7")), "65538 bytes");
  }

  @Test
  void atMostTwoSecondsOfSoundPassEverySecond() {
    final AtomicLong now = new AtomicLong(TimeUnit.HOURS.toNanos(1));
    final List<Integer> passed = new ArrayList<>();
    final PageAudio audio = new PageAudio(samples -> passed.add(samples.length), now::get);
    final byte[] chunk = new byte[HelperProtocol.MAX_AUDIO_BYTES];
    final String event = call(chunk);
    for (int count = 0; count < 6; count++) {
      audio.onEvent(PageAudio.BINDING_EVENT, event);
    }
    // 2 s are 384000 bytes: five chunks of 65536 fit, the sixth does not
    assertEquals(5, passed.size());
    // 56320 bytes are left; a quarter second adds half a second of sound, 96000 bytes, which two more chunks fit
    now.addAndGet(TimeUnit.MILLISECONDS.toNanos(250));
    for (int count = 0; count < 3; count++) {
      audio.onEvent(PageAudio.BINDING_EVENT, event);
    }
    assertEquals(7, passed.size());
    // a long quiet time refills no more than the budget; a clock that goes back refills nothing
    now.addAndGet(TimeUnit.DAYS.toNanos(365));
    for (int count = 0; count < 6; count++) {
      audio.onEvent(PageAudio.BINDING_EVENT, event);
    }
    assertEquals(12, passed.size());
    now.addAndGet(-TimeUnit.SECONDS.toNanos(10));
    audio.onEvent(PageAudio.BINDING_EVENT, event);
    assertEquals(12, passed.size());
    // what is not sound costs nothing
    now.addAndGet(TimeUnit.SECONDS.toNanos(1));
    audio.onEvent(PageAudio.BINDING_EVENT, "{}");
    for (int count = 0; count < 6; count++) {
      audio.onEvent(PageAudio.BINDING_EVENT, event);
    }
    assertEquals(17, passed.size());
    assertEquals(2 * SECOND_BYTES, PageAudio.BUDGET_SECONDS_PER_SECOND * SECOND_BYTES);
  }

  @Test
  void theLengthOfACallSaysHowMuchSoundItHoldsAtLeast() {
    for (int length = 0; length <= 300; length++) {
      final String payload = Base64.getEncoder().encodeToString(new byte[length]);
      final long nearest = PageAudio.leastBytes(call(PageAudio.BINDING, payload, "-1234567890"));
      assertTrue(nearest <= length && length - nearest <= 2, length + " bytes, " + nearest + " at least");
      for (final String context : List.of("0", "7", "1234567890")) {
        final long least = PageAudio.leastBytes(call(PageAudio.BINDING, payload, context));
        assertTrue(least <= length, length + " bytes, not " + least);
      }
    }
    assertTrue(PageAudio.leastBytes("{}") < 0, "a short event may hold no sound");
  }

  @Test
  void aCallOverTheBudgetIsRefusedBeforeItIsDecoded() {
    final AtomicLong now = new AtomicLong();
    final AtomicInteger looks = new AtomicInteger();
    final List<Integer> passed = new ArrayList<>();
    final PageAudio audio = new PageAudio(
      samples -> passed.add(samples.length),
      () -> {
        looks.incrementAndGet();
        return now.get();
      }
    );
    final String largest = call(new byte[HelperProtocol.MAX_AUDIO_BYTES]);
    for (int count = 0; count < 5; count++) {
      audio.onEvent(PageAudio.BINDING_EVENT, largest);
    }
    assertEquals(5, passed.size());
    // 56320 bytes are left: the sixth is refused for its length, with one look at the clock for the budget
    looks.set(0);
    audio.onEvent(PageAudio.BINDING_EVENT, largest);
    assertEquals(1, looks.get(), "the budget alone was looked at");
    assertEquals(5, passed.size());
    // a call that fits is decoded and then taken, which looks at the clock again
    looks.set(0);
    audio.onEvent(PageAudio.BINDING_EVENT, call(new byte[4_000]));
    assertEquals(2, looks.get());
    assertEquals(List.of(65_536, 65_536, 65_536, 65_536, 65_536, 4_000), passed);
  }

  @Test
  void aCallThatItsLengthLetsPassIsStillRefusedWhenItsSoundIsMoreThanIsLeft() {
    final AtomicLong now = new AtomicLong();
    final List<Integer> passed = new ArrayList<>();
    final PageAudio audio = new PageAudio(samples -> passed.add(samples.length), now::get);
    final String largest = call(new byte[HelperProtocol.MAX_AUDIO_BYTES]);
    for (int count = 0; count < 5; count++) {
      audio.onEvent(PageAudio.BINDING_EVENT, largest);
    }
    // the 56320 bytes left of the budget, which leaves nothing
    audio.onEvent(PageAudio.BINDING_EVENT, call(new byte[56_320]));
    // 170664063 ns refill 65535 bytes: the length of the largest call says 65527 bytes at least, its sound is 65536
    now.addAndGet(170_664_063L);
    assertTrue(PageAudio.leastBytes(largest) <= 65_535);
    audio.onEvent(PageAudio.BINDING_EVENT, largest);
    audio.onEvent(PageAudio.BINDING_EVENT, call(new byte[65_532]));
    assertEquals(List.of(65_536, 65_536, 65_536, 65_536, 65_536, 56_320, 65_532), passed);
  }

  @Test
  void aCallThatTheBudgetFitsExactlyPasses() {
    final AtomicLong now = new AtomicLong();
    final List<Integer> passed = new ArrayList<>();
    final PageAudio audio = new PageAudio(samples -> passed.add(samples.length), now::get);
    // one context throughout, whose id is as long as an id can be, so the length of a call tells its sound exactly
    final String context = "-1234567890";
    final String largest = call(PageAudio.BINDING, Base64.getEncoder().encodeToString(new byte[HelperProtocol.MAX_AUDIO_BYTES]), context);
    for (int count = 0; count < 5; count++) {
      audio.onEvent(PageAudio.BINDING_EVENT, largest);
    }
    audio.onEvent(PageAudio.BINDING_EVENT, call(PageAudio.BINDING, Base64.getEncoder().encodeToString(new byte[56_320]), context));
    // 10417 ns refill 4 bytes; a frame of sound says from its length alone that it holds 4 bytes at least, which is
    // what is left
    now.addAndGet(10_417L);
    final String frame = call(PageAudio.BINDING, Base64.getEncoder().encodeToString(new byte[4]), context);
    assertEquals(4, PageAudio.leastBytes(frame));
    audio.onEvent(PageAudio.BINDING_EVENT, frame);
    audio.onEvent(PageAudio.BINDING_EVENT, frame);
    assertEquals(List.of(65_536, 65_536, 65_536, 65_536, 65_536, 56_320, 4), passed, "the budget is spent after the first");
  }

  @Test
  void theBudgetGrowsUntilTheSoundIsTaken() {
    final AtomicLong now = new AtomicLong();
    final AtomicLong step = new AtomicLong();
    final List<Integer> passed = new ArrayList<>();
    final PageAudio audio = new PageAudio(samples -> passed.add(samples.length), () -> now.addAndGet(step.get()));
    final String largest = call(new byte[HelperProtocol.MAX_AUDIO_BYTES]);
    for (int count = 0; count < 5; count++) {
      audio.onEvent(PageAudio.BINDING_EVENT, largest);
    }
    audio.onEvent(PageAudio.BINDING_EVENT, call(new byte[56_320]));
    // every look at the clock now finds 10417 ns more, 4 bytes of budget: the look before decoding leaves 4 bytes,
    // and the look when the sound is taken 8, which two frames need
    step.set(10_417L);
    audio.onEvent(PageAudio.BINDING_EVENT, call(new byte[8]));
    assertEquals(List.of(65_536, 65_536, 65_536, 65_536, 65_536, 56_320, 8), passed);
  }

  @Test
  void aPageGetsTheBindingAndTheScriptInThatOrder() {
    final List<DevToolsInput.DevToolsCall> calls = PageAudio.install();
    assertEquals(3, calls.size());
    assertEquals("Runtime.enable", calls.get(0).getMethod());
    assertEquals("{}", calls.get(0).getParameters());
    assertEquals("Runtime.addBinding", calls.get(1).getMethod());
    assertEquals("{\"name\":\"__mcavAudio\"}", calls.get(1).getParameters());
    assertEquals(DevToolsInput.ADD_SCRIPT_METHOD, calls.get(2).getMethod());
    assertEquals("{\"source\":" + DevToolsInput.quote(PageAudio.SCRIPT) + "}", calls.get(2).getParameters());
  }

  @Test
  void theScriptTakesTheBindingAwayAndSendsWhatThePagePlays() {
    final String script = PageAudio.SCRIPT;
    assertTrue(script.contains("const BINDING = '" + PageAudio.BINDING + "';"), "the script uses the binding's name");
    assertTrue(script.contains("delete globalThis[BINDING]"), "the page cannot send through it once it is taken");
    assertTrue(script.contains("const RATE = 48000;"), "the sound has the rate of mcav's audio pipeline");
    assertTrue(script.contains("if (Object.prototype.hasOwnProperty.call(globalThis, PLACED)) {"), "placed twice, it runs once");
  }
}

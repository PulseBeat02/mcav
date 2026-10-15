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
import org.checkerframework.checker.nullness.qual.Nullable;
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
  void shouldReadACallOfTheBindingAsSoundOfWholeFramesFromItsContext() {
    final PageAudio.Chunk chunk = PageAudio.parse(PageAudio.BINDING_EVENT, call(FRAMES));
    assertNotNull(chunk);
    final long chunkContext = chunk.context();
    assertEquals(7, chunkContext);
    final long negativeContext = PageAudio.parse(PageAudio.BINDING_EVENT, call(PageAudio.BINDING, "AAAAAA==", "-12")).context();
    assertEquals(-12, negativeContext);
  }

  @Test
  void shouldPassTheSoundOfOneFrameAtATimeAndAnotherAfterAQuietMoment() {
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
  void shouldPassTheSilenceAfterTheSoundOfAFrameWithoutKeepingAnotherFrameFromSpeaking() {
    final AtomicLong now = new AtomicLong();
    final List<Byte> passed = new ArrayList<>();
    final PageAudio audio = new PageAudio(samples -> passed.add(samples[0]), now::get);
    final String sound = call(PageAudio.BINDING, Base64.getEncoder().encodeToString(new byte[] { 1, 0, 0, 0 }), "1");
    final String silence = call(PageAudio.BINDING, Base64.getEncoder().encodeToString(new byte[4]), "1");
    final String other = call(PageAudio.BINDING, Base64.getEncoder().encodeToString(new byte[] { 2, 0, 0, 0 }), "2");
    audio.onEvent(PageAudio.BINDING_EVENT, sound);
    now.addAndGet(TimeUnit.MILLISECONDS.toNanos(PageAudio.QUIET_MILLIS - 1));
    audio.onEvent(PageAudio.BINDING_EVENT, silence);
    assertEquals(List.of((byte) 1, (byte) 0), passed);
    now.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
    audio.onEvent(PageAudio.BINDING_EVENT, other);
    assertEquals(List.of((byte) 1, (byte) 0, (byte) 2), passed, "another frame speaks after the quiet moment");
    audio.onEvent(PageAudio.BINDING_EVENT, silence);
    final int passedCount = passed.size();
    assertEquals(3, passedCount, "the silence of a frame that lost the word does not pass");
  }

  @Test
  void shouldReadACallOfTheBindingAsSoundOfWholeFrames() {
    final byte[] samples = samplesOf(PageAudio.parse(PageAudio.BINDING_EVENT, call(FRAMES)));
    assertArrayEquals(FRAMES, samples);
    final String encoded = Base64.getEncoder().encodeToString(FRAMES);
    final byte[] negativeContextSamples = samplesOf(PageAudio.parse(PageAudio.BINDING_EVENT, call(PageAudio.BINDING, encoded, "-12")));
    assertArrayEquals(FRAMES, negativeContextSamples);
    final byte[] longContextSamples = samplesOf(PageAudio.parse(PageAudio.BINDING_EVENT, call(PageAudio.BINDING, encoded, "1234567890")));
    assertArrayEquals(FRAMES, longContextSamples);
  }

  @Test
  void shouldNotReadAnythingButExactlyACallOfTheBindingAsSound() {
    final String encoded = Base64.getEncoder().encodeToString(FRAMES);
    final PageAudio.@Nullable Chunk otherEventChunk = PageAudio.parse("Runtime.consoleAPICalled", call(FRAMES));
    assertNull(otherEventChunk, "another event");
    final PageAudio.@Nullable Chunk otherBindingChunk = PageAudio.parse(PageAudio.BINDING_EVENT, call("other", encoded, "7"));
    assertNull(otherBindingChunk, "another binding");
    final PageAudio.@Nullable Chunk longerNameChunk = PageAudio.parse(PageAudio.BINDING_EVENT, call(PageAudio.BINDING + "X", encoded, "7"));
    assertNull(longerNameChunk, "a longer name");
    final PageAudio.@Nullable Chunk longIdChunk = PageAudio.parse(PageAudio.BINDING_EVENT, call(PageAudio.BINDING, encoded, "12345678901"));
    assertNull(longIdChunk, "a long id");
    final PageAudio.@Nullable Chunk missingIdChunk = PageAudio.parse(PageAudio.BINDING_EVENT, call(PageAudio.BINDING, encoded, "x"));
    assertNull(missingIdChunk, "no id");
    final PageAudio.@Nullable Chunk trailingTextChunk = PageAudio.parse(PageAudio.BINDING_EVENT, call(FRAMES) + " ");
    assertNull(trailingTextChunk, "more after the call");
    final PageAudio.@Nullable Chunk extraFieldChunk = PageAudio.parse(PageAudio.BINDING_EVENT, call(FRAMES).replace("}", ",\"more\":1}"));
    assertNull(extraFieldChunk, "another field");
    final String unterminated = "{\"name\":\"" + PageAudio.BINDING + "\",\"payload\":\"" + encoded;
    final PageAudio.@Nullable Chunk unterminatedChunk = PageAudio.parse(PageAudio.BINDING_EVENT, unterminated);
    assertNull(unterminatedChunk, "no end of the payload");
    final PageAudio.@Nullable Chunk escapedQuoteChunk = PageAudio.parse(
      PageAudio.BINDING_EVENT,
      call(PageAudio.BINDING, "AAAA\\\"AAAA", "7")
    );
    assertNull(escapedQuoteChunk, "an escaped quote");
    final PageAudio.@Nullable Chunk invalidBase64Chunk = PageAudio.parse(
      PageAudio.BINDING_EVENT,
      call(PageAudio.BINDING, "AAAA AAAA", "7")
    );
    assertNull(invalidBase64Chunk, "not Base64");
  }

  @Test
  void shouldTakeSoundAsWholeFramesUpToTheLimitOfAMessage() {
    final PageAudio.@Nullable Chunk emptyChunk = PageAudio.parse(PageAudio.BINDING_EVENT, call(new byte[0]));
    assertNull(emptyChunk, "no sound");
    final PageAudio.@Nullable Chunk halfFrameMoreChunk = PageAudio.parse(PageAudio.BINDING_EVENT, call(new byte[6]));
    assertNull(halfFrameMoreChunk, "half a frame more");
    final byte[] largest = new byte[HelperProtocol.MAX_AUDIO_BYTES];
    largest[largest.length - 1] = 9;
    final byte[] largestSamples = samplesOf(PageAudio.parse(PageAudio.BINDING_EVENT, call(largest)));
    assertArrayEquals(largest, largestSamples);
    final PageAudio.@Nullable Chunk frameMoreChunk = PageAudio.parse(
      PageAudio.BINDING_EVENT,
      call(new byte[HelperProtocol.MAX_AUDIO_BYTES + 4])
    );
    assertNull(frameMoreChunk, "a frame more");
    final PageAudio.@Nullable Chunk twoFramesMoreChunk = PageAudio.parse(
      PageAudio.BINDING_EVENT,
      call(new byte[HelperProtocol.MAX_AUDIO_BYTES + 8])
    );
    assertNull(twoFramesMoreChunk, "longer still");
    final String longest = "A".repeat(87_384);
    final PageAudio.@Nullable Chunk longestChunk = PageAudio.parse(PageAudio.BINDING_EVENT, call(PageAudio.BINDING, longest, "7"));
    assertNull(longestChunk, "65538 bytes");
  }

  @Test
  void shouldPassAtMostTwoSecondsOfSoundEverySecond() {
    final AtomicLong now = new AtomicLong(TimeUnit.HOURS.toNanos(1));
    final List<Integer> passed = new ArrayList<>();
    final PageAudio audio = new PageAudio(samples -> passed.add(samples.length), now::get);
    final byte[] chunk = new byte[HelperProtocol.MAX_AUDIO_BYTES];
    final String event = call(chunk);
    for (int count = 0; count < 6; count++) {
      audio.onEvent(PageAudio.BINDING_EVENT, event);
    }
    final int countAtStart = passed.size();
    assertEquals(5, countAtStart);
    now.addAndGet(TimeUnit.MILLISECONDS.toNanos(250));
    for (int count = 0; count < 3; count++) {
      audio.onEvent(PageAudio.BINDING_EVENT, event);
    }
    final int countAfterAQuarterSecond = passed.size();
    assertEquals(7, countAfterAQuarterSecond);
    now.addAndGet(TimeUnit.DAYS.toNanos(365));
    for (int count = 0; count < 6; count++) {
      audio.onEvent(PageAudio.BINDING_EVENT, event);
    }
    final int countAfterAYear = passed.size();
    assertEquals(12, countAfterAYear);
    now.addAndGet(-TimeUnit.SECONDS.toNanos(10));
    audio.onEvent(PageAudio.BINDING_EVENT, event);
    final int countAfterTheClockWentBack = passed.size();
    assertEquals(12, countAfterTheClockWentBack);
    now.addAndGet(TimeUnit.SECONDS.toNanos(1));
    audio.onEvent(PageAudio.BINDING_EVENT, "{}");
    for (int count = 0; count < 6; count++) {
      audio.onEvent(PageAudio.BINDING_EVENT, event);
    }
    final int countAfterAnotherSecond = passed.size();
    assertEquals(17, countAfterAnotherSecond);
    final int budgetBytes = PageAudio.BUDGET_SECONDS_PER_SECOND * SECOND_BYTES;
    assertEquals(2 * SECOND_BYTES, budgetBytes);
  }

  @Test
  void shouldTellFromTheLengthOfACallHowMuchSoundItHoldsAtLeast() {
    for (int length = 0; length <= 300; length++) {
      final String payload = Base64.getEncoder().encodeToString(new byte[length]);
      final long nearest = PageAudio.leastBytes(call(PageAudio.BINDING, payload, "-1234567890"));
      final boolean nearestIsClose = nearest <= length && length - nearest <= 2;
      assertTrue(nearestIsClose, length + " bytes, " + nearest + " at least");
      for (final String context : List.of("0", "7", "1234567890")) {
        final long least = PageAudio.leastBytes(call(PageAudio.BINDING, payload, context));
        final boolean leastFits = least <= length;
        assertTrue(leastFits, length + " bytes, not " + least);
      }
    }
    final boolean noSoundInAShortEvent = PageAudio.leastBytes("{}") < 0;
    assertTrue(noSoundInAShortEvent, "a short event may hold no sound");
  }

  @Test
  void shouldRefuseACallOverTheBudgetBeforeItIsDecoded() {
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
    final int passedCountAtBudget = passed.size();
    assertEquals(5, passedCountAtBudget);
    looks.set(0);
    audio.onEvent(PageAudio.BINDING_EVENT, largest);
    final int looksOverBudget = looks.get();
    assertEquals(1, looksOverBudget, "the budget alone was looked at");
    final int passedCountAfterRefusal = passed.size();
    assertEquals(5, passedCountAfterRefusal);
    looks.set(0);
    audio.onEvent(PageAudio.BINDING_EVENT, call(new byte[4_000]));
    final int looksAtASmallCall = looks.get();
    assertEquals(2, looksAtASmallCall);
    assertEquals(List.of(65_536, 65_536, 65_536, 65_536, 65_536, 4_000), passed);
  }

  @Test
  void shouldStillRefuseACallThatItsLengthLetsPassWhenItsSoundIsMoreThanIsLeft() {
    final AtomicLong now = new AtomicLong();
    final List<Integer> passed = new ArrayList<>();
    final PageAudio audio = new PageAudio(samples -> passed.add(samples.length), now::get);
    final String largest = call(new byte[HelperProtocol.MAX_AUDIO_BYTES]);
    for (int count = 0; count < 5; count++) {
      audio.onEvent(PageAudio.BINDING_EVENT, largest);
    }
    audio.onEvent(PageAudio.BINDING_EVENT, call(new byte[56_320]));
    now.addAndGet(170_664_063L);
    final boolean lengthLetsItPass = PageAudio.leastBytes(largest) <= 65_535;
    assertTrue(lengthLetsItPass);
    audio.onEvent(PageAudio.BINDING_EVENT, largest);
    audio.onEvent(PageAudio.BINDING_EVENT, call(new byte[65_532]));
    assertEquals(List.of(65_536, 65_536, 65_536, 65_536, 65_536, 56_320, 65_532), passed);
  }

  @Test
  void shouldPassACallThatTheBudgetFitsExactly() {
    final AtomicLong now = new AtomicLong();
    final List<Integer> passed = new ArrayList<>();
    final PageAudio audio = new PageAudio(samples -> passed.add(samples.length), now::get);
    final String context = "-1234567890";
    final String largest = call(PageAudio.BINDING, Base64.getEncoder().encodeToString(new byte[HelperProtocol.MAX_AUDIO_BYTES]), context);
    for (int count = 0; count < 5; count++) {
      audio.onEvent(PageAudio.BINDING_EVENT, largest);
    }
    audio.onEvent(PageAudio.BINDING_EVENT, call(PageAudio.BINDING, Base64.getEncoder().encodeToString(new byte[56_320]), context));
    now.addAndGet(10_417L);
    final String frame = call(PageAudio.BINDING, Base64.getEncoder().encodeToString(new byte[4]), context);
    final long frameLeastBytes = PageAudio.leastBytes(frame);
    assertEquals(4, frameLeastBytes);
    audio.onEvent(PageAudio.BINDING_EVENT, frame);
    audio.onEvent(PageAudio.BINDING_EVENT, frame);
    assertEquals(List.of(65_536, 65_536, 65_536, 65_536, 65_536, 56_320, 4), passed, "the budget is spent after the first");
  }

  @Test
  void shouldGrowTheBudgetUntilTheSoundIsTaken() {
    final AtomicLong now = new AtomicLong();
    final AtomicLong step = new AtomicLong();
    final List<Integer> passed = new ArrayList<>();
    final PageAudio audio = new PageAudio(samples -> passed.add(samples.length), () -> now.addAndGet(step.get()));
    final String largest = call(new byte[HelperProtocol.MAX_AUDIO_BYTES]);
    for (int count = 0; count < 5; count++) {
      audio.onEvent(PageAudio.BINDING_EVENT, largest);
    }
    audio.onEvent(PageAudio.BINDING_EVENT, call(new byte[56_320]));
    step.set(10_417L);
    audio.onEvent(PageAudio.BINDING_EVENT, call(new byte[8]));
    assertEquals(List.of(65_536, 65_536, 65_536, 65_536, 65_536, 56_320, 8), passed);
  }

  @Test
  void shouldGiveAPageTheBindingAndTheScriptInThatOrder() {
    final List<DevToolsInput.DevToolsCall> calls = PageAudio.install();
    final int callCount = calls.size();
    assertEquals(3, callCount);
    final String enableMethod = calls.get(0).getMethod();
    assertEquals("Runtime.enable", enableMethod);
    final String enableParameters = calls.get(0).getParameters();
    assertEquals("{}", enableParameters);
    final String bindingMethod = calls.get(1).getMethod();
    assertEquals("Runtime.addBinding", bindingMethod);
    final String bindingParameters = calls.get(1).getParameters();
    assertEquals("{\"name\":\"__mcavAudio\"}", bindingParameters);
    final String scriptMethod = calls.get(2).getMethod();
    assertEquals(DevToolsInput.ADD_SCRIPT_METHOD, scriptMethod);
    final String scriptParameters = calls.get(2).getParameters();
    assertEquals("{\"source\":" + DevToolsInput.quote(PageAudio.SCRIPT) + "}", scriptParameters);
  }

  @Test
  void shouldTakeTheBindingAwayInTheScriptAndSendWhatThePagePlays() throws Exception {
    final String script = PageAudio.SCRIPT;
    ScriptAssertions.execute("page-audio.mjs", script);
    final boolean usesTheBindingName = script.contains("const BINDING = '" + PageAudio.BINDING + "';");
    assertTrue(usesTheBindingName, "the script uses the binding's name");
    final boolean deletesTheBinding = script.contains("delete globalThis[BINDING]");
    assertTrue(deletesTheBinding, "the page cannot send through it once it is taken");
    final boolean setsTheRate = script.contains("const RATE = 48000;");
    assertTrue(setsTheRate, "the sound has the rate of mcav's audio pipeline");
    final boolean guardsAgainstPlacingTwice = script.contains("if (Object.prototype.hasOwnProperty.call(globalThis, PLACED)) {");
    assertTrue(guardsAgainstPlacingTwice, "placed twice, it runs once");
  }
}

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import me.brandonli.mcav.browser.testing.EqualityAssertions;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

class PageAudioTest {

  private static final String FIRST_CONTEXT_ID = "1";
  private static final String NEGATIVE_CONTEXT_ID = "-12";
  private static final String SECOND_CONTEXT_ID = "2";
  private static final String LONG_CONTEXT_ID = "1234567890";
  private static final String LONG_NEGATIVE_CONTEXT_ID = "-1234567890";

  private static final String PAGE_AUDIO_SCRIPT_FILE = "page-audio.mjs";

  private static final long FRAME_BUDGET_NANOSECONDS = 10_417L;

  private static final byte[] FRAMES = { 1, 0, 2, 0, 3, 0, 4, 0 };
  private static final byte[] OTHER_FRAMES = { 5, 0, 6, 0 };

  private static final int MAXIMUM_CHUNK_BYTES = 65_536;
  private static final int PARTIAL_CHUNK_BYTES = 56_320;

  private static final String CONTEXT_ID = "7";

  private static final String EMPTY_OBJECT = "{}";

  private String call(final String name, final String payload, final String context) {
    return "{\"name\":\"%s\",\"payload\":\"%s\",\"executionContextId\":%s}".formatted(name, payload, context);
  }

  private String call(final byte[] samples) {
    return this.call(PageAudio.BINDING, Base64.getEncoder().encodeToString(samples), CONTEXT_ID);
  }

  private byte[] samplesOf(final PageAudio.Chunk chunk) {
    assertNotNull(chunk);

    return chunk.getSamples();
  }

  @Test
  void shouldReadACallOfTheBindingAsSoundOfWholeFramesFromItsContext() {
    final PageAudio.Chunk chunk = PageAudio.parse(PageAudio.BINDING_EVENT, this.call(FRAMES));
    assertNotNull(chunk);

    final long chunkContext = chunk.getContextId();
    assertEquals(7, chunkContext);

    final long negativeContext = PageAudio.parse(
      PageAudio.BINDING_EVENT,
      this.call(PageAudio.BINDING, "AAAAAA==", NEGATIVE_CONTEXT_ID)
    ).getContextId();
    assertEquals(-12, negativeContext);
  }

  @Test
  void shouldPassTheSoundOfOneFrameAtATimeAndAnotherAfterAQuietMoment() {
    final AtomicLong now = new AtomicLong();
    final List<Byte> passed = new ArrayList<>();
    final PageAudio audio = new PageAudio(samples -> passed.add(samples[0]), now::get);
    final String first = this.call(PageAudio.BINDING, Base64.getEncoder().encodeToString(new byte[] { 1, 0, 0, 0 }), FIRST_CONTEXT_ID);
    final String second = this.call(PageAudio.BINDING, Base64.getEncoder().encodeToString(new byte[] { 2, 0, 0, 0 }), SECOND_CONTEXT_ID);
    audio.onEvent(PageAudio.BINDING_EVENT, second);
    audio.onEvent(PageAudio.BINDING_EVENT, first);
    now.addAndGet(TimeUnit.MILLISECONDS.toNanos(PageAudio.QUIET_MILLISECONDS - 1));
    audio.onEvent(PageAudio.BINDING_EVENT, first);
    audio.onEvent(PageAudio.BINDING_EVENT, second);
    final List<Byte> expectedPassedSamples = List.of((byte) 2, (byte) 2);
    assertEquals(expectedPassedSamples, passed, "the frame that spoke first keeps the word");

    now.addAndGet(TimeUnit.MILLISECONDS.toNanos(PageAudio.QUIET_MILLISECONDS));
    audio.onEvent(PageAudio.BINDING_EVENT, first);
    audio.onEvent(PageAudio.BINDING_EVENT, second);
    final List<Byte> expectedPassedSamplesAfterQuietMoment = List.of((byte) 2, (byte) 2, (byte) 1);
    assertEquals(expectedPassedSamplesAfterQuietMoment, passed, "after a quiet moment another frame speaks");
  }

  @Test
  void shouldPassTheSilenceAfterTheSoundOfAFrameWithoutKeepingAnotherFrameFromSpeaking() {
    final AtomicLong now = new AtomicLong();
    final List<Byte> passed = new ArrayList<>();
    final PageAudio audio = new PageAudio(samples -> passed.add(samples[0]), now::get);
    final String sound = this.call(PageAudio.BINDING, Base64.getEncoder().encodeToString(new byte[] { 1, 0, 0, 0 }), FIRST_CONTEXT_ID);
    final String silence = this.call(PageAudio.BINDING, Base64.getEncoder().encodeToString(new byte[4]), FIRST_CONTEXT_ID);
    final String other = this.call(PageAudio.BINDING, Base64.getEncoder().encodeToString(new byte[] { 2, 0, 0, 0 }), SECOND_CONTEXT_ID);
    audio.onEvent(PageAudio.BINDING_EVENT, sound);
    now.addAndGet(TimeUnit.MILLISECONDS.toNanos(PageAudio.QUIET_MILLISECONDS - 1));
    audio.onEvent(PageAudio.BINDING_EVENT, silence);
    final List<Byte> expectedPassedSamples = List.of((byte) 1, (byte) 0);
    assertEquals(expectedPassedSamples, passed);

    now.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
    audio.onEvent(PageAudio.BINDING_EVENT, other);
    final List<Byte> expectedPassedSamplesAfterQuietMoment = List.of((byte) 1, (byte) 0, (byte) 2);
    assertEquals(expectedPassedSamplesAfterQuietMoment, passed, "another frame speaks after the quiet moment");

    audio.onEvent(PageAudio.BINDING_EVENT, silence);
    final int passedCount = passed.size();
    assertEquals(3, passedCount, "the silence of a frame that lost the word does not pass");
  }

  @Test
  void shouldReadACallOfTheBindingAsSoundOfWholeFrames() {
    final byte[] samples = this.samplesOf(PageAudio.parse(PageAudio.BINDING_EVENT, this.call(FRAMES)));
    assertArrayEquals(FRAMES, samples);

    final String encoded = Base64.getEncoder().encodeToString(FRAMES);
    final byte[] negativeContextSamples = this.samplesOf(
      PageAudio.parse(PageAudio.BINDING_EVENT, this.call(PageAudio.BINDING, encoded, NEGATIVE_CONTEXT_ID))
    );
    assertArrayEquals(FRAMES, negativeContextSamples);

    final byte[] longContextSamples = this.samplesOf(
      PageAudio.parse(PageAudio.BINDING_EVENT, this.call(PageAudio.BINDING, encoded, LONG_CONTEXT_ID))
    );
    assertArrayEquals(FRAMES, longContextSamples);
  }

  @Test
  void shouldNotReadAnythingButExactlyACallOfTheBindingAsSound() {
    final String encoded = Base64.getEncoder().encodeToString(FRAMES);
    final PageAudio.@Nullable Chunk otherEventChunk = PageAudio.parse("Runtime.consoleAPICalled", this.call(FRAMES));
    assertNull(otherEventChunk, "another event");

    final PageAudio.@Nullable Chunk otherBindingChunk = PageAudio.parse(PageAudio.BINDING_EVENT, this.call("other", encoded, CONTEXT_ID));
    assertNull(otherBindingChunk, "another binding");

    final PageAudio.@Nullable Chunk longerNameChunk = PageAudio.parse(
      PageAudio.BINDING_EVENT,
      this.call(PageAudio.BINDING + "X", encoded, CONTEXT_ID)
    );
    assertNull(longerNameChunk, "a longer name");

    final PageAudio.@Nullable Chunk longIdChunk = PageAudio.parse(
      PageAudio.BINDING_EVENT,
      this.call(PageAudio.BINDING, encoded, "12345678901")
    );
    assertNull(longIdChunk, "a long id");

    final PageAudio.@Nullable Chunk missingIdChunk = PageAudio.parse(PageAudio.BINDING_EVENT, this.call(PageAudio.BINDING, encoded, "x"));
    assertNull(missingIdChunk, "no id");

    final PageAudio.@Nullable Chunk trailingTextChunk = PageAudio.parse(PageAudio.BINDING_EVENT, this.call(FRAMES) + " ");
    assertNull(trailingTextChunk, "more after the call");

    final PageAudio.@Nullable Chunk extraFieldChunk = PageAudio.parse(
      PageAudio.BINDING_EVENT,
      this.call(FRAMES).replace("}", ",\"more\":1}")
    );
    assertNull(extraFieldChunk, "another field");

    final String unterminated = "{\"name\":\"" + PageAudio.BINDING + "\",\"payload\":\"" + encoded;
    final PageAudio.@Nullable Chunk unterminatedChunk = PageAudio.parse(PageAudio.BINDING_EVENT, unterminated);
    assertNull(unterminatedChunk, "no end of the payload");

    final PageAudio.@Nullable Chunk escapedQuoteChunk = PageAudio.parse(
      PageAudio.BINDING_EVENT,
      this.call(PageAudio.BINDING, "AAAA\\\"AAAA", CONTEXT_ID)
    );
    assertNull(escapedQuoteChunk, "an escaped quote");

    final PageAudio.@Nullable Chunk invalidBase64Chunk = PageAudio.parse(
      PageAudio.BINDING_EVENT,
      this.call(PageAudio.BINDING, "AAAA AAAA", CONTEXT_ID)
    );
    assertNull(invalidBase64Chunk, "not Base64");
  }

  @Test
  void shouldTakeSoundAsWholeFramesUpToTheLimitOfAMessage() {
    final PageAudio.@Nullable Chunk emptyChunk = PageAudio.parse(PageAudio.BINDING_EVENT, this.call(new byte[0]));
    assertNull(emptyChunk, "no sound");

    final PageAudio.@Nullable Chunk halfFrameMoreChunk = PageAudio.parse(PageAudio.BINDING_EVENT, this.call(new byte[6]));
    assertNull(halfFrameMoreChunk, "half a frame more");

    final byte[] largest = new byte[HelperProtocol.MAXIMUM_AUDIO_BYTES];
    largest[largest.length - 1] = 9;
    final byte[] largestSamples = this.samplesOf(PageAudio.parse(PageAudio.BINDING_EVENT, this.call(largest)));
    assertArrayEquals(largest, largestSamples);

    final PageAudio.@Nullable Chunk frameMoreChunk = PageAudio.parse(
      PageAudio.BINDING_EVENT,
      this.call(new byte[HelperProtocol.MAXIMUM_AUDIO_BYTES + 4])
    );
    assertNull(frameMoreChunk, "a frame more");

    final PageAudio.@Nullable Chunk twoFramesMoreChunk = PageAudio.parse(
      PageAudio.BINDING_EVENT,
      this.call(new byte[HelperProtocol.MAXIMUM_AUDIO_BYTES + 8])
    );
    assertNull(twoFramesMoreChunk, "longer still");

    final String longest = "A".repeat(87_384);
    final PageAudio.@Nullable Chunk longestChunk = PageAudio.parse(
      PageAudio.BINDING_EVENT,
      this.call(PageAudio.BINDING, longest, CONTEXT_ID)
    );
    assertNull(longestChunk, "65538 bytes");
  }

  @Test
  void shouldPassAtMostTwoSecondsOfSoundEverySecond() {
    final AtomicLong now = new AtomicLong(TimeUnit.HOURS.toNanos(1));
    final List<Integer> passed = new ArrayList<>();
    final PageAudio audio = new PageAudio(samples -> passed.add(samples.length), now::get);
    final byte[] chunk = new byte[HelperProtocol.MAXIMUM_AUDIO_BYTES];
    final String event = this.call(chunk);
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
    audio.onEvent(PageAudio.BINDING_EVENT, EMPTY_OBJECT);
    for (int count = 0; count < 6; count++) {
      audio.onEvent(PageAudio.BINDING_EVENT, event);
    }

    final int countAfterAnotherSecond = passed.size();
    assertEquals(17, countAfterAnotherSecond);
  }

  @Test
  void shouldTellFromTheLengthOfACallHowMuchSoundItHoldsAtLeast() {
    for (int length = 0; length <= 300; length++) {
      final String payload = Base64.getEncoder().encodeToString(new byte[length]);
      final long nearest = PageAudio.leastBytes(this.call(PageAudio.BINDING, payload, LONG_NEGATIVE_CONTEXT_ID));
      final boolean nearestIsClose = nearest <= length && length - nearest <= 2;
      assertTrue(nearestIsClose, length + " bytes, " + nearest + " at least");

      for (final String context : List.of("0", CONTEXT_ID, LONG_CONTEXT_ID)) {
        final long least = PageAudio.leastBytes(this.call(PageAudio.BINDING, payload, context));
        final boolean leastFits = least <= length;
        assertTrue(leastFits, length + " bytes, not " + least);
      }
    }

    final boolean noSoundInAShortEvent = PageAudio.leastBytes(EMPTY_OBJECT) < 0;
    assertTrue(noSoundInAShortEvent, "a short event may hold no sound");
  }

  @Test
  void shouldRefuseACallOverTheBudgetBeforeItIsDecoded() {
    final AtomicLong now = new AtomicLong();
    final AtomicInteger looks = new AtomicInteger();
    final List<Integer> passed = new ArrayList<>();
    final PageAudio audio = new PageAudio(samples -> passed.add(samples.length), () -> this.countLookAndRead(looks, now));
    final String largest = this.call(new byte[HelperProtocol.MAXIMUM_AUDIO_BYTES]);
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
    audio.onEvent(PageAudio.BINDING_EVENT, this.call(new byte[4_000]));
    final int looksAtASmallCall = looks.get();
    assertEquals(2, looksAtASmallCall);
    final List<Integer> expectedChunkSizes = List.of(
      MAXIMUM_CHUNK_BYTES,
      MAXIMUM_CHUNK_BYTES,
      MAXIMUM_CHUNK_BYTES,
      MAXIMUM_CHUNK_BYTES,
      MAXIMUM_CHUNK_BYTES,
      4_000
    );
    assertEquals(expectedChunkSizes, passed);
  }

  private long countLookAndRead(final AtomicInteger looks, final AtomicLong now) {
    looks.incrementAndGet();

    return now.get();
  }

  @Test
  void shouldStillRefuseACallThatItsLengthLetsPassWhenItsSoundIsMoreThanIsLeft() {
    final AtomicLong now = new AtomicLong();
    final List<Integer> passed = new ArrayList<>();
    final PageAudio audio = new PageAudio(samples -> passed.add(samples.length), now::get);
    final String largest = this.call(new byte[HelperProtocol.MAXIMUM_AUDIO_BYTES]);
    for (int count = 0; count < 5; count++) {
      audio.onEvent(PageAudio.BINDING_EVENT, largest);
    }

    audio.onEvent(PageAudio.BINDING_EVENT, this.call(new byte[PARTIAL_CHUNK_BYTES]));
    now.addAndGet(170_664_063L);
    final boolean lengthLetsItPass = PageAudio.leastBytes(largest) <= 65_535;
    assertTrue(lengthLetsItPass);

    audio.onEvent(PageAudio.BINDING_EVENT, largest);
    audio.onEvent(PageAudio.BINDING_EVENT, this.call(new byte[65_532]));
    final List<Integer> expectedChunkSizes = List.of(
      MAXIMUM_CHUNK_BYTES,
      MAXIMUM_CHUNK_BYTES,
      MAXIMUM_CHUNK_BYTES,
      MAXIMUM_CHUNK_BYTES,
      MAXIMUM_CHUNK_BYTES,
      PARTIAL_CHUNK_BYTES,
      65_532
    );
    assertEquals(expectedChunkSizes, passed);
  }

  @Test
  void shouldPassACallThatTheBudgetFitsExactly() {
    final AtomicLong now = new AtomicLong();
    final List<Integer> passed = new ArrayList<>();
    final PageAudio audio = new PageAudio(samples -> passed.add(samples.length), now::get);
    final String context = LONG_NEGATIVE_CONTEXT_ID;
    final String largest = this.call(
      PageAudio.BINDING,
      Base64.getEncoder().encodeToString(new byte[HelperProtocol.MAXIMUM_AUDIO_BYTES]),
      context
    );
    for (int count = 0; count < 5; count++) {
      audio.onEvent(PageAudio.BINDING_EVENT, largest);
    }

    audio.onEvent(
      PageAudio.BINDING_EVENT,
      this.call(PageAudio.BINDING, Base64.getEncoder().encodeToString(new byte[PARTIAL_CHUNK_BYTES]), context)
    );
    now.addAndGet(FRAME_BUDGET_NANOSECONDS);
    final String frame = this.call(PageAudio.BINDING, Base64.getEncoder().encodeToString(new byte[4]), context);
    final long frameLeastBytes = PageAudio.leastBytes(frame);
    assertEquals(4, frameLeastBytes);

    audio.onEvent(PageAudio.BINDING_EVENT, frame);
    audio.onEvent(PageAudio.BINDING_EVENT, frame);
    final List<Integer> expectedChunkSizes = List.of(
      MAXIMUM_CHUNK_BYTES,
      MAXIMUM_CHUNK_BYTES,
      MAXIMUM_CHUNK_BYTES,
      MAXIMUM_CHUNK_BYTES,
      MAXIMUM_CHUNK_BYTES,
      PARTIAL_CHUNK_BYTES,
      4
    );
    assertEquals(expectedChunkSizes, passed, "the budget is spent after the first");
  }

  @Test
  void shouldGrowTheBudgetUntilTheSoundIsTaken() {
    final AtomicLong now = new AtomicLong();
    final AtomicLong step = new AtomicLong();
    final List<Integer> passed = new ArrayList<>();
    final PageAudio audio = new PageAudio(samples -> passed.add(samples.length), () -> now.addAndGet(step.get()));
    final String largest = this.call(new byte[HelperProtocol.MAXIMUM_AUDIO_BYTES]);
    for (int count = 0; count < 5; count++) {
      audio.onEvent(PageAudio.BINDING_EVENT, largest);
    }

    audio.onEvent(PageAudio.BINDING_EVENT, this.call(new byte[PARTIAL_CHUNK_BYTES]));
    step.set(FRAME_BUDGET_NANOSECONDS);
    audio.onEvent(PageAudio.BINDING_EVENT, this.call(new byte[8]));
    final List<Integer> expectedChunkSizes = List.of(
      MAXIMUM_CHUNK_BYTES,
      MAXIMUM_CHUNK_BYTES,
      MAXIMUM_CHUNK_BYTES,
      MAXIMUM_CHUNK_BYTES,
      MAXIMUM_CHUNK_BYTES,
      PARTIAL_CHUNK_BYTES,
      8
    );
    assertEquals(expectedChunkSizes, passed);
  }

  @Test
  void shouldGiveAPageTheBindingAndTheScriptInThatOrder() {
    final List<DevToolsInput.DevToolsCall> calls = PageAudio.install();
    final int callCount = calls.size();
    assertEquals(3, callCount);

    final String enableMethod = calls.get(0).getMethod();
    assertEquals("Runtime.enable", enableMethod);

    final String enableParameters = calls.get(0).getParameters();
    assertEquals(EMPTY_OBJECT, enableParameters);

    final String bindingMethod = calls.get(1).getMethod();
    assertEquals("Runtime.addBinding", bindingMethod);

    final String bindingParameters = calls.get(1).getParameters();
    assertEquals("{\"name\":\"__mcavAudio\"}", bindingParameters);

    final String scriptMethod = calls.get(2).getMethod();
    assertEquals(DevToolsInput.ADD_SCRIPT_METHOD, scriptMethod);

    final String scriptParameters = calls.get(2).getParameters();
    final String quotedScript = DevToolsInput.quote(PageAudio.SCRIPT);
    final String expectedScriptParameters = "{\"source\":%s}".formatted(quotedScript);
    assertEquals(expectedScriptParameters, scriptParameters);
  }

  @Test
  void shouldTakeTheBindingAwayInTheScriptAndSendWhatThePagePlays() throws Exception {
    final String script = PageAudio.SCRIPT;
    ScriptAssertions.execute(PAGE_AUDIO_SCRIPT_FILE, script);
    final boolean usesTheBindingName = script.contains("const BINDING = \"" + PageAudio.BINDING + "\";");
    assertTrue(usesTheBindingName, "the script uses the binding's name");

    final boolean deletesTheBinding = script.contains("delete globalThis[BINDING]");
    assertTrue(deletesTheBinding, "the page cannot send through it once it is taken");

    final boolean setsTheRate = script.contains("const SAMPLE_RATE = 48000;");
    assertTrue(setsTheRate, "the sound has the rate of mcav's audio pipeline");

    final boolean guardsAgainstPlacingTwice = script.contains("if (Object.prototype.hasOwnProperty.call(globalThis, PLACED)) {");
    assertTrue(guardsAgainstPlacingTwice, "placed twice, it runs once");
  }

  @Test
  void shouldTreatChunksOfTheSameContextAndSoundAsEqual() {
    final String encoded = Base64.getEncoder().encodeToString(FRAMES);
    EqualityAssertions.assertEqualityContract(
      PageAudio.parse(PageAudio.BINDING_EVENT, this.call(FRAMES)),
      PageAudio.parse(PageAudio.BINDING_EVENT, this.call(FRAMES)),
      PageAudio.parse(PageAudio.BINDING_EVENT, this.call(PageAudio.BINDING, encoded, SECOND_CONTEXT_ID)),
      PageAudio.parse(PageAudio.BINDING_EVENT, this.call(OTHER_FRAMES))
    );
  }

  @Test
  void shouldHashChunksOfOtherSoundDifferently() {
    final PageAudio.Chunk chunk = PageAudio.parse(PageAudio.BINDING_EVENT, this.call(FRAMES));
    final PageAudio.Chunk otherChunk = PageAudio.parse(PageAudio.BINDING_EVENT, this.call(OTHER_FRAMES));
    final int hashCode = chunk.hashCode();
    final int otherHashCode = otherChunk.hashCode();
    assertNotEquals(hashCode, otherHashCode);
  }

  @Test
  void shouldDescribeAChunkByItsContextAndSize() {
    final PageAudio.Chunk chunk = PageAudio.parse(PageAudio.BINDING_EVENT, this.call(FRAMES));
    final String description = chunk.toString();
    assertEquals("Chunk{contextId=7, sampleBytes=8}", description);
  }
}

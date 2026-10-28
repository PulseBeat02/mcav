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
package me.brandonli.mcav.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntUnaryOperator;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.managers.Presence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.verification.VerificationMode;

final class PcmDiscordPlayerTest {

  private static final int FRAME_BYTES = 3840;
  private static final int FRAME_MILLISECONDS = 20;
  private static final int MAXIMUM_FRAMES = 150;
  private static final int SAMPLES_PER_FRAME = FRAME_BYTES / Short.BYTES;

  private static final int PRODUCERS = 4;
  private static final int FRAMES_PER_PRODUCER = 25;
  private static final long PRODUCER_TIMEOUT_SECONDS = 10;

  private static final int MAXIMUM_NAME_LENGTH = 128;
  private static final String UNKNOWN_TITLE = "Unknown title";
  private static final String ELLIPSIS = "…";

  private static final OriginalAudioMetadata METADATA = OriginalAudioMetadata.of(
    "pcm_s16le",
    OriginalAudioMetadata.UNKNOWN,
    AudioFilter.SAMPLE_RATE,
    AudioFilter.CHANNELS,
    OriginalAudioMetadata.UNKNOWN
  );

  private JDA jda;
  private Presence presence;
  private PcmDiscordPlayer player;

  @BeforeEach
  void setUp() {
    this.jda = Mockito.mock(JDA.class);
    this.presence = Mockito.mock(Presence.class);
    Mockito.when(this.jda.getPresence()).thenReturn(this.presence);
    this.player = new PcmDiscordPlayer(this.jda);
  }

  @Test
  void shouldHoldTwentyMillisecondsOfStereoSixteenBitAudioInAFrame() {
    final int frameBytes = PcmDiscordPlayer.FRAME_BYTES;
    assertEquals(FRAME_BYTES, frameBytes);
  }

  @Test
  void shouldCreateTheDefaultPlayerAndRejectNull() {
    final DiscordPlayer created = DiscordPlayer.voice(this.jda);
    assertInstanceOf(PcmDiscordPlayer.class, created);
    assertThrows(NullPointerException.class, () -> DiscordPlayer.voice(null));
  }

  @Test
  void shouldStartEmptyAndSendPcmRatherThanOpus() {
    final boolean canProvide = this.player.canProvide();
    final ByteBuffer frame = this.player.provide20MsAudio();
    final long queued = this.player.getQueuedMilliseconds();
    final boolean opus = this.player.isOpus();
    assertFalse(canProvide);
    assertNull(frame);
    assertEquals(0L, queued);
    assertFalse(opus);
  }

  @Test
  void shouldConvertLittleEndianSamplesToBigEndianFrames() {
    final IntUnaryOperator sampleAt = IntUnaryOperator.identity();
    final ByteBuffer samples = this.createLittleEndianSamples(sampleAt);

    final boolean changed = this.player.applyFilter(samples, METADATA);
    final int inputPosition = samples.position();
    assertFalse(changed, "the filter only reads the sample, so it reports no change");
    assertEquals(0, inputPosition);

    final ByteBuffer frame = this.player.provide20MsAudio();
    assertNotNull(frame);

    final int frameSize = frame.remaining();
    assertEquals(FRAME_BYTES, frameSize);
    this.assertBigEndianSamples(frame, sampleAt);
  }

  private ByteBuffer createLittleEndianSamples(final IntUnaryOperator sampleAt) {
    final ByteBuffer samples = ByteBuffer.allocate(FRAME_BYTES);
    samples.order(ByteOrder.LITTLE_ENDIAN);
    for (int index = 0; index < SAMPLES_PER_FRAME; index++) {
      final int sample = sampleAt.applyAsInt(index);
      samples.putShort((short) sample);
    }

    return samples.flip();
  }

  private void assertBigEndianSamples(final ByteBuffer frame, final IntUnaryOperator sampleAt) {
    assertNotNull(frame);
    frame.order(ByteOrder.BIG_ENDIAN);
    for (int index = 0; index < SAMPLES_PER_FRAME; index++) {
      final short expected = (short) sampleAt.applyAsInt(index);
      final short sample = frame.getShort();
      assertEquals(expected, sample);
    }
  }

  @Test
  void shouldTreatSamplesAsLittleEndianWhateverOrderTheBufferReports() {
    final IntUnaryOperator sampleAt = index -> index * 3 - 1000;
    final ByteBuffer samples = this.createLittleEndianSamples(sampleAt);
    samples.order(ByteOrder.BIG_ENDIAN);

    this.player.applyFilter(samples, METADATA);
    final ByteOrder orderAfter = samples.order();
    final int positionAfter = samples.position();
    assertEquals(ByteOrder.BIG_ENDIAN, orderAfter, "the order of the shared buffer is not changed");
    assertEquals(0, positionAfter);

    final ByteBuffer frame = this.player.provide20MsAudio();
    this.assertBigEndianSamples(frame, sampleAt);
  }

  @Test
  void shouldStripSurroundingWhitespaceFromTheTitle() {
    this.player.setPlaying("  \t Song \n");

    final String name = this.shownActivityName();
    assertEquals("Song", name);
  }

  private String shownActivityName() {
    final VerificationMode atLeastOnce = Mockito.atLeastOnce();
    final List<Activity> activities = this.shownActivities(atLeastOnce);
    final Activity activity = activities.getLast();
    final Activity.ActivityType type = activity.getType();
    assertEquals(Activity.ActivityType.PLAYING, type);

    return activity.getName();
  }

  private List<Activity> shownActivities(final VerificationMode mode) {
    final ArgumentCaptor<Activity> captor = ArgumentCaptor.forClass(Activity.class);
    final Presence verified = Mockito.verify(this.presence, mode);
    final Activity captured = captor.capture();
    verified.setActivity(captured);
    return captor.getAllValues();
  }

  @Test
  void shouldShowUnknownTitleForBlankTitles() {
    final List<String> blanks = List.of("", "   ", "\n\t");
    for (final String blank : blanks) {
      this.player.setPlaying(blank);
      final String name = this.shownActivityName();
      assertEquals(UNKNOWN_TITLE, name);
    }
  }

  @Test
  void shouldRemoveTheControlCharactersThatDiscordTrimsFromTheTitle() {
    final List<String> invisibles = List.of("\u0000", "\u0001\u2003\u0001");
    for (final String invisible : invisibles) {
      this.player.setPlaying(invisible);
      final String name = this.shownActivityName();
      assertEquals(UNKNOWN_TITLE, name);
    }

    final String title = "a".repeat(200);
    this.player.setPlaying("\u0000" + title);

    final String name = this.shownActivityName();
    final String kept = title.substring(0, MAXIMUM_NAME_LENGTH - ELLIPSIS.length());
    assertEquals(kept + ELLIPSIS, name);
  }

  @Test
  void shouldRemoveWhitespaceAndControlCharactersAroundTheTitleInAnyOrder() {
    this.player.setPlaying("\u0001\u2003Song\u2003\u0001");

    final String name = this.shownActivityName();
    assertEquals("Song", name);
  }

  @Test
  void shouldKeepTitlesOfExactlyTheMaximumLength() {
    final String title = "a".repeat(MAXIMUM_NAME_LENGTH);
    this.player.setPlaying("  " + title + "  ");

    final String name = this.shownActivityName();
    assertEquals(title, name);

    final int maximumLength = Activity.MAX_ACTIVITY_NAME_LENGTH;
    assertEquals(MAXIMUM_NAME_LENGTH, maximumLength);
  }

  @Test
  void shouldCutTitlesLongerThanTheMaximumLengthAndEndThemWithAnEllipsis() {
    final StringBuilder builder = new StringBuilder();
    for (int index = 0; index < 300; index++) {
      final char letter = (char) ('a' + (index % 26));
      builder.append(letter);
    }

    final String title = builder.toString();
    final String kept = title.substring(0, MAXIMUM_NAME_LENGTH - ELLIPSIS.length());
    final String expected = kept + ELLIPSIS;
    this.player.setPlaying(title);

    final String name = this.shownActivityName();
    final int length = name.length();
    assertEquals(expected, name);
    assertEquals(MAXIMUM_NAME_LENGTH, length);

    final UrlParseDump dump = UrlParseDump.of(title);
    this.player.setCurrentMedia(dump);

    final String dumpName = this.shownActivityName();
    assertEquals(expected, dumpName);
  }

  @Test
  void shouldCutLongTitlesWithoutSplittingAnEmoji() {
    final String emoji = "😀";
    final String tail = "b".repeat(10);
    final String splitPrefix = "a".repeat(126);
    this.player.setPlaying(splitPrefix + emoji + tail);

    final String withoutEmoji = this.shownActivityName();
    assertEquals(splitPrefix + ELLIPSIS, withoutEmoji, "half an emoji is dropped instead of shown as a broken character");

    final String fittingPrefix = "a".repeat(125);
    this.player.setPlaying(fittingPrefix + emoji + tail);

    final String withEmoji = this.shownActivityName();
    final int length = withEmoji.length();
    assertEquals(fittingPrefix + emoji + ELLIPSIS, withEmoji, "an emoji that fits is kept whole");
    assertEquals(MAXIMUM_NAME_LENGTH, length);
  }

  @Test
  void shouldCollectPartialInputUntilAFrameIsComplete() {
    final ByteBuffer first = this.createFilledBuffer(1000, (byte) 1);
    this.player.applyFilter(first, METADATA);

    final boolean providesAfterFirst = this.player.canProvide();
    final long queuedAfterFirst = this.player.getQueuedMilliseconds();
    assertFalse(providesAfterFirst);
    assertEquals(0L, queuedAfterFirst);

    final ByteBuffer second = this.createFilledBuffer(FRAME_BYTES - 1000 + 100, (byte) 2);
    this.player.applyFilter(second, METADATA);

    final boolean providesAfterSecond = this.player.canProvide();
    final long queuedAfterSecond = this.player.getQueuedMilliseconds();
    assertTrue(providesAfterSecond);
    assertEquals(20L, queuedAfterSecond);

    final ByteBuffer firstFrame = this.player.provide20MsAudio();
    this.assertFrameHolds(firstFrame, (byte) 1, 1000, (byte) 2);

    final ByteBuffer third = this.createFilledBuffer(FRAME_BYTES - 100, (byte) 3);
    this.player.applyFilter(third, METADATA);

    final ByteBuffer secondFrame = this.player.provide20MsAudio();
    final boolean providesAfterThird = this.player.canProvide();
    this.assertFrameHolds(secondFrame, (byte) 2, 100, (byte) 3);
    assertFalse(providesAfterThird);
  }

  private ByteBuffer createFilledBuffer(final int size, final byte value) {
    final byte[] bytes = new byte[size];
    Arrays.fill(bytes, value);
    final ByteBuffer buffer = ByteBuffer.wrap(bytes);
    return buffer.order(ByteOrder.BIG_ENDIAN);
  }

  private void assertFrameHolds(final ByteBuffer frame, final byte before, final int split, final byte after) {
    assertNotNull(frame);

    final byte[] bytes = this.readContent(frame);
    final byte first = bytes[0];
    final byte lastBeforeSplit = bytes[split - 1];
    final byte firstAfterSplit = bytes[split];
    final byte last = bytes[FRAME_BYTES - 1];
    assertEquals(before, first);
    assertEquals(before, lastBeforeSplit);
    assertEquals(after, firstAfterSplit);
    assertEquals(after, last);
  }

  private byte[] readContent(final ByteBuffer frame) {
    final int remaining = frame.remaining();
    final byte[] bytes = new byte[remaining];
    frame.get(bytes);
    return bytes;
  }

  @Test
  void shouldSplitLargeInputIntoSeveralFrames() {
    final ByteBuffer samples = this.createFilledBuffer(FRAME_BYTES * 3 + 10, (byte) 7);
    this.player.applyFilter(samples, METADATA);

    final long queued = this.player.getQueuedMilliseconds();
    assertEquals(60L, queued);

    for (int index = 0; index < 3; index++) {
      final ByteBuffer frame = this.player.provide20MsAudio();
      assertNotNull(frame);
      final int size = frame.remaining();
      assertEquals(FRAME_BYTES, size);
    }

    final ByteBuffer none = this.player.provide20MsAudio();
    assertNull(none);
  }

  @Test
  void shouldDropTheOldestFramesWhenThreeSecondsAreQueued() {
    for (int index = 0; index < MAXIMUM_FRAMES + 2; index++) {
      final ByteBuffer frame = this.createFilledBuffer(FRAME_BYTES, (byte) index);
      this.player.applyFilter(frame, METADATA);
    }

    final long queued = this.player.getQueuedMilliseconds();
    assertEquals(3000L, queued);

    final ByteBuffer oldest = this.player.provide20MsAudio();
    assertNotNull(oldest);

    final byte first = oldest.get(0);
    final long afterPoll = this.player.getQueuedMilliseconds();
    assertEquals((byte) 2, first);
    assertEquals(2980L, afterPoll);
  }

  @Test
  void shouldDropQueuedFramesAndThePartialFrameOnFlush() {
    final ByteBuffer frames = this.createFilledBuffer(FRAME_BYTES * 2 + 500, (byte) 5);
    this.player.applyFilter(frames, METADATA);
    this.player.flush();

    final boolean canProvide = this.player.canProvide();
    final long queued = this.player.getQueuedMilliseconds();
    assertFalse(canProvide);
    assertEquals(0L, queued);

    final ByteBuffer next = this.createFilledBuffer(FRAME_BYTES - 500, (byte) 9);
    this.player.applyFilter(next, METADATA);

    final boolean partialWasKept = this.player.canProvide();
    assertFalse(partialWasKept);

    final ByteBuffer rest = this.createFilledBuffer(500, (byte) 9);
    this.player.applyFilter(rest, METADATA);

    final ByteBuffer frame = this.player.provide20MsAudio();
    assertNotNull(frame);

    final byte[] bytes = this.readContent(frame);
    for (final byte value : bytes) {
      assertEquals((byte) 9, value);
    }
  }

  @Test
  void shouldAcceptEmptyInput() {
    final ByteBuffer empty = ByteBuffer.allocate(0);

    final boolean changed = this.player.applyFilter(empty, METADATA);
    final boolean canProvide = this.player.canProvide();
    assertFalse(changed, "the filter only reads the sample, so it reports no change");
    assertFalse(canProvide);
  }

  @Test
  void shouldRejectNullSamplesAndMetadata() {
    final ByteBuffer samples = ByteBuffer.allocate(FRAME_BYTES);
    assertThrows(NullPointerException.class, () -> this.player.applyFilter(null, METADATA));
    assertThrows(NullPointerException.class, () -> this.player.applyFilter(samples, null));

    final boolean canProvide = this.player.canProvide();
    assertFalse(canProvide, "rejected samples are not queued");
  }

  @Test
  void shouldQueueEveryFrameFromConcurrentProducers() throws Exception {
    this.produceConcurrently();

    final long queued = this.player.getQueuedMilliseconds();
    final long expected = (long) PRODUCERS * FRAMES_PER_PRODUCER * FRAME_MILLISECONDS;
    assertEquals(expected, queued);

    final int[] counts = this.countFramesPerProducer();
    for (final int count : counts) {
      assertEquals(FRAMES_PER_PRODUCER, count);
    }
  }

  private void produceConcurrently() throws Exception {
    final CountDownLatch start = new CountDownLatch(1);
    try (final ExecutorService executor = Executors.newFixedThreadPool(PRODUCERS)) {
      final List<Future<?>> futures = new ArrayList<>();
      for (int producer = 0; producer < PRODUCERS; producer++) {
        final byte marker = (byte) producer;
        final Future<?> future = executor.submit(() -> this.produceFrames(start, marker));
        futures.add(future);
      }

      start.countDown();
      for (final Future<?> future : futures) {
        future.get(PRODUCER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
    }
  }

  private Void produceFrames(final CountDownLatch start, final byte marker) throws InterruptedException {
    start.await();
    for (int index = 0; index < FRAMES_PER_PRODUCER; index++) {
      final ByteBuffer frame = this.createFilledBuffer(FRAME_BYTES, marker);
      this.player.applyFilter(frame, METADATA);
    }

    return null;
  }

  private int[] countFramesPerProducer() {
    final int[] counts = new int[PRODUCERS];
    ByteBuffer frame = this.player.provide20MsAudio();
    while (frame != null) {
      final byte[] bytes = this.readContent(frame);
      final byte marker = bytes[0];
      for (final byte value : bytes) {
        assertEquals(marker, value, "frames of different producers must not interleave");
      }
      counts[marker]++;
      frame = this.player.provide20MsAudio();
    }

    return counts;
  }

  @Test
  void shouldShowTheTitleAsTheActivityOfTheBot() {
    this.player.setPlaying("Never Gonna Give You Up");

    final ArgumentCaptor<Activity> captor = ArgumentCaptor.forClass(Activity.class);
    final InOrder order = Mockito.inOrder(this.presence);
    final Presence statusVerified = order.verify(this.presence);
    statusVerified.setStatus(OnlineStatus.ONLINE);
    final Presence activityVerified = order.verify(this.presence);
    final Activity captured = captor.capture();
    activityVerified.setActivity(captured);

    final Activity activity = captor.getValue();
    final String name = activity.getName();
    final Activity.ActivityType type = activity.getType();
    assertEquals("Never Gonna Give You Up", name);
    assertEquals(Activity.ActivityType.PLAYING, type);

    assertThrows(NullPointerException.class, () -> this.player.setPlaying(null));
  }

  @Test
  void shouldShowTheTitleOfTheDump() {
    final UrlParseDump dump = UrlParseDump.of("Some Video");
    this.player.setCurrentMedia(dump);

    final VerificationMode once = Mockito.times(1);
    final List<Activity> activities = this.shownActivities(once);
    final Activity activity = activities.getFirst();
    final String name = activity.getName();
    assertEquals("Some Video", name);
  }

  @Test
  void shouldShowUnknownTitleForMissingOrBlankTitlesOfTheDump() {
    final Gson gson = GsonProvider.getSimple();
    final UrlParseDump missing = gson.fromJson("{}", UrlParseDump.class);
    final UrlParseDump blank = UrlParseDump.of("   ");
    this.player.setCurrentMedia(missing);
    this.player.setCurrentMedia(blank);

    final VerificationMode twice = Mockito.times(2);
    final List<Activity> activities = this.shownActivities(twice);
    for (final Activity activity : activities) {
      final String name = activity.getName();
      assertEquals(UNKNOWN_TITLE, name);
    }

    assertThrows(NullPointerException.class, () -> this.player.setCurrentMedia(null));
  }
}

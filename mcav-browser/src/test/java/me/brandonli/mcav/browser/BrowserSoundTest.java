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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import me.brandonli.mcav.browser.testing.Await;
import me.brandonli.mcav.browser.testing.HelperCoverage;
import me.brandonli.mcav.browser.testing.TestPages;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.interaction.MouseClick;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

// Plays page sound through real Chromium; the A/V sync measurement needs -Pmcav.sync_measurement=true, as load delays the events it times.
@Tag("cef")
class BrowserSoundTest {

  private static final int TARGET_MINIMUM_SOUND_DELAY_MILLISECONDS = -40;
  private static final int ACCEPTABLE_MINIMUM_SOUND_DELAY_MILLISECONDS = -90;
  private static final long POLL_PAUSE_MILLISECONDS = 50L;
  private static final long SOUND_OBSERVATION_MILLISECONDS = 2_000L;
  private static final int TARGET_MAXIMUM_SOUND_DELAY_MILLISECONDS = 80;
  private static final int ACCEPTABLE_MAXIMUM_SOUND_DELAY_MILLISECONDS = 185;
  private static final long ON_TIME_SOUND_DELAY_MILLISECONDS = 20L;

  private static final String PROCESS_SEPARATOR = " ";

  private static final String MOUSE_DOWN_EVENT = "mousedown";
  private static final String SIZE_EVENT = "size";
  private static final String FREQUENCY_UNIT = " Hz";

  private static final String PAGE_SIZE_MESSAGE = "the page reported its size";
  private static final String TWO_SECONDS_OF_SOUND_MESSAGE = "two seconds of sound";

  private static final String HALF_VOLUME_TONE_PATH = TestPages.TONE_ELEMENT_PATH + "?volume=0.5";
  private static final String MUTED_TONE_PATH = TestPages.TONE_ELEMENT_PATH + "?muted=1";

  private static final int WIDTH = 320;
  private static final int HEIGHT = 240;

  private static final int WINDOW_FRAMES = AudioFilter.SAMPLE_RATE / 200;

  private static final BrowserOptions LOCAL = BrowserOptions.builder().privateNetworks(true).build();

  private static final int FREQUENCY_TOLERANCE_HERTZ = 20;
  private static final double TONE_SHARE_THRESHOLD = 0.5;

  private static final long CLICK_DEADLINE_SECONDS = 20L;

  private final TestPages pages = TestPages.start();
  private final List<BrowserPlayer> players = new ArrayList<>();
  private final List<String> failures = new CopyOnWriteArrayList<>();

  private BrowserPlayer player() {
    return this.player(LOCAL);
  }

  private BrowserPlayer player(final BrowserOptions options) {
    final CefBrowserPlayer player = new CefBrowserPlayer(
      options,
      new CefBrowserPlayer.HelperSessionFactory(new JcefNatives(), HelperCoverage.jvmOptions())
    );
    player.setExceptionHandler((message, failure) -> this.failures.add(message + ": " + failure.getMessage()));
    this.players.add(player);

    return player;
  }

  @AfterEach
  void releasePlayers() {
    for (final BrowserPlayer player : this.players) {
      player.release();
    }

    this.pages.close();
    final int processCount = HelperProcesses.count();
    assertEquals(0, processCount);

    Await.until("every browser process of this test has ended", () -> CefBrowserIntegrationTest.countBrowserProcesses() == 0);
    // orphaned CEF processes are no longer below this JVM; only print them, as other programs may run CEF
    try (final Stream<ProcessHandle> all = ProcessHandle.allProcesses()) {
      final List<String> stray = all
        .filter(handle -> handle.info().command().orElse("").contains("jcef"))
        .map(handle -> handle.pid() + PROCESS_SEPARATOR + handle.info().command().orElse(""))
        .toList();
      if (!stray.isEmpty()) {
        System.out.println("CEF processes on this machine after the test: " + stray);
      }
    }

    assertEquals(List.of(), this.failures);
  }

  // the first sound of a helper is slow on a busy machine
  private void awaitSound(final Recording recording, final int bytes, final String what) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (recording.size() < bytes) {
      final boolean beforeDeadline = System.nanoTime() < deadline;
      assertTrue(
        beforeDeadline,
        () -> what + " arrived, only " + recording.size() + " bytes did; the page reported " + this.soundReports()
      );

      Thread.sleep(POLL_PAUSE_MILLISECONDS);
    }
  }

  private List<String> soundReports() {
    final List<String> reports = new ArrayList<>();
    for (final TestPages.PageEvent event : this.pages.getEvents()) {
      final String type = event.getType();
      if (type.equals("state") || type.equals("time") || type.equals("play") || type.equals(MOUSE_DOWN_EVENT)) {
        reports.add(type + PROCESS_SEPARATOR + event.getKey());
      }
    }

    return reports;
  }

  private void startAndClick(final BrowserPlayer player, final String path) {
    final boolean started = player.start(BrowserSource.uri(this.pages.uri(path), WIDTH, HEIGHT, 1));
    assertTrue(started);

    Await.until(PAGE_SIZE_MESSAGE, () -> this.pages.count(SIZE_EVENT) > 0);
    this.startClicking(player);
  }

  @Test
  void shouldPassTheToneOfAPageToTheAudioPipelineOnceAPlayerClickedIt() throws InterruptedException {
    final BrowserPlayer player = this.player();
    final Recording recording = Recording.attach(player);
    final boolean started = player.start(BrowserSource.uri(this.pages.uri(TestPages.TONE_PATH), WIDTH, HEIGHT, 1));
    assertTrue(started);

    Await.until(PAGE_SIZE_MESSAGE, () -> this.pages.count(SIZE_EVENT) > 0);
    Thread.sleep(SOUND_OBSERVATION_MILLISECONDS);
    final int recordedByteCount = recording.size();
    assertEquals(0, recordedByteCount, "no sound before the first click");

    this.startClicking(player);
    this.awaitSound(recording, 2 * AudioFilter.SAMPLE_RATE * AudioFilter.FRAME_SIZE, TWO_SECONDS_OF_SOUND_MESSAGE);
    final short[] left = recording.left();
    final int start = left.length - AudioFilter.SAMPLE_RATE;
    final double frequency = this.zeroCrossings(left, start) / 2.0;
    final double level = this.meanLevel(left, start);
    final int held = this.toneWindows(left, start);
    System.out.printf(
      Locale.ROOT,
      "captured %d frames, zero crossings say %.1f Hz, mean level %.0f, the tone holds in %d of %d windows%n",
      left.length,
      frequency,
      level,
      held,
      AudioFilter.SAMPLE_RATE / WINDOW_FRAMES
    );
    final boolean atTheToneFrequency = Math.abs(frequency - TestPages.TONE_HERTZ) < FREQUENCY_TOLERANCE_HERTZ;
    assertTrue(atTheToneFrequency, "the tone has " + frequency + FREQUENCY_UNIT);

    final boolean toneHolds = held > ((AudioFilter.SAMPLE_RATE / WINDOW_FRAMES) * 9) / 10;
    assertTrue(toneHolds, "the tone holds in " + held + " windows");

    final double expected = (2 * TestPages.TONE_AMPLITUDE * Short.MAX_VALUE) / Math.PI;
    final boolean atItsOwnLevel = Math.abs(level - expected) < expected * 0.1;
    assertTrue(atItsOwnLevel, "the tone plays at its own level, " + level);
  }

  // Chromium ignores clicks for a moment after a page appears
  private void startClicking(final BrowserPlayer player) {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CLICK_DEADLINE_SECONDS);
    while (this.pages.count(MOUSE_DOWN_EVENT) == 0) {
      final boolean beforeDeadline = System.nanoTime() < deadline;
      assertTrue(beforeDeadline, "the page never took a click");

      player.sendMouseEvent(MouseClick.LEFT, 10, 10);
      final long probeEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
      while (this.pages.count(MOUSE_DOWN_EVENT) == 0 && System.nanoTime() < probeEnd) {
        Thread.onSpinWait();
      }
    }
  }

  @Test
  void shouldKeepAPageThatWrapsWebAudioBeforeItsFirstContextFromTheCaptureAndPlayItOnlyAfterAClick() throws InterruptedException {
    final BrowserPlayer player = this.player();
    final Recording recording = Recording.attach(player);
    final boolean started = player.start(BrowserSource.uri(this.pages.uri(TestPages.TONE_WRAPPED_PATH), WIDTH, HEIGHT, 1));
    assertTrue(started);

    Await.until("the page reported what its wrappers saw", () -> this.pages.count("taps") > 0);
    final int wrappedNodeCount = this.pages.getEvents("taps").getFirst().getX();
    assertEquals(0, wrappedNodeCount, "no node of the capture passed the page's wrappers");

    Thread.sleep(SOUND_OBSERVATION_MILLISECONDS);
    final int recordedByteCount = recording.size();
    assertEquals(0, recordedByteCount, "no sound before the first click, neither played nor forged");

    this.startClicking(player);
    this.awaitSound(recording, 2 * AudioFilter.SAMPLE_RATE * AudioFilter.FRAME_SIZE, TWO_SECONDS_OF_SOUND_MESSAGE);
    final short[] left = recording.left();
    final double frequency = this.zeroCrossings(left, left.length - AudioFilter.SAMPLE_RATE) / 2.0;
    final boolean atTheToneFrequency = Math.abs(frequency - TestPages.TONE_HERTZ) < FREQUENCY_TOLERANCE_HERTZ;
    assertTrue(atTheToneFrequency, "after the click the page's own tone plays: " + frequency + FREQUENCY_UNIT);
  }

  @Test
  void shouldLetAPagePlayRightAwayWhenTheOptionsAllowIt() throws InterruptedException {
    final BrowserPlayer player = this.player(BrowserOptions.builder().privateNetworks(true).autoplay(true).build());
    final Recording recording = Recording.attach(player);
    final boolean started = player.start(BrowserSource.uri(this.pages.uri(TestPages.TONE_PATH), WIDTH, HEIGHT, 1));
    assertTrue(started);

    this.awaitSound(recording, AudioFilter.SAMPLE_RATE * AudioFilter.FRAME_SIZE, "a second of sound nobody clicked for");
    final int clickCount = this.pages.count(MOUSE_DOWN_EVENT);
    assertEquals(0, clickCount, "nobody clicked the page");
  }

  @Test
  void shouldPlayAnAudioElementAtItsVolumeAndKeepAMutedOneSilent() throws InterruptedException {
    final BrowserPlayer half = this.player();
    final Recording halfRecording = Recording.attach(half);
    this.startAndClick(half, HALF_VOLUME_TONE_PATH);
    this.awaitSound(halfRecording, AudioFilter.SAMPLE_RATE * AudioFilter.FRAME_SIZE, "a second of the element's sound");
    Thread.sleep(1_000L);
    final short[] left = halfRecording.left();
    final int start = left.length - AudioFilter.SAMPLE_RATE / 2;
    final double level = this.meanLevel(left, start);
    final double full = (2 * TestPages.TONE_AMPLITUDE * Short.MAX_VALUE) / Math.PI;
    System.out.printf(Locale.ROOT, "an element at half volume: mean level %.0f of %.0f at full volume%n", level, full);
    final boolean atHalfVolume = Math.abs(level - full / 2) < full * 0.1;
    assertTrue(atHalfVolume, "the element plays at half volume, once: " + level);

    half.release();
    this.pages.clearEvents();
    final BrowserPlayer muted = this.player();
    final Recording mutedRecording = Recording.attach(muted);
    this.startAndClick(muted, MUTED_TONE_PATH);
    Thread.sleep(3_000L);
    final int mutedByteCount = mutedRecording.size();
    assertEquals(0, mutedByteCount, "a muted element plays nothing");
  }

  @Test
  void shouldKeepTheSoundOfAPageUpWithItsPicture() throws InterruptedException {
    assumeTrue(
      Boolean.getBoolean("mcav.sync_measurement"),
      "This test times real events; run it with -Pmcav.sync_measurement=true on a quiet machine!"
    );
    final BrowserPlayer player = this.player();
    final List<long[]> sound = new CopyOnWriteArrayList<>();
    final List<long[]> picture = new CopyOnWriteArrayList<>();
    player.getAudioAttachableCallback().attach(AudioPipelineStep.of((samples, _) -> this.recordToneWindows(sound, samples)));
    player.getVideoAttachableCallback().attach(VideoPipelineStep.of((image, _) -> this.recordCenterBrightness(picture, image)));
    this.startAndClick(player, TestPages.AV_SYNC_PATH);
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
    while (this.onsets(picture).size() < 50) {
      final boolean beforeDeadline = System.nanoTime() < deadline;
      assertTrue(beforeDeadline, "the page turned on " + this.onsets(picture).size() + " times");

      Thread.sleep(100L);
    }

    player.release();
    final long matchWindow = TimeUnit.MILLISECONDS.toNanos(TestPages.TOGGLE_MILLISECONDS);
    final SyncJudgement judgement = SyncJudgement.of(this.onsets(picture), this.onsets(sound), matchWindow);
    final List<Double> offsets = judgement.offsets();
    final int changes = judgement.changes();
    final List<Double> sorted = new ArrayList<>(offsets);
    Collections.sort(sorted);
    final int halfCount = offsets.size() / 2;
    final double firstHalf = this.median(offsets.subList(0, halfCount));
    final double secondHalf = this.median(offsets.subList(halfCount, offsets.size()));
    final long within = judgement.within(TARGET_MINIMUM_SOUND_DELAY_MILLISECONDS, TARGET_MAXIMUM_SOUND_DELAY_MILLISECONDS);
    System.out.printf(
      Locale.ROOT,
      "A/V sync: %d changes matched of %d, sound minus picture: median %.1f ms, p5 %.1f ms, p95 %.1f ms, %d within [-40, +80] ms; first half %.1f ms, second half %.1f ms%n",
      offsets.size(),
      changes,
      this.median(sorted),
      sorted.get(sorted.size() / 20),
      sorted.get((sorted.size() * 19) / 20),
      within,
      firstHalf,
      secondHalf
    );
    // ITU-R BT.1359: sound may lead the picture by 90 ms and lag it by 185 ms
    final long acceptable = judgement.within(ACCEPTABLE_MINIMUM_SOUND_DELAY_MILLISECONDS, ACCEPTABLE_MAXIMUM_SOUND_DELAY_MILLISECONDS);
    System.out.printf(Locale.ROOT, "A/V sync: %d of %d within ITU-R BT.1359 acceptability [-90, +185] ms%n", acceptable, changes);
    final boolean mostChangesMatched = offsets.size() >= Math.ceil(changes * 0.9);
    assertTrue(mostChangesMatched, "matched " + offsets.size() + " of " + changes + " changes of the picture");

    final double middle = this.median(sorted);
    final boolean togetherInTheMiddle =
      middle >= TARGET_MINIMUM_SOUND_DELAY_MILLISECONDS && middle <= TARGET_MAXIMUM_SOUND_DELAY_MILLISECONDS;
    assertTrue(togetherInTheMiddle, "the sound and the picture arrive together in the middle: " + middle);

    final boolean mostChangesWithinTarget = within >= Math.ceil(changes * 0.95);
    assertTrue(mostChangesWithinTarget, within + " of " + changes + " changes are within the target");

    final boolean mostChangesAcceptable = acceptable >= Math.ceil(changes * 0.9);
    assertTrue(mostChangesAcceptable, acceptable + " of " + changes + " changes are in sync");

    final boolean noDrift = Math.abs(secondHalf - firstHalf) < 25;
    assertTrue(noDrift, "the sound does not drift from the picture");
  }

  private boolean recordToneWindows(final List<long[]> sound, final ByteBuffer samples) {
    final long arrival = System.nanoTime();
    final byte[] copy = new byte[samples.remaining()];
    samples.get(copy);
    final short[] left = leftChannel(copy);
    final int windows = left.length / WINDOW_FRAMES;
    for (int window = 0; window < windows; window++) {
      final boolean hasTone = this.toneShare(left, window * WINDOW_FRAMES, WINDOW_FRAMES) > TONE_SHARE_THRESHOLD;
      final long end = (long) (window + 1) * WINDOW_FRAMES;
      final long before = TimeUnit.SECONDS.toNanos(left.length - end) / AudioFilter.SAMPLE_RATE;
      sound.add(new long[] { arrival - before, hasTone ? 1 : 0 });
    }

    return true;
  }

  private boolean recordCenterBrightness(final List<long[]> picture, final ImageBuffer image) {
    final int[] pixels = image.getPixels();
    final int center = pixels[(image.getHeight() / 2) * image.getWidth() + image.getWidth() / 2];
    picture.add(new long[] { System.nanoTime(), (center & 0xFF) > 128 ? 1 : 0 });

    return false;
  }

  @Test
  void shouldCountAChangeWithoutItsSoundAsOutOfSyncInTheSyncOracle() {
    final List<Long> picture = new ArrayList<>();
    final List<Long> sound = new ArrayList<>();
    for (int change = 0; change < 50; change++) {
      final long shown = TimeUnit.MILLISECONDS.toNanos(800L * change);
      picture.add(shown);
      sound.add(shown + TimeUnit.MILLISECONDS.toNanos(change < 10 ? ON_TIME_SOUND_DELAY_MILLISECONDS : 300));
    }

    final SyncJudgement late = SyncJudgement.of(picture, sound, TimeUnit.MILLISECONDS.toNanos(400));
    final int lateChanges = late.changes();
    assertEquals(50, lateChanges);

    final boolean lateOutOfSync =
      late.within(ACCEPTABLE_MINIMUM_SOUND_DELAY_MILLISECONDS, ACCEPTABLE_MAXIMUM_SOUND_DELAY_MILLISECONDS) <
      Math.ceil(late.changes() * 0.9);
    assertTrue(lateOutOfSync, "40 of 50 changes 300 ms late are not in sync");

    final List<Long> onTime = picture
      .stream()
      .map(shown -> shown + TimeUnit.MILLISECONDS.toNanos(ON_TIME_SOUND_DELAY_MILLISECONDS))
      .toList();
    final SyncJudgement inSync = SyncJudgement.of(picture, onTime, TimeUnit.MILLISECONDS.toNanos(400));
    final long changesWithinTarget = inSync.within(TARGET_MINIMUM_SOUND_DELAY_MILLISECONDS, TARGET_MAXIMUM_SOUND_DELAY_MILLISECONDS);
    assertEquals(50, changesWithinTarget);
  }

  private int zeroCrossings(final short[] left, final int start) {
    int crossings = 0;
    for (int index = start + 1; index < left.length; index++) {
      if (left[index - 1] < 0 != left[index] < 0) {
        crossings++;
      }
    }

    return crossings;
  }

  private double meanLevel(final short[] left, final int start) {
    double sum = 0;
    for (int index = start; index < left.length; index++) {
      sum += Math.abs(left[index]);
    }

    return sum / (left.length - start);
  }

  private int toneWindows(final short[] left, final int start) {
    int windows = 0;
    for (int window = start; window + WINDOW_FRAMES <= left.length; window += WINDOW_FRAMES) {
      if (this.toneShare(left, window, WINDOW_FRAMES) > TONE_SHARE_THRESHOLD) {
        windows++;
      }
    }

    return windows;
  }

  // Goertzel: the share of the tone in the window of the left channel
  private double toneShare(final short[] left, final int start, final int length) {
    final double omega = (2 * Math.PI * TestPages.TONE_HERTZ) / AudioFilter.SAMPLE_RATE;
    final double coefficient = 2 * Math.cos(omega);
    double previous = 0;
    double beforePrevious = 0;
    double energy = 0;
    for (int index = start; index < start + length; index++) {
      final double sample = left[index];
      energy += sample * sample;
      final double current = sample + coefficient * previous - beforePrevious;
      beforePrevious = previous;
      previous = current;
    }

    if (energy < length * 100.0 * 100.0) {
      return 0;
    }

    final double power = previous * previous + beforePrevious * beforePrevious - coefficient * previous * beforePrevious;
    return Math.min(1, power / ((energy * length) / 2));
  }

  private static short[] leftChannel(final byte[] pcm) {
    final ByteBuffer samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
    final short[] left = new short[pcm.length / AudioFilter.FRAME_SIZE];
    for (int index = 0; index < left.length; index++) {
      left[index] = samples.getShort(index * AudioFilter.FRAME_SIZE);
    }

    return left;
  }

  private double median(final List<Double> values) {
    final List<Double> sorted = new ArrayList<>(values);
    Collections.sort(sorted);

    return sorted.get(sorted.size() / 2);
  }

  // a gap counts as off: the page sends no sound while it is silent
  private List<Long> onsets(final List<long[]> samples) {
    final List<long[]> ordered = new ArrayList<>(samples);
    ordered.sort((first, second) -> Long.compare(first[0], second[0]));
    final long gap = TimeUnit.MILLISECONDS.toNanos(60);
    final List<Long> onsets = new ArrayList<>();
    for (int index = 0; index < ordered.size(); index++) {
      final long[] current = ordered.get(index);
      if (current[1] != 1) {
        continue;
      }
      final boolean first = index == 0;
      final boolean followsSilence = !first && ordered.get(index - 1)[1] == 0;
      final boolean followsGap = !first && current[0] - ordered.get(index - 1)[0] > gap;
      if (first || followsSilence || followsGap) {
        onsets.add(current[0]);
      }
    }

    return onsets;
  }

  private static final class Recording {

    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();

    static Recording attach(final BrowserPlayer player) {
      final Recording recording = new Recording();
      player.getAudioAttachableCallback().attach(AudioPipelineStep.of(recording::record));

      return recording;
    }

    private boolean record(final ByteBuffer samples, final OriginalAudioMetadata metadata) {
      final int audioSampleRate = metadata.getAudioSampleRate();
      assertEquals(AudioFilter.SAMPLE_RATE, audioSampleRate);

      final int audioChannels = metadata.getAudioChannels();
      assertEquals(AudioFilter.CHANNELS, audioChannels);

      final byte[] copy = new byte[samples.remaining()];
      samples.get(copy);
      synchronized (this.pcm) {
        this.pcm.writeBytes(copy);
      }

      return true;
    }

    int size() {
      synchronized (this.pcm) {
        return this.pcm.size();
      }
    }

    short[] left() {
      final byte[] captured;
      synchronized (this.pcm) {
        captured = this.pcm.toByteArray();
      }

      return leftChannel(captured);
    }
  }

  // Pairs picture and sound changes within half the shortest fixture period; a miss counts against all rates, so few matches cannot pass.
  record SyncJudgement(int changes, List<Double> offsets) {
    static SyncJudgement of(final List<Long> pictureOnsets, final List<Long> soundOnsets, final long windowNanoseconds) {
      final List<Double> offsets = new ArrayList<>();
      for (final long shown : pictureOnsets) {
        final long nearest = nearestOffset(shown, soundOnsets);
        if (Math.abs(nearest) <= windowNanoseconds) {
          offsets.add(nearest / 1e6);
        }
      }

      return new SyncJudgement(pictureOnsets.size(), List.copyOf(offsets));
    }

    private static long nearestOffset(final long shown, final List<Long> soundOnsets) {
      long nearest = Long.MAX_VALUE;
      for (final long heard : soundOnsets) {
        if (Math.abs(heard - shown) < Math.abs(nearest)) {
          nearest = heard - shown;
        }
      }

      return nearest;
    }

    // low and high in milliseconds; a change without sound never counts
    long within(final double low, final double high) {
      return this.offsets
        .stream()
        .filter(offset -> offset >= low && offset <= high)
        .count();
    }
  }
}

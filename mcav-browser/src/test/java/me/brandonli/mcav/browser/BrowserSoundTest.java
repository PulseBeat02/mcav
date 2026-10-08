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
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.interaction.MouseClick;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Proves with real Chromium that the sound of a page reaches the audio pipeline of the player: a Web Audio tone and an
 * audio element, each once the page may play, after the first click on it, and in step with the picture. The A/V
 * measurement times real events, which a busy machine delays, so it runs on request:
 * {@code -Pmcav.syncMeasurement=true}.
 */
@Tag("cef")
class BrowserSoundTest {

  private static final int WIDTH = 320;
  private static final int HEIGHT = 240;
  private static final int WINDOW_FRAMES = AudioFilter.SAMPLE_RATE / 200;
  private static final BrowserOptions LOCAL = BrowserOptions.builder().privateNetworks(true).build();

  private final TestPages pages = TestPages.start();
  private final List<BrowserPlayer> players = new ArrayList<>();
  private final List<String> failures = new CopyOnWriteArrayList<>();

  private BrowserPlayer player() {
    return this.player(LOCAL);
  }

  private BrowserPlayer player(final BrowserOptions options) {
    final CefBrowserPlayer player = new CefBrowserPlayer(
      options,
      new CefBrowserPlayer.DefaultSessionFactory(new JcefNatives(), HelperCoverage.jvmOptions())
    );
    player.setExceptionHandler((message, error) -> this.failures.add(message + ": " + error.getMessage()));
    this.players.add(player);
    return player;
  }

  @AfterEach
  void releasePlayers() {
    for (final BrowserPlayer player : this.players) {
      player.release();
    }
    this.pages.close();
    assertEquals(0, HelperProcesses.count());
    Await.until("every browser process of this test has ended", () -> CefBrowserIntegrationTest.countBrowserProcesses() == 0);
    // a CEF process that lost its parent is no longer below this JVM; they are only told, as other programs may run CEF
    try (final Stream<ProcessHandle> all = ProcessHandle.allProcesses()) {
      final List<String> stray = all
        .filter(handle -> handle.info().command().orElse("").contains("jcef"))
        .map(handle -> handle.pid() + " " + handle.info().command().orElse(""))
        .toList();
      if (!stray.isEmpty()) {
        System.out.println("CEF processes on this machine after the test: " + stray);
      }
    }
    assertEquals(List.of(), this.failures);
  }

  /**
   * Records the sound that reaches the audio pipeline of a player.
   */
  private static final class Recording {

    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();

    static Recording attach(final BrowserPlayer player) {
      final Recording recording = new Recording();
      player.getAudioAttachableCallback().attach(
        AudioPipelineStep.of((samples, metadata) -> {
          assertEquals(AudioFilter.SAMPLE_RATE, metadata.getAudioSampleRate());
          assertEquals(AudioFilter.CHANNELS, metadata.getAudioChannels());
          final byte[] copy = new byte[samples.remaining()];
          samples.get(copy);
          synchronized (recording.pcm) {
            recording.pcm.writeBytes(copy);
          }
          return true;
        })
      );
      return recording;
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

  /**
   * Waits up to 30 s, as long as the first sound of a helper can take on a busy or slow machine, for as many bytes of
   * sound, and fails with what the page reported about its sound.
   *
   * @param recording the sound of a player
   * @param bytes     how many bytes must arrive
   * @param what      the sound that is waited for
   * @throws InterruptedException if the wait is interrupted
   */
  private void awaitSound(final Recording recording, final int bytes, final String what) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (recording.size() < bytes) {
      assertTrue(
        System.nanoTime() < deadline,
        () -> what + " arrived, only " + recording.size() + " bytes did; the page reported " + this.soundReports()
      );
      Thread.sleep(50L);
    }
  }

  private List<String> soundReports() {
    final List<String> reports = new ArrayList<>();
    for (final TestPages.PageEvent event : this.pages.getEvents()) {
      final String type = event.getType();
      if (type.equals("state") || type.equals("time") || type.equals("play") || type.equals("mousedown")) {
        reports.add(type + " " + event.getKey());
      }
    }
    return reports;
  }

  private void startAndClick(final BrowserPlayer player, final String path) {
    assertTrue(player.start(BrowserSource.uri(this.pages.uri(path), WIDTH, HEIGHT, 1)));
    Await.until("the page reported its size", () -> this.pages.count("size") > 0);
    this.startClicking(player);
  }

  @Test
  void theToneOfAPageReachesTheAudioPipelineOnceAPlayerClickedIt() throws InterruptedException {
    final BrowserPlayer player = this.player();
    final Recording recording = Recording.attach(player);
    assertTrue(player.start(BrowserSource.uri(this.pages.uri("/tone"), WIDTH, HEIGHT, 1)));
    Await.until("the page reported its size", () -> this.pages.count("size") > 0);
    Thread.sleep(2_000L);
    assertEquals(0, recording.size(), "no sound before the first click");
    this.startClicking(player);
    this.awaitSound(recording, 2 * AudioFilter.SAMPLE_RATE * AudioFilter.FRAME_SIZE, "two seconds of sound");
    final short[] left = recording.left();
    final int start = left.length - AudioFilter.SAMPLE_RATE;
    final double frequency = zeroCrossings(left, start) / 2.0;
    final double level = meanLevel(left, start);
    final int held = toneWindows(left, start);
    System.out.printf(
      Locale.ROOT,
      "captured %d frames, zero crossings say %.1f Hz, mean level %.0f, the tone holds in %d of %d windows%n",
      left.length,
      frequency,
      level,
      held,
      AudioFilter.SAMPLE_RATE / WINDOW_FRAMES
    );
    assertTrue(Math.abs(frequency - TestPages.TONE_HERTZ) < 20, "the tone has " + frequency + " Hz");
    assertTrue(held > ((AudioFilter.SAMPLE_RATE / WINDOW_FRAMES) * 9) / 10, "the tone holds in " + held + " windows");
    final double expected = (2 * TestPages.TONE_AMPLITUDE * Short.MAX_VALUE) / Math.PI;
    assertTrue(Math.abs(level - expected) < expected * 0.1, "the tone plays at its own level, " + level);
  }

  /**
   * Clicks the page until it reports a click: Chromium ignores clicks for a moment after a page appears.
   *
   * @param player the player showing a page of {@link #pages}
   */
  private void startClicking(final BrowserPlayer player) {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (this.pages.count("mousedown") == 0) {
      assertTrue(System.nanoTime() < deadline, "the page never took a click");
      player.sendMouseEvent(MouseClick.LEFT, 10, 10);
      final long probeEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
      while (this.pages.count("mousedown") == 0 && System.nanoTime() < probeEnd) {
        Thread.onSpinWait();
      }
    }
  }

  @Test
  void aPageThatWrapsWebAudioBeforeItsFirstContextCannotReachTheCaptureAndPlaysOnlyAfterAClick() throws InterruptedException {
    final BrowserPlayer player = this.player();
    final Recording recording = Recording.attach(player);
    assertTrue(player.start(BrowserSource.uri(this.pages.uri("/tone-wrapped"), WIDTH, HEIGHT, 1)));
    Await.until("the page reported what its wrappers saw", () -> this.pages.count("taps") > 0);
    assertEquals(0, this.pages.getEvents("taps").getFirst().getX(), "no node of the capture passed the page's wrappers");
    Thread.sleep(2_000L);
    assertEquals(0, recording.size(), "no sound before the first click, neither played nor forged");
    this.startClicking(player);
    this.awaitSound(recording, 2 * AudioFilter.SAMPLE_RATE * AudioFilter.FRAME_SIZE, "two seconds of sound");
    final short[] left = recording.left();
    final double frequency = zeroCrossings(left, left.length - AudioFilter.SAMPLE_RATE) / 2.0;
    assertTrue(Math.abs(frequency - TestPages.TONE_HERTZ) < 20, "after the click the page's own tone plays: " + frequency + " Hz");
  }

  @Test
  void aPageMayPlayRightAwayWhenTheOptionsLetIt() throws InterruptedException {
    final BrowserPlayer player = this.player(BrowserOptions.builder().privateNetworks(true).autoplay(true).build());
    final Recording recording = Recording.attach(player);
    assertTrue(player.start(BrowserSource.uri(this.pages.uri("/tone"), WIDTH, HEIGHT, 1)));
    this.awaitSound(recording, AudioFilter.SAMPLE_RATE * AudioFilter.FRAME_SIZE, "a second of sound nobody clicked for");
    assertEquals(0, this.pages.count("mousedown"), "nobody clicked the page");
  }

  @Test
  void anAudioElementPlaysAtItsVolumeAndAMutedOneStaysSilent() throws InterruptedException {
    final BrowserPlayer half = this.player();
    final Recording halfRecording = Recording.attach(half);
    this.startAndClick(half, "/tone-element?volume=0.5");
    this.awaitSound(halfRecording, AudioFilter.SAMPLE_RATE * AudioFilter.FRAME_SIZE, "a second of the element's sound");
    Thread.sleep(1_000L);
    final short[] left = halfRecording.left();
    final int start = left.length - AudioFilter.SAMPLE_RATE / 2;
    final double level = meanLevel(left, start);
    final double full = (2 * TestPages.TONE_AMPLITUDE * Short.MAX_VALUE) / Math.PI;
    System.out.printf(Locale.ROOT, "an element at half volume: mean level %.0f of %.0f at full volume%n", level, full);
    assertTrue(Math.abs(level - full / 2) < full * 0.1, "the element plays at half volume, once: " + level);
    half.release();
    this.pages.clearEvents();
    final BrowserPlayer muted = this.player();
    final Recording mutedRecording = Recording.attach(muted);
    this.startAndClick(muted, "/tone-element?muted=1");
    Thread.sleep(3_000L);
    assertEquals(0, mutedRecording.size(), "a muted element plays nothing");
  }

  @Test
  void theSoundOfAPageKeepsUpWithItsPicture() throws InterruptedException {
    assumeTrue(Boolean.getBoolean("mcav.syncMeasurement"), "times real events; run with -Pmcav.syncMeasurement=true on a quiet machine");
    final BrowserPlayer player = this.player();
    final List<long[]> sound = new CopyOnWriteArrayList<>();
    final List<long[]> picture = new CopyOnWriteArrayList<>();
    player.getAudioAttachableCallback().attach(
      AudioPipelineStep.of((samples, metadata) -> {
        final long arrival = System.nanoTime();
        final byte[] copy = new byte[samples.remaining()];
        samples.get(copy);
        final short[] left = leftChannel(copy);
        final int windows = left.length / WINDOW_FRAMES;
        for (int window = 0; window < windows; window++) {
          final boolean hasTone = toneShare(left, window * WINDOW_FRAMES, WINDOW_FRAMES) > 0.5;
          final long end = (long) (window + 1) * WINDOW_FRAMES;
          final long before = TimeUnit.SECONDS.toNanos(left.length - end) / AudioFilter.SAMPLE_RATE;
          sound.add(new long[] { arrival - before, hasTone ? 1 : 0 });
        }
        return true;
      })
    );
    player.getVideoAttachableCallback().attach(
      VideoPipelineStep.of((image, metadata) -> {
        final int[] pixels = image.getPixels();
        final int center = pixels[(image.getHeight() / 2) * image.getWidth() + image.getWidth() / 2];
        picture.add(new long[] { System.nanoTime(), (center & 0xFF) > 128 ? 1 : 0 });
        return false;
      })
    );
    this.startAndClick(player, "/av-sync");
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
    while (onsets(picture).size() < 50) {
      assertTrue(System.nanoTime() < deadline, "the page turned on " + onsets(picture).size() + " times");
      Thread.sleep(100L);
    }
    player.release();
    final long matchWindow = TimeUnit.MILLISECONDS.toNanos(TestPages.TOGGLE_MILLIS);
    final SyncJudgement judgement = SyncJudgement.of(onsets(picture), onsets(sound), matchWindow);
    final List<Double> offsets = judgement.offsets();
    final int changes = judgement.changes();
    final List<Double> sorted = new ArrayList<>(offsets);
    Collections.sort(sorted);
    final int halfCount = offsets.size() / 2;
    final double firstHalf = median(offsets.subList(0, halfCount));
    final double secondHalf = median(offsets.subList(halfCount, offsets.size()));
    final long within = judgement.within(-40, 80);
    System.out.printf(
      Locale.ROOT,
      "A/V sync: %d changes matched of %d, sound minus picture: median %.1f ms, p5 %.1f ms, p95 %.1f ms, %d within [-40, +80] ms; first half %.1f ms, second half %.1f ms%n",
      offsets.size(),
      changes,
      median(sorted),
      sorted.get(sorted.size() / 20),
      sorted.get((sorted.size() * 19) / 20),
      within,
      firstHalf,
      secondHalf
    );
    // ITU-R BT.1359: sound may lead the picture by 90 ms and lag it by 185 ms before viewers find it unacceptable
    final long acceptable = judgement.within(-90, 185);
    System.out.printf(Locale.ROOT, "A/V sync: %d of %d within ITU-R BT.1359 acceptability [-90, +185] ms%n", acceptable, changes);
    assertTrue(offsets.size() >= Math.ceil(changes * 0.9), "matched " + offsets.size() + " of " + changes + " changes of the picture");
    final double middle = median(sorted);
    assertTrue(middle >= -40 && middle <= 80, "the sound and the picture arrive together in the middle: " + middle);
    assertTrue(within >= Math.ceil(changes * 0.95), within + " of " + changes + " changes are within the target");
    assertTrue(acceptable >= Math.ceil(changes * 0.9), acceptable + " of " + changes + " changes are in sync");
    assertTrue(Math.abs(secondHalf - firstHalf) < 25, "the sound does not drift from the picture");
  }

  /**
   * What the changes of a picture and of its sound say about their sync. Every change of the picture is matched with
   * the nearest change of the sound within a window of half the shortest time between two changes of the fixture, and a
   * change without one counts against every rate: leaving those out before the rates were taken let a stream out of sync
   * for most of its changes pass on the few that matched (deep review DR-016).
   *
   * @param changes the changes of the picture
   * @param offsets the sound minus the picture of each matched change, in milliseconds, in the order of the picture
   */
  record SyncJudgement(int changes, List<Double> offsets) {
    static SyncJudgement of(final List<Long> pictureOnsets, final List<Long> soundOnsets, final long windowNanos) {
      final List<Double> offsets = new ArrayList<>();
      for (final long shown : pictureOnsets) {
        long nearest = Long.MAX_VALUE;
        for (final long heard : soundOnsets) {
          if (Math.abs(heard - shown) < Math.abs(nearest)) {
            nearest = heard - shown;
          }
        }
        if (Math.abs(nearest) <= windowNanos) {
          offsets.add(nearest / 1e6);
        }
      }
      return new SyncJudgement(pictureOnsets.size(), List.copyOf(offsets));
    }

    /** How many changes of the picture had their sound in the range, in milliseconds; a change without sound never. */
    long within(final double low, final double high) {
      return this.offsets
        .stream()
        .filter(offset -> offset >= low && offset <= high)
        .count();
    }
  }

  @Test
  void theSyncOracleCountsAChangeWithoutItsSoundAsOutOfSync() {
    final List<Long> picture = new ArrayList<>();
    final List<Long> sound = new ArrayList<>();
    for (int change = 0; change < 50; change++) {
      final long shown = TimeUnit.MILLISECONDS.toNanos(800L * change);
      picture.add(shown);
      sound.add(shown + TimeUnit.MILLISECONDS.toNanos(change < 10 ? 20 : 300));
    }
    final SyncJudgement late = SyncJudgement.of(picture, sound, TimeUnit.MILLISECONDS.toNanos(400));
    assertEquals(50, late.changes());
    assertTrue(late.within(-90, 185) < Math.ceil(late.changes() * 0.9), "40 of 50 changes 300 ms late are not in sync");
    final List<Long> onTime = picture
      .stream()
      .map(shown -> shown + TimeUnit.MILLISECONDS.toNanos(20))
      .toList();
    final SyncJudgement inSync = SyncJudgement.of(picture, onTime, TimeUnit.MILLISECONDS.toNanos(400));
    assertEquals(50, inSync.within(-40, 80));
  }

  private static int zeroCrossings(final short[] left, final int start) {
    int crossings = 0;
    for (int index = start + 1; index < left.length; index++) {
      if (left[index - 1] < 0 != left[index] < 0) {
        crossings++;
      }
    }
    return crossings;
  }

  private static double meanLevel(final short[] left, final int start) {
    double sum = 0;
    for (int index = start; index < left.length; index++) {
      sum += Math.abs(left[index]);
    }
    return sum / (left.length - start);
  }

  private static int toneWindows(final short[] left, final int start) {
    int windows = 0;
    for (int window = start; window + WINDOW_FRAMES <= left.length; window += WINDOW_FRAMES) {
      if (toneShare(left, window, WINDOW_FRAMES) > 0.5) {
        windows++;
      }
    }
    return windows;
  }

  /**
   * Measures how strong the tone is in a window of the left channel, compared with everything in it (Goertzel).
   *
   * @param left   the samples of the left channel
   * @param start  the first sample of the window
   * @param length the number of samples
   * @return the share of the energy at the tone, from 0 to 1, or 0 for silence
   */
  private static double toneShare(final short[] left, final int start, final int length) {
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

  private static double median(final List<Double> values) {
    final List<Double> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    return sorted.get(sorted.size() / 2);
  }

  /**
   * Finds the times at which a signal switches on: a sample that is on after one that is off, or after a gap. The page
   * sends no sound while it is silent, so the sound has gaps where the picture has dark frames.
   *
   * @param samples pairs of a time in nanoseconds and a state, 1 for on
   * @return the times the signal switched on
   */
  private static List<Long> onsets(final List<long[]> samples) {
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
}

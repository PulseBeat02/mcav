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
package me.brandonli.mcav.vm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.vm.testing.FreePorts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

// The guests are boot sectors in /guests/ beside their sources: beep.asm plays 1000 Hz on the PC speaker, and toggle.asm
// switches that tone and a red screen on and off together at irregular times.
final class VmSoundTest {

  private static final int TONE_HERTZ = 1000;

  private static final int MINIMUM_MATCHED = 10;
  private static final int WINDOW_FRAMES = AudioFilter.SAMPLE_RATE / 200;

  private static final String GUESTS_FOLDER = "/guests/";
  private static final String BEEP_IMAGE = "beep.img";
  private static final String TOGGLE_IMAGE = "toggle.img";

  private static final int WIDTH = 320;
  private static final int HEIGHT = 200;

  private static final double TONE_SHARE = 0.5;

  private static final long MATCH_WINDOW_MILLISECONDS = 165L;

  private static final int TIGHT_LEAD_MILLISECONDS = 40;
  private static final int TIGHT_LAG_MILLISECONDS = 80;

  // ITU-R BT.1359: sound may lead the picture by 90 ms and lag it by 185 ms before viewers find it unacceptable
  private static final int ACCEPTABLE_LEAD_MILLISECONDS = 90;
  private static final int ACCEPTABLE_LAG_MILLISECONDS = 185;

  private static final double IN_SYNC_SHARE = 0.9;

  @TempDir
  private Path directory;

  private void assumeQemuInstalled() {
    final ExecutableFinder finder = new ExecutableFinder();
    final String command = VmPlayer.Architecture.X86_64.getCommand();
    final Optional<Path> qemu = finder.find(command);
    assumeTrue(qemu.isPresent(), "QEMU for x86-64 is not installed!");
  }

  private Path bootSector(final String name) throws IOException {
    final Path image = this.directory.resolve(name);
    final String message = "Guest image %s is missing from the test resources!".formatted(name);
    try (final InputStream resource = Objects.requireNonNull(VmSoundTest.class.getResourceAsStream(GUESTS_FOLDER + name), message)) {
      Files.copy(resource, image);
    }

    return image;
  }

  private VmConfiguration floppy(final Path image) {
    final String drive = "file=%s,format=raw,if=floppy".formatted(image);
    return VmConfiguration.builder().memory(16).option("drive", drive).boot("a").build();
  }

  private void waitUntil(final String description, final long timeoutSeconds, final BooleanSupplier condition) {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    while (!condition.getAsBoolean()) {
      final boolean beforeTheDeadline = System.nanoTime() < deadline;
      assertTrue(beforeTheDeadline, "timed out waiting for %s".formatted(description));
      try {
        Thread.sleep(20L);
      } catch (final InterruptedException exception) {
        final Thread current = Thread.currentThread();
        current.interrupt();

        throw new AssertionError(exception);
      }
    }
  }

  private double toneShare(final short[] left, final int start, final int length) {
    final double omega = (2 * Math.PI * TONE_HERTZ) / AudioFilter.SAMPLE_RATE;
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

  private short[] leftChannel(final byte[] pcm) {
    final ByteBuffer samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
    final short[] left = new short[pcm.length / AudioFilter.FRAME_SIZE];
    for (int index = 0; index < left.length; index++) {
      left[index] = samples.getShort(index * AudioFilter.FRAME_SIZE);
    }

    return left;
  }

  @Test
  void shouldHandTheToneOfTheGuestsPcSpeakerToTheAudioPipeline() throws IOException {
    this.assumeQemuInstalled();

    final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    final VmPlayer player = VmPlayer.create();
    final AudioAttachableCallback audio = player.getAudioAttachableCallback();
    audio.attach(AudioPipelineStep.of((samples, metadata) -> this.recordSound(pcm, samples, metadata)));
    try {
      final int port = FreePorts.find();
      final VmSettings settings = VmSettings.of(port, WIDTH, HEIGHT, 10);
      final boolean started = player.start(settings, VmPlayer.Architecture.X86_64, this.floppy(this.bootSector(BEEP_IMAGE)));
      assertTrue(started);

      final int twoSeconds = 2 * AudioFilter.SAMPLE_RATE * AudioFilter.FRAME_SIZE;
      this.waitUntil("two seconds of sound", 60, () -> this.holdsAtLeast(pcm, twoSeconds));
    } finally {
      player.release();
    }

    final byte[] captured;
    synchronized (pcm) {
      captured = pcm.toByteArray();
    }

    final short[] left = this.leftChannel(captured);
    final int start = left.length - AudioFilter.SAMPLE_RATE;
    int crossings = 0;
    for (int index = start + 1; index < left.length; index++) {
      if (left[index - 1] < 0 != left[index] < 0) {
        crossings++;
      }
    }

    final double frequency = crossings / 2.0;
    System.out.printf(Locale.ROOT, "captured %d bytes, zero crossings say %.1f Hz%n", captured.length, frequency);
    final boolean nearTheTone = Math.abs(frequency - TONE_HERTZ) < 20;
    assertTrue(nearTheTone, "the tone has %s Hz".formatted(frequency));

    int toneWindows = 0;
    final int windows = AudioFilter.SAMPLE_RATE / WINDOW_FRAMES;
    for (int window = 0; window < windows; window++) {
      if (this.toneShare(left, start + window * WINDOW_FRAMES, WINDOW_FRAMES) > TONE_SHARE) {
        toneWindows++;
      }
    }

    final boolean mostlyTone = toneWindows > (windows * 9) / 10;
    assertTrue(mostlyTone, "%s of %s windows hold the tone".formatted(toneWindows, windows));
  }

  private boolean recordSound(final ByteArrayOutputStream pcm, final ByteBuffer samples, final OriginalAudioMetadata metadata) {
    final int sampleRate = metadata.getAudioSampleRate();
    assertEquals(AudioFilter.SAMPLE_RATE, sampleRate);

    final byte[] copy = new byte[samples.remaining()];
    samples.get(copy);
    synchronized (pcm) {
      pcm.writeBytes(copy);
    }

    return true;
  }

  private boolean holdsAtLeast(final ByteArrayOutputStream pcm, final int bytes) {
    synchronized (pcm) {
      return pcm.size() >= bytes;
    }
  }

  @Test
  void shouldKeepTheSoundOfTheGuestWithItsPicture() throws IOException {
    final boolean syncMeasurement = Boolean.getBoolean("mcav.sync_measurement");
    assumeTrue(syncMeasurement, "Test times real events, so run it with -Pmcav.sync_measurement=true on a quiet machine!");

    this.assumeQemuInstalled();

    final List<long[]> sound = new CopyOnWriteArrayList<>();
    final List<long[]> picture = new CopyOnWriteArrayList<>();
    final VmPlayer player = VmPlayer.create();
    final AudioAttachableCallback audio = player.getAudioAttachableCallback();
    audio.attach(
      AudioPipelineStep.of((samples, metadata) -> {
        final long arrival = System.nanoTime();
        final byte[] copy = new byte[samples.remaining()];
        samples.get(copy);
        final short[] left = this.leftChannel(copy);
        final int windows = left.length / WINDOW_FRAMES;
        for (int window = 0; window < windows; window++) {
          final boolean hasTone = this.toneShare(left, window * WINDOW_FRAMES, WINDOW_FRAMES) > TONE_SHARE;
          final long end = (long) (window + 1) * WINDOW_FRAMES;
          final long before = TimeUnit.SECONDS.toNanos(left.length - end) / AudioFilter.SAMPLE_RATE;
          sound.add(new long[] { arrival - before, hasTone ? 1 : 0 });
        }
        return true;
      })
    );
    final VideoAttachableCallback video = player.getVideoAttachableCallback();
    video.attach(
      VideoPipelineStep.of((image, metadata) -> {
        final ByteBuffer pixels = image.getData();
        final int center = pixels.position() + ((image.getHeight() / 2) * image.getWidth() + image.getWidth() / 2) * 3;
        final int blue = pixels.get(center) & 0xFF;
        final int red = pixels.get(center + 2) & 0xFF;
        picture.add(new long[] { System.nanoTime(), red > 128 && blue < 64 ? 1 : 0 });
        return false;
      })
    );
    try {
      final int port = FreePorts.find();
      final VmSettings settings = VmSettings.of(port, WIDTH, HEIGHT, 60);
      final boolean started = player.start(settings, VmPlayer.Architecture.X86_64, this.floppy(this.bootSector(TOGGLE_IMAGE)));
      assertTrue(started);

      this.waitUntil("20 seconds of sound", 90, () -> sound.size() >= 20 * 200);
    } finally {
      player.release();
    }

    final long matchWindow = TimeUnit.MILLISECONDS.toNanos(MATCH_WINDOW_MILLISECONDS);
    final SyncJudgement judgement = SyncJudgement.of(this.onsets(picture), this.onsets(sound), matchWindow);
    final List<Double> offsets = judgement.offsets();
    final int changes = judgement.changes();
    final List<Double> sorted = new ArrayList<>(offsets);
    Collections.sort(sorted);
    final int half = offsets.size() / 2;
    final double firstHalf = this.median(offsets.subList(0, half));
    final double secondHalf = this.median(offsets.subList(half, offsets.size()));
    final long within = judgement.within(-TIGHT_LEAD_MILLISECONDS, TIGHT_LAG_MILLISECONDS);
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
    final long acceptable = judgement.within(-ACCEPTABLE_LEAD_MILLISECONDS, ACCEPTABLE_LAG_MILLISECONDS);
    System.out.printf(Locale.ROOT, "A/V sync: %d of %d within ITU-R BT.1359 acceptability [-90, +185] ms%n", acceptable, changes);
    this.assertInSync(judgement);
  }

  private void assertInSync(final SyncJudgement judgement) {
    final List<Double> offsets = judgement.offsets();
    final int changes = judgement.changes();
    final boolean enoughMatched = offsets.size() >= MINIMUM_MATCHED;
    final boolean nearlyAllMatched = offsets.size() >= Math.ceil(changes * IN_SYNC_SHARE);
    assertTrue(enoughMatched, "matched %s changes of the picture, fewer than %s".formatted(offsets.size(), MINIMUM_MATCHED));
    assertTrue(nearlyAllMatched, "matched %s of %s changes of the picture".formatted(offsets.size(), changes));

    final List<Double> sorted = new ArrayList<>(offsets);
    Collections.sort(sorted);
    final boolean togetherInTheMiddle = Math.abs(this.median(sorted)) <= 40;
    assertTrue(togetherInTheMiddle, "the sound and the picture arrive together in the middle");

    final long acceptable = judgement.within(-ACCEPTABLE_LEAD_MILLISECONDS, ACCEPTABLE_LAG_MILLISECONDS);
    final boolean nearlyAllInSync = acceptable >= Math.ceil(changes * IN_SYNC_SHARE);
    assertTrue(nearlyAllInSync, "%s of %s changes are in sync".formatted(acceptable, changes));

    final int half = offsets.size() / 2;
    final double drift = this.median(offsets.subList(half, offsets.size())) - this.median(offsets.subList(0, half));
    final boolean steady = Math.abs(drift) < 25;
    assertTrue(steady, "the sound does not drift from the picture");
  }

  @Test
  void shouldCountAChangeWithoutItsSoundAsOutOfSync() {
    final List<Long> picture = new ArrayList<>();
    final List<Long> sound = new ArrayList<>();
    for (int change = 0; change < 50; change++) {
      final long shown = TimeUnit.MILLISECONDS.toNanos(800L * change);
      picture.add(shown);
      sound.add(shown + TimeUnit.MILLISECONDS.toNanos(change < 10 ? 20 : 300));
    }

    final SyncJudgement late = SyncJudgement.of(picture, sound, TimeUnit.MILLISECONDS.toNanos(MATCH_WINDOW_MILLISECONDS));
    final int lateChanges = late.changes();
    final boolean tooFewInSync =
      late.within(-ACCEPTABLE_LEAD_MILLISECONDS, ACCEPTABLE_LAG_MILLISECONDS) < Math.ceil(late.changes() * IN_SYNC_SHARE);
    assertEquals(50, lateChanges);
    assertTrue(tooFewInSync, "40 of 50 changes 300 ms late are not in sync");

    final List<Long> onTime = picture
      .stream()
      .map(shown -> shown + TimeUnit.MILLISECONDS.toNanos(20))
      .toList();
    final SyncJudgement inSync = SyncJudgement.of(picture, onTime, TimeUnit.MILLISECONDS.toNanos(MATCH_WINDOW_MILLISECONDS));
    final long onTimeWithin = inSync.within(-TIGHT_LEAD_MILLISECONDS, TIGHT_LAG_MILLISECONDS);
    assertEquals(50, onTimeWithin);
    this.assertInSync(inSync);
    assertThrows(AssertionFailedError.class, () -> this.assertInSync(late));

    final List<Long> twice = List.of(TimeUnit.SECONDS.toNanos(5), TimeUnit.SECONDS.toNanos(15));
    final List<Long> twiceHeard = twice
      .stream()
      .map(shown -> shown + TimeUnit.MILLISECONDS.toNanos(20))
      .toList();
    final SyncJudgement undersampled = SyncJudgement.of(twice, twiceHeard, TimeUnit.MILLISECONDS.toNanos(MATCH_WINDOW_MILLISECONDS));
    final long undersampledWithin = undersampled.within(-ACCEPTABLE_LEAD_MILLISECONDS, ACCEPTABLE_LAG_MILLISECONDS);
    assertEquals(2, undersampledWithin);
    assertThrows(AssertionFailedError.class, () -> this.assertInSync(undersampled));
  }

  private double median(final List<Double> values) {
    final List<Double> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    return sorted.get(sorted.size() / 2);
  }

  // QEMU sends nothing while the speaker is off, so the sound has gaps where the picture has dark frames
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

  // a change of the picture without sound counts against every rate: leaving those out let a stream out of sync for
  // most of its changes pass on the few that matched
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

    long within(final double low, final double high) {
      return this.offsets
        .stream()
        .filter(offset -> offset >= low && offset <= high)
        .count();
    }
  }
}
